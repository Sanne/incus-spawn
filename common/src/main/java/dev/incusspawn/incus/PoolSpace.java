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
        return check(usage, pool, need, Platform.isMacOS());
    }

    /** {@link #check(IncusClient, String, Need)} on a usage already read. */
    static String check(IncusClient.PoolUsage usage, String pool, Need need, boolean macOS) {
        if (usage == null) return null;
        // A build is refused wherever a branch is, and also once the pool is critical
        var refused = usage.isExhausted() || (need == Need.BUILD && usage.isCritical());
        if (refused) {
            return "the storage pool '" + pool + "' has " + free(usage) + ", too little to "
                    + (need == Need.BUILD ? "build in" : "branch into")
                    + ", and a full disk stops Incus. " + remedy(macOS) + "."
                    // A delete's space shows once btrfs commits it, minutes later on an older appliance (#2555)
                    + " The space a delete frees can take up to about five minutes to show as free.";
        }
        if (usage.isCritical()) {
            BuildOutput.warn("The storage pool '" + pool + "' has only " + free(usage) + ".",
                    remedy(macOS) + ", before it fills: a full disk stops Incus.");
        }
        return null;
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
