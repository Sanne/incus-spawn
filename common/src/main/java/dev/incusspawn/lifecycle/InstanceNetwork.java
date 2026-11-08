package dev.incusspawn.lifecycle;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
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
import dev.incusspawn.util.Output;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * An instance's address on the bridge: static IP assignment and subnet migration, IP spoofing
 * protection, NIC masking and the guest's {@code .network} file. Split out of
 * {@link InstanceLifecycle}, which calls it.
 */
public final class InstanceNetwork {

    private InstanceNetwork() {}

    /**
     * Give a stopped build container an address the proxy can identify it by, with
     * {@code config} (its template's account pins) in the same write, before its first start.
     *
     * <p>The proxy picks credentials by source address, and knows only instances with a
     * {@link Metadata#STATIC_IP}. An unregistered DHCP address cannot authorize credential
     * injection, even when the build uses configured defaults (#1107). The address comes
     * from the same allocator as a branch's, with the same {@code security.ipv4_filtering} that
     * makes it trustworthy. Unlike a branch no {@code .network} file is pushed: the build's guest
     * keeps DHCP, and Incus's DHCP server hands out the NIC's {@code ipv4.address}. It will not
     * while another MAC holds a lease on that address -- Incus clears only IPv6 leases when a
     * NIC's {@code ipv4.address} changes, and a force-stopped guest never releases its own -- so
     * the allocator also skips every address the bridge has leased, such as the one the parent
     * template's build has just given back. {@link #requireBuildAddress} checks the outcome once
     * the guest is up. Undone by {@link #releaseBuildAddress} once the build has stopped.
     *
     * @param read the build container, as read by the caller
     * @return the address assigned
     */
    public static String assignBuildAddress(IncusClient incus, String name, IncusClient.InstanceRead read,
                                            Map<String, String> config) {
        var instance = read.metadata();
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
        return claimAndWrite(incus, name, read, update, nicDevice, bridge, leased, false);
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
        requireBuildAddress(incus, name, assigned, BUILD, BUILD_ADDRESS_WAIT);
    }

    /**
     * What a failed {@link #requireBuildAddress} names: the instance, and what to retry once the
     * lease has expired.
     *
     * @param subject how the failure names the instance, as in "Build container"
     * @param retry   what the user runs again, as in "the build"
     */
    public record AddressUse(String subject, String retry) {}

    /** A template build's, the default. */
    public static final AddressUse BUILD = new AddressUse("Build container", "the build");

    /** {@link #requireBuildAddress} for another use of a build address, such as {@code isx update-all}'s. */
    public static void requireBuildAddress(IncusClient incus, String name, String assigned, AddressUse use) {
        requireBuildAddress(incus, name, assigned, use, BUILD_ADDRESS_WAIT);
    }

    static void requireBuildAddress(IncusClient incus, String name, String assigned, Duration wait) {
        requireBuildAddress(incus, name, assigned, BUILD, wait);
    }

