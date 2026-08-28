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

    // --- gibShort: compact GiB readout for the header gauge ---

    @Test
    void gibShortIsCompact() {
        assertEquals("8.7G", ListCommand.gibShort((long) (8.7 * 1024 * 1024 * 1024)));
        assertEquals("14G", ListCommand.gibShort(14L * 1024 * 1024 * 1024));
        assertEquals("0.0G", ListCommand.gibShort(0));
    }

    // --- runningSummary: the header's "N running" badge, split by instance type ---

    @Test
    void runningSummaryEmptyWhenNothingRuns() {
        assertEquals("", ListCommand.runningSummary(0, 0));
    }

    @Test
    void runningSummaryPluralizesEachKind() {
        assertEquals("1 container running", ListCommand.runningSummary(1, 0));
        assertEquals("2 containers running", ListCommand.runningSummary(2, 0));
        assertEquals("1 VM running", ListCommand.runningSummary(0, 1));
        assertEquals("3 VMs running", ListCommand.runningSummary(0, 3));
    }

    @Test
    void runningSummaryCombinesBothKinds() {
        assertEquals("2 containers, 1 VM running", ListCommand.runningSummary(2, 1));
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

    // --- sharedBaseBytes: the base image + CoW-shared blocks folded into the root template ---

    @Test
    void sharedBaseBytesIsPoolUsedMinusRowUnique() {
        // 14 GiB used, rows account for ~0.5 GiB unique -> ~13.5 GiB belongs to the shared base.
        long used = 14L * 1024 * 1024 * 1024;
        long unique = 512L * 1024 * 1024;
        assertEquals(used - unique, ListCommand.sharedBaseBytes(used, unique));
    }

    @Test
    void sharedBaseBytesClampsAtZero() {
        // Rounding / metadata slack can make the row sum momentarily exceed reported pool usage.
        assertEquals(0, ListCommand.sharedBaseBytes(1000, 1200));
    }
}
