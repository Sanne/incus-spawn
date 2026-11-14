package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The built-in {@code idea-backend}'s {@code index-mcp} opt-in, after substitution (#1638).
 * Its script is run in a local {@code sh} with the commands it calls stubbed to record their
 * arguments, so a parameter the substitutor left for the shell, which reads
 * {@code ${param_index-mcp}} as {@code $param_index} defaulting to {@code mcp}, shows as an
 * install that never happens.
 */
class IdeaBackendIndexMcpTest {

    private static final String CONTAINER = "test-container";
    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final List<String> STUBBED = List.of("unzip", "claude");

    @TempDir
    Path home;

    @Test
    void theDefaultInstallsAndRegistersNothing() throws Exception {
        var params = resolve(Map.of());
        assertEquals("false", params.get("index-mcp"));

        var run = runIndexMcpStep(params);

        assertEquals(0, run.exitCode(), run.output());
        assertEquals("", run.calls());
        assertTrue(verifyCommand(params).contains("if [ \"false\" = \"true\" ]; then"), "verify checks no plugin");
    }

    @Test
    void enabledInstallsTheCheckedPluginAndRegistersItOnTheConfiguredPort() throws Exception {
        var params = resolve(Map.of("index-mcp", "true"));

        var run = runIndexMcpStep(params);

        assertEquals(0, run.exitCode(), run.output());
        var calls = run.calls().lines().toList();
        assertEquals(List.of("unzip", "claude"), run.commands());

        var registration = Pattern.compile("http://127\\.0\\.0\\.1:(\\d+)/index-mcp/streamable-http").matcher(calls.get(1));
        assertTrue(calls.get(1).startsWith("claude mcp add ") && registration.find(), calls.get(1));
        var pluginXml = Files.readString(home.resolve(".config/JetBrains/IntelliJIdea2026.2/options/mcp-plugin.xml"));
        assertTrue(pluginXml.contains("name=\"serverPort\" value=\"" + registration.group(1) + "\""), pluginXml);

        assertTrue(verifyCommand(params).contains("if [ \"true\" = \"true\" ]; then ls -d ~/.local/share/JetBrains/*/plugins/jetbrains-index-mcp-plugin"),
                "verify checks the plugin");
    }

    /**
     * The plugin is a {@code downloads:} entry, so the build checks its sum before the step ever runs
     * (and the upstream watch's {@code idea-index-plugin} item moves it), never a line of script.
     */
    @Test
    void thePluginIsACheckedDownloadTheStepOnlyUnpacks() throws Exception {
        var def = definition();
        var plugin = def.getDownloads().stream().filter(d -> d.getUrl().contains("jetbrains-index-mcp-plugin")).toList();
        assertEquals(1, plugin.size());
        assertTrue(plugin.get(0).getSha256().matches("[0-9a-f]{64}"), plugin.get(0).getSha256());
        assertEquals("/opt/index-mcp-plugin.zip", plugin.get(0).getDestinationFile());
        assertNull(plugin.get(0).getArch());
        assertFalse(String.join("\n", def.getRunAsUser()).contains("curl"), "the step downloads nothing");
    }

    private static ToolDef definition() throws Exception {
        try (var in = IdeaBackendIndexMcpTest.class.getResourceAsStream("/tools/idea-backend.yaml")) {
            return ToolDef.loadFromStream(in);
        }
    }

    private static Map<String, String> resolve(Map<String, String> provided) throws Exception {
        var result = ParameterResolver.resolve(definition().getParameters(), provided);
        assertEquals(List.of(), result.errors());
        return result.resolvedValues();
    }

    private static String verifyCommand(Map<String, String> params) throws Exception {
        var verify = new YamlToolSetup(definition()).verifyCommand(params);
        assertFalse(verify.contains("${param_"), verify);
        return verify;
    }

    /** {@code calls} holds one line per stubbed command run, its name and then its arguments. */
    private record Run(int exitCode, String output, String calls) {
        List<String> commands() {
            return calls.lines().map(call -> call.split(" ", 2)[0]).toList();
        }
    }

    /** The run_as_user step that handles index-mcp, as install() hands it to the guest, run locally. */
    private Run runIndexMcpStep(Map<String, String> params) throws Exception {
        var def = definition();
        def.setDownloads(List.of());
        var incus = mock(IncusClient.class);
        when(incus.getInstanceArchitecture(CONTAINER)).thenReturn("x86_64");
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);
        new YamlToolSetup(def).install(new Container(incus, CONTAINER), params);
        var scripts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(incus, atLeastOnce()).execInContainer(eq(CONTAINER), eq("agentuser"), scripts.capture());
        var step = scripts.getAllValues().stream().filter(s -> s.contains("index-mcp")).findFirst().orElseThrow();
        assertFalse(step.contains("${param_"), step);
        // Only the guest's paths change: the host's own IDE or /tmp must not decide the result
        var idea = Files.createDirectories(home.resolve("opt-idea"));
        Files.writeString(idea.resolve("product-info.json"), "{\"dataDirectoryName\": \"IntelliJIdea2026.2\"}");
        step = step.replace("/opt/idea/", idea + "/").replace("/opt/index-mcp-plugin.zip", home + "/index-mcp-plugin.zip");

        var stubs = Files.createDirectories(home.resolve("stubs"));
        var calls = home.resolve("calls");
        Files.writeString(calls, "");
        for (var command : STUBBED) {
            var stub = stubs.resolve(command);
            Files.writeString(stub, "#!/bin/sh\necho \"" + command + " $*\" >> \"$ISX_CALLS\"\n");
            assertTrue(stub.toFile().setExecutable(true));
        }
        var result = GuestShell.exec(home, Map.of("PATH", stubs + ":" + System.getenv("PATH"),
                "ISX_CALLS", calls.toString()), "sh", "-c", step);
        return new Run(result.exitCode(), result.output(), Files.readString(calls));
    }
}
