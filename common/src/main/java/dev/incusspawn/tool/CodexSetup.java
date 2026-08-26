package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CodexSetup implements ToolSetup {

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
        apiKey.setConfigPath("openai.apiKey");
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
    public List<EnvEntry> envEntries(java.util.Map<String, String> resolvedParams) {
        var entries = new ArrayList<EnvEntry>();
        entries.add(EnvEntry.set("OPENAI_API_KEY", "sk-placeholder"));
        return entries;
    }

    @Override
    public void install(Container c, java.util.Map<String, String> resolvedParams) {
        installBinary(c);
        configureSettings(c);
    }

    private void installBinary(Container c) {
        BuildOutput.stepStart("Installing Codex CLI...");
        c.runQuiet("Failed to install Codex CLI",
                "npm", "install", "-g", "--ignore-scripts", "--loglevel=error", "@openai/codex");
        BuildOutput.stepDone();
    }

    public static final String CONFIG_PATH = "/home/agentuser/.codex/config.toml";
    static final String AUTH_PATH = "/home/agentuser/.codex/auth.json";

    private void configureSettings(Container c) {
        BuildOutput.stepStart("Configuring Codex CLI...");
        var configToml = """
                model = "o4-mini"
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
                """;
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
