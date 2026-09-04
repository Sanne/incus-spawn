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
                eq("npm"), eq("install"), eq("-g"), eq("--ignore-scripts"), eq("--loglevel=error"), eq("@earendil-works/pi-coding-agent"));
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
    void installWritesCustomProviderAndModel() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new PiSetup().install(new Container(incus, CONTAINER),
                Map.of("provider", "vertex", "model", "gemini-3.7-flash"));

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains("\"defaultProvider\": \"vertex\"") &&
                        arg.contains("\"defaultModel\": \"gemini-3.7-flash\"")));
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

    @Test
    void envEntriesSetsGcpVarsForVertexProviderWhenVertexConfigured() {
        var config = SpawnConfig.load();
        config.getClaude().setUseVertex(true);
        config.getClaude().setVertexProjectId("my-project");
        config.getClaude().setCloudMlRegion("us-central1");
        config.save();

        var entries = new PiSetup().envEntries(Map.of("provider", "vertex"));

        assertTrue(entries.stream().anyMatch(e ->
                        "GOOGLE_CLOUD_PROJECT".equals(e.getName()) && "my-project".equals(e.getValue())),
                "Should set GOOGLE_CLOUD_PROJECT from Vertex config");
        assertTrue(entries.stream().anyMatch(e ->
                        "GOOGLE_CLOUD_LOCATION".equals(e.getName()) && "us-central1".equals(e.getValue())),
                "Should set GOOGLE_CLOUD_LOCATION from Vertex config");
    }

    @Test
    void envEntriesSetsGcpVarsForGoogleProviderWhenVertexConfigured() {
        var config = SpawnConfig.load();
        config.getClaude().setUseVertex(true);
        config.getClaude().setVertexProjectId("my-project");
        config.getClaude().setCloudMlRegion("europe-west1");
        config.save();

        var entries = new PiSetup().envEntries(Map.of("provider", "google"));

        assertTrue(entries.stream().anyMatch(e ->
                        "GOOGLE_CLOUD_PROJECT".equals(e.getName()) && "my-project".equals(e.getValue())),
                "google provider should also set GOOGLE_CLOUD_PROJECT from Vertex config");
    }

    @Test
    void envEntriesSetsSkipVersionCheckForNonAnthropicProvider() {
        var entries = new PiSetup().envEntries(Map.of("provider", "vertex"));

        assertTrue(entries.stream().anyMatch(e ->
                "PI_SKIP_VERSION_CHECK".equals(e.getName()) && "1".equals(e.getValue())),
                "PI_SKIP_VERSION_CHECK should be set regardless of provider");
    }

    @Test
    void parametersDeclaresProviderAndModel() {
        var params = new PiSetup().parameters();

        assertTrue(params.containsKey("provider"), "Should declare provider parameter");
        assertTrue(params.containsKey("model"), "Should declare model parameter");
        assertEquals("anthropic", params.get("provider").getDefault());
        assertEquals("claude-sonnet-4-6", params.get("model").getDefault());
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
