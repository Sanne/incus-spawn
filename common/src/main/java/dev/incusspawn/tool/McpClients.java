package dev.incusspawn.tool;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusException;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.util.ArrayList;
import java.util.List;

/**
 * The agents that take a tool's MCP servers ({@link ToolSetup#mcpClient}), each in its own form.
 * Claude Code, Codex and Pi are driven through their own {@code mcp add} and {@code mcp remove},
 * so each CLI stays the authority on its file. opencode's {@code mcp add} has no remove and
 * rewrites the {@code opencode.json} of the directory it runs in, so isx merges its entries into
 * the user config {@link OpencodeSetup} writes. A bearer token is always a variable the agent
 * reads when it connects, never a value written into its config.
 */
public final class McpClients {

    private McpClients() {}

    /** The header a bearer token goes in. */
    static final String AUTHORIZATION = "Authorization";

    /**
     * Claude Code: {@code claude mcp add-json} at user scope, after a removal, since add-json
     * refuses a name already there. A token is sent by a {@code headersHelper}, which Claude Code
     * runs before each request, so it reads the variable as it is then.
     */
    public static final McpClient CLAUDE = new McpClient() {
        @Override public String agent() { return "claude"; }

        @Override public String addScript(McpServer server) {
            return removeScript(server.name()) + " && claude mcp add-json --scope user "
                    + Container.shellQuote(server.name()) + " " + Container.shellQuote(claudeJson(server));
        }

        @Override public String removeScript(String name) {
            return "{ claude mcp remove --scope user " + Container.shellQuote(name) + " >/dev/null 2>&1 || true; }";
        }
    };

    /** The entry {@code claude mcp add-json} takes for {@code server}. */
    static String claudeJson(McpServer server) {
        var json = JqObject.builder(4);
        if (server.stdio()) {
            var args = JqArray.arrayBuilder();
            server.command().subList(1, server.command().size()).forEach(args::add);
            var env = JqObject.builder(server.env().size());
            server.env().forEach(env::put);
            json.put("type", "stdio").put("command", server.command().getFirst())
                    .put("args", args.build()).put("env", env.build());
        } else {
            json.put("type", "http").put("url", server.url());
            if (server.bearerTokenEnv() != null) {
                json.put("headersHelper", "printf '{\"" + AUTHORIZATION + "\":\"Bearer %s\"}' \"$"
                        + server.bearerTokenEnv() + "\"");
            }
        }
        return json.build().toJsonString();
    }

    /** Codex: {@code codex mcp add}, which replaces an entry of the same name and keeps the rest of {@code config.toml}. */
    public static final McpClient CODEX = new McpClient() {
        @Override public String agent() { return "codex"; }

        @Override public String addScript(McpServer server) {
            var args = new ArrayList<>(List.of("codex", "mcp", "add", server.name()));
            if (server.stdio()) {
                server.env().forEach((k, v) -> args.addAll(List.of("--env", k + "=" + v)));
                args.add("--");
                args.addAll(server.command());
            } else {
                args.addAll(List.of("--url", server.url()));
                if (server.bearerTokenEnv() != null) args.addAll(List.of("--bearer-token-env-var", server.bearerTokenEnv()));
            }
            return McpClient.quoted(args) + " >/dev/null";
        }

        @Override public String removeScript(String name) {
            return "{ codex mcp remove " + Container.shellQuote(name) + " >/dev/null 2>&1 || true; }";
        }
    };

    /**
     * Pi: {@code pi mcp add}, which replaces an entry of the same name. Its tools are
     * {@code direct}, declared to the model as Claude Code declares them, rather than Pi's
     * default of reaching them only from codemode scripts, as {@code isx}'s own entry is.
     */
    public static final McpClient PI = new McpClient() {
        @Override public String agent() { return "pi"; }

        @Override public String addScript(McpServer server) {
            var args = new ArrayList<>(List.of("pi", "mcp", "add", server.name()));
            if (!server.stdio()) {
                args.addAll(List.of("--url", server.url()));
                if (server.bearerTokenEnv() != null) args.addAll(List.of("--bearer-token-env-var", server.bearerTokenEnv()));
            }
            server.env().forEach((k, v) -> args.addAll(List.of("--env", k + "=" + v)));
            args.addAll(List.of("--exposure", "direct"));
            if (server.description() != null && !server.description().isBlank()) {
                args.addAll(List.of("--description", server.description()));
            }
            if (server.stdio()) {
                args.add("--");
                args.addAll(server.command());
            }
            return McpClient.quoted(args) + " >/dev/null";
        }

        @Override public String removeScript(String name) {
            return "{ pi mcp remove " + Container.shellQuote(name) + " >/dev/null 2>&1 || true; }";
        }
    };

