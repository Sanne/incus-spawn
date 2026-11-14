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
