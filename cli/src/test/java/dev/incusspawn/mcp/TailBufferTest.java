package dev.incusspawn.mcp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailBufferTest {

    private static void write(TailBuffer b, String s) {
        var bytes = s.getBytes(StandardCharsets.UTF_8);
        b.write(bytes, 0, bytes.length);
    }

    @Test
    void shortOutputIsKeptWhole() {
        var b = new TailBuffer(16);
        write(b, "hello\nworld\n");
        assertEquals("hello\nworld\n", b.text());
        assertEquals(12, b.total());
        assertFalse(b.truncated());
        assertEquals("world", b.lastLine());
    }

    @Test
    void longOutputKeepsTheEndAndCountsTheRest() {
        var b = new TailBuffer(8);
        for (int i = 0; i < 1000; i++) write(b, "line " + i + "\n");
        assertTrue(b.truncated());
        assertEquals("ne 999\n", b.text().substring(1));
        assertEquals(8, b.text().length());
        assertEquals("line 999", b.lastLine());
        assertTrue(b.total() > 8000);
    }
}
