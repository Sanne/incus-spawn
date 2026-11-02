package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.incusspawn.proxy.ContainerTls.startInBackground;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MitmProxy#stop()} waits for its listeners and upstream clients to close within one
 * budget that holds on a loaded host, and says which did not (#1894). A close that took longer
 * used to be dropped after 1 or 2 s without a word, leaving a port bound after stop() returned.
 * <p>
 * A slow close is made by stalling the proxy's only event loop, which every close needs: its
 * completion is delivered there. Nothing is timed but the stall itself.
 * <p>
 * A completed close may still hold its port for a moment: the JDK closes a channel registered
 * with a selector only when that selector next runs, on Vert.x's acceptor thread (#1942). So a
 * port is checked over a short grace, far shorter than the gap a dropped close would leave.
 */
class ProxyStopTest {

    /** Long enough for a selector to run on a loaded host; a dropped close leaves its port bound for a second. */
    private static final long RELEASE_GRACE_MILLIS = 250;

    @TempDir
    Path home;
    private String originalHome;
    private Vertx vertx;
    private MitmProxy proxy;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".config/incus-spawn"));
        // One event loop, so stalling it stalls every close; the stall is on purpose, not a blocked thread
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1)
                .setBlockedThreadCheckInterval(TimeUnit.MINUTES.toMillis(10)));
        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        startInBackground(proxy);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            release.countDown();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void aCloseSlowerThanTheOldLimitsIsWaitedFor() throws Exception {
        int mitm = proxy.mitmPort();
        int health = proxy.healthPort();
        stallEventLoop();
        var releaser = new Thread(() -> {
            try {
                // Past the 2 s the MITM listener used to get, well inside the budget
                Thread.sleep(3_000);
            } catch (InterruptedException ignored) {
            } finally {
                release.countDown();
            }
        });
        releaser.start();

        proxy.stop();

        assertTrue(becomesFree(mitm), "stop() returned with the MITM port still bound:\n" + lifecycleLog());
        assertTrue(becomesFree(health), "stop() returned with the health port still bound:\n" + lifecycleLog());
        assertFalse(lifecycleLog().contains("did not close"), "a close inside the budget was reported:\n" + lifecycleLog());
    }

    @Test
    void aClosedChannelIsWaitedForUntilItsSelectorReleasesThePort() throws Exception {
        try (var selector = Selector.open(); var channel = ServerSocketChannel.open()) {
            channel.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_ACCEPT);
            int port = ((InetSocketAddress) channel.getLocalAddress()).getPort();
            channel.close();
            assertFalse(isFree(port), "the JDK released a selector's channel before the selector ran");

            var selecting = new Thread(() -> {
                try {
                    Thread.sleep(50);
                    selector.selectNow();
                } catch (Exception ignored) {
                }
            });
            selecting.start();

            assertTrue(becomesFree(port), "a port the selector released was reported bound");
            selecting.join();
        }
    }

    @Test
    void aCloseThatOutlastsTheBudgetIsReported() throws Exception {
        stallEventLoop();

        proxy.stop();

        var log = lifecycleLog();
        assertTrue(log.contains("[WARN] MITM listener did not close"), "the stuck MITM listener went unreported:\n" + log);
        assertTrue(log.contains("[WARN] Health listener did not close"), "the stuck health listener went unreported:\n" + log);
        assertTrue(log.contains("may still be bound"), "the warning does not explain the bound port:\n" + log);
    }

    private void stallEventLoop() throws InterruptedException {
        var stalled = new CountDownLatch(1);
        vertx.runOnContext(v -> {
            stalled.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(stalled.await(5, TimeUnit.SECONDS), "the event loop never ran the stall");
    }

    private static String lifecycleLog() throws Exception {
        var file = Environment.proxyLifecycleLogFile();
        return Files.exists(file) ? Files.readString(file) : "";
    }

    private static boolean becomesFree(int port) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RELEASE_GRACE_MILLIS);
        while (!isFree(port)) {
            if (System.nanoTime() - deadline > 0) return false;
            Thread.sleep(5);
        }
        return true;
    }

    private static boolean isFree(int port) {
        try (var ignored = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
