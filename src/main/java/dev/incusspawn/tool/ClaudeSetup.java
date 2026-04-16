package dev.incusspawn.tool;

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
                  },
                  "skipDangerousModePermissionPrompt": true
                }
                """;
        var claudeJson = """
                {
                  "hasCompletedOnboarding": true,
                  "hasSeenTasksHint": true,
                  "numStartups": 1,
                  "autoUpdates": false,
                  "customApiKeyResponses": {
                    "approved": ["sk-ant-placeholder"],
                    "rejected": []
                  },
                  "projects": {
                    "/home/agentuser": {
                      "allowedTools": [],
                      "hasTrustDialogAccepted": true
                    }
                  }
                }
                """;
        c.sh("mkdir -p /home/agentuser/.claude");
        c.writeFile("/home/agentuser/.claude/settings.json", settingsJson);
        c.writeFile("/home/agentuser/.claude.json", claudeJson);
        c.chown("/home/agentuser/.claude", "agentuser:agentuser");
        c.chown("/home/agentuser/.claude.json", "agentuser:agentuser");

        c.appendToProfile("export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1");
    }

    /**
     * Configure auth env vars so Claude Code skips login and makes API requests.
     * The MITM proxy handles actual credential injection — no real secrets enter the container.
     * <p>
     * Always uses standard (non-Vertex) mode with a placeholder API key. When the host
     * is configured for Vertex AI, the proxy transparently translates standard API
     * requests to Vertex AI rawPredict format. The container has zero knowledge of Vertex.
     */
    private void configureAuth(Container c) {
        c.appendToProfile("export ANTHROPIC_API_KEY=sk-ant-placeholder");
    }
}
