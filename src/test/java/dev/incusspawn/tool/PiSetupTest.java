package dev.incusspawn.tool;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
    void installSetsAnthropicApiKeyPlaceholder() {
        var incus = mock(IncusClient.class);
        when(incus.shellExecInteractive(anyString(), any(String[].class))).thenReturn(0);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new PiSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("ANTHROPIC_API_KEY=sk-ant-placeholder"));
    }

    @Test
    void installSetsOauthPlaceholderWhenHostHasOauthToken() {
        var config = SpawnConfig.load();
        config.getClaude().setOauthToken("sk-ant-oat01-real-token-on-host");
        config.save();

        var incus = mock(IncusClient.class);
        when(incus.shellExecInteractive(anyString(), any(String[].class))).thenReturn(0);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new PiSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        // Pi's own Anthropic provider detects an OAuth token by its "sk-ant-oat" prefix
        // and builds the Bearer/identity-header request itself — Pi never sees the real
        // host token, just a placeholder shaped like one.
        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("ANTHROPIC_OAUTH_TOKEN=sk-ant-oat01-placeholder"));
        verify(incus, never()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("ANTHROPIC_API_KEY=sk-ant-placeholder"));
    }

    @Test
    void installSetsSkipVersionCheck() {
        var incus = mock(IncusClient.class);
        when(incus.shellExecInteractive(anyString(), any(String[].class))).thenReturn(0);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new PiSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("PI_SKIP_VERSION_CHECK=1"));
    }

    @Test
    void doesNotSetVertexSpecificEnvVars() {
        var incus = mock(IncusClient.class);
        when(incus.shellExecInteractive(anyString(), any(String[].class))).thenReturn(0);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new PiSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus, never()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("CLAUDE_CODE_USE_VERTEX"));
        verify(incus, never()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("ANTHROPIC_VERTEX_PROJECT_ID"));
    }
}
