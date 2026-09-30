package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.Environment;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.HostLock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public final class StaticIpAllocator {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Dot-prefixed, as no instance name can be: the TUI's per-instance locks in the same
     * directory are {@code <instance>.lock}, so an instance named {@code static-ip} would
     * otherwise open, close and delete this very file.
     */
    static final String LOCK_FILE = ".static-ip.lock";

    private StaticIpAllocator() {}

    /**
     * Allocate the lowest free address on the bridge subnet (.2-.254, skipping every NIC's
     * {@code ipv4.address}) and hand it to {@code write}, which must stamp it on the instance's
     * NIC before returning. Nothing reserves an address between the listing that finds it free
     * and that write, so two concurrent branches could otherwise both be given the same one
     * (#815). A {@link HostLock} is held across both. It lives under the user's home, so it
     * serializes the isx processes of one user; Incus's own conflict check (409 "IP address ...
     * already defined on another NIC") refuses most duplicates from writers outside it, such as
     * {@code sudo isx} or another user on the same daemon, but not two such writes at once.
     * That check is also why a home that cannot be locked only warns: branching goes on, and
     * a collision fails a branch rather than sharing an address.
     *
     * @param bridge read by the caller before the claim, so no one waits on that read
     * @return the address written
     */
    public static String claim(IncusClient incus, BridgeAddress bridge, Consumer<String> write) {
        return claim(incus, bridge, Output.TERMINAL, write);
    }

    /**
     * Where a claim, and the flows around it, report progress (waiting for another process)
     * and warnings (a lock it cannot take). The TUI passes its own: printing would draw over
     * its screen.
     */
    public record Output(Consumer<String> step, Consumer<String> warn) {
        public static final Output TERMINAL = new Output(BuildOutput::step, BuildOutput::warn);
    }

    public static String claim(IncusClient incus, BridgeAddress bridge, Output output,
                               Consumer<String> write) {
        return claim(incus, bridge, Set.of(), output, write);
    }

    /**
     * As {@link #claim(IncusClient, BridgeAddress, Output, Consumer)}, never handing out one of
     * {@code alsoTaken} either: addresses no NIC declares that are still not free, such as the
     * DHCP leases a claimant that relies on DHCP must avoid.
     */
    public static String claim(IncusClient incus, BridgeAddress bridge, Set<String> alsoTaken,
                               Output output, Consumer<String> write) {
        return claim(incus, bridge, Environment.lockDir().resolve(LOCK_FILE), alsoTaken, output, write);
    }

    static String claim(IncusClient incus, BridgeAddress bridge, Path lockFile, Output output,
                        Consumer<String> write) {
        return claim(incus, bridge, lockFile, Set.of(), output, write);
    }

    static String claim(IncusClient incus, BridgeAddress bridge, Path lockFile, Set<String> alsoTaken,
                        Output output, Consumer<String> write) {
        // Nesting would hand the inner claim the outer's still unwritten address: HostLock
        // refuses it
        try (var lock = HostLock.acquireOrDegrade(lockFile, "assigning a static IP",
                output.step(), output.warn())) {
            var claimed = getClaimedIps(incus);
            claimed.add(CidrUtils.ipToLong(bridge.gateway()));
            alsoTaken.forEach(ip -> claimed.add(CidrUtils.ipToLong(ip)));
            var ip = pickFreeIp(bridge.subnet(), claimed);
            write.accept(ip);
            return ip;
        }
    }

    static String pickFreeIp(CidrUtils.Cidr cidr, Set<Long> claimed) {
        long networkBase = cidr.network();
        int prefixLen = cidr.prefixLen();
        long hostCount = 1L << (32 - prefixLen);
        for (long offset = 2; offset < hostCount - 1; offset++) {
            long candidate = networkBase + offset;
            if (!claimed.contains(candidate)) {
                return CidrUtils.longToIp(candidate);
            }
        }
        throw new IncusException("No free IP addresses on the bridge subnet "
                + CidrUtils.longToIp(networkBase) + "/" + prefixLen);
    }

    /**
     * Find the NIC device name attached to incusbr0 on an instance.
     * The NIC is typically inherited from the default Incus profile,
     * so we check expanded_devices rather than instance devices.
     */
    public static String findNicDevice(IncusClient incus, String instanceName) {
        var name = incus.findNicDeviceName(instanceName, BridgeAddress.BRIDGE);
        if (name == null) {
            throw new IncusException("No NIC device for incusbr0 found on " + instanceName);
        }
        return name;
    }

    /**
     * Every address a NIC claims. Fails rather than returning what it could read: an address
     * missing from this set is handed out.
     */
    static Set<Long> getClaimedIps(IncusClient incus) {
        var claimed = new HashSet<Long>();
        JsonNode instances;
        try {
            instances = JSON.readTree(incus.listJsonConfig());
        } catch (IOException e) {
            throw new IncusException("Failed to parse the instance listing: " + e.getMessage(), e);
        }
        for (var instance : instances) {
            collectClaimedIps(instance.path("expanded_devices"), claimed);
            collectClaimedIps(instance.path("devices"), claimed);
        }
        return claimed;
    }

    static void collectClaimedIps(JsonNode devices, Set<Long> claimed) {
        if (devices.isMissingNode()) return;
        for (var it = devices.fields(); it.hasNext(); ) {
            var dev = it.next().getValue();
            if (!"nic".equals(dev.path("type").asText())) continue;
            var ipStr = dev.path("ipv4.address").asText("");
            if (!ipStr.isEmpty()) {
                try {
                    claimed.add(CidrUtils.ipToLong(ipStr));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

}
