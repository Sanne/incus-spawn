package dev.incusspawn.tool;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A tool's {@code mcp:} entries (#2206): how they are read, refused, and rendered for each agent. */
class McpServerEntryTest {

    private static ToolDef def(String yaml) throws Exception {
        return ToolDef.loadFromStream(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static final McpServer STDIO = new McpServer("jasolo", "Java sources",
            List.of("java", "-jar", "/opt/jasolo/jasolo.jar"), Map.of("JASOLO_GIT_ROOTS", "/home/agentuser/q"), null, null);
    private static final McpServer HTTP = new McpServer("atlassian", null, List.of(), Map.of(),
            "https://mcp.atlassian.com/v2/mcp", "ATLASSIAN_MCP_AUTH");

    @Test
    void anEntryIsReadWithItsParametersSubstituted() throws Exception {
        var tool = def("""
                name: t
                parameters:
                  roots: { type: string, default: "${isx_repo_paths}" }
                mcp:
                  - name: s
                    command: [srv, --root, "${param_roots}"]
                    env: { ROOTS: "${param_roots}", EMPTY: "" }
                """);
        assertEquals(List.of(), tool.unknownKeyWarnings());
        var setup = new YamlToolSetup(tool);
        var resolved = ParameterResolver.resolve(setup.parameters(), Map.of()).resolvedValues();
        var server = setup.mcpServers(resolved).getFirst().expandRepos("/home/agentuser/a,/home/agentuser/b");
        assertEquals(List.of("srv", "--root", "/home/agentuser/a,/home/agentuser/b"), server.command());
        // An empty value is a variable not set
        assertEquals(Map.of("ROOTS", "/home/agentuser/a,/home/agentuser/b"), server.env());
    }

    @Test
    void theEntriesAreInTheFingerprint() throws Exception {
        var one = def("name: t\nmcp:\n  - name: s\n    command: [a]\n");
        var other = def("name: t\nmcp:\n  - name: s\n    command: [b]\n");
        assertNotEquals(one.contentFingerprint(), other.contentFingerprint());
    }

    @Test
    void anEntryThatCannotBeRegisteredRefusesTheTool() throws Exception {
        for (var entry : List.of(
                "  - name: Bad Name\n    command: [a]",
                "  - name: isx\n    command: [a]",
                "  - name: s",
                "  - name: s\n    command: [a]\n    url: https://x",
                "  - name: s\n    url: http://x",
                "  - name: s\n    url: https://x\n    env: { A: b }",
                "  - name: s\n    command: [a]\n    bearer-token-env: T",
                "  - name: s\n    url: https://x\n    bearer-token-env: 'not a name'",
                "  - name: s\n    command: [a]\n    env: { 'A B': c }",
                "  - name: s\n    command: [a]\n    env: { API_TOKEN: c }",
                "  - name: s\n    command: [a]\n  - name: s\n    command: [b]")) {
            var tool = def("name: t\nmcp:\n" + entry + "\n");
            assertNotNull(ToolDefValidator.refusal(tool), entry);
        }
        assertNull(ToolDefValidator.refusal(def("name: t\nmcp:\n  - name: s\n    command: [a]\n    env: { ROOTS: x }\n")));
    }

    @Test
    void aBearerTokenComesOnlyFromTheToolsOwnProofPlaceholder() throws Exception {
        var proxied = """
                name: t
                env:
                  - name: T_AUTH
                    value: t-placeholder
                proxy:
                  config-namespace: t
                  placeholders:
                    - env: T_AUTH
                      prefix: t-
                  configuration:
                    token: { config-path: token, description: d, secret: true }
                  auth:
                    - domains: [mcp.example.com]
                      type: bearer
                      token: "${token}"
                mcp:
                  - name: s
                    url: https://mcp.example.com/mcp
                    bearer-token-env: %s
                """;
        assertNull(ToolDefValidator.refusal(def(proxied.formatted("T_AUTH"))));
        // A variable no start fills with a proof: an agent would send whatever it holds, unchecked
        var refused = ToolDefValidator.refusal(def(proxied.formatted("OTHER_TOKEN")));
        assertNotNull(refused);
        assertTrue(refused.contains("is not one of the tool's proxy.placeholders"), refused);
        assertNotNull(ToolDefValidator.refusal(def("name: t\nmcp:\n  - name: s\n    url: https://x\n    bearer-token-env: T\n")),
                "a tool with no proxy entry has no placeholder to name");
        // A proof sent to a host the proxy does not inject for is never checked
        var elsewhere = ToolDefValidator.refusal(def(proxied.formatted("T_AUTH")
                .replace("url: https://mcp.example.com/mcp", "url: https://other.example.net/mcp")));
        assertNotNull(elsewhere);
        assertTrue(elsewhere.contains("none of the tool's proxy.auth domains"), elsewhere);
        // A declared placeholder the tool does not export is never filled with a proof
        assertNotNull(ToolDefValidator.refusal(def(proxied.formatted("T_AUTH")
                .replace("env:\n  - name: T_AUTH\n    value: t-placeholder\n", ""))));
    }

    @Test
    void theBuiltInsDeclareServersEveryAgentCanTake() throws Exception {
        for (var name : List.of("jasolo", "atlassian-mcp")) {
            try (var is = getClass().getClassLoader().getResourceAsStream("tools/" + name + ".yaml")) {
                var tool = ToolDef.loadFromStream(is);
                assertNull(ToolDefValidator.refusal(tool), name);
                assertFalse(tool.getMcp().isEmpty(), name);
            }
        }
    }

    @Test
    void claudeGetsItsAddJsonEntryAfterARemoval() {
        var json = (JqObject) JqValues.parseStrict(McpClients.claudeJson(STDIO));
        assertEquals("""
                {"type":"stdio","command":"java","args":["-jar","/opt/jasolo/jasolo.jar"],"env":{"JASOLO_GIT_ROOTS":"/home/agentuser/q"}}""",
                json.toJsonString());
        // The token is read from the variable before each request, never written into the entry
        assertEquals("""
                {"type":"http","url":"https://mcp.atlassian.com/v2/mcp","headersHelper":"printf '{\\"Authorization\\":\\"Bearer %s\\"}' \\"$ATLASSIAN_MCP_AUTH\\""}""",
                McpClients.claudeJson(HTTP));
        var script = McpClients.CLAUDE.addScript(STDIO);
        assertTrue(script.startsWith("{ claude mcp remove --scope user 'jasolo' >/dev/null 2>&1 || true; } && claude mcp add-json --scope user 'jasolo' "),
                script);
    }

    @Test
    void codexAndPiGetTheirOwnAddArguments() {
        assertEquals("'codex' 'mcp' 'add' 'jasolo' '--env' 'JASOLO_GIT_ROOTS=/home/agentuser/q' '--' 'java' '-jar' '/opt/jasolo/jasolo.jar' >/dev/null",
                McpClients.CODEX.addScript(STDIO));
        assertEquals("'codex' 'mcp' 'add' 'atlassian' '--url' 'https://mcp.atlassian.com/v2/mcp' '--bearer-token-env-var' 'ATLASSIAN_MCP_AUTH' >/dev/null",
                McpClients.CODEX.addScript(HTTP));
        // Direct, as Claude Code declares the tools to the model
        assertEquals("'pi' 'mcp' 'add' 'jasolo' '--env' 'JASOLO_GIT_ROOTS=/home/agentuser/q' '--exposure' 'direct' '--description' 'Java sources' '--' 'java' '-jar' '/opt/jasolo/jasolo.jar' >/dev/null",
                McpClients.PI.addScript(STDIO));
        assertEquals("'pi' 'mcp' 'add' 'atlassian' '--url' 'https://mcp.atlassian.com/v2/mcp' '--bearer-token-env-var' 'ATLASSIAN_MCP_AUTH' '--exposure' 'direct' >/dev/null",
                McpClients.PI.addScript(HTTP));
    }

    @Test
    void opencodeKeepsItsConfigAndTheEntriesIsxDidNotMake() {
        var config = """
                {"$schema": "https://opencode.ai/config.json", "autoupdate": false,
                 "mcp": {"mine": {"type": "local", "command": ["x"]}, "gone": {"type": "local", "command": ["y"]}}}
                """;
        var merged = (JqObject) JqValues.parseStrict(McpClients.opencodeConfig(config, List.of(STDIO, HTTP), List.of("gone")));
        assertEquals("false", merged.get("autoupdate").toJsonString());
        var mcp = (JqObject) merged.get("mcp");
        assertEquals(List.of("mine", "jasolo", "atlassian"), new ArrayList<>(mcp.keys()));
        assertEquals("""
                {"type":"local","command":["java","-jar","/opt/jasolo/jasolo.jar"],"environment":{"JASOLO_GIT_ROOTS":"/home/agentuser/q"},"enabled":true}""",
                mcp.get("jasolo").toJsonString());
        assertEquals("""
                {"type":"remote","url":"https://mcp.atlassian.com/v2/mcp","headers":{"Authorization":"Bearer {env:ATLASSIAN_MCP_AUTH}"},"enabled":true}""",
                mcp.get("atlassian").toJsonString());
    }

    @Test
    void theFourBuiltInAgentsTakeServers() {
        assertSame(McpClients.CLAUDE, new ClaudeSetup().mcpClient());
        assertSame(McpClients.CODEX, new CodexSetup().mcpClient());
        assertSame(McpClients.PI, new PiSetup().mcpClient());
        assertSame(McpClients.OPENCODE, new OpencodeSetup().mcpClient());
    }

    @Test
    void geminiCopilotAndBobGetEveryServerInTheirOwnFormWithTheTokenAsAReference() {
        var mine = "{\"theme\": \"x\", \"mcpServers\": {\"mine\": {\"command\": \"m\"}, \"old\": {}}}";
        assertSame(McpClients.GEMINI, new GeminiSetup().mcpClient());
        assertSame(McpClients.COPILOT, new CopilotSetup().mcpClient());
        assertSame(McpClients.BOB, new BobSetup().mcpClient());
        var gemini = JqValues.parse(McpClients.mergeServers(mine, "p", List.of(STDIO, HTTP), List.of("old"),
                McpClients::geminiEntry));
        var servers = (JqObject) ((JqObject) gemini).get("mcpServers");
        assertEquals(java.util.Set.of("mine", "jasolo", "atlassian"), new java.util.HashSet<>(servers.keys()));
        assertTrue(gemini.toJsonString().contains("\"theme\":\"x\""));
        var http = ((JqObject) servers.get("atlassian")).toJsonString();
        assertTrue(http.contains("\"httpUrl\"") && http.contains("Bearer ${ATLASSIAN_MCP_AUTH}"), http);
        var copilot = JqValues.parse(McpClients.mergeServers("{}", "p", List.of(STDIO, HTTP), List.of(),
                McpClients::copilotEntry)).toJsonString();
        assertTrue(copilot.contains("\"type\":\"local\"") && copilot.contains("\"type\":\"http\"")
                && copilot.contains("\"tools\":[\"*\"]"), copilot);
    }

    /**
     * Antigravity CLI reads no variable in its MCP config (#2366), so a server whose bearer token
     * is one is registered as the stdio bridge, which reads it per request (#2476): the entry
     * holds the variable's name and never a token. Every other server is written in the form
     * {@code agy mcp add} writes.
     */
    @Test
    void antigravityGetsABridgeForAServerWhoseTokenIsAVariable() {
        assertSame(McpClients.ANTIGRAVITY, new AntigravitySetup().mcpClient());
        var plain = new McpServer("docs", null, List.of(), Map.of(), "https://docs.example.com/mcp", null);
        assertNull(McpClients.ANTIGRAVITY.refuses(HTTP), "none is refused any more");

        var mine = "{\"mcpServers\": {\"mine\": {\"command\": \"m\"}}}";
        var merged = (JqObject) JqValues.parse(McpClients.mergeServers(mine, "p", List.of(STDIO, plain, HTTP),
                List.of(), McpClients::antigravityEntry));
        var servers = (JqObject) merged.get("mcpServers");
        assertEquals(java.util.Set.of("mine", "jasolo", "docs", "atlassian"), new java.util.HashSet<>(servers.keys()));
        assertEquals("{\"command\":\"java\",\"args\":[\"-jar\",\"/opt/jasolo/jasolo.jar\"],\"env\":{\"JASOLO_GIT_ROOTS\":\"/home/agentuser/q\"}}",
                servers.get("jasolo").toJsonString());
        assertEquals("{\"serverUrl\":\"https://docs.example.com/mcp\"}", servers.get("docs").toJsonString());
        var bridged = servers.get("atlassian").toJsonString();
        assertEquals("{\"command\":\"node\",\"args\":[\"" + McpStdioBridge.PATH + "\",\"--url\",\"" + HTTP.url()
                + "\",\"--bearer-env\",\"ATLASSIAN_MCP_AUTH\"],\"env\":{}}", bridged);
    }
}
