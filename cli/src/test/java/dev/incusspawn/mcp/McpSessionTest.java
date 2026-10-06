package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSessionTest {

    private static final SessionId SELF = new SessionId(4242, 1_700_000_000_000L);
    private static final String DEAD = "2-200";
    private static final String ALIVE = "3-300";

    private final FakeBackend backend = new FakeBackend().template("tpl-dev", true, "claude");
    private static final InstanceBackend.TemplateInfo TEMPLATE =
            new InstanceBackend.TemplateInfo("tpl-dev", "", true, false, List.of(), null, false, Map.of());
    private final McpConfig config = new McpConfig();
    private final Set<SessionId> alive = new HashSet<>(Set.of(SELF, SessionId.parse(ALIVE).orElseThrow()));

    private McpSession session(int maxInstances) {
        config.setMaxInstances(maxInstances);
        config.setTemplates(List.of("tpl-dev"));
        return new McpSession(SELF, "alice", 99, "/home/alice/project", backend, () -> config, alive::contains);
    }

    private static String create(McpSession session, FakeBackend backend) {
        var name = session.reserve(TEMPLATE, null, null, null, null);
        backend.create(TEMPLATE, name, session.stamps(null, null));
        session.created(name);
        return name;
    }

    /** An instance another session of {@code owner} made. */
    private void other(String name, String session, String owner, String... extra) {
        var config = new java.util.HashMap<String, String>();
        config.put(Metadata.TYPE, Metadata.TYPE_CLONE);
        config.put(Metadata.PARENT, "tpl-dev");
        config.put(Metadata.MCP_SESSION, session);
        config.put(Metadata.MCP_OWNER, owner);
        for (int i = 0; i < extra.length; i += 2) config.put(extra[i], extra[i + 1]);
        backend.instance(name, config);
    }

    @Test
    void anInstanceLetGoOfIsHeldNoMoreForTheReasonItWasLetGo() {
        var s = session(3);
        var destroyed = create(s, backend);
        var deleted = create(s, backend);
        var taken = create(s, backend);
        s.destroy(destroyed);
        backend.instances.remove(deleted); // deleted behind the session's back
        assertThrows(ToolError.class, () -> s.requireOwned(deleted));
        backend.stamp(taken, Metadata.MCP_SESSION, "42-1"); // another session adopted it
        assertThrows(ToolError.class, () -> s.requireOwned(taken));

        var reads = backend.metadataReads.get();
        assertEquals(McpSession.Hold.GONE, s.hold(destroyed), "a destroy is not an adoption");
        assertEquals(McpSession.Hold.GONE, s.hold(deleted));
        assertEquals(McpSession.Hold.RELEASED, s.hold(taken));
        assertEquals(McpSession.Hold.GONE, s.whyNotHeld(destroyed));
        assertEquals(McpSession.Hold.RELEASED, s.whyNotHeld("mcp-never-held"));
        assertEquals(reads, backend.metadataReads.get(), "known when let go of: no request");
    }

    @Test
    void anInstanceIncusCannotAnswerForIsStillHeld() {
        var s = session(3);
        var name = create(s, backend);
        backend.metadataFailure = new ToolError(ToolError.Code.UNAVAILABLE, "cannot read it from Incus right now");
        assertEquals(McpSession.Hold.HELD, s.hold(name));
        assertThrows(RuntimeException.class, () -> s.requireOwned(name));
        backend.metadataFailure = null;
        s.requireOwned(name); // still held once Incus answers again
    }

    @Test
    void anInstanceStillBeingCreatedCannotBeAdoptedIntoReadiness() {
        var s = session(3);
        var name = s.reserve(TEMPLATE, null, null, null, null);
        backend.create(TEMPLATE, name, s.stamps(null, null)); // the copy exists and carries our stamp
        assertThrows(ToolError.class, () -> s.adopt(name, false));
        var e = assertThrows(ToolError.class, () -> s.requireOwned(name));
        assertTrue(e.getMessage().contains("still being created"), e.getMessage());
    }

    @Test
    void anInstanceDeletedBehindTheSessionsBackFreesItsPlace() {
        var s = session(1);
        var name = create(s, backend);
        backend.instances.remove(name); // deleted from the TUI: the session was not told
        s.reserve(TEMPLATE, null, null, null, null);
    }

    /**
     * A sweep marks the orphan for deletion after adopt's checks and before its stamp lands;
     * {@code outcome} is what the sweep does next, seen on adopt's first read after that.
     */
    private McpSession adoptWhileASweepMarks(Runnable outcome) {
        var s = session(3);
        s.settleStep = java.time.Duration.ofMillis(1);
        other("worker", DEAD, "alice", Metadata.MCP_ORPHANED, "2026-09-01T00:00:00Z");
        var marked = new java.util.concurrent.atomic.AtomicInteger();
        backend.onStamp = () -> {
            backend.instances.get("worker").put(Metadata.PENDING_OP, Metadata.OP_DELETING);
            marked.set(1);
        };
        backend.onRead = () -> {
            // The read-back right after the stamp still sees the mark; the next one the outcome.
            if (marked.get() > 0 && marked.getAndIncrement() == 2) outcome.run();
        };
        return s;
    }

    @Test
    void anAdoptionTheSweepDeletesUnderneathIsNeverReportedAsAdopted() {
        // The sweep read the holder before the stamp, so it deletes the instance.
        var s = adoptWhileASweepMarks(() -> backend.instances.remove("worker"));
        var e = assertThrows(ToolError.class, () -> s.adopt("worker", false));
        assertTrue(e.getMessage().contains("removed as an orphan"), e.getMessage());
        assertFalse(s.holds("worker"));
    }

    @Test
    void anAdoptionTheSweepBacksOffFromIsHeld() {
        // The sweep read the holder after the stamp: it takes its mark back, and the adoption stands.
        var s = adoptWhileASweepMarks(() -> backend.instances.get("worker").remove(Metadata.PENDING_OP));
        assertEquals("worker", s.adopt("worker", false).name());
        assertTrue(s.holds("worker"), "never left stamped as ours without being held");
    }

    @Test
    void namesSayWhereTheyCameFrom() {
        var s = session(3);
        assertTrue(s.reserve(TEMPLATE, null, null, null, null).matches("mcp-dev-[a-z2-7]{5}"));
        assertTrue(s.reserve(TEMPLATE, "870-impl", null, null, null).matches("mcp-dev-870-impl-[a-z2-7]{5}"));
    }

    @Test
    void aHintCannotSmuggleCharactersIntoTheName() {
        var s = session(3);
        for (var hint : List.of("Upper", "semi;colon", "-leading", "much-too-long-a-hint", "a b")) {
            assertThrows(ToolError.class, () -> s.reserve(TEMPLATE, hint, null, null, null), hint);
        }
    }

    @Test
    void aPurposeIsOneLine() {
        var s = session(3);
        assertThrows(ToolError.class, () -> s.reserve(TEMPLATE, null, "two\nlines", null, null));
        assertThrows(ToolError.class, () -> s.reserve(TEMPLATE, null, "x".repeat(201), null, null));
        assertEquals("#870 implement", s.stamps("  #870 implement ", null).get(Metadata.MCP_PURPOSE));
        assertFalse(s.stamps(" ", null).containsKey(Metadata.MCP_PURPOSE));
    }

    @Test
    void theCapCountsCreatesStillInFlight() {
        var s = session(1);
        s.reserve(TEMPLATE, null, null, null, null); // not created yet
        var e = assertThrows(ToolError.class, () -> s.reserve(TEMPLATE, null, null, null, null));
        assertTrue(e.getMessage().contains("mcp.max-instances"), e.getMessage());
    }

    @Test
    void theCapIsPerUserAcrossSessions() {
        other("held", ALIVE, "alice");
        other("orphan", DEAD, "alice");
        other("kept", DEAD, "alice", Metadata.MCP_KEPT, "2026-09-27");
        other("bobs", DEAD, "bob");
        var s = session(3);
        s.reserve(TEMPLATE, null, null, null, null);
        var e = assertThrows(ToolError.class, () -> s.reserve(TEMPLATE, null, null, null, null));
        assertTrue(e.getMessage().contains("1 in this session, 2 held by other sessions or orphaned"), e.getMessage());
    }

    @Test
    void anAbandonedCreateFreesItsSlot() {
        var s = session(1);
        s.abandon(s.reserve(TEMPLATE, null, null, null, null), McpSession.Hold.GONE);
        s.reserve(TEMPLATE, null, null, null, null);
    }

    @Test
    void keptInstancesDoNotCountAgainstTheCap() {
        var s = session(1);
        s.keep(create(s, backend));
        create(s, backend);
    }

    @Test
    void everyInstanceIsStampedWithItsOwnerAndHolder() {
        var s = session(3);
        s.clientName("claude-code");
        var stamps = s.stamps("#870 implement", null);
        assertEquals("4242-1700000000000", stamps.get(Metadata.MCP_SESSION));
        assertEquals("alice", stamps.get(Metadata.MCP_OWNER));
        assertEquals("claude-code", stamps.get(Metadata.MCP_CLIENT));
        assertEquals("99", stamps.get(Metadata.MCP_CLIENT_PID));
        assertEquals("/home/alice/project", stamps.get(Metadata.MCP_CWD));
        assertEquals("#870 implement", stamps.get(Metadata.MCP_PURPOSE));
        assertFalse(stamps.containsValue(null), "the copy request must not carry nulls: " + stamps);
    }

    @Test
    void anInstanceThisSessionDoesNotHoldIsRefused() {
        backend.instance("users-box", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE));
        var s = session(3);
        assertThrows(ToolError.class, () -> s.requireOwned("users-box"));
        assertThrows(ToolError.class, () -> s.requireOwned("tpl-dev"));
        assertThrows(ToolError.class, () -> s.destroy("users-box"));
        assertTrue(backend.instances.containsKey("users-box"));
    }

    @Test
    void anotherSessionsInstancePointsAtAdoption() {
        other("orphan", DEAD, "alice");
        var e = assertThrows(ToolError.class, () -> session(3).requireOwned("orphan"));
        assertTrue(e.getMessage().contains("adopt_instance"), e.getMessage());
    }

    @Test
    void aSameNamedInstanceWithoutOurStampIsRefused() {
        // The user destroyed our instance and made their own under the same name.
        var s = session(3);
        var name = create(s, backend);
        backend.instances.put(name, new java.util.concurrent.ConcurrentHashMap<>(
                Map.of(Metadata.TYPE, Metadata.TYPE_CLONE)));
        assertThrows(ToolError.class, () -> s.requireOwned(name));
        assertThrows(ToolError.class, () -> s.destroy(name));
        assertTrue(backend.instances.containsKey(name), "must not destroy the user's instance");
    }

    @Test
    void aBusyInstanceIsRefused() {
        var s = session(3);
        var name = create(s, backend);
        backend.stamp(name, Metadata.PENDING_OP, Metadata.OP_DELETING);
        assertThrows(ToolError.class, () -> s.requireOwned(name));
    }

    @Test
    void anInstanceStillBeingCreatedIsNotUsable() {
        var s = session(3);
        var name = s.reserve(TEMPLATE, null, null, null, null);
        assertThrows(ToolError.class, () -> s.requireOwned(name));
    }

    @Test
    void keepingStampsTheInstance() {
        var s = session(3);
        var name = create(s, backend);
        s.keep(name);
        assertTrue(backend.instances.get(name).containsKey(Metadata.MCP_KEPT));
        assertTrue(s.instances().getFirst().kept());
        s.requireOwned(name); // still usable until the session ends
    }

    @Test
    void endingTheSessionReleasesRatherThanDestroys() {
        var s = session(3);
        var kept = create(s, backend);
        var worker = create(s, backend);
        s.keep(kept);
        assertEquals(List.of(worker), s.release());
        assertEquals(List.of(), backend.destroyed, "nothing is destroyed when a session ends");
        assertTrue(backend.instances.get(worker).containsKey(Metadata.MCP_ORPHANED));
        assertFalse(backend.instances.get(kept).containsKey(Metadata.MCP_ORPHANED));
    }

    @Test
    void releasingSkipsAnInstanceAnotherSessionAdopted() {
        var s = session(3);
        var name = create(s, backend);
        backend.stamp(name, Metadata.MCP_SESSION, ALIVE);
        assertEquals(List.of(), s.release());
        assertFalse(backend.instances.get(name).containsKey(Metadata.MCP_ORPHANED));
    }

    @Test
    void anOrphanIsAdoptedWithItsPurpose() {
        other("mcp-dev-870-impl-abcde", DEAD, "alice", Metadata.MCP_PURPOSE, "#870 implement",
                Metadata.MCP_ORPHANED, "2026-09-28T10:00:00Z");
        var s = session(3);
        s.clientName("coordinator");
        var adopted = s.adopt("mcp-dev-870-impl-abcde", false);
        assertEquals("tpl-dev", adopted.template());
        assertEquals("#870 implement", adopted.purpose());
        assertTrue(adopted.supportsDelegate());
        var config = backend.instances.get("mcp-dev-870-impl-abcde");
        assertEquals(SELF.toString(), config.get(Metadata.MCP_SESSION));
        assertEquals("coordinator", config.get(Metadata.MCP_CLIENT));
        assertNull(config.get(Metadata.MCP_ORPHANED), "an adopted instance is no longer an orphan");
        s.requireOwned("mcp-dev-870-impl-abcde");
        assertEquals(adopted, s.adopt("mcp-dev-870-impl-abcde", false), "adopting twice is harmless");
    }

    @Test
    void aDormantOrphanIsStartedWhenAdoptedAndNoLongerDormant() {
        other("hung", DEAD, "alice", Metadata.MCP_ORPHANED, "2026-09-28T10:00:00Z",
                Metadata.MCP_DORMANT, Orphans.orphanedStamp(java.time.Instant.parse("2026-09-30T10:00:00Z"), DEAD),
                Metadata.MCP_CPU_SAMPLE, "2026-09-30T10:00:00Z 5 2026-09-29T10:00:00Z");
        other("stopped", DEAD, "alice", Metadata.MCP_ORPHANED, "2026-09-28T10:00:00Z");
        backend.stopped.addAll(List.of("hung", "stopped"));
        var s = session(3);
        var said = new java.util.ArrayList<String>();
        s.adopt("hung", false);
        assertTrue(s.wakeIfDormant("hung", said::add));
        assertFalse(backend.stopped.contains("hung"), "the sweep stopped it; adopting starts it again");
        assertEquals(1, said.size(), "a start takes seconds: the client is told");
        assertNull(backend.instances.get("hung").get(Metadata.MCP_DORMANT));
        assertNull(backend.instances.get("hung").get(Metadata.MCP_CPU_SAMPLE), "a later orphaning samples afresh");
        s.adopt("stopped", false);
        assertFalse(s.wakeIfDormant("stopped", said::add));
        assertTrue(backend.stopped.contains("stopped"), "one its session stopped stays stopped, as before");
    }

    @Test
    void anAdoptionTheSweepIsStillStoppingIsHeldButBusyAndARepeatStartsIt() {
        // The sweep marked it and read the old holder before this adoption stamped; its stop
        // outlasts the wait for the mark. Reported adopted, it would be stopped under us.
        other("hung", DEAD, "alice", Metadata.MCP_ORPHANED, "2026-09-28T10:00:00Z");
        var s = session(3);
        s.settleStep = java.time.Duration.ofMillis(1);
        s.settleLimit = java.time.Duration.ofMillis(20);
        backend.onStamp = () -> {
            backend.onStamp = null;
            backend.instances.get("hung").put(Metadata.PENDING_OP, Metadata.OP_STOPPING);
            backend.instances.get("hung").put(Metadata.MCP_DORMANT,
                    Orphans.orphanedStamp(java.time.Instant.parse("2026-10-06T10:00:00Z"), DEAD));
        };
        var e = assertThrows(ToolError.class, () -> s.adopt("hung", false));
        assertEquals(ToolError.Code.BUSY, e.code);
        assertEquals(McpSession.Hold.HELD, s.whyNotHeld("hung"), "held: stamped ours, it must not vanish from view");
        assertEquals(ToolError.Code.BUSY, assertThrows(ToolError.class, () -> s.adopt("hung", false)).code,
                "a repeat while it is still being stopped is busy again");

        backend.instances.get("hung").remove(Metadata.PENDING_OP); // the sweep's stop ends
        backend.stopped.add("hung");
        s.adopt("hung", false);
        assertTrue(s.wakeIfDormant("hung", m -> { }), "the repeat starts it");
        assertFalse(backend.stopped.contains("hung"));
    }

    @Test
    void aDormantStartThatFailsIsUnavailableAndARepeatStartsIt() {
        other("hung", DEAD, "alice", Metadata.MCP_ORPHANED, "2026-09-28T10:00:00Z",
                Metadata.MCP_DORMANT, Orphans.orphanedStamp(java.time.Instant.parse("2026-09-30T10:00:00Z"), DEAD));
        backend.stopped.add("hung");
        backend.startFailure = new IllegalStateException("proxy down");
        var s = session(3);
        s.adopt("hung", false);
        var e = assertThrows(ToolError.class, () -> s.wakeIfDormant("hung", m -> { }));
        assertEquals(ToolError.Code.UNAVAILABLE, e.code);
        backend.startFailure = null;
        s.adopt("hung", false);
        assertTrue(s.wakeIfDormant("hung", m -> { }), "the retry unavailable promises does the start");
        assertFalse(backend.stopped.contains("hung"));
    }

    @Test
    void aForkIsAdoptedByTheTemplateItDescendsFrom() {
        // A fork's PARENT is the instance it was branched from; its lineage is PROFILE (#1013).
        other("mcp-dev-fork-abcde", DEAD, "alice", Metadata.PARENT, "mcp-dev-src-fghij",
                Metadata.PROFILE, "tpl-dev", Metadata.MCP_ORPHANED, "2026-09-28T10:00:00Z");
        var adopted = session(3).adopt("mcp-dev-fork-abcde", false);
        assertEquals("tpl-dev", adopted.template());
        assertTrue(adopted.supportsDelegate());
    }

    @Test
    void aLiveSessionsInstanceIsAdoptedOnlyByForce() {
        other("held", ALIVE, "alice");
        var s = session(3);
        var e = assertThrows(ToolError.class, () -> s.adopt("held", false));
        assertTrue(e.getMessage().contains("still running"), e.getMessage());
        s.adopt("held", true);
        assertEquals(SELF.toString(), backend.instances.get("held").get(Metadata.MCP_SESSION));
    }

    @Test
    void theSessionAnInstanceWasAdoptedFromLosesIt() {
        var s = session(3);
        var name = create(s, backend);
        backend.stamp(name, Metadata.MCP_SESSION, ALIVE); // another session forced it
        var e = assertThrows(ToolError.class, () -> s.requireOwned(name));
        assertTrue(e.getMessage().contains("another session adopted it"), e.getMessage());
        assertEquals(List.of(), s.instances());
    }

    @Test
    void onlyThisUsersUnkeptMcpInstancesOfApprovedTemplatesCanBeAdopted() {
        other("kept", DEAD, "alice", Metadata.MCP_KEPT, "2026-09-27");
        other("bobs", DEAD, "bob");
        other("busy", DEAD, "alice", Metadata.PENDING_OP, Metadata.OP_STOPPING);
        other("unapproved", DEAD, "alice", Metadata.PARENT, "tpl-other");
        backend.instance("users-box", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE));
        var s = session(3);
        for (var name : List.of("kept", "bobs", "busy", "unapproved", "users-box", "missing")) {
            assertThrows(ToolError.class, () -> s.adopt(name, true), name);
        }
        assertEquals(DEAD, backend.instances.get("bobs").get(Metadata.MCP_SESSION));
    }

    @Test
    void destroyingForgetsTheInstance() {
        var s = session(3);
        var name = create(s, backend);
        assertTrue(s.destroy(name));
        assertEquals(List.of(), s.instances());
        assertThrows(ToolError.class, () -> s.destroy(name));
    }

    @Test
    void anInstanceStillBeingCreatedCannotBeDestroyed() {
        var s = session(3);
        var name = s.reserve(TEMPLATE, null, null, null, null);
        // The copy exists, but its stamp arrives with the configuring write.
        backend.instance(name, Map.of(Metadata.TYPE, Metadata.TYPE_CLONE));
        var e = assertThrows(ToolError.class, () -> s.destroy(name));
        assertTrue(e.getMessage().contains("still being created"), e.getMessage());
        assertTrue(s.holds(name), "the reservation survives");
        assertTrue(backend.instances.containsKey(name));
    }
    /** An instance's tasks as the per-user task probe would find them: busy ones, by instance. */
    private final Map<String, Integer> busyIn = new java.util.concurrent.ConcurrentHashMap<>();

    /** A second, live session of the same user, with its own tasks. */
    private Tasks tasksOf(McpSession session) {
        backend.instanceResponder = (instance, script) -> {
            if (script.equals(TaskScripts.busy())) {
                return java.util.stream.IntStream.range(0, busyIn.getOrDefault(instance, 0))
                        .mapToObj(i -> "task t" + i + "-x running\n").collect(java.util.stream.Collectors.joining());
            }
            return ""; // a launch, or a state probe of nothing believed running
        };
        return new Tasks(session, backend, () -> config);
    }

    @Test
    void twoSessionsOfOneUserShareOneTaskBudget() {
        var a = session(8);
        config.setMaxConcurrentTasks(3);
        var bId = SessionId.parse(ALIVE).orElseThrow();
        var b = new McpSession(bId, "alice", 98, "/home/alice/other", backend, () -> config, alive::contains);
        var aTasks = tasksOf(a);
        var bTasks = tasksOf(b);
        var aInstance = create(a, backend);
        var bInstance = create(b, backend);
        other("mcp-orphan", DEAD, "alice");
        other("mcp-bobs", DEAD, "bob");
        other("mcp-kept", DEAD, "alice", Metadata.MCP_KEPT, "true");
        // Kept by a session that still runs: it can go on starting tasks there.
        alive.add(SessionId.parse("5-500").orElseThrow());
        other("mcp-kept-live", "5-500", "alice", Metadata.MCP_KEPT, "true");
        other("mcp-stopped", DEAD, "alice");
        backend.stopped.add("mcp-stopped");

        aTasks.startCommand(aInstance, "/", Map.of(), "make serve", null);
        busyIn.put(aInstance, 1);
        busyIn.put("mcp-orphan", 1);
        busyIn.put("mcp-bobs", 5);
        busyIn.put("mcp-kept", 5);
        busyIn.put("mcp-kept-live", 1);
        busyIn.put("mcp-stopped", 5);

        var e = assertThrows(ToolError.class, () -> bTasks.startCommand(bInstance, "/", Map.of(), "make test", null));
        assertTrue(e.getMessage().contains("mcp.max-concurrent-tasks") && e.getMessage().contains("(3)"),
                e.getMessage());
        assertTrue(e.getMessage().contains("0 in this session, 3 in other sessions or orphaned"), e.getMessage());
        assertTrue(e.getMessage().contains(aInstance + " 1") && e.getMessage().contains("mcp-orphan 1")
                && e.getMessage().contains("mcp-kept-live 1"), "names where they run: " + e.getMessage());

        busyIn.put("mcp-orphan", 0);
        backend.scripts.clear();
        bTasks.startCommand(bInstance, "/", Map.of(), "make test", null);
        var probed = backend.scripts.stream().filter(sc -> sc.equals(TaskScripts.busy())).count();
        assertEquals(3, probed, "only alice's running instances held elsewhere are asked: " + backend.scripts);
    }

    /** A session's tasks and its instance. */
    private record Held(Tasks tasks, String instance) {}

    /** A session whose only other instance, mcp-hung, never answers until released. */
    private Held withAHungInstance(java.util.concurrent.CountDownLatch release,
                                   java.util.concurrent.atomic.AtomicInteger asked) {
        return withASlowInstance("mcp-hung", java.time.Duration.ofMillis(200), asked, () -> {
            try {
                release.await(); // a profile, or a FIFO, that never returns
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** A session whose only other instance, {@code other}, runs {@code answering} before saying it has one task. */
    private Held withASlowInstance(String other, java.time.Duration timeout,
                                   java.util.concurrent.atomic.AtomicInteger asked, Runnable answering) {
        var a = session(8);
        config.setMaxConcurrentTasks(5);
        var tasks = tasksOf(a);
        tasks.elsewhereTimeout = timeout;
        var instance = create(a, backend);
        other(other, DEAD, "alice");
        backend.instanceResponder = (name, script) -> {
            if (!name.equals(other)) return "";
            asked.incrementAndGet();
            answering.run();
            return "task t1-x running\njunk\n";
        };
        return new Held(tasks, instance);
    }

    @Test
    void anInstanceThatNeverAnswersCountsNothingAndIsNotAskedAgainForAWhile() {
        var release = new java.util.concurrent.CountDownLatch(1);
        var asked = new java.util.concurrent.atomic.AtomicInteger();
        var held = withAHungInstance(release, asked);
        var tasks = held.tasks();
        var instance = held.instance();
        try {
            tasks.startCommand(instance, "/", Map.of(), "make serve", null); // waits out the timeout once
            var started = System.nanoTime();
            tasks.startCommand(instance, "/", Map.of(), "make test", null);
            tasks.startCommand(instance, "/", Map.of(), "make lint", null);
            assertTrue(System.nanoTime() - started < 150_000_000L, "a silent instance is not waited for again");
            assertEquals(1, asked.get(), "nor asked again while it cools down");
            assertEquals(List.of(java.time.Duration.ofMillis(200)), backend.limits,
                    "the probe itself is bounded, by the same timeout");
        } finally {
            release.countDown();
        }
    }

    @Test
    void anInstanceThatAnswersLateStaysQuietAndCountsNothing() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var asked = new java.util.concurrent.atomic.AtomicInteger();
        var held = withAHungInstance(release, asked);
        held.tasks().startCommand(held.instance(), "/", Map.of(), "make serve", null); // times out: quiet
        release.countDown(); // ...then it answers, one running task: too late to be believed for long
        Thread.sleep(100);
        config.setMaxConcurrentTasks(2); // ours and its would fill it
        for (int i = 0; i < 3; i++) held.tasks().checkCapacityForNewAgent(); // not refused: it counts nothing
        assertEquals(1, asked.get(), "and is not asked again while it cools down");
    }

    @Test
    void anInstanceThatAnswersSlowlyIsCountedOnceThenCoolsDown() {
        var asked = new java.util.concurrent.atomic.AtomicInteger();
        var held = withASlowInstance("mcp-slow", java.time.Duration.ofMillis(400), asked, () -> {
            try {
                Thread.sleep(250); // in time, but past half of it: a FIFO a loop feeds just in time
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var tasks = held.tasks();
        var instance = held.instance();
        config.setMaxConcurrentTasks(1); // its one task fills the cap, if its answer is counted
        var e = assertThrows(ToolError.class, () -> tasks.startCommand(instance, "/", Map.of(), "make serve", null));
        assertTrue(e.getMessage().contains("mcp-slow 1"), "its slow answer still counts: " + e.getMessage());
        var started = System.nanoTime();
        tasks.startCommand(instance, "/", Map.of(), "make test", null); // cap still 1: it counts nothing now
        assertTrue(System.nanoTime() - started < 200_000_000L, "not waited for again");
        assertEquals(1, asked.get(), "slow to answer, it cools down like one that did not");
    }

    @Test
    void aHungProbeIsNeverJoinedByAnother() {
        var release = new java.util.concurrent.CountDownLatch(1);
        var asked = new java.util.concurrent.atomic.AtomicInteger();
        var held = withAHungInstance(release, asked);
        var tasks = held.tasks();
        var instance = held.instance();
        tasks.elsewhereCooldown = java.time.Duration.ZERO; // asked again at once: only the probe under way holds it
        try {
            for (int i = 0; i < 3; i++) tasks.startCommand(instance, "/", Map.of(), "make t" + i, null);
            assertEquals(1, asked.get(), "one exec in the instance, however many reservations wait on it");
        } finally {
            release.countDown();
        }
    }

    @Test
    void aSessionAloneAsksNoOtherInstance() {
        var a = session(8);
        config.setMaxConcurrentTasks(2);
        var tasks = tasksOf(a);
        var instance = create(a, backend);
        tasks.startCommand(instance, "/", Map.of(), "make serve", null);
        tasks.startCommand(instance, "/", Map.of(), "make test", null);
        assertFalse(backend.scripts.stream().anyMatch(sc -> sc.equals(TaskScripts.busy())), backend.scripts.toString());
        var e = assertThrows(ToolError.class, () -> tasks.startCommand(instance, "/", Map.of(), "make more", null));
        assertTrue(e.getMessage().contains("2 in this session, 0 in other sessions"), e.getMessage());
        assertFalse(e.getMessage().contains("adopt_instance"), "nothing elsewhere to point at: " + e.getMessage());
    }

    // --- idempotency keys (#1011) ---

    private static Map.Entry<String, Map<String, String>> made(String name, String createdAt) {
        return Map.entry(name, createdAt == null ? Map.of() : Map.of(InstanceBackend.CREATED_AT, createdAt));
    }

    @Test
    void ofTwoInstancesUnderOneKeyEverySessionPicksTheSameOne() {
        var earlier = made("mcp-dev-zz", "2026-10-06T08:40:45.118936073Z");
        var later = made("mcp-dev-aa", "2026-10-06T08:40:45.118936074Z");
        var tie = made("mcp-dev-ab", "2026-10-06T08:40:45.118936074Z");
        var unknown = made("mcp-dev-00", null);
        for (var order : List.of(List.of(earlier, later, tie, unknown), List.of(unknown, tie, later, earlier))) {
            assertEquals("mcp-dev-zz", order.stream().min(McpSession.FIRST_MADE).orElseThrow().getKey(),
                    "by Incus's record, whatever the names or the order listed");
        }
        assertTrue(McpSession.FIRST_MADE.compare(later, tie) < 0, "a tie goes by name");
        assertTrue(McpSession.FIRST_MADE.compare(unknown, later) > 0, "an unreadable time never comes first");
        assertEquals(-McpSession.FIRST_MADE.compare(later, tie), McpSession.FIRST_MADE.compare(tie, later));
    }

    @Test
    void aKeyThisSessionIsCreatingUnderIsBusyUntilTheCreateReturns() {
        var s = session(3);
        var name = s.reserve(TEMPLATE, null, null, "k1", null);
        var again = assertThrows(ToolError.class, () -> s.reserve(TEMPLATE, null, null, "k1", null));
        assertEquals(ToolError.Code.BUSY, again.code);
        backend.create(TEMPLATE, name, s.stamps(null, "k1"));
        // Listed already, as Incus lists a copy from the start: still the create in flight.
        var listing = s.listing();
        var found = s.keyed(listing, "k1");
        assertEquals(name, found.getKey());
        assertEquals(ToolError.Code.BUSY, assertThrows(ToolError.class, () -> s.replayable(name, found.getValue(), "k1")).code);
        s.created(name);
        assertFalse(s.replayable(name, s.keyed(s.listing(), "k1").getValue(), "k1"), "held: nothing to adopt");
        assertEquals("k1", s.instances().getFirst().key());
    }

    @Test
    void aSessionsOwnUnfinishedCopyCountsUnderItsKeyButADeadOnesDoesNot() {
        var s = session(3);
        other("mcp-dev-dead", DEAD, "alice", Metadata.MCP_IDEMPOTENCY_KEY, "k1");
        other("mcp-dev-live", ALIVE, "alice", Metadata.MCP_IDEMPOTENCY_KEY, "k2");
        other("mcp-dev-mine", SELF.toString(), "alice", Metadata.MCP_IDEMPOTENCY_KEY, "k3");
        var listing = s.listing();
        assertNull(s.keyed(listing, "k1"), "a dead session's copy that never got its address");
        assertEquals("mcp-dev-live", s.keyed(listing, "k2").getKey(), "a live one may still be configuring it");
        assertEquals("mcp-dev-mine", s.keyed(listing, "k3").getKey());
        backend.stamp("mcp-dev-dead", Metadata.STATIC_IP, "10.0.0.5");
        assertEquals("mcp-dev-dead", s.keyed(s.listing(), "k1").getKey(), "finished, it is an orphan like any other");
    }
}
