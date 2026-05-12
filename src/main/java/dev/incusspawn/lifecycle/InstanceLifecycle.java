package dev.incusspawn.lifecycle;

import dev.incusspawn.command.BranchCommand;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.git.AutoRemoteService;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.MitmProxy;

import java.nio.file.Path;

/**
 * Shared helpers for instance/template creation lifecycle.
 * Eliminates duplication between BranchCommand and ListCommand.
 */
public final class InstanceLifecycle {

    private InstanceLifecycle() {}

    public static void applyResourceLimits(IncusClient incus, String name,
                                          int cpu, String memory, String disk) {
        incus.configSet(name, "limits.cpu", String.valueOf(cpu));
        incus.configSet(name, "limits.memory", memory);
        incus.exec("config", "device", "set", name, "root", "size=" + disk);
    }

    public static void configureNetwork(IncusClient incus, String name, NetworkMode mode) {
        switch (mode) {
            case FULL -> {}
            case PROXY_ONLY -> {
                System.out.println("Configuring proxy-only network...");
                var gatewayIp = MitmProxy.resolveGatewayIp(incus);
                incus.configSet(name, Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name());
                incus.configSet(name, Metadata.PROXY_GATEWAY, gatewayIp);
            }
            case AIRGAP -> {
                System.out.println("Enabling network airgap...");
                var result = incus.exec("network", "detach", "incusbr0", name);
                if (!result.success()) {
                    incus.exec("config", "device", "override", name, "eth0");
                    incus.exec("config", "device", "remove", name, "eth0");
                }
            }
        }
    }

    public static void tagMetadata(IncusClient incus, String name, String type, String parent) {
        incus.configSet(name, Metadata.TYPE, type);
        incus.configSet(name, Metadata.PARENT, parent);
        incus.configSet(name, Metadata.CREATED, Metadata.today());
    }

    /**
     * Apply host resource devices and (for instances) add git remotes.
     */
    public static void integrateWithHost(IncusClient incus, String name, InstanceType instanceType) {
        var hrJson = incus.configGet(name, Metadata.HOST_RESOURCES);
        var hostResources = HostResourceSetup.deserialize(hrJson);
        if (!hostResources.isEmpty()) {
            System.out.println("Applying host-resource devices...");
            HostResourceSetup.applyForInstance(incus, name, hostResources);
        }

        if (instanceType == InstanceType.INSTANCE) {
            AutoRemoteService.addRemotes(incus, name);
        }
    }

    /**
     * Post-start setup: firewall, inbox, home ownership, SSH keys.
     * GUI is NOT handled here — it must be configured before start.
     */
    public static void setupRuntime(IncusClient incus, String name,
                                   NetworkMode networkMode, Path inboxPath) {
        if (networkMode == NetworkMode.PROXY_ONLY) {
            BranchCommand.applyProxyOnlyFirewall(incus, name);
        }

        if (inboxPath != null) {
            if (java.nio.file.Files.isDirectory(inboxPath)) {
                System.out.println("Mounting inbox: " + inboxPath.toAbsolutePath() +
                        " -> /home/agentuser/inbox (read-only)");
                incus.deviceAdd(name, "inbox", "disk",
                        "source=" + inboxPath.toAbsolutePath(),
                        "path=/home/agentuser/inbox",
                        "readonly=true");
            } else {
                System.err.println("Warning: inbox path '" + inboxPath +
                        "' is not a directory, skipping.");
            }
        }

        var uid = getUid();
        incus.shellExec(name, "chown", uid + ":" + uid, "/home/agentuser");

        BranchCommand.injectSshKeyIfAvailable(incus, name);
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
