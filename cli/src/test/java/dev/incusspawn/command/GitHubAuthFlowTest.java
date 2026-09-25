package dev.incusspawn.command;

import dev.incusspawn.config.NamespaceAccounts;
import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.incusspawn.command.IsolatedHome.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx init}'s GitHub step, end to end: account menu, PAT prompt, verification, the
 * {@code gh} fallback, and what lands in config.yaml.
 *
 * <p>What matters here is where a token is written and whose identity it is, so every
 * assertion reads the file back from disk rather than trusting the in-memory config. Every
 * write produces the accounts layout (#773): a first credential becomes {@code accounts.default}. The
 * network, the host's {@code gh} login and the browser are the overridable seams on
 * {@link InitCommand}.
 */
@ExtendWith(IsolatedHome.class)
class GitHubAuthFlowTest {

    private static final String AGENT_PAT = "github_pat_agent_0000000000";
    private static final String PERSONAL_GH_TOKEN = "gho_personal_0000000000";

    private static final String ONE_FLAT_CREDENTIAL = """
            github:
              token: "ghp_flat"
              email: "me@example.com"
            """;

    /** Answers verification from a table instead of api.github.com. */
    static class FakeInit extends InitCommand {
        final Map<String, GitHubVerifyResult> valid = new HashMap<>();
        final List<String> verified = new ArrayList<>();
        final List<String> opened = new ArrayList<>();
        boolean ghLoggedIn;
        String ghToken;
        int ghTokenReads;

        FakeInit accept(String token, String login, String email) {
            valid.put(token, new GitHubVerifyResult(login, email));
            return this;
        }

        FakeInit ghLogin(String token) {
            ghLoggedIn = true;
            ghToken = token;
            return this;
        }

        @Override
        GitHubVerifyResult verifyGitHubToken(String token, Prompts prompts) {
            verified.add(token);
            return valid.get(token);
        }

        @Override
        boolean ghCliLoggedIn() {
            return ghLoggedIn;
        }

        @Override
        String readGhAuthToken() {
            ghTokenReads++;
            return ghToken;
        }

        @Override
        boolean openUrl(String url) {
            opened.add(url);
            return true;
        }
    }

    private static void run(FakeInit init, SpawnConfig config, ScriptedPrompts prompts) {
        init.setupGitHubAuth(config, prompts);
        prompts.assertFullyConsumed();
    }

    // ── first-time setup ─────────────────────────────────────────────────────────

    @Test
    void aVerifiedPatIsSavedWithItsEmail() {
        var init = new FakeInit().accept(AGENT_PAT, "agent-bot", "bot@example.com");
        run(init, new SpawnConfig(), new ScriptedPrompts().line("n").secret(AGENT_PAT));

        assertEquals(AGENT_PAT, saved("github.accounts.default.token"));
        assertEquals("bot@example.com", saved("github.accounts.default.email"));
        assertTrue(init.opened.isEmpty(), "declining the browser must not open it");
    }

    /**
     * readPassword() echoes nothing, so the flow confirms a paste arrived -- by length and
     * masked form only. The token itself must never reach the terminal.
     */
    @Test
    void anEnteredTokenIsAcknowledgedWithoutBeingShown() {
        var init = new FakeInit().accept(AGENT_PAT, "agent-bot", "bot@example.com");
        var out = captureStdout(() -> run(init, new SpawnConfig(),
                new ScriptedPrompts().line("n").secret("  " + AGENT_PAT + " ")));

        assertTrue(out.contains("Received 27 characters (github_pat_...0000)"), out);
        assertFalse(out.contains(AGENT_PAT), "the token was printed:\n" + out);
    }

    @Test
    void aSkippedTokenIsNotAcknowledged() {
        var out = captureStdout(() -> run(new FakeInit(), new SpawnConfig(),
                new ScriptedPrompts().line("n").secret("")));
        assertFalse(out.contains("Received"), out);
    }

    private static String captureStdout(Runnable body) {
        var original = System.out;
        var buffer = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void theTokenPageOpensByDefault() {
        var init = new FakeInit();
        run(init, new SpawnConfig(), new ScriptedPrompts().line("").secret(""));

        assertEquals(List.of("https://github.com/settings/personal-access-tokens/new"), init.opened);
        assertFalse(Files.exists(configFile()));
    }

    /** CI runs {@code isx init </dev/null}: every prompt hits EOF and nothing may be written. */
    @Test
    void closedStdinWritesNothing() {
        var init = new FakeInit().ghLogin(PERSONAL_GH_TOKEN);
        init.setupGitHubAuth(new SpawnConfig(), new ScriptedPrompts());

        assertFalse(Files.exists(configFile()));
        assertEquals(0, init.ghTokenReads, "EOF must never count as agreeing to reuse the personal login");
    }

    @Test
    void rejectedTokenAndDeclinedRetryWritesNothing() {
        var init = new FakeInit();
        run(init, new SpawnConfig(), new ScriptedPrompts().line("n").secret("ghp_bad").line("n"));

        assertEquals(List.of("ghp_bad"), init.verified);
        assertFalse(Files.exists(configFile()));
    }

    @Test
    void retryingAfterARejectedTokenSavesTheNextOne() {
        var init = new FakeInit().accept(AGENT_PAT, "agent-bot", "bot@example.com");
        run(init, new SpawnConfig(),
                new ScriptedPrompts().line("n").secret("ghp_bad").line("").secret(AGENT_PAT));

        assertEquals(AGENT_PAT, saved("github.accounts.default.token"));
    }

    // ── the personal 'gh' login fallback ─────────────────────────────────────────

    /**
     * Reusing the host's 'gh' login makes the agent act as the operator. It is offered only
     * when the PAT is skipped, and pressing Enter must decline it.
     */
    @Test
    void skippingThePatDoesNotReuseThePersonalLoginByDefault() {
        var init = new FakeInit().ghLogin(PERSONAL_GH_TOKEN)
                .accept(PERSONAL_GH_TOKEN, "operator", "me@example.com");
        run(init, new SpawnConfig(), new ScriptedPrompts().line("n").secret("").line(""));

        assertEquals(0, init.ghTokenReads, "the personal token must not even be read without a yes");
        assertFalse(Files.exists(configFile()));
    }

    @Test
    void thePersonalLoginIsReusedOnlyWhenExplicitlyAccepted() {
        var init = new FakeInit().ghLogin(PERSONAL_GH_TOKEN)
                .accept(PERSONAL_GH_TOKEN, "operator", "me@example.com");
        run(init, new SpawnConfig(), new ScriptedPrompts().line("n").secret("").line("y"));

        assertEquals(PERSONAL_GH_TOKEN, saved("github.accounts.default.token"));
    }

    @Test
    void withoutAGhLoginTheReuseQuestionIsNeverAsked() {
        var init = new FakeInit();
        // assertFullyConsumed fails if the flow asks anything after the skipped PAT.
        run(init, new SpawnConfig(), new ScriptedPrompts().line("n").secret(""));
        assertFalse(Files.exists(configFile()));
    }

    /** The fallback promises to "continue with manual setup" when it fails, so it must. */
    @Test
    void aPersonalTokenThatFailsVerificationFallsBackToThePatPrompt() {
        var init = new FakeInit().ghLogin(PERSONAL_GH_TOKEN)
                .accept(AGENT_PAT, "agent-bot", "bot@example.com");
        run(init, new SpawnConfig(),
                new ScriptedPrompts().line("n").secret("").line("y").secret(AGENT_PAT));

        assertEquals(AGENT_PAT, saved("github.accounts.default.token"));
    }

    // ── tokens without an email ──────────────────────────────────────────────────

    @Test
    void aTokenWithoutEmailIsSavedWithoutOneRatherThanAnEmptyString() throws Exception {
        var init = new FakeInit().accept(AGENT_PAT, "agent-bot", null);
        run(init, new SpawnConfig(), new ScriptedPrompts().line("n").secret(AGENT_PAT).secret(""));

        assertEquals(AGENT_PAT, saved("github.accounts.default.token"));
        assertFalse(Files.readString(configFile()).contains("email"),
                "an absent email must stay absent, not become email: \"\"");
    }

    @Test
    void aRejectedReplacementKeepsTheOriginalToken() {
        var init = new FakeInit().accept(AGENT_PAT, "agent-bot", null);
        run(init, new SpawnConfig(),
                new ScriptedPrompts().line("n").secret(AGENT_PAT).secret("ghp_bad"));

        assertEquals(AGENT_PAT, saved("github.accounts.default.token"));
    }

    // ── re-runs: the account menu around the prompt ──────────────────────────────

    @Test
    void enterOnReRunLeavesTheFileUntouched() throws Exception {
        var config = seed(ONE_FLAT_CREDENTIAL);
        run(new FakeInit(), config, ScriptedPrompts.lines(""));

        assertEquals(ONE_FLAT_CREDENTIAL, Files.readString(configFile()));
    }

    /** Replacing a flat credential moves it into the accounts layout, leaving nothing flat behind. */
    @Test
    void replacingTheSingleCredentialLeavesItTheOnlyAccount() throws Exception {
        var config = seed(ONE_FLAT_CREDENTIAL);
        var init = new FakeInit().accept(AGENT_PAT, "agent-bot", "bot@example.com");
        run(init, config, new ScriptedPrompts().line("r", "n").secret(AGENT_PAT));

        assertEquals(AGENT_PAT, saved("github.accounts.default.token"));
        assertEquals("bot@example.com", saved("github.accounts.default.email"));
        assertEquals(List.of("default"), NamespaceAccounts.names(SpawnConfig.load(), "github"));
        assertEquals("", saved("github.token"));
        assertEquals("", saved("github.email"), "the old flat email must not linger beside the new account");
    }

    @Test
    void addingASecondAccountKeepsTheFirst() throws Exception {
        var config = seed(ONE_FLAT_CREDENTIAL);
        var init = new FakeInit().accept(AGENT_PAT, "agent-bot", "bot@example.com");
        run(init, config, new ScriptedPrompts().line("a", "work", "n").secret(AGENT_PAT));

        assertEquals("ghp_flat", saved("github.accounts.default.token"));
        assertEquals("me@example.com", saved("github.accounts.default.email"));
        assertEquals(AGENT_PAT, saved("github.accounts.work.token"));
        assertEquals("default", saved("github.default"),
                "adding an account must not re-point the default at it");
        assertEquals("", saved("github.token"), "the flat token moves, it is not duplicated");
    }

    /**
     * #740 end to end: naming a second account and then abandoning at the PAT prompt must
     * leave the file byte-for-byte as it was -- not migrated into a shape an older isx reads as
     * having no GitHub credentials.
     */
    @Test
    void abandoningASecondAccountAtThePatPromptChangesNothing() throws Exception {
        var config = seed(ONE_FLAT_CREDENTIAL);
        run(new FakeInit(), config, new ScriptedPrompts().line("a", "work", "n").secret(""));

        assertEquals(ONE_FLAT_CREDENTIAL, Files.readString(configFile()));
    }

    @Test
    void abandoningASecondAccountAfterARejectedTokenChangesNothing() throws Exception {
        var config = seed(ONE_FLAT_CREDENTIAL);
        run(new FakeInit(), config,
                new ScriptedPrompts().line("a", "work", "n").secret("ghp_bad").line("n"));

        assertEquals(ONE_FLAT_CREDENTIAL, Files.readString(configFile()));
    }

    /** A re-minted PAT belongs to the account the user picked, not back in the flat field. */
    @Test
    void aReMintedTokenLandsInTheChosenAccount() throws Exception {
        var config = seed(ONE_FLAT_CREDENTIAL);
        var init = new FakeInit()
                .accept(AGENT_PAT, "agent-bot", null)
                .accept("github_pat_with_email_000", "agent-bot", "bot@example.com");
        run(init, config, new ScriptedPrompts().line("a", "work", "n")
                .secret(AGENT_PAT).secret("github_pat_with_email_000"));

        assertEquals("github_pat_with_email_000", saved("github.accounts.work.token"));
        assertEquals("bot@example.com", saved("github.accounts.work.email"));
        assertEquals("ghp_flat", saved("github.accounts.default.token"));
    }

    @Test
    void thePersonalLoginReusedForANamedAccountLandsInThatAccount() throws Exception {
        var config = seed(ONE_FLAT_CREDENTIAL);
        var init = new FakeInit().ghLogin(PERSONAL_GH_TOKEN)
                .accept(PERSONAL_GH_TOKEN, "operator", "me@example.com");
        run(init, config, new ScriptedPrompts().line("a", "mine", "n").secret("").line("y"));

        assertEquals(PERSONAL_GH_TOKEN, saved("github.accounts.mine.token"));
        assertEquals("ghp_flat", saved("github.accounts.default.token"));
        assertEquals("default", saved("github.default"));
    }

    // ── choosing the commit email ────────────────────────────────────────────────

    private static final String SEVERAL_EMAILS = """
            [{"email":"me@work.example","verified":true,"primary":true},
             {"email":"me@home.example","verified":true,"primary":false},
             {"email":"unverified@example.com","verified":false,"primary":false},
             {"email":"1234+me@users.noreply.github.com","verified":true,"primary":false}]""";

    private static String chooseEmail(String json, String... input) {
        var prompts = ScriptedPrompts.lines(input);
        var email = InitCommand.chooseEmail(InitCommand.parseGitHubEmails(json), prompts);
        prompts.assertFullyConsumed();
        return email;
    }

    /** The default must be the private address, so pressing Enter never publishes a real one. */
    @Test
    void enterPicksTheNoreplyAddress() {
        assertEquals("1234+me@users.noreply.github.com", chooseEmail(SEVERAL_EMAILS, ""));
    }

    @Test
    void closedStdinPicksTheNoreplyAddress() {
        var email = InitCommand.chooseEmail(InitCommand.parseGitHubEmails(SEVERAL_EMAILS), new ScriptedPrompts());
        assertEquals("1234+me@users.noreply.github.com", email);
    }

    @Test
    void aNumberPicksThatAddress() {
        // Listed as: 1. noreply, 2. work (primary), 3. home -- unverified ones never appear.
        assertEquals("me@work.example", chooseEmail(SEVERAL_EMAILS, "2"));
        assertEquals("me@home.example", chooseEmail(SEVERAL_EMAILS, " 3 "));
    }

    @Test
    void anOutOfRangeOrUnreadableChoiceFallsBackToTheFirst() {
        assertEquals("1234+me@users.noreply.github.com", chooseEmail(SEVERAL_EMAILS, "4"));
        assertEquals("1234+me@users.noreply.github.com", chooseEmail(SEVERAL_EMAILS, "0"));
        assertEquals("1234+me@users.noreply.github.com", chooseEmail(SEVERAL_EMAILS, "me@home.example"));
    }

    @Test
    void aSingleVerifiedAddressIsTakenWithoutAsking() {
        // assertFullyConsumed fails if a question was asked.
        assertEquals("only@example.com", chooseEmail("""
                [{"email":"only@example.com","verified":true,"primary":true},
                 {"email":"other@example.com","verified":false,"primary":false}]"""));
    }

    @Test
    void withoutANoreplyAddressEnterTakesTheFirstVerifiedOne() {
        assertEquals("me@work.example", chooseEmail("""
                [{"email":"me@work.example","verified":true,"primary":true},
                 {"email":"me@home.example","verified":true,"primary":false}]""", ""));
    }
}
