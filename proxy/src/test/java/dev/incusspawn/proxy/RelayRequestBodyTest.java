package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A relayed request's body reaches upstream however fast it arrives (#1164). The whole request is
 * one write, so it has all reached the proxy before the relay's upstream connection is ready, which
 * is how npm's audit POST arrived after an install left no idle connection to the registry.
 */
class RelayRequestBodyTest {

    /** One per routing branch that relays: npm, a container registry, a Maven repository. */
    static final List<String> HOSTS = List.of("registry.npmjs.org", "ghcr.io", "repo1.maven.org");

    @TempDir
    static Path tempHome;

    static String origHome;
    static Vertx vertx;
    static MitmProxy proxy;
    static NetClient client;
    static int mitmPort;
    /** What each upstream received, by host. */
    static final Map<String, CompletableFuture<Received>> received = new ConcurrentHashMap<>();

    record Received(String method, String path, String contentEncoding, byte[] body) {}

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));

        var ca = CertificateAuthority.loadOrCreate();
        vertx = Vertx.vertx();
        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1",
                new ProxyCredentials("", "", false, "", "", List.of()));
        proxy.trustUpstreamCertificate(SpawnConfig.configDir().resolve("ca.crt").toString());
        var spec = new StringBuilder();
        for (var host : HOSTS) {
            var server = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                            .setKeyCertOptions(NpmCacheProxyTest.keyCert(ca, host)))
                    .requestHandler(req -> answer(host, req));
            if (!spec.isEmpty()) spec.append(',');
            spec.append(host).append("=127.0.0.1:").append(NpmCacheProxyTest.listen(server));
        }
        assertTrue(ProxyMain.applyBenchUpstream(proxy, spec.toString(), ""));
        ContainerTls.startInBackground(proxy);
        mitmPort = proxy.mitmPort();

        client = vertx.createNetClient(new NetClientOptions()
                .setSsl(true).setTrustAll(true).setHostnameVerificationAlgorithm(""));
    }

    /** Records the request and closes the connection, so the proxy's pool never has one ready. */
    static void answer(String host, HttpServerRequest req) {
        req.body().onSuccess(body -> {
            received.computeIfAbsent(host, h -> new CompletableFuture<>()).complete(
                    new Received(req.method().name(), req.path(), req.getHeader("Content-Encoding"), body.getBytes()));
            req.response().putHeader("Connection", "close").putHeader("Content-Type", "application/json").end("{}");
        });
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

    /** npm's audit request: a gzipped JSON body, sent with its length. */
    @ParameterizedTest
    @FieldSource("HOSTS")
    void bodyWithLengthReachesUpstream(String host) throws Exception {
        var body = gzip("{\"left-pad\":[\"1.3.0\"]}");
        var head = "POST /-/npm/v1/security/advisories/bulk HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Encoding: gzip\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        var answer = exchange(host, Buffer.buffer(head).appendBytes(body));

        assertTrue(answer.startsWith("HTTP/1.1 200"), answer);
        var got = received.get(host).get(5, TimeUnit.SECONDS);
        assertEquals("POST", got.method());
        assertEquals("/-/npm/v1/security/advisories/bulk", got.path());
        assertEquals("gzip", got.contentEncoding());
        assertArrayEquals(body, got.body());
    }

    @Test
    void chunkedBodyReachesUpstream() throws Exception {
        var host = "registry.npmjs.org";
        var head = "PUT /left-pad HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n"
                + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n";
        var answer = exchange(host, Buffer.buffer(head));

        assertTrue(answer.startsWith("HTTP/1.1 200"), answer);
        var got = received.get(host).get(5, TimeUnit.SECONDS);
        assertEquals("PUT", got.method());
        assertEquals("hello world", new String(got.body(), StandardCharsets.UTF_8));
    }

    /**
     * Clients that leave before their body is all sent do not keep upstream connections held: more of
     * them than the proxy's pool to a host holds would leave every later relay there waiting for one.
     */
    @Test
    void abandonedUploadsLetTheirUpstreamConnectionsGo() throws Exception {
        var host = "ghcr.io";
        for (int i = 0; i < 25; i++) {
            var socket = client.connect(mitmPort, "127.0.0.1", host)
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            socket.write(Buffer.buffer("PATCH /v2/app/blobs/uploads/" + i + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Content-Length: 100\r\n\r\n"
                    + "only some of it"));
            socket.close();
        }

        var answer = exchange(host, Buffer.buffer("POST /v2/app/blobs/uploads/ HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Content-Length: 2\r\n"
                + "Connection: close\r\n\r\n{}"));
        assertTrue(answer.startsWith("HTTP/1.1 200"), answer);
        assertEquals("/v2/app/blobs/uploads/", received.get(host).get(5, TimeUnit.SECONDS).path());
    }

    /**
     * A body that has all arrived starts the client's silence budget, so a connect upstream never
     * completes is cut at the budget, not left to the TLS handshake's own timeout (#929).
     */
    @Test
    void stalledConnectAfterTheBodyIsCutAtTheBudget() throws Exception {
        var host = "quay.io";
        // Accepts the connection and never answers the handshake
        var blackHole = vertx.createNetServer().connectHandler(socket -> socket.handler(ignored -> {}));
        var port = blackHole.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS).actualPort();
        proxy.overrideUpstream(host, "127.0.0.1", port);
        var budget = proxy.upstream.clientSilenceBudgetSeconds;
        proxy.upstream.clientSilenceBudgetSeconds = 2;
        try {
            var answer = exchange(host, Buffer.buffer("POST /v2/app/blobs/uploads/ HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Content-Length: 2\r\n"
                    + "Connection: close\r\n\r\n{}"), 6);
            assertTrue(answer.startsWith("HTTP/1.1 502"), answer);
            assertTrue(answer.contains("Upstream timed out"), answer);
        } finally {
            proxy.upstream.clientSilenceBudgetSeconds = budget;
            blackHole.close();
        }
    }

    /** Sends the request in one write and returns everything the proxy answers until it closes. */
    static String exchange(String host, Buffer request) throws Exception {
        return exchange(host, request, 10);
    }

    static String exchange(String host, Buffer request, int seconds) throws Exception {
        received.clear();
        var answer = new CompletableFuture<String>();
        var bytes = new ByteArrayOutputStream();
        client.connect(mitmPort, "127.0.0.1", host).onSuccess(socket -> {
            socket.handler(b -> bytes.writeBytes(b.getBytes()));
            socket.closeHandler(v -> answer.complete(bytes.toString(StandardCharsets.UTF_8)));
            socket.write(request);
        }).onFailure(answer::completeExceptionally);
        try {
            return answer.get(seconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return fail("No answer from the proxy within " + seconds + "s; got so far: '" + bytes + "'");
        }
    }

    static byte[] gzip(String text) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
