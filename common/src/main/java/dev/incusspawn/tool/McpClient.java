package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;

import java.util.ArrayList;
import java.util.List;

/**
 * How an agent takes the MCP servers the image's tools declare ({@link ToolSetup#mcpClient}).
 * The build calls it once, after every tool is set up, with the servers of the whole template
 * chain, so neither the order of the tools nor the layer that installed the agent decides what it
 * gets. Most agents are driven through their own CLI, which stays the authority on its file
 * format: {@link #addScript} and {@link #removeScript}, run as agentuser.
 */
public interface McpClient {

    /** The agent's name, as the tool is named, for the build's record and its messages. */
    String agent();

    /** Shell that adds {@code server}, replacing an entry of the same name. */
    String addScript(McpServer server);

    /** Shell that removes the entry {@code name}, succeeding when there is none. */
    String removeScript(String name);

    /**
     * Brings the agent's config in line: {@code servers} added or replaced, {@code stale} (names
     * isx registered by an earlier build that no tool declares now) removed. One exec as agentuser,
     * from its home, which fails the build when any command fails.
     */
    default void apply(Container container, List<McpServer> servers, List<String> stale) {
        var steps = new ArrayList<String>();
        for (var name : stale) steps.add(removeScript(name));
        for (var server : servers) steps.add(addScript(server));
        if (steps.isEmpty()) return;
        container.shAsUser("agentuser", "cd && " + String.join(" && ", steps))
                .assertSuccess("Failed to register MCP servers with " + agent());
    }

    /** {@code args}, each quoted for the guest shell, separated by spaces. */
    static String quoted(List<String> args) {
        return String.join(" ", args.stream().map(Container::shellQuote).toList());
    }
}
