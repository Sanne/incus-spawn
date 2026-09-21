package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CodexSetup implements ToolSetup {

    private static final String DEFAULT_MODEL = "o4-mini";
    private static final String DEFAULT_EFFORT = "high";

    @Override
    public String name() {
        return "codex";
    }

    @Override
    public String description() {
        return "Codex CLI — OpenAI coding assistant";
    }

    @Override
    public String feature() {
        return "openai";
    }

    @Override
    public ToolDef.ProxyDef proxy() {
        var apiKey = new ToolDef.ConfigEntry();
        apiKey.setConfigPath("apiKey");
        apiKey.setDescription("OpenAI API key");
        apiKey.setSecret(true);
        apiKey.setHelp(List.of(
                "To create an API key:",
                "  1. Go to https://platform.openai.com/api-keys",
                "  2. Click 'Create new secret key'",
                "  3. Copy the generated key (it is only shown once)",
                "",
                "Note: API usage requires billing credits, even on free accounts.",
                "Add credits at https://platform.openai.com/settings/organization/billing"));

        var auth = new ToolDef.AuthDef();
        auth.setDomains(List.of("api.openai.com"));
        auth.setType("bearer");
        auth.setToken("${api-key}");

        var proxy = new ToolDef.ProxyDef();
        proxy.setConfigNamespace("openai");
        proxy.setConfiguration(Map.of("api-key", apiKey));
        proxy.setAuth(List.of(auth));
        return proxy;
    }

    @Override
    public List<ToolDef.ActionEntry> actions() {
        var a = new ToolDef.ActionEntry();
        a.setLabel("Codex CLI");
        a.setType("shell");
        a.setCommand("codex");
        a.setAutoReturn(true);
        return List.of(a);
    }

    @Override
    public List<String> packages() {
        return List.of("nodejs");
    }

    @Override
    public Map<String, ToolDef.ParameterDef> parameters() {
        var params = new java.util.LinkedHashMap<String, ToolDef.ParameterDef>();

        var model = new ToolDef.ParameterDef();
        model.setType("string");
        model.setDescription("OpenAI model ID (e.g. o4-mini, gpt-5.3-codex)");
        model.setPattern("^[a-zA-Z0-9][-a-zA-Z0-9._@:]*$");
        model.setOptional(true);
        model.setReconfigurable(true);
        model.setDefault(DEFAULT_MODEL);
        params.put("model", model);

        var effort = new ToolDef.ParameterDef();
        effort.setType("string");
        effort.setDescription("Model reasoning effort (minimal, low, medium, high, xhigh)");
        effort.setPattern("^(minimal|low|medium|high|xhigh)$");
        effort.setOptional(true);
        effort.setReconfigurable(true);
        effort.setDefault(DEFAULT_EFFORT);
        params.put("effort", effort);

        return params;
    }

    @Override
    public List<EnvEntry> envEntries(java.util.Map<String, String> resolvedParams) {
        var entries = new ArrayList<EnvEntry>();
        entries.add(EnvEntry.set("OPENAI_API_KEY", "sk-placeholder"));
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
        BuildOutput.stepStart("Installing Codex CLI...");
        c.runQuiet("Failed to install Codex CLI",
                "npm", "install", "-g", "--ignore-scripts", "--loglevel=error", "@openai/codex");
        BuildOutput.stepDone();
    }

    public static final String CONFIG_PATH = "/home/agentuser/.codex/config.toml";
    static final String AUTH_PATH = "/home/agentuser/.codex/auth.json";

    private void configureSettings(Container c, Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Configuring Codex CLI...");
        var model = resolvedParams.getOrDefault("model", DEFAULT_MODEL);
        var effort = resolvedParams.getOrDefault("effort", DEFAULT_EFFORT);
        var configToml = """
                model = "%s"
                model_reasoning_effort = "%s"
                approval_policy = "never"
                sandbox_mode = "danger-full-access"
                forced_login_method = "api"
                check_for_update_on_startup = false

                [notice]
                hide_full_access_warning = true

                [tui]
                show_tooltips = false

                [projects."/home/agentuser"]
                trust_level = "trusted"
                """.formatted(model, effort);
        var authJson = """
                {
                  "auth_mode": "apikey",
                  "OPENAI_API_KEY": "sk-placeholder"
                }
                """;
        c.sh("mkdir -p /home/agentuser/.codex");
        c.writeFile(CONFIG_PATH, configToml);
        c.writeFile(AUTH_PATH, authJson);
        c.chown("/home/agentuser/.codex", "agentuser:agentuser");
        BuildOutput.stepDone();
    }

}
