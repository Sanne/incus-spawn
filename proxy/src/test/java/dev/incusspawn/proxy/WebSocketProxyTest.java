package dev.incusspawn.proxy;

import dev.incusspawn.DerEncoder;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.net.PemKeyCertOptions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketProxyTest {

    @TempDir
    static Path tempHome;

    static String origHome;
    static Vertx serverVertx;
    static Vertx clientVertx;
    static MitmProxy proxy;
    static int mitmPort;
    static HttpServer mockUpstream;

    static final ConcurrentLinkedQueue<String> capturedAuthHeaders = new ConcurrentLinkedQueue<>();
    static final AtomicInteger upstreamCloseCount = new AtomicInteger();

    @BeforeAll
    static void startProxy() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));

        // Generate the test CA (writes to tempHome/.config/incus-spawn/ca.{crt,key}).
        var ca = CertificateAuthority.loadOrCreate();

        // Mint a leaf cert for api.openai.com signed by the test CA — the mock
        // upstream serves this, exercising the full TLS+SNI WebSocket path.
        var leaf = ca.generateDomainCert("api.openai.com");
        var leafCertPem = DerEncoder.toPem("CERTIFICATE", leaf.cert().getEncoded());
        var leafKeyPem = DerEncoder.toPem("PRIVATE KEY", leaf.key().getEncoded());

        // Resolve api.openai.com to loopback for both the proxy's upstream
        // client and the test client.
        var resolver = new AddressResolverOptions()
                .setHostsValue(Buffer.buffer("127.0.0.1 api.openai.com\n"));

        serverVertx = Vertx.vertx(new VertxOptions().setAddressResolverOptions(resolver));

        // TLS mock upstream with a cert signed by the test CA.
        var keyCert = new PemKeyCertOptions()
                .setKeyValue(Buffer.buffer(leafKeyPem))
                .setCertValue(Buffer.buffer(leafCertPem));
        var serverOpts = new HttpServerOptions()
                .setSsl(true)
                .setKeyCertOptions(keyCert);
        mockUpstream = serverVertx.createHttpServer(serverOpts);
        mockUpstream.webSocketHandler(ws -> {
            var auth = ws.headers().get("Authorization");
            if (auth != null) capturedAuthHeaders.add(auth);
            ws.textMessageHandler(msg -> {
                if ("close-with-4008".equals(msg)) {
                    ws.close((short) 4008, "quota_exceeded");
                } else {
                    ws.writeTextMessage("echo:" + msg);
                }
            });
            ws.binaryMessageHandler(buf ->
                    ws.writeBinaryMessage(Buffer.buffer("echo:").appendBuffer(buf)));
            ws.closeHandler(v -> upstreamCloseCount.incrementAndGet());
        });
        int mockPort = mockUpstream.listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS).actualPort();

        mitmPort = findFreePort();
        int healthPort = findFreePort();

        var credentials = new ProxyCredentials(
                "", "", "", "", "sk-real-openai-key", false, "", "");
        proxy = new MitmProxy(serverVertx, "127.0.0.1", mitmPort, healthPort,
                "127.0.0.1", credentials);
        proxy.upstreamWsPort = mockPort;
        proxy.upstreamWsSsl = true;
        proxy.upstreamTrustAll = true;

        var readyLatch = new CountDownLatch(1);
        var proxyThread = new Thread(() -> {
            try {
                proxy.start(readyLatch::countDown);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "test-proxy");
        proxyThread.setDaemon(true);
        proxyThread.start();
        assertTrue(readyLatch.await(15, TimeUnit.SECONDS), "Proxy did not start in time");

        clientVertx = Vertx.vertx(new VertxOptions().setAddressResolverOptions(resolver));
    }

    @AfterAll
    static void stopProxy() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (mockUpstream != null) mockUpstream.close()
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            if (clientVertx != null) clientVertx.close()
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            if (serverVertx != null) serverVertx.close()
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @BeforeEach
    void clearCaptured() {
        capturedAuthHeaders.clear();
    }

    private WebSocketConnectOptions connectOptions(String path) {
        return new WebSocketConnectOptions()
                .setHost("api.openai.com")
                .setPort(mitmPort)
                .setSsl(true)
                .setURI(path);
    }

    private io.vertx.core.http.HttpClient createClient() {
        return clientVertx.createHttpClient(new HttpClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false));
    }

    @Test
    void textFramesAreRelayed() throws Exception {
        var client = createClient();
        var result = new CompletableFuture<String>();

        client.webSocket(connectOptions("/v1/realtime")).onSuccess(ws -> {
            ws.textMessageHandler(result::complete);
            ws.writeTextMessage("hello-ws");
        }).onFailure(result::completeExceptionally);

        assertEquals("echo:hello-ws", result.get(5, TimeUnit.SECONDS));
        client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void binaryFramesAreRelayed() throws Exception {
        var client = createClient();
        var result = new CompletableFuture<Buffer>();

        client.webSocket(connectOptions("/v1/data")).onSuccess(ws -> {
            ws.binaryMessageHandler(result::complete);
            ws.writeBinaryMessage(Buffer.buffer(new byte[]{0x01, 0x02, 0x03}));
        }).onFailure(result::completeExceptionally);

        var expected = Buffer.buffer("echo:").appendBytes(new byte[]{0x01, 0x02, 0x03});
        assertEquals(expected, result.get(5, TimeUnit.SECONDS));
        client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void authHeaderIsInjected() throws Exception {
        var client = createClient();
        var result = new CompletableFuture<String>();
        var opts = connectOptions("/v1/realtime");
        opts.addHeader("Authorization", "Bearer sk-placeholder");

        client.webSocket(opts).onSuccess(ws -> {
            ws.textMessageHandler(result::complete);
            ws.writeTextMessage("ping");
        }).onFailure(result::completeExceptionally);

        result.get(5, TimeUnit.SECONDS);

        assertFalse(capturedAuthHeaders.isEmpty(),
                "Mock upstream should have received an auth header");
        assertEquals("Bearer sk-real-openai-key", capturedAuthHeaders.peek(),
                "Proxy should inject real API key, not the placeholder");
        client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void clientClosePropagatesUpstream() throws Exception {
        var countBefore = upstreamCloseCount.get();
        var client = createClient();

        var ws = client.webSocket(connectOptions("/v1/close-test"))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        var echo = new CompletableFuture<String>();
        ws.textMessageHandler(echo::complete);
        ws.writeTextMessage("hi");
        assertEquals("echo:hi", echo.get(5, TimeUnit.SECONDS));

        ws.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        for (int i = 0; i < 50 && upstreamCloseCount.get() <= countBefore; i++) {
            Thread.sleep(100);
        }
        assertTrue(upstreamCloseCount.get() > countBefore,
                "Upstream WebSocket should close when client disconnects");
        client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void upstreamCloseCodeIsPropagated() throws Exception {
        var client = createClient();
        var closeCode = new CompletableFuture<Short>();

        var ws = client.webSocket(connectOptions("/v1/close-code-test"))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        ws.closeHandler(v -> closeCode.complete(ws.closeStatusCode()));
        ws.writeTextMessage("close-with-4008");

        assertEquals((short) 4008, closeCode.get(5, TimeUnit.SECONDS),
                "Upstream close status code should propagate through the proxy");
        client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void upstreamCloseDoesNotLogClientError() throws Exception {
        var client = createClient();
        var stderrCapture = new java.io.ByteArrayOutputStream();
        var origStderr = System.err;
        var captureErr = new java.io.PrintStream(stderrCapture);

        var ws = client.webSocket(connectOptions("/v1/close-quiet-test"))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        var closed = new CompletableFuture<Void>();
        ws.closeHandler(v -> closed.complete(null));

        System.setErr(captureErr);
        try {
            ws.writeTextMessage("close-with-4008");
            closed.get(5, TimeUnit.SECONDS);
            // Wait for any async exception handlers on the server event loop
            // to fire while stderr is still captured.
            Thread.sleep(500);
        } finally {
            System.setErr(origStderr);
        }

        var captured = stderrCapture.toString();
        assertFalse(captured.contains("WebSocket client error"),
                "Normal upstream close should not log a client error, got: " + captured);
        client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void proxySendsKeepalivePingsToClient() throws Exception {
        var client = createClient();
        var pingReceived = new CompletableFuture<Void>();

        var ws = client.webSocket(connectOptions("/v1/ping-test"))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Detect the proxy's keepalive ping via raw frame handler since
        // Vert.x handles ping/pong at the protocol level automatically.
        ws.frameHandler(frame -> {
            if (frame.isPing()) {
                pingReceived.complete(null);
            }
        });

        // The proxy sends keepalive pings every 30s. The periodic timer
        // starts on connect, so we wait up to 35s.
        pingReceived.get(35, TimeUnit.SECONDS);
        ws.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    static int findFreePort() throws Exception {
        try (var ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }
}
