package dev.incusspawn.vm;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.ResourceLimits;
import dev.incusspawn.tool.DownloadCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Manages the incus-spawn VM appliance lifecycle.
 * Ports appliance/vm.sh to Java for use from isx commands.
 */
public final class VmManager {

    private VmManager() {}

    public enum Backend { VFKIT, QEMU }

    private static final String DEFAULT_GATEWAY = "10.166.11.1";
    private static final String DEFAULT_MITM_PORT = "18443";
    private static final String DEFAULT_DISK_SIZE = "60G";
    private static final String DEFAULT_SWAP_SIZE = "12G";
    private static final int GA_VSOCK_PORT = 1024;

    // --- Resource detection ---

    public static int detectCpus() {
        var env = System.getenv("ISX_VM_CPUS");
        if (env != null && !env.isBlank()) {
            try {
                int val = Integer.parseInt(env);
                if (val < 1) {
                    System.err.println("Warning: ISX_VM_CPUS=" + env + " is invalid, using 1");
                    return 1;
                }
                return val;
            } catch (NumberFormatException e) {
                System.err.println("Warning: ISX_VM_CPUS=" + env + " is not a number, ignoring");
            }
        }
        if (Environment.isMacOS()) {
            int pcores = detectPerformanceCores();
            if (pcores > 0) return pcores;
        }
        int available = Runtime.getRuntime().availableProcessors();
        return Math.max(1, available - 2);
    }

