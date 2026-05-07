package dev.incusspawn.command;

import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.ProjectConfig;
import dev.incusspawn.git.AutoRemoteService;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.ResourceLimits;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.MitmProxy;
import dev.incusspawn.proxy.ProxyHealthCheck;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
@Command(
        name = "branch",
        description = "Create a new instance from an existing one",
        mixinStandardHelpOptions = true
)
public class BranchCommand implements Runnable {

    @Parameters(index = "0", description = "Name for the new instance")
    String name;

    @Option(names = "--from", description = "Source instance to branch from (auto-detected from cwd if omitted)")
    String source;

    @Option(names = "--gui", description = "Enable GUI passthrough (Wayland + GPU + audio)")
    boolean gui;

    @Option(names = "--airgap", description = "Disable network access (complete isolation)")
    boolean airgap;

    @Option(names = "--proxy-only", description = "Restrict network to host proxy only (Claude + GitHub via proxy)")
    boolean proxyOnly;

    @Option(names = "--inbox", description = "Host directory to mount read-only at /home/agentuser/inbox")
    Path inbox;

    @Option(names = "--cpu", description = "CPU core limit (default: adaptive)")
    Integer cpuLimit;

    @Option(names = "--memory", description = "Memory limit, e.g. '8GB' (default: adaptive)")
    String memoryLimit;

    @Option(names = "--disk", description = "Disk size limit (default: adaptive)")
    String diskLimit;

    @Option(names = "--no-start", description = "Don't start the instance after creation")
    boolean noStart;

    @Inject
    IncusClient incus;

    @Override
    public void run() {
        var resolvedSource = resolveSource();
        if (resolvedSource == null) return;

        if (incus.exists(name)) {
            System.err.println("Error: an instance named '" + name + "' already exists.");
            return;
        }

        var networkMode = resolveNetworkMode();
        if (networkMode != NetworkMode.AIRGAP) {
            if (!ProxyHealthCheck.checkOrWarn(incus)) return;
            if (checkCaMismatch(resolvedSource)) return;
        }

        System.out.println("Branching '" + name + "' from '" + resolvedSource + "'...");
        incus.copy(resolvedSource, name);

        // Apply resource limits
        var cpu = cpuLimit != null ? cpuLimit : ResourceLimits.adaptiveCpuLimit();
        var memory = memoryLimit != null ? memoryLimit : ResourceLimits.adaptiveMemoryLimit();
        var disk = diskLimit != null ? diskLimit : ResourceLimits.defaultDiskLimit();

        System.out.println("Applying resource limits: " + cpu + " CPUs, " + memory + " memory, " + disk + " disk");
        incus.configSet(name, "limits.cpu", String.valueOf(cpu));
        incus.configSet(name, "limits.memory", memory);
        incus.exec("config", "device", "set", name, "root", "size=" + disk);

        configureNetwork(networkMode);

        // Tag with metadata — override inherited type so the TUI shows this as an instance, not a template
        incus.configSet(name, Metadata.TYPE, Metadata.TYPE_CLONE);
        incus.configSet(name, Metadata.PARENT, resolvedSource);
        incus.configSet(name, Metadata.CREATED, Metadata.today());

        applyHostResourceDevices(name);

        // Add git remotes to host repos (doesn't require instance to be running)
        AutoRemoteService.addRemotes(incus, name);

        // Configure GUI before start so environment.* keys are visible to init
        if (gui) {
            if (configureGui()) {
                incus.configSet(name, Metadata.GUI_ENABLED, "true");
            } else {
                System.err.println("Continuing without GUI passthrough.");
            }
        } else {
            warnIfTemplateWantsGui(resolvedSource);
        }

        if (noStart) {
            System.out.println("Branch '" + name + "' created (not started).");
            return;
        }

        incus.start(name);
        waitForReady(name);

        if (networkMode == NetworkMode.PROXY_ONLY) {
            applyProxyOnlyFirewall(name);
        }

        if (inbox != null) {
            if (java.nio.file.Files.isDirectory(inbox)) {
                System.out.println("Mounting inbox: " + inbox.toAbsolutePath() + " -> /home/agentuser/inbox (read-only)");
                incus.deviceAdd(name, "inbox", "disk",
                        "source=" + inbox.toAbsolutePath(),
                        "path=/home/agentuser/inbox",
                        "readonly=true");
            } else {
                System.err.println("Warning: inbox path '" + inbox + "' is not a directory, skipping.");
            }
        }

        // Fix ownership of home dir itself (not recursively — files inside already
        // have correct ownership from the template, and -R would be very slow on
        // large images with many pre-built dependencies)
        incus.shellExec(name, "chown", getUid() + ":" + getUid(), "/home/agentuser");

        injectSshKeyIfAvailable(incus, name);

        System.out.println("Branch '" + name + "' is ready.\n");
        incus.interactiveShell(name, "agentuser");
    }

