package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BridgeSubnetCheckTest {

    private static final List<String> SAMPLE_ROUTES = List.of(
            "default via 192.168.1.1 dev wlp3s0 proto dhcp metric 600",
            "10.0.0.0/8 via 10.64.0.1 dev tun0",
            "10.166.11.0/24 dev incusbr0 proto kernel scope link src 10.166.11.1",
            "192.168.1.0/24 dev wlp3s0 proto kernel scope link src 192.168.1.100");

    @Test
    void findConflictingRouteDetectsVpnOverlap() {
        var conflict = BridgeSubnetCheck.findConflictingRoute("10.166.11.1/24", SAMPLE_ROUTES);
        assertNotNull(conflict);
        assertTrue(conflict.contains("10.0.0.0/8"));
    }

    @Test
    void findConflictingRouteSkipsBridgeRoutes() {
        var routes = List.of(
                "default via 192.168.1.1 dev wlp3s0",
                "10.166.11.0/24 dev incusbr0 proto kernel scope link src 10.166.11.1");
        var conflict = BridgeSubnetCheck.findConflictingRoute("10.166.11.1/24", routes);
        assertNull(conflict);
    }

    @Test
    void findConflictingRouteSkipsDefaultRoute() {
        var routes = List.of("default via 192.168.1.1 dev wlp3s0");
        var conflict = BridgeSubnetCheck.findConflictingRoute("10.166.11.1/24", routes);
        assertNull(conflict);
    }

    @Test
    void findConflictingRouteReturnsNullWhenNoConflict() {
        var routes = List.of(
                "default via 192.168.1.1 dev wlp3s0",
                "192.168.1.0/24 dev wlp3s0 proto kernel scope link src 192.168.1.100",
                "172.20.0.0/24 dev incusbr0 proto kernel scope link src 172.20.0.1");
        var conflict = BridgeSubnetCheck.findConflictingRoute("172.20.0.1/24", routes);
        assertNull(conflict);
    }

    @Test
    void findConflictingRouteReturnsFirstConflict() {
        var routes = List.of(
                "10.0.0.0/8 via 10.64.0.1 dev tun0",
                "10.64.0.0/10 dev tun0 scope link");
        var conflict = BridgeSubnetCheck.findConflictingRoute("10.166.11.1/24", routes);
        assertNotNull(conflict);
        assertTrue(conflict.contains("10.0.0.0/8"));
    }

    @Test
    void findNonConflictingSubnetReturnsFirstCandidate() {
        var routes = List.of(
                "default via 192.168.1.1 dev wlp3s0",
                "10.0.0.0/8 via 10.64.0.1 dev tun0",
                "10.166.11.0/24 dev incusbr0 proto kernel scope link src 10.166.11.1",
                "192.168.1.0/24 dev wlp3s0");
        var subnet = BridgeSubnetCheck.findNonConflictingSubnet("10.166.11.1/24", routes);
        assertEquals("172.20.0.1/24", subnet);
    }

    @Test
    void findNonConflictingSubnetSkipsConflicting() {
        var routes = List.of(
                "172.20.0.0/24 dev somedev",
                "172.21.0.0/24 dev somedev2");
        var subnet = BridgeSubnetCheck.findNonConflictingSubnet("10.166.11.1/24", routes);
        assertEquals("172.22.0.1/24", subnet);
    }

    @Test
    void findNonConflictingSubnetSkipsCurrentBridgeSubnet() {
        var routes = List.of(
                "172.20.0.0/24 dev incusbr0 proto kernel scope link src 172.20.0.1");
        var subnet = BridgeSubnetCheck.findNonConflictingSubnet("172.20.0.1/24", routes);
        assertEquals("172.21.0.1/24", subnet);
    }

    @Test
    void findNonConflictingSubnetReturnsNullWhenAllConflict() {
        var routes = List.of("172.16.0.0/12 via 10.0.0.1 dev tun0");
        var subnet = BridgeSubnetCheck.findNonConflictingSubnet("10.166.11.1/24", routes);
        assertNull(subnet);
    }

    @Test
    void detectAndFixNoConflict() {
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("172.20.0.1/24");

        var result = BridgeSubnetCheck.detectAndFix(incus);
        assertFalse(result.conflictDetected());
        assertNull(result.conflictingRoute());
        assertNull(result.newSubnet());
    }

    @Test
    void detectConflictDiagnosticReturnsNullWhenNoConflict() {
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("172.20.0.1/24");

        var diagnostic = BridgeSubnetCheck.detectConflictDiagnostic(incus);
        assertNull(diagnostic);
    }

    // VPN routes in a policy routing table (e.g. table 75) must be detected as
    // conflicts, since they override main-table routes via ip rule priority.
    @Test
    void findConflictingRouteDetectsVpnInPolicyRoutingTable() {
        var routes = List.of(
                "default via 192.168.0.254 dev wlp9s0f0 proto dhcp src 192.168.0.24 metric 600",
                "10.26.206.0/24 dev incusbr0 proto kernel scope link src 10.26.206.1 metric 426",
                "192.168.0.0/24 dev wlp9s0f0 proto kernel scope link src 192.168.0.24 metric 600",
                "10.0.0.0/8 via 10.44.48.1 dev tun0 table 75 proto static metric 50",
                "10.44.48.0/20 dev tun0 table 75 proto kernel scope link src 10.44.48.62 metric 50",
                "local 10.26.206.1 dev incusbr0 table local proto kernel scope host src 10.26.206.1",
                "broadcast 10.26.206.255 dev incusbr0 table local proto kernel scope link src 10.26.206.1",
                "local 10.44.48.62 dev tun0 table local proto kernel scope host src 10.44.48.62");
        var conflict = BridgeSubnetCheck.findConflictingRoute("10.26.206.1/24", routes);
        assertNotNull(conflict);
        assertTrue(conflict.contains("10.0.0.0/8"));
    }

    // Candidate subnet selection must also consider routes from policy routing
    // tables, not just the main table, to avoid picking a subnet claimed by a VPN.
    @Test
    void findNonConflictingSubnetWorksWithPolicyRoutingTables() {
        var routes = List.of(
                "default via 192.168.0.254 dev wlp9s0f0 proto dhcp",
                "10.0.0.0/8 via 10.44.48.1 dev tun0 table 75 proto static",
                "local 10.26.206.1 dev incusbr0 table local proto kernel scope host",
                "broadcast 10.26.206.255 dev incusbr0 table local proto kernel scope link");
        var subnet = BridgeSubnetCheck.findNonConflictingSubnet("10.26.206.1/24", routes);
        assertEquals("172.20.0.1/24", subnet);
    }

    // Entries from the kernel's local table (local, broadcast) must not trigger
    // false-positive conflicts when processing `ip route show table all` output.
    @Test
    void findConflictingRouteSkipsLocalTableEntries() {
        var routes = List.of(
                "local 10.166.11.1 dev incusbr0 table local proto kernel scope host src 10.166.11.1",
                "broadcast 10.166.11.255 dev incusbr0 table local proto kernel scope link src 10.166.11.1",
                "local 127.0.0.0/8 dev lo table local proto kernel scope host src 127.0.0.1");
        var conflict = BridgeSubnetCheck.findConflictingRoute("10.166.11.1/24", routes);
        assertNull(conflict);
    }

    @Test
    void findConflictingRouteHandlesEmptyRoutes() {
        var conflict = BridgeSubnetCheck.findConflictingRoute("10.166.11.1/24", List.of());
        assertNull(conflict);
    }

    @Test
    void findConflictingRouteHandlesMalformedLines() {
        var routes = List.of("", "   ", "unreachable", "blackhole 10.0.0.0/8");
        var conflict = BridgeSubnetCheck.findConflictingRoute("10.166.11.1/24", routes);
        assertNull(conflict);
    }
}