    static void requireBuildAddress(IncusClient incus, String name, String assigned, AddressUse use, Duration wait) {
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
        throw new IncusException(use.subject() + " " + name + " was given " + assigned
                + " but its DHCP client got " + String.join(", ", onBridge) + ". The bridge's DHCP"
                + " server did not hand out the reserved address, most likely because another"
                + " instance still holds a lease on it, and the proxy drops traffic from any other"
                + " address. Retry " + use.retry() + " once that lease has expired, or report this with"
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
        IncusClient.retryWhileChanged(() -> releaseBuildAddressOnce(incus, name));
    }

    /**
     * {@link #releaseBuildAddress}, after a build or an {@code isx update-all}, warning to
     * {@code out} rather than failing: the template is built, and an address it keeps costs one
     * of the bridge's addresses, not correctness -- its copies never carry it, and it is freed
     * when the template is next updated, rebuilt or removed. Never throws.
     */
    public static void releaseBuildAddressOrWarn(IncusClient incus, String name, Output out) {
        try {
            releaseBuildAddress(incus, name);
        } catch (RuntimeException e) {
            out.warn("Could not release " + name + "'s static IP: " + e.getMessage()
                    + ". The template keeps it until it is next updated, rebuilt or removed.");
        }
    }

    private static void releaseBuildAddressOnce(IncusClient incus, String name) {
        var read = incus.readInstance(name);
        var instance = read.metadata();
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
        incus.update(name, read, update);
    }

    /** The devices the instance's profiles give it, by name, one read per profile. */
    private static Map<String, JqValue> profileDevices(IncusClient incus, JqValue instance) {
        // Later profiles override earlier ones, as Incus expands them
        var devices = new LinkedHashMap<String, JqValue>();
        for (var profile : instance.getField("profiles").asList()) {
            var profileDevices = incus.profileDevices(profile.asDisplayText(""));
            if (profileDevices instanceof JqObject devicesObj) {
                devicesObj.forEach(devices::put);
            }
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
     *                          Output, java.util.function.Consumer)}
     * @param pushNetworkConfig whether to push a static {@code .network} file into a container,
     *                          so it boots without asking DHCP; a branch does, a build does not
     */
    static String claimAndWrite(IncusClient incus, String name, IncusClient.InstanceRead read,
                                        InstanceUpdate update, String nicDevice, BridgeAddress bridge,
                                        Set<String> alsoTaken, boolean pushNetworkConfig) {
        var vm = IncusClient.machineType(read.metadata()) == MachineType.VM;
        var filteringRefused = new AtomicBoolean();
        var output = BuildOutput.bound();
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
                pushStaticNetworkConfig(incus, name, CONTAINER_NIC_MATCH, ip, bridge.gateway(),
                        bridge.prefixLen(), output);
            }
            try {
                incus.update(name, read, update);
            } catch (IncusClient.InstanceChangedException e) {
                throw e; // read again by the caller, which builds its update anew
            } catch (IncusException e) {
                // Failing to pin the address is fatal, failing to enable filtering only warns,
                // and one write cannot say which of them Incus refused. Incus rolls a refused
                // write back whole, so retry without filtering: if that fails too, the error is
                // the real one. If it goes through, enable filtering on its own, so a first
                // failure that had nothing to do with filtering does not leave the instance
                // without it.
                incus.update(name, read,
                        update.withoutDeviceProperty(nicDevice, "security.ipv4_filtering"));
                filteringRefused.set(true);
            }
        });
        // After the claim: the address is written, so no one need wait on this write
        if (filteringRefused.get()) applyIpFiltering(incus, name, nicDevice, output);
        return assigned;
    }

    /** The devices Incus would attach to {@code instance}, its own or a profile's, that match {@code which}, sorted. */
    static List<String> expandedDevices(JqValue instance, Predicate<JqValue> which) {
        if (!(instance.getField("expanded_devices") instanceof JqObject devices)) return List.of();
        return devices.keys().stream().filter(device -> which.test(devices.path(device))).sorted().toList();
    }

    /**
     * Why {@code instance} cannot have airgap or proxy-only, when it passes host hardware through
     * ({@link IncusClient#isPassthrough}); null when it does not. isx cannot tell a network card
     * from other hardware, so it refuses rather than mask hardware the user attached (#1518).
     *
     * @param subject how the refusal names it, as the subject of "passes"
     * @param remedy  what the user can do besides passing a GPU as a {@code gpu} device
     */
    static String passthroughRefusal(String subject, JqValue instance, String remedy) {
        var devices = expandedDevices(instance, IncusClient::isPassthrough);
        if (devices.isEmpty()) return null;
        var pci = devices.stream().anyMatch(device -> "pci".equals(
                instance.path("expanded_devices").path(device).pathText("type", "")));
        return subject + " passes host hardware through (" + String.join(", ", devices) + "), which could be a"
                + " network card that reaches the network directly; isx cannot tell. "
                + (pci ? "Pass a GPU as a 'gpu' device instead of 'pci', which Incus accepts only for a GPU. Otherwise "
                        : "To keep it, ") + remedy;
    }

    /** {@link #passthroughRefusal} for an existing instance in {@code mode} ("airgapped", "proxy-only"), which isx will not start or use. */
    static String passthroughRefusal(String name, String mode, JqValue instance) {
        return passthroughRefusal("'" + name + "' is " + mode + ", but it", instance,
                "remove the device from it or its profile; isx will not use it until then. 'isx destroy " + name + "' removes it.");
    }

    private static final Map<String, String> MASKED_DEVICE = Map.of("type", "none");

    /**
     * Take every network device away from the instance ({@link IncusClient#isNetworkDevice}: NICs,
     * InfiniBand devices and proxies bound in the instance), whichever
     * network it is on and wherever it comes from. Removing an instance device only drops the
     * instance's own copy: a NIC the {@code default} profile provides, or one the instance
     * overrides from it (a clone's static IP), applies again. A {@code type: none} device of the
     * same name masks it. The masked names are stamped ({@link Metadata#AIRGAP_MASKED}), with
     * those of an airgapped source, whose masks the copy carried.
     */
    static void maskNics(IncusClient incus, JqValue instance, InstanceUpdate update) {
        var masks = masks(incus, instance);
        masks.devices().forEach((name, mask) -> update.replaceDevice(name, mask));
        update.config(Metadata.AIRGAP_MASKED, masks.stamp());
    }

    /**
     * What {@link #maskNics} writes: a {@code type: none} device over each network device, and
     * the {@link Metadata#AIRGAP_MASKED} stamp naming them with those an airgapped source masked.
     */
    record Masks(Map<String, Map<String, String>> devices, String stamp) {}

    /** The {@link Masks} that airgap {@code instance}, or a copy of it with its devices. */
    static Masks masks(IncusClient incus, JqValue instance) {
        var masked = new LinkedHashSet<String>();
        if (NetworkMode.isAirgapped(instance)) {
            var stamped = stampedMasks(instance);
            masked.addAll(stamped != null ? stamped : preStampMasks(instance, profileDevices(incus, instance)));
        }
        var devices = new LinkedHashMap<String, Map<String, String>>();
        for (var name : expandedDevices(instance, IncusClient::isNetworkDevice)) {
            devices.put(name, MASKED_DEVICE);
            masked.add(name);
        }
        return new Masks(devices, masked.isEmpty() ? "," : String.join(",", masked));
    }

    /** The devices airgap stamped as masked, or null when it predates the stamp. */
    private static Set<String> stampedMasks(JqValue instance) {
        var stamp = instance.path("config").pathText(Metadata.AIRGAP_MASKED, "");
        if (stamp.isEmpty()) return null;
        var names = new LinkedHashSet<String>();
        for (var name : stamp.split(",")) if (!name.isEmpty()) names.add(name);
        return names;
    }

    /**
     * What airgap masked before it stamped the names: NICs only, so its {@code type: none}
     * devices over a profile NIC.
     */
    private static Set<String> preStampMasks(JqValue instance, Map<String, JqValue> profileDevices) {
        var names = new LinkedHashSet<String>();
        if (instance.getField("devices") instanceof JqObject declared) {
            declared.forEach((name, device) -> {
                var profileDevice = profileDevices.get(name);
                if ("none".equals(device.pathText("type", ""))
                        && profileDevice != null && IncusClient.isNic(profileDevice)) names.add(name);
            });
        }
        return names;
    }

    /**
     * Put back the profile network devices {@link #maskNics} masked, as instance devices
     * overwriting the masks: a PATCH replaces a device whole, so this needs no separate removal.
     * Returns the instance as it will then expand, so the static IP is set on the NIC that comes
     * back. Only the masks airgap wrote ({@link #stampedMasks}, {@link #preStampMasks}) are
     * touched: a {@code type: none} device the template set itself stays, even over a profile
     * network device.
     */
    static JqValue unmaskNics(IncusClient incus, JqValue instance, InstanceUpdate update) {
        var profileDevices = profileDevices(incus, instance);
        var stamped = stampedMasks(instance);
        var masks = stamped != null ? stamped : preStampMasks(instance, profileDevices);
        var expanded = JqObject.builder(8);
        var current = instance.getField("expanded_devices");
        if (current instanceof JqObject currentObj) currentObj.forEach(expanded::put);
        var declared = instance.getField("devices");
        if (declared instanceof JqObject declaredObj) {
            declaredObj.forEach((name, device) -> {
                var profileDevice = profileDevices.get(name);
                if (masks.contains(name) && "none".equals(device.pathText("type", ""))
                        && profileDevice != null && IncusClient.isNetworkDevice(profileDevice)) {
                    expanded.put(name, profileDevice);
                    // A changed device is sent whole from the expanded view: the profile's device
                    update.device(name, "type", profileDevice.pathText("type", ""));
                }
            });
        }
        if (!(instance instanceof JqObject root)) return instance;
        return root.with("expanded_devices", expanded.build());
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
     * separation between instances that is not enforced, in one warning to {@code out}.
     */
    private static void applyIpFiltering(IncusClient incus, String name, String nicDevice,
                                         Output out) {
        try {
            incus.deviceConfigSet(name, nicDevice, "security.ipv4_filtering", "true");
        } catch (RuntimeException e) {
            out.warn("could not enable IP spoofing protection on " + name + ": " + e.getMessage()
                    + "; instances on this host can impersonate each other's credential accounts.");
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

    static boolean disableIpFiltering(IncusClient incus, String name, Output out) {
        try {
            var nic = incus.findNic(name, BridgeAddress.BRIDGE);
            if (nic == null || !"true".equals(nic.config().get("security.ipv4_filtering"))) {
                return false;
            }
            // A host that cannot install the per-NIC rules cannot install a proxy-only ACL either,
            // and that instance must not start without it: its failure stands (#1137)
            if (ProxyOnlyAcl.anyKey(nic.config())) return false;
            incus.deviceConfigSet(name, nic.name(), "security.ipv4_filtering", "false");
            out.warn("this host cannot enforce IP spoofing protection, so it has been disabled on "
                    + name + ": instances on this host can impersonate each other's credential accounts.");
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static int bridgePrefixLen(IncusClient incus) {
        return BridgeAddress.read(incus).map(BridgeAddress::prefixLen).orElse(24);
    }

    static void pushStaticNetworkConfig(IncusClient incus, String name,
                                        String ip, String gateway, int prefixLen) {
        pushStaticNetworkConfig(incus, name, CONTAINER_NIC_MATCH, ip, gateway, prefixLen, BuildOutput.bound());
    }

    static final String NETWORK_FILE = "/etc/systemd/network/10-eth0.network";

    /** Incus names a container's NIC after the device's {@code name}, which isx sets to eth0. */
    private static final String CONTAINER_NIC_MATCH = "Name=eth0";

    /**
     * Shell that puts a container's static address and default route from {@link #NETWORK_FILE} in
     * place at once, so the first exec after a start need not wait for networkd (#801). networkd is
     * ordered after udev and configures eth0 ~200 ms into boot, while exec answers at ~80 ms; it then
     * adopts what it finds, since it is the configuration it would have applied. {@code brd +} and
     * {@code proto static} are what make the two identical to its own, so systemd 262 replaces
     * neither and the guest is never without them.
     *
     * <p>Only a file for eth0 with one address, as {@link #pushStaticNetworkConfig} writes it: a VM's
     * matches its NIC by MAC (#997), and any other file stays networkd's to read. Best-effort and
     * idempotent: an airgapped container has no eth0, and the readiness probe may run it twice.
     */
    static final String APPLY_STATIC_ADDRESS = "{ isx_net='" + NETWORK_FILE + "'; "
            + "if grep -qx '" + CONTAINER_NIC_MATCH + "' \"$isx_net\" && [ \"$(grep -c '^Address=' \"$isx_net\")\" = 1 ]; then "
            + "isx_addr=$(sed -n 's/^Address=//p' \"$isx_net\"); "
            + "isx_gw=$(sed -n '/^Gateway=/{s///p;q;}' \"$isx_net\"); "
            + "ip addr replace \"$isx_addr\" brd + dev eth0 && ip link set eth0 up "
            + "&& { [ -z \"$isx_gw\" ] || ip route replace default via \"$isx_gw\" dev eth0 proto static; }; "
            + "fi; } >/dev/null 2>&1; unset isx_net isx_addr isx_gw; true";

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
                                        String gateway, int prefixLen, Output out) {
        var content = "[Match]\n" + match + "\n\n[Network]\n"
                + "Address=" + ip + "/" + prefixLen + "\n"
                + "Gateway=" + gateway + "\n"
                + "DNS=" + gateway + "\n";
        try {
            incus.filePushContent(content, name, NETWORK_FILE, "0", "0", "0644");
            return true;
        } catch (IOException | RuntimeException e) {
            out.warn("failed to push static network config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Push files that can't be written to a stopped VM (file push requires the
     * incus-agent). Call after {@code incus.start()} + {@code waitForReady()}.
     * SSH keys and terminfo are not among them: {@link RuntimeSetup#setupRuntime} writes those.
     *
     * @return the instance as read after its start, for the checks that follow; null when nothing
     *         was read
     */
    public static JqValue pushDeferredVmFiles(IncusClient incus, String name, NetworkMode networkMode) {
        if (networkMode == NetworkMode.AIRGAP) return null;
        var instance = incus.instanceMetadata(name);
        pushVmNetworkConfig(incus, name, instance, bridgePrefixLen(incus), BuildOutput.bound());
        return instance.isMissingNode() ? null : instance;
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
    private static VmPush pushVmNetworkConfig(IncusClient incus, String name, JqValue instance,
                                              int prefixLen, Output out) {
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
            out.warn("could not find the MAC address of " + name
                    + "'s NIC; it keeps the address DHCP gives it");
            return VmPush.NOTHING_TO_PUSH;
        }
        return pushStaticNetworkConfig(incus, name, "PermanentMACAddress=" + mac, ip, gateway,
                prefixLen, out) ? VmPush.PUSHED : VmPush.FAILED;
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
        return fixStaticIpIfNeeded(incus, name, BuildOutput.bound());
    }

    /** @param output where the reassignment, and any wait for the allocation lock, is reported */
    public static boolean fixStaticIpIfNeeded(IncusClient incus, String name,
                                              Output output) {
        return fixStaticIpIfNeeded(incus, name, output, null);
    }

    public static boolean fixStaticIpIfNeeded(IncusClient incus, String name,
                                              Output output,
                                              MachineType machineType) {
        var storedIp = incus.configGet(name, Metadata.STATIC_IP);
        if (storedIp.isEmpty()) return false;
        var bridge = BridgeAddress.read(incus);
        return bridge.isPresent() && fixStaticIp(incus, name, storedIp, bridge.get(), output, machineType);
    }

    private static boolean fixStaticIp(IncusClient incus, String name, String storedIp,
                                       BridgeAddress bridge, Output output,
                                       MachineType machineType) {
        if (storedIp.isEmpty() || CidrUtils.isInSubnet(storedIp, bridge.subnet())) return false;

        var newGateway = bridge.gateway();
        var nicDevice = StaticIpAllocator.findNicDevice(incus, name);

        var newIp = StaticIpAllocator.claim(incus, bridge, output, ip -> {
            output.step("Reassigning " + name + ": " + storedIp + " → " + ip);
            incus.deviceConfigSet(name, nicDevice, "ipv4.address", ip);
        });
        applyIpFiltering(incus, name, nicDevice, output);

        var updates = new HashMap<String, String>();
        updates.put(Metadata.STATIC_IP, newIp);
        updates.put(Metadata.STATIC_GATEWAY, newGateway);
        var proxyGw = incus.configGet(name, Metadata.PROXY_GATEWAY);
        if (!proxyGw.isEmpty()) {
            updates.put(Metadata.PROXY_GATEWAY, newGateway);
            // The ACL allows the old gateway only, so until it is rewritten this instance reaches
            // nothing: a failure leaves it closed, not open
            try {
                ProxyOnlyAcl.write(incus, newGateway);
            } catch (RuntimeException e) {
                output.warn("the proxy-only ACL could not be moved to " + newGateway + " (" + e.getMessage()
                        + "): proxy-only instances reach nothing until the next proxy-only branch rewrites it");
            }
        }
        var resolvedType = machineType != null ? machineType : incus.machineType(name);
        // The VM's file still holds the old address and is only pushed while it runs
        if (resolvedType == MachineType.VM) updates.put(Metadata.NETWORK_PUSH_PENDING, "true");
        incus.configSetAll(name, updates);

        if (resolvedType == MachineType.CONTAINER) {
            pushStaticNetworkConfig(incus, name, CONTAINER_NIC_MATCH, newIp, newGateway,
                    bridge.prefixLen(), output);
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
        return migrateAllInstancesToNewSubnet(incus, BuildOutput.bound());
    }

    /** @param output where each reassignment, and each warning, is reported */
    static int migrateAllInstancesToNewSubnet(IncusClient incus, Output output) {
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
                    var storedIp = listed.path("config").pathText(Metadata.STATIC_IP, "");
                    // Incus checks a running instance's NIC update against the address it holds
                    // now, which is off the new subnet: it refuses the update that would move it
                    // (#1009). Only a stopped instance can be moved.
                    var status = listed.pathText("status", "");
                    if ("Frozen".equalsIgnoreCase(status)) {
                        // Paused on purpose, and a stop would wait on a guest that cannot answer
                        output.warn(name + " is frozen and was not moved: resume it, then repair it again");
                        continue;
                    }
                    var running = "Running".equalsIgnoreCase(status);
                    if (running) {
                        output.warn("Stopping " + name + " to move it onto the bridge's new subnet;"
                                + " it is started again once moved");
                        try {
                            incus.stop(name);
                        } catch (RuntimeException e) {
                            // Never forced: a guest that ignores the shutdown is left running, and one
                            // that stops later is moved by its next shell
                            output.warn(name + " did not stop in time and was not moved (" + e.getMessage()
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
                        if (running) startAgain(incus, name, type, moved, bridge.get().prefixLen(), output);
                    }
                } catch (Exception e) {
                    output.warn("failed to migrate " + stale.getKey() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            output.warn("could not list instances for migration: " + e.getMessage());
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
                                   int prefixLen, Output out) {
        try {
            InstanceLifecycle.startForUse(incus, name, type, out);
            // Read after the fix, which wrote the address the file needs
            if (moved && type == MachineType.VM) {
                pushDeferredNetworkConfig(incus, name, incus.instanceMetadata(name), prefixLen, out);
            }
        } catch (Exception e) {
            out.warn("could not start " + name + " again: " + e.getMessage()
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
    static void pushDeferredNetworkConfig(IncusClient incus, String name, JqValue instance,
                                          int prefixLen, Output out) {
        var settled = switch (pushVmNetworkConfig(incus, name, instance, prefixLen, out)) {
            case FAILED -> false;
            // Nothing to push leaves the VM on DHCP, which gives it its address: done too
            case NOTHING_TO_PUSH -> true;
            // The VM already booted with the old .network file. reload re-reads the files
            // and reconfigures every link whose file changed, so it needs no interface
            // name; reconfigure alone would re-apply the config networkd already had.
            case PUSHED -> {
                if (execSucceeds(incus, name, "networkctl", "reload")) yield true;
                // Still pending: its old address is dropped until a reboot applies the file
                out.warn("could not apply the new network config in " + name
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
    private static Map<String, JqValue> staleStaticIps(IncusClient incus, BridgeAddress bridge) {
        // The listing already carries each instance's config: one request, not one per instance.
        JqValue instances = incus.listJsonConfig();
        var stale = new LinkedHashMap<String, JqValue>();
        for (var instance : instances.asList()) {
            var name = instance.pathText("name", "");
            if (name.isEmpty()) continue;
            var storedIp = instance.getField("config").pathText(Metadata.STATIC_IP, "");
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
    static void rearmIpFiltering(IncusClient incus, String name, JqValue instance, Output out) {
        // Airgapped: a bridge NIC it gained is about to be masked (AirgapGuard), not filtered
        if (NetworkMode.isAirgapped(instance)) return;
        var nic = IncusClient.nic(instance, BridgeAddress.BRIDGE);
        // No bridge NIC: airgap, or a hand-made instance -- nothing to filter.
        if (nic == null || "true".equals(nic.config().get("security.ipv4_filtering"))) return;
        applyIpFiltering(incus, name, nic.name(), out);
    }
}