    private static int detectPerformanceCores() {
        try {
            var pb = new ProcessBuilder("sysctl", "-n", "hw.perflevel0.logicalcpu");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() == 0 && !output.isBlank()) {
                return Integer.parseInt(output);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    public static int detectMemoryMiB() {
        var env = System.getenv("ISX_VM_MEMORY");
        if (env != null && !env.isBlank()) {
            try {
                int val = Integer.parseInt(env);
                if (val < 2048) {
                    System.err.println("Warning: ISX_VM_MEMORY=" + env + " is below minimum, using 2048 MiB");
                    return 2048;
                }
                return val;
            } catch (NumberFormatException e) {
                System.err.println("Warning: ISX_VM_MEMORY=" + env + " is not a number, ignoring");
            }
        }
        long totalBytes = ResourceLimits.totalMemoryBytes();
        if (totalBytes <= 0) {
            return 4096;
        }
        int pct = Environment.isMacOS() ? 40 : 60;
        long limitMiB = totalBytes * pct / 100 / (1024 * 1024);
        return (int) Math.max(2048, limitMiB);
    }

    public static String diskSize() {
        var env = System.getenv("ISX_VM_DISK");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return DEFAULT_DISK_SIZE;
    }

    public static String swapSize() {
        var env = System.getenv("ISX_VM_SWAP");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return DEFAULT_SWAP_SIZE;
    }

    static String gatewayIp() {
        var env = System.getenv("ISX_GATEWAY");
        return (env != null && !env.isBlank()) ? env : DEFAULT_GATEWAY;
    }

    static String mitmPort() {
        var env = System.getenv("ISX_MITM_PORT");
        return (env != null && !env.isBlank()) ? env : DEFAULT_MITM_PORT;
    }

    // --- Backend detection ---

    public static Backend detectBackend() {
        if (Environment.isMacOS()) {
            if (!commandExists("vfkit")) {
                throw new VmException("vfkit not found. Install with: brew install vfkit");
            }
            return Backend.VFKIT;
        }
        var arch = normalizeArch();
        if (!commandExists("qemu-system-" + arch)) {
            throw new VmException("qemu-system-" + arch + " not found. Install QEMU.");
        }
        return Backend.QEMU;
    }

    // --- Process lifecycle ---

    public static boolean isRunning() {
        var pidFile = Environment.vmPidFile();
        if (!Files.exists(pidFile)) return false;
        try {
            long pid = Long.parseLong(Files.readString(pidFile).strip());
            var handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                cleanupStaleFiles();
                return false;
            }
            var cmd = handle.get().info().command().orElse("");
            if (!cmd.contains("vfkit") && !cmd.contains("qemu")) {
                cleanupStaleFiles();
                return false;
            }
            return true;
        } catch (IOException | NumberFormatException e) {
            cleanupStaleFiles();
            return false;
        }
    }

    static long readPid() {
        try {
            return Long.parseLong(Files.readString(Environment.vmPidFile()).strip());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    public static boolean start() {
        if (isRunning()) {
            System.out.println("VM already running (pid=" + readPid() + ")");
            return true;
        }
        try {
            checkArtifacts();
            ensureDisk();
            ensureSwap();
        } catch (VmException e) {
            System.err.println("Error: " + e.getMessage());
            return false;
        }

        try {
            IncusRemoteSetup.ensureCertExists();
        } catch (java.io.IOException e) {
            System.err.println("Warning: could not ensure client certificate: " + e.getMessage());
        }

        var backend = detectBackend();
        int cpus = detectCpus();
        int memoryMiB = detectMemoryMiB();

        if (backend == Backend.VFKIT && !Files.exists(Environment.vmLogFile())) {
            System.out.println();
            System.out.println("  macOS may show permission dialogs for home folder access and");
            System.out.println("  local network connectivity. These are safe to approve:");
            System.out.println("  - Your home directory is mounted read-only (nothing is modified)");
            System.out.println("  - Agents run in sandboxed containers that only see paths you configure");
            System.out.println("  - Network access enables connectivity for the Linux containers");
            System.out.println();
        }

        System.out.println("Starting VM with " + backend.name().toLowerCase()
                + " (cpus=" + cpus + ", memory=" + memoryMiB + "M)...");
        try {
            Files.createDirectories(Environment.vmStateDir());
            switch (backend) {
                case VFKIT -> startVfkit(cpus, memoryMiB);
                case QEMU -> startQemu(cpus, memoryMiB);
            }
            return true;
        } catch (Exception e) {
            System.err.println("Failed to start VM: " + e.getMessage());
            return false;
        }
    }

    public static void stop() {
        if (!isRunning()) {
            System.out.println("VM not running");
            cleanupStaleFiles();
            return;
        }
        long pid = readPid();
        var handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            System.out.println("VM not running");
            cleanupStaleFiles();
            return;
        }

        // Attempt graceful shutdown via REST API (vfkit only)
        var restUriFile = Environment.vmRestUriFile();
        if (Files.exists(restUriFile)) {
            try {
                var uri = Files.readString(restUriFile).strip();
                var client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build();
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(uri + "/vm/state"))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"state\":\"Stop\"}"))
                        .header("Content-Type", "application/json")
                        .build();
                client.send(request, HttpResponse.BodyHandlers.discarding());
                for (int i = 0; i < 10; i++) {
                    if (!handle.get().isAlive()) break;
                    Thread.sleep(500);
                }
            } catch (Exception ignored) {}
        }

        // SIGTERM
        if (handle.get().isAlive()) {
            handle.get().destroy();
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }

        // SIGKILL
        if (handle.get().isAlive()) {
            handle.get().destroyForcibly();
        }

