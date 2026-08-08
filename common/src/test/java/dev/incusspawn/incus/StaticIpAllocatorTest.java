package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StaticIpAllocatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void pickFreeIpReturnsFirstAvailable() {
        var cidr = CidrUtils.parseCidr("10.166.11.1/24");
        var claimed = new HashSet<Long>();
        claimed.add(CidrUtils.ipToLong("10.166.11.1"));

        assertEquals("10.166.11.2", StaticIpAllocator.pickFreeIp(cidr, claimed));
    }

    @Test
    void pickFreeIpSkipsClaimed() {
        var cidr = CidrUtils.parseCidr("10.166.11.1/24");
        var claimed = new HashSet<Long>();
        claimed.add(CidrUtils.ipToLong("10.166.11.1"));
        claimed.add(CidrUtils.ipToLong("10.166.11.2"));
        claimed.add(CidrUtils.ipToLong("10.166.11.3"));

        assertEquals("10.166.11.4", StaticIpAllocator.pickFreeIp(cidr, claimed));
    }

    @Test
    void pickFreeIpFillsGaps() {
        var cidr = CidrUtils.parseCidr("10.166.11.1/24");
        var claimed = new HashSet<Long>();
        claimed.add(CidrUtils.ipToLong("10.166.11.1"));
        claimed.add(CidrUtils.ipToLong("10.166.11.2"));
        claimed.add(CidrUtils.ipToLong("10.166.11.4"));

        assertEquals("10.166.11.3", StaticIpAllocator.pickFreeIp(cidr, claimed));
    }

    @Test
    void pickFreeIpWorksWithAlternateSubnet() {
        var cidr = CidrUtils.parseCidr("172.20.0.1/24");
        var claimed = new HashSet<Long>();
        claimed.add(CidrUtils.ipToLong("172.20.0.1"));

        assertEquals("172.20.0.2", StaticIpAllocator.pickFreeIp(cidr, claimed));
    }

    @Test
    void pickFreeIpThrowsWhenExhausted() {
        var cidr = CidrUtils.parseCidr("10.166.11.1/24");
        var claimed = new HashSet<Long>();
        // Claim all host addresses .1 through .254
        for (int i = 1; i <= 254; i++) {
            claimed.add(CidrUtils.ipToLong("10.166.11." + i));
        }

        assertThrows(IncusException.class,
                () -> StaticIpAllocator.pickFreeIp(cidr, claimed));
    }

    @Test
    void pickFreeIpNeverAllocatesNetworkOrBroadcast() {
        var cidr = CidrUtils.parseCidr("10.166.11.1/24");
        var claimed = new HashSet<Long>();
        // Claim .1 through .253
        for (int i = 1; i <= 253; i++) {
            claimed.add(CidrUtils.ipToLong("10.166.11." + i));
        }

        // .254 is the last usable host address (before .255 broadcast)
        assertEquals("10.166.11.254", StaticIpAllocator.pickFreeIp(cidr, claimed));
    }

    @Test
    void collectClaimedIpsFromDevicesJson() throws Exception {
        var devices = JSON.readTree("""
                {
                    "eth0": {
                        "type": "nic",
                        "network": "incusbr0",
                        "ipv4.address": "10.166.11.5"
                    },
                    "root": {
                        "type": "disk",
                        "path": "/",
                        "pool": "default"
                    }
                }
                """);
        var claimed = new HashSet<Long>();
        StaticIpAllocator.collectClaimedIps(devices, claimed);

        assertEquals(Set.of(CidrUtils.ipToLong("10.166.11.5")), claimed);
    }

    @Test
    void collectClaimedIpsIgnoresNonNicDevices() throws Exception {
        var devices = JSON.readTree("""
                {
                    "root": {
                        "type": "disk",
                        "path": "/",
                        "pool": "default",
                        "ipv4.address": "10.166.11.99"
                    }
                }
                """);
        var claimed = new HashSet<Long>();
        StaticIpAllocator.collectClaimedIps(devices, claimed);

        assertTrue(claimed.isEmpty());
    }

    @Test
    void collectClaimedIpsIgnoresNicWithoutIp() throws Exception {
        var devices = JSON.readTree("""
                {
                    "eth0": {
                        "type": "nic",
                        "network": "incusbr0"
                    }
                }
                """);
        var claimed = new HashSet<Long>();
        StaticIpAllocator.collectClaimedIps(devices, claimed);

        assertTrue(claimed.isEmpty());
    }

    @Test
    void collectClaimedIpsHandlesMissingNode() {
        var claimed = new HashSet<Long>();
        StaticIpAllocator.collectClaimedIps(JSON.missingNode(), claimed);
        assertTrue(claimed.isEmpty());
    }

    @Test
    void collectClaimedIpsHandlesMultipleNics() throws Exception {
        var devices = JSON.readTree("""
                {
                    "eth0": {
                        "type": "nic",
                        "network": "incusbr0",
                        "ipv4.address": "10.166.11.2"
                    },
                    "eth1": {
                        "type": "nic",
                        "parent": "br-custom",
                        "ipv4.address": "192.168.1.100"
                    }
                }
                """);
        var claimed = new HashSet<Long>();
        StaticIpAllocator.collectClaimedIps(devices, claimed);

        assertEquals(2, claimed.size());
        assertTrue(claimed.contains(CidrUtils.ipToLong("10.166.11.2")));
        assertTrue(claimed.contains(CidrUtils.ipToLong("192.168.1.100")));
    }
}
