package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.CodexSetup;
import dev.incusspawn.tool.McpServer;
import dev.incusspawn.tool.PiSetup;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The build step that registers the tools' MCP servers with every agent in the image (#2206):
 * whichever order the tools come in and whichever layer brought the agent.
 */
class McpServerRegistrationTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");

    private static ToolSetup yamlTool(String yaml) throws Exception {
        return new YamlToolSetup(ToolDef.loadFromStream(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))));
    }

    private static BuildTools.ResolvedTool server() throws Exception {
        return new BuildTools.ResolvedTool("srv", yamlTool("""
                name: srv
                mcp:
                  - name: srv
                    command: [srv-bin]
                    env: { ROOTS: "${isx_repo_paths}" }
                """), Map.of());
    }

    private static IncusClient incus(String record, String missingCommands) {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), any(String[].class))).thenReturn(OK);
        when(incus.shellExec("test", "cat", McpServerRegistration.RECORD))
                .thenReturn(record == null ? new IncusClient.ExecResult(1, "", "No such file") : new IncusClient.ExecResult(0, record, ""));
        when(incus.execInContainer(eq("test"), eq("agentuser"), anyString())).thenReturn(OK);
        when(incus.execInContainer(eq("test"), eq("agentuser"), startsWith("for c in")))
                .thenReturn(new IncusClient.ExecResult(0, missingCommands, ""));
        return incus;
    }

    private static ImageDef template() {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/quarkusio/quarkus.git");
        repo.setPath("~/quarkus");
        var def = new ImageDef();
        def.setName("tpl-x");
        def.setRepos(List.of(repo));
        return def;
    }

    /** The scripts agentuser ran, the command check excluded. */
    private static List<String> asAgent(IncusClient incus) {
        var scripts = asAgentOrNone(incus);
        assertFalse(scripts.isEmpty(), "agentuser ran nothing");
        return scripts;
    }

    @Test
    void aServerReachesEveryAgentWhateverTheOrderAndLayer() throws Exception {
        var incus = incus(null, "");
        // The server's tool comes before the agents, and Codex and Pi come from a later layer than Claude
        var tools = List.of(server(),
                new BuildTools.ResolvedTool("claude", new ClaudeSetup(), Map.of()),
                new BuildTools.ResolvedTool("codex", new CodexSetup(), Map.of()),
                new BuildTools.ResolvedTool("pi", new PiSetup(), Map.of()));

        McpServerRegistration.register(new Container(incus, "test"), template(), Map.of(), tools);

        var scripts = asAgent(incus);
        assertEquals(3, scripts.size(), scripts.toString());
        assertTrue(scripts.get(0).contains("claude mcp add-json --scope user 'srv'"), scripts.get(0));
        assertTrue(scripts.get(0).contains("\"ROOTS\":\"/home/agentuser/quarkus\""), "the template's repos: " + scripts.get(0));
        assertTrue(scripts.get(1).contains("'codex' 'mcp' 'add' 'srv' '--env' 'ROOTS=/home/agentuser/quarkus' '--' 'srv-bin'"), scripts.get(1));
        assertTrue(scripts.get(2).contains("'pi' 'mcp' 'add' 'srv'"), scripts.get(2));
        var shell = ArgumentCaptor.forClass(String.class);
        verify(incus, atLeastOnce()).shellExec(eq("test"), eq("sh"), eq("-c"), shell.capture());
        assertTrue(shell.getAllValues().stream().anyMatch(s -> s.contains(McpServerRegistration.RECORD)
                && s.contains("claude srv\ncodex srv\npi srv")), shell.getAllValues().toString());
    }

    @Test
    void aServerNoToolDeclaresAnyMoreIsRemovedAndOthersAreLeftAlone() throws Exception {
        var incus = incus("claude old\nclaude srv\n", "");
        var tools = List.of(server(), new BuildTools.ResolvedTool("claude", new ClaudeSetup(), Map.of()));

        McpServerRegistration.register(new Container(incus, "test"), new ImageDef(), Map.of(), tools);

        var script = asAgent(incus).getFirst();
        assertTrue(script.contains("claude mcp remove --scope user 'old'"), script);
        // A name isx never recorded, such as one the user added, is not touched
        assertFalse(script.contains("'mine'"), script);
    }

    @Test
    void aServerWhoseCommandIsMissingFailsTheBuild() throws Exception {
        var incus = incus(null, "srv-bin\n");
        var tools = List.of(server(), new BuildTools.ResolvedTool("claude", new ClaudeSetup(), Map.of()));

        var e = assertThrows(IncusException.class,
                () -> McpServerRegistration.register(new Container(incus, "test"), new ImageDef(), Map.of(), tools));
        assertTrue(e.getMessage().contains("runs 'srv-bin', which is not on agentuser's PATH"), e.getMessage());
        assertTrue(asAgentOrNone(incus).isEmpty(), "nothing is registered");
    }

    @Test
    void twoToolsDeclaringOneServerFailTheBuild() throws Exception {
        var other = new BuildTools.ResolvedTool("other", yamlTool("name: other\nmcp:\n  - name: srv\n    command: [x]\n"), Map.of());
        var e = assertThrows(IncusException.class, () -> McpServerRegistration.servers(List.of(server(), other), ""));
        assertTrue(e.getMessage().contains("declared by both the srv and the other tools"), e.getMessage());
    }

    @Test
    void withoutAnAgentNothingRuns() throws Exception {
        var incus = incus(null, "");
        McpServerRegistration.register(new Container(incus, "test"), new ImageDef(), Map.of(), List.of(server()));
        verifyNoInteractions(incus);
    }

    @Test
    void aTemplateWithAgentsAndNoServersCostsOneRead() {
        var incus = incus(null, "");
        McpServerRegistration.register(new Container(incus, "test"), new ImageDef(), Map.of(),
                List.of(new BuildTools.ResolvedTool("claude", new ClaudeSetup(), Map.of())));
        verify(incus).shellExec("test", "cat", McpServerRegistration.RECORD);
        verifyNoMoreInteractions(incus);
    }

    @Test
    void theRecordReadsOnlyLinesIsxWrites() {
        assertEquals(Map.of("claude", java.util.Set.of("a")),
                McpServerRegistration.parseRecord("claude a\nclaude Bad Name\ngarbage\n\n"));
        assertTrue(McpServer.NAME.matcher("a").matches());
    }

    private static List<String> asAgentOrNone(IncusClient incus) {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(incus, atLeast(0)).execInContainer(eq("test"), eq("agentuser"), captor.capture());
        return captor.getAllValues().stream().filter(s -> !s.startsWith("for c in")).toList();
    }
}
