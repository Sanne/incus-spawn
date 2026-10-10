package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.StaticIpAllocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A VM guest keeps its kernel's predictable NIC name ({@code enp5s0}), whatever the Incus device
 * is called, so a {@code .network} file matching {@code Name=eth0} matches nothing there (#997).
 * The VM's file matches its NIC by MAC address instead, which Incus records for the device.
 */
@ExtendWith(TempHome.class)
class VmNetworkConfigTest {

    private static final String FILE = InstanceNetwork.NETWORK_FILE;
    private static final String MAC = "10:66:6a:3c:9e:01";

    private static FakeIncusDaemon runningVm(Map<String, String> config) {
        return new FakeIncusDaemon().instance("vm", "virtual-machine", "Running", config);
    }

    @Test
    void matchesTheVmNicByMacNotByName() {
        var daemon = runningVm(Map.of(Metadata.STATIC_IP, "10.166.11.7",
                Metadata.STATIC_GATEWAY, "10.166.11.1", "volatile.eth0.hwaddr", MAC));
        InstanceNetwork.pushDeferredVmFiles(daemon.client(), "vm", NetworkMode.FULL);

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
        InstanceNetwork.pushDeferredVmFiles(daemon.client(), "vm", NetworkMode.FULL);
        assertEquals(List.of("GET /1.0/instances/vm", "GET /1.0/networks/incusbr0",
                "POST /1.0/instances/vm/files?path=%2Fetc%2Fsystemd%2Fnetwork%2F10-eth0.network"),
                daemon.requests());
    }

    @Test
    void aVmWithoutAKnownMacKeepsItsDhcpConfig() {
        var daemon = runningVm(Map.of(Metadata.STATIC_IP, "10.166.11.7",
                Metadata.STATIC_GATEWAY, "10.166.11.1"));
        InstanceNetwork.pushDeferredVmFiles(daemon.client(), "vm", NetworkMode.FULL);
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
        InstanceNetwork.pushDeferredVmFiles(daemon.client(), "vm", NetworkMode.FULL);
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
        InstanceNetwork.migrateAllInstancesToNewSubnet(incus);
        assertEquals("true", daemon.instance("vm").path("config")
                .path(Metadata.NETWORK_PUSH_PENDING).asText());
        assertNull(daemon.pushedContent("vm", FILE));

        // The next isx shell finds nothing left to reassign -- so no "Reassigned" banner --
        // but ensureReady still owes the guest its file (see aRunningVmStillGetsAPendingFile)
        assertFalse(InstanceNetwork.fixStaticIpIfNeeded(incus, "vm",
                StaticIpAllocator.Output.TERMINAL,
                MachineType.VM));
        var warnings = new ArrayList<String>();
        InstanceNetwork.pushDeferredNetworkConfig(incus, "vm", daemon.instance("vm"), 24, warnings::add);
        var content = daemon.pushedContent("vm", FILE);
        assertTrue(content.contains("Address=10.166.11.2/24\n"), content);

        // This daemon's agent answers no exec, so networkctl reload cannot run: the guest still
        // holds its old address, so the push stays owed, and the user is told
        assertEquals("true", daemon.instance("vm").path("config")
                .path(Metadata.NETWORK_PUSH_PENDING).asText());
        assertEquals(List.of("could not apply the new network config in vm;"
                + " restart it, or the next shell tries again"), warnings);
    }

    /**
     * A VM repaired while stopped and then started outside isx (plain incus start, autostart
     * after a host reboot) is already running at the next isx shell, and boots its old file:
     * the pending push must not wait for a stopped start, or the VM never gets back on the
     * network.
     */
    @Test
    void aRunningVmStillGetsAPendingFile() throws Exception {
        var incus = agentAnsweringVm(Map.of("volatile.eth0.hwaddr", MAC, Metadata.NETWORK_PUSH_PENDING, "true"));
        InstanceLifecycle.ensureReady(incus, "vm", incus.instanceMetadata("vm"), MachineType.VM, msg -> {});

        verify(incus).filePush(anyString(), eq("vm"), eq(FILE), eq("0"), eq("0"), eq("0644"));
        verify(incus).shellExec("vm", "networkctl", "reload");
        verify(incus).configUnset("vm", Metadata.NETWORK_PUSH_PENDING);
        verify(incus, never()).start(anyString());
    }

    /** With no file it could push, the VM is on DHCP, which gives it its address: nothing is owed. */
    @Test
    void aPendingVmWithoutAMacIsNotRetriedForever() throws Exception {
        var incus = agentAnsweringVm(Map.of(Metadata.NETWORK_PUSH_PENDING, "true"));
        var warnings = new ArrayList<String>();
        InstanceLifecycle.ensureReady(incus, "vm", incus.instanceMetadata("vm"), MachineType.VM, warnings::add);

        verify(incus, never()).filePush(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(incus).configUnset("vm", Metadata.NETWORK_PUSH_PENDING);
        assertEquals(1, warnings.size(), warnings::toString);
    }

    /**
     * A VM's file can only be pushed while it runs, and {@code --no-start} skips the push at
     * branch time: the branch owes it to its next start through {@link InstanceLifecycle#ensureReady},
     * which delivers it. Marked in the write that claims the address, so it costs no request (#1004).
     */
    @Test
    void aVmBranchedWithoutStartingOwesItsNetworkFile() {
        var daemon = branchUnstarted("virtual-machine", NetworkMode.FULL);
        var config = daemon.instance("vm-2").path("config");
        assertFalse(config.path(Metadata.STATIC_IP).asText().isEmpty(), "precondition: an address was claimed");
        assertEquals("true", config.path(Metadata.NETWORK_PUSH_PENDING).asText(), config::toString);
        assertEquals(1, daemon.requests().stream().filter(r -> r.equals("PATCH /1.0/instances/vm-2")).count(),
                () -> String.join("\n", daemon.requests()));
    }

    @Test
    void onlyAVmWithAnAddressOwesANetworkFile() {
        assertFalse(branchUnstarted("container", NetworkMode.FULL).instance("vm-2")
                .path("config").has(Metadata.NETWORK_PUSH_PENDING), "a container's file is pushed while stopped");
        assertFalse(branchUnstarted("virtual-machine", NetworkMode.AIRGAP).instance("vm-2")
                .path("config").has(Metadata.NETWORK_PUSH_PENDING), "an airgapped VM has no address");
    }

    private static FakeIncusDaemon branchUnstarted(String type, NetworkMode mode) {
        var daemon = new FakeIncusDaemon().instance("vm", type, "Stopped",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, "volatile.eth0.hwaddr", MAC));
        var request = new BranchFlow.Request("vm", "vm-2", false, false, mode,
                null, null, null, null, List.of(), false, Map.of());
        // A non-airgapped branch checks the host's proxy first; this host may not run one.
        var original = BranchFlow.proxyHealthCheck;
        BranchFlow.proxyHealthCheck = i -> true;
        try {
            BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of()));
        } finally {
            BranchFlow.proxyHealthCheck = original;
        }
        return daemon;
    }

    @Test
    void aBranchDoesNotInheritItsSourcesPendingPush() {
        var daemon = new FakeIncusDaemon().instance("vm", "virtual-machine", "Stopped",
                Map.of(Metadata.NETWORK_PUSH_PENDING, "true"));
        daemon.client().copy("vm", "vm-2");
        assertFalse(daemon.instance("vm-2").path("config").has(Metadata.NETWORK_PUSH_PENDING));
    }

    @Test
    void aVmOwingNothingIsNotTouched() throws Exception {
        var incus = agentAnsweringVm(Map.of("volatile.eth0.hwaddr", MAC));
        InstanceLifecycle.ensureReady(incus, "vm", incus.instanceMetadata("vm"), MachineType.VM, msg -> {});

        verify(incus, never()).filePush(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(incus, never()).configUnset(anyString(), anyString());
    }

    /** A running VM whose agent answers, which FakeIncusDaemon cannot serve: it has no exec. */
    private static IncusClient agentAnsweringVm(Map<String, String> moreConfig) throws Exception {
        var config = new HashMap<>(Map.of(Metadata.STATIC_IP, "10.166.11.9",
                Metadata.STATIC_GATEWAY, "10.166.11.1"));
        config.putAll(moreConfig);
        var json = new ObjectMapper();
        var instance = json.createObjectNode().put("status", "Running");
        instance.set("config", json.valueToTree(config));
        instance.putObject("expanded_devices").putObject("eth0").put("type", "nic").put("network", "incusbr0");
        var incus = mock(IncusClient.class);
        when(incus.instanceMetadata("vm")).thenReturn(instance);
        when(incus.shellExec(eq("vm"), any(String[].class)))
                .thenReturn(new IncusClient.ExecResult(0, "ready\n", ""));
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("10.166.11.1/24");
        return incus;
    }

    @Test
    void containersStillMatchEth0() {
        // Incus names a container's NIC after the device's name, so eth0 is right there.
        var daemon = new FakeIncusDaemon().container("c", Map.of());
        InstanceNetwork.pushStaticNetworkConfig(daemon.client(), "c", "10.166.11.8", "10.166.11.1", 24);
        var content = daemon.pushedContent("c", FILE);
        assertTrue(content.startsWith("[Match]\nName=eth0\n"), content);
        assertFalse(content.contains("MACAddress"));
    }
}
