package dev.incusspawn.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the disk-metric formatting backing the TUI storage gauge and DISK columns. */
class UsageFormatTest {

    // --- diskCell: compact per-row usage with the "~" approximate marker ---

    @Test
    void diskCellUnknownRendersDash() {
        assertEquals("-", UsageFormat.diskCell(-1));
    }

    @Test
    void diskCellScalesUnits() {
        assertEquals("~500B", UsageFormat.diskCell(500));
        assertEquals("~2K", UsageFormat.diskCell(2048));
        assertEquals("~1M", UsageFormat.diskCell(1024L * 1024));
        assertEquals("~3.1G", UsageFormat.diskCell((long) (3.1 * 1024 * 1024 * 1024)));
    }

    @Test
    void diskCellAlwaysCarriesApproxMarker() {
        assertTrue(UsageFormat.diskCell(1234567).startsWith("~"),
                "per-row disk figures must flag that they are approximate");
    }

    // --- bar: fractional-eighths gauge fill ---

    @Test
    void barEmptyAndFull() {
        assertEquals("          ", UsageFormat.bar(0, 10));
        assertEquals("██████████", UsageFormat.bar(100, 10));
    }

    @Test
    void barWidthIsExact() {
        for (int p = 0; p <= 100; p += 7) {
            assertEquals(10, UsageFormat.bar(p, 10).length(),
                    "bar must always fill exactly its cell width at " + p + "%");
        }
    }

    @Test
    void barClampsOutOfRange() {
        assertEquals("     ", UsageFormat.bar(-20, 5));
        assertEquals("█████", UsageFormat.bar(150, 5));
    }

    @Test
    void barZeroWidth() {
        assertEquals("", UsageFormat.bar(50, 0));
    }

    // --- gib: pool-level readout ---

    @Test
    void gibFormatsGibibytes() {
        assertEquals("60.0 GiB", UsageFormat.gib(60L * 1024 * 1024 * 1024));
        assertEquals("0.0 GiB", UsageFormat.gib(0));
    }

    // --- gibShort: compact GiB readout for the header gauge ---

    @Test
    void gibShortIsCompact() {
        assertEquals("8.7G", UsageFormat.gibShort((long) (8.7 * 1024 * 1024 * 1024)));
        assertEquals("14G", UsageFormat.gibShort(14L * 1024 * 1024 * 1024));
        assertEquals("0.0G", UsageFormat.gibShort(0));
    }

    // --- runningSummary: the header's "N running" badge, split by instance type ---

    @Test
    void runningSummaryEmptyWhenNothingRuns() {
        assertEquals("", UsageFormat.runningSummary(0, 0));
    }

    @Test
    void runningSummaryPluralizesEachKind() {
        assertEquals("1 container running", UsageFormat.runningSummary(1, 0));
        assertEquals("2 containers running", UsageFormat.runningSummary(2, 0));
        assertEquals("1 VM running", UsageFormat.runningSummary(0, 1));
        assertEquals("3 VMs running", UsageFormat.runningSummary(0, 3));
    }

    @Test
    void runningSummaryCombinesBothKinds() {
        assertEquals("2 containers, 1 VM running", UsageFormat.runningSummary(2, 1));
    }
}
