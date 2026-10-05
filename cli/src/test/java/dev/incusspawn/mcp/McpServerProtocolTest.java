package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerProtocolTest {

    /** Collects what the server sends; input is fed through {@link McpServer#handle}. */
    static final class Captured implements McpTransport {
        final List<JsonNode> sent = new CopyOnWriteArrayList<>();

        @Override
        public String read() {
            return null;
        }

        @Override
        public void send(String json) throws IOException {
            assertFalse(json.contains("\n"), "a message must be one line");
            sent.add(JsonRpc.JSON.readTree(json));
        }

        JsonNode byId(int id) {
            return sent.stream().filter(m -> m.path("id").asInt(-1) == id).findFirst()
                    .orElseThrow(() -> new AssertionError("no response with id " + id + " in " + sent));
        }
    }

    private final Captured out = new Captured();
    private final List<McpTool> tools = new ArrayList<>();
    private final JsonNode[] clientInfo = new JsonNode[1];

    private McpServer server() {
        return new McpServer(out, tools, "1.2.3", "Use isx.", info -> clientInfo[0] = info);
    }

    private static McpTool tool(String name, McpTool.Handler handler) {
        return new McpTool(name, "does " + name, Schema.object().string("x", "an x", false).build(),
                Schema.object().string("x", "the x", true).build(), McpTool.annotations(true, false, true), handler);
    }

    private static String call(int id, String tool, String args) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + args + "}}";
    }

    @Test
    void initializeEchoesASupportedVersionAndIdentifiesTheServer() {
        var server = server();
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"claude-code\",\"version\":\"2.1\"}}}");
        var result = out.byId(1).path("result");
        assertEquals("2025-03-26", result.path("protocolVersion").asText());
        assertEquals("isx", result.path("serverInfo").path("name").asText());
        assertEquals("1.2.3", result.path("serverInfo").path("version").asText());
        assertTrue(result.path("capabilities").has("tools"));
        assertEquals("Use isx.", result.path("instructions").asText());
        assertEquals("claude-code", clientInfo[0].path("name").asText());
    }

    @Test
    void anExperimentalCapabilityStartsOnlyWhenAskedForAndAfterTheResponse() {
        var sentBeforeStart = new ArrayList<Integer>();
        var server = new McpServer(out, tools, "1", null, null,
                java.util.Map.of("isx/x", () -> sentBeforeStart.add(out.sent.size())));
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"capabilities\":{}}}");
        assertTrue(out.byId(1).path("result").path("capabilities").path("experimental").has("isx/x"));
        assertEquals(List.of(), sentBeforeStart, "not asked for");
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{"
                + "\"capabilities\":{\"experimental\":{\"isx/x\":{}}}}}");
        assertEquals(List.of(2), sentBeforeStart, "started once both responses were out");
        server.notify("notifications/isx/x", JsonRpc.JSON.createObjectNode().put("a", 1));
        var note = out.sent.getLast();
        assertFalse(note.has("id"), "a notification");
        assertEquals("notifications/isx/x", note.path("method").asText());
    }

    @Test
    void anUnknownVersionIsAnsweredWithTheNewest() {
        server().handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"1999-01-01\"}}");
        assertEquals(McpServer.PROTOCOL_VERSIONS.getFirst(),
                out.byId(1).path("result").path("protocolVersion").asText());
    }

    @Test
    void toolsAreListedWithSchemasAndAnnotations() {
        tools.add(tool("a", (args, ctx) -> ToolResult.text("ok", x("ok"))));
        server().handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        var listed = out.byId(2).path("result").path("tools").get(0);
        assertEquals("a", listed.path("name").asText());
        assertEquals("object", listed.path("inputSchema").path("type").asText());
        assertEquals("string", listed.path("outputSchema").path("properties").path("x").path("type").asText());
        assertTrue(listed.path("annotations").path("readOnlyHint").asBoolean());
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode x(String value) {
        var node = JsonRpc.JSON.createObjectNode();
        node.put("x", value);
        return node;
    }

    @Test
    void aToolResultCarriesItsStructureAndAToolErrorOnlyItsCodeInMeta() throws Exception {
        tools.add(tool("ok", (args, ctx) -> ToolResult.text("x=" + args.string("x"), x(args.string("x")))));
        tools.add(tool("json", (args, ctx) -> ToolResult.json(x("1"), "Then do y.")));
        tools.add(tool("refuse", (args, ctx) -> { throw new ToolError(ToolError.Code.NOT_FOUND, "no such task"); }));
        tools.add(tool("crash", (args, ctx) -> { throw new IllegalStateException("boom"); }));
        var server = server();
        server.handle(call(1, "ok", "{\"x\":\"1\"}"));
        server.handle(call(2, "json", "{}"));
        server.handle(call(3, "refuse", "{\"task_id\":\"t1-ab\",\"instance\":\"box\",\"x\":\"y\"}"));
        server.handle(call(4, "crash", "{}"));
        server.handle(call(5, "ok", "{\"x\":5}"));
        server.awaitIdle();

        var results = new StructuredResults(tools);
        var ok = results.check("ok", out.byId(1).path("result"));
        assertEquals("x=1", ok.path("content").get(0).path("text").asText(), "the text stays for the model");
        assertEquals("1", ok.path("structuredContent").path("x").asText());
        assertFalse(ok.has("_meta"));

        var json = results.check("json", out.byId(2).path("result"));
        assertEquals("{\n  \"x\" : \"1\"\n}\n\nThen do y.", json.path("content").get(0).path("text").asText(),
                "the structure, then guidance that is not part of it");

        var refused = results.check("refuse", out.byId(3).path("result"));
        var error = refused.path("_meta").path("dev.incusspawn/error");
        assertEquals("not_found", error.path("code").asText());
        assertEquals("t1-ab", error.path("task_id").asText(), "what the call named");
        assertEquals("box", error.path("instance").asText());
        assertEquals(4, error.size(), "code, message, task_id, instance -- not x: " + error);
        assertEquals("internal", results.check("crash", out.byId(4).path("result"))
                .path("_meta").path("dev.incusspawn/error").path("code").asText());
        var mistyped = results.check("ok", out.byId(5).path("result"));
        assertEquals("invalid_argument", mistyped.path("_meta").path("dev.incusspawn/error").path("code").asText());
        assertTrue(mistyped.path("content").get(0).path("text").asText().contains("must be a string"));
    }

    @Test
    void protocolFaultsAreJsonRpcErrors() {
        var server = server();
        server.handle("{not json");
        server.handle("[1,2]");
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/list\"}");
        server.handle(call(8, "no-such-tool", "{}"));
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"x\",\"arguments\":[1]}}");

        assertEquals(JsonRpc.PARSE_ERROR, out.sent.get(0).path("error").path("code").asInt());
        assertTrue(out.sent.get(0).path("id").isNull());
        assertEquals(JsonRpc.INVALID_REQUEST, out.sent.get(1).path("error").path("code").asInt());
        assertEquals(JsonRpc.METHOD_NOT_FOUND, out.byId(7).path("error").path("code").asInt());
        assertEquals(JsonRpc.INVALID_PARAMS, out.byId(8).path("error").path("code").asInt());
        assertEquals(JsonRpc.INVALID_PARAMS, out.byId(9).path("error").path("code").asInt());
    }

    @Test
    void notificationsGetNoReply() {
        var server = server();
        server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/whatever\"}");
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}");
        assertEquals(List.of(), out.sent);
    }

    @Test
    void pingIsAnsweredWhileAToolRuns() throws Exception {
        var release = new CountDownLatch(1);
        tools.add(tool("slow", (args, ctx) -> {
            release.await(10, TimeUnit.SECONDS);
            return ToolResult.text("done", x("done"));
        }));
        var server = server();
        server.handle(call(1, "slow", "{}"));
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");
        assertTrue(out.byId(2).path("result").isObject());
        assertEquals(1, out.sent.size(), "the slow call has not answered yet");
        release.countDown();
        server.awaitIdle();
        assertEquals("done", out.byId(1).path("result").path("content").get(0).path("text").asText());
    }

    @Test
    void cancellingACallRunsItsCancelHookAndSuppressesTheResponse() throws Exception {
        var started = new CountDownLatch(1);
        var hookRan = new AtomicBoolean();
        tools.add(tool("long", (args, ctx) -> {
            var stop = new CountDownLatch(1);
            ctx.onCancel(() -> { hookRan.set(true); stop.countDown(); });
            started.countDown();
            stop.await(10, TimeUnit.SECONDS);
            return ToolResult.text("late", x("late"));
        }));
        var server = server();
        server.handle(call(5, "long", "{}"));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":5}}");
        server.awaitIdle();
        assertTrue(hookRan.get());
        assertEquals(List.of(), out.sent, "no response for a cancelled request");
    }

    @Test
    void progressIsSentOnlyWhenTheClientAskedForIt() throws Exception {
        tools.add(tool("chatty", (args, ctx) -> {
            ctx.progress("step 1");
            return ToolResult.text("ok", x("ok"));
        }));
        var server = server();
        server.handle(call(1, "chatty", "{}"));
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"chatty\","
                + "\"arguments\":{},\"_meta\":{\"progressToken\":\"tok\"}}}");
        server.awaitIdle();
        var progress = out.sent.stream()
                .filter(m -> "notifications/progress".equals(m.path("method").asText())).toList();
        assertEquals(1, progress.size());
        assertEquals("tok", progress.getFirst().path("params").path("progressToken").asText());
        assertEquals("step 1", progress.getFirst().path("params").path("message").asText());
    }

    @Test
    void endOfInputCancelsCallsInFlight() throws Exception {
        var cancelled = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        tools.add(tool("long", (args, ctx) -> {
            ctx.onCancel(cancelled::countDown);
            started.countDown();
            cancelled.await(10, TimeUnit.SECONDS);
            return ToolResult.text("x", x("x"));
        }));
        var lines = new java.util.ArrayDeque<>(List.of(call(1, "long", "{}")));
        var transport = new McpTransport() {
            @Override
            public String read() throws IOException {
                if (lines.isEmpty()) {
                    try {
                        started.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    return null;
                }
                return lines.poll();
            }

            @Override
            public void send(String json) {}
        };
        new McpServer(transport, tools, "1", null, null).run();
        assertEquals(0, cancelled.getCount());
    }
}
