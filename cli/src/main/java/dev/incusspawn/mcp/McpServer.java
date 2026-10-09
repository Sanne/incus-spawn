package dev.incusspawn.mcp;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.incus.IncusException;

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
 * progress, {@code prompts/list} and {@code prompts/get}, plus isx's own notifications a client
 * asks for under {@code capabilities.experimental}.
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
    private final Map<String, McpPrompt> prompts = new LinkedHashMap<>();
    private final String version;
    private final String instructions;
    private final Consumer<JqValue> onInitialize;
    private final Map<String, Runnable> experimental;
    private final Map<String, ToolContext> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    McpServer(McpTransport transport, List<McpTool> tools, String version, String instructions,
              Consumer<JqValue> onInitialize) {
        this(transport, tools, version, instructions, onInitialize, Map.of());
    }

    /**
     * {@code experimental}: capabilities the server offers under {@code capabilities.experimental},
     * each with what to start when the client's {@code initialize} lists it too. Started only once
     * the {@code initialize} response is out, so nothing they send can precede it.
     */
    McpServer(McpTransport transport, List<McpTool> tools, String version, String instructions,
              Consumer<JqValue> onInitialize, Map<String, Runnable> experimental) {
        this(transport, tools, List.of(), version, instructions, onInitialize, experimental);
    }

    /** {@code prompts}: offered under {@code capabilities.prompts}, when there are any. */
    McpServer(McpTransport transport, List<McpTool> tools, List<McpPrompt> prompts, String version,
              String instructions, Consumer<JqValue> onInitialize, Map<String, Runnable> experimental) {
        this.transport = transport;
        prompts.forEach(p -> this.prompts.put(p.name(), p));
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
        JqValue msg;
        try {
            msg = JsonRpc.parse(line);
        } catch (RuntimeException e) {
            var detail = e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse("");
            send(JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Parse error: " + detail));
            return;
        }
        if (!(msg instanceof JqObject root)) {
            // Batches were removed from MCP in 2025-06-18; nobody sends them.
            send(JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Invalid request: expected a JSON object"));
            return;
        }
        var hasId = root.keys().contains("id");
        var id = hasId ? root.getField("id") : JsonRpc.nullNode();
        var method = JsonRpc.text(root, "method", null);
        if (method == null) return; // a response to a request we never send
        var params = root.getField("params");
        if (!hasId) {
            handleNotification(method, params);
            return;
        }
        switch (method) {
            case "initialize" -> {
                send(JsonRpc.response(id, initialize(params)));
                var wanted = params instanceof JqObject caps ? caps.getField("capabilities") : JsonRpc.nullNode();
                var experimentalWanted = wanted instanceof JqObject capsObj ? capsObj.getField("experimental") : JsonRpc.nullNode();
                experimental.forEach((name, start) -> {
                    if (experimentalWanted instanceof JqObject expObj && expObj.keys().contains(name)) start.run();
                });
            }
            case "ping" -> send(JsonRpc.response(id, JsonRpc.emptyObject()));
            case "tools/list" -> send(JsonRpc.response(id, listTools()));
            case "tools/call" -> callTool(id, params);
            case "prompts/list" -> send(JsonRpc.response(id, listPrompts()));
            case "prompts/get" -> getPrompt(id, params);
            default -> send(JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "Method not found: " + method));
        }
    }

    private void handleNotification(String method, JqValue params) {
        if (method.equals("notifications/cancelled")) {
            var requestId = params instanceof JqObject obj && obj.keys().contains("requestId")
                    ? obj.getField("requestId").toJsonString() : null;
            if (requestId == null) return;
            var ctx = inFlight.get(requestId);
            if (ctx != null) {
                // The client has stopped waiting: no response is sent for a cancelled request.
                ctx.cancel();
            }
        }
        // notifications/initialized and anything unknown need no action
    }

    private JqObject initialize(JqValue params) {
        var requested = JsonRpc.text(params, "protocolVersion", "");
        var tools = JqObject.builder(1).put("listChanged", false).build();
        var capabilities = JqObject.builder(3).put("tools", tools);
        if (!prompts.isEmpty()) capabilities.put("prompts", JqObject.builder(1).put("listChanged", false).build());
        if (!experimental.isEmpty()) {
            var offered = JqObject.builder(experimental.size());
            experimental.keySet().forEach(name -> offered.put(name, JsonRpc.emptyObject()));
            capabilities.put("experimental", offered.build());
        }
        var info = JqObject.builder(2).put("name", "isx").put("version", version).build();
        var result = JqObject.builder(4)
                .put("protocolVersion",
                        PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.getFirst())
                .put("capabilities", capabilities.build())
                .put("serverInfo", info);
        if (instructions != null) result.put("instructions", instructions);
        if (onInitialize != null) onInitialize.accept(params instanceof JqObject o ? o.getField("clientInfo") : JsonRpc.nullNode());
        return result.build();
    }

    private JqObject listTools() {
        var list = io.hyperfoil.tools.jjq.value.JqArray.arrayBuilder();
        tools.values().forEach(t -> list.add(t.descriptor()));
        return JqObject.builder(1).put("tools", list.build()).build();
    }

    private JqObject listPrompts() {
        var list = io.hyperfoil.tools.jjq.value.JqArray.arrayBuilder();
        prompts.values().forEach(p -> list.add(p.descriptor()));
        return JqObject.builder(1).put("prompts", list.build()).build();
    }

    private void getPrompt(JqValue id, JqValue params) {
        var prompt = prompts.get(params.path("name").asText(""));
        send(prompt == null
                ? JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Unknown prompt: " + params.path("name").asText(""))
                : JsonRpc.response(id, prompt.get()));
    }

    private void callTool(JqValue id, JqValue params) {
        var name = params instanceof JqObject named ? JsonRpc.text(named, "name", "") : "";
        var tool = tools.get(name);
        if (tool == null) {
            send(JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Unknown tool: " + name));
            return;
        }
        var arguments = params instanceof JqObject withArgs ? withArgs.getField("arguments") : JsonRpc.nullNode();
        if (!arguments.isNull() && !arguments.isObject()) {
            send(JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "arguments must be an object"));
            return;
        }
        var args = new McpTool.Args(arguments.isObject() ? arguments : JsonRpc.emptyObject());
        var meta = params instanceof JqObject withMeta ? withMeta.getField("_meta") : JsonRpc.nullNode();
        var ctx = new ToolContext(progressSink(meta instanceof JqObject metaObj ? metaObj.getField("progressToken") : JsonRpc.nullNode()));
        var idKey = id.toJsonString();
        if (inFlight.putIfAbsent(idKey, ctx) != null) {
            send(JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "Duplicate request id: " + id.toJsonString()));
            return;
        }
        workers.execute(() -> {
            ToolResult result;
            try {
                result = tool.handler().call(args, ctx);
            } catch (ToolError e) {
                result = ToolResult.error(e.code, e.getMessage(), args.node());
            } catch (IncusException e) {
                // Incus did not answer as asked: worth a retry, unlike a bug in isx.
                result = ToolResult.error(ToolError.Code.UNAVAILABLE, "Incus failed during " + name + ": " + e.getMessage(),
                        args.node());
            } catch (Exception e) {
                result = ToolResult.error(ToolError.Code.INTERNAL, "isx failed running " + name + ": " + e.getMessage(),
                        args.node());
                System.err.println("isx mcp: " + name + " failed:");
                e.printStackTrace();
            }
            try {
                if (!ctx.cancelled()) send(JsonRpc.response(id, result.toJson()));
            } finally {
                // Only once answered: a call is in flight until its response is out.
                inFlight.remove(idKey);
            }
        });
    }

    private ToolContext.ProgressSink progressSink(JqValue token) {
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
                var params = JqObject.builder(3)
                        .put("progressToken", token)
                        .put("progress", (long) state.count)
                        .put("message", message)
                        .build();
                notify("notifications/progress", params);
            }
        };
    }

    /** Send a notification the client did not ask for by request: one it opted into. */
    void notify(String method, JqValue params) {
        send(JsonRpc.notification(method, params));
    }

    private void send(JqValue message) {
        try {
            transport.send(JsonRpc.write(message));
        } catch (IOException e) {
            System.err.println("isx mcp: could not send a message: " + e.getMessage());
        }
    }
}
