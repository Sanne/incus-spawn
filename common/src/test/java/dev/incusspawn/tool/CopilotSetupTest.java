package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CopilotSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String CONTAINER = "test-container";

    @Test
    void installRunsNpmInstallGlobalThenVerifiesTheBinary() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), any(String[].class))).thenReturn(OK);

        new CopilotSetup().install(new Container(incus, CONTAINER), Map.of());

        var order = inOrder(incus);
        order.verify(incus).shellExec(eq(CONTAINER),
                eq("npm"), eq("install"), eq("-g"), eq("--ignore-scripts"), eq("--loglevel=error"), eq("@github/copilot"));
        order.verify(incus).execInContainer(CONTAINER, "agentuser", "copilot --version");
    }

    @Test
    void installFailsWhenNpmSkippedThePlatformBinary() {
        // @github/copilot's loader exits 1 when its optional platform package is missing,
        // which npm install -g does not report as a failure (#808).
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), any(String[].class))).thenReturn(
                new IncusClient.ExecResult(1, "", "GitHub Copilot CLI: no platform package found."));

        var e = assertThrows(IncusException.class,
                () -> new CopilotSetup().install(new Container(incus, CONTAINER), Map.of()));
        assertTrue(e.getMessage().contains("copilot --version"), e.getMessage());
    }
}
