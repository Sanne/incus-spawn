package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.AutoRemoteService;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.CidrUtils;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.InstanceUpdate;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.StaticIpAllocator;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.util.BuildOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helpers for instance/template creation lifecycle.
 * Eliminates duplication between BranchCommand and ListCommand.
 */
public final class InstanceLifecycle {

    private static final ObjectMapper JSON = new ObjectMapper();

    private InstanceLifecycle() {}

    /**
     * What {@link #configureBranch} sets on a fresh copy before it starts.
     *
     * @param cpu      {@code limits.cpu}; null or empty leaves the CPU count unlimited
     * @param parent   the instance or template the branch was copied from
     * @param accounts credential account pins to stamp, which clear any the copy carried that
     *                 they do not name; empty leaves the copied pins as they are
     * @param kvm      whether KVM passthrough is configured next; if not, KVM devices and
     *                 metadata inherited from the source are dropped
     */
    public record BranchSettings(String cpu, String memory, String disk, NetworkMode networkMode,
                                 String parent, Map<String, String> accounts, boolean kvm) {}

    /**
     * Configure a freshly copied branch before its first start, in one write to Incus.
     *
     * <p>Resource limits, the network mode, the static IP and its spoofing protection, the
     * branch metadata, the account pins and the removal of inherited KVM passthrough used to be
     * nine writes. Each costs Incus a rewrite of the instance's backup file, which is what made
     * them add up to ~110 ms of the time before start (#804). They are collected into one
     * {@link InstanceUpdate} against a single read of the instance instead: one PATCH, or one PUT
     * when an inherited KVM device has to go.
     *
     * <p>Airgap mode still detaches the NIC separately first; it is rare, and the detach has
     * to override a profile device before it can remove it.
     */
    public static void configureBranch(IncusClient incus, String name, BranchSettings settings) {
        var mode = settings.networkMode();
        if (mode == NetworkMode.AIRGAP) {
            BuildOutput.stepStart("Enabling network airgap...");
            incus.networkDetach(name, "incusbr0");
            BuildOutput.stepDone();
        }

        var instance = incus.instanceMetadata(name);
        if (instance.isMissingNode()) throw new IncusException("Failed to read instance " + name);
        var update = new InstanceUpdate();

        var cpu = settings.cpu();
        update.config("limits.cpu", cpu != null && !cpu.isEmpty() ? cpu : null);
        update.config("limits.memory", settings.memory());
        update.device("root", "size", settings.disk());

        String ip = null;
        String gateway = null;
        String nicDevice = null;
        if (mode != NetworkMode.AIRGAP) {
            gateway = ProxyConfig.resolveGatewayIp(incus);
            if (mode == NetworkMode.PROXY_ONLY) {
                BuildOutput.step("Configuring proxy-only network.");
                update.config(Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name());
                update.config(Metadata.PROXY_GATEWAY, gateway);
            }
            // A static IP, so no DHCP lease is ever acquired: leases expire across host
            // sleep/wake. See pushStaticNetworkConfig for the guest side.
            ip = StaticIpAllocator.allocate(incus);
            nicDevice = IncusClient.nicDeviceName(instance, "incusbr0");
            if (nicDevice == null) {
                throw new IncusException("No NIC device for incusbr0 found on " + name);
            }
            BuildOutput.step("Assigning static IP " + ip + ".");
            update.device(nicDevice, "ipv4.address", ip);
            update.device(nicDevice, "security.ipv4_filtering", "true");
            update.config(Metadata.STATIC_IP, ip);
            update.config(Metadata.STATIC_GATEWAY, gateway);
        }

        update.config(Metadata.TYPE, Metadata.TYPE_CLONE);
        update.config(Metadata.PARENT, settings.parent());
        update.config(Metadata.CREATED, Metadata.today());
        if (!settings.accounts().isEmpty()) {
            update.config(AccountSelection.stampUpdates(settings.accounts(),
                    AccountSelection.fromConfig(instance.path("config"))));
        }
        if (!settings.kvm()) KvmPassthrough.removeKvm(instance, update);

        // Pushed before the write rather than after, so the push is not the last thing before
        // the start: see "Why nothing is pushed into an instance just before it starts".
        if (ip != null && !"virtual-machine".equals(instance.path("type").asText(""))) {
            pushStaticNetworkConfig(incus, name, ip, gateway, bridgePrefixLen(incus));
        }

        try {
            incus.update(name, instance, update);
        } catch (IncusException e) {
            if (nicDevice == null) throw e;
            // Failing to pin the address is fatal, failing to enable filtering only warns, and
            // one write cannot say which of them Incus refused. Incus rolls a refused write back
            // whole, so retry without filtering: if that fails too, the error is the real one.
            // If it goes through, enable filtering on its own, so a first failure that had
            // nothing to do with filtering does not leave the instance without it.
            incus.update(name, instance,
                    update.withoutDeviceProperty(nicDevice, "security.ipv4_filtering"));
            applyIpFiltering(incus, name, nicDevice);
        }
    }

