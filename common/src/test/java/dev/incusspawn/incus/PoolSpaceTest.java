package dev.incusspawn.incus;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** When a branch or a build is refused or warned for want of space (#2555). */
class PoolSpaceTest {

    private static final long GIB = 1024L * 1024 * 1024;

    private static IncusClient.PoolUsage free(double freeGib, long totalGib) {
        return new IncusClient.PoolUsage(totalGib * GIB - (long) (freeGib * GIB), totalGib * GIB);
    }

    @Test
    void aBuildIsRefusedOnceThePoolIsCritical() {
        var refusal = PoolSpace.check(free(4, 60), "cow", PoolSpace.Need.BUILD, false);
        assertNotNull(refusal);
        assertTrue(refusal.contains("too little to build in"), refusal);
        assertFalse(refusal.contains("vm resize"), "Linux has no VM to grow: " + refusal);
        assertNull(PoolSpace.check(free(6, 60), "cow", PoolSpace.Need.BUILD, false));
    }

    @Test
    void aBuildIsRefusedOnASmallPoolABranchIsRefusedOn() {
        // 1.9 GiB free of 15 GiB is only 87% full, so not critical, but a branch is refused there
        assertNotNull(PoolSpace.check(free(1.9, 15), "cow", PoolSpace.Need.BRANCH, false));
        var refusal = PoolSpace.check(free(1.9, 15), "cow", PoolSpace.Need.BUILD, false);
        assertNotNull(refusal);
        assertTrue(refusal.contains("too little to build in"), refusal);
    }

    @Test
    void aBranchIsOnlyWarnedAtCriticalAndRefusedOnceExhausted() {
        var recorded = RecordingOutput.during(() ->
                assertNull(PoolSpace.check(free(4, 60), "cow", PoolSpace.Need.BRANCH, true)));
        assertEquals(1, recorded.warnings().size(), () -> String.join("\n", recorded.warnings()));
        var refusal = PoolSpace.check(free(1.9, 60), "cow", PoolSpace.Need.BRANCH, true);
        assertNotNull(refusal);
        assertTrue(refusal.contains("isx vm resize <size>"), refusal);
    }

    @Test
    void aLargePoolIsExhaustedByItsFreeSpaceAloneWhateverThePercentage() {
        // 1.5 GiB free of 1 TiB is 99.9% full; 2 GiB free of 4 GiB is half
        assertTrue(free(1.5, 1024).isExhausted());
        assertFalse(free(2, 4).isExhausted());
        assertFalse(new IncusClient.PoolUsage(0, 0).isExhausted(), "a pool that reports no size is not judged");
    }

    @Test
    void anUnknownUsageRefusesNothing() {
        var recorded = RecordingOutput.during(() -> assertNull(PoolSpace.check(null, "cow", PoolSpace.Need.BUILD, true)));
        assertTrue(recorded.events().isEmpty());
    }
}
