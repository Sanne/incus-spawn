package dev.incusspawn.incus;

import dev.incusspawn.incus.IncusClient.PoolUsage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Whether {@code isx branch} names {@code isx overview} for a filling pool (#2563), while there is
 * still room to choose what to destroy.
 * <p>
 * It shows from the {@linkplain PoolUsage#isHint() hint level} up, at most once per
 * {@link #QUIET}, and again at once when the level rises. The stamp holds when it last showed and
 * at which level; one that cannot be read or written means the hint shows again, never that it
 * stays silent for good.
 */
public final class DiskHint {

    /** How long the hint stays quiet after it showed, unless the level rises. */
    static final Duration QUIET = Duration.ofHours(24);

    private static final double GIB = 1024.0 * 1024 * 1024;

    private DiskHint() {}

    /** Whether the hint is to show now for {@code usage}, given the stamp at {@code stamp}. */
    public static boolean due(PoolUsage usage, boolean enabled, Path stamp, Instant now) {
        if (!enabled || usage == null) return false;
        var level = usage.level();
        if (level.compareTo(PoolUsage.Level.HINT) < 0) return false;
        String[] last;
        try {
            last = Files.readString(stamp, StandardCharsets.UTF_8).trim().split(" ");
        } catch (IOException | RuntimeException e) {
            return true;
        }
        try {
            var shownAt = Instant.ofEpochSecond(Long.parseLong(last[0]));
            var shownLevel = PoolUsage.Level.valueOf(last[1]);
            if (level.compareTo(shownLevel) > 0) return true;
            // A stamp from the future (a clock set back) does not silence the hint
            return shownAt.isAfter(now) || !now.isBefore(shownAt.plus(QUIET));
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Records that the hint showed at {@code level}; a failed write only means it shows again. */
    public static void shown(Path stamp, PoolUsage.Level level, Instant now) {
        try {
            Files.createDirectories(stamp.getParent());
            Files.writeString(stamp, now.getEpochSecond() + " " + level.name() + "\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // Shown again next time, which is the safe side
        }
    }

    /**
     * The hint's lines: what the listing and the pool read hold, and where to choose. It calls no
     * instance forgotten or unused, since idle time does not say whether a box is still needed.
     */
    public static List<String> lines(PoolUsage usage, int instances, int stopped) {
        var count = instances == 1 ? "1 instance" : instances + " instances";
        var halted = stopped == 0 ? "" : instances == 1 ? ", which is stopped" : ", " + stopped + " of them stopped";
        return List.of(
                String.format(Locale.ROOT, "The storage pool is %d%% full (%.1f GiB free), with %s%s.",
                        usage.percent(), usage.freeBytes() / GIB, count, halted),
                "'isx overview', or the Work tab in 'isx', shows what each one did and what destroying it would lose.");
    }
}