    /**
     * Re-derive anything the build baked from a credential account the instance is no longer
     * pinned to -- today, the git identity after {@code isx branch --account github=other} or
     * {@code isx account set}.
     *
     * <p>Runs after start, because re-deriving means asking the API through the proxy, which is
     * also what makes it correct: the proxy already knows which account this instance uses, so
     * the tool needs no argument beyond the account name to stamp back.
     *
     * <p>Called from {@code BranchCommand} so a freshly branched instance is right from its
     * first commit, and again from {@code InstancePrep} so one branched {@code --no-start}, or
     * re-pointed later, is reconciled on its next use. Both are needed: only the second can
     * catch a swap on an existing instance, and only the first stops an instance being wrong
     * for as long as nobody happens to run {@code isx shell}.
     *
     * <p>Only namespaces whose tool can re-derive appear here; the ones that cannot were
     * refused at selection time, so there is nothing to reconcile.
     */
    public static void reconcileAccountIdentities(IncusClient incus, String name) {
        try {
            var config = SpawnConfig.load();
            var stale = AccountSelection.staleIdentities(config, incus, name);
            if (stale.isEmpty()) return;

            // Re-deriving goes out through the proxy, and a just-started instance may not have
            // an address yet. Only paid for when something is actually stale, and returns as
            // soon as the address is up -- which for an instance that has been running a while
            // is the first poll.
            if (!incus.pollUntilReady(name, 30, "sh", "-c",
                    "ip -4 -o addr show scope global | grep -q inet")) {
                throw new IncusException(name + " has no IPv4 address");
            }

            var container = new Container(incus, name);
            var setups = AccountSelection.namespaceSetups(config);
            var updates = new LinkedHashMap<String, String>();
            stale.forEach((namespace, identity) -> {
                var setup = setups.get(namespace);
                if (setup == null) return;
                BuildOutput.step("Updating " + namespace + " identity for account '"
                        + identity + "'...");
                setup.rebakeForAccount(container, identity);
                updates.put(Metadata.accountIdentityKey(namespace), identity);
            });
            if (!updates.isEmpty()) incus.configSetAll(name, updates);
        } catch (Exception e) {
            // Best effort: a stale identity is a wrong commit author, not a broken instance,
            // and the next use tries again because the stamp is only updated on success.
            System.err.println("Warning: could not update credential identity for " + name
                    + ": " + e.getMessage());
        }
    }

    /**
     * Pin the instance to the address it was allocated, so it cannot answer for another.
     *
     * <p>The proxy attributes a request to an instance by its source address, and picks that
     * instance's credential account from it. Without filtering, root inside a container could
     * re-address its interface as a neighbour and spend that neighbour's subscription --
     * which would make per-instance credentials worse than useless, since they would look
     * like isolation while providing none.
     *
     * <p>Incus enforces this with nftables/ebtables rules and requires the pinned
     * {@code ipv4.address} set with it. A host whose kernel or firewall backend cannot do
     * it warns rather than failing the branch: the instance still works, it is only the
     * separation between instances that is not enforced.
     */
    public static void applyIpFiltering(IncusClient incus, String name, String nicDevice) {
        try {
            incus.deviceConfigSet(name, nicDevice, "security.ipv4_filtering", "true");
        } catch (RuntimeException e) {
            System.err.println(BuildOutput.STEP_INDENT
                    + "Warning: could not enable IP spoofing protection on " + name + ": "
                    + e.getMessage());
            System.err.println(BuildOutput.STEP_INDENT
                    + "Instances on this host can impersonate each other's credential accounts.");
        }
    }

    /**
     * Start an instance, falling back once if IP spoofing protection is what stops it.
     *
     * <p>Setting {@code security.ipv4_filtering} <em>succeeds</em> on a host that cannot enforce
     * it; the failure only appears when the instance starts and Incus tries to install the
     * per-NIC rules ("The kernel doesn't support the ebtables 'filter' table" on xtables, the
     * equivalent nft error otherwise). Without this, such a host would accept every branch and
     * then be unable to start any of them -- a working setup broken by a hardening step.
     *
     * <p>The fallback drops the setting, says plainly what is no longer enforced, and starts.
     * An instance that works without separation beats an instance that does not work, but the
     * user has to know which one they have.
     */
    public static void startInstance(IncusClient incus, String name) {
        try {
            incus.start(name);
        } catch (RuntimeException e) {
            if (!looksLikeIpFilteringFailure(e) || !disableIpFiltering(incus, name)) throw e;
            incus.start(name);
        }
    }

