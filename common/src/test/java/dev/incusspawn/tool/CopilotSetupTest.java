package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import io.hyperfoil.tools.jjq.value.JqValues;
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
                    argThat(arg -> arg.contains("/home/agentuser/.copilot/settings.json")));
        }
    }

    @Test
    void settingsJsonHoldsOnlyThePermissionModeWhenNoParameterIsSet() {
        assertEquals("""
                {
                  "defaultPermissionMode": "allow-all"
                }
                """, CopilotSetup.settingsJson(Map.of()));
    }

    @Test
    void settingsJsonMapsParametersToCopilotSettingKeys() {
        var json = CopilotSetup.settingsJson(Map.of(
                "model", "claude-opus-5.5", "effort", "medium", "context", "long_context"));

        assertEquals("""
                {
                  "defaultPermissionMode": "allow-all",
                  "model": "claude-opus-5.5",
                  "effortLevel": "medium",
                  "contextTier": "long_context"
                }
                """, json);
    }

    @Test
    void reconfigureWithoutParametersDropsTheParentsModelButKeepsAllowAll() {
        // A child that sets no parameter rewrites the file rather than removing it, since it also
        // holds the interactive permission mode (#2066).
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new CopilotSetup().reconfigure(new Container(incus, CONTAINER), Map.of());

        assertTrue(invocationArguments(incus).anyMatch(
                a -> a.contains("/home/agentuser/.copilot/settings.json")
                        && a.contains("\"defaultPermissionMode\": \"allow-all\"")
                        && !a.contains("\"model\"")));
        assertFalse(invocationArguments(incus).anyMatch(a -> a.contains("rm -f")));
    }

    @Test
    void reconfigureWritesSettingsWhenParametersAreSet() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new CopilotSetup().reconfigure(new Container(incus, CONTAINER), Map.of("effort", "high"));

        assertTrue(invocationArguments(incus).anyMatch(
                a -> a.contains("/home/agentuser/.copilot/settings.json")
                        && a.contains("\"defaultPermissionMode\": \"allow-all\"")
                        && a.contains("\"effortLevel\": \"high\"")));
    }

    @Test
    void installWritesAllowAllAndTheModelToTheUserSettingsCopilotReads() {
        // Copilot reads defaultPermissionMode from settings.json; config.json is its own state,
        // where the key leaves interactive sessions in manual approval (#2066). The model
        // settings share the file, so the one write must hold both.
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);

        new CopilotSetup().install(new Container(incus, CONTAINER), Map.of("model", "auto"));

        assertTrue(invocationArguments(incus).anyMatch(
                a -> a.contains("/home/agentuser/.copilot/settings.json")
                        && a.contains("\"defaultPermissionMode\": \"allow-all\"")
                        && a.contains("\"model\": \"auto\"")));
        assertFalse(invocationArguments(incus).anyMatch(
                a -> a.contains("/home/agentuser/.copilot/config.json")));
    }

    @Test
    void refreshInheritedAddsAllowAllToAParentBuiltByAnOlderIsx() {
        // A child that inherits Copilot without reconfiguring it would otherwise keep the old
        // parent's settings.json, which lacks the permission mode (#2066).
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class)))
                .thenReturn(new IncusClient.ExecResult(0, "{\n  \"model\": \"auto\"\n}\n", ""));

        new CopilotSetup().refreshInherited(new Container(incus, CONTAINER));

        assertTrue(invocationArguments(incus).anyMatch(
                a -> a.contains("cat > '/home/agentuser/.copilot/settings.json'")
                        && a.contains("\"defaultPermissionMode\"") && a.contains("\"allow-all\"")
                        && a.contains("\"model\"")));
    }

    @Test
    void withAllowAllKeepsTheParentsSettings() {
        assertEquals(JqValues.parseStrict("{\"defaultPermissionMode\":\"allow-all\"}"),
                JqValues.parseStrict(CopilotSetup.withAllowAll("")));
        // What a parent built with #2065 but before #2066 holds, and shapes a line-based
        // rewrite would have broken: an empty object across lines, an object on one line.
        for (var json : java.util.List.of("{\n  \"model\": \"auto\"\n}\n", "{\"model\":\"auto\"}")) {
            var after = JqValues.parseStrict(CopilotSetup.withAllowAll(json));
            assertEquals("allow-all", after.pathText("defaultPermissionMode", null), json);
            assertEquals("auto", after.pathText("model", null), json);
        }
        assertEquals(JqValues.parseStrict("{\"defaultPermissionMode\":\"allow-all\"}"),
                JqValues.parseStrict(CopilotSetup.withAllowAll("{\n}\n")));
        var twice = CopilotSetup.withAllowAll(CopilotSetup.withAllowAll("{\"model\":\"auto\"}"));
        assertEquals(CopilotSetup.withAllowAll("{\"model\":\"auto\"}"), twice);
        assertThrows(RuntimeException.class, () -> CopilotSetup.withAllowAll("[]"));
    }

    private static java.util.stream.Stream<String> invocationArguments(IncusClient incus) {
        return mockingDetails(incus).getInvocations().stream()
                .flatMap(i -> java.util.Arrays.stream(i.getRawArguments()))
                .flatMap(a -> a instanceof Object[] arr ? java.util.Arrays.stream(arr) : java.util.stream.Stream.of(a))
                .map(String::valueOf);
    }
}
