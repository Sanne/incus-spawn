package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code https://mcp.isx.internal/mcp}: {@code isx mcp} for a coordinating agent that runs in an
 * isx instance (#915), served over MCP's Streamable HTTP transport.
 *
 * <p><b>Who may call.</b> An instance stamped {@code mcp-caller} ({@code isx branch --mcp-client}),
 * identified by its source address <em>and</em> the per-start secret it presents
 * ({@link InstanceSecret#HEADER}): {@link InstanceRegistry#identifyMcpCaller}. Anything else is
 * refused before its body is read. The session is bound to the instance that opened it: a
 * session id presented from another instance is unknown there.
 *
 * <p><b>What serves it.</b> The MCP server lives in the CLI, which the proxy cannot run in
 * process, so each session is a host process, {@code isx mcp --caller-instance <name>}, whose
 * stdio this bridges: every POSTed message is one line on its stdin, and every line it writes
 * goes back as the answer to the POST that asked (matched by JSON-RPC id), or else -- a
 * notification -- on the session's GET stream. One process per instance: a new
 * {@code initialize} from it ends the previous one first, because two processes serving one
 * instance session would each count only their own instances against {@code mcp.max-instances}.
 *
 * <p><b>Contract.</b> POST a JSON-RPC message (no batches) with {@code Mcp-Session-Id} after
 * {@code initialize}: a request is answered as {@code text/event-stream} carrying its one
 * response, with comment lines meanwhile so that a tool running for an hour does not trip the
 * proxy's idle timeout; a notification or response is answered 202. GET opens the stream for
 * server notifications (one at a time; a new one replaces the old). DELETE ends the session.
 * An unknown or ended session is 404, which tells the client to {@code initialize} again.
 */
final class McpBridge {

    static final String PATH = "/mcp";
    static final String SESSION_HEADER = "Mcp-Session-Id";
    /** Larger than any message a tool accepts; a body past it is refused unread. */
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    /** Under the MITM server's idle timeout, which would otherwise drop a long tool call. */
    static final long KEEPALIVE_MILLIS = 30_000;
    /** Notifications kept for a GET stream not yet opened; the oldest go first. */
    static final int MAX_BUFFERED = 1_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Vertx vertx;
    private final Supplier<InstanceRegistry> registry;
    private final Function<String, List<String>> command;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /** Held while a session starts: initializes are rare, so one lock for all instances. */
    private final Object startLock = new Object();
    private long keepaliveTimer = -1;

    /**
     * @param command the process serving one instance's session, given its name
     */
    McpBridge(Vertx vertx, Supplier<InstanceRegistry> registry, Function<String, List<String>> command) {
        this.vertx = vertx;
        this.registry = registry;
        this.command = command;
    }

    /** The command {@code isx-proxy} runs: the {@code isx} installed beside it. */
    static List<String> isxMcp(String instance) {
        var self = ProcessHandle.current().info().command().map(Path::of).orElse(null);
        var isx = java.util.Objects.requireNonNullElse(ProxyService.isxNextTo(self), "isx");
        return List.of(isx, "mcp", "--caller-instance", instance);
    }

    void start() {
        keepaliveTimer = vertx.setPeriodic(KEEPALIVE_MILLIS, id -> {
            var current = registry.get();
            sessions.values().forEach(s -> {
                // Ended with the stamp, or the instance: nothing it holds is served any more.
                if (current != null && !current.isMcpCaller(s.instance)) {
                    ProxyLog.info("MCP session of " + s.instance + " ended: it may no longer call isx mcp");
                    end(s);
                } else {
                    s.keepalive();
                }
            });
        });
    }

    void stop() {
        if (keepaliveTimer >= 0) vertx.cancelTimer(keepaliveTimer);
        sessions.values().forEach(s -> s.process.destroy());
        sessions.clear();
    }

    /** Serve one request to {@link ProxyConfig#MCP_DOMAIN}. */
    void handle(HttpServerRequest req, String address) {
        req.pause();
        if (!PATH.equals(req.path())) {
            refuse(req, 404, "isx mcp is served at https://" + ProxyConfig.MCP_DOMAIN + PATH);
            return;
        }
        var secret = req.getHeader(InstanceSecret.HEADER);
        var current = registry.get();
        if (current == null) {
            refuse(req, 503, "the proxy cannot tell instances apart yet; try again shortly");
            return;
        }
        var instance = current.identifyMcpCaller(address, secret);
        if (instance != null) {
            serve(req, instance);
        } else if (current.wantsMissRefresh()) {
            // A start rotates the secret without telling the proxy: one refresh, then decide.
            vertx.executeBlocking(() -> {
                current.refresh();
                return current.identifyMcpCaller(address, secret);
            }, false).onComplete(ar -> {
                if (ar.succeeded() && ar.result() != null) serve(req, ar.result());
                else refuse(req, 403, whyNot(current, address, secret));
            });
        } else {
            refuse(req, 403, whyNot(current, address, secret));
        }
    }

    private static String whyNot(InstanceRegistry registry, String address, String secret) {
        var instance = registry.lookup(address);
        if (instance == null) return "only an isx instance may call isx mcp";
        if (registry.identify(address, secret) == null) {
            return "send this start's secret as " + InstanceSecret.HEADER + " (from $"
                    + InstanceSecret.FILE_ENV_VAR + ")";
        }
        return "instance '" + instance.instanceName() + "' may not call isx mcp: only one branched "
                + "with 'isx branch --mcp-client' may";
    }

    private void serve(HttpServerRequest req, String instance) {
        var method = req.method();
        if (method == HttpMethod.POST) {
            readBody(req, body -> post(req, instance, body));
            return;
        }
        var session = sessionOf(req, instance);
        if (session == null) {
            refuse(req, 404, "no such MCP session; initialize again");
        } else if (method == HttpMethod.GET) {
            session.openStream(req.response());
            req.resume();
        } else if (method == HttpMethod.DELETE) {
            end(session);
            req.resume();
            req.response().setStatusCode(200).end();
        } else {
            refuse(req, 405, "POST, GET or DELETE");
        }
    }

    private Session sessionOf(HttpServerRequest req, String instance) {
        var id = req.getHeader(SESSION_HEADER);
        var session = sessions.get(instance);
        return session != null && session.id.equals(id) ? session : null;
    }

    private void readBody(HttpServerRequest req, java.util.function.Consumer<Buffer> then) {
        var length = req.getHeader("Content-Length");
        try {
            if (length != null && Long.parseLong(length.strip()) > MAX_BODY_BYTES) {
                refuse(req, 413, "a message is limited to " + MAX_BODY_BYTES + " bytes");
                return;
            }
        } catch (NumberFormatException e) {
            refuse(req, 400, "bad Content-Length");
            return;
        }
        var body = Buffer.buffer();
        var refused = new boolean[1];
        req.handler(chunk -> {
            if (refused[0]) return;
            if (body.length() + chunk.length() > MAX_BODY_BYTES) {
                refused[0] = true;
                refuse(req, 413, "a message is limited to " + MAX_BODY_BYTES + " bytes");
                return;
            }
            body.appendBuffer(chunk);
        });
        req.endHandler(v -> {
            if (!refused[0]) then.accept(body);
        });
        req.resume();
    }

    private void post(HttpServerRequest req, String instance, Buffer body) {
        JsonNode message;
        try {
            message = JSON.readTree(body.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            refuse(req, 400, "not JSON: " + e.getMessage());
            return;
        }
        if (message == null || !message.isObject()) {
            refuse(req, 400, "one JSON-RPC message per POST; batches are not accepted");
            return;
        }
        // One line on the child's stdin: re-serialized, so it holds no newline whatever was sent.
        var line = message.toString();
        var isRequest = message.hasNonNull("method") && message.has("id");
        if (isRequest && "initialize".equals(message.path("method").asText())) {
            vertx.executeBlocking(() -> startSession(instance), false).onComplete(ar -> {
                if (ar.failed()) {
                    ProxyLog.warn("Could not start isx mcp for " + instance + ": " + ar.cause().getMessage());
                    refuse(req, 502, "could not start isx mcp on the host: " + ar.cause().getMessage());
                    return;
                }
                var session = ar.result();
                req.response().putHeader(SESSION_HEADER, session.id);
                session.request(message.get("id"), line, req.response());
            });
            return;
        }
        var session = sessionOf(req, instance);
        if (session == null) {
            refuse(req, 404, "no such MCP session; initialize again");
        } else if (isRequest) {
            session.request(message.get("id"), line, req.response());
        } else {
            session.write(line);
            req.response().setStatusCode(202).end();
        }
    }

    /** End the instance's previous session, if any, and start its process. Blocks. */
    private Session startSession(String instance) throws IOException {
        synchronized (startLock) {
            var previous = sessions.remove(instance);
            if (previous != null) {
                // Gone before the next one starts: both would hold the same instances.
                previous.process.destroy();
                try {
                    if (!previous.process.waitFor(10, TimeUnit.SECONDS)) {
                        previous.process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while ending the previous session");
                }
            }
            var process = new ProcessBuilder(command.apply(instance))
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            // Random and unguessable, though it is only valid from the instance that opened it.
            var session = new Session(instance, InstanceSecret.generate(), process);
            sessions.put(instance, session);
            session.startPumps();
            ProxyLog.info("MCP session for " + instance + " started (isx mcp pid " + process.pid() + ")");
            return session;
        }
    }

    private void end(Session session) {
        sessions.remove(session.instance, session);
        session.process.destroy();
    }

    private static void refuse(HttpServerRequest req, int status, String message) {
        req.resume();
        var resp = req.response();
        if (resp.ended() || resp.closed()) return;
        try {
            resp.setStatusCode(status).putHeader("Content-Type", "text/plain; charset=utf-8").end(message + "\n");
        } catch (IllegalStateException ignored) {
            // the client went away meanwhile
        }
    }

    /** Start {@code resp} as an event stream. */
    private static void beginEventStream(HttpServerResponse resp) {
        if (resp.headWritten()) return;
        resp.setChunked(true).setStatusCode(200)
                .putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache");
    }

    /**
     * Write {@code text} (if any) to {@code resp}, ending it if {@code end}; nothing once the
     * client has gone.
     */
    private static void send(HttpServerResponse resp, String text, boolean end) {
        try {
            if (resp.ended() || resp.closed()) return;
            if (end) {
                if (text == null) resp.end();
                else resp.end(text);
            } else {
                resp.write(text);
            }
        } catch (IllegalStateException ignored) {
            // the client went away meanwhile
        }
    }

    private static String event(String message) {
        return "event: message\ndata: " + message + "\n\n";
    }

    /** A request waiting for its response. */
    private record Pending(JsonNode id, HttpServerResponse resp) {}

    /** One instance's session: its {@code isx mcp} process and the exchanges waiting on it. */
    private final class Session {
        final String instance;
        final String id;
        final Process process;
        private final LinkedBlockingQueue<String> toChild = new LinkedBlockingQueue<>();
        /** By the JSON text of the request's id. */
        private final Map<String, Pending> pending = new HashMap<>();
        private final ArrayDeque<String> buffered = new ArrayDeque<>();
        private HttpServerResponse stream;
        private boolean ended;
        private Thread stdinPump;

        Session(String instance, String id, Process process) {
            this.instance = instance;
            this.id = id;
            this.process = process;
        }

        void startPumps() {
            stdinPump = Thread.ofVirtual().name("isx-mcp-in-" + instance).start(() -> {
                try (OutputStream in = process.getOutputStream()) {
                    while (true) {
                        var line = toChild.take();
                        in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        in.flush();
                    }
                } catch (IOException | InterruptedException e) {
                    // the process ended; onExit reports it
                }
            });
            Thread.ofVirtual().name("isx-mcp-out-" + instance).start(() -> {
                try (var out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                        if (!line.isBlank()) fromChild(line);
                    }
                } catch (IOException e) {
                    // the process ended
                }
            });
            process.onExit().thenRun(this::exited);
        }

        void write(String line) {
            toChild.add(line);
        }

        synchronized void request(JsonNode id, String line, HttpServerResponse resp) {
            if (ended) {
                answer(resp, error(id, "isx mcp has ended; initialize again"));
                return;
            }
            beginEventStream(resp);
            pending.put(id.toString(), new Pending(id, resp));
            write(line);
        }

        synchronized void openStream(HttpServerResponse resp) {
            if (stream != null) send(stream, null, true);
            stream = resp;
            beginEventStream(resp);
            resp.closeHandler(v -> {
                synchronized (Session.this) {
                    if (stream == resp) stream = null;
                }
            });
            send(resp, ": " + instance + "\n\n", false);
            while (!buffered.isEmpty()) send(resp, event(buffered.poll()), false);
        }

        private void fromChild(String line) {
            // Parsed outside the lock, which the event loop takes too: a line may be megabytes.
            JsonNode message;
            try {
                message = JSON.readTree(line);
            } catch (IOException e) {
                ProxyLog.warn("isx mcp for " + instance + " wrote a line that is not JSON; dropped");
                return;
            }
            var isResponse = message.isObject() && !message.has("method") && message.has("id")
                    && (message.has("result") || message.has("error"));
            synchronized (this) {
                if (isResponse) {
                    // Its asker may have left; a response never goes to the stream.
                    var waiting = pending.remove(message.get("id").toString());
                    if (waiting != null) answer(waiting.resp(), line);
                } else if (stream != null) {
                    send(stream, event(line), false);
                } else {
                    if (buffered.size() >= MAX_BUFFERED) buffered.poll();
                    buffered.add(line);
                }
            }
        }

        synchronized void keepalive() {
            pending.values().forEach(p -> send(p.resp(), ": keepalive\n\n", false));
            if (stream != null) send(stream, ": keepalive\n\n", false);
        }

        private synchronized void exited() {
            ended = true;
            // Blocked on the queue otherwise, holding this session for the proxy's lifetime.
            stdinPump.interrupt();
            sessions.remove(instance, this);
            ProxyLog.info("MCP session for " + instance + " ended (isx mcp exit " + process.exitValue() + ")");
            pending.values().forEach(p -> answer(p.resp(), error(p.id(), "isx mcp ended before answering; initialize again")));
            pending.clear();
            if (stream != null) send(stream, null, true);
            stream = null;
        }

        private static String error(JsonNode id, String message) {
            ObjectNode node = JSON.createObjectNode();
            node.put("jsonrpc", "2.0");
            node.set("id", id);
            node.putObject("error").put("code", -32603).put("message", message);
            return node.toString();
        }

        private static void answer(HttpServerResponse resp, String line) {
            try {
                if (!resp.ended() && !resp.closed()) beginEventStream(resp);
            } catch (IllegalStateException ignored) {
                return;
            }
            send(resp, event(line), true);
        }
    }
}
