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
        when(incus.shellExecInteractive(anyString(), any(String[].class))).thenReturn(0);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new PiSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).shellExecInteractive(eq(CONTAINER),
                eq("npm"), eq("install"), eq("-g"), eq("--ignore-scripts"), eq("--loglevel=error"), eq("@earendil-works/pi-coding-agent"));
    }

    @Test
    void installWritesSettingsJson() {
        var incus = mock(IncusClient.class);
        when(incus.shellExecInteractive(anyString(), any(String[].class))).thenReturn(0);
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
    void envEntriesDoesNotSetVertexSpecificVars() {
        var entries = new PiSetup().envEntries(Map.of());

        assertFalse(entries.stream().anyMatch(e ->
                "CLAUDE_CODE_USE_VERTEX".equals(e.getName())));
        assertFalse(entries.stream().anyMatch(e ->
                "ANTHROPIC_VERTEX_PROJECT_ID".equals(e.getName())));
    }
}
