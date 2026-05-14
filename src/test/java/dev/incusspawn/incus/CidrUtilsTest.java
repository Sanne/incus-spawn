package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CidrUtilsTest {

    @Test
    void ipToLongConvertsCorrectly() {
        assertEquals(0x0AA60B01L, CidrUtils.ipToLong("10.166.11.1"));
        assertEquals(0xFFFFFFFFL, CidrUtils.ipToLong("255.255.255.255"));
        assertEquals(0L, CidrUtils.ipToLong("0.0.0.0"));
        assertEquals(0xC0A80101L, CidrUtils.ipToLong("192.168.1.1"));
    }

    @Test
    void longToIpRoundTrips() {
        assertEquals("10.166.11.1", CidrUtils.longToIp(CidrUtils.ipToLong("10.166.11.1")));
        assertEquals("255.255.255.255", CidrUtils.longToIp(CidrUtils.ipToLong("255.255.255.255")));
        assertEquals("0.0.0.0", CidrUtils.longToIp(0L));
    }

    @Test
    void ipToLongRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> CidrUtils.ipToLong("10.0.0"));
        assertThrows(IllegalArgumentException.class, () -> CidrUtils.ipToLong("10.0.0.256"));
        assertThrows(IllegalArgumentException.class, () -> CidrUtils.ipToLong("10.0.0.-1"));
    }

    @Test
    void networkMaskReturnsCorrectMasks() {
        assertEquals(0xFFFFFFFFL, CidrUtils.networkMask(32));
        assertEquals(0xFFFFFF00L, CidrUtils.networkMask(24));
        assertEquals(0xFFFF0000L, CidrUtils.networkMask(16));
        assertEquals(0xFF000000L, CidrUtils.networkMask(8));
        assertEquals(0L, CidrUtils.networkMask(0));
    }

    @Test
    void parseCidrParsesWithPrefix() {
        var cidr = CidrUtils.parseCidr("10.166.11.1/24");
        assertEquals(CidrUtils.ipToLong("10.166.11.0"), cidr.network());
        assertEquals(24, cidr.prefixLen());
    }

    @Test
    void parseCidrHandlesBareIpAsHost() {
        var cidr = CidrUtils.parseCidr("10.166.11.1");
        assertEquals(CidrUtils.ipToLong("10.166.11.1"), cidr.network());
        assertEquals(32, cidr.prefixLen());
    }

    @Test
    void parseCidrMasksNetworkCorrectly() {
        var cidr = CidrUtils.parseCidr("10.0.0.0/8");
        assertEquals(CidrUtils.ipToLong("10.0.0.0"), cidr.network());
        assertEquals(8, cidr.prefixLen());
    }

    @Test
    void overlapsDetectsVpnCoveringBridge() {
        var vpn = CidrUtils.parseCidr("10.0.0.0/8");
        var bridge = CidrUtils.parseCidr("10.166.11.0/24");
        assertTrue(CidrUtils.overlaps(vpn, bridge));
        assertTrue(CidrUtils.overlaps(bridge, vpn));
    }

    @Test
    void overlapsReturnsFalseForNonOverlapping() {
        var bridge172 = CidrUtils.parseCidr("172.20.0.0/24");
        var vpn10 = CidrUtils.parseCidr("10.0.0.0/8");
        assertFalse(CidrUtils.overlaps(bridge172, vpn10));

        var local = CidrUtils.parseCidr("192.168.1.0/24");
        assertFalse(CidrUtils.overlaps(local, vpn10));
    }

    @Test
    void overlapsReturnsTrueForIdentical() {
        var a = CidrUtils.parseCidr("10.166.11.0/24");
        var b = CidrUtils.parseCidr("10.166.11.0/24");
        assertTrue(CidrUtils.overlaps(a, b));
    }

    @Test
    void overlapsReturnsTrueForPartialOverlap() {
        var a = CidrUtils.parseCidr("10.64.0.0/10");
        var b = CidrUtils.parseCidr("10.64.51.0/24");
        assertTrue(CidrUtils.overlaps(a, b));
    }

    @Test
    void overlapsReturnsFalseForAdjacentSubnets() {
        var a = CidrUtils.parseCidr("10.0.0.0/24");
        var b = CidrUtils.parseCidr("10.0.1.0/24");
        assertFalse(CidrUtils.overlaps(a, b));
    }
}
