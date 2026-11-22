package dev.incusspawn.proxy;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A proxy that cannot read the instance list from Incus says so on {@code /health}, to the host,
 * for {@code isx proxy status}, and stops saying so once Incus answers, with no instance traffic (#2386).
 */
@Timeout(60)
class IncusErrorHealthTest {

    @TempDir
    Path home;
    private String origHome;
    private Vertx vertx;
    private MitmProxy proxy;

    @AfterEach
    void stop() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            if (origHome != null) System.setProperty("user.home", origHome);
        }
    }

    @Test
    void theHostIsToldWhyTheInstanceListCannotBeRead() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.writeString(Files.createDirectories(home.resolve(".config/incus-spawn")).resolve("config.yaml"), "{}\n");
        CertificateAuthority.loadOrCreate();
        vertx = Vertx.vertx();
        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        var up = new AtomicBoolean();
        var now = new AtomicLong(1_000_000);
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon();
        var registry = new InstanceRegistry(daemon.clientReachableWhen(up::get, now::get), now::get);
        proxy.useInstanceRegistry(registry);
        ContainerTls.startInBackground(proxy);

        assertFalse(registry.refresh());
        var info = ProxyHealthCheck.fetchProxyInfo("127.0.0.1", proxy.healthPort(), 5_000);
        assertTrue(info.hasIncusError());
        assertTrue(info.incusError().endsWith(" failed: the Incus API did not answer"), info.incusError());

        // No instance sends a request: the status read itself lists again, in the background
        up.set(true);
        now.addAndGet(60_000);
        dev.incusspawn.util.Eventually.await("a status read after Incus answers to list again",
                () -> !ProxyHealthCheck.fetchProxyInfo("127.0.0.1", proxy.healthPort(), 5_000).hasIncusError());
        assertFalse(registry.listingUnavailable());
    }
}
