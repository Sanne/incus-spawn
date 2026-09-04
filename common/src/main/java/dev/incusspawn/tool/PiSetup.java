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
    public java.util.Map<String, ToolDef.ParameterDef> parameters() {
        var params = new java.util.LinkedHashMap<String, ToolDef.ParameterDef>();

        var provider = new ToolDef.ParameterDef();
        provider.setType("string");
        provider.setDescription("Pi provider (e.g. anthropic, vertex, google)");
        provider.setPattern("^[a-z][a-z0-9_-]*$");
        provider.setOptional(true);
        provider.setReconfigurable(true);
        provider.setDefault(DEFAULT_PROVIDER);
        params.put("provider", provider);

        var model = new ToolDef.ParameterDef();
        model.setType("string");
        model.setDescription("Model ID (e.g. claude-sonnet-4-6, gemini-3.7-flash)");
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
        a.setCommand("pi");
        a.setAutoReturn(true);
        return List.of(a);
    }

    @Override
    public List<String> packages() {
        // fd-find (provides 'fd') and ripgrep (provides 'rg') are pre-installed so
        // pi's tools-manager finds them in PATH and skips downloading them on first run.
        return List.of("nodejs", "fd-find", "ripgrep");
    }

    @Override
    public List<EnvEntry> envEntries(java.util.Map<String, String> resolvedParams) {
        var entries = new ArrayList<EnvEntry>();
        var provider = resolvedParams.getOrDefault("provider", DEFAULT_PROVIDER);

        if ("vertex".equals(provider) || "google".equals(provider)) {
            var claude = SpawnConfig.load().getClaude();
            if (claude.isUseVertex()) {
                entries.add(EnvEntry.set("GOOGLE_CLOUD_PROJECT", claude.getVertexProjectId()));
                entries.add(EnvEntry.set("GOOGLE_CLOUD_LOCATION", claude.getCloudMlRegion()));
            }
        } else {
            var claude = SpawnConfig.load().getClaude();
            if (claude.isOauthMode()) {
                entries.add(EnvEntry.set("ANTHROPIC_OAUTH_TOKEN", SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN));
            } else {
                entries.add(EnvEntry.set("ANTHROPIC_API_KEY", "sk-ant-placeholder"));
            }
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
        c.runQuiet("Failed to install Pi coding agent",
                "npm", "install", "-g", "--ignore-scripts", "--loglevel=error", "@earendil-works/pi-coding-agent");
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
