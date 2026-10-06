package dev.incusspawn.incus;

/**
 * When the post-exit drain of an exec's output stops waiting. After the operation completes
 * the data sockets are drained before they are force-closed, so trailing output isn't truncated
 * when close frames never arrive (macOS vsock). Rather than a blind fixed wait (too short risks
 * truncation on a slow tunnel; too long adds that latency to <em>every</em> exec on machines
 * where close frames don't arrive), the drain is adaptive: it closes once output has been idle
 * for {@link #IDLE_MS} -- which also gives bytes in flight that long from the start -- extends
 * while output is still arriving, and is bounded by an absolute cap. On the healthy path the close frames arrive and the reader threads
 * finish at once, so none of this is reached.
 *
 * Idle is counted only while the drain was watching (#1062, #1084). When the host process
 * pauses (a VM's vCPU descheduled, a GC), the first poll after the pause would otherwise read it
 * as quiet output and close the sockets before the reader threads, paused too, had taken the
 * bytes that arrived meanwhile. So a poll that comes late starts the idle window again; the
 * ceiling stays wall time, so pauses never hold the caller past it.
 *
 * Times are {@link System#nanoTime} values, passed in so the decision can be tested without
 * waiting.
 */
final class OutputDrain {

    static final long IDLE_MS = 200;   // close once output has been idle this long
    static final long MAX_MS  = 5000;  // absolute ceiling
    static final long POLL_MS = 20;
    /** A poll this late after the previous one means the drain itself was not running. */
    static final long PAUSE_MS = IDLE_MS / 2;

    private final long start;
    private long lastPoll;
    private long watchingSince;

    OutputDrain(long now) {
        this.start = now;
        this.lastPoll = now;
        this.watchingSince = now;
    }

    /** @param lastData when the latest byte arrived on any data fd */
    boolean done(long now, long lastData) {
        if ((now - lastPoll) / 1_000_000L > PAUSE_MS) watchingSince = now;
        lastPoll = now;
        long sinceStartMs = (now - start) / 1_000_000L;
        long idleMs = (now - Math.max(lastData, watchingSince)) / 1_000_000L;
        return sinceStartMs >= MAX_MS || idleMs >= IDLE_MS;
    }
}
