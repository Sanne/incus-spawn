package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Server-to-client frame parsing in {@link UnixSocketTransport} (unmasked frames). */
class WebSocketFramingTest {

    private static final int TEXT = 0x1, CONTINUATION = 0x0, CLOSE = 0x8, PING = 0x9;

    private static void frame(ByteArrayOutputStream out, boolean fin, int opcode, String payload) {
        var bytes = payload.getBytes(StandardCharsets.UTF_8);
        out.write((fin ? 0x80 : 0) | opcode);
        if (bytes.length < 126) {
            out.write(bytes.length);
        } else {
            out.write(126);
            out.write(bytes.length >> 8);
            out.write(bytes.length & 0xFF);
        }
        out.writeBytes(bytes);
    }

    private static String text(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void readMessageReassemblesFragments() throws Exception {
        var wire = new ByteArrayOutputStream();
        frame(wire, false, TEXT, "{\"type\":");
        frame(wire, false, CONTINUATION, "\"lifecycle\"");
        frame(wire, true, CONTINUATION, "}");
        frame(wire, true, TEXT, "{}");
        var in = new ByteArrayInputStream(wire.toByteArray());
        assertEquals("{\"type\":\"lifecycle\"}", text(UnixSocketTransport.wsReadMessage(in, p -> {})));
        assertEquals("{}", text(UnixSocketTransport.wsReadMessage(in, p -> {})));
        assertNull(UnixSocketTransport.wsReadMessage(in, p -> {}), "EOF reads as null");
    }

    @Test
    void pingIsAnsweredWithItsPayloadAndSkipped() throws Exception {
        var wire = new ByteArrayOutputStream();
        frame(wire, true, PING, "hb-1");
        frame(wire, false, TEXT, "a");
        frame(wire, true, PING, "hb-2"); // control frames may interleave with a fragmented message
        frame(wire, true, CONTINUATION, "b");
        var pongs = new ArrayList<String>();
        var message = UnixSocketTransport.wsReadMessage(new ByteArrayInputStream(wire.toByteArray()),
                p -> pongs.add(text(p)));
        assertEquals("ab", text(message));
        assertEquals(java.util.List.of("hb-1", "hb-2"), pongs);
    }

    @Test
    void pongIsReportedAndSkipped() throws Exception {
        var wire = new ByteArrayOutputStream();
        frame(wire, true, 0xA, "");
        frame(wire, true, TEXT, "data");
        var pongs = new java.util.concurrent.atomic.AtomicInteger();
        var control = new UnixSocketTransport.ControlFrames() {
            @Override public void ping(byte[] payload) {}
            @Override public void pong() { pongs.incrementAndGet(); }
        };
        assertEquals("data", text(UnixSocketTransport.wsReadMessage(new ByteArrayInputStream(wire.toByteArray()), control)));
        assertEquals(1, pongs.get());
    }

    @Test
    void closeFrameEndsTheStream() throws Exception {
        var wire = new ByteArrayOutputStream();
        frame(wire, false, TEXT, "partial");
        frame(wire, true, CLOSE, "");
        assertNull(UnixSocketTransport.wsReadMessage(new ByteArrayInputStream(wire.toByteArray()), p -> {}));
    }

    @Test
    void readFrameStillReturnsFragmentsIndividually() throws Exception {
        // Byte streams (exec stdout) don't care about message boundaries; keep that path unchanged.
        var wire = new ByteArrayOutputStream();
        frame(wire, false, TEXT, "x".repeat(200));
        frame(wire, true, CONTINUATION, "y");
        var in = new ByteArrayInputStream(wire.toByteArray());
        var first = UnixSocketTransport.wsReadFrame(in, p -> {});
        assertFalse(first.fin());
        assertEquals(200, first.payload().length);
        assertTrue(UnixSocketTransport.wsReadFrame(in, p -> {}).fin());
    }

    // --- Over a real socket: which read path is allowed to write ---

    /**
     * Serve one WebSocket client on a temp Unix socket: complete the handshake, send
     * {@code frames}, then report every byte the client writes back until it disconnects.
     */
    private static java.util.concurrent.CompletableFuture<byte[]> serveOnce(java.nio.file.Path socket, byte[] frames)
            throws Exception {
        var server = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX);
        server.bind(java.net.UnixDomainSocketAddress.of(socket));
        var written = new java.util.concurrent.CompletableFuture<byte[]>();
        Thread.ofVirtual().start(() -> {
            try (server; var ch = server.accept()) {
                var in = java.nio.channels.Channels.newInputStream(ch);
                var out = java.nio.channels.Channels.newOutputStream(ch);
                var request = new StringBuilder();
                while (!request.toString().endsWith("\r\n\r\n")) request.append((char) in.read());
                out.write("HTTP/1.1 101 Switching Protocols\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.write(frames);
                out.flush();
                written.complete(in.readAllBytes());
            } catch (Exception e) {
                written.completeExceptionally(e);
            }
        });
        return written;
    }

    @Test
    void byteStreamReadsNeverWrite() throws Exception {
        // The exec/shell path: a PING is skipped without a reply, exactly as before event
        // subscriptions existed, so reading can never contend with a stdin writer.
        var dir = java.nio.file.Files.createTempDirectory("ws-test");
        var socket = dir.resolve("s.sock");
        var wire = new ByteArrayOutputStream();
        frame(wire, true, PING, "hb");
        frame(wire, true, 0x2, "out");
        var written = serveOnce(socket, wire.toByteArray());
        try (var ws = new UnixSocketTransport(socket.toString()).openWebSocket("/x")) {
            assertEquals("out", text(ws.readPayload()));
        }
        assertEquals(0, written.get(5, java.util.concurrent.TimeUnit.SECONDS).length);
    }

    @Test
    void messageReadsAnswerPings() throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("ws-test");
        var socket = dir.resolve("s.sock");
        var wire = new ByteArrayOutputStream();
        frame(wire, true, PING, "hb");
        frame(wire, true, TEXT, "{}");
        var written = serveOnce(socket, wire.toByteArray());
        try (var ws = new UnixSocketTransport(socket.toString()).openWebSocket("/x")) {
            assertEquals("{}", text(ws.readMessage()));
            assertTrue(ws.millisSinceLastReceived() < 5_000);
        }
        var reply = written.get(5, java.util.concurrent.TimeUnit.SECONDS);
        // One masked PONG (client frames are masked): FIN|0xA, MASK|len 2, 4-byte key, 2 bytes.
        assertEquals(8, reply.length);
        assertEquals((byte) 0x8A, reply[0]);
        assertEquals((byte) 0x82, reply[1]);
        var payload = new byte[] {(byte) (reply[6] ^ reply[2]), (byte) (reply[7] ^ reply[3])};
        assertEquals("hb", text(payload));
    }

