package dev.incusspawn.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * An MCP server a tool provides, as the build registers it with each agent in the image: a stdio
 * {@code command} with its {@code env}, or an HTTPS {@code url} whose bearer token the agent reads
 * from the variable {@code bearerTokenEnv} each time it connects, never a token written into its
 * config. Declared as {@code mcp:} in a tool's YAML ({@link ToolDef.McpEntry}), with parameters
 * substituted; {@link ToolDefValidator#invalidMcpServers} holds the rules below for a definition.
 *
 * @param description what the server offers, for an agent that shows it to the model (Pi); may be null
 * @param bearerTokenEnv may be null, and is only set with {@code url}
 */
public record McpServer(String name, String description, List<String> command, Map<String, String> env,
                        String url, String bearerTokenEnv) {

    /** What a server may be named: one an agent's CLI takes as one argument, and none of their option syntax. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    /** A variable name, as a server's environment and a bearer token's source take it. */
    public static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * The name that stays the {@code isx} coordinator's ({@code McpClientRegistration}), which
     * every start reconciles: a tool's server by that name would be added by the build and taken
     * out by the next start of an instance that is not a coordinator.
     */
    public static final String RESERVED_NAME = "isx";

    /** A parameter placeholder substitution left in place, since the parameter has no value. */
    private static final Pattern UNSET_PARAM = Pattern.compile("\\$\\{param_[^}]*}");

    /**
     * Stands for the template's repositories in a server's {@code env} and {@code command}: their
     * paths across the template chain, comma-separated, or empty when it has none. Expanded when
     * the build registers the server, after every layer's repos are cloned ({@link #expandRepos}).
     */
    public static final String REPO_PATHS = "${isx_repo_paths}";

    public McpServer {
        command = List.copyOf(command);
        env = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(env));
    }

    public boolean stdio() { return url == null; }

    /** {@code entry} with the tool's resolved parameters substituted. */
    public static McpServer of(ToolDef.McpEntry entry, Map<String, String> params) {
        UnaryOperator<String> sub = s -> ParameterSubstitutor.substitute(s, params);
        var env = new LinkedHashMap<String, String>();
        // An optional parameter left unset is not in params: its placeholder empties the variable
        entry.getEnv().forEach((k, v) -> env.put(k,
                UNSET_PARAM.matcher(sub.apply(v == null ? "" : v)).replaceAll("")));
        return new McpServer(entry.getName(), entry.getDescription(),
                entry.getCommand().stream().map(sub).toList(), env, entry.getUrl(), entry.getBearerTokenEnv());
    }

    /**
     * This server as the build registers it: {@link #REPO_PATHS} in its command and environment
     * replaced by {@code repoPaths}, and each variable left empty not set at all, which is what an
     * unset parameter means to the server.
     */
    public McpServer expandRepos(String repoPaths) {
        UnaryOperator<String> sub = s -> s.replace(REPO_PATHS, repoPaths);
        var expanded = new LinkedHashMap<String, String>();
        env.forEach((k, v) -> {
            var value = sub.apply(v);
            if (!value.isEmpty()) expanded.put(k, value);
        });
        return new McpServer(name, description, command.stream().map(sub).toList(), expanded, url, bearerTokenEnv);
    }

    /**
     * Why {@code entry} cannot be registered, or null when it can. A server's values reach each
     * agent's CLI as arguments, quoted, so these rules are about meaning, not escaping: a name every
     * agent stores as is, exactly one way to reach the server, and a credential only as a variable
     * the agent reads when it connects.
     */
    public static String problem(ToolDef.McpEntry entry) {
        var name = entry.getName();
        if (name == null || !NAME.matcher(name).matches()) {
            return "name must be lowercase letters, digits, '-' and '_', starting with a letter or digit";
        }
        if (RESERVED_NAME.equals(name)) return "name 'isx' is reserved for isx's own MCP server";
        var hasCommand = !entry.getCommand().isEmpty();
        var hasUrl = entry.getUrl() != null;
        if (hasCommand == hasUrl) return "must set exactly one of 'command' (stdio) or 'url' (HTTP)";
        if (hasCommand) {
            if (entry.getCommand().stream().anyMatch(a -> a == null || a.isEmpty())) {
                return "'command' must not hold an empty argument";
            }
            if (entry.getBearerTokenEnv() != null) return "'bearer-token-env' applies to a 'url' server only";
        } else {
            if (!entry.getUrl().startsWith("https://") || entry.getUrl().chars().anyMatch(Character::isWhitespace)) {
                return "'url' must be an https:// URL";
            }
            if (!entry.getEnv().isEmpty()) return "'env' applies to a 'command' server only";
            if (entry.getBearerTokenEnv() != null && !ENV_NAME.matcher(entry.getBearerTokenEnv()).matches()) {
                return "'bearer-token-env' must be a variable name";
            }
        }
        for (var key : entry.getEnv().keySet()) {
            if (!ENV_NAME.matcher(key).matches()) return "env key '" + key + "' is not a variable name";
        }
        return null;
    }
}
