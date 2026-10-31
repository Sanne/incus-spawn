package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.proxy.ProofToken;
import dev.incusspawn.util.BuildOutput;

import java.util.ArrayList;
import java.util.List;

public class PiSetup implements ToolSetup {

    static final String DEFAULT_PROVIDER = "anthropic";
    static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    /**
     * Where Pi finds the environment instructions. Pi has no system-wide layer like Claude Code's
     * managed policy, but it loads the {@code AGENTS.md} of every directory from its working
     * directory up to the root, so the root's reaches every session. The user's own
     * {@code ~/.pi/agent/AGENTS.md} stays theirs, and loads before it.
     */
    public static final String CONTEXT_PATH = "/AGENTS.md";

    /**
     * The extension that puts Claude Code's status line in Pi's footer: that this is isx, its
     * version and the instance's name. Root-owned outside the home like Claude Code's
     * {@code statusline.sh}, and loaded by the user settings' {@code extensions} entry, so the
     * user's own {@code ~/.pi/agent/extensions/} stays theirs.
     */
    static final String STATUS_EXTENSION_PATH = "/etc/isx/pi/isx-status.ts";

    /**
     * Reads {@code ISX_VERSION} and {@code ISX_CONTAINER} when Pi starts, never fixed by a build,
     * so a branch shows its own name. They come from the login environment; the hostname is the
     * instance's name too, for a Pi started without one. Every call is guarded: Pi exits when an
     * extension throws while loading, and a footer line is not worth an agent that will not start.
     */
    static final String STATUS_EXTENSION = """
            // Written by isx: Pi's footer says it runs in isx, which version, and in which instance.
            import { hostname } from "node:os";

            // Pi refuses to start when an extension throws, so a Pi whose API moved loses only this line.
            export default function (pi) {
              try {
                pi.on("session_start", async (_event, ctx) => {
                  try {
                    const version = process.env.ISX_VERSION || "dev";
                    const instance = process.env.ISX_CONTAINER || hostname();
                    const theme = ctx.ui.theme;
                    ctx.ui.setStatus("isx", theme.fg("success", theme.bold(`Running unconstrained in isx ${version}`))
                        + theme.fg("success", ` [${instance}]`));
                  } catch {}
                });
              } catch {}
            }
            """;

    @Override
    public String name() {
        return "pi";
    }

    @Override
    public String description() {
        return "Pi — AI coding assistant";
    }

    @Override
    public java.util.Map<String, ToolDef.ParameterDef> parameters() {
        var params = new java.util.LinkedHashMap<String, ToolDef.ParameterDef>();

        var provider = new ToolDef.ParameterDef();
        provider.setType("string");
        provider.setDescription("Pi provider (e.g. anthropic, openai, google, google-vertex)");
        provider.setPattern("^[a-z][a-z0-9_-]*$");
        provider.setOptional(true);
        provider.setReconfigurable(true);
        provider.setDefault(DEFAULT_PROVIDER);
        params.put("provider", provider);

        var model = new ToolDef.ParameterDef();
        model.setType("string");
        model.setDescription("Model ID (e.g. claude-sonnet-4-6, gpt-4.1, gemini-3.7-flash); omitted, "
                + DEFAULT_MODEL + " for " + DEFAULT_PROVIDER + " and Pi's own default otherwise");
        model.setPattern("^[a-zA-Z0-9][-a-zA-Z0-9._@:]*$");
        model.setOptional(true);
        model.setReconfigurable(true);
        // No declared default: one would be resolved before the build, so configureSettings could
        // not tell it from a model the template chose, and every provider would get a Claude model.
        params.put("model", model);

        return params;
    }

    @Override
    public List<ToolDef.ActionEntry> actions() {
        var a = new ToolDef.ActionEntry();
        a.setLabel("Pi Coding Agent");
        a.setType("shell");
        a.setCommand("d=$(find ~/.pi/agent/sessions -name '*.jsonl' -printf '%T@ %p\\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-); if [ -n \"$d\" ]; then c=$(grep -m1 -o '\"cwd\":\"[^\"]*\"' \"$d\" | cut -d'\"' -f4); [ -n \"$c\" ] && [ -d \"$c\" ] && cd \"$c\"; pi --continue || pi; else pi; fi");
        a.setAutoReturn(true);
        return List.of(a);
    }

    @Override
    public List<String> packages() {
        // fd-find (provides 'fd') and ripgrep (provides 'rg') are pre-installed so
        // pi's tools-manager finds them in PATH and skips downloading them on first run.
        return List.of("nodejs", "fd-find", "ripgrep");
    }

