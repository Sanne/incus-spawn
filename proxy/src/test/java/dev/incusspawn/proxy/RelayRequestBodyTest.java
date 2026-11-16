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

    record Received(String method, String path, String contentEncoding, byte[] body, io.vertx.core.MultiMap headers) {}

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
                            .setHandle100ContinueAutomatically(true)
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

    /** Completed once a {@code /stream/} upload has brought upstream its first 10 bytes. */
    static volatile CompletableFuture<Buffer> streamStarted = new CompletableFuture<>();

    /** More than the kernel buffers between the proxy and a client that does not read can take. */
    static final int LONG_ANSWER = 16 * 1024 * 1024;

    /** Records the request and closes the connection, so the proxy's pool never has one ready. */
    static void answer(String host, HttpServerRequest req) {
        if (req.path().startsWith("/answer-at-once/")) {
            // Answers before it has the upload: a long body first, so the client can hold it back,
            // and the end once the upload has all come, with how much of it did
            var resp = req.response().setChunked(true);
            resp.write(Buffer.buffer(new byte[LONG_ANSWER]));
            var got = new long[1];
            req.handler(chunk -> got[0] += chunk.length());
            req.endHandler(v -> resp.end("done:" + got[0]));
            return;
        }
        if (req.path().startsWith("/answer-then-stall/")) {
            // Answers before it has the upload, reads the upload, and never sends another byte
            req.handler(ignored -> {});
            req.response().setChunked(true).write("started");
            return;
        }
        if (req.path().startsWith("/early/")) {
            // Refuses the upload on its head, and reads the rest, as nginx does before it closes
            req.handler(ignored -> {});
            req.response().setStatusCode(413).putHeader("Connection", "close").end("too large");
            return;
        }
        if (req.path().startsWith("/throttled/")) {
            req.response().setStatusCode(429).putHeader("Retry-After", "17").putHeader("RateLimit-Remaining", "0")
                    .putHeader("Connection", "close").end("slow down");
            return;
        }
        if (req.path().startsWith("/stream/")) {
            var body = Buffer.buffer();
            req.handler(chunk -> {
                body.appendBuffer(chunk);
                if (body.length() >= 10) streamStarted.complete(body.copy());
            });
            req.endHandler(v -> record(host, req, body));
            return;
        }
        req.body().onSuccess(body -> record(host, req, body));
    }

    static void record(String host, HttpServerRequest req, Buffer body) {
        received.computeIfAbsent(host, h -> new CompletableFuture<>()).complete(
                new Received(req.method().name(), req.path(), req.getHeader("Content-Encoding"), body.getBytes(),
                        io.vertx.core.MultiMap.caseInsensitiveMultiMap().addAll(req.headers())));
        req.response().putHeader("Connection", "close").putHeader("Content-Type", "application/json").end("{}");
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

    /** Hop-by-hop headers are for the guest's hop to the proxy, not for upstream (#1360, RFC 9110 7.6.1). */
    @Test
    void hopByHopHeadersStayOnTheGuestsHop() throws Exception {
        var host = "registry.npmjs.org";
        var head = "GET /left-pad HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Connection: close\r\n"
                + "Connection: X-Foo\r\n"
                + "X-Foo: 1\r\n"
                + "TE: trailers\r\n"
                + "Keep-Alive: timeout=5\r\n"
                + "Proxy-Connection: keep-alive\r\n"
                + "Proxy-Authorization: Basic Zm9vOmJhcg==\r\n"
                + "X-Kept: 2\r\n\r\n";
        var answer = exchange(host, Buffer.buffer(head));

        assertTrue(answer.startsWith("HTTP/1.1 200"), answer);
        var headers = received.get(host).get(5, TimeUnit.SECONDS).headers();
        for (var name : List.of("X-Foo", "TE", "Keep-Alive", "Proxy-Connection", "Proxy-Authorization")) {
            assertNull(headers.get(name), name + " reached upstream");
        }
        assertEquals("2", headers.get("X-Kept"));
        assertEquals(host, headers.get("Host"));
    }

    /** Naming what frames the body in Connection does not break the upload (#1360). */
    @Test
    void connectionCannotNameTheBodysFramingAway() throws Exception {
        var host = "registry.npmjs.org";
        var head = "POST /left-pad HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Connection: close\r\n"
                + "Connection: Content-Length, Expect\r\n"
                + "Expect: 100-continue\r\n"
                + "Content-Length: 5\r\n\r\nhello";
        var answer = exchange(host, Buffer.buffer(head));

        assertTrue(answer.contains("HTTP/1.1 200"), answer);
        assertEquals("hello", new String(received.get(host).get(5, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8));
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

    /**
     * An upload is streamed upstream as it arrives, not held whole in the proxy's heap first (#1175):
     * upstream has the first half while the client still holds the second.
     */
    @Test
    void uploadReachesUpstreamBeforeItHasAllArrived() throws Exception {
        var host = "ghcr.io";
        streamStarted = new CompletableFuture<>();
        received.clear();
        var socket = connect(host);
        var answer = collectUntilClose(socket);
        socket.write(Buffer.buffer("PATCH /stream/v2/app/blobs/uploads/1 HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Content-Length: 20\r\n"
                + "Connection: close\r\n\r\n"
                + "first half"));
        try {
            assertEquals("first half", streamStarted.get(5, TimeUnit.SECONDS).getString(0, 10));
        } catch (java.util.concurrent.TimeoutException e) {
            fail("Upstream got nothing of the upload before all of it had reached the proxy");
        }

        socket.write(Buffer.buffer(", the rest"));
        var reply = answer.get(10, TimeUnit.SECONDS);
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
        assertEquals("first half, the rest",
                new String(received.get(host).get(5, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8));
    }

    /**
     * A relay that fails before it read the upload closes the client's connection rather than read
     * the rest only to discard it, and never leaves a keep-alive connection waiting on that body.
     */
    @Test
    void failedRelayClosesAKeepAliveConnectionItDidNotRead() throws Exception {
        var host = "quay.io";
        // A port nothing holds, so the connect is refused (ArtifactCacheProxyTest.refused)
        int port;
        try (var closed = new java.net.Socket()) {
            closed.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            port = closed.getLocalPort();
        }
        proxy.overrideUpstream(host, "127.0.0.1", port);
        var reply = exchange(host, Buffer.buffer("PATCH /v2/app/blobs/uploads/1 HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Content-Length: 100000\r\n\r\n"
                + "only the start of it"));
        assertTrue(reply.startsWith("HTTP/1.1 502"), reply);
    }

    /** A relayed domain's 429 reaches the client as upstream sent it: a client waits as long as its headers say (#2309). */
    @Test
    void throttledAnswerIsRelayedWithItsRetryHeaders() throws Exception {
        var host = "ghcr.io";
        var reply = exchange(host, Buffer.buffer("GET /throttled/v2/app/manifests/latest HTTP/1.1\r\n"
                + "Host: " + host + "\r\nConnection: close\r\n\r\n"));
        assertTrue(reply.startsWith("HTTP/1.1 429"), reply);
        var head = reply.toLowerCase(java.util.Locale.ROOT);
        assertTrue(head.contains("\r\nretry-after: 17\r\n"), reply);
        assertTrue(head.contains("\r\nratelimit-remaining: 0\r\n"), reply);
        assertTrue(reply.endsWith("slow down"), reply);
    }

    /** Upstream's answer to an upload it refuses early reaches the client before the upload ends. */
    @Test
    void earlyAnswerReachesTheClient() throws Exception {
        var host = "ghcr.io";
        // The rest never comes: the connection closes once it has paused for the linger
        ApiRequestBodyTest.withLinger(proxy, 200, 60_000, () -> {
            var reply = exchange(host, Buffer.buffer("PUT /early/v2/app/blobs/uploads/1 HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Content-Length: 100000\r\n\r\n"
                    + "only the start of it"));
            assertTrue(reply.startsWith("HTTP/1.1 413"), reply);
            assertTrue(reply.endsWith("too large"), reply);
        });
    }

    /**
     * A client still sending when upstream answers early may send the rest before it reads (#1623):
     * the proxy reads and drops it, and closes once it has ended, since closing with some of it
     * unread resets the connection, and a reset can take upstream's answer with it. The wait is
     * longer than the test, so only the upload's end can close the connection. A client that
     * awaited upstream's 100 Continue was told to send its body, and is read the same way.
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void earlyAnswerReadsTheRestBeforeClosing(boolean expectContinue) throws Exception {
        var host = "ghcr.io";
        ApiRequestBodyTest.withLinger(proxy, 60_000, 60_000, () -> {
            var socket = connect(host);
            var bytes = new StringBuffer();
            var answered = new CompletableFuture<Void>();
            var closed = new CompletableFuture<Void>();
            var errors = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
            socket.handler(b -> {
                bytes.append(b.toString(StandardCharsets.UTF_8));
                if (bytes.indexOf("too large") >= 0) answered.complete(null);
            });
            socket.exceptionHandler(errors::add);
            socket.closeHandler(v -> closed.complete(null));
            try {
                var body = "x".repeat(256 * 1024);
                // Awaiting the 100, the client sends nothing before the answer
                var split = expectContinue ? 0 : 128 * 1024;
                socket.write("PUT /early/v2/app/blobs/uploads/1 HTTP/1.1\r\n"
                        + "Host: " + host + "\r\n"
                        + (expectContinue ? "Expect: 100-continue\r\n" : "")
                        + "Content-Length: " + body.length() + "\r\n\r\n"
                        + body.substring(0, split));
                answered.get(5, TimeUnit.SECONDS);
                assertFalse(closed.isDone(), "closed before the upload had ended");
                var slices = 20;
                var slice = (body.length() - split + slices - 1) / slices;
                var writes = new java.util.ArrayList<io.vertx.core.Future<Void>>();
                for (int at = split; at < body.length(); at += slice) {
                    writes.add(socket.write(body.substring(at, Math.min(body.length(), at + slice))));
                    Thread.sleep(100);
                }
                closed.get(10, TimeUnit.SECONDS);
                for (var write : writes) {
                    assertDoesNotThrow(() -> write.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS),
                            "the rest of the upload could not be sent");
                }
                assertEquals(List.of(), errors, "the connection was reset");
                var reply = bytes.toString();
                assertTrue(reply.startsWith(expectContinue ? "HTTP/1.1 100" : "HTTP/1.1 413"), reply);
                assertTrue(reply.contains("HTTP/1.1 413"), reply);
            } finally {
                socket.close();
            }
        });
    }

    /**
     * While the client sends, the relay is never cut, also when upstream answered early and the
     * client has held that answer back and taken it again meanwhile.
     */
    @Test
    void uploadIsNotCutWhileTheClientSendsAfterAnEarlyAnswer() throws Exception {
        var host = "ghcr.io";
        withBudget(2, () -> {
            var socket = connect(host);
            socket.pause();
            var answer = collectUntilClose(socket);
            socket.write(Buffer.buffer("PUT /answer-at-once/v2/app/blobs/uploads/1 HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Content-Length: 9\r\n"
                    + "Connection: close\r\n\r\n"
                    + "a"));
            Thread.sleep(500);
            // Takes the early answer: the proxy's write queue to it drains
            socket.resume();
            // Twice the budget, a byte at a time
            for (var b : "bcdefghi".toCharArray()) {
                Thread.sleep(500);
                socket.write(String.valueOf(b));
            }
            var reply = answer.get(10, TimeUnit.SECONDS);
            assertTrue(reply.startsWith("HTTP/1.1 200"), reply.substring(0, Math.min(200, reply.length())));
            assertTrue(reply.contains("done:9"), "the relay was cut before the upload ended");
        });
    }

    /** A client that holds back an early answer after its upload has ended is not cut for it. */
    @Test
    void clientHoldingBackAnEarlyAnswerAfterItsUploadIsNotCut() throws Exception {
        var host = "ghcr.io";
        withBudget(2, () -> {
            var socket = connect(host);
            socket.pause();
            var answer = collectUntilClose(socket);
            socket.write(Buffer.buffer("PUT /answer-at-once/v2/app/blobs/uploads/1 HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Content-Length: 4\r\n"
                    + "Connection: close\r\n\r\n"
                    + "ab"));
            // The early answer has filled what the client does not read by now
            Thread.sleep(500);
            socket.write(Buffer.buffer("cd"));
            // Twice the budget holding the answer back after the upload has ended
            Thread.sleep(4000);
            socket.resume();
            var reply = answer.get(10, TimeUnit.SECONDS);
            assertTrue(reply.startsWith("HTTP/1.1 200"), reply.substring(0, Math.min(200, reply.length())));
            assertTrue(reply.contains("done:4"), "the relay was cut while the client held the answer back");
        });
    }

    /** Once the upload has ended, an upstream that stalls its early answer is cut at the budget. */
    @Test
    void stalledEarlyAnswerIsCutOnceTheUploadHasEnded() throws Exception {
        var host = "ghcr.io";
        withBudget(2, () -> {
            var socket = connect(host);
            var answer = collectUntilClose(socket);
            socket.write(Buffer.buffer("PUT /answer-then-stall/v2/app/blobs/uploads/1 HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Content-Length: 4\r\n"
                    + "Connection: close\r\n\r\n"
                    + "ab"));
            Thread.sleep(500);
            socket.write(Buffer.buffer("cd"));
            try {
                var reply = answer.get(6, TimeUnit.SECONDS);
                assertTrue(reply.contains("started"), reply);
            } catch (java.util.concurrent.TimeoutException e) {
                socket.close();
                fail("A stalled answer was not cut within three times the budget");
            }
        });
    }

    interface Body {
        void run() throws Exception;
    }

    /** Runs {@code body} with the client's silence budget at {@code seconds}. */
    static void withBudget(int seconds, Body body) throws Exception {
        withBudget(proxy.upstream, seconds, body);
    }

    /** As above, for another proxy's {@code upstream}. */
    static void withBudget(Upstream upstream, int seconds, Body body) throws Exception {
        var budget = upstream.clientSilenceBudgetSeconds;
        upstream.clientSilenceBudgetSeconds = seconds;
        try {
            body.run();
        } finally {
            upstream.clientSilenceBudgetSeconds = budget;
        }
    }

    /**
     * A client that asks {@code Expect: 100-continue} gets upstream's {@code 100} rather than wait out
     * its own timeout before it sends the body (about a second per upload for curl).
     */
    @Test
    void expectContinueIsAnsweredByUpstream() throws Exception {
        var host = "ghcr.io";
        received.clear();
        var socket = connect(host);
        var bytes = new StringBuffer();
        var continued = new CompletableFuture<Void>();
        var answer = new CompletableFuture<String>();
        socket.handler(b -> {
            bytes.append(b.toString(StandardCharsets.UTF_8));
            if (bytes.indexOf("HTTP/1.1 100 Continue\r\n\r\n") == 0) continued.complete(null);
        });
        socket.closeHandler(v -> answer.complete(bytes.toString()));
        socket.write(Buffer.buffer("PUT /v2/app/blobs/uploads/1?digest=x HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Expect: 100-continue\r\n"
                + "Content-Length: 5\r\n"
                + "Connection: close\r\n\r\n"));
        try {
            continued.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            socket.close();
            fail("No 100 Continue for the client; got so far: '" + bytes + "'");
        }

        socket.write(Buffer.buffer("hello"));
        var reply = answer.get(10, TimeUnit.SECONDS);
        assertTrue(reply.contains("HTTP/1.1 200"), reply);
        assertEquals("hello", new String(received.get(host).get(5, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8));
    }

    static io.vertx.core.net.NetSocket connect(String host) throws Exception {
        return client.connect(mitmPort, "127.0.0.1", host)
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /** Everything the proxy sends on {@code socket} until it closes it. */
    static CompletableFuture<String> collectUntilClose(io.vertx.core.net.NetSocket socket) {
        var answer = new CompletableFuture<String>();
        var bytes = new ByteArrayOutputStream();
        socket.handler(b -> {
            synchronized (bytes) { bytes.writeBytes(b.getBytes()); }
        });
        socket.closeHandler(v -> {
            synchronized (bytes) { answer.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return answer;
    }

    /** Sends the request in one write and returns everything the proxy answers until it closes. */
    static String exchange(String host, Buffer request) throws Exception {
        return exchange(host, request, 10);
    }

    static String exchange(String host, Buffer request, int seconds) throws Exception {
        received.clear();
        var socket = connect(host);
        var answer = collectUntilClose(socket);
        socket.write(request);
        try {
            return answer.get(seconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            socket.close();
            return fail("The proxy did not close the connection within " + seconds + "s");
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
