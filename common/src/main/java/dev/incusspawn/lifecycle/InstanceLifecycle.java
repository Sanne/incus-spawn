package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.AutoRemoteService;
import dev.incusspawn.incus.BridgeAddress;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
     * @param accountOrigins who chose each of {@code accounts}, recorded beside the pin
     * @param kvm      whether KVM passthrough is configured next; if not, KVM devices and
     *                 metadata inherited from the source are dropped
     * @param extraConfig further {@code config} keys the caller stamps on its branch, in the
     *                 same write; applied last
     */
    public record BranchSettings(String cpu, String memory, String disk, NetworkMode networkMode,
                                 String parent, Map<String, String> accounts,
                                 Map<String, dev.incusspawn.config.AccountOrigin> accountOrigins,
                                 boolean kvm, Map<String, String> extraConfig) {
        public BranchSettings {
            accountOrigins = accountOrigins == null ? Map.of() : Map.copyOf(accountOrigins);
            extraConfig = extraConfig == null ? Map.of() : Map.copyOf(extraConfig);
        }

        public BranchSettings(String cpu, String memory, String disk, NetworkMode networkMode,
                              String parent, Map<String, String> accounts, boolean kvm) {
            this(cpu, memory, disk, networkMode, parent, accounts, Map.of(), kvm, Map.of());
        }
    }

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
     * <p>Airgap masks every NIC with {@code type: none} in that same write, which is the only way
     * to take a profile's NIC away from one instance (#813). Branching with network from an
     * airgapped instance overwrites those masks with the profile's NICs, in the same write too.
     */
    public static void configureBranch(IncusClient incus, String name, BranchSettings settings) {
        configureBranch(incus, name, settings, incus.instanceMetadata(name), null);
    }

    /**
     * As {@link #configureBranch(IncusClient, String, BranchSettings)}, against what the caller
     * already read: the new instance's metadata, and the bridge's {@code ipv4.address} (null to
     * read it here, if the network mode needs it).
     */
    public static void configureBranch(IncusClient incus, String name, BranchSettings settings,
                                       JsonNode instance, String bridgeCidr) {
        var mode = settings.networkMode();
        // Not isMissingNode(): Incus answers a missing instance with "metadata": null.
        if (!instance.isObject()) throw new IncusException("Failed to read instance " + name);
        var update = new InstanceUpdate();
        if (mode != NetworkMode.AIRGAP && isAirgapped(instance)) {
            instance = unmaskNics(incus, instance, update);
        }

        var cpu = settings.cpu();
        update.config("limits.cpu", cpu != null && !cpu.isEmpty() ? cpu : null);
        update.config("limits.memory", settings.memory());
        update.device("root", "size", settings.disk());

        BridgeAddress bridge = null;
        String nicDevice = null;
        // Full internet is the absence of a mode, not whatever the source was copied with
        update.config(Metadata.NETWORK_MODE, mode == NetworkMode.FULL ? null : mode.name());
        if (mode == NetworkMode.AIRGAP) {
            BuildOutput.step("Enabling network airgap.");
            maskNics(instance, update);
            // A copy of a networked instance carries its address: the proxy must not take this
            // one for its source.
            update.unset(Metadata.STATIC_IP);
            update.unset(Metadata.STATIC_GATEWAY);
        } else {
            // One read gives the gateway, the subnet to allocate from and the prefix to push
            bridge = bridgeCidr != null ? BridgeAddress.require(bridgeCidr) : BridgeAddress.require(incus);
            if (mode == NetworkMode.PROXY_ONLY) BuildOutput.step("Configuring proxy-only network.");
            nicDevice = IncusClient.nicDeviceName(instance, BridgeAddress.BRIDGE);
            if (nicDevice == null) {
                throw new IncusException("No NIC device for " + BridgeAddress.BRIDGE + " found on " + name);
            }
            // The address itself is allocated in claimAndWrite, under the allocation lock
            update.device(nicDevice, "security.ipv4_filtering", "true");
            update.config(Metadata.STATIC_GATEWAY, bridge.gateway());
        }
        update.config(Metadata.PROXY_GATEWAY,
                mode == NetworkMode.PROXY_ONLY ? bridge.gateway() : null);

        update.config(Metadata.TYPE, Metadata.TYPE_CLONE);
        update.config(Metadata.PARENT, settings.parent());
        update.config(Metadata.CREATED, Metadata.today());
        if (!settings.accounts().isEmpty()) {
            update.config(AccountSelection.stampUpdates(settings.accounts(), settings.accountOrigins(),
                    AccountSelection.fromConfig(instance.path("config"))));
        }
        if (!settings.kvm()) KvmPassthrough.removeKvm(instance, update);
        update.config(settings.extraConfig());
        // Templates built before free page reporting existed don't carry it to their copies
        if (IncusClient.isVm(instance)
                && instance.path("config").path(RAW_QEMU_CONF).asText("").isBlank()) {
            update.config(RAW_QEMU_CONF, FREE_PAGE_REPORTING_CONF);
        }

        if (nicDevice == null) incus.update(name, instance, update);
        else claimAndWrite(incus, name, instance, update, nicDevice, bridge);
    }

    /**
     * Allocate the branch's address and write {@code update} with it, holding the allocation
     * lock throughout. Everything that does not depend on the address is read before it, so
     * concurrent branches wait on each other only for the listing, the push and the write (#815).
     */
    private static void claimAndWrite(IncusClient incus, String name, JsonNode instance,
                                      InstanceUpdate update, String nicDevice, BridgeAddress bridge) {
        var isVm = IncusClient.isVm(instance);
        var filteringRefused = new AtomicBoolean();
        StaticIpAllocator.claim(incus, bridge, ip -> {
            // A static IP, so no DHCP lease is ever acquired: leases expire across host
            // sleep/wake. See pushStaticNetworkConfig for the guest side.
            BuildOutput.step("Assigning static IP " + ip + ".");
            update.device(nicDevice, "ipv4.address", ip);
            update.config(Metadata.STATIC_IP, ip);
            // Pushed before the write rather than after, so the push is not the last thing
            // before the start: see "Why nothing is pushed into an instance just before it
            // starts".
            if (!isVm) {
                pushStaticNetworkConfig(incus, name, ip, bridge.gateway(), bridge.prefixLen());
            }
            try {
                incus.update(name, instance, update);
            } catch (IncusException e) {
                // Failing to pin the address is fatal, failing to enable filtering only warns,
                // and one write cannot say which of them Incus refused. Incus rolls a refused
                // write back whole, so retry without filtering: if that fails too, the error is
                // the real one. If it goes through, enable filtering on its own, so a first
                // failure that had nothing to do with filtering does not leave the instance
                // without it.
                incus.update(name, instance,
                        update.withoutDeviceProperty(nicDevice, "security.ipv4_filtering"));
                filteringRefused.set(true);
            }
        });
        // After the claim: the address is written, so no one need wait on this write
        if (filteringRefused.get()) applyIpFiltering(incus, name, nicDevice);
    }

    private static final Map<String, String> MASKED_DEVICE = Map.of("type", "none");

    private static boolean isAirgapped(JsonNode instance) {
        return NetworkMode.AIRGAP.name().equals(
                instance.path("config").path(Metadata.NETWORK_MODE).asText(""));
    }

    /**
     * Take every NIC away from the instance, whichever network it is on and wherever it comes
     * from. Removing an instance device only drops the instance's own copy: a NIC the {@code
     * default} profile provides, or one the instance overrides from it (a clone's static IP),
     * applies again. A {@code type: none} device of the same name masks it.
     */
    static void maskNics(JsonNode instance, InstanceUpdate update) {
        instance.path("expanded_devices").properties().forEach(e -> {
            if (IncusClient.isNic(e.getValue())) update.replaceDevice(e.getKey(), MASKED_DEVICE);
        });
    }

    /**
     * Put back the profile NICs {@link #maskNics} masked, as instance devices overwriting the
     * masks: a PATCH replaces a device whole, so this needs no separate removal. Returns the
     * instance as it will then expand, so the static IP is set on the NIC that comes back. Only
     * masks of a profile NIC are touched: a {@code type: none} device the template set itself
     * stays.
     */
    private static JsonNode unmaskNics(IncusClient incus, JsonNode instance, InstanceUpdate update) {
        // Later profiles override earlier ones, as Incus expands them
        var profileDevices = new LinkedHashMap<String, JsonNode>();
        for (var profile : instance.path("profiles")) {
            incus.profileDevices(profile.asText()).properties()
                    .forEach(e -> profileDevices.put(e.getKey(), e.getValue()));
        }
        var view = instance.deepCopy();
        var expanded = (ObjectNode) view.path("expanded_devices");
        instance.path("devices").properties().forEach(e -> {
            var profileDevice = profileDevices.get(e.getKey());
            if ("none".equals(e.getValue().path("type").asText())
                    && profileDevice != null && IncusClient.isNic(profileDevice)) {
                expanded.set(e.getKey(), profileDevice);
                // A changed device is sent whole from the expanded view: the profile's NIC
                update.device(e.getKey(), "type", "nic");
            }
        });
        return view;
    }

    static final String RAW_QEMU_CONF = "raw.qemu.conf";
    static final String FREE_PAGE_REPORTING_CONF =
            "[device \"qemu_balloon\"]\nfree-page-reporting = \"on\"\n";

    /**
     * Turn on virtio-balloon free page reporting, so memory the guest frees goes back to the host.
     * Without it QEMU keeps every page the guest ever touched until the VM stops, and a VM's page
     * cache grows until it has touched all of {@code limits.memory}. Incus merges this into its own
     * balloon device section. Takes effect at the next start.
     *
     * <p>A {@code raw.qemu.conf} set by someone else is left alone: merging into another author's
     * QEMU config is not worth the risk of a VM that no longer starts.
     */
    public static void enableFreePageReporting(IncusClient incus, String name) {
        var current = incus.configGet(name, RAW_QEMU_CONF);
        if (current.isBlank()) {
            incus.configSet(name, RAW_QEMU_CONF, FREE_PAGE_REPORTING_CONF);
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
        reconcileAccountIdentities(incus, name, BuildOutput::step,
                msg -> System.err.println("Warning: " + msg));
    }

    /**
     * As {@link #reconcileAccountIdentities(IncusClient, String)}, reporting through sinks rather
     * than the terminal -- the TUI's, where a line printed from a background thread would be
     * drawn over and lost.
     */
    public static void reconcileAccountIdentities(IncusClient incus, String name,
                                                  Consumer<String> progress, Consumer<String> warnings) {
        reconcileAccountIdentities(incus, name, SpawnConfig.load(), null, progress, warnings);
    }

    /**
     * @param knownSetups the credential namespaces' tools, when the caller has them -- the TUI
     *                    passes its own so the tool definitions are not re-read from disk;
     *                    {@code null} discovers them
     */
    private static void reconcileAccountIdentities(IncusClient incus, String name, SpawnConfig config,
                                                   Map<String, dev.incusspawn.tool.ToolSetup> knownSetups,
                                                   Consumer<String> progress, Consumer<String> warnings) {
        try {
            var stale = AccountSelection.staleIdentities(config, incus, name, knownSetups);
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
            var setups = knownSetups != null ? knownSetups : AccountSelection.namespaceSetups(config);
            var updates = new LinkedHashMap<String, String>();
            stale.forEach((namespace, identity) -> {
                var setup = setups.get(namespace);
                if (setup == null) return;
                progress.accept("Updating " + namespace + " identity for account '"
                        + identity + "'...");
                setup.rebakeForAccount(container, identity);
                updates.put(Metadata.accountIdentityKey(namespace), identity);
            });
            if (!updates.isEmpty()) incus.configSetAll(name, updates);
        } catch (Exception e) {
            // Best effort: a stale identity is a wrong commit author, not a broken instance,
            // and the next use tries again because the stamp is only updated on success.
            warnings.accept("could not update credential identity for " + name
                    + ": " + e.getMessage());
        }
    }

    /**
     * Refuse an account change {@link #changeAccounts} would refuse, without making it -- for a
     * front end that wants to say no while the user is still choosing and make the change
     * elsewhere (the TUI, off its event thread).
     *
     * @param setups the credential namespaces' tools ({@link AccountSelection#namespaceSetups})
     * @throws AccountSelection.InvalidSelectionException  when the built instance cannot honour it
     * @throws dev.incusspawn.config.AccountResolver.UnknownAccountException  for an unknown or
     *         incomplete account
     */
    public static void checkAccountChange(IncusClient incus, String instance, SpawnConfig config,
                                          Map<String, dev.incusspawn.tool.ToolSetup> setups,
                                          Map<String, String> changes) {
        checkAccountChange(incus, instance, config, setups, changes, AccountSelection.read(incus, instance));
    }

    /**
     * Only the namespaces being changed are checked. A pin the change does not touch is not the
     * user's question right now -- and one naming an account since removed would otherwise
     * refuse every change to anything else on the instance, including the one that repairs it.
     *
     * @return the pins the change would leave
     */
    private static Map<String, String> checkAccountChange(IncusClient incus, String instance, SpawnConfig config,
                                                          Map<String, dev.incusspawn.tool.ToolSetup> setups,
                                                          Map<String, String> changes,
                                                          Map<String, String> current) {
        var pins = new LinkedHashMap<String, String>();
        changes.forEach((ns, account) -> { if (account != null) pins.put(ns, account); });
        AccountSelection.validate(config, pins, setups);

        // The check reads a null account as "the default": the default may be a Claude auth
        // mode the build did not bake, so unpinning is refused on the same grounds as pinning.
        var reason = AccountSelection.incompatibilityReason(config, incus, instance,
                new LinkedHashMap<>(changes), setups);
        if (!reason.isEmpty()) throw new AccountSelection.InvalidSelectionException(reason);

        var merged = new LinkedHashMap<>(current);
        changes.forEach((ns, account) -> {
            if (account == null) merged.remove(ns); else merged.put(ns, account);
        });
        return merged;
    }

    /**
     * Change which credential accounts an instance uses. {@code changes} maps a namespace to the
     * account to pin, or to {@code null} to remove the pin so the instance follows the default;
     * namespaces it does not name keep what they had -- naming one must never silently unpin
     * another.
     *
     * <p>The one path for every front end ({@code isx account set/unset}, the TUI), so none can
     * skip a step: validate, refuse what the built instance could not honour, stamp, tell the
     * proxy, and re-derive what the build baked while the instance is running (a stopped one
     * is reconciled by its next use). Refusing happens while the user is still choosing, rather
     * than surfacing later as a failing request inside.
     *
     * @param setups the credential namespaces' tools ({@link AccountSelection#namespaceSetups});
     *               the TUI passes those it already loaded, so nothing here re-reads the tool
     *               definitions from disk
     * @return the instance's pins afterwards
     * @throws AccountSelection.InvalidSelectionException  when the built instance cannot honour it
     * @throws dev.incusspawn.config.AccountResolver.UnknownAccountException  for an unknown or
     *         incomplete account
     */
    public static Map<String, String> changeAccounts(IncusClient incus, String instance, SpawnConfig config,
                                                     Map<String, dev.incusspawn.tool.ToolSetup> setups,
                                                     Map<String, String> changes,
                                                     Consumer<String> progress, Consumer<String> warnings) {
        var current = AccountSelection.read(incus, instance);
        var merged = checkAccountChange(incus, instance, config, setups, changes, current);

        // Untouched pins keep who chose them; every pin made here is an explicit choice --
        // including re-choosing the account a template pinned, which from now on is the user's
        // and no longer the template's.
        var currentOrigins = AccountSelection.readOrigins(incus, instance);
        var origins = new LinkedHashMap<>(currentOrigins);
        changes.forEach((ns, account) -> {
            if (account == null) origins.remove(ns); else origins.put(ns, dev.incusspawn.config.AccountOrigin.EXPLICIT);
        });
        if (merged.equals(current) && origins.equals(currentOrigins)) return merged;
        AccountSelection.stamp(incus, instance, merged, origins, current);
        if (merged.equals(current)) return merged; // only who chose it changed: nothing to re-derive
        dev.incusspawn.proxy.ProxyService.signalAccountRefresh();

        // The token swaps live, but anything the build *baked* from the old account -- the
        // git identity -- would still be the old one, so the instance would push with one
        // account and commit as another.
        if (!"Stopped".equalsIgnoreCase(incus.getInstanceStatus(instance))) {
            reconcileAccountIdentities(incus, instance, config, setups, progress, warnings);
        }
        return merged;
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
        applyIpFiltering(incus, name, nicDevice, STDERR_WARN);
    }

    private static void applyIpFiltering(IncusClient incus, String name, String nicDevice,
                                         Consumer<String> warn) {
        try {
            incus.deviceConfigSet(name, nicDevice, "security.ipv4_filtering", "true");
        } catch (RuntimeException e) {
            warn.accept("could not enable IP spoofing protection on " + name + ": "
                    + e.getMessage());
            warn.accept("Instances on this host can impersonate each other's credential accounts.");
        }
    }

    /**
     * Make {@code name}, currently in {@code status}, answer exec before a shell or command runs
     * in it: start it if stopped, or recover a VM whose agent does not answer
     * ({@link VmAgentRecovery}). With {@code pushNetworkConfig}, then push the VM's deferred
     * {@code .network} file, which could not be written while it was stopped.
     *
     * <p>Progress and warnings go to {@code say}, never straight to stdout or stderr, so a caller
     * that owns the terminal (the TUI) can route them.
     */
    public static void ensureReady(IncusClient incus, String name, String status, boolean pushNetworkConfig,
                                   Consumer<String> say) {
        if ("Stopped".equalsIgnoreCase(status)) {
            say.accept("Starting " + name + "...");
            prepareHostDevicesForStart(incus, name, say);
            startInstance(incus, name);
            incus.waitForReady(name);
        } else if (incus.isVm(name) && !agentAnswers(incus, name)) {
            VmAgentRecovery.restartForAgent(incus, name, say);
        }
        if (pushNetworkConfig) pushDeferredNetworkConfig(incus, name);
    }

    /** Whether a running VM's agent answers exec; Incus refuses the exec outright when it is down. */
    private static boolean agentAnswers(IncusClient incus, String name) {
        try {
            return incus.shellExec(name, "echo", "ready").success();
        } catch (RuntimeException agentDown) {
            return false;
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
            var nic = incus.findNic(name, BridgeAddress.BRIDGE);
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
        return BridgeAddress.read(incus).map(BridgeAddress::prefixLen).orElse(24);
    }

    /** Warnings from flows that print them, as the terminal shows them. */
    private static final Consumer<String> STDERR_WARN =
            msg -> System.err.println(BuildOutput.STEP_INDENT + "Warning: " + msg);

    static void pushStaticNetworkConfig(IncusClient incus, String name,
                                        String ip, String gateway, int prefixLen) {
        pushStaticNetworkConfig(incus, name, ip, gateway, prefixLen, STDERR_WARN);
    }

    /**
     * Push a systemd-networkd static config into the container, overwriting the
     * DHCP config the template carries from build time. This makes the branch
     * boot directly into static addressing (instant network, no DHCP round trip).
     *
     * <p>Root-owned 0644 explicitly: the mode would otherwise be the temp file's 0600, which
     * {@code systemd-networkd}, running as {@code systemd-network}, cannot read. A template's
     * own {@code 10-eth0.network} hid this, since overwriting keeps a file's mode; a template
     * without one got a branch with no network.
     */
    static void pushStaticNetworkConfig(IncusClient incus, String name, String ip,
                                        String gateway, int prefixLen, Consumer<String> warn) {
        var content = "[Match]\nName=eth0\n\n[Network]\n"
                + "Address=" + ip + "/" + prefixLen + "\n"
                + "Gateway=" + gateway + "\n"
                + "DNS=" + gateway + "\n";
        try {
            var tmp = Files.createTempFile("isx-network-", ".network");
            try {
                Files.writeString(tmp, content);
                incus.filePush(tmp.toString(), name, "/etc/systemd/network/10-eth0.network",
                        "0", "0", "0644");
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException e) {
            warn.accept("failed to push static network config: " + e.getMessage());
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
        return fixStaticIpIfNeeded(incus, name, StaticIpAllocator.Output.TERMINAL);
    }

    /** @param output where the reassignment, and any wait for the allocation lock, is reported */
    public static boolean fixStaticIpIfNeeded(IncusClient incus, String name,
                                              StaticIpAllocator.Output output) {
        var storedIp = incus.configGet(name, Metadata.STATIC_IP);
        if (storedIp.isEmpty()) return false;
        var bridge = BridgeAddress.read(incus);
        return bridge.isPresent() && fixStaticIp(incus, name, storedIp, bridge.get(), output);
    }

    private static boolean fixStaticIp(IncusClient incus, String name, String storedIp,
                                       BridgeAddress bridge, StaticIpAllocator.Output output) {
        if (storedIp.isEmpty() || CidrUtils.isInSubnet(storedIp, bridge.subnet())) return false;

        var newGateway = bridge.gateway();
        var nicDevice = StaticIpAllocator.findNicDevice(incus, name);

        var newIp = StaticIpAllocator.claim(incus, bridge, output, ip -> {
            output.step().accept("Reassigning " + name + ": " + storedIp + " → " + ip);
            incus.deviceConfigSet(name, nicDevice, "ipv4.address", ip);
        });
        applyIpFiltering(incus, name, nicDevice, output.warn());

        var updates = new HashMap<String, String>();
        updates.put(Metadata.STATIC_IP, newIp);
        updates.put(Metadata.STATIC_GATEWAY, newGateway);
        var proxyGw = incus.configGet(name, Metadata.PROXY_GATEWAY);
        if (!proxyGw.isEmpty()) {
            updates.put(Metadata.PROXY_GATEWAY, newGateway);
        }
        incus.configSetAll(name, updates);

        if (!incus.isVm(name)) {
            pushStaticNetworkConfig(incus, name, newIp, newGateway, bridge.prefixLen(),
                    output.warn());
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
            // One bridge read and one listing for every instance, not requests per instance
            var bridge = BridgeAddress.read(incus);
            if (bridge.isEmpty()) return 0;
            for (var stale : staleStaticIps(incus, bridge.get()).entrySet()) {
                try {
                    if (fixStaticIp(incus, stale.getKey(), stale.getValue(), bridge.get(),
                            StaticIpAllocator.Output.TERMINAL)) {
                        fixed++;
                    }
                } catch (Exception e) {
                    System.err.println("  Warning: failed to migrate " + stale.getKey()
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
        var bridge = BridgeAddress.read(incus);
        return bridge.isEmpty() ? List.of()
                : List.copyOf(staleStaticIps(incus, bridge.get()).keySet());
    }

    /** Each instance whose static IP is off the bridge's subnet, with that IP. */
    private static Map<String, String> staleStaticIps(IncusClient incus, BridgeAddress bridge) {
        // The listing already carries each instance's config: one request, not one per instance.
        JsonNode instances;
        try {
            instances = JSON.readTree(incus.listJsonConfig());
        } catch (IOException e) {
            throw new IncusException("Failed to parse instance list: " + e.getMessage());
        }
        var stale = new LinkedHashMap<String, String>();
        for (var instance : instances) {
            var name = instance.path("name").asText("");
            if (name.isEmpty()) continue;
            var storedIp = instance.path("config").path(Metadata.STATIC_IP).asText("");
            if (storedIp.isEmpty()) continue;
            if (!CidrUtils.isInSubnet(storedIp, bridge.subnet())) {
                stale.put(name, storedIp);
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
        prepareHostDevicesForStart(incus, name, System.err::println);
    }

    /**
     * As above, reporting each device it removes to {@code warn}. The TUI starts instances while it
     * owns the terminal, where stderr would be drawn over and lost, so it passes a sink that puts
     * the warning on its status line instead.
     */
    public static void prepareHostDevicesForStart(IncusClient incus, String name, Consumer<String> warn) {
        // All repairs read the same instance, so fetch it once: start has to
        // stay as cheap as it was before the repairs existed.
        prepareHostDevicesForStart(incus, name, incus.instanceMetadata(name), warn);
    }

    /** {@link #prepareHostDevicesForStart(IncusClient, String, Consumer)} with the instance already read. */
    public static void prepareHostDevicesForStart(IncusClient incus, String name, JsonNode instance,
                                                  Consumer<String> warn) {
        HostResourceSetup.removeStaleDevices(incus, name, instance, warn);
        removeStaleInbox(incus, name, instance, warn);
        ZmxSocketForward.ensureHostDirForStart(incus, name, instance);
    }

    /**
     * Removes the {@code --inbox} device when its host directory is gone (#854), which would
     * otherwise fail Incus start validation with {@code Missing source path}. The inbox is not a
     * host resource, so {@link HostResourceSetup#removeStaleDevices} never sees it. The user asked
     * for this mount, so dropping it is announced, with the command to add it back.
     */
    static void removeStaleInbox(IncusClient incus, String name, JsonNode instance, Consumer<String> warn) {
        var source = IncusClient.deviceSource(instance, INBOX_DEVICE);
        if (source.isEmpty()) return;
        var hostPath = HostResourceSetup.hostPathOfDeviceSource(source);
        if (hostPath == null || Files.isDirectory(hostPath)) return;
        incus.deviceRemove(name, INBOX_DEVICE);
        // The hint reuses the device's own source: on macOS that is the appliance VM's /host view
        // of the path, which is what incus needs there, not the host path the user would type.
        warn.accept(BuildOutput.STEP_INDENT + "Warning: inbox directory not found: " + hostPath
                + " (device removed, starting without ~/inbox).\n" + BuildOutput.STEP_INDENT
                + "  To add it back, recreate it, then: incus config device add " + name + " " + INBOX_DEVICE
                + " disk source=" + source + " path=" + INBOX_PATH + " readonly=true");
    }

    /**
     * Apply host resource devices and (for instances) add git remotes.
     *
     * @param instance the instance's metadata as already read; only keys the branch copied from
     *                 its source are used (host resources, build source, type)
     * @param repos    the repos its template chain declares ({@link dev.incusspawn.git.GitRemoteUtils#collectRepos})
     */
    public static void integrateWithHost(IncusClient incus, String name, InstanceType instanceType,
                                         JsonNode instance, List<ImageDef.RepoEntry> repos) {
        var config = instance.path("config");
        var hostResources = HostResourceSetup.deserialize(IncusClient.configValue(config, Metadata.HOST_RESOURCES));
        if (!hostResources.isEmpty()) {
            BuildOutput.step("Applying host resources.");
            HostResourceSetup.applyForInstance(incus, name, hostResources, IncusClient.isVm(instance));
        }

        if (instanceType == InstanceType.INSTANCE) {
            AutoRemoteService.addRemotes(name, repos, BuildOutput::step);

            var buildSourceJson = IncusClient.configValue(config, Metadata.BUILD_SOURCE);
            if (ZmxSocketForward.isZmxInstalled(buildSourceJson)) {
                ZmxSocketForward.configure(incus, name);
            }
        }
    }

    /**
     * Read what {@link #setupRuntime} needs while the new instance is still stopped, then
     * start it. Used by {@link BranchFlow}, behind both {@code isx branch} and the TUI.
     *
     * <p>Nothing is pushed into the instance between the two: Incus stops its forkfile file
     * server on start, and one still finishing a push makes the start wait a full second.
     * Anything the instance needs goes into the post-start setup script instead.
     */
    public static RuntimeConfig prefetchAndStart(IncusClient incus, String name, boolean isVm) {
        var prefetched = prefetchRuntimeConfig(incus, name);
        startShowingProgress(incus, name, isVm);
        return prefetched;
    }

    /**
     * Start a stopped instance as one progress step. For a caller that read its
     * {@link RuntimeConfig} already ({@link #runtimeConfig}); the same rule holds -- nothing may
     * be pushed into the instance between that read and this.
     */
    public static void startShowingProgress(IncusClient incus, String name, boolean isVm) {
        BuildOutput.stepStart(isVm ? "Starting VM..." : "Starting container...");
        startInstance(incus, name);
        BuildOutput.stepDone();
    }

    /**
     * Pre-fetch instance metadata that setupRuntime needs, while the container
     * is still stopped. Reading config from a stopped container avoids lock
     * contention with the seccomp_notify handler that activates on start.
     */
    public static RuntimeConfig prefetchRuntimeConfig(IncusClient incus, String name) {
        // One read for all four keys: configGet is a full instance GET per key.
        return runtimeConfig(incus.configByPrefix(name, ""), BridgeSubnetCheck.detectConflictDiagnostic(incus));
    }

    /**
     * As {@link #prefetchRuntimeConfig}, from what a caller already read: the instance's
     * {@code config} and the bridge's {@code ipv4.address}.
     */
    public static RuntimeConfig runtimeConfig(JsonNode instanceConfig, String bridgeCidr) {
        return runtimeConfig(IncusClient.configByPrefix(instanceConfig, ""),
                BridgeSubnetCheck.detectConflictDiagnostic(bridgeCidr));
    }

    private static RuntimeConfig runtimeConfig(Map<String, String> config, String subnetDiag) {
        var buildSourceJson = config.getOrDefault(Metadata.BUILD_SOURCE, "");
        var hasSshKeys = !config.getOrDefault("user.incus-spawn.ssh-setup", "").isEmpty()
                || hasSshdTool(buildSourceJson);
        var workdir = config.getOrDefault(Metadata.WORKDIR, "");
        var shellCommand = config.getOrDefault(Metadata.SHELL_COMMAND, "");
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

    static final String INBOX_DEVICE = "inbox";
    static final String INBOX_PATH = "/home/agentuser/inbox";

    /**
     * Attach the {@code --inbox} directory, before start (#828): on a VM a device present at start
     * gets its own PCIe root port instead of one of the 8 spare hotplug slots.
     */
    public static void attachInbox(IncusClient incus, String name, Path inboxPath) {
        if (inboxPath == null) return;
        if (!java.nio.file.Files.isDirectory(inboxPath)) {
            System.err.println(BuildOutput.STEP_INDENT + "Warning: inbox path '" + inboxPath +
                    "' is not a directory, skipping.");
            return;
        }
        BuildOutput.step("Mounting inbox: " + inboxPath.toAbsolutePath() + ".");
        incus.deviceAdd(name, INBOX_DEVICE, "disk",
                "source=" + HostResourceSetup.translateForVm(inboxPath.toAbsolutePath().toString()),
                "path=" + INBOX_PATH,
                "readonly=true");
    }

    /**
     * Post-start setup: firewall, home ownership, SSH keys.
     * GUI and the inbox are NOT handled here — they must be configured before start.
     *
     * @param prefetched config read before start to avoid seccomp lock contention;
     *                   if null, config is read live (slower on macOS)
     */
    public static void setupRuntime(IncusClient incus, String name,
                                   NetworkMode networkMode, RuntimeConfig prefetched) {
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

    public static void setupRuntime(IncusClient incus, String name, NetworkMode networkMode) {
        setupRuntime(incus, name, networkMode, null);
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
