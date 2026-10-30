package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;

import io.hyperfoil.tools.jjq.value.JqObject;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class BobSetup implements ToolSetup {

    private static final String PLACEHOLDER_API_KEY = "bob-placeholder";
    private static final String NPM_PACKAGE = "@ibm/bob";

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
                "IBM Bob Shell requires acceptance of the IBM license agreement.",
                "The license is presented on first launch of Bob Shell.",
                "Pre-accepting here skips that prompt inside containers."));

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
        maxTurns.setDescription("Maximum session turns (-1 for unlimited)");
        maxTurns.setOptional(true);
        maxTurns.setReconfigurable(true);
        params.put("max-session-turns", maxTurns);

        var compression = new ToolDef.ParameterDef();
        compression.setType("string");
        compression.setDescription("Context compression threshold (0.0–1.0)");
        compression.setPattern("^(0?\\.\\d+|1\\.0)$");
        compression.setOptional(true);
        compression.setReconfigurable(true);
        params.put("compression-threshold", compression);

        var checkpointing = new ToolDef.ParameterDef();
        checkpointing.setType("boolean");
        checkpointing.setDescription("Enable safety checkpoints");
        checkpointing.setOptional(true);
        checkpointing.setReconfigurable(true);
        params.put("checkpointing", checkpointing);

        return params;
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

    @Override
    public void install(Container c, Map<String, String> resolvedParams) {
        installBinary(c);
        configureSettings(c, resolvedParams);
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
        BuildOutput.stepStart("Configuring Bob Shell");
        var bobConfig = SpawnConfig.load().getBob();

        c.sh("mkdir -p /home/agentuser/.bob/settings");
        c.writeFile("/home/agentuser/.bob/settings/settings.json",
                buildUserSettings(resolvedParams, bobConfig));
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

        var checkpointing = params.get("checkpointing");
        if (checkpointing != null) {
            root.put("general", JqObject.builder(1)
                    .put("checkpointing", JqObject.builder(1)
                            .put("enabled", Boolean.parseBoolean(checkpointing)).build())
                    .build());
        }

        var model = JqObject.builder(2);
        boolean hasModel = false;
        var maxTurns = params.get("max-session-turns");
        if (maxTurns != null) {
            model.put("maxSessionTurns", Long.parseLong(maxTurns));
            hasModel = true;
        }
        var compression = params.get("compression-threshold");
        if (compression != null) {
            model.put("chatCompression", JqObject.builder(1)
                    .put("contextPercentageThreshold", Double.parseDouble(compression)).build());
            hasModel = true;
        }
        if (hasModel) root.put("model", model.build());

        return io.hyperfoil.tools.jjq.value.JqValues.toPrettyJsonString(root.build()) + "\n";
    }
}
