package dev.incusspawn.command;

import dev.incusspawn.command.BuildTools.ResolvedTool;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.tool.McpClient;
import dev.incusspawn.tool.McpServer;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Registers the MCP servers the image's tools declare ({@code mcp:}, {@link
 * dev.incusspawn.tool.ToolSetup#mcpServers}) with every agent in it that takes them ({@link
 * dev.incusspawn.tool.ToolSetup#mcpClient}), #2206.
 *
 * <p>It runs once per build, in both build paths, after every tool is set up and every layer's
 * repositories are cloned, over the tools of the whole template chain. So a server reaches an
 * agent whichever order the tools are listed in and whichever layer installed the agent, and an
 * agent whose setup rewrote its config whole (Codex, opencode) gets every server again. Nothing
 * of it runs on a start.
 *
 * <p>Root keeps a record of what it registered, {@link #RECORD}, one {@code <agent> <server>}
 * per line, so a later build removes a server no tool declares any more and leaves an entry
 * isx did not make alone.
 */
final class McpServerRegistration {

    /** What the build registered with which agent, root's. */
    static final String RECORD = "/var/lib/isx/mcp-servers";

    private McpServerRegistration() {}

    static void register(Container container, ImageDef imageDef, Map<String, ImageDef> defs,
                         List<ResolvedTool> allTools) {
        // An ancestor's tool a layer reconfigures is listed twice: the layer's parameters win
        var tools = new LinkedHashMap<String, ResolvedTool>();
        for (var tool : allTools) tools.put(tool.name(), tool);

        var servers = servers(tools.values(), repoPaths(imageDef, defs));
        var clients = tools.values().stream().map(t -> t.setup().mcpClient())
                .filter(java.util.Objects::nonNull).toList();
        if (clients.isEmpty()) {
            if (!servers.isEmpty()) {
                BuildOutput.warn("No agent in this template takes MCP servers",
                        "Not registered: " + servers.stream().map(s -> s.server().name()).collect(Collectors.joining(", ")),
                        "Add claude, codex, pi or opencode to the template to use them");
            }
            // Without reading the record, which costs every agent-less build an exec: a record
            // names only agents installed in this rootfs, and a copied rootfs never loses a tool.
            // The one exception is a child built from a parent image older than a definition that
            // dropped its agent: that agent is then still installed, and keeps its entries until
            // the parent is rebuilt, which the staleness check asks for.
            return;
        }
        var recorded = recorded(container);
        if (servers.isEmpty() && recorded.isEmpty()) return;

        requireCommands(container, servers);
        var declared = servers.stream().map(s -> s.server().name()).collect(Collectors.toSet());
        var record = new ArrayList<String>();
        try (var group = BuildOutput.group("MCP servers", servers.stream()
                .map(s -> s.server().name()).collect(Collectors.joining(", ")))) {
            for (var client : clients) {
                var stale = recorded.getOrDefault(client.agent(), Set.of()).stream()
                        .filter(name -> !declared.contains(name)).toList();
                BuildOutput.stepStart("Registering with " + client.agent());
                client.apply(container, servers.stream().map(Declared::server).toList(), stale);
                BuildOutput.stepDone();
                for (var server : servers) record.add(client.agent() + " " + server.server().name());
            }
        }
        if (record.isEmpty()) {
            container.sh("rm -f " + RECORD).assertSuccess("Failed to remove " + RECORD);
        } else {
            container.writeFile(RECORD, String.join("\n", record) + "\n");
        }
    }

    /** A server, with the tool that declared it. */
    record Declared(String tool, McpServer server) {}

    /** Every tool's servers, as registered; two tools declaring one name fail the build. */
    static List<Declared> servers(Iterable<ResolvedTool> tools, String repoPaths) {
        var byName = new LinkedHashMap<String, Declared>();
        for (var tool : tools) {
            for (var server : tool.setup().mcpServers(tool.parameters())) {
                var previous = byName.putIfAbsent(server.name(), new Declared(tool.name(), server.expandRepos(repoPaths)));
                if (previous != null) {
                    throw new IncusException(OutputFormat.oneLine("MCP server '" + server.name() + "' is declared by both the "
                            + previous.tool() + " and the " + tool.name() + " tools"));
                }
            }
        }
        return List.copyOf(byName.values());
    }

    /** {@link McpServer#REPO_PATHS}: the paths of the chain's repositories, comma-separated. */
    static String repoPaths(ImageDef imageDef, Map<String, ImageDef> defs) {
        return GuestProvisioning.chainRepos(imageDef, defs).stream()
                .map(repo -> GuestProvisioning.expandHome(repo.getPath()))
                .distinct().collect(Collectors.joining(","));
    }

    /** {@link #RECORD}, by agent; a line that is not one isx writes is ignored. */
    static Map<String, Set<String>> recorded(Container container) {
        var read = container.exec("cat", RECORD);
        if (!read.success()) return Map.of();
        return parseRecord(read.stdout());
    }

    static Map<String, Set<String>> parseRecord(String content) {
        var byAgent = new LinkedHashMap<String, Set<String>>();
        for (var line : content.lines().toList()) {
            var parts = line.strip().split(" ");
            if (parts.length != 2 || !McpServer.NAME.matcher(parts[1]).matches()) continue;
            byAgent.computeIfAbsent(parts[0], k -> new LinkedHashSet<>()).add(parts[1]);
        }
        return byAgent;
    }

    /**
     * Fails the build when a stdio server's command is not on agentuser's login PATH: every agent
     * would list a server that cannot start, and say so only when a session tries it.
     */
    static void requireCommands(Container container, List<Declared> servers) {
        var commands = servers.stream().filter(s -> s.server().stdio())
                .map(s -> s.server().command().getFirst()).distinct().toList();
        if (commands.isEmpty()) return;
        var check = container.shAsUser("agentuser", "for c in " + McpClient.quoted(commands)
                + "; do command -v \"$c\" >/dev/null 2>&1 || printf '%s\\n' \"$c\"; done");
        if (!check.success()) {
            throw new IncusException("Could not check the MCP servers' commands: " + OutputFormat.oneLine(check.stderr()));
        }
        var missing = check.stdout().lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.toSet());
        for (var server : servers) {
            if (server.server().stdio() && missing.contains(server.server().command().getFirst())) {
                throw new IncusException(OutputFormat.oneLine("The " + server.tool() + " tool's MCP server '"
                        + server.server().name() + "' runs '" + server.server().command().getFirst()
                        + "', which is not on agentuser's PATH"));
            }
        }
    }
}
