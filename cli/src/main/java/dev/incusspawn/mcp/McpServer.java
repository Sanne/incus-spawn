package dev.incusspawn.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The MCP protocol loop: reads JSON-RPC messages from a {@link McpTransport} and answers them.
 *
 * <p>Implemented by hand rather than with the Quarkus MCP extension: even initialized only on
 * demand, that extension (and the Vert.x it needs) added ~1.7 ms to the startup of every isx
 * command, including ones that never serve MCP. What isx needs of the protocol is small --
 * {@code initialize}, {@code ping}, {@code tools/list}, {@code tools/call}, cancellation and
 * progress, plus isx's own notifications a client asks for under {@code capabilities.experimental}.
 *
 * <p>The reader thread never runs a tool: each {@code tools/call} runs on its own virtual thread,
 * so {@code ping} and {@code notifications/cancelled} are answered while a long exec is running.
 */
final class McpServer {

    /** Newest first; an unknown client version is answered with the first. */
    static final List<String> PROTOCOL_VERSIONS =
            List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    private static final long PROGRESS_INTERVAL_MS = 1000;

    private final McpTransport transport;
    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final String version;
    private final String instructions;
    private final Consumer<JsonNode> onInitialize;
    private final Map<String, Runnable> experimental;
    private final Map<JsonNode, ToolContext> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    McpServer(McpTransport transport, List<McpTool> tools, String version, String instructions,
              Consumer<JsonNode> onInitialize) {
        this(transport, tools, version, instructions, onInitialize, Map.of());
    }

    /**
     * {@code experimental}: capabilities the server offers under {@code capabilities.experimental},
     * each with what to start when the client's {@code initialize} lists it too. Started only once
     * the {@code initialize} response is out, so nothing they send can precede it.
     */
    McpServer(McpTransport transport, List<McpTool> tools, String version, String instructions,
              Consumer<JsonNode> onInitialize, Map<String, Runnable> experimental) {
        this.transport = transport;
        this.experimental = experimental;
        tools.forEach(t -> this.tools.put(t.name(), t));
        this.version = version;
        this.instructions = instructions;
        this.onInitialize = onInitialize;
    }

    /**
     * Serve until the client goes away (end of input). Calls still running are then cancelled
     * and given a moment to wind down; their responses have nowhere to go.
     */
    void run() throws IOException {
        String line;
        while ((line = transport.read()) != null) {
            handle(line);
        }
        inFlight.values().forEach(ToolContext::cancel);
        workers.shutdown();
        try {
            workers.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Wait for calls in flight to finish. For tests driving {@link #handle} directly. */
    void awaitIdle() throws InterruptedException {
        while (!inFlight.isEmpty()) Thread.sleep(5);
    }

    void handle(String line) {
        JsonNode msg;
        try {
            msg = JsonRpc.JSON.readTree(line);
        } catch (JsonProcessingException e) {
            send(JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Parse error: " + e.getOriginalMessage()));
            return;
        }
        if (msg == null || !msg.isObject()) {
            // Batches were removed from MCP in 2025-06-18; nobody sends them.
            send(JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Invalid request: expected a JSON object"));
            return;
        }
        var id = msg.get("id");
        var method = msg.path("method").asText(null);
        if (method == null) return; // a response to a request we never send
        var params = msg.path("params");
        if (id == null) {
            handleNotification(method, params);
            return;
        }
        switch (method) {
            case "initialize" -> {
                send(JsonRpc.response(id, initialize(params)));
                var wanted = params.path("capabilities").path("experimental");
                experimental.forEach((name, start) -> {
                    if (wanted.has(name)) start.run();
                });
            }
            case "ping" -> send(JsonRpc.response(id, JsonRpc.JSON.createObjectNode()));
            case "tools/list" -> send(JsonRpc.response(id, listTools()));
            case "tools/call" -> callTool(id, params);
            default -> send(JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "Method not found: " + method));
        }
    }

    private void handleNotification(String method, JsonNode params) {
        if (method.equals("notifications/cancelled")) {
            var requestId = params.get("requestId");
            if (requestId == null) return;
            var ctx = inFlight.get(requestId);
            if (ctx != null) {
                // The client has stopped waiting: no response is sent for a cancelled request.
                ctx.cancel();
            }
        }
        // notifications/initialized and anything unknown need no action
    }

    private ObjectNode initialize(JsonNode params) {
        var requested = params.path("protocolVersion").asText("");
        var result = JsonRpc.JSON.createObjectNode();
        result.put("protocolVersion",
                PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.getFirst());
        var capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        if (!experimental.isEmpty()) {
            var offered = capabilities.putObject("experimental");
            experimental.keySet().forEach(offered::putObject);
        }
        var info = result.putObject("serverInfo");
        info.put("name", "isx");
        info.put("version", version);
        if (instructions != null) result.put("instructions", instructions);
        if (onInitialize != null) onInitialize.accept(params.path("clientInfo"));
        return result;
    }

    private ObjectNode listTools() {
        var result = JsonRpc.JSON.createObjectNode();
        var list = result.putArray("tools");
        tools.values().forEach(t -> list.add(t.descriptor()));
        return result;
    }

    private void callTool(JsonNode id, JsonNode params) {
        var name = params.path("name").asText("");
        var tool = tools.get(name);
        if (tool == null) {
            send(JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Unknown tool: " + name));
            return;
        }
        var arguments = params.path("arguments");
        if (!arguments.isMissingNode() && !arguments.isNull() && !arguments.isObject()) {
            send(JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "arguments must be an object"));
            return;
        }
        var args = new McpTool.Args(arguments.isObject() ? arguments : JsonRpc.JSON.createObjectNode());
        var ctx = new ToolContext(progressSink(params.path("_meta").get("progressToken")));
        if (inFlight.putIfAbsent(id, ctx) != null) {
            send(JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "Duplicate request id: " + id));
            return;
        }
        workers.execute(() -> {
            ToolResult result;
            try {
                result = tool.handler().call(args, ctx);
            } catch (ToolError e) {
                result = ToolResult.error(e.getMessage());
            } catch (Exception e) {
                result = ToolResult.error("isx failed running " + name + ": " + e.getMessage());
                System.err.println("isx mcp: " + name + " failed:");
                e.printStackTrace();
            }
            try {
                if (!ctx.cancelled()) send(JsonRpc.response(id, result.toJson()));
            } finally {
                // Only once answered: a call is in flight until its response is out.
                inFlight.remove(id);
            }
        });
    }

    private ToolContext.ProgressSink progressSink(JsonNode token) {
        if (token == null || token.isNull()) return null;
        var state = new Object() {
            int count;
            long last;
        };
        return message -> {
            var now = System.currentTimeMillis();
            synchronized (state) {
                if (now - state.last < PROGRESS_INTERVAL_MS) return;
                state.last = now;
                state.count++;
                var params = JsonRpc.JSON.createObjectNode();
                params.set("progressToken", token);
                params.put("progress", state.count);
                params.put("message", message);
                notify("notifications/progress", params);
            }
        };
    }

    /** Send a notification the client did not ask for by request: one it opted into. */
    void notify(String method, JsonNode params) {
        send(JsonRpc.notification(method, params));
    }

    private void send(ObjectNode message) {
        try {
            transport.send(JsonRpc.JSON.writeValueAsString(message));
        } catch (IOException e) {
            System.err.println("isx mcp: could not send a message: " + e.getMessage());
        }
    }
}
