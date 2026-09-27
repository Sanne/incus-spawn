package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BridgeAddressTest {

    @Test
    void theBridgeAddressIsTheGatewayAndNamesTheSubnet() {
        var bridge = BridgeAddress.parse("10.166.11.1/24").orElseThrow();
        assertEquals("10.166.11.1", bridge.gateway());
        assertEquals(CidrUtils.parseCidr("10.166.11.0/24"), bridge.subnet());
        assertEquals(24, bridge.prefixLen());
    }

    @Test
    void anAddressWithoutAPrefixLengthIsASlash24() {
        var bridge = BridgeAddress.parse("172.20.0.1").orElseThrow();
        assertEquals("172.20.0.1", bridge.gateway());
        assertEquals(24, bridge.prefixLen());
    }

    @Test
    void noAddressIsEmpty() {
        assertEquals(Optional.empty(), BridgeAddress.parse(""));
    }
}
