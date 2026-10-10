package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.BridgeAddress;
import dev.incusspawn.incus.CidrUtils;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.InstanceUpdate;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.StaticIpAllocator;
import dev.incusspawn.util.BuildOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
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
 * An instance's address on the bridge: static IP assignment and subnet migration, IP spoofing
 * protection, NIC masking and the guest's {@code .network} file. Split out of
 * {@link InstanceLifecycle}, which calls it.
 */
public final class InstanceNetwork {

    private static final ObjectMapper JSON = new ObjectMapper();

    private InstanceNetwork() {}

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
    static String claimAndWrite(IncusClient incus, String name, JsonNode instance,
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
    static JsonNode unmaskNics(IncusClient incus, JsonNode instance, InstanceUpdate update) {
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

    /** Whether a command runs and succeeds in a running instance; Incus refuses exec when its agent is down. */
    private static boolean execSucceeds(IncusClient incus, String name, String... command) {
        try {
            return incus.shellExec(name, command).success();
        } catch (RuntimeException agentDown) {
            return false;
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

    static boolean disableIpFiltering(IncusClient incus, String name, Consumer<String> warn) {
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
     * SSH keys and terminfo are not among them: {@link RuntimeSetup#setupRuntime} writes those.
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
     * instead, and {@link InstanceLifecycle#ensureReady} pushes it once the VM runs.
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
        return migrateAllInstancesToNewSubnet(incus, StaticIpAllocator.Output.TERMINAL);
    }

    /** @param output where each reassignment, and each warning, is reported */
    static int migrateAllInstancesToNewSubnet(IncusClient incus, StaticIpAllocator.Output output) {
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
                    // Incus checks a running instance's NIC update against the address it holds
                    // now, which is off the new subnet: it refuses the update that would move it
                    // (#1009). Only a stopped instance can be moved.
                    var status = listed.path("status").asText("");
                    if ("Frozen".equalsIgnoreCase(status)) {
                        // Paused on purpose, and a stop would wait on a guest that cannot answer
                        output.warn().accept(name + " is frozen and was not moved: resume it, then repair it again");
                        continue;
                    }
                    var running = "Running".equalsIgnoreCase(status);
                    if (running) {
                        output.warn().accept("Stopping " + name + " to move it onto the bridge's new subnet;"
                                + " it is started again once moved");
                        try {
                            incus.stop(name);
                        } catch (RuntimeException e) {
                            // Never forced: a guest that ignores the shutdown is left running, and one
                            // that stops later is moved by its next shell
                            output.warn().accept(name + " did not stop in time and was not moved (" + e.getMessage()
                                    + "): once it is stopped, repair it again");
                            continue;
                        }
                    }
                    boolean moved = false;
                    try {
                        moved = fixStaticIp(incus, name, storedIp, bridge.get(), output, type);
                        if (moved) fixed++;
                    } finally {
                        // Moved or not, it is left running, as it was found
                        if (running) startAgain(incus, name, type, moved, bridge.get().prefixLen(), output.warn());
                    }
                } catch (Exception e) {
                    output.warn().accept("failed to migrate " + stale.getKey() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            output.warn().accept("could not list instances for migration: " + e.getMessage());
        }
        return fixed;
    }

    /**
     * Start an instance {@link #migrateAllInstancesToNewSubnet} stopped to move it. A container
     * booted with its new file; a VM booted with its old one, and takes the new one now that it
     * runs. A failure is a warning, since the next shell starts it and pushes what it owes. The stopped branch of
     * {@link InstanceLifecycle#ensureReady}, without its bridge read: the caller already has the prefix length.
     */
    private static void startAgain(IncusClient incus, String name, MachineType type, boolean moved,
                                   int prefixLen, Consumer<String> warn) {
        try {
            InstanceLifecycle.startForUse(incus, name, type, warn);
            // Read after the fix, which wrote the address the file needs
            if (moved && type == MachineType.VM) {
                pushDeferredNetworkConfig(incus, name, incus.instanceMetadata(name), prefixLen, warn);
            }
        } catch (Exception e) {
            warn.accept("could not start " + name + " again: " + e.getMessage()
                    + "; 'isx shell " + name + "' finishes the repair");
        }
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

    /**
     * Turn IP spoofing protection back on for an instance that does not have it: one branched
     * before isx set it, or one {@link InstanceLifecycle#startInstance} had to start without it on a host that
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
}
