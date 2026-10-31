package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDef;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An upstream that closes an idle keep-alive connection just as the relay sends a request on it
 * fails that request before any answer (#1914): a CI clone of a public GitHub repository got a 502
 * from {@code Connection was closed}. A safe request without a body is sent again, once, on another
 * connection; one with a body is not, since what it sent cannot be sent again.
 */
class StaleUpstreamConnectionTest {

    static final String HOST = "github.com";
    static final String REAL_GITHUB = "ghp_realtoken";

    @TempDir
    static Path tempHome;

    static String origHome;
    static Vertx vertx;
    static MitmProxy proxy;
    static NetClient client;
    static int mitmPort;

    /** Requests the stub has had on each of its connections. */
    static final Map<HttpConnection, AtomicInteger> perConnection = Collections.synchronizedMap(new WeakHashMap<>());
    /** Requests the stub dropped by closing their connection unanswered. */
    static final AtomicInteger dropped = new AtomicInteger();
    /** Requests the stub has had, by method. */
    static final Map<String, AtomicInteger> byMethod = new ConcurrentHashMap<>();
    /** Every request is dropped, on a new connection too. */
    static volatile boolean dropAll;
    /** The Authorization header of the last request the stub answered. */
    static volatile String answeredAuthorization;

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));

        var ca = CertificateAuthority.loadOrCreate();
        vertx = Vertx.vertx();
        var auth = new ToolDef.AuthDef();
        auth.setType("basic");
        auth.setUsername("x-access-token");
        auth.setPassword("${token}");
        var github = new ResolvedToolProxy("gh", HOST, auth, Map.of("token", REAL_GITHUB), Set.of("github"));
        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1",
                new ProxyCredentials("", "", false, "", "", List.of(github)));
        proxy.trustUpstreamCertificate(SpawnConfig.configDir().resolve("ca.crt").toString());
        var server = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(ca, HOST)))
                .requestHandler(StaleUpstreamConnectionTest::answer);
        var port = NpmCacheProxyTest.listen(server);
        ContainerTls.startInBackground(proxy);
        ProofTestSupport.register(proxy);
        proxy.overrideUpstream(HOST, "127.0.0.1", port);
        mitmPort = proxy.mitmPort();
        client = vertx.createNetClient(new NetClientOptions()
                .setSsl(true).setTrustAll(true).setHostnameVerificationAlgorithm(""));
    }

    /**
     * Answers the first request on a connection and keeps it open, so the proxy pools it; closes the
     * connection on the next one without a word, as a server does whose idle timeout fired just as
     * the request reached it.
     */
    static void answer(HttpServerRequest req) {
        byMethod.computeIfAbsent(req.method().name(), m -> new AtomicInteger()).incrementAndGet();
        var n = perConnection.computeIfAbsent(req.connection(), c -> new AtomicInteger()).incrementAndGet();
        if (n > 1 || dropAll) {
            dropped.incrementAndGet();
            req.connection().close();
            return;
        }
        answeredAuthorization = req.getHeader("Authorization");
        req.body().onSuccess(body -> req.response().putHeader("Content-Type", "text/plain").end("ok"));
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

    @BeforeEach
    void reset() {
        // A connection an earlier test left pooled starts afresh: each test's first request is answered
        perConnection.clear();
        dropped.set(0);
        byMethod.clear();
        dropAll = false;
        answeredAuthorization = null;
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    void safeRequestIsSentAgainWhenItsPooledConnectionCloses(String method) throws Exception {
        assertTrue(get("/octocat/Hello-World.git/info/refs?service=git-upload-pack").startsWith("HTTP/1.1 200"));

        var answer = exchange(Buffer.buffer(method + " /octocat/Hello-World.git/info/refs?service=git-upload-pack HTTP/1.1\r\n"
                + "Host: " + HOST + "\r\n"
                + "Connection: close\r\n\r\n"));

        assertEquals(1, dropped.get(), "The " + method + " did not go out on the pooled connection");
        assertEquals(method.equals("GET") ? 3 : 2, byMethod.get(method).get(), "The " + method + " was not sent again");
        assertTrue(answer.startsWith("HTTP/1.1 200"), answer);
    }

    /** The request sent again gets its credential again: its proof is checked as for any other. */
    @Test
    void requestSentAgainCarriesTheInjectedCredential() throws Exception {
        assertTrue(get("/warm").startsWith("HTTP/1.1 200"));

        var answer = get("/octocat/private.git/info/refs?service=git-upload-pack",
                "Authorization: " + basic(ProofTestSupport.token("github", "gho_")));

        assertEquals(1, dropped.get(), "The second request did not go out on the pooled connection");
        assertTrue(answer.startsWith("HTTP/1.1 200"), answer);
        assertEquals(basic(REAL_GITHUB), answeredAuthorization);
    }

    /** A resent request without a proof gets no credential: the resend fails closed as the first attempt does. */
    @Test
    void requestSentAgainWithoutProofGetsNoCredential() throws Exception {
        assertTrue(get("/warm").startsWith("HTTP/1.1 200"));

        var answer = get("/octocat/Hello-World.git/info/refs?service=git-upload-pack");

        assertEquals(1, dropped.get(), "The second request did not go out on the pooled connection");
        assertTrue(answer.startsWith("HTTP/1.1 200"), answer);
        assertNull(answeredAuthorization, "The resend reached upstream with a credential");
    }

    /** Sent again once only: an upstream that closes every connection gets the request twice, and the guest a 502. */
    @Test
    void safeRequestIsSentAgainOnlyOnce() throws Exception {
        dropAll = true;

        var answer = get("/octocat/Hello-World.git/info/refs?service=git-upload-pack");

        assertEquals(2, dropped.get(), "The request was not sent exactly twice");
        assertTrue(answer.startsWith("HTTP/1.1 502"), answer);
    }

    @Test
    void requestWithBodyIsNotSentAgain() throws Exception {
        assertTrue(get("/warm").startsWith("HTTP/1.1 200"));

        var body = "0032want 7fd1a60b01f91b314f59955a4e4d4e80d8edf11d\n00000009done\n";
        var answer = exchange(Buffer.buffer(
                "POST /octocat/Hello-World.git/git-upload-pack HTTP/1.1\r\n"
                        + "Host: " + HOST + "\r\n"
                        + "Content-Type: application/x-git-upload-pack-request\r\n"
                        + "Content-Length: " + body.length() + "\r\n"
                        + "Connection: close\r\n\r\n" + body));

        assertEquals(1, dropped.get(), "The POST did not go out on the pooled connection");
        assertEquals(1, byMethod.get("POST").get(), "The POST was sent upstream again");
        assertTrue(answer.startsWith("HTTP/1.1 502"), answer);
    }

    /** No body is not enough: a method that may change state upstream is never sent twice. */
    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "POST"})
    void bodylessUnsafeRequestIsNotSentAgain(String method) throws Exception {
        assertTrue(get("/warm").startsWith("HTTP/1.1 200"));

        var answer = exchange(Buffer.buffer(method + " /repos/octocat/Hello-World/git/refs/heads/topic HTTP/1.1\r\n"
                + "Host: " + HOST + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n"));

        assertEquals(1, dropped.get(), "The " + method + " did not go out on the pooled connection");
        assertEquals(1, byMethod.get(method).get(), "The " + method + " was sent upstream again");
        assertTrue(answer.startsWith("HTTP/1.1 502"), answer);
    }

    static String get(String path, String... headers) throws Exception {
        var head = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: " + HOST + "\r\n");
        for (var h : headers) head.append(h).append("\r\n");
        return exchange(Buffer.buffer(head.append("Connection: close\r\n\r\n").toString()));
    }

    static String basic(String password) {
        return "Basic " + Base64.getEncoder().encodeToString(("x-access-token:" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** Sends the request in one write and returns everything the proxy answers until it closes. */
    static String exchange(Buffer request) throws Exception {
        var socket = client.connect(mitmPort, "127.0.0.1", HOST)
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var answer = new CompletableFuture<String>();
        var bytes = Buffer.buffer();
        socket.handler(bytes::appendBuffer);
        socket.closeHandler(v -> answer.complete(bytes.toString(StandardCharsets.UTF_8)));
        socket.write(request);
        try {
            return answer.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            socket.close();
            return fail("The proxy did not close the connection within 10s");
        }
    }
}
