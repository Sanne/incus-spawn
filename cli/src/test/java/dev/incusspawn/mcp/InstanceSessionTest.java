package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session of an isx instance calling {@code isx mcp} through the proxy (#915): the instance
 * is the session, so what it made stays held across its restarts and is orphaned only once the
 * instance is gone or may no longer call.
 */
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

    /** A connection of the coordinator: a new {@code isx mcp --caller-instance coord}. */
    private McpSession connect() {
        return connect(SELF);
    }

    private McpSession connect(SessionId as) {
        return new McpSession(as, "alice", -1, null, backend, () -> config, alive);
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
        var name = session.reserve(TEMPLATE, null, null);
        backend.create(TEMPLATE, name, session.stamps(null));
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
        assertEquals(List.of(worker), List.copyOf(next.resume().keySet()));
        next.requireOwned(worker);
        // The cap counts it from the start: one more fits, a third does not.
        create(next);
        assertThrows(ToolError.class, () -> next.reserve(TEMPLATE, null, null));
    }

    @Test
    void aCoordinatorRecreatedUnderTheSameNameDoesNotTakeTheOldOnesWorkers() {
        var worker = create(connect());
        var successor = connect(recreateCoordinator());
        assertEquals(Map.of(), successor.resume(), "nothing of its predecessor's is held again");
        assertThrows(ToolError.class, () -> successor.requireOwned(worker));
        // The predecessor is gone: its worker is an orphan, on the clock and counted as such.
        var others = Orphans.othersOf(backend.mcpInstances(), "alice", new SessionId(1, 1), alive);
        assertTrue(others.get(worker).orphaned());
        successor.reserve(TEMPLATE, null, null);
        assertThrows(ToolError.class, () -> successor.reserve(TEMPLATE, null, null),
                "the orphan still counts against the cap until it is reaped");
    }

    @Test
    void resumeReleasesWhatIsNoLongerApprovedInsteadOfTakingItBack() {
        var worker = create(connect());
        config.setTemplates(List.of("tpl-other"));
        var next = connect();
        assertEquals(Map.of(), next.resume());
        assertThrows(ToolError.class, () -> next.requireOwned(worker));
        assertEquals(SELF.toString(), backend.instances.get(worker).get(Metadata.MCP_SESSION));
        assertTrue(Orphans.orphanedSince(backend.instances.get(worker)) != null, "its orphan clock runs");
        var others = Orphans.othersOf(backend.mcpInstances(), "alice", new SessionId(1, 1), alive);
        assertTrue(others.get(worker).orphaned(), "an orphan to every session, though its holder may still call");
        // To the coordinator itself too: it counts against its cap, and its own sweep reaps it.
        assertTrue(next.others().containsKey(worker), "listed and counted by the session that released it");
        next.reserve(TEMPLATE, null, null);
        assertThrows(ToolError.class, () -> next.reserve(TEMPLATE, null, null));
        var later = Instant.now().plus(Duration.ofHours(2));
        assertEquals(List.of(worker), Orphans.sweep(backend, SELF, "alice", alive, Duration.ofHours(1), later, n -> false));
        backend.create(TEMPLATE, worker, connect().stamps(null));
        backend.stamp(worker, Metadata.MCP_ORPHANED, Orphans.orphanedStamp(Instant.now(), SELF.toString()));
        // And it stays released: approving the template again does not hand it back silently.
        config.setTemplates(List.of("tpl-dev"));
        assertEquals(Map.of(), connect().resume());
    }

    @Test
    void resumeTakesOnlyWhatThisInstanceHeldAndDidNotKeep() {
        var s = connect();
        var kept = create(s);
        s.keep(kept);
        backend.instance("mcp-someone-elses", Map.of(Metadata.MCP_OWNER, "alice", Metadata.MCP_SESSION, "instance:other:" + GRANT));
        backend.instance("mcp-other-user", Map.of(Metadata.MCP_OWNER, "bob", Metadata.MCP_SESSION, SELF.toString()));
        assertEquals(Map.of(), connect().resume());
    }

    @Test
    void aProcessSessionResumesNothing() {
        connect();
        var stdio = new McpSession(new SessionId(1, 1), "alice", 1, "/w", backend, () -> config, alive);
        assertEquals(Map.of(), stdio.resume());
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
        Orphans.sweep(backend, mine, "alice", alive, grace, now, n -> false);
        assertFalse(backend.instances.get(worker).containsKey(Metadata.MCP_ORPHANED), "held while it may call");

        recreateCoordinator();
        Orphans.sweep(backend, mine, "alice", alive, grace, now, n -> false);
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
}
