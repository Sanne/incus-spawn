package dev.incusspawn.mcp;

import dev.incusspawn.incus.Metadata;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
 * destroys it -- unless a person or one of its tasks is still working in it.
 *
 * <p>Only this host user's instances are considered, and only ones that were never kept and are
 * not in the middle of another operation. A session is dead when its process no longer exists,
 * or a different process now has its pid.
 */
final class Orphans {

    /** How long the sweep waits for an orphan to say whether it is in use. */
    static final Duration PROBE_LIMIT = Duration.ofSeconds(10);

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

    /**
     * The {@code mcp-orphaned} value saying {@code session}'s holding of the instance ended at
     * {@code at}. It names the session so that a stamp which lost a race -- a sweep stamping an
     * instance an adoption took meanwhile -- never counts for the next holder: its grace period
     * starts when that holder is known to be gone, not earlier.
     */
    static String orphanedStamp(Instant at, String session) {
        return at + " " + session;
    }

    /**
     * When the instance became an orphan, or null if nobody has stamped it yet for the session
     * that holds it now ({@link #orphanedStamp}).
     */
    static Instant orphanedSince(Map<String, String> config) {
        var value = config.get(Metadata.MCP_ORPHANED);
        if (value == null) return null;
        var space = value.indexOf(' ');
        if (space >= 0 && !value.substring(space + 1).equals(config.get(Metadata.MCP_SESSION))) return null;
        try {
            return Instant.parse(space >= 0 ? value.substring(0, space) : value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Start the grace period of orphans nobody has stamped yet (their session was killed), and
     * destroy those whose grace period is over and which {@code inUse} finds idle. Returns what
     * was destroyed.
     *
     * @param inUse whether a person or a task is working in the instance ({@link #inUse}); it
     *              must answer true when it cannot tell, so what cannot be inspected is left for
     *              the user. Not asked about a stopped instance.
     */
    static List<String> sweep(InstanceBackend backend, SessionId self, String owner, Predicate<SessionId> alive,
                              Duration grace, Instant now, Predicate<String> inUse) {
        var destroyed = new ArrayList<String>();
        othersOf(backend.mcpInstances(), owner, self, alive).values().forEach(other -> {
            if (!other.orphaned()) return;
            if (!Metadata.pendingOp(other.config()).isEmpty()) return;
            var name = other.name();
            try {
                var since = orphanedSince(other.config());
                if (since == null) {
                    backend.stamp(name, Map.of(Metadata.MCP_ORPHANED,
                            orphanedStamp(now, other.config().get(Metadata.MCP_SESSION))));
                    return;
                }
                if (now.isBefore(since.plus(grace))) return;
                // Stopped, nobody can be in it, and inUse could not look inside to tell.
                var stopped = InstanceBackend.stopped(other.config());
                if (!stopped && inUse.test(name)) {
                    System.err.println("isx mcp: keeping orphaned instance " + name
                            + ": someone, or a task it was given, is still working in it");
                    return;
                }
                // Not if it was adopted while we looked, or is being adopted now.
                if (backend.destroyIfHeldBy(name, other.config().get(Metadata.MCP_SESSION), stopped)) destroyed.add(name);
            } catch (RuntimeException e) {
                System.err.println("isx mcp: could not handle orphaned instance " + name + ": " + e.getMessage());
            }
        });
        if (!destroyed.isEmpty()) backend.refreshProxy();
        return destroyed;
    }

    /**
     * Whether a person works in the instance ({@link Presence}) or one of its tasks has not
     * finished: a delegate outliving its coordinator by the grace period still has unpushed
     * work. True when the instance cannot be looked into or systemd cannot say; the sweep does
     * not ask about a stopped instance, which has nobody in it.
     */
    static boolean inUse(InstanceBackend backend, String name) {
        try {
            var out = new ByteArrayOutputStream();
            // Bounded, and without the login shell a hung profile would hold forever: unanswered is in use.
            if (backend.probe(name, Presence.script("") + "; " + TaskScripts.unfinished(), out, PROBE_LIMIT) != 0) {
                return true;
            }
            var lines = out.toString(StandardCharsets.UTF_8).lines().toList();
            return Presence.parse(lines).attended() || !TaskScripts.taskIds(lines.stream()).isEmpty();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
