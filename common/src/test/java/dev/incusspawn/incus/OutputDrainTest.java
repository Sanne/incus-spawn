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
        for (long t = fromMs; t <= toMs; t += OutputDrain.POLL_MS) {
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
    void aPauseStillGetsAWholeIdleWindowAfterIt() {
        var drain = new OutputDrain(0);
        assertEquals(-1, pollUntilDone(drain, 0, 100, 0));
        assertFalse(drain.done(450 * MS, 0));
        assertEquals(450 + OutputDrain.IDLE_MS, pollUntilDone(drain, 470, 2000, 0),
                "idle is counted only while the drain was watching");
    }

    @Test
    void theCeilingHoldsWhileOutputNeverStops() {
        var drain = new OutputDrain(0);
        for (long t = 0; t < OutputDrain.MAX_MS; t += OutputDrain.POLL_MS) {
            assertFalse(drain.done(t * MS, t * MS), "at " + t + "ms");
        }
        assertTrue(drain.done(OutputDrain.MAX_MS * MS, OutputDrain.MAX_MS * MS));
    }

    @Test
    void theCeilingHoldsThroughRepeatedPauses() {
        var drain = new OutputDrain(0);
        for (long t = 0; t < OutputDrain.MAX_MS; t += 300) {
            assertFalse(drain.done(t * MS, 0), "at " + t + "ms");
        }
        assertTrue(drain.done(OutputDrain.MAX_MS * MS, 0), "pauses never extend it past its ceiling");
    }
}
