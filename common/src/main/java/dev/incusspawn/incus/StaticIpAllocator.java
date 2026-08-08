package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

public final class StaticIpAllocator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private StaticIpAllocator() {}

    /**
     * Allocate the lowest free static IP on the incusbr0 bridge subnet.
     * Scans all existing instances for claimed {@code ipv4.address} on NIC devices,
     * then picks the first available host address in .2-.254.
     */
    public static String allocate(IncusClient incus) {
        var bridgeAddr = incus.networkConfigGet("incusbr0", "ipv4.address");
        if (bridgeAddr.isEmpty()) {
            throw new IncusException("Bridge incusbr0 has no ipv4.address configured");
        }
        var cidr = CidrUtils.parseCidr(bridgeAddr);
        var gateway = bridgeAddr.contains("/")
                ? bridgeAddr.substring(0, bridgeAddr.indexOf('/')) : bridgeAddr;

        var claimed = getClaimedIps(incus);
        claimed.add(CidrUtils.ipToLong(gateway));
        return pickFreeIp(cidr, claimed);
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

    static Set<Long> getClaimedIps(IncusClient incus) {
        var claimed = new HashSet<Long>();
        try {
            var jsonStr = incus.listJsonConfig();
            var instances = JSON.readTree(jsonStr);
            for (var instance : instances) {
                collectClaimedIps(instance.path("expanded_devices"), claimed);
                collectClaimedIps(instance.path("devices"), claimed);
            }
        } catch (Exception e) {
            // If listing fails, return empty set — allocation will still succeed
            // as long as there's no collision at the Incus level
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
