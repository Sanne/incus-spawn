package dev.incusspawn.lifecycle;

import io.hyperfoil.tools.jjq.value.JqValue;
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
import dev.incusspawn.ssh.HostKeys;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Output;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Post-start setup of a new instance: the config prefetched before its start, the setup script,
 * and SSH. Split out of {@link InstanceLifecycle}, which calls it.
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
        BuildOutput.stepStart(machineType == MachineType.VM ? "Starting VM" : "Starting container");
        // The bound output, like the rest of BranchFlow's: the TUI branches on the released terminal
        // and copies its warnings into its log, since the shell after it draws over them.
        InstanceLifecycle.startInstance(incus, name, BuildOutput.bound());
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
        var hasSshKeys = hasSshCapability(config.getOrDefault(Metadata.SSH_SETUP, ""), buildSourceJson);
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
                                String templateName, JqValue instance) {

        public IncusClient.ShellPrep toShellPrep() {
            return IncusClient.ShellPrep.fromPrefetched(
                    workdir, shellCommand, buildSourceJson, subnetDiagnostic,
                    terminfo != null, templateName);
        }
    }

    /**
     * Post-start setup: home ownership, SSH keys. A proxy-only instance's network is restricted
     * on the host before it starts ({@link ProxyOnlyAcl}), so nothing here restricts it.
     * GUI and the inbox are NOT handled here — they must be configured before start.
     *
     * @param prefetched config read before start to avoid seccomp lock contention
     * @param secret     the instance secret to put in place (#934), or null for none
     * @param mcpCaller  whether the instance holds the {@code mcp-caller} grant, which the same
     *                   exec reconciles its Claude Code registration with (#1182)
     * @param placeholders the variables to give the secret's proof tokens in (#1106)
     * @param out        where the wait is shown, and a setup that may be incomplete or an SSH entry
     *                   that failed is reported, rather than bare stderr: the TUI keeps a branch's
     *                   in its log (#1646)
     */
    public static void setupRuntime(IncusClient incus, String name,
                                   NetworkMode networkMode, RuntimeConfig prefetched, InstanceSecret.Delivery secret,
                                   boolean mcpCaller, List<ProofToken.Placeholder> placeholders,
                                   Output out) {
        // Build a single setup script that handles readiness polling, home
        // ownership, terminfo, and tool readiness — all in one exec call.
        // Each additional exec round trip blocks for seconds due to
        // seccomp_notify lock contention during container startup.
        var sshKeys = prefetched.hasSshKeys() ? sshKeysToInject() : List.<String>of();
        var setupScript = buildSetupScript(prefetched, prefetched.buildSourceJson(), networkMode, sshKeys, secret != null);
        out.stepStart("Waiting for container");
        var env = secret != null ? InstanceSecret.guestEnv(secret, mcpCaller, placeholders) : Map.<String, String>of();
        var answer = incus.pollUntilReadyOutput(name, 30, env, "sh", "-c", setupScript);
        if (answer == null) {
            out.stepBreak();
            out.warn("Container setup may not be complete.");
        } else {
            out.stepDone(null);
        }

        configureSshHostEntry(name, prefetched.hasSshKeys(), prefetched.staticIp(), HostKeys.parse(answer), out);
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
        // The static .network config is pushed into the stopped container before start
        // (see configureBranch). A container applies it first thing rather than waiting ~200 ms
        // for networkd, which is ordered after udev, to come up and apply it (#801); a VM's
        // address is DHCP's until then, and there already. Airgap branches have no NIC.
        if (networkMode != NetworkMode.AIRGAP) sb.append(InstanceNetwork.APPLY_STATIC_ADDRESS).append('\n');
        if (prefetched != null && prefetched.terminfo() != null) {
            sb.append(Container.heredoc("tic -x - 2>/dev/null", prefetched.terminfo())).append('\n');
        }
        if (deliverSecret) sb.append(InstanceSecret.GUEST_SCRIPT).append('\n');
        // Best-effort like the other steps: only the readiness checks below decide whether
        // the script succeeded, which is what pollUntilReady retries on.
        sb.append("chown agentuser:agentuser /home/agentuser || true");
        if (!sshKeys.isEmpty()) {
            // As agentuser, so after the home is theirs: a branch of an instance copies the ~/.ssh
            // agentuser owns, so a symlink there must not steer a root write (#1698). The closing
            // true takes the next step's separator, which may not follow a heredoc's end.
            sb.append('\n').append(Container.heredoc(asAgentuser(WRITE_KEYS), String.join("\n", sshKeys)))
              .append("\ntrue");
            // The host keys to pin (#1863), on the answer this exec gives anyway. Before the
            // checks below, which decide whether the script succeeded.
            sb.append('\n').append(HostKeys.GUEST_SCRIPT);
        }
        // Confirm the address is up, polled every 50 ms, since a coarser interval is paid in
        // full by every branch that waits. Airgap branches have no NIC, so the wait would always
        // time out — skip it.
        if (networkMode != NetworkMode.AIRGAP) {
            var addressUp = addressUpCheck(prefetched != null ? prefetched.staticIp() : null);
            sb.append("\n{ ")
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

        var keys = acceptedKeys();
        if (keys.isEmpty()) {
            BuildOutput.step("SSH is available but no public key found.");
        }
        return keys;
    }

    /**
     * The isx-managed key and the user's own default key, those that exist, read without creating
     * either and without a word: the start path, which the TUI reaches, may not print.
     */
    static List<String> acceptedKeys() {
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
                    if (!personalKey.isEmpty() && !keys.contains(personalKey)) {
                        keys.add(personalKey);
                    }
                } catch (IOException ignored) {}
                break;
            }
        }
        return keys;
    }

    /**
     * What a start for use of an SSH-capable instance adds to the guest script its readiness probe
     * runs (#1640): the accepted keys, so a {@code --no-start} branch is reachable after its first
     * start as a started branch is, and sshd's host keys, printed for the probe's answer to pin
     * (#1863). Empty when there are none, when no key isx holds logs in.
     */
    static String sshStartScript() {
        var keys = acceptedKeys();
        return keys.isEmpty() ? "" : "\n" + authorizeKeysScript(keys) + "\n" + HostKeys.GUEST_SCRIPT;
    }

    /**
     * Shell setting {@code b} to the base64 blob of the key line in {@code k}: a key is the same
     * key whatever its comment or options, so a user's {@code from="..."} copy of one counts.
     */
    private static final String KEY_BLOB = "b=${k#* }; b=${b%% *};";

    /** Shell, run as agentuser with its home as {@code $1}, that sets {@code f} and makes a 0700 {@code ~/.ssh}. */
    private static final String KEYS_DIR = "d=\"$1/.ssh\"; f=\"$d/authorized_keys\"; umask 077;"
            + " mkdir -p \"$d\" && chmod 700 \"$d\"";

    /**
     * Shell, run as agentuser with its home as {@code $1}, that makes {@code authorized_keys} hold
     * exactly the keys on its stdin, 0600: a branch's keys replace what its source held. A file
     * agentuser does not own, which root's write used to repair, is removed first, as agentuser
     * may in its own {@code ~/.ssh}.
     */
    static final String WRITE_KEYS = KEYS_DIR + " && { [ ! -e \"$f\" ] || [ -O \"$f\" ] || rm -f \"$f\"; }"
            + " && cat > \"$f\" && chmod 600 \"$f\"";

    /**
     * Shell, run as agentuser with its home as {@code $1}, that adds each key on its stdin which
     * {@code authorized_keys} lacks, keeping what it already accepts: unlike a branch, whose file
     * {@link #WRITE_KEYS} writes whole, a started instance may hold keys its user added.
     */
    static final String APPEND_KEYS = KEYS_DIR + ";"
            + " [ -s $f ] && [ -n \"$(tail -c1 $f)\" ] && echo >> $f;"
            + " while IFS= read -r k; do " + KEY_BLOB + " [ -n \"$b\" ] && ! " + activeLinesHold("$f")
            + " && printf \"%s\\n\" \"$k\" >> $f; done; chmod 600 $f";

    /**
     * Shell running {@code script} on its stdin as agentuser, never as root, with its home as
     * {@code $1}. Bounded, since agentuser controls the file the script writes and may make it a
     * FIFO. Its environment is cleared, since agentuser can read it while it runs: nothing the exec
     * carries reaches agentuser, whatever a later step adds or forgets to unset. Best-effort and
     * quiet, like the steps around it.
     */
    private static String asAgentuser(String script) {
        return "(cd /home/agentuser && timeout 10 runuser -u agentuser -- env -i HOME=/home/agentuser"
                + " PATH=/usr/bin:/bin sh -c '" + script + "' isx-keys /home/agentuser) >/dev/null 2>&1";
    }

    /**
     * Shell, run as root on the keys on its stdin, that succeeds only when {@code file} holds every
     * one on a line that is not commented out. agentuser controls that file, so the read is
     * bounded in time: a huge line or a FIFO swapped in fails it, which counts as a key missing and
     * leaves the decision to {@link #APPEND_KEYS}, under its own bound.
     */
    static String keysPresentCheck(String file) {
        return "timeout -k 1 2 sh -c 'while IFS= read -r k; do " + KEY_BLOB
                + " [ -z \"$b\" ] || " + activeLinesHold("\"$1\"") + " || exit 1; done' isx-check " + file;
    }

    /** Shell testing whether a line of {@code file} that is not a comment holds the key blob {@code $b}. */
    private static String activeLinesHold(String file) {
        return "grep -v \"^[[:space:]]*#\" " + file + " 2>/dev/null | grep -qF -- \"$b\"";
    }

    /**
     * Add {@code keys} to agentuser's {@code authorized_keys} ({@link #APPEND_KEYS}). As agentuser,
     * never as root: the user owns that directory, and a symlink there must not steer a root write
     * (#1640). Root first only reads, under a time bound ({@link #keysPresentCheck}), to skip the
     * user switch on the usual start, where every key is already there. It skips a symlinked
     * {@code ~/.ssh} or file and anything but a regular file. agentuser can still swap one in
     * between that test and the read, which then only tells root whether another file holds a
     * public key, within the same bound. Idempotent and never fails, as a readiness probe's script
     * must be.
     */
    static String authorizeKeysScript(List<String> keys) {
        var d = "/home/agentuser/.ssh";
        var f = d + "/authorized_keys";
        return "{ [ -d /home/agentuser ] && { " + Container.heredoc("keys=$(cat", String.join("\n", keys)) + "\n);"
                + " { [ ! -L " + d + " ] && [ ! -L " + f + " ] && [ -f " + f + " ]"
                + " && printf '%s\\n' \"$keys\" | " + keysPresentCheck(f) + "; }"
                + " || printf '%s\\n' \"$keys\" | " + asAgentuser(APPEND_KEYS) + "; }; } >/dev/null 2>&1; true";
    }

    /**
     * Give a started SSH-capable instance its {@code Host} entry if it has none, or one for an
     * address it no longer has (#1640), and pin the host keys its probe reported (#1863). Local
     * file reads when both are current, so the start path pays nothing on every later start; the
     * address is the one {@code configureBranch} stamped, so it costs no request either.
     *
     * @param probeAnswer what the readiness probe's guest script printed ({@link HostKeys#parse});
     *     null or without keys pins nothing, and the block still works for a client that does not
     *     insist on checking
     */
    static void ensureSshHostEntry(String name, JqValue instance, String probeAnswer) {
        if (!SshKeyManager.exists()) return;
        var address = instance.path("config").pathText(Metadata.STATIC_IP, "");
        // The pin first, so a strict client never meets a block whose key is not there yet
        SshKeyManager.pinHostKeys(name, address, HostKeys.parse(probeAnswer));
        if (!SshKeyManager.hasHostEntry(name, address)) {
            SshKeyManager.ensureSshConfigInclude();
            SshKeyManager.addHostEntry(name, address);
        }
        // herdr's machine targets the start alias (#2038), whose block may be lost or stale with the
        // instance's or alone. Only for an instance herdr reaches that way: one record read, then the
        // config read a current block costs
        if (HostIntegration.anyUsesStartAlias(name) && !SshKeyManager.hasStartEntry(name)) {
            SshKeyManager.addStartEntry(name);
        }
    }

    /**
     * Configure the SSH host entry with Hostname directive, and pin the host keys the setup
     * reported (#1863).
     *
     * @param staticIp the address {@code configureBranch} stamped, or "" for none (airgapped): the
     *     block's {@code Hostname} and the pin both name it, as on a start
     *     ({@link #ensureSshHostEntry}), so this costs no request. Never one the guest reports,
     *     since its interfaces are its own and pinning an address takes it off every other
     *     instance's line.
     */
    static void configureSshHostEntry(String name, boolean hasSsh, String staticIp,
                                      List<HostKeys.HostKey> hostKeys, Output out) {
        if (!SshKeyManager.exists()) return;
        if (!hasSsh) return;

        boolean includeConfigured = SshKeyManager.ensureSshConfigInclude();
        boolean hostConfigured = false;
        try {
            SshKeyManager.pinHostKeys(name, staticIp, hostKeys);
            hostConfigured = SshKeyManager.addHostEntry(name, staticIp);
        } catch (RuntimeException e) {
            out.warn("Failed to configure SSH host entry: " + e.getMessage());
        }

        if (hostConfigured && includeConfigured) {
            BuildOutput.step("SSH access: ssh " + name);
        } else if (hostConfigured) {
            BuildOutput.step("SSH access: ssh -F ~/.config/incus-spawn/ssh/config " + name);
        } else {
            BuildOutput.step("SSH is available — connect with: isx shell " + name);
        }
    }

    /** {@link #hasSshCapability(String, String)} from the instance the caller already read; false for null. */
    public static boolean hasSshCapability(JqValue instance) {
        if (instance == null) return false;
        var config = instance.path("config");
        return hasSshCapability(config.pathText(Metadata.SSH_SETUP, ""), config.pathText(Metadata.BUILD_SOURCE, ""));
    }

    /**
     * Whether an instance was built with SSH capability (sshd tool or explicit ssh-setup
     * config), from config values the caller already read. Used to avoid advertising SSH
     * access for containers that don't have sshd installed.
     */
    public static boolean hasSshCapability(String sshSetup, String buildSourceJson) {
        return !sshSetup.isEmpty() || hasSshdTool(buildSourceJson);
    }

    static boolean hasSshdTool(String buildSourceJson) {
        return BuildSource.installs(buildSourceJson, "sshd");
    }
}
