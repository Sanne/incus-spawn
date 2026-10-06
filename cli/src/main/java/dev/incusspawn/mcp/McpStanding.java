package dev.incusspawn.mcp;

import dev.incusspawn.incus.Metadata;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * How an instance an {@code isx mcp} session made stands, for a person reading {@code isx list}
 * or the TUI (#1053): held by a session, orphaned (its session ended or released it), or kept
 * (handed to the user). The rules are {@code list_instances}' ({@link Orphans#othersOf}), so the
 * person and the agent are told the same thing.
 *
 * <p>Read from one instance listing and nothing else: a process session is alive while its
 * process runs (a local read, see {@link SessionId#isAlive}), and a coordinator instance's session
 * while that instance is in the same listing with the same grant. The listing a person asked for
 * pays no further Incus request.
 *
 * @param purpose       what the agent said the instance is for, or null
 * @param holder        who holds it, as a person reads it ({@link SessionId#describe}), or null
 *                      when it is not held or the stamp names nobody
 * @param client        the MCP client of the session that made or adopted it, or null
 * @param cwd           where that session runs, or null
 * @param orphanedSince when it became an orphan, or null when that is not known yet
 */
public record McpStanding(State state, String purpose, String holder, String client, String cwd,
                          Instant orphanedSince) {

    public enum State {
        HELD, ORPHANED, KEPT;

        /** The value {@code isx list --format=plain|json} prints: part of its contract. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * How the instance with this config stands, or null when no {@code isx mcp} session made it.
     *
     * @param callerGrants every listed instance's {@link Metadata#MCP_CALLER} grant, by name
     */
    public static McpStanding of(Map<String, String> config, Map<String, String> callerGrants) {
        return of(config, session -> session.isInstance()
                ? session.grantedBy(callerGrants.get(session.instance()))
                : session.isAlive());
    }

    static McpStanding of(Map<String, String> config, Predicate<SessionId> alive) {
        if (!config.containsKey(Metadata.MCP_OWNER)) return null;
        var session = SessionId.parse(config.get(Metadata.MCP_SESSION));
        var state = config.containsKey(Metadata.MCP_KEPT) ? State.KEPT
                : Orphans.orphaned(config, session, alive) ? State.ORPHANED : State.HELD;
        // An unreadable session stamp counts as held, but names nobody.
        return new McpStanding(state, nonBlank(config.get(Metadata.MCP_PURPOSE)),
                state == State.HELD ? session.map(SessionId::describe).orElse(null) : null,
                nonBlank(config.get(Metadata.MCP_CLIENT)), nonBlank(config.get(Metadata.MCP_CWD)),
                state == State.ORPHANED ? Orphans.orphanedSince(config) : null);
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
