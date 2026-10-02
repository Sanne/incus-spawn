package dev.incusspawn.proxy;

import dev.incusspawn.DerEncoder;
import dev.incusspawn.tool.ToolDef;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.net.PemKeyCertOptions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketProxyTest {

    @TempDir
    static Path tempHome;

    static String origHome;
    static Vertx serverVertx;
    static Vertx clientVertx;
    // Every WebSocket of the test client is opened, written to and closed on this one
    // context, never from the test thread: driving the client from the test thread is
    // the pattern that corrupted ArtifactCacheProxyTest's client TLS stream under
    // load (#880).
    static Context clientContext;
    static MitmProxy proxy;
    static int mitmPort;
    static HttpServer mockUpstream;

    static final ConcurrentLinkedQueue<String> capturedAuthHeaders = new ConcurrentLinkedQueue<>();
    // Per path: a test that leaves its socket to close late must not satisfy another's wait
    static final Map<String, AtomicInteger> upstreamCloses = new ConcurrentHashMap<>();

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
            if (ws.path().startsWith("/v1/greet")) {
                // Speaks first, in the handshake's own event-loop turn: the proxy must
                // not lose a frame that reaches it before it has wired the relay
                ws.writeTextMessage("greeting");
            }
            ws.textMessageHandler(msg -> {
                if ("close-with-4008".equals(msg)) {
                    ws.close((short) 4008, "quota_exceeded");
                } else {
                    ws.writeTextMessage("echo:" + msg);
                }
            });
            ws.binaryMessageHandler(buf ->
                    ws.writeBinaryMessage(Buffer.buffer("echo:").appendBuffer(buf)));
            var path = ws.path();
            ws.closeHandler(v -> upstreamCloses.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet());
        });
        int mockPort = await(mockUpstream.listen(0, "127.0.0.1"), 5).actualPort();

        mitmPort = findFreePort();
        int healthPort = findFreePort();

        var openaiAuth = new ToolDef.AuthDef();
        openaiAuth.setType("bearer");
        openaiAuth.setToken("${token}");
        var toolProxies = List.of(new ResolvedToolProxy(
                "codex", "api.openai.com", openaiAuth, Map.of("token", "sk-real-openai-key")));
        var credentials = new ProxyCredentials(
                "", "", false, "", "", toolProxies);
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
        clientContext = clientVertx.getOrCreateContext();
    }

    @AfterAll
    static void stopProxy() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (mockUpstream != null) await(mockUpstream.close(), 2);
            if (clientVertx != null) await(clientVertx.close(), 2);
            if (serverVertx != null) await(serverVertx.close(), 2);
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

    private HttpClient createClient() {
        return clientVertx.createHttpClient(new HttpClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false));
    }

    /** Runs {@code action} on {@link #clientContext}, completing with the future it returns. */
    private static <T> Future<T> onClient(Supplier<Future<T>> action) {
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

    private static <T> T await(Future<T> future, long seconds) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(seconds, TimeUnit.SECONDS);
    }

    /**
     * Opens a WebSocket and runs {@code setup} on it in the same event-loop turn, so its
     * handlers are in place before any frame can arrive.
     */
    private static WebSocket connect(HttpClient client, WebSocketConnectOptions options,
            Consumer<WebSocket> setup) throws Exception {
        return await(onClient(() -> client.webSocket(options).map(ws -> {
            setup.accept(ws);
            return ws;
        })), 5);
    }

    private static void close(HttpClient client) throws Exception {
        await(onClient(client::close), 2);
    }

    @Test
    void textFramesAreRelayed() throws Exception {
        var client = createClient();
        var result = new CompletableFuture<String>();

        connect(client, connectOptions("/v1/realtime"), ws -> {
            ws.textMessageHandler(result::complete);
            ws.writeTextMessage("hello-ws");
        });

        assertEquals("echo:hello-ws", result.get(5, TimeUnit.SECONDS));
        close(client);
    }

    @Test
    void binaryFramesAreRelayed() throws Exception {
        var client = createClient();
        var result = new CompletableFuture<Buffer>();

        connect(client, connectOptions("/v1/data"), ws -> {
            ws.binaryMessageHandler(result::complete);
            ws.writeBinaryMessage(Buffer.buffer(new byte[]{0x01, 0x02, 0x03}));
        });

        var expected = Buffer.buffer("echo:").appendBytes(new byte[]{0x01, 0x02, 0x03});
        assertEquals(expected, result.get(5, TimeUnit.SECONDS));
        close(client);
    }

    @Test
    void authHeaderIsInjected() throws Exception {
        var client = createClient();
        var result = new CompletableFuture<String>();
        var opts = connectOptions("/v1/realtime");
        opts.addHeader("Authorization", "Bearer sk-placeholder");

        connect(client, opts, ws -> {
            ws.textMessageHandler(result::complete);
            ws.writeTextMessage("ping");
        });

        result.get(5, TimeUnit.SECONDS);

        assertFalse(capturedAuthHeaders.isEmpty(),
                "Mock upstream should have received an auth header");
        assertEquals("Bearer sk-real-openai-key", capturedAuthHeaders.peek(),
                "Proxy should inject real API key, not the placeholder");
        close(client);
    }

    @Test
    void clientClosePropagatesUpstream() throws Exception {
        var path = "/v1/close-test";
        var closes = upstreamCloses.computeIfAbsent(path, p -> new AtomicInteger());
        var countBefore = closes.get();
        var client = createClient();

        var echo = new CompletableFuture<String>();
        var ws = connect(client, connectOptions(path), socket -> {
            socket.textMessageHandler(echo::complete);
            socket.writeTextMessage("hi");
        });
        assertEquals("echo:hi", echo.get(5, TimeUnit.SECONDS));

        await(onClient(ws::close), 2);

        for (int i = 0; i < 50 && closes.get() <= countBefore; i++) {
            Thread.sleep(100);
        }
        assertTrue(closes.get() > countBefore,
                "Upstream WebSocket should close when client disconnects");
        close(client);
    }

    @Test
    void upstreamCloseCodeIsPropagated() throws Exception {
        var client = createClient();
        var closeCode = new CompletableFuture<Short>();

        connect(client, connectOptions("/v1/close-code-test"), ws -> {
            ws.closeHandler(v -> closeCode.complete(ws.closeStatusCode()));
            ws.writeTextMessage("close-with-4008");
        });

        assertEquals((short) 4008, closeCode.get(5, TimeUnit.SECONDS),
                "Upstream close status code should propagate through the proxy");
        close(client);
    }

    @Test
    void upstreamCloseDoesNotLogClientError() throws Exception {
        var client = createClient();
        var stderrCapture = new java.io.ByteArrayOutputStream();
        var origStderr = System.err;
        var captureErr = new java.io.PrintStream(stderrCapture);

        var closed = new CompletableFuture<Void>();
        var ws = connect(client, connectOptions("/v1/close-quiet-test"),
                socket -> socket.closeHandler(v -> closed.complete(null)));

        System.setErr(captureErr);
        try {
            await(onClient(() -> ws.writeTextMessage("close-with-4008")), 2);
            closed.get(5, TimeUnit.SECONDS);
            // Wait for any async exception handlers on the server event loop
            // to fire while stderr is still captured.
            Thread.sleep(500);
        } finally {
            System.setErr(origStderr);
        }

        captureErr.flush();
        var captured = stderrCapture.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(captured.contains("WebSocket client error"),
                "Normal upstream close should not log a client error, got: " + captured);
        close(client);
    }

    @Test
    void proxySendsKeepalivePingsToClient() throws Exception {
        var client = createClient();
        var pingReceived = new CompletableFuture<Void>();

        // Detect the proxy's keepalive ping via raw frame handler since
        // Vert.x handles ping/pong at the protocol level automatically.
        var ws = connect(client, connectOptions("/v1/ping-test"), socket -> socket.frameHandler(frame -> {
            if (frame.isPing()) {
                pingReceived.complete(null);
            }
        }));

        // The proxy sends keepalive pings every 30s. The periodic timer
        // starts on connect, so we wait up to 35s.
        pingReceived.get(35, TimeUnit.SECONDS);
        await(onClient(ws::close), 2);
        close(client);
    }

    /**
     * Frames a client sends the moment its handshake completes must reach upstream. The
     * proxy only wires the relay once its own upstream WebSocket is open, so until then
     * the client's socket is paused and its frames buffered; a frame delivered to an
     * unwired socket would be dropped. Many sockets at once, each writing in the turn
     * its handshake completed, to give that window a chance.
     * <p>
     * On Vert.x 4 the existing relay tests already fail when that window is mishandled;
     * this one and {@link #upstreamFrameSentRightAfterTheHandshakeIsNotLost} are canaries
     * for Vert.x 5, which hands over the accepted socket later and already flowing.
     */
    @Test
    void framesSentRightAfterTheHandshakeAreNotLost() throws Exception {
        var client = createClient();
        try {
            int sockets = 40;
            var echoes = new ConcurrentLinkedQueue<String>();
            var allEchoed = new CountDownLatch(sockets);
            var opened = new ArrayList<Future<WebSocket>>();
            var expected = new ArrayList<String>();
            for (int i = 0; i < sockets; i++) {
                var message = "burst-" + i;
                expected.add(message + " <- echo:" + message);
                opened.add(onClient(() -> client.webSocket(connectOptions("/v1/burst")).map(ws -> {
                    ws.textMessageHandler(msg -> {
                        echoes.add(message + " <- " + msg);
                        allEchoed.countDown();
                    });
                    ws.writeTextMessage(message);
                    return ws;
                })));
            }
            for (var f : opened) await(f, 10);
            assertTrue(allEchoed.await(10, TimeUnit.SECONDS),
                    "Every frame sent right after the handshake should be relayed, got " + echoes);
            // Each socket got its own echo: nothing cross-routed or dropped
            assertEquals(expected.stream().sorted().toList(), echoes.stream().sorted().toList());
        } finally {
            // 40 open relays would otherwise run on into the following tests
            close(client);
        }
    }

    /**
     * Upstream speaking first, in the same turn its handshake completes, must reach the client.
     * A Vert.x 5 canary, like {@link #framesSentRightAfterTheHandshakeAreNotLost}.
     */
    @Test
    void upstreamFrameSentRightAfterTheHandshakeIsNotLost() throws Exception {
        var client = createClient();
        var greeting = new CompletableFuture<String>();

        connect(client, connectOptions("/v1/greet"), ws -> ws.textMessageHandler(greeting::complete));

        assertEquals("greeting", greeting.get(5, TimeUnit.SECONDS));
        close(client);
    }

    /** An upgrade request without {@code Sec-WebSocket-Key} is refused with a 400, not left hanging. */
    @Test
    void malformedUpgradeIsAnswered400() throws Exception {
        try (var socket = openTls()) {
            var out = socket.getOutputStream();
            out.write(("GET /v1/no-key HTTP/1.1\r\n"
                    + "Host: api.openai.com\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            var head = readHead(socket.getInputStream());
            assertTrue(head.startsWith("HTTP/1.1 400"), "Expected a 400, got: " + head);
        }
    }

    /**
     * A frame pipelined in the same write as the upgrade request is relayed. RFC 6455
     * 4.1 says a client waits for the 101 first, so this is not a requirement -- but the
     * proxy relays it today: the frame is buffered while the client socket is paused
     * and upstream connects, like any other early frame.
     */
    @Test
    void framePipelinedWithTheUpgradeRequestIsRelayed() throws Exception {
        try (var socket = openTls()) {
            // RFC 6455's sample nonce, encoded here so a secret scanner does not mistake it for a key
            var key = Base64.getEncoder().encodeToString("the sample nonce".getBytes(StandardCharsets.US_ASCII));
            var request = ("GET /v1/pipelined HTTP/1.1\r\n"
                    + "Host: api.openai.com\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            // One masked text frame, as a client must send it
            var payload = "pipelined".getBytes(StandardCharsets.UTF_8);
            var all = new ByteArrayOutputStream();
            all.write(request);
            all.write(0x81);
            all.write(0x80 | payload.length);
            byte[] mask = {1, 2, 3, 4};
            all.write(mask);
            for (int i = 0; i < payload.length; i++) all.write(payload[i] ^ mask[i % 4]);
            var out = socket.getOutputStream();
            out.write(all.toByteArray());
            out.flush();

            var in = socket.getInputStream();
            var head = readHead(in);
            assertTrue(head.startsWith("HTTP/1.1 101"), "Expected a 101, got: " + head);
            int b0 = in.read();
            assertEquals(0x81, b0, "Expected one unfragmented text frame (-1: the connection closed)");
            int len = in.read();
            assertTrue(len >= 0 && len < 126, "Expected a short unmasked frame, length byte " + len);
            var body = in.readNBytes(len);
            assertEquals("echo:pipelined", new String(body, StandardCharsets.UTF_8));
        }
    }

    /** A raw TLS connection to the proxy as {@code api.openai.com}, for requests no WebSocket client would send. */
    private SSLSocket openTls() throws Exception {
        var trustAll = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[]{trustAll}, null);
        var socket = (SSLSocket) ssl.getSocketFactory().createSocket("127.0.0.1", mitmPort);
        var params = socket.getSSLParameters();
        params.setServerNames(List.of(new SNIHostName("api.openai.com")));
        socket.setSSLParameters(params);
        socket.setSoTimeout(5_000);
        socket.startHandshake();
        return socket;
    }

    /** Reads an HTTP response head up to and including the blank line, byte by byte. */
    private static String readHead(InputStream in) throws IOException {
        var head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) break;
            head.append((char) b);
        }
        return head.toString();
    }

    static int findFreePort() throws Exception {
        try (var ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }
}