    /**
     * Pi spends Claude Code's credential, OpenAI's with {@code provider: openai}, or Gemini's with
     * {@code provider: google}, through the proxy entries those tools declare -- it has none of its
     * own. All are listed: which one a template uses is a parameter, and offering one account
     * choice too many is harmless.
     */
    @Override
    public java.util.Set<String> credentialNamespaces() {
        return new java.util.LinkedHashSet<>(List.of(SpawnConfig.ClaudeConfig.NAMESPACE, "openai", GEMINI_NAMESPACE));
    }

    static final String GEMINI_NAMESPACE = "gemini";

    /**
     * What Pi's google-vertex provider sends as its API key ({@code x-goog-api-key}): the claude
     * namespace's proof, which the proxy swaps for the host's gcloud token in the Vertex account's
     * project and region (#1114). The prefix is a Google API key's; nothing checks it.
     */
    static final ProofToken.Placeholder VERTEX_KEY =
            new ProofToken.Placeholder("GOOGLE_CLOUD_API_KEY", "AIza", SpawnConfig.ClaudeConfig.NAMESPACE);

    /**
     * Pi reads {@code ANTHROPIC_API_KEY}, {@code OPENAI_API_KEY} and {@code GEMINI_API_KEY} as
     * Claude Code, Codex and Gemini CLI do, which declare them; Claude's OAuth token and the
     * Vertex key under names of their own, declared here.
     */
    @Override
    public List<ProofToken.Placeholder> placeholders() {
        return List.of(new ProofToken.Placeholder("ANTHROPIC_OAUTH_TOKEN",
                SpawnConfig.ClaudeConfig.OAUTH_TOKEN_PREFIX, SpawnConfig.ClaudeConfig.NAMESPACE), VERTEX_KEY);
    }

    /** Only the one its {@code provider} spends, once the template has said which. */
    @Override
    public java.util.Set<String> credentialNamespaces(java.util.Map<String, String> resolvedParams) {
        return switch (credentialFor(resolvedParams)) {
            case ANTHROPIC, VERTEX -> java.util.Set.of(SpawnConfig.ClaudeConfig.NAMESPACE);
            case OPENAI -> java.util.Set.of("openai");
            case GEMINI -> java.util.Set.of(GEMINI_NAMESPACE);
            case NONE -> java.util.Set.of();
        };
    }

    @Override
    public String credentialProblem(SpawnConfig config, java.util.Map<String, String> resolvedParams,
                                    java.util.Map<String, String> selection) {
        return switch (credentialFor(resolvedParams)) {
            case ANTHROPIC -> ClaudeSetup.missingAccount(config, selection);
            case VERTEX -> {
                var account = config.getClaude().accountNamed(selection.get(SpawnConfig.ClaudeConfig.NAMESPACE));
                yield account != null && account.isComplete()
                        && account.effectiveType() == SpawnConfig.ClaudeAccountType.VERTEX
                        ? "" : "Vertex AI configuration";
            }
            case OPENAI, GEMINI, NONE -> "";
        };
    }

    /**
     * Names pi and its provider, since a template that lists only pi would not otherwise say why
     * it needs a Claude account, and offers another provider (#1750).
     */
    @Override
    public List<String> credentialNote(java.util.Map<String, String> resolvedParams) {
        var account = switch (credentialFor(resolvedParams)) {
            case ANTHROPIC -> "a Claude account";
            case VERTEX -> "a Vertex AI Claude account";
            case OPENAI, GEMINI, NONE -> "";
        };
        if (account.isEmpty()) return List.of();
        return List.of("Pi needs " + account + " for its provider '" + provider(resolvedParams) + "'.",
                "To use another provider, set it in the template's tools, run 'isx build' on the template"
                        + " and branch from it:"
                        + " '- pi: {provider: openai}' spends an OpenAI API key,"
                        + " and a provider isx does not manage needs nothing from isx.");
    }

    /** The isx-managed credential a pi provider spends. */
    private enum Credential {
        /** Claude's account, whatever its type. */
        ANTHROPIC,
        /** Claude's account, which must be a Vertex AI one, for Gemini on Vertex. */
        VERTEX,
        /** The {@code openai} namespace's API key. */
        OPENAI,
        /** The {@code gemini} namespace's API key, for the Gemini API. */
        GEMINI,
        /** None isx manages: pi brings its own for a provider isx does not know. */
        NONE
    }

    /** Which credential pi spends for the {@code provider} in its resolved parameters. */
    private static Credential credentialFor(java.util.Map<String, String> resolvedParams) {
        return switch (provider(resolvedParams)) {
            case "anthropic" -> Credential.ANTHROPIC;
            case "vertex", GOOGLE_VERTEX -> Credential.VERTEX;
            case "google" -> Credential.GEMINI;
            case "openai" -> Credential.OPENAI;
            default -> Credential.NONE;
        };
    }

    /** Pi's name for Gemini on Vertex AI; isx also takes {@code vertex}, which Pi does not know. */
    static final String GOOGLE_VERTEX = "google-vertex";

