package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceLimitsTest {

    private static final long GIB = 1024L * 1024 * 1024;

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
