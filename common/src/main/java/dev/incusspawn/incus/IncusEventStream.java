package dev.incusspawn.incus;

import java.io.IOException;

/**
 * A subscription to the daemon's event feed ({@code /1.0/events}): one JSON document per
 * message, for as long as the connection lives. Closing it from another thread unblocks a
 * pending {@link #next()}, which is how a reader thread is stopped.
 */
public interface IncusEventStream extends AutoCloseable {

    /** Block until the next event and return its raw JSON, or null once the stream has closed. */
    String next() throws IOException;

    @Override
    void close(); // best-effort, no throw
}
