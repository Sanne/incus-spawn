package dev.incusspawn.mcp;

import dev.incusspawn.incus.Metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Destroys instances left behind by MCP sessions that ended without reaping -- the client was
 * killed, the machine lost power. Runs when a new session starts, off the handshake's path.
 *
 * <p>Only this host user's instances are considered, and only ones that were never kept and are
 * not in the middle of another operation. An instance's session is dead when its process no
 * longer exists, or a different process now has its pid.
 */
final class OrphanReaper {

    private OrphanReaper() {}

    static List<String> reap(InstanceBackend backend, SessionId self, String owner,
                             Predicate<SessionId> alive) {
        var reaped = new ArrayList<String>();
        backend.mcpInstances().forEach((name, config) -> {
            if (!owner.equals(config.get(Metadata.MCP_OWNER))) return;
            if (config.containsKey(Metadata.MCP_KEPT)) return;
            if (!config.getOrDefault(Metadata.PENDING_OP, "").isEmpty()) return;
            var session = SessionId.parse(config.get(Metadata.MCP_SESSION));
            // An unparseable stamp is not ours to judge: leave it for the user.
            if (session.isEmpty() || session.get().equals(self) || alive.test(session.get())) return;
            try {
                backend.destroy(name);
                reaped.add(name);
            } catch (RuntimeException e) {
                System.err.println("isx mcp: could not reap orphaned instance " + name + ": " + e.getMessage());
            }
        });
        if (!reaped.isEmpty()) backend.refreshProxy();
        return reaped;
    }
}
