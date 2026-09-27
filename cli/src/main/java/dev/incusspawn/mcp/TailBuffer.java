package dev.incusspawn.mcp;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * An output sink that keeps only the last {@code capacity} bytes and counts the rest, so a
 * command's output is bounded by what a tool result can carry rather than by how much the
 * command prints. Also remembers the last complete line, for progress messages.
 */
final class TailBuffer extends OutputStream {

    private final byte[] ring;
    private long total;
    private final StringBuilder currentLine = new StringBuilder();
    private volatile String lastLine = "";

    TailBuffer(int capacity) {
        this.ring = new byte[Math.max(1, capacity)];
    }

    @Override
    public synchronized void write(int b) {
        ring[(int) (total % ring.length)] = (byte) b;
        total++;
        trackLine((byte) b);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        for (int i = 0; i < len; i++) write(b[off + i]);
    }

    private void trackLine(byte b) {
        if (b == '\n') {
            if (!currentLine.isEmpty()) lastLine = currentLine.toString();
            currentLine.setLength(0);
        } else if (currentLine.length() < 200 && b != '\r') {
            currentLine.append((char) (b & 0xff));
        }
    }

    synchronized long total() {
        return total;
    }

    synchronized boolean truncated() {
        return total > ring.length;
    }

    String lastLine() {
        return lastLine;
    }

    /** The retained tail, decoded as UTF-8 (a character cut at the start is replaced). */
    synchronized String text() {
        int size = (int) Math.min(total, ring.length);
        var out = new byte[size];
        int start = (int) ((total - size) % ring.length);
        for (int i = 0; i < size; i++) out[i] = ring[(start + i) % ring.length];
        return new String(out, StandardCharsets.UTF_8);
    }
}
