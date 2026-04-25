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
        var claudeJsonBuilder = new StringBuilder();
        claudeJsonBuilder.append("""
                {
                  "hasCompletedOnboarding": true,
                  "hasSeenTasksHint": true,
                  "numStartups": 1,
                  "autoUpdates": false,
                """);
        if (!SpawnConfig.load().getClaude().isUseVertex()) {
            claudeJsonBuilder.append("""
                  "customApiKeyResponses": {
                    "approved": ["sk-ant-placeholder"],
                    "rejected": []
                  },
                """);
        }
        claudeJsonBuilder.append("""
                  "projects": {
                    "/home/agentuser": {
                      "allowedTools": [],
                      "hasTrustDialogAccepted": true
                    }
                  }
                }
                """);
        var claudeJson = claudeJsonBuilder.toString();
        c.sh("mkdir -p /home/agentuser/.claude");
        c.writeFile("/home/agentuser/.claude/settings.json", settingsJson);
        c.writeFile("/home/agentuser/.claude.json", claudeJson);
        c.chown("/home/agentuser/.claude", "agentuser:agentuser");
        c.chown("/home/agentuser/.claude.json", "agentuser:agentuser");

        c.appendToProfile("export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1");
        c.appendToProfile("export CLAUDE_CODE_DISABLE_TERMINAL_TITLE=1");
    }

    /**
     * Configure auth env vars so Claude Code skips login and makes API requests.
     * The MITM proxy handles actual credential injection — no real secrets enter the container.
     * <p>
     * When the host uses Vertex AI, the container also runs in Vertex mode (with auth
     * skipped) so it gets the same model list and features. Requests go to
     * api.anthropic.com via ANTHROPIC_VERTEX_BASE_URL, where the proxy intercepts them
     * and forwards to the real Vertex endpoint with GCP credentials.
     * <p>
     * When the host uses a direct API key, the container gets a placeholder API key
     * and the proxy injects the real key.
     */
    private void configureAuth(Container c) {
        var config = SpawnConfig.load();
        var claude = config.getClaude();
        if (claude.isUseVertex()) {
            c.appendToProfile("export CLAUDE_CODE_USE_VERTEX=1");
            c.appendToProfile("export CLAUDE_CODE_SKIP_VERTEX_AUTH=1");
            c.appendToProfile("export CLOUD_ML_REGION=" + claude.getCloudMlRegion());
            c.appendToProfile("export ANTHROPIC_VERTEX_PROJECT_ID=" + claude.getVertexProjectId());
            c.appendToProfile("export ANTHROPIC_VERTEX_BASE_URL=https://api.anthropic.com/v1");
        } else {
            c.appendToProfile("export ANTHROPIC_API_KEY=sk-ant-placeholder");
        }
    }
}
