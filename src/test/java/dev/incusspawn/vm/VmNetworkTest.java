package dev.incusspawn.vm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VmNetworkTest {

    @Test
    void macAddressIsLocallyAdministered() {
        var firstOctet = Integer.parseInt(VmNetwork.ISX_VM_MAC.split(":")[0], 16);
        assertTrue((firstOctet & 0x02) != 0, "MAC should have locally-administered bit set");
    }

    @Test
    void normalizeMacStripsLeadingZeros() {
        assertEquals("4a:53:58:0:0:1", VmNetwork.normalizeMac("4a:53:58:00:00:01"));
    }

    @Test
    void normalizeMacHandlesAlreadyNormalized() {
        assertEquals("4a:53:58:0:0:1", VmNetwork.normalizeMac("4a:53:58:0:0:1"));
    }

    @Test
    void normalizeMacIsCaseInsensitive() {
        assertEquals("4a:53:58:0:0:1", VmNetwork.normalizeMac("4A:53:58:00:00:01"));
    }

    @Test
    void discoverVmIpReturnsNullOnLinux() {
        // /var/db/dhcpd_leases doesn't exist on Linux
        if (dev.incusspawn.Environment.isLinux()) {
            assertNull(VmNetwork.discoverVmIp());
        }
    }
}
