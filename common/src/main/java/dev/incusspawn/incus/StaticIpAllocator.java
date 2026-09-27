package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.Environment;
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
     *
     * @param bridge read by the caller before the claim, so no one waits on that read
     * @return the address written
     */
    public static String claim(IncusClient incus, BridgeAddress bridge, Consumer<String> write) {
        return claim(incus, bridge, Environment.lockDir().resolve(LOCK_FILE), write);
    }

    static String claim(IncusClient incus, BridgeAddress bridge, Path lockFile,
                        Consumer<String> write) {
        // Nesting would hand the inner claim the outer's still unwritten address: HostLock
        // refuses it
        try (var lock = HostLock.acquire(lockFile, "assigning a static IP", System.err::println)) {
            var claimed = getClaimedIps(incus);
            claimed.add(CidrUtils.ipToLong(bridge.gateway()));
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
        var name = incus.findNicDeviceName(instanceName, "incusbr0");
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
