package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.util.List;
import java.util.Map;

/** Gemini CLI uses a guest placeholder; the proxy holds and injects its API key. */
public class GeminiSetup implements ToolSetup {

    private static final String PLACEHOLDER_API_KEY = "gemini-placeholder";
    static final String CONFIG_PATH = "/home/agentuser/.gemini/settings.json";
    public static final String MEMORY_PATH = "/home/agentuser/.gemini/GEMINI.md";
    static final String POLICY_PATH = "/home/agentuser/.gemini/policies/isx-auto-approve.toml";
    static final String AUTO_APPROVE_POLICY = """
            # isx provides the isolation boundary. Higher-priority user rules can override this default.
            [[rule]]
            toolName = "*"
            decision = "allow"
            allowRedirection = true
            priority = 0
            modes = ["default", "autoEdit", "yolo"]
            """;
    static final String LEGACY_LOGIN_NOTICE_PATH = "/etc/profile.d/isx-gemini-login.sh";

    @Override
    public String name() { return "gemini-cli"; }

    @Override
    public String description() { return "Gemini CLI — Google coding assistant"; }

    @Override
    public ToolDef.ProxyDef proxy() {
        var apiKey = new ToolDef.ConfigEntry();
        apiKey.setConfigPath("apiKey");
        apiKey.setDescription("Gemini API key");
        apiKey.setSecret(true);
        apiKey.setHelp(List.of("Create a Gemini API key at https://aistudio.google.com/apikey.",
                "The key stays on this host; the proxy supplies it to Gemini requests."));

        var auth = new ToolDef.AuthDef();
        auth.setDomains(List.of("generativelanguage.googleapis.com"));
        auth.setType("header");
        auth.setName("x-goog-api-key");
        auth.setValue("${api-key}");

        var proxy = new ToolDef.ProxyDef();
        proxy.setConfigNamespace("gemini");
        proxy.setConfiguration(Map.of("api-key", apiKey));
        proxy.setAuth(List.of(auth));
        proxy.setPlaceholders(List.of(new ToolDef.PlaceholderDef("GEMINI_API_KEY", "gemini-")));
        return proxy;
    }

    @Override
    public List<EnvEntry> envEntries(Map<String, String> resolvedParams) {
        // Read by Gemini at launch, never copied into its settings or credential storage.
        return List.of(EnvEntry.set("GEMINI_API_KEY", PLACEHOLDER_API_KEY));
    }

    @Override
    public List<String> packages() { return List.of("nodejs", "ripgrep"); }

    @Override
    public Map<String, ToolDef.ParameterDef> parameters() {
        var model = new ToolDef.ParameterDef();
        model.setType("string");
        model.setDescription("Gemini model ID (e.g. gemini-4-argon); omitted to use the CLI default");
        model.setPattern("^[a-zA-Z0-9][-a-zA-Z0-9._@:]*$");
        model.setOptional(true);
        model.setReconfigurable(true);
        return Map.of("model", model);
    }

    @Override
    public List<ToolDef.ActionEntry> actions() {
        var action = new ToolDef.ActionEntry();
        action.setLabel("Gemini CLI");
        action.setType("shell");
        // Actions update before old guests are rebuilt: preserve their login notice through zmx.
        action.setCommand("bash -lc gemini");
        action.setAutoReturn(true);
        return List.of(action);
    }

    @Override
    public void install(Container c, Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Installing Gemini CLI...");
        NpmGlobalInstall.install(c, "Gemini CLI", "@google/gemini-cli");
        BuildOutput.stepDone();
        configureSettings(c, resolvedParams);
    }

    @Override
    public void reconfigure(Container c, Map<String, String> resolvedParams) {
        configureSettings(c, resolvedParams);
    }

    @Override
    public void refreshInherited(Container c) {
        // Interactive Gemini needs an explicit auth selection even when the environment has
        // a key. Keep the parent's model and custom settings while upgrading #1407's files.
        var settings = c.exec("cat", CONFIG_PATH).assertSuccess("Failed to read Gemini settings").stdout();
        c.writeFile(CONFIG_PATH, selectApiKeyAuth(settings));
        c.chown(CONFIG_PATH, "agentuser:agentuser");
        removeLoginNotice(c);
    }

    static String selectApiKeyAuth(String json) {
        if (!(JqValues.parseStrict(json) instanceof JqObject root)) {
            throw new IllegalStateException("Gemini settings must be a JSON object");
        }
        var security = objectOrEmpty(root, "security");
        var auth = objectOrEmpty(security, "auth").with("selectedType", JqString.of("gemini-api-key"));
        return JqValues.toPrettyJsonString(root.with("security", security.with("auth", auth))) + "\n";
    }

    private static JqObject objectOrEmpty(JqObject parent, String key) {
        if (!parent.keys().contains(key)) return JqObject.EMPTY;
        if (parent.getField(key) instanceof JqObject object) return object;
        throw new IllegalStateException("Gemini setting '" + key + "' must be a JSON object");
    }

    private static void removeLoginNotice(Container c) {
        c.exec("rm", "-f", LEGACY_LOGIN_NOTICE_PATH)
                .assertSuccess("Failed to remove the old Gemini login notice");
    }

    /** Called after all template layers are built, so inherited notes and repositories are included. */
    public static void writeEnvironmentContext(Container c, String content) {
        c.sh("mkdir -p /home/agentuser/.gemini");
        c.writeFile(MEMORY_PATH, content);
        c.chown(MEMORY_PATH, "agentuser:agentuser");
    }

    private void configureSettings(Container c, Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Configuring Gemini CLI...");
        removeLoginNotice(c);
        c.sh("mkdir -p /home/agentuser/.gemini/policies");
        c.writeFile(CONFIG_PATH, settingsJson(resolvedParams));
        c.writeFile(POLICY_PATH, AUTO_APPROVE_POLICY);
        c.chown("/home/agentuser/.gemini", "agentuser:agentuser");
        BuildOutput.stepDone();
    }

    static String settingsJson(Map<String, String> resolvedParams) {
        // As with Claude and Codex, let upstream choose its default model unless explicitly pinned.
        var model = resolvedParams.get("model");
        var modelSetting = model == null ? "" : "  \"model\": {\"name\": \"" + model + "\"},\n";
        return "{\n" + modelSetting + """
                  "general": {
                    "enableAutoUpdate": false,
                    "enableAutoUpdateNotification": false
                  },
                  "ui": {"hideTips": true, "hideBanner": true, "showHomeDirectoryWarning": false},
                  "tools": {"sandbox": false},
                  "security": {
                    "auth": {"selectedType": "gemini-api-key"},
                    "folderTrust": {"enabled": false},
                    "toolSandboxing": false
                  },
                  "privacy": {"usageStatisticsEnabled": false},
                  "telemetry": {"enabled": false}
                }
                """;
    }
}