    @Test
    void frameCutShortInItsPayloadIsAnError() {
        var wire = new ByteArrayOutputStream();
        wire.write(0x80 | TEXT);
        wire.write(100);
        wire.writeBytes("only this much".getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.EOFException.class,
                () -> UnixSocketTransport.wsReadFrame(new ByteArrayInputStream(wire.toByteArray()), p -> {}),
                "a truncated frame must not be delivered as if it were complete");
    }

    @Test
    void frameCutShortInItsExtendedLengthIsAnError() {
        // EOF read as 0xFF used to assemble a length of -1, and readNBytes(-1) threw an
        // IllegalArgumentException that the exec reader threads don't catch.
        var wire = new byte[] {(byte) (0x80 | TEXT), 127, 0, 0};
        assertThrows(java.io.EOFException.class,
                () -> UnixSocketTransport.wsReadFrame(new ByteArrayInputStream(wire), p -> {}));
    }

    @Test
    void frameCutShortAfterItsFirstByteIsAnError() {
        var wire = new byte[] {(byte) (0x80 | TEXT)};
        assertThrows(java.io.EOFException.class,
                () -> UnixSocketTransport.wsReadFrame(new ByteArrayInputStream(wire), p -> {}));
    }

    @Test
    void absurdFrameLengthIsRejected() {
        var wire = new ByteArrayOutputStream();
        wire.write(0x80 | TEXT);
        wire.write(127);
        wire.writeBytes(new byte[] {0x7F, -1, -1, -1, -1, -1, -1, -1});
        assertThrows(java.io.IOException.class,
                () -> UnixSocketTransport.wsReadFrame(new ByteArrayInputStream(wire.toByteArray()), p -> {}));
    }
}
