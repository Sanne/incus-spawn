package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    public String description() {
        return "Claude Code — AI coding assistant";
    }

    @Override
    public ToolDef.ProxyDef proxy() {
        var apiKey = new ToolDef.ConfigEntry();
        apiKey.setConfigPath("apiKey");
        apiKey.setDescription("Anthropic API key");
        apiKey.setSecret(true);

        var oauthToken = new ToolDef.ConfigEntry();
        oauthToken.setConfigPath("oauthToken");
        oauthToken.setDescription("Claude Pro/Max OAuth token");
        oauthToken.setSecret(true);

        var auth = new ToolDef.AuthDef();
        auth.setDomains(List.of("api.anthropic.com"));
        auth.setType("anthropic");

        var proxy = new ToolDef.ProxyDef();
        proxy.setConfigNamespace(SpawnConfig.ClaudeConfig.NAMESPACE);
        proxy.setConfiguration(Map.of("api-key", apiKey, "oauth-token", oauthToken));
        proxy.setAuth(List.of(auth));
        return proxy;
    }

    @Override
    public dev.incusspawn.config.AccountShape accountShape() {
        return SpawnConfig.ClaudeConfig.ACCOUNT_SHAPE;
    }

    /**
     * The auth mode, not the account name: {@code envEntries} writes a different set of
     * variables for each mode, so vertex/oauth/api-key accounts are not interchangeable on a
     * container that has already been built -- but two accounts of the same mode are, since
     * nothing in the image distinguishes them.
     *
     * <p>No {@code rebakeForAccount}: the variables live in {@code /etc/profile.d}, which an
     * already-running agent has read, so rewriting them would describe a mode the live process
     * is not using. A cross-mode move means branching from a template built for it.
     */
    @Override
    public String bakedAccountIdentity(SpawnConfig config, String accountName) {
        var account = config.getClaude().accountNamed(accountName);
        if (account == null) return "";
        var type = account.effectiveType();
        return type == null ? "" : type.wireName();
    }

    @Override
    public List<ToolDef.ActionEntry> actions() {
        var a = new ToolDef.ActionEntry();
        a.setLabel("Claude Code");
        a.setType("shell");
        a.setCommand("d=$(find ~/.claude/projects -name '*.jsonl' -printf '%T@ %p\\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-); if [ -n \"$d\" ]; then c=$(grep -m1 -o '\"cwd\":\"[^\"]*\"' \"$d\" | cut -d'\"' -f4); [ -n \"$c\" ] && [ -d \"$c\" ] && cd \"$c\"; claude --continue; else claude; fi");
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
        return envEntries(resolvedParams, Map.of());
    }

    /**
     * The auth mode baked into the container comes from the account the <em>template</em>
     * selected, not from the global default -- otherwise a template pinned to a Vertex
     * account would build a container configured for an API key.
     */
    @Override
    public List<EnvEntry> envEntries(Map<String, String> resolvedParams,
                                     Map<String, String> accountSelection) {
        var account = SpawnConfig.load().getClaude()
                .accountNamed(accountSelection.get(SpawnConfig.ClaudeConfig.NAMESPACE));
        var type = account == null ? null : account.effectiveType();
        var entries = new ArrayList<EnvEntry>();
        entries.add(EnvEntry.raw("export PATH=\"$HOME/.local/bin${PATH:+:$PATH}\""));
        if (type == SpawnConfig.ClaudeAccountType.VERTEX) {
            entries.add(EnvEntry.set("CLAUDE_CODE_USE_VERTEX", "1"));
            entries.add(EnvEntry.set("CLAUDE_CODE_SKIP_VERTEX_AUTH", "1"));
            entries.add(EnvEntry.set("CLOUD_ML_REGION", account.getCloudMlRegion()));
            entries.add(EnvEntry.set("ANTHROPIC_VERTEX_PROJECT_ID", account.getVertexProjectId()));
            entries.add(EnvEntry.set("ANTHROPIC_VERTEX_BASE_URL", "https://api.anthropic.com/v1"));
        } else if (type == SpawnConfig.ClaudeAccountType.OAUTH) {
            entries.add(EnvEntry.set("CLAUDE_CODE_OAUTH_TOKEN", SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN));
        } else {
            entries.add(EnvEntry.set("ANTHROPIC_API_KEY", "sk-ant-placeholder"));
        }
        return entries;
    }

    @Override
    public void install(Container c, java.util.Map<String, String> resolvedParams) {
        installBinary(c);
        linkSkillsDir(c);
        var claude = SpawnConfig.load().getClaude();
        syncGcloudStub(c, claude);
        configureSettings(c, claude, resolvedParams);
    }

    private void linkSkillsDir(Container c) {
        c.sh("mkdir -p /home/agentuser/.agents/skills /home/agentuser/.claude"
                // Replace a pre-existing .claude/skills directory (from builds before the shared path)
                + " && if [ -d /home/agentuser/.claude/skills ] && [ ! -L /home/agentuser/.claude/skills ]; then"
                + " rm -rf /home/agentuser/.claude/skills; fi"
                + " && ln -sfn /home/agentuser/.agents/skills /home/agentuser/.claude/skills"
                + " && chown -R agentuser:agentuser /home/agentuser/.agents");
    }

    @Override
    public void reconfigure(Container c, java.util.Map<String, String> resolvedParams) {
        var claude = SpawnConfig.load().getClaude();
        syncGcloudStub(c, claude);
        configureSettings(c, claude, resolvedParams);
    }

    private void installBinary(Container c) {
        BuildOutput.stepStart("Installing Claude Code...");
        c.sh("mkdir -p /home/agentuser/.local/bin && " +
                "chown -R agentuser:agentuser /home/agentuser/.local");

        try {
            var version = Files.readString(
                    downloadCache.download(DOWNLOAD_BASE_URL + "/latest", null)).strip();

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
            BuildOutput.stepDone();
            BuildOutput.note("Claude Code " + version);
        } catch (IOException e) {
            throw new RuntimeException("Failed to install Claude Code: " + e.getMessage(), e);
        }
    }

    static final String GCLOUD_STUB_PATH = "/usr/local/bin/gcloud";

    private static final String GCLOUD_STUB_SCRIPT = """
            #!/bin/bash
            case "$*" in
              *auth*print-access-token*) echo "ya29.placeholder-for-proxy" ;;
              *) echo "gcloud stub: unsupported command: $*" >&2; exit 1 ;;
            esac
            """;

    /**
     * Ensure the gcloud stub state matches the current Vertex config.
     * In Vertex mode: install a stub that satisfies credential-refresh attempts
     * inside the container — the MITM proxy replaces the Authorization header,
     * so the token value is irrelevant.  Never overwrites an existing non-stub gcloud.
     * Outside Vertex mode: remove a leftover stub (from a parent built with Vertex)
     * so it doesn't shadow a real gcloud installed later.
     */
    public void syncGcloudStub(Container c, SpawnConfig.ClaudeConfig claude) {
        if (claude.isUseVertex()) {
            if (c.sh("command -v gcloud").success()) {
                return;
            }
            c.writeFile(GCLOUD_STUB_PATH, GCLOUD_STUB_SCRIPT);
            c.exec("chmod", "+x", GCLOUD_STUB_PATH);
        } else {
            c.sh("grep -q 'placeholder-for-proxy' " + GCLOUD_STUB_PATH + " 2>/dev/null && rm -f " + GCLOUD_STUB_PATH);
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
    /**
     * Claude Code's managed-policy memory layer. Written once per build by
     * {@code BuildCommand.writeAgentContext} (it needs the fully resolved image, which a
     * per-tool install step cannot see), but the path belongs here beside the rest of
     * {@code /etc/claude-code}.
     */
    public static final String MANAGED_MEMORY_PATH = "/etc/claude-code/CLAUDE.md";
    static final String USER_SETTINGS_PATH = "/home/agentuser/.claude/settings.json";
    private static final String STATUSLINE_PATH = "/etc/claude-code/statusline.sh";

    void configureSettings(Container c, SpawnConfig.ClaudeConfig claudeConfig) {
        configureSettings(c, claudeConfig, Map.of());
    }

    void configureSettings(Container c, SpawnConfig.ClaudeConfig claudeConfig, Map<String, String> params) {
        BuildOutput.stepStart("Configuring Claude Code...");
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
                    "CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY": "1",
                    "CLAUDE_CODE_SKIP_AUTO_UPDATE": "1"
                  },
                  "skipDangerousModePermissionPrompt": true,
                  "sandbox": {
                    "enabled": false
                  },
                  "statusLine": {
                    "type": "command",
                    "command": "%s"
                  }
                }
                """.formatted(STATUSLINE_PATH);
        c.sh("mkdir -p /etc/claude-code");
        c.writeFile(MANAGED_SETTINGS_PATH, managedSettingsJson);
        c.writeFile(STATUSLINE_PATH, STATUSLINE_SH);
        c.exec("chmod", "+x", STATUSLINE_PATH);

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
        BuildOutput.stepDone();
    }

    private static final String STATUSLINE_SH = """
            #!/bin/bash
            printf '\\033[1;32mRunning unconstrained in isx %s \\033[22m[%s]\\033[0m' "${ISX_VERSION:-dev}" "${ISX_CONTAINER:-container}"
            """;

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
