package dev.incusspawn.tool;

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
        a.setCommand("bob");
        a.setAutoReturn(true);
        return List.of(a);
    }

    @Override
    public void install(Container c, Map<String, String> resolvedParams) {
        installBinary(c);
        configureAuth(c);
    }

    private void installBinary(Container c) {
        System.out.println("Installing Bob Shell...");
        c.sh("mkdir -p /home/agentuser/.local/bin && " +
                "chown -R agentuser:agentuser /home/agentuser/.local && " +
                "grep -q '.local/bin' /home/agentuser/.bashrc 2>/dev/null || " +
                "echo 'export PATH=\"$HOME/.local/bin:$PATH\"' >> /home/agentuser/.bashrc");

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

    private void configureAuth(Container c) {
        c.appendToProfile("export BOBSHELL_API_KEY=" + PLACEHOLDER_API_KEY);

        c.sh("mkdir -p /home/agentuser/.bob && " +
                "printf '%s' '{\"ibm\":{\"licenseConsent\":true},\"security\":{\"auth\":{\"selectedType\":\"api-key\"}}}' " +
                "> /home/agentuser/.bob/settings.json && " +
                "chown -R agentuser:agentuser /home/agentuser/.bob");
    }
}
