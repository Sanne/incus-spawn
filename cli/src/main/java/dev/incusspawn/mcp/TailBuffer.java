package dev.incusspawn.mcp;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * An output sink that keeps only the last {@code capacity} bytes and counts the rest, so a
 * command's output is bounded by what a tool result can carry rather than by how much the
 * command prints. Also remembers the last complete line, for progress messages.
 */
final class TailBuffer extends OutputStream {

    private static final int MAX_LINE = 200;

    private final byte[] ring;
    private long total;
    private final ByteArrayOutputStream currentLine = new ByteArrayOutputStream();
    private volatile String lastLine = "";

    TailBuffer(int capacity) {
        this.ring = new byte[Math.max(1, capacity)];
    }

    @Override
    public void write(int b) {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        // Only the part of this chunk that can survive in the ring is copied, in at most two runs.
        int keep = Math.min(len, ring.length);
        int from = off + len - keep;
        int pos = (int) ((total + len - keep) % ring.length);
        int first = Math.min(keep, ring.length - pos);
        System.arraycopy(b, from, ring, pos, first);
        System.arraycopy(b, from + first, ring, 0, keep - first);
        total += len;
        trackLines(b, off, len);
    }

    private void trackLines(byte[] b, int off, int len) {
        int start = off;
        for (int i = off; i < off + len; i++) {
            if (b[i] != '\n') continue;
            appendToLine(b, start, i - start);
            var line = currentLine.toString(StandardCharsets.UTF_8).strip();
            if (!line.isEmpty()) lastLine = line;
            currentLine.reset();
            start = i + 1;
        }
        appendToLine(b, start, off + len - start);
    }

    private void appendToLine(byte[] b, int off, int len) {
        currentLine.write(b, off, Math.min(len, Math.max(0, MAX_LINE - currentLine.size())));
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
        int first = Math.min(size, ring.length - start);
        System.arraycopy(ring, start, out, 0, first);
        System.arraycopy(ring, 0, out, first, size - first);
        return new String(out, StandardCharsets.UTF_8);
    }
}
