package dev.incusspawn.incus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UnixSocketTransport} over a real Unix socket served by {@link LossyIncusServer}: response
 * framing, the one-shot watchdog, WebSocket handshake rejection, and the pooled path's
 * stale-connection retry. Every test also checks the connection gauge returns to where it
 * started, since a leaked permit or count on one failure path is exactly what nobody would see.
 */
class UnixSocketTransportTest {

    private static final byte[] NO_BODY = new byte[0];

    private LossyIncusServer server;
    private UnixSocketTransport transport;
    private int openBefore;

    @BeforeEach
    void start() throws IOException {
        server = new LossyIncusServer();
        transport = new UnixSocketTransport(server.socketPath(), 1);
        openBefore = UnixSocketTransport.openConnectionCount();
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
        assertEquals(openBefore, UnixSocketTransport.openConnectionCount(),
                "every connection opened by the test must be closed and its permit released");
    }

    private static String text(IncusTransport.RawResponse r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    @Test
    @Timeout(10)
    void oneShotReadsEveryResponseFraming() throws IOException {
        for (var framing : LossyIncusServer.Framing.values()) {
            server.framing = framing;
            var resp = transport.request("GET", "/x", null, Map.of(), NO_BODY);
            assertEquals(200, resp.statusCode(), framing.name());
            assertTrue(text(resp).startsWith("{\"n\":"), framing + " body: " + text(resp));
        }
    }

    @Test
    @Timeout(10)
    void oneShotTimesOutAgainstASilentPeer() {
        server.fault = LossyIncusServer.Fault.SILENT_ON_ACCEPT;
        long start = System.nanoTime();
        var e = assertThrows(IOException.class,
                () -> transport.request("GET", "/x", null, Map.of(), NO_BODY));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertTrue(elapsedMs < 5000, "the watchdog must fire at its deadline (1s): " + elapsedMs + "ms");
    }

    @Test
    @Timeout(10)
    void oneShotTimesOutWhenThePeerStallsMidExchange() {
        server.fault = LossyIncusServer.Fault.STALL_AFTER_REQUEST;
        var e = assertThrows(IOException.class,
                () -> transport.request("GET", "/x", null, Map.of(), NO_BODY));
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertEquals(1, server.requestCount("GET /x"), "the request reached the server before it stalled");
    }

    @Test
    @Timeout(10)
    void rejectedWebSocketUpgradeFailsWithoutLeaking() {
        server.fault = LossyIncusServer.Fault.REJECT_UPGRADE;
        var e = assertThrows(IOException.class,
                () -> transport.openWebSocket("/1.0/operations/op1/websocket?secret=s1"));
        assertTrue(e.getMessage().contains("upgrade failed"), e.getMessage());
    }

    @Test
    @Timeout(10)
    void webSocketCloseIsCountedOnceWhenCalledTwice() throws IOException {
        var ws = transport.openWebSocket("/1.0/operations/op1/websocket?secret=s1");
        assertEquals(openBefore + 1, UnixSocketTransport.openConnectionCount());
        ws.close();
        ws.close(); // try-with-resources plus IncusApi's force-close both call it
        assertEquals(openBefore, UnixSocketTransport.openConnectionCount());
    }

    @Test
    @Timeout(10)
    void pooledRequestsShareOneConnection() throws IOException {
        for (int i = 0; i < 5; i++) {
            assertTrue(transport.requestPooled("GET", "/x", null, Map.of(), NO_BODY).isSuccess());
        }
        assertEquals(1, server.connectionsAccepted.get(), "sequential pooled calls must reuse one socket");
    }

    @Test
    @Timeout(10)
    void pooledRequestRetriesOnceWhenTheParkedConnectionWasDropped() throws IOException {
        server.fault = LossyIncusServer.Fault.DROP_AFTER_RESPONSE;
        transport.requestPooled("GET", "/first", null, Map.of(), NO_BODY); // parks a connection the server has closed
        var resp = transport.requestPooled("GET", "/second", null, Map.of(), NO_BODY);
        assertTrue(resp.isSuccess(), "a dropped idle connection must be recycled transparently");
        assertEquals(1, server.requestCount("GET /second"),
                "the stale attempt never reached the server, so the retry runs the request exactly once");
        assertEquals(2, server.connectionsAccepted.get());
    }

    @Test
    @Timeout(10)
    void pooledRequestTruncatedMidBodyIsNotRetried() {
        server.fault = LossyIncusServer.Fault.TRUNCATE_BODY;
        var e = assertThrows(IOException.class,
                () -> transport.requestPooled("POST", "/mutate", "application/json", Map.of(), "{}".getBytes()));
        assertTrue(e.getMessage().contains("truncated"), e.getMessage());
        assertEquals(1, server.requestCount("POST /mutate"),
                "a request that may have executed must not be replayed");
        assertEquals(0, ConnectionPool.global().idleCount(server.socketPath()), "a broken connection is never parked");
    }

    @Test
    @Timeout(10)
    void pooledRequestAgainstAStalledPeerTimesOutAndIsNotParked() {
        server.fault = LossyIncusServer.Fault.STALL_AFTER_REQUEST;
        var e = assertThrows(IOException.class,
                () -> transport.requestPooled("GET", "/x", null, Map.of(), NO_BODY));
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertEquals(1, server.requestCount("GET /x"), "a timeout is not stale: no retry");
        assertEquals(0, ConnectionPool.global().idleCount(server.socketPath()));
    }

    // --- a response that ends early must fail, never read as a (smaller) success ---

    @FunctionalInterface
    interface Call { IncusTransport.RawResponse run(UnixSocketTransport t) throws IOException; }

    enum Path {
        ONE_SHOT(t -> t.request("GET", "/x", null, Map.of(), NO_BODY)),
        POOLED(t -> t.requestPooled("GET", "/x", null, Map.of(), NO_BODY));
        final Call call;
        Path(Call call) { this.call = call; }
    }

    private void assertFailsFast(Path path, LossyIncusServer.Fault fault) {
        server.fault = fault;
        var slow = new UnixSocketTransport(server.socketPath(), 5);
        long start = System.nanoTime();
        var e = assertThrows(IOException.class, () -> path.call.run(slow),
                fault + " on the " + path + " path must fail, not return a partial response");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertFalse(e.getMessage().contains("timed out"),
                "EOF must be reported as EOF, not waited out until the watchdog: " + e.getMessage());
        assertTrue(elapsedMs < 2000, fault + " took " + elapsedMs + "ms");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    @Timeout(10)
    void bodyCutShortFails(Path path) {
        assertFailsFast(path, LossyIncusServer.Fault.TRUNCATE_BODY);
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    @Timeout(10)
    void headersCutShortFail(Path path) {
        assertFailsFast(path, LossyIncusServer.Fault.TRUNCATE_HEADERS);
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    @Timeout(10)
    void chunkedBodyCutShortFails(Path path) {
        assertFailsFast(path, LossyIncusServer.Fault.TRUNCATE_CHUNKED);
    }

    @Test
    @Timeout(10)
    void oneShotReportsAPeerThatClosedWithoutResponding() {
        server.fault = LossyIncusServer.Fault.CLOSE_BEFORE_RESPONSE;
        var e = assertThrows(IOException.class,
                () -> transport.request("GET", "/x", null, Map.of(), NO_BODY));
        assertTrue(e.getMessage().contains("closed"), e.getMessage());
    }

    // --- WebSocket handshake ---

    @Test
    @Timeout(10)
    void webSocketHandshakeTimesOutAgainstASilentPeer() {
        server.fault = LossyIncusServer.Fault.SILENT_ON_ACCEPT;
        long start = System.nanoTime();
        var e = assertThrows(IOException.class,
                () -> transport.openWebSocket("/1.0/operations/op1/websocket?secret=s1"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertFalse(e.getMessage().contains("secret"), "the fd secret must not leak into the message: " + e.getMessage());
        assertTrue(elapsedMs < 5000, "the handshake must be bounded by the transport timeout (1s): " + elapsedMs + "ms");
    }

    @Test
    @Timeout(10)
    void webSocketHandshakeFailsWhenThePeerClosesWithoutAnswering() {
        // Before this was checked, EOF ended the header loop like a blank line would, and the
        // caller got a "connected" WebSocket on a dead socket -- an exec fd that read as
        // immediately closed, silently dropping that stream's output.
        server.fault = LossyIncusServer.Fault.CLOSE_BEFORE_RESPONSE;
        var e = assertThrows(IOException.class,
                () -> transport.openWebSocket("/1.0/operations/op1/websocket?secret=s1"));
        assertTrue(e.getMessage().contains("handshake"), e.getMessage());
    }
}
