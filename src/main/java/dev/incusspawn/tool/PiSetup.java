package dev.incusspawn.tool;

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
        return List.of("nodejs", "npm", "fd-find", "ripgrep");
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
                "npm", "install", "-g", "@earendil-works/pi-coding-agent");
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
     * Pi always uses the standard Anthropic API format (/v1/messages).
     * The MITM proxy handles both direct key injection and Vertex AI
     * translation transparently — no Vertex-specific env vars needed.
     */
    private void configureAuth(Container c) {
        c.appendToProfile("export ANTHROPIC_API_KEY=sk-ant-placeholder");
        c.appendToProfile("export PI_SKIP_VERSION_CHECK=1");
    }
}
