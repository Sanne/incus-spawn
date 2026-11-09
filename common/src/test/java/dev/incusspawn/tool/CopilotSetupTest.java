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
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);

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

        try (var ignored = new NpmWaits()) {
            var e = assertThrows(IncusException.class,
                    () -> new CopilotSetup().install(new Container(incus, CONTAINER), Map.of()));
            assertTrue(e.getMessage().contains("@github/copilot-linux-x64"), e.getMessage());
            verify(incus, never()).shellExec(eq(CONTAINER), eq("sh"), eq("-c"),
                    argThat(arg -> arg.contains("/home/agentuser/.copilot/config.json")));
        }
    }

    @Test
    void settingsJsonIsOmittedWhenNoParameterIsSet() {
        assertNull(CopilotSetup.settingsJson(Map.of()));
    }

    @Test
    void settingsJsonMapsParametersToCopilotSettingKeys() {
        var json = CopilotSetup.settingsJson(Map.of(
                "model", "claude-opus-5.5", "effort", "medium", "context", "long_context"));

        assertEquals("""
                {
                  "model": "claude-opus-5.5",
                  "effortLevel": "medium",
                  "contextTier": "long_context"
                }
                """, json);
    }

    @Test
    void reconfigureWithoutParametersRemovesTheParentsSettings() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new CopilotSetup().reconfigure(new Container(incus, CONTAINER), Map.of());

        assertTrue(invocationArguments(incus).anyMatch(
                a -> a.contains("rm -f /home/agentuser/.copilot/settings.json")));
    }

    @Test
    void reconfigureWritesSettingsWhenParametersAreSet() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new CopilotSetup().reconfigure(new Container(incus, CONTAINER), Map.of("effort", "high"));

        assertTrue(invocationArguments(incus).anyMatch(
                a -> a.contains("/home/agentuser/.copilot/settings.json")
                        && a.contains("\"effortLevel\": \"high\"")));
    }

    private static java.util.stream.Stream<String> invocationArguments(IncusClient incus) {
        return mockingDetails(incus).getInvocations().stream()
                .flatMap(i -> java.util.Arrays.stream(i.getRawArguments()))
                .flatMap(a -> a instanceof Object[] arr ? java.util.Arrays.stream(arr) : java.util.stream.Stream.of(a))
                .map(String::valueOf);
    }
}
