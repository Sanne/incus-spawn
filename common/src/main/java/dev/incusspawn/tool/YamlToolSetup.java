package dev.incusspawn.tool;

import dev.incusspawn.FileTrees;
import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.util.BuildOutput;

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
    public String description() {
        return def.getDescription();
    }

    @Override
    public String agentNote() {
        return def.getAgentNote();
    }

    @Override
    public dev.incusspawn.config.ImageDef.SkillsDef skills() {
        return def.getSkills();
    }

    @Override
    public ToolDef.ProxyDef proxy() {
        return def.getProxy();
    }

    @Override
    public String feature() {
        return def.getFeature();
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
    public java.util.List<EnvEntry> envEntries(java.util.Map<String, String> resolvedParams) {
        return def.getEnv().stream()
                .map(e -> e.withSubstitution(s -> ParameterSubstitutor.substitute(s, resolvedParams)))
                .toList();
    }

    /**
     * Only the declared placeholders this tool sets through {@code env:}: a start replaces a
     * variable the build set, so any other name could only be a typo, which would silently keep
     * the static placeholder -- or another tool's variable ({@code PATH}, {@code LD_PRELOAD}),
     * which a proof would clobber in every login shell. {@link ToolDefValidator} reports either.
     */
    @Override
    public java.util.List<dev.incusspawn.proxy.ProofToken.Placeholder> placeholders() {
        var exported = new java.util.HashSet<>(placeholderCandidates(def));
        var own = new java.util.ArrayList<dev.incusspawn.proxy.ProofToken.Placeholder>();
        for (var placeholder : declaredPlaceholders()) {
            if (exported.contains(placeholder.env())) {
                own.add(placeholder);
            } else {
                dev.incusspawn.Warnings.warn("tool '" + name() + "' declares " + placeholder.env()
                        + " as a credential placeholder but does not export it; it gets no proof token");
            }
        }
        return own;
    }

    /**
     * The variables {@code def} sets outright -- a placeholder's value is all the variable holds,
     * never a part prepended or appended to one, as {@code PATH} is.
     */
    static java.util.List<String> placeholderCandidates(ToolDef def) {
        return def.getEnv().stream()
                .filter(e -> e.getStrategy() == EnvEntry.Strategy.SET || e.getStrategy() == EnvEntry.Strategy.SET_IF_UNSET)
                .map(EnvEntry::getName).toList();
    }

    /** Every placeholder the {@code proxy:} entry declares, exported or not. */
    java.util.List<dev.incusspawn.proxy.ProofToken.Placeholder> declaredPlaceholders() {
        return ToolSetup.super.placeholders();
    }

    @Override
    public void install(Container container, java.util.Map<String, String> resolvedParams) {
        var label = def.getDescription().isEmpty() ? def.getName() : def.getDescription();
        BuildOutput.stepStart("Installing " + label + "...");

        // Packages are installed in bulk by BuildCommand before tool.install() is called.

        // 1. Downloads — fetch on host, then copy and/or extract into the container
        var containerArch = canonicalArch(container.getArchitecture());
        for (var dl : def.getDownloads()) {
            if (dl.getArch() != null && !dl.getArch().equals(containerArch)) {
                continue;
            }
            BuildOutput.stepProgress("downloading " + fileName(dl.getUrl()));
            processDownload(dl, container);
        }

        // 2. Shell commands as root (with parameter substitution)
        for (var script : def.getRun()) {
            var substituted = ParameterSubstitutor.substitute(script, resolvedParams);
            BuildOutput.stepProgress(firstLine(substituted));
            container.runQuiet("Failed to run setup for " + def.getName(),
                    "sh", "-c", substituted);
        }

        // 3. Shell commands as agentuser (with parameter substitution)
        for (var script : def.getRunAsUser()) {
            var substituted = ParameterSubstitutor.substitute(script, resolvedParams);
            BuildOutput.stepProgress(firstLine(substituted));
            container.runAsUserQuiet("agentuser", substituted,
                    "Failed to run user setup for " + def.getName());
        }

        // 4. Files (with parameter substitution in path and content)
        for (var file : def.getFiles()) {
            var path = ParameterSubstitutor.substitute(file.getPath(), resolvedParams);
            var content = ParameterSubstitutor.substitute(file.getContent(), resolvedParams);
            BuildOutput.stepProgress("writing " + path);
            container.writeFile(path, content);
            if (file.getOwner() != null && !file.getOwner().isEmpty()) {
                chownWithParents(container, path, file.getOwner());
            }
        }

        // 5. Environment variables are collected centrally by BuildCommand
        // and written to /etc/profile.d/isx-env.sh — see envEntries().

        // 6. Verification runs later, from ToolVerifier: a verify often needs another tool's
        // environment (Maven needs the JDK's JAVA_HOME), which exists only once every tool is
        // installed and isx-env.sh is written.

        BuildOutput.stepDone();
    }

    @Override
    public boolean verifyAsRoot() {
        return def.isVerifyAsRoot();
    }

    @Override
    public String verifyCommand(java.util.Map<String, String> resolvedParams) {
        var verify = def.getVerify();
        if (verify == null || verify.isBlank()) return null;
        return ParameterSubstitutor.substitute(verify, resolvedParams);
    }

    private static String firstNonBlankLine(String text) {
        if (text == null) return "";
        return text.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
    }

    private static String firstLine(String script) {
        var line = firstNonBlankLine(script);
        return line.length() > 60 ? line.substring(0, 59) + "…" : line;
    }

    /** The last path segment of {@code url}, for the progress line. Plain string work, never parsing:
     *  a malformed URL must still reach {@code DownloadCache}, whose error names the tool. */
    static String fileName(String url) {
        if (url == null) return "";
        var end = url.length();
        for (var c : new char[] {'?', '#'}) {
            var i = url.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        var path = url.substring(0, end);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        var name = path.substring(path.lastIndexOf('/') + 1);
        return name.isEmpty() ? url : name;
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
            var hasDestinationFile = hasText(dl.getDestinationFile());
            var hasExtract = hasText(dl.getExtract());
            if (!hasDestinationFile && !hasExtract) {
                throw new IllegalArgumentException("Download entry for " + def.getName()
                        + " must set extract, destination_file, or both");
            }

            var cached = downloadCache.download(dl.getUrl(), dl.getSha256());
            var machineType = container.machineType();

            if (hasDestinationFile) {
                copyToDestination(dl, cached, container, machineType);
            }

            if (!hasExtract) {
                return;
            }

            if (machineType == MachineType.VM) {
                // VMs use the incus-agent for file I/O (over vsock), which
                // cannot handle pushing large files. Mount the host directory
                // as a disk device and copy locally inside the VM instead.
                extractOnHostAndMountCopy(dl, cached, container);
            } else if (dl.isExtractInContainer()) {
                extractInContainer(dl, cached, container);
            } else {
                extractOnHostAndPush(dl, cached, container);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to process download for " + def.getName()
                    + ": " + e.getMessage(), e);
        }
    }

    private void copyToDestination(ToolDef.DownloadEntry dl, Path cached, Container container,
                                   MachineType machineType) throws IOException {
        var destination = expandUserHome(dl.getDestinationFile());
        var parent = Path.of(destination).getParent();
        if (parent != null) {
            container.exec("mkdir", "-p", parent.toString());
        }

        if (machineType == MachineType.VM) {
            copyFileViaMount(cached, destination, container);
        } else {
            container.filePush(cached.toString(), destination);
        }
        container.exec("chmod", "a+r", destination);
    }

    private void copyFileViaMount(Path cached, String destination, Container container) throws IOException {
        var absoluteCached = cached.toAbsolutePath();
        var stagingDir = Files.createTempDirectory(absoluteCached.getParent(), "isx-mount-");
        var staged = stagingDir.resolve(absoluteCached.getFileName());
        var deviceName = "dl-file-" + def.getName();
        var mountPath = "/mnt/isx-download-file";
        try {
            Files.copy(absoluteCached, staged);
            ensureWorldReadable(stagingDir);
            container.exec("rm", "-rf", mountPath);
            container.addDiskDevice(deviceName, HostResourceSetup.translateForVm(stagingDir.toString()), mountPath, true);
            container.waitForPath(mountPath);
            container.runQuiet("Failed to copy download for " + def.getName(),
                    "cp", mountPath + "/" + staged.getFileName(), destination);
        } finally {
            try { container.removeDiskDevice(deviceName); } catch (Exception ignored) {}
            try { container.exec("rm", "-rf", mountPath); } catch (Exception ignored) {}
            FileTrees.deleteQuietly(stagingDir);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String expandUserHome(String path) {
        if ("~".equals(path)) {
            return "/home/agentuser";
        }
        if (path.startsWith("~/")) {
            return "/home/agentuser/" + path.substring(2);
        }
        return path;
    }

    private void extractInContainer(ToolDef.DownloadEntry dl, Path cached, Container container) {
        var filename = cached.getFileName().toString();
        var containerArchive = "/tmp/" + filename;

        container.exec("mkdir", "-p", dl.getExtract());
        container.filePush(cached.toString(), containerArchive);
        container.runQuiet("Failed to extract " + filename + " in container",
                "tar", "xf", containerArchive, "-C", dl.getExtract());
        container.exec("rm", "-f", containerArchive);
        container.exec("chmod", "-R", "a+rX", dl.getExtract());

        for (var linkEntry : dl.getLinks().entrySet()) {
            container.exec("ln", "-sf", linkEntry.getKey(), linkEntry.getValue());
        }
    }

    private void extractOnHostAndMountCopy(ToolDef.DownloadEntry dl, Path cached, Container container)
            throws IOException {
        var extractDir = Files.createTempDirectory("isx-extract-");
        var deviceName = "dl-" + def.getName();
        var mountPath = "/mnt/isx-download";
        try {
            extractOnHost(cached, extractDir);
            ensureWorldReadable(extractDir);

            // Remove stale mount point from a previous tool's download so
            // waitForPath detects the new virtio-fs mount, not the leftover dir
            container.exec("rm", "-rf", mountPath);
            container.addDiskDevice(deviceName, extractDir.toString(), mountPath, true);
            container.waitForPath(mountPath);
            container.exec("mkdir", "-p", dl.getExtract());
            container.runQuiet("Failed to copy download for " + def.getName(),
                    "cp", "-a", mountPath + "/.", dl.getExtract());
            container.exec("chmod", "-R", "a+rX", dl.getExtract());

            for (var linkEntry : dl.getLinks().entrySet()) {
                container.exec("ln", "-sf", linkEntry.getKey(), linkEntry.getValue());
            }
        } finally {
            try { container.removeDiskDevice(deviceName); } catch (Exception ignored) {}
            try { container.exec("rm", "-rf", mountPath); } catch (Exception ignored) {}
            FileTrees.deleteQuietly(extractDir);
        }
    }

    private void extractOnHostAndPush(ToolDef.DownloadEntry dl, Path cached, Container container)
            throws IOException {
        var extractDir = Files.createTempDirectory("isx-extract-");
        try {
            extractOnHost(cached, extractDir);
            ensureWorldReadable(extractDir);

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
            FileTrees.deleteQuietly(extractDir);
        }
    }

    private static void extractOnHost(Path archive, Path destDir) throws IOException {
        var name = archive.getFileName().toString().toLowerCase();
        var archivePath = archive.toString();
        var destPath = destDir.toString();
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            runProcess("Extraction of " + archive, "tar", "xzf", archivePath, "-C", destPath);
        } else if (name.endsWith(".tar.bz2")) {
            runProcess("Extraction of " + archive, "tar", "xjf", archivePath, "-C", destPath);
        } else if (name.endsWith(".tar.xz")) {
            runProcess("Extraction of " + archive, "tar", "xJf", archivePath, "-C", destPath);
        } else if (name.endsWith(".zip")) {
            runProcess("Extraction of " + archive, "unzip", "-q", archivePath, "-d", destPath);
        } else {
            throw new IOException("Unsupported archive format: " + name);
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

    private static void ensureWorldReadable(Path dir) throws IOException {
        runProcess("chmod " + dir, "chmod", "-R", "a+rX", dir.toString());
    }

    private static void runProcess(String label, String... command) throws IOException {
        try {
            // Captured, not inherited: this runs under a live step line, which the child's own
            // terminal output would be drawn over.
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException(label + " failed (exit code " + exitCode + ")"
                        + (output.isEmpty() ? "" : ": " + output));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(label + " interrupted", e);
        }
    }
}
