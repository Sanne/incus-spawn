package dev.incusspawn.incus;

import dev.incusspawn.Platform;
import dev.incusspawn.vm.VmAgentClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Per-subvolume <em>referenced</em> (rfer) bytes for a btrfs storage pool, keyed by instance name.
 *
 * <p>Incus's API only ever exposes each subvolume's <em>exclusive</em> bytes (blocks unique to that
 * one subvolume; see the btrfs driver's {@code GetVolumeUsage}, which reads the qgroup but returns
 * only the exclusive column). Exclusive collapses to ~0 for any subvolume that has a CoW descendant,
 * so it can't tell you a template's real weight. The <em>referenced</em> column — a subvolume's full
 * logical size including blocks shared with ancestors — can, and it's what lets the TUI show each row
 * as a delta from its parent. There is no Incus API for it, so we read it from btrfs directly:
 *
 * <ul>
 *   <li><b>Linux</b>: the pool lives on the host; run {@code btrfs} under a scoped NOPASSWD sudoers
 *       rule installed by {@code isx init} (qgroup show + subvolume list are read-only).</li>
 *   <li><b>macOS</b>: the pool lives inside the appliance VM; ask the in-VM control agent (which runs
 *       as root and can see {@code /var/lib/incus/storage-pools}) via its {@code btrfs-usage} verb.</li>
 * </ul>
 *
 * Both paths feed the same {@link #parse} logic, so the join is covered by unit tests without btrfs.
 * Any failure (qgroups off, btrfs unreachable, no sudoers rule) yields an empty map and the caller
 * falls back to the exclusive-usage display.
 */
public final class BtrfsUsage {

    /** Standard Incus on-disk layout: pools are mounted here on the host and inside the VM alike. */
    static final String POOL_MOUNT_PREFIX = "/var/lib/incus/storage-pools/";

    /** Separates the two command outputs in the agent's {@code btrfs-usage} reply. */
    public static final String AGENT_SECTION_MARKER = "---ISX-SUBVOL---";

    private static final long PROBE_TIMEOUT_SECONDS = 8;

    private BtrfsUsage() {}

    /**
     * Referenced bytes per instance/template name for {@code poolName}, or an empty map if the data
     * can't be obtained. Never throws — a failure is a fallback signal, not an error.
     */
    public static Map<String, Long> probe(String poolName) {
        if (poolName == null || !isSafePoolName(poolName)) return Map.of();
        try {
            if (Platform.isMacOS()) {
                var resp = VmAgentClient.btrfsUsage(poolName);
                if (resp.isEmpty()) return Map.of();
                var parts = resp.get().split(AGENT_SECTION_MARKER, 2);
                if (parts.length != 2) return Map.of();
                return parse(parts[0], parts[1]);
            }
            var mount = POOL_MOUNT_PREFIX + poolName;
            var qgroup = runBtrfs("qgroup", "show", "-re", "--raw", mount);
            var subvols = runBtrfs("subvolume", "list", mount);
            if (qgroup == null || subvols == null) return Map.of();
            return parse(qgroup, subvols);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * Join {@code btrfs qgroup show -re --raw} output (Qgroupid / Referenced / Exclusive columns)
     * with {@code btrfs subvolume list} output (ID … path …) to map each instance/template name to
     * its referenced bytes. Only top-level instance and VM subvolumes are considered — snapshots,
     * images, custom volumes and any subvolumes nested inside a guest are skipped.
     */
    public static Map<String, Long> parse(String qgroupShowOutput, String subvolumeListOutput) {
        Map<Long, Long> referencedBySubvolid = new HashMap<>();
        for (var line : qgroupShowOutput.split("\n")) {
            var fields = line.trim().split("\\s+");
            if (fields.length < 2) continue;
            var qid = fields[0];
            if (!qid.startsWith("0/")) continue;                 // skip headers and higher-level qgroups
            try {
                long subvolid = Long.parseLong(qid.substring(2));
                long referenced = Long.parseLong(fields[1]);     // column order: Qgroupid Referenced Exclusive
                referencedBySubvolid.put(subvolid, referenced);
            } catch (NumberFormatException ignored) {
                // header row ("Qgroupid Referenced …") or unexpected format — skip
            }
        }

        Map<String, Long> byName = new HashMap<>();
        for (var line : subvolumeListOutput.split("\n")) {
            var fields = line.trim().split("\\s+");
            long subvolid = -1;
            String path = null;
            for (int i = 0; i + 1 < fields.length; i++) {
                if ("ID".equals(fields[i])) {
                    try { subvolid = Long.parseLong(fields[i + 1]); } catch (NumberFormatException ignored) {}
                } else if ("path".equals(fields[i])) {
                    path = String.join(" ", java.util.Arrays.copyOfRange(fields, i + 1, fields.length));
                    break;
                }
            }
            if (subvolid < 0 || path == null) continue;
            var name = instanceNameFromPath(path);
            if (name == null) continue;
            var referenced = referencedBySubvolid.get(subvolid);
            if (referenced != null) byName.put(name, referenced);
        }
        return byName;
    }

    /**
     * The instance/template name for a subvolume path, or null if it isn't a top-level instance
     * subvolume. Matches exactly {@code …/containers/<name>} and {@code …/virtual-machines/<name>}
     * where {@code <name>} is the final path segment (so nested guest subvolumes, snapshots under
     * {@code containers-snapshots}, images and custom volumes are all excluded).
     */
    static String instanceNameFromPath(String path) {
        var parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (("containers".equals(parts[i]) || "virtual-machines".equals(parts[i]))
                    && i == parts.length - 2) {
                return parts[i + 1];
            }
        }
        return null;
    }

    /**
     * Pool names come from Incus, but they're interpolated into a path, so keep them boring. The
     * single Java source of truth for this check (the {@code isx-agent} shell script keeps its own
     * copy on purpose — a separate trust boundary in another language).
     */
    public static boolean isSafePoolName(String poolName) {
        return poolName != null && poolName.matches("[A-Za-z0-9._-]+");
    }

    /** Run {@code sudo -n btrfs <args>} on the host; returns stdout, or null on any failure. */
    private static String runBtrfs(String... args) {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("sudo");
        cmd.add("-n");
        cmd.add("btrfs");
        java.util.Collections.addAll(cmd, args);
        try {
            var pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            var proc = pb.start();
            var out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!proc.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return null;
            }
            return proc.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
