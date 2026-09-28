package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;

import java.util.ArrayList;
import java.util.List;

public class PiSetup implements ToolSetup {

    static final String DEFAULT_PROVIDER = "anthropic";
    static final String DEFAULT_MODEL = "claude-sonnet-4-6";

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
        provider.setDescription("Pi provider (e.g. anthropic, openai, vertex, google)");
        provider.setPattern("^[a-z][a-z0-9_-]*$");
        provider.setOptional(true);
        provider.setReconfigurable(true);
        provider.setDefault(DEFAULT_PROVIDER);
        params.put("provider", provider);

        var model = new ToolDef.ParameterDef();
        model.setType("string");
        model.setDescription("Model ID (e.g. claude-sonnet-4-6, gpt-4.1, gemini-3.7-flash)");
        model.setPattern("^[a-zA-Z0-9][-a-zA-Z0-9._@:]*$");
        model.setOptional(true);
        model.setReconfigurable(true);
        model.setDefault(DEFAULT_MODEL);
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
     * Pi spends Claude Code's credential, or OpenAI's with {@code provider: openai}, through the
     * proxy entries those tools declare -- it has none of its own. Both are listed: which one a
     * template uses is a parameter, and offering one account choice too many is harmless.
     */
    @Override
    public java.util.Set<String> credentialNamespaces() {
        return new java.util.LinkedHashSet<>(List.of(SpawnConfig.ClaudeConfig.NAMESPACE, "openai"));
    }

    /** Only the one its {@code provider} spends, once the template has said which. */
    @Override
    public java.util.Set<String> credentialNamespaces(java.util.Map<String, String> resolvedParams) {
        return switch (credentialFor(resolvedParams)) {
            case ANTHROPIC, VERTEX -> java.util.Set.of(SpawnConfig.ClaudeConfig.NAMESPACE);
            case OPENAI -> java.util.Set.of("openai");
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
            case OPENAI, NONE -> "";
        };
    }

    /** The isx-managed credential a pi provider spends. */
    private enum Credential {
        /** Claude's account, whatever its type. */
        ANTHROPIC,
        /** Claude's account, which must be a Vertex AI one. */
        VERTEX,
        /** The {@code openai} namespace's API key. */
        OPENAI,
        /** None isx manages: pi brings its own for a provider isx does not know. */
        NONE
    }

    /** Which credential pi spends for the {@code provider} in its resolved parameters. */
    private static Credential credentialFor(java.util.Map<String, String> resolvedParams) {
        var provider = resolvedParams.get("provider");
        return switch (provider != null ? provider : DEFAULT_PROVIDER) {
            case "anthropic" -> Credential.ANTHROPIC;
            case "vertex", "google" -> Credential.VERTEX;
            case "openai" -> Credential.OPENAI;
            default -> Credential.NONE;
        };
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
            if (type == SpawnConfig.ClaudeAccountType.VERTEX) {
                entries.add(EnvEntry.set("GOOGLE_CLOUD_PROJECT", account.getVertexProjectId()));
                entries.add(EnvEntry.set("GOOGLE_CLOUD_LOCATION", account.getCloudMlRegion()));
            }
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
        BuildOutput.stepStart("Installing Pi coding agent...");
        NpmGlobalInstall.install(c, "Pi coding agent", "@earendil-works/pi-coding-agent");
        BuildOutput.stepDone();
    }

    private void configureSettings(Container c, java.util.Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Configuring Pi...");
        var provider = resolvedParams.getOrDefault("provider", DEFAULT_PROVIDER);
        var model = resolvedParams.getOrDefault("model", DEFAULT_MODEL);
        var settingsJson = """
                {
                  "enableInstallTelemetry": false,
                  "quietStartup": true,
                  "defaultProvider": "%s",
                  "defaultModel": "%s",
                  "defaultThinkingLevel": "medium"
                }
                """.formatted(provider, model);
        c.sh("mkdir -p /home/agentuser/.pi/agent");
        c.writeFile("/home/agentuser/.pi/agent/settings.json", settingsJson);
        c.chown("/home/agentuser/.pi", "agentuser:agentuser");
        BuildOutput.stepDone();
    }

}
