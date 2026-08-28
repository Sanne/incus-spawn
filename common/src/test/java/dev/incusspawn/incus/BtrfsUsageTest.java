package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BtrfsUsageTest {

    // Representative `btrfs qgroup show -re --raw <mount>`: header, top-level, then per-subvolume
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
}
