package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class BobSetup implements ToolSetup {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PLACEHOLDER_API_KEY = "bob-placeholder";
    private static final String VERSION_URL =
            "https://s3.us-south.cloud-object-storage.appdomain.cloud/bob-shell/bobshell2-version.txt";
    private static final String BASE_URL =
            "https://s3.us-south.cloud-object-storage.appdomain.cloud/bob-shell/bobshell-";
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    private final DownloadCache downloadCache;

    public BobSetup() {
        this(new DownloadCache());
    }

    BobSetup(DownloadCache downloadCache) {
        this.downloadCache = downloadCache;
    }

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
        a.setCommand("d=$(sqlite3 -noheader ~/.bob/db/bob.db \"SELECT REPLACE(project_id,'file:','') FROM tasks WHERE parent_id IS NULL ORDER BY updated_at DESC LIMIT 1\" 2>/dev/null); if [ -n \"$d\" ] && [ -d \"$d\" ]; then cd \"$d\"; bob --resume --auto-approve || bob --auto-approve; else bob --auto-approve; fi");
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
        BuildOutput.stepStart("Installing Bob Shell...");

        try {
            var version = Files.readString(
                    downloadCache.download(VERSION_URL, null)).strip();
            if (!VERSION_PATTERN.matcher(version).matches()) {
                throw new IOException("Unexpected version format: " + version);
            }

            var tarballUrl = BASE_URL + version + ".tgz";
            var sha256 = Files.readString(
                    downloadCache.download(tarballUrl + ".sha256", null)).strip();
            var cached = downloadCache.download(tarballUrl, sha256);

            var containerTarball = "/tmp/bobshell-" + version + ".tgz";
            c.filePush(cached.toString(), containerTarball);
            c.exec("npm", "install", "-g", containerTarball)
                    .assertSuccess("Failed to npm install Bob Shell");
            c.exec("rm", "-f", containerTarball);
            BuildOutput.stepDone();
            BuildOutput.note("Bob Shell " + version);
        } catch (IOException e) {
            throw new RuntimeException("Failed to install Bob Shell: " + e.getMessage(), e);
        }
    }

    void configureSettings(Container c, Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Configuring Bob Shell...");
        var bobConfig = SpawnConfig.load().getBob();

        var userSettings = new StringBuilder();
        userSettings.append("{\"security\":{\"auth\":{\"selectedType\":\"api-key\"}}");
        if (bobConfig.isLicenseConsent()) {
            userSettings.append(",\"ibm\":{\"licenseConsent\":true}");
        }
        userSettings.append("}");

        c.sh("mkdir -p /home/agentuser/.bob");
        c.writeFile("/home/agentuser/.bob/settings.json", userSettings.toString());
        c.writeFile("/home/agentuser/.bob/trustedFolders.json",
                "{\"/home/agentuser\":\"TRUST_FOLDER\"}");
        c.chown("/home/agentuser/.bob", "agentuser:agentuser");

        c.sh("mkdir -p /etc/bobshell");
        c.writeFile("/etc/bobshell/settings.json", buildSystemSettings(resolvedParams));
        BuildOutput.stepDone();
    }

    static String buildSystemSettings(Map<String, String> params) {
        var root = JSON.createObjectNode();
        var general = root.putObject("general");
        general.put("disableAutoUpdate", true);
        general.put("disableUpdateNag", true);
        var checkpointing = params.get("checkpointing");
        if (checkpointing != null) {
            general.putObject("checkpointing").put("enabled", Boolean.parseBoolean(checkpointing));
        }
        var model = (ObjectNode) null;
        var maxTurns = params.get("max-session-turns");
        if (maxTurns != null) {
            model = root.putObject("model");
            model.put("maxSessionTurns", Integer.parseInt(maxTurns));
        }
        var compression = params.get("compression-threshold");
        if (compression != null) {
            if (model == null) model = root.putObject("model");
            model.putObject("chatCompression")
                    .put("contextPercentageThreshold", Double.parseDouble(compression));
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize Bob system settings", e);
        }
    }
}
