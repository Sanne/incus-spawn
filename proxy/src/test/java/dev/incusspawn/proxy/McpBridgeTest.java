package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code https://mcp.isx.internal/mcp} (#915): who the proxy lets reach {@code isx mcp}, and how
 * it bridges MCP's Streamable HTTP to the session's stdio. The session's process is a bash
 * stand-in that answers every request with its method and the instance it serves, so these
 * tests see exactly what reached it.
 */
class McpBridgeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "a".repeat(64);
    private static final String CALLER = "coord";

    @TempDir
    static Path home;
    static String origHome;
    static Vertx vertx;
    static Vertx clientVertx;
    static HttpClient client;
    /**
     * Every client call starts on this one context, never on the test thread: a response whose
     * body arrives before a handler registered from another thread loses it, which is how a
     * refusal once read as an empty body (#880 is the same pattern in ArtifactCacheProxyTest).
     */
    static Context clientContext;
    static MitmProxy proxy;
    static int port;
    static Path pids;
    static volatile String listing;
    static InstanceRegistry registry;

    /** Answers each request with {@code {"method":..., "instance":...}}; "notify" also notifies. */
    private static final String STAND_IN = """
            echo $$ >> "$2"
            while IFS= read -r line; do
              id=$(printf '%s' "$line" | sed -n 's/.*"id":\\([0-9]*\\).*/\\1/p')
              method=$(printf '%s' "$line" | sed -n 's/.*"method":"\\([^"]*\\)".*/\\1/p')
              if [ "$method" = notify ]; then
                printf '{"jsonrpc":"2.0","method":"notifications/isx/task_changed","params":{"task_id":"t1"}}\\n'
              fi
              if [ "$method" = slow ]; then sleep 1; fi
              if [ -n "$id" ]; then
                printf '{"jsonrpc":"2.0","id":%s,"result":{"method":"%s","instance":"%s"}}\\n' "$id" "$method" "$1"
              fi
            done
            """;

    @BeforeAll
    static void startProxy() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".config/incus-spawn"));
        CertificateAuthority.loadOrCreate();
        var script = Files.writeString(home.resolve("isx-mcp-stand-in.sh"), STAND_IN);
        pids = home.resolve("pids");

        vertx = Vertx.vertx();
        port = WebSocketProxyTest.findFreePort();
        proxy = new MitmProxy(vertx, "127.0.0.1", port, WebSocketProxyTest.findFreePort(), "127.0.0.1",
                new ProxyCredentials("", "", false, "", "", List.of()));
        registry = new InstanceRegistry(new IncusClient() {
            @Override
            public String listJsonConfig() {
                return listing;
            }
        });
        proxy.useInstanceRegistry(registry);
        proxy.useMcpCommand(instance -> List.of("bash", script.toString(), instance, pids.toString()));
        var ready = new CompletableFuture<Void>();
        var thread = new Thread(() -> {
            try {
                proxy.start(() -> ready.complete(null));
            } catch (Exception e) {
                ready.completeExceptionally(e);
            }
        }, "test-proxy");
        thread.setDaemon(true);
        thread.start();
        ready.get(15, TimeUnit.SECONDS);
        clientVertx = Vertx.vertx();
        clientContext = clientVertx.getOrCreateContext();
        client = clientVertx.createHttpClient(new HttpClientOptions().setSsl(true).setTrustAll(true)
                .setVerifyHost(false).setForceSni(true));
    }

    @AfterAll
    static void stopProxy() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (clientVertx != null) clientVertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @BeforeEach
    void callerIsStamped() throws Exception {
        instance(true);
        Files.deleteIfExists(pids);
    }

    /** The instance at 127.0.0.1, this start's secret recorded, and the stamp if {@code caller}. */
    private static void instance(boolean caller) {
        listing = """
                [{"name":"%s","status":"Running","config":{
                   "%s":"127.0.0.1", "%s":"%s"%s}}]
                """.formatted(CALLER, Metadata.STATIC_IP, Metadata.INSTANCE_SECRET_SHA256,
                InstanceSecret.sha256(SECRET),
                caller ? ", \"" + Metadata.MCP_CALLER + "\":\"" + Metadata.newMcpCallerGrant() + "\"" : "");
        registry.refresh();
    }

    record Reply(int status, MultiMap headers, String body) {
        /** The JSON-RPC messages of a {@code text/event-stream} body. */
        List<JsonNode> messages() {
            return body.lines().filter(l -> l.startsWith("data: ")).map(l -> {
                try {
                    return JSON.readTree(l.substring(6));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }).toList();
        }
    }

    private static RequestOptions options(HttpMethod method, String path, String secret, String session) {
        var options = new RequestOptions().setMethod(method)
                .setServer(SocketAddress.inetSocketAddress(port, "127.0.0.1"))
                .setHost(ProxyConfig.MCP_DOMAIN).setPort(443).setURI(path)
                .putHeader("Accept", "application/json, text/event-stream");
        if (secret != null) options.putHeader(InstanceSecret.HEADER, secret);
        if (session != null) options.putHeader(McpBridge.SESSION_HEADER, session);
        return options;
    }

    private static Reply send(HttpMethod method, String path, String secret, String session, String body)
            throws Exception {
        Future<Reply> reply = onClient(() -> client.request(options(method, path, secret, session))
                .compose(req -> (body == null ? req.send() : req.send(Buffer.buffer(body))))
                .compose(resp -> resp.body().map(b -> new Reply(resp.statusCode(), resp.headers(), b.toString()))));
        return reply.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    /** Runs {@code action} on {@link #clientContext}, completing with the future it returns. */
    private static <T> Future<T> onClient(java.util.function.Supplier<Future<T>> action) {
        var result = Promise.<T>promise();
        clientContext.runOnContext(v -> {
            try {
                action.get().onComplete(result);
            } catch (RuntimeException e) {
                result.fail(e);
            }
        });
        return result.future();
    }

    private static Reply post(String session, String body) throws Exception {
        return send(HttpMethod.POST, McpBridge.PATH, SECRET, session, body);
    }

    private static String initialize() throws Exception {
        var reply = post(null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertEquals(200, reply.status(), reply.body());
        var session = reply.headers().get(McpBridge.SESSION_HEADER);
        assertNotNull(session, "initialize names the session");
        return session;
    }

    private static List<String> startedProcesses() throws Exception {
        return Files.exists(pids) ? Files.readAllLines(pids) : List.of();
    }

    @Test
    void anInstanceNotBranchedAsAClientIsRefusedAndNothingStarts() throws Exception {
        instance(false);
        var reply = post(null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
        assertEquals(403, reply.status());
        assertTrue(reply.body().contains("--mcp-client"), reply.body());
        assertEquals(List.of(), startedProcesses());
    }

    @Test
    void theAddressAloneIsNotEnough() throws Exception {
        var none = send(HttpMethod.POST, McpBridge.PATH, null, null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
        assertEquals(403, none.status());
        assertTrue(none.body().contains(InstanceSecret.HEADER), "body: [" + none.body() + "]");
        var wrong = send(HttpMethod.POST, McpBridge.PATH, "b".repeat(64), null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
        assertEquals(403, wrong.status());
        assertEquals(List.of(), startedProcesses());
    }

    @Test
    void aCallerGetsASessionServedForItself() throws Exception {
        var session = initialize();
        var reply = post(session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertEquals(200, reply.status());
        assertTrue(reply.headers().get("Content-Type").startsWith("text/event-stream"));
        var messages = reply.messages();
        assertEquals(1, messages.size(), reply.body());
        assertEquals(2, messages.getFirst().path("id").asInt());
        assertEquals("tools/list", messages.getFirst().path("result").path("method").asText());
        // The instance comes from the proxy's identification, never from anything it sent.
        assertEquals(CALLER, messages.getFirst().path("result").path("instance").asText());
        assertEquals(202, post(session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").status());
    }

    @Test
    void concurrentRequestsEachGetTheirOwnAnswer() throws Exception {
        var session = initialize();
        var slow = CompletableFuture.supplyAsync(() -> {
            try {
                return post(session, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"slow\"}");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(200);
        var fast = post(session, "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/list\"}");
        assertEquals(8, fast.messages().getFirst().path("id").asInt());
        assertEquals(7, slow.get(10, TimeUnit.SECONDS).messages().getFirst().path("id").asInt());
    }

    @Test
    void withoutItsSessionARequestIsSentBackToInitialize() throws Exception {
        initialize();
        assertEquals(404, post(null, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").status());
        assertEquals(404, post("not-the-session", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").status());
    }

    @Test
    void aBatchIsRefused() throws Exception {
        var session = initialize();
        assertEquals(400, post(session, "[{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}]").status());
    }

    @Test
    void notificationsReachTheStream() throws Exception {
        var session = initialize();
        var received = new CompletableFuture<String>();
        var text = new StringBuilder();
        // The handler is set in the same turn as the response arrives, on the client's context.
        onClient(() -> client.request(options(HttpMethod.GET, McpBridge.PATH, SECRET, session)).compose(req -> req.send())
                .onSuccess(resp -> resp.handler(chunk -> {
                    synchronized (text) {
                        text.append(chunk.toString());
                        if (text.toString().contains("task_changed")) received.complete(text.toString());
                    }
                })));
        Thread.sleep(300);
        var reply = post(session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"notify\"}");
        assertEquals(3, reply.messages().getFirst().path("id").asInt(), "the answer goes to its POST");
        assertFalse(reply.body().contains("task_changed"), "and the notification does not");
        assertTrue(received.get(10, TimeUnit.SECONDS).contains("\"task_id\":\"t1\""));
    }

    @Test
    void aNewSessionEndsTheInstancesPreviousOne() throws Exception {
        var first = initialize();
        var second = initialize();
        assertNotEquals(first, second);
        var started = startedProcesses();
        assertEquals(2, started.size());
        var previous = ProcessHandle.of(Long.parseLong(started.getFirst().strip()));
        assertTrue(previous.isEmpty() || !previous.get().isAlive(),
                "two processes serving one instance would each count only their own instances");
        assertEquals(404, post(first, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").status());
        assertEquals(200, post(second, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").status());
    }

    @Test
    void deleteEndsTheSession() throws Exception {
        var session = initialize();
        assertEquals(200, send(HttpMethod.DELETE, McpBridge.PATH, SECRET, session, null).status());
        assertEquals(404, post(session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").status());
    }

    @Test
    void onlyTheMcpPathIsServed() throws Exception {
        assertEquals(404, send(HttpMethod.GET, "/", SECRET, null, null).status());
    }
}
