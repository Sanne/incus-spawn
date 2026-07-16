package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

public class BobSetup implements ToolSetup {

    private static final String PLACEHOLDER_API_KEY = "bob-placeholder";
    private static final String VERSION_URL =
            "https://s3.us-south.cloud-object-storage.appdomain.cloud/bob-shell/bobshell-version.txt";
    private static final String TARBALL_URL_PREFIX =
            "https://s3.us-south.cloud-object-storage.appdomain.cloud/bob-shell/bobshell-";

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
    public List<String> packages() {
        return List.of("nodejs");
    }

    @Override
    public List<ToolDef.ActionEntry> actions() {
        var a = new ToolDef.ActionEntry();
        a.setLabel("Bob Shell");
        a.setType("shell");
        a.setCommand("bob --yolo");
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
        configureSettings(c);
    }

    private void installBinary(Container c) {
        System.out.println("Installing Bob Shell...");

        try {
            var version = Files.readString(
                    downloadCache.download(VERSION_URL, null)).strip();
            System.out.println("  Latest version: " + version);

            var tarballUrl = TARBALL_URL_PREFIX + version + ".tgz";
            var cached = downloadCache.download(tarballUrl, null);

            var containerTarball = "/tmp/bobshell-" + version + ".tgz";
            c.filePush(cached.toString(), containerTarball);
            c.sh("npm install -g " + containerTarball)
                    .assertSuccess("Failed to npm install Bob Shell");
            c.sh("rm -f " + containerTarball);
        } catch (IOException e) {
            throw new RuntimeException("Failed to install Bob Shell: " + e.getMessage(), e);
        }
    }

    private void configureSettings(Container c) {
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

        var systemSettings = """
                {"general":{"disableAutoUpdate":true,"disableUpdateNag":true}}""";
        c.sh("mkdir -p /etc/bobshell");
        c.writeFile("/etc/bobshell/settings.json", systemSettings);
    }
}
