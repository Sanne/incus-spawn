package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.config.SecretRedactor;
import dev.incusspawn.util.OutputFormat;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The coding agents already set up on the host (#2408): for each agent isx supports, whether it is
 * installed, whether it is signed in, the kind of account and the model the person chose.
 * <p>
 * It only reads. It runs no agent, {@code gh} or {@code gcloud}, which could refresh or rewrite a
 * session, and it never opens the macOS Keychain: Claude Code's {@code oauthAccount} in
 * {@code ~/.claude.json} says a subscription login exists on both platforms. It returns no
 * secret: a record holds the kind of account and where it was seen, never a token or key, and the
 * model, project and region it does hold are kept only when they look like one (see {@link #plain}).
 * A subscription session is never copied: init runs {@code claude setup-token} for one instead.
 * <p>
 * An agent whose files cannot be read or parsed counts as not found, with no error.
 */
public final class HostAgentProbe {

    private HostAgentProbe() {}

    /** Larger files are not read: {@code ~/.claude.json} grows with every project, but not this far. */
    static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

    /** What a model, project, region or provider name may look like: no spaces, no quotes, bounded. */
    private static final Pattern PLAIN_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@\\[\\]-]{0,127}");
    /** A run this long without a separator is a key or a token, never a model or a region; {@code /} separates, as in {@code openrouter/anthropic/claude-sonnet-4}. */
    private static final Pattern TOKEN_RUN = Pattern.compile("[A-Za-z0-9+_]{24,}");
    /** Codex's {@code model = "..."} at the top level of {@code config.toml}. */
    private static final Pattern TOML_MODEL = Pattern.compile("^\\s*model\\s*=\\s*[\"']([^\"']*)[\"']\\s*(#.*)?$");
    private static final Pattern TOML_TABLE = Pattern.compile("^\\s*\\[.*");
    private static final Pattern DOTENV_LINE = Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*?)\\s*$");

    /** The kind of account an agent is signed in with. */
    public enum Kind {
        /** A subscription login: Claude Pro or Max, ChatGPT, a Google account, GitHub Copilot. */
        SUBSCRIPTION,
        /** An API key, in the agent's files or the environment. */
        API_KEY,
        /** A cloud provider, such as Vertex AI, with its project and region when they were found. */
        CLOUD,
        /** No sign-in this probe can tell apart, or none at all. */
        UNKNOWN;

        /** The kind as the formats write it: {@code subscription}, {@code api-key}, {@code cloud}, {@code unknown}. */
        public String value() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * One agent found on the host. Only agents that are installed are returned, so being in the
     * list means installed: its command is on {@code PATH} or its configuration directory exists.
     *
     * @param agent    the agent's tool name in isx ({@code claude}, {@code codex}, {@code gemini-cli},
     *                 {@code antigravity}, {@code pi}, {@code opencode}, {@code copilot}, {@code bob})
     * @param signedIn whether a sign-in was seen; false also when this probe cannot tell
     * @param kind     the kind of account, {@link Kind#UNKNOWN} when not signed in
     * @param provider whose account it is, when the agent serves several ({@code anthropic},
     *                 {@code openai}, {@code google}) or a cloud ({@code vertex-ai}, {@code bedrock}); else null
     * @param project  the cloud project, or null
     * @param region   the cloud region, or null
     * @param model    the model the person chose, or null when they left the agent's default
     * @param foundIn  where the sign-in was seen: a file under the home ({@code ~/.claude.json}) or
     *                 an environment variable ({@code $ANTHROPIC_API_KEY}), never its value; null when not signed in
     */
    public record HostAgent(String agent, boolean signedIn, Kind kind, String provider, String project,
                            String region, String model, String foundIn) {

        /** The agent's name as a person knows it. */
        public String title() {
            return HostAgentProbe.title(agent);
        }

        /** What was found, in a few words: {@code signed in with a subscription, model opus (~/.claude.json)}. */
        public String summary() {
            var parts = new ArrayList<String>();
            if (!signedIn) {
                parts.add("installed, no sign-in found");
            } else {
                var account = switch (kind) {
                    case SUBSCRIPTION -> "a subscription";
                    case API_KEY -> "an API key";
                    case CLOUD -> "a cloud provider";
                    case UNKNOWN -> "an account of a kind isx cannot tell";
                };
                parts.add("signed in with " + account + (provider == null ? "" : " (" + provider + ")"));
                if (project != null) parts.add("project " + project);
                if (region != null) parts.add("region " + region);
            }
            if (model != null) parts.add("model " + model);
            // A path under CLAUDE_CONFIG_DIR or CODEX_HOME is the environment's, shown as plain shows a value
            return OutputFormat.oneLine(String.join(", ", parts) + (foundIn == null ? "" : " (" + foundIn + ")"));
        }
    }

    /** An agent's name as a person knows it, by its tool's name: {@code Claude Code}; the name itself for one isx does not know. */
    public static String title(String agent) {
        return switch (agent) {
            case "claude" -> "Claude Code";
            case "codex" -> "Codex CLI";
            case "gemini-cli" -> "Gemini CLI";
            case "antigravity" -> "Antigravity CLI";
            case "pi" -> "Pi";
            case "opencode" -> "opencode";
            case "copilot" -> "GitHub Copilot CLI";
            case "bob" -> "Bob Shell";
            default -> agent;
        };
    }

    /**
     * What {@code isx init --plan} says of {@code agents} in Credential Setup's note, or null when
     * none was found: init does not act on it yet, it only shows what a later step can reuse.
     */
    public static String note(List<HostAgent> agents) {
        if (agents.isEmpty()) return null;
        return "Coding agents on this host: " + String.join("; ", agents.stream().map(a -> a.title() + " " + a.summary()).toList()) + ".";
    }

    /** The agents on this host, read from its home, environment and {@code PATH}. */
    public static List<HostAgent> probe() {
        var env = System.getenv();
        return probe(Environment.home(), env, pathDirs(env.get("PATH")));
    }

    /**
     * The agents found under {@code home}, with {@code env} as the environment and commands looked
     * for in {@code path}; in the order isx lists its agents. Nothing is written and nothing is run.
     */
    public static List<HostAgent> probe(Path home, Map<String, String> env, List<Path> path) {
        var host = new Host(home, env, path);
        var found = new ArrayList<HostAgent>();
        for (Function<Host, Optional<HostAgent>> agent : List.<Function<Host, Optional<HostAgent>>>of(
                HostAgentProbe::claude, HostAgentProbe::codex, HostAgentProbe::gemini, HostAgentProbe::antigravity,
                HostAgentProbe::pi, HostAgentProbe::opencode, HostAgentProbe::copilot, HostAgentProbe::bob)) {
            try {
                agent.apply(host).ifPresent(found::add);
            } catch (RuntimeException unreadable) {
                // A file that cannot be read or parsed: the agent counts as not found
            }
        }
        return List.copyOf(found);
    }

    /** The directories of a {@code PATH} value, in order. */
    static List<Path> pathDirs(String path) {
        if (path == null || path.isBlank()) return List.of();
        var dirs = new ArrayList<Path>();
        for (var dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            try {
                dirs.add(Path.of(dir));
            } catch (RuntimeException invalid) {
                // not a path this system can name
            }
        }
        return dirs;
    }

    /** Claude Code: {@code ~/.claude.json} and {@code ~/.claude/settings.json}, or under {@code CLAUDE_CONFIG_DIR}. */
    private static Optional<HostAgent> claude(Host host) {
        var configDir = host.env("CLAUDE_CONFIG_DIR");
        var dir = configDir != null ? Path.of(configDir) : host.home.resolve(".claude");
        var state = configDir != null ? dir.resolve(".claude.json") : host.home.resolve(".claude.json");
        if (!host.onPath("claude") && !Files.isDirectory(dir) && !Files.isRegularFile(state)) return Optional.empty();
        var settingsFile = dir.resolve("settings.json");
        var settings = host.json(settingsFile);
        var settingsEnv = settings.get("env") instanceof JqObject e ? e : JqObject.EMPTY;
        Function<String, String> setting = name -> host.env(name) != null ? host.env(name) : text(settingsEnv, name);
        Function<String, String> where = name -> host.env(name) != null ? "$" + name : host.display(settingsFile);
        var model = plain(setting.apply("ANTHROPIC_MODEL") != null ? setting.apply("ANTHROPIC_MODEL") : text(settings, "model"));
        if (truthy(setting.apply("CLAUDE_CODE_USE_VERTEX"))) {
            var rawProject = setting.apply("ANTHROPIC_VERTEX_PROJECT_ID");
            return found("claude", rawProject != null, Kind.CLOUD, "vertex-ai", plain(rawProject),
                    plain(setting.apply("CLOUD_ML_REGION")), model, where.apply("CLAUDE_CODE_USE_VERTEX"));
        }
        if (truthy(setting.apply("CLAUDE_CODE_USE_BEDROCK"))) {
            return found("claude", true, Kind.CLOUD, "bedrock", null,
                    plain(setting.apply("AWS_REGION")), model, where.apply("CLAUDE_CODE_USE_BEDROCK"));
        }
        // In Claude Code's own order: a bearer token, an API key, a key helper, then a subscription token
        if (setting.apply("ANTHROPIC_AUTH_TOKEN") != null) {
            return found("claude", true, Kind.API_KEY, null, null, null, model, where.apply("ANTHROPIC_AUTH_TOKEN"));
        }
        if (setting.apply("ANTHROPIC_API_KEY") != null) {
            return found("claude", true, Kind.API_KEY, null, null, null, model, where.apply("ANTHROPIC_API_KEY"));
        }
        if (text(settings, "apiKeyHelper") != null) {
            return found("claude", true, Kind.API_KEY, null, null, null, model, host.display(settingsFile));
        }
        if (host.env("CLAUDE_CODE_OAUTH_TOKEN") != null) {
            return found("claude", true, Kind.SUBSCRIPTION, null, null, null, model, "$CLAUDE_CODE_OAUTH_TOKEN");
        }
        var account = host.json(state);
        if (account.get("oauthAccount") instanceof JqObject) {
            return found("claude", true, Kind.SUBSCRIPTION, null, null, null, model, host.display(state));
        }
        if (text(account, "primaryApiKey") != null) {
            return found("claude", true, Kind.API_KEY, null, null, null, model, host.display(state));
        }
        return found("claude", false, Kind.UNKNOWN, null, null, null, model, null);
    }

    /** Codex CLI: {@code auth.json} and {@code config.toml} in {@code CODEX_HOME}, by default {@code ~/.codex}. */
    private static Optional<HostAgent> codex(Host host) {
        var codexHome = host.env("CODEX_HOME");
        var dir = codexHome != null ? Path.of(codexHome) : host.home.resolve(".codex");
        if (!host.onPath("codex") && !Files.isDirectory(dir)) return Optional.empty();
        String model = null;
        var config = dir.resolve("config.toml");
        if (Files.isRegularFile(config)) {
            for (var line : host.read(config).split("\n", -1)) {
                if (TOML_TABLE.matcher(line).matches()) break;
                var m = TOML_MODEL.matcher(line);
                if (m.matches()) model = plain(m.group(1));
            }
        }
        var authFile = dir.resolve("auth.json");
        var auth = host.json(authFile);
        var mode = text(auth, "auth_mode");
        if ("chatgpt".equalsIgnoreCase(mode) || mode == null && auth.get("tokens") instanceof JqObject) {
            return found("codex", true, Kind.SUBSCRIPTION, "openai", null, null, model, host.display(authFile));
        }
        if ("apikey".equalsIgnoreCase(mode) || text(auth, "OPENAI_API_KEY") != null) {
            return found("codex", true, Kind.API_KEY, "openai", null, null, model, host.display(authFile));
        }
        if (host.env("OPENAI_API_KEY") != null) {
            return found("codex", true, Kind.API_KEY, "openai", null, null, model, "$OPENAI_API_KEY");
        }
        return found("codex", false, Kind.UNKNOWN, null, null, null, model, null);
    }

    /**
     * Gemini CLI: the auth type chosen in {@code ~/.gemini/settings.json}, and what it needs from
     * the environment or the {@code .env} files Gemini CLI reads ({@code ~/.gemini/.env}, {@code ~/.env}).
     */
    private static Optional<HostAgent> gemini(Host host) {
        var dir = host.home.resolve(".gemini");
        var settingsFile = dir.resolve("settings.json");
        if (!host.onPath("gemini") && !Files.isRegularFile(settingsFile)) return Optional.empty();
        var settings = host.json(settingsFile);
        var vars = host.envWithDotenv(dir.resolve(".env"), host.home.resolve(".env"));
        var security = settings.get("security") instanceof JqObject s ? s : JqObject.EMPTY;
        var auth = security.get("auth") instanceof JqObject a ? a : JqObject.EMPTY;
        var type = text(auth, "selectedType") != null ? text(auth, "selectedType") : text(settings, "selectedAuthType");
        var model = vars.get("GEMINI_MODEL") != null ? plain(vars.get("GEMINI_MODEL"))
                : settings.get("model") instanceof JqObject m ? plain(text(m, "name")) : plain(text(settings, "model"));
        var where = host.display(settingsFile);
        if (type == null) {
            if (vars.get("GEMINI_API_KEY") != null) {
                return found("gemini-cli", true, Kind.API_KEY, "google", null, null, model, "$GEMINI_API_KEY");
            }
            return found("gemini-cli", false, Kind.UNKNOWN, null, null, null, model, null);
        }
        return switch (type) {
            case "oauth-personal" -> {
                var creds = dir.resolve("oauth_creds.json");
                var signedIn = Files.isRegularFile(creds);
                yield found("gemini-cli", signedIn, signedIn ? Kind.SUBSCRIPTION : Kind.UNKNOWN, signedIn ? "google" : null,
                        null, null, model, signedIn ? host.display(creds) : null);
            }
            case "gemini-api-key" -> {
                var signedIn = vars.get("GEMINI_API_KEY") != null;
                yield found("gemini-cli", signedIn, signedIn ? Kind.API_KEY : Kind.UNKNOWN, signedIn ? "google" : null,
                        null, null, model, signedIn ? "$GEMINI_API_KEY" : null);
            }
            case "vertex-ai" -> {
                yield found("gemini-cli", vars.get("GOOGLE_CLOUD_PROJECT") != null || vars.get("GOOGLE_API_KEY") != null,
                        Kind.CLOUD, "vertex-ai", plain(vars.get("GOOGLE_CLOUD_PROJECT")), plain(vars.get("GOOGLE_CLOUD_LOCATION")), model, where);
            }
            case "cloud-shell", "compute-default-credentials" ->
                    found("gemini-cli", true, Kind.CLOUD, "google-cloud", plain(vars.get("GOOGLE_CLOUD_PROJECT")),
                            plain(vars.get("GOOGLE_CLOUD_LOCATION")), model, where);
            default -> found("gemini-cli", true, Kind.UNKNOWN, null, null, null, model, where);
        };
    }

    /**
     * Antigravity CLI: only its Gemini API key mode, {@code modelProvider: gemini} with
     * {@code GEMINI_API_KEY}, can be told apart; how its Google sign-in is stored is not read.
     */
    private static Optional<HostAgent> antigravity(Host host) {
        var dir = host.home.resolve(".gemini").resolve("antigravity-cli");
        if (!host.onPath("agy") && !Files.isDirectory(dir)) return Optional.empty();
        var settings = host.json(dir.resolve("settings.json"));
        if ("gemini".equals(text(settings, "modelProvider")) && host.env("GEMINI_API_KEY") != null) {
            return found("antigravity", true, Kind.API_KEY, "google", null, null, null, "$GEMINI_API_KEY");
        }
        return found("antigravity", false, Kind.UNKNOWN, null, null, null, null, null);
    }

    /**
     * Pi: the default provider and model of {@code settings.json} and that provider's entry in
     * {@code auth.json}, in {@code PI_CODING_AGENT_DIR}, by default {@code ~/.pi/agent}.
     */
    private static Optional<HostAgent> pi(Host host) {
        var agentDir = host.env("PI_CODING_AGENT_DIR");
        var dir = agentDir != null ? Path.of(agentDir) : host.home.resolve(".pi").resolve("agent");
        if (!host.onPath("pi") && !Files.isDirectory(dir)) return Optional.empty();
        var settings = host.json(dir.resolve("settings.json"));
        var provider = plain(text(settings, "defaultProvider"));
        var model = plain(text(settings, "defaultModel"));
        var authFile = dir.resolve("auth.json");
        var entry = providerEntry(host.json(authFile), provider);
        if (entry != null) {
            var kind = switch (String.valueOf(text(entry.value(), "type"))) {
                case "oauth" -> Kind.SUBSCRIPTION;
                case "api_key" -> Kind.API_KEY;
                default -> Kind.UNKNOWN;
            };
            return found("pi", true, kind, entry.provider(), null, null, model, host.display(authFile));
        }
        if ("google-vertex".equals(provider)) {
            var signedIn = host.env("GOOGLE_CLOUD_PROJECT") != null;
            return found("pi", signedIn, Kind.CLOUD, "vertex-ai", plain(host.env("GOOGLE_CLOUD_PROJECT")),
                    plain(host.env("GOOGLE_CLOUD_LOCATION")), model, signedIn ? "$GOOGLE_CLOUD_PROJECT" : null);
        }
        var keyVar = provider == null ? null : switch (provider) {
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "openai" -> "OPENAI_API_KEY";
            case "google" -> "GEMINI_API_KEY";
            default -> null;
        };
        if (keyVar != null && host.env(keyVar) != null) {
            return found("pi", true, Kind.API_KEY, provider, null, null, model, "$" + keyVar);
        }
        return found("pi", false, Kind.UNKNOWN, null, null, null, model, null);
    }

    /**
     * opencode: the {@code provider/model} of {@code opencode.json} in its XDG configuration
     * directory and that provider's entry in {@code auth.json} in its XDG data directory.
     */
    private static Optional<HostAgent> opencode(Host host) {
        var configDir = host.xdg("XDG_CONFIG_HOME", ".config").resolve("opencode");
        var dataDir = host.xdg("XDG_DATA_HOME", ".local/share").resolve("opencode");
        if (!host.onPath("opencode") && !Files.isDirectory(configDir) && !Files.isDirectory(dataDir)) return Optional.empty();
        var model = plain(text(host.json(configDir.resolve("opencode.json")), "model"));
        var provider = model != null && model.contains("/") ? model.substring(0, model.indexOf('/')) : null;
        var authFile = dataDir.resolve("auth.json");
        var entry = providerEntry(host.json(authFile), provider);
        if (entry != null) {
            var kind = switch (String.valueOf(text(entry.value(), "type"))) {
                case "oauth" -> Kind.SUBSCRIPTION;
                case "api" -> Kind.API_KEY;
                default -> Kind.UNKNOWN;
            };
            return found("opencode", true, kind, entry.provider(), null, null, model, host.display(authFile));
        }
        return found("opencode", false, Kind.UNKNOWN, null, null, null, model, null);
    }

    /** GitHub Copilot CLI: a signed-in user in {@code ~/.copilot/config.json}, or {@code COPILOT_GITHUB_TOKEN}. */
    private static Optional<HostAgent> copilot(Host host) {
        var dir = host.home.resolve(".copilot");
        if (!host.onPath("copilot") && !Files.isDirectory(dir)) return Optional.empty();
        var configFile = dir.resolve("config.json");
        var config = host.json(configFile);
        var settings = host.json(dir.resolve("settings.json"));
        var model = plain(text(settings, "model") != null ? text(settings, "model") : text(config, "model"));
        if (config.get("logged_in_users") instanceof JqArray users && users.size() > 0
                || config.get("last_logged_in_user") instanceof JqObject) {
            return found("copilot", true, Kind.SUBSCRIPTION, "github", null, null, model, host.display(configFile));
        }
        if (host.env("COPILOT_GITHUB_TOKEN") != null) {
            return found("copilot", true, Kind.SUBSCRIPTION, "github", null, null, model, "$COPILOT_GITHUB_TOKEN");
        }
        return found("copilot", false, Kind.UNKNOWN, null, null, null, model, null);
    }

    /** Bob Shell: only its API key, {@code BOBSHELL_API_KEY}, is read. */
    private static Optional<HostAgent> bob(Host host) {
        if (!host.onPath("bob") && !Files.isDirectory(host.home.resolve(".bob"))) return Optional.empty();
        if (host.env("BOBSHELL_API_KEY") != null) {
            return found("bob", true, Kind.API_KEY, null, null, null, null, "$BOBSHELL_API_KEY");
        }
        return found("bob", false, Kind.UNKNOWN, null, null, null, null, null);
    }

    private static Optional<HostAgent> found(String agent, boolean signedIn, Kind kind, String provider, String project,
                                             String region, String model, String foundIn) {
        return Optional.of(new HostAgent(agent, signedIn, signedIn ? kind : Kind.UNKNOWN, plain(provider),
                project, region, model, signedIn ? foundIn : null));
    }

    /**
     * A provider's entry in an {@code auth.json} keyed by provider: {@code preferred}'s, or the
     * first when no provider is preferred. Another provider's entry never stands in for the
     * preferred one, which the agent would not use.
     */
    private static ProviderEntry providerEntry(JqObject auth, String preferred) {
        if (preferred != null) {
            return auth.get(preferred) instanceof JqObject entry ? new ProviderEntry(preferred, entry) : null;
        }
        for (var key : auth.keys()) {
            var name = plain(key);
            if (name != null && auth.get(key) instanceof JqObject entry) return new ProviderEntry(name, entry);
        }
        return null;
    }

    private record ProviderEntry(String provider, JqObject value) {}

    /**
     * {@code value} when it is plain enough to be a model, project, region or provider name, else
     * null: what the probe returns comes from the person's files, so this is what holds that none
     * of it is a credential, even one put where a model belongs.
     */
    static String plain(String value) {
        if (value == null) return null;
        var v = value.strip();
        if (!PLAIN_VALUE.matcher(v).matches() || TOKEN_RUN.matcher(v).find() || SecretRedactor.hasSecretShape(v)) return null;
        return v;
    }

    private static String text(JqObject object, String key) {
        var value = object.get(key);
        if (value == null || !value.isString()) return null;
        var text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static boolean truthy(String value) {
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true"));
    }

    /** A file that exists but cannot be read, is too large, or does not parse. */
    private static final class Unreadable extends RuntimeException {
        Unreadable() {
            super(null, null, false, false);
        }
    }

    /** The home, environment and {@code PATH} the probe reads. */
    private record Host(Path home, Map<String, String> environment, List<Path> path) {

        /** An environment variable, null when unset or blank. */
        String env(String name) {
            var value = environment.get(name);
            return value == null || value.isBlank() ? null : value;
        }

        boolean onPath(String command) {
            for (var dir : path) {
                var file = dir.resolve(command);
                if (Files.isRegularFile(file) && Files.isExecutable(file)) return true;
            }
            return false;
        }

        Path xdg(String variable, String fallback) {
            var dir = env(variable);
            return dir != null && Path.of(dir).isAbsolute() ? Path.of(dir) : home.resolve(fallback);
        }

        /** {@code file}'s text; empty when it does not exist. */
        String read(Path file) {
            if (!Files.exists(file)) return "";
            try {
                if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) throw new Unreadable();
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                throw new Unreadable();
            }
        }

        /** {@code file}'s JSON object; empty when the file does not exist or is empty. */
        JqObject json(Path file) {
            var text = read(file);
            if (text.isBlank()) return JqObject.EMPTY;
            JqValue value;
            try {
                value = Jjq.parseForeign(text, file.toString());
            } catch (RuntimeException e) {
                throw new Unreadable();
            }
            if (value instanceof JqObject object) return object;
            throw new Unreadable();
        }

        /** The environment over the first of the {@code .env} files that exists, as Gemini CLI reads only that one. */
        Map<String, String> envWithDotenv(Path... files) {
            var vars = new HashMap<String, String>();
            for (var file : files) {
                if (!Files.exists(file)) continue;
                for (var line : read(file).split("\n", -1)) {
                    var m = DOTENV_LINE.matcher(line);
                    if (!m.matches()) continue;
                    var value = m.group(2);
                    if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                            || value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (!value.isBlank()) vars.put(m.group(1), value);
                }
                break;
            }
            environment.forEach((k, v) -> {
                if (v != null && !v.isBlank()) vars.put(k, v);
            });
            return vars;
        }

        /** {@code file} as a person reads it: under the home as {@code ~/...}. */
        String display(Path file) {
            return file.startsWith(home) ? "~/" + home.relativize(file).toString().replace(File.separatorChar, '/') : file.toString();
        }
    }
}
