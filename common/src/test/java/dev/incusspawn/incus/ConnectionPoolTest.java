package dev.incusspawn.incus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConnectionPool} expiry and health checks. The TTL exists so a parked connection is
 * always retired well before the in-VM forwarder's {@code socat -T} reaps it; a short TTL
 * stands in for the real 5s so the tests don't wait it out. Retention caps and reuse are in
 * {@link KeepAliveConnectionTest}.
 */
class ConnectionPoolTest {

    private static final long SHORT_TTL_NANOS = 100_000_000L; // 100ms

    private LossyIncusServer server;
    private int openBefore;

    @BeforeEach
    void start() throws IOException {
        server = new LossyIncusServer();
        openBefore = UnixSocketTransport.openConnectionCount();
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
        assertEquals(openBefore, UnixSocketTransport.openConnectionCount(),
                "every connection the pool evicts must be closed, not dropped");
    }

    private KeepAliveConnection used() throws IOException {
        var c = KeepAliveConnection.open(server.socketPath());
        c.execute("GET", "/x", null, Map.of(), new byte[0], 5);
        return c;
    }

    @Test
    @Timeout(10)
    void expiredConnectionIsClosedOnBorrowRatherThanReused() throws Exception {
        var pool = new ConnectionPool(SHORT_TTL_NANOS);
        var c = used();
        pool.release(c);
        Thread.sleep(150);
        assertNull(pool.borrow(server.socketPath()), "a connection idle past its TTL must not be handed out");
        assertEquals(KeepAliveConnection.State.DEAD, c.state());
    }

    @Test
    @Timeout(10)
    void expiredConnectionsArePrunedOnRelease() throws Exception {
        var pool = new ConnectionPool(SHORT_TTL_NANOS);
        var old = used();
        pool.release(old);
        Thread.sleep(150);
        var fresh = used();
        pool.release(fresh);
        assertEquals(1, pool.idleCount(server.socketPath()));
        assertEquals(KeepAliveConnection.State.DEAD, old.state());
        assertSame(fresh, pool.borrow(server.socketPath()));
        fresh.close();
    }

    @Test
    @Timeout(10)
    void connectionTheServerAskedToCloseIsNeverParked() throws IOException {
        var pool = new ConnectionPool();
        var c = KeepAliveConnection.open(server.socketPath());
        c.execute("GET", "/x", null, Map.of("Connection", "close"), new byte[0], 5);
        assertFalse(c.healthy(), "Connection: close in the response makes it unusable");
        pool.release(c);
        assertEquals(0, pool.idleCount(server.socketPath()));
        assertEquals(KeepAliveConnection.State.DEAD, c.state());
    }

    @Test
    @Timeout(10)
    void connectionsArePooledPerSocketPath() throws IOException {
        try (var other = new LossyIncusServer()) {
            var pool = new ConnectionPool();
            pool.release(used());
            assertNull(pool.borrow(other.socketPath()), "a connection to one daemon must never serve another");
            var c = pool.borrow(server.socketPath());
            assertNotNull(c);
            c.close();
        }
    }
}
