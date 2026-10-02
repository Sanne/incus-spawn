package dev.incusspawn.proxy;

import io.vertx.core.Context;
import io.vertx.core.Vertx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.incusspawn.proxy.ContainerTls.assertServerRefuses;
import static dev.incusspawn.proxy.ContainerTls.freePort;
import static dev.incusspawn.proxy.ContainerTls.handshake;
import static dev.incusspawn.proxy.ContainerTls.startInBackground;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The MITM server's TLS setup as a container sees it: one certificate per SNI name, no ALPN,
 * a reload that swaps the certificates without touching the rest, and an SNI lookup -- which
 * can mint a leaf -- that never runs on an event loop. Both drive the real {@link MitmProxy},
 * so they pin the options it listens and reloads with, not a copy of them.
 */
class MitmTlsTest {

    // Built-in intercepted domains, so they survive a reload from an empty config.
    private static final List<String> HOSTS = List.of("cdn01.quay.io", "x.y.repo1.maven.org");

    @TempDir
    Path home;
    private String originalHome;
    private Vertx vertx;
    private MitmProxy proxy;

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
            if (proxy != null) proxy.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void aReloadSwapsCertificatesButKeepsSniAndTheNegotiatedProtocol() throws Exception {
        int port = startProxy();
        var oldCa = CertificateAuthority.loadOrCreate().caCert();
        var before = handshakeAll(port, oldCa);

        // Rotate the CA: only a reload that really installed new key/cert options can
        // serve leaves it signed.
        Files.delete(home.resolve(".config/incus-spawn/ca.key"));
        Files.delete(home.resolve(".config/incus-spawn/ca.crt"));
        reload();
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
        assertServerRefuses(port, null, newCa,
                "a client sending no SNI is still refused after a reload");
    }

    /**
     * Minting a leaf generates an RSA key and writes it to disk, so the SNI lookup must stay
     * off the event loop, or every connection on that loop stalls behind it. Vert.x 4 always
     * runs the lookup on a worker; Vert.x 5 runs it on the event loop unless the SSL engine is
     * told to use worker threads.
     * <p>
     * The mint is observed through the line {@code InterceptedCertOptions} logs for it, which
     * {@link ProxyLog} prints on the calling thread: no seam is needed in the proxy, and the
     * server under test is the one {@link MitmProxy#start} builds.
     */
    @Test
    void theSniLookupRunsOffTheEventLoop() throws Exception {
        int port = startProxy();
        var ca = CertificateAuthority.loadOrCreate().caCert();
        // A name two labels under an intercepted domain has no pre-minted cert, so the
        // handshake makes the lookup mint *.b.quay.io.
        var minting = "Minting certificate for *.b.quay.io";
        var mints = new AtomicInteger();
        var mintedOnEventLoop = ConcurrentHashMap.<String>newKeySet();
        var originalErr = System.err;
        System.setErr(new PrintStream(originalErr, true) {
            @Override
            public void println(String line) {
                if (line != null && line.contains(minting)) {
                    mints.incrementAndGet();
                    if (Context.isOnEventLoopThread()) mintedOnEventLoop.add(Thread.currentThread().getName());
                }
                super.println(line);
            }
        });
        ProxyLog.setSuppressStderr(false);
        try {
            handshake(port, "a.b.quay.io", ca);
        } finally {
            System.setErr(originalErr);
        }

        assertTrue(mints.get() > 0, "the SNI lookup never minted a leaf for a.b.quay.io");
        assertEquals(Set.of(), mintedOnEventLoop, "SNI lookup minted on these event-loop threads");
    }

    private int startProxy() throws Exception {
        int port = freePort();
        proxy = new MitmProxy(vertx, "127.0.0.1", port, freePort(), "127.0.0.1", ConfigFingerprint.load());
        startInBackground(proxy);
        return port;
    }

    /** {@link MitmProxy#reload} reports a failure instead of throwing it; surface it here, not as a later handshake error. */
    private void reload() {
        var out = new ByteArrayOutputStream();
        var originalOut = System.out;
        var originalErr = System.err;
        System.setOut(new PrintStream(new ProxyMain.TeeOutputStream(originalOut, out), true));
        System.setErr(new PrintStream(new ProxyMain.TeeOutputStream(originalErr, out), true));
        try {
            proxy.reload();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        assertTrue(out.toString().contains("Configuration reloaded successfully."), "the reload failed:\n" + out);
    }

    private static Map<String, ContainerTls.Handshake> handshakeAll(int port, X509Certificate ca) throws Exception {
        var result = new HashMap<String, ContainerTls.Handshake>();
        for (var host : HOSTS) result.put(host, handshake(port, host, ca));
        return result;
    }
}
