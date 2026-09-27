package dev.incusspawn.mcp;

import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrphanReaperTest {

    private static final SessionId SELF = new SessionId(1, 100);
    private static final String DEAD = "2-200";
    private static final String ALIVE = "3-300";

    private static Map<String, String> stamped(String session, String owner, String... extra) {
        var config = new java.util.HashMap<String, String>();
        config.put(Metadata.MCP_SESSION, session);
        config.put(Metadata.MCP_OWNER, owner);
        for (int i = 0; i < extra.length; i += 2) config.put(extra[i], extra[i + 1]);
        return config;
    }

    @Test
    void onlyThisUsersUnkeptInstancesOfDeadSessionsAreReaped() {
        var backend = new FakeBackend()
                .instance("orphan", stamped(DEAD, "alice"))
                .instance("live-session", stamped(ALIVE, "alice"))
                .instance("mine-now", stamped(SELF.toString(), "alice"))
                .instance("kept", stamped(DEAD, "alice", Metadata.MCP_KEPT, "2026-09-27"))
                .instance("other-user", stamped(DEAD, "bob"))
                .instance("busy", stamped(DEAD, "alice", Metadata.PENDING_OP, Metadata.OP_STOPPING))
                .instance("garbled", stamped("not-a-session", "alice"))
                .instance("plain", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE));
        var alive = Set.of(SessionId.parse(ALIVE).orElseThrow(), SELF);

        assertEquals(List.of("orphan"), OrphanReaper.reap(backend, SELF, "alice", alive::contains));
        assertEquals(List.of("orphan"), backend.destroyed);
    }

    @Test
    void theCurrentProcessIsAlive() {
        var self = SessionId.current();
        assertEquals(true, self.isAlive());
        assertEquals(false, new SessionId(self.pid(), self.startMillis() + 1).isAlive(),
                "same pid, different start: the pid was reused");
        assertEquals(self, SessionId.parse(self.toString()).orElseThrow());
    }
}