    private String resolveSource() {
        if (source != null) {
            if (!incus.exists(source)) {
                System.err.println("Error: source instance '" + source + "' does not exist.");
                return null;
            }
            return source;
        }

        // Try to auto-detect from cwd
        var projectConfig = ProjectConfig.findInDirectory(Path.of("."));
        if (projectConfig != null && projectConfig.getName() != null) {
            var detected = projectConfig.getName();
            if (incus.exists(detected)) {
                System.out.println("Auto-detected source: " + detected);
                return detected;
            }
            System.err.println("Error: auto-detected source '" + detected + "' does not exist.");
            return null;
        }

        System.err.println("Error: no --from specified and no incus-spawn.yaml found in current directory.");
        System.err.println("Usage: isx branch <name> --from <source-instance>");
        return null;
    }

    private boolean configureGui() {
        return configureGui(incus, name);
    }

    // Env vars needed for Wayland GUI passthrough (toolkit backends + quirk suppressors).
    private static final java.util.Map<String, String> WAYLAND_ENV = java.util.Map.of(
            "GDK_BACKEND", "wayland",
            "QT_QPA_PLATFORM", "wayland",
            "SDL_VIDEODRIVER", "wayland",
            "MOZ_ENABLE_WAYLAND", "1",
            "ELECTRON_OZONE_PLATFORM_HINT", "wayland",
            "NO_AT_BRIDGE", "1");

