package dev.incusspawn.command;

import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The TUI restarts an instance with the {@code mcp-caller} bit its listing read (#1182): only a
 * well-formed grant reads as a coordinator, so a copy or a malformed stamp is restarted as
 * nothing more, and its Claude Code registration is removed rather than made.
 */
class InstanceListingMcpCallerTest {

    private static String instance(String name, String grant) {
        var stamp = grant == null ? "" : ",\"" + Metadata.MCP_CALLER + "\":\"" + grant + "\"";
        return "{\"name\":\"" + name + "\",\"status\":\"Running\",\"type\":\"container\","
                + "\"config\":{\"" + Metadata.PARENT + "\":\"tpl-dev\"" + stamp + "}}";
    }

    @Test
    void onlyAGrantMakesACoordinator() {
        var listing = "[" + String.join(",",
                instance("coord", Metadata.newMcpCallerGrant()),
                instance("copy", null),
                instance("old", "2026-10-05T10:00:00")) + "]";

        var callers = InstanceListing.collectEntries(listing).stream()
                .collect(Collectors.toMap(InstanceListing.InstanceInfo::name, InstanceListing.InstanceInfo::mcpCaller));

        assertEquals(Map.of("coord", true, "copy", false, "old", false), callers);
    }
}
