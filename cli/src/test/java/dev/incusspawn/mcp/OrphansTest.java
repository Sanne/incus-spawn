package dev.incusspawn.mcp;

import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OrphansTest {

    private static final SessionId SELF = new SessionId(1, 100);
    private static final String DEAD = "2-200";
    private static final String ALIVE = "3-300";
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final Duration GRACE = Duration.ofHours(24);
    private static final Orphans.Windows WINDOWS = new Orphans.Windows(GRACE, Duration.ofHours(24), Duration.ofHours(168));
    private static final String LONG_AGO = NOW.minus(Duration.ofHours(25)).toString();
    private static final String RECENTLY = NOW.minus(Duration.ofHours(1)).toString();

    private static Map<String, String> stamped(String session, String owner, String... extra) {
        var config = new java.util.HashMap<String, String>();
        config.put(Metadata.MCP_SESSION, session);
        config.put(Metadata.MCP_OWNER, owner);
        for (int i = 0; i < extra.length; i += 2) config.put(extra[i], extra[i + 1]);
        return config;
    }

    private final Set<SessionId> alive = Set.of(SessionId.parse(ALIVE).orElseThrow(), SELF);

    @Test
    void othersAreThisUsersUnkeptInstancesOfOtherSessions() {
        var others = Orphans.othersOf(Map.of(
                "orphan", stamped(DEAD, "alice"),
                "held", stamped(ALIVE, "alice"),
                "mine", stamped(SELF.toString(), "alice"),
                "kept", stamped(DEAD, "alice", Metadata.MCP_KEPT, "2026-09-27"),
                "bobs", stamped(DEAD, "bob"),
                "garbled", stamped("not-a-session", "alice")), "alice", SELF, alive::contains);
        assertEquals(Set.of("orphan", "held", "garbled"), others.keySet());
        assertTrue(others.get("orphan").orphaned());
        assertFalse(others.get("held").orphaned());
        assertFalse(others.get("garbled").orphaned(), "an unparseable stamp is not ours to judge");
    }

    @Test
    void anOrphanIsDestroyedOnlyAfterItsGracePeriodAndWithNobodyInIt() {
        var backend = new FakeBackend()
                .instance("expired", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO))
                .instance("recent", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, RECENTLY))
                .instance("unstamped", stamped(DEAD, "alice"))
                .instance("attended", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO))
                .instance("held", stamped(ALIVE, "alice", Metadata.MCP_ORPHANED, LONG_AGO))
                .instance("kept", stamped(DEAD, "alice", Metadata.MCP_KEPT, "2026-09-27", Metadata.MCP_ORPHANED, LONG_AGO))
                .instance("bobs", stamped(DEAD, "bob", Metadata.MCP_ORPHANED, LONG_AGO))
                .instance("busy", stamped(DEAD, "alice", Metadata.PENDING_OP, Metadata.OP_STOPPING,
                        Metadata.MCP_ORPHANED, LONG_AGO))
                .instance("plain", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE));

        var destroyed = Orphans.sweep(backend, SELF, "alice", alive::contains, WINDOWS, NOW,
                name -> name.equals("attended") ? Orphans.Use.UNKNOWN : Orphans.Use.NOBODY).destroyed();

        assertEquals(List.of("expired"), destroyed);
        assertEquals(List.of("expired"), backend.destroyed);
        assertEquals(1, backend.proxyRefreshes);
        assertEquals(NOW, Orphans.orphanedSince(backend.instances.get("unstamped")),
                "a killed session's orphan starts its grace period when first noticed");
    }

    @Test
    void anOrphanWhoseDelegateIsStillWorkingIsSpared() {
        // Its coordinator died, but the delegate it started has unpushed work in progress.
        var backend = new FakeBackend()
                .instance("working", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO))
                .instance("idle", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO))
                .instance("unasked", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO));
        var current = new String[1];
        backend.responder = script -> {
            assertTrue(script.contains("isx-task-"), "the probe asks systemd about unfinished tasks");
            return switch (current[0]) {
                case "working" -> "task t1-abc running\n";
                case "unasked" -> "task t1-abc unknown\n";
                default -> "";
            };
        };
        var destroyed = Orphans.sweep(backend, SELF, "alice", alive::contains, WINDOWS, NOW, name -> {
            current[0] = name;
            return Orphans.probe(backend, name);
        }).destroyed();
        assertEquals(List.of("idle"), destroyed);
        assertEquals(List.of(Orphans.PROBE_LIMIT, Orphans.PROBE_LIMIT, Orphans.PROBE_LIMIT), backend.limits,
                "each asked through the bounded probe: an orphan whose profile hangs cannot hold the sweep");
    }

    @Test
    void anOrphanThatDoesNotAnswerInTimeIsInUse() {
        var backend = new FakeBackend().instance("hung", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO));
        backend.responder = script -> {
            throw new dev.incusspawn.incus.IncusException("exec did not finish within its time limit; given up on");
        };
        assertEquals(Orphans.Use.UNKNOWN, Orphans.probe(backend, "hung"), "unanswered is never taken for idle");
    }

    @Test
    void aStoppedOrphanHasNobodyInItAndIsDestroyedAfterItsGracePeriod() {
        // A session that stopped the instance it forks from, then died, must not leak it (#1013).
        var backend = new FakeBackend()
                .instance("stopped", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO))
                .instance("unreachable", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO));
        backend.stopped.add("stopped");
        backend.execFailure = new IllegalStateException("Instance is not running");
        var destroyed = Orphans.sweep(backend, SELF, "alice", alive::contains, WINDOWS, NOW,
                name -> Orphans.probe(backend, name)).destroyed();
        assertEquals(List.of("stopped"), destroyed, "a running instance that cannot be looked into is spared");
    }

    /** A sample taken {@code hours} before {@link #NOW}, with the CPU quiet since then. */
    private static String sampled(int hours, long cpu) {
        var at = NOW.minus(Duration.ofHours(hours));
        return new Orphans.CpuSample(at, cpu, at).toString();
    }

    private static final long CPU = 50_000_000_000L;
    private static final long DAY = Duration.ofHours(25).toSeconds();

    @Test
    void anOrphanWhoseDelegateShowsNoActivityIsStoppedNotDestroyed() {
        // A delegate hung on a dead call: kept forever before #1028, with its memory held.
        var backend = new FakeBackend();
        for (var name : List.of("hung", "writing", "computing", "attended", "first-look", "no-cpu")) {
            backend.instance(name, stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO,
                    Metadata.MCP_CPU_SAMPLE, sampled(25, CPU)));
            backend.cpu.put(name, CPU + 1_000_000_000L); // a second of CPU in a day
        }
        backend.instances.get("first-look").remove(Metadata.MCP_CPU_SAMPLE);
        backend.cpu.put("computing", CPU + Duration.ofHours(1).toNanos());
        backend.cpu.remove("no-cpu");
        var uses = Map.of(
                "hung", new Orphans.Use(false, true, DAY),
                "writing", new Orphans.Use(false, true, 60L),
                "computing", new Orphans.Use(false, true, DAY),
                "attended", new Orphans.Use(true, true, DAY),
                "first-look", new Orphans.Use(false, true, DAY),
                "no-cpu", new Orphans.Use(false, true, DAY));

        var swept = Orphans.sweep(backend, SELF, "alice", alive::contains, WINDOWS, NOW, uses::get);

        assertEquals(List.of(), swept.destroyed(), "unpushed work is never destroyed while its delegate has not finished");
        assertEquals(List.of("hung"), swept.stopped());
        assertEquals(Set.of("hung"), backend.stopped);
        assertEquals(Orphans.orphanedStamp(NOW, DEAD), backend.instances.get("hung").get(Metadata.MCP_DORMANT));
        assertEquals(NOW, Orphans.dormantSince(backend.instances.get("hung")));
        assertFalse(backend.instances.get("hung").containsKey(Metadata.PENDING_OP), "the mark is taken back");
        var computing = Orphans.CpuSample.parse(backend.instances.get("computing").get(Metadata.MCP_CPU_SAMPLE));
        assertEquals(NOW, computing.quietSince(), "busy CPU starts the quiet window again");
        assertEquals(NOW, Orphans.CpuSample.parse(backend.instances.get("first-look").get(Metadata.MCP_CPU_SAMPLE)).at(),
                "the first look takes the sample the next sweep compares with");
    }

    @Test
    void sweepsMomentsApartDoNotHoldTheProbesOwnCpuAgainstTheDelegate() {
        // A coordinator restarting often sweeps every few seconds; each probe costs the guest CPU.
        var sample = new Orphans.CpuSample(NOW.minusSeconds(5), CPU, NOW.minus(Duration.ofHours(23)));
        var backend = new FakeBackend().instance("hung", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO,
                Metadata.MCP_CPU_SAMPLE, sample.toString()));
        backend.cpu.put("hung", CPU + 200_000_000L); // one probe's fifth of a second
        var swept = Orphans.sweep(backend, SELF, "alice", alive::contains, WINDOWS, NOW,
                name -> new Orphans.Use(false, true, DAY));
        assertEquals(List.of(), swept.stopped());
        assertEquals(sample.toString(), backend.instances.get("hung").get(Metadata.MCP_CPU_SAMPLE),
                "neither compared nor recorded: the quiet window goes on");
    }

    @Test
    void aDormantOrphanIsDestroyedAfterItsOwnGracePeriodWithoutBeingLookedInto() {
        var backend = new FakeBackend()
                .instance("dormant-a-day", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO,
                        Metadata.MCP_DORMANT, Orphans.orphanedStamp(NOW.minus(Duration.ofDays(1)), DEAD)))
                .instance("dormant-a-week", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO,
                        Metadata.MCP_DORMANT, Orphans.orphanedStamp(NOW.minus(Duration.ofDays(8)), DEAD)))
                .instance("dormant-for-another", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO,
                        Metadata.MCP_DORMANT, Orphans.orphanedStamp(NOW.minus(Duration.ofDays(1)), "5-500")))
                .instance("stopped-by-someone", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO));
        backend.stopped.addAll(backend.instances.keySet());
        var swept = Orphans.sweep(backend, SELF, "alice", alive::contains, WINDOWS, NOW, name -> {
            throw new AssertionError("a stopped instance is never looked into: " + name);
        });
        assertEquals(Set.of("dormant-a-week", "dormant-for-another", "stopped-by-someone"), Set.copyOf(swept.destroyed()),
                "a stamp for an earlier holder, or none, is a stopped orphan's treatment as before");
        assertTrue(backend.scripts.isEmpty());
    }

    @Test
    void aStoppedOrphanStartedSinceTheListingIsSpared() {
        // Someone ran isx shell on it after the sweep listed it stopped: it was not looked into.
        var backend = new FakeBackend().instance("stopped", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO));
        backend.stopped.add("stopped");
        backend.onMarked = () -> backend.stopped.remove("stopped");
        assertEquals(List.of(), Orphans.sweep(backend, SELF, "alice", alive::contains, WINDOWS, NOW, name -> Orphans.Use.NOBODY).destroyed());
        assertFalse(backend.instances.get("stopped").containsKey(Metadata.PENDING_OP), "the mark is taken back");
    }

    @Test
    void anOrphanAdoptedDuringTheSweepIsSpared() {
        var backend = new FakeBackend().instance("expired", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO));
        var destroyed = Orphans.sweep(backend, SELF, "alice", alive::contains, WINDOWS, NOW, name -> {
            backend.stamp(name, Metadata.MCP_SESSION, ALIVE); // adopted while we looked inside
            return Orphans.Use.NOBODY;
        }).destroyed();
        assertEquals(List.of(), destroyed);
    }

    @Test
    void anOrphanAdoptedWhileTheSweepMarksItIsSpared() {
        var backend = new FakeBackend().instance("expired", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO));
        // Adopted between the sweep's mark and its re-read: the adopter's stamp is what it reads.
        backend.onMarked = () -> backend.stamp("expired", Metadata.MCP_SESSION, ALIVE);
        assertEquals(List.of(), Orphans.sweep(backend, SELF, "alice", alive::contains, WINDOWS, NOW, name -> Orphans.Use.NOBODY).destroyed());
        assertFalse(backend.instances.get("expired").containsKey(Metadata.PENDING_OP), "the mark is taken back");
    }

    @Test
    void anOrphanedStampLeftForAnEarlierHolderDoesNotStartTheNextOnesGracePeriod() {
        // A sweep stamped the killed holder DEAD just as another session adopted; that session
        // was then killed too. Its grace period starts when it is first noticed, not before.
        var backend = new FakeBackend().instance("worker", stamped("4-400", "alice",
                Metadata.MCP_ORPHANED, Orphans.orphanedStamp(Instant.parse(LONG_AGO), DEAD)));
        assertEquals(null, Orphans.orphanedSince(backend.instances.get("worker")));
        assertEquals(List.of(), Orphans.sweep(backend, SELF, "alice", alive::contains, WINDOWS, NOW, name -> Orphans.Use.NOBODY).destroyed());
        assertEquals(NOW, Orphans.orphanedSince(backend.instances.get("worker")));
    }

    @Test
    void theCurrentProcessIsAlive() {
        var self = SessionId.current();
        assertTrue(self.isAlive());
        assertFalse(new SessionId(self.pid(), self.start() + 1).isAlive(),
                "same pid, different start: the pid was reused");
        assertEquals(self, SessionId.parse(self.toString()).orElseThrow());
    }

    @Test
    void onLinuxTheStartIsTheKernelsTicksSinceBootNotAWallClockInstant() throws Exception {
        // A wall-clock start moves when NTP steps the clock, and a live holder then reads as dead (#858).
        var stat = Path.of("/proc/self/stat");
        assumeTrue(Files.isReadable(stat), "Linux only");
        var fields = Files.readString(stat).replaceFirst("^.*\\) ", "").trim().split(" +");
        assertEquals(Long.parseLong(fields[19]), SessionId.current().start());
    }

    @Test
    void theStartTicksAreFoundWhateverTheCommandNameHolds() {
        var stat = "4242 (evil) 1 2 3 (x) S 1 4242 4242 0 -1 4194304 100 0 0 0 1 2 0 0 20 0 1 0 987654 1 2 3\n";
        assertEquals(987654L, SessionId.procStartTicks(stat).orElseThrow());
        assertTrue(SessionId.procStartTicks("garbage").isEmpty());
    }
}
