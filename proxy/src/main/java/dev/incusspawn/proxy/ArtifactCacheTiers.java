package dev.incusspawn.proxy;

import dev.incusspawn.config.ArtifactCacheConfig;
import dev.incusspawn.config.SpawnConfig;

import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

/**
 * How long a cached Maven/Gradle artifact's last confirmation with upstream is
 * trusted ({@code artifact-cache:} in config.yaml). Younger than {@link #fresh}
 * it is served with no check; younger than {@link #maxStale} it is served at once
 * and confirmed again in the background; older, it is confirmed before it is
 * served. Both zero is "confirm every hit".
 */
record ArtifactCacheTiers(Duration fresh, Duration maxStale) {

    static final ArtifactCacheTiers DEFAULT = new ArtifactCacheTiers(Duration.ofHours(2), Duration.ofDays(7));
    static final ArtifactCacheTiers CONFIRM_EVERY_HIT = new ArtifactCacheTiers(Duration.ZERO, Duration.ZERO);

    enum Tier { FRESH, STALE, EXPIRED }

    ArtifactCacheTiers {
        // A max-stale below fresh leaves no background tier, rather than an inverted one
        if (maxStale.compareTo(fresh) < 0) maxStale = fresh;
    }

    static ArtifactCacheTiers from(SpawnConfig config) {
        var section = config.artifactCache();
        if (section == null) return DEFAULT;
        if (!section.wellFormed()) {
            ProxyLog.warn("Ignoring artifact-cache: in config.yaml (expected fresh: and max-stale: under it);"
                    + " using the defaults");
            return DEFAULT;
        }
        return new ArtifactCacheTiers(
                duration("fresh", section.fresh(), DEFAULT.fresh),
                duration("max-stale", section.maxStale(), DEFAULT.maxStale));
    }

    private static Duration duration(String key, String value, Duration fallback) {
        if (value == null || value.isBlank()) return fallback;
        var parsed = ArtifactCacheConfig.parseDuration(value);
        if (parsed != null) return parsed;
        ProxyLog.warn("Ignoring artifact-cache." + key + " '" + value
                + "' (expected e.g. 30m, 2h, 7d or 0); using " + describe(fallback));
        return fallback;
    }

    /**
     * The tier of a copy last confirmed at {@code confirmedAt}, or EXPIRED when that
     * is unknown. A time in the future (a clock stepped back) is not trusted either.
     */
    Tier tierOf(FileTime confirmedAt, Instant now) {
        if (confirmedAt == null) return Tier.EXPIRED;
        var age = Duration.between(confirmedAt.toInstant(), now);
        if (age.isNegative()) return Tier.EXPIRED;
        if (age.compareTo(fresh) < 0) return Tier.FRESH;
        if (age.compareTo(maxStale) < 0) return Tier.STALE;
        return Tier.EXPIRED;
    }

    /** A duration in whole units of the largest one it has two of: {@code 45s}, {@code 90m}, {@code 7d}. */
    static String describe(Duration d) {
        long s = d.toSeconds();
        if (s < 120) return s + "s";
        if (s < 7_200) return s / 60 + "m";
        if (s < 172_800) return s / 3_600 + "h";
        return s / 86_400 + "d";
    }

    @Override
    public String toString() {
        return "served unchecked for " + describe(fresh) + ", checked in the background up to "
                + describe(maxStale) + ", before serving after that";
    }
}
