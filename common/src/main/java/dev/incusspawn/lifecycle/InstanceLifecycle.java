package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.AutoRemoteService;
import dev.incusspawn.incus.BridgeAddress;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.CidrUtils;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.InstanceUpdate;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.StaticIpAllocator;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.util.BuildOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

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
        var mode = settings.networkMode();
        var instance = incus.instanceMetadata(name);
        if (instance.isMissingNode()) throw new IncusException("Failed to read instance " + name);
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
            // No address: the copy request already removed its source's (IncusClient.copy), and an
            // airgapped instance must not keep one the proxy could take for another's.
            update.unset(Metadata.STATIC_IP);
            update.unset(Metadata.STATIC_GATEWAY);
        } else {
            // One read gives the gateway, the subnet to allocate from and the prefix to push
            bridge = BridgeAddress.require(incus);
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
        // A copy carries its source's MCP ownership stamps. A branch of an agent's instance
        // (say, one it handed over with keep_instance) belongs to whoever made the branch, and
        // must not be mistaken for the dead session's orphan and reaped.
        instance.path("config").fieldNames().forEachRemaining(key -> {
            if (Metadata.isMcpKey(key) && !settings.extraConfig().containsKey(key)) update.unset(key);
        });
        update.config(settings.extraConfig());
        // Templates built before free page reporting existed don't carry it to their copies
        if (IncusClient.machineType(instance) == MachineType.VM
                && instance.path("config").path(RAW_QEMU_CONF).asText("").isBlank()) {
            update.config(RAW_QEMU_CONF, FREE_PAGE_REPORTING_CONF);
        }

        if (nicDevice == null) incus.update(name, instance, update);
        else claimAndWrite(incus, name, instance, update, nicDevice, bridge, Set.of(), true);
    }

    /**
     * Give a stopped build container an address the proxy can identify it by, with
     * {@code config} (its template's account pins) in the same write, before its first start.
     *
     * <p>The proxy picks credentials by source address, and knows only instances with a
     * {@link Metadata#STATIC_IP}. A build on a DHCP address would be served every namespace's
     * default account, whatever its template's {@code accounts:} chose (#903). The address comes
     * from the same allocator as a branch's, with the same {@code security.ipv4_filtering} that
     * makes it trustworthy. Unlike a branch no {@code .network} file is pushed: the build's guest
     * keeps DHCP, and Incus's DHCP server hands out the NIC's {@code ipv4.address}. It will not
     * while another MAC holds a lease on that address -- Incus clears only IPv6 leases when a
     * NIC's {@code ipv4.address} changes, and a force-stopped guest never releases its own -- so
     * the allocator also skips every address the bridge has leased, such as the one the parent
     * template's build has just given back. {@link #requireBuildAddress} checks the outcome once
     * the guest is up. Undone by {@link #releaseBuildAddress} once the build has stopped.
     *
     * @param instance the build container, as read by the caller
     * @return the address assigned
     */
    public static String assignBuildAddress(IncusClient incus, String name, JsonNode instance,
                                            Map<String, String> config) {
        if (instance.isMissingNode()) throw new IncusException("Failed to read instance " + name);
        var bridge = BridgeAddress.require(incus);
        var nicDevice = IncusClient.nicDeviceName(instance, BridgeAddress.BRIDGE);
        if (nicDevice == null) {
            throw new IncusException("No NIC device for " + BridgeAddress.BRIDGE + " found on " + name);
        }
        var leased = incus.networkLeaseAddresses(BridgeAddress.BRIDGE);
        var update = new InstanceUpdate();
        update.device(nicDevice, "security.ipv4_filtering", "true");
        update.config(config);
        return claimAndWrite(incus, name, instance, update, nicDevice, bridge, leased, false);
    }

    /** How long {@link #requireBuildAddress} waits for the guest to hold an address at all. */
    static final Duration BUILD_ADDRESS_WAIT = Duration.ofSeconds(15);

    /**
     * Fail the build unless its started guest holds the address {@link #assignBuildAddress}
     * gave it. If the bridge's DHCP server handed it another one, {@code ipv4_filtering} drops
     * all its traffic, and the build would otherwise fail much later as a DNS error that says
     * nothing about why. Waits up to {@link #BUILD_ADDRESS_WAIT} for the address, then fails
     * only if the guest holds another one on the bridge's subnet: addresses on other interfaces
     * (a parent's docker0 or podman bridge) say nothing, and a guest with no bridge address yet
     * is left to the build's own network waits, which diagnose that case.
     */
    public static void requireBuildAddress(IncusClient incus, String name, String assigned) {
        requireBuildAddress(incus, name, assigned, BUILD_ADDRESS_WAIT);
    }

    static void requireBuildAddress(IncusClient incus, String name, String assigned, Duration wait) {
        var deadline = System.nanoTime() + wait.toNanos();
        var held = incus.ipv4Addresses(name);
        while (!held.contains(assigned) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            held = incus.ipv4Addresses(name);
        }
        if (held.contains(assigned)) return;
        // Only now, so a build that got its address pays nothing for it
        var subnet = BridgeAddress.require(incus).subnet();
        var mask = subnet.prefixLen() == 0 ? 0L : (0xFFFFFFFFL << (32 - subnet.prefixLen())) & 0xFFFFFFFFL;
        var onBridge = held.stream().filter(ip -> (CidrUtils.ipToLong(ip) & mask) == subnet.network()).toList();
        if (onBridge.isEmpty()) return;
        throw new IncusException("Build container " + name + " was given " + assigned
                + " but its DHCP client got " + String.join(", ", onBridge) + ". The bridge's DHCP"
                + " server did not hand out the reserved address, most likely because another"
                + " instance still holds a lease on it, and the proxy drops traffic from any other"
                + " address. Retry the build once that lease has expired, or report this with"
                + " 'isx doctor --bundle'.");
    }

    /**
     * Give back the address {@link #assignBuildAddress} claimed, on the stopped template. A
     * template makes no requests of its own and its copies never keep an address, so holding
     * one would only use up the bridge's addresses, one per template. Only the bridge NIC the
     * address was claimed on is touched, and it goes back to the profile's when all the build
     * changed was the address and its filtering.
     */
    public static void releaseBuildAddress(IncusClient incus, String name) {
        var instance = incus.instanceMetadata(name);
        if (instance.isMissingNode()) throw new IncusException("Failed to read instance " + name);
        var update = new InstanceUpdate();
        update.unset(Metadata.STATIC_IP);
        var nicDevice = IncusClient.nicDeviceName(instance, BridgeAddress.BRIDGE);
        var remaining = nicDevice == null ? null
                : IncusClient.withoutStaticAddress(instance.path("devices")).get(nicDevice);
        if (remaining != null) {
            remaining.remove("security.ipv4_filtering");
            var profileDevice = profileDevices(incus, instance).get(nicDevice);
            if (profileDevice != null && remaining.equals(IncusClient.deviceConfig(profileDevice))) {
                update.removeDevice(nicDevice);
            } else {
                update.replaceDevice(nicDevice, remaining);
            }
        }
        incus.update(name, instance, update);
    }

    /** The devices the instance's profiles give it, by name, one read per profile. */
    private static Map<String, JsonNode> profileDevices(IncusClient incus, JsonNode instance) {
        // Later profiles override earlier ones, as Incus expands them
        var devices = new LinkedHashMap<String, JsonNode>();
        for (var profile : instance.path("profiles")) {
            incus.profileDevices(profile.asText()).properties()
                    .forEach(e -> devices.put(e.getKey(), e.getValue()));
        }
        return devices;
    }

    /**
     * Allocate the branch's address and write {@code update} with it, holding the allocation
     * lock throughout. Everything that does not depend on the address is read before it, so
     * concurrent branches wait on each other only for the listing, the push and the write (#815).
     *
     * @param alsoTaken         addresses to skip besides those NICs declare, see
     *                          {@link StaticIpAllocator#claim(IncusClient, BridgeAddress, Set,
     *                          StaticIpAllocator.Output, java.util.function.Consumer)}
     * @param pushNetworkConfig whether to push a static {@code .network} file into a container,
     *                          so it boots without asking DHCP; a branch does, a build does not
     */
    private static String claimAndWrite(IncusClient incus, String name, JsonNode instance,
                                        InstanceUpdate update, String nicDevice, BridgeAddress bridge,
                                        Set<String> alsoTaken, boolean pushNetworkConfig) {
        var vm = IncusClient.machineType(instance) == MachineType.VM;
        var filteringRefused = new AtomicBoolean();
        var output = StaticIpAllocator.Output.TERMINAL;
        var assigned = StaticIpAllocator.claim(incus, bridge, alsoTaken, output, ip -> {
            // A static IP, so no DHCP lease is ever acquired: leases expire across host
            // sleep/wake. See pushStaticNetworkConfig for the guest side.
            if (pushNetworkConfig) BuildOutput.step("Assigning static IP " + ip + ".");
            update.device(nicDevice, "ipv4.address", ip);
            update.config(Metadata.STATIC_IP, ip);
            // Pushed before the write rather than after, so the push is not the last thing
            // before the start: see "Why nothing is pushed into an instance just before it
            // starts".
            if (!vm && pushNetworkConfig) {
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
        return assigned;
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
        var profileDevices = profileDevices(incus, instance);
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
     * {@code isx account set}, or after the account's token is replaced with another user's.
     *
     * <p>Runs after start, because re-deriving means asking the API through the proxy, which is
     * also what makes it correct: the proxy already knows which account this instance uses, so
     * the tool needs no argument beyond the account name.
     *
     * <p>Called from {@code BranchCommand} so a freshly branched instance is right from its
     * first commit, and again from {@code InstancePrep} so one branched {@code --no-start}, or
     * re-pointed later, is reconciled on its next use. Both are needed: only the second can
     * catch a swap on an existing instance, and only the first stops an instance being wrong
     * for as long as nobody happens to run {@code isx shell}.
     *
     * <p>Only namespaces whose tool can re-derive appear here; the ones that cannot were
     * refused at selection time, so there is nothing to reconcile.
     *
     * <p>An instance from a template an older isx built carries no
     * {@link Metadata#ACCOUNT_IDENTITY_VERIFIED}: its stamp came from config.yaml and may claim
     * an identity its {@code .gitconfig} never got. Its first reconcile with an account
     * configured asks the guest once, re-derives what is missing and sets the marker -- one
     * exec and one write for such an instance, none for one whose template this isx built.
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
    static void reconcileAccountIdentities(IncusClient incus, String name, SpawnConfig config,
                                                   Map<String, dev.incusspawn.tool.ToolSetup> knownSetups,
                                                   Consumer<String> progress, Consumer<String> warnings) {
        try {
            var plan = AccountSelection.identityReconcile(config, incus, name, knownSetups);
            if (plan.isEmpty()) return;

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
            var stale = new LinkedHashMap<>(plan.stale());
            // From a template an older isx built: its stamp may claim an identity the guest never
            // got, so ask the guest once. The marker is set only once everything here succeeded.
            plan.unverified().forEach((namespace, account) -> {
                var setup = setups.get(namespace);
                if (setup != null && setup.lacksBakedIdentity(container)) stale.put(namespace, account);
            });
            var updates = new LinkedHashMap<String, String>();
            if (!plan.unverified().isEmpty()) updates.put(Metadata.ACCOUNT_IDENTITY_VERIFIED, "true");
            stale.forEach((namespace, account) -> {
                var setup = setups.get(namespace);
                if (setup == null) return;
                progress.accept("Updating " + namespace + " identity for account '"
                        + account + "'...");
                setup.rebakeForAccount(container, account);
                updates.put(Metadata.accountIdentityKey(namespace), setup.bakedAccountIdentity(config, account));
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
     * Make {@code name} answer exec before a shell or command runs in it: start it if stopped,
     * or recover a VM whose agent does not answer ({@link VmAgentRecovery}). A running instance
     * rebooted without isx -- {@code incus restart}, a reboot in the guest, autostart after a
     * host reboot -- lost its secret with its {@code /run}, and gets a new one
     * ({@link #giveSecretToThisBoot}). Then push a VM's
     * deferred {@code .network} file if it still owes one ({@link Metadata#NETWORK_PUSH_PENDING}),
     * whatever the status it was in: a VM repaired while stopped and then started outside isx
     * (plain {@code incus start}, autostart after a host reboot) boots its old file, and only this
     * push brings it back onto the network.
     *
     * <p>Progress and warnings go to {@code say}, never straight to stdout or stderr, so a caller
     * that owns the terminal (the TUI) can route them.
     *
     * @param instance the instance as read, for its status and the pending mark
     */
    public static void ensureReady(IncusClient incus, String name, JsonNode instance,
                                   MachineType machineType, Consumer<String> say) {
        if ("Stopped".equalsIgnoreCase(instance.path("status").asText(""))) {
            say.accept("Starting " + name + "...");
            startForUse(incus, name, machineType, say);
        } else if (machineType == MachineType.VM) {
            // QEMU may reboot a VM in place, which changes nothing the host can see: only the
            // guest can tell, so the agent probe asks it on the way, for no request of its own
            var probe = probeAgent(incus, name);
            if (probe == null) {
                VmAgentRecovery.restartForAgent(incus, name, say);
            } else if (InstanceSecret.missingIn(probe)) {
                giveSecretToThisBoot(incus, name, null, say);
            }
        } else {
            // Every container reboot is a start: the instance already read says which boot it is
            var bootedAt = bootOf(instance);
            if (!bootedAt.isEmpty()
                    && !bootedAt.equals(instance.path("config").path(Metadata.INSTANCE_SECRET_BOOT).asText(""))) {
                giveSecretToThisBoot(incus, name, bootedAt, say);
            }
        }
        // Only a VM is ever marked
        if (instance.path("config").has(Metadata.NETWORK_PUSH_PENDING)) {
            pushDeferredNetworkConfig(incus, name, instance, bridgePrefixLen(incus),
                    msg -> say.accept(BuildOutput.STEP_INDENT + "Warning: " + msg));
        }
    }

    /**
     * Start a stopped instance for use and wait until it answers exec: the start every path
     * that brings an existing instance up goes through -- {@code isx shell} and {@code isx run},
     * the TUI's shell, and their CA repairs. Prepares its host devices
     * ({@link #prepareHostDevicesForStart}), and gives it a new secret ({@link
     * #rotateInstanceSecret}), which the readiness probe itself puts in place: it costs no
     * request of its own.
     *
     * @return the instance as read once it answered ({@link #recordSecretBoot}), for the caller
     *         to reuse rather than read again; null when there is none
     */
    public static JsonNode startForUse(IncusClient incus, String name, MachineType machineType,
                                       Consumer<String> warn) {
        prepareHostDevicesForStart(incus, name, warn);
        var secret = rotateInstanceSecret(incus, name);
        startInstance(incus, name, warn);
        incus.waitForReady(name, machineType, InstanceSecret.GUEST_SCRIPT, InstanceSecret.guestEnv(secret));
        return recordSecretBoot(incus, name, machineType);
    }

    /**
     * Restart a running instance and wait until it answers, with a new secret (#934): the
     * reboot empties the guest's {@code /run}, so without one the box would be left with none.
     */
    public static void restartForUse(IncusClient incus, String name, MachineType machineType) {
        // Before, not after: once the restart returns the guest is booting, when Incus API calls
        // contend with it. A restart that then fails leaves a guest whose secret no longer
        // matches, which is refused -- the safe way for it to go wrong.
        var secret = rotateInstanceSecret(incus, name);
        incus.restart(name);
        incus.waitForReady(name, machineType, InstanceSecret.GUEST_SCRIPT, InstanceSecret.guestEnv(secret));
        recordSecretBoot(incus, name, machineType);
    }

    /**
     * Give an instance a new secret for the start about to happen, and return it for the
     * caller to put in place once the guest answers (#934). Recording its hash is one write.
     *
     * <p>The proxy is not signalled: on Linux that costs a bridge read, a health call and a fork
     * on every start, and makes the proxy list every instance while this one boots. Nothing
     * needs it sooner -- the previous start's secret went with the guest's {@code /run}, and a
     * caller refused on a snapshot taken before the start refreshes and asks again
     * ({@link dev.incusspawn.proxy.InstanceRegistry#identify}).
     */
    static String rotateInstanceSecret(IncusClient incus, String name) {
        return rotateInstanceSecret(incus, name, Map.of());
    }

    /** {@link #rotateInstanceSecret}, with {@code alongside} recorded in the same write. */
    private static String rotateInstanceSecret(IncusClient incus, String name, Map<String, String> alongside) {
        var stamp = new HashMap<>(alongside);
        var secret = InstanceSecret.stampInto(stamp);
        incus.configSetAll(name, stamp);
        return secret;
    }

    /** When Incus last started {@code instance} -- on every start, a reboot included -- or "". */
    private static String bootOf(JsonNode instance) {
        return instance.path("last_used_at").asText("");
    }

    /**
     * Record which boot of container {@code name} its new secret went to, once the guest
     * answers, so that {@link #ensureReady} can tell a later reboot from it at no cost
     * ({@link Metadata#INSTANCE_SECRET_BOOT}). Its boot is only known after the start, so this
     * reads the instance, and returns that read for the caller's next step to reuse rather than
     * make again (the CA check, {@link CertificateAuthority#fixContainerCaIfNeeded(IncusClient,
     * String, JsonNode)}): the write is the only request it adds. Best-effort: without it, the
     * next shell gives the box another secret, which is the safe way for it to go wrong. A VM is
     * never stamped.
     *
     * @return the instance as read once the guest answered, or null for a VM or a failed read
     */
    static JsonNode recordSecretBoot(IncusClient incus, String name, MachineType machineType) {
        if (machineType == MachineType.VM) return null;
        try {
            // A failed read must not reach the CA check as an empty instance, which it would
            // take for one with no CA to check: null makes it read again
            var instance = incus.instanceMetadataOrThrow(name);
            if (instance == null) return null;
            var bootedAt = bootOf(instance);
            if (!bootedAt.isEmpty()) incus.configSet(name, Metadata.INSTANCE_SECRET_BOOT, bootedAt);
            return instance;
        } catch (RuntimeException e) {
            // The next ensureReady sees an unrecorded boot
            return null;
        }
    }

    /**
     * Give running instance {@code name} a new secret, for a boot isx did not start (#1024):
     * its hash recorded first -- with {@code bootedAt}, the container boot it goes to, when
     * there is one -- then put in place by the script every start runs. Best-effort, like every
     * delivery: a box left without its secret is refused, and the shell must still open.
     */
    private static void giveSecretToThisBoot(IncusClient incus, String name, String bootedAt,
                                             Consumer<String> say) {
        String failure;
        try {
            var secret = rotateInstanceSecret(incus, name,
                    bootedAt == null ? Map.of() : Map.of(Metadata.INSTANCE_SECRET_BOOT, bootedAt));
            // GUEST_SCRIPT never fails, so the same exec asks whether the secret is now there:
            // a write it skipped would otherwise give a VM a new secret on every shell, silently
            var delivery = incus.shellExec(name, InstanceSecret.guestEnv(secret), "sh", "-c",
                    InstanceSecret.GUEST_SCRIPT + "\n" + InstanceSecret.GUEST_CHECK);
            if (delivery.success() && !InstanceSecret.missingIn(delivery.stdout())) return;
            failure = delivery.success() ? "the guest did not keep it" : "exit code " + delivery.exitCode();
        } catch (RuntimeException e) {
            failure = e.getMessage();
        }
        // The stamp went with the hash, before the guest had the secret: unset, so the next
        // shell tries again rather than taking this boot for one that holds it. Only on this
        // path, which costs the one that works nothing.
        if (bootedAt != null) {
            try {
                incus.configSet(name, Metadata.INSTANCE_SECRET_BOOT, "");
            } catch (RuntimeException ignored) {
                // Then the box stays without a secret until isx starts it: refused, which is safe
            }
        }
        say.accept(BuildOutput.STEP_INDENT + "Warning: could not give " + name
                + " a new instance secret after its reboot: " + failure);
    }

    /**
     * Ask a running VM's agent whether it answers, and its guest whether it still holds its
     * secret ({@link InstanceSecret#GUEST_CHECK}): what the guest printed, or null if the agent
     * does not answer.
     */
    private static String probeAgent(IncusClient incus, String name) {
        try {
            var result = incus.shellExec(name, "sh", "-c", InstanceSecret.GUEST_CHECK);
            return result != null && result.success() ? result.stdout() : null;
        } catch (RuntimeException agentDown) {
            return null;
        }
    }

    /** Whether a command runs and succeeds in a running instance; Incus refuses exec when its agent is down. */
    private static boolean execSucceeds(IncusClient incus, String name, String... command) {
        try {
            return incus.shellExec(name, command).success();
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
        startInstance(incus, name, System.err::println);
    }

    /**
     * As above, reporting the loss of protection to {@code warn} rather than stderr, for callers
     * that may own the terminal (the TUI).
     */
    public static void startInstance(IncusClient incus, String name, Consumer<String> warn) {
        try {
            incus.start(name);
        } catch (RuntimeException e) {
            if (!looksLikeIpFilteringFailure(e) || !disableIpFiltering(incus, name, warn)) throw e;
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

    private static boolean disableIpFiltering(IncusClient incus, String name, Consumer<String> warn) {
        try {
            var nic = incus.findNic(name, BridgeAddress.BRIDGE);
            if (nic == null || !"true".equals(nic.config().get("security.ipv4_filtering"))) {
                return false;
            }
            incus.deviceConfigSet(name, nic.name(), "security.ipv4_filtering", "false");
            warn.accept(BuildOutput.STEP_INDENT
                    + "Warning: this host cannot enforce IP spoofing protection, so it has been"
                    + " disabled on " + name + ".\n" + BuildOutput.STEP_INDENT
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
        pushStaticNetworkConfig(incus, name, CONTAINER_NIC_MATCH, ip, gateway, prefixLen, STDERR_WARN);
    }

    static final String NETWORK_FILE = "/etc/systemd/network/10-eth0.network";

    /** Incus names a container's NIC after the device's {@code name}, which isx sets to eth0. */
    private static final String CONTAINER_NIC_MATCH = "Name=eth0";

    private static final Pattern MAC = Pattern.compile("[0-9a-f]{2}(:[0-9a-f]{2}){5}");

    /**
     * Push a systemd-networkd static config into the container, overwriting the
     * DHCP config the template carries from build time. This makes the branch
     * boot directly into static addressing (instant network, no DHCP round trip).
     *
     * <p>Root-owned 0644 explicitly: the mode would otherwise be the temp file's 0600, which
     * {@code systemd-networkd}, running as {@code systemd-network}, cannot read. A template's
     * own {@code 10-eth0.network} hid this, since overwriting keeps a file's mode; a template
     * without one got a branch with no network.
     *
     * @param match the {@code [Match]} line naming the NIC: {@link #CONTAINER_NIC_MATCH} for a
     *              container, the MAC address for a VM (see {@link #pushVmNetworkConfig})
     * @return whether the file was pushed
     */
    static boolean pushStaticNetworkConfig(IncusClient incus, String name, String match, String ip,
                                        String gateway, int prefixLen, Consumer<String> warn) {
        var content = "[Match]\n" + match + "\n\n[Network]\n"
                + "Address=" + ip + "/" + prefixLen + "\n"
                + "Gateway=" + gateway + "\n"
                + "DNS=" + gateway + "\n";
        try {
            var tmp = Files.createTempFile("isx-network-", ".network");
            try {
                Files.writeString(tmp, content);
                incus.filePush(tmp.toString(), name, NETWORK_FILE, "0", "0", "0644");
            } finally {
                Files.deleteIfExists(tmp);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            warn.accept("failed to push static network config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Push files that can't be written to a stopped VM (file push requires the
     * incus-agent). Call after {@code incus.start()} + {@code waitForReady()}.
     * SSH keys and terminfo are not among them: {@link #setupRuntime} writes those.
     */
    public static void pushDeferredVmFiles(IncusClient incus, String name, NetworkMode networkMode) {
        if (networkMode != NetworkMode.AIRGAP) {
            pushVmNetworkConfig(incus, name, incus.instanceMetadata(name), bridgePrefixLen(incus),
                    STDERR_WARN);
        }
    }

    /**
     * Push a running VM's static {@code .network} file, from one read of the instance.
     *
     * <p>A VM guest keeps its kernel's predictable name for the NIC ({@code enp5s0} on Incus's
     * PCIe layout) whatever the device's {@code name} says, so the file matches the NIC by the
     * MAC address Incus gives the device rather than by any name (#997). The permanent one, so
     * a VLAN or bridge a user builds on the NIC, which takes over its MAC, does not match too.
     * Until the file applies, at the next boot or {@code networkctl reload}, the VM has the same
     * address from DHCP: Incus's DHCP server hands out the NIC's {@code ipv4.address}.
     *
     * @param instance the instance as read; none of what is used here changes across a start
     */
    private static VmPush pushVmNetworkConfig(IncusClient incus, String name, JsonNode instance,
                                              int prefixLen, Consumer<String> warn) {
        var config = instance.path("config");
        var ip = config.path(Metadata.STATIC_IP).asText("");
        var gateway = config.path(Metadata.STATIC_GATEWAY).asText("");
        if (ip.isEmpty() || gateway.isEmpty()) return VmPush.NOTHING_TO_PUSH;
        var nic = IncusClient.nic(instance, BridgeAddress.BRIDGE);
        // A MAC set on the device wins; Incus records the one it generates in volatile config
        var mac = nic == null ? "" : nic.config().getOrDefault("hwaddr",
                config.path("volatile." + nic.name() + ".hwaddr").asText("")).toLowerCase(Locale.ROOT);
        if (!MAC.matcher(mac).matches()) {
            // A file matching no link is inert, and one matching every link would put the
            // address on a nested bridge too: leave the VM on DHCP, which gives it the same one.
            warn.accept("could not find the MAC address of " + name
                    + "'s NIC; it keeps the address DHCP gives it");
            return VmPush.NOTHING_TO_PUSH;
        }
        return pushStaticNetworkConfig(incus, name, "PermanentMACAddress=" + mac, ip, gateway,
                prefixLen, warn) ? VmPush.PUSHED : VmPush.FAILED;
    }

    private enum VmPush { PUSHED, NOTHING_TO_PUSH, FAILED }

    /**
     * Detect and fix stale static IP configuration caused by a bridge subnet
     * change. Compares the instance's stored {@code STATIC_IP} against the
     * current bridge subnet. If stale, allocates a new IP on the current
     * subnet and updates the NIC device config, metadata, and (for containers)
     * the in-guest {@code .network} file.
     *
     * <p>For VMs the {@code .network} file cannot be pushed while stopped
     * (requires the incus-agent): the VM is marked {@link Metadata#NETWORK_PUSH_PENDING}
     * instead, and {@link #ensureReady} pushes it once the VM runs.
     *
     * @return true if a fix was applied
     */
    public static boolean fixStaticIpIfNeeded(IncusClient incus, String name) {
        return fixStaticIpIfNeeded(incus, name, StaticIpAllocator.Output.TERMINAL);
    }

    /** @param output where the reassignment, and any wait for the allocation lock, is reported */
    public static boolean fixStaticIpIfNeeded(IncusClient incus, String name,
                                              StaticIpAllocator.Output output) {
        return fixStaticIpIfNeeded(incus, name, output, null);
    }

    public static boolean fixStaticIpIfNeeded(IncusClient incus, String name,
                                              StaticIpAllocator.Output output,
                                              MachineType machineType) {
        var storedIp = incus.configGet(name, Metadata.STATIC_IP);
        if (storedIp.isEmpty()) return false;
        var bridge = BridgeAddress.read(incus);
        return bridge.isPresent() && fixStaticIp(incus, name, storedIp, bridge.get(), output, machineType);
    }

    private static boolean fixStaticIp(IncusClient incus, String name, String storedIp,
                                       BridgeAddress bridge, StaticIpAllocator.Output output,
                                       MachineType machineType) {
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
        var resolvedType = machineType != null ? machineType : incus.machineType(name);
        // The VM's file still holds the old address and is only pushed while it runs
        if (resolvedType == MachineType.VM) updates.put(Metadata.NETWORK_PUSH_PENDING, "true");
        incus.configSetAll(name, updates);

        if (resolvedType == MachineType.CONTAINER) {
            pushStaticNetworkConfig(incus, name, CONTAINER_NIC_MATCH, newIp, newGateway,
                    bridge.prefixLen(), output.warn());
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
                    var name = stale.getKey();
                    var listed = stale.getValue();
                    var type = MachineType.fromIncus(listed);
                    var storedIp = listed.path("config").path(Metadata.STATIC_IP).asText("");
                    if (fixStaticIp(incus, name, storedIp, bridge.get(),
                            StaticIpAllocator.Output.TERMINAL, type)) {
                        fixed++;
                        // A running VM can take its file now; a stopped one gets it at its next
                        // start. Read again after the fix, which wrote the address the file needs.
                        if (type == MachineType.VM
                                && "Running".equalsIgnoreCase(listed.path("status").asText(""))) {
                            pushDeferredNetworkConfig(incus, name, incus.instanceMetadata(name),
                                    bridge.get().prefixLen(), STDERR_WARN);
                        }
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
     * Deliver the {@code .network} file a running VM owes ({@link Metadata#NETWORK_PUSH_PENDING}):
     * push it, apply it with {@code networkctl reload}, and clear the mark once settled. A failure
     * leaves the mark, so the next shell tries again.
     *
     * @param instance the instance as read after its address was reassigned
     */
    static void pushDeferredNetworkConfig(IncusClient incus, String name, JsonNode instance,
                                          int prefixLen, Consumer<String> warn) {
        var settled = switch (pushVmNetworkConfig(incus, name, instance, prefixLen, warn)) {
            case FAILED -> false;
            // Nothing to push leaves the VM on DHCP, which gives it its address: done too
            case NOTHING_TO_PUSH -> true;
            // The VM already booted with the old .network file. reload re-reads the files
            // and reconfigures every link whose file changed, so it needs no interface
            // name; reconfigure alone would re-apply the config networkd already had.
            case PUSHED -> {
                if (execSucceeds(incus, name, "networkctl", "reload")) yield true;
                // Still pending: its old address is dropped until a reboot applies the file
                warn.accept("could not apply the new network config in " + name
                        + "; restart it, or the next shell tries again");
                yield false;
            }
        };
        if (!settled) return;
        try {
            incus.configUnset(name, Metadata.NETWORK_PUSH_PENDING);
        } catch (Exception ignored) {
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

    /** The listed instances whose static IP is off the bridge's subnet, by name. */
    private static Map<String, JsonNode> staleStaticIps(IncusClient incus, BridgeAddress bridge) {
        // The listing already carries each instance's config: one request, not one per instance.
        JsonNode instances;
        try {
            instances = JSON.readTree(incus.listJsonConfig());
        } catch (IOException e) {
            throw new IncusException("Failed to parse instance list: " + e.getMessage());
        }
        var stale = new LinkedHashMap<String, JsonNode>();
        for (var instance : instances) {
            var name = instance.path("name").asText("");
            if (name.isEmpty()) continue;
            var storedIp = instance.path("config").path(Metadata.STATIC_IP).asText("");
            if (storedIp.isEmpty()) continue;
            if (!CidrUtils.isInSubnet(storedIp, bridge.subnet())) {
                stale.put(name, instance);
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
     * Repair an existing, stopped instance before starting it. Every path that starts one for use
     * (the {@code isx shell}/{@code run} prep, the TUI, {@link #ensureReady}, VM agent recovery)
     * goes through here, so a repair placed here reaches all of them, and any path that merges
     * them must keep calling it.
     *
     * <ul>
     *   <li>Host-side devices: a host-resource source that has disappeared and a missing zmx socket
     *       directory otherwise fail Incus start validation with {@code Missing source path}.</li>
     *   <li>IP spoofing protection ({@link #rearmIpFiltering}): turned back on where it is off.</li>
     * </ul>
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
        rearmIpFiltering(incus, name, instance, warn);
    }

    /**
     * Turn IP spoofing protection back on for an instance that does not have it: one branched
     * before isx set it, or one {@link #startInstance} had to start without it on a host that
     * could not enforce it -- every Mac, until the appliance kernel gained the nft bridge family
     * (#905). Re-arming on every stopped start is what lets such an instance regain protection
     * once its host can enforce it, instead of staying open until it is re-branched; where the
     * host still cannot, the start falls back again and says so.
     *
     * <p>Only while stopped, which is all this is called for: NIC device changes on a live
     * instance are not reliably applied. Reads the instance the other repairs already read, so
     * an instance that has it (every one branched since) costs no request at all.
     */
    static void rearmIpFiltering(IncusClient incus, String name, JsonNode instance, Consumer<String> warn) {
        var nic = IncusClient.nic(instance, BridgeAddress.BRIDGE);
        // No bridge NIC: airgap, or a hand-made instance -- nothing to filter.
        if (nic == null || "true".equals(nic.config().get("security.ipv4_filtering"))) return;
        applyIpFiltering(incus, name, nic.name(),
                msg -> warn.accept(BuildOutput.STEP_INDENT + "Warning: " + msg));
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
     */
    public static void integrateWithHost(IncusClient incus, String name, InstanceType instanceType,
                                         MachineType machineType) {
        var hrJson = incus.configGet(name, Metadata.HOST_RESOURCES);
        var hostResources = HostResourceSetup.deserialize(hrJson);
        if (!hostResources.isEmpty()) {
            BuildOutput.step("Applying host resources.");
            HostResourceSetup.applyForInstance(incus, name, hostResources, machineType);
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
                subnetDiag, terminfo, config.getOrDefault(Metadata.STATIC_IP, ""),
                Metadata.templateOf(config));
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

    /** @param staticIp the address {@code configureBranch} assigned, or "" for none */
    public record RuntimeConfig(String buildSourceJson, boolean hasSshKeys,
                                String workdir, String shellCommand,
                                String subnetDiagnostic, String terminfo, String staticIp,
                                String templateName) {

        public IncusClient.ShellPrep toShellPrep() {
            return IncusClient.ShellPrep.fromPrefetched(
                    workdir, shellCommand, buildSourceJson, subnetDiagnostic,
                    terminfo != null, templateName);
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
     * @param secret     the instance secret to put in place (#934), or null for none
     */
    public static void setupRuntime(IncusClient incus, String name,
                                   NetworkMode networkMode, RuntimeConfig prefetched, String secret) {
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
        var env = secret != null ? InstanceSecret.guestEnv(secret) : Map.<String, String>of();
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
        setupRuntime(incus, name, networkMode, null, null);
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
