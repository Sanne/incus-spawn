package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeminiSetupTest {
    private static final String INSTANCE = "test-container";
    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");

    @Test
    void settingsSelectApiKeyAuthWithoutStoringTheKey() {
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
        assertEquals("gemini-api-key", settings.getField("security").getField("auth").pathText("selectedType", null));
        assertFalse(GeminiSetup.settingsJson(Map.of()).contains("gemini-placeholder"));
        assertFalse(GeminiSetup.settingsJson(Map.of()).contains("GEMINI_API_KEY"));
    }

    @Test
    void toolDeclaresItsCredentialAndCallTimePlaceholder() {
        var tool = new ToolDefLoader().allToolSetups().get("gemini-cli");
        assertInstanceOf(GeminiSetup.class, tool);
        assertEquals(Set.of("gemini"), tool.credentialNamespaces());
        assertEquals(List.of(new dev.incusspawn.proxy.ProofToken.Placeholder("GEMINI_API_KEY", "gemini-", "gemini")),
                tool.placeholders());
        var env = tool.envEntries(Map.of()).getFirst();
        assertEquals("GEMINI_API_KEY", env.getName());
        assertEquals("gemini-placeholder", env.getValue());
        assertEquals("bash -lc gemini", tool.actions().getFirst().getCommand());
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
        verify(incus).shellExec(INSTANCE, "rm", "-f", GeminiSetup.LEGACY_LOGIN_NOTICE_PATH);
        clearInvocations(incus);
        tool.reconfigure(container, Map.of("model", "gemini-4-argon"));
        verify(incus, never()).shellExec(eq(INSTANCE),
                argThat((String[] args) -> args.length > 0 && args[0].equals("npm")));
        verify(incus).shellExec(eq(INSTANCE), eq("sh"), eq("-c"),
                argThat(cmd -> cmd.contains(GeminiSetup.CONFIG_PATH) && cmd.contains("gemini-4-argon")));
        verify(incus).shellExec(eq(INSTANCE), eq("sh"), eq("-c"),
                argThat(cmd -> cmd.contains(GeminiSetup.POLICY_PATH)));
        verify(incus).shellExec(INSTANCE, "rm", "-f", GeminiSetup.LEGACY_LOGIN_NOTICE_PATH);
    }

    @Test
    void anInheritedToolLosesTheOldLoginBlockWithoutReinstallingOrReplacingItsModel() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.shellExec(INSTANCE, "cat", GeminiSetup.CONFIG_PATH))
                .thenReturn(new IncusClient.ExecResult(0, "{\"model\":{\"name\":\"inherited-model\"}}", ""));
        new GeminiSetup().refreshInherited(new Container(incus, INSTANCE));
        verify(incus).shellExec(INSTANCE, "cat", GeminiSetup.CONFIG_PATH);
        verify(incus).shellExec(eq(INSTANCE), eq("sh"), eq("-c"),
                argThat(cmd -> cmd.contains(GeminiSetup.CONFIG_PATH) && cmd.contains("inherited-model")
                        && cmd.contains("gemini-api-key")));
        verify(incus).shellExec(INSTANCE, "chown", "-R", "agentuser:agentuser", GeminiSetup.CONFIG_PATH);
        verify(incus).shellExec(INSTANCE, "rm", "-f", GeminiSetup.LEGACY_LOGIN_NOTICE_PATH);
        verifyNoMoreInteractions(incus);
    }

    @Test
    void authSelectionPreservesOtherSettingsAndRejectsCorruptSettings() {
        var before = JqValues.parseStrict("""
                {"model":{"name":"custom"},"security":{"folderTrust":{"enabled":false},
                "auth":{"useExternal":false}},"custom":{"nested":[1,2,3]}}
                """);
        var after = JqValues.parseStrict(GeminiSetup.selectApiKeyAuth(before.toJsonString()));
        assertEquals(before.getField("model"), after.getField("model"));
        assertEquals(before.getField("custom"), after.getField("custom"));
        assertEquals(before.getField("security").getField("folderTrust"), after.getField("security").getField("folderTrust"));
        assertEquals(JqBoolean.FALSE, after.getField("security").getField("auth").getField("useExternal"));
        assertEquals("gemini-api-key", after.getField("security").getField("auth").pathText("selectedType", null));
        for (var json : List.of("[]", "null", "{", "{\"security\":false}", "{\"security\":{\"auth\":[]}}")) {
            assertThrows(RuntimeException.class, () -> GeminiSetup.selectApiKeyAuth(json), json);
        }
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
