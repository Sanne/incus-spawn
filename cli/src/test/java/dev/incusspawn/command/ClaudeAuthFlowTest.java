package dev.incusspawn.command;

import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static dev.incusspawn.command.IsolatedHome.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx init}'s Claude step, end to end: environment detection, the account menu, the
 * auth-mode choice, verification, and what lands in config.yaml. Every assertion reads the file
 * back from disk.
 */
@ExtendWith(IsolatedHome.class)
class ClaudeAuthFlowTest {

    private static final String API_KEY = "sk-ant-api03-good";
    private static final String OAUTH_TOKEN = SpawnConfig.ClaudeConfig.OAUTH_TOKEN_PREFIX + "good";

    private static final String ONE_LEGACY_KEY = """
            claude:
              apiKey: "sk-ant-api03-old"
            """;

    private static final String TWO_ACCOUNTS = """
            claude:
              accounts:
                personal:
                  type: api-key
                  apiKey: "sk-ant-api03-personal"
                client:
                  type: oauth
                  oauthToken: "sk-ant-oat01-client"
              default: client
            """;

    /** Answers verification and environment lookups from tables, built up by chaining. */
    static class FakeInit extends InitCommand {
        private final Map<String, String> env = new HashMap<>();
        private final Set<String> validKeys = new HashSet<>();
        private final Set<String> validOauth = new HashSet<>();
        private final Set<String> validVertex = new HashSet<>();
        private boolean hasClaudeCli;
        int setupTokenRuns;

        FakeInit acceptKey(String... keys) {
            validKeys.addAll(Set.of(keys));
            return this;
        }

        FakeInit acceptOauth(String token) {
            validOauth.add(token);
            return this;
        }

        FakeInit acceptVertex(String region, String projectId) {
            validVertex.add(region + "/" + projectId);
            return this;
        }

        FakeInit env(String name, String value) {
            env.put(name, value);
            return this;
        }

        /** Vertex selected through the environment, as Claude Code itself reads it. */
        FakeInit vertexEnv(String region, String projectId) {
            return env("CLAUDE_CODE_USE_VERTEX", "1")
                    .env("CLOUD_ML_REGION", region)
                    .env("ANTHROPIC_VERTEX_PROJECT_ID", projectId);
        }

        FakeInit withClaudeCli() {
            hasClaudeCli = true;
            return this;
        }

        @Override
        String env(String name) {
            return env.getOrDefault(name, "");
        }

        @Override
        AuthResult verifyAnthropicApiKey(String key) {
            return new AuthResult(validKeys.contains(key), "key checked");
        }

        @Override
        AuthResult verifyOauthToken(String token) {
            return new AuthResult(validOauth.contains(token), "token checked");
        }

        @Override
        AuthResult verifyVertexConfig(String region, String projectId) {
            return new AuthResult(validVertex.contains(region + "/" + projectId), "vertex checked");
        }

        @Override
        boolean hostHasCommand(String command) {
            return "claude".equals(command) && hasClaudeCli;
        }

        @Override
        void runClaudeSetupToken() {
            setupTokenRuns++;
        }
    }

    /** The saved Claude config, read back from disk. */
    private static SpawnConfig.ClaudeConfig savedClaude() {
        return SpawnConfig.load().getClaude();
    }

    /** The account instances use by default, as saved. */
    private static SpawnConfig.ClaudeAccount savedAccount() {
        return savedClaude().account();
    }

    private static Map<String, SpawnConfig.ClaudeAccount> savedAccounts() {
        return savedClaude().allAccounts();
    }

    /** First-time setup: no config yet. */
    private static void run(FakeInit init, ScriptedPrompts prompts) {
        run(init, new SpawnConfig(), prompts);
    }

    private static void run(FakeInit init, SpawnConfig config, ScriptedPrompts prompts) {
        init.setupClaudeAuth(config, prompts);
        prompts.assertFullyConsumed();
    }

    // ── first-time setup, one per auth mode ──────────────────────────────────────

    @Test
    void aVerifiedApiKeyIsSaved() {
        run(new FakeInit().acceptKey(API_KEY), new ScriptedPrompts().line("1").secret(API_KEY));

        assertEquals(SpawnConfig.ClaudeAccountType.API_KEY, savedAccount().effectiveType());
        assertEquals(API_KEY, savedAccount().getApiKey());
    }

    @Test
    void aVerifiedOauthTokenIsSaved() {
        run(new FakeInit().acceptOauth(OAUTH_TOKEN), new ScriptedPrompts().line("2").secret(OAUTH_TOKEN));

        assertEquals(SpawnConfig.ClaudeAccountType.OAUTH, savedAccount().effectiveType());
        assertEquals(OAUTH_TOKEN, savedAccount().getOauthToken());
    }

