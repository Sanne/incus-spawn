package dev.incusspawn.vm;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.ResourceLimits;
import dev.incusspawn.tool.DownloadCache;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.CpuInfo;
import dev.incusspawn.util.HostLock;
import dev.incusspawn.util.HostPath;
import dev.incusspawn.util.OwnSession;
import dev.incusspawn.Platform;
import dev.incusspawn.Warnings;
import dev.incusspawn.util.SystemTrust;

import java.io.IOException;
import java.io.RandomAccessFile;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

/**
 * Manages the incus-spawn VM appliance lifecycle.
 * Ports appliance/vm.sh to Java for use from isx commands.
 */
public final class VmManager {

    private VmManager() {}

    /** Where forwarder streams are leaking, inferred from host vs in-guest connection counts. */
    public enum LeakLayer {
        FORWARDER("forwarder is lingering children (link 3) — the in-VM forwarder-restart clears it"),
        LAUNCHER("the VM's launcher holds connections the guest has closed (link 2) — a VM restart is required");
        public final String description;
        LeakLayer(String description) { this.description = description; }
    }

    public static LeakLayer leakLayer(int hostCount, int guestCount) {
        if (guestCount <= 0) return LeakLayer.FORWARDER;
        return guestCount * 2 <= hostCount ? LeakLayer.LAUNCHER : LeakLayer.FORWARDER;
    }

    /**
     * Determine which tunnel layer is responsible for a leak using the launcher's relay count
     * and the in-guest forwarder socat count. Returns empty if either count is unavailable.
     */
    public static Optional<LeakLayer> detectLeakLayer() {
        // A launcher whose Incus socket an older isx removed: no count to compare, and a restart
        // of the guest's forwarder cannot bring a host socket back. A VM still under vfkit has no
        // count either, and is left unknown, so recovery first tries the forwarder restart, which
        // takes a second and stops no instance; unreachableAdvice() then says to restart.
        if (isRunning() && incusSocketRemoved()) return Optional.of(LeakLayer.LAUNCHER);
        return countedLeakLayer();
    }

    /** {@link #detectLeakLayer()} from the two counts alone, for a caller that ruled out a restart. */
    private static Optional<LeakLayer> countedLeakLayer() {
        int host = vsockForwarderConnectionCount();
        if (host < 0) return Optional.empty();
        var guest = VmAgentClient.socatCount();
        if (guest.isEmpty()) return Optional.empty();
        return Optional.of(leakLayer(host, guest.getAsInt()));
    }

    /**
     * Whether the launcher runs but its Incus socket is gone. The launcher makes its sockets once
     * the machine has started, the control socket last, so a booting VM has neither: the socket
     * counts as removed only while the control socket answers.
     */
    private static boolean incusSocketRemoved() {
        return !Files.exists(Environment.vmVsockSocket()) && VmControl.state(Environment.vmControlSocket()).isPresent();
    }

    /**
     * What to tell a person on macOS whose Incus does not answer while the VM runs, when the host
     * side is at fault and only {@code isx vm restart} helps; empty otherwise, for the generic
     * diagnosis. That one knows Linux's remedies alone ({@code systemctl}, {@code isx init}),
     * which are wrong for a VM (#2031).
     */
    public static Optional<String> unreachableAdvice() {
        if (!isRunning()) return Optional.empty();
        if (runsUnderLegacyHypervisor()) {
            return Optional.of("The VM still runs under vfkit, started by an isx before the launcher, and its tunnel"
                    + " is not answering. Run 'isx vm restart' to move it to isx-vm.");
        }
        if (incusSocketRemoved()) {
            return Optional.of("The VM is running, but its Incus socket " + Environment.vmVsockSocket()
                    + " is gone (an isx from before the launcher, still open, removes it)."
                    + " Quit any older isx, then run 'isx vm restart' to restore connectivity.");
        }
        // Both cases above are told apart already, so only the counts are left.
        if (countedLeakLayer().filter(l -> l == LeakLayer.LAUNCHER).isPresent()) {
            return Optional.of("The host-side vsock tunnel is wedged (the VM's launcher is not forwarding "
                    + "connections). Run 'isx vm restart' to restore connectivity.");
        }
        return Optional.empty();
    }

    /** Overall tunnel health, for proactive detection of wedged tunnels. */
    public enum TunnelHealth {
        HEALTHY,
        LAUNCHER_WEDGED,
        FORWARDER_ISSUE,
        UNKNOWN
    }

    /**
     * Probe the vsock tunnel's health. Intended for periodic checks from the TUI or during
     * long-running operations (builds). When the Incus tunnel is dead but the agent lane answers,
     * the host-side tunnel (the launcher) is wedged and only {@code isx vm restart} can fix it.
     */
    public static TunnelHealth probeTunnelHealth() {
        if (!Platform.isMacOS()) return TunnelHealth.HEALTHY;
        if (!isRunning()) return TunnelHealth.UNKNOWN;
        if (IncusClient.isReachable()) return TunnelHealth.HEALTHY;
        // API is unreachable — try to determine which layer is at fault.
        // A result from the counts comes from the agent (socatCount), so it also confirms the
        // agent lane is healthy; LAUNCHER for a removed Incus socket asks no agent, and needs
        // the restart whatever the agent does.
        var layer = detectLeakLayer();
        if (layer.isPresent()) {
            return switch (layer.get()) {
                case LAUNCHER -> TunnelHealth.LAUNCHER_WEDGED;
                case FORWARDER -> TunnelHealth.FORWARDER_ISSUE;
            };
        }
        // Layer detection unavailable (the launcher's counts or socatCount failed); a bare ping
        // only proves the agent lane is alive — it cannot distinguish a launcher wedge from a
        // stopped Incus daemon or a crashed forwarder, so we cannot safely claim LAUNCHER_WEDGED here.
        return TunnelHealth.UNKNOWN;
    }

    private static final String DEFAULT_GATEWAY = "10.166.11.1";
    private static final String DEFAULT_MITM_PORT = "18443";
    private static final String DEFAULT_DISK_SIZE = "60G";
    private static final String DEFAULT_SWAP_SIZE = "12G";
    private static final int GA_VSOCK_PORT = 1024;
    private static final int AGENT_VSOCK_PORT = 1025;
    private static final int INCUS_VSOCK_PORT = 8443;

    private static final String LATEST_KNOWN_RELEASE = "0.2.2";
    /** Resolved once per process; package-private so a test names the version instead of asking GitHub. */
    static volatile String resolvedApplianceVersion;

    /** Where the appliance's release assets are. Not final: a test points it at a name that does not resolve. */
    static String releaseDownloads = "https://github.com/Sanne/incus-spawn/releases/download";

    // --- Resource detection ---

    public static int detectCpus() {
        var env = System.getenv("ISX_VM_CPUS");
        if (env != null && !env.isBlank()) {
            try {
                int val = Integer.parseInt(env);
                if (val < 1) {
                    BuildOutput.warn("ISX_VM_CPUS=" + env + " is invalid, using 1");
                    return 1;
                }
                return val;
            } catch (NumberFormatException e) {
                BuildOutput.warn("ISX_VM_CPUS=" + env + " is not a number, ignoring");
            }
        }
        if (Platform.isMacOS()) {
            int pcores = CpuInfo.performanceCores();
            if (pcores > 0) return pcores;
        }
        return Math.max(1, ResourceLimits.hostProcessorCount() - 2);
    }