    /**
     * opencode: its entries merged into the {@code mcp} object of the user config isx writes, in
     * the forms opencode 1.x reads. Entries isx did not register are kept as they are.
     */
    public static final McpClient OPENCODE = new McpClient() {
        @Override public String agent() { return "opencode"; }

        @Override public String addScript(McpServer server) {
            throw new UnsupportedOperationException("opencode's entries are merged into its config");
        }

        @Override public String removeScript(String name) {
            throw new UnsupportedOperationException("opencode's entries are merged into its config");
        }

        @Override public void apply(Container container, List<McpServer> servers, List<String> stale) {
            if (servers.isEmpty() && stale.isEmpty()) return;
            var read = container.exec("cat", OpencodeSetup.CONFIG_PATH);
            if (!read.success()) {
                throw new IncusException("Could not read " + OpencodeSetup.CONFIG_PATH + " to register MCP servers with opencode");
            }
            container.writeFile(OpencodeSetup.CONFIG_PATH, opencodeConfig(read.stdout(), servers, stale));
            container.chown(OpencodeSetup.CONFIG_PATH, "agentuser:agentuser");
        }
    };

    /** {@code config}, opencode's user config, with {@code servers} put into its {@code mcp} object and {@code stale} taken out. */
    static String opencodeConfig(String config, List<McpServer> servers, List<String> stale) {
        if (!(Jjq.parseForeign(config, OpencodeSetup.CONFIG_PATH) instanceof JqObject root)) {
            throw new IncusException(OpencodeSetup.CONFIG_PATH + " is not a JSON object");
        }
        var mcp = root.get("mcp") instanceof JqObject existing ? existing : JqObject.EMPTY;
        for (var name : stale) mcp = mcp.without(name);
        for (var server : servers) mcp = mcp.with(server.name(), opencodeEntry(server));
        return JqValues.toPrettyJsonString(root.with("mcp", mcp)) + "\n";
    }

    /** Gemini CLI: {@code mcpServers} in its {@code settings.json}, which expands {@code ${VAR}} when it loads the file. */
    public static final McpClient GEMINI = jsonFile("gemini", GeminiSetup.CONFIG_PATH, false, McpClients::geminiEntry);

    /** Bob Shell, a Gemini CLI fork: the same {@code mcpServers} in {@code ~/.bob/settings/settings.json}. */
    public static final McpClient BOB = jsonFile("bob", BobSetup.SETTINGS_PATH, false, McpClients::geminiEntry);

    static JqObject geminiEntry(McpServer server) {
        var entry = JqObject.builder(4);
        if (server.stdio()) {
            stdio(entry, server);
        } else {
            entry.put("httpUrl", server.url());
            bearerHeader(entry, server);
        }
        return entry.build();
    }

    /**
     * Antigravity CLI: {@code mcpServers} in {@code ~/.gemini/config/mcp_config.json}, a remote
     * server under {@code serverUrl}, as {@code agy mcp add} writes it. It expands no variable in
     * that file (its own plugin guide says so, and a {@code ${VAR}} header reaches the server as
     * written), so a server whose bearer token is read from a variable is registered as a stdio
     * server running {@link McpStdioBridge}, which reads the variable it was started with: the
     * token is never written there (#2476).
     */
    public static final McpClient ANTIGRAVITY = withBridge(
            jsonFile("antigravity", AntigravitySetup.MCP_CONFIG_PATH, true, McpClients::antigravityEntry));

    static JqObject antigravityEntry(McpServer server) {
        var entry = JqObject.builder(4);
        if (server.stdio()) {
            stdio(entry, server);
        } else if (server.bearerTokenEnv() != null) {
            // agy hands its own environment to the server it starts, so the variable is read there
            stdio(entry, new McpServer(server.name(), null, McpStdioBridge.commandForBearer(server.url(), server.bearerTokenEnv()),
                    java.util.Map.of(), null, null));
        } else {
            entry.put("serverUrl", server.url());
        }
        return entry.build();
    }

    /** {@code client}, putting the bridge in the guest first when a server needs it. */
    private static McpClient withBridge(McpClient client) {
        return new McpClient() {
            @Override public String agent() { return client.agent(); }

            @Override public String addScript(McpServer server) { return client.addScript(server); }

            @Override public String removeScript(String name) { return client.removeScript(name); }

            @Override public void apply(Container container, List<McpServer> servers, List<String> stale) {
                if (servers.stream().anyMatch(s -> s.bearerTokenEnv() != null)) McpStdioBridge.install(container);
                client.apply(container, servers, stale);
            }
        };
    }

