package dev.incusspawn.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemorySizeTest {

    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024 * MIB;

    @Test
    void readsTheJvmsLettersAndIncusUnits() {
        assertEquals(2 * GIB, MemorySize.parse("2g"));
        assertEquals(512 * MIB, MemorySize.parse("512M"));
        assertEquals(768 * MIB, MemorySize.parse("768MiB"));
        assertEquals(4 * GIB, MemorySize.parse("4GiB"));
        // Incus counts GB in 1000s
        assertEquals(4_000_000_000L, MemorySize.parse("4GB"));
        assertEquals(1024, MemorySize.parse("1024"));
    }

    @Test
    void anythingElseIsNoSize() {
        assertEquals(-1, MemorySize.parse("lots"));
        assertEquals(-1, MemorySize.parse("2.5GiB"));
        assertEquals(-1, MemorySize.parse("2 parsecs"));
        assertEquals(-1, MemorySize.parse(""));
        assertEquals(-1, MemorySize.parse(null));
        assertEquals(-1, MemorySize.parse("99999999999999999999T"));
    }

    @Test
    void aLimitMayBeAShareOfTheHost() {
        assertEquals(8 * GIB, MemorySize.ofLimit("50%", 16 * GIB));
        assertEquals(-1, MemorySize.ofLimit("50%", -1));
        assertEquals(3 * GIB, MemorySize.ofLimit("3GiB", -1));
    }

    @Test
    void showsSizesForPeople() {
        assertEquals("2.5 GiB", MemorySize.shown(2 * GIB + 512 * MIB));
        assertEquals("3 GiB", MemorySize.shown(3 * GIB));
        assertEquals("768 MiB", MemorySize.shown(768 * MIB));
        assertEquals("3GiB", MemorySize.wholeGib(2 * GIB + 1));
    }
}
