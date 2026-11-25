package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.config.NamespaceAccounts;
import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * {@link CredentialSetup}.
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
    static class FakeInit extends CredentialSetup {
        final Map<String, GitHubVerifyResult> valid = new HashMap<>();
        final List<String> verified = new ArrayList<>();
        final List<String> opened = new ArrayList<>();
        boolean ghLoggedIn;
        String ghToken;
        int ghTokenReads;
        /** Whether this computer has a clipboard to copy to, never read from the machine a test runs on. */
        boolean clipboard;
        /** Whether a copy succeeds. */
        boolean copies = true;
        final List<String> copied = new ArrayList<>();

        @Override
        List<String> clipboardCommand() {
            return clipboard ? List.of("a-clipboard-command") : null;
        }

        @Override
        boolean copyToClipboard(String text) {
            if (!clipboard || !copies) return false;
            copied.add(text);
            return true;
        }

        FakeInit accept(String token, String login, String email) {
            valid.put(token, new GitHubVerifyResult(login, email));
            return this;
        }

        /** The agent's dedicated PAT, which verifies with an email. */
        FakeInit acceptAgentPat() {
            return accept(AGENT_PAT, "agent-bot", "bot@example.com");
        }

        /** An authenticated host 'gh' whose token may or may not verify. */
        FakeInit ghLogin(String token) {
            ghLoggedIn = true;
            ghToken = token;
            return this;
        }

        /** The operator's own 'gh' login, whose token verifies as them. */
        FakeInit personalGhLogin() {
            return ghLogin(PERSONAL_GH_TOKEN).accept(PERSONAL_GH_TOKEN, "operator", "me@example.com");
        }

        @Override
        GitHubVerifyResult verifyGitHubToken(String token, Prompts prompts, java.util.function.Predicate<String> keepLogin) {
            verified.add(token);
            // As the real check: the account is offered to keepLogin before the email is chosen
            var result = valid.get(token);
            return result != null && result.login() != null && !keepLogin.test(result.login())
                    ? GitHubVerifyResult.DECLINED : result;
        }

        @Override
        boolean ghCliLoggedIn() {
            return ghLoggedIn;
        }

        @Override
        String ghCliLogin() {
            return ghLoggedIn ? "operator" : null;
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

    /** Enter at the account menu that follows every saved token: no more accounts to add. */
    private static final String FINISHED = "";

    /**
     * A script for the token prompt, which follows the guide straight away: isx never opens the
     * page, since the browser is signed in as the person, not the agent (#2528).
     */
    private static ScriptedPrompts declineBrowser() {
        return new ScriptedPrompts();
    }

    /** First-time setup: no config yet. */
    private static void run(FakeInit init, ScriptedPrompts prompts) {
        run(init, new SpawnConfig(), prompts);
    }

    private static void run(FakeInit init, SpawnConfig config, ScriptedPrompts prompts) {
        init.setupGitHubAuth(config, prompts);
        prompts.assertFullyConsumed();
    }

    // ── first-time setup ─────────────────────────────────────────────────────────

    @Test
    void aVerifiedPatIsSavedWithItsEmail() {
        var init = new FakeInit().acceptAgentPat();
        run(init, declineBrowser().secret(AGENT_PAT).line(FINISHED));

        assertEquals(AGENT_PAT, saved("github.accounts.default.token"));
        assertEquals("bot@example.com", saved("github.accounts.default.email"));
        assertTrue(init.opened.isEmpty(), "the token page is printed, never opened");
    }

    /**
     * readPassword() echoes nothing, and the token itself must never reach the terminal: its
     * check and save say it arrived (#2528).
     */
    @Test
    void anEnteredTokenIsNeverShown() {
        var init = new FakeInit().acceptAgentPat();
        var out = reported(() -> run(init, declineBrowser().secret("  " + AGENT_PAT + " ").line(FINISHED)));

        assertEquals(AGENT_PAT, saved("github.accounts.default.token"));
        assertTrue(out.contains("Token verified and saved"), out);
        assertFalse(out.contains(AGENT_PAT), "the token was printed:\n" + out);
    }

    /**
     * The token is asked for through the prompts that read it (#1703): the question and the
     * answer are the conversation a test sees, and the question is a secret's, never echoed.
     */
    @Test
    void theTokenIsAskedForThroughThePromptsThatReadIt() {
        var prompts = declineBrowser().secret(AGENT_PAT).line(FINISHED);
        run(new FakeInit().acceptAgentPat(), prompts);

        assertTrue(prompts.questions().contains("Paste the GitHub token, or Enter to skip: "), prompts.questions()::toString);
        assertTrue(prompts.conversation().stream().noneMatch(line -> line.contains(AGENT_PAT)), "the token was shown");
    }

    @Test
    void aSkippedTokenIsNotAcknowledged() {
        var out = reported(() -> run(new FakeInit(), declineBrowser().secret("")));
        assertFalse(out.contains("Received"), out);
    }

    /**
     * A pasted token is not echoed, so a receipt says it arrived (#2565): its length and masked
     * ends, with no tick, since nothing is checked yet, and before the line that says it is.
     */
    @Test
    void aPastedTokenGetsAReceiptBeforeItsOutcome() {
        var init = new FakeInit().acceptAgentPat();
        var lines = RecordingOutput.during(() -> run(init, declineBrowser().secret(AGENT_PAT).line(FINISHED))).lines();

        var receipt = CredentialSetup.describeReceivedSecret(AGENT_PAT);
        var at = java.util.stream.IntStream.range(0, lines.size())
                .filter(i -> lines.get(i).contains(receipt)).findFirst().orElse(-1);
        assertTrue(at >= 0, () -> "no receipt '" + receipt + "' in:\n" + String.join("\n", lines));
        var outcome = java.util.stream.IntStream.range(0, lines.size())
                .filter(i -> lines.get(i).contains("Token verified and saved")).findFirst().orElseThrow();
        assertTrue(at < outcome, () -> String.join("\n", lines));
        assertFalse(lines.get(at).contains("\u2713"), "a receipt is no tick: " + lines.get(at));
        assertTrue(lines.stream().noneMatch(l -> l.contains(AGENT_PAT)), "the token was printed");
    }

    /** The letters the token prompt takes are answers, not tokens: no receipt for them. */
    @Test
    void theLettersOfTheTokenPromptGetNoReceipt() {
        var init = new FakeInit();
        init.clipboard = true;
        var out = reported(() -> run(init, new ScriptedPrompts().secret("c").secret("u").secret("")));
        assertFalse(out.contains("Received"), out);
    }

    /** What the flow said and asked, in order, a line each: steps, warnings and questions alike. */
    private static String reported(Runnable body) {
        return String.join("\n", RecordingOutput.during(body::run).lines());
    }

    /**
     * The browser is signed in as the person, so isx neither opens the token page nor prints a
     * link to it, which a click would open there too: the guide names the page in the step that
     * uses it, and the address comes on request (#2528).
     */
    @Test
    void theTokenPageIsNeitherOpenedNorLinked() {
        var init = new FakeInit();
        var out = reported(() -> run(init, new ScriptedPrompts().secret("")));

        assertEquals(List.of(), init.opened);
        assertTrue(out.contains("In that window, open GitHub's page for a new token."), out);
        assertFalse(out.contains("personal-access-tokens"), "the address is shown only on request:\n" + out);
        assertNothingSaved();
    }

    /** The page is named before anything refers to it, and the step says how to get its address. */
    @Test
    void theGuideSaysHowToGetTheAddressWhereThePageIsUsed() {
        var withClipboard = new FakeInit();
        withClipboard.clipboard = true;
        var out = reported(() -> run(withClipboard, new ScriptedPrompts().secret("")));
        assertTrue(out.contains("Type c below to copy the address, or u to show it."), out);

        var without = reported(() -> run(new FakeInit(), new ScriptedPrompts().secret("")));
        assertTrue(without.contains("Type u below to show the address."), without);
        assertFalse(without.contains("Type c"), "c is offered only where a copy can work:\n" + without);
    }

    /** {@code u} prints the page's whole address, to copy by hand. */
    @Test
    void uShowsTheWholeAddress() {
        var out = reported(() -> run(new FakeInit(), new ScriptedPrompts().secret("u").secret("")));

        assertTrue(out.contains(CredentialSetup.GH_PAT_NEW_URL), out);
        assertNothingSaved();
    }

    /** {@code c} copies the whole address, to paste in the window signed in as the agent, and asks again. */
    @Test
    void cCopiesTheWholeAddress() {
        var init = new FakeInit();
        init.clipboard = true;
        var out = reported(() -> run(init, new ScriptedPrompts().secret("c").secret("")));

        assertEquals(List.of(CredentialSetup.GH_PAT_NEW_URL), init.copied);
        assertTrue(out.contains("Copied the address. Paste it in that browser window."), out);
        assertFalse(out.contains(CredentialSetup.GH_PAT_NEW_URL), "a copied address is not printed as well:\n" + out);
        assertEquals(List.of(), init.verified, "c is never checked as a token");
        assertNothingSaved();
    }

    /**
     * Where no clipboard command exists the guide does not offer c, and a c typed anyway shows
     * the address as u does: it is never received and checked as a one-letter token.
     */
    @Test
    void cWithNoClipboardShowsTheAddressAndIsNeverAToken() {
        var init = new FakeInit();
        var out = reported(() -> run(init, new ScriptedPrompts().secret("c").secret("")));

        assertTrue(out.contains(CredentialSetup.GH_PAT_NEW_URL), out);
        assertFalse(out.contains("Received"), out);
        assertEquals(List.of(), init.verified, "c is never checked as a token");
        assertEquals(List.of(), init.copied);
        assertNothingSaved();
    }

    /** A copy that fails still gives the person the address. */
    @Test
    void aCopyThatFailsShowsTheAddress() {
        var init = new FakeInit();
        init.clipboard = true;
        init.copies = false;
        var out = reported(() -> run(init, new ScriptedPrompts().secret("c").secret("")));

        assertTrue(out.contains("isx could not copy the address. Here it is:"), out);
        assertTrue(out.contains(CredentialSetup.GH_PAT_NEW_URL), out);
        assertNothingSaved();
    }

    /** Over SSH a command here reaches this computer's clipboard, not the one the person pastes from. */
    @Test
    void noClipboardIsOfferedOverSsh() {
        var init = new CredentialSetup() {
            @Override String env(String name) { return name.equals("SSH_CONNECTION") ? "10.0.0.1 50000 10.0.0.2 22" : "x"; }
            @Override boolean hostHasCommand(String command) { return true; }
        };
        assertNull(init.clipboardCommand());
    }

    /**
     * GitHub's permission picker only lists permissions already added, so the email one is easy
     * to miss by hand; without it, commits carry no author email.
     */
    @Test
    void theTokenPageIsPrefilledWithTheNeededPermissions() {
        var url = URI.create(CredentialSetup.GH_PAT_NEW_URL);
        assertEquals("/settings/personal-access-tokens/new", url.getPath());
        var params = List.of(url.getQuery().split("&"));
        for (var expected : List.of("emails=read", "contents=write", "issues=write", "pull_requests=write")) {
            assertTrue(params.contains(expected), "missing " + expected + " in " + url);
        }
    }

    /** CI runs {@code isx init </dev/null}: every prompt hits EOF and nothing may be written. */
    @Test
    void closedStdinWritesNothing() {
        var init = new FakeInit().ghLogin(PERSONAL_GH_TOKEN);
        init.setupGitHubAuth(new SpawnConfig(), new ScriptedPrompts());

        assertNothingSaved();
        assertEquals(0, init.ghTokenReads, "EOF must never count as agreeing to reuse the personal login");
    }

    @Test
    void rejectedTokenAndDeclinedRetryWritesNothing() {
        var init = new FakeInit();
        run(init, declineBrowser().secret("ghp_bad").line("s"));

        assertEquals(List.of("ghp_bad"), init.verified);
        assertNothingSaved();
    }

    /** As in the Claude step, a token whose check could not reach GitHub may be saved anyway, rather than dropped (#1441). */
    @Test
    void aTokenThatFailedVerificationCanBeSavedAnyway() {
        var init = new FakeInit();
        init.gitHubReached = false;
        var out = reported(() -> run(init, declineBrowser().secret("ghp_unchecked").line("u").line(FINISHED)));

        assertEquals(List.of("ghp_unchecked"), init.verified);
        assertEquals("ghp_unchecked", saved("github.accounts.default.token"));
        assertEquals("", saved("github.accounts.default.email"));
        assertTrue(out.contains("Token saved on " + dev.incusspawn.Platform.thisComputer() + ", unverified"), out);
    }

    @Test
    void retryingAfterARejectedTokenSavesTheNextOne() {
        run(new FakeInit().acceptAgentPat(), declineBrowser().secret("ghp_bad").line("").secret(AGENT_PAT).line(FINISHED));

        assertEquals(AGENT_PAT, saved("github.accounts.default.token"));
    }

    // ── the personal 'gh' login fallback ─────────────────────────────────────────

    /**
     * Reusing the host's 'gh' login makes the agent act as the operator. It is offered only
     * when the PAT is skipped, and pressing Enter must decline it.
     */
    @Test
    void skippingThePatDoesNotReuseThePersonalLoginByDefault() {
        var init = new FakeInit().personalGhLogin();
        run(init, declineBrowser().secret("").line(""));

        assertEquals(0, init.ghTokenReads, "the personal token must not even be read without a yes");
        assertNothingSaved();
    }

    @Test
    void thePersonalLoginIsReusedOnlyWhenExplicitlyAccepted() {
        run(new FakeInit().personalGhLogin(), declineBrowser().secret("").line("y").line(FINISHED));

        assertEquals(PERSONAL_GH_TOKEN, saved("github.accounts.default.token"));
    }

    @Test
    void withoutAGhLoginTheReuseQuestionIsNeverAsked() {
        // assertFullyConsumed fails if the flow asks anything after the skipped PAT.
        run(new FakeInit(), declineBrowser().secret(""));
        assertNothingSaved();
    }

    // ── a pasted token of the person's own account (#2544) ───────────────────────

    /** A token made in a browser signed in as the person is theirs: No goes back to the paste prompt. */
    @Test
    void aPastedTokenOfTheGhAccountIsAskedAboutAndNoAsksForAnother() {
        var init = new FakeInit().personalGhLogin().acceptAgentPat().accept("ghp_mine", "Operator", "me@example.com");
        var prompts = declineBrowser().secret("ghp_mine").line("").secret(AGENT_PAT).line(FINISHED);

        var output = RecordingOutput.during(() -> run(init, prompts));

        assertTrue(prompts.conversation().contains("This token belongs to Operator, your own account."),
                () -> prompts.conversation().toString());
        assertEquals(AGENT_PAT, saved("github.accounts.default.token"));
        assertEquals(0, init.ghTokenReads);
        assertTrue(output.events(RecordingOutput.Kind.WARN).isEmpty());
    }

    @Test
    void aPastedTokenOfTheGhAccountIsSavedWhenConfirmed() {
        var init = new FakeInit().personalGhLogin().accept("ghp_mine", "operator", "me@example.com");
        run(init, declineBrowser().secret("ghp_mine").line("y").line(FINISHED));

        assertEquals("ghp_mine", saved("github.accounts.default.token"));
    }

    /** Another account's token, or no gh login to compare with, is saved without the question. */
    @Test
    void aTokenOfAnotherAccountIsNotAskedAbout() {
        var another = declineBrowser().secret(AGENT_PAT).line(FINISHED);
        run(new FakeInit().personalGhLogin().acceptAgentPat(), another);
        assertEquals(AGENT_PAT, saved("github.accounts.default.token"));

        var noGh = declineBrowser().secret("ghp_mine").line(FINISHED);
        run(new FakeInit().accept("ghp_mine", "operator", null), noGh);
        assertEquals("ghp_mine", saved("github.accounts.default.token"));

        for (var prompts : List.of(another, noGh)) {
            assertTrue(prompts.questions().stream().noneMatch(q -> q.contains("Use it anyway?")), prompts.questions()::toString);
        }
    }

    // ── the token's shape ────────────────────────────────────────────────────────

    @Test
    void knownTokenPrefixesPassTheShapeCheck() {
        for (var token : List.of(AGENT_PAT, "ghp_classic", PERSONAL_GH_TOKEN)) {
            assertEquals(Optional.empty(), CredentialSetup.githubTokenShapeWarning(token), token);
        }
    }

    /** Input is hidden, so a keystroke before the paste is invisible until it is named. */
    @Test
    void aStrayKeystrokeBeforeThePasteIsNamed() {
        var warning = CredentialSetup.githubTokenShapeWarning("1" + AGENT_PAT).orElseThrow();
        assertTrue(warning.contains("1 character comes before 'github_pat_'"), warning);
    }

    @Test
    void somethingThatIsNotATokenIsFlagged() {
        var warning = CredentialSetup.githubTokenShapeWarning("hunter2").orElseThrow();
        assertTrue(warning.contains("does not start with 'github_pat_'"), warning);
    }

    // ── re-runs: the account menu around the prompt ──────────────────────────────

    @Test
    void enterOnReRunLeavesTheFileUntouched() throws Exception {
        run(new FakeInit(), seed(ONE_FLAT_CREDENTIAL), ScriptedPrompts.lines(""));

        assertUnchanged(ONE_FLAT_CREDENTIAL);
    }

    /** Replacing a flat credential moves it into the accounts layout, leaving nothing flat behind. */
    @Test
    void replacingTheSingleCredentialLeavesItTheOnlyAccount() throws Exception {
        run(new FakeInit().acceptAgentPat(), seed(ONE_FLAT_CREDENTIAL),
                new ScriptedPrompts().line("r").secret(AGENT_PAT).line(FINISHED));

        assertEquals(AGENT_PAT, saved("github.accounts.default.token"));
        assertEquals("bot@example.com", saved("github.accounts.default.email"));
        assertEquals(List.of("default"), NamespaceAccounts.names(SpawnConfig.load(), "github"));
        assertEquals("", saved("github.token"));
        assertEquals("", saved("github.email"), "the old flat email must not linger beside the new account");
    }

    @Test
    void addingASecondAccountKeepsTheFirst() throws Exception {
        run(new FakeInit().acceptAgentPat(), seed(ONE_FLAT_CREDENTIAL),
                new ScriptedPrompts().line("a", "work").secret(AGENT_PAT).line(FINISHED));

        assertEquals("ghp_flat", saved("github.accounts.default.token"));
        assertEquals("me@example.com", saved("github.accounts.default.email"));
        assertEquals(AGENT_PAT, saved("github.accounts.work.token"));
        assertEquals("default", saved("github.default"),
                "adding an account must not re-point the default at it");
        assertEquals("", saved("github.token"), "the flat token moves, it is not duplicated");
    }

    /** After a first token is saved, the menu returns so another identity fits in the same run. */
    @Test
    void aSecondAccountCanBeAddedInTheSameRun() {
        var init = new FakeInit().acceptAgentPat().accept("github_pat_work_000", "work-bot", "work@example.com");
        run(init, declineBrowser().secret(AGENT_PAT)
                .line("a", "work").secret("github_pat_work_000")
                .line(FINISHED));

        assertEquals(AGENT_PAT, saved("github.accounts.default.token"));
        assertEquals("github_pat_work_000", saved("github.accounts.work.token"));
        assertEquals("work@example.com", saved("github.accounts.work.email"));
        assertEquals("default", saved("github.default"));
    }

    /** Skipping an extra account must not claim GitHub setup was skipped: the first is saved. */
    @Test
    void skippingASecondAccountSaysOnlyThatOneWasSkipped() {
        var out = reported(() -> run(new FakeInit().acceptAgentPat(),
                declineBrowser().secret(AGENT_PAT).line("a", "work").secret("")));

        assertTrue(out.contains("Skipped the token. This account stays as it was."), out);
        assertFalse(out.contains("Skipped GitHub setup"), out);
        assertEquals(AGENT_PAT, saved("github.accounts.default.token"));
    }

    @Test
    void skippingTheOnlyAccountSaysSetupWasSkipped() {
        var out = reported(() -> run(new FakeInit(), declineBrowser().secret("")));

        assertTrue(out.contains("adds one later"), out);
    }

    /**
     * #740 end to end: naming a second account and then abandoning at the PAT prompt must
     * leave the file byte-for-byte as it was -- not migrated into a shape an older isx reads as
     * having no GitHub credentials.
     */
    @Test
    void abandoningASecondAccountAtThePatPromptChangesNothing() throws Exception {
        run(new FakeInit(), seed(ONE_FLAT_CREDENTIAL), new ScriptedPrompts().line("a", "work").secret(""));

        assertUnchanged(ONE_FLAT_CREDENTIAL);
    }

    @Test
    void abandoningASecondAccountAfterARejectedTokenChangesNothing() throws Exception {
        run(new FakeInit(), seed(ONE_FLAT_CREDENTIAL),
                new ScriptedPrompts().line("a", "work").secret("ghp_bad").line("s"));

        assertUnchanged(ONE_FLAT_CREDENTIAL);
    }

    /** A token for a named account belongs to that account, not back in the flat field. */
    @Test
    void aTokenLandsInTheChosenAccount() throws Exception {
        var init = new FakeInit().accept("github_pat_with_email_000", "agent-bot", "bot@example.com");
        run(init, seed(ONE_FLAT_CREDENTIAL),
                new ScriptedPrompts().line("a", "work").secret("github_pat_with_email_000").line(FINISHED));

        assertEquals("github_pat_with_email_000", saved("github.accounts.work.token"));
        assertEquals("bot@example.com", saved("github.accounts.work.email"));
        assertEquals("ghp_flat", saved("github.accounts.default.token"));
    }

    @Test
    void thePersonalLoginReusedForANamedAccountLandsInThatAccount() throws Exception {
        run(new FakeInit().personalGhLogin(), seed(ONE_FLAT_CREDENTIAL),
                new ScriptedPrompts().line("a", "mine").secret("").line("y").line(FINISHED));

        assertEquals(PERSONAL_GH_TOKEN, saved("github.accounts.mine.token"));
        assertEquals("ghp_flat", saved("github.accounts.default.token"));
        assertEquals("default", saved("github.default"));
    }

    // ── choosing the commit email ────────────────────────────────────────────────

    private static final CredentialSetup.GitHubUser AGENT = new CredentialSetup.GitHubUser("me", 1234, "me@work.example");
    private static final String NOREPLY = "1234+me@users.noreply.github.com";

    private static String chooseEmail(CredentialSetup.GitHubUser user, String... input) {
        var prompts = ScriptedPrompts.lines(input);
        var email = CredentialSetup.chooseCommitEmail(user, prompts);
        prompts.assertFullyConsumed();
        return email;
    }

    /** The default must be the private address, so pressing Enter never publishes a real one. */
    @Test
    void enterPicksTheNoreplyAddress() {
        assertEquals(NOREPLY, chooseEmail(AGENT, ""));
    }

    @Test
    void closedStdinPicksTheNoreplyAddress() {
        assertEquals(NOREPLY, CredentialSetup.chooseCommitEmail(AGENT, new ScriptedPrompts()));
    }

    @Test
    void twoPicksThePrimaryAddress() {
        assertEquals("me@work.example", chooseEmail(AGENT, "2"));
    }

    @Test
    void anUnreadableChoiceIsAskedAgain() {
        assertEquals("me@work.example", chooseEmail(AGENT, "4", "me@home.example", " 2 "));
    }

    /** With no address to read, GitHub's private one is still the account's: taken without asking. */
    @Test
    void withNoReadableAddressTheNoreplyOneIsTakenWithoutAsking() {
        assertEquals(NOREPLY, chooseEmail(new CredentialSetup.GitHubUser("me", 1234, null)));
    }

    /** With no account number, there is no private address to offer. */
    @Test
    void withNoAccountNumberThePrimaryIsTakenWithoutAsking() {
        assertEquals("me@work.example", chooseEmail(new CredentialSetup.GitHubUser("me", 0, "me@work.example")));
    }
}