        cleanupStaleFiles();
        System.out.println("VM stopped");
    }

    public static String status() {
        if (isRunning()) {
            long pid = readPid();
            var sb = new StringBuilder();
            sb.append("VM running (pid=").append(pid).append(")");
            var restUriFile = Environment.vmRestUriFile();
            if (Files.exists(restUriFile)) {
                try {
                    sb.append("\n  REST API: ").append(Files.readString(restUriFile).strip());
                } catch (IOException ignored) {}
            }
            sb.append("\n  Log: ").append(Environment.vmLogFile());
            return sb.toString();
        }
        cleanupStaleFiles();
        return "VM not running";
    }

    /**
     * Wait for the Incus daemon inside the VM to become reachable.
     */
    public static boolean waitUntilReady(int maxWaitSeconds) {
        for (int i = 0; i < maxWaitSeconds; i++) {
            if (IncusClient.isReachable()) return true;
            try { Thread.sleep(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Auto-start hook: ensure VM is running and Incus is reachable.
     * Prints progress to stderr so it doesn't interfere with command output.
     */
    public static boolean ensureRunning() {
        if (isRunning()) {
            if (IncusClient.isReachable()) return true;
            if (Environment.isMacOS()) {
                return configureRemoteAndWait();
            }
            System.err.println("VM is running but Incus is not reachable. Waiting...");
            return waitUntilReady(30);
        }

        if (!Files.exists(Environment.applianceKernel())
                || (!Files.exists(Environment.applianceDiskImage())
                    && !Files.exists(Environment.vmDiskImage()))) {
            System.err.println("Downloading VM appliance artifacts...");
            try {
                downloadArtifacts();
            } catch (IOException e) {
                System.err.println("Failed to download appliance artifacts: " + e.getMessage());
                return false;
            }
        }

        System.err.print("Starting incus-spawn VM... ");
        if (!start()) return false;

        if (Environment.isMacOS()) {
            return configureRemoteAndWait();
        }

        System.err.println("Waiting for Incus daemon...");
        if (waitUntilReady(60)) {
            System.err.println("VM is ready.");
            return true;
        }
        System.err.println("Warning: VM started but Incus daemon did not become reachable within 60s.");
        System.err.println("Check 'isx vm console' for boot logs.");
        return false;
    }

    private static boolean configureRemoteAndWait() {
        System.err.println("Waiting for VM network...");
        var vmIp = VmNetwork.waitForVmIp(30);
        if (vmIp == null) {
            System.err.println("Error: could not discover VM IP address.");
            System.err.println("Check that the VM is running: isx vm status");
            return false;
        }
        System.err.println("  VM IP: " + vmIp);

        if (!IncusRemoteSetup.isConfigured()) {
            try {
                IncusRemoteSetup.configure(vmIp);
            } catch (IOException e) {
                System.err.println("Failed to configure Incus remote: " + e.getMessage());
                return false;
            }
        } else {
            try {
                IncusRemoteSetup.updateVmIp(vmIp);
            } catch (IOException e) {
                System.err.println("Warning: could not update VM IP: " + e.getMessage());
            }
        }

        System.err.println("Waiting for Incus daemon...");
        if (waitUntilReady(60)) {
            System.err.println("VM is ready.");
            return true;
        }
        System.err.println("Warning: VM started but Incus daemon did not become reachable within 60s.");
        System.err.println("Check 'isx vm console' for boot logs.");
        return false;
    }

    // --- Internal: vfkit ---

    private static final Path VFKIT_APP_BUNDLE = Environment.vmStateDir().resolve("incus-spawn-vm.app");

    /**
     * Create a macOS .app bundle wrapper around the vfkit binary. macOS uses the
     * bundle's Info.plist for permission dialog text (home folder access, local
     * network), giving users meaningful descriptions instead of a generic prompt.
     */
    private static String ensureVfkitAppBundle() throws IOException {
        var macosDir = VFKIT_APP_BUNDLE.resolve("Contents/MacOS");
        var plistFile = VFKIT_APP_BUNDLE.resolve("Contents/Info.plist");
        var linkedBin = macosDir.resolve("vfkit");

        var vfkitPath = resolveVfkitPath();

        if (Files.exists(linkedBin) && Files.exists(plistFile)) {
            if (Files.isSymbolicLink(linkedBin)) {
                var target = Files.readSymbolicLink(linkedBin);
                if (target.equals(Path.of(vfkitPath))) {
                    return linkedBin.toString();
                }
            }
            Files.delete(linkedBin);
        }

        Files.createDirectories(macosDir);
        Files.writeString(plistFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" \
                "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>CFBundleIdentifier</key>
                    <string>dev.incusspawn.vm</string>
                    <key>CFBundleName</key>
                    <string>incus-spawn VM</string>
                    <key>CFBundleExecutable</key>
                    <string>vfkit</string>
                    <key>NSLocalNetworkUsageDescription</key>
                    <string>incus-spawn runs Linux containers inside a lightweight \
                virtual machine on your Mac. Local network access is required so \
                that containers can reach the network and communicate with the \
                host.</string>
                    <key>NSHomeDirectoryUsageDescription</key>
                    <string>incus-spawn runs Linux containers inside a lightweight \
                virtual machine on your Mac. Your home directory is mounted \
                read-only to enable host file sharing — no data is modified. \
                Agents run inside sandboxed containers, and each container only \
                receives access to the specific paths you explicitly configure \
                (such as a project directory or a build cache). Containers never \
                see your full home directory.</string>
                </dict>
                </plist>
                """);
        Files.createSymbolicLink(linkedBin, Path.of(vfkitPath));
        return linkedBin.toString();
    }

    private static String resolveVfkitPath() throws IOException {
        try {
            var pb = new ProcessBuilder("which", "vfkit");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() == 0 && !output.isBlank()) {
                return Path.of(output).toRealPath().toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new IOException("vfkit not found in PATH. Install it with: brew install vfkit");
    }

    private static void startVfkit(int cpus, int memoryMiB) throws IOException {
        int restPort = findFreePort();
        ensureDummyInitrd();

        var vfkitBin = ensureVfkitAppBundle();

        var cmd = new ArrayList<>(List.of(
                vfkitBin,
                "--cpus", String.valueOf(cpus),
                "--memory", String.valueOf(memoryMiB),
                "--kernel", Environment.applianceKernel().toString(),
                "--initrd", Environment.vmDummyInitrd().toString(),
                "--kernel-cmdline", kernelCmdline("hvc0"),
                "--device", "virtio-blk,path=" + Environment.vmDiskImage(),
                "--device", "virtio-blk,path=" + Environment.vmSwapImage(),
                "--device", "virtio-net,nat,mac=" + VmNetwork.ISX_VM_MAC,
                "--device", "virtio-serial,logFilePath=" + Environment.vmLogFile(),
                "--device", "virtio-fs,sharedDir=" + System.getProperty("user.home") + ",mountTag=hostfs",
                "--timesync", "vsockPort=" + GA_VSOCK_PORT,
                "--restful-uri", "tcp://localhost:" + restPort
        ));

        var pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        var process = pb.start();
        long pid = process.pid();

        Files.writeString(Environment.vmPidFile(), String.valueOf(pid));
        Files.writeString(Environment.vmRestUriFile(), "http://localhost:" + restPort);
        System.out.println("VM started (pid=" + pid + ", rest=localhost:" + restPort + ")");
    }

    // --- Internal: QEMU ---

    private static void startQemu(int cpus, int memoryMiB) throws IOException {
        var arch = normalizeArch();
        var qemuBin = "qemu-system-" + arch;
        String console;
        var machineArgs = new ArrayList<String>();

        switch (arch) {
            case "x86_64" -> {
                console = "ttyS0";
                if (Files.exists(Path.of("/dev/kvm"))) {
                    machineArgs.addAll(List.of("-machine", "pc", "-cpu", "host", "-enable-kvm"));
                } else {
                    machineArgs.addAll(List.of("-machine", "pc", "-cpu", "qemu64"));
                }
            }
            case "aarch64" -> {
                console = "ttyAMA0";
                if (Files.exists(Path.of("/dev/kvm"))) {
                    machineArgs.addAll(List.of("-machine", "virt", "-cpu", "host", "-enable-kvm"));
                } else {
                    machineArgs.addAll(List.of("-machine", "virt", "-cpu", "cortex-a57"));
                }
            }
            default -> throw new VmException("Unsupported architecture: " + arch);
        }

        var cmd = new ArrayList<String>();
        cmd.add(qemuBin);
        cmd.addAll(machineArgs);
        cmd.addAll(List.of(
                "-m", String.valueOf(memoryMiB),
                "-smp", String.valueOf(cpus),
                "-nographic",
                "-nodefaults",
                "-serial", "stdio",
                "-kernel", Environment.applianceKernel().toString(),
                "-drive", "id=root,file=" + Environment.vmDiskImage() + ",format=raw,if=virtio",
                "-drive", "id=swap,file=" + Environment.vmSwapImage() + ",format=raw,if=virtio",
                "-netdev", "user,id=net0",
                "-device", "virtio-net-pci,netdev=net0",
                "-append", kernelCmdline(console)
        ));

        var pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(Environment.vmLogFile().toFile()));
        pb.redirectErrorStream(true);
        var process = pb.start();
        long pid = process.pid();

        Files.writeString(Environment.vmPidFile(), String.valueOf(pid));
        System.out.println("VM started (pid=" + pid + ")");
    }

    // --- Internal: disk management ---

    static void checkArtifacts() {
        if (!Files.exists(Environment.applianceKernel())) {
            throw new VmException(Environment.applianceKernel() + " not found.\n"
                    + "Run 'isx init' to download appliance artifacts, or set ISX_APPLIANCE_DIR.");
        }
        if (!Files.exists(Environment.vmDiskImage()) && !Files.exists(Environment.applianceDiskImage())) {
            throw new VmException("No disk image found.\n"
                    + "Run 'isx init' to download appliance artifacts, or set ISX_APPLIANCE_DIR.");
        }
    }

    static void ensureDisk() {
        if (Files.exists(Environment.vmDiskImage())) return;

        var compressed = Environment.applianceDiskImage();
        if (!Files.exists(compressed)) {
            throw new VmException("disk.img.gz not found at " + compressed
                    + "\nRun 'isx init' to download appliance artifacts.");
        }

        System.out.println("Extracting disk image (first run)...");
        var tmp = Environment.vmDiskImage().resolveSibling("disk.img.tmp");
        try {
            Files.createDirectories(Environment.vmStateDir());
            try (var gzIn = new GZIPInputStream(Files.newInputStream(compressed), 64 * 1024);
                 var out = Files.newOutputStream(tmp)) {
                gzIn.transferTo(out);
            }
            try (var raf = new RandomAccessFile(tmp.toFile(), "rw")) {
                raf.setLength(parseDiskSize(diskSize()));
            }
            Files.move(tmp, Environment.vmDiskImage(), StandardCopyOption.ATOMIC_MOVE);
            System.out.println("Disk image ready: " + humanSize(Files.size(Environment.vmDiskImage()))
                    + " (" + diskSize() + " virtual)");
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw new VmException("Failed to extract disk image: " + e.getMessage());
        }
    }

    static void ensureSwap() {
        var swapImage = Environment.vmSwapImage();
        if (Files.exists(swapImage)) return;

        try {
            Files.createDirectories(Environment.vmStateDir());
            try (var raf = new RandomAccessFile(swapImage.toFile(), "rw")) {
                raf.setLength(parseDiskSize(swapSize()));
            }
        } catch (IOException e) {
            throw new VmException("Failed to create swap image: " + e.getMessage());
        }
    }

    /**
     * Download vmlinuz and disk.img.gz from the GitHub release matching
     * the current isx version.
     */
    public static void downloadArtifacts() throws IOException {
        var version = BuildInfo.instance().version();
        var arch = normalizeArch();
        var baseUrl = "https://github.com/Sanne/incus-spawn/releases/download/v" + version;

        Files.createDirectories(Environment.applianceDir());
        var cache = new DownloadCache();

        if (!Files.exists(Environment.applianceKernel())) {
            System.out.println("  Downloading vmlinuz (" + arch + ")...");
            var cached = cache.download(baseUrl + "/vmlinuz-" + arch, null);
            Files.copy(cached, Environment.applianceKernel(), StandardCopyOption.REPLACE_EXISTING);
        }

        if (!Files.exists(Environment.applianceDiskImage())) {
            System.out.println("  Downloading disk image (" + arch + ")...");
            var cached = cache.download(baseUrl + "/disk-" + arch + ".img.gz", null);
            Files.copy(cached, Environment.applianceDiskImage(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static long parseDiskSize(String size) {
        size = size.strip().toUpperCase();
        long multiplier = 1;
        if (size.endsWith("G")) {
            multiplier = 1024L * 1024 * 1024;
            size = size.substring(0, size.length() - 1);
        } else if (size.endsWith("M")) {
            multiplier = 1024L * 1024;
            size = size.substring(0, size.length() - 1);
        } else if (size.endsWith("T")) {
            multiplier = 1024L * 1024 * 1024 * 1024;
            size = size.substring(0, size.length() - 1);
        }
        try {
            return Long.parseLong(size) * multiplier;
        } catch (NumberFormatException e) {
            throw new VmException("Invalid disk size: '" + size
                    + "'. Expected a number with optional G, M, or T suffix (e.g., 60G, 512M).");
        }
    }

    // --- Internal: helpers ---

    private static String kernelCmdline(String console) {
        return "root=/dev/vda rw rootflags=commit=300 console=" + console
                + " quiet mitigations=off"
                + " isx.gateway=" + gatewayIp()
                + " isx.mitm_port=" + mitmPort()
                + " isx.time=" + (System.currentTimeMillis() / 1000)
                + " isx.ga_vsock=" + GA_VSOCK_PORT
                + " isx.proxy=remote"
                + " isx.shared=/host";
    }

    private static void ensureDummyInitrd() throws IOException {
        var path = Environment.vmDummyInitrd();
        if (Files.exists(path)) return;
        Files.createDirectories(path.getParent());
        // Minimal empty cpio archive, gzipped (vfkit requires --initrd even if unused)
        var baos = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(baos)) {
            // Empty CPIO newc archive: just the trailer
            var trailer = "070701"                // magic
                    + "00000000"                   // ino
                    + "00000000"                   // mode
                    + "00000000"                   // uid
                    + "00000000"                   // gid
                    + "00000001"                   // nlink
                    + "00000000"                   // mtime
                    + "00000000"                   // filesize
                    + "00000000"                   // devmajor
                    + "00000000"                   // devminor
                    + "00000000"                   // rdevmajor
                    + "00000000"                   // rdevminor
                    + "0000000B"                   // namesize (11 = "TRAILER!!!\0")
                    + "00000000"                   // checksum
                    + "TRAILER!!!\0";              // name
            gzip.write(trailer.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        Files.write(path, baos.toByteArray());
    }

    private static int findFreePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new VmException("Could not find a free port for vfkit REST API");
        }
    }

    private static String normalizeArch() {
        var arch = System.getProperty("os.arch");
        if ("amd64".equals(arch)) return "x86_64";
        if ("arm64".equals(arch)) return "aarch64";
        return arch;
    }

    private static boolean commandExists(String command) {
        try {
            var pb = new ProcessBuilder("which", command);
            pb.redirectErrorStream(true);
            return pb.start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void cleanupStaleFiles() {
        try { Files.deleteIfExists(Environment.vmPidFile()); } catch (IOException ignored) {}
        try { Files.deleteIfExists(Environment.vmRestUriFile()); } catch (IOException ignored) {}
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format("%.1fG", bytes / (1024.0 * 1024 * 1024));
        }
        if (bytes >= 1024 * 1024) {
            return String.format("%.1fM", bytes / (1024.0 * 1024));
        }
        return bytes + "B";
    }
}
