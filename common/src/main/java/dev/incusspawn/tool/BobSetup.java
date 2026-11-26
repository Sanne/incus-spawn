package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;

import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class BobSetup implements ToolSetup {

    private static final String PLACEHOLDER_API_KEY = "bob-placeholder";
    private static final String UNLIMITED_TURNS_HINT = "Use 0 for unlimited turns.";
    private static final String NPM_PACKAGE = "@ibm/bob";
    private static final Pattern COMPRESSION_THRESHOLD = Pattern.compile("^(0?\\.\\d+|1\\.0)$");

    @Override
    public boolean codingAgent() { return true; }

    @Override
    public String name() {
        return "bob";
    }

    @Override
    public String description() {
        return "Bob Shell — IBM AI coding assistant";
    }

    @Override
    public ToolDef.ProxyDef proxy() {
        var apiKey = new ToolDef.ConfigEntry();
        apiKey.setConfigPath("apiKey");
        apiKey.setDescription("IBM Bob API key");
        apiKey.setSecret(true);
        // Bob's API is not documented: no request is known to answer for the key alone
        apiKey.setCheck(SecretCheck.none("IBM documents no request that checks an API key"));
        apiKey.setHelp(List.of(
                "To create an API key:",
                "  1. Go to https://bob.ibm.com/admin/apikeys",
                "  2. Click 'Create API key'",
                "  3. Set the scope to Inference",
                "  4. Copy the generated key"));

        var license = new ToolDef.ConfigEntry();
        license.setConfigPath("licenseConsent");
        license.setType("confirm");
        license.setDescription("Accept IBM license terms");
        license.setHelp(List.of(
                "Bob Shell asks you to accept IBM's license at first start.",
                "Accept it here, and Bob Shell does not ask you again."));

        var auth = new ToolDef.AuthDef();
        auth.setDomains(List.of("bob.ibm.com", "*.bob.ibm.com",
                "us-east.bob.ibm.com", "eu-de.bob.ibm.com", "jp-tok.bob.ibm.com"));
        auth.setType("header");
        auth.setName("Authorization");
        auth.setValue("Apikey ${api-key}");

        var proxy = new ToolDef.ProxyDef();
        proxy.setConfigNamespace("bob");
        var config = new LinkedHashMap<String, ToolDef.ConfigEntry>();
        config.put("api-key", apiKey);
        config.put("license", license);
        proxy.setConfiguration(config);
        proxy.setAuth(List.of(auth));
        proxy.setPlaceholders(List.of(new ToolDef.PlaceholderDef("BOBSHELL_API_KEY", "bob-")));
        return proxy;
    }

    @Override
    public Map<String, ToolDef.ParameterDef> parameters() {
        var params = new LinkedHashMap<String, ToolDef.ParameterDef>();

        var maxTurns = new ToolDef.ParameterDef();
        maxTurns.setType("integer");
        maxTurns.setMin(0);
        maxTurns.setDescription("Maximum session turns (0 for unlimited)");
        maxTurns.setOptional(true);
        maxTurns.setReconfigurable(true);
        params.put("max-session-turns", maxTurns);

        var compression = new ToolDef.ParameterDef();
        compression.setType("string");
        compression.setDescription("Context compression threshold (0.0–1.0)");
        compression.setPattern(COMPRESSION_THRESHOLD.pattern());
        compression.setOptional(true);
        compression.setReconfigurable(true);
        params.put("compression-threshold", compression);

        return params;
    }

    @Override
    public String parameterErrorHint(String parameter) {
        return switch (parameter) {
            case "max-session-turns" -> UNLIMITED_TURNS_HINT;
            case "checkpointing" -> "Bob Shell 2.1 does not support checkpointing. Remove it from the bob tool parameters.";
            default -> "";
        };
    }

    @Override
    public List<String> packages() {
        return List.of("nodejs", "sqlite");
    }

    @Override
    public List<ToolDef.ActionEntry> actions() {
        var a = new ToolDef.ActionEntry();
        a.setLabel("Bob Shell");
        a.setType("shell");
        a.setCommand("d=$(sqlite3 -noheader ~/.bob/db/bob.db \"SELECT REPLACE(project_id,'file:','') FROM tasks WHERE parent_id IS NULL ORDER BY updated_at DESC LIMIT 1\" 2>/dev/null); if [ -n \"$d\" ] && [ -d \"$d\" ]; then cd \"$d\"; bob chat --resume --auto-approve || bob chat --auto-approve; else bob chat --auto-approve; fi");
        a.setAutoReturn(true);
        return List.of(a);
    }

    @Override
    public List<EnvEntry> envEntries(Map<String, String> resolvedParams) {
        return List.of(EnvEntry.set("BOBSHELL_API_KEY", PLACEHOLDER_API_KEY));
    }

    static final String SETTINGS_PATH = "/home/agentuser/.bob/settings/settings.json";

    @Override
    public McpClient mcpClient() { return McpClients.BOB; }

    @Override
    public void install(Container c, Map<String, String> resolvedParams) {
        var settings = buildUserSettings(resolvedParams, SpawnConfig.load().getBob());
        installBinary(c);
        writeSettings(c, settings);
    }

    @Override
    public void reconfigure(Container c, Map<String, String> resolvedParams) {
        configureSettings(c, resolvedParams);
    }

    private void installBinary(Container c) {
        BuildOutput.stepStart("Installing Bob Shell");
        NpmGlobalInstall.install(c, "Bob Shell", NPM_PACKAGE);
        BuildOutput.stepDone();
    }

    void configureSettings(Container c, Map<String, String> resolvedParams) {
        writeSettings(c, buildUserSettings(resolvedParams, SpawnConfig.load().getBob()));
    }

    private void writeSettings(Container c, String settings) {
        BuildOutput.stepStart("Configuring Bob Shell");

        c.sh("mkdir -p /home/agentuser/.bob/settings");
        c.writeFile(SETTINGS_PATH, settings);
        c.writeFile("/home/agentuser/.bob/trustedFolders.json",
                "{\"version\":1,\"folders\":{\"/home/agentuser\":\"TRUST_FOLDER\"}}");
        c.chown("/home/agentuser/.bob", "agentuser:agentuser");
        BuildOutput.stepDone();
    }

    static String buildUserSettings(Map<String, String> params,
                                    SpawnConfig.BobConfig bobConfig) {
        var security = JqObject.builder(2)
                .put("auth", JqObject.builder(1)
                        .put("selectedType", "api-key").build())
                .put("folderTrust", JqObject.builder(1)
                        .put("enabled", false).build());

        var bobShell = JqObject.builder(1)
                .put("autoUpdate", false);

        var root = JqObject.builder(5)
                .put("security", security.build())
                .put("bobShell", bobShell.build());

        if (bobConfig.isLicenseConsent()) {
            root.put("licenseConsent", true);
        }

        var session = JqObject.builder(2);
        var maxTurns = params.get("max-session-turns");
        if (maxTurns != null) {
            int turns = Integer.parseInt(maxTurns);
            if (turns < 0) {
                throw new IllegalArgumentException(
                        "Bob max-session-turns must be nonnegative. " + UNLIMITED_TURNS_HINT);
            }
            session.put("maxTurns", turns);
        }
        var compression = params.get("compression-threshold");
        if (compression != null) {
            var percent = new BigDecimal(compression).movePointRight(2);
            session.put("compactionThresholdPercent", JqNumber.of(percent));
        }
        if (maxTurns != null || compression != null) {
            root.put("session", session.build());
        }

        return io.hyperfoil.tools.jjq.value.JqValues.toPrettyJsonString(root.build()) + "\n";
    }
}
