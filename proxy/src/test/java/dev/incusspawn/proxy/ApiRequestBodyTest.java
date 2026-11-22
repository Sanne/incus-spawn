package dev.incusspawn.proxy;

import dev.incusspawn.util.Eventually;
import java.time.Duration;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDef;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;

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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A request to a credential-injected domain that the proxy does not translate streams upstream as it
 * arrives, with the real credential on its head, instead of being held whole in the shared proxy's
 * heap first (#1315). Held whole, a 64 MiB {@code git push} exhausted the native proxy's heap.
 */
class ApiRequestBodyTest {

    /** The Anthropic API (an API key) and GitHub (a tool proxy's basic auth). */
    static final List<String> HOSTS = List.of("api.anthropic.com", "github.com");
    /** Where a Vertex account's model calls go, for the region below. */
    static final String VERTEX_HOST = "us-east5-aiplatform.googleapis.com";

    static final String REAL_ANTHROPIC = "sk-ant-api03-real";
    static final String REAL_GITHUB = "ghp_realtoken";
    static final String REAL_OAUTH = "sk-ant-oat01-real";
    /** What each host's request must carry upstream instead of the instance's placeholder. */
    static final Map<String, String> CREDENTIAL = Map.of(
            "api.anthropic.com", "x-api-key: " + REAL_ANTHROPIC,
            "github.com", "Authorization: Basic "
                    + Base64.getEncoder().encodeToString(("x-access-token:" + REAL_GITHUB).getBytes(StandardCharsets.UTF_8)));

    @TempDir
    static Path tempHome;

    static String origHome;
    static Vertx vertx;
    static MitmProxy proxy;
    static MitmProxy oauthProxy;
    static MitmProxy vertexProxy;
    /** Makes the Vertex proxy's token fetch fail, as gcloud does when its login has lapsed. */
    static volatile boolean gcloudFails;
    /** Makes the Vertex proxy's rewriter throw what it never should, in "accept" or at "end". */
    static volatile String rewriterFails;
    /** The response upstream streams on for a {@code hold-open} model call, until a test ends it. */
    static volatile CompletableFuture<io.vertx.core.http.HttpServerResponse> heldOpen = new CompletableFuture<>();
    /** The response upstream never sends for a {@code no-answer} call, once the body is in. */
    static volatile CompletableFuture<io.vertx.core.http.HttpServerResponse> unanswered = new CompletableFuture<>();
    /** Model calls upstream has refused once with a 401, by path. */
    static final java.util.Set<String> refusedOnce = ConcurrentHashMap.newKeySet();
    /** The body each refused model call carried, by path. */
    static final Map<String, byte[]> refusedBodies = new ConcurrentHashMap<>();
    static NetClient client;
    static final Map<String, Integer> upstreamPorts = new ConcurrentHashMap<>();
    /** What each upstream received, by host; its head is recorded as it arrives. */
    static final Map<String, CompletableFuture<Received>> received = new ConcurrentHashMap<>();
    /** Completed once a {@code /stream/} upload has brought upstream its first 10 bytes. */
    static volatile CompletableFuture<Buffer> streamStarted = new CompletableFuture<>();

    record Received(String uri, Map<String, String> head, byte[] body) {
        String header(String name) {
            return head.get(name.toLowerCase());
        }
    }

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));

        var ca = CertificateAuthority.loadOrCreate();
        vertx = Vertx.vertx();
        for (var host : java.util.stream.Stream.concat(HOSTS.stream(), java.util.stream.Stream.of(VERTEX_HOST)).toList()) {
            var server = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                            .setHandle100ContinueAutomatically(true)
                            .setKeyCertOptions(NpmCacheProxyTest.keyCert(ca, host)))
                    .requestHandler(req -> answer(host, req));
            upstreamPorts.put(host, NpmCacheProxyTest.listen(server));
        }

        var auth = new ToolDef.AuthDef();
        auth.setType("basic");
        auth.setUsername("x-access-token");
        auth.setPassword("${token}");
        var github = new ResolvedToolProxy("gh", "github.com", auth, Map.of("token", REAL_GITHUB), java.util.Set.of("github"));
        proxy = startProxy(new ProxyCredentials(REAL_ANTHROPIC, "", false, "", "", List.of(github)));
        oauthProxy = startProxy(new ProxyCredentials("", REAL_OAUTH, false, "", "", List.of()));
        vertexProxy = startProxy(new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1",
                new ProxyCredentials("", "", true, "us-east5", "proj", List.of())) {
            @Override
            VertexBodyRewriter vertexRewriter(String path, ProxyCredentials credentials, java.util.function.Consumer<Buffer> output) {
                var failAt = rewriterFails;
                if (failAt == null) return super.vertexRewriter(path, credentials, output);
                return new VertexBodyRewriter(path, "proj", "us-east5", output) {
                    @Override
                    void accept(Buffer chunk) throws InvalidBody, RefusedBody {
                        if (failAt.equals("accept")) throw new IllegalStateException("a bug in the rewriter");
                        super.accept(chunk);
                    }

                    @Override
                    Result end() throws InvalidBody, RefusedBody {
                        if (failAt.equals("end")) throw new IllegalStateException("a bug in the rewriter");
                        return super.end();
                    }
                };
            }

            @Override
            String fetchGcloudToken() {
                if (gcloudFails) throw new RuntimeException("gcloud auth print-access-token failed (exit 1): stub");
                return "ya29.fresh";
            }
        });
        vertexProxy.vertexToken.set(MitmProxy.VertexTokenEntry.resolved("ya29.stale"));

        client = vertx.createNetClient(new NetClientOptions()
                .setSsl(true).setTrustAll(true).setHostnameVerificationAlgorithm(""));
    }

    static MitmProxy startProxy(ProxyCredentials credentials) throws Exception {
        return startProxy(new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", credentials));
    }

    static MitmProxy startProxy(MitmProxy p) throws Exception {
        p.trustUpstreamCertificate(SpawnConfig.configDir().resolve("ca.crt").toString());
        ContainerTls.startInBackground(p);
        ProofTestSupport.register(p);
        upstreamPorts.forEach((host, port) -> p.overrideUpstream(host, "127.0.0.1", port));
        return p;
    }

    static void answer(String host, HttpServerRequest req) {
        var head = new ConcurrentHashMap<String, String>();
        req.headers().forEach(e -> head.put(e.getKey().toLowerCase(), e.getValue()));
        if (req.path().startsWith("/early/")) {
            // Refuses the upload on its head, and reads the rest, as nginx does before it closes
            req.handler(ignored -> {});
            req.response().setStatusCode(413).putHeader("Connection", "close").end("too large");
            return;
        }
        if (req.path().contains("refuse-once") && refusedOnce.add(req.path())) {
            // A token Vertex no longer takes: the proxy gets a fresh one and sends the request again
            req.body().onSuccess(refused -> {
                refusedBodies.put(req.path(), refused.getBytes());
                req.response().setStatusCode(401).end("stale token");
            });
            return;
        }
        if (req.path().contains("no-answer")) {
            // Takes the body, then never answers, like a connection that died silently
            req.handler(ignored -> {});
            req.endHandler(v -> unanswered.complete(req.response()));
            return;
        }
        if (req.path().contains("hold-open")) {
            // Answers once the body is in, then streams on until a test ends it
            req.body().onSuccess(ignored -> {
                var resp = req.response().setChunked(true);
                resp.write("event: message_start\n\n");
                heldOpen.complete(resp);
            });
            return;
        }
        var body = Buffer.buffer();
        req.handler(chunk -> {
            body.appendBuffer(chunk);
            if (body.length() >= 10) streamStarted.complete(body.copy());
        });
        req.endHandler(v -> {
            received.computeIfAbsent(host, h -> new CompletableFuture<>())
                    .complete(new Received(req.uri(), head, body.getBytes()));
            req.response().putHeader("Connection", "close").putHeader("Content-Type", "application/json")
                    .end("{\"got\":" + body.length() + "}");
        });
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (oauthProxy != null) oauthProxy.stop();
            if (vertexProxy != null) vertexProxy.stop();
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    /** The head a container sends: its placeholder, which the proxy replaces. */
    static String head(String method, String host, String path, String... headers) {
        var sb = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\n");
        sb.append(host.equals("github.com")
                ? "Authorization: Basic " + Base64.getEncoder().encodeToString(("x-access-token:" + ProofTestSupport.token("github", "gho_")).getBytes(StandardCharsets.UTF_8))
                : "x-api-key: " + ProofTestSupport.token("claude", "sk-ant-"));
        sb.append("\r\n");
        for (var h : headers) sb.append(h).append("\r\n");
        return sb.append("Connection: close\r\n\r\n").toString();
    }

    static void assertRealCredential(String host, Received got) {
        var expected = CREDENTIAL.get(host);
        var name = expected.substring(0, expected.indexOf(':'));
        assertEquals(expected.substring(name.length() + 2), got.header(name), "credential on the upstream head");
        assertFalse(got.head().toString().contains("placeholder"), "the placeholder reached upstream: " + got.head());
    }

    /** Upstream has the first half of the upload while the client still holds the second. */
    @ParameterizedTest
    @FieldSource("HOSTS")
    void uploadReachesUpstreamBeforeItHasAllArrived(String host) throws Exception {
        streamStarted = new CompletableFuture<>();
        received.clear();
        var socket = connect(proxy, host);
        var answer = RelayRequestBodyTest.collectUntilClose(socket);
        socket.write(head("POST", host, "/stream/upload", "Content-Length: 20") + "first half");
        try {
            assertEquals("first half", streamStarted.get(5, TimeUnit.SECONDS).getString(0, 10));
        } catch (java.util.concurrent.TimeoutException e) {
            socket.close();
            fail("Upstream got nothing of the upload before all of it had reached the proxy");
        }

        socket.write(", the rest");
        var reply = answer.get(10, TimeUnit.SECONDS);
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
        var got = received.get(host).get(5, TimeUnit.SECONDS);
        assertEquals("first half, the rest", new String(got.body(), StandardCharsets.UTF_8));
        assertRealCredential(host, got);
    }

    /** A chunked upload (git's push) reaches upstream chunked, as the client framed it. */
    @ParameterizedTest
    @FieldSource("HOSTS")
    void chunkedUploadStaysChunked(String host) throws Exception {
        var reply = exchange(proxy, host, head("POST", host, "/o/r.git/git-receive-pack", "Transfer-Encoding: chunked")
                + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n");
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
        var got = received.get(host).get(5, TimeUnit.SECONDS);
        assertEquals("hello world", new String(got.body(), StandardCharsets.UTF_8));
        assertEquals("chunked", got.header("Transfer-Encoding"));
        assertNull(got.header("Content-Length"), "re-framed with a length the proxy computed");
        assertRealCredential(host, got);
    }

    /** OAuth's bearer replaces whatever the instance sent, on a streamed request too. */
    @Test
    void oauthBearerIsInjectedOnAStreamedUpload() throws Exception {
        var host = "api.anthropic.com";
        var reply = exchange(oauthProxy, host, "POST /v1/messages HTTP/1.1\r\nHost: " + host + "\r\n"
                + "x-api-key: " + ProofTestSupport.token("claude", "sk-ant-") + "\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}");
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
        var got = received.get(host).get(5, TimeUnit.SECONDS);
        assertEquals("Bearer " + REAL_OAUTH, got.header("Authorization"));
        assertNull(got.header("x-api-key"), "the placeholder key reached upstream");
    }

    /**
     * A client that asks {@code Expect: 100-continue} gets upstream's {@code 100}, which upstream sends
     * on a head that already carries the real credential, instead of waiting out its own timeout.
     */
    @ParameterizedTest
    @FieldSource("HOSTS")
    void expectContinueIsAnsweredOnAHeadWithTheCredential(String host) throws Exception {
        received.clear();
        var socket = connect(proxy, host);
        var bytes = new StringBuffer();
        var continued = new CompletableFuture<Void>();
        var answer = new CompletableFuture<String>();
        socket.handler(b -> {
            bytes.append(b.toString(StandardCharsets.UTF_8));
            if (bytes.indexOf("HTTP/1.1 100 Continue\r\n\r\n") == 0) continued.complete(null);
        });
        socket.closeHandler(v -> answer.complete(bytes.toString()));
        socket.write(head("POST", host, "/v1/files", "Expect: 100-continue", "Content-Length: 5"));
        try {
            continued.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            socket.close();
            fail("No 100 Continue for the client; got so far: '" + bytes + "'");
        }

        socket.write("hello");
        var reply = answer.get(10, TimeUnit.SECONDS);
        assertTrue(reply.contains("HTTP/1.1 200"), reply);
        var got = received.get(host).get(5, TimeUnit.SECONDS);
        assertEquals("hello", new String(got.body(), StandardCharsets.UTF_8));
        assertRealCredential(host, got);
    }

    /** Upstream's answer to an upload it refuses early reaches the client before the upload ends. */
    @ParameterizedTest
    @FieldSource("HOSTS")
    void earlyAnswerReachesTheClient(String host) throws Exception {
        // The rest never comes: the connection closes once it has paused for the linger
        withLinger(proxy, 200, 60_000, () -> {
            var reply = exchange(proxy, host, head("POST", host, "/early/upload", "Content-Length: 100000")
                    + "only the start of it");
            assertTrue(reply.startsWith("HTTP/1.1 413"), reply);
            assertTrue(reply.endsWith("too large"), reply);
        });
    }

    /**
     * A relay whose connect fails answers, and closes once the rest of the upload has stopped
     * coming; a client that waits for 100 Continue sends none, so its connection closes at once.
     */
    @ParameterizedTest
    @FieldSource("HOSTS")
    void failedConnectAnswersAndCloses(String host) throws Exception {
        // A port nothing holds, so the connect is refused (ArtifactCacheProxyTest.refused)
        int port;
        try (var closed = new java.net.Socket()) {
            closed.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            port = closed.getLocalPort();
        }
        proxy.overrideUpstream(host, "127.0.0.1", port);
        try {
            withLinger(proxy, 200, 60_000, () -> {
                var reply = exchange(proxy, host, head("POST", host, "/upload", "Content-Length: 100000")
                        + "only the start of it");
                assertTrue(reply.startsWith("HTTP/1.1 502"), reply);
            });
            withLinger(proxy, 60_000, 60_000, () -> {
                var reply = exchange(proxy, host, head("POST", host, "/upload",
                        "Expect: 100-continue", "Content-Length: 100000"));
                assertTrue(reply.startsWith("HTTP/1.1 502"), reply);
                assertFalse(reply.contains("100 Continue"), reply);
            });
        } finally {
            proxy.overrideUpstream(host, "127.0.0.1", upstreamPorts.get(host));
            proxy.upstream.clearBackoffs();
        }
    }

    /**
     * {@code --debug} does not hold a request back to log it: the upload still streams, and the log
     * holds a bounded prefix of each body, marked when it was cut.
     */
    @Test
    void debugLogCapturesAPrefixWhileTheUploadStreams() throws Exception {
        var host = "github.com";
        var logDir = tempHome.resolve("debug");
        proxy.setDebugLog(new ApiTrafficLog(logDir));
        try {
            streamStarted = new CompletableFuture<>();
            received.clear();
            var total = ApiTrafficLog.MAX_BODY_CAPTURE + 100;
            var socket = connect(proxy, host);
            var answer = RelayRequestBodyTest.collectUntilClose(socket);
            socket.write(head("POST", host, "/stream/upload", "Content-Length: " + total) + "first half");
            try {
                streamStarted.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                socket.close();
                fail("With --debug, upstream got nothing of the upload before all of it had reached the proxy");
            }
            socket.write("x".repeat(total - 10));
            var reply = answer.get(10, TimeUnit.SECONDS);
            assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
            assertEquals(total, received.get(host).get(5, TimeUnit.SECONDS).body().length);

            var log = waitForLog(logDir, "\"got\": " + total);
            assertTrue(log.contains("[captured " + ApiTrafficLog.MAX_BODY_CAPTURE + " of " + total + " bytes]"), log);
            assertTrue(log.contains("\"got\": " + total), "the response body is logged: " + log);
            assertFalse(log.contains(REAL_GITHUB), "the real credential is never logged");
        } finally {
            proxy.setDebugLog(null);
        }
    }

    // --- Vertex model calls: translated, so still read whole before they are sent ---

    /** A standard Messages request is translated into Vertex's rawPredict, with the GCP token, and none of its query. */
    @Test
    void vertexMessagesAreTranslated() throws Exception {
        var body = "{\"model\":\"claude-sonnet-4-6-20250929\",\"max_tokens\":5,\"messages\":[],"
                + "\"context_management\":{},\"stream\":true}";
        var reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com", "/v1/messages?beta=true",
                "anthropic-beta: claude-code-20250219", "Content-Length: " + body.length()) + body);
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
        var got = received.get(VERTEX_HOST).get(5, TimeUnit.SECONDS);
        assertEquals("/v1/projects/proj/locations/us-east5/publishers/anthropic/models/claude-sonnet-4-6:streamRawPredict",
                got.uri());
        assertTrue(got.header("Authorization").startsWith("Bearer ya29."), got.header("Authorization"));
        assertNull(got.header("x-api-key"));
        assertNull(got.header("anthropic-beta"));
        var sent = new String(got.body(), StandardCharsets.UTF_8);
        assertEquals(String.valueOf(got.body().length), got.header("Content-Length"));
        assertTrue(sent.contains("\"anthropic_version\":\"vertex-2023-10-16\""), sent);
        assertFalse(sent.contains("model") || sent.contains("context_management"), sent);
    }

    /** A 401 from Vertex is answered by sending the same request again, once, with a fresh token. */
    @Test
    void vertex401IsRetriedWithAFreshToken() throws Exception {
        var path = "/v1/projects/proj/locations/us-east5/publishers/anthropic/models/refuse-once@20251001:rawPredict";
        var body = "{\"anthropic_version\":\"vertex-2023-10-16\",\"max_tokens\":5,\"messages\":[]}";
        var reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com", path,
                "Content-Length: " + body.length()) + body);
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
        var got = received.get(VERTEX_HOST).get(5, TimeUnit.SECONDS);
        assertEquals(path.replace("@20251001", ""), got.uri());
        assertEquals("Bearer ya29.fresh", got.header("Authorization"));
        assertEquals(body, new String(got.body(), StandardCharsets.UTF_8));
    }

    /**
     * A body already in Vertex's format loses a tool's {@code strict}, as a translated one does
     * (#1568): an organization policy may disallow it, and Vertex then refuses every request with
     * tools. Everything else, the client's {@code anthropic_version} and {@code model} included, is
     * forwarded as sent. A {@code Content-Encoding} that names no coding but identity, in any
     * spelling, or none at all ({@code encodings} empty), does not spare the body the rewrite.
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "identity", " IDENTITY ", "identity, identity", "identity|"})
    void vertexFormatBodiesLoseToolStrict(String encodings) throws Exception {
        var path = "/v1/projects/proj/locations/us-east5/publishers/anthropic/models/claude-opus-5-5:streamRawPredict";
        var body = "{\"anthropic_version\":\"vertex-2023-10-16\",\"model\":\"m\",\"context_management\":{},"
                + "\"tools\":[{\"name\":\"read\",\"strict\":true,\"input_schema\":{\"properties\":{\"strict\":{}}},"
                + "\"cache_control\":{\"type\":\"ephemeral\",\"scope\":\"global\"}}],\"messages\":[],\"stream\":true}";
        var reply = exchange(vertexProxy, "api.anthropic.com",
                head("POST", "api.anthropic.com", path, encodedHeaders(encodings, body.length())) + body);
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
        var got = received.get(VERTEX_HOST).get(5, TimeUnit.SECONDS);
        assertEquals(path, got.uri());
        assertEquals(body.replace("\"strict\":true,", ""), new String(got.body(), StandardCharsets.UTF_8));
        assertEquals(String.valueOf(got.body().length), got.header("Content-Length"));
    }

    /**
     * A Vertex-format body with a {@code Content-Encoding} is not JSON the rewriter can read: it is
     * forwarded unchanged, encoding and all, as before #1568, not refused. An identity among the
     * codings hides none of the others.
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"gzip", "identity, gzip", "identity|gzip", "|gzip"})
    void encodedVertexFormatBodiesAreForwardedUnchanged(String encodings) throws Exception {
        var path = "/v1/projects/proj/locations/us-east5/publishers/anthropic/models/claude-opus-5-5@20251001:rawPredict";
        var body = RelayRequestBodyTest.gzip("{\"tools\":[{\"name\":\"a\",\"strict\":true}],\"messages\":[]}");
        var request = Buffer.buffer(head("POST", "api.anthropic.com", path, encodedHeaders(encodings, body.length)))
                .appendBytes(body);
        var reply = exchange(vertexProxy, "api.anthropic.com", request);
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
        var got = received.get(VERTEX_HOST).get(5, TimeUnit.SECONDS);
        assertEquals(path.replace("@20251001", ""), got.uri());
        assertArrayEquals(body, got.body());
    }

    /**
     * A request already in Vertex's format reaches Vertex with its query string, rewritten body or
     * encoded, and again on a 401: only the path's {@code @date} suffix goes (#1576).
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "gzip"})
    void vertexFormatRequestsKeepTheirQuery(String encodings) throws Exception {
        var path = "/v1/projects/proj/locations/us-east5/publishers/anthropic/models/refuse-once-query-"
                + encodings + "@20251001:streamRawPredict";
        var json = "{\"anthropic_version\":\"vertex-2023-10-16\",\"tools\":[{\"name\":\"a\"}],\"messages\":[]}";
        var body = encodings.isEmpty() ? json.getBytes(StandardCharsets.UTF_8) : RelayRequestBodyTest.gzip(json);
        var request = Buffer.buffer(head("POST", "api.anthropic.com", path + "?alt=sse",
                encodedHeaders(encodings, body.length))).appendBytes(body);
        var reply = exchange(vertexProxy, "api.anthropic.com", request);
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
        var got = received.get(VERTEX_HOST).get(5, TimeUnit.SECONDS);
        assertTrue(refusedOnce.contains(path.replace("@20251001", "")), "the first attempt was refused");
        assertEquals(path.replace("@20251001", "") + "?alt=sse", got.uri());
        assertArrayEquals(body, got.body());
    }

    /**
     * A {@code Content-Encoding} header per {@code |}-separated value of {@code encodings}, an empty
     * value included (none at all if {@code encodings} is empty), and the length.
     */
    private static String[] encodedHeaders(String encodings, int length) {
        var headers = new java.util.ArrayList<String>();
        if (!encodings.isEmpty()) {
            for (var value : encodings.split("\\|", -1)) headers.add("Content-Encoding: " + value);
        }
        headers.add("Content-Length: " + length);
        return headers.toArray(String[]::new);
    }

    // --- Vertex model calls: held in a bounded spool until their exchange ends ---

    /** A body of {@code size} bytes or so, as Claude Code sends: one large message, model and stream last. */
    static String largeMessages(int size) {
        return "{\"max_tokens\":5,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\""
                + "x".repeat(size) + "\",\"cache_control\":{\"type\":\"ephemeral\",\"scope\":\"global\"}}]}],"
                + "\"context_management\":{},\"model\":\"claude-sonnet-4-6\",\"stream\":true}";
    }

    static byte[] translated(String body) throws Exception {
        var out = new ByteArrayOutputStream();
        var rewriter = new VertexBodyRewriter("/v1/messages", "proj", "us-east5", b -> out.writeBytes(b.getBytes()));
        rewriter.accept(Buffer.buffer(body));
        rewriter.end();
        return out.toByteArray();
    }

    /** Runs {@code body} with the Vertex proxy's spool budget at {@code limit}, and checks it is all given back. */
    static void withSpoolBudget(long limit, Body body) throws Exception {
        var budget = vertexProxy.spoolBudget;
        var small = new SpoolBudget(limit);
        vertexProxy.spoolBudget = small;
        try {
            body.run();
            Eventually.holdsWithin(Duration.ofMillis(5000), () -> !(small.held() != 0));
            assertEquals(0, small.held(), "the exchange gave its spool back");
        } finally {
            vertexProxy.spoolBudget = budget;
        }
    }

    interface Body {
        void run() throws Exception;
    }

    /** A body far beyond the spool's memory goes to disk, and reaches Vertex translated and whole. */
    @Test
    void vertexBodyBeyondTheBudgetIsSpilledAndSentWhole() throws Exception {
        withSpoolBudget(64 * 1024, () -> {
            var body = largeMessages(1024 * 1024);
            var reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com", "/v1/messages",
                    "Content-Length: " + body.length()) + body);
            assertTrue(reply.startsWith("HTTP/1.1 200"), reply.substring(0, Math.min(200, reply.length())));
            var got = received.get(VERTEX_HOST).get(5, TimeUnit.SECONDS);
            assertEquals("/v1/projects/proj/locations/us-east5/publishers/anthropic/models/claude-sonnet-4-6:streamRawPredict", got.uri());
            assertArrayEquals(translated(body), got.body());
            assertFalse(new String(got.body(), StandardCharsets.UTF_8).contains("scope"));
            assertEquals(String.valueOf(got.body().length), got.header("Content-Length"));
        });
    }

    /** The retry after a 401 sends the same spilled body again: the spool outlives the first send. */
    @Test
    void vertex401RetryReplaysTheSpilledBody() throws Exception {
        withSpoolBudget(64 * 1024, () -> {
            var path = "/v1/projects/proj/locations/us-east5/publishers/anthropic/models/refuse-once-spilled:rawPredict";
            var body = "{\"anthropic_version\":\"vertex-2023-10-16\",\"messages\":[\"" + "y".repeat(512 * 1024) + "\"]}";
            var reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com", path,
                    "Content-Length: " + body.length()) + body);
            assertTrue(reply.startsWith("HTTP/1.1 200"), reply.substring(0, Math.min(200, reply.length())));
            var got = received.get(VERTEX_HOST).get(5, TimeUnit.SECONDS);
            assertEquals(body, new String(refusedBodies.get(path), StandardCharsets.UTF_8), "the first send");
            assertEquals(body, new String(got.body(), StandardCharsets.UTF_8), "the retry");
            assertEquals("Bearer ya29.fresh", got.header("Authorization"));
        });
    }

    /**
     * A Vertex request cannot ask upstream for {@code 100 Continue}, since its head waits for the body:
     * the proxy answers it, and a client that withholds its body until then is not left waiting.
     */
    @Test
    void retainedRequestAnswersContinueLocally() throws Exception {
        received.clear();
        var host = "api.anthropic.com";
        var body = "{\"model\":\"m\",\"messages\":[]}";
        var socket = connect(vertexProxy, host);
        var bytes = new StringBuffer();
        var continued = new CompletableFuture<Void>();
        var answer = new CompletableFuture<String>();
        socket.handler(b -> {
            bytes.append(b.toString(StandardCharsets.UTF_8));
            if (bytes.indexOf("HTTP/1.1 100 Continue\r\n\r\n") == 0) continued.complete(null);
        });
        socket.closeHandler(v -> answer.complete(bytes.toString()));
        socket.write(head("POST", host, "/v1/messages", "Expect: 100-continue", "Content-Length: " + body.length()));
        try {
            continued.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            socket.close();
            fail("No 100 Continue for a Vertex request; got so far: '" + bytes + "'");
        }
        socket.write(body);
        var reply = answer.get(10, TimeUnit.SECONDS);
        assertTrue(reply.contains("HTTP/1.1 200"), reply);
        var got = received.get(VERTEX_HOST).get(5, TimeUnit.SECONDS);
        assertNull(got.header("Expect"), "upstream was asked to continue a body it gets whole");
    }

    /**
     * Too little disk for a body that may spill is a 507 naming the host's space, before the client
     * sends it; and a client that waits to be told to send it sends nothing, so its connection closes at once.
     */
    @Test
    void lowDiskIsRefusedBeforeTheBodyIsSent() throws Exception {
        var store = vertexProxy.spoolStore;
        var full = new BodySpoolTest.FakeStore();
        full.room = false;
        vertexProxy.spoolStore = full;
        try {
            withLinger(60_000, 60_000, () -> {
                var reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com", "/v1/messages",
                        "Expect: 100-continue", "Content-Length: 40000000"));
                assertTrue(reply.startsWith("HTTP/1.1 507"), reply);
                assertFalse(reply.contains("100 Continue"), reply);
                assertTrue(reply.contains("no room"), reply);
            });
        } finally {
            vertexProxy.spoolStore = store;
        }
    }

    /** Runs {@code body} with a refused body's connection lingering {@code idle} ms after its last read, {@code cap} ms at most. */
    static void withLinger(long idle, long cap, Body body) throws Exception {
        withLinger(vertexProxy, idle, cap, body);
    }

    /** As above, on {@code p}. */
    static void withLinger(MitmProxy p, long idle, long cap, Body body) throws Exception {
        var linger = p.upstream.lingerMillis;
        var lingerCap = p.upstream.lingerCapMillis;
        p.upstream.lingerMillis = idle;
        p.upstream.lingerCapMillis = cap;
        try {
            body.run();
        } finally {
            p.upstream.lingerMillis = linger;
            p.upstream.lingerCapMillis = lingerCap;
        }
    }

    interface RefusedBody {
        void run(NetSocket socket, Refusal refusal) throws Exception;
    }

    /**
     * Runs {@code body} on a connection to the Vertex proxy, whose disk admits nothing a body spills,
     * under a 64 KiB budget; the connection is closed after it, however it ends.
     */
    static void withNoRoomToSpill(RefusedBody body) throws Exception {
        var store = vertexProxy.spoolStore;
        var full = new BodySpoolTest.FakeStore();
        full.admits = false;
        vertexProxy.spoolStore = full;
        try {
            withSpoolBudget(64 * 1024, () -> {
                var socket = connect(vertexProxy, "api.anthropic.com");
                try {
                    body.run(socket, Refusal.on(socket));
                } finally {
                    socket.close();
                }
            });
        } finally {
            vertexProxy.spoolStore = store;
        }
    }

    /** What the proxy answers on a socket: {@code refused} once its 507 for want of disk is in, {@code closed} with all of it. */
    record Refusal(StringBuffer bytes, CompletableFuture<Void> refused, CompletableFuture<String> closed, List<Throwable> errors) {
        static Refusal on(NetSocket socket) {
            var refusal = new Refusal(new StringBuffer(), new CompletableFuture<>(), new CompletableFuture<>(),
                    new CopyOnWriteArrayList<>());
            socket.handler(b -> {
                refusal.bytes.append(b.toString(StandardCharsets.UTF_8));
                if (refusal.bytes.indexOf("could not be held") >= 0) refusal.refused.complete(null);
            });
            socket.exceptionHandler(refusal.errors::add);
            socket.closeHandler(v -> refusal.closed.complete(refusal.bytes.toString()));
            return refusal;
        }

        void assertRefused() {
            var reply = bytes.toString();
            assertTrue(reply.startsWith("HTTP/1.1 507"), reply.substring(0, Math.min(200, reply.length())));
        }

        /** The writes all went out, and the connection closed without a reset. */
        void assertReadWhole(List<io.vertx.core.Future<Void>> writes) throws Exception {
            closed.get(10, TimeUnit.SECONDS);
            assertRefused();
            for (var write : writes) {
                assertDoesNotThrow(() -> write.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS),
                        "the rest of the body could not be sent");
            }
            assertEquals(List.of(), errors, "the connection was reset");
        }
    }

    /**
     * A body that outgrows memory when the disk has no room left is a 507, and the connection closes
     * once the rest stops coming, even though the body never ends.
     */
    @Test
    void lowDiskMidBodyIsRefused() throws Exception {
        withLinger(200, 60_000, () -> withNoRoomToSpill((socket, refusal) -> {
            var body = largeMessages(256 * 1024);
            socket.write(head("POST", "api.anthropic.com", "/v1/messages",
                    "Content-Length: " + (body.length() + 1000)) + body);
            refusal.refused().get(5, TimeUnit.SECONDS);
            refusal.assertRefused();
            refusal.closed().get(10, TimeUnit.SECONDS);
        }));
    }

    /**
     * A client still sending its body when the 507 comes may send the rest and then read (#1617): the
     * proxy reads it and closes once the body has ended, since closing with some of it unread resets
     * the connection, and a reset can take the 507 with it. A body still coming is read for as long
     * as it keeps coming, here twice as long as it may pause.
     */
    @Test
    void lowDiskRefusalReadsTheRestBeforeClosing() throws Exception {
        withLinger(1000, 60_000, () -> withNoRoomToSpill((socket, refusal) -> {
            var body = largeMessages(256 * 1024);
            var split = 128 * 1024;
            socket.write(head("POST", "api.anthropic.com", "/v1/messages", "Content-Length: " + body.length())
                    + body.substring(0, split));
            refusal.refused().get(5, TimeUnit.SECONDS);
            assertFalse(refusal.closed().isDone(), "closed before the body had ended");
            var slices = 20;
            var slice = (body.length() - split + slices - 1) / slices;
            var writes = new java.util.ArrayList<io.vertx.core.Future<Void>>();
            for (int at = split; at < body.length(); at += slice) {
                writes.add(socket.write(body.substring(at, Math.min(body.length(), at + slice))));
                Thread.sleep(100);
            }
            refusal.assertReadWhole(writes);
        }));
    }

    /**
     * The chunk the disk refused does not leave the rest unread: with the shared budget spent by
     * other bodies, the spool would ask the client to wait for room it never gives back once refused.
     * Only the body's end can close the connection within the wait.
     */
    @Test
    void aRefusalWhileTheBudgetIsSpentStillReadsTheRest() throws Exception {
        withLinger(60_000, 60_000, () -> withNoRoomToSpill((socket, refusal) -> {
            var budget = vertexProxy.spoolBudget;
            budget.acquire(64 * 1024);
            try {
                var body = largeMessages(256 * 1024);
                socket.write(head("POST", "api.anthropic.com", "/v1/messages", "Content-Length: " + body.length())
                        + body.substring(0, 1024));
                refusal.refused().get(5, TimeUnit.SECONDS);
                refusal.assertReadWhole(List.of(socket.write(body.substring(1024))));
            } finally {
                budget.release(64 * 1024, false);
            }
        }));
    }

    /**
     * A refused body that keeps trickling in cannot hold its connection: it closes at the cap, counted
     * from the refusal, though no pause ever reaches the idle wait. The 507 is in before it closes.
     */
    @Test
    void aTricklingRefusedBodyIsClosedAtTheCap() throws Exception {
        withLinger(4000, 2000, () -> withNoRoomToSpill((socket, refusal) -> {
            var body = largeMessages(256 * 1024);
            socket.write(head("POST", "api.anthropic.com", "/v1/messages",
                    "Content-Length: " + (body.length() + 1000)) + body);
            refusal.refused().get(5, TimeUnit.SECONDS);
            var refusedAt = System.nanoTime();
            for (int i = 0; i < 100 && !refusal.closed().isDone(); i++) {
                socket.write("x");
                Thread.sleep(100);
            }
            assertTrue(refusal.closed().isDone(), "a body trickling in held its refused connection for 10 s");
            // Counted from after the refusal, so it may be short of the cap, but not by half
            var held = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - refusedAt);
            assertTrue(held >= 1000, "closed after " + held + " ms, before the 2000 ms cap");
            refusal.assertRefused();
        }));
    }

    @Test
    void vertexRefusalsAreAnswered() throws Exception {
        var duplicate = "{\"model\":\"a\",\"model\":\"b\"}";
        var reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com", "/v1/messages",
                "Content-Length: " + duplicate.length()) + duplicate);
        assertTrue(reply.startsWith("HTTP/1.1 400"), reply);
        var invalid = "{\"messages\":[1,]}";
        reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com", "/v1/messages",
                "Content-Length: " + invalid.length()) + invalid);
        assertTrue(reply.startsWith("HTTP/1.1 502"), reply);
    }

    /** The log in {@code dir} once it holds {@code marker}: it is written after the client has its answer. */
    /**
     * Neither a refused credential nor the first send of a 401 retry keeps its upstream connection:
     * more of them than the pool to a host holds (20) would otherwise leave the next request waiting.
     */
    @Test
    void vertexRetriesAndRefusalsLetTheirConnectionsGo() throws Exception {
        var body = "{\"anthropic_version\":\"vertex-2023-10-16\",\"messages\":[]}";
        for (int i = 0; i < 25; i++) {
            var path = "/v1/projects/proj/locations/us-east5/publishers/anthropic/models/refuse-once-" + i + ":rawPredict";
            var reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com", path,
                    "Content-Length: " + body.length()) + body);
            assertTrue(reply.startsWith("HTTP/1.1 200"), "retry " + i + ": " + reply);
        }
        gcloudFails = true;
        try {
            for (int i = 0; i < 25; i++) {
                vertexProxy.vertexToken.set(null);
                var reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com",
                        "/v1/projects/proj/locations/us-east5/publishers/anthropic/models/m:rawPredict",
                        "Content-Length: " + body.length()) + body);
                assertTrue(reply.startsWith("HTTP/1.1 502"), "refusal " + i + ": " + reply);
            }
        } finally {
            gcloudFails = false;
        }
        var reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com",
                "/v1/projects/proj/locations/us-east5/publishers/anthropic/models/m:rawPredict",
                "Content-Length: " + body.length()) + body);
        assertTrue(reply.startsWith("HTTP/1.1 200"), "after them: " + reply);
    }

    /** A small body never reaches the disk: a host low on disk serves it, where it refuses one that may spill. */
    @Test
    void smallVertexCallsAreServedWhenTheDiskIsLow() throws Exception {
        var store = vertexProxy.spoolStore;
        var full = new BodySpoolTest.FakeStore();
        full.room = false;
        full.admits = false;
        vertexProxy.spoolStore = full;
        try {
            var body = "{\"model\":\"m\",\"messages\":[]}";
            var reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com", "/v1/messages",
                    "Content-Length: " + body.length()) + body);
            assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
            reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com", "/v1/messages",
                    "Transfer-Encoding: chunked") + "0\r\n\r\n");
            assertTrue(reply.startsWith("HTTP/1.1 507"), "a body of unknown length may spill: " + reply);
        } finally {
            vertexProxy.spoolStore = store;
        }
    }

    /** Whatever goes wrong inside the Vertex path, the client gets an answer and the spool is given back. */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"accept", "end"})
    void anUnexpectedErrorIsAnswered(String where) throws Exception {
        rewriterFails = where;
        try {
            withSpoolBudget(64 * 1024, () -> {
                var body = "{\"model\":\"m\",\"messages\":[]}";
                var reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com", "/v1/messages",
                        "Content-Length: " + body.length()) + body);
                assertTrue(reply.startsWith("HTTP/1.1 502"), reply);
            });
        } finally {
            rewriterFails = null;
        }
    }

    /** Once an answer that is not a 401 has come and the body has all gone, the spool is given back: a long stream does not keep it. */
    @Test
    void theSpoolIsGivenBackWhileALongAnswerStreams() throws Exception {
        heldOpen = new CompletableFuture<>();
        var small = new SpoolBudget(SpoolBudget.DEFAULT_LIMIT);
        var budget = vertexProxy.spoolBudget;
        vertexProxy.spoolBudget = small;
        try {
            var path = "/v1/projects/proj/locations/us-east5/publishers/anthropic/models/hold-open:streamRawPredict";
            var body = "{\"anthropic_version\":\"vertex-2023-10-16\",\"messages\":[\"" + "z".repeat(100_000) + "\"]}";
            var socket = connect(vertexProxy, "api.anthropic.com");
            var answer = RelayRequestBodyTest.collectUntilClose(socket);
            socket.write(head("POST", "api.anthropic.com", path, "Content-Length: " + body.length()) + body);
            var upstream = heldOpen.get(5, TimeUnit.SECONDS);
            Eventually.holdsWithin(Duration.ofMillis(5000), () -> !(small.held() != 0));
            assertEquals(0, small.held(), "the body was kept while the answer streamed");
            upstream.end("data: done\n\n");
            assertTrue(answer.get(10, TimeUnit.SECONDS).contains("data: done"));
        } finally {
            vertexProxy.spoolBudget = budget;
        }
    }

    /**
     * A Vertex model call that upstream stops answering is cut at the client's silence budget, as any
     * other relay is (#1059), not dropped silently at the MITM server's idle timeout: with a 502 when
     * no head came, with a reset mid-stream. Either way its spool is given back.
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"no-answer", "hold-open"})
    void stalledVertexCallIsCutAtTheBudget(String model) throws Exception {
        heldOpen = new CompletableFuture<>();
        RelayRequestBodyTest.withBudget(vertexProxy.upstream, 1, () -> withSpoolBudget(SpoolBudget.DEFAULT_LIMIT, () -> {
            var path = "/v1/projects/proj/locations/us-east5/publishers/anthropic/models/" + model + ":streamRawPredict";
            var body = "{\"anthropic_version\":\"vertex-2023-10-16\",\"messages\":[]}";
            var socket = connect(vertexProxy, "api.anthropic.com");
            var answer = RelayRequestBodyTest.collectUntilClose(socket);
            socket.write(head("POST", "api.anthropic.com", path, "Content-Length: " + body.length()) + body);
            String reply;
            try {
                reply = answer.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                socket.close();
                reply = fail("A stalled Vertex call was not cut within five times the budget");
            }
            if (model.equals("hold-open")) {
                assertTrue(reply.startsWith("HTTP/1.1 200") && reply.contains("message_start"), reply);
                var upstream = heldOpen.get(5, TimeUnit.SECONDS);
                ArtifactCacheProxyTest.await("upstream's stream to be reset", upstream::closed);
            } else {
                assertTrue(reply.startsWith("HTTP/1.1 502"), reply);
                assertTrue(reply.contains("Upstream timed out"), reply);
            }
        }));
    }

    /**
     * A client that leaves before its relay ends resets the upstream request, as a cut does: while
     * the head is awaited, or partway through a streamed answer. Otherwise upstream generates, and
     * bills for, an answer nobody reads, until it ends or the read-idle timeout fires (#1448).
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,no-answer", "false,hold-open", "true,no-answer", "true,hold-open", "false,get-no-answer"})
    void aClientThatLeavesResetsTheUpstreamRequest(boolean vertex, String model) throws Exception {
        heldOpen = new CompletableFuture<>();
        unanswered = new CompletableFuture<>();
        var uri = vertex
                ? "/v1/projects/proj/locations/us-east5/publishers/anthropic/models/" + model + ":streamRawPredict"
                : "/v1/" + model;
        var body = "{\"anthropic_version\":\"vertex-2023-10-16\",\"messages\":[]}";
        var socket = connect(vertex ? vertexProxy : proxy, "api.anthropic.com");
        var streaming = new CompletableFuture<Void>();
        var bytes = new StringBuilder();
        socket.handler(b -> {
            bytes.append(b.toString(StandardCharsets.UTF_8));
            if (bytes.indexOf("message_start") >= 0) streaming.complete(null);
        });
        if (model.startsWith("get")) socket.write(head("GET", "api.anthropic.com", uri));
        else socket.write(head("POST", "api.anthropic.com", uri, "Content-Length: " + body.length()) + body);
        var upstream = (model.equals("hold-open") ? heldOpen : unanswered).get(5, TimeUnit.SECONDS);
        try {
            if (model.equals("hold-open")) streaming.get(5, TimeUnit.SECONDS);
            socket.close();
            ArtifactCacheProxyTest.await("upstream's request to be reset", upstream::closed);
        } finally {
            // Not left streaming into the next test's pool when this one fails
            if (!upstream.closed()) upstream.reset();
        }
    }

    static String waitForLog(Path dir, String marker) throws Exception {
        var last = "";
        for (int i = 0; i < 50; i++) {
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    var file = files.findFirst();
                    if (file.isPresent()) {
                        last = Files.readString(file.get());
                        if (last.contains(marker)) return last;
                    }
                }
            }
            Thread.sleep(100);
        }
        return fail("No debug log holding '" + marker + "' was written to " + dir + "; last read: " + last);
    }

    static NetSocket connect(MitmProxy p, String host) throws Exception {
        return client.connect(p.mitmPort(), "127.0.0.1", host)
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /** Sends the request in one write and returns everything the proxy answers until it closes. */
    static String exchange(MitmProxy p, String host, String request) throws Exception {
        return exchange(p, host, Buffer.buffer(request));
    }

    static String exchange(MitmProxy p, String host, Buffer request) throws Exception {
        received.clear();
        var socket = connect(p, host);
        var answer = RelayRequestBodyTest.collectUntilClose(socket);
        socket.write(request);
        try {
            return answer.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            socket.close();
            var sofar = answer.get(5, TimeUnit.SECONDS);
            return fail("The proxy did not close the connection within 10s; got " + sofar.substring(0, Math.min(300, sofar.length())));
        }
    }
}
