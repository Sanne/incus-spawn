package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The drain's decision, on a clock the test drives: no sockets, no sleeps, no scheduler.
 * {@link IncusApiLossyTunnelTest} covers the same drain end to end against the lossy fake.
 */
class OutputDrainTest {

    private static final long MS = 1_000_000L;

    /** Polls on schedule from {@code fromMs} to {@code toMs}; the first time it says done, or -1. */
    private static long pollUntilDone(OutputDrain drain, long fromMs, long toMs, long lastDataMs) {
        return pollUntilDone(drain, fromMs, toMs, lastDataMs, OutputDrain.POLL_MS);
    }

    private static long pollUntilDone(OutputDrain drain, long fromMs, long toMs, long lastDataMs, long everyMs) {
        for (long t = fromMs; t <= toMs; t += everyMs) {
            if (drain.done(t * MS, lastDataMs * MS)) return t;
        }
        return -1;
    }

    @Test
    void quietOutputIsClosedOnceIdle() {
        var drain = new OutputDrain(0);
        assertEquals(OutputDrain.IDLE_MS, pollUntilDone(drain, 0, 1000, 0));
    }

    @Test
    void outputStillArrivingExtendsTheDrain() {
        var drain = new OutputDrain(0);
        assertEquals(-1, pollUntilDone(drain, 0, 180, 0));
        assertFalse(drain.done(200 * MS, 200 * MS), "a byte just now: still flowing");
        assertEquals(200 + OutputDrain.IDLE_MS, pollUntilDone(drain, 220, 1000, 200));
    }

    // #1062, #1084: a pause of the host process (a VM's vCPU descheduled, a GC) is not output
    // going quiet. The first poll after it saw the pause as idle time and closed the sockets
    // before the reader threads, paused with it, could take the bytes that arrived meanwhile.
    @Test
    void aPauseOfTheDrainItselfIsNotIdleOutput() {
        var drain = new OutputDrain(0);
        assertEquals(-1, pollUntilDone(drain, 0, 100, 90));
        assertFalse(drain.done(450 * MS, 90 * MS),
                "the drain was not running for 350ms: that says nothing about the output");
        assertFalse(drain.done(470 * MS, 470 * MS), "the readers caught up after the pause");
        assertEquals(470 + OutputDrain.IDLE_MS, pollUntilDone(drain, 490, 2000, 470));
    }

    @Test
    void thePollAfterAPauseNeverEndsTheDrain() {
        var drain = new OutputDrain(0);
        assertEquals(-1, pollUntilDone(drain, 0, 100, 0));
        assertFalse(drain.done(450 * MS, 0), "the readers have not run since the pause");
        assertTrue(drain.done(470 * MS, 0), "they had the sleep since, and nothing came");
    }

    @Test
    void theCeilingHoldsWhileOutputNeverStops() {
        var drain = new OutputDrain(0);
        for (long t = 0; t < OutputDrain.MAX_MS; t += OutputDrain.POLL_MS) {
            assertFalse(drain.done(t * MS, t * MS), "at " + t + "ms");
        }
        assertTrue(drain.done(OutputDrain.MAX_MS * MS, OutputDrain.MAX_MS * MS));
    }

    // #1122: on the macOS runners polls come late again and again. Restarting the window on each
    // late poll held the drain to its ceiling (5031ms) for output that had been quiet from the start.
    @Test
    void aDrainWhosePollsAreAllLateStillEndsOnceIdle() {
        var drain = new OutputDrain(0);
        long late = OutputDrain.PAUSE_MS + 50;
        assertEquals(3 * late, pollUntilDone(drain, 0, OutputDrain.MAX_MS, 0, late),
                "a late poll slows the drain, it must not hold it to the ceiling");
    }

    @Test
    void theCeilingHoldsThroughRepeatedPauses() {
        var drain = new OutputDrain(0);
        for (long t = 0; t < OutputDrain.MAX_MS; t += 300) {
            assertFalse(drain.done(t * MS, t * MS - 10 * MS), "at " + t + "ms");
        }
        assertTrue(drain.done(OutputDrain.MAX_MS * MS, OutputDrain.MAX_MS * MS - 10 * MS),
                "pauses never extend it past its ceiling");
    }
}
