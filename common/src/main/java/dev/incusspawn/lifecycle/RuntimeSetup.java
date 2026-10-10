package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.CidrUtils;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.ProofToken;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.util.BuildOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Post-start setup of a new instance: the config prefetched before its start, the setup script,
 * the proxy-only firewall and SSH. Split out of {@link InstanceLifecycle}, which calls it.
 */
public final class RuntimeSetup {

    private RuntimeSetup() {}

    /**
     * Read what {@link #setupRuntime} needs while the new instance is still stopped, then
     * start it. Used by {@link BranchFlow}, behind both {@code isx branch} and the TUI.
     *
     * <p>Nothing is pushed into the instance between the two: Incus stops its forkfile file
     * server on start, and one still finishing a push makes the start wait a full second.
     * Anything the instance needs goes into the post-start setup script instead -- its secret
     * included.
     */
    public static RuntimeConfig prefetchAndStart(IncusClient incus, String name, MachineType machineType) {
        var prefetched = prefetchRuntimeConfig(incus, name);
        BuildOutput.stepStart(machineType == MachineType.VM ? "Starting VM..." : "Starting container...");
        // Plain stderr for the fallback warning, like the rest of BranchFlow's output: the TUI
        // branches only as a pendingAction, after its runner has released the terminal.
        InstanceLifecycle.startInstance(incus, name);
        BuildOutput.stepDone();
        return prefetched;
    }

