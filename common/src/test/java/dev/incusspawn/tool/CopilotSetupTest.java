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
    void installChecksThePlatformPackageAfterNpmInstall() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), any(String[].class))).thenReturn(OK);

        new CopilotSetup().install(new Container(incus, CONTAINER), Map.of());

        var order = inOrder(incus);
        order.verify(incus).shellExec(eq(CONTAINER),
                eq("npm"), eq("install"), eq("-g"), eq("--ignore-scripts"), eq("--loglevel=verbose"), eq("@github/copilot"));
        order.verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"),
                argThat(arg -> arg.contains("npm root -g") && arg.contains("'@github/copilot-'")));
        order.verify(incus).execInContainer(CONTAINER, "agentuser", "command -v copilot");
    }

    @Test
    void installFailsBeforeConfiguringWhenThePlatformPackageStaysMissing() {
        // @github/copilot's loader exits 1 when its optional platform package is missing,
        // which npm install -g does not report as a failure (#808).
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), argThat(arg -> arg.contains("npm root -g"))))
                .thenReturn(new IncusClient.ExecResult(3, "@github/copilot-linux-x64\n", ""));

        var e = assertThrows(IncusException.class,
                () -> new CopilotSetup().install(new Container(incus, CONTAINER), Map.of()));
        assertTrue(e.getMessage().contains("@github/copilot-linux-x64"), e.getMessage());
        verify(incus, never()).shellExec(eq(CONTAINER), eq("sh"), eq("-c"),
                argThat(arg -> arg.contains("/home/agentuser/.copilot/config.json")));
    }
}
