package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared HTTP response parser. The EOF cases are the point: each used to be read as a
 * shorter-but-successful response by the one-shot transport path.
 */
class HttpResponseReaderTest {

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String body(HttpResponseReader.Response r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    // --- readLine ---

    @Test
    void readLineHandlesCrLf() throws Exception {
        var in = stream("hello\r\nworld\r\n\r\n");
        assertEquals("hello", HttpResponseReader.readLine(in));
        assertEquals("world", HttpResponseReader.readLine(in));
        assertEquals("", HttpResponseReader.readLine(in));
    }

    @Test
    void readLineHandlesBareNewline() throws Exception {
        var in = stream("hello\nworld\n");
        assertEquals("hello", HttpResponseReader.readLine(in));
        assertEquals("world", HttpResponseReader.readLine(in));
    }

    @Test
    void readLineReturnsNullOnlyAtEofBeforeAnyByte() throws Exception {
        assertNull(HttpResponseReader.readLine(stream("")));
        assertThrows(EOFException.class, () -> HttpResponseReader.readLine(stream("partial")),
                "a line cut off by EOF must not read as a complete line");
    }

    // --- readChunkedBody ---

    @Test
    void readChunkedBodyDecodesSimpleChunk() throws Exception {
        assertArrayEquals("Hello".getBytes(StandardCharsets.UTF_8),
                HttpResponseReader.readChunkedBody(stream("5\r\nHello\r\n0\r\n\r\n")));
    }

    @Test
    void readChunkedBodyDecodesMultipleChunks() throws Exception {
        assertArrayEquals("Hello World".getBytes(StandardCharsets.UTF_8),
                HttpResponseReader.readChunkedBody(stream("5\r\nHello\r\n6\r\n World\r\n0\r\n\r\n")));
    }

    @Test
    void readChunkedBodyHandlesChunkExtensions() throws Exception {
        assertArrayEquals("Hello".getBytes(StandardCharsets.UTF_8),
                HttpResponseReader.readChunkedBody(stream("5;ext=ignored\r\nHello\r\n0\r\n\r\n")));
    }

    @Test
    void readChunkedBodyHandlesEmptyBody() throws Exception {
        assertEquals(0, HttpResponseReader.readChunkedBody(stream("0\r\n\r\n")).length);
    }

    @Test
    void chunkedBodyEndingEarlyIsAnError() {
        // Each of these used to spin on empty lines until the request watchdog fired.
        for (var wire : new String[] {"5\r\nHello\r\n", "5\r\nHel", "5\r\nHello\r\n0\r\n", ""}) {
            assertThrows(EOFException.class, () -> HttpResponseReader.readChunkedBody(stream(wire)),
                    "'" + wire.replace("\r\n", "\\r\\n") + "'");
        }
    }

    @Test
    void malformedChunkSizeIsAnIoError() {
        assertThrows(IOException.class, () -> HttpResponseReader.readChunkedBody(stream("zz\r\n")));
    }

    // --- read ---

    @Test
    void readsLengthFramedResponseAndLeavesTheConnectionReusable() throws Exception {
        var in = stream("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}NEXT");
        var r = HttpResponseReader.read(in);
        assertEquals(200, r.statusCode());
        assertEquals("{}", body(r));
        assertTrue(r.reusable());
        assertEquals('N', in.read(), "the reader must stop exactly at the end of the body");
    }

    @Test
    void closeFramedResponseReadsToEofAndIsNotReusable() throws Exception {
        var r = HttpResponseReader.read(stream("HTTP/1.1 200 OK\r\n\r\nall of it"));
        assertEquals("all of it", body(r));
        assertFalse(r.reusable());
    }

    @Test
    void connectionCloseMakesAResponseNotReusable() throws Exception {
        assertFalse(HttpResponseReader.read(
                stream("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")).reusable());
    }

    @Test
    void eofBeforeAnyByteIsNoResponse() {
        assertThrows(HttpResponseReader.NoResponseException.class, () -> HttpResponseReader.read(stream("")));
    }

    @Test
    void eofAfterTheFirstByteIsNotNoResponse() {
        // Something was answered, so a keep-alive caller must not treat it as safe to retry.
        var e = assertThrows(EOFException.class, () -> HttpResponseReader.read(stream("HTTP/1.1 2")));
        assertFalse(e instanceof HttpResponseReader.NoResponseException);
    }

    @Test
    void eofInTheHeadersIsAnError() {
        assertThrows(EOFException.class,
                () -> HttpResponseReader.read(stream("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n")));
    }

    @Test
    void shortLengthFramedBodyIsAnError() {
        assertThrows(EOFException.class,
                () -> HttpResponseReader.read(stream("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort")));
    }

    @Test
    void malformedHeadIsAnIoErrorNotARuntimeException() {
        for (var wire : new String[] {
                "garbage\r\n\r\n",
                "HTTP/1.1 abc OK\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: nope\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n"}) {
            assertThrows(IOException.class, () -> HttpResponseReader.read(stream(wire)), wire);
        }
    }
}