    /** Copilot CLI: {@code mcpServers} in {@code ~/.copilot/mcp-config.json}, a file it does not create until asked. */
    public static final McpClient COPILOT = jsonFile("copilot", CopilotSetup.MCP_CONFIG_PATH, true, McpClients::copilotEntry);

    static JqObject copilotEntry(McpServer server) {
        var entry = JqObject.builder(5);
        if (server.stdio()) {
            entry.put("type", "local");
            stdio(entry, server);
        } else {
            entry.put("type", "http").put("url", server.url());
            bearerHeader(entry, server);
        }
        return entry.put("tools", JqArray.arrayBuilder().add("*").build()).build();
    }

    private static void stdio(JqObject.Builder entry, McpServer server) {
        var args = JqArray.arrayBuilder();
        server.command().subList(1, server.command().size()).forEach(args::add);
        var env = JqObject.builder(server.env().size());
        server.env().forEach(env::put);
        entry.put("command", server.command().getFirst()).put("args", args.build()).put("env", env.build());
    }

    /** The bearer token as a reference to its variable, which the agent resolves when it connects. */
    private static void bearerHeader(JqObject.Builder entry, McpServer server) {
        if (server.bearerTokenEnv() == null) return;
        entry.put("headers", JqObject.builder(1)
                .put(AUTHORIZATION, "Bearer ${" + server.bearerTokenEnv() + "}").build());
    }

    /**
     * An agent whose servers are the {@code mcpServers} object of one JSON file, merged as
     * opencode's are: what isx did not register stays. {@code optional} files may be missing.
     */
    private static McpClient jsonFile(String agent, String path, boolean optional,
                                      java.util.function.Function<McpServer, JqObject> entry) {
        return new McpClient() {
            @Override public String agent() { return agent; }

            @Override public String addScript(McpServer server) {
                throw new UnsupportedOperationException(agent + "'s entries are merged into its config");
            }

            @Override public String removeScript(String name) {
                throw new UnsupportedOperationException(agent + "'s entries are merged into its config");
            }

            @Override public void apply(Container container, List<McpServer> servers, List<String> stale) {
                if (servers.isEmpty() && stale.isEmpty()) return;
                var read = container.sh(optional ? "if [ -f " + path + " ]; then cat " + path + "; fi"
                        : "cat " + path);
                if (!read.success()) {
                    throw new IncusException("Could not read " + path + " to register MCP servers with " + agent);
                }
                var text = read.stdout().isBlank() ? "{}" : read.stdout();
                container.shAsUser("agentuser", "mkdir -p \"$(dirname " + path + ")\"").assertSuccess("Failed to create the directory of " + path);
                container.writeFile(path, mergeServers(text, path, servers, stale, entry));
                container.chown(path, "agentuser:agentuser");
            }
        };
    }

    /** {@code config} with {@code servers} put into its {@code mcpServers} object, as {@code entry} renders them, and {@code stale} taken out. */
    static String mergeServers(String config, String path, List<McpServer> servers, List<String> stale,
                               java.util.function.Function<McpServer, JqObject> entry) {
        if (!(Jjq.parseForeign(config, path) instanceof JqObject root)) {
            throw new IncusException(path + " is not a JSON object");
        }
        var mcp = root.get("mcpServers") instanceof JqObject existing ? existing : JqObject.EMPTY;
        for (var name : stale) mcp = mcp.without(name);
        for (var server : servers) mcp = mcp.with(server.name(), entry.apply(server));
        return JqValues.toPrettyJsonString(root.with("mcpServers", mcp)) + "\n";
    }

    private static JqObject opencodeEntry(McpServer server) {
        var entry = JqObject.builder(4);
        if (server.stdio()) {
            var command = JqArray.arrayBuilder();
            server.command().forEach(command::add);
            var env = JqObject.builder(server.env().size());
            server.env().forEach(env::put);
            entry.put("type", "local").put("command", command.build()).put("environment", env.build());
        } else {
            entry.put("type", "remote").put("url", server.url());
            if (server.bearerTokenEnv() != null) {
                entry.put("headers", JqObject.builder(1)
                        .put(AUTHORIZATION, "Bearer {env:" + server.bearerTokenEnv() + "}").build());
            }
        }
        return entry.put("enabled", true).build();
    }
}
