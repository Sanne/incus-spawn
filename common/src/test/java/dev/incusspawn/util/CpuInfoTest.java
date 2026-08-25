package dev.incusspawn.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CpuInfoTest {

    @Test
    void logicalCoresIsAtLeastOne() {
        assertTrue(CpuInfo.logicalCores() >= 1, "must always report at least one core");
    }

    @Test
    void performanceCoresIsNonNegative() {
        // 0 is the "cannot determine" sentinel (homogeneous CPU / Intel Mac).
        assertTrue(CpuInfo.performanceCores() >= 0);
    }

    @Test
    void performanceCoresNeverExceedsLogicalCores() {
        int p = CpuInfo.performanceCores();
        if (p > 0) {
            assertTrue(p <= CpuInfo.logicalCores(),
                    "P-cores are a subset of all logical cores");
        }
    }

    @Test
    void highPerfCoresIsAtLeastOne() {
        assertTrue(CpuInfo.highPerfCores() >= 1, "must always report at least one core");
    }

    @Test
    void highPerfCoresDoesNotExceedLogicalCores() {
        // P-cores are a subset of all logical cores; the fallback returns exactly
        // the logical count, so highPerfCores can never be larger. Compared against
        // logicalCores() (not availableProcessors(), which the native image caps).
        assertTrue(CpuInfo.highPerfCores() <= CpuInfo.logicalCores(),
                "high-performance cores cannot exceed total logical cores");
    }
}
