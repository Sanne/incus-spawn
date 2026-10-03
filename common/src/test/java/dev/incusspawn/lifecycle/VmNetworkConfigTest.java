package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.StaticIpAllocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A VM guest keeps its kernel's predictable NIC name ({@code enp5s0}), whatever the Incus device
 * is called, so a {@code .network} file matching {@code Name=eth0} matches nothing there (#997).
 * The VM's file matches its NIC by MAC address instead, which Incus records for the device.
 */
@ExtendWith(TempHome.class)
class VmNetworkConfigTest {

    private static final String FILE = InstanceLifecycle.NETWORK_FILE;
    private static final String MAC = "10:66:6a:3c:9e:01";

    private static FakeIncusDaemon runningVm(Map<String, String> config) {
        return new FakeIncusDaemon().instance("vm", "virtual-machine", "Running", config);
    }

    @Test
    void matchesTheVmNicByMacNotByName() {
        var daemon = runningVm(Map.of(Metadata.STATIC_IP, "10.166.11.7",
                Metadata.STATIC_GATEWAY, "10.166.11.1", "volatile.eth0.hwaddr", MAC));
        InstanceLifecycle.pushDeferredVmFiles(daemon.client(), "vm", NetworkMode.FULL);

        var content = daemon.pushedContent("vm", FILE);
        // Permanent: a VLAN or bridge built on the NIC takes over its MAC, not its permanent one
        assertEquals("[Match]\nPermanentMACAddress=" + MAC + "\n\n[Network]\nAddress=10.166.11.7/24\n"
                + "Gateway=10.166.11.1\nDNS=10.166.11.1\n", content);
        assertEquals("0644", daemon.pushedMode("vm", FILE));
    }

    @Test
    void oneInstanceReadForAddressGatewayAndMac() {
        var daemon = runningVm(Map.of(Metadata.STATIC_IP, "10.166.11.7",
                Metadata.STATIC_GATEWAY, "10.166.11.1", "volatile.eth0.hwaddr", MAC));
        InstanceLifecycle.pushDeferredVmFiles(daemon.client(), "vm", NetworkMode.FULL);
        assertEquals(List.of("GET /1.0/instances/vm", "GET /1.0/networks/incusbr0",
                "POST /1.0/instances/vm/files?path=%2Fetc%2Fsystemd%2Fnetwork%2F10-eth0.network"),
                daemon.requests());
    }

    @Test
    void aVmWithoutAKnownMacKeepsItsDhcpConfig() {
        var daemon = runningVm(Map.of(Metadata.STATIC_IP, "10.166.11.7",
                Metadata.STATIC_GATEWAY, "10.166.11.1"));
        InstanceLifecycle.pushDeferredVmFiles(daemon.client(), "vm", NetworkMode.FULL);
        // A file matching no link would be as inert as Name=eth0 was, and one matching every
        // link would put the address on a nested bridge too.
        assertNull(daemon.pushedContent("vm", FILE));
    }

    @Test
    void aMacSetOnTheDeviceWins() {
        var daemon = runningVm(Map.of(Metadata.STATIC_IP, "10.166.11.7",
                Metadata.STATIC_GATEWAY, "10.166.11.1"))
                .device("vm", "eth0", Map.of("type", "nic", "network", "incusbr0", "name", "eth0",
                        "hwaddr", "10:66:6A:00:00:02"));
        InstanceLifecycle.pushDeferredVmFiles(daemon.client(), "vm", NetworkMode.FULL);
        assertTrue(daemon.pushedContent("vm", FILE).contains("PermanentMACAddress=10:66:6a:00:00:02\n"));
    }

    /**
     * Now that a VM's file applies, a subnet repair made while the VM is stopped must reach it:
     * the guest would otherwise come up on the old address, which IP filtering drops. Before
     * #997 the file matched nothing and DHCP covered for it.
     */
    @Test
    void aRepairMadeWhileStoppedIsPushedAtTheNextStart() {
        var daemon = new FakeIncusDaemon().instance("vm", "virtual-machine", "Stopped",
                Map.of(Metadata.STATIC_IP, "10.99.0.7", Metadata.STATIC_GATEWAY, "10.99.0.1",
                        "volatile.eth0.hwaddr", MAC));
        var incus = daemon.client();
        // As isx init or doctor repairs it, with no start to push the file at
        InstanceLifecycle.migrateAllInstancesToNewSubnet(incus);
        assertEquals("true", daemon.instance("vm").path("config")
                .path(Metadata.NETWORK_PUSH_PENDING).asText());
        assertNull(daemon.pushedContent("vm", FILE));

        // The next isx shell finds nothing left to reassign, but still has a file to push
        assertTrue(InstanceLifecycle.fixStaticIpIfNeeded(incus, "vm",
                StaticIpAllocator.Output.TERMINAL,
                MachineType.VM));
        InstanceLifecycle.pushDeferredNetworkConfig(incus, "vm", msg -> {});
        var content = daemon.pushedContent("vm", FILE);
        assertTrue(content.contains("Address=10.166.11.2/24\n"), content);
        assertFalse(daemon.instance("vm").path("config").has(Metadata.NETWORK_PUSH_PENDING));
        assertFalse(InstanceLifecycle.fixStaticIpIfNeeded(incus, "vm",
                StaticIpAllocator.Output.TERMINAL,
                MachineType.VM));
    }

    @Test
    void containersStillMatchEth0() {
        // Incus names a container's NIC after the device's name, so eth0 is right there.
        var daemon = new FakeIncusDaemon().container("c", Map.of());
        InstanceLifecycle.pushStaticNetworkConfig(daemon.client(), "c", "10.166.11.8", "10.166.11.1", 24);
        var content = daemon.pushedContent("c", FILE);
        assertTrue(content.startsWith("[Match]\nName=eth0\n"), content);
        assertFalse(content.contains("MACAddress"));
    }
}
