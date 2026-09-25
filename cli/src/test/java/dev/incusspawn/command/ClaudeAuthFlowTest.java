package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Answers verification and environment lookups from tables. */
    static class FakeInit extends InitCommand {
        final Map<String, String> env = new HashMap<>();
        final Set<String> validKeys = new HashSet<>();
        final Set<String> validOauth = new HashSet<>();
        final Set<String> validVertex = new HashSet<>();
        boolean hasClaudeCli;
        int setupTokenRuns;

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

    private static Path configFile() {
        return Environment.configDir().resolve("config.yaml");
    }

    private static SpawnConfig seed(String yaml) throws Exception {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), yaml);
        return SpawnConfig.load();
    }

    private static Map<String, SpawnConfig.ClaudeAccount> savedAccounts() {
        return SpawnConfig.load().getClaude().allAccounts();
    }

    private static void run(FakeInit init, SpawnConfig config, ScriptedPrompts prompts) {
        init.setupClaudeAuth(config, prompts);
        prompts.assertFullyConsumed();
    }

    // ── first-time setup, one per auth mode ──────────────────────────────────────

    @Test
    void aVerifiedApiKeyIsSaved() {
        var init = new FakeInit();
        init.validKeys.add(API_KEY);
        run(init, new SpawnConfig(), new ScriptedPrompts().line("1").secret(API_KEY));

        var account = SpawnConfig.load().getClaude().account();
        assertEquals(SpawnConfig.ClaudeAccountType.API_KEY, account.effectiveType());
        assertEquals(API_KEY, account.getApiKey());
    }

    @Test
    void aVerifiedOauthTokenIsSaved() {
        var init = new FakeInit();
        init.validOauth.add(OAUTH_TOKEN);
        run(init, new SpawnConfig(), new ScriptedPrompts().line("2").secret(OAUTH_TOKEN));

        var account = SpawnConfig.load().getClaude().account();
        assertEquals(SpawnConfig.ClaudeAccountType.OAUTH, account.effectiveType());
        assertEquals(OAUTH_TOKEN, account.getOauthToken());
    }

    @Test
    void theOauthFlowOffersToRunSetupTokenWhenClaudeIsInstalled() {
        var init = new FakeInit();
        init.hasClaudeCli = true;
        init.validOauth.add(OAUTH_TOKEN);
        run(init, new SpawnConfig(), new ScriptedPrompts().line("2", "").secret(OAUTH_TOKEN));

        assertEquals(1, init.setupTokenRuns);
        assertEquals(OAUTH_TOKEN, SpawnConfig.load().getClaude().account().getOauthToken());
    }

    @Test
    void aVerifiedVertexConfigurationIsSaved() {
        var init = new FakeInit();
        init.validVertex.add("us-east5/my-project");
        run(init, new SpawnConfig(), ScriptedPrompts.lines("3", "us-east5", "my-project"));

        var account = SpawnConfig.load().getClaude().account();
        assertEquals(SpawnConfig.ClaudeAccountType.VERTEX, account.effectiveType());
        assertEquals("us-east5", account.getCloudMlRegion());
        assertEquals("my-project", account.getVertexProjectId());
    }

    /** CI runs {@code isx init </dev/null}: every prompt hits EOF and nothing may be written. */
    @Test
    void closedStdinWritesNothing() {
        new FakeInit().setupClaudeAuth(new SpawnConfig(), new ScriptedPrompts());
        assertFalse(Files.exists(configFile()));
    }

    // ── verification failures ────────────────────────────────────────────────────

    @Test
    void aRejectedKeyIsNotSavedWhenTheUserGivesUp() {
        run(new FakeInit(), new SpawnConfig(),
                new ScriptedPrompts().line("1").secret("sk-ant-bad").line("n"));
        assertFalse(Files.exists(configFile()));
    }

    /** EOF at "try again?" must skip rather than loop forever re-asking for the key. */
    @Test
    void eofAtTheRetryQuestionSkips() {
        new FakeInit().setupClaudeAuth(new SpawnConfig(),
                new ScriptedPrompts().line("1").secret("sk-ant-bad"));
        assertFalse(Files.exists(configFile()));
    }

    @Test
    void saveAnywayKeepsAnUnverifiedKey() {
        run(new FakeInit(), new SpawnConfig(),
                new ScriptedPrompts().line("1").secret("sk-ant-unverified").line("s"));
        assertEquals("sk-ant-unverified", SpawnConfig.load().getClaude().account().getApiKey());
    }

    @Test
    void retryingSavesTheKeyThatVerifies() {
        var init = new FakeInit();
        init.validKeys.add(API_KEY);
        run(init, new SpawnConfig(),
                new ScriptedPrompts().line("1").secret("sk-ant-bad").line("y").secret(API_KEY));
        assertEquals(API_KEY, SpawnConfig.load().getClaude().account().getApiKey());
    }

    // ── credentials found in the environment ─────────────────────────────────────

    @Test
    void aVerifiedEnvironmentKeyIsSavedOnEnter() {
        var init = new FakeInit();
        init.env.put("ANTHROPIC_API_KEY", API_KEY);
        init.validKeys.add(API_KEY);
        run(init, new SpawnConfig(), ScriptedPrompts.lines(""));

        assertEquals(API_KEY, SpawnConfig.load().getClaude().account().getApiKey());
    }

    @Test
    void decliningTheEnvironmentKeyFallsThroughToManualSetup() {
        var init = new FakeInit();
        init.env.put("ANTHROPIC_API_KEY", "sk-ant-from-env");
        init.validKeys.addAll(List.of("sk-ant-from-env", API_KEY));
        run(init, new SpawnConfig(), new ScriptedPrompts().line("n", "1").secret(API_KEY));

        assertEquals(API_KEY, SpawnConfig.load().getClaude().account().getApiKey());
    }

    /**
     * With several accounts, an environment credential replaces the default one and nothing
     * else -- the prompt says so, and the other accounts must survive.
     */
    @Test
    void anEnvironmentKeyReplacesOnlyTheDefaultAccount() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        var init = new FakeInit();
        init.env.put("ANTHROPIC_API_KEY", API_KEY);
        init.validKeys.add(API_KEY);
        run(init, config, ScriptedPrompts.lines(""));

        var accounts = savedAccounts();
        assertEquals(Set.of("personal", "client"), accounts.keySet());
        assertEquals(API_KEY, accounts.get("client").getApiKey());
        assertEquals("sk-ant-api03-personal", accounts.get("personal").getApiKey());
        assertEquals("client", SpawnConfig.load().getClaude().getDefaultAccount());
    }

    // ── re-runs: the account menu ────────────────────────────────────────────────

    @Test
    void enterOnReRunLeavesTheFileUntouched() throws Exception {
        var config = seed(ONE_LEGACY_KEY);
        run(new FakeInit(), config, ScriptedPrompts.lines(""));
        assertEquals(ONE_LEGACY_KEY, Files.readString(configFile()));
    }

    @Test
    void addingAnAccountKeepsTheExistingOneAsDefault() throws Exception {
        var config = seed(ONE_LEGACY_KEY);
        var init = new FakeInit();
        init.validOauth.add(OAUTH_TOKEN);
        run(init, config, new ScriptedPrompts().line("a", "work", "2").secret(OAUTH_TOKEN));

        var accounts = savedAccounts();
        assertEquals("sk-ant-api03-old", accounts.get("default").getApiKey());
        assertEquals(OAUTH_TOKEN, accounts.get("work").getOauthToken());
        assertEquals("default", SpawnConfig.load().getClaude().accountName());
    }

    @Test
    void abandoningANewAccountChangesNothing() throws Exception {
        var config = seed(ONE_LEGACY_KEY);
        run(new FakeInit(), config, new ScriptedPrompts().line("a", "work", "1").secret(""));
        assertEquals(ONE_LEGACY_KEY, Files.readString(configFile()));
    }

    @Test
    void anAccountNameAlreadyInUseIsRefused() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        var init = new FakeInit();
        init.validKeys.add(API_KEY);
        // 'personal' is taken, so the name prompt asks again.
        run(init, config, new ScriptedPrompts().line("a", "personal", "third", "1").secret(API_KEY));

        var accounts = savedAccounts();
        assertEquals("sk-ant-api03-personal", accounts.get("personal").getApiKey());
        assertEquals(API_KEY, accounts.get("third").getApiKey());
    }

    @Test
    void replaceAllLeavesASingleAccount() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        var init = new FakeInit();
        init.validKeys.add(API_KEY);
        run(init, config, new ScriptedPrompts().line("r", "1").secret(API_KEY));

        var accounts = savedAccounts();
        assertEquals(1, accounts.size());
        assertEquals(API_KEY, SpawnConfig.load().getClaude().account().getApiKey());
    }

    /** Only the credential collected afterwards writes; choosing 'r' alone must not wipe anything. */
    @Test
    void replaceAllAbandonedChangesNothing() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        run(new FakeInit(), config, ScriptedPrompts.lines("r", ""));
        assertEquals(TWO_ACCOUNTS, Files.readString(configFile()));
    }

    @Test
    void changingTheDefaultIsSavedImmediately() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        run(new FakeInit(), config, ScriptedPrompts.lines("d", "personal", ""));
        assertEquals("personal", SpawnConfig.load().getClaude().getDefaultAccount());
    }

    @Test
    void removingTheDefaultAccountRepointsTheDefault() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        run(new FakeInit(), config, ScriptedPrompts.lines("x", "client", ""));

        assertEquals(Set.of("personal"), savedAccounts().keySet());
        assertEquals("personal", SpawnConfig.load().getClaude().getDefaultAccount());
    }
}
