package dev.incusspawn.tool;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import jakarta.enterprise.context.Dependent;

@Dependent
public class ClaudeSetup implements ToolSetup {

    @Override
    public String name() {
        return "claude";
    }

    @Override
    public void install(Container c) {
        installBinary(c);
        configureSettings(c);
        configureAuth(c);
    }

    private void installBinary(Container c) {
        System.out.println("Installing Claude Code...");
        // Ensure ~/.local/bin is on agentuser's PATH before installing, so the
        // installer doesn't warn about it and claude is immediately available.
        c.sh("mkdir -p /home/agentuser/.local/bin && " +
                "chown -R agentuser:agentuser /home/agentuser/.local && " +
                "grep -q '.local/bin' /home/agentuser/.bashrc 2>/dev/null || " +
                "echo 'export PATH=\"$HOME/.local/bin:$PATH\"' >> /home/agentuser/.bashrc");
        // Install as agentuser so it lands in /home/agentuser/.local/bin
        c.runAsUser("agentuser", "curl -fsSL https://claude.ai/install.sh | sh",
                "Failed to install Claude Code");
    }

    private void configureSettings(Container c) {
        System.out.println("Configuring Claude Code for agent use...");
        var settingsJson = """
                {
                  "permissions": {
                    "defaultMode": "bypassPermissions",
                    "skipDangerousModePermissionPrompt": true,
                    "allow": [
                      "Bash(*)",
                      "Read(**)",
                      "Edit(**)",
                      "Write(**)",
                      "Glob(**)",
                      "Grep(**)",
                      "WebFetch",
                      "WebSearch",
                      "Agent(*)"
                    ]
                  }
                }
                """;
        var claudeJson = """
                {
                  "hasCompletedOnboarding": true,
                  "hasSeenTasksHint": true,
                  "numStartups": 1,
                  "autoUpdates": false
                }
                """;
        c.sh("mkdir -p /home/agentuser/.claude");
        c.writeFile("/home/agentuser/.claude/settings.json", settingsJson);
        c.writeFile("/home/agentuser/.claude.json", claudeJson);
        c.chown("/home/agentuser/.claude", "agentuser:agentuser");
        c.chown("/home/agentuser/.claude.json", "agentuser:agentuser");

        c.appendToProfile("export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1");
    }

    private void configureAuth(Container c) {
        var config = SpawnConfig.load();

        if (!config.getClaude().isUseVertex() && config.getClaude().getApiKey().isBlank()) {
            System.err.println("  Warning: no Claude credentials configured. Run 'isx init' to set up authentication.");
        }

        if (config.getClaude().isUseVertex()) {
            c.appendToProfile("export CLAUDE_CODE_USE_VERTEX=1");
            if (!config.getClaude().getCloudMlRegion().isBlank()) {
                c.appendToProfile("export CLOUD_ML_REGION=" + config.getClaude().getCloudMlRegion());
            }
            if (!config.getClaude().getVertexProjectId().isBlank()) {
                c.appendToProfile("export ANTHROPIC_VERTEX_PROJECT_ID=" + config.getClaude().getVertexProjectId());
            }
        } else if (!config.getClaude().getApiKey().isBlank()) {
            c.appendToProfile("export ANTHROPIC_API_KEY=" + config.getClaude().getApiKey());
        }
    }
}
