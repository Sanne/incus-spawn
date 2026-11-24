package dev.incusspawn.incus;

import dev.incusspawn.Platform;
import dev.incusspawn.util.BuildOutput;

import java.util.Locale;

/**
 * Keeps isx from filling the disk its pool lives on (#2555).
 * <p>
 * A full pool is worse than a failed write: on macOS the pool shares the VM's data disk with
 * Incus's own database, so Incus stops, and nothing can be deleted through it any more. So what
 * writes to the pool is checked before it starts: a branch is refused once the pool is
 * {@linkplain IncusClient.PoolUsage#isExhausted() exhausted}, a build, which writes gigabytes,
 * once it is exhausted or {@linkplain IncusClient.PoolUsage#isCritical() critical}, and a branch is only
 * warned at critical. A pool whose usage cannot be read is let through: Incus decides.
 */
public final class PoolSpace {

    private static final double GIB = 1024.0 * 1024 * 1024;

    private PoolSpace() {}

    /** What is about to write to the pool. */
    public enum Need { BRANCH, BUILD }

    /**
     * Reads {@code pool}'s usage, warns when it is short, and returns why {@code need} must not
     * start, or null. One request.
     */
    public static String check(IncusClient incus, String pool, Need need) {
        if (pool == null) return null;
        IncusClient.PoolUsage usage;
        try {
            usage = incus.getPoolUsageBytes(pool);
        } catch (RuntimeException e) {
            return null;
        }
        var macOS = Platform.isMacOS();
        // Asked only for a pool already short, so a branch with room pays no second request
        return check(usage, pool, need, macOS, () -> macOS || sharesIncusDisk(incus, pool));
    }

    /**
     * {@link #check(IncusClient, String, Need)} on a usage already read. {@code stopsIncus} says
     * whether filling this pool fills the disk Incus keeps its database on; it is asked only when
     * the pool is short.
     */
    static String check(IncusClient.PoolUsage usage, String pool, Need need, boolean macOS,
                        java.util.function.BooleanSupplier stopsIncus) {
        if (usage == null) return null;
        // A build is refused wherever a branch is, and also once the pool is critical
        var refused = usage.isExhausted() || (need == Need.BUILD && usage.isCritical());
        if (!refused && !usage.isCritical()) return null;
        var consequence = stopsIncus.getAsBoolean() ? "a full disk stops Incus" : null;
        if (refused) {
            // Only the macOS VM's deletes were measured to show minutes later (#2555); a Linux host
            // commits every 30 s by default
            return "the storage pool '" + pool + "' has " + free(usage) + ", too little to "
                    + (need == Need.BUILD ? "build in" : "branch into")
                    + (consequence != null ? ", and " + consequence : "") + ". " + remedy(macOS) + "."
                    + (macOS ? " The space a delete frees can take up to about five minutes to show as free." : "");
        }
        BuildOutput.warn("The storage pool '" + pool + "' has only " + free(usage) + ".",
                remedy(macOS) + (consequence != null ? ", before it fills: " + consequence + "." : ", before it fills."));
        return null;
    }

    /**
     * Whether a Linux pool shares its disk with Incus's database: a subvolume pool is on the host's
     * own filesystem, which holds {@code /var/lib/incus}, and only a loop-file pool keeps the two
     * apart. One pool listing; a pool that cannot be read is taken to share it.
     */
    private static boolean sharesIncusDisk(IncusClient incus, String pool) {
        try {
            var info = incus.listPoolInfo().get(pool);
            return info == null || !isLoopFile(info.config());
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** A pool backed by a loop file, which Incus names {@code disks/<pool>.img} for every driver. */
    public static boolean isLoopFile(java.util.Map<String, String> config) {
        // A subvolume or device source never ends so
        var source = config.getOrDefault("source", "");
        return source.startsWith("/") && source.endsWith(".img");
    }

    /** What frees or adds space, as an instruction. */
    public static String remedy(boolean macOS) {
        return "Free space with 'isx destroy <name>' and 'isx clean pool'"
                + (macOS ? ", or grow the VM disk with 'isx vm resize <size>'" : "");
    }

    private static String free(IncusClient.PoolUsage usage) {
        return String.format(Locale.ROOT, "%.1f GiB free (%d%% full)", usage.freeBytes() / GIB, usage.percent());
    }
}
