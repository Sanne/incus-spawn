package dev.incusspawn.proxy;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.net.KeyCertOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The MITM server's TLS setup as a container sees it: one certificate per SNI name, no ALPN,
 * and a reload that swaps the certificates without touching the rest. {@code reload()} hands
 * Vert.x a fresh {@code ServerSSLOptions} holding only the key/cert options, so these pin that
 * SNI and the listen-time settings survive it.
 */
class MitmTlsTest {

    // Built-in intercepted domains, so they survive a reload from an empty config.
    private static final List<String> HOSTS = List.of("cdn01.quay.io", "x.y.repo1.maven.org");

    @TempDir
    Path home;
    private String originalHome;
    private Vertx vertx;

    @BeforeEach
    void isolateHome() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".config/incus-spawn"));
        vertx = Vertx.vertx();
    }

    @AfterEach
    void restore() throws Exception {
        try {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void aReloadSwapsCertificatesButKeepsSniAndTheNegotiatedProtocol() throws Exception {
        int port = freePort();
        var proxy = new MitmProxy(vertx, "127.0.0.1", port, freePort(), "127.0.0.1", ConfigFingerprint.load());
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
        assertTrue(ready.await(30, TimeUnit.SECONDS), "Proxy did not start in time");
        try {
            var oldCa = CertificateAuthority.loadOrCreate().caCert();
            var before = handshakeAll(port, oldCa);

            // Rotate the CA: only a reload that really installed new key/cert options can
            // serve leaves it signed.
            Files.delete(home.resolve(".config/incus-spawn/ca.key"));
            Files.delete(home.resolve(".config/incus-spawn/ca.crt"));
            proxy.reload();
            var newCa = CertificateAuthority.loadOrCreate().caCert();
            assertNotEquals(oldCa.getPublicKey(), newCa.getPublicKey(), "the CA did not rotate");

            var after = handshakeAll(port, newCa);
            for (var host : HOSTS) {
                var b = before.get(host);
                var a = after.get(host);
                a.leaf().verify(newCa.getPublicKey());
                assertEquals(b.alpn(), a.alpn(), "ALPN changed across the reload for " + host);
                assertEquals(b.protocol(), a.protocol(), "TLS version changed across the reload for " + host);
                assertEquals(b.cipher(), a.cipher(), "cipher changed across the reload for " + host);
            }
            // Clients still offer h2; the MITM server has never answered with ALPN.
            assertEquals("", after.get(HOSTS.get(0)).alpn());
            assertNotEquals(after.get(HOSTS.get(0)).leaf(), after.get(HOSTS.get(1)).leaf(),
                    "every SNI name must get its own leaf after a reload");
            assertThrows(SSLException.class, () -> handshake(port, null, newCa),
                    "a client sending no SNI is still refused after a reload");
        } finally {
            proxy.stop();
        }
    }

    /**
     * Minting a leaf generates an RSA key and writes it to disk, so the SNI lookup must stay
     * off the event loop. Vert.x 4 always ran it on a worker; Vert.x 5 runs it on the event
     * loop unless the SSL engine is told to use worker threads.
     */
    @Test
    void theSniLookupRunsOffTheEventLoop() throws Exception {
        var onEventLoop = ConcurrentHashMap.<String>newKeySet();
        var ca = CertificateAuthority.loadOrCreate();
        var delegate = new InterceptedCertOptions(ca, Set.of("quay.io"));
        var recording = new KeyCertOptions() {
            @Override public KeyCertOptions copy() { return this; }
            @Override public KeyManagerFactory getKeyManagerFactory(Vertx v) { return delegate.getKeyManagerFactory(v); }
            @Override public Function<String, KeyManagerFactory> keyManagerFactoryMapper(Vertx v) {
                var mapper = delegate.keyManagerFactoryMapper(v);
                return name -> {
                    onEventLoop.add(name + "=" + Context.isOnEventLoopThread()
                            + "@" + Thread.currentThread().getName());
                    return mapper.apply(name);
                };
            }
        };
        int port = freePort();
        var server = vertx.createHttpServer(MitmProxy.mitmServerOptions("127.0.0.1", port, recording))
                .requestHandler(r -> r.response().end());
        server.listen().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        handshake(port, "a.b.quay.io", ca.caCert());

        assertFalse(onEventLoop.isEmpty(), "the SNI mapper was never asked");
        for (var call : onEventLoop) {
            assertTrue(call.startsWith("a.b.quay.io=false@"), "SNI lookup ran on the event loop: " + call);
        }
    }

    record Handshake(X509Certificate leaf, String alpn, String protocol, String cipher) {}

    private static Map<String, Handshake> handshakeAll(int port, X509Certificate ca) throws Exception {
        var result = new java.util.HashMap<String, Handshake>();
        for (var host : HOSTS) result.put(host, handshake(port, host, ca));
        return result;
    }

    private static Handshake handshake(int port, String host, X509Certificate ca) throws Exception {
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("isx", ca);
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, tmf.getTrustManagers(), null);
        try (var socket = (SSLSocket) tls.getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            var params = socket.getSSLParameters();
            params.setServerNames(host == null ? List.of() : List.of(new SNIHostName(host)));
            params.setEndpointIdentificationAlgorithm("HTTPS");
            params.setApplicationProtocols(new String[]{"h2", "http/1.1"});
            socket.setSSLParameters(params);
            socket.startHandshake();
            var session = socket.getSession();
            return new Handshake((X509Certificate) session.getPeerCertificates()[0],
                    socket.getApplicationProtocol(), session.getProtocol(), session.getCipherSuite());
        }
    }

    private static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
