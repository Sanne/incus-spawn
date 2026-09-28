package dev.incusspawn.mcp;

import dev.incusspawn.incus.Metadata;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Instances whose session is gone. An orphan is quarantined, never destroyed on sight: a
 * coordinating agent that restarts finds its workers where it left them and adopts them. Once
 * {@code mcp.orphan-grace-hours} have passed since it was orphaned, the next session to start
 * destroys it -- unless a person is working in it.
 *
 * <p>Only this host user's instances are considered, and only ones that were never kept and are
 * not in the middle of another operation. A session is dead when its process no longer exists,
 * or a different process now has its pid.
 */
final class Orphans {

    private Orphans() {}

    /** How another session's instance stands, as {@code list_instances} shows it. */
    record Other(String name, Map<String, String> config, boolean orphaned) {}

    /**
     * This user's MCP instances held by other sessions, alive or dead, from one listing. Kept
     * instances are the user's and not listed; an unparseable session stamp counts as held.
     */
    static Map<String, Other> othersOf(Map<String, Map<String, String>> mcpInstances, String owner,
                                       SessionId self, Predicate<SessionId> alive) {
        var result = new LinkedHashMap<String, Other>();
        mcpInstances.forEach((name, config) -> {
            if (!owner.equals(config.get(Metadata.MCP_OWNER)) || config.containsKey(Metadata.MCP_KEPT)) return;
            var session = SessionId.parse(config.get(Metadata.MCP_SESSION));
            if (session.isPresent() && session.get().equals(self)) return;
            result.put(name, new Other(name, config, session.isPresent() && !alive.test(session.get())));
        });
        return result;
    }

    /** When the instance became an orphan, or null if nobody has stamped it yet. */
    static Instant orphanedSince(Map<String, String> config) {
        var value = config.get(Metadata.MCP_ORPHANED);
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Start the grace period of orphans nobody has stamped yet (their session was killed), and
     * destroy those whose grace period is over and in which {@code attended} finds nobody at
     * work. Returns what was destroyed.
     *
     * @param attended whether a person is working in the instance; it must answer true when it
     *                 cannot tell, so what cannot be inspected is left for the user
     */
    static List<String> sweep(InstanceBackend backend, SessionId self, String owner, Predicate<SessionId> alive,
                              Duration grace, Instant now, Predicate<String> attended) {
        var destroyed = new ArrayList<String>();
        othersOf(backend.mcpInstances(), owner, self, alive).values().forEach(other -> {
            if (!other.orphaned()) return;
            if (!other.config().getOrDefault(Metadata.PENDING_OP, "").isEmpty()) return;
            var name = other.name();
            try {
                var since = orphanedSince(other.config());
                if (since == null) {
                    backend.stamp(name, Map.of(Metadata.MCP_ORPHANED, now.toString()));
                    return;
                }
                if (now.isBefore(since.plus(grace))) return;
                if (attended.test(name)) {
                    System.err.println("isx mcp: keeping orphaned instance " + name + ": someone is working in it");
                    return;
                }
                // Adopted while we looked: it is somebody's again.
                var current = backend.metadata(name);
                if (current == null || !other.config().get(Metadata.MCP_SESSION).equals(current.get(Metadata.MCP_SESSION))) {
                    return;
                }
                backend.destroy(name);
                destroyed.add(name);
            } catch (RuntimeException e) {
                System.err.println("isx mcp: could not handle orphaned instance " + name + ": " + e.getMessage());
            }
        });
        if (!destroyed.isEmpty()) backend.refreshProxy();
        return destroyed;
    }
}
