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
        assertFalse(usage(74, 100).isLow());
        assertTrue(usage(75, 100).isLow());
        assertFalse(usage(89, 100).isCritical());
        assertTrue(usage(90, 100).isCritical());
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
        assertFalse(usage(974, 1024).isLow(), "exactly 50 GiB free");
        assertTrue(usage(975, 1024).isLow());
        assertFalse(usage(1004, 1024).isCritical(), "exactly 20 GiB free");
        assertTrue(usage(1005, 1024).isCritical());
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
