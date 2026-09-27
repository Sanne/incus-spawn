package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSessionTest {

    private static final SessionId SELF = new SessionId(4242, 1_700_000_000_000L);

    private final FakeBackend backend = new FakeBackend().template("tpl-dev", true);

    private McpSession session(int maxInstances) {
        var config = new McpConfig();
        config.setMaxInstances(maxInstances);
        return new McpSession(SELF, "alice", 99, "/home/alice/project", backend, () -> config);
    }

    private static String create(McpSession session, FakeBackend backend) {
        var name = session.reserve("tpl-dev", null);
        backend.create("tpl-dev", name, session.stamps());
        session.created(name);
        return name;
    }

    @Test
    void namesSayWhereTheyCameFrom() {
        var s = session(3);
        assertTrue(s.reserve("tpl-dev", null).matches("mcp-dev-[a-z2-7]{5}"));
        assertTrue(s.reserve("tpl-dev", "flaky-test").matches("mcp-dev-flaky-test-[a-z2-7]{5}"));
    }

    @Test
    void aHintCannotSmuggleCharactersIntoTheName() {
        var s = session(3);
        for (var hint : List.of("Upper", "semi;colon", "-leading", "much-too-long-a-hint", "a b")) {
            assertThrows(ToolError.class, () -> s.reserve("tpl-dev", hint), hint);
        }
    }

    @Test
    void theCapCountsCreatesStillInFlight() {
        var s = session(1);
        s.reserve("tpl-dev", null); // not created yet
        var e = assertThrows(ToolError.class, () -> s.reserve("tpl-dev", null));
        assertTrue(e.getMessage().contains("mcp.max-instances"), e.getMessage());
    }

    @Test
    void anAbandonedCreateFreesItsSlot() {
        var s = session(1);
        s.abandon(s.reserve("tpl-dev", null));
        s.reserve("tpl-dev", null);
    }

    @Test
    void keptInstancesDoNotCountAgainstTheCap() {
        var s = session(1);
        s.keep(create(s, backend));
        create(s, backend);
    }

    @Test
    void everyInstanceIsStampedWithTheSession() {
        var s = session(3);
        s.clientName("claude-code");
        var stamps = s.stamps();
        assertEquals("4242-1700000000000", stamps.get(Metadata.MCP_SESSION));
        assertEquals("alice", stamps.get(Metadata.MCP_OWNER));
        assertEquals("claude-code", stamps.get(Metadata.MCP_CLIENT));
        assertEquals("99", stamps.get(Metadata.MCP_CLIENT_PID));
        assertEquals("/home/alice/project", stamps.get(Metadata.MCP_CWD));
    }

    @Test
    void anInstanceThisSessionDidNotCreateIsRefused() {
        backend.instance("users-box", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE));
        var s = session(3);
        assertThrows(ToolError.class, () -> s.requireOwned("users-box"));
        assertThrows(ToolError.class, () -> s.requireOwned("tpl-dev"));
        assertThrows(ToolError.class, () -> s.destroy("users-box"));
        assertTrue(backend.instances.containsKey("users-box"));
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
        var name = s.reserve("tpl-dev", null);
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
    void reapingDestroysOnlyWhatWasNotKept() {
        var s = session(3);
        var kept = create(s, backend);
        var temp = create(s, backend);
        s.keep(kept);
        assertEquals(List.of(temp), s.reap());
        assertTrue(backend.instances.containsKey(kept));
        assertFalse(backend.instances.containsKey(temp));
    }

    @Test
    void reapingSkipsAnInstanceNoLongerOurs() {
        var s = session(3);
        var name = create(s, backend);
        backend.stamp(name, Metadata.MCP_SESSION, "1-1");
        assertEquals(List.of(), s.reap());
        assertTrue(backend.instances.containsKey(name));
    }

    @Test
    void destroyingForgetsTheInstance() {
        var s = session(3);
        var name = create(s, backend);
        assertTrue(s.destroy(name));
        assertEquals(List.of(), s.instances());
        assertThrows(ToolError.class, () -> s.destroy(name));
    }
}
