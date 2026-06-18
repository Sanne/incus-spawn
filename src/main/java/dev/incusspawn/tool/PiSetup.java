package dev.incusspawn.tool;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;

import java.util.List;

public class PiSetup implements ToolSetup {

    @Override
    public String name() {
        return "pi";
    }

    @Override
    public List<String> packages() {
        // fd-find (provides 'fd') and ripgrep (provides 'rg') are pre-installed so
        // pi's tools-manager finds them in PATH and skips downloading them on first run.
        return List.of("nodejs", "fd-find", "ripgrep");
    }

    @Override
    public void install(Container c, java.util.Map<String, String> resolvedParams) {
        installBinary(c);
        configureSettings(c);
        configureAuth(c);
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

    /**
     * Pi always talks to the standard Anthropic API format (/v1/messages), so it can't
     * be put in Vertex mode the way Claude Code can — the proxy keeps doing the
     * standard-to-Vertex translation transparently for Pi's traffic.
     * <p>
     * For OAuth (Claude Pro/Max), Pi's own Anthropic provider already knows how to send
     * an OAuth-shaped request (Bearer auth, Claude Code identity/beta headers) whenever
     * its credential looks like an OAuth token (contains "sk-ant-oat"), read from
     * ANTHROPIC_OAUTH_TOKEN in preference to ANTHROPIC_API_KEY. So we hand Pi a
     * placeholder with that shape via ANTHROPIC_OAUTH_TOKEN and let Pi build its own
     * request — the proxy only swaps the placeholder for the real token.
     */
    void configureAuth(Container c) {
        var claude = SpawnConfig.load().getClaude();
        if (claude.isOauthMode()) {
            c.appendToProfile("export ANTHROPIC_OAUTH_TOKEN=" + SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN);
        } else {
            c.appendToProfile("export ANTHROPIC_API_KEY=sk-ant-placeholder");
        }
        c.appendToProfile("export PI_SKIP_VERSION_CHECK=1");
    }
}
