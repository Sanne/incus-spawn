package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AiderSetup implements ToolSetup {

    static final String CONFIG_PATH = "/home/agentuser/.aider.conf.yml";

    @Override
    public String name() {
        return "aider";
    }

    @Override
    public List<ToolDef.ActionEntry> actions() {
        var a = new ToolDef.ActionEntry();
        a.setLabel("aider");
        a.setType("shell");
        a.setCommand("aider");
        a.setAutoReturn(true);
        return List.of(a);
    }

    @Override
    public List<String> packages() {
        return List.of("pipx", "python3.12");
    }

    @Override
    public List<EnvEntry> envEntries(Map<String, String> resolvedParams) {
        var entries = new ArrayList<EnvEntry>();
        entries.add(EnvEntry.set("ANTHROPIC_API_KEY", "sk-ant-placeholder"));
        var config = SpawnConfig.load();
        if (config.isFeatureEnabled("openai")) {
            entries.add(EnvEntry.set("OPENAI_API_KEY", "sk-placeholder"));
        }
        return entries;
    }

    @Override
    public void install(Container c, Map<String, String> resolvedParams) {
        installBinary(c);
        configureSettings(c);
    }

    private void installBinary(Container c) {
        System.out.println("Installing aider...");
        c.runInteractive("Failed to install aider",
                "sh", "-c", "PIPX_HOME=/opt/pipx PIPX_BIN_DIR=/usr/local/bin pipx install" +
                        " --python python3.12 aider-chat");
    }

    private void configureSettings(Container c) {
        System.out.println("Configuring aider...");
        var config = """
                model: anthropic/claude-sonnet-4-6
                auto-commits: false
                check-update: false
                analytics-disable: true
                yes-always: true
                """;
        c.writeFile(CONFIG_PATH, config);
        c.chown(CONFIG_PATH, "agentuser:agentuser");
    }
}
