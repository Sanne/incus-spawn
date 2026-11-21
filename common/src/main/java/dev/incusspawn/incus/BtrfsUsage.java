package dev.incusspawn.incus;

import dev.incusspawn.Platform;
import dev.incusspawn.vm.VmAgentClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern POOL_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    /** Standard Incus on-disk layout: pools are mounted here on the host and inside the VM alike. */
    public static final String POOL_MOUNT_PREFIX = "/var/lib/incus/storage-pools/";

    /** Separates the two command outputs in the agent's {@code btrfs-usage} reply. */
    public static final String AGENT_SECTION_MARKER = "---ISX-SUBVOL---";
    private static final Pattern AGENT_SECTION = Pattern.compile(Pattern.quote(AGENT_SECTION_MARKER));

    private static final long PROBE_TIMEOUT_SECONDS = 8;

    /** How {@code btrfs qgroup show} paths the qgroup of a subvolume btrfs has not freed yet. */
    private static final String UNDER_DELETION = "<under deletion>";

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

        /**
         * Whether the pool has a threshold isx may set or has set: full accounting that reports one.
         * It reads 0 while another delete's drops are, or were left, untraced.
         */
        public boolean canSkipDrops() {
            return available && enabled && !"squota".equals(mode) && dropSubtreeThreshold >= 0;
        }

        /**
         * Whether a delete traces every shared extent, under full qgroups at a threshold other than
         * 0 (#2061). A kernel that reports no threshold (-1) predates the attribute and traces them all.
         */
        public boolean tracesDrops() {
            return available && enabled && !"squota".equals(mode) && dropSubtreeThreshold != SKIP_DROP_ACCOUNTING;
        }

        /** Whether a delete skips that tracing and leaves the accounting for a rescan. */
        public boolean skipsDropAccounting() {
            return available && enabled && !"squota".equals(mode) && dropSubtreeThreshold == SKIP_DROP_ACCOUNTING;
        }

        QgroupStatus withDropSubtreeThreshold(int threshold) {
            return new QgroupStatus(available, enabled, inconsistent, mode, threshold);
        }
    }

    /**
     * Read the pool's qgroup accounting status. Never throws; unreadable → {@link QgroupStatus#UNAVAILABLE}.
     */
    public static QgroupStatus status(String poolName) {
        return statusSource.apply(poolName);
    }

    /** What {@link #status} and {@link #fsid} read from, swappable by a test, which has no pool. */
    static volatile java.util.function.Function<String, QgroupStatus> statusSource = BtrfsUsage::readStatus;
    static volatile java.util.function.Function<String, java.util.Optional<String>> fsidSource = BtrfsUsage::readFsid;

    private static QgroupStatus readStatus(String poolName) {
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

    /** How long {@link #rescan} waits for a delete holding the pool before leaving the rescan to its settle. */
    static final java.time.Duration RESCAN_LOCK_WAIT = java.time.Duration.ofSeconds(3);

    /**
     * Start a {@code btrfs quota rescan} on the pool, unthrottled (for {@code isx doctor}'s explicit
     * remediation). Returns true if the kernel accepted it <em>or</em> one is already running —
     * either way a rescan is in progress. Never throws.
     */

    public static boolean rescan(String poolName) {
        if (poolName == null || !isSafePoolName(poolName)) return false;
        // A delete is between its threshold and its settle: its drops would cancel this rescan, and
        // its settle starts one, so none is scheduled here (#2435)
        // Waits a few seconds first, so doctor is told a rescan started when the holder is brief
        var lock = PoolQgroupLock.acquire(poolName, RESCAN_LOCK_WAIT, false);
        if (lock.isEmpty()) return true;
        try {
            return rescanNow(poolName);
        } finally {
            lock.get().release();
        }
    }

    /** {@link #rescan} for a caller that holds the pool's lock, a settle. */
    private static boolean rescanNow(String poolName) {
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

    /**
     * The {@code drop_subtree_threshold} isx gives a pool under full qgroup accounting (#2061): a
     * subvolume delete then skips tracing the extents it shared with its template and marks the
     * accounting inconsistent instead, for a rescan to rebuild in the background. At the kernel's
     * default of 3, {@code btrfs-cleaner} traces them inside the transaction a new snapshot must
     * commit, and a branch after a bulk delete waited seconds, for minutes (design/storage.md).
     */
    static final int SKIP_DROP_ACCOUNTING = 0;

    /** The {@code tee} the sudoers rule lets write {@link #dropThresholdPath}. */
    public static final String TEE = "/usr/bin/tee";

    /** How long a settle waits for {@code btrfs-cleaner} to free what was deleted. */
    private static final long SETTLE_TIMEOUT_SECONDS = 60;

    /** A btrfs filesystem UUID, as its {@code /sys/fs/btrfs/<fsid>} directory is named. */
    private static final Pattern FSID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** The sysfs file holding the drop threshold of the filesystem {@code fsid}. */
    public static String dropThresholdPath(String fsid) {
        return "/sys/fs/btrfs/" + fsid + "/qgroups/drop_subtree_threshold";
    }

    /** Whether {@code fsid} is a filesystem UUID, safe to put in a path and a sudoers rule. */
    public static boolean isFsid(String fsid) {
        return fsid != null && FSID.matcher(fsid).matches();
    }

    /**
     * The UUID of the btrfs filesystem holding {@code poolName} on this Linux host, read without
     * privilege from mountinfo and sysfs, or empty when it cannot be found. Never throws.
     */
    public static java.util.Optional<String> fsid(String poolName) {
        return fsidSource.apply(poolName);
    }

    private static java.util.Optional<String> readFsid(String poolName) {
        if (!Platform.isLinux() || !isSafePoolName(poolName)) return java.util.Optional.empty();
        try {
            return BtrfsSysfs.fsidDir(BtrfsSysfs.MOUNTINFO, BtrfsSysfs.SYSFS_BTRFS, POOL_MOUNT_PREFIX + poolName)
                    .map(dir -> dir.getFileName().toString()).filter(BtrfsUsage::isFsid);
        } catch (IOException | RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * What {@link #skipDropAccounting} did to a pool before its deletes.
     *
     * @param status the status after: what {@link #settleDeletes} needs to know whether to settle
     * @param restoreTo the threshold isx replaced, which the settle puts back, or -1 when isx changed none
     * @param lock the pool's lock this delete took, which its settle releases, or null
     */
    public record DropAccounting(QgroupStatus status, int restoreTo, PoolQgroupLock lock) {
        DropAccounting(QgroupStatus status, int restoreTo) {
            this(status, restoreTo, null);
        }

        DropAccounting under(PoolQgroupLock held) {
            return new DropAccounting(status, restoreTo, held);
        }

        public static final DropAccounting UNAVAILABLE = new DropAccounting(QgroupStatus.UNAVAILABLE, -1);
    }

    /**
     * Before a delete: give a pool under full qgroups {@link #SKIP_DROP_ACCOUNTING}, unless it has
     * it already. The kernel resets the threshold to 3 at every mount and every quota enable, and
     * Incus enables quotas lazily, so it is set here rather than once at boot. The settle puts the
     * old value back once the cleaner is done, so a delete isx does not make, a plain
     * {@code incus delete} or Incus pruning an image, is traced as before and leaves no accounting
     * inconsistent with nobody to settle it. A rescan still running, the previous delete's settle's
     * or one a TUI refresh started, is waited for first: a drop at threshold 0 marks the accounting
     * inconsistent, which cancels a running rescan, and a series of {@code isx destroy} that kept
     * cancelling each other's left the pool reading "rescan running" with no rescan, refusing every
     * new one, until it was mounted again (measured, design/storage.md). A rescan the wait does not
     * see end leaves the threshold as it is, so that delete is traced as before. Best-effort: without the
     * sudoers entry, or the agent's verb, the pool keeps its threshold and a delete costs what it
     * did before. Never throws.
     */
    public static DropAccounting skipDropAccounting(String poolName) {
        var status = status(poolName);
        // No attribute to set on a kernel that reports none. A pool that reads 0 is not turned away:
        // another delete's batch may hold it there, and its drops would cancel this delete's rescan,
        // so it waits for the lock and decides under it (#2435)
        if (!status.canSkipDrops()) return new DropAccounting(status, -1);
        // Nor on a Linux pool whose filesystem is not found: the write below would fail, so the
        // rescan wait would only delay the delete. Read once, for that and the write.
        var fsid = Platform.isLinux() ? fsid(poolName).orElse(null) : null;
        if (Platform.isLinux() && fsid == null) return withoutLock(status);
        // Held until the settle has started its rescan: another isx process dropping meanwhile would
        // cancel it (#2435). Without the lock, this delete writes no threshold and is not settled.
        var lock = PoolQgroupLock.acquire(poolName, PoolQgroupLock.waitFor(), true);
        if (lock.isEmpty()) return withoutLock(status);
        lock.get().keep();
        DropAccounting prepared = DropAccounting.UNAVAILABLE;
        try {
            prepared = setThresholdUnderLock(poolName, status, fsid).under(lock.get());
            return prepared;
        } finally {
            // A pool that traces drops, or whose write failed, has no settle to release the lock
            if (!prepared.status().skipsDropAccounting()) PoolQgroupLock.releaseHeld(poolName);
        }
    }

    /**
     * A delete that goes on without the pool's lock: never one the settle runs for, even when the
     * pool reads 0, since that 0 is another delete's, whose drops would cancel this one's rescan. It
     * gets the throttled repair instead, which leaves the rescan to the holder's settle.
     */
    private static DropAccounting withoutLock(QgroupStatus status) {
        return status.skipsDropAccounting() ? DropAccounting.UNAVAILABLE : new DropAccounting(status, -1);
    }

    private static DropAccounting setThresholdUnderLock(String poolName, QgroupStatus status, String fsid) {
        // Read again under the lock, every time: another process may have started a rescan, or put
        // the threshold back, since the read before it, and this delete must not cancel that rescan
        status = status(poolName);
        if (!status.canSkipDrops()) return new DropAccounting(status, -1);
        boolean set;
        try {
            // The flag stays set while a rescan runs, so a consistent pool has none to wait for.
            // One still running after the wait keeps the threshold: this delete is traced, as before.
            if (status.inconsistent() && Platform.isLinux()) {
                var waited = runBtrfsResult(SETTLE_TIMEOUT_SECONDS, "quota", "rescan", "-W", POOL_MOUNT_PREFIX + poolName);
                if (waited == null || waited.exit() != 0) return new DropAccounting(status, -1);
            }
            // Already 0 with nobody holding the lock: a killed isx left it. Nothing to write or restore,
            // and the settle rescans.
            if (status.skipsDropAccounting()) return new DropAccounting(status, -1);
            set = Platform.isMacOS()
                    ? VmAgentClient.btrfsSkipDropAccounting(poolName).map("ok"::equals).orElse(false)
                    : writeThresholdOf(fsid, SKIP_DROP_ACCOUNTING);
        } catch (RuntimeException e) {
            set = false;
        }
        return set ? new DropAccounting(status.withDropSubtreeThreshold(SKIP_DROP_ACCOUNTING), status.dropSubtreeThreshold())
                : new DropAccounting(status, -1);
    }

    /** Write {@code threshold} to the pool's {@code drop_subtree_threshold} through the sudoers rule's {@code tee} (Linux). */
    private static boolean writeThreshold(String poolName, int threshold) {
        return writeThresholdOf(fsid(poolName).orElse(null), threshold);
    }

    /** {@link #writeThreshold} on the filesystem {@code fsid}, already read. */
    private static boolean writeThresholdOf(String fsid, int threshold) {
        if (!isFsid(fsid) || threshold < 0) return false;
        var cmd = List.of("sudo", "-n", TEE, dropThresholdPath(fsid));
        dev.incusspawn.util.Privilege.check(cmd);
        var r = runProcess(new ProcessBuilder(cmd), PROBE_TIMEOUT_SECONDS,
                (threshold + "\n").getBytes(StandardCharsets.US_ASCII));
        return r != null && r.exit() == 0;
    }

    /**
     * Put back a threshold {@link #skipDropAccounting} replaced, alone, without a settle: isx is
     * exiting before its settle ran (IncusClient's shutdown hook). On macOS the agent's settle is
     * the one verb that writes it, and it returns at once. Never throws.
     */
    public static void restoreThreshold(String poolName, int threshold) {
        if (threshold < 0 || !isSafePoolName(poolName)) return;
        try {
            if (Platform.isMacOS()) VmAgentClient.btrfsSettle(poolName, threshold);
            else if (Platform.isLinux()) writeThreshold(poolName, threshold);
        } catch (RuntimeException e) {
            // Best-effort, at exit
        } finally {
            PoolQgroupLock.releaseHeld(poolName);
        }
    }

    /**
     * Whether this Linux host's sudoers rule lets isx set the pool's threshold without a password.
     * It writes back the value the pool has, which changes nothing: {@code sudo -n -l} would also
     * say yes for a rule that wants a password, such as an administrator's {@code %wheel} one.
     * False when the rule predates the entry ({@code isx init} rewrites it), or the threshold or the
     * filesystem cannot be read. A threshold of 0, which isx sets during its deletes, counts as
     * settable and is not written, and so does a pool a delete holds the lock of. Never throws.
     */
    public static boolean canSkipDropAccounting(String poolName) {
        if (!Platform.isLinux()) return false;
        // Under the pool's lock, or not at all: a delete holding it may have just set 0, and writing
        // back the value read before would undo it. A held lock means isx is using the rule now.
        var lock = PoolQgroupLock.acquire(poolName, java.time.Duration.ZERO, false);
        if (lock.isEmpty()) return true;
        try {
            int threshold = status(poolName).dropSubtreeThreshold();
            // 0 is what isx sets during its deletes, so the rule lets it; writing 0 back could
            // land after that delete's settle put the old value back, and leave 0 with nobody to restore it
            if (threshold == SKIP_DROP_ACCOUNTING) return true;
            return writeThreshold(poolName, threshold);
        } catch (RuntimeException e) {
            return false;
        } finally {
            lock.get().release();
        }
    }

    /**
     * After the deletes {@link #skipDropAccounting} prepared: have btrfs free them now and rebuild
     * the accounting they left inconsistent. While it is inconsistent btrfs enforces no qgroup limit,
     * so the root {@code size} of every instance is not held. Left alone, the cleaner frees a delete
     * only at the next commit, up to {@code commit=300} seconds later, and nothing in isx would
     * notice the flag it then sets. So this forces the commit and waits for the cleaner, which skips
     * the tracing and is done in a second or two. A rescan that overlapped the cleaner, such as the
     * previous delete's, clears the flag when it completes, and the commit that then removes a freed
     * subvolume's qgroup sets it again (measured: 20 {@code isx destroy} in a row ended inconsistent).
     * So it waits for any such rescan, commits again, and only then starts its own. Once the cleaner
     * is done it puts back the threshold isx replaced. On macOS the agent does the same in the VM,
     * without making the caller wait. Does nothing for a pool that still traces drops, whose
     * cleaner can take minutes. Never throws.
     */
    public static void settleDeletes(String poolName, DropAccounting before) {
        if (!before.status().skipsDropAccounting()) return;
        try {
            if (!isSafePoolName(poolName)) return;
            if (Platform.isMacOS()) {
                VmAgentClient.btrfsSettle(poolName, before.restoreTo());
                return;
            }
            if (!Platform.isLinux()) return;
            var mount = POOL_MOUNT_PREFIX + poolName;
            runBtrfsResult(PROBE_TIMEOUT_SECONDS, "qgroup", "show", "-re", "--raw", "--sync", mount);
            try {
                runBtrfsResult(SETTLE_TIMEOUT_SECONDS, "subvolume", "sync", mount);
            } finally {
                // After the cleaner, or once the wait gave up: a drop still to come is traced, slowly but consistently
                if (before.restoreTo() >= 0) writeThreshold(poolName, before.restoreTo());
            }
            runBtrfsResult(SETTLE_TIMEOUT_SECONDS, "quota", "rescan", "-W", mount);
            runBtrfsResult(PROBE_TIMEOUT_SECONDS, "qgroup", "show", "-re", "--raw", "--sync", mount);
            if (status(poolName).untrusted()) rescanNow(poolName);
        } catch (RuntimeException e) {
            // Best-effort: the deletes already succeeded, and the TUI, a build or doctor repair it later
        } finally {
            // The delete's own, held since the threshold was set; one that took none releases nothing
            if (before.lock() != null) before.lock().release();
        }
    }

    private static boolean throttleOpen() {
        synchronized (RESCAN_LOCK) {
            long now = System.nanoTime();
            if (rescanTriggers >= MAX_RESCAN_TRIGGERS) return false;
            return lastRescanTriggerNanos == Long.MIN_VALUE
                    || now - lastRescanTriggerNanos >= RESCAN_RETRY_INTERVAL.toNanos();
        }
    }

    private static void triggerRescanThrottled(String poolName) {
        // The throttle first: a refresh it turns away takes no lock and starts no thread
        if (!throttleOpen()) return;
        // A delete is settling the pool, and its settle starts the rescan: nothing is triggered, so
        // nothing is counted or claimed (#2435)
        var lock = PoolQgroupLock.acquire(poolName, java.time.Duration.ZERO, false);
        if (lock.isEmpty()) return;
        try {
            triggerRescanThrottledLocked(poolName);
        } finally {
            lock.get().release();
        }
    }

    private static void triggerRescanThrottledLocked(String poolName) {
        synchronized (RESCAN_LOCK) {
            long now = System.nanoTime();
            if (rescanTriggers >= MAX_RESCAN_TRIGGERS) return;
            if (lastRescanTriggerNanos != Long.MIN_VALUE
                    && now - lastRescanTriggerNanos < RESCAN_RETRY_INTERVAL.toNanos()) return;
            lastRescanTriggerNanos = now;
            rescanTriggers++;
        }
        boolean started = rescanNow(poolName);
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
                var sections = agentSections(poolName, sync);
                return sections == null ? Map.of() : parse(sections[0], sections[1], poolName);
            }
            var mount = POOL_MOUNT_PREFIX + poolName;
            var qgroup = sync
                    ? runBtrfs("qgroup", "show", "-re", "--raw", "--sync", mount)
                    : runBtrfs("qgroup", "show", "-re", "--raw", mount);
            var subvols = runBtrfs("subvolume", "list", mount);
            if (qgroup == null || subvols == null) return Map.of();
            return parse(qgroup, subvols, poolName);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * Raw {@code btrfs subvolume list} output for the pool, or null if it can't be read. Unlike
     * {@link #probe}, this does not depend on qgroups: it still answers with quotas off or
     * inconsistent, which is what {@link InstanceSubvolumes} needs to see every subvolume. An agent
     * whose listing failed answers with an empty section, which callers must not read as "no
     * subvolumes".
     */
    static String subvolumeList(String poolName) {
        if (poolName == null || !isSafePoolName(poolName)) return null;
        try {
            if (Platform.isMacOS()) {
                var sections = agentSections(poolName, false);
                return sections == null ? null : sections[1];
            }
            return runBtrfs("subvolume", "list", POOL_MOUNT_PREFIX + poolName);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The agent's {@code btrfs-usage} reply as {qgroup show, subvolume list}, or null. */
    private static String[] agentSections(String poolName, boolean sync) {
        var resp = VmAgentClient.btrfsUsage(poolName, sync);
        if (resp.isEmpty()) return null;
        var parts = AGENT_SECTION.split(resp.get(), 2);
        return parts.length == 2 ? parts : null;
    }

    /**
     * Join {@code btrfs qgroup show -re --raw} output (Qgroupid / Referenced / Exclusive columns)
     * with {@code btrfs subvolume list} output (ID … path …) to map each instance/template name to
     * its referenced bytes. Only the pool's top-level instance and VM subvolumes are considered, as
     * {@link InstanceSubvolumes#parse} finds them: snapshots, images, custom volumes and any
     * subvolumes nested inside a guest are skipped.
     */
    public static Map<String, Long> parse(String qgroupShowOutput, String subvolumeListOutput, String poolName) {
        Map<Long, Long> referencedBySubvolid = new HashMap<>();
        for (var line : qgroupShowOutput.split("\n")) {
            var fields = WHITESPACE.split(line.trim());
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
        InstanceSubvolumes.parseWithIds(subvolumeListOutput, poolName).forEach((ref, subvolid) -> {
            var referenced = referencedBySubvolid.get(subvolid);
            if (referenced != null) byName.put(ref.name(), referenced);
        });
        return byName;
    }

    /**
     * Deleted subvolumes that btrfs has not freed yet (#2061). A subvolume delete returns at once,
     * and the {@code btrfs-cleaner} thread drops its tree in the background. Under full qgroup
     * accounting it traces every extent the subvolume shared with its template, and a new snapshot
     * waits for that work: measured, {@code incus copy} of a 2.7 GiB instance went from 0.15 s to
     * 5 s for each of the 20 deleted copies, one after the other (design/storage.md).
     *
     * @param subvolumes how many are still waiting
     * @param referencedBytes the referenced bytes of those, as their qgroups last counted them
     */
    public record PendingCleanup(int subvolumes, long referencedBytes) {
        public static final PendingCleanup NONE = new PendingCleanup(0, 0);
    }

    /**
     * The pool's {@link PendingCleanup}, or empty when it cannot be read (no sudoers rule, an
     * unreachable agent). Reads the same {@code qgroup show} as {@link #probe}, under the same
     * sudoers rule and agent verb, so it needs no new privilege. Never throws.
     */
    public static java.util.Optional<PendingCleanup> pendingCleanup(String poolName) {
        if (poolName == null || !isSafePoolName(poolName)) return java.util.Optional.empty();
        try {
            String qgroup;
            if (Platform.isMacOS()) {
                var sections = agentSections(poolName, false);
                qgroup = sections == null ? null : sections[0];
            } else {
                qgroup = runBtrfs("qgroup", "show", "-re", "--raw", POOL_MOUNT_PREFIX + poolName);
            }
            return qgroup == null ? java.util.Optional.empty() : java.util.Optional.of(parsePendingCleanup(qgroup));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Count the level-0 qgroups {@code btrfs qgroup show} paths as {@code <under deletion>}: their
     * subvolume is deleted and its tree not yet freed. Once the cleaner has freed it, the qgroup
     * stays until a later commit with its referenced bytes unchanged, and btrfs-progs paths it as
     * {@code <stale>}, so neither a missing subvolume nor a non-zero count tells the two apart; the
     * label does. A btrfs-progs too old to print it reports no backlog.
     */
    static PendingCleanup parsePendingCleanup(String qgroupShowOutput) {
        int count = 0;
        long bytes = 0;
        for (var line : qgroupShowOutput.split("\n")) {
            var trimmed = line.trim();
            if (!trimmed.startsWith("0/") || !trimmed.endsWith(UNDER_DELETION)) continue;
            var fields = WHITESPACE.split(trimmed);
            try {
                bytes += Long.parseLong(fields[1]);
                count++;
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
                // unexpected format: skip
            }
        }
        return count == 0 ? PendingCleanup.NONE : new PendingCleanup(count, bytes);
    }

    /**
     * Pool names come from Incus, but they're interpolated into a path, so keep them boring. The
     * single Java source of truth for this check (the {@code isx-agent} shell script keeps its own
     * copy on purpose — a separate trust boundary in another language).
     */
    public static boolean isSafePoolName(String poolName) {
        return poolName != null && POOL_NAME.matcher(poolName).matches();
    }

    /** Run {@code sudo -n btrfs <args>} on the host; returns stdout, or null on any failure. */
    private static String runBtrfs(String... args) {
        var r = runBtrfsResult(args);
        return r != null && r.exit() == 0 ? r.stdout() : null;
    }

    /** Package-private so the timeout behaviour can be tested without invoking sudo/btrfs. */
    record ProcResult(int exit, String stdout, String stderr) {}

    /** A stream being consumed on its own thread, so no read can outlive the caller's timeout. */
    private record Drain(Thread thread, java.util.concurrent.atomic.AtomicReference<byte[]> bytes) {
        /** At most a second: a grandchild still holding the pipe, such as the btrfs sudo forked, never blocks the caller. */
        void join() throws InterruptedException {
            thread.join(1000);
        }

        String text() {
            return new String(bytes.get(), StandardCharsets.UTF_8);
        }
    }

    private static Drain drain(java.io.InputStream stream) {
        var bytes = new java.util.concurrent.atomic.AtomicReference<byte[]>(new byte[0]);
        var thread = Thread.ofVirtual().start(() -> {
            try (stream) {
                bytes.set(stream.readAllBytes());
            } catch (IOException ignored) {
                // Killed process / closed pipe — whatever arrived before that is enough.
            }
        });
        return new Drain(thread, bytes);
    }

    /**
     * Run {@code sudo -n btrfs <args>} on the host and capture both streams (the rescan trigger
     * needs stderr to tell "already in progress" from a real failure); null if it couldn't run.
     */
    private static ProcResult runBtrfsResult(String... args) {
        return runBtrfsResult(PROBE_TIMEOUT_SECONDS, args);
    }

    private static ProcResult runBtrfsResult(long timeoutSeconds, String... args) {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("sudo");
        cmd.add("-n");
        cmd.add("btrfs");
        java.util.Collections.addAll(cmd, args);
        dev.incusspawn.util.Privilege.check(cmd);
        var pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        return runProcess(pb, timeoutSeconds);
    }

    /**
     * Run a process, capture both streams, and give up after {@code timeoutSeconds}. Package-private
     * (rather than inlined above) so the timeout is testable without shelling out to sudo/btrfs —
     * this is the path that must never block the TUI, so the bound is worth pinning down in a test.
     */
    static ProcResult runProcess(ProcessBuilder pb, long timeoutSeconds) {
        return runProcess(pb, timeoutSeconds, null);
    }

    /** {@link #runProcess(ProcessBuilder, long)}, writing {@code input} to the process's stdin first, when given. */
    static ProcResult runProcess(ProcessBuilder pb, long timeoutSeconds, byte[] input) {
        try {
            var proc = pb.start();
            try (var stdin = proc.getOutputStream()) {
                if (input != null) stdin.write(input);
            } catch (IOException ignored) {
                // A process that exited before reading: its exit status says what happened
            }
            // Drain BOTH streams on their own threads. readAllBytes() blocks until EOF, so reading
            // either one inline would outlast the bounded wait below whenever the child hangs with
            // its pipe still open — the timeout would never be reached and the caller would block
            // indefinitely. That caller is now the TUI reload and the post-delete repair trigger,
            // not just build-time stamping, so an unbounded wait here is a UI hang.
            var out = drain(proc.getInputStream());
            var err = drain(proc.getErrorStream());
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                // Kills sudo, not the root btrfs it forked, which can keep both pipes open: the
                // drain joins are bounded (Drain.join), and the virtual drain threads end with it
                proc.destroyForcibly();
                out.join();
                err.join();
                return null;
            }
            out.join();
            err.join();
            return new ProcResult(proc.exitValue(), out.text(), err.text());
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
