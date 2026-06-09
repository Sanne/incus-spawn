package dev.incusspawn.lifecycle;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.git.AutoRemoteService;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.MitmProxy;
import dev.incusspawn.ssh.SshKeyManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Shared helpers for instance/template creation lifecycle.
 * Eliminates duplication between BranchCommand and ListCommand.
 */
public final class InstanceLifecycle {

    private InstanceLifecycle() {}

    public static void applyResourceLimits(IncusClient incus, String name,
                                          String cpu, String memory, String disk) {
        incus.configSetAll(name, Map.of("limits.cpu", cpu, "limits.memory", memory));
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
        incus.configSetAll(name, Map.of(
                Metadata.TYPE, type,
                Metadata.PARENT, parent,
                Metadata.CREATED, Metadata.today()));
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
     * Pre-fetch instance metadata that setupRuntime needs, while the container
     * is still stopped. Reading config from a stopped container avoids lock
     * contention with the seccomp_notify handler that activates on start.
     */
    public static RuntimeConfig prefetchRuntimeConfig(IncusClient incus, String name) {
        var buildSourceJson = incus.configGet(name, Metadata.BUILD_SOURCE);
        var hasSshKeys = !incus.configGet(name, "user.incus-spawn.ssh-setup").isEmpty()
                || buildSourceJson.contains("\"ssh\"");
        var workdir = incus.configGet(name, Metadata.WORKDIR);
        var shellCommand = incus.configGet(name, Metadata.SHELL_COMMAND);
        var subnetDiag = BridgeSubnetCheck.detectConflictDiagnostic(incus);
        var terminfo = captureHostTerminfo();
        return new RuntimeConfig(buildSourceJson, hasSshKeys, workdir, shellCommand,
                subnetDiag, terminfo);
    }

    private static String captureHostTerminfo() {
        var term = System.getenv("TERM");
        if (term == null || term.isEmpty()) return null;
        try {
            var pb = new ProcessBuilder("infocmp", "-x", term);
            pb.redirectErrorStream(true);
            var proc = pb.start();
            var output = new String(proc.getInputStream().readAllBytes()).strip();
            return proc.waitFor() == 0 && !output.isEmpty() ? output : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Push terminfo source into a stopped container so it can be compiled
     * during setupRuntime without separate exec calls.
     */
    public static void pushTerminfoIfNeeded(IncusClient incus, String name, String terminfo) {
        if (terminfo == null) return;
        try {
            var tmp = Files.createTempFile("isx-terminfo-", ".src");
            try {
                Files.writeString(tmp, terminfo + "\n");
                incus.filePush(tmp.toString(), name, "/tmp/.isx-terminfo.src");
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException e) {
            // best-effort — propagateTerminfo in interactiveShell is the fallback
        }
    }

    public record RuntimeConfig(String buildSourceJson, boolean hasSshKeys,
                                String workdir, String shellCommand,
                                String subnetDiagnostic, String terminfo) {

        public IncusClient.ShellPrep toShellPrep() {
            return IncusClient.ShellPrep.fromPrefetched(
                    workdir, shellCommand, buildSourceJson, subnetDiagnostic,
                    terminfo != null);
        }
    }

    /**
     * Post-start setup: firewall, inbox, home ownership, SSH keys.
     * GUI is NOT handled here — it must be configured before start.
     *
     * @param prefetched config read before start to avoid seccomp lock contention;
     *                   if null, config is read live (slower on macOS)
     */
    public static void setupRuntime(IncusClient incus, String name,
                                   NetworkMode networkMode, Path inboxPath,
                                   RuntimeConfig prefetched) {
        if (networkMode == NetworkMode.PROXY_ONLY) {
            applyProxyOnlyFirewall(incus, name);
        }

        if (inboxPath != null) {
            if (java.nio.file.Files.isDirectory(inboxPath)) {
                System.out.println("Mounting inbox: " + inboxPath.toAbsolutePath() +
                        " -> /home/agentuser/inbox (read-only)");
                incus.deviceAdd(name, "inbox", "disk",
                        "source=" + dev.incusspawn.config.HostResourceSetup.translateForVm(
                                inboxPath.toAbsolutePath().toString()),
                        "path=/home/agentuser/inbox",
                        "readonly=true");
            } else {
                System.err.println("Warning: inbox path '" + inboxPath +
                        "' is not a directory, skipping.");
            }
        }

        // Build a single setup script that handles readiness polling, home
        // ownership, terminfo, and tool readiness — all in one exec call.
        // Each additional exec round trip blocks for seconds due to
        // seccomp_notify lock contention during container startup.
        var buildSourceJson = prefetched != null ? prefetched.buildSourceJson()
                : incus.configGet(name, Metadata.BUILD_SOURCE);
        var setupScript = buildSetupScript(prefetched, buildSourceJson);
        System.out.println("Waiting for container...");
        if (!incus.pollUntilReady(name, 30, "sh", "-c", setupScript)) {
            System.err.println("Warning: container setup may not be complete.");
        }

        if (prefetched == null) {
            System.out.println("Configuring SSH access...");
            injectSshKeyIfAvailable(incus, name, null);
        }
    }

    public static void setupRuntime(IncusClient incus, String name,
                                   NetworkMode networkMode, Path inboxPath) {
        setupRuntime(incus, name, networkMode, inboxPath, null);
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

        incus.shellExec(name, "sh", "-c", String.join(" && ",
                "iptables -A OUTPUT -o lo -j ACCEPT",
                "iptables -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT",
                "iptables -A OUTPUT -d " + gatewayIp + " -p tcp --dport " + mitmPort + " -j ACCEPT",
                "iptables -A OUTPUT -d " + gatewayIp + " -p tcp --dport " + healthPort + " -j ACCEPT",
                "iptables -A OUTPUT -d " + gatewayIp + " -p udp --dport 53 -j ACCEPT",
                "iptables -P OUTPUT DROP"));

        System.out.println("  Outbound traffic restricted to " + gatewayIp +
                " ports " + mitmPort + " (MITM), " + healthPort + " (health), 53 (DNS)");
    }

    public static void awaitToolReadiness(IncusClient incus, String name, String buildSourceJson) {
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

    /**
     * Build a shell script that performs all post-start setup in one exec:
     * home ownership, terminfo compilation, and tool readiness polling.
     * Batching avoids multiple exec round trips that each block due to
     * seccomp_notify lock contention during container startup.
     */
    static String buildSetupScript(RuntimeConfig prefetched, String buildSourceJson) {
        var sb = new StringBuilder();
        sb.append("chown agentuser:agentuser /home/agentuser");
        if (prefetched != null && prefetched.terminfo() != null) {
            sb.append("; tic -x /tmp/.isx-terminfo.src 2>/dev/null; rm -f /tmp/.isx-terminfo.src");
        }
        var buildSource = BuildSource.fromJson(buildSourceJson);
        if (buildSource != null) {
            for (var tool : buildSource.getTools().values()) {
                if (tool.getReady() == null || tool.getReady().isBlank()) continue;
                sb.append("; i=0; while ! (").append(tool.getReady())
                  .append(") >/dev/null 2>&1; do i=$((i+1)); [ $i -ge 75 ] && break; sleep 0.2; done");
            }
        }
        return sb.toString();
    }

    /**
     * @param hasSshKeys pre-fetched from stopped container config; null to check live
     */
    public static void injectSshKeyIfAvailable(IncusClient incus, String name, Boolean hasSshKeys) {
        if (hasSshKeys != null) {
            if (!hasSshKeys) return;
        } else {
            var check = incus.shellExec(name, "test", "-f", "/home/agentuser/.ssh/authorized_keys");
            if (!check.success()) return;
        }

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
                // Push with agentuser ownership (uid=1000) and mode 0600 directly,
                // avoiding a separate chown+chmod exec round trip
                incus.filePush(tmpKey.toString(), name, "/home/agentuser/.ssh/authorized_keys",
                        "1000", "1000", "0600");
            } finally {
                Files.deleteIfExists(tmpKey);
            }
        } catch (IOException e) {
            System.err.println("  Warning: failed to inject SSH key: " + e.getMessage());
            return;
        }

        // Configure SSH ProxyCommand entry for seamless access
        boolean hostConfigured = false;
        if (SshKeyManager.exists()) {
            try {
                hostConfigured = SshKeyManager.addHostEntry(name);
            } catch (Exception e) {
                System.err.println("  Warning: failed to configure SSH host entry: " + e.getMessage());
            }
        }

        if (hostConfigured && includeConfigured) {
            System.out.println("  SSH access: ssh " + name);
        } else if (hostConfigured) {
            System.out.println("  SSH access: ssh -F ~/.config/incus-spawn/ssh/config " + name);
        } else {
            System.out.println("  SSH is available — connect with: isx shell " + name);
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
