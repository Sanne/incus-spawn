package dev.incusspawn.proxy;

import io.vertx.core.Context;
import io.vertx.core.Vertx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.incusspawn.proxy.ContainerTls.assertServerRefuses;
import static dev.incusspawn.proxy.ContainerTls.handshake;
import static dev.incusspawn.proxy.ContainerTls.startInBackground;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The MITM server's TLS setup as a container sees it: one certificate per SNI name, no ALPN,
 * a reload that swaps the certificates without touching the rest, and an SNI lookup -- which
 * can mint a leaf -- that never runs on an event loop. They drive the real {@link MitmProxy}
 * or read its own options, so they pin what it listens and reloads with, not a copy of it.
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

    /**
     * What keeps the SNI lookup off the event loop under Vert.x 5. Vert.x 4 runs the lookup on a
     * worker whatever the engine is told, so {@link #theSniLookupRunsOffTheEventLoop} passes
     * without the flag until the upgrade: this pins the flag itself until then. It reads the
     * options {@link MitmProxy#start} and {@link MitmProxy#reload} both take, not the bound
     * server, which exposes no engine options.
     */
    @Test
    void theSslEngineRunsItsDelegatedTasksOnWorkers() {
        var engine = MitmProxy.mitmServerOptions().getSslEngineOptions();
        assertTrue(engine.getUseWorkerThread(), "the MITM server's SSL engine runs on the event loop");
    }

    /**
     * Given port 0, both servers bind ports the kernel picks and report them once ready, so a
     * test never picks a port first and loses it to another listener before the proxy binds it.
     */
    @Test
    void portZeroBindsAndReportsTheActualPorts() throws Exception {
        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        startInBackground(proxy);

        assertNotEquals(0, proxy.mitmPort(), "the MITM port was not reported");
        assertNotEquals(0, proxy.healthPort(), "the health port was not reported");
        handshake(proxy.mitmPort(), HOSTS.get(0), CertificateAuthority.loadOrCreate().caCert());
        try (var client = HttpClient.newHttpClient()) {
            var health = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + proxy.healthPort() + "/health")).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(200, health.statusCode());
        }
    }

    @Test
    void thePortsAreRefusedUntilTheServersListen() {
        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());

        var mitm = assertThrows(IllegalStateException.class, proxy::mitmPort);
        assertTrue(mitm.getMessage().contains("MITM server is not listening yet"), mitm.getMessage());
        var health = assertThrows(IllegalStateException.class, proxy::healthPort);
        assertTrue(health.getMessage().contains("health server is not listening yet"), health.getMessage());
    }

    /** A fixed port still names a server that is not listening: Vert.x reports it before and after a failed bind. */
    @Test
    void aFixedPortThatFailedToBindIsNotReported() throws Exception {
        try (var taken = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            proxy = new MitmProxy(vertx, "127.0.0.1", taken.getLocalPort(), 0, "127.0.0.1",
                    ConfigFingerprint.load());
            assertThrows(Exception.class, () -> startInBackground(proxy), "the MITM port was taken");

            assertThrows(IllegalStateException.class, proxy::mitmPort);
        }
    }

    /**
     * A taken MITM port is waited on only while an older proxy answers on this proxy's own
     * health port (#1188). The stand-in old proxy frees both ports as it answers its first
     * probe, so the new one binds on its first retry; probing any other port finds nothing
     * and the start fails at once.
     */
    @Test
    void aTakenPortIsWaitedOnWhileTheHealthPortGivenAnswers() throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        var taken = new ServerSocket(0, 1, loopback);
        var oldHealth = new ServerSocket(0, 1, loopback);
        var probes = new AtomicInteger();
        var oldProxy = stoppingOnFirstProbe(oldHealth, probes, taken);
        try {
            proxy = new MitmProxy(vertx, "127.0.0.1", taken.getLocalPort(), oldHealth.getLocalPort(),
                    "127.0.0.1", ConfigFingerprint.load());
            startInBackground(proxy);

            assertEquals(1, probes.get());
            assertEquals(taken.getLocalPort(), proxy.mitmPort());
        } finally {
            taken.close();
            oldHealth.close();
            oldProxy.join(5_000);
        }
    }

    /**
     * An older proxy frees its health port after its MITM port (#1219), so a new one that has
     * bound the MITM port waits on the health port too while the old proxy still answers on it.
     * The stand-in frees the health port as it answers its first probe.
     */
    @Test
    void aTakenHealthPortIsWaitedOnWhileTheOldProxyAnswersOnIt() throws Exception {
        var oldHealth = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        var probes = new AtomicInteger();
        var oldProxy = stoppingOnFirstProbe(oldHealth, probes);
        try {
            proxy = new MitmProxy(vertx, "127.0.0.1", 0, oldHealth.getLocalPort(),
                    "127.0.0.1", ConfigFingerprint.load());
            startInBackground(proxy);

            assertEquals(1, probes.get());
            assertEquals(oldHealth.getLocalPort(), proxy.healthPort());
        } finally {
            oldHealth.close();
            oldProxy.join(5_000);
        }
    }

    /** A stop while the health port is waited on ends the start before it gets ready. */
    @Test
    void aStopWhileTheHealthPortIsWaitedOnEndsTheStartUnready() throws Exception {
        var oldHealth = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        var probed = new CountDownLatch(1);
        var oldProxy = answeringEveryProbe(oldHealth, probed);
        var ready = new AtomicBoolean();
        var failure = new AtomicReference<Exception>();
        try {
            proxy = new MitmProxy(vertx, "127.0.0.1", 0, oldHealth.getLocalPort(), "127.0.0.1",
                    ConfigFingerprint.load());
            var starting = startInThread(proxy, () -> ready.set(true), failure);
            assertTrue(probed.await(10, TimeUnit.SECONDS), "the health port was never waited on");

            proxy.stop();
            starting.join(5_000);

            assertFalse(starting.isAlive(), "start() kept waiting after stop()");
            assertNull(failure.get(), "a stopped start is not a failure");
            assertFalse(ready.get(), "a stopped start got ready");
            assertThrows(IllegalStateException.class, proxy::mitmPort, "the MITM server is still listening");
        } finally {
            oldHealth.close();
            oldProxy.join(5_000);
        }
    }

    /**
     * A stop that is still running when the health port comes free ends the start unready too,
     * and closes the health server it then bound: the stop latch drops only once stop() has
     * closed everything, so start() cannot wait for it to tell. The old proxy frees the port
     * from inside stop(), at its first log line, and stop() goes on only once start() has
     * parked on the stop latch, as it does both stopped and ready.
     */
    @Test
    void aStopThatOverlapsTheHealthBindEndsTheStartUnready() throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        var oldHealth = new ServerSocket(0, 50, loopback);
        int healthPort = oldHealth.getLocalPort();
        var probed = new CountDownLatch(1);
        var oldProxy = answeringEveryProbe(oldHealth, probed);
        var ready = new AtomicBoolean();
        var failure = new AtomicReference<Exception>();
        var starting = new AtomicReference<Thread>();
        var freedInsideStop = new AtomicBoolean();
        var originalErr = System.err;
        System.setErr(new PrintStream(originalErr, true) {
            @Override
            public void println(String line) {
                super.println(line);
                if (line == null || !line.contains("Stopping proxy")) return;
                freedInsideStop.set(true);
                try {
                    oldHealth.close();
                    // start() ends parked on the stop latch, ready or not: stop() goes on then.
                    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (starting.get().getState() != Thread.State.WAITING && starting.get().isAlive()
                            && System.nanoTime() < deadline) {
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        });
        var wasSuppressed = ProxyLog.setSuppressStderr(false);
        try {
            proxy = new MitmProxy(vertx, "127.0.0.1", 0, healthPort, "127.0.0.1", ConfigFingerprint.load());
            starting.set(startInThread(proxy, () -> ready.set(true), failure));
            assertTrue(probed.await(10, TimeUnit.SECONDS), "the health port was never waited on");

            proxy.stop();
            starting.get().join(5_000);
        } finally {
            System.setErr(originalErr);
            ProxyLog.setSuppressStderr(wasSuppressed);
            oldHealth.close();
            oldProxy.join(5_000);
        }

        assertTrue(freedInsideStop.get(), "stop() never logged, so the port was not freed inside it");
        assertFalse(starting.get().isAlive(), "start() kept running after stop()");
        assertNull(failure.get(), "a stopped start is not a failure");
        assertFalse(ready.get(), "a start that a stop overlapped got ready");
        try (var rebound = new ServerSocket()) {
            rebound.setReuseAddress(true);
            assertDoesNotThrow(() -> rebound.bind(new java.net.InetSocketAddress(loopback, healthPort)),
                    "the stopped start left the health port bound");
        }
    }

    /**
     * A reload while start() waits on a taken MITM port reaches the server that finally binds:
     * each attempt makes a new server, and one built from the certificates read before the wait
     * would serve the old CA's leaves until the next reload.
     */
    @Test
    void aReloadWhileTheMitmPortIsWaitedOnReachesTheServerThatBinds() throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        var taken = new ServerSocket(0, 1, loopback);
        var oldHealth = new ServerSocket(0, 50, loopback);
        var probed = new CountDownLatch(1);
        var oldProxy = answeringEveryProbe(oldHealth, probed);
        var ready = new CountDownLatch(1);
        var failure = new AtomicReference<Exception>();
        try {
            proxy = new MitmProxy(vertx, "127.0.0.1", taken.getLocalPort(), oldHealth.getLocalPort(),
                    "127.0.0.1", ConfigFingerprint.load());
            startInThread(proxy, ready::countDown, failure);
            assertTrue(probed.await(10, TimeUnit.SECONDS), "the MITM port was never waited on");

            Files.delete(home.resolve(".config/incus-spawn/ca.key"));
            Files.delete(home.resolve(".config/incus-spawn/ca.crt"));
            reload();
            var newCa = CertificateAuthority.loadOrCreate().caCert();
            taken.close();
            oldHealth.close();
            assertTrue(ready.await(10, TimeUnit.SECONDS), "the proxy did not get ready: " + failure.get());

            handshake(proxy.mitmPort(), HOSTS.get(0), newCa).leaf().verify(newCa.getPublicKey());
        } finally {
            taken.close();
            oldHealth.close();
            oldProxy.join(5_000);
        }
    }

    /** An older proxy that never finishes stopping: it answers every health probe until closed. */
    private static Thread answeringEveryProbe(ServerSocket health, CountDownLatch probed) {
        var oldProxy = new Thread(() -> {
            try (health) {
                while (true) {
                    try (var probe = health.accept()) {
                        probe.getInputStream().read(new byte[1024]);
                        probe.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
                    }
                    probed.countDown();
                }
            } catch (Exception closed) {
                // The test is over.
            }
        }, "old-proxy");
        oldProxy.start();
        return oldProxy;
    }

    private static Thread startInThread(MitmProxy proxy, Runnable onReady, AtomicReference<Exception> failure) {
        var starting = new Thread(() -> {
            try {
                proxy.start(onReady);
            } catch (Exception e) {
                failure.set(e);
            }
        }, "test-proxy");
        starting.setDaemon(true);
        starting.start();
        return starting;
    }

    /**
     * A health port held by something that is not a proxy is not waited on, and the failed
     * start does not leave the MITM server it already bound listening.
     */
    @Test
    void aHealthPortHeldByAnotherProcessFailsTheStartAndFreesTheMitmPort() throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        int mitmPort;
        try (var free = new ServerSocket(0, 1, loopback)) {
            mitmPort = free.getLocalPort();
        }
        try (var taken = new ServerSocket(0, 1, loopback)) {
            proxy = new MitmProxy(vertx, "127.0.0.1", mitmPort, taken.getLocalPort(), "127.0.0.1",
                    ConfigFingerprint.load());
            assertThrows(Exception.class, () -> startInBackground(proxy), "the health port was taken");
        }

        try (var rebound = new ServerSocket()) {
            rebound.setReuseAddress(true);
            assertDoesNotThrow(() -> rebound.bind(new java.net.InetSocketAddress(loopback, mitmPort)),
                    "the failed start left the MITM port bound");
        }
    }

    /**
     * A stand-in for an older proxy that is stopping: it frees {@code health} and {@code alsoFree}
     * as it answers its first health probe with 200, counted in {@code probes}.
     */
    private static Thread stoppingOnFirstProbe(ServerSocket health, AtomicInteger probes,
                                               ServerSocket... alsoFree) {
        var oldProxy = new Thread(() -> {
            try (health; var probe = health.accept()) {
                probes.incrementAndGet();
                health.close();
                for (var socket : alsoFree) socket.close();
                probe.getInputStream().read(new byte[1024]);
                probe.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
            } catch (Exception ignored) {
                // Closed unprobed by the test: its assertion reports it.
            }
        }, "old-proxy");
        oldProxy.start();
        return oldProxy;
    }

    private int startProxy() throws Exception {
        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        startInBackground(proxy);
        return proxy.mitmPort();
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
