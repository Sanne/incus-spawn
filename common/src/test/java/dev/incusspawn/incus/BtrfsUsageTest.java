package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BtrfsUsageTest {

    // Representative `btrfs qgroup show -re --raw --sync <mount>`: header, top-level, then per-subvolume
    // rows. Columns are Qgroupid, Referenced, Exclusive — we want Referenced (the middle column).
    private static final String QGROUP = """
            Qgroupid         Referenced        Exclusive
            --------         ----------        ---------
            0/5                   16384            16384
            0/256            1200000000           500000
            0/257            1800000000        300000000
            0/263             900000000         10000000
            1/0              9999999999                0
            """;

    // Representative `btrfs subvolume list <mount>`: instance/VM subvolumes plus an image, a
    // snapshot, and a subvolume nested inside a guest — all of which must be ignored.
    private static final String SUBVOLS = """
            ID 256 gen 30 top level 5 path storage-pools/cow/containers/tpl-minimal
            ID 257 gen 42 top level 5 path storage-pools/cow/containers/tpl-isx
            ID 260 gen 50 top level 5 path storage-pools/cow/images/abcdef0123
            ID 261 gen 51 top level 5 path storage-pools/cow/containers-snapshots/tpl-isx/snap0
            ID 262 gen 52 top level 256 path storage-pools/cow/containers/tpl-minimal/var/nested
            ID 263 gen 53 top level 5 path storage-pools/cow/virtual-machines/tpl-vm
            """;

    @Test
    void joinsReferencedBytesByInstanceName() {
        var byName = BtrfsUsage.parse(QGROUP, SUBVOLS);
        assertEquals(1_200_000_000L, byName.get("tpl-minimal"));
        assertEquals(1_800_000_000L, byName.get("tpl-isx"));
        assertEquals(900_000_000L, byName.get("tpl-vm"));   // virtual-machines/ subvolumes count too
    }

    @Test
    void ignoresImagesSnapshotsAndNestedSubvolumes() {
        var byName = BtrfsUsage.parse(QGROUP, SUBVOLS);
        assertEquals(3, byName.size());
        assertNull(byName.get("abcdef0123"));               // image subvolume
        assertNull(byName.get("snap0"));                    // snapshot under containers-snapshots
        assertNull(byName.get("nested"));                   // subvolume nested inside a container
    }

    @Test
    void handlesEmptyOrGarbageOutput() {
        assertTrue(BtrfsUsage.parse("", "").isEmpty());
        assertTrue(BtrfsUsage.parse("not a table", "no paths here").isEmpty());
    }

    @Test
    void skipsSubvolumesWithoutAQgroupEntry() {
        // A subvolume present in the list but absent from qgroup output (quota just enabled, not yet
        // rescanned) must not appear with a bogus size.
        var subvols = "ID 999 gen 1 top level 5 path storage-pools/cow/containers/fresh\n";
        assertTrue(BtrfsUsage.parse(QGROUP, subvols).isEmpty());
    }

    @Test
    void instanceNameFromPathMatchesOnlyTopLevelInstanceSubvolumes() {
        assertEquals("foo", BtrfsUsage.instanceNameFromPath("storage-pools/cow/containers/foo"));
        assertEquals("vm1", BtrfsUsage.instanceNameFromPath("storage-pools/cow/virtual-machines/vm1"));
        assertNull(BtrfsUsage.instanceNameFromPath("storage-pools/cow/images/deadbeef"));
        assertNull(BtrfsUsage.instanceNameFromPath("storage-pools/cow/containers-snapshots/foo/s0"));
        assertNull(BtrfsUsage.instanceNameFromPath("storage-pools/cow/containers/foo/nested/sub"));
    }

    @Test
    void rejectsUnsafePoolNames() {
        assertTrue(BtrfsUsage.isSafePoolName("cow"));
        assertTrue(BtrfsUsage.isSafePoolName("pool-1_2.3"));
        assertTrue(BtrfsUsage.probe("cow; rm -rf /").isEmpty());     // never shells out on a bad name
        assertTrue(BtrfsUsage.probe("../../etc").isEmpty());
    }

    @Test
    void probeReturnsEmptyMapForNullPool() {
        assertEquals(Map.of(), BtrfsUsage.probe(null));
    }

    // --- qgroup accounting status: the trust gate in front of every rfer/exclusive read ---

    @Test
    void parseStatusReadsTheAgentReply() {
        var s = BtrfsUsage.parseStatus("enabled=1\ninconsistent=1\nmode=qgroup\ndrop_subtree_threshold=3\n");
        assertTrue(s.available());
        assertTrue(s.enabled());
        assertTrue(s.inconsistent());
        assertTrue(s.untrusted());
        assertEquals("qgroup", s.mode());
        assertEquals(3, s.dropSubtreeThreshold());

        var ok = BtrfsUsage.parseStatus("enabled=1\ninconsistent=0\nmode=squota\ndrop_subtree_threshold=8");
        assertTrue(ok.available());
        assertFalse(ok.untrusted());
        assertEquals("squota", ok.mode());
    }

    @Test
    void parseStatusTreatsUnknownAsUnavailableNeverAsTrusted() {
        // An agent that predates the verb, a bad-pool rejection, or an "available=0" reply (quota off /
        // no sysfs attributes) all mean "can't assess": behave as before the check existed.
        for (var reply : new String[] {"error: unknown verb", "error: bad pool name", "available=0", "", "garbage"}) {
            var s = BtrfsUsage.parseStatus(reply);
            assertFalse(s.available(), reply);
            assertFalse(s.untrusted(), reply);
        }
        assertFalse(BtrfsUsage.parseStatus(null).available());
    }

    @Test
    void parseStatusToleratesMissingOptionalKeys() {
        var s = BtrfsUsage.parseStatus("enabled=1\ninconsistent=0");
        assertTrue(s.available());
        assertEquals("", s.mode());
        assertEquals(-1, s.dropSubtreeThreshold());
        var bad = BtrfsUsage.parseStatus("enabled=1\ninconsistent=0\ndrop_subtree_threshold=lots");
        assertEquals(-1, bad.dropSubtreeThreshold());
    }

    @Test
    void quotaOffIsNotUntrusted() {
        // Quotas disabled: nothing to repair, and the rfer probe fails on its own (the pre-existing
        // "no stamp" path). Only a positively-inconsistent, enabled pool counts as untrusted.
        var s = BtrfsUsage.parseStatus("enabled=0\ninconsistent=0");
        assertTrue(s.available());
        assertFalse(s.untrusted());
        assertFalse(BtrfsUsage.parseStatus("enabled=0\ninconsistent=1").untrusted());
    }

    // --- opportunistic throttle: a burst of deletes must cost one check, not one per instance ---

    @Test
    void burstOfDeletesRunsOneCheckNotOnePerDelete() {
        BtrfsUsage.resetThrottleStateForTest();
        var lookups = new java.util.concurrent.atomic.AtomicInteger();
        // Simulate `isx clean` deleting 10 failed builds back to back. The supplier stands in for
        // the caller's pool lookup (an Incus API call) and must not be invoked when throttled.
        for (int i = 0; i < 10; i++) {
            BtrfsUsage.repairIfInconsistentThrottled(() -> { lookups.incrementAndGet(); return null; });
        }
        assertEquals(1, lookups.get(), "a burst of deletes must collapse to a single check");
    }

    @Test
    void throttledCheckSkipsTheExpensivePoolLookupEntirely() {
        BtrfsUsage.resetThrottleStateForTest();
        var first = BtrfsUsage.repairIfInconsistentThrottled(() -> null);
        var second = BtrfsUsage.repairIfInconsistentThrottled(() -> {
            throw new AssertionError("pool lookup must not run while throttled");
        });
        assertTrue(first.isEmpty());        // null pool -> no status, but the check did run
        assertTrue(second.isEmpty());
    }

    @Test
    void consecutiveTriggerBudgetResetsOnceAccountingReadsConsistent() {
        // The budget bounds a *stuck* repair. A long-lived TUI session legitimately repairs many
        // times over a day, so observing consistent accounting must clear the count — otherwise
        // auto-repair silently stops working after MAX_RESCAN_TRIGGERS for the rest of the session.
        BtrfsUsage.resetThrottleStateForTest();
        assertEquals(0, BtrfsUsage.rescanTriggerCountForTest());
        // An unreadable pool yields an unavailable status: neither a trigger nor a reset.
        BtrfsUsage.repairIfInconsistent("no-such-pool-for-tests");
        assertEquals(0, BtrfsUsage.rescanTriggerCountForTest());
    }

    @Test
    void statusAndRescanRejectUnsafePoolNames() {
        assertFalse(BtrfsUsage.status("cow; rm -rf /").available());   // never shells out / hits the agent
        assertFalse(BtrfsUsage.rescan("../../etc"));
        assertFalse(BtrfsUsage.status(null).available());
        assertFalse(BtrfsUsage.repairIfInconsistent(null).untrusted());
    }
}
