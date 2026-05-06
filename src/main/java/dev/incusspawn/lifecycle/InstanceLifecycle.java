package dev.incusspawn.lifecycle;

import dev.incusspawn.command.BranchCommand;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.git.AutoRemoteService;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;

import java.nio.file.Path;
import java.util.List;

/**
 * Manages the lifecycle of instance creation with explicit phases and dependency validation.
 *
 * <p>Instance creation follows a strict sequence:</p>
 * <ol>
 *   <li><b>Creation</b>: incus.copy() or incus.launch() - instance exists but unconfigured</li>
 *   <li><b>Resource Configuration</b>: limits, network, devices</li>
 *   <li><b>Metadata Tagging</b>: TYPE, PARENT, CREATED (required before host integration)</li>
 *   <li><b>Host Integration</b>: host resources, git remotes (depends on metadata)</li>
 *   <li><b>Startup</b> (instances only): start, wait for ready</li>
 *   <li><b>Runtime Setup</b> (instances only): firewall, GUI, ownership, SSH</li>
 * </ol>
 *
 * <p>Templates skip phases 5-6 since they remain stopped.</p>
 */
public final class InstanceLifecycle {

    private InstanceLifecycle() {}

    /**
     * Apply resource limits (CPU, memory, disk) to an instance.
     * Must be called after instance exists, can be called on stopped instances.
     */
    public static void applyResourceLimits(IncusClient incus, String name,
                                          int cpu, String memory, String disk) {
        if (!incus.exists(name)) {
            throw new IllegalStateException("Instance " + name + " must exist before applying resource limits");
        }

        incus.configSet(name, "limits.cpu", String.valueOf(cpu));
        incus.configSet(name, "limits.memory", memory);
        incus.exec("config", "device", "set", name, "root", "size=" + disk);
    }

    /**
     * Configure network mode for an instance.
     * Must be called after instance exists, before starting.
     *
     * @param mode FULL (default bridge), PROXY_ONLY (restricted), or AIRGAP (no network)
     */
    public static void configureNetwork(IncusClient incus, String name, NetworkMode mode) {
        if (!incus.exists(name)) {
            throw new IllegalStateException("Instance " + name + " must exist before configuring network");
        }

        switch (mode) {
            case FULL -> {} // Default: keep on incusbr0
            case PROXY_ONLY -> configureProxyOnly(incus, name);
            case AIRGAP -> configureAirgap(incus, name);
        }
    }

    private static void configureAirgap(IncusClient incus, String name) {
        var result = incus.exec("network", "detach", "incusbr0", name);
        if (!result.success()) {
            incus.exec("config", "device", "override", name, "eth0");
            incus.exec("config", "device", "remove", name, "eth0");
        }
    }

    private static void configureProxyOnly(IncusClient incus, String name) {
        var gatewayIp = resolveGatewayIp(incus);
        incus.configSet(name, Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name());
        incus.configSet(name, Metadata.PROXY_GATEWAY, gatewayIp);
    }

    private static String resolveGatewayIp(IncusClient incus) {
        var raw = incus.exec("network", "get", "incusbr0", "ipv4.address")
                .assertSuccess("Failed to get bridge IP").stdout().strip();
        return raw.contains("/") ? raw.substring(0, raw.indexOf('/')) : raw;
    }

    /**
     * Tag instance with metadata (TYPE, PARENT, CREATED).
     * Must be called after instance exists.
     * This is a prerequisite for host integration (addRemotes needs PARENT).
     *
     * @param type Metadata.TYPE_CLONE, TYPE_BASE, TYPE_PROJECT, etc.
     * @param parent Name of the parent instance/template
     */
    public static void tagMetadata(IncusClient incus, String name, String type, String parent) {
        if (!incus.exists(name)) {
            throw new IllegalStateException("Instance " + name + " must exist before tagging metadata");
        }

        incus.configSet(name, Metadata.TYPE, type);
        incus.configSet(name, Metadata.PARENT, parent);
        incus.configSet(name, Metadata.CREATED, Metadata.today());
    }

