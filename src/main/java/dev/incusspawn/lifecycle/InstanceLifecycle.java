package dev.incusspawn.lifecycle;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.git.AutoRemoteService;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.MitmProxy;
import dev.incusspawn.ssh.SshKeyManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared helpers for instance/template creation lifecycle.
 * Eliminates duplication between BranchCommand and ListCommand.
 */
public final class InstanceLifecycle {

    private InstanceLifecycle() {}

    public static void applyResourceLimits(IncusClient incus, String name,
                                          String cpu, String memory, String disk) {
        incus.configSet(name, "limits.cpu", cpu);
        incus.configSet(name, "limits.memory", memory);
        incus.deviceConfigSet(name, "root", "size", disk);
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
                incus.networkDetach(name, "incusbr0");
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
            applyProxyOnlyFirewall(incus, name);
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

        awaitToolReadiness(incus, name);
        injectSshKeyIfAvailable(incus, name);
    }

    /**
     * Apply iptables rules inside the container to restrict outbound traffic to only
     * the host MITM proxy and DNS. Called after the container is started.
     */
    public static void applyProxyOnlyFirewall(IncusClient incus, String name) {
        var gatewayIp = incus.configGet(name, Metadata.PROXY_GATEWAY);
        if (gatewayIp.isEmpty()) {
            System.err.println("Warning: no proxy gateway configured, skipping firewall rules.");
            return;
        }

        var mitmPort = MitmProxy.CONTAINER_FACING_PORT;
        var healthPort = MitmProxy.DEFAULT_HEALTH_PORT;

        System.out.println("Applying proxy-only firewall rules...");

        incus.shellExec(name, "iptables", "-A", "OUTPUT", "-o", "lo", "-j", "ACCEPT");
        incus.shellExec(name, "iptables", "-A", "OUTPUT", "-m", "conntrack",
                "--ctstate", "ESTABLISHED,RELATED", "-j", "ACCEPT");
        incus.shellExec(name, "iptables", "-A", "OUTPUT", "-d", gatewayIp,
                "-p", "tcp", "--dport", String.valueOf(mitmPort), "-j", "ACCEPT");
        incus.shellExec(name, "iptables", "-A", "OUTPUT", "-d", gatewayIp,
                "-p", "tcp", "--dport", String.valueOf(healthPort), "-j", "ACCEPT");
        incus.shellExec(name, "iptables", "-A", "OUTPUT", "-d", gatewayIp,
                "-p", "udp", "--dport", "53", "-j", "ACCEPT");
        incus.shellExec(name, "iptables", "-P", "OUTPUT", "DROP");

        System.out.println("  Outbound traffic restricted to " + gatewayIp +
                " ports " + mitmPort + " (MITM), " + healthPort + " (health), 53 (DNS)");
    }

    public static void awaitToolReadiness(IncusClient incus, String name) {
        var buildSourceJson = incus.configGet(name, Metadata.BUILD_SOURCE);
        var buildSource = BuildSource.fromJson(buildSourceJson);
        if (buildSource == null) return;

        for (var tool : buildSource.getTools().values()) {
            if (tool.getReady() == null || tool.getReady().isBlank()) continue;
            var toolName = tool.getName();
            System.out.println("Waiting for " + toolName + "...");
            if (!incus.pollUntilReady(name, 15, "sh", "-c", tool.getReady())) {
                System.err.println("Warning: " + toolName + " did not become ready in time.");
            }
        }
    }

    public static void injectSshKeyIfAvailable(IncusClient incus, String name) {
        var check = incus.shellExec(name, "test", "-f", "/home/agentuser/.ssh/authorized_keys");
        if (!check.success()) return;

        // Ensure managed key infrastructure exists (creates lazily for pre-existing installs)
        boolean includeConfigured = false;
        try {
            if (!SshKeyManager.exists()) {
                SshKeyManager.ensureKeyPairExists();
            }
            includeConfigured = SshKeyManager.ensureSshConfigInclude();
        } catch (Exception ignored) {}

        // Collect keys to inject — managed key plus any personal key
        var keys = new java.util.ArrayList<String>();

        if (SshKeyManager.exists()) {
            try {
                keys.add(SshKeyManager.publicKeyContent());
            } catch (Exception ignored) {}
        }

        var home = System.getProperty("user.home");
        for (var keyName : List.of("id_ed25519.pub", "id_ecdsa.pub", "id_rsa.pub")) {
            var candidate = Path.of(home, ".ssh", keyName);
            if (Files.exists(candidate)) {
                try {
                    var personalKey = Files.readString(candidate).strip();
                    if (!keys.contains(personalKey)) {
                        keys.add(personalKey);
                    }
                } catch (IOException ignored) {}
                break;
            }
        }

        if (keys.isEmpty()) {
            System.out.println("  SSH is available but no public key found");
            System.out.println("  Add your key manually: ssh-copy-id agentuser@<container-ip>");
            return;
        }

        try {
            var tmpKey = Files.createTempFile("isx-ssh-", ".pub");
            try {
                Files.writeString(tmpKey, String.join("\n", keys) + "\n");
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

        // Harvest host key and configure ssh alias
        boolean hostConfigured = false;
        if (SshKeyManager.exists()) {
            try {
                hostConfigured = SshKeyManager.harvestHostKey(incus, name);
            } catch (Exception e) {
                System.err.println("  Warning: failed to harvest SSH host key: " + e.getMessage());
            }
        }

        var ipResult = incus.shellExec(name, "hostname", "-I");
        if (ipResult.success()) {
            var ip = ipResult.stdout().strip().split("\\s+")[0];
            if (hostConfigured && includeConfigured) {
                System.out.println("  SSH access: ssh " + name);
            } else if (SshKeyManager.exists()) {
                System.out.println("  SSH access: ssh -i ~/.config/incus-spawn/ssh/id_ed25519 agentuser@" + ip);
            } else {
                System.out.println("  SSH access: ssh agentuser@" + ip);
            }
        }
    }

    public static String getUid() {
        try {
            var pb = new ProcessBuilder("id", "-u");
            var p = pb.start();
            var output = new String(p.getInputStream().readAllBytes()).strip();
            int exitCode = p.waitFor();
            if (exitCode != 0 || output.isEmpty() || !output.chars().allMatch(Character::isDigit)) {
                return "1000";
            }
            return output;
        } catch (Exception e) {
            return "1000";
        }
    }
}
