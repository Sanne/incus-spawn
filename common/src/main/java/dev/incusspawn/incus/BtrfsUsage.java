package dev.incusspawn.incus;

import dev.incusspawn.Platform;
import dev.incusspawn.vm.VmAgentClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 *
 * <h2>Trust: the accounting can be silently wrong</h2>
 *
 * qgroup numbers are only meaningful while the kernel considers the accounting <em>consistent</em>.
 * Two routine events break that: enabling quotas on a pool that already holds data (Incus turns
 * them on lazily, when the first instance size limit is applied), and deleting a subvolume whose
 * shared subtree is deeper than {@code drop_subtree_threshold} (every template rebuild deletes
 * one). In either case btrfs sets an {@code inconsistent} flag and stops maintaining the counters,
 * so every read — {@code qgroup show} and Incus's own {@code state.disk} usage alike — returns
 * plausible-looking but frozen figures until a {@code btrfs quota rescan} rebuilds them. Nothing
 * clears the flag on its own.
 *
 * <p>So every consumer checks {@link #status} first (the flag is world-readable in sysfs, see
 * {@link BtrfsSysfs}), and {@link #repairIfInconsistent} kicks off the rescan asynchronously the
 * moment the state is detected — the kernel does the walk in the background and clears the flag
 * when done, typically within seconds. Rfer stamps are only ever taken from consistent accounting.
 */
public final class BtrfsUsage {

    /** Standard Incus on-disk layout: pools are mounted here on the host and inside the VM alike. */
    static final String POOL_MOUNT_PREFIX = "/var/lib/incus/storage-pools/";

    /** Separates the two command outputs in the agent's {@code btrfs-usage} reply. */
    public static final String AGENT_SECTION_MARKER = "---ISX-SUBVOL---";

    private static final long PROBE_TIMEOUT_SECONDS = 8;

    /**
     * A repair trigger is cheap but not free (a whole-filesystem extent walk), and a rescan already
     * in flight keeps the flag set until it finishes — so don't re-trigger on every refresh: wait
     * this long between triggers, and give up after {@link #MAX_RESCAN_TRIGGERS} <em>consecutive</em>
     * ones (a flag that survives that many rescans isn't going to clear by trying harder; leave it
     * to {@code isx doctor}). The count resets as soon as the accounting reads consistent again, so
     * this bounds a stuck repair, not the number of repairs a long session may legitimately need.
     */
    static final Duration RESCAN_RETRY_INTERVAL = Duration.ofSeconds(60);
    static final int MAX_RESCAN_TRIGGERS = 5;

    /**
     * Minimum spacing between <em>opportunistic</em> checks (see
     * {@link #repairIfInconsistentThrottled}). Deliberate callers — a build about to stamp, the
     * TUI's own cadence, {@code isx doctor} — are never gated by this.
     */
    static final Duration CHECK_MIN_INTERVAL = Duration.ofSeconds(5);

    private static final Object RESCAN_LOCK = new Object();
    private static long lastRescanTriggerNanos = Long.MIN_VALUE;
    private static int rescanTriggers;
    private static long lastOpportunisticCheckNanos = Long.MIN_VALUE;

    /** Clear all throttle state, so each test starts from a known point. */
    static void resetThrottleStateForTest() {
        synchronized (RESCAN_LOCK) {
            lastRescanTriggerNanos = Long.MIN_VALUE;
            rescanTriggers = 0;
            lastOpportunisticCheckNanos = Long.MIN_VALUE;
        }
    }

    /** Consecutive rescan triggers since the accounting last read consistent. Test-only accessor. */
    static int rescanTriggerCountForTest() {
        synchronized (RESCAN_LOCK) {
            return rescanTriggers;
        }
    }

    private BtrfsUsage() {}

    /**
     * The kernel's view of a pool's qgroup accounting, from sysfs. {@code available} is false when
     * it couldn't be read at all (non-Linux without the agent, an agent that predates the verb, a
     * pre-6.1 kernel with no {@code qgroups/} attributes, or quotas simply off) — in which case the
     * other fields are meaningless and callers behave as they did before this check existed.
     *
     * @param inconsistent the kernel's {@code inconsistent} flag: the counters are frozen and every
     *                     rfer/exclusive figure is untrustworthy until a rescan completes
     * @param mode {@code qgroup} (full accounting) or {@code squota} (simple quotas)
     * @param dropSubtreeThreshold the subtree depth above which a subvolume delete skips accounting
     *                     and marks the filesystem inconsistent (8 = never); -1 if not reported
     */
    public record QgroupStatus(boolean available, boolean enabled, boolean inconsistent,
                               String mode, int dropSubtreeThreshold) {

        public static final QgroupStatus UNAVAILABLE = new QgroupStatus(false, false, false, "", -1);

        /**
         * Whether accounting has been positively detected as broken. Only this state changes
         * behaviour — an unreadable status is treated as it always was (numbers taken at face value),
         * so an older agent or kernel degrades to the previous behaviour rather than to nothing.
         */
        public boolean untrusted() {
            return available && enabled && inconsistent;
        }
    }

    /**
     * Read the pool's qgroup accounting status. Never throws; unreadable → {@link QgroupStatus#UNAVAILABLE}.
     */
    public static QgroupStatus status(String poolName) {
        if (poolName == null || !isSafePoolName(poolName)) return QgroupStatus.UNAVAILABLE;
        try {
            if (Platform.isMacOS()) {
                return VmAgentClient.btrfsStatus(poolName).map(BtrfsUsage::parseStatus)
                        .orElse(QgroupStatus.UNAVAILABLE);
            }
            if (Platform.isLinux()) return BtrfsSysfs.status(POOL_MOUNT_PREFIX + poolName);
            return QgroupStatus.UNAVAILABLE;
        } catch (RuntimeException e) {
            return QgroupStatus.UNAVAILABLE;
        }
    }

    /**
     * Parse {@code key=value} status lines (the agent's {@code btrfs-status} reply; the same shape
     * {@link BtrfsSysfs} assembles from the sysfs attributes). No {@code enabled} key — including an
     * {@code error:} reply from an agent that doesn't know the verb — means unavailable. {@code mode}
     * and {@code drop_subtree_threshold} are optional (added to sysfs after the two flags).
     */
    public static QgroupStatus parseStatus(String text) {
        if (text == null) return QgroupStatus.UNAVAILABLE;
        Map<String, String> kv = new HashMap<>();
        for (var line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            kv.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        var enabled = kv.get("enabled");
        if (enabled == null) return QgroupStatus.UNAVAILABLE;
        int threshold = -1;
        try {
            threshold = Integer.parseInt(kv.getOrDefault("drop_subtree_threshold", "-1"));
        } catch (NumberFormatException ignored) {
            // unexpected attribute format — leave unknown
        }
        return new QgroupStatus(true, "1".equals(enabled), "1".equals(kv.get("inconsistent")),
                kv.getOrDefault("mode", ""), threshold);
    }

    /**
     * Detect-and-repair in one call: read the status and, if the accounting is inconsistent, start
     * a {@code btrfs quota rescan} (asynchronous — the kernel walks the extent tree in the background
     * and clears the flag when done). Throttled per process (see {@link #RESCAN_RETRY_INTERVAL}), so
     * it's safe to call on every TUI refresh. Returns the status <em>as read</em>, i.e. still
     * inconsistent when a repair was just triggered: callers must not trust the numbers yet.
     */
    public static QgroupStatus repairIfInconsistent(String poolName) {
        var status = status(poolName);
        if (status.untrusted()) {
            triggerRescanThrottled(poolName);
        } else if (status.available()) {
            // Consistent accounting means any earlier repair worked (or none was needed), so the
            // failed-attempt budget starts over. Without this the cap is a per-process lifetime
            // limit, and a long-lived TUI session doing more than MAX_RESCAN_TRIGGERS rebuild
            // cycles would silently stop repairing itself for the rest of the session.
            synchronized (RESCAN_LOCK) {
                rescanTriggers = 0;
            }
        }
        return status;
    }

    /**
     * Opportunistic detect-and-repair for hot paths that fire once per mutation — every subvolume
     * delete goes through {@code IncusClient.delete}, so deleting N instances in a loop
     * ({@code isx clean}, destroying several branches) would otherwise pay N status reads, and on
     * macOS each one is a round trip to the in-VM agent. Skips entirely if any opportunistic check
     * ran within {@link #CHECK_MIN_INTERVAL}, so a burst costs one check rather than N.
     *
     * <p>{@code poolNameSupplier} is only invoked when the check actually runs, so the caller's own
     * pool lookup (an Incus API call) is skipped too. Nothing is lost by skipping: the flag can only
     * have been set by the very deletes in this burst, the first check already caught that and
     * started the rescan, and the TUI's reload cadence plus the next build both re-check anyway.
     *
     * @return the status when a check ran, else empty
     */
    public static java.util.Optional<QgroupStatus> repairIfInconsistentThrottled(
            java.util.function.Supplier<String> poolNameSupplier) {
        synchronized (RESCAN_LOCK) {
            long now = System.nanoTime();
            if (lastOpportunisticCheckNanos != Long.MIN_VALUE
                    && now - lastOpportunisticCheckNanos < CHECK_MIN_INTERVAL.toNanos()) {
                return java.util.Optional.empty();
            }
            lastOpportunisticCheckNanos = now;
        }
        var pool = poolNameSupplier.get();
        if (pool == null) return java.util.Optional.empty();
        return java.util.Optional.of(repairIfInconsistent(pool));
    }

    /**
     * Poll the status until the accounting is consistent again or {@code maxWait} elapses. For the
     * one caller that needs a trustworthy read <em>now</em> (the build-time stamp): a rescan of a
     * developer-sized pool finishes in seconds, so a short bounded wait usually yields a correct
     * stamp, while a huge pool merely leaves the template unstamped for the TUI to backfill later.
     */
    public static QgroupStatus awaitConsistent(String poolName, Duration maxWait) {
        var deadline = System.nanoTime() + maxWait.toNanos();
        var status = status(poolName);
        while (status.untrusted() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return status;
            }
            status = status(poolName);
        }
        return status;
    }

    /**
     * Start a {@code btrfs quota rescan} on the pool, unthrottled (for {@code isx doctor}'s explicit
     * remediation). Returns true if the kernel accepted it <em>or</em> one is already running —
     * either way a rescan is in progress. Never throws.
     */
    public static boolean rescan(String poolName) {
        if (poolName == null || !isSafePoolName(poolName)) return false;
        try {
            if (Platform.isMacOS()) {
                var reply = VmAgentClient.btrfsRescan(poolName).orElse("");
                return "started".equals(reply) || "running".equals(reply);
            }
            if (!Platform.isLinux()) return false;
            var r = runBtrfsResult("quota", "rescan", POOL_MOUNT_PREFIX + poolName);
            if (r == null) return false;
            // btrfs-progs reports an in-flight rescan as an error (EINPROGRESS); that's still "running".
            return r.exit() == 0 || r.stderr().toLowerCase().contains("in progress");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void triggerRescanThrottled(String poolName) {
        synchronized (RESCAN_LOCK) {
            long now = System.nanoTime();
            if (rescanTriggers >= MAX_RESCAN_TRIGGERS) return;
            if (lastRescanTriggerNanos != Long.MIN_VALUE
                    && now - lastRescanTriggerNanos < RESCAN_RETRY_INTERVAL.toNanos()) return;
            lastRescanTriggerNanos = now;
            rescanTriggers++;
        }
        boolean started = rescan(poolName);
        dev.incusspawn.ClientLog.warn("btrfs qgroup accounting for pool '" + poolName
                + "' is inconsistent; " + (started ? "triggered quota rescan" : "could not trigger quota rescan"
                + " (run 'isx doctor')"));
    }

    /**
     * Referenced bytes per instance/template name for {@code poolName}, read <em>without</em> forcing
     * a filesystem sync — the cheap flavour, safe to call on a periodic sampling cadence. Never throws.
     */
    public static Map<String, Long> probe(String poolName) {
        return probe(poolName, false);
    }

    /**
     * Referenced bytes per instance/template name for {@code poolName}, or an empty map if the data
     * can't be obtained. Never throws — a failure is a fallback signal, not an error.
     *
     * <p>{@code sync} selects between two flavours of the read:
     * <ul>
     *   <li>{@code false} (default): read committed accounting as-is. Cheap; use for periodic
     *       sampling, where forcing a full-filesystem commit on every tick would be wasteful.</li>
     *   <li>{@code true}: pass {@code --sync} so btrfs commits the current transaction before
     *       reporting. Necessary when the accounting may be stale — e.g. right after a build, whose
     *       final writes are otherwise still uncommitted. Heavyweight (a whole-fs commit), so reserve
     *       it for the rare accuracy-critical read, not the sampling path.</li>
     * </ul>
     */
    public static Map<String, Long> probe(String poolName, boolean sync) {
        if (poolName == null || !isSafePoolName(poolName)) return Map.of();
        try {
            if (Platform.isMacOS()) {
                var resp = VmAgentClient.btrfsUsage(poolName, sync);
                if (resp.isEmpty()) return Map.of();
                var parts = resp.get().split(AGENT_SECTION_MARKER, 2);
                if (parts.length != 2) return Map.of();
                return parse(parts[0], parts[1]);
            }
            var mount = POOL_MOUNT_PREFIX + poolName;
            var qgroup = sync
                    ? runBtrfs("qgroup", "show", "-re", "--raw", "--sync", mount)
                    : runBtrfs("qgroup", "show", "-re", "--raw", mount);
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
        var r = runBtrfsResult(args);
        return r != null && r.exit() == 0 ? r.stdout() : null;
    }

    private record ProcResult(int exit, String stdout, String stderr) {}

    /**
     * Run {@code sudo -n btrfs <args>} on the host and capture both streams (the rescan trigger
     * needs stderr to tell "already in progress" from a real failure); null if it couldn't run.
     */
    private static ProcResult runBtrfsResult(String... args) {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("sudo");
        cmd.add("-n");
        cmd.add("btrfs");
        java.util.Collections.addAll(cmd, args);
        try {
            var pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            var proc = pb.start();
            // Drain stderr concurrently so a chatty command can't block on a full pipe.
            var errBytes = new java.util.concurrent.atomic.AtomicReference<byte[]>(new byte[0]);
            var errThread = Thread.ofVirtual().start(() -> {
                try { errBytes.set(proc.getErrorStream().readAllBytes()); } catch (IOException ignored) {}
            });
            var out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!proc.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return null;
            }
            errThread.join(1000);
            return new ProcResult(proc.exitValue(), out, new String(errBytes.get(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
