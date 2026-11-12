package dev.incusspawn.proxy;

import com.sun.net.httpserver.HttpServer;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A proxy whose ports another user's {@code isx-proxy} holds (#1570) is not waiting for an older
 * proxy of its own to stop: nothing it or its service does ends that one. It says who holds the
 * ports and fails at once, rather than retrying its bind as if a restart were under way.
 */
class ForeignHolderBindTest {

    @TempDir
    Path home;
    private String originalHome;
    private Vertx vertx;
    private ServerSocket mitmHolder;
    private HttpServer healthHolder;
    private final AtomicInteger healthProbes = new AtomicInteger();

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        vertx = ProxyTransport.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (healthHolder != null) healthHolder.stop(0);
        if (mitmHolder != null) mitmHolder.close();
        vertx.close().toCompletionStage().toCompletableFuture().get();
        System.setProperty("user.home", originalHome);
    }

    @Test
    void anotherUsersProxyOnThePortsFailsTheStartAtOnceAndIsNamed() throws Exception {
        // pid 1 belongs to root; the holder only has to be a process another user owns.
        var owner = ProcessHandle.of(1).flatMap(h -> h.info().user()).orElse(null);
        assumeTrue(owner != null, "pid 1's owner is readable");
        assumeFalse(owner.equals(System.getProperty("user.name")), "the test runs as another user than pid 1's");

        var proxy = holdPortsAndBuildProxy(1);
        var failure = assertThrows(Exception.class, () -> proxy.start(null));

        assertTrue(failure.getMessage().contains("pid 1, owned by user " + owner), failure.getMessage());
        assertTrue(failure.getMessage().contains("0.3.12"), failure.getMessage());
        // The wait for an own predecessor asks the holder again after each failed bind, up to 29
        // times; this one is asked once. Counted rather than timed: start() first mints a fresh CA
        // and a leaf per intercepted domain, whose RSA key generation takes seconds on a few slow
        // cores.
        assertEquals(1, healthProbes.get(), "asked the holder again, waiting for a proxy it cannot end");
    }

    private MitmProxy holdPortsAndBuildProxy(long holderPid) throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        mitmHolder = new ServerSocket(0, 50, loopback);
        healthHolder = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        healthHolder.createContext("/health", exchange -> {
            healthProbes.incrementAndGet();
            var body = ("{\"status\":\"ok\",\"version\":\"0.3.12\",\"pid\":" + holderPid + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        healthHolder.start();
        return new MitmProxy(vertx, "127.0.0.1", mitmHolder.getLocalPort(), healthHolder.getAddress().getPort(),
                "127.0.0.1", new ProxyCredentials("sk-ant-api03-real", "", false, "", "", List.of()));
    }
}
