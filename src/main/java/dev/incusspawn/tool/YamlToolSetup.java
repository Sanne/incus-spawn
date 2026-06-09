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

    public ToolDef toolDef() { return def; }

    @Override
    public String name() {
        return def.getName();
    }

    @Override
    public java.util.List<String> packages() {
        return def.getPackages();
    }

    @Override
    public java.util.List<dev.incusspawn.config.ImageDef.PackageRepo> packageRepos() {
        return def.getPackageRepos();
    }

    @Override
    public java.util.List<String> requires() {
        return def.getRequires().stream()
            .map(ToolDef.ToolRef::getName)
            .toList();
    }

    @Override
    public java.util.Map<String, ToolDef.ParameterDef> parameters() {
        return def.getParameters();
    }

    @Override
    public void install(Container container, java.util.Map<String, String> resolvedParams) {
        var label = def.getDescription().isEmpty() ? def.getName() : def.getDescription();
        System.out.println("Installing " + label + "...");

        // Packages are installed in bulk by BuildCommand before tool.install() is called.

        // 1. Downloads — fetch on host, extract on host, push into container
        var containerArch = canonicalArch(container.getArchitecture());
        for (var dl : def.getDownloads()) {
            if (dl.getArch() != null && !dl.getArch().equals(containerArch)) {
                continue;
            }
            processDownload(dl, container);
        }

        // 2. Shell commands as root (with parameter substitution)
        for (var script : def.getRun()) {
            var substituted = ParameterSubstitutor.substitute(script, resolvedParams);
            container.runInteractive("Failed to run setup for " + def.getName(),
                    "sh", "-c", substituted);
        }

        // 3. Shell commands as agentuser (with parameter substitution)
        for (var script : def.getRunAsUser()) {
            var substituted = ParameterSubstitutor.substitute(script, resolvedParams);
            container.runAsUser("agentuser", substituted,
                    "Failed to run user setup for " + def.getName());
        }

        // 4. Files (with parameter substitution in path and content)
        for (var file : def.getFiles()) {
            var path = ParameterSubstitutor.substitute(file.getPath(), resolvedParams);
            var content = ParameterSubstitutor.substitute(file.getContent(), resolvedParams);
            container.writeFile(path, content);
            if (file.getOwner() != null && !file.getOwner().isEmpty()) {
                chownWithParents(container, path, file.getOwner());
            }
        }

        // 5. Environment variables (with parameter substitution)
        for (var line : def.getEnv()) {
            var substituted = ParameterSubstitutor.substitute(line, resolvedParams);
            container.appendToProfile(substituted);
        }

        // 6. Verification (with parameter substitution)
        if (def.getVerify() != null && !def.getVerify().isBlank()) {
            var substituted = ParameterSubstitutor.substitute(def.getVerify(), resolvedParams);
            var result = container.exec(substituted.split("\\s+"));
            if (result.success()) {
                System.out.println("  " + result.stdout().lines().findFirst().orElse(""));
            } else {
                System.err.println("  Warning: verification failed for " + def.getName());
            }
        }
    }

    static String canonicalArch(String arch) {
        return switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
    }

    private void processDownload(ToolDef.DownloadEntry dl, Container container) {
        try {
            var cached = downloadCache.download(dl.getUrl(), dl.getSha256());

            if (dl.isExtractInContainer()) {
                extractInContainer(dl, cached, container);
            } else {
                extractOnHostAndPush(dl, cached, container);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to process download for " + def.getName()
                    + ": " + e.getMessage(), e);
        }
    }

    private void extractInContainer(ToolDef.DownloadEntry dl, Path cached, Container container) {
        var filename = cached.getFileName().toString();
        var containerArchive = "/tmp/" + filename;

        container.exec("mkdir", "-p", dl.getExtract());
        container.filePush(cached.toString(), containerArchive);
        container.runInteractive("Failed to extract " + filename + " in container",
                "tar", "xf", containerArchive, "-C", dl.getExtract());
        container.exec("rm", "-f", containerArchive);

        for (var linkEntry : dl.getLinks().entrySet()) {
            container.exec("ln", "-sf", linkEntry.getKey(), linkEntry.getValue());
        }
    }

    private void extractOnHostAndPush(ToolDef.DownloadEntry dl, Path cached, Container container)
            throws IOException {
        var extractDir = Files.createTempDirectory("isx-extract-");
        try {
            extractOnHost(cached, extractDir);

            container.exec("mkdir", "-p", dl.getExtract());
            try (var entries = Files.list(extractDir)) {
                for (var entry : entries.toList()) {
                    container.filePushRecursive(entry.toString(), dl.getExtract());
                }
            }

            for (var linkEntry : dl.getLinks().entrySet()) {
                container.exec("ln", "-sf", linkEntry.getKey(), linkEntry.getValue());
            }
        } finally {
            deleteRecursive(extractDir);
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

    /**
     * Chown file and any root-owned parent directories inside /home that
     * writeFile's mkdir -p created. Without this, a tool writing
     * /home/user/.config/foo.conf leaves .config owned by root, blocking
     * later run_as_user steps from creating siblings.
     */
    private static void chownWithParents(Container container, String path, String owner) {
        container.chown(path, owner);
        var ownerUser = owner.split(":")[0];
        var home = "/home/" + ownerUser;
        container.sh(
                "d=$(dirname " + Container.shellQuote(path) + "); " +
                "while [ \"$d\" != " + Container.shellQuote(home) + " ] && " +
                      "[ \"$d\" != / ] && " +
                      "case $d in " + Container.shellQuote(home) + "/*) true;; *) false;; esac; do " +
                "  [ \"$(stat -c %U \"$d\")\" = root ] && chown " + Container.shellQuote(owner) + " \"$d\"; " +
                "  d=$(dirname \"$d\"); " +
                "done");
    }

    private static void deleteRecursive(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
