package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.config.DocsLinksTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CowPoolBackingTest {

    @Test
    void onBtrfsThePoolIsASubvolumeWithNoSizeCap() {
        // Incus creates the subvolume itself when the source is the pool's own mount path; a loop
        // file there would make every write copy-on-write twice (#1656).
        var cmd = List.of(CowPoolBacking.createCommand("btrfs", true));
        assertEquals(List.of("sudo", "incus", "storage", "create", "cow", "btrfs",
                "source=/var/lib/incus/storage-pools/cow"), cmd);
        assertEquals(cmd, List.of(CowPoolBacking.createCommand("btrfs", false)));
    }

    @Test
    void elsewhereTheLoopFileCommitsEveryFiveMinutesAndSkipsTrimWhenSupported() {
        assertEquals(List.of("sudo", "incus", "storage", "create", "cow", "btrfs", "size=100GiB",
                        "btrfs.mount_options=user_subvol_rm_allowed,noatime,commit=300,compress=zstd:1",
                        "btrfs.create_options=-K"),
                List.of(CowPoolBacking.createCommand("ext4", true)));
        // Incus 6.x rejects the whole create over the unknown key (#820)
        assertEquals(List.of("sudo", "incus", "storage", "create", "cow", "btrfs", "size=100GiB",
                        "btrfs.mount_options=user_subvol_rm_allowed,noatime,commit=300,compress=zstd:1"),
                List.of(CowPoolBacking.createCommand("ext4", false)));
    }

    @Test
    void aFailedSubvolumeFallsBackToALoopThatDoesNotCompressOnBtrfs() {
        var ran = new ArrayList<List<String>>();
        var warned = new ArrayList<String>();
        var created = CowPoolBacking.create(p -> "btrfs", () -> true, cmd -> {
            ran.add(List.of(cmd));
            return cmd[cmd.length - 1].startsWith("source=")
                    ? new CowPoolBacking.Attempt(1, "Error: Storage pool directory \"/var/lib/incus/storage-pools/cow\" already exists\n")
                    : new CowPoolBacking.Attempt(0, "");
        }, () -> ran.add(List.of("mkdir")), (msg, details) -> {
            warned.add(msg);
            warned.addAll(details);
        });
        assertFalse(created.subvolume());
        assertEquals(0, created.result().exitCode());
        assertEquals(List.of(
                List.of("sudo", "incus", "storage", "create", "cow", "btrfs", "source=/var/lib/incus/storage-pools/cow"),
                List.of("mkdir"),
                // The host may compress the file again, so the pool does not
                List.of("sudo", "incus", "storage", "create", "cow", "btrfs", "size=100GiB",
                        "btrfs.mount_options=user_subvol_rm_allowed,noatime,commit=300", "btrfs.create_options=-K")), ran);
        assertTrue(warned.get(0).contains("falling back to a loop file"), warned.toString());
        assertTrue(warned.stream().anyMatch(w -> w.contains("already exists")), warned.toString());
        // Worded after Incus's error, as a final failure would be
        assertTrue(warned.stream().anyMatch(w -> w.contains("leftover")), warned.toString());
    }

    @Test
    void aSubvolumeThatWorksIsTheOnlyCommand() {
        var ran = new ArrayList<List<String>>();
        var created = CowPoolBacking.create(p -> "btrfs", () -> fail("a subvolume runs no mkfs"),
                cmd -> { ran.add(List.of(cmd)); return new CowPoolBacking.Attempt(0, ""); },
                () -> fail("a subvolume needs no disks directory"), (w, d) -> fail(w));
        assertTrue(created.subvolume());
        assertEquals(List.of(List.of(CowPoolBacking.subvolumeCommand())), ran);
    }

    @Test
    void anotherFilesystemGetsTheCompressedLoopStraightAway() {
        var ran = new ArrayList<List<String>>();
        var created = CowPoolBacking.create(p -> "ext4", () -> false,
                cmd -> { ran.add(List.of(cmd)); return new CowPoolBacking.Attempt(0, ""); },
                () -> ran.add(List.of("mkdir")), (w, d) -> fail(w));
        assertFalse(created.subvolume());
        assertEquals(List.of(List.of("mkdir"), List.of("sudo", "incus", "storage", "create", "cow", "btrfs", "size=100GiB",
                "btrfs.mount_options=user_subvol_rm_allowed,noatime,commit=300,compress=zstd:1")), ran);
        // A filesystem that cannot be read may be one that compresses the file again
        assertEquals(List.of("sudo", "incus", "storage", "create", "cow", "btrfs", "size=100GiB",
                        "btrfs.mount_options=user_subvol_rm_allowed,noatime,commit=300"),
                List.of(CowPoolBacking.createCommand(null, false)));
    }

    @Test
    void theFallbackLoopCompressesAfterTheFilesystemItLivesOn() {
        // Pools on btrfs, disks on a separate ext4 mount: the file cannot be compressed twice
        var ran = new ArrayList<List<String>>();
        CowPoolBacking.create(p -> p.equals(CowPoolBacking.POOLS_DIR) ? "btrfs" : "ext4", () -> false,
                cmd -> { ran.add(List.of(cmd)); return new CowPoolBacking.Attempt(cmd[cmd.length - 1].startsWith("source=") ? 1 : 0, ""); },
                () -> {}, (w, d) -> {});
        assertEquals("btrfs.mount_options=user_subvol_rm_allowed,noatime,commit=300,compress=zstd:1",
                ran.get(1).get(ran.get(1).size() - 1));
    }

    @Test
    void cowPoolFailureIsWordedAfterIncusError() {
        var unrelated = CowPoolBacking.failureExplanation(
                "Error: Invalid option \"btrfs.create_options\"\n");
        assertEquals(List.of("Error: Invalid option \"btrfs.create_options\""), unrelated);

        var loop = CowPoolBacking.failureExplanation("Error: Failed to find a free loop device");
        assertTrue(loop.stream().anyMatch(l -> l.contains("modprobe loop")), loop.toString());

        var mkfs = CowPoolBacking.failureExplanation("Error: exec: \"mkfs.btrfs\": not found");
        assertTrue(mkfs.stream().anyMatch(l -> l.contains("btrfs-progs")), mkfs.toString());

        var leftover = CowPoolBacking.failureExplanation(
                "Error: Storage pool directory \"/var/lib/incus/storage-pools/cow\" already exists");
        assertTrue(leftover.stream().anyMatch(l -> l.contains("leftover /var/lib/incus/storage-pools/cow")), leftover.toString());

        assertEquals(List.of(), CowPoolBacking.failureExplanation(""));
    }

    @Test
    void mountOptionsKeepWhatTheUserSet() {
        assertEquals("user_subvol_rm_allowed,noatime,commit=300,compress=zstd:1", CowPoolBacking.withLoopMountOptions("", true));
        assertEquals("user_subvol_rm_allowed,noatime,commit=300,compress=zstd:1", CowPoolBacking.withLoopMountOptions(null, true));
        assertEquals("user_subvol_rm_allowed,compress=zstd:1,relatime,commit=300",
                CowPoolBacking.withLoopMountOptions("user_subvol_rm_allowed,compress=zstd:1,relatime", true));
        assertEquals("user_subvol_rm_allowed,commit=60,noatime,compress=zstd:1",
                CowPoolBacking.withLoopMountOptions("user_subvol_rm_allowed,commit=60", true));
        // A compression or no-compression choice of the user's stays theirs
        assertEquals("user_subvol_rm_allowed,compress-force=lzo,noatime,commit=300",
                CowPoolBacking.withLoopMountOptions("user_subvol_rm_allowed,compress-force=lzo", true));
        // Spaces and empty entries are tidied, not taken for missing options
        assertEquals("user_subvol_rm_allowed,noatime,commit=60,compress=zstd:1",
                CowPoolBacking.withLoopMountOptions(" user_subvol_rm_allowed, noatime,,commit=60 ", true));
        // Compression would turn nodatacow and nodatasum off
        assertEquals("user_subvol_rm_allowed,nodatacow,noatime,commit=300",
                CowPoolBacking.withLoopMountOptions("user_subvol_rm_allowed,nodatacow", true));
        assertEquals("user_subvol_rm_allowed,noatime,commit=300",
                CowPoolBacking.withLoopMountOptions("", false));
        // A value set without Incus's default lost it; it comes back first
        assertEquals("user_subvol_rm_allowed,noatime,commit=300,compress=zstd:1",
                CowPoolBacking.withLoopMountOptions("noatime,commit=300,compress=zstd:1", true));
        // nodiratime leaves file atimes on
        assertEquals("user_subvol_rm_allowed,nodiratime,noatime,commit=300,compress=zstd:1",
                CowPoolBacking.withLoopMountOptions("user_subvol_rm_allowed,nodiratime", true));
    }

    private static final Map<String, String> LOOP = Map.of(
            "source", "/var/lib/incus/disks/cow.img", "size", "100GiB");

    private static IncusClient.PoolInfo btrfs(Map<String, String> config) {
        return new IncusClient.PoolInfo("btrfs", config);
    }

    @Test
    void doctorWarnsAboutALoopFileOnBtrfsAndOffersTheMountOptions() throws Exception {
        var set = new ArrayList<String>();
        var findings = CowPoolBacking.doctorFindings("cow", btrfs(LOOP), p -> "btrfs",
                (pool, key, value) -> set.add(pool + " " + key + "=" + value));
        assertEquals(2, findings.size(), findings.toString());

        var nested = findings.get(0);
        assertEquals(DoctorCommand.Status.WARN, nested.status());
        assertTrue(nested.label().contains("loop file on btrfs"), nested.label());
        assertTrue(nested.detail().contains("/var/lib/incus/disks/cow.img"), nested.detail());
        assertTrue(nested.detail().contains("docs/dev/MIGRATIONS.md#the-cow-pool-is-a-loop-file-on-a-btrfs-host"), nested.detail());
        assertNull(nested.remediation(), "a migration moves instances, so doctor only explains it");

        var mount = findings.get(1);
        assertEquals(DoctorCommand.Status.WARN, mount.status());
        mount.remediation().action().run();
        // A loop file on btrfs is not offered compression: the host may compress it again
        assertEquals(List.of("cow btrfs.mount_options=user_subvol_rm_allowed,noatime,commit=300"), set);
    }

    @Test
    void doctorAsksNothingOfALoopFileElsewhereOnceItIsMountedRight() {
        var tuned = new java.util.HashMap<>(LOOP);
        tuned.put("btrfs.mount_options", "user_subvol_rm_allowed,noatime,commit=300,compress=zstd:1");
        assertEquals(List.of(), CowPoolBacking.doctorFindings("cow", btrfs(tuned), p -> "ext4", (a, b, c) -> fail()));
        var spaced = new java.util.HashMap<>(LOOP);
        spaced.put("btrfs.mount_options", "user_subvol_rm_allowed, noatime, commit=300, compress=zstd:1");
        assertEquals(List.of(), CowPoolBacking.doctorFindings("cow", btrfs(spaced), p -> "ext4", (a, b, c) -> fail()));
        // Nor a reason to compress a file the host may compress again
        var untuned = CowPoolBacking.doctorFindings("cow", btrfs(LOOP), p -> null, (a, b, c) -> {});
        assertEquals(1, untuned.size(), untuned.toString());
        assertEquals("Mount it with user_subvol_rm_allowed,noatime,commit=300", untuned.get(0).remediation().description());
        // An unreadable filesystem type is not a reason to tell anyone to migrate
        assertEquals(List.of(), CowPoolBacking.doctorFindings("cow", btrfs(tuned), p -> null, (a, b, c) -> fail()));
    }

    @Test
    void doctorSaysNothingAboutASubvolumePool() {
        var subvolume = Map.of("source", "/var/lib/incus/storage-pools/cow");
        assertFalse(CowPoolBacking.isLoopFile(subvolume));
        assertEquals(List.of(), CowPoolBacking.doctorFindings("cow", btrfs(subvolume), p -> "btrfs", (a, b, c) -> fail()));
        assertTrue(CowPoolBacking.isLoopFile(LOOP));
        assertFalse(CowPoolBacking.isLoopFile(Map.of("source", "8d0f6b1e-uuid")));
        assertFalse(CowPoolBacking.isLoopFile(Map.of()));
        // A zfs loop pool is not mounted with btrfs options
        assertEquals(List.of(), CowPoolBacking.doctorFindings("cow", new IncusClient.PoolInfo("zfs", LOOP),
                p -> "btrfs", (a, b, c) -> fail()));
    }

    @Test
    void filesystemTypeOfAMissingPathIsUnknown() {
        assertNull(CowPoolBacking.filesystemType(Path.of("/nonexistent/isx-1656")));
    }

    @Test
    @Tag("docs")
    void theMigrationNotesLinkNamesAHeadingTheNotesHave() throws IOException {
        var anchor = CowPoolBacking.MIGRATION_NOTES.substring(CowPoolBacking.MIGRATION_NOTES.indexOf('#') + 1);
        assertTrue(DocsLinksTest.anchors(Path.of("../docs/dev/MIGRATIONS.md")).contains(anchor), anchor);
    }
}
