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
}
