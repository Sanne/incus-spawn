package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceLimitsTest {

    private static final long GIB = 1024L * 1024 * 1024;

    // vmCpusFor(host CPUs, the top tier's physical cores on a hybrid host, else 0)

    @Test
    void aHomogeneousHostGetsItsCpusButTwoUpToEight() {
        // A 16-core, 32-thread desktop
        assertEquals(8, ResourceLimits.vmCpusFor(32, 0));
        // A 4-core, 8-thread host keeps 6: its cores do not limit it, only a hybrid's top tier does
        assertEquals(6, ResourceLimits.vmCpusFor(8, 0));
        // A 4-core host without SMT
        assertEquals(2, ResourceLimits.vmCpusFor(4, 0));
    }

    @Test
    void aHybridHostIsAlsoLimitedToItsTopTiersPhysicalCores() {
        // 6 P-cores among 22 threads, an i7-12700H's shape
        assertEquals(6, ResourceLimits.vmCpusFor(22, 6));
    }

    @Test
    void aHybridHostWithMoreThanEightTopTierCoresIsCappedAtEight() {
        // An i9-13900K: 8 P-cores among 32 threads; a 12-P-core part
        assertEquals(8, ResourceLimits.vmCpusFor(32, 8));
        assertEquals(8, ResourceLimits.vmCpusFor(40, 12));
    }

    @Test
    void anUnknownTopologyFollowsTheHomogeneousRule() {
        // A hybrid whose tiers cannot be told apart reports hybridTopTier 0
        // (CpuInfoTest#anUndetectableHybridIsTreatedAsHomogeneous): an i7-12700H's 20 threads
        // then give min(8, 18), not its 6 P-cores
        assertEquals(8, ResourceLimits.vmCpusFor(20, 0));
    }

    @Test
    void vmCpusNeverFallBelowOne() {
        assertEquals(1, ResourceLimits.vmCpusFor(2, 0));
        assertEquals(1, ResourceLimits.vmCpusFor(1, 1));
    }

    @Test
    void vmMemoryIsAQuarterOfTheHost() {
        assertEquals("8GiB", ResourceLimits.vmMemoryLimitFor(32 * GIB));
    }

    @Test
    void vmMemoryIsCappedSoSeveralVmsFitOnALargeHost() {
        assertEquals("16GiB", ResourceLimits.vmMemoryLimitFor(92 * GIB));
        assertEquals("16GiB", ResourceLimits.vmMemoryLimitFor(512 * GIB));
    }

    @Test
    void vmMemoryHasAFloorOnSmallHosts() {
        assertEquals("4GiB", ResourceLimits.vmMemoryLimitFor(8 * GIB));
    }

    @Test
    void vmMemoryFloorNeverExceedsTheContainerDefaultOnTinyHosts() {
        assertEquals("2457MiB", ResourceLimits.vmMemoryLimitFor(4 * GIB));
    }

    @Test
    void vmMemoryFallsBackToTheFloorWhenHostMemoryIsUnknown() {
        assertEquals("4GiB", ResourceLimits.vmMemoryLimitFor(-1));
    }
}
