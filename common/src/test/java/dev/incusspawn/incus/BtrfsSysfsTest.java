package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The sysfs qgroup-status lookup, against a fake /proc/self/mountinfo and /sys/fs/btrfs tree. */
class BtrfsSysfsTest {

    // Real shapes: btrfs mounts carry an anonymous major:minor (0:28) — which is exactly why the
    // lookup goes through the source device instead. Includes a root btrfs, the appliance's data
    // disk at /var/lib/incus, a look-alike prefix (/var/lib/incus2), and a non-btrfs mount.
    private static final List<String> MOUNTINFO = List.of(
            "24 1 0:22 / / rw,relatime shared:1 - btrfs /dev/vda rw,subvolid=5,subvol=/",
            "57 24 0:28 / /var/lib/incus rw,relatime shared:2 - btrfs /dev/vdc rw,compress=zstd:1,subvolid=5,subvol=/",
            "58 24 0:29 / /var/lib/incus2 rw,relatime shared:3 - btrfs /dev/vdd rw,subvolid=5,subvol=/",
            "59 24 259:1 / /boot rw,relatime shared:4 - ext4 /dev/vda1 rw",
            "60 24 0:30 / /mnt/with\\040space rw shared:5 - btrfs /dev/vde rw"
    );

    @Test
    void picksTheLongestBtrfsMountPrefix() {
        assertEquals("/dev/vdc", BtrfsSysfs.sourceDeviceForPath(MOUNTINFO, "/var/lib/incus/storage-pools/cow"));
        assertEquals("/dev/vdc", BtrfsSysfs.sourceDeviceForPath(MOUNTINFO, "/var/lib/incus"));
        assertEquals("/dev/vda", BtrfsSysfs.sourceDeviceForPath(MOUNTINFO, "/var/lib/other/pool"));
    }

    @Test
    void prefixMatchIsOnPathSegments() {
        // /var/lib/incus must not claim /var/lib/incus2's pool
        assertEquals("/dev/vdd", BtrfsSysfs.sourceDeviceForPath(MOUNTINFO, "/var/lib/incus2/storage-pools/cow"));
        assertEquals("/dev/vda", BtrfsSysfs.sourceDeviceForPath(MOUNTINFO, "/var/lib/incus-other"));
    }

    @Test
    void ignoresNonBtrfsMountsAndUnescapesMountinfo() {
        assertEquals("/dev/vda", BtrfsSysfs.sourceDeviceForPath(MOUNTINFO, "/boot/loader"));  // ext4 /boot skipped
        assertEquals("/dev/vde", BtrfsSysfs.sourceDeviceForPath(MOUNTINFO, "/mnt/with space/x"));
        assertNull(BtrfsSysfs.sourceDeviceForPath(List.of("garbage line", "59 24 259:1 / / rw - ext4 /dev/x rw"), "/a"));
    }

    @Test
    void unescapeHandlesOctalSequences() {
        assertEquals("with space", BtrfsSysfs.unescape("with\\040space"));
        assertEquals("plain", BtrfsSysfs.unescape("plain"));
        assertEquals("trail\\", BtrfsSysfs.unescape("trail\\"));          // dangling backslash kept
        assertEquals("a\\b", BtrfsSysfs.unescape("a\\b"));                // non-octal kept
    }

    @Test
    void deviceNameFallsBackToBasenameWhenUnresolvable() {
        assertEquals("vdc", BtrfsSysfs.deviceName("/dev/vdc"));           // no such node on the test host
        assertEquals("dm-0", BtrfsSysfs.deviceName("/dev/dm-0"));
    }

    @Test
    void resolvesFsidByDeviceEntryAndReadsStatus(@TempDir Path tmp) throws IOException {
        var sysfs = tmp.resolve("sys/fs/btrfs");
        // Two filesystems: the root disk (no qgroups/, quota off) and the data disk (quota on, inconsistent).
        var rootFs = sysfs.resolve("d984c9f4-eb1b-4932-a2ea-81aa00f0276c");
        var dataFs = sysfs.resolve("97294d7e-a544-11f1-bd19-a1e8a52cd59f");
        Files.createDirectories(rootFs.resolve("devices/vda"));
        Files.createDirectories(dataFs.resolve("devices/vdc"));
        var q = dataFs.resolve("qgroups");
        Files.createDirectories(q);
        Files.writeString(q.resolve("enabled"), "1\n");
        Files.writeString(q.resolve("inconsistent"), "1\n");
        Files.writeString(q.resolve("mode"), "qgroup\n");
        Files.writeString(q.resolve("drop_subtree_threshold"), "3\n");
        var mountinfo = tmp.resolve("mountinfo");
        Files.write(mountinfo, MOUNTINFO);

        var fsid = BtrfsSysfs.fsidDir(mountinfo, sysfs, "/var/lib/incus/storage-pools/cow");
        assertTrue(fsid.isPresent());
        assertEquals(dataFs, fsid.get());

        var status = BtrfsSysfs.readStatus(fsid.get());
        assertTrue(status.available());
        assertTrue(status.enabled());
        assertTrue(status.inconsistent());
        assertTrue(status.untrusted());
        assertEquals("qgroup", status.mode());
        assertEquals(3, status.dropSubtreeThreshold());

        // The root disk has no qgroups/ directory: can't assess → unavailable, never "trusted".
        var rootStatus = BtrfsSysfs.readStatus(BtrfsSysfs.fsidDir(mountinfo, sysfs, "/srv/x").orElseThrow());
        assertFalse(rootStatus.available());
        assertFalse(rootStatus.untrusted());
    }

    @Test
    void toleratesKernelsWithoutTheOptionalAttributes(@TempDir Path tmp) throws IOException {
        var q = tmp.resolve("qgroups");
        Files.createDirectories(q);
        Files.writeString(q.resolve("enabled"), "1");
        Files.writeString(q.resolve("inconsistent"), "0");
        var status = BtrfsSysfs.readStatus(tmp);
        assertTrue(status.available());
        assertFalse(status.untrusted());
        assertEquals("", status.mode());
        assertEquals(-1, status.dropSubtreeThreshold());
    }

    @Test
    void unknownDeviceOrMissingRootsYieldEmpty(@TempDir Path tmp) throws IOException {
        var sysfs = tmp.resolve("btrfs");
        Files.createDirectories(sysfs.resolve("abc/devices/vda"));
        assertTrue(BtrfsSysfs.fsidDirForDevice(sysfs, "vdz").isEmpty());
        assertTrue(BtrfsSysfs.fsidDir(tmp.resolve("nope"), sysfs, "/x").isEmpty());
        assertTrue(BtrfsSysfs.fsidDir(Files.writeString(tmp.resolve("mi"), ""), tmp.resolve("nosys"), "/x").isEmpty());
    }
}
