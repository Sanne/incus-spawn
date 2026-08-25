package dev.incusspawn.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CpuInfoTest {

    @Test
    void highPerfCoresIsAtLeastOne() {
        assertTrue(CpuInfo.highPerfCores() >= 1, "must always report at least one core");
    }

    @Test
    void highPerfCoresDoesNotExceedLogicalProcessors() {
        // P-cores are a subset of all logical processors; the fallback returns
        // exactly the logical count, so it can never be larger.
        assertTrue(CpuInfo.highPerfCores() <= Runtime.getRuntime().availableProcessors(),
                "high-performance cores cannot exceed total logical processors");
    }
}