    @Test
    void theOauthFlowOffersToRunSetupTokenWhenClaudeIsInstalled() {
        var init = new FakeInit().withClaudeCli().acceptOauth(OAUTH_TOKEN);
        run(init, new ScriptedPrompts().line("2", "").secret(OAUTH_TOKEN));

        assertEquals(1, init.setupTokenRuns);
        assertEquals(OAUTH_TOKEN, savedAccount().getOauthToken());
    }

    @Test
    void aVerifiedVertexConfigurationIsSaved() {
        run(new FakeInit().acceptVertex("us-east5", "my-project"),
                ScriptedPrompts.lines("3", "us-east5", "my-project"));

        assertEquals(SpawnConfig.ClaudeAccountType.VERTEX, savedAccount().effectiveType());
        assertEquals("us-east5", savedAccount().getCloudMlRegion());
        assertEquals("my-project", savedAccount().getVertexProjectId());
    }

    /** CI runs {@code isx init </dev/null}: every prompt hits EOF and nothing may be written. */
    @Test
    void closedStdinWritesNothing() {
        new FakeInit().setupClaudeAuth(new SpawnConfig(), new ScriptedPrompts());
        assertNothingSaved();
    }

    // ── verification failures ────────────────────────────────────────────────────

    @Test
    void aRejectedKeyIsNotSavedWhenTheUserGivesUp() {
        run(new FakeInit(), new ScriptedPrompts().line("1").secret("sk-ant-bad").line("n"));
        assertNothingSaved();
    }

    /** EOF at "try again?" must skip rather than loop forever re-asking for the key. */
    @Test
    void eofAtTheRetryQuestionSkips() {
        new FakeInit().setupClaudeAuth(new SpawnConfig(), new ScriptedPrompts().line("1").secret("sk-ant-bad"));
        assertNothingSaved();
    }

    @Test
    void saveAnywayKeepsAnUnverifiedKey() {
        run(new FakeInit(), new ScriptedPrompts().line("1").secret("sk-ant-unverified").line("s"));
        assertEquals("sk-ant-unverified", savedAccount().getApiKey());
    }

    @Test
    void retryingSavesTheKeyThatVerifies() {
        run(new FakeInit().acceptKey(API_KEY),
                new ScriptedPrompts().line("1").secret("sk-ant-bad").line("y").secret(API_KEY));
        assertEquals(API_KEY, savedAccount().getApiKey());
    }

    // ── credentials found in the environment ─────────────────────────────────────

    @Test
    void aVerifiedEnvironmentKeyIsSavedOnEnter() {
        run(new FakeInit().env("ANTHROPIC_API_KEY", API_KEY).acceptKey(API_KEY), ScriptedPrompts.lines(""));
        assertEquals(API_KEY, savedAccount().getApiKey());
    }

    @Test
    void decliningTheEnvironmentKeyFallsThroughToManualSetup() {
        var init = new FakeInit().env("ANTHROPIC_API_KEY", "sk-ant-from-env").acceptKey("sk-ant-from-env", API_KEY);
        run(init, new ScriptedPrompts().line("n", "1").secret(API_KEY));
        assertEquals(API_KEY, savedAccount().getApiKey());
    }

    @Test
    void aVerifiedEnvironmentVertexConfigIsSavedOnEnter() {
        run(new FakeInit().vertexEnv("us-east5", "my-project").acceptVertex("us-east5", "my-project"),
                ScriptedPrompts.lines(""));

        assertEquals(SpawnConfig.ClaudeAccountType.VERTEX, savedAccount().effectiveType());
        assertEquals("us-east5", savedAccount().getCloudMlRegion());
        assertEquals("my-project", savedAccount().getVertexProjectId());
    }

    /** Unverified, the environment config is saved only on an explicit yes; Enter means "set up manually". */
    @Test
    void anUnverifiedEnvironmentVertexConfigIsNotSavedOnEnter() {
        run(new FakeInit().vertexEnv("us-east5", "my-project"), ScriptedPrompts.lines("", ""));
        assertNothingSaved();
    }

    @Test
    void anUnverifiedEnvironmentVertexConfigCanBeSavedAnyway() {
        run(new FakeInit().vertexEnv("us-east5", "my-project"), ScriptedPrompts.lines("y"));
        assertEquals("my-project", savedAccount().getVertexProjectId());
    }

    /** Half a Vertex config cannot be verified, so nothing is offered: straight to manual setup. */
    @Test
    void anIncompleteEnvironmentVertexConfigGoesStraightToManualSetup() {
        run(new FakeInit().vertexEnv("us-east5", "").acceptKey(API_KEY),
                new ScriptedPrompts().line("1").secret(API_KEY));
        assertEquals(API_KEY, savedAccount().getApiKey());
    }

