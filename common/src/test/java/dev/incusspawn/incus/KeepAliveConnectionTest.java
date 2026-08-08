package dev.incusspawn.incus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolation tests for the keep-alive connection + pool against a fake HTTP/1.1 server on a
 * Unix socket — no Incus required. Verifies reuse (one socket for sequential requests), pool
 * park/borrow, stale-connection detection, and overflow beyond the retention cap.
 */
class KeepAliveConnectionTest {

    private Path dir;
    private Path sock;
    private ServerSocketChannel server;
    private final AtomicInteger connectionsAccepted = new AtomicInteger();
    private final AtomicInteger requestsHandled = new AtomicInteger();
    private volatile int closeAfterRequests = Integer.MAX_VALUE;
    private volatile boolean corruptResponse = false;

    @BeforeEach
    void start() throws IOException {
        dir = Files.createTempDirectory("kac");
        sock = dir.resolve("s.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(sock.toString()));
        Thread.ofVirtual().start(this::acceptLoop);
    }

    @AfterEach
    void stop() throws IOException {
        try { server.close(); } catch (IOException ignored) {}
        Files.deleteIfExists(sock);
        Files.deleteIfExists(dir);
    }

    private void acceptLoop() {
        while (server.isOpen()) {
            try {
                var conn = server.accept();
                connectionsAccepted.incrementAndGet();
                Thread.ofVirtual().start(() -> handle(conn));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(SocketChannel conn) {
        try (conn) {
            var in = Channels.newInputStream(conn);
            var out = Channels.newOutputStream(conn);
            int handled = 0;
            while (handled < closeAfterRequests) {
                var reqLine = readLine(in);
                if (reqLine == null) return; // client closed
                int cl = 0;
                String h;
                while (!(h = readLine(in)).isEmpty()) {
                    if (h.toLowerCase().startsWith("content-length:")) {
                        cl = Integer.parseInt(h.substring(15).trim());
                    }
                }
                if (cl > 0) in.readNBytes(cl);
                handled++;
                requestsHandled.incrementAndGet();
                String resp;
                if (corruptResponse) {
                    // Non-numeric Content-Length → NumberFormatException while parsing.
                    resp = "HTTP/1.1 200 OK\r\nContent-Length: not-a-number\r\n\r\n";
                } else {
                    var bodyStr = "{\"n\":" + handled + "}";
                    resp = "HTTP/1.1 200 OK\r\nContent-Length: " + bodyStr.length() + "\r\n\r\n" + bodyStr;
                }
                out.write(resp.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int c = in.read();
        if (c == -1) return null;
        while (c != -1 && c != '\n') {
            if (c != '\r') sb.append((char) c);
            c = in.read();
        }
        return sb.toString();
    }

    @Test
    @Timeout(10)
    void sequentialRequestsReuseOneSocket() throws IOException {
        var conn = KeepAliveConnection.open(sock.toString());
        var r1 = conn.execute("GET", "/1.0", null, Map.of(), new byte[0], 5);
        var r2 = conn.execute("GET", "/1.0", null, Map.of(), new byte[0], 5);
        conn.close();
        assertTrue(r1.isSuccess());
        assertTrue(r2.isSuccess());
        assertEquals(1, connectionsAccepted.get(), "both requests must ride one socket");
        assertEquals(2, requestsHandled.get());
    }

    @Test
    @Timeout(10)
    void poolParksAndReturnsSameConnection() throws IOException {
        var pool = new ConnectionPool();
        var conn = KeepAliveConnection.open(sock.toString());
        conn.execute("GET", "/1.0", null, Map.of(), new byte[0], 5);
        pool.release(conn);
        assertEquals(1, pool.idleCount(sock.toString()));

        var borrowed = pool.borrow(sock.toString());
        assertSame(conn, borrowed, "a warm connection should be reused");
        assertEquals(0, pool.idleCount(sock.toString()));
        borrowed.execute("GET", "/1.0", null, Map.of(), new byte[0], 5);
        borrowed.close();
        assertEquals(1, connectionsAccepted.get(), "reuse must not open a second socket");
    }

    @Test
    @Timeout(10)
    void serverClosedConnectionIsDetectedAsStale() throws IOException {
        closeAfterRequests = 1; // server hangs up after the first response
        var conn = KeepAliveConnection.open(sock.toString());
        assertTrue(conn.execute("GET", "/1.0", null, Map.of(), new byte[0], 5).isSuccess());
        assertThrows(KeepAliveConnection.StaleConnectionException.class,
                () -> conn.execute("GET", "/1.0", null, Map.of(), new byte[0], 5),
                "a server-closed connection must surface as stale, not a generic error");
        assertEquals(KeepAliveConnection.State.DEAD, conn.state());
    }

    @Test
    @Timeout(10)
    void corruptResponseMarksConnectionDead() throws IOException {
        corruptResponse = true;
        var conn = KeepAliveConnection.open(sock.toString());
        assertThrows(RuntimeException.class,
                () -> conn.execute("GET", "/1.0", null, Map.of(), new byte[0], 5),
                "a parse error must propagate");
        assertEquals(KeepAliveConnection.State.DEAD, conn.state(),
                "a connection that failed to parse a response must be marked DEAD, not reused");
    }

    @Test
    @Timeout(10)
    void releaseReclaimsSlotsFromDeadParkedConnections() throws IOException {
        var pool = new ConnectionPool();
        var parked = new KeepAliveConnection[ConnectionPool.MAX_IDLE];
        for (int i = 0; i < parked.length; i++) {
            parked[i] = KeepAliveConnection.open(sock.toString());
            pool.release(parked[i]); // fills the pool to the cap
        }
        for (var c : parked) c.close(); // they die while parked (server closed them, etc.)

        var fresh = KeepAliveConnection.open(sock.toString());
        pool.release(fresh);
        assertEquals(1, pool.idleCount(sock.toString()),
                "dead parked connections must be pruned so a healthy release is retained");
        assertNotEquals(KeepAliveConnection.State.DEAD, fresh.state(),
                "the fresh healthy connection must be parked, not dropped");
    }

    @Test
    @Timeout(10)
    void overflowBeyondCapIsClosedNotHoarded() throws IOException {
        var pool = new ConnectionPool();
        var conns = new KeepAliveConnection[ConnectionPool.MAX_IDLE + 1];
        for (int i = 0; i < conns.length; i++) {
            conns[i] = KeepAliveConnection.open(sock.toString());
            pool.release(conns[i]);
        }
        assertEquals(ConnectionPool.MAX_IDLE, pool.idleCount(sock.toString()),
                "pool must retain at most MAX_IDLE");
        assertEquals(KeepAliveConnection.State.DEAD, conns[conns.length - 1].state(),
                "the overflow connection must be closed");
    }
}
