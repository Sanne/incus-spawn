package dev.incusspawn.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A memory size as people write it: {@code 768MiB}, {@code 4GB}, {@code 2g}, as Incus takes it
 * for {@code limits.memory} and the JVM for {@code -Xmx}.
 *
 * <p>A unit ending in {@code iB} and a single letter ({@code k}, {@code m}, {@code g},
 * {@code t}, the JVM's) count in 1024s; {@code kB}, {@code MB}, {@code GB} and {@code TB} in
 * 1000s, as Incus counts them. A bare number is bytes.
 */
public final class MemorySize {

    private static final Pattern SIZE = Pattern.compile("([0-9]+)\\s*([A-Za-z]*)");

    public static final long MIB = 1024L * 1024;
    public static final long GIB = 1024 * MIB;

    private MemorySize() {}

    /** The bytes {@code text} names, or -1 when it is not a size this class reads. */
    public static long parse(String text) {
        if (text == null) return -1;
        var m = SIZE.matcher(text.strip());
        if (!m.matches()) return -1;
        long multiplier = switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "", "b" -> 1;
            case "k", "kib" -> 1024;
            case "m", "mib" -> MIB;
            case "g", "gib" -> GIB;
            case "t", "tib" -> 1024 * GIB;
            case "kb" -> 1000;
            case "mb" -> 1000L * 1000;
            case "gb" -> 1000L * 1000 * 1000;
            case "tb" -> 1000L * 1000 * 1000 * 1000;
            default -> -1;
        };
        if (multiplier < 0) return -1;
        try {
            return Math.multiplyExact(Long.parseLong(m.group(1)), multiplier);
        } catch (ArithmeticException | NumberFormatException tooLarge) {
            return -1;
        }
    }

    /**
     * The bytes an Incus {@code limits.memory} value gives an instance on a host with
     * {@code hostBytes} of RAM, or -1 when it cannot be read: a size, or a percentage of the host.
     */
    public static long ofLimit(String limit, long hostBytes) {
        if (limit == null) return -1;
        var text = limit.strip();
        if (text.endsWith("%")) {
            if (hostBytes <= 0) return -1;
            try {
                var percent = Integer.parseInt(text.substring(0, text.length() - 1).strip());
                return percent < 0 ? -1 : (long) (hostBytes * (percent / 100.0));
            } catch (NumberFormatException notAPercentage) {
                return -1;
            }
        }
        return parse(text);
    }

    /** {@code bytes} for a person: {@code 2.5 GiB}, {@code 768 MiB}. */
    public static String shown(long bytes) {
        if (bytes >= GIB) {
            var tenths = Math.round(bytes * 10.0 / GIB);
            return tenths % 10 == 0 ? tenths / 10 + " GiB" : tenths / 10 + "." + tenths % 10 + " GiB";
        }
        return Math.max(1, Math.round(bytes / (double) MIB)) + " MiB";
    }

    /** {@code bytes} rounded up to a whole GiB, as a value for {@code --memory} and {@code limits.memory}. */
    public static String wholeGib(long bytes) {
        return (bytes + GIB - 1) / GIB + "GiB";
    }
}
