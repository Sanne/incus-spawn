package dev.incusspawn.mcp;

import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrphansTest {

    private static final SessionId SELF = new SessionId(1, 100);
    private static final String DEAD = "2-200";
    private static final String ALIVE = "3-300";
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final Duration GRACE = Duration.ofHours(24);
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

        var destroyed = Orphans.sweep(backend, SELF, "alice", alive::contains, GRACE, NOW, "attended"::equals);

        assertEquals(List.of("expired"), destroyed);
        assertEquals(List.of("expired"), backend.destroyed);
        assertEquals(1, backend.proxyRefreshes);
        assertEquals(NOW.toString(), backend.instances.get("unstamped").get(Metadata.MCP_ORPHANED),
                "a killed session's orphan starts its grace period when first noticed");
    }

    @Test
    void anOrphanAdoptedDuringTheSweepIsSpared() {
        var backend = new FakeBackend().instance("expired", stamped(DEAD, "alice", Metadata.MCP_ORPHANED, LONG_AGO));
        var destroyed = Orphans.sweep(backend, SELF, "alice", alive::contains, GRACE, NOW, name -> {
            backend.stamp(name, Metadata.MCP_SESSION, ALIVE); // adopted while we looked inside
            return false;
        });
        assertEquals(List.of(), destroyed);
    }

    @Test
    void theCurrentProcessIsAlive() {
        var self = SessionId.current();
        assertTrue(self.isAlive());
        assertFalse(new SessionId(self.pid(), self.startMillis() + 1).isAlive(),
                "same pid, different start: the pid was reused");
        assertEquals(self, SessionId.parse(self.toString()).orElseThrow());
    }
}
