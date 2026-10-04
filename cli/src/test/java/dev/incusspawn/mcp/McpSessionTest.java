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
        var name = session.reserve(TEMPLATE, null, null);
        backend.create(TEMPLATE, name, session.stamps(null));
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
    void anInstanceIncusCannotAnswerForIsStillHeld() {
        var s = session(3);
        var name = create(s, backend);
        backend.metadataFailure = new ToolError("cannot read it from Incus right now");
        assertTrue(s.stillHolds(name));
        assertThrows(RuntimeException.class, () -> s.requireOwned(name));
        backend.metadataFailure = null;
        s.requireOwned(name); // still held once Incus answers again
    }

    @Test
    void anInstanceStillBeingCreatedCannotBeAdoptedIntoReadiness() {
        var s = session(3);
        var name = s.reserve(TEMPLATE, null, null);
        backend.create(TEMPLATE, name, s.stamps(null)); // the copy exists and carries our stamp
        assertThrows(ToolError.class, () -> s.adopt(name, false));
        var e = assertThrows(ToolError.class, () -> s.requireOwned(name));
        assertTrue(e.getMessage().contains("still being created"), e.getMessage());
    }

    @Test
    void anInstanceDeletedBehindTheSessionsBackFreesItsPlace() {
        var s = session(1);
        var name = create(s, backend);
        backend.instances.remove(name); // deleted from the TUI: the session was not told
        s.reserve(TEMPLATE, null, null);
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
        assertTrue(s.reserve(TEMPLATE, null, null).matches("mcp-dev-[a-z2-7]{5}"));
        assertTrue(s.reserve(TEMPLATE, "870-impl", null).matches("mcp-dev-870-impl-[a-z2-7]{5}"));
    }

    @Test
    void aHintCannotSmuggleCharactersIntoTheName() {
        var s = session(3);
        for (var hint : List.of("Upper", "semi;colon", "-leading", "much-too-long-a-hint", "a b")) {
            assertThrows(ToolError.class, () -> s.reserve(TEMPLATE, hint, null), hint);
        }
    }

    @Test
    void aPurposeIsOneLine() {
        var s = session(3);
        assertThrows(ToolError.class, () -> s.reserve(TEMPLATE, null, "two\nlines"));
        assertThrows(ToolError.class, () -> s.reserve(TEMPLATE, null, "x".repeat(201)));
        assertEquals("#870 implement", s.stamps("  #870 implement ").get(Metadata.MCP_PURPOSE));
        assertFalse(s.stamps(" ").containsKey(Metadata.MCP_PURPOSE));
    }

    @Test
    void theCapCountsCreatesStillInFlight() {
        var s = session(1);
        s.reserve(TEMPLATE, null, null); // not created yet
        var e = assertThrows(ToolError.class, () -> s.reserve(TEMPLATE, null, null));
        assertTrue(e.getMessage().contains("mcp.max-instances"), e.getMessage());
    }

    @Test
    void theCapIsPerUserAcrossSessions() {
        other("held", ALIVE, "alice");
        other("orphan", DEAD, "alice");
        other("kept", DEAD, "alice", Metadata.MCP_KEPT, "2026-09-27");
        other("bobs", DEAD, "bob");
        var s = session(3);
        s.reserve(TEMPLATE, null, null);
        var e = assertThrows(ToolError.class, () -> s.reserve(TEMPLATE, null, null));
        assertTrue(e.getMessage().contains("1 in this session, 2 held by other sessions or orphaned"), e.getMessage());
    }

    @Test
    void anAbandonedCreateFreesItsSlot() {
        var s = session(1);
        s.abandon(s.reserve(TEMPLATE, null, null));
        s.reserve(TEMPLATE, null, null);
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
        var stamps = s.stamps("#870 implement");
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
        var name = s.reserve(TEMPLATE, null, null);
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
        var name = s.reserve(TEMPLATE, null, null);
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
                return "task t1-x running\n".repeat(busyIn.getOrDefault(instance, 0));
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

        aTasks.startCommand(aInstance, "/", Map.of(), "make serve");
        busyIn.put(aInstance, 1);
        busyIn.put("mcp-orphan", 1);
        busyIn.put("mcp-bobs", 5);
        busyIn.put("mcp-kept", 5);
        busyIn.put("mcp-kept-live", 1);
        busyIn.put("mcp-stopped", 5);

        var e = assertThrows(ToolError.class, () -> bTasks.startCommand(bInstance, "/", Map.of(), "make test"));
        assertTrue(e.getMessage().contains("mcp.max-concurrent-tasks") && e.getMessage().contains("(3)"),
                e.getMessage());
        assertTrue(e.getMessage().contains("0 in this session, 3 in other sessions or orphaned"), e.getMessage());

        busyIn.put("mcp-orphan", 0);
        backend.scripts.clear();
        bTasks.startCommand(bInstance, "/", Map.of(), "make test");
        var probed = backend.scripts.stream().filter(sc -> sc.equals(TaskScripts.busy())).count();
        assertEquals(3, probed, "only alice's running instances held elsewhere are asked: " + backend.scripts);
    }

    @Test
    void anInstanceThatNeverAnswersCountsNothingAndBlocksNothing() throws Exception {
        var a = session(8);
        config.setMaxConcurrentTasks(1);
        var tasks = tasksOf(a);
        tasks.elsewhereTimeout = java.time.Duration.ofMillis(200);
        var instance = create(a, backend);
        other("mcp-hung", DEAD, "alice");
        var release = new java.util.concurrent.CountDownLatch(1);
        backend.instanceResponder = (name, script) -> {
            if (name.equals("mcp-hung")) {
                try {
                    release.await(); // a su - that never returns
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "task t1-x running\n";
            }
            return "";
        };
        try {
            var started = System.nanoTime();
            tasks.startCommand(instance, "/", Map.of(), "make serve");
            assertTrue(System.nanoTime() - started < 5_000_000_000L, "waited for the hung instance");
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
        tasks.startCommand(instance, "/", Map.of(), "make serve");
        tasks.startCommand(instance, "/", Map.of(), "make test");
        assertFalse(backend.scripts.stream().anyMatch(sc -> sc.equals(TaskScripts.busy())), backend.scripts.toString());
        var e = assertThrows(ToolError.class, () -> tasks.startCommand(instance, "/", Map.of(), "make more"));
        assertTrue(e.getMessage().contains("2 in this session, 0 in other sessions"), e.getMessage());
        assertFalse(e.getMessage().contains("adopt_instance"), "nothing elsewhere to point at: " + e.getMessage());
    }
}
