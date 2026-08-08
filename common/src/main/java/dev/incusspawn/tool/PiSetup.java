package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;

import java.util.ArrayList;
import java.util.List;

public class PiSetup implements ToolSetup {

    @Override
    public String name() {
        return "pi";
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
        var claude = SpawnConfig.load().getClaude();
        var entries = new ArrayList<EnvEntry>();
        if (claude.isOauthMode()) {
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
        configureSettings(c);
    }

    private void installBinary(Container c) {
        System.out.println("Installing Pi coding agent...");
        c.runInteractive("Failed to install Pi coding agent",
                "npm", "install", "-g", "--ignore-scripts", "--loglevel=error", "@earendil-works/pi-coding-agent");
    }

    private void configureSettings(Container c) {
        System.out.println("Configuring Pi for agent use...");
        var settingsJson = """
                {
                  "enableInstallTelemetry": false,
                  "quietStartup": true,
                  "defaultProvider": "anthropic",
                  "defaultModel": "claude-sonnet-4-6",
                  "defaultThinkingLevel": "medium"
                }
                """;
        c.sh("mkdir -p /home/agentuser/.pi/agent");
        c.writeFile("/home/agentuser/.pi/agent/settings.json", settingsJson);
        c.chown("/home/agentuser/.pi", "agentuser:agentuser");
    }

}
