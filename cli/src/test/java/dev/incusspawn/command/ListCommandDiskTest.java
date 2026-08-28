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

    // --- referencedDelta: per-template weight from stamped btrfs referenced (rfer) sizes ---

    @Test
    void referencedDeltaRootShowsFullReferenced() {
        // The root template has no template parent, so it owns the base-image weight: its own rfer.
        long baseImage = 1_200_000_000L;
        assertEquals(baseImage, ListCommand.referencedDelta(baseImage, null, true));
        assertEquals(baseImage, ListCommand.referencedDelta(baseImage, 999L, true));  // parent ignored for root
    }

    @Test
    void referencedDeltaDerivedShowsDeltaOverParent() {
        // tpl-isx references base+deltas; over its parent tpl-java it shows only what isx added.
        long isx = 1_800_000_000L;
        long java = 1_300_000_000L;
        assertEquals(500_000_000L, ListCommand.referencedDelta(isx, java, false));
    }

    @Test
    void referencedDeltaClampsNegativeToZero() {
        // A child that deleted files present in its parent can reference slightly less than it.
        assertEquals(0, ListCommand.referencedDelta(1000, 1200L, false));
    }

    @Test
    void referencedDeltaFallsBackToFullReferencedWhenParentUnknown() {
        // Parent out of scope / unstamped: show full rfer rather than over-subtracting to a bogus delta.
        assertEquals(1_800_000_000L, ListCommand.referencedDelta(1_800_000_000L, null, false));
    }

    // --- nearestStampedAncestorRfer: template-deletion resilience for the delta model ---

    // Chain (YAML definitions, which survive deletion): minimal <- dev <- java <- isx
    private static java.util.Map<String, String> chainDefs() {
        var m = new java.util.HashMap<String, String>();
        m.put("tpl-minimal", "");
        m.put("tpl-dev", "tpl-minimal");
        m.put("tpl-java", "tpl-dev");
        m.put("tpl-isx", "tpl-java");
        return m;
    }

    @Test
    void ancestorUsesImmediateParentWhenPresent() {
        var rfer = java.util.Map.of("tpl-minimal", 1_000L, "tpl-java", 1_300L, "tpl-isx", 1_800L);
        assertEquals(1_300L, ListCommand.nearestStampedAncestorRfer("tpl-java", rfer, chainDefs()));
    }

    @Test
    void ancestorClimbsPastADeletedIntermediate() {
        // tpl-java (isx's parent) was deleted -> its rfer is gone; climb to the surviving tpl-dev.
        var rfer = java.util.Map.of("tpl-minimal", 1_000L, "tpl-dev", 1_150L, "tpl-isx", 1_800L);
        assertEquals(1_150L, ListCommand.nearestStampedAncestorRfer("tpl-java", rfer, chainDefs()));
    }

    @Test
    void ancestorReturnsNullWhenWholeChainAboveWasDeleted() {
        // Every ancestor deleted -> null, so the caller shows full rfer and re-absorbs the base weight.
        var rfer = java.util.Map.of("tpl-isx", 1_800L);
        assertNull(ListCommand.nearestStampedAncestorRfer("tpl-java", rfer, chainDefs()));
    }

    @Test
    void ancestorTerminatesOnCyclicParentDefinition() {
        var cyclic = java.util.Map.of("a", "b", "b", "a");
        assertNull(ListCommand.nearestStampedAncestorRfer("a", java.util.Map.of(), cyclic));
    }

    // --- hasDescendant: an instance/template that something was branched or derived from ---

    @Test
    void hasDescendantWhenAnotherRowNamesItAsParent() {
        // instance "work-b" was branched from instance "work-a": work-a has a live descendant.
        var parents = java.util.List.of("tpl-isx", "work-a", "");
        assertTrue(ListCommand.hasDescendant("work-a", parents));
    }

    @Test
    void noDescendantForALeafInstance() {
        var parents = java.util.List.of("tpl-isx", "work-a", "");
        assertFalse(ListCommand.hasDescendant("work-b", parents));
    }

    @Test
    void hasDescendantIgnoresNullAndBlankNames() {
        var parents = java.util.List.of("", "tpl-isx");
        assertFalse(ListCommand.hasDescendant(null, parents));
        assertFalse(ListCommand.hasDescendant("", parents));   // blank parent must never self-match
    }
}
