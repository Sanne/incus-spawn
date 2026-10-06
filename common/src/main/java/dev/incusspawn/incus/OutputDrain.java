package dev.incusspawn.incus;

/**
 * When the post-exit drain of an exec's output stops waiting. After the operation completes
 * the data sockets are drained before they are force-closed, so trailing output isn't truncated
 * when close frames never arrive (macOS vsock). Rather than a blind fixed wait (too short risks
 * truncation on a slow tunnel; too long adds that latency to <em>every</em> exec on machines
 * where close frames don't arrive), the drain is adaptive: wait a short minimum for in-flight
 * bytes, extend while output is still arriving, and close once it has been idle briefly --
 * bounded by an absolute cap. On the healthy path the close frames arrive and the reader threads
 * finish at once, so none of this is reached.
 *
 * Times are {@link System#nanoTime} values, passed in so the decision can be tested without
 * waiting.
 */
final class OutputDrain {

    static final long MIN_MS  = 200;   // always wait this for bytes in flight
    static final long IDLE_MS = 200;   // then close once output has been idle this long
    static final long MAX_MS  = 5000;  // absolute ceiling
    static final long POLL_MS = 20;

    private final long start;

    OutputDrain(long now) {
        this.start = now;
    }

    /** @param lastData when the latest byte arrived on any data fd */
    boolean done(long now, long lastData) {
        long sinceStartMs = (now - start) / 1_000_000L;
        long sinceDataMs  = (now - lastData) / 1_000_000L;
        if (sinceStartMs >= MAX_MS) return true;
        return sinceStartMs >= MIN_MS && sinceDataMs >= IDLE_MS;
    }
}
