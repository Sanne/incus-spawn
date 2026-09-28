package dev.incusspawn.incus;

import dev.incusspawn.incus.InstanceSubvolumes.Kind;
import dev.incusspawn.incus.InstanceSubvolumes.Ref;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InstanceSubvolumesTest {

    private static Ref ct(String name) {
        return new Ref(Kind.CONTAINER, name);
    }

    private static Ref vm(String name) {
        return new Ref(Kind.VM, name);
    }

    /** A loop-backed pool on Linux: the pool is the filesystem root. */
    private static final String LOOP_POOL = """
            ID 256 gen 10 top level 5 path containers
            ID 257 gen 11 top level 5 path containers-snapshots
            ID 258 gen 12 top level 5 path images/0f3c1e2d
            ID 259 gen 13 top level 5 path custom/default_dnf-cache
            ID 300 gen 40 top level 256 path containers/tpl-minimal
            ID 301 gen 41 top level 256 path containers/dev-1
            ID 302 gen 42 top level 5 path virtual-machines/tpl-vm
            ID 303 gen 43 top level 257 path containers-snapshots/dev-1/snap0
            ID 304 gen 44 top level 301 path containers/dev-1/rootfs/var/lib/containers/storage/btrfs/subvolumes/abc
            """;

    @Test
    void parsesTopLevelInstanceSubvolumesOnly() {
        assertEquals(Set.of(ct("tpl-minimal"), ct("dev-1"), vm("tpl-vm")),
                InstanceSubvolumes.parse(LOOP_POOL, "cow"));
    }

    @Test
    void parsesTheApplianceLayoutWherePoolsSitUnderStoragePools() {
        var listing = """
                ID 259 gen 13 top level 5 path storage-pools/cow/custom/default_dnf-cache
                ID 328 gen 90 top level 5 path storage-pools/cow/containers/tpl-isx
                ID 392 gen 99 top level 5 path storage-pools/cow/images/9a8b
                """;
        assertEquals(Set.of(ct("tpl-isx")), InstanceSubvolumes.parse(listing, "cow"));
    }

    @Test
    void anotherPoolOnTheSameFilesystemIsNotThisPools() {
        var listing = "ID 400 gen 1 top level 5 path storage-pools/other/containers/x\n";
        assertEquals(Set.of(), InstanceSubvolumes.parse(listing, "cow"));
    }

    @Test
    void aGuestRunningItsOwnIncusDoesNotReportItsInstances() {
        // tpl-isx runs Incus inside: its pool is nested in the guest's rootfs, and its instances
        // must not read as this pool's -- that would call them orphans.
        var listing = """
                ID 328 gen 90 top level 5 path storage-pools/cow/containers/tpl-isx
                ID 500 gen 91 top level 328 path storage-pools/cow/containers/tpl-isx/rootfs/var/lib/incus/storage-pools/cow/containers/inner
                ID 501 gen 92 top level 328 path containers/tpl-isx/rootfs/var/lib/incus/storage-pools/default/containers/inner2
                """;
        assertEquals(Set.of(ct("tpl-isx")), InstanceSubvolumes.parse(listing, "cow"));
    }

    @Test
    void comparesDiskWithRecords() {
        var onDisk = Set.of(ct("tpl-minimal"), ct("dev-1"), ct("proj_web"), ct("tpl-isx"));
        var volumes = Set.of(ct("dev-1"), ct("proj_web"), ct("tpl-isx-rebuilding"));
        var instances = Set.of(ct("dev-1"), ct("tpl-isx-rebuilding"));

        var scan = InstanceSubvolumes.compare("cow", onDisk, volumes, instances);

        assertEquals(List.of(ct("tpl-isx"), ct("tpl-minimal")), List.copyOf(scan.orphans()));
        assertEquals(List.of(ct("tpl-isx-rebuilding")), List.copyOf(scan.dangling()));
        assertTrue(scan.isOrphan("tpl-minimal"));
        assertTrue(scan.isDangling("tpl-isx-rebuilding"));
        assertFalse(scan.isOrphan("dev-1"));
        assertFalse(scan.isClean());
    }

    @Test
    void theKindIsPartOfTheName() {
        // A VM's volume does not account for a container subvolume of the same name.
        var scan = InstanceSubvolumes.compare("cow", Set.of(ct("x")), Set.of(vm("x")), Set.of(vm("x")));
        assertEquals(Set.of(ct("x")), scan.orphans());
        assertEquals(Set.of(vm("x")), scan.dangling());
    }

    @Test
    void anEmptyListingWhileIncusHasInstancesIsNotTrusted() {
        assertNull(InstanceSubvolumes.compare("cow", Set.of(), Set.of(ct("a")), Set.of(ct("a"))),
                "a listing that failed quietly must not make every instance dangling");
        assertTrue(InstanceSubvolumes.compare("cow", Set.of(), Set.of(), Set.of()).isClean());
    }

    // ---- IncusClient, against FakeIncusDaemon ----

    private static String listing(String... names) {
        var sb = new StringBuilder();
        int id = 300;
        for (var n : names) sb.append("ID ").append(id++).append(" gen 1 top level 5 path containers/").append(n).append('\n');
        return sb.toString();
    }

    @Test
    void deleteRefusesARecordWhoseSubvolumeIsMissing() {
        var daemon = new FakeIncusDaemon().container("tpl-isx-rebuilding", Map.of()).container("dev-1", Map.of());
        var client = daemon.client();
        client.subvolumeLister = pool -> listing("dev-1", "tpl-isx");

        var e = assertThrows(IncusClient.DanglingRecordException.class,
                () -> client.delete("tpl-isx-rebuilding", false));
        assertTrue(e.getMessage().contains("containers/tpl-isx-rebuilding"), e.getMessage());
        assertTrue(daemon.requests().stream().noneMatch(r -> r.startsWith("DELETE ")),
                "the record must not be deleted: " + daemon.requests());
        assertEquals(Optional.of(false), client.storageOnDisk("tpl-isx-rebuilding"));
    }

    @Test
    void deleteProceedsWhenTheSubvolumeIsThere() {
        var daemon = new FakeIncusDaemon().container("dev-1", Map.of());
        var client = daemon.client();
        client.subvolumeLister = pool -> listing("dev-1");

        client.delete("dev-1", false);

        assertTrue(daemon.requests().contains("DELETE /1.0/instances/dev-1"));
    }

    @Test
    void deleteFailsOpenWhenThePoolCannotBeListed() {
        for (var unavailable : new String[]{null, ""}) {
            var daemon = new FakeIncusDaemon().container("dev-1", Map.of());
            var client = daemon.client();
            client.subvolumeLister = pool -> unavailable;

            client.delete("dev-1", false);

            assertTrue(daemon.requests().contains("DELETE /1.0/instances/dev-1"));
            assertTrue(client.storageOnDisk("dev-1").isEmpty());
        }
    }

    @Test
    void scanFindsOrphansAndDanglingRecordsButNotOtherProjects() {
        var daemon = new FakeIncusDaemon()
                .container("dev-1", Map.of())
                .container("tpl-isx-rebuilding", Map.of())
                .volume("web", "app", "container");
        var client = daemon.client();
        client.subvolumeLister = pool -> listing("dev-1", "tpl-isx", "web_app");

        var scan = client.scanSubvolumes().orElseThrow();

        assertEquals("default", scan.pool());
        assertEquals(Set.of(ct("tpl-isx")), scan.orphans());
        assertEquals(Set.of(ct("tpl-isx-rebuilding")), scan.dangling());
    }

    @Test
    void renameChecksTheSubvolumeMovedWithTheRecord() {
        var daemon = new FakeIncusDaemon().container("tpl-x-rebuilding", Map.of());
        var client = daemon.client();
        // The record moves; the listing still has the subvolume under the old name.
        client.subvolumeLister = pool -> listing("tpl-x-rebuilding");

        var e = assertThrows(IncusException.class, () -> client.rename("tpl-x-rebuilding", "tpl-x"));
        assertTrue(e.getMessage().contains("left its storage behind"), e.getMessage());
    }

    @Test
    void renameSucceedsWhenBothMoved() {
        var daemon = new FakeIncusDaemon().container("tpl-x-rebuilding", Map.of());
        var client = daemon.client();
        client.subvolumeLister = pool -> listing("tpl-x");

        client.rename("tpl-x-rebuilding", "tpl-x");

        assertTrue(client.exists("tpl-x"));
    }

    @Test
    void scanIsEmptyWhenThePoolCannotBeListed() {
        var client = new FakeIncusDaemon().container("dev-1", Map.of()).client();
        client.subvolumeLister = pool -> null;
        assertTrue(client.scanSubvolumes().isEmpty());
    }
}
