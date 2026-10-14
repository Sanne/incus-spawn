package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeminiSetupTest {
    private static final String INSTANCE = "test-container";
    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");

    @Test
    void settingsAreValidJsonWithUnattendedDefaultsAndNoAuthSelection() {
        var settings = JqValues.parseStrict(GeminiSetup.settingsJson(Map.of("model", "gemini-4-argon")));
        assertEquals("gemini-4-argon", settings.getField("model").pathText("name", null));
        for (var path : new String[][]{
                {"general", "enableAutoUpdate"}, {"general", "enableAutoUpdateNotification"},
                {"ui", "showHomeDirectoryWarning"},
                {"tools", "sandbox"}, {"security", "toolSandboxing"},
                {"privacy", "usageStatisticsEnabled"}, {"telemetry", "enabled"}}) {
            assertEquals(JqBoolean.FALSE, settings.getField(path[0]).getField(path[1]));
        }
        assertEquals(JqBoolean.FALSE, settings.getField("security").getField("folderTrust").getField("enabled"));
        assertFalse(GeminiSetup.settingsJson(Map.of()).contains("\"auth\""));
    }

    @Test
    void toolIsDiscoverableWithoutCredentialIntegration() {
        var tool = new ToolDefLoader().allToolSetups().get("gemini-cli");
        assertInstanceOf(GeminiSetup.class, tool);
        assertNull(tool.proxy());
        assertTrue(tool.credentialNamespaces().isEmpty());
        assertTrue(tool.placeholders().isEmpty());
        assertTrue(tool.envEntries(Map.of()).isEmpty());
        assertEquals("gemini", tool.actions().getFirst().getCommand());
    }

    @Test
    void modelIsOptionalAndRejectsJsonOrShellInjection() {
        var parameters = new GeminiSetup().parameters();
        assertTrue(ParameterResolver.resolve(parameters, Map.of()).resolvedValues().isEmpty());
        assertTrue(parameters.get("model").isReconfigurable());
        assertFalse(ParameterResolver.resolve(parameters, Map.of("model", "gemini-4-argon")).hasErrors());
        for (var value : new String[]{"gemini/gemini-4-argon", "model\"", "$(id)", "model\nname"}) {
            assertTrue(ParameterResolver.resolve(parameters, Map.of("model", value)).hasErrors(), value);
        }
        assertFalse(GeminiSetup.settingsJson(Map.of()).contains("\"model\""));
        assertTrue(GeminiSetup.settingsJson(Map.of("model", "gemini-4-argon"))
                .contains("\"name\": \"gemini-4-argon\""));
    }

    @Test
    void installWritesUserSettingsAndReconfigureDoesNotReinstall() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        var tool = new GeminiSetup();
        var container = new Container(incus, INSTANCE);
        tool.install(container, Map.of());
        verify(incus).shellExec(eq(INSTANCE), eq("npm"), eq("install"), eq("-g"),
                eq("--ignore-scripts"), eq("--loglevel=verbose"), eq("@google/gemini-cli"));
        verify(incus).shellExec(eq(INSTANCE), eq("sh"), eq("-c"),
                argThat(cmd -> cmd.contains(GeminiSetup.CONFIG_PATH)
                        && cmd.contains("enableAutoUpdate") && cmd.contains("usageStatisticsEnabled")));
        verify(incus).shellExec(eq(INSTANCE), eq("sh"), eq("-c"),
                argThat(cmd -> cmd.contains(GeminiSetup.POLICY_PATH)
                        && cmd.contains("decision = \"allow\"") && cmd.contains("priority = 0")
                        && cmd.contains("modes = [\"default\", \"autoEdit\", \"yolo\"]")));
        clearInvocations(incus);
        tool.reconfigure(container, Map.of("model", "gemini-4-argon"));
        verify(incus, never()).shellExec(eq(INSTANCE),
                argThat((String[] args) -> args.length > 0 && args[0].equals("npm")));
        verify(incus).shellExec(eq(INSTANCE), eq("sh"), eq("-c"),
                argThat(cmd -> cmd.contains(GeminiSetup.CONFIG_PATH) && cmd.contains("gemini-4-argon")));
        verify(incus).shellExec(eq(INSTANCE), eq("sh"), eq("-c"),
                argThat(cmd -> cmd.contains(GeminiSetup.POLICY_PATH)));
    }

    @Test
    void failedInstallDoesNotWriteConfiguration() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class)))
                .thenReturn(new IncusClient.ExecResult(1, "", "npm failed"));
        assertThrows(IncusException.class, () -> new GeminiSetup().install(new Container(incus, INSTANCE), Map.of()));
        verify(incus, never()).shellExec(eq(INSTANCE), eq("sh"), eq("-c"), anyString());
    }
}
