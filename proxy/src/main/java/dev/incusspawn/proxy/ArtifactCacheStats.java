package dev.incusspawn.proxy;

/**
 * Counts Maven/Gradle cache hits between two log lines. A build asks for thousands
 * of artifacts, and a line per hit, written on the event loop, cost more than
 * serving a hit on trust does; the proxy logs a summary instead, at most every
 * {@link #INTERVAL_SECONDS} and never while idle. Evictions, errors and newly
 * cached artifacts are still logged one by one.
 */
final class ArtifactCacheStats {

    static final int INTERVAL_SECONDS = 5;

    enum Hit {
        /** Last confirmation still fresh: served with no upstream request. */
        TRUSTED,
        /** Aging confirmation: served, and confirmed again in the background. */
        TRUSTED_CHECKING,
        /** Confirmed with upstream before it was served. */
        CONFIRMED,
        /** Upstream unreachable, so served unconfirmed. */
        UNCONFIRMED,
        /** Imported from the host's {@code ~/.m2} once upstream confirmed it. */
        HOST_COPY
    }

    // Counts and bytes change together, so a hit is never split across two summaries.
    // One uncontended monitor per hit is noise next to the file I/O that serves it.
    private final long[] counts = new long[Hit.values().length];
    private long bytes;

    synchronized void record(Hit hit, long size) {
        counts[hit.ordinal()]++;
        if (size > 0) bytes += size;
    }

    /** Hits counted since the last summary. */
    synchronized long pending() {
        long total = 0;
        for (var c : counts) total += c;
        return total;
    }

    /** The summary of the hits since the last call, resetting the counts, or null when there were none. */
    String drain() {
        long[] n;
        long total = 0;
        long served;
        synchronized (this) {
            n = counts.clone();
            java.util.Arrays.fill(counts, 0);
            served = bytes;
            bytes = 0;
        }
        for (var c : n) total += c;
        if (total == 0) return null;
        var line = new StringBuilder("Maven/Gradle cache: ").append(total)
                .append(total == 1 ? " hit, " : " hits, ").append(MitmProxy.formatSize(served)).append(" (");
        var sep = "";
        for (var hit : Hit.values()) {
            if (n[hit.ordinal()] == 0) continue;
            line.append(sep).append(n[hit.ordinal()]).append(' ').append(label(hit));
            sep = ", ";
        }
        return line.append(")").toString();
    }

    private static String label(Hit hit) {
        return switch (hit) {
            case TRUSTED -> "served on a fresh confirmation";
            case TRUSTED_CHECKING -> "served while confirming again";
            case CONFIRMED -> "confirmed first";
            case UNCONFIRMED -> "unconfirmed, upstream unreachable";
            case HOST_COPY -> "from ~/.m2";
        };
    }
}
