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
        var params = new java.util.LinkedHashMap<String, ToolDef.ParameterDef>();

        var model = new ToolDef.ParameterDef();
        model.setType("string");
        model.setDescription("Claude model ID (e.g. claude-opus-4-6, claude-sonnet-4-6, claude-haiku-4-5-20251001)");
        model.setPattern("^claude-[a-z0-9][-a-z0-9.]*$");
        model.setOptional(true);
        model.setReconfigurable(true);
        params.put("model", model);

        var attributionCommit = new ToolDef.ParameterDef();
        attributionCommit.setType("string");
        attributionCommit.setDescription("Git commit attribution trailer (e.g. 'Assisted-By: Claude Code <noreply@anthropic.com>')");
        attributionCommit.setOptional(true);
        attributionCommit.setReconfigurable(true);
        params.put("attribution-commit", attributionCommit);

        var attributionPr = new ToolDef.ParameterDef();
        attributionPr.setType("string");
        attributionPr.setDescription("Pull request attribution text (empty string to omit)");
        attributionPr.setOptional(true);
        attributionPr.setReconfigurable(true);
        params.put("attribution-pr", attributionPr);

        var theme = new ToolDef.ParameterDef();
        theme.setType("string");
        theme.setDescription("Color theme (dark, light, dark-daltonized, light-daltonized)");
        theme.setPattern("^(dark|light|dark-daltonized|light-daltonized)$");
        theme.setOptional(true);
        theme.setReconfigurable(true);
        params.put("theme", theme);

        var editorMode = new ToolDef.ParameterDef();
        editorMode.setType("string");
        editorMode.setDescription("Input editor mode (normal, vim)");
        editorMode.setPattern("^(normal|vim)$");
        editorMode.setOptional(true);
        editorMode.setReconfigurable(true);
        params.put("editor-mode", editorMode);

        var outputStyle = new ToolDef.ParameterDef();
        outputStyle.setType("string");
        outputStyle.setDescription("Response style (Default, Proactive, Explanatory, Learning)");
        outputStyle.setOptional(true);
        outputStyle.setReconfigurable(true);
        params.put("output-style", outputStyle);

        return params;
    }

    @Override
    public List<EnvEntry> envEntries(Map<String, String> resolvedParams) {
        var claude = SpawnConfig.load().getClaude();
        var entries = new ArrayList<EnvEntry>();
        entries.add(EnvEntry.raw("export PATH=\"$HOME/.local/bin${PATH:+:$PATH}\""));
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
        configureSettings(c, claude, resolvedParams);
    }

    @Override
    public void reconfigure(Container c, java.util.Map<String, String> resolvedParams) {
        var claude = SpawnConfig.load().getClaude();
        configureSettings(c, claude, resolvedParams);
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
    static final String USER_SETTINGS_PATH = "/home/agentuser/.claude/settings.json";

    void configureSettings(Container c, SpawnConfig.ClaudeConfig claudeConfig) {
        configureSettings(c, claudeConfig, Map.of());
    }

    void configureSettings(Container c, SpawnConfig.ClaudeConfig claudeConfig, Map<String, String> params) {
        System.out.println("Configuring Claude Code for agent use...");
        var managedSettingsJson = """
                {
                  "permissions": {
                    "defaultMode": "bypassPermissions",
                    "allow": [
                      "Bash(*)",
                      "Read(**)",
                      "Edit(**)",
                      "WebFetch",
                      "WebSearch",
                      "Agent(*)"
                    ]
                  },
                  "env": {
                    "DISABLE_AUTOUPDATER": "1",
                    "DO_NOT_TRACK": "1",
                    "DISABLE_ERROR_REPORTING": "1",
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

        var settingsJson = buildUserSettings(params);
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
        c.writeFile(USER_SETTINGS_PATH, settingsJson);
        c.writeFile("/home/agentuser/.claude.json", claudeJson);
        c.chown("/home/agentuser/.claude", "agentuser:agentuser");
        c.chown("/home/agentuser/.claude.json", "agentuser:agentuser");
    }

    static String buildUserSettings(Map<String, String> params) {
        var root = JSON.createObjectNode();
        root.put("disableDeepLinkRegistration", "disable");

        putIfPresent(root, params, "model", "model");
        putIfPresent(root, params, "theme", "theme");
        putIfPresent(root, params, "editor-mode", "editorMode");
        putIfPresent(root, params, "output-style", "outputStyle");

        var commitTrailer = params.get("attribution-commit");
        var prAttribution = params.get("attribution-pr");
        if (commitTrailer != null || prAttribution != null) {
            var attribution = root.putObject("attribution");
            if (commitTrailer != null) attribution.put("commit", commitTrailer);
            if (prAttribution != null) attribution.put("pr", prAttribution);
        }

        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize settings JSON", e);
        }
    }

    private static void putIfPresent(com.fasterxml.jackson.databind.node.ObjectNode node,
                                      Map<String, String> params, String paramKey, String jsonKey) {
        var value = params.get(paramKey);
        if (value != null) {
            node.put(jsonKey, value);
        }
    }

}
