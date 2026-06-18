package dev.incusspawn.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;

import java.io.IOException;
import java.nio.file.Files;

public class ClaudeSetup implements ToolSetup {

    private static final String DOWNLOAD_BASE_URL = "https://downloads.claude.ai/claude-code-releases";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DownloadCache downloadCache;

    public ClaudeSetup() {
        this(new DownloadCache());
    }

    ClaudeSetup(DownloadCache downloadCache) {
        this.downloadCache = downloadCache;
    }

    @Override
    public String name() {
        return "claude";
    }

    @Override
    public void install(Container c, java.util.Map<String, String> resolvedParams) {
        installBinary(c);
        var claude = SpawnConfig.load().getClaude();
        configureSettings(c, claude);
        configureAuth(c, claude);
    }

    private void installBinary(Container c) {
        System.out.println("Installing Claude Code...");
        c.sh("mkdir -p /home/agentuser/.local/bin && " +
                "chown -R agentuser:agentuser /home/agentuser/.local && " +
                "grep -q '.local/bin' /home/agentuser/.bashrc 2>/dev/null || " +
                "echo 'export PATH=\"$HOME/.local/bin:$PATH\"' >> /home/agentuser/.bashrc");

        try {
            var version = Files.readString(
                    downloadCache.download(DOWNLOAD_BASE_URL + "/latest", null)).strip();
            System.out.println("  Latest version: " + version);

            var platform = detectPlatform(c.getArchitecture());
            var manifestJson = Files.readString(
                    downloadCache.download(DOWNLOAD_BASE_URL + "/" + version + "/manifest.json", null));
            var sha256 = extractChecksum(manifestJson, platform);

            var binaryUrl = DOWNLOAD_BASE_URL + "/" + version + "/" + platform + "/claude";
            var cached = downloadCache.download(binaryUrl, sha256);

            var versionBin = "/home/agentuser/.local/share/claude/versions/" + version;
            c.sh("mkdir -p /home/agentuser/.local/share/claude/versions"
                    + " /home/agentuser/.local/state/claude/locks"
                    + " /home/agentuser/.cache/claude/staging");
            c.filePush(cached.toString(), versionBin);
            c.exec("chmod", "+x", versionBin);
            c.sh("ln -sf " + versionBin + " /home/agentuser/.local/bin/claude");
            c.sh("chown -R agentuser:agentuser"
                    + " /home/agentuser/.local/share"
                    + " /home/agentuser/.local/state"
                    + " /home/agentuser/.cache");
        } catch (IOException e) {
            throw new RuntimeException("Failed to install Claude Code: " + e.getMessage(), e);
        }
    }

    static String detectPlatform(String containerArch) {
        return switch (containerArch) {
            case "amd64", "x86_64" -> "linux-x64";
            case "aarch64", "arm64" -> "linux-arm64";
            default -> throw new RuntimeException("Unsupported architecture: " + containerArch);
        };
    }

    static String extractChecksum(String manifestJson, String platform) throws IOException {
        var root = JSON.readTree(manifestJson);
        var checksum = root.path("platforms").path(platform).path("checksum").asText(null);
        if (checksum == null) {
            throw new IOException("Platform " + platform + " not found in manifest");
        }
        if (!checksum.matches("[a-fA-F0-9]{64}")) {
            throw new IOException("Invalid checksum for platform " + platform + ": " + checksum);
        }
        return checksum;
    }

    void configureSettings(Container c, SpawnConfig.ClaudeConfig claudeConfig) {
        System.out.println("Configuring Claude Code for agent use...");
        var settingsJson = """
                {
                  "env": {
                    "DISABLE_AUTOUPDATER": "1",
                    "DISABLE_TELEMETRY": "1",
                    "DO_NOT_TRACK": "1",
                    "DISABLE_ERROR_REPORTING": "1",
                    "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1",
                    "CLAUDE_CODE_DISABLE_TERMINAL_TITLE": "1",
                    "CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY": "1"
                  },
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
                  "skipDangerousModePermissionPrompt": true,
                  "disableDeepLinkRegistration": "disable"
                }
                """;
        var claudeJsonBuilder = new StringBuilder();
        claudeJsonBuilder.append("""
                {
                  "hasCompletedOnboarding": true,
                  "hasAcceptedTerms": true,
                  "hasSeenTasksHint": true,
                  "numStartups": 1,
                  "autoUpdates": false,
                  "installMethod": "native",
                  "officialMarketplaceAutoInstallAttempted": true,
                  "officialMarketplaceAutoInstalled": true,
                """);
        if (!claudeConfig.isUseVertex() && !claudeConfig.isOauthMode()) {
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
    }

    /**
     * Configure auth env vars so Claude Code skips login and makes API requests.
     * The MITM proxy replaces the placeholder credential with the real one — no real
     * secrets enter the container.
     * <p>
     * When the host uses Vertex AI, the container also runs in Vertex mode (with auth
     * skipped) so it gets the same model list and features. Requests go to
     * api.anthropic.com via ANTHROPIC_VERTEX_BASE_URL, where the proxy intercepts them
     * and forwards to the real Vertex endpoint with GCP credentials.
     * <p>
     * When the host has a Claude Pro/Max OAuth token configured, the container gets
     * CLAUDE_CODE_OAUTH_TOKEN (not ANTHROPIC_API_KEY) so Claude Code itself builds the
     * OAuth-shaped request (Bearer auth, Claude Code identity/beta headers) — the proxy
     * only swaps the placeholder token for the real one and never has to replicate
     * Anthropic's auth-header requirements itself.
     * <p>
     * Otherwise the container gets a placeholder direct API key and the proxy replaces
     * the x-api-key header with the real key.
     */
    void configureAuth(Container c, SpawnConfig.ClaudeConfig claude) {
        if (claude.isUseVertex()) {
            c.appendToProfile("export CLAUDE_CODE_USE_VERTEX=1");
            c.appendToProfile("export CLAUDE_CODE_SKIP_VERTEX_AUTH=1");
            c.appendToProfile("export CLOUD_ML_REGION=" + claude.getCloudMlRegion());
            c.appendToProfile("export ANTHROPIC_VERTEX_PROJECT_ID=" + claude.getVertexProjectId());
            c.appendToProfile("export ANTHROPIC_VERTEX_BASE_URL=https://api.anthropic.com/v1");
        } else if (claude.isOauthMode()) {
            c.appendToProfile("export CLAUDE_CODE_OAUTH_TOKEN=" + SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN);
        } else {
            c.appendToProfile("export ANTHROPIC_API_KEY=sk-ant-placeholder");
        }
    }
}
