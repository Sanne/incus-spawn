package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PiSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String CONTAINER = "test-container";

    @TempDir
    Path tempDir;

    private String originalUserHome;

    @BeforeEach
    void setup() {
        // configureAuth() reads SpawnConfig.load(); point it at an isolated, empty
        // config dir so these tests don't depend on (or pollute) the real ~/.config.
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void nameIsPi() {
        assertEquals("pi", new PiSetup().name());
    }

    @Test
    void declaresRequiredPackages() {
        // fd-find and ripgrep are pre-installed so pi's tools-manager finds them
        // in PATH and skips downloading them on first run.
        assertEquals(java.util.List.of("nodejs", "fd-find", "ripgrep"), new PiSetup().packages());
    }

    @Test
    void installRunsNpmInstallGlobal() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new PiSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).shellExec(eq(CONTAINER),
                eq("npm"), eq("install"), eq("-g"), eq("--ignore-scripts"), eq("--loglevel=verbose"), eq("@earendil-works/pi-coding-agent"));
    }

    @Test
    void installWritesSettingsJsonWithDefaults() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new PiSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains("enableInstallTelemetry") &&
                        arg.contains("quietStartup") &&
                        arg.contains("defaultProvider") &&
                        arg.contains("anthropic") &&
                        arg.contains("defaultModel") &&
                        arg.contains("claude-sonnet-4-6") &&
                        arg.contains("defaultThinkingLevel") &&
                        arg.contains("medium")));
    }

    @Test
    void installPutsIsxVersionAndInstanceNameInFooter() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new PiSetup().install(new Container(incus, CONTAINER), Map.of());

        verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), argThat(arg ->
                arg.contains("cat > '/etc/isx/pi/isx-status.ts'")
                        && arg.contains("ctx.ui.setStatus(\"isx\"")
                        && arg.contains("Running unconstrained in isx ${version}")
                        && arg.contains("process.env.ISX_VERSION")
                        && arg.contains("process.env.ISX_CONTAINER")
                        // Pi exits when an extension throws on load; the footer must never cost Pi.
                        && arg.contains("try {") && arg.contains("} catch {}")));
        verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), argThat(arg ->
                arg.contains("/home/agentuser/.pi/agent/settings.json")
                        && arg.contains("\"extensions\": [\"/etc/isx/pi/isx-status.ts\"]")));
    }

    @Test
    void reconfigureKeepsFooterExtension() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new PiSetup().reconfigure(new Container(incus, CONTAINER), Map.of("model", "gpt-4.1"));

        verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), argThat(arg ->
                arg.contains("cat > '/etc/isx/pi/isx-status.ts'")));
        verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), argThat(arg ->
                arg.contains("\"defaultModel\": \"gpt-4.1\"")
                        && arg.contains("\"extensions\": [\"/etc/isx/pi/isx-status.ts\"]")));
    }

    @Test
    void installLeavesModelToPiForAnotherProviderWithoutModel() {
        // Pi picks a model the provider serves; claude-sonnet-4-6 sent to Vertex's Gemini path is a 404 (#1918).
        // Pi's own provider names, literally: isx's vertex is Pi's google-vertex
        var written = Map.of("vertex", "google-vertex", "google", "google",
                "google-vertex", "google-vertex", "openai", "openai");
        written.forEach((provider, piProvider) -> {
            var incus = mock(IncusClient.class);
            when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
            var params = ParameterResolver.resolve(new PiSetup().parameters(), Map.of("provider", provider));

            new PiSetup().install(new Container(incus, CONTAINER), params.resolvedValues());

            verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), argThat(arg ->
                    arg.contains("/home/agentuser/.pi/agent/settings.json")
                            && arg.contains("\"defaultProvider\": \"" + piProvider + "\"")
                            && !arg.contains("defaultModel")));
        });
    }

    /** A model the template names is kept for any provider, even the anthropic default; isx never checks it. */
    @Test
    void installKeepsExplicitClaudeModelForAnotherProvider() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        // Pi's opencode provider serves claude-sonnet-4-6 under that very id
        var params = ParameterResolver.resolve(new PiSetup().parameters(),
                Map.of("provider", "opencode", "model", "claude-sonnet-4-6"));

        new PiSetup().install(new Container(incus, CONTAINER), params.resolvedValues());

        verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), argThat(arg ->
                arg.contains("\"defaultModel\": \"claude-sonnet-4-6\"")));
    }

    @Test
    void installWritesCustomProviderAndModel() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new PiSetup().install(new Container(incus, CONTAINER),
                Map.of("provider", "vertex", "model", "gemini-3.7-flash"));

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains("\"defaultProvider\": \"google-vertex\"") &&
                        arg.contains("\"defaultModel\": \"gemini-3.7-flash\"")));
    }

    /** Pi has no provider named vertex: isx's name for it is written as Pi's own. */
    @Test
    void installWritesPisOwnNameForEitherVertexSpelling() {
        for (var provider : java.util.List.of("vertex", "google-vertex")) {
            var incus = mock(IncusClient.class);
            when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
            new PiSetup().install(new Container(incus, CONTAINER), Map.of("provider", provider));
            verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"),
                    argThat(arg -> arg.contains("\"defaultProvider\": \"google-vertex\"")));
        }
    }

    @Test
    void envEntriesSetsAnthropicApiKeyPlaceholderByDefault() {
        var entries = new PiSetup().envEntries(Map.of());

        assertTrue(entries.stream().anyMatch(e ->
                "ANTHROPIC_API_KEY".equals(e.getName()) && "sk-ant-placeholder".equals(e.getValue())));
    }

    @Test
    void envEntriesSetsOauthPlaceholderWhenHostHasOauthToken() {
        var config = SpawnConfig.load();
        config.getClaude().setOauthToken("sk-ant-oat01-real-token-on-host");
        config.save();

        var entries = new PiSetup().envEntries(Map.of());

        assertTrue(entries.stream().anyMatch(e ->
                        "ANTHROPIC_OAUTH_TOKEN".equals(e.getName())),
                "Should set ANTHROPIC_OAUTH_TOKEN in OAuth mode");
        assertFalse(entries.stream().anyMatch(e ->
                        "ANTHROPIC_API_KEY".equals(e.getName())),
                "Should not set ANTHROPIC_API_KEY in OAuth mode");
    }

    @Test
    void envEntriesSetsSkipVersionCheck() {
        var entries = new PiSetup().envEntries(Map.of());

        assertTrue(entries.stream().anyMatch(e ->
                "PI_SKIP_VERSION_CHECK".equals(e.getName()) && "1".equals(e.getValue())));
    }

    @Test
    void envEntriesDoesNotSetVertexSpecificVarsForAnthropicProvider() {
        var entries = new PiSetup().envEntries(Map.of());

        assertFalse(entries.stream().anyMatch(e ->
                "CLAUDE_CODE_USE_VERTEX".equals(e.getName())));
        assertFalse(entries.stream().anyMatch(e ->
                "ANTHROPIC_VERTEX_PROJECT_ID".equals(e.getName())));
    }

    @Test
    void envEntriesSkipsAnthropicKeysForVertexProvider() {
        var entries = new PiSetup().envEntries(Map.of("provider", "vertex"));

        assertFalse(entries.stream().anyMatch(e ->
                        "ANTHROPIC_API_KEY".equals(e.getName())),
                "Should not set ANTHROPIC_API_KEY for vertex provider");
        assertFalse(entries.stream().anyMatch(e ->
                        "ANTHROPIC_OAUTH_TOKEN".equals(e.getName())),
                "Should not set ANTHROPIC_OAUTH_TOKEN for vertex provider");
    }

    @Test
    void envEntriesSkipsAnthropicKeysForGoogleProvider() {
        var entries = new PiSetup().envEntries(Map.of("provider", "google"));

        assertFalse(entries.stream().anyMatch(e ->
                        "ANTHROPIC_API_KEY".equals(e.getName())),
                "Should not set ANTHROPIC_API_KEY for google provider");
    }

    /**
     * Pi's google-vertex provider takes an API key, which the proxy swaps for the host's gcloud
     * token: the variable carries the claude namespace's proof, filled at login.
     */
    @Test
    void envEntriesSetsAVertexKeyPlaceholderForEitherVertexSpelling() {
        for (var provider : java.util.List.of("vertex", "google-vertex")) {
            var entries = new PiSetup().envEntries(Map.of("provider", provider));
            assertTrue(entries.stream().anyMatch(e ->
                            "GOOGLE_CLOUD_API_KEY".equals(e.getName()) && "AIzaplaceholder".equals(e.getValue())),
                    provider + ": " + entries);
            assertFalse(entries.stream().anyMatch(e -> "GEMINI_API_KEY".equals(e.getName())), provider);
        }
    }

    @Test
    void theVertexKeyCarriesTheClaudeNamespacesProof() {
        var placeholder = new PiSetup().placeholders().stream()
                .filter(p -> p.env().equals("GOOGLE_CLOUD_API_KEY")).findFirst().orElseThrow();
        assertEquals("AIza", placeholder.prefix());
        assertEquals(SpawnConfig.ClaudeConfig.NAMESPACE, placeholder.namespace());
        assertEquals("", placeholder.problem());
    }

    /** Pi's google provider is the Gemini API: it spends the gemini namespace's key, as Gemini CLI does. */
    @Test
    void envEntriesSetsTheGeminiPlaceholderForGoogleProvider() {
        var entries = new PiSetup().envEntries(Map.of("provider", "google"));
        assertTrue(entries.stream().anyMatch(e ->
                "GEMINI_API_KEY".equals(e.getName()) && "gemini-placeholder".equals(e.getValue())), entries.toString());
        assertFalse(entries.stream().anyMatch(e -> e.getName().startsWith("GOOGLE_CLOUD_")), entries.toString());
        assertEquals(java.util.Set.of("gemini"), new PiSetup().credentialNamespaces(Map.of("provider", "google")));
        assertTrue(new PiSetup().credentialNamespaces().contains("gemini"));
    }

    @Test
    void bothVertexSpellingsSpendTheClaudeAccount() {
        for (var provider : java.util.List.of("vertex", "google-vertex")) {
            assertEquals(java.util.Set.of(SpawnConfig.ClaudeConfig.NAMESPACE),
                    new PiSetup().credentialNamespaces(Map.of("provider", provider)), provider);
        }
    }

    @Test
    void envEntriesSetsOpenaiKeyForOpenaiProvider() {
        var entries = new PiSetup().envEntries(Map.of("provider", "openai"));

        assertTrue(entries.stream().anyMatch(e ->
                        "OPENAI_API_KEY".equals(e.getName()) && "sk-placeholder".equals(e.getValue())),
                "Should set OPENAI_API_KEY for openai provider");
    }

    @Test
    void envEntriesSkipsAnthropicKeysForOpenaiProvider() {
        var entries = new PiSetup().envEntries(Map.of("provider", "openai"));

        assertFalse(entries.stream().anyMatch(e ->
                        "ANTHROPIC_API_KEY".equals(e.getName())),
                "Should not set ANTHROPIC_API_KEY for openai provider");
        assertFalse(entries.stream().anyMatch(e ->
                        "ANTHROPIC_OAUTH_TOKEN".equals(e.getName())),
                "Should not set ANTHROPIC_OAUTH_TOKEN for openai provider");
    }

    @Test
    void envEntriesSkipsVertexVarsForOpenaiProvider() {
        var entries = new PiSetup().envEntries(Map.of("provider", "openai"));

        assertFalse(entries.stream().anyMatch(e ->
                        "GOOGLE_CLOUD_PROJECT".equals(e.getName())),
                "Should not set GOOGLE_CLOUD_PROJECT for openai provider");
    }

    @Test
    void envEntriesSetsSkipVersionCheckForOpenaiProvider() {
        var entries = new PiSetup().envEntries(Map.of("provider", "openai"));

        assertTrue(entries.stream().anyMatch(e ->
                        "PI_SKIP_VERSION_CHECK".equals(e.getName()) && "1".equals(e.getValue())),
                "PI_SKIP_VERSION_CHECK should be set for openai provider");
    }

    @Test
    void installWritesOpenaiProviderAndModel() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new PiSetup().install(new Container(incus, CONTAINER),
                Map.of("provider", "openai", "model", "gpt-4.1"));

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains("\"defaultProvider\": \"openai\"") &&
                        arg.contains("\"defaultModel\": \"gpt-4.1\"")));
    }

    @Test
    void envEntriesSetsSkipVersionCheckForNonAnthropicProvider() {
        var entries = new PiSetup().envEntries(Map.of("provider", "vertex"));

        assertTrue(entries.stream().anyMatch(e ->
                "PI_SKIP_VERSION_CHECK".equals(e.getName()) && "1".equals(e.getValue())),
                "PI_SKIP_VERSION_CHECK should be set regardless of provider");
    }

    @Test
    void reconfigureSkipsInstallAndWritesSettings() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new PiSetup().reconfigure(new Container(incus, CONTAINER),
                Map.of("provider", "google", "model", "gemini-3.5-flash"));

        verify(incus, never()).shellExec(eq(CONTAINER),
                eq("npm"), any(), any(), any(), any(), any());
        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains("\"defaultProvider\": \"google\"") &&
                        arg.contains("\"defaultModel\": \"gemini-3.5-flash\"")));
    }

    @Test
    void parametersDeclaresProviderAndModel() {
        var params = new PiSetup().parameters();

        assertTrue(params.containsKey("provider"), "Should declare provider parameter");
        assertTrue(params.containsKey("model"), "Should declare model parameter");
        assertEquals("anthropic", params.get("provider").getDefault());
        assertNull(params.get("model").getDefault(), "model has no declared default: Pi picks one per provider");
        assertTrue(params.get("provider").isReconfigurable());
        assertTrue(params.get("model").isReconfigurable());
    }

    @Test
    void parametersHavePatternValidation() {
        var params = new PiSetup().parameters();

        assertNotNull(params.get("provider").getPattern(), "provider should have pattern validation");
        assertNotNull(params.get("model").getPattern(), "model should have pattern validation");
    }
}
