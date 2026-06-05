package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PiSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String CONTAINER = "test-container";

    @Test
    void nameIsPi() {
        assertEquals("pi", new PiSetup().name());
    }

    @Test
    void declaresRequiredPackages() {
        // fd-find and ripgrep are pre-installed so pi's tools-manager finds them
        // in PATH and skips downloading them on first run.
        assertEquals(java.util.List.of("nodejs", "npm", "fd-find", "ripgrep"), new PiSetup().packages());
    }

    @Test
    void installRunsNpmInstallGlobal() {
        var incus = mock(IncusClient.class);
        when(incus.shellExecInteractive(anyString(), any(String[].class))).thenReturn(0);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new PiSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).shellExecInteractive(eq(CONTAINER),
                eq("npm"), eq("install"), eq("-g"), eq("@earendil-works/pi-coding-agent"));
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
