package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(TempHome.class)
class InstanceLifecycleNetworkFixTest {

    @Test
    void fixStaticIpIfNeeded_noStaticIp_returnsFalse() {
        var incus = mock(IncusClient.class);
        when(incus.configGet("test", Metadata.STATIC_IP)).thenReturn("");

        assertFalse(InstanceLifecycle.fixStaticIpIfNeeded(incus, "test"));
        verify(incus, never()).deviceConfigSet(any(), any(), any(), any());
    }

    @Test
    void fixStaticIpIfNeeded_ipOnCurrentSubnet_returnsFalse() {
        var incus = mock(IncusClient.class);
        when(incus.configGet("test", Metadata.STATIC_IP)).thenReturn("172.20.0.5");
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("172.20.0.1/24");

        assertFalse(InstanceLifecycle.fixStaticIpIfNeeded(incus, "test"));
        verify(incus, never()).deviceConfigSet(any(), any(), any(), any());
    }

    @Test
    void fixStaticIpIfNeeded_ipOnStaleSubnet_reallocates() {
        var incus = mock(IncusClient.class);
        when(incus.configGet("test", Metadata.STATIC_IP)).thenReturn("172.20.0.5");
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("172.21.0.1/24");

        // StaticIpAllocator.claim needs these
        when(incus.listJsonConfig()).thenReturn("[]");
        when(incus.findNicDeviceName("test", "incusbr0")).thenReturn("eth0");
        when(incus.configGet("test", Metadata.PROXY_GATEWAY)).thenReturn("");
        when(incus.machineType("test")).thenReturn(MachineType.CONTAINER);

        assertTrue(InstanceLifecycle.fixStaticIpIfNeeded(incus, "test"));

        verify(incus).deviceConfigSet(eq("test"), eq("eth0"), eq("ipv4.address"),
                argThat(ip -> ip.startsWith("172.21.0.")));
        verify(incus).configSetAll(eq("test"), argThat(map ->
                map.containsKey(Metadata.STATIC_IP)
                && map.get(Metadata.STATIC_IP).startsWith("172.21.0.")
                && map.containsKey(Metadata.STATIC_GATEWAY)
                && !map.containsKey(Metadata.PROXY_GATEWAY)));
    }

    @Test
    void fixStaticIpIfNeeded_proxyOnly_updatesProxyGateway() {
        var incus = mock(IncusClient.class);
        when(incus.configGet("test", Metadata.STATIC_IP)).thenReturn("172.20.0.5");
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("172.21.0.1/24");
        when(incus.listJsonConfig()).thenReturn("[]");
        when(incus.findNicDeviceName("test", "incusbr0")).thenReturn("eth0");
        when(incus.configGet("test", Metadata.PROXY_GATEWAY)).thenReturn("172.20.0.1");
        when(incus.machineType("test")).thenReturn(MachineType.CONTAINER);

        assertTrue(InstanceLifecycle.fixStaticIpIfNeeded(incus, "test"));

        verify(incus).configSetAll(eq("test"), argThat(map ->
                map.containsKey(Metadata.PROXY_GATEWAY)
                && "172.21.0.1".equals(map.get(Metadata.PROXY_GATEWAY))));
    }

    @Test
    void fixStaticIpIfNeeded_vm_skipsFilePush() {
        var incus = mock(IncusClient.class);
        when(incus.configGet("test", Metadata.STATIC_IP)).thenReturn("172.20.0.5");
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("172.21.0.1/24");
        when(incus.listJsonConfig()).thenReturn("[]");
        when(incus.findNicDeviceName("test", "incusbr0")).thenReturn("eth0");
        when(incus.configGet("test", Metadata.PROXY_GATEWAY)).thenReturn("");
        when(incus.machineType("test")).thenReturn(MachineType.VM);

        assertTrue(InstanceLifecycle.fixStaticIpIfNeeded(incus, "test"));

        verify(incus, never()).filePush(any(), eq("test"), any());
        // ...so the file is pushed once the VM runs (#997)
        verify(incus).configSetAll(eq("test"), argThat(map ->
                "true".equals(map.get(Metadata.NETWORK_PUSH_PENDING))));
    }

    @Test
    void findStaleSubnetInstances_detectsStaleIps() {
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("172.21.0.1/24");
        when(incus.listJsonConfig()).thenReturn("""
                [{"name": "good", "config": {"%1$s": "172.21.0.5"}},
                 {"name": "stale", "config": {"%1$s": "172.20.0.5"}},
                 {"name": "airgap", "config": {}}]
                """.formatted(Metadata.STATIC_IP));

        var stale = InstanceLifecycle.findStaleSubnetInstances(incus);
        assertEquals(List.of("stale"), stale);
    }

    @Test
    void migrateAllInstancesToNewSubnet_handlesFailureGracefully() {
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("172.21.0.1/24");
        // One listing names every stale instance: no read per instance
        when(incus.listJsonConfig()).thenReturn("""
                [{"name":"fail-instance","config":{"%1$s":"172.20.0.2"}},
                 {"name":"ok-instance","config":{"%1$s":"172.21.0.3"}},
                 {"name":"stale-instance","config":{"%1$s":"172.20.0.4"}}]
                """.formatted(Metadata.STATIC_IP));
        when(incus.findNicDeviceName("fail-instance", "incusbr0"))
                .thenThrow(new RuntimeException("no NIC"));
        when(incus.findNicDeviceName("stale-instance", "incusbr0")).thenReturn("eth0");
        when(incus.configGet("stale-instance", Metadata.PROXY_GATEWAY)).thenReturn("");
        when(incus.machineType("stale-instance")).thenReturn(MachineType.CONTAINER);

        assertEquals(1, InstanceLifecycle.migrateAllInstancesToNewSubnet(incus),
                "a failure on one instance must not stop the others");
        verify(incus).deviceConfigSet("stale-instance", "eth0", "ipv4.address", "172.21.0.2");
        verify(incus, never()).configGet(anyString(), eq(Metadata.STATIC_IP));
        verify(incus, never()).deviceConfigSet(eq("ok-instance"), any(), any(), any());
    }
}