    /** The provider Pi's settings name for {@code provider}: its own name for isx's {@code vertex}. */
    static String piProvider(String provider) {
        return "vertex".equals(provider) ? GOOGLE_VERTEX : provider;
    }

    /** The {@code provider} in pi's resolved parameters; a null one is the default. */
    private static String provider(java.util.Map<String, String> resolvedParams) {
        var provider = resolvedParams.get("provider");
        return provider != null ? provider : DEFAULT_PROVIDER;
    }

    @Override
    public List<EnvEntry> envEntries(java.util.Map<String, String> resolvedParams) {
        return envEntries(resolvedParams, java.util.Map.of());
    }

    /**
     * Pi authenticates with the same Claude credential as Claude Code, so it has to resolve the
     * same account -- the template's, not the global default. Both write into one
     * {@code isx-env.sh}, so disagreeing here would describe two different auth modes at once.
     */
    @Override
    public List<EnvEntry> envEntries(java.util.Map<String, String> resolvedParams,
                                     java.util.Map<String, String> accountSelection) {
        var entries = new ArrayList<EnvEntry>();
        var credential = credentialFor(resolvedParams);
        var account = SpawnConfig.load().getClaude()
                .accountNamed(accountSelection.get(SpawnConfig.ClaudeConfig.NAMESPACE));
        var type = account == null ? null : account.effectiveType();

        if (credential == Credential.OPENAI) {
            // OpenAI has its own credential; the Claude account is irrelevant here.
            entries.add(EnvEntry.set("OPENAI_API_KEY", "sk-placeholder"));
        } else if (credential == Credential.VERTEX) {
            // An API key puts Pi on Vertex's express form, which the proxy moves into the account's project
            entries.add(EnvEntry.set(VERTEX_KEY.env(), VERTEX_KEY.staticValuePrefix()));
        } else if (credential == Credential.GEMINI) {
            entries.add(EnvEntry.set("GEMINI_API_KEY", GeminiSetup.PLACEHOLDER_API_KEY));
        } else if (type == SpawnConfig.ClaudeAccountType.OAUTH) {
            // ANTHROPIC, and NONE too: a placeholder is harmless to a provider that ignores it.
            entries.add(EnvEntry.set("ANTHROPIC_OAUTH_TOKEN", SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN));
        } else {
            entries.add(EnvEntry.set("ANTHROPIC_API_KEY", "sk-ant-placeholder"));
        }
        entries.add(EnvEntry.set("PI_SKIP_VERSION_CHECK", "1"));
        return entries;
    }

    @Override
    public void install(Container c, java.util.Map<String, String> resolvedParams) {
        installBinary(c);
        configureSettings(c, resolvedParams);
    }

    @Override
    public void reconfigure(Container c, java.util.Map<String, String> resolvedParams) {
        configureSettings(c, resolvedParams);
    }

    private void installBinary(Container c) {
        BuildOutput.stepStart("Installing Pi coding agent");
        NpmGlobalInstall.install(c, "Pi coding agent", "@earendil-works/pi-coding-agent");
        BuildOutput.stepDone();
    }

    /**
     * A hardlink to the build's one stored copy, like Claude Code's managed policy: Pi depends
     * neither on the claude tool nor on where Claude Code keeps its files. Pi never writes it.
     */
    @Override
    public String environmentContextPath() { return CONTEXT_PATH; }

    private void configureSettings(Container c, java.util.Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Configuring Pi");
        var provider = piProvider(provider(resolvedParams));
        var model = defaultModel(provider, resolvedParams.get("model"));
        var modelLine = model == null ? "" : "  \"defaultModel\": \"" + model + "\",\n";
        var settingsJson = """
                {
                  "enableInstallTelemetry": false,
                  "quietStartup": true,
                  "defaultProvider": "%s",
                %s  "defaultThinkingLevel": "medium",
                  "extensions": ["%s"]
                }
                """.formatted(provider, modelLine, STATUS_EXTENSION_PATH);
        c.sh("mkdir -p /etc/isx/pi /home/agentuser/.pi/agent");
        c.writeFile(STATUS_EXTENSION_PATH, STATUS_EXTENSION);
        c.writeFile("/home/agentuser/.pi/agent/settings.json", settingsJson);
        c.chown("/home/agentuser/.pi", "agentuser:agentuser");
        BuildOutput.stepDone();
    }

    /**
     * The template's model, or {@link #DEFAULT_MODEL} for the {@code anthropic} provider. Another
     * provider without one gets none, so Pi picks a model that provider serves: a Claude model
     * named to Vertex's Gemini path or to OpenAI is a 404 (#1918).
     */
    static String defaultModel(String provider, String model) {
        if (model != null) return model;
        return DEFAULT_PROVIDER.equals(provider) ? DEFAULT_MODEL : null;
    }

}