    /**
     * Configure GUI passthrough on a (stopped) container: GPU device, Wayland
     * socket mount, environment variables, and tmpfiles.d for XDG_RUNTIME_DIR.
     */
    static boolean configureGui(IncusClient incus, String name) {
        var xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        var waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        if (xdgRuntimeDir == null || waylandDisplay == null) {
            System.err.println("Error: GUI passthrough requires WAYLAND_DISPLAY and XDG_RUNTIME_DIR.");
            System.err.println("Make sure you are running isx from a Wayland graphical session.");
            return false;
        }
        var hostSocket = xdgRuntimeDir + "/" + waylandDisplay;
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(hostSocket))) {
            System.err.println("Error: Wayland socket not found at " + hostSocket);
            System.err.println("Make sure you are running isx from a Wayland graphical session.");
            return false;
        }

        System.out.println("Enabling GUI passthrough...");
        // Remove first in case the source already had these devices (incus copy carries them over).
        incus.exec("config", "device", "remove", name, "gpu");
        incus.exec("config", "device", "remove", name, "xdg-runtime");
        incus.deviceAdd(name, "gpu", "gpu");
        // Mount to /mnt/host-xdg instead of /run/user/<uid> — systemd-logind
        // mounts its own tmpfs at /run/user/<uid> which would hide this device.
        incus.deviceAdd(name, "xdg-runtime", "disk",
                "source=" + xdgRuntimeDir,
                "path=/mnt/host-xdg");
        // Set env vars via container config (visible to init and direct exec)
        // AND via profile.d script (visible to login shells, since su - resets env).
        var uid = getUid();
        var waylandSocketPath = "/mnt/host-xdg/" + waylandDisplay;
        incus.configSet(name, "environment.WAYLAND_DISPLAY", waylandSocketPath);
        WAYLAND_ENV.forEach((k, v) -> incus.configSet(name, "environment." + k, v));
        pushWaylandFiles(incus, name, waylandSocketPath, uid);
        return true;
    }

    /**
     * Warn at shell entry if a GUI-enabled container can't reach the host
     * Wayland compositor.
     */
    static void checkGuiHealth(IncusClient incus, String name) {
        var guiEnabled = incus.configGet(name, Metadata.GUI_ENABLED);
        if (!"true".equals(guiEnabled)) return;
        var waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        var xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        if (waylandDisplay == null || xdgRuntimeDir == null) {
            System.err.println("\033[33mWarning: GUI passthrough is enabled but no Wayland session detected.\033[0m");
            System.err.println("GUI applications will not work in this session.");
            return;
        }
        var socket = xdgRuntimeDir + "/" + waylandDisplay;
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(socket))) {
            System.err.println("\033[33mWarning: GUI passthrough is enabled but Wayland socket not found.\033[0m");
            System.err.println("GUI applications may not work. Try re-branching with --gui.");
        }
    }

    private static void pushWaylandFiles(IncusClient incus, String container, String waylandSocketPath, String uid) {
        try {
            var profile = new StringBuilder();
            profile.append("export WAYLAND_DISPLAY=").append(waylandSocketPath).append('\n');
            profile.append("export XDG_RUNTIME_DIR=/run/user/").append(uid).append('\n');
            WAYLAND_ENV.forEach((k, v) -> profile.append("export ").append(k).append('=').append(v).append('\n'));
            pushTempFile(incus, container, profile.toString(), "/etc/profile.d/wayland.sh");

            // systemd-logind may not create /run/user/<uid> in containers;
            // ensure it exists at boot so XDG_RUNTIME_DIR is usable.
            var tmpfiles = "d /run/user/" + uid + " 0700 " + uid + " " + uid + " -\n";
            pushTempFile(incus, container, tmpfiles, "/etc/tmpfiles.d/wayland-runtime.conf");
        } catch (IOException e) {
            System.err.println("Warning: failed to push wayland config: " + e.getMessage());
        }
    }

    private static void pushTempFile(IncusClient incus, String container, String content, String destPath)
            throws IOException {
        var tmp = Files.createTempFile("isx-", ".tmp");
        Files.writeString(tmp, content);
        incus.filePush(tmp.toString(), container, destPath);
        Files.delete(tmp);
    }

    private void warnIfTemplateWantsGui(String source) {
        if ("true".equals(incus.configGet(source, Metadata.GUI_ENABLED))) {
            System.err.println("Note: '" + source + "' has GUI passthrough — consider using --gui.");
            return;
        }
        var defs = ImageDef.loadAll();
        var def = defs.get(source);
        if (def != null && def.isGui()) {
            System.err.println("Note: '" + source + "' has GUI passthrough — consider using --gui.");
        }
    }

    private NetworkMode resolveNetworkMode() {
        if (airgap && proxyOnly) {
            System.err.println("Error: --airgap and --proxy-only are mutually exclusive.");
            System.exit(1);
        }
        if (airgap) return NetworkMode.AIRGAP;
        if (proxyOnly) return NetworkMode.PROXY_ONLY;
        return NetworkMode.FULL;
    }

    private void configureNetwork(NetworkMode mode) {
        switch (mode) {
            case FULL -> {} // Default: keep on incusbr0, no restrictions
            case PROXY_ONLY -> configureProxyOnly();
            case AIRGAP -> configureAirgap();
        }
    }

    private void configureAirgap() {
        System.out.println("Enabling network airgap...");
        var result = incus.exec("network", "detach", "incusbr0", name);
        if (!result.success()) {
            incus.exec("config", "device", "override", name, "eth0");
            incus.exec("config", "device", "remove", name, "eth0");
        }
    }

    private void configureProxyOnly() {
        System.out.println("Configuring proxy-only network...");
        var gatewayIp = MitmProxy.resolveGatewayIp(incus);

        incus.configSet(name, Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name());
        incus.configSet(name, Metadata.PROXY_GATEWAY, gatewayIp);
    }

    /**
     * Apply iptables rules inside the container to restrict outbound traffic to only
     * the host MITM proxy and DNS. Called after the container is started.
     */
    static void applyProxyOnlyFirewall(IncusClient incus, String name) {
        var gatewayIp = incus.configGet(name, Metadata.PROXY_GATEWAY);
        if (gatewayIp.isEmpty()) {
            System.err.println("Warning: no proxy gateway configured, skipping firewall rules.");
            return;
        }

        var mitmPort = MitmProxy.CONTAINER_FACING_PORT;
        var healthPort = MitmProxy.DEFAULT_HEALTH_PORT;

        System.out.println("Applying proxy-only firewall rules...");

        // Allow loopback
        incus.shellExec(name, "iptables", "-A", "OUTPUT", "-o", "lo", "-j", "ACCEPT");
        // Allow established/related connections
        incus.shellExec(name, "iptables", "-A", "OUTPUT", "-m", "conntrack",
                "--ctstate", "ESTABLISHED,RELATED", "-j", "ACCEPT");
        // Allow MITM TLS proxy (port 443)
        incus.shellExec(name, "iptables", "-A", "OUTPUT", "-d", gatewayIp,
                "-p", "tcp", "--dport", String.valueOf(mitmPort), "-j", "ACCEPT");
        // Allow health check endpoint
        incus.shellExec(name, "iptables", "-A", "OUTPUT", "-d", gatewayIp,
                "-p", "tcp", "--dport", String.valueOf(healthPort), "-j", "ACCEPT");
        // Allow DNS to gateway
        incus.shellExec(name, "iptables", "-A", "OUTPUT", "-d", gatewayIp,
                "-p", "udp", "--dport", "53", "-j", "ACCEPT");
        // Drop everything else
        incus.shellExec(name, "iptables", "-P", "OUTPUT", "DROP");

        System.out.println("  Outbound traffic restricted to " + gatewayIp +
                " ports " + mitmPort + " (MITM), " + healthPort + " (health), 53 (DNS)");
    }

    private void applyProxyOnlyFirewall(String name) {
        applyProxyOnlyFirewall(incus, name);
    }

    private static String getUid() {
        try {
            var pb = new ProcessBuilder("id", "-u");
            var p = pb.start();
            var output = new String(p.getInputStream().readAllBytes()).strip();
            p.waitFor();
            return output;
        } catch (Exception e) {
            return "1000";
        }
    }

    private void applyHostResourceDevices(String containerName) {
        var hrJson = incus.configGet(containerName, Metadata.HOST_RESOURCES);
        var resources = HostResourceSetup.deserialize(hrJson);
        if (!resources.isEmpty()) {
            System.out.println("Applying host-resource devices...");
            HostResourceSetup.applyForInstance(incus, containerName, resources);
        }
    }

    private boolean checkCaMismatch(String source) {
        var imageCaFp = incus.configGet(source, Metadata.CA_FINGERPRINT);
        if (imageCaFp.isEmpty()) return false;
        var localCaFp = CertificateAuthority.currentCaFingerprint();
        if (localCaFp.isEmpty() || imageCaFp.equals(localCaFp)) return false;
        var profile = incus.configGet(source, Metadata.PROFILE);
        var sep = "\033[33m" + "─".repeat(60) + "\033[0m";
        System.err.println(sep);
        System.err.println("\033[1;33mCA certificate mismatch\033[0m");
        System.err.println("Template '" + source + "' was built with a different CA certificate.");
        System.err.println("TLS connections through the proxy will fail in branches.");
        if (!profile.isEmpty()) {
            System.err.println("Rebuild the template to fix: \033[1misx build " + profile + "\033[0m");
        }
        System.err.println(sep);
        return true;
    }

    private void waitForReady(String container) {
        for (int i = 0; i < 30; i++) {
            var result = incus.shellExec(container, "true");
            if (result.success()) return;
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
        System.err.println("Warning: instance " + container + " may not be fully ready.");
    }

    static void injectSshKeyIfAvailable(IncusClient incus, String name) {
        var check = incus.shellExec(name, "test", "-f", "/home/agentuser/.ssh/authorized_keys");
        if (!check.success()) return;

        var home = System.getProperty("user.home");
        Path pubKey = null;
        for (var keyName : List.of("id_ed25519.pub", "id_ecdsa.pub", "id_rsa.pub")) {
            var candidate = Path.of(home, ".ssh", keyName);
            if (Files.exists(candidate)) {
                pubKey = candidate;
                break;
            }
        }
        if (pubKey == null) {
            System.out.println("  SSH is available but no public key found in ~/.ssh/");
            System.out.println("  Add your key manually: ssh-copy-id agentuser@<container-ip>");
            return;
        }

        try {
            var keyContent = Files.readString(pubKey).strip();
            var tmpKey = Files.createTempFile("isx-ssh-", ".pub");
            try {
                Files.writeString(tmpKey, keyContent + "\n");
                incus.filePush(tmpKey.toString(), name, "/home/agentuser/.ssh/authorized_keys");
                incus.shellExec(name, "chown", "agentuser:agentuser", "/home/agentuser/.ssh/authorized_keys");
                incus.shellExec(name, "chmod", "600", "/home/agentuser/.ssh/authorized_keys");
            } finally {
                Files.deleteIfExists(tmpKey);
            }
        } catch (IOException e) {
            System.err.println("  Warning: failed to inject SSH key: " + e.getMessage());
            return;
        }

        var ipResult = incus.shellExec(name, "hostname", "-I");
        if (ipResult.success()) {
            var ip = ipResult.stdout().strip().split("\\s+")[0];
            System.out.println("  SSH access: ssh agentuser@" + ip);
        }
    }
}
