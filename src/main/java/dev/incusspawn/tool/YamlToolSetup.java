package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Adapts a {@link ToolDef} (parsed from YAML) into a {@link ToolSetup}
 * that can be executed by the build system.
 */
public class YamlToolSetup implements ToolSetup {

    private final ToolDef def;
    private final DownloadCache downloadCache;

    public YamlToolSetup(ToolDef def) {
        this(def, new DownloadCache());
    }

    YamlToolSetup(ToolDef def, DownloadCache downloadCache) {
        this.def = def;
        this.downloadCache = downloadCache;
    }

    @Override
    public String name() {
        return def.getName();
    }

    @Override
    public java.util.List<String> packages() {
        return def.getPackages();
    }

    @Override
    public void install(Container container) {
        var label = def.getDescription().isEmpty() ? def.getName() : def.getDescription();
        System.out.println("Installing " + label + "...");

        // Packages are installed in bulk by BuildCommand before tool.install() is called.

        // 1. Downloads — fetch on host, extract on host, push into container
        for (var dl : def.getDownloads()) {
            processDownload(dl, container);
        }

        // 2. Shell commands as root
        for (var script : def.getRun()) {
            container.runInteractive("Failed to run setup for " + def.getName(),
                    "sh", "-c", script);
        }

        // 3. Shell commands as agentuser
        for (var script : def.getRunAsUser()) {
            container.runAsUser("agentuser", script,
                    "Failed to run user setup for " + def.getName());
        }

        // 4. Files
        for (var file : def.getFiles()) {
            container.writeFile(file.getPath(), file.getContent());
            if (file.getOwner() != null && !file.getOwner().isEmpty()) {
                container.chown(file.getPath(), file.getOwner());
            }
        }

        // 5. Environment variables
        for (var line : def.getEnv()) {
            container.appendToProfile(line);
        }

        // 6. Verification
        if (def.getVerify() != null && !def.getVerify().isBlank()) {
            var result = container.exec(def.getVerify().split("\\s+"));
            if (result.success()) {
                System.out.println("  " + result.stdout().lines().findFirst().orElse(""));
            } else {
                System.err.println("  Warning: verification failed for " + def.getName());
            }
        }
    }

    private void processDownload(ToolDef.DownloadEntry dl, Container container) {
        try {
            // Download to host cache
            var cached = downloadCache.download(dl.getUrl(), dl.getSha256());

            // Extract on host into temp directory
            var extractDir = Files.createTempDirectory("isx-extract-");
            try {
                extractOnHost(cached, extractDir);

                // Push extracted content into container
                container.exec("mkdir", "-p", dl.getExtract());
                try (var entries = Files.list(extractDir)) {
                    for (var entry : entries.toList()) {
                        container.filePushRecursive(entry.toString(), dl.getExtract());
                    }
                }

                // Create symlinks
                for (var linkEntry : dl.getLinks().entrySet()) {
                    container.exec("ln", "-sf", linkEntry.getKey(), linkEntry.getValue());
                }
            } finally {
                deleteRecursive(extractDir);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to process download for " + def.getName()
                    + ": " + e.getMessage(), e);
        }
    }

    private static void extractOnHost(Path archive, Path destDir) throws IOException {
        var name = archive.getFileName().toString().toLowerCase();
        int exitCode;
        try {
            if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
                exitCode = new ProcessBuilder("tar", "xzf", archive.toString(), "-C", destDir.toString())
                        .inheritIO().start().waitFor();
            } else if (name.endsWith(".tar.bz2")) {
                exitCode = new ProcessBuilder("tar", "xjf", archive.toString(), "-C", destDir.toString())
                        .inheritIO().start().waitFor();
            } else if (name.endsWith(".tar.xz")) {
                exitCode = new ProcessBuilder("tar", "xJf", archive.toString(), "-C", destDir.toString())
                        .inheritIO().start().waitFor();
            } else if (name.endsWith(".zip")) {
                exitCode = new ProcessBuilder("unzip", "-q", archive.toString(), "-d", destDir.toString())
                        .inheritIO().start().waitFor();
            } else {
                throw new IOException("Unsupported archive format: " + name);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Extraction interrupted", e);
        }
        if (exitCode != 0) {
            throw new IOException("Extraction failed (exit code " + exitCode + ") for " + archive);
        }
    }

    private static void deleteRecursive(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
