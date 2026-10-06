package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.incus.Metadata;

import java.time.Instant;
import java.util.HashMap;
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
 * @param dormantSince  when a sweep stopped the orphan as dormant (#1028), or null; only while it
 *                      is stopped, as {@code list_instances} reports {@code dormant_since}
 */
public record McpStanding(State state, String purpose, String holder, String client, String cwd,
                          Instant orphanedSince, Instant dormantSince) {

    public enum State {
        HELD, ORPHANED, KEPT;

        /** The value {@code isx list --format=plain|json} prints: part of its contract. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Every instance's {@link Metadata#MCP_CALLER} grant in an Incus instance listing, by name. */
    public static Map<String, String> callerGrants(JsonNode listing) {
        var grants = new HashMap<String, String>();
        for (var node : listing) {
            var grant = node.path("config").path(Metadata.MCP_CALLER).asText("");
            if (!grant.isEmpty()) grants.put(node.path("name").asText(), grant);
        }
        return grants;
    }

    /**
     * How an instance of an Incus listing stands, or null when no {@code isx mcp} session made
     * it. Its {@code mcp-} stamps and status, as {@link InstanceBackend#metadata} carries them.
     *
     * @param callerGrants {@link #callerGrants} of the same listing
     */
    public static McpStanding fromListing(JsonNode instance, Map<String, String> callerGrants) {
        var config = instance.path("config");
        if (!config.has(Metadata.MCP_OWNER)) return null;
        var stamps = new HashMap<String, String>();
        config.properties().forEach(e -> {
            if (Metadata.isMcpKey(e.getKey())) stamps.put(e.getKey(), e.getValue().asText(""));
        });
        stamps.put(InstanceBackend.STATUS, instance.path("status").asText());
        return of(stamps, callerGrants);
    }

    static McpStanding of(Map<String, String> config, Map<String, String> callerGrants) {
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
                state == State.ORPHANED ? Orphans.orphanedSince(config) : null,
                state == State.ORPHANED ? Orphans.dormantNow(config) : null);
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
