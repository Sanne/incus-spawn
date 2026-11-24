package dev.incusspawn.tool;

import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.AccountShape;
import dev.incusspawn.config.ModelIds;
import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.proxy.GoogleAccounts;
import dev.incusspawn.proxy.GoogleSessionStore;
import dev.incusspawn.util.BuildOutput;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gemini CLI uses a guest placeholder; the proxy holds and injects its API key.
 *
 * <p>The {@code gemini} namespace also holds accounts of {@code type: google} (#2443): a Google
 * sign-in Antigravity CLI makes through the proxy, kept on the host ({@link GoogleSessionStore}),
 * with no key in {@code config.yaml}. Gemini CLI itself runs on API keys only; such an account
 * serves {@code agy}, and this tool declares the shape since it owns the namespace.
 */
public class GeminiSetup implements ToolSetup {

    static final String PLACEHOLDER_API_KEY = "gemini-placeholder";
    public static final String NAMESPACE = GoogleAccounts.NAMESPACE;
    static final String API_KEY = "apiKey";
    static final String TYPE = "type";
    static final String TYPE_API_KEY = "api-key";

    /**
     * An account is an API key ({@code apiKey}, {@code type} absent or {@code api-key}) or a Google
     * sign-in ({@code type: google}, no key here: the session file says whether it has signed in).
     * The flat, pre-accounts layout holds a key alone.
     */
    static final AccountShape ACCOUNT_SHAPE = new AccountShape() {
        private final AccountShape keyed = AccountShape.declared(List.of(API_KEY), List.of(TYPE, API_KEY));

        @Override
        public boolean hasFlatCredential(JqValue namespace) { return keyed.hasFlatCredential(namespace); }

        @Override
        public JqValue flatAccount(JqValue namespace) { return keyed.flatAccount(namespace); }

        @Override
        public List<String> flatKeys() { return keyed.flatKeys(); }

        @Override
        public boolean hasCredential(Map<String, String> values) {
            return GoogleAccounts.TYPE.equals(values.get(TYPE)) || keyed.hasCredential(values);
        }

        @Override
        public String problem(JqValue account, JqValue namespace) {
            var type = account == null ? "" : account.pathText(TYPE, "").strip();
            if (GoogleAccounts.TYPE.equals(type)) return "";
            if (!type.isEmpty() && !TYPE_API_KEY.equals(type)) {
                return "its type '" + type + "' is not '" + TYPE_API_KEY + "' or '" + GoogleAccounts.TYPE + "'";
            }
            var key = account == null ? "" : account.pathText(API_KEY, "").strip();
            if (key.isEmpty() && namespace != null) key = namespace.pathText(API_KEY, "").strip();
            return key.isEmpty() ? "it holds no credential" : "";
        }

        @Override
        public boolean credentialOutsideConfig(JqValue account) { return GoogleAccounts.isGoogle(account); }
    };

    /** How a {@code type: google} account signs in: in an instance, through the proxy. */
    static final HostedSignIn GOOGLE_SIGN_IN = new HostedSignIn(GoogleAccounts.TYPE,
            "Google account: an AI Pro or Ultra plan, for Antigravity CLI",
            List.of("No credential is entered here. The first time you start agy in an instance on this",
                    "account (from isx shell), it prints a Google sign-in link in that terminal: open it on",
                    "this machine, sign in, and paste the code it gives back into agy. The proxy keeps the",
                    "session on this host, and the instance holds only a placeholder. Gemini CLI, Pi and",
                    "opencode still need an API key account."));
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
    public boolean codingAgent() { return true; }

    @Override
    public String name() { return "gemini-cli"; }

    @Override
    public String description() { return "Gemini CLI — Google coding assistant"; }

    @Override
    public ToolDef.ProxyDef proxy() {
        var apiKey = new ToolDef.ConfigEntry();
        apiKey.setConfigPath(API_KEY);
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
        proxy.setConfigNamespace(NAMESPACE);
        proxy.setConfiguration(Map.of("api-key", apiKey));
        proxy.setAuth(List.of(auth));
        proxy.setPlaceholders(List.of(new ToolDef.PlaceholderDef("GEMINI_API_KEY", "gemini-")));
        return proxy;
    }

    @Override
    public AccountShape accountShape() { return ACCOUNT_SHAPE; }

    /**
     * What an image bakes from its {@code gemini} account: Antigravity CLI's mode ({@link
     * AntigravitySetup#accountMode}). Gemini CLI itself reads its key at launch and bakes
     * nothing, but it owns the namespace, and account selection asks the owner; agy, which
     * borrows the credential, would never be asked. So the build stamps the mode, and a move
     * across modes re-derives it in place rather than leaving agy configured for the old one.
     */
    @Override
    public String bakedAccountIdentity(SpawnConfig config, String accountName) {
        return AntigravitySetup.accountMode(config, accountName);
    }

    @Override
    public boolean canRebakeForAccount() { return true; }

    /**
     * Antigravity CLI's mode, where the instance has agy; nothing in an image without it. An agy
     * already running keeps the mode it started in, and the Google mode's login script runs only
     * in a new login shell, so the person is told to start it again from one.
     */
    @Override
    public String rebakeForAccount(dev.incusspawn.incus.Container container, String accountName) {
        var google = GoogleAccounts.isGoogleAccount(SpawnConfig.load().tree(), accountName);
        if (!AntigravitySetup.rebakeIfInstalled(container, google)) return null;
        return "Antigravity CLI is now set up for " + (google ? "a Google account" : "an API key")
                + ", but an agy already running in " + container.name() + " keeps its old mode:"
                + " quit it and start it again from a new isx shell " + container.name() + ".";
    }

    @Override
    public HostedSignIn hostedSignIn() { return GOOGLE_SIGN_IN; }

    /**
     * An API key request from an instance on a Google account: Gemini CLI, Pi and opencode need
     * a key account, and agy sends one only while its instance is still set up for a key.
     */
    @Override
    public String hostedSignInRefusal(String instance, String account) {
        return "isx sent nothing to Google: instance '" + instance + "' uses gemini account '" + account
                + "', a Google account, which holds no API key for this request."
                + " Gemini CLI, Pi and opencode need an API key account: isx account set " + instance
                + " gemini=<an API key account>."
                + " If Antigravity CLI sent it, agy is still set up for an API key:"
                + " restart agy from a new isx shell " + instance + ", and rebuild the template if this repeats.";
    }

    /** A Google account: whether it has signed in, and as whom; "" for an API key account. */
    @Override
    public String describeAccount(SpawnConfig config, String accountName) {
        if (!GoogleAccounts.isGoogleAccount(config.tree(), accountName)) return "";
        return describeGoogleAccount(new GoogleSessionStore(), accountName);
    }

    public static String describeGoogleAccount(GoogleSessionStore store, String accountName) {
        GoogleSessionStore.Tokens tokens;
        try {
            var session = store.read(accountName);
            tokens = session == null ? null : session.tokens();
        } catch (RuntimeException e) {
            return "Google account, session file unreadable";
        }
        if (tokens == null) return "Google account, not signed in yet: run agy in an instance";
        return tokens.email().isEmpty() ? "Google account, signed in" : "Google account, signed in as " + tokens.email();
    }

    /**
     * Whether the {@code gemini} account {@code selection} resolves to is a Google sign-in, for
     * what a build derives from it. A selection that names no usable account is no Google one.
     */
    public static boolean selectsGoogleAccount(SpawnConfig config, Map<String, String> selection) {
        try {
            var account = AccountResolver.effectiveAccount(config, NAMESPACE, selection.get(NAMESPACE));
            return GoogleAccounts.isGoogleAccount(config.tree(), account);
        } catch (AccountResolver.UnknownAccountException e) {
            return false;
        }
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
        model.setDescription("Gemini model ID (e.g. " + ModelIds.get("example-gemini") + "); omitted to use the CLI default");
        model.setPattern("^[a-zA-Z0-9][-a-zA-Z0-9._@:]*$");
        model.setOptional(true);
        model.setReconfigurable(true);

        var effort = new ToolDef.ParameterDef();
        effort.setType("string");
        effort.setDescription("Thinking level for the model (minimal, low, medium, high, xhigh); which exist depends on the"
                + " model, and the Gemini API refuses one the model lacks. Needs model");
        effort.setPattern("^(minimal|low|medium|high|xhigh)$");
        effort.setOptional(true);
        effort.setReconfigurable(true);

        var params = new LinkedHashMap<String, ToolDef.ParameterDef>();
        params.put("model", model);
        params.put("effort", effort);
        return params;
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
    public McpClient mcpClient() { return McpClients.GEMINI; }

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
        return "{\n" + modelSetting + thinkingLevel(model, resolvedParams.get("effort")) + """
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

    /**
     * The effort as the selected model's thinking level: an override matched on the model, added
     * to Gemini CLI's own, so it reaches requests for that model alone. Its helper requests run
     * other models with a thinking budget, which a level beside it would contradict.
     */
    private static String thinkingLevel(String model, String effort) {
        if (effort == null) return "";
        if (model == null) {
            throw new IllegalStateException("The gemini-cli tool's effort parameter needs a model, because isx sets"
                    + " it for one model: set model too");
        }
        return "  \"modelConfigs\": {\"customOverrides\": [{\"match\": {\"model\": \"" + model + "\"},"
                + " \"modelConfig\": {\"generateContentConfig\": {\"thinkingConfig\": {\"thinkingLevel\": \""
                + effort.toUpperCase(Locale.ROOT) + "\"}}}}]},\n";
    }
}
