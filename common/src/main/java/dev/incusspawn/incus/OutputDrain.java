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
 * bytes that arrived meanwhile. So the time between two polls counts as idle for at most
 * {@link #PAUSE_MS}, and a late poll ends the drain only if the poll before it already found the
 * output idle: the readers get the sleep in between to catch up. Restarting the window on every
 * late poll instead held the drain to its ceiling on the macOS runners, where polls come late
 * again and again (#1122): lateness may slow the drain, never hold it there.
 * The ceiling stays wall time, so pauses never hold the caller past it.
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

    private static final long IDLE_NS = IDLE_MS * 1_000_000L;
    private static final long MAX_NS = MAX_MS * 1_000_000L;
    private static final long PAUSE_NS = PAUSE_MS * 1_000_000L;

    private final long start;
    private long lastPoll;
    private long countingFrom; // the lastData idle is counted since
    private long idleNanos;

    OutputDrain(long now) {
        this.start = now;
        this.lastPoll = now;
    }

    /** @param lastData when the latest byte arrived on any data fd */
    boolean done(long now, long lastData) {
        if (lastData != countingFrom) {
            countingFrom = lastData;
            idleNanos = 0;
        }
        boolean late = now - lastPoll > PAUSE_NS;
        long idleBefore = idleNanos; // a gap counts for less than a window: this was the last poll's verdict
        idleNanos += Math.clamp(now - Math.max(lastPoll, lastData), 0, PAUSE_NS);
        lastPoll = now;
        return now - start >= MAX_NS || (late ? idleBefore : idleNanos) >= IDLE_NS;
    }
}
