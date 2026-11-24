package dev.incusspawn.incus;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** When a branch or a build is refused or warned for want of space (#2555). */
class PoolSpaceTest {

    private static final long GIB = 1024L * 1024 * 1024;

    /** On macOS, or a Linux loop-file pool, which keeps Incus's database apart from the pool. */
    private static String check(IncusClient.PoolUsage usage, String pool, PoolSpace.Need need, boolean macOS) {
        return PoolSpace.check(usage, pool, need, macOS, () -> macOS);
    }

    private static IncusClient.PoolUsage free(double freeGib, long totalGib) {
        return new IncusClient.PoolUsage(totalGib * GIB - (long) (freeGib * GIB), totalGib * GIB);
    }

    @Test
    void aBuildIsRefusedOnceThePoolIsCritical() {
        var refusal = check(free(4, 60), "cow", PoolSpace.Need.BUILD, false);
        assertNotNull(refusal);
        assertTrue(refusal.contains("too little to build in"), refusal);
        assertFalse(refusal.contains("vm resize"), "Linux has no VM to grow: " + refusal);
        assertFalse(refusal.contains("stops Incus"), "a Linux loop pool does not hold Incus's database: " + refusal);
        assertNull(check(free(6, 60), "cow", PoolSpace.Need.BUILD, false));
    }

    @Test
    void aBuildIsRefusedOnASmallPoolABranchIsRefusedOn() {
        // 1.9 GiB free of 15 GiB is only 87% full, so not critical, but a branch is refused there
        assertNotNull(check(free(1.9, 15), "cow", PoolSpace.Need.BRANCH, false));
        var refusal = check(free(1.9, 15), "cow", PoolSpace.Need.BUILD, false);
        assertNotNull(refusal);
        assertTrue(refusal.contains("too little to build in"), refusal);
    }

    @Test
    void aBranchIsOnlyWarnedAtCriticalAndRefusedOnceExhausted() {
        var recorded = RecordingOutput.during(() ->
                assertNull(check(free(4, 60), "cow", PoolSpace.Need.BRANCH, true)));
        assertEquals(1, recorded.warnings().size(), () -> String.join("\n", recorded.warnings()));
        var refusal = check(free(1.9, 60), "cow", PoolSpace.Need.BRANCH, true);
        assertNotNull(refusal);
        assertTrue(refusal.contains("isx vm resize <size>"), refusal);
        assertTrue(refusal.contains("a full disk stops Incus"), refusal);
        assertTrue(refusal.contains("about five minutes"), refusal);
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
        var recorded = RecordingOutput.during(() -> assertNull(check(null, "cow", PoolSpace.Need.BUILD, true)));
        assertTrue(recorded.events().isEmpty());
    }

    @Test
    void aLinuxPoolOnTheHostsOwnDiskSaysAFullDiskStopsIncus() {
        // A subvolume pool fills the host's filesystem, which holds /var/lib/incus
        var refusal = PoolSpace.check(free(1.5, 200), "cow", PoolSpace.Need.BRANCH, false, () -> true);
        assertTrue(refusal.contains("and a full disk stops Incus"), refusal);
        assertFalse(refusal.contains("five minutes"), "measured on the macOS VM only: " + refusal);
    }

    @Test
    void aPoolWithRoomIsNotAskedWhetherItSharesIncussDisk() {
        assertNull(PoolSpace.check(free(20, 60), "cow", PoolSpace.Need.BUILD, false,
                () -> { throw new AssertionError("asked for a pool with room"); }));
    }

    @Test
    void onlyAPoolOnTheHostsOwnFilesystemSharesItsDisk() {
        assertTrue(PoolSpace.sharesHostDisk(java.util.Map.of()));
        assertTrue(PoolSpace.sharesHostDisk(java.util.Map.of("source", "/var/lib/incus/storage-pools/cow")));
        assertFalse(PoolSpace.sharesHostDisk(java.util.Map.of("source", "/var/lib/incus/disks/cow.img")));
        assertFalse(PoolSpace.sharesHostDisk(java.util.Map.of("source", "/dev/sdb1")));
        // A zfs dataset and an LVM volume group are disks of their own
        assertFalse(PoolSpace.sharesHostDisk(java.util.Map.of("source", "tank/incus")));
        assertFalse(PoolSpace.sharesHostDisk(java.util.Map.of("source", "incus-vg")));
    }

    @Test
    void onlyALoopFileSourceIsALoopFile() {
        assertTrue(PoolSpace.isLoopFile(java.util.Map.of("source", "/var/lib/incus/disks/cow.img")));
        assertFalse(PoolSpace.isLoopFile(java.util.Map.of("source", "/var/lib/incus/storage-pools/cow")));
        assertFalse(PoolSpace.isLoopFile(java.util.Map.of()));
    }
}