    /**
     * Integrate instance with host resources and git repos.
     * Must be called after metadata is tagged (addRemotes depends on Metadata.PARENT).
     *
     * @param instanceType INSTANCE (adds remotes) or TEMPLATE (skips remotes)
     */
    public static void integrateWithHost(IncusClient incus, String name, InstanceType instanceType) {
        // Validate prerequisites
        var parent = incus.configGet(name, Metadata.PARENT);
        if (parent.isEmpty()) {
            throw new IllegalStateException(
                "Metadata.PARENT must be set before host integration (needed by AutoRemoteService.addRemotes)");
        }

        // Apply host resource devices (if configured in parent template)
        var hrJson = incus.configGet(name, Metadata.HOST_RESOURCES);
        var hostResources = HostResourceSetup.deserialize(hrJson);
        if (!hostResources.isEmpty()) {
            HostResourceSetup.applyForInstance(incus, name, hostResources);
        }

        // Add git remotes to host repos (instances only, not templates)
        if (instanceType == InstanceType.INSTANCE) {
            AutoRemoteService.addRemotes(incus, name);
        }
    }

    /**
     * Configure runtime features that require a running instance.
     * Must be called after instance is started and ready.
     * Only applicable to instances, not templates.
     *
     * @param networkMode Network mode (for proxy-only firewall)
     * @param enableGui Whether to configure GUI passthrough
     * @param inboxPath Optional inbox directory to mount (read-only)
     */
    public static void setupRuntime(IncusClient incus, String name,
                                   NetworkMode networkMode, boolean enableGui, Path inboxPath) {
        if (!isRunning(incus, name)) {
            throw new IllegalStateException("Instance " + name + " must be running for runtime setup");
        }

        // Apply proxy-only firewall rules if needed
        if (networkMode == NetworkMode.PROXY_ONLY) {
            BranchCommand.applyProxyOnlyFirewall(incus, name);
        }

        // Configure GUI passthrough
        if (enableGui) {
            configureGui(incus, name);
        }

        // Mount inbox
        if (inboxPath != null) {
            mountInbox(incus, name, inboxPath);
        }

        // Fix home directory ownership
        fixHomeOwnership(incus, name);

        // Inject SSH keys
        BranchCommand.injectSshKeyIfAvailable(incus, name);
    }

    private static boolean isRunning(IncusClient incus, String name) {
        var status = incus.exec("list", name, "--format=csv", "--columns=s").stdout().strip();
        return "RUNNING".equalsIgnoreCase(status);
    }

    private static void configureGui(IncusClient incus, String name) {
        var xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        var waylandDisplay = System.getenv("WAYLAND_DISPLAY");

        if (xdgRuntimeDir == null || waylandDisplay == null) {
            System.err.println("Error: GUI passthrough requires WAYLAND_DISPLAY and XDG_RUNTIME_DIR.");
            System.err.println("Make sure you are running isx from a Wayland graphical session.");
            return;
        }

        var hostSocket = xdgRuntimeDir + "/" + waylandDisplay;
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(hostSocket))) {
            System.err.println("Error: Wayland socket not found at " + hostSocket);
            return;
        }

        System.out.println("Enabling GUI passthrough...");
        var uid = getUid();

        incus.deviceAdd(name, "gpu", "gpu");
        incus.deviceAdd(name, "xdg-runtime", "disk",
                "source=" + xdgRuntimeDir,
                "path=/run/user/" + uid);

        incus.shellExec(name, "sh", "-c",
                "cat > /etc/profile.d/wayland.sh << 'ENVEOF'\n" +
                "export WAYLAND_DISPLAY=" + waylandDisplay + "\n" +
                "export XDG_RUNTIME_DIR=/run/user/" + uid + "\n" +
                "export GDK_BACKEND=wayland\n" +
                "export QT_QPA_PLATFORM=wayland\n" +
                "export SDL_VIDEODRIVER=wayland\n" +
                "export MOZ_ENABLE_WAYLAND=1\n" +
                "export ELECTRON_OZONE_PLATFORM_HINT=wayland\n" +
                "ENVEOF\n" +
                "chmod 644 /etc/profile.d/wayland.sh");
    }

    private static void mountInbox(IncusClient incus, String name, Path inboxPath) {
        if (!java.nio.file.Files.isDirectory(inboxPath)) {
            System.err.println("Warning: inbox path '" + inboxPath + "' is not a directory, skipping.");
            return;
        }

        System.out.println("Mounting inbox: " + inboxPath.toAbsolutePath() + " -> /home/agentuser/inbox (read-only)");
        incus.deviceAdd(name, "inbox", "disk",
                "source=" + inboxPath.toAbsolutePath(),
                "path=/home/agentuser/inbox",
                "readonly=true");
    }

    private static void fixHomeOwnership(IncusClient incus, String name) {
        // Fix ownership of home dir itself (not recursively — files inside already
        // have correct ownership from the template, and -R would be very slow on
        // large images with many pre-built dependencies)
        var uid = getUid();
        incus.shellExec(name, "chown", uid + ":" + uid, "/home/agentuser");
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
}
