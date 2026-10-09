package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The coordination playbook is served as an MCP prompt (#1182), so a coordinator's Claude Code
 * offers it as {@code /mcp__isx__coordinate} wherever the server is reachable.
 */
class McpPromptTest {

    private final McpServerProtocolTest.Captured out = new McpServerProtocolTest.Captured();

    private McpServer server() {
        return new McpServer(out, List.of(), McpPrompt.ALL, "1", null, null, Map.of());
    }

    private static String request(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    @Test
    void theServerOffersThePlaybook() {
        var server = server();
        server.handle(request(1, "initialize", "{\"capabilities\":{}}"));
        assertFalse(out.byId(1).path("result").path("capabilities").path("prompts").isMissingNode());

        server.handle(request(2, "prompts/list", "{}"));
        var listed = out.byId(2).path("result").path("prompts");
        assertEquals(1, listed.size());
        assertEquals("coordinate", listed.getElement(0).path("name").asText());
        assertFalse(listed.getElement(0).path("description").asText().isBlank());

        server.handle(request(3, "prompts/get", "{\"name\":\"coordinate\"}"));
        var message = out.byId(3).path("result").path("messages").getElement(0);
        assertEquals("user", message.path("role").asText());
        assertEquals(McpPrompt.COORDINATE.text(), message.path("content").path("text").asText());
    }

    @Test
    void anUnknownPromptIsAnError() {
        var server = server();
        server.handle(request(1, "prompts/get", "{\"name\":\"nope\"}"));
        assertEquals(JsonRpc.INVALID_PARAMS, out.byId(1).path("error").path("code").asInt(0));
    }

    @Test
    void aServerWithoutPromptsOffersNone() {
        var server = new McpServer(out, List.of(), "1", null, null);
        server.handle(request(1, "initialize", "{\"capabilities\":{}}"));
        assertTrue(out.byId(1).path("result").path("capabilities").path("prompts").isMissingNode());
    }

    @Test
    void thePlaybookNamesOnlyToolsThatExist() {
        // It ships with the server, so it must not drift from the tool list
        var config = new McpConfig();
        var backend = new FakeBackend();
        var session = new McpSession(new SessionId(7, 1), "alice", 1, "/work", backend, () -> config, s -> false);
        var tools = new McpTools(session, backend, new TemplatePolicy(backend, () -> config),
                new Tasks(session, backend, () -> config)).all().stream()
                .map(McpTool::name).collect(Collectors.toSet());
        var named = Pattern.compile("\\b[a-z]+_[a-z_]+\\b").matcher(McpPrompt.COORDINATE.text()).results()
                .map(m -> m.group()).filter(w -> !w.equals("idempotency_key")).collect(Collectors.toSet());
        assertTrue(named.size() >= 10, named.toString());
        assertTrue(tools.containsAll(named), "not tools: " + named.stream().filter(n -> !tools.contains(n)).toList());
    }
}
