package dev.incusspawn.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code artifact-cache:} in config.yaml: how long the proxy trusts a cached
 * Maven/Gradle artifact's last confirmation with upstream. Durations are written
 * {@code 30m}, {@code 2h}, {@code 7d} (or {@code 0}); a blank or unreadable value
 * means the proxy's default.
 * <p>
 * Read from the raw YAML value rather than bound by Jackson: a mistyped section
 * ({@code artifact-cache: 0}) must not fail the whole config.yaml, which would
 * leave the proxy without any credentials. Not a credential namespace, so not a
 * nested {@link SpawnConfig} class.
 *
 * @param fresh      served with no check for this long after a confirmation; null when unset
 * @param maxStale   until then, served at once and confirmed again in the background; null when unset
 * @param wellFormed false when the section is there but is not a mapping
 */
public record ArtifactCacheConfig(String fresh, String maxStale, boolean wellFormed) {

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*([smhd])");

    /** The section as written, or null when config.yaml has none. */
    static ArtifactCacheConfig of(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> map)) return new ArtifactCacheConfig(null, null, false);
        return new ArtifactCacheConfig(scalar(map.get("fresh")), scalar(map.get("max-stale")), true);
    }

    // A YAML number (fresh: 0) arrives as an Integer
    private static String scalar(Object value) {
        return value instanceof String || value instanceof Number ? String.valueOf(value) : null;
    }

    /** {@code 0} or a number with one unit (s, m, h, d); null when blank or unreadable. */
    public static Duration parseDuration(String value) {
        if (value == null || value.isBlank()) return null;
        var v = value.strip().toLowerCase(Locale.ROOT);
        if (v.equals("0")) return Duration.ZERO;
        var m = DURATION.matcher(v);
        if (!m.matches()) return null;
        try {
            long n = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                default -> Duration.ofDays(n);
            };
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
