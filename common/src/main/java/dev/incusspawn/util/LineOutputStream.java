package dev.incusspawn.util;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * An {@link OutputStream} that buffers bytes and delivers each completed line
 * (split on {@code \n}, carriage returns stripped) to a {@link Consumer} as a
 * UTF-8 string. Used to tap a streamed exec's output line-by-line — e.g. to
 * parse progress — without echoing it to the terminal.
 *
 * <p>Not internally synchronized: give each stream (stdout, stderr) its own
 * instance. The {@code sink} may be called from the exec's reader thread, so it
 * must itself be thread-safe if shared across instances.
 */
public final class LineOutputStream extends OutputStream {

    private final Consumer<String> sink;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    public LineOutputStream(Consumer<String> sink) {
        this.sink = sink;
    }

    @Override
    public void write(int b) {
        if (b == '\n') {
            emit();
        } else if (b != '\r') {
            buf.write(b);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) {
        for (int i = 0; i < len; i++) {
            write(b[off + i]);
        }
    }

    private void emit() {
        if (buf.size() == 0) return;
        sink.accept(buf.toString(StandardCharsets.UTF_8));
        buf.reset();
    }

    /** Flush any buffered partial line (a final line with no trailing newline). */
    @Override
    public void close() {
        emit();
    }
}
