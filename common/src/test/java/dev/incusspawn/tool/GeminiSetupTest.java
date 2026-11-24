package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
    void effortIsTheThinkingLevelOfTheSelectedModel() {
        assertFalse(GeminiSetup.settingsJson(Map.of("model", "gemini-4-argon")).contains("modelConfigs"));
        var settings = JqValues.parseStrict(GeminiSetup.settingsJson(Map.of("model", "gemini-4-argon", "effort", "xhigh")));
        var overrides = (JqArray) settings.getField("modelConfigs").getField("customOverrides");
        assertEquals(1, overrides.size());
        var override = overrides.first();
        assertEquals("gemini-4-argon", override.getField("match").pathText("model", null));
        // The API's enum names are upper case
        assertEquals("XHIGH", override.getField("modelConfig").getField("generateContentConfig")
                .getField("thinkingConfig").pathText("thinkingLevel", null));
    }

    @Test
    void effortWithoutAModelFailsTheBuild() {
        var e = assertThrows(IllegalStateException.class,
                () -> GeminiSetup.settingsJson(Map.of("effort", "high")));
        assertTrue(e.getMessage().contains("needs a model"), e.getMessage());
    }

    @Test
    void effortTakesOnlyAThinkingLevel() {
        var parameters = new GeminiSetup().parameters();
        assertTrue(parameters.get("effort").isReconfigurable());
        for (var value : new String[]{"minimal", "low", "medium", "high", "xhigh"}) {
            assertFalse(ParameterResolver.resolve(parameters, Map.of("effort", value)).hasErrors(), value);
        }
        for (var value : new String[]{"max", "none", "HIGH", "high\""}) {
            assertTrue(ParameterResolver.resolve(parameters, Map.of("effort", value)).hasErrors(), value);
        }
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
        assertFalse(commands(incus).stream().anyMatch(cmd -> cmd.startsWith("npm ")), "reconfigure reinstalls nothing");
        verify(incus).shellExec(eq(INSTANCE), eq("sh"), eq("-c"),
                argThat(cmd -> cmd.contains(GeminiSetup.CONFIG_PATH) && cmd.contains("gemini-4-argon")));
        verify(incus).shellExec(eq(INSTANCE), eq("sh"), eq("-c"),
                argThat(cmd -> cmd.contains(GeminiSetup.POLICY_PATH)));
        verify(incus).shellExec(INSTANCE, "rm", "-f", GeminiSetup.LEGACY_LOGIN_NOTICE_PATH);
    }

    /**
     * The build's instructions are an extension's context file, never the user's own
     * {@code ~/.gemini/GEMINI.md} (#1784): Gemini loads a {@code GEMINI.md} beside a manifest
     * that names it, from a directory under its extensions directory.
     */
    @Test
    void instructionsGoToAnExtensionOfIsxsOwn() {
        var path = new GeminiSetup().environmentContextPath();
        assertEquals(GeminiSetup.EXTENSION_DIR + "/GEMINI.md", path);
        assertTrue(GeminiSetup.EXTENSION_DIR.startsWith("/home/agentuser/.gemini/extensions/"));
        var manifest = JqValues.parseStrict(GeminiSetup.EXTENSION_MANIFEST);
        assertEquals("isx", manifest.pathText("name", null));
        assertNotNull(manifest.pathText("version", null), "Gemini skips an extension without a version");
        assertEquals("GEMINI.md", manifest.pathText("contextFileName", null));

        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        new GeminiSetup().install(new Container(incus, INSTANCE), Map.of());
        verify(incus).shellExec(eq(INSTANCE), eq("sh"), eq("-c"),
                argThat(cmd -> cmd.contains(GeminiSetup.EXTENSION_MANIFEST_PATH) && cmd.contains("contextFileName")));
    }

    /** The extension's script, run in a real shell under a scratch home: the heredoc feeds the manifest. */
    @Test
    void theExtensionScriptWritesTheManifestAndHandsItToAgentuser(@TempDir Path root) throws Exception {
        var bin = Files.createDirectories(root.resolve("bin"));
        Files.writeString(bin.resolve("chown"), "#!/bin/sh\necho \"$@\" >> \"$(dirname \"$0\")/chown.log\"\n");
        bin.resolve("chown").toFile().setExecutable(true);
        var script = GeminiSetup.extensionScript().replace("/home/agentuser", root + "/home");
        var pb = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);

        var manifest = Files.readString(Path.of(GeminiSetup.EXTENSION_MANIFEST_PATH.replace("/home/agentuser", root + "/home")));
        assertEquals(GeminiSetup.EXTENSION_MANIFEST.strip() + "\n", manifest);
        // The extensions directory itself, and only isx's own extension below it.
        assertEquals("agentuser:agentuser " + root + "/home/.gemini/extensions\n"
                        + "-R agentuser:agentuser " + root + "/home/.gemini/extensions/isx\n",
                Files.readString(bin.resolve("chown.log")));
    }

    /**
     * Every command the mock ran, its arguments joined. A lambda {@code argThat} on the varargs
     * never matches the expanded arguments, so a {@code verify(never())} with one is vacuous.
     */
    private static List<String> commands(IncusClient incus) {
        return mockingDetails(incus).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("shellExec"))
                .map(invocation -> invocation.getRawArguments()[invocation.getRawArguments().length - 1])
                .map(command -> String.join(" ", (String[]) command))
                .toList();
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
        // A parent built before #1784 gets the extension its instructions now load from.
        verify(incus).shellExec(eq(INSTANCE), eq("sh"), eq("-c"),
                argThat(cmd -> cmd.contains(GeminiSetup.EXTENSION_MANIFEST_PATH) && cmd.contains("\"isx\"")
                        && cmd.endsWith("chown -R agentuser:agentuser " + GeminiSetup.EXTENSION_DIR)));
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

    @Test
    void settingsWithALoneSurrogateEscapeAreReadAndBrokenOnesExplained() {
        var after = JqValues.parseStrict(GeminiSetup.selectApiKeyAuth("{\"model\":{\"name\":\"cut \\ud83d\"}}"));
        assertEquals("cut \ufffd", after.path("model").path("name").asText());
        var e = assertThrows(IllegalArgumentException.class, () -> GeminiSetup.selectApiKeyAuth("{\"model\":\"\\q\"}"));
        assertEquals(GeminiSetup.CONFIG_PATH + " is not valid JSON: Invalid escape '\\q' at line 1, column 12", e.getMessage());
    }
}
