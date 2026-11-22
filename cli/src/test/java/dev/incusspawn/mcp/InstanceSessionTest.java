package dev.incusspawn.mcp;

import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.incus.Metadata;
import io.hyperfoil.tools.jjq.value.JqValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session of an isx instance calling {@code isx mcp} through the proxy (#915): the instance
 * is the session, so what it made stays held across its restarts and is orphaned only once the
 * instance is gone or may no longer call.
 */
@ExtendWith(IsolatedHome.class) // the audit log goes there
class InstanceSessionTest {

    private static final String COORD = "coord";
    private static final String GRANT = "0123456789abcdef0123456789abcdef";
    private static final SessionId SELF = SessionId.ofInstance(COORD, GRANT);

    private final FakeBackend backend = new FakeBackend().template("tpl-dev", true, "claude");
    private static final InstanceBackend.TemplateInfo TEMPLATE =
            new InstanceBackend.TemplateInfo("tpl-dev", "", true, false, List.of(), null, false, Map.of());
    private final McpConfig config = new McpConfig();
    private final AtomicLong clock = new AtomicLong();
    private final CallerLiveness alive = new CallerLiveness(backend, clock::get);

    InstanceSessionTest() {
        config.setTemplates(List.of("tpl-dev"));
        config.setMaxInstances(2);
        backend.instance(COORD, Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.MCP_CALLER, GRANT));
    }

    /** The coordinator's scope: its template, {@code tpl-coord}, lists {@code tpl-dev} under {@code control-over}. */
    private static final java.util.function.Function<McpConfig, dev.incusspawn.config.McpScope> COORDINATOR =
            controlling(() -> List.of("tpl-dev"));

    /** The scope of a coordinator of {@code tpl-coord}, whose {@code control-over} is read from {@code controls} per call. */
    private static java.util.function.Function<McpConfig, dev.incusspawn.config.McpScope> controlling(
            Supplier<List<String>> controls) {
        return c -> {
            var def = new dev.incusspawn.config.ImageDef();
            def.setName("tpl-coord");
            var coordinator = new dev.incusspawn.config.ImageDef.Coordinator();
            coordinator.setControlOver(List.copyOf(controls.get()));
            def.setCoordinator(coordinator);
            return dev.incusspawn.config.McpScope.coordinator("tpl-coord", null, Map.of("tpl-coord", def), c.templates());
        };
    }

    /** A connection of the coordinator: a new {@code isx mcp --caller-instance coord}. */
    private McpSession connect() {
        return connect(SELF);
    }

    private McpSession connect(SessionId as) {
        return new McpSession(as, "alice", -1, null, backend, () -> config, COORDINATOR, alive);
    }

    /** {@link #COORD} destroyed and branched again with --mcp-client: same name, a new grant. */
    private SessionId recreateCoordinator() {
        var grant = Metadata.newMcpCallerGrant();
        backend.instances.remove(COORD);
        backend.instance(COORD, Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.MCP_CALLER, grant));
        clock.addAndGet(CallerLiveness.REMEMBER.toNanos());
        return SessionId.ofInstance(COORD, grant);
    }

    private String create(McpSession session) {
        var name = session.reserve(TEMPLATE, null, null, null, null);
        backend.create(TEMPLATE, name, session.stamps(null, null));
        session.created(name);
        return name;
    }

    @Test
    void theStampNamesTheInstanceAndReadsBack() {
        assertEquals("instance:coord:" + GRANT, SELF.toString());
        assertEquals(SELF, SessionId.parse("instance:coord:" + GRANT).orElseThrow());
        assertEquals(new SessionId(42, 7), SessionId.parse("42-7").orElseThrow());
        assertTrue(SessionId.parse("instance:").isEmpty());
        assertTrue(SessionId.parse("instance:coord").isEmpty(), "a name alone is not a coordinator");
        assertTrue(SessionId.parse("instance:no spaces:" + GRANT).isEmpty());
        assertTrue(SessionId.parse("instance:coord:2026-10-05T10:00:00").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SessionId.ofInstance("-leading-dash", GRANT));
        assertThrows(IllegalArgumentException.class, () -> SessionId.ofInstance(COORD, null));
        assertTrue(Metadata.MCP_CALLER_GRANT.matcher(Metadata.newMcpCallerGrant()).matches());
        assertNotEquals(Metadata.newMcpCallerGrant(), Metadata.newMcpCallerGrant());
    }

    @Test
    void anInstanceSessionLeavesNoHostTraceOnWhatItMakes() {
        var name = create(connect());
        var stamps = backend.instances.get(name);
        assertEquals("instance:coord:" + GRANT, stamps.get(Metadata.MCP_SESSION));
        assertFalse(stamps.containsKey(Metadata.MCP_CWD));
        assertFalse(stamps.containsKey(Metadata.MCP_CLIENT_PID));
        assertFalse(stamps.containsKey(Metadata.MCP_CALLER), "a worker can never call isx mcp itself");
    }

    @Test
    void whatItHeldIsStillItsAfterItsConnectionEnds() {
        var first = connect();
        var worker = create(first);
        assertEquals(List.of(), first.release(), "ending a connection releases nothing");
        assertFalse(backend.instances.get(worker).containsKey(Metadata.MCP_ORPHANED));

        var next = connect();
        assertEquals(List.of(worker), List.copyOf(next.resumeIfPending().keySet()));
        next.requireOwned(worker);
        // The cap counts it from the start: one more fits, a third does not.
        create(next);
        assertThrows(ToolError.class, () -> next.reserve(TEMPLATE, null, null, null, null));
    }

    @Test
    void aCoordinatorRecreatedUnderTheSameNameDoesNotTakeTheOldOnesWorkers() {
        var worker = create(connect());
        var successor = connect(recreateCoordinator());
        assertEquals(Map.of(), successor.resumeIfPending(), "nothing of its predecessor's is held again");
        assertThrows(ToolError.class, () -> successor.requireOwned(worker));
        // The predecessor is gone: its worker is an orphan, on the clock and counted as such.
        var others = Orphans.othersOf(backend.mcpInstances(), "alice", new SessionId(1, 1), alive);
        assertTrue(others.get(worker).orphaned());
        successor.reserve(TEMPLATE, null, null, null, null);
        assertThrows(ToolError.class, () -> successor.reserve(TEMPLATE, null, null, null, null),
                "the orphan still counts against the cap until it is reaped");
    }

    /** The tools as {@code isx mcp} serves them to {@code session}, and the requests it answers. */
    private final class Served {
        final McpServerProtocolTest.Captured out = new McpServerProtocolTest.Captured();
        final McpServer server;
        final StructuredResults results;
        int nextId = 1;

        Served(McpSession session, Supplier<McpConfig> policy) {
            var tasks = new Tasks(session, backend, policy);
            var tools = McpMain.requireInitAndTakeBack(
                    new McpTools(session, backend, new TemplatePolicy(backend, policy, COORDINATOR), tasks).all(),
                    () -> true, () -> McpMain.takeBack(session, tasks));
            server = new McpServer(out, tools, "1", null, null);
            results = new StructuredResults(tools);
        }

        JqValue call(String tool, String args) throws Exception {
            var id = nextId++;
            server.handle("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                    + tool + "\",\"arguments\":" + args + "}}");
            server.awaitIdle();
            return results.check(tool, out.byId(id).path("result"));
        }
    }

    /**
     * A connection that could not take back what it held -- mcp.yaml refused, as after an upgrade
     * left it to be created (#1514) -- takes it back on its next tool call once that is fixed:
     * listed, counted against the cap and its own, not left to a reconnect or adopt_instance.
     */
    @Test
    void aTakeBackThatFailedIsTriedAgainByTheNextCall() throws Exception {
        var worker = create(connect());
        var refused = new AtomicBoolean(true);
        Supplier<McpConfig> policy = () -> {
            if (refused.get()) throw new ToolError(ToolError.Code.REFUSED, "mcp.yaml does not exist yet");
            return config;
        };
        var next = new McpSession(SELF, "alice", -1, null, backend, policy, COORDINATOR, alive);
        var served = new Served(next, policy);
        assertTrue(served.call("list_instances", "{}").path("isError").asBoolean(false));
        assertFalse(next.holds(worker), "nothing is taken back while mcp.yaml is refused");

        refused.set(false);
        var listed = served.call("list_instances", "{}").path("structuredContent").path("instances");
        assertEquals(1, listed.size(), listed.toString());
        assertEquals(worker, listed.getElement(0).path("instance").asText());
        assertEquals("ready", listed.getElement(0).path("state").asText(), "held by this session again");
        next.requireOwned(worker);
        // The cap counts it again: one more fits, a third does not.
        create(next);
        assertThrows(ToolError.class, () -> next.reserve(TEMPLATE, null, null, null, null));
    }

    /** No call goes ahead without what it could not take back: here, a create past the cap. */
    @Test
    void noCallRunsWithoutWhatItCouldNotTakeBack() throws Exception {
        create(connect());
        create(connect());
        var next = connect();
        var served = new Served(next, () -> config);
        backend.onListing = () -> { throw new IllegalStateException("Incus is restarting"); };
        // A call that lists nothing itself: only the take-back can refuse it.
        var refused = served.call("list_templates", "{}");
        assertTrue(refused.path("isError").asBoolean(false), refused.toString());
        assertTrue(refused.toString().contains("Incus is restarting"), refused.toString());

        backend.onListing = null;
        var full = served.call("create_instance", "{\"template\":\"tpl-dev\"}");
        assertTrue(full.toString().contains("max-instances"), "both are counted: " + full);
        assertEquals(2, next.instances().size());
    }

    /** Calls that waited on a failing attempt share it: one listing, not one per call. */
    @Test
    void callsWaitingOnAFailedTakeBackShareItsFailure() throws Exception {
        var next = connect();
        var outcomes = whileOneWaits(next, () -> {
            throw new IllegalStateException("Incus is restarting");
        });
        assertEquals("Incus is restarting", outcomes[0].getMessage());
        assertEquals("Incus is restarting", outcomes[1].getMessage());
        assertEquals(1, backend.listings.get(), "the waiting call did not list again");

        backend.onListing = null;
        next.resumeIfPending();
        assertEquals(2, backend.listings.get(), "a later call tries again");
    }

    /** An attempt an Error ended has no failure to share: the call that waited makes its own. */
    @Test
    void aCallWaitingOnAnAttemptAnErrorEndedMakesItsOwn() throws Exception {
        var next = connect();
        var worker = create(connect());
        var before = backend.listings.get();
        var outcomes = whileOneWaits(next, () -> {
            throw new AssertionError("a bug");
        });
        assertEquals("a bug", outcomes[0].getMessage());
        assertNull(outcomes[1], "taken back by its own attempt");
        assertEquals(before + 2, backend.listings.get());
        next.requireOwned(worker);
    }

    /**
     * Two calls to {@code session}'s take-back: the first fails in its listing by running {@code fail},
     * which only that first listing does, once the second is waiting on it. What each threw, or null.
     */
    private Throwable[] whileOneWaits(McpSession session, Runnable fail) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        backend.onListing = () -> {
            if (!first.getAndSet(false)) return;
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            fail.run();
        };
        var outcomes = new Throwable[2];
        var a = new Thread(() -> outcomes[0] = failureOf(session));
        a.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the first call never reached its listing");
        var b = new Thread(() -> outcomes[1] = failureOf(session));
        b.start();
        dev.incusspawn.util.Eventually.await("the second call to wait on the first",
                () -> b.getState() == Thread.State.BLOCKED);
        release.countDown();
        a.join();
        b.join();
        return outcomes;
    }

    private static Throwable failureOf(McpSession session) {
        try {
            session.resumeIfPending();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    /** One release that cannot be stamped does not hold up taking back the rest. */
    @Test
    void aReleaseThatFailsDoesNotFailTheTakeBack() {
        var worker = create(connect());
        backend.instance("mcp-other-unapproved", Map.of(Metadata.MCP_OWNER, "alice", Metadata.MCP_SESSION, SELF.toString(),
                Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PARENT, "tpl-gone"));
        backend.onStamp = () -> { throw new IllegalStateException("Incus is restarting"); };
        var next = connect();
        assertEquals(List.of(worker), List.copyOf(next.resumeIfPending().keySet()));
        next.requireOwned(worker);
    }

    @Test
    void resumeReleasesWhatIsNoLongerApprovedInsteadOfTakingItBack() {
        var worker = create(connect());
        config.setTemplates(List.of("tpl-other"));
        var next = connect();
        assertEquals(Map.of(), next.resumeIfPending());
        assertThrows(ToolError.class, () -> next.requireOwned(worker));
        assertEquals(SELF.toString(), backend.instances.get(worker).get(Metadata.MCP_SESSION));
        assertTrue(Orphans.orphanedSince(backend.instances.get(worker)) != null, "its orphan clock runs");
        var others = Orphans.othersOf(backend.mcpInstances(), "alice", new SessionId(1, 1), alive);
        assertTrue(others.get(worker).orphaned(), "an orphan to every session, though its holder may still call");
        // To the coordinator itself too: it counts against its cap, and its own sweep reaps it.
        assertTrue(next.others().containsKey(worker), "listed and counted by the session that released it");
        next.reserve(TEMPLATE, null, null, null, null);
        assertThrows(ToolError.class, () -> next.reserve(TEMPLATE, null, null, null, null));
        var later = Instant.now().plus(Duration.ofHours(2));
        assertEquals(List.of(worker), Orphans.sweep(backend, SELF, "alice", alive, windows(Duration.ofHours(1)), later,
                n -> Orphans.Use.NOBODY).destroyed());
        backend.create(TEMPLATE, worker, connect().stamps(null, null));
        backend.stamp(worker, Metadata.MCP_ORPHANED, Orphans.orphanedStamp(Instant.now(), SELF.toString()));
        // And it stays released: approving the template again does not hand it back silently.
        config.setTemplates(List.of("tpl-dev"));
        assertEquals(Map.of(), connect().resumeIfPending());
    }

    @Test
    void anInstanceSessionMadeWithoutItsScopeControlsNothing() {
        // #1812: the constructor that gives mcp.yaml's whole list is the host's; an instance session
        // built with it fails closed rather than widening to that list
        var worker = create(connect());
        var unscoped = new McpSession(SELF, "alice", -1, null, backend, () -> config, alive);
        assertEquals(Map.of(), unscoped.resumeIfPending(), "nothing taken back: tpl-dev is out of its scope");
        var e = assertThrows(ToolError.class, () -> unscoped.adopt(worker, true));
        assertTrue(e.getMessage().contains("controls no template"), e.getMessage());
    }

    @Test
    void resumeReleasesWhatTheCoordinatorsOwnTemplateNoLongerLists() {
        // #1812: narrowing the definition's coordinator.control-over reaches the coordinator as
        // narrowing mcp.yaml does
        var controls = new java.util.ArrayList<>(List.of("tpl-dev"));
        var scope = controlling(() -> controls);
        var worker = create(new McpSession(SELF, "alice", -1, null, backend, () -> config, scope, alive));
        controls.clear();
        var next = new McpSession(SELF, "alice", -1, null, backend, () -> config, scope, alive);
        assertEquals(Map.of(), next.resumeIfPending());
        assertTrue(Orphans.orphanedSince(backend.instances.get(worker)) != null, "released, its orphan clock running");
        var e = assertThrows(ToolError.class, () -> next.adopt(worker, true));
        assertTrue(e.getMessage().contains("not in coordinator.control-over of tpl-coord"), e.getMessage());
    }

    @Test
    void resumeTakesOnlyWhatThisInstanceHeldAndDidNotKeep() {
        var s = connect();
        var kept = create(s);
        s.keep(kept);
        backend.instance("mcp-someone-elses", Map.of(Metadata.MCP_OWNER, "alice", Metadata.MCP_SESSION, "instance:other:" + GRANT));
        backend.instance("mcp-other-user", Map.of(Metadata.MCP_OWNER, "bob", Metadata.MCP_SESSION, SELF.toString()));
        assertEquals(Map.of(), connect().resumeIfPending());
    }

    @Test
    void aProcessSessionResumesNothing() {
        connect();
        var stdio = new McpSession(new SessionId(1, 1), "alice", 1, "/w", backend, () -> config, alive);
        assertEquals(Map.of(), stdio.resumeIfPending());
    }

    @Test
    void anotherSessionCannotTakeItsWorkersWhileItMayStillCall() {
        var worker = create(connect());
        var stdio = new McpSession(new SessionId(1, 1), "alice", 1, "/w", backend, () -> config, alive);
        var refusal = assertThrows(ToolError.class, () -> stdio.adopt(worker, false));
        assertTrue(refusal.getMessage().contains("isx instance coord"), refusal.getMessage());
    }

    @Test
    void itsWorkersAreOrphanedOnceItCanNoLongerCall() {
        var worker = create(connect());
        var mine = new SessionId(1, 1);
        var grace = Duration.ofHours(1);
        var now = Instant.parse("2026-10-05T12:00:00Z");
        Orphans.sweep(backend, mine, "alice", alive, windows(grace), now, n -> Orphans.Use.NOBODY);
        assertFalse(backend.instances.get(worker).containsKey(Metadata.MCP_ORPHANED), "held while it may call");

        recreateCoordinator();
        Orphans.sweep(backend, mine, "alice", alive, windows(grace), now, n -> Orphans.Use.NOBODY);
        assertTrue(backend.instances.get(worker).containsKey(Metadata.MCP_ORPHANED));
    }

    @Test
    void aDestroyedCoordinatorsWorkersAreOrphans() {
        var worker = create(connect());
        backend.instances.remove(COORD);
        var others = Orphans.othersOf(backend.mcpInstances(), "alice", new SessionId(1, 1), alive);
        assertTrue(others.get(worker).orphaned());
    }

    @Test
    void anInstanceIncusCannotAnswerForStillHolds() {
        var worker = create(connect());
        backend.metadataFailure = new ToolError(ToolError.Code.UNAVAILABLE, "Incus is restarting");
        var others = Orphans.othersOf(backend.mcpInstances(), "alice", new SessionId(1, 1), alive);
        assertFalse(others.get(worker).orphaned());
    }

    @Test
    void livenessIsOneReadPerInstanceWhileRemembered() {
        create(connect());
        var reads = backend.metadataReads.get();
        for (int i = 0; i < 5; i++) alive.test(SELF);
        assertEquals(reads + 1, backend.metadataReads.get());
    }

    private static Orphans.Windows windows(Duration grace) {
        return new Orphans.Windows(grace, Duration.ofHours(24), Duration.ofHours(168));
    }
}
