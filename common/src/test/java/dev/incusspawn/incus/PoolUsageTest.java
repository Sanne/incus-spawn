package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one rule the TUI gauge, build diagnostics and doctor share for a pool running short (#2204). */
class PoolUsageTest {

    private static final long GIB = 1024L * 1024 * 1024;

    private static IncusClient.PoolUsage usage(long usedGib, long totalGib) {
        return new IncusClient.PoolUsage(usedGib * GIB, totalGib * GIB);
    }

    @Test
    void smallPoolFollowsThePercentage() {
        // Under 40 GiB the percentage leaves less than either floor free
        assertFalse(usage(14, 20).isLow());
        assertTrue(usage(15, 20).isLow());
        assertFalse(usage(17, 20).isCritical());
        assertTrue(usage(18, 20).isCritical());
    }

    @Test
    void largePoolPastThePercentageWithPlentyFreeIsNeitherLowNorCritical() {
        // 1 TiB at 90% still has about 100 GiB free
        var usage = usage(922, 1024);
        assertFalse(usage.isLow());
        assertFalse(usage.isCritical());
    }

    @Test
    void largePoolTurnsLowThenCriticalAsTheFreeSpaceRunsShort() {
        assertFalse(usage(1014, 1024).isLow(), "exactly 10 GiB free");
        assertTrue(usage(1015, 1024).isLow());
        assertFalse(usage(1019, 1024).isCritical(), "exactly 5 GiB free");
        assertTrue(usage(1020, 1024).isCritical());
    }

    @Test
    void theDefaultLoopPoolSizeFollowsTheFloors() {
        // 100 GiB: amber from 90% and red from 95%, not from 75% and 90%
        assertFalse(usage(90, 100).isLow(), "exactly 10 GiB free");
        assertTrue(usage(91, 100).isLow());
        assertFalse(usage(95, 100).isCritical(), "exactly 5 GiB free");
        assertTrue(usage(96, 100).isCritical());
    }

    @Test
    void plentyFreeButFarUnderThePercentageIsFine() {
        // A floor alone would call a mostly empty 40 GiB pool low
        assertFalse(usage(10, 40).isLow());
        assertFalse(usage(10, 40).isCritical());
    }

    @Test
    void noSpaceInfoIsNeitherLowNorCritical() {
        assertFalse(usage(0, 0).isLow());
        assertFalse(usage(0, 0).isCritical());
    }
}
