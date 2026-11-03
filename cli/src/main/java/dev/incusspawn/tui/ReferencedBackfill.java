package dev.incusspawn.tui;

import dev.incusspawn.lifecycle.TemplateLock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which live btrfs read fills the TUI's unstamped templates, and which of them it saves as their
 * {@link dev.incusspawn.incus.Metadata#DISK_REFERENCED} stamp. A template's first backfill is
 * measured as a build would measure it and saved, so that costs it once. A gap that measure could
 * not fill is read again only with the light, non-sync flavour, and kept in memory.
 *
 * <p>The save is held to a build's conditions. Each template is held in place
 * ({@link TemplateLock#tryReading}), so no swap puts another instance under its name between the
 * read and the stamp. The accounting is checked consistent just before and after the read, not
 * taken from the reload's status, which may predate the swap that flagged it. And the read forces
 * a filesystem commit, so the stamp counts every write.
 *
 * <p>It runs on the TUI's UI thread, so it never waits for a template's lock: a template a swap
 * holds right now is left out, its gap kept for a later reload.
 */
final class ReferencedBackfill {

    /** What the backfill reads and writes: the pool's accounting, the templates' locks and stamps. */
    interface Pool {
        /** Whether the accounting is untrusted, starting its repair when it is. */
        boolean repairIfInconsistent();

        /** Whether the accounting is untrusted now. */
        boolean untrusted();

        /** Every subvolume's referenced size, by instance name; {@code sync} forces a commit first. */
        Map<String, Long> probe(boolean sync);

        /** Holds {@code template} in place, or empty when a swap holds it right now. */
        Optional<TemplateLock.Held> tryHold(String template);

        /** Saves {@code referenced} as the template's stamp; whether it was written. */
        boolean stamp(String template, long referenced);
    }

    // Unstamped templates already measured, or tried, with a forced commit, so a gap it cannot
    // fill costs that once, not on every reload. Kept to the current gaps.
    private final Set<String> measured = new HashSet<>();

    /**
     * @param unstamped the built templates with no stamp
     * @return the live sizes to fill them from, by name; empty to leave the gaps as they are
     */
    Map<String, Long> fill(List<String> unstamped, Pool pool) {
        measured.retainAll(unstamped);
        if (unstamped.isEmpty()) return Map.of();
        var held = new ArrayList<TemplateLock.Held>();
        var busy = new HashSet<String>();
        var toMeasure = new ArrayList<String>();
        try {
            for (var name : unstamped) {
                if (measured.contains(name)) continue;
                var hold = pool.tryHold(name);
                if (hold.isEmpty()) {
                    busy.add(name);
                    continue;
                }
                held.add(hold.get());
                toMeasure.add(name);
            }
            // Every gap held by a swap: nothing a read could fill
            if (busy.size() == unstamped.size()) return Map.of();
            var live = toMeasure.isEmpty() ? pool.probe(false) : measureAndStamp(toMeasure, pool);
            if (busy.isEmpty() || live.isEmpty()) return live;
            // What a swap holds may be the old template or nothing at all: leave its gap
            var settled = new HashMap<>(live);
            settled.keySet().removeAll(busy);
            return settled;
        } finally {
            held.forEach(TemplateLock.Held::close);
        }
    }

    /** One read with a commit that also stamps {@code names}, all held in place by the caller. */
    private Map<String, Long> measureAndStamp(List<String> names, Pool pool) {
        if (pool.repairIfInconsistent()) return Map.of();
        // Before the read: one that comes back empty is not retried with a commit either
        measured.addAll(names);
        var live = pool.probe(true);
        if (live.isEmpty()) return live;
        if (pool.untrusted()) {
            measured.removeAll(names);
            return Map.of();
        }
        for (var name : names) {
            // A rebuild in this session leaves a new gap under the same name: measure it afresh
            if (pool.stamp(name, live.getOrDefault(name, -1L))) measured.remove(name);
        }
        return live;
    }
}