    public static int detectMemoryMiB() {
        var env = System.getenv("ISX_VM_MEMORY");
        if (env != null && !env.isBlank()) {
            try {
                int val = Integer.parseInt(env);
                if (val < 2048) {
                    BuildOutput.warn("ISX_VM_MEMORY=" + env + " is below minimum, using 2048 MiB");
                    return 2048;
                }
                return val;
            } catch (NumberFormatException e) {
                BuildOutput.warn("ISX_VM_MEMORY=" + env + " is not a number, ignoring");
            }
        }
        long totalBytes = ResourceLimits.totalMemoryBytes();
        if (totalBytes <= 0) {
            return 4096;
        }
        int pct = Platform.isMacOS() ? 40 : 60;
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

    static String rootDiskSize() {
        return "4G";
    }

    public static String swapSize() {
        var env = System.getenv("ISX_VM_SWAP");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return DEFAULT_SWAP_SIZE;
    }

    public static String gatewayIp() {
        var env = System.getenv("ISX_GATEWAY");
        return (env != null && !env.isBlank()) ? env : DEFAULT_GATEWAY;
    }

    static String mitmPort() {
        var env = System.getenv("ISX_MITM_PORT");
        return (env != null && !env.isBlank()) ? env : DEFAULT_MITM_PORT;
    }

    // --- Appliance version resolution ---

    public static String applianceVersion() {
        var cached = resolvedApplianceVersion;
        if (cached != null) return cached;

        var build = BuildInfo.instance();
        String result;
        if (!build.isDev()) {
            result = build.version();
        } else {
            var latest = queryLatestGitHubRelease();
            if (latest != null) {
                result = latest;
            } else {
                BuildOutput.warn("could not query latest release from GitHub, "
                        + "using fallback version " + LATEST_KNOWN_RELEASE);
                result = LATEST_KNOWN_RELEASE;
            }
        }
        resolvedApplianceVersion = result;
        return result;
    }

    private static String queryLatestGitHubRelease() {
        try {
            var client = SystemTrust.httpClient()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://github.com/Sanne/incus-spawn/releases/latest"))
                    .timeout(Duration.ofSeconds(10))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            var location = response.headers().firstValue("location").orElse(null);
            if (location != null && location.contains("/tag/v")) {
                var tag = location.substring(location.lastIndexOf("/tag/v") + 6);
                if (!tag.isBlank()) return tag;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // --- Hypervisor ---

    /**
     * The appliance runs under isx's launcher, {@code isx-vm}, on macOS, and nowhere else: on Linux isx drives the host's
     * own Incus. The QEMU backend this once had was unreachable from every released isx and was
     * removed (#1695); design/macos-and-vms.md "A Linux appliance mode" says where to find it.
     */
    private static void requireMacOs() {
        if (!Platform.isMacOS()) {
            throw new VmException("The VM appliance runs only on macOS; on Linux isx uses the host's Incus.");
        }
    }

    /** The program name of isx's launcher, on disk and as {@link #isRunning()} recognises it. */
    static final String LAUNCHER = "isx-vm";

    /**
     * The hypervisor every isx before the launcher ran (#2031). A VM it started may still be
     * running after an upgrade, on the same disks: it is recognised as the running VM, so isx never
     * starts a second one beside it, and is stopped like any other. isx never starts it. Remove this
     * once no supported upgrade starts from a release that ran it.
     */
    static final String LEGACY_HYPERVISOR = "vfkit";

    /**
     * Files only an isx before the launcher wrote in the VM's state directory: vfkit's REST socket
     * and the file naming it, the placeholder initrd it insisted on, and its own output. Removed
     * with the VM's stale files.
     */
    private static final List<String> LEGACY_FILES = List.of("vm.rest-uri", "vm.rest.sock", "empty-initrd", "vfkit.log");

    /**
     * Where {@code isx-vm} is: beside this native {@code isx}, else beside the {@code isx} on the
     * {@code PATH} (Homebrew installs both in one directory, {@code install.sh} both in
     * {@code ~/.local/bin}), else in {@code ~/.local/bin}, else on the {@code PATH}. This process
     * comes first, so two installs side by side each start their own launcher.
     */
    public static Path findLauncher() {
        var candidates = new ArrayList<String>();
        var self = ProcessHandle.current().info().command();
        self.ifPresent(candidates::add);  // java's, on a JVM: no isx-vm there
        var onPath = dev.incusspawn.proxy.ProxyService.resolveIsxPath();
        if (onPath != null) candidates.add(onPath);
        for (var isx : candidates) {
            try {
                var sibling = Path.of(isx).toRealPath().resolveSibling(LAUNCHER);
                if (Files.isExecutable(sibling)) return sibling;
            } catch (IOException ignored) {}
        }
        var local = Environment.localBinIsx().resolveSibling(LAUNCHER);
        if (Files.isExecutable(local)) return local;
        var found = HostPath.find(LAUNCHER);
        if (found != null) return Path.of(found);
        boolean onJvm = self.map(c -> Path.of(c).getFileName().toString().equals("java")).orElse(false);
        var isx = onJvm ? onPath : self.orElse(onPath);
        String realIsx = null;
        if (isx != null) {
            try {
                realIsx = Path.of(isx).toRealPath().toString();
            } catch (IOException e) {
                realIsx = isx;
            }
        }
        throw new VmException(launcherMissing(realIsx, Environment.localBinIsx().getParent(), onJvm, BuildInfo.instance()));
    }

    /** The release asset {@code isx-vm} ships as, the only macOS arch released (#1693). */
    static final String LAUNCHER_ASSET = "isx-vm-macos-aarch64";

    /**
     * Why no VM can start without {@code isx-vm}, and how to install it the way this isx was
     * installed (#2552): Homebrew's formula ships it, {@code install.sh} builds it on a Mac, and a
     * JBang install (the JVM, from neither) downloads it from its release. {@code realIsx} is the
     * running isx's real path, or the one on the PATH on a JVM; null when neither is known.
     */
    static String launcherMissing(String realIsx, Path localBin, boolean onJvm, BuildInfo build) {
        var headline = LAUNCHER + ", the program that runs the VM, was not found beside isx or on the PATH.";
        if (realIsx != null) {
            var cellar = HOMEBREW_CELLAR.matcher(realIsx);
            if (cellar.find()) {
                return headline + "\nReinstall isx with Homebrew, which installs " + LAUNCHER + " beside it:"
                        + "\n  brew reinstall Sanne/tap/" + cellar.group(1);
            }
            if (Path.of(realIsx).getParent() != null && Path.of(realIsx).getParent().equals(localBin)) {
                return headline + "\nRun ./install.sh again from your incus-spawn checkout: on a Mac it builds "
                        + LAUNCHER + " and installs it beside isx.";
            }
        }
        if (build.isDev()) {
            return headline + "\nBuild it in your incus-spawn checkout with launcher/build-isx-vm.sh,"
                    + " and copy the file it prints to " + localBin.resolve(LAUNCHER) + ".";
        }
        var release = "https://github.com/Sanne/incus-spawn/releases/download/v" + build.version();
        return headline + "\n" + (onJvm ? "This isx runs on a JVM, so its install (such as JBang's) did not include "
                        + LAUNCHER + "." : "This install did not include " + LAUNCHER + ".")
                + "\nDownload it from release v" + build.version() + ", check it against the release's SHA256SUMS,"
                + " and install it in " + localBin + ", where isx looks for it:"
                // One && chain: a failed download or check, an empty one included, installs nothing
                + "\n  cd \"$(mktemp -d)\" &&"
                + "\n  curl -fL -O " + release + "/" + LAUNCHER_ASSET + " -O " + release + "/SHA256SUMS &&"
                + "\n  grep ' " + LAUNCHER_ASSET + "$' SHA256SUMS | shasum -a 256 -c &&"
                + "\n  mkdir -p \"" + localBin + "\" && install -m 755 " + LAUNCHER_ASSET + " \"" + localBin.resolve(LAUNCHER) + "\"";
    }

    /** A Homebrew install's real path, {@code <prefix>/Cellar/<formula>/<version>/bin/isx}. */
    private static final java.util.regex.Pattern HOMEBREW_CELLAR = java.util.regex.Pattern.compile("/Cellar/([^/]+)/[^/]+/bin/[^/]+$");

    // --- VM lifecycle lock ---

    private static HostLock acquireVmLock() {
        try {
            return HostLock.acquire(Environment.vmStateDir().resolve("vm.lock"),
                    "managing the VM", BuildOutput::note);
        } catch (HostLock.HostLockException e) {
            throw new VmException(e.getMessage());
        }
    }

    // --- Process lifecycle ---

    public static boolean isRunning() {
        var pidFile = Environment.vmPidFile();
        if (!Files.exists(pidFile)) return adoptRunningLauncher();
        try {
            long pid = Long.parseLong(Files.readString(pidFile).strip());
            if (isVmProcess(pid)) return true;
        } catch (IOException | NumberFormatException ignored) {}
        if (adoptRunningLauncher()) return true;
        // A pid file naming no VM: a hypervisor still on this user's disks is the VM, and its
        // sockets must not be removed as stale before it is found (#2031). Rare, so the listing
        // costs nothing on the path of a running or a stopped VM.
        if (adoptHypervisorOnDisks().isPresent()) return true;
        cleanupStaleFiles();
        return false;
    }

    private static boolean isVmProcess(long pid) {
        var handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) return false;
        var cmd = handle.get().info().command().orElse("");
        // qemu: the macOS-role CI job's stand-in for the launcher (appliance/test-macos-role.sh)
        return cmd.contains(LAUNCHER) || cmd.contains(LEGACY_HYPERVISOR) || cmd.contains("qemu");
    }

    /**
     * A launcher that still runs the VM though its pid file is gone or names another process
     * (#2031): an isx from before the launcher, still running after the upgrade (an open TUI, an
     * {@code isx mcp} session), takes an {@code isx-vm} pid for a stale one and removes the pid
     * file and the vsock sockets. Taken for "not running", the next start would put a second VM
     * on the same disks. The control socket, which that isx never removes, names the launcher's
     * pid, so it is recorded again and the VM counts as running: a restart then stops it and
     * brings the sockets back. Costs nothing when no control socket exists.
     */
    static boolean adoptRunningLauncher() {
        var control = Environment.vmControlSocket();
        if (!Files.exists(control)) return false;
        // A launcher that is stopping still holds the disks until it exits, so it counts too.
        var launcher = VmControl.state(control).map(VmControl.State::pid)
                .or(() -> launcherServing(control));
        if (launcher.isEmpty()) {
            // No launcher found, and nothing listens on the socket at all (a refused connect, not
            // a slow answer): a launcher that exited leaves it behind. Removed, or with no pid file
            // left to clean up every later command would list this user's processes again.
            if (nothingListensOn(control)) {
                try { Files.deleteIfExists(control); } catch (IOException ignored) {}
            }
            return false;
        }
        if (!isVmProcess(launcher.get())) return false;
        try {
            Files.writeString(Environment.vmPidFile(), String.valueOf(launcher.get()));
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    private static boolean nothingListensOn(Path socket) {
        try (var ch = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            ch.connect(java.net.UnixDomainSocketAddress.of(socket));
            return false;
        } catch (java.net.ConnectException refused) {
            return true;
        } catch (IOException other) {
            return false;
        }
    }

    /**
     * The launcher started with {@code control} as its control socket, found among this user's
     * processes, for a control socket that exists and did not answer: a launcher whose control
     * loop is busy still holds the disks, so its sockets must not be cleaned up as stale nor a
     * second VM started. Only on that path, so no listing of processes costs an ordinary command.
     */
    private static Optional<Long> launcherServing(Path control) {
        var wanted = control.toString();
        try (var processes = ProcessHandle.allProcesses()) {
            return processes
                    .filter(h -> h.info().command().map(c -> c.contains(LAUNCHER)).orElse(false))
                    .filter(h -> h.info().arguments().map(args -> java.util.Arrays.asList(args).contains(wanted)).orElse(false))
                    .map(ProcessHandle::pid)
                    .findFirst();
        }
    }

    /**
     * Whether the VM runs, by {@code vm.pid} or, failing that, by a hypervisor holding this user's
     * disks ({@link #adoptHypervisorOnDisks()}, which records its pid). For a command about to act
     * on the VM (a restart, a start): a vfkit VM whose pid file was lost in an upgrade is then
     * stopped and moved, not taken for a stopped VM (#2031). Lists processes when the pid file
     * does not name the VM, so never on the path of a running VM.
     */
    public static boolean runningOrAdopted() {
        return isRunning() || adoptHypervisorOnDisks().isPresent();
    }

    /**
     * A hypervisor of this user, {@code isx-vm} or an older isx's vfkit, whose arguments name this
     * state directory's root disk: the VM, whatever vm.pid says (#2031). An upgrade can lose the
     * pid file while vfkit runs on (an older isx still open, a crash, a cleaned state directory),
     * and the pid file is all {@link #isRunning()} reads. Recorded as the VM's pid when found, and
     * not reported when that record fails, so the next {@link #isRunning()} agrees. Lists this
     * user's processes, so it runs only before a launch and when vm.pid names no VM, never when
     * vm.pid is right.
     */
    static Optional<Long> adoptHypervisorOnDisks() {
        var disk = Environment.vmDiskImage().toString();
        Optional<Long> found;
        try (var processes = ProcessHandle.allProcesses()) {
            found = processes
                    .filter(h -> h.info().command().map(c -> c.contains(LAUNCHER) || c.contains(LEGACY_HYPERVISOR)).orElse(false))
                    .filter(h -> h.info().arguments().map(args -> java.util.Arrays.stream(args).anyMatch(a -> namesDisk(a, disk))).orElse(false))
                    .map(ProcessHandle::pid)
                    .findFirst();
        }
        if (found.isEmpty()) return found;
        try {
            Files.writeString(Environment.vmPidFile(), String.valueOf(found.get()));
        } catch (IOException e) {
            return Optional.empty();
        }
        return found;
    }

    /** {@code isx-vm}'s {@code --disk <path>} argument, or vfkit's {@code virtio-blk,path=<path>[,...]}. */
    private static boolean namesDisk(String arg, String disk) {
        return arg.equals(disk) || arg.endsWith(",path=" + disk) || arg.contains(",path=" + disk + ",");
    }

    static long readPid() {
        try {
            return Long.parseLong(Files.readString(Environment.vmPidFile()).strip());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    public static boolean start() {
        try (var ignored = acquireVmLock()) {
            var result = startLocked();
            if (result == StartResult.FAILED) return false;
            if (result == StartResult.ALREADY_RUNNING) return true;
            return awaitReady();
        } catch (VmException e) {
            BuildOutput.error(e.getMessage());
            return false;
        }
    }

    private enum StartResult { LAUNCHED, ALREADY_RUNNING, FAILED }

    private static StartResult startLocked() {
        return startLocked(false);
    }

    /**
     * @param replacesLegacy the VM this start replaces ran under vfkit: macOS asks again for the
     *                       new program, though the old one left a log behind (#2552)
     */
    private static StartResult startLocked(boolean replacesLegacy) {
        requireMacOs();
        if (isRunning()) {
            BuildOutput.note("VM already running (pid=" + readPid() + ").");
            return StartResult.ALREADY_RUNNING;
        }
        // The last guard before a launch: a hypervisor still running on this user's disks, which
        // vm.pid does not name (removed, or naming another process), is the VM. Starting a second
        // one on the same images would corrupt them.
        var holder = adoptHypervisorOnDisks();
        if (holder.isPresent()) {
            var hypervisor = runningLauncher(holder.get());
            BuildOutput.warn("The VM is already running (pid=" + holder.get() + (hypervisor == null ? "" : ", " + hypervisor)
                            + "), though isx had lost track of it; it was not started a second time.",
                    LEGACY_HYPERVISOR.equals(hypervisor)
                            ? "It was started by an isx before the launcher. Run 'isx vm restart' to move it to " + LAUNCHER + "."
                            : "Run 'isx vm restart' if Incus does not answer.");
            return StartResult.ALREADY_RUNNING;
        }
        try {
            checkArtifacts();
            ensureDisk();
            ensureDataDisk();
            ensureSwap();
        } catch (VmException e) {
            BuildOutput.error(e.getMessage());
            return StartResult.FAILED;
        }

        Path launcher;
        try {
            launcher = findLauncher();
        } catch (VmException e) {
            BuildOutput.error(e.getMessage());
            return StartResult.FAILED;
        }
        int cpus = detectCpus();
        int memoryMiB = detectMemoryMiB();

        if (!Files.exists(Environment.vmLogFile()) || replacesLegacy) {
            // The launcher shares the home read-only at the hypervisor, and the appliance mounts it
            // read-only too (rcS).
            if (replacesLegacy) BuildOutput.note(LAUNCHER + " now runs the VM in place of " + LEGACY_HYPERVISOR + ".");
            BuildOutput.note("macOS may ask to allow access to your home folder and the local network.");
            BuildOutput.note("Both are safe to approve.");
            BuildOutput.note("isx's Linux VM mounts your home folder read-only, to share host paths you configure.");
            BuildOutput.note("Your agents' machines see only those paths, never your whole home folder.");
            BuildOutput.note("Local network access gives the Linux containers their network.");
        }

        BuildOutput.stepStart("Launching VM (" + LAUNCHER + ", cpus=" + cpus + ", memory=" + memoryMiB + "M)");
        try {
            Files.createDirectories(Environment.vmStateDir());
            startLauncher(launcher, cpus, memoryMiB);
            return StartResult.LAUNCHED;
        } catch (Exception e) {
            BuildOutput.stepFail("Failed to start VM: " + e.getMessage());
            return StartResult.FAILED;
        }
    }

    private static boolean awaitReady() {
        BuildOutput.stepStart("Waiting for Incus daemon");
        if (waitUntilReady(60)) {
            BuildOutput.stepDone();
            return true;
        }
        BuildOutput.stepFail("Incus daemon did not become reachable within 60s.\nCheck 'isx vm console' for boot logs.");
        return false;
    }

    /**
     * Restart the VM, applying any pending appliance disk update via {@link #ensureDisk()}.
     */
    public static boolean restart() {
        try (var ignored = acquireVmLock()) {
            boolean fromLegacy = false;
            if (!runningOrAdopted()) {
                BuildOutput.note("VM not running — starting.");
            } else {
                // Before the stop: a VM stopped for a launcher that is not installed stays down,
                // which is a JBang install's first restart after the move from vfkit (#2552).
                findLauncher();
                downloadPendingAppliance();
                fromLegacy = runsUnderLegacyHypervisor();
                stopLocked();
            }
            if (startLocked(fromLegacy) != StartResult.LAUNCHED) return false;
            return awaitReady();
        } catch (VmException e) {
            BuildOutput.error(e.getMessage());
            return false;
        }
    }

    /**
     * The appliance a restart is about to boot, downloaded while the old VM still runs (#2552):
     * the kernel and disk image of a new release are two downloads, and a stop that came first
     * left the VM down for both, and down for good when one failed. A failed download fails the
     * restart before the stop; the running VM boots from memory, so replacing its files is safe.
     */
    private static void downloadPendingAppliance() {
        if (!Files.exists(Environment.vmDiskImage()) || diskIsCurrent(applianceVersion(), readVersionFile())) return;
        try {
            downloadArtifacts();
        } catch (IOException e) {
            throw new VmException("Could not download appliance " + applianceVersion() + ": " + e.getMessage()
                    + "\nThe VM was not stopped. Run 'isx vm restart' again once github.com can be reached."
                    + "\nMeanwhile 'isx vm stop' then 'isx vm start' boots the current appliance again.");
        }
    }

    /**
     * How a stop ended. {@code SIGNALLED} means the guest did not shut down when asked and the
     * hypervisor was killed: its disks were cut off mid-flight, may need recovery on the next
     * boot, and can have lost the last few minutes of writes (#881). That is every stop of an
     * appliance whose agent has no {@code shutdown} verb.
     */
    public enum StopResult { NOT_RUNNING, SHUT_DOWN, SIGNALLED, FAILED }

    public static StopResult stop() {
        return stop(true);
    }

    /** {@link #stop()} for a caller about to delete the VM's disks, which has no use for the warning. */
    public static StopResult stopToDelete() {
        return stop(false);
    }

    private static StopResult stop(boolean disksKept) {
        try (var ignored = acquireVmLock()) {
            return stopLocked(disksKept);
        } catch (VmException e) {
            BuildOutput.error(e.getMessage());
            return StopResult.FAILED;
        }
    }

    private static StopResult stopLocked() {
        return stopLocked(true);
    }

    /**
     * @param disksKept ask the guest to shut down first and warn, on {@code SIGNALLED}, that the
     *                  disks cut off may need recovery; false for disks that are deleted next
     */
    private static StopResult stopLocked(boolean disksKept) {
        // runningOrAdopted: a stop and every disk change after it must see a VM vm.pid lost.
        if (!runningOrAdopted()) {
            BuildOutput.note("VM not running.");
            cleanupStaleFiles();
            return StopResult.NOT_RUNNING;
        }
        long pid = readPid();
        var handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            BuildOutput.note("VM not running.");
            cleanupStaleFiles();
            return StopResult.NOT_RUNNING;
        }

        BuildOutput.stepStart("Shutting down VM");

        // Ask the guest itself first (#881): the agent runs poweroff, so BusyBox init runs rcK,
        // which stops Incus and its instances and syncs the disks before the guest powers off
        // and the hypervisor exits. An appliance from before the verb answers "unknown verb".
        // Disks about to be deleted need none of that.
        boolean guestAgreed = disksKept && VmAgentClient.shutdown();
        if (guestAgreed) {
            awaitExit(handle.get(), guestShutdownWait);
        }

        // A guest that is still running is stopped by the launcher, which cuts its disks off as
        // a power cut would: asked on its control socket, or with SIGTERM, which it takes the same
        // way. A VM an older isx started under vfkit has no control socket and gets the signal.
        boolean signalled = handle.get().isAlive();
        if (signalled) {
            // Only the launcher this pid is: a vfkit VM has no socket, and one answering beside it
            // is another machine, which a stop of this VM must not cut off. One already stopping
            // its machine is given the same time to finish before it is signalled.
            var control = Environment.vmControlSocket();
            var launcher = VmControl.state(control).filter(l -> l.pid() == pid);
            if (launcher.isPresent() && (launcher.get().stopping() || VmControl.stop(control))) {
                awaitExit(handle.get(), Duration.ofSeconds(5));
            }
            if (handle.get().isAlive()) {
                handle.get().destroy();
                awaitExit(handle.get(), Duration.ofSeconds(1));
            }
        }

        // SIGKILL
        if (handle.get().isAlive()) {
            handle.get().destroyForcibly();
        }

        // destroyForcibly() only sends SIGKILL; it does not wait for the process to actually
        // release the vsock socket files and disk images. A caller that starts a new VM right
        // after (restart()) would otherwise race that teardown, which fails the new launcher almost
        // immediately with no diagnostic (see resetDataDisk(), which already had to work around
        // this). Throwing here -- rather than swallowing a timeout -- means every caller gets the
        // same protection restart() needs, instead of each having to re-check isAlive() itself.
        awaitExit(handle.get(), Duration.ofSeconds(10));
        if (handle.get().isAlive()) {
            BuildOutput.stepBreak();
            throw new VmException("VM process " + pid + " did not exit after SIGKILL.");
        }

        cleanupStaleFiles();
        VmAgentClient.clearVersionCache();
        BuildOutput.stepDone();
        if (!signalled) return StopResult.SHUT_DOWN;
        if (disksKept) {
            BuildOutput.warn((guestAgreed
                    ? "The guest did not finish shutting down within " + guestShutdownWait.toSeconds() + " s"
                    : "The guest could not be asked to shut down (its appliance predates the agent's"
                            + " 'shutdown', or the agent did not answer)")
                    + ", so the VM was stopped with signals; writes of its last few minutes may be lost,"
                    + " and its disks may need recovery on the next boot (#881).");
        }
        return StopResult.SIGNALLED;
    }

    /**
     * How long a guest that agreed to shut down gets before it is signalled. {@code rcK} gives
     * the instances 10 s to stop ({@code incus admin shutdown --timeout 10}) and init then takes
     * a few more to end what is left; measured on a Mac, a guest with two running containers is
     * gone in 4-5 s. Kept under the 60 s {@code uninstall.sh} allows a whole stop. Not final:
     * tests shorten it.
     */
    static Duration guestShutdownWait = Duration.ofSeconds(30);

    private static void awaitExit(ProcessHandle process, Duration wait) {
        try {
            process.onExit().get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception stillRunning) {
            // ExecutionException/TimeoutException/CancellationException: the caller looks at
            // isAlive(), which is what decides
        }
    }

    public static String status() {
        return state().render();
    }

    /**
     * What {@code isx vm status} reports, read once, for its text ({@link #render}) and the fields
     * of its {@code --format=plain|json}. A fact that is not known is {@code null}, or -1 for a
     * number; a VM that is not running knows none.
     */
    public record State(boolean running, long pid, String launcher, String controlSocket, String log,
                        String appliance, String applianceInstalled, int vsockConnections) {

        static final State NOT_RUNNING = new State(false, -1, null, null, null, null, null, -1);

        public String render() {
            if (!running) return "VM not running";
            var sb = new StringBuilder();
            sb.append("VM running (pid=").append(pid).append(")");
            if (launcher != null) {
                sb.append("\n  Launcher: ").append(launcher);
                if (LEGACY_HYPERVISOR.equals(launcher)) {
                    sb.append("  (started by an older isx — 'isx vm restart' moves it to ").append(LAUNCHER).append(")");
                }
            }
            sb.append("\n  Log: ").append(log);
            if (appliance != null) {
                sb.append("\n  Appliance: ").append(appliance);
                if (applianceInstalled != null) {
                    sb.append("  (installed: ").append(applianceInstalled)
                            .append(" — restart to apply)");
                }
            }
            if (vsockConnections >= 0) {
                sb.append("\n  vsock forwarder connections: ").append(vsockConnections);
                if (vsockConnections > VSOCK_CONN_WARN_THRESHOLD) {
                    sb.append("  ⚠ high — the in-VM forwarder may be leaking streams; 'isx vm restart' clears them");
                }
            }
            return sb.toString();
        }
    }

    public static State state() {
        if (!isRunning()) {
            cleanupStaleFiles();
            return State.NOT_RUNNING;
        }
        long pid = readPid();
        var launcher = runningLauncher(pid);
        var control = Environment.vmControlSocket();
        var appliance = runningApplianceVersion();
        var skew = applianceSkew(appliance);
        return new State(true, pid, launcher, Files.exists(control) ? control.toString() : null,
                Environment.vmLogFile().toString(), appliance,
                skew == null ? null : skew.installed(), vsockForwarderConnectionCount());
    }

    /**
     * Which program runs the VM: {@code isx-vm}, or {@code vfkit} for a VM an isx before the
     * launcher started and that has not been restarted since; null when it cannot be read.
     */
    static String runningLauncher(long pid) {
        var cmd = ProcessHandle.of(pid).flatMap(h -> h.info().command()).orElse("");
        if (cmd.contains(LAUNCHER)) return LAUNCHER;
        if (cmd.contains(LEGACY_HYPERVISOR)) return LEGACY_HYPERVISOR;
        return null;
    }

    /**
     * Whether the running VM is still one an isx before the launcher started under vfkit. For a
     * caller that already knows the VM runs ({@link #isRunning()}), so it is not asked again.
     */
    public static boolean runsUnderLegacyHypervisor() {
        return LEGACY_HYPERVISOR.equals(runningLauncher(readPid()));
    }

    // Above this many held vsock connections, the appliance's socat forwarder is
    // likely leaking streams (it does not reap connections whose close never
    // propagates across the vsock boundary), which degrades new-connection latency.
    public static final int VSOCK_CONN_WARN_THRESHOLD = 64;

    /**
     * The connections the launcher's relay holds on the Incus socket, as the relay itself counts
     * them on its control socket (macOS only). Each is one host connection and one in-guest
     * forwarder child; a steadily climbing count is the signature of the forwarder leak. Returns
     * -1 if the count can't be read (non-macOS, no launcher answering, or a VM an older isx
     * started under vfkit, which reports none).
     */
    public static int vsockForwarderConnectionCount() {
        var control = Environment.vmControlSocket();
        if (!Platform.isMacOS() || !Files.exists(control)) return -1;
        return VmControl.held(control, INCUS_VSOCK_PORT).orElse(-1);
    }

    /**
     * Wait for the Incus daemon inside the VM to become reachable.
     *
     * Bounded by a wall-clock deadline, not an iteration count: each isReachable()
     * probe can take up to the connect watchdog (seconds) when the vsock is stalled,
     * so a count-based loop would silently overrun its "maxWaitSeconds" budget several
     * fold in exactly the degraded state we need to bound.
     */
    public static boolean waitUntilReady(int maxWaitSeconds) {
        long deadline = System.nanoTime() + maxWaitSeconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (IncusClient.isReachable()) return true;
            try { Thread.sleep(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // Recovery budget for a VM that is up but whose Incus tunnel is not answering. The primary
    // recovery is the agent-driven forwarder restart (~1 s); the grace and backstop are short
    // bounded probes around it. This is deliberately NOT sized to outwait the forwarder's own
    // `socat -T 180` self-heal: if the agent cannot restart the forwarder and it does not clear
    // within the budget, we give up so the caller can surface an actionable error rather than
    // blocking the command for minutes.
    private static final int REACHABILITY_GRACE_SECONDS = 10;
    private static final int FORWARDER_RECOVERY_WAIT_SECONDS = 15;
    private static final int REACHABILITY_BACKSTOP_SECONDS = 30;

    /**
     * Recover reachability when the VM is running but the Incus tunnel is not answering.
     *
     * Almost always this means the vsock forwarder is wedged with leaked connections: a tunnel
     * that does not propagate connection close (vfkit's did not) leaves stranded streams that pin host-side fds
     * and degrade new-connection latency until the forwarder's own {@code socat -T 180} inactivity
     * backstop reaps them. Rather than block a command for that long, we give a brief transient a
     * chance to clear, then proactively restart the forwarder via the control agent — an
     * independent vsock port that answers even when the Incus tunnel is wedged, so recovery is
     * ~1 s. Restarting only drops connections that are already stalled (nothing is getting through,
     * or we would not be here), so it is safe to trigger even for other isx processes.
     *
     * <p>Before attempting a forwarder restart, the recovery path now checks which tunnel layer is
     * wedged (see {@link LeakLayer}). A host-side (launcher) wedge cannot be fixed by restarting the
     * guest forwarder — the recovery skips the restart and fails fast with an actionable message.
     */
    private static boolean recoverReachability() {
        return recoverReachability(VmManager::waitUntilReady, VmAgentClient::restartForwarder,
                VmManager::detectLeakLayer);
    }

    /**
     * Testable core of {@link #recoverReachability()}. {@code waitUntilReady} probes reachability
     * for a given number of seconds; {@code restartForwarder} asks the control agent to restart the
     * forwarder and returns whether it confirmed; {@code detectLayer} identifies whether the wedge
     * is on the host (launcher) or guest (forwarder) side. Split out so the grace / restart / backstop
     * orchestration can be unit-tested without a live VM.
     */
    static boolean recoverReachability(IntPredicate waitUntilReady, BooleanSupplier restartForwarder,
                                       Supplier<Optional<LeakLayer>> detectLayer) {
        // A momentary blip (e.g. the daemon finishing a burst of work) may clear on its own.
        if (waitUntilReady.test(REACHABILITY_GRACE_SECONDS)) return true;

        // Determine which layer is wedged before attempting the wrong remedy.
        var layer = detectLayer.get();
        if (layer.isPresent() && layer.get() == LeakLayer.LAUNCHER) {
            BuildOutput.warn("The host-side tunnel (the VM's launcher) is wedged — a forwarder restart cannot fix this.",
                    "Run 'isx vm restart' to restore connectivity.");
            return false;
        }

        // Forwarder-layer issue or unknown layer: restart the wedged forwarder in place.
        if (restartForwarder.getAsBoolean()) {
            BuildOutput.step("Restarted the vsock forwarder.");
            BuildOutput.stepStart("Waiting for Incus");
            if (waitUntilReady.test(FORWARDER_RECOVERY_WAIT_SECONDS)) {
                BuildOutput.stepDone();
                return true;
            }
            BuildOutput.stepBreak();
        } else {
            BuildOutput.warn("The control agent did not confirm a forwarder restart.");
        }

        // Bounded final probe; if this fails, ensureRunning() returns false and the caller surfaces
        // an actionable connection error (rather than blocking for the forwarder's ~180 s self-heal).
        return waitUntilReady.test(REACHABILITY_BACKSTOP_SECONDS);
    }

    /**
     * Auto-start hook: ensure VM is running and Incus is reachable.
     * Holds the VM lifecycle lock for the full check-then-start window so a concurrent
     * restart cannot race between the isRunning() check and the start attempt.
     */
    public static boolean ensureRunning() {
        try (var ignored = acquireVmLock()) {
            return ensureRunningLocked();
        } catch (VmException e) {
            BuildOutput.error(e.getMessage());
            return false;
        }
    }

    private static boolean ensureRunningLocked() {
        if (isRunning()) {
            if (IncusClient.isReachable()) {
                warnIfApplianceStale();
                return true;
            }
            // A step: a blip usually clears within the grace period, and the failures after it warn
            BuildOutput.step("VM is running but Incus is not reachable, attempting recovery");
            return recoverReachability();
        }

        // The same start and wait as 'isx vm start', under a header of its own, a download first
        BuildOutput.header("Starting incus-spawn VM", null);
        if (!Files.exists(Environment.applianceKernel())
                || (!Files.exists(Environment.applianceDiskImage())
                    && !Files.exists(Environment.vmDiskImage()))) {
            BuildOutput.step("Downloading VM appliance artifacts");
            try {
                downloadArtifacts();
            } catch (IOException e) {
                BuildOutput.error("Failed to download appliance artifacts: " + e.getMessage());
                return false;
            }
        }

        if (startLocked() == StartResult.FAILED) return false;
        return awaitReady();
    }

    private static final long SKEW_WARN_INTERVAL_MS = 60_000;

    /**
     * The version of the appliance actually running in the VM. Prefers the live agent response
     * ({@code /etc/isx-version}); falls back to the host-side {@code disk.version} file.
     */
    public static String runningApplianceVersion() {
        var agentVer = VmAgentClient.applianceVersion();
        if (agentVer.isPresent()) return agentVer.get();
        var diskVer = readVersionFile();
        return diskVer.isEmpty() ? null : diskVer;
    }

    /** The running appliance differs from the installed one, and restarting the VM would apply it. */
    public record ApplianceSkew(String running, String installed) {
        public String message() {
            return "VM is running appliance " + running + "; " + installed
                    + " is installed — run 'isx vm restart' to apply it.";
        }
    }

    /**
     * The skew between the running appliance and the installed one, or {@code null} when there
     * is none a restart would clear. Every "restart the VM" hint goes through here.
     */
    public static ApplianceSkew applianceSkew() {
        return applianceSkew(runningApplianceVersion());
    }

    /**
     * {@link #applianceSkew()} for a caller that already read {@link #runningApplianceVersion()}
     * and also shows it: asking the agent twice costs a round trip, and the two answers can differ.
     */
    public static ApplianceSkew applianceSkew(String running) {
        if (running == null) return null;
        var installed = applianceVersion();
        if (running.equals(installed)) return null;
        return applianceSkew(running, installed, readVersionFile());
    }

    /**
     * A restart boots whatever {@link #ensureDisk()} leaves in place, and it keeps the root disk
     * when {@code disk.version} already names the installed version. The running appliance can
     * still report something else: a locally built appliance embeds the project version
     * ({@code 0.0.0-SNAPSHOT}) while a dev CLI stamps the disk with the latest release. Restarting
     * then boots the same disk, so only a differing stamp is skew worth reporting.
     */
    static ApplianceSkew applianceSkew(String running, String installed, String diskVersion) {
        if (running.equals(installed) || diskIsCurrent(installed, diskVersion)) return null;
        return new ApplianceSkew(running, installed);
    }

    /** Whether {@link #ensureDisk()} keeps a root disk stamped {@code diskVersion}. */
    private static boolean diskIsCurrent(String installed, String diskVersion) {
        return installed.equals(diskVersion);
    }

    private static void warnIfApplianceStale() {
        try {
            var skew = applianceSkew();
            if (skew == null) return;
            warnOfSkew(skew, Environment.vmStateDir().resolve(".appliance-skew-warned"));
        } catch (Exception ignored) {
            // Best-effort; never block a command for a stale-version warning.
        }
    }

    /**
     * Report a skew at most once per {@link #SKEW_WARN_INTERVAL_MS}, tracked by {@code marker}.
     * Through {@link Warnings}: bare {@code isx} runs this before its TUI opens, and the TUI would
     * draw over a line printed straight to stderr (#1154).
     */
    static void warnOfSkew(ApplianceSkew skew, Path marker) throws IOException {
        if (Files.exists(marker)) {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(marker).toMillis();
            if (age < SKEW_WARN_INTERVAL_MS) return;
        }
        Warnings.warn(skew.message());
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "");
    }

    // --- Internal: the launcher ---

    /** The bundle's Info.plist: macOS takes the permission dialogs' text from it. */
    static final String APP_BUNDLE_PLIST = """
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
                <string>isx-vm</string>
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
            """;

    /**
     * Puts the launcher inside a macOS .app bundle, as a link to the installed binary. macOS uses
     * the bundle's Info.plist for permission dialog text (home folder access, local network),
     * giving users meaningful descriptions instead of a generic prompt. A bundle an older isx made
     * for vfkit is brought up to date: its link is removed and its plist rewritten.
     */
    static String ensureAppBundle(Path bundle, Path launcher) throws IOException {
        var macosDir = bundle.resolve("Contents/MacOS");
        var plistFile = bundle.resolve("Contents/Info.plist");
        var linkedBin = macosDir.resolve(LAUNCHER);
        var target = launcher.toRealPath();

        if (Files.isSymbolicLink(linkedBin) && Files.readSymbolicLink(linkedBin).equals(target)
                && Files.exists(plistFile) && Files.readString(plistFile).equals(APP_BUNDLE_PLIST)) {
            return linkedBin.toString();
        }
        // The links themselves, not their targets: a dangling link is not "exists" but still blocks the new link.
        Files.deleteIfExists(linkedBin);
        Files.deleteIfExists(macosDir.resolve(LEGACY_HYPERVISOR));

        Files.createDirectories(macosDir);
        Files.writeString(plistFile, APP_BUNDLE_PLIST);
        Files.createSymbolicLink(linkedBin, target);
        return linkedBin.toString();
    }

    private static void startLauncher(Path launcher, int cpus, int memoryMiB) throws IOException {
        var cmd = launcherCommand(ensureAppBundle(Environment.vmAppBundle(), launcher), cpus, memoryMiB);

        // The launcher's own output goes to launcherLogFile(), never vmLogFile(): the launcher
        // opens that one itself, non-append, for the VM's console, and a second, append-mode
        // writer on the same file corrupts both (#971 review). Its own lines (a wake, the guest's
        // clock, a relay's complaint) and perl's, should the exec into it fail, go there.
        long pid = launchInOwnSession(cmd, LAUNCHER, Environment.launcherLogFile(), Environment.vmPidFile()).pid();
        BuildOutput.stepDone("pid=" + pid + noSessionNote());
    }

    /**
     * The launcher's command line (launcher/Sources/IsxVmCore/VmSpec.swift reads it). The disks
     * are the guest's vda, vdb and vdc in this order; the home is shared read-only, at the
     * hypervisor; the control socket answers isx's questions and a stop.
     */
    static List<String> launcherCommand(String launcherBin, int cpus, int memoryMiB) {
        return new ArrayList<>(List.of(
                launcherBin,
                "--cpus", String.valueOf(cpus),
                "--memory", String.valueOf(memoryMiB),
                "--kernel", Environment.applianceKernel().toString(),
                "--cmdline", kernelCmdline(),
                "--disk", Environment.vmDiskImage().toString(),
                "--disk", Environment.vmSwapImage().toString(),
                "--disk", Environment.vmDataImage().toString(),
                "--nat", VmNetwork.ISX_VM_MAC,
                "--console", Environment.vmLogFile().toString(),
                "--share", "hostfs=" + System.getProperty("user.home"),
                "--vsock", INCUS_VSOCK_PORT + "=" + Environment.vmVsockSocket(),
                "--vsock", AGENT_VSOCK_PORT + "=" + Environment.vmAgentSocket(),
                "--clock-port", String.valueOf(GA_VSOCK_PORT),
                "--control", Environment.vmControlSocket().toString()
        ));
    }

    /**
     * Starts the VM's hypervisor as the leader of a session of its own ({@link OwnSession}), output
     * and errors appended to {@code log}, and records its pid in {@code pidFile} once it has become
     * {@code program}, the word {@link #isRunning()} recognises it by (#971, #993).
     * <p>
     * The VM has to outlive the command that starts it. Started as a plain child it stays in
     * that command's process group and on its terminal, and both take it down: closing the
     * terminal hangs up every process on it, Ctrl+C reaches the whole foreground group, and
     * launchd kills whatever is left in a job's process group once the job exits, which is what
     * the launch agent's {@code isx vm start} does as soon as the VM is up (#971).
     */
    static Process launchInOwnSession(List<String> cmd, String program, Path log, Path pidFile) throws IOException {
        var pb = OwnSession.apply(new ProcessBuilder(cmd));
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        pb.redirectErrorStream(true);
        var process = pb.start();
        try {
            awaitExec(process, program);
            Files.writeString(pidFile, String.valueOf(process.pid()));
        } catch (IOException e) {
            // Nothing was recorded, so nothing else will ever find this process to stop it.
            process.destroyForcibly();
            throw new IOException(e.getMessage() + " (see " + log + ")", e);
        }
        return process;
    }

    private static String noSessionNote() {
        return OwnSession.available() ? "" : "; no setsid or perl, so it stops when the command that started it exits";
    }

    /**
     * Waits until a process started through {@link OwnSession} has become {@code program}.
     * <p>
     * For its first moments the pid is still the wrapper's (setsid or perl). {@link #isRunning()}
     * takes a recorded pid whose command is not the VM's for a stale one and removes the VM's
     * files, so the pid must not be recorded before the exec; and an exec that fails, which {@code ProcessBuilder}
     * used to report itself, now shows only as the wrapper exiting.
     */
    static void awaitExec(Process process, String program) throws IOException {
        Optional<String> last = Optional.empty();
        for (int i = 0; i < 100; i++) {
            if (!process.isAlive()) {
                throw new IOException(program + " could not be started (exit " + process.exitValue() + ")");
            }
            last = process.info().command();
            if (last.map(c -> c.contains(program)).orElse(false)) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while starting " + program);
            }
        }
        if (last.isPresent()) {
            // Still the wrapper two seconds in: something is wrong with the exec, not with reading it.
            throw new IOException(program + " did not exec within 2s (still " + last.get() + ")");
        }
        // The command could not be read at all; a live process is the best answer there is.
    }

    // --- Internal: disk management ---

    static void checkArtifacts() {
        if (!Files.exists(Environment.applianceKernel())) {
            throw new VmException(Environment.applianceKernel() + " not found.\n"
                    + "Run 'isx init' to download appliance artifacts, or set ISX_APPLIANCE_DIR.");
        }
        boolean hasDiskVersion = Files.exists(Environment.vmDiskVersion());
        boolean needsReExtract = (hasDiskVersion && !diskIsCurrent(applianceVersion(), readVersionFile()))
                || (Files.exists(Environment.vmDiskImage()) && !hasDiskVersion);
        if (needsReExtract || (!Files.exists(Environment.vmDiskImage())
                && !Files.exists(Environment.applianceDiskImage()))) {
            if (!Files.exists(Environment.applianceDiskImage())) {
                throw new VmException("No disk image found.\n"
                        + "Run 'isx init' to download appliance artifacts, or set ISX_APPLIANCE_DIR.");
            }
        }
    }

    private static String readVersionFile() {
        try {
            return Files.readString(Environment.vmDiskVersion()).strip();
        } catch (IOException e) {
            return "";
        }
    }

    static void ensureDisk() {
        var currentVersion = applianceVersion();
        var versionFile = Environment.vmDiskVersion();
        var tmp = Environment.vmDiskImage().resolveSibling("disk.img.tmp");

        // Clean up incomplete extraction from a prior crash.
        try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}

        if (Files.exists(Environment.vmDiskImage())) {
            String diskVersion;
            try {
                diskVersion = Files.exists(versionFile) ? Files.readString(versionFile).strip() : "untracked";
            } catch (IOException e) {
                return;
            }
            if (diskIsCurrent(currentVersion, diskVersion)) return;
            // Downloaded before the old disk goes: a failed download keeps it, and the old
            // disk.img.gz is never extracted under the new version's stamp (#2552).
            // A failed download boots the disk it would have replaced, beside the kernel it came
            // with: an offline dev build, whose version falls back to an old release, still starts.
            try {
                downloadArtifacts();
            } catch (IOException e) {
                BuildOutput.warn("Could not download appliance " + currentVersion + ": " + e.getMessage(),
                        "The VM starts on its current appliance. Run 'isx vm restart' once github.com can be reached.");
                return;
            }
            // Only once the download succeeded, so a failed one does not first claim the replacement
            BuildOutput.step("Replacing appliance root disk (" + diskVersion + " → " + currentVersion + ")");
            try {
                Files.delete(Environment.vmDiskImage());
                Files.deleteIfExists(versionFile);
            } catch (IOException e) {
                return;
            }
        }

        var compressed = Environment.applianceDiskImage();
        if (!Files.exists(compressed)) {
            throw new VmException("disk.img.gz not found at " + compressed
                    + "\nRun 'isx init' to download appliance artifacts.");
        }

        BuildOutput.stepStart("Extracting root disk");
        try {
            Files.createDirectories(Environment.vmStateDir());
            try (var gzIn = new GZIPInputStream(Files.newInputStream(compressed), 64 * 1024);
                 var out = Files.newOutputStream(tmp)) {
                gzIn.transferTo(out);
            }
            try (var raf = new RandomAccessFile(tmp.toFile(), "rw")) {
                raf.setLength(parseDiskSize(rootDiskSize()));
                raf.getFD().sync();
            }
            validateBtrfsMagic(tmp);
            Files.writeString(versionFile, currentVersion);
            Files.move(tmp, Environment.vmDiskImage(), StandardCopyOption.ATOMIC_MOVE);
            BuildOutput.stepDone(humanSize(Files.size(Environment.vmDiskImage()))
                    + ", " + rootDiskSize() + " virtual");
        } catch (IOException e) {
            BuildOutput.stepBreak();
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw new VmException("Failed to extract disk image: " + e.getMessage());
        }
    }

    private static final long BTRFS_SUPERBLOCK_MAGIC_OFFSET = 0x10040;
    private static final byte[] BTRFS_MAGIC = "_BHRfS_M".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public static void validateBtrfsMagic(Path diskImage) throws IOException {
        var magic = new byte[BTRFS_MAGIC.length];
        try (var raf = new RandomAccessFile(diskImage.toFile(), "r")) {
            if (raf.length() < BTRFS_SUPERBLOCK_MAGIC_OFFSET + BTRFS_MAGIC.length) {
                throw new IOException("disk image too small for a btrfs superblock ("
                        + raf.length() + " bytes)");
            }
            raf.seek(BTRFS_SUPERBLOCK_MAGIC_OFFSET);
            raf.readFully(magic);
        }
        if (!java.util.Arrays.equals(magic, BTRFS_MAGIC)) {
            throw new IOException("disk image has no valid btrfs superblock");
        }
    }

    static void ensureDataDisk() {
        if (Files.exists(Environment.vmDataImage())) return;
        createDataDisk(parseDiskSize(diskSize()));
    }

    /** A blank sparse data disk; the appliance's rcS formats it on the next boot. */
    private static void createDataDisk(long bytes) {
        BuildOutput.stepStart("Creating data disk (" + humanSize(bytes) + " sparse)");
        try {
            Files.createDirectories(Environment.vmStateDir());
            try (var raf = new RandomAccessFile(Environment.vmDataImage().toFile(), "rw")) {
                raf.setLength(bytes);
            }
            BuildOutput.stepDone();
        } catch (IOException e) {
            BuildOutput.stepBreak();
            throw new VmException("Failed to create data disk: " + e.getMessage());
        }
    }

    /**
     * Wipe everything Incus knows -- its database, the {@code cow} pool with every instance,
     * template and image, the dnf cache -- by replacing the data disk with a blank one, then boot
     * so the appliance formats it and recreates the bridge, pool and default profile. The root
     * disk, swap and appliance artifacts are kept. The last resort for a pool Incus cannot account
     * for, such as orphaned subvolumes (#717), when nothing on it is worth keeping.
     *
     * <p>The new disk keeps the old one's size, so a disk grown with {@code isx vm resize} stays
     * grown. Holds the VM lifecycle lock throughout, so no other command can start the VM on the
     * old disk halfway through.
     *
     * @return whether the disk was replaced, and if so whether the VM came back with Incus answering
     */
    public static ResetResult resetDataDisk() {
        boolean wiped = false;
        try (var ignored = acquireVmLock()) {
            long size = dataDiskSizeBytes();
            if (size <= 0) size = parseDiskSize(diskSize());
            if (runningOrAdopted()) {
                // stopLocked() itself throws if the hypervisor doesn't exit, which is exactly
                // what must hold here: a lingering process means the disk it still writes to
                // must not be replaced.
                stopLocked(false);
            }
            BuildOutput.stepStart("Deleting data disk");
            try {
                Files.deleteIfExists(Environment.vmDataImage());
            } catch (IOException e) {
                BuildOutput.stepBreak();
                throw new VmException("Failed to delete data disk: " + e.getMessage());
            }
            wiped = true;
            markDataDiskReset(java.time.LocalDate.now());
            BuildOutput.stepDone();
            createDataDisk(size);
            return startLocked() == StartResult.LAUNCHED && awaitReady() ? ResetResult.READY : ResetResult.VM_DOWN;
        } catch (VmException e) {
            BuildOutput.error(e.getMessage());
            return wiped ? ResetResult.VM_DOWN : ResetResult.NOT_RESET;
        }
    }

    /** When {@link #resetDataDisk} last replaced the disk, so {@code isx list} can say why it is empty (#1393); else null. */
    public static java.time.LocalDate dataDiskResetOn() {
        try {
            return java.time.LocalDate.parse(Files.readString(dataDiskResetMarker()).strip());
        } catch (IOException | java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    static void markDataDiskReset(java.time.LocalDate on) {
        try {
            Files.createDirectories(Environment.vmStateDir());
            Files.writeString(dataDiskResetMarker(), on + "\n");
        } catch (IOException e) {
            // Only the hint in 'isx list' is lost.
        }
    }

    public static void forgetDataDiskReset() {
        try {
            Files.deleteIfExists(dataDiskResetMarker());
        } catch (IOException e) {
            // The hint stays one more listing.
        }
    }

    private static Path dataDiskResetMarker() {
        return Environment.vmStateDir().resolve("data-disk-reset-on");
    }

    /**
     * How {@link #resetDataDisk} ended. Only {@code NOT_RESET} leaves the old disk, and with it
     * every instance, in place: callers must not clean up after instances in that case.
     */
    public enum ResetResult { NOT_RESET, VM_DOWN, READY }

    /**
     * Current logical size of the data-disk image in bytes, or -1 if it does not exist.
     * An I/O error on an <em>existing</em> image is surfaced as a {@link VmException} rather than
     * folded into -1, so callers can distinguish "no disk yet" from "disk present but unreadable".
     */
    public static long dataDiskSizeBytes() {
        var dataImage = Environment.vmDataImage();
        if (!Files.exists(dataImage)) return -1;
        try {
            return Files.size(dataImage);
        } catch (IOException e) {
            throw new VmException("Could not read data disk size at " + dataImage + ": " + e.getMessage());
        }
    }

    /**
     * Grow the appliance data disk — the sparse raw image at {@link Environment#vmDataImage()}
     * that the guest exposes as {@code /dev/vdc} and mounts at {@code /var/lib/incus} to back the
     * btrfs {@code cow} storage pool. Grow-only: {@code newSize} must exceed the current size.
     *
     * This only extends the host-side image (a sparse {@code setLength}, so no host blocks are
     * allocated up front); the guest expands btrfs to fill the larger device on its next boot
     * (see the appliance rcS "btrfs filesystem resize max /var/lib/incus"). The VM must be
     * stopped first so it re-reads the device geometry on restart.
     *
     * @return the new size in bytes
     */
    public static long resizeDataDisk(String newSize) {
        var dataImage = Environment.vmDataImage();
        if (!Files.exists(dataImage)) {
            throw new VmException("No data disk found at " + dataImage
                    + "\nRun 'isx vm start' once to create it before resizing.");
        }
        if (runningOrAdopted()) {
            throw new VmException("VM is running. Stop it ('isx vm stop') before resizing the data disk.");
        }
        long target = parseDiskSize(newSize);
        long current;
        try {
            current = Files.size(dataImage);
        } catch (IOException e) {
            throw new VmException("Could not read current data disk size: " + e.getMessage());
        }
        if (target <= current) {
            throw new VmException("New size (" + humanSize(target) + ") must be larger than the current size ("
                    + humanSize(current) + "). Shrinking is not supported.");
        }
        try (var raf = new RandomAccessFile(dataImage.toFile(), "rw")) {
            raf.setLength(target);
        } catch (IOException e) {
            throw new VmException("Failed to resize data disk: " + e.getMessage());
        }
        return target;
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
     * the current isx version. Re-downloads when the version changes —
     * a stale disk.img.gz would produce a disk.img missing features
     * added in newer releases.
     */
    public static void downloadArtifacts() throws IOException {
        var version = applianceVersion();
        var arch = normalizeArch();
        var baseUrl = releaseDownloads + "/v" + version;

        Files.createDirectories(Environment.applianceDir());

        var versionFile = Environment.applianceDir().resolve("version");
        boolean versionMatch = false;
        if (Files.exists(versionFile)) {
            try {
                versionMatch = Files.readString(versionFile).strip().equals(version);
            } catch (IOException ignored) {}
        }
        // Another version's files are downloaded beside the current ones and moved into place only
        // once both arrived: a download that fails leaves the last working pair in place, and the
        // version file still names it (#2552).
        var cache = new DownloadCache();
        var kernel = Environment.applianceKernel();
        var image = Environment.applianceDiskImage();
        var nextKernel = kernel.resolveSibling(kernel.getFileName() + ".next");
        var nextImage = image.resolveSibling(image.getFileName() + ".next");
        boolean newKernel = !versionMatch || !Files.exists(kernel);
        boolean newImage = !versionMatch || !Files.exists(image);
        try {
            if (newKernel) downloadKernel(cache, baseUrl, arch, nextKernel);
            if (newImage) {
                downloadStep(cache, "Downloading disk image (" + arch + ")",
                        baseUrl + "/disk-" + arch + ".img.gz", nextImage);
            }
            if (newKernel) Files.move(nextKernel, kernel, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (newImage) Files.move(nextImage, image, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(nextKernel);
            Files.deleteIfExists(nextImage);
        }

        Files.writeString(versionFile, version);
    }

    /**
     * Fetch the appliance kernel, which is published gzipped.
     *
     * <p>Compressing it for distribution matters most on aarch64 -- the macOS target -- because
     * that arch has no self-decompressing kernel target: {@code Image} is the raw thing, where
     * x86_64's {@code bzImage} already compresses itself. Gzipping the asset roughly halves the
     * download rather than shipping 11 MB of uncompressed kernel.
     *
     * <p>Releases published before that change carry only the uncompressed asset. A dev build
     * resolves its appliance version to whatever the latest release is, so it can legitimately
     * point at one of those; fall back to the old name instead of failing, and report the
     * compressed attempt's failure if neither is there.
     */
    private static void downloadKernel(DownloadCache cache, String baseUrl, String arch, java.nio.file.Path dest)
            throws IOException {
        try {
            downloadStep(cache, "Downloading vmlinuz (" + arch + ")",
                    baseUrl + "/vmlinuz-" + arch + ".gz", dest, true);
        } catch (IOException compressed) {
            try {
                downloadStep(cache, "Downloading vmlinuz (" + arch + ", uncompressed)",
                        baseUrl + "/vmlinuz-" + arch, dest, false);
            } catch (IOException legacy) {
                compressed.addSuppressed(legacy);
                throw compressed;
            }
        }
    }

    /**
     * Run one download as a single terminal step, closing its own dangling step line on failure
     * before propagating. Because only the code that printed the {@code stepStart} knows a line is
     * outstanding, owning the {@code stepBreak} here lets callers report the error with a plain
     * message rather than each guessing whether a line needs terminating.
     */
    private static void downloadStep(DownloadCache cache, String label, String url, java.nio.file.Path dest)
            throws IOException {
        downloadStep(cache, label, url, dest, false);
    }

    /**
     * As above, optionally gunzipping the download on the way to {@code dest}. The decompressed
     * form lands via a temp file and an atomic move: {@link #downloadArtifacts()} treats an
     * existing file as a completed download, so a half-written one would be indistinguishable
     * from a good one on the next run.
     */
    private static void downloadStep(DownloadCache cache, String label, String url,
            java.nio.file.Path dest, boolean gunzip) throws IOException {
        BuildOutput.stepStart(label);
        try {
            var cached = cache.download(url, null);
            if (gunzip) {
                var tmp = dest.resolveSibling(dest.getFileName() + ".part");
                try {
                    try (var gzIn = new GZIPInputStream(Files.newInputStream(cached), 64 * 1024);
                         var out = Files.newOutputStream(tmp)) {
                        gzIn.transferTo(out);
                    }
                    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException | RuntimeException e) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                    throw e;
                }
            } else {
                // Through a temp file too: it replaces the previous version's copy (#2552)
                var tmp = dest.resolveSibling(dest.getFileName() + ".part");
                try {
                    Files.copy(cached, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException | RuntimeException e) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                    throw e;
                }
            }
        } catch (IOException | RuntimeException e) {
            BuildOutput.stepBreak();
            throw e;
        }
        BuildOutput.stepDone();
    }

    public static long parseDiskSize(String size) {
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

    static String kernelCmdline() {
        // No mitigations=off: the macOS appliance is aarch64, where arm64 forces
        // CPU_MITIGATIONS on (only x86_64 compiles them off). rootfstype=btrfs
        // avoids the kernel probing fuseblk (which rejects the 'commit'
        // rootflag) before btrfs at root mount.
        return "root=/dev/vda rootfstype=btrfs rw rootflags=commit=300 console=hvc0"
                + " isx.gateway=" + gatewayIp()
                + " isx.mitm_port=" + mitmPort()
                + " isx.time=" + (System.currentTimeMillis() / 1000)
                + " isx.ga_vsock=" + GA_VSOCK_PORT
                + " isx.vsock_incus=" + INCUS_VSOCK_PORT
                + " isx.agent_vsock=" + AGENT_VSOCK_PORT
                + " isx.proxy=remote"
                + " isx.shared=/host";
    }

    private static String normalizeArch() {
        var arch = System.getProperty("os.arch");
        if ("amd64".equals(arch)) return "x86_64";
        if ("arm64".equals(arch)) return "aarch64";
        return arch;
    }


    private static void cleanupStaleFiles() {
        try { Files.deleteIfExists(Environment.vmPidFile()); } catch (IOException ignored) {}
        try { Files.deleteIfExists(Environment.vmControlSocket()); } catch (IOException ignored) {}
        for (var legacy : LEGACY_FILES) {
            try { Files.deleteIfExists(Environment.vmStateDir().resolve(legacy)); } catch (IOException ignored) {}
        }
        try { Files.deleteIfExists(Environment.vmVsockSocket()); } catch (IOException ignored) {}
        try { Files.deleteIfExists(Environment.vmAgentSocket()); } catch (IOException ignored) {}
    }

    public static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format("%.1fG", bytes / (1024.0 * 1024 * 1024));
        }
        if (bytes >= 1024 * 1024) {
            return String.format("%.1fM", bytes / (1024.0 * 1024));
        }
        return bytes + "B";
    }
}
