package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.incus.Container;

import java.util.ArrayList;
import java.util.List;

public class CodexSetup implements ToolSetup {

    @Override
    public String name() {
        return "codex";
    }

    @Override
    public String feature() {
        return "openai";
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
        System.out.println("Installing Codex CLI...");
        c.runInteractive("Failed to install Codex CLI",
                "npm", "install", "-g", "--ignore-scripts", "--loglevel=error", "@openai/codex");
    }

    static final String CONFIG_PATH = "/home/agentuser/.codex/config.toml";

    private void configureSettings(Container c) {
        System.out.println("Configuring Codex CLI...");
        var configToml = """
                model = "o4-mini"
                approval_policy = "full-auto"
                forced_login_method = "api"
                check_for_update_on_startup = false

                [projects."/home/agentuser"]
                trust_level = "trusted"
                """;
        c.sh("mkdir -p /home/agentuser/.codex");
        c.writeFile(CONFIG_PATH, configToml);
        c.chown("/home/agentuser/.codex", "agentuser:agentuser");
    }

}