    /**
     * Narrow on purpose: only a device-level failure naming the filtering machinery may strip a
     * security setting. Any other start failure must surface as itself.
     */
    static boolean looksLikeIpFilteringFailure(Throwable e) {
        for (var cause = e; cause != null; cause = cause.getCause()) {
            var message = cause.getMessage();
            if (message == null) continue;
            var lower = message.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains("failed to start device")) continue;
            if (lower.contains("ebtables") || lower.contains("nft")
                    || lower.contains("ipv4_filtering") || lower.contains("iptables")) {
                return true;
            }
        }
        return false;
    }

    private static boolean disableIpFiltering(IncusClient incus, String name) {
        try {
            var nic = incus.findNic(name, "incusbr0");
            if (nic == null || !"true".equals(nic.config().get("security.ipv4_filtering"))) {
                return false;
            }
            incus.deviceConfigSet(name, nic.name(), "security.ipv4_filtering", "false");
            System.err.println(BuildOutput.STEP_INDENT
                    + "Warning: this host cannot enforce IP spoofing protection, so it has been"
                    + " disabled on " + name + ".");
            System.err.println(BuildOutput.STEP_INDENT
                    + "Instances on this host can impersonate each other's credential accounts.");
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static int bridgePrefixLen(IncusClient incus) {
        var bridgeAddr = incus.networkConfigGet("incusbr0", "ipv4.address");
        return bridgeAddr.contains("/") ? CidrUtils.parseCidr(bridgeAddr).prefixLen() : 24;
    }

    /**
     * Push a systemd-networkd static config into the container, overwriting the
     * DHCP config the template carries from build time. This makes the branch
     * boot directly into static addressing (instant network, no DHCP round trip).
     */
    static void pushStaticNetworkConfig(IncusClient incus, String name,
                                                String ip, String gateway, int prefixLen) {
        var content = "[Match]\nName=eth0\n\n[Network]\n"
                + "Address=" + ip + "/" + prefixLen + "\n"
                + "Gateway=" + gateway + "\n"
                + "DNS=" + gateway + "\n";
        try {
            var tmp = Files.createTempFile("isx-network-", ".network");
            try {
                Files.writeString(tmp, content);
                incus.filePush(tmp.toString(), name, "/etc/systemd/network/10-eth0.network");
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println(BuildOutput.STEP_INDENT + "Warning: failed to push static network config: " + e.getMessage());
        }
    }

    /**
     * Push files that can't be written to a stopped VM (file push requires the
     * incus-agent). Call after {@code incus.start()} + {@code waitForReady()}.
     * SSH keys and terminfo are not among them: {@link #setupRuntime} writes those.
     */
    public static void pushDeferredVmFiles(IncusClient incus, String name, NetworkMode networkMode) {
        if (networkMode != NetworkMode.AIRGAP) {
            var ip = incus.configGet(name, Metadata.STATIC_IP);
            var gateway = incus.configGet(name, Metadata.STATIC_GATEWAY);
            if (!ip.isEmpty() && !gateway.isEmpty()) {
                pushStaticNetworkConfig(incus, name, ip, gateway, bridgePrefixLen(incus));
            }
        }
    }

    /**
     * Detect and fix stale static IP configuration caused by a bridge subnet
     * change. Compares the instance's stored {@code STATIC_IP} against the
     * current bridge subnet. If stale, allocates a new IP on the current
     * subnet and updates the NIC device config, metadata, and (for containers)
     * the in-guest {@code .network} file.
     *
     * <p>For VMs the {@code .network} file cannot be pushed while stopped
     * (requires the incus-agent). The caller must arrange a deferred push
     * via {@link #pushDeferredNetworkConfig} after start.
     *
     * @return true if a fix was applied
     */
    public static boolean fixStaticIpIfNeeded(IncusClient incus, String name) {
        var storedIp = incus.configGet(name, Metadata.STATIC_IP);
        if (storedIp.isEmpty()) return false;

        var bridgeAddr = incus.networkConfigGet("incusbr0", "ipv4.address");
        if (bridgeAddr.isEmpty()) return false;
        var bridgeCidr = CidrUtils.parseCidr(bridgeAddr);

        if (CidrUtils.isInSubnet(storedIp, bridgeCidr)) return false;

        var newIp = StaticIpAllocator.allocate(incus);
        var newGateway = ProxyConfig.resolveGatewayIp(incus);
        var nicDevice = StaticIpAllocator.findNicDevice(incus, name);
        var prefixLen = bridgePrefixLen(incus);

        BuildOutput.step("Reassigning " + name + ": " + storedIp + " → " + newIp);
        incus.deviceConfigSet(name, nicDevice, "ipv4.address", newIp);
        applyIpFiltering(incus, name, nicDevice);

        var updates = new HashMap<String, String>();
        updates.put(Metadata.STATIC_IP, newIp);
        updates.put(Metadata.STATIC_GATEWAY, newGateway);
        var proxyGw = incus.configGet(name, Metadata.PROXY_GATEWAY);
        if (!proxyGw.isEmpty()) {
            updates.put(Metadata.PROXY_GATEWAY, newGateway);
        }
        incus.configSetAll(name, updates);

        if (!incus.isVm(name)) {
            pushStaticNetworkConfig(incus, name, newIp, newGateway, prefixLen);
        }
        return true;
    }

    /**
     * Walk all instances and fix any whose static IP belongs to a stale subnet.
     * Called from {@code InitCommand} after a bridge subnet change, and from
     * {@code DoctorCommand} as an interactive remediation.
     *
     * @return count of instances that were migrated
     */
    public static int migrateAllInstancesToNewSubnet(IncusClient incus) {
        int fixed = 0;
        try {
            for (var instance : incus.list()) {
                var name = instance.get("name");
                if (name == null || name.isEmpty()) continue;
                try {
                    if (fixStaticIpIfNeeded(incus, name)) {
                        fixed++;
                    }
                } catch (Exception e) {
                    System.err.println("  Warning: failed to migrate " + name
                            + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("  Warning: could not list instances for migration: "
                    + e.getMessage());
        }
        return fixed;
    }

    /**
     * Push the static {@code .network} file into a running VM using its stored
     * metadata. Call after {@code incus.start()} + {@code waitForReady()} for
     * VMs whose static IP was fixed while stopped.
     */
    public static void pushDeferredNetworkConfig(IncusClient incus, String name) {
        var ip = incus.configGet(name, Metadata.STATIC_IP);
        var gateway = incus.configGet(name, Metadata.STATIC_GATEWAY);
        if (!ip.isEmpty() && !gateway.isEmpty()) {
            pushStaticNetworkConfig(incus, name, ip, gateway, bridgePrefixLen(incus));
            // The VM already booted with the old .network file; tell networkd
            // to re-read and apply the new config without a full reboot.
            try {
                incus.shellExec(name, "networkctl", "reconfigure", "eth0");
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Count instances whose static IP is on a different subnet than the
     * current bridge. Used by {@code DoctorCommand} for detection.
     */
    public static List<String> findStaleSubnetInstances(IncusClient incus) {
        var bridgeAddr = incus.networkConfigGet("incusbr0", "ipv4.address");
        if (bridgeAddr.isEmpty()) return List.of();
        var bridgeCidr = CidrUtils.parseCidr(bridgeAddr);

        // The listing already carries each instance's config: one request, not one per instance.
        JsonNode instances;
        try {
            instances = JSON.readTree(incus.listJsonConfig());
        } catch (IOException e) {
            throw new IncusException("Failed to parse instance list: " + e.getMessage());
        }
        var stale = new ArrayList<String>();
        for (var instance : instances) {
            var name = instance.path("name").asText("");
            if (name.isEmpty()) continue;
            var storedIp = instance.path("config").path(Metadata.STATIC_IP).asText("");
            if (storedIp.isEmpty()) continue;
            if (!CidrUtils.isInSubnet(storedIp, bridgeCidr)) {
                stale.add(name);
            }
        }
        return stale;
    }

    public static void tagMetadata(IncusClient incus, String name, String type, String parent) {
        incus.configSetAll(name, Map.of(
                Metadata.TYPE, type,
                Metadata.PARENT, parent,
                Metadata.CREATED, Metadata.today()));
    }

    /**
     * Remove host-side integration for a destroyed or renamed instance.
     */
    public static void removeHostIntegration(String name) {
        AutoRemoteService.removeRemotes(name, msg -> {});
        SshKeyManager.cleanupInstance(name);
        ZmxSocketForward.cleanup(name);
    }

    /**
     * Repair host-side devices before starting an instance.  Both a
     * host-resource source that has disappeared and a missing zmx socket
     * directory otherwise fail Incus start validation with
     * {@code Missing source path}.
     */
    public static void prepareHostDevicesForStart(IncusClient incus, String name) {
        // Both repairs read the same instance, so fetch it once: start has to
        // stay as cheap as it was before the repairs existed.
        var instance = incus.instanceMetadata(name);
        HostResourceSetup.removeStaleDevices(incus, name, instance);
        ZmxSocketForward.ensureHostDirForStart(incus, name, instance);
    }

    /**
     * Apply host resource devices and (for instances) add git remotes.
     */
    public static void integrateWithHost(IncusClient incus, String name, InstanceType instanceType) {
        var hrJson = incus.configGet(name, Metadata.HOST_RESOURCES);
        var hostResources = HostResourceSetup.deserialize(hrJson);
        if (!hostResources.isEmpty()) {
            BuildOutput.step("Applying host resources.");
            HostResourceSetup.applyForInstance(incus, name, hostResources, incus.isVm(name));
        }

        if (instanceType == InstanceType.INSTANCE) {
            AutoRemoteService.addRemotes(incus, name, BuildOutput::step);

            var buildSourceJson = incus.configGet(name, Metadata.BUILD_SOURCE);
            if (ZmxSocketForward.isZmxInstalled(buildSourceJson)) {
                ZmxSocketForward.configure(incus, name);
            }
        }
    }

    /**
     * Read what {@link #setupRuntime} needs while the new instance is still stopped, then
     * start it. Shared by {@code isx branch} and the TUI's branch action.
     *
     * <p>Nothing is pushed into the instance between the two: Incus stops its forkfile file
     * server on start, and one still finishing a push makes the start wait a full second.
     * Anything the instance needs goes into the post-start setup script instead.
     */
    public static RuntimeConfig prefetchAndStart(IncusClient incus, String name, boolean isVm) {
        var prefetched = prefetchRuntimeConfig(incus, name);
        BuildOutput.stepStart(isVm ? "Starting VM..." : "Starting container...");
        startInstance(incus, name);
        BuildOutput.stepDone();
        return prefetched;
    }

    /**
     * Pre-fetch instance metadata that setupRuntime needs, while the container
     * is still stopped. Reading config from a stopped container avoids lock
     * contention with the seccomp_notify handler that activates on start.
     */
    public static RuntimeConfig prefetchRuntimeConfig(IncusClient incus, String name) {
        // One read for all four keys: configGet is a full instance GET per key.
        var config = incus.configByPrefix(name, "");
        var buildSourceJson = config.getOrDefault(Metadata.BUILD_SOURCE, "");
        var hasSshKeys = !config.getOrDefault("user.incus-spawn.ssh-setup", "").isEmpty()
                || hasSshdTool(buildSourceJson);
        var workdir = config.getOrDefault(Metadata.WORKDIR, "");
        var shellCommand = config.getOrDefault(Metadata.SHELL_COMMAND, "");
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
                BuildOutput.step("Mounting inbox: " + inboxPath.toAbsolutePath() + ".");
                incus.deviceAdd(name, "inbox", "disk",
                        "source=" + dev.incusspawn.config.HostResourceSetup.translateForVm(
                                inboxPath.toAbsolutePath().toString()),
                        "path=/home/agentuser/inbox",
                        "readonly=true");
            } else {
                System.err.println(BuildOutput.STEP_INDENT + "Warning: inbox path '" + inboxPath +
                        "' is not a directory, skipping.");
            }
        }

        // Build a single setup script that handles readiness polling, home
        // ownership, terminfo, and tool readiness — all in one exec call.
        // Each additional exec round trip blocks for seconds due to
        // seccomp_notify lock contention during container startup.
        var buildSourceJson = prefetched != null ? prefetched.buildSourceJson()
                : incus.configGet(name, Metadata.BUILD_SOURCE);
        var sshKeys = prefetched != null && prefetched.hasSshKeys() ? sshKeysToInject() : List.<String>of();
        var setupScript = buildSetupScript(prefetched, buildSourceJson, networkMode, sshKeys);
        BuildOutput.stepStart("Waiting for container...");
        if (!incus.pollUntilReady(name, 30, "sh", "-c", setupScript)) {
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
     * home ownership, network readiness and tool readiness. Batching avoids multiple exec round
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
            sb.append("\n{ systemctl start systemd-networkd 2>/dev/null; ")
              .append("for i in $(seq 1 300); do ip -4 -o addr show eth0 | grep -q 'inet ' && break; sleep 0.05; done; ")
              .append("ip -4 -o addr show eth0 | grep -q 'inet '; }");
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
