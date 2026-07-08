package dev.incusspawn.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    public List<ToolDef.ActionEntry> actions() {
        var a = new ToolDef.ActionEntry();
        a.setLabel("Claude Code");
        a.setType("shell");
        a.setCommand("if find ~/.claude/projects -maxdepth 2 -name '*.jsonl' 2>/dev/null | grep -q .; then claude --continue; else claude; fi");
        a.setAutoReturn(true);
        return List.of(a);
    }

    @Override
    public Map<String, ToolDef.ParameterDef> parameters() {
        var model = new ToolDef.ParameterDef();
        model.setType("string");
        model.setDescription("Claude model ID (e.g. claude-opus-4-6, claude-sonnet-4-6, claude-haiku-4-5-20251001)");
        model.setPattern("^claude-[a-z0-9][-a-z0-9.]*$");
        model.setOptional(true);
        model.setReconfigurable(true);
        return Map.of("model", model);
    }

    @Override
    public List<EnvEntry> envEntries(Map<String, String> resolvedParams) {
        var claude = SpawnConfig.load().getClaude();
        var entries = new ArrayList<EnvEntry>();
        entries.add(EnvEntry.prepend("PATH", "$HOME/.local/bin", ":"));
        if (claude.isUseVertex()) {
            entries.add(EnvEntry.set("CLAUDE_CODE_USE_VERTEX", "1"));
            entries.add(EnvEntry.set("CLAUDE_CODE_SKIP_VERTEX_AUTH", "1"));
            entries.add(EnvEntry.set("CLOUD_ML_REGION", claude.getCloudMlRegion()));
            entries.add(EnvEntry.set("ANTHROPIC_VERTEX_PROJECT_ID", claude.getVertexProjectId()));
            entries.add(EnvEntry.set("ANTHROPIC_VERTEX_BASE_URL", "https://api.anthropic.com/v1"));
        } else if (claude.isOauthMode()) {
            entries.add(EnvEntry.set("CLAUDE_CODE_OAUTH_TOKEN", SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN));
        } else {
            entries.add(EnvEntry.set("ANTHROPIC_API_KEY", "sk-ant-placeholder"));
        }
        return entries;
    }

    @Override
    public void install(Container c, java.util.Map<String, String> resolvedParams) {
        installBinary(c);
        var claude = SpawnConfig.load().getClaude();
        configureSettings(c, claude, resolvedParams.get("model"));
    }

    @Override
    public void reconfigure(Container c, java.util.Map<String, String> resolvedParams) {
        var claude = SpawnConfig.load().getClaude();
        configureSettings(c, claude, resolvedParams.get("model"));
    }

    private void installBinary(Container c) {
        System.out.println("Installing Claude Code...");
        c.sh("mkdir -p /home/agentuser/.local/bin && " +
                "chown -R agentuser:agentuser /home/agentuser/.local");

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

    static final String MANAGED_SETTINGS_PATH = "/etc/claude-code/managed-settings.json";

    void configureSettings(Container c, SpawnConfig.ClaudeConfig claudeConfig) {
        configureSettings(c, claudeConfig, null);
    }

    void configureSettings(Container c, SpawnConfig.ClaudeConfig claudeConfig, String model) {
        System.out.println("Configuring Claude Code for agent use...");
        var managedSettingsJson = """
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
                  "env": {
                    "DISABLE_AUTOUPDATER": "1",
                    "DISABLE_TELEMETRY": "1",
                    "DO_NOT_TRACK": "1",
                    "DISABLE_ERROR_REPORTING": "1",
                    "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1",
                    "CLAUDE_CODE_DISABLE_TERMINAL_TITLE": "1",
                    "CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY": "1"
                  },
                  "skipDangerousModePermissionPrompt": true,
                  "sandbox": {
                    "enabled": false
                  }
                }
                """;
        c.sh("mkdir -p /etc/claude-code");
        c.writeFile(MANAGED_SETTINGS_PATH, managedSettingsJson);

        var settingsJsonBuilder = new StringBuilder();
        settingsJsonBuilder.append("{\n");
        settingsJsonBuilder.append("  \"disableDeepLinkRegistration\": \"disable\"");
        if (model != null) {
            settingsJsonBuilder.append(",\n  \"model\": \"").append(model).append("\"");
        }
        settingsJsonBuilder.append("\n}\n");
        var settingsJson = settingsJsonBuilder.toString();
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

}
