package dev.incusspawn.command;

import dev.incusspawn.command.HostAgentProbe.HostAgent;
import dev.incusspawn.command.HostAgentProbe.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probe of the coding agents on the host (#2408), against made-up home directories: each
 * agent in each kind of account it can tell apart, files it cannot read, and no credential returned.
 */
class HostAgentProbeTest {

    /** Stands in for every secret a fixture holds; no record may ever contain it. */
    private static final String SENTINEL = "x".repeat(32);

    @TempDir
    Path home;

    private final Map<String, String> env = new HashMap<>();
    private Path bin;

    private void write(String relative, String content) throws IOException {
        var file = home.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void onPath(String command) throws IOException {
        if (bin == null) bin = Files.createDirectories(home.resolve("bin"));
        var file = bin.resolve(command);
        Files.writeString(file, "#!/bin/sh\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private List<HostAgent> probe() {
        var found = HostAgentProbe.probe(home, env, bin == null ? List.of() : List.of(bin));
        for (var agent : found) {
            assertFalse(agent.toString().contains(SENTINEL), agent.toString());
            assertFalse(agent.summary().contains(SENTINEL), agent.summary());
        }
        var note = HostAgentProbe.note(found);
        assertTrue(note == null || !note.contains(SENTINEL), note);
        return found;
    }

    private HostAgent only(String agent) {
        var found = probe().stream().filter(a -> a.agent().equals(agent)).toList();
        assertEquals(1, found.size(), "found " + found);
        return found.getFirst();
    }

    private static void assertAccount(HostAgent agent, Kind kind, String foundIn) {
        assertTrue(agent.signedIn(), agent.toString());
        assertEquals(kind, agent.kind(), agent.toString());
        assertEquals(foundIn, agent.foundIn());
    }

    @Test
    void anEmptyHomeHasNoAgents() {
        assertEquals(List.of(), probe());
        assertNull(HostAgentProbe.note(List.of()));
    }

    // ---- Claude Code ----

    @Test
    void claudeSubscriptionIsSeenInItsStateFile() throws IOException {
        write(".claude.json", "{\"oauthAccount\": {\"emailAddress\": \"a@example.com\"}, \"projects\": {}}");
        write(".claude/settings.json", "{\"model\": \"opus\"}");
        var claude = only("claude");
        assertAccount(claude, Kind.SUBSCRIPTION, "~/.claude.json");
        assertEquals("opus", claude.model());
        assertFalse(claude.toString().contains("a@example.com"));
    }

    @Test
    void claudeApiKeyFromALoginIsSeenWithoutItsValue() throws IOException {
        write(".claude.json", "{\"primaryApiKey\": \"" + SENTINEL + "\"}");
        assertAccount(only("claude"), Kind.API_KEY, "~/.claude.json");
    }

    @Test
    void claudeApiKeyInTheEnvironmentIsNamedNotShown() throws IOException {
        onPath("claude");
        env.put("ANTHROPIC_API_KEY", SENTINEL);
        assertAccount(only("claude"), Kind.API_KEY, "$ANTHROPIC_API_KEY");
    }

    @Test
    void claudeOauthTokenInTheEnvironmentIsASubscription() throws IOException {
        onPath("claude");
        env.put("CLAUDE_CODE_OAUTH_TOKEN", SENTINEL);
        assertAccount(only("claude"), Kind.SUBSCRIPTION, "$CLAUDE_CODE_OAUTH_TOKEN");
    }

    @Test
    void claudeBearerTokenAndApiKeyComeBeforeASubscriptionToken() throws IOException {
        onPath("claude");
        env.put("CLAUDE_CODE_OAUTH_TOKEN", SENTINEL);
        env.put("ANTHROPIC_API_KEY", SENTINEL);
        assertAccount(only("claude"), Kind.API_KEY, "$ANTHROPIC_API_KEY");
        env.put("ANTHROPIC_AUTH_TOKEN", SENTINEL);
        assertAccount(only("claude"), Kind.API_KEY, "$ANTHROPIC_AUTH_TOKEN");
    }

    @Test
    void claudeOnVertexHasItsProjectAndRegion() throws IOException {
        write(".claude/settings.json", """
                {"env": {"CLAUDE_CODE_USE_VERTEX": "1", "ANTHROPIC_VERTEX_PROJECT_ID": "my-project-42",
                         "CLOUD_ML_REGION": "us-east5"}, "model": "claude-opus-4-1[1m]"}""");
        var claude = only("claude");
        assertAccount(claude, Kind.CLOUD, "~/.claude/settings.json");
        assertEquals("vertex-ai", claude.provider());
        assertEquals("my-project-42", claude.project());
        assertEquals("us-east5", claude.region());
        assertEquals("claude-opus-4-1[1m]", claude.model());
    }

    @Test
    void claudeInstalledButNotSignedIn() throws IOException {
        onPath("claude");
        var claude = only("claude");
        assertFalse(claude.signedIn());
        assertEquals(Kind.UNKNOWN, claude.kind());
        assertNull(claude.foundIn());
    }

    @Test
    void claudeConfigDirIsFollowed() throws IOException {
        write("elsewhere/.claude.json", "{\"oauthAccount\": {}}");
        env.put("CLAUDE_CONFIG_DIR", home.resolve("elsewhere").toString());
        assertAccount(only("claude"), Kind.SUBSCRIPTION, "~/elsewhere/.claude.json");
    }

    // ---- Codex ----

    @Test
    void codexChatGptLogin() throws IOException {
        write(".codex/auth.json", "{\"OPENAI_API_KEY\": null, \"tokens\": {\"access_token\": \"" + SENTINEL + "\"}}");
        write(".codex/config.toml", "model = \"gpt-5-codex\"\n\n[profiles.other]\nmodel = \"o3\"\n");
        var codex = only("codex");
        assertAccount(codex, Kind.SUBSCRIPTION, "~/.codex/auth.json");
        assertEquals("gpt-5-codex", codex.model());
    }

    @Test
    void codexApiKey() throws IOException {
        write(".codex/auth.json", "{\"auth_mode\": \"apikey\", \"OPENAI_API_KEY\": \"" + SENTINEL + "\"}");
        write(".codex/config.toml", "[profiles.other]\nmodel = \"o3\"\n");
        var codex = only("codex");
        assertAccount(codex, Kind.API_KEY, "~/.codex/auth.json");
        assertNull(codex.model(), "a model in a table is a profile's, not the default");
    }

    // ---- Gemini CLI ----

    @Test
    void geminiGoogleLogin() throws IOException {
        write(".gemini/settings.json", "{\"security\": {\"auth\": {\"selectedType\": \"oauth-personal\"}}, \"model\": {\"name\": \"gemini-2.5-pro\"}}");
        write(".gemini/oauth_creds.json", "{\"refresh_token\": \"" + SENTINEL + "\"}");
        var gemini = only("gemini-cli");
        assertAccount(gemini, Kind.SUBSCRIPTION, "~/.gemini/oauth_creds.json");
        assertEquals("gemini-2.5-pro", gemini.model());
    }

    @Test
    void geminiApiKeyFromItsEnvFile() throws IOException {
        write(".gemini/settings.json", "{\"selectedAuthType\": \"gemini-api-key\"}");
        write(".gemini/.env", "GEMINI_API_KEY=\"" + SENTINEL + "\"\n");
        assertAccount(only("gemini-cli"), Kind.API_KEY, "$GEMINI_API_KEY");
    }

    @Test
    void geminiOnVertex() throws IOException {
        write(".gemini/settings.json", "{\"security\": {\"auth\": {\"selectedType\": \"vertex-ai\"}}}");
        env.put("GOOGLE_CLOUD_PROJECT", "proj-1");
        env.put("GOOGLE_CLOUD_LOCATION", "europe-west4");
        var gemini = only("gemini-cli");
        assertAccount(gemini, Kind.CLOUD, "~/.gemini/settings.json");
        assertEquals("proj-1", gemini.project());
        assertEquals("europe-west4", gemini.region());
    }

    @Test
    void geminiApiKeyChosenButMissingIsNotSignedIn() throws IOException {
        write(".gemini/settings.json", "{\"security\": {\"auth\": {\"selectedType\": \"gemini-api-key\"}}}");
        assertFalse(only("gemini-cli").signedIn());
    }

    // ---- Antigravity CLI ----

    @Test
    void antigravityInGeminiApiKeyMode() throws IOException {
        write(".gemini/antigravity-cli/settings.json", "{\"modelProvider\": \"gemini\"}");
        env.put("GEMINI_API_KEY", SENTINEL);
        assertAccount(only("antigravity"), Kind.API_KEY, "$GEMINI_API_KEY");
    }

    @Test
    void antigravityGoogleSignInAndModelLabel() throws IOException {
        write(".gemini/antigravity-cli/settings.json", "{\"model\": \"Gemini 4 Neon (Medium)\"}");
        write(".gemini/antigravity-cli/antigravity-oauth-token", SENTINEL);
        var agy = only("antigravity");
        assertAccount(agy, Kind.SUBSCRIPTION, "~/.gemini/antigravity-cli/antigravity-oauth-token");
        assertEquals("google", agy.provider());
        assertEquals("Gemini 4 Neon (Medium)", agy.model());
        assertFalse(agy.toString().contains(SENTINEL));
    }

    @Test
    void antigravityModelHoldingASecretIsDropped() throws IOException {
        write(".gemini/antigravity-cli/settings.json", "{\"model\": \"" + SENTINEL + "\"}");
        assertNull(only("antigravity").model());
    }

    @Test
    void antigravityOtherwiseInstalledOnly() throws IOException {
        onPath("agy");
        var agy = only("antigravity");
        assertFalse(agy.signedIn());
        assertEquals(Kind.UNKNOWN, agy.kind());
    }

    @Test
    void geminiReadsOnlyTheFirstEnvFile() throws IOException {
        write(".gemini/settings.json", "{\"security\": {\"auth\": {\"selectedType\": \"gemini-api-key\"}}}");
        write(".gemini/.env", "GEMINI_MODEL=gemini-2.5-pro\n");
        write(".env", "GEMINI_API_KEY=" + SENTINEL + "\n");
        var gemini = only("gemini-cli");
        assertFalse(gemini.signedIn(), gemini.toString());
        assertEquals("gemini-2.5-pro", gemini.model());
    }

    // ---- Pi ----

    @Test
    void piSubscriptionOfItsDefaultProvider() throws IOException {
        write(".pi/agent/settings.json", "{\"defaultProvider\": \"anthropic\", \"defaultModel\": \"claude-sonnet-4-5\"}");
        write(".pi/agent/auth.json", "{\"openai\": {\"type\": \"api_key\", \"key\": \"" + SENTINEL + "\"},"
                + " \"anthropic\": {\"type\": \"oauth\", \"refresh\": \"" + SENTINEL + "\"}}");
        var pi = only("pi");
        assertAccount(pi, Kind.SUBSCRIPTION, "~/.pi/agent/auth.json");
        assertEquals("anthropic", pi.provider());
        assertEquals("claude-sonnet-4-5", pi.model());
    }

    @Test
    void piApiKey() throws IOException {
        write(".pi/agent/auth.json", "{\"openai\": {\"type\": \"api_key\", \"key\": \"" + SENTINEL + "\"}}");
        var pi = only("pi");
        assertAccount(pi, Kind.API_KEY, "~/.pi/agent/auth.json");
        assertEquals("openai", pi.provider());
    }

    @Test
    void piOnVertex() throws IOException {
        write(".pi/agent/settings.json", "{\"defaultProvider\": \"google-vertex\"}");
        env.put("GOOGLE_CLOUD_PROJECT", "proj-2");
        env.put("GOOGLE_CLOUD_LOCATION", "us-central1");
        var pi = only("pi");
        assertAccount(pi, Kind.CLOUD, "$GOOGLE_CLOUD_PROJECT");
        assertEquals("proj-2", pi.project());
        assertEquals("us-central1", pi.region());
    }

    @Test
    void piDoesNotReportAnotherProvidersEntryForItsDefault() throws IOException {
        write(".pi/agent/settings.json", "{\"defaultProvider\": \"openai\"}");
        write(".pi/agent/auth.json", "{\"anthropic\": {\"type\": \"oauth\", \"refresh\": \"" + SENTINEL + "\"}}");
        env.put("OPENAI_API_KEY", SENTINEL);
        var pi = only("pi");
        assertAccount(pi, Kind.API_KEY, "$OPENAI_API_KEY");
        assertEquals("openai", pi.provider());
    }

    @Test
    void piOnVertexWithAProjectTooLongToShowIsStillSignedIn() throws IOException {
        write(".pi/agent/settings.json", "{\"defaultProvider\": \"google-vertex\"}");
        env.put("GOOGLE_CLOUD_PROJECT", "mycompanyproductionproject");
        var pi = only("pi");
        assertAccount(pi, Kind.CLOUD, "$GOOGLE_CLOUD_PROJECT");
        assertNull(pi.project());
    }

    // ---- opencode ----

    @Test
    void opencodeSubscriptionOfItsModelsProvider() throws IOException {
        write(".config/opencode/opencode.json", "{\"model\": \"anthropic/claude-sonnet-4-5\"}");
        write(".local/share/opencode/auth.json", "{\"openai\": {\"type\": \"api\", \"key\": \"" + SENTINEL + "\"},"
                + " \"anthropic\": {\"type\": \"oauth\", \"access\": \"" + SENTINEL + "\"}}");
        var opencode = only("opencode");
        assertAccount(opencode, Kind.SUBSCRIPTION, "~/.local/share/opencode/auth.json");
        assertEquals("anthropic", opencode.provider());
        assertEquals("anthropic/claude-sonnet-4-5", opencode.model());
    }

    @Test
    void opencodeModelThroughARouterKeepsItsName() throws IOException {
        write(".config/opencode/opencode.json", "{\"model\": \"openrouter/anthropic/claude-sonnet-4\"}");
        write(".local/share/opencode/auth.json", "{\"anthropic\": {\"type\": \"oauth\"}}");
        var opencode = only("opencode");
        assertEquals("openrouter/anthropic/claude-sonnet-4", opencode.model());
        assertFalse(opencode.signedIn(), "anthropic's entry is not openrouter's: " + opencode);
    }

    @Test
    void opencodeApiKeyUnderXdgDataHome() throws IOException {
        write("data/opencode/auth.json", "{\"openai\": {\"type\": \"api\", \"key\": \"" + SENTINEL + "\"}}");
        env.put("XDG_DATA_HOME", home.resolve("data").toString());
        assertAccount(only("opencode"), Kind.API_KEY, "~/data/opencode/auth.json");
    }

    // ---- GitHub Copilot CLI ----

    @Test
    void copilotSignedInUser() throws IOException {
        write(".copilot/config.json", "{\"logged_in_users\": [{\"host\": \"https://github.com\", \"login\": \"someone\"}]}");
        write(".copilot/settings.json", "{\"model\": \"claude-sonnet-4.5\"}");
        var copilot = only("copilot");
        assertAccount(copilot, Kind.SUBSCRIPTION, "~/.copilot/config.json");
        assertEquals("claude-sonnet-4.5", copilot.model());
        assertFalse(copilot.toString().contains("someone"));
    }

    @Test
    void copilotTokenInTheEnvironment() throws IOException {
        onPath("copilot");
        env.put("COPILOT_GITHUB_TOKEN", SENTINEL);
        assertAccount(only("copilot"), Kind.SUBSCRIPTION, "$COPILOT_GITHUB_TOKEN");
    }

    // ---- Bob Shell ----

    @Test
    void bobApiKey() throws IOException {
        onPath("bob");
        env.put("BOBSHELL_API_KEY", SENTINEL);
        assertAccount(only("bob"), Kind.API_KEY, "$BOBSHELL_API_KEY");
    }

    @Test
    void bobInstalledOnly() throws IOException {
        Files.createDirectories(home.resolve(".bob"));
        assertFalse(only("bob").signedIn());
    }

    // ---- What is not found, and what is never returned ----

    @Test
    void anUnparsableFileCountsAsNotFoundAndLeavesTheOthers() throws IOException {
        write(".claude.json", "{not json");
        write(".codex/auth.json", "[\"an array, not an object\"]");
        write(".copilot/config.json", "{\"logged_in_users\": [{}]}");
        var found = probe();
        assertEquals(List.of("copilot"), found.stream().map(HostAgent::agent).toList());
    }

    @Test
    void anUnreadableFileCountsAsNotFound() throws IOException {
        write(".pi/agent/auth.json", "{\"anthropic\": {\"type\": \"oauth\"}}");
        var auth = home.resolve(".pi/agent/auth.json");
        Files.setPosixFilePermissions(auth, PosixFilePermissions.fromString("---------"));
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(auth), "running as root");
            assertEquals(List.of(), probe());
        } finally {
            Files.setPosixFilePermissions(auth, PosixFilePermissions.fromString("rw-------"));
        }
        // a directory where a file belongs is not readable either
        write(".gemini/settings.json/inside", "");
        assertTrue(probe().stream().noneMatch(a -> a.agent().equals("gemini-cli")));
    }

    @Test
    void aSecretWhereAModelBelongsIsNotReturned() throws IOException {
        write(".claude.json", "{\"oauthAccount\": {}}");
        write(".claude/settings.json", "{\"model\": \"" + SENTINEL + "\"}");
        write(".codex/auth.json", "{\"tokens\": {}}");
        write(".codex/config.toml", "model = \"sk-proj-" + SENTINEL + "\"\n");
        write(".config/opencode/opencode.json", "{\"model\": \"anthropic/key with spaces\"}");
        write(".local/share/opencode/auth.json", "{\"anthropic\": {\"type\": \"oauth\"}}");
        env.put("GOOGLE_CLOUD_PROJECT", SENTINEL);
        write(".pi/agent/settings.json", "{\"defaultProvider\": \"google-vertex\"}");
        var found = probe();
        assertEquals(4, found.size(), found.toString());
        assertTrue(found.stream().allMatch(a -> a.model() == null && a.project() == null), found.toString());
    }

    @Test
    void plainKeepsNamesAndDropsTokens() {
        for (var name : List.of("opus", "claude-opus-4-1[1m]", "anthropic/claude-sonnet-4-5", "us-east5", "gpt-5.1-codex",
                "claude-sonnet-4-5@20250929")) {
            assertEquals(name, HostAgentProbe.plain(name));
        }
        for (var value : List.of(SENTINEL, "has a space", "\"quoted\"", "x".repeat(200), "AIza" + "0".repeat(35), "")) {
            assertNull(HostAgentProbe.plain(value), value);
        }
    }

    @Test
    void doctorNotesEachAgentAndNeverWarns() throws IOException {
        assertEquals(List.of("Coding agents on this host"),
                DoctorCommand.hostAgentFindings(List.of()).stream().map(DoctorCommand.Finding::label).toList());
        onPath("codex");
        write(".claude.json", "{\"oauthAccount\": {}}");
        var findings = DoctorCommand.hostAgentFindings(probe());
        assertEquals(List.of("Claude Code on this host", "Codex CLI on this host"),
                findings.stream().map(DoctorCommand.Finding::label).toList());
        assertTrue(findings.stream().allMatch(f -> f.status() == DoctorCommand.Status.NOTE));
        assertEquals("(signed in with a subscription (~/.claude.json))", findings.getFirst().detail());
        assertEquals("(installed, no sign-in found)", findings.get(1).detail());
    }

    @Test
    void thePlanNoteNamesEveryAgent() throws IOException {
        write(".claude.json", "{\"oauthAccount\": {}}");
        write(".claude/settings.json", "{\"model\": \"sonnet\"}");
        env.put("BOBSHELL_API_KEY", SENTINEL);
        onPath("bob");
        assertEquals("Coding agents on this host: Claude Code signed in with a subscription, model sonnet (~/.claude.json);"
                + " Bob Shell signed in with an API key ($BOBSHELL_API_KEY).", HostAgentProbe.note(probe()));
    }
}