    /**
     * Pre-fetch instance metadata that setupRuntime needs, while the container
     * is still stopped. Reading config from a stopped container avoids lock
     * contention with the seccomp_notify handler that activates on start.
     */
    public static RuntimeConfig prefetchRuntimeConfig(IncusClient incus, String name) {
        // One read for all four keys: configGet is a full instance GET per key. Kept, for the
        // account reconcile once the instance is up.
        var instance = incus.instanceMetadataOrThrow(name);
        if (instance == null) throw new IncusException("Failed to read config from " + name);
        var config = IncusClient.configByPrefix(instance, "");
        var buildSourceJson = config.getOrDefault(Metadata.BUILD_SOURCE, "");
        var hasSshKeys = !config.getOrDefault("user.incus-spawn.ssh-setup", "").isEmpty()
                || hasSshdTool(buildSourceJson);
        var workdir = config.getOrDefault(Metadata.WORKDIR, "");
        var shellCommand = config.getOrDefault(Metadata.SHELL_COMMAND, "");
        var subnetDiag = BridgeSubnetCheck.detectConflictDiagnostic(incus);
        var terminfo = captureHostTerminfo();
        return new RuntimeConfig(buildSourceJson, hasSshKeys, workdir, shellCommand,
                subnetDiag, terminfo, config.getOrDefault(Metadata.STATIC_IP, ""),
                Metadata.templateOf(config), instance);
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
     * @param staticIp the address {@code configureBranch} assigned, or "" for none
     * @param instance the instance as read before the start, or null
     */
    public record RuntimeConfig(String buildSourceJson, boolean hasSshKeys,
                                String workdir, String shellCommand,
                                String subnetDiagnostic, String terminfo, String staticIp,
                                String templateName, JsonNode instance) {

        public IncusClient.ShellPrep toShellPrep() {
            return IncusClient.ShellPrep.fromPrefetched(
                    workdir, shellCommand, buildSourceJson, subnetDiagnostic,
                    terminfo != null, templateName);
        }
    }

    /**
     * Post-start setup: firewall, home ownership, SSH keys.
     * GUI and the inbox are NOT handled here — they must be configured before start.
     *
     * @param prefetched config read before start to avoid seccomp lock contention;
     *                   if null, config is read live (slower on macOS)
     * @param secret     the instance secret to put in place (#934), or null for none
     * @param mcpCaller  whether the instance holds the {@code mcp-caller} grant, which the same
     *                   exec reconciles its Claude Code registration with (#1182)
     * @param placeholders the variables to give the secret's proof tokens in (#1106)
     */
    public static void setupRuntime(IncusClient incus, String name,
                                   NetworkMode networkMode, RuntimeConfig prefetched, String secret,
                                   boolean mcpCaller, List<ProofToken.Placeholder> placeholders) {
        if (networkMode == NetworkMode.PROXY_ONLY) {
            applyProxyOnlyFirewall(incus, name);
        }

        // Build a single setup script that handles readiness polling, home
        // ownership, terminfo, and tool readiness — all in one exec call.
        // Each additional exec round trip blocks for seconds due to
        // seccomp_notify lock contention during container startup.
        var buildSourceJson = prefetched != null ? prefetched.buildSourceJson()
                : incus.configGet(name, Metadata.BUILD_SOURCE);
        var sshKeys = prefetched != null && prefetched.hasSshKeys() ? sshKeysToInject() : List.<String>of();
        var setupScript = buildSetupScript(prefetched, buildSourceJson, networkMode, sshKeys, secret != null);
        BuildOutput.stepStart("Waiting for container...");
        var env = secret != null ? InstanceSecret.guestEnv(secret, mcpCaller, placeholders) : Map.<String, String>of();
        if (!incus.pollUntilReady(name, 30, env, "sh", "-c", setupScript)) {
            BuildOutput.stepBreak();
            System.err.println(BuildOutput.STEP_INDENT + "Warning: container setup may not be complete.");
        } else {
            BuildOutput.stepDone();
        }

        boolean sshCapable;
        if (prefetched != null) {
            sshCapable = prefetched.hasSshKeys();
        } else {
            sshCapable = hasSshCapability(incus, name);
            if (sshCapable) {
                injectSshKeyIfAvailable(incus, name, null);
            }
        }

        configureSshHostEntry(incus, name, sshCapable);
    }

    public static void setupRuntime(IncusClient incus, String name, NetworkMode networkMode) {
        setupRuntime(incus, name, networkMode, null, null, false, List.of());
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

        var mitmPort = ProxyConfig.CONTAINER_FACING_PORT;
        var healthPort = ProxyConfig.DEFAULT_HEALTH_PORT;

        BuildOutput.stepStart("Applying proxy-only firewall rules...");

        incus.shellExec(name, "sh", "-c", String.join(" && ",
                "iptables -A OUTPUT -o lo -j ACCEPT",
                "iptables -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT",
                "iptables -A OUTPUT -d " + gatewayIp + " -p tcp --dport " + mitmPort + " -j ACCEPT",
                "iptables -A OUTPUT -d " + gatewayIp + " -p tcp --dport " + healthPort + " -j ACCEPT",
                "iptables -A OUTPUT -d " + gatewayIp + " -p udp --dport 53 -j ACCEPT",
                // Allow ICMP echo to the gateway so the connectivity watchdog's
                // `ping $GATEWAY` reachability check works here too — otherwise it
                // would see the gateway as unreachable and restart networkd every 30s.
                "iptables -A OUTPUT -d " + gatewayIp + " -p icmp --icmp-type echo-request -j ACCEPT",
                "iptables -P OUTPUT DROP"));

        BuildOutput.stepDone();
    }

    public static void awaitToolReadiness(IncusClient incus, String name, String buildSourceJson) {
        var buildSource = BuildSource.fromJson(buildSourceJson);
        if (buildSource == null) return;

        for (var tool : buildSource.getTools().values()) {
            if (tool.getReady() == null || tool.getReady().isBlank()) continue;
            var toolName = tool.getName();
            BuildOutput.stepStart("Waiting for " + toolName + "...");
            if (!incus.pollUntilReady(name, 15, "sh", "-c", tool.getReady())) {
                BuildOutput.stepBreak();
                System.err.println(BuildOutput.STEP_INDENT + "Warning: " + toolName + " did not become ready in time.");
            } else {
                BuildOutput.stepDone();
            }
        }
    }

    /**
     * Build a shell script that performs all post-start setup in one exec: SSH keys, terminfo,
     * the instance secret, home ownership, network readiness and tool readiness. Batching avoids multiple exec round
     * trips that each block due to seccomp_notify lock contention during container startup.
     *
     * <p>SSH keys and terminfo travel inside the script rather than being pushed into the
     * stopped instance beforehand. A start stops Incus's forkfile helper (which serves file
     * pushes to a stopped instance) and, if a push is still finishing, forkfile re-checks only
     * once a second -- so a push just before the start cost a full second of every branch.
     *
     * <p>The script may run more than once ({@code pollUntilReady} retries it), so every step
     * is idempotent.
     */
    static String buildSetupScript(RuntimeConfig prefetched, String buildSourceJson,
                                   NetworkMode networkMode, List<String> sshKeys) {
        return buildSetupScript(prefetched, buildSourceJson, networkMode, sshKeys, false);
    }

    /** @param deliverSecret whether the script puts in place the secret its exec environment carries */
    static String buildSetupScript(RuntimeConfig prefetched, String buildSourceJson,
                                   NetworkMode networkMode, List<String> sshKeys, boolean deliverSecret) {
        var sb = new StringBuilder();
        if (!sshKeys.isEmpty()) {
            // Same result as the file push this replaces: agentuser (uid 1000) owns it, 0600.
            sb.append("install -d -m 700 -o 1000 -g 1000 /home/agentuser/.ssh\n")
              .append(Container.heredoc("(umask 077 && cat > /home/agentuser/.ssh/authorized_keys)",
                      String.join("\n", sshKeys)))
              .append("\nchown 1000:1000 /home/agentuser/.ssh/authorized_keys"
                      + " && chmod 600 /home/agentuser/.ssh/authorized_keys\n");
        }
        if (prefetched != null && prefetched.terminfo() != null) {
            sb.append(Container.heredoc("tic -x - 2>/dev/null", prefetched.terminfo())).append('\n');
        }
        if (deliverSecret) sb.append(InstanceSecret.GUEST_SCRIPT).append('\n');
        // Best-effort like the steps above: only the readiness checks below decide whether
        // the script succeeded, which is what pollUntilReady retries on.
        sb.append("chown agentuser:agentuser /home/agentuser || true");
        // The static .network config is pushed into the stopped container before start
        // (see configureBranch), so the interface comes up immediately at boot — no DHCP
        // wait. Here we only ensure the service is running and confirm the address is up.
        // Polled every 50 ms: the address usually appears within a few hundred ms of start,
        // and a coarser interval is paid in full by every branch. Airgap branches have no
        // NIC, so the wait would always time out — skip it.
        if (networkMode != NetworkMode.AIRGAP) {
            var addressUp = addressUpCheck(prefetched != null ? prefetched.staticIp() : null);
            sb.append("\n{ systemctl start systemd-networkd 2>/dev/null; ")
              .append("for i in $(seq 1 300); do ").append(addressUp).append(" && break; sleep 0.05; done; ")
              .append(addressUp).append("; }");
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
     * The setup script's test for the instance's network being up. It looks for the address,
     * not at an interface: a VM's NIC keeps its kernel's predictable name rather than eth0
     * (#997), and the assigned address is the one the proxy identifies the instance by, where
     * any address would also take a nested docker0's.
     */
    static String addressUpCheck(String staticIp) {
        try {
            // Normalized, so only digits and dots reach the shell
            var ip = CidrUtils.longToIp(CidrUtils.ipToLong(staticIp));
            return "ip -4 -o addr show | grep -qF ' inet " + ip + "/'";
        } catch (RuntimeException noAddress) {
            // Nothing to look for: a default route, which a nested docker0 does not add
            return "ip -4 route show default | grep -q .";
        }
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

        var keys = sshKeysToInject();
        if (keys.isEmpty()) return;

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
            System.err.println(BuildOutput.STEP_INDENT + "Warning: failed to inject SSH key: " + e.getMessage());
            return;
        }
    }

    /**
     * The public keys an SSH-capable instance should accept: the isx-managed key (created on
     * first use) plus the user's own default key, if any. Host-side only; empty, with a
     * notice, when there is none.
     */
    static List<String> sshKeysToInject() {
        // Ensure managed key infrastructure exists (creates lazily for pre-existing installs)
        try {
            if (!SshKeyManager.exists()) {
                SshKeyManager.ensureKeyPairExists();
            }
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
            BuildOutput.step("SSH is available but no public key found.");
        }
        return keys;
    }

    /**
     * Configure the SSH host entry with Hostname directive. Must be called after
     * the container is started so the IPv4 address is available.
     */
    public static void configureSshHostEntry(IncusClient incus, String name) {
        configureSshHostEntry(incus, name, hasSshCapability(incus, name));
    }

    static void configureSshHostEntry(IncusClient incus, String name, boolean hasSsh) {
        if (!SshKeyManager.exists()) return;
        if (!hasSsh) return;

        boolean includeConfigured = SshKeyManager.ensureSshConfigInclude();
        boolean hostConfigured = false;
        try {
            var ipv4 = incus.getContainerIpv4(name);
            hostConfigured = SshKeyManager.addHostEntry(name, ipv4);
        } catch (Exception e) {
            System.err.println(BuildOutput.STEP_INDENT + "Warning: failed to configure SSH host entry: " + e.getMessage());
        }

        if (hostConfigured && includeConfigured) {
            BuildOutput.step("SSH access: ssh " + name);
        } else if (hostConfigured) {
            BuildOutput.step("SSH access: ssh -F ~/.config/incus-spawn/ssh/config " + name);
        } else {
            BuildOutput.step("SSH is available — connect with: isx shell " + name);
        }
    }

    /**
     * Check whether an instance was built with SSH capability (sshd tool or
     * explicit ssh-setup config). Used to avoid advertising SSH access for
     * containers that don't have sshd installed.
     */
    public static boolean hasSshCapability(IncusClient incus, String name) {
        return !incus.configGet(name, "user.incus-spawn.ssh-setup").isEmpty()
                || hasSshdTool(incus.configGet(name, Metadata.BUILD_SOURCE));
    }

    static boolean hasSshdTool(String buildSourceJson) {
        var bs = BuildSource.fromJson(buildSourceJson);
        return bs != null && bs.getTools().containsKey("sshd");
    }
}
