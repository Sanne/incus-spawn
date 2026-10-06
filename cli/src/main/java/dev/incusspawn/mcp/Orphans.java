package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;
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
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Instances whose session is gone. An orphan is quarantined, never destroyed on sight: a
 * coordinating agent that restarts finds its workers where it left them and adopts them. Once
 * {@code mcp.orphan-grace-hours} have passed since it was orphaned, the next session to start
 * destroys it -- unless a person or one of its tasks is still working in it. One kept only for a
 * delegate that shows no activity is stopped instead, and destroyed once
 * {@code mcp.dormant-grace-hours} more have passed (#1028).
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
            // What this session released is an orphan to it as well: counted, listed and swept.
            if (session.isPresent() && session.get().equals(self) && !releasedByHolder(config)) return;
            result.put(name, new Other(name, config, orphaned(config, session, alive)));
        });
        return result;
    }

    /**
     * Whether an unkept instance held by {@code session} (its parsed {@code mcp-session}) is an
     * orphan: released by its holder ({@link #orphanedStamp} naming it), or the holder is gone. An
     * instance session outlives its holding of what it released. An unreadable stamp is held.
     */
    static boolean orphaned(Map<String, String> config, java.util.Optional<SessionId> session,
                            Predicate<SessionId> alive) {
        return session.isPresent() && (releasedByHolder(config) || !alive.test(session.get()));
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
     * Whether the session holding the instance released it: an {@link #orphanedStamp} naming
     * that holder. Only an instance session lives on after releasing something (#915); for it,
     * this is what makes a release an orphan though its holder is alive. A stamp without a
     * session, from before stamps named one, says nothing about a live holder.
     */
    static boolean releasedByHolder(Map<String, String> config) {
        var value = config.get(Metadata.MCP_ORPHANED);
        var holder = config.get(Metadata.MCP_SESSION);
        return value != null && holder != null && value.endsWith(" " + holder) && orphanedSince(config) != null;
    }

    /**
     * When the instance became an orphan, or null if nobody has stamped it yet for the session
     * that holds it now ({@link #orphanedStamp}).
     */
    static Instant orphanedSince(Map<String, String> config) {
        return stampedSince(config, Metadata.MCP_ORPHANED, true);
    }

    /**
     * The time in a {@code <time> <session>} stamp under {@code key}, or null if there is none or
     * it names another session than the holder; a bare {@code <time>}, from before stamps named
     * one, only if {@code bareCounts}.
     */
    private static Instant stampedSince(Map<String, String> config, String key, boolean bareCounts) {
        var value = config.get(key);
        if (value == null) return null;
        var space = value.indexOf(' ');
        if (space < 0 ? !bareCounts : !value.substring(space + 1).equals(config.get(Metadata.MCP_SESSION))) return null;
        try {
            return Instant.parse(space >= 0 ? value.substring(0, space) : value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * When the sweep stopped the instance as dormant, or null if it did not -- or did so for an
     * earlier holder: the stamp names the session, as {@link #orphanedStamp} does.
     */
    static Instant dormantSince(Map<String, String> config) {
        return stampedSince(config, Metadata.MCP_DORMANT, false);
    }

    /**
     * When a sweep stopped the instance as dormant, if it still is: stopped, by the
     * {@link InstanceBackend#STATUS} its metadata carries. What {@code list_instances} reports as
     * {@code dormant_since}.
     */
    static Instant dormantNow(Map<String, String> config) {
        return InstanceBackend.stopped(config) ? dormantSince(config) : null;
    }

    /** The sweep's periods: before an orphan is destroyed, found dormant, and destroyed once dormant. */
    record Windows(Duration grace, Duration dormantAfter, Duration dormantGrace) {
        static Windows of(McpConfig config) {
            return new Windows(Duration.ofHours(config.orphanGraceHours()), Duration.ofHours(config.dormantAfterHours()),
                    Duration.ofHours(config.dormantGraceHours()));
        }
    }

    /** What a sweep did: the orphans it destroyed, and those it stopped as dormant. */
    record Swept(List<String> destroyed, List<String> stopped) {}

    /**
     * What the probe found in a running orphan: a person in it, a delegated agent that has not
     * finished, and how many seconds ago a delegate last wrote to its task directory (null if
     * none has).
     */
    record Use(boolean attended, boolean unfinished, Long idleSeconds) {
        static final Use NOBODY = new Use(false, false, null);
        /** It could not be looked into: as good as a person in it, so nothing is done to it. */
        static final Use UNKNOWN = new Use(true, false, null);
    }

    /**
     * The CPU time an instance may gain between two sweeps, as a share of the time between them,
     * and still count as idle: one percent of one CPU. An idle system and a Claude Code waiting
     * on a dead connection stay well below it; a delegate that runs anything does not.
     */
    static final double QUIET_CPU_SHARE = 0.01;

    /** The least time between two CPU samples {@link #quiet} compares (or the window, if shorter). */
    static final Duration MIN_SAMPLE_GAP = Duration.ofHours(1);

    /**
     * Start the grace period of orphans nobody has stamped yet (their session was killed), and
     * deal with those whose grace period is over: destroy them if {@code probe} finds nobody and
     * no unfinished delegate in them; stop them, stamped {@link Metadata#MCP_DORMANT}, if all it
     * finds is a delegate that has shown no activity for {@code dormantAfter} ({@link #quiet});
     * else keep them. A stopped orphan is destroyed without being looked into -- one stamped
     * dormant only once {@code dormantGrace} has passed since it was stopped (#1028).
     *
     * @param probe what is working in the instance ({@link #probe}); it must answer
     *              {@link Use#UNKNOWN} when it cannot tell, so what cannot be inspected is left
     *              for the user. Not asked about a stopped instance.
     */
    static Swept sweep(InstanceBackend backend, SessionId self, String owner, Predicate<SessionId> alive,
                       Windows windows, Instant now, Function<String, Use> probe) {
        var destroyed = new ArrayList<String>();
        var stopped = new ArrayList<String>();
        othersOf(backend.mcpInstances(), owner, self, alive).values().forEach(other -> {
            if (!other.orphaned()) return;
            if (!Metadata.pendingOp(other.config()).isEmpty()) return;
            var name = other.name();
            var holder = other.config().get(Metadata.MCP_SESSION);
            try {
                var since = orphanedSince(other.config());
                if (since == null) {
                    backend.stamp(name, Map.of(Metadata.MCP_ORPHANED, orphanedStamp(now, holder)));
                    return;
                }
                if (now.isBefore(since.plus(windows.grace()))) return;
                if (InstanceBackend.stopped(other.config())) {
                    var dormant = dormantSince(other.config());
                    if (dormant != null && now.isBefore(dormant.plus(windows.dormantGrace()))) return;
                    // Stopped, nobody can be in it, and the probe could not look inside to tell.
                    if (backend.destroyIfHeldBy(name, holder, true)) destroyed.add(name);
                    return;
                }
                var use = probe.apply(name);
                if (!use.attended() && use.unfinished()
                        && quiet(backend, name, other.config(), use.idleSeconds(), windows.dormantAfter(), now)) {
                    // As the destroy below: not if it was adopted meanwhile.
                    if (backend.stopIfHeldBy(name, holder, Map.of(Metadata.MCP_DORMANT, orphanedStamp(now, holder)))) {
                        System.err.println("isx mcp: stopped orphaned instance " + name + ": a task it was given "
                                + "has not finished, but nothing in it has moved for " + windows.dormantAfter().toHours() + " hours");
                        stopped.add(name);
                    }
                    return;
                }
                if (use.attended() || use.unfinished()) {
                    System.err.println("isx mcp: keeping orphaned instance " + name
                            + ": someone, or a task it was given, is still working in it");
                    return;
                }
                // Not if it was adopted while we looked, or is being adopted now.
                if (backend.destroyIfHeldBy(name, holder, false)) destroyed.add(name);
            } catch (RuntimeException e) {
                System.err.println("isx mcp: could not handle orphaned instance " + name + ": " + e.getMessage());
            }
        });
        if (!destroyed.isEmpty()) backend.refreshProxy();
        return new Swept(destroyed, stopped);
    }

    /**
     * Whether a running orphan has shown no activity for {@code after}: no delegate wrote to its
     * task directory ({@code idleSeconds}), and its CPU time grew by less than
     * {@link #QUIET_CPU_SHARE} between every two samples since, taken at least
     * {@link #MIN_SAMPLE_GAP} apart. Takes this sweep's CPU sample (one
     * state read) and records it on the instance ({@link Metadata#MCP_CPU_SAMPLE}) for the next;
     * so never true at the first look, nor when Incus cannot say.
     */
    static boolean quiet(InstanceBackend backend, String name, Map<String, String> config, Long idleSeconds,
                         Duration after, Instant now) {
        var cpu = backend.cpuUsage(name);
        if (cpu < 0) return false;
        var previous = CpuSample.parse(config.get(Metadata.MCP_CPU_SAMPLE));
        // Sweeps seconds apart would hold the probes' own CPU against a tiny allowance: such a
        // sweep neither compares nor records, and the next one compares across the whole gap.
        var gap = after.compareTo(MIN_SAMPLE_GAP) < 0 ? after : MIN_SAMPLE_GAP;
        if (previous != null && cpu >= previous.cpu() && now.isBefore(previous.at().plus(gap))) return false;
        // A counter that went back is a restart: what it did before is unknown.
        var compared = previous != null && cpu >= previous.cpu() && now.isAfter(previous.at());
        var busy = !compared || cpu - previous.cpu() > Duration.between(previous.at(), now).toNanos() * QUIET_CPU_SHARE;
        var quietSince = busy ? now : previous.quietSince();
        backend.stamp(name, Map.of(Metadata.MCP_CPU_SAMPLE, new CpuSample(now, cpu, quietSince).toString()));
        return !busy && !now.isBefore(quietSince.plus(after))
                && idleSeconds != null && idleSeconds >= after.toSeconds();
    }

    /** {@link Metadata#MCP_CPU_SAMPLE}: when it was taken, the CPU time then, and since when the CPU was quiet. */
    record CpuSample(Instant at, long cpu, Instant quietSince) {
        static CpuSample parse(String value) {
            if (value == null) return null;
            var parts = value.split(" ");
            if (parts.length != 3) return null;
            try {
                return new CpuSample(Instant.parse(parts[0]), Long.parseLong(parts[1]), Instant.parse(parts[2]));
            } catch (DateTimeParseException | NumberFormatException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return at + " " + cpu + " " + quietSince;
        }
    }

    /**
     * Whether a person works in the instance ({@link Presence}) or one of its tasks has not
     * finished: a delegate outliving its coordinator by the grace period still has unpushed
     * work, as is one systemd cannot say about; and how long ago a delegate last wrote anything
     * ({@link TaskScripts#agentIdle}). {@link Use#UNKNOWN} when the instance cannot be looked into; the
     * sweep does not ask about a stopped instance, which has nobody in it.
     */
    static Use probe(InstanceBackend backend, String name) {
        try {
            var out = new ByteArrayOutputStream();
            // Bounded, and without the login shell a hung profile would hold forever: unanswered is in use.
            if (backend.probe(name, Presence.script("") + "; " + TaskScripts.agentIdle() + "; " + TaskScripts.unfinished(),
                    out, PROBE_LIMIT) != 0) {
                return Use.UNKNOWN;
            }
            var lines = out.toString(StandardCharsets.UTF_8).lines().toList();
            // A delegate systemd could not be asked about counts as unfinished.
            return new Use(Presence.parse(lines).attended(), !TaskScripts.taskIds(lines.stream()).isEmpty(),
                    TaskScripts.agentIdle(lines));
        } catch (RuntimeException e) {
            return Use.UNKNOWN;
        }
    }
}