    /** CLAUDE_CODE_USE_VERTEX wins over a key or token in the same environment, as it does for Claude Code. */
    @Test
    void vertexInTheEnvironmentTakesPrecedenceOverAKey() {
        var init = new FakeInit()
                .vertexEnv("us-east5", "my-project").acceptVertex("us-east5", "my-project")
                .env("ANTHROPIC_API_KEY", API_KEY).acceptKey(API_KEY);
        run(init, ScriptedPrompts.lines(""));

        assertEquals(SpawnConfig.ClaudeAccountType.VERTEX, savedAccount().effectiveType());
    }

    @Test
    void aVerifiedEnvironmentOauthTokenIsSavedOnEnter() {
        run(new FakeInit().env("CLAUDE_CODE_OAUTH_TOKEN", OAUTH_TOKEN).acceptOauth(OAUTH_TOKEN),
                ScriptedPrompts.lines(""));
        assertEquals(OAUTH_TOKEN, savedAccount().getOauthToken());
    }

    /** A rejected environment token is not offered for saving at all. */
    @Test
    void aRejectedEnvironmentOauthTokenGoesStraightToManualSetup() {
        run(new FakeInit().env("CLAUDE_CODE_OAUTH_TOKEN", OAUTH_TOKEN), ScriptedPrompts.lines(""));
        assertNothingSaved();
    }

    /**
     * With several accounts, an environment credential replaces the default one and nothing
     * else -- the prompt says so, and the other accounts must survive.
     */
    @Test
    void anEnvironmentKeyReplacesOnlyTheDefaultAccount() throws Exception {
        run(new FakeInit().env("ANTHROPIC_API_KEY", API_KEY).acceptKey(API_KEY), seed(TWO_ACCOUNTS),
                ScriptedPrompts.lines(""));

        var accounts = savedAccounts();
        assertEquals(Set.of("personal", "client"), accounts.keySet());
        assertEquals(API_KEY, accounts.get("client").getApiKey());
        assertEquals("sk-ant-api03-personal", accounts.get("personal").getApiKey());
        assertEquals("client", savedClaude().getDefaultAccount());
    }

    // ── re-runs: the account menu ────────────────────────────────────────────────

    @Test
    void enterOnReRunLeavesTheFileUntouched() throws Exception {
        run(new FakeInit(), seed(ONE_LEGACY_KEY), ScriptedPrompts.lines(""));
        assertUnchanged(ONE_LEGACY_KEY);
    }

    @Test
    void addingAnAccountKeepsTheExistingOneAsDefault() throws Exception {
        run(new FakeInit().acceptOauth(OAUTH_TOKEN), seed(ONE_LEGACY_KEY),
                new ScriptedPrompts().line("a", "work", "2").secret(OAUTH_TOKEN));

        var accounts = savedAccounts();
        assertEquals("sk-ant-api03-old", accounts.get("default").getApiKey());
        assertEquals(OAUTH_TOKEN, accounts.get("work").getOauthToken());
        assertEquals("default", savedClaude().accountName());
    }

    @Test
    void abandoningANewAccountChangesNothing() throws Exception {
        run(new FakeInit(), seed(ONE_LEGACY_KEY), new ScriptedPrompts().line("a", "work", "1").secret(""));
        assertUnchanged(ONE_LEGACY_KEY);
    }

    @Test
    void anAccountNameAlreadyInUseIsRefused() throws Exception {
        // 'personal' is taken, so the name prompt asks again.
        run(new FakeInit().acceptKey(API_KEY), seed(TWO_ACCOUNTS),
                new ScriptedPrompts().line("a", "personal", "third", "1").secret(API_KEY));

        var accounts = savedAccounts();
        assertEquals("sk-ant-api03-personal", accounts.get("personal").getApiKey());
        assertEquals(API_KEY, accounts.get("third").getApiKey());
    }

    @Test
    void replaceAllLeavesASingleAccount() throws Exception {
        run(new FakeInit().acceptKey(API_KEY), seed(TWO_ACCOUNTS),
                new ScriptedPrompts().line("r", "1").secret(API_KEY));

        assertEquals(1, savedAccounts().size());
        assertEquals(API_KEY, savedAccount().getApiKey());
    }

    /** Only the credential collected afterwards writes; choosing 'r' alone must not wipe anything. */
    @Test
    void replaceAllAbandonedChangesNothing() throws Exception {
        run(new FakeInit(), seed(TWO_ACCOUNTS), ScriptedPrompts.lines("r", ""));
        assertUnchanged(TWO_ACCOUNTS);
    }

    @Test
    void changingTheDefaultIsSavedImmediately() throws Exception {
        run(new FakeInit(), seed(TWO_ACCOUNTS), ScriptedPrompts.lines("d", "personal", ""));
        assertEquals("personal", savedClaude().getDefaultAccount());
    }

    @Test
    void removingTheDefaultAccountRepointsTheDefault() throws Exception {
        run(new FakeInit(), seed(TWO_ACCOUNTS), ScriptedPrompts.lines("x", "client", ""));

        assertEquals(Set.of("personal"), savedAccounts().keySet());
        assertEquals("personal", savedClaude().getDefaultAccount());
    }
}
