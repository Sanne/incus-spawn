package dev.incusspawn.mcp;

import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** How {@code isx list} and the TUI tell a person an isx mcp instance stands (#1053). */
class McpStandingTest {

    private static final String DEAD = "2-200";
    private static final String ALIVE = "3-300";
    private static final Set<SessionId> LIVE = Set.of(SessionId.parse(ALIVE).orElseThrow());

    private static Map<String, String> stamped(String session, String... extra) {
        var config = new HashMap<String, String>();
        config.put(Metadata.MCP_OWNER, "alice");
        if (session != null) config.put(Metadata.MCP_SESSION, session);
        for (int i = 0; i < extra.length; i += 2) config.put(extra[i], extra[i + 1]);
        return config;
    }

    private static McpStanding standing(Map<String, String> config) {
        return McpStanding.of(config, LIVE::contains);
    }

    @Test
    void anInstanceNoSessionMadeHasNone() {
        assertNull(standing(Map.of(Metadata.TYPE, Metadata.TYPE_CLONE)));
        assertNull(standing(Map.of(Metadata.MCP_CALLER, "0123456789abcdef0123456789abcdef")),
                "a coordinator is not an instance a session made");
    }

    @Test
    void aLiveSessionHoldsItAndIsNamed() {
        var held = standing(stamped(ALIVE, Metadata.MCP_PURPOSE, "#870 implement",
                Metadata.MCP_CLIENT, "claude-code", Metadata.MCP_CWD, "/home/alice/repo"));
        assertEquals(McpStanding.State.HELD, held.state());
        assertEquals("isx mcp pid 3", held.holder());
        assertEquals("#870 implement", held.purpose());
        assertEquals("claude-code", held.client());
        assertEquals("/home/alice/repo", held.cwd());
        assertNull(held.orphanedSince());
    }

    @Test
    void aDeadOrReleasingSessionLeavesAnOrphan() {
        var dead = standing(stamped(DEAD));
        assertEquals(McpStanding.State.ORPHANED, dead.state());
        assertNull(dead.holder());
        assertNull(dead.orphanedSince(), "nobody has stamped it yet");

        var released = standing(stamped(ALIVE, Metadata.MCP_ORPHANED, "2026-10-05T08:00:00Z " + ALIVE));
        assertEquals(McpStanding.State.ORPHANED, released.state(), "its live holder released it");
        assertEquals(Instant.parse("2026-10-05T08:00:00Z"), released.orphanedSince());

        var stale = standing(stamped(ALIVE, Metadata.MCP_ORPHANED, "2026-10-05T08:00:00Z " + DEAD));
        assertEquals(McpStanding.State.HELD, stale.state(), "a stamp naming an earlier holder says nothing now");
    }

    @Test
    void keptIsTheUsersWhoeverHeldIt() {
        assertEquals(McpStanding.State.KEPT, standing(stamped(ALIVE, Metadata.MCP_KEPT, "2026-10-05")).state());
        assertEquals(McpStanding.State.KEPT, standing(stamped(DEAD, Metadata.MCP_KEPT, "2026-10-05")).state());
    }

    @Test
    void anUnreadableSessionCountsAsHeldByNobodyNamed() {
        var garbled = standing(stamped("not-a-session"));
        assertEquals(McpStanding.State.HELD, garbled.state());
        assertNull(garbled.holder());
    }

    @Test
    void aCoordinatorHoldsItsWorkersWhileListedWithItsGrant() {
        var grant = "0123456789abcdef0123456789abcdef";
        var worker = stamped("instance:coord:" + grant);
        var held = McpStanding.of(worker, Map.of("coord", grant));
        assertEquals(McpStanding.State.HELD, held.state());
        assertEquals("isx instance coord", held.holder());
        assertEquals(McpStanding.State.ORPHANED, McpStanding.of(worker, Map.of()).state(), "coordinator destroyed");
        assertEquals(McpStanding.State.ORPHANED,
                McpStanding.of(worker, Map.of("coord", "fedcba9876543210fedcba9876543210")).state(),
                "another coordinator by the same name does not hold its predecessor's workers");
    }

    @Test
    void aPersonIsToldWhatListInstancesTellsTheAgent() {
        var instances = Map.of(
                "held", stamped(ALIVE),
                "orphan", stamped(DEAD),
                "released", stamped(ALIVE, Metadata.MCP_ORPHANED, "2026-10-05T08:00:00Z " + ALIVE),
                "stale", stamped(ALIVE, Metadata.MCP_ORPHANED, "2026-10-05T08:00:00Z " + DEAD),
                "garbled", stamped("not-a-session"));
        var others = Orphans.othersOf(instances, "alice", new SessionId(99, 99), LIVE::contains);
        assertEquals(instances.keySet(), others.keySet());
        others.forEach((name, other) -> assertEquals(other.orphaned(),
                standing(instances.get(name)).state() == McpStanding.State.ORPHANED, name));
    }
}
