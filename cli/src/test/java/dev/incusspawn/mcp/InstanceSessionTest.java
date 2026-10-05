package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session of an isx instance calling {@code isx mcp} through the proxy (#915): the instance
 * is the session, so what it made stays held across its restarts and is orphaned only once the
 * instance is gone or may no longer call.
 */
class InstanceSessionTest {

    private static final String COORD = "coord";
    private static final SessionId SELF = SessionId.ofInstance(COORD);

    private final FakeBackend backend = new FakeBackend().template("tpl-dev", true, "claude");
    private static final InstanceBackend.TemplateInfo TEMPLATE =
            new InstanceBackend.TemplateInfo("tpl-dev", "", true, false, List.of(), null, false, Map.of());
    private final McpConfig config = new McpConfig();
    private final AtomicLong clock = new AtomicLong();
    private final CallerLiveness alive = new CallerLiveness(backend, clock::get);

    InstanceSessionTest() {
        config.setTemplates(List.of("tpl-dev"));
        config.setMaxInstances(2);
        backend.instance(COORD, Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.MCP_CALLER, "2026-10-05T10:00:00"));
    }

    /** A connection of the coordinator: a new {@code isx mcp --caller-instance coord}. */
    private McpSession connect() {
        return new McpSession(SELF, "alice", -1, null, backend, () -> config, alive);
    }

    private String create(McpSession session) {
        var name = session.reserve(TEMPLATE, null, null);
        backend.create(TEMPLATE, name, session.stamps(null));
        session.created(name);
        return name;
    }

    @Test
    void theStampNamesTheInstanceAndReadsBack() {
        assertEquals("instance:coord", SELF.toString());
        assertEquals(SELF, SessionId.parse("instance:coord").orElseThrow());
        assertEquals(new SessionId(42, 7), SessionId.parse("42-7").orElseThrow());
        assertTrue(SessionId.parse("instance:").isEmpty());
        assertTrue(SessionId.parse("instance:no spaces").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SessionId.ofInstance("-leading-dash"));
    }

    @Test
    void anInstanceSessionLeavesNoHostTraceOnWhatItMakes() {
        var name = create(connect());
        var stamps = backend.instances.get(name);
        assertEquals("instance:coord", stamps.get(Metadata.MCP_SESSION));
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
    void resumingStopsAnOrphanClockStartedWhileItCouldNotCall() {
        var worker = create(connect());
        backend.stamp(worker, Metadata.MCP_ORPHANED, Orphans.orphanedStamp(Instant.EPOCH, SELF.toString()));
        connect().resume();
        assertFalse(backend.instances.get(worker).containsKey(Metadata.MCP_ORPHANED));
    }

    @Test
    void resumeTakesOnlyWhatThisInstanceHeldAndDidNotKeep() {
        var s = connect();
        var kept = create(s);
        s.keep(kept);
        backend.instance("mcp-someone-elses", Map.of(Metadata.MCP_OWNER, "alice", Metadata.MCP_SESSION, "instance:other"));
        backend.instance("mcp-other-user", Map.of(Metadata.MCP_OWNER, "bob", Metadata.MCP_SESSION, "instance:coord"));
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

        var withoutStamp = new HashMap<>(backend.instances.get(COORD));
        withoutStamp.remove(Metadata.MCP_CALLER);
        backend.instances.put(COORD, new java.util.concurrent.ConcurrentHashMap<>(withoutStamp));
        clock.addAndGet(CallerLiveness.REMEMBER.toNanos());
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
