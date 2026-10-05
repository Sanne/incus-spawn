package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.incus.IncusClient;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /activity} (#898): an instance's model calls through the proxy, counted and read out of
 * their responses' usage, for the host to ask about without touching the instance.
 */
class ApiActivityProxyTest {

    private static final String ANTHROPIC = "api.anthropic.com";
    private static final String WORKER = "mcp-isx-870-impl-k3x9q";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path tempHome;
    static String origHome;
    static Vertx vertx;
    static MitmProxy proxy;
    static HttpServer upstream;
    static HttpClient client;
    static int mitmPort;
    static int healthPort;
    /** The Accept-Encoding each upstream path was asked with. */
    static final Map<String, String> acceptEncodings = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));

        vertx = Vertx.vertx();
        upstream = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(CertificateAuthority.loadOrCreate(), ANTHROPIC)))
                .requestHandler(ApiActivityProxyTest::answer);
        int upstreamPort = NpmCacheProxyTest.listen(upstream);

        mitmPort = WebSocketProxyTest.findFreePort();
        healthPort = WebSocketProxyTest.findFreePort();
        proxy = new MitmProxy(vertx, "127.0.0.1", mitmPort, healthPort, "127.0.0.1",
                new ProxyCredentials("sk-ant-api03-real", "", false, "", "", List.of()));
        proxy.upstreamTrustAll = true;
        var ready = new CountDownLatch(1);
        var thread = new Thread(() -> {
            try {
                proxy.start(ready::countDown);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "test-proxy");
        thread.setDaemon(true);
        thread.start();
        assertTrue(ready.await(15, TimeUnit.SECONDS), "Proxy did not start in time");
        proxy.overrideUpstream(ANTHROPIC, "127.0.0.1", upstreamPort);

        // The test client connects from 127.0.0.1, which the registry says is the worker
        var registry = new InstanceRegistry(new IncusClient() {
            @Override
            public String listJsonConfig() {
                return """
                        [{"name":"%s","config":{"user.incus-spawn.static-ip":"127.0.0.1"}}]
                        """.formatted(WORKER);
            }
        });
        registry.refresh();
        proxy.useInstanceRegistry(registry);

        client = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false).setKeepAlive(false));
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    static void answer(HttpServerRequest req) {
        var accept = req.getHeader("Accept-Encoding");
        acceptEncodings.put(req.path(), accept == null ? "" : accept);
        var resp = req.response();
        switch (req.path()) {
            case "/v1/messages" -> {
                if ("stream".equals(req.getParam("mode"))) {
                    resp.setChunked(true).putHeader("Content-Type", "text/event-stream");
                    // Split mid-line, as a real stream is: the usage must be read across chunks
                    resp.write("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":"
                            + "{\"input_tokens\":100,\"cache_read_input_tokens\":4000,");
                    resp.write("\"cache_creation_input_tokens\":50,\"output_tokens\":1}}}\n\n"
                            + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":"
                            + "{\"text\":\"the word \\\"usage\\\" in text\"}}\n\n");
                    resp.write("event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":12}}\n");
                    resp.end("data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":30}}\n\n");
                } else if ("fail".equals(req.getParam("mode"))) {
                    resp.setStatusCode(529).putHeader("Content-Type", "application/json")
                            .end("{\"type\":\"error\",\"usage\":{\"input_tokens\":999}}");
                } else {
                    resp.putHeader("Content-Type", "application/json")
                            .end("{\"type\":\"message\",\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}");
                }
            }
            case "/v1/messages/count_tokens" -> resp.putHeader("Content-Type", "application/json")
                    .end("{\"input_tokens\":500}");
            default -> resp.putHeader("Content-Type", "application/json").end("{}");
        }
    }

    static int post(String uri) throws Exception {
        var options = new RequestOptions()
                .setMethod(HttpMethod.POST)
                .setServer(SocketAddress.inetSocketAddress(mitmPort, "127.0.0.1"))
                .setHost(ANTHROPIC)
                .setPort(443)
                .setURI(uri);
        return client.request(options)
                .compose(req -> req.putHeader("Accept-Encoding", "gzip, br").send(Buffer.buffer("{}")))
                .compose(resp -> resp.body().map(b -> resp.statusCode()))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    static JsonNode activity() throws Exception {
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            var resp = http.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + healthPort + "/activity")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(), resp.body());
            return JSON.readTree(resp.body());
        }
    }

    @Test
    void countsAnInstancesModelCallsAndTheTokensTheirResponsesReport() throws Exception {
        var before = System.currentTimeMillis();
        assertEquals(200, post("/v1/messages?mode=stream"));
        assertEquals(200, post("/v1/messages"));
        assertEquals(529, post("/v1/messages?mode=fail"));
        // Not model calls: an idle agent's other traffic must not make it look busy
        assertEquals(200, post("/v1/messages/count_tokens"));
        assertEquals(200, post("/api/oauth/profile"));

        var worker = activity().path("instances").path(WORKER);
        assertEquals(3, worker.path("requests").asLong(), worker.toString());
        assertTrue(worker.path("counting_since").asLong() >= before, "from its first call");
        assertEquals(0, worker.path("in_flight").asLong());
        assertTrue(worker.path("last_request_at").asLong() >= before);
        assertTrue(worker.path("last_response_at").asLong() >= worker.path("last_request_at").asLong());
        // The stream's message_delta counts are cumulative: 30, not 1 + 12 + 30; a failed call reports none
        assertEquals(107, worker.path("input_tokens").asLong());
        assertEquals(33, worker.path("output_tokens").asLong());
        assertEquals(4000, worker.path("cache_read_input_tokens").asLong());
        assertEquals(50, worker.path("cache_creation_input_tokens").asLong());

        // Asked uncompressed, or the usage could not be read; other requests are left as sent
        assertEquals("identity", acceptEncodings.get("/v1/messages"));
        assertEquals("gzip, br", acceptEncodings.get("/api/oauth/profile"));
    }

}
