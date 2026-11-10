package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who an exposed host port admits (#2117): the bridge's subnet on Linux, the VM's address on macOS,
 * and nothing that cannot be placed, so a LAN peer that routes to the proxy's address is refused.
 */
class RelayCallersTest {

    private static InetAddress ip(String literal) {
        return InetAddress.ofLiteral(literal);
    }

    @Test
    void theBridgeSubnetIsAdmittedAndAnAddressBesideItIsNot() {
        var bridge = RelayCallers.subnet(ip("10.166.11.1"), 24);
        assertTrue(bridge.test(ip("10.166.11.1")), "the host on the bridge");
        assertTrue(bridge.test(ip("10.166.11.57")));
        assertFalse(bridge.test(ip("10.166.12.57")), "the next subnet");
        assertFalse(bridge.test(ip("192.168.1.20")), "a LAN peer");
        assertFalse(bridge.test(ip("fd42::1")), "IPv6 never matches an IPv4 bridge");
        assertFalse(RelayCallers.subnet(ip("fd42::1"), 64).test(ip("fd42::2")), "only IPv4 bridges are placed");
        assertFalse(RelayCallers.subnet(ip("10.0.0.1"), 33).test(ip("10.0.0.1")));
    }

    @Test
    void onMacOsOnlyTheVmsAddressIsAdmitted() {
        var vm = RelayCallers.only("192.168.64.7");
        assertTrue(vm.test(ip("192.168.64.7")));
        assertFalse(vm.test(ip("192.168.64.8")), "another VM on the same vmnet");
        assertFalse(RelayCallers.only(null).test(ip("192.168.64.7")), "no lease: nobody");
        assertFalse(RelayCallers.only("not an address").test(ip("192.168.64.7")));
    }

    @Test
    void aMappedAddressIsCheckedAsTheIpv4AddressItCarries() {
        var callers = new RelayCallers(() -> RelayCallers.subnet(ip("10.1.2.1"), 24), () -> 0);
        assertTrue(callers.admits(ip("::ffff:10.1.2.9")));
        assertFalse(callers.admits(ip("::ffff:10.1.3.9")));
    }

    @Test
    void aLoopbackBindAdmitsTheHostAlone() {
        var loopback = RelayCallers.subnetOf("127.0.0.2");
        assertTrue(loopback.test(ip("127.0.0.1")));
        assertFalse(loopback.test(ip("10.0.0.5")));
    }

    @Test
    void anAddressNoInterfaceHoldsAdmitsNobody() {
        // TEST-NET-3: documentation only, on no interface of any test host
        assertFalse(RelayCallers.subnetOf("203.0.113.77").test(ip("203.0.113.78")));
    }

    /**
     * The bridge's subnet or the VM's lease may change under a running proxy, so the rule is
     * looked up again once it is an interval old, for admitted and refused callers alike, and a
     * stream of connections costs one lookup per interval.
     */
    @Test
    void theRuleIsLookedUpAgainOncePerIntervalWhoeverCalls() {
        var lookups = new AtomicInteger();
        var now = new AtomicLong(1_000_000);
        var vm = new String[] {"192.168.64.7"};
        var callers = new RelayCallers(() -> {
            lookups.incrementAndGet();
            return RelayCallers.only(vm[0]);
        }, now::get);
        assertTrue(callers.admits(ip("192.168.64.7")));
        assertEquals(1, lookups.get());

        vm[0] = "192.168.64.9";
        for (int i = 0; i < 100; i++) assertFalse(callers.admits(ip("192.168.64.9")), "still the old rule");
        assertEquals(1, lookups.get(), "callers within the interval look nothing up");

        now.addAndGet(RelayCallers.REDISCOVER_MILLIS);
        assertFalse(callers.admits(ip("192.168.64.7")), "the old lease is no longer admitted, though the old rule held it");
        assertEquals(2, lookups.get());
        assertTrue(callers.admits(ip("192.168.64.9")));
        assertEquals(2, lookups.get());
    }
}
