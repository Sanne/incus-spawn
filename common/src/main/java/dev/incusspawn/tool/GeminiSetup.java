package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.util.List;
import java.util.Map;

/** Gemini CLI uses a guest placeholder; the proxy holds and injects its API key. */
public class GeminiSetup implements ToolSetup {

    static final String PLACEHOLDER_API_KEY = "gemini-placeholder";
    static final String CONFIG_PATH = "/home/agentuser/.gemini/settings.json";
    /**
     * Gemini CLI reads no system-wide context file, and {@code ~/.gemini/GEMINI.md} is the
     * user's own (#1784). An extension's context file is loaded beside it, so the build's
     * instructions go to an extension of isx's own.
     */
    static final String EXTENSIONS_DIR = "/home/agentuser/.gemini/extensions";
    static final String EXTENSION_DIR = EXTENSIONS_DIR + "/isx";
    public static final String CONTEXT_PATH = EXTENSION_DIR + "/GEMINI.md";
    /** Where builds wrote the instructions before #1784: the user's own global context file. */
    public static final String LEGACY_CONTEXT_PATH = "/home/agentuser/.gemini/GEMINI.md";
    static final String EXTENSION_MANIFEST_PATH = EXTENSION_DIR + "/gemini-extension.json";
    static final String EXTENSION_MANIFEST = """
            {"name": "isx", "version": "1.0.0", "contextFileName": "GEMINI.md"}
            """;
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
        BuildOutput.stepStart("Installing Gemini CLI");
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
        // A parent built before #1784 has no extension for the instructions to load from.
        writeExtension(c);
        removeLoginNotice(c);
    }

    static String selectApiKeyAuth(String json) {
        if (!(Jjq.parseForeign(json, CONFIG_PATH) instanceof JqObject root)) {
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

    @Override
    public String environmentContextPath() { return CONTEXT_PATH; }

    /** The extension's directory and manifest, owned by agentuser, in one exec. */
    private static void writeExtension(Container c) {
        c.sh(extensionScript()).assertSuccess("Failed to write the isx Gemini extension");
    }

    static String extensionScript() {
        return Container.heredoc("set -e; mkdir -p " + EXTENSION_DIR + "; cat > " + EXTENSION_MANIFEST_PATH,
                EXTENSION_MANIFEST.strip()) + "\nchown agentuser:agentuser " + EXTENSIONS_DIR
                // Only isx's own extension recursively: on an inherited parent the user's may
                // hold whole node_modules trees.
                + "\nchown -R agentuser:agentuser " + EXTENSION_DIR;
    }

    private void configureSettings(Container c, Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Configuring Gemini CLI");
        removeLoginNotice(c);
        // One exec for both directories; the recursive chown below covers the extension too.
        c.sh("mkdir -p /home/agentuser/.gemini/policies\n" + extensionScript())
                .assertSuccess("Failed to write the isx Gemini extension");
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
