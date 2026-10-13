package dev.incusspawn.config;

import java.time.Duration;
import java.util.Map;

/** Budget for immutable remote-derived Git snapshots. Zero disables retention. */
public record GitCacheConfig(long snapshotBytes, Duration maxAge) {
    public static final GitCacheConfig DEFAULT = new GitCacheConfig(1024L << 20, Duration.ofDays(7));

    static GitCacheConfig of(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return DEFAULT;
        return new GitCacheConfig(mib(map, "snapshot-max-mib", DEFAULT.snapshotBytes),
                Duration.ofDays(number(map, "max-age-days", DEFAULT.maxAge.toDays(), 36500)));
    }

    private static long mib(Map<?, ?> map, String key, long fallback) {
        return number(map, key, fallback >> 20, Long.MAX_VALUE >> 20) << 20;
    }

    private static long number(Map<?, ?> map, String key, long fallback, long max) {
        try {
            long value = Long.parseLong(String.valueOf(map.get(key)));
            return value >= 0 && value <= max ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
