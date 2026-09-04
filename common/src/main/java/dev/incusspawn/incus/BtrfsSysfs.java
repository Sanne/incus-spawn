package dev.incusspawn.incus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Reads a btrfs filesystem's qgroup accounting status straight from sysfs
 * ({@code /sys/fs/btrfs/<fsid>/qgroups/}), which the kernel exposes world-readable — so, unlike
 * the rfer read itself, checking whether the accounting can be <em>trusted</em> needs no root.
 *
 * <p>The only awkward part is finding the filesystem: the pool path is a plain directory (or
 * subvolume) inside a larger btrfs mount, and btrfs {@code st_dev} numbers are per-subvolume
 * anonymous devices, so neither {@code stat} nor the {@code major:minor} column of
 * {@code /proc/self/mountinfo} identify the block device. What does is the mount's <em>source</em>
 * (e.g. {@code /dev/vdc}); its resolved device name appears as an entry under
 * {@code /sys/fs/btrfs/<fsid>/devices/}. So: longest btrfs mount point that prefixes the pool path
 * → source device → device name → the fsid directory listing that device.
 *
 * <p>Pure file logic, parameterised on the roots so it's unit-tested against a fake tree. The
 * in-VM {@code isx-agent} implements the same lookup in shell for macOS (see the
 * {@code btrfs-status} verb) — keep the two in step.
 */
final class BtrfsSysfs {

    static final Path MOUNTINFO = Path.of("/proc/self/mountinfo");
    static final Path SYSFS_BTRFS = Path.of("/sys/fs/btrfs");

    private BtrfsSysfs() {}

    /** Qgroup status for the btrfs filesystem containing {@code path} on this host. */
    static BtrfsUsage.QgroupStatus status(String path) {
        try {
            return fsidDir(MOUNTINFO, SYSFS_BTRFS, path)
                    .map(BtrfsSysfs::readStatus)
                    .orElse(BtrfsUsage.QgroupStatus.UNAVAILABLE);
        } catch (IOException | RuntimeException e) {
            return BtrfsUsage.QgroupStatus.UNAVAILABLE;
        }
    }

    /** The {@code /sys/fs/btrfs/<fsid>} directory of the filesystem containing {@code path}. */
    static Optional<Path> fsidDir(Path mountinfo, Path sysfsBtrfs, String path) throws IOException {
        if (!Files.isRegularFile(mountinfo) || !Files.isDirectory(sysfsBtrfs)) return Optional.empty();
        var source = sourceDeviceForPath(Files.readAllLines(mountinfo), path);
        if (source == null) return Optional.empty();
        return fsidDirForDevice(sysfsBtrfs, deviceName(source));
    }

    /**
     * The source device of the btrfs mount that contains {@code path} — the mount with the longest
     * mount point that equals the path or is a directory prefix of it — or null if the path isn't
     * on btrfs. A btrfs mount at {@code /var/lib/incus} wins over {@code /} for
     * {@code /var/lib/incus/storage-pools/cow}, and never matches {@code /var/lib/incus2}.
     */
    static String sourceDeviceForPath(List<String> mountinfoLines, String path) {
        String best = null;
        int bestLen = -1;
        for (var line : mountinfoLines) {
            // mountID parentID major:minor root mountpoint options [optional...] - fstype source superopts
            int sep = line.indexOf(" - ");
            if (sep < 0) continue;
            var head = line.substring(0, sep).split(" ");
            var tail = line.substring(sep + 3).split(" ");
            if (head.length < 5 || tail.length < 2) continue;
            if (!"btrfs".equals(tail[0])) continue;
            var mountPoint = unescape(head[4]);
            boolean prefix = mountPoint.equals(path)
                    || "/".equals(mountPoint)
                    || path.startsWith(mountPoint + "/");
            if (prefix && mountPoint.length() > bestLen) {
                bestLen = mountPoint.length();
                best = unescape(tail[1]);
            }
        }
        return best;
    }

    /**
     * The kernel device name for a mount source: the final path segment after resolving symlinks
     * ({@code /dev/mapper/vg-lv} → {@code dm-0}); falls back to the literal basename when the node
     * can't be resolved (e.g. in tests).
     */
    static String deviceName(String source) {
        var p = Path.of(source);
        try {
            p = p.toRealPath();
        } catch (IOException | RuntimeException ignored) {
            // not a resolvable node here — use the name as given
        }
        var name = p.getFileName();
        return name == null ? source : name.toString();
    }

    /** The fsid directory whose {@code devices/} lists {@code deviceName}, if any. */
    static Optional<Path> fsidDirForDevice(Path sysfsBtrfs, String deviceName) throws IOException {
        try (var fsids = Files.list(sysfsBtrfs)) {
            return fsids.filter(dir -> Files.exists(dir.resolve("devices").resolve(deviceName)))
                    .findFirst();
        }
    }

    /**
     * Parse the {@code qgroups/} attribute files of an fsid directory. A missing {@code qgroups}
     * directory means either quotas are off or the kernel predates these attributes (pre-6.1) —
     * both "can't assess", so it reports unavailable rather than guessing.
     */
    static BtrfsUsage.QgroupStatus readStatus(Path fsidDir) {
        var q = fsidDir.resolve("qgroups");
        if (!Files.isDirectory(q)) return BtrfsUsage.QgroupStatus.UNAVAILABLE;
        var sb = new StringBuilder();
        for (var key : List.of("enabled", "inconsistent", "mode", "drop_subtree_threshold")) {
            String value;
            try {
                value = Files.readString(q.resolve(key)).strip();
            } catch (IOException ignored) {
                continue;   // attribute absent on this kernel — parseStatus tolerates missing optional keys
            }
            sb.append(key).append('=').append(value).append('\n');
        }
        return BtrfsUsage.parseStatus(sb.toString());
    }

    /** mountinfo escapes space, tab, newline and backslash as octal ({@code \040} etc.). */
    static String unescape(String field) {
        if (field.indexOf('\\') < 0) return field;
        var sb = new StringBuilder(field.length());
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == '\\' && isOctal(field, i + 1)) {
                sb.append((char) Integer.parseInt(field.substring(i + 1, i + 4), 8));
                i += 3;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isOctal(String s, int from) {
        if (from + 3 > s.length()) return false;
        for (int i = from; i < from + 3; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '7') return false;
        }
        return true;
    }
}
