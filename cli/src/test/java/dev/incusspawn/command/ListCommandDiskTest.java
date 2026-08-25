package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the disk-metric helpers backing the TUI storage gauge and DISK columns. */
class ListCommandDiskTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // --- diskCell: compact per-row usage with the "~" approximate marker ---

    @Test
    void diskCellUnknownRendersDash() {
        assertEquals("-", ListCommand.diskCell(-1));
    }

    @Test
    void diskCellScalesUnits() {
        assertEquals("~500B", ListCommand.diskCell(500));
        assertEquals("~2K", ListCommand.diskCell(2048));
        assertEquals("~1M", ListCommand.diskCell(1024L * 1024));
        assertEquals("~3.1G", ListCommand.diskCell((long) (3.1 * 1024 * 1024 * 1024)));
    }

    @Test
    void diskCellAlwaysCarriesApproxMarker() {
        assertTrue(ListCommand.diskCell(1234567).startsWith("~"),
                "per-row disk figures must flag that they are approximate");
    }

    // --- bar: fractional-eighths gauge fill ---

    @Test
    void barEmptyAndFull() {
        assertEquals("          ", ListCommand.bar(0, 10));
        assertEquals("██████████", ListCommand.bar(100, 10));
    }

    @Test
    void barWidthIsExact() {
        for (int p = 0; p <= 100; p += 7) {
            assertEquals(10, ListCommand.bar(p, 10).length(),
                    "bar must always fill exactly its cell width at " + p + "%");
        }
    }

    @Test
    void barClampsOutOfRange() {
        assertEquals("     ", ListCommand.bar(-20, 5));
        assertEquals("█████", ListCommand.bar(150, 5));
    }

    @Test
    void barZeroWidth() {
        assertEquals("", ListCommand.bar(50, 0));
    }

    // --- gib: pool-level readout ---

    @Test
    void gibFormatsGibibytes() {
        assertEquals("60.0 GiB", ListCommand.gib(60L * 1024 * 1024 * 1024));
        assertEquals("0.0 GiB", ListCommand.gib(0));
    }

    // --- sumDiskUsage: parse state.disk.<dev>.usage from recursion=2 payload ---

    @Test
    void sumDiskUsageAddsAllDevices() throws Exception {
        var node = JSON.readTree("{\"root\":{\"usage\":1000},\"extra\":{\"usage\":500}}");
        assertEquals(1500, ListCommand.sumDiskUsage(node));
    }

    @Test
    void sumDiskUsageMissingReturnsUnknown() throws Exception {
        // dir pools / stopped instances report no usage -> -1 (renders as "-")
        var node = JSON.readTree("{\"root\":{}}");
        assertEquals(-1, ListCommand.sumDiskUsage(node));
    }

    @Test
    void sumDiskUsageEmptyOrMissingNode() throws Exception {
        assertEquals(-1, ListCommand.sumDiskUsage(JSON.readTree("{}")));
        assertEquals(-1, ListCommand.sumDiskUsage(JSON.nullNode()));
    }
}
