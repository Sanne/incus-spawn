package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

        // StaticIpAllocator.allocate needs these
        when(incus.listJsonConfig()).thenReturn("[]");
        when(incus.findNicDeviceName("test", "incusbr0")).thenReturn("eth0");
        when(incus.configGet("test", Metadata.PROXY_GATEWAY)).thenReturn("");
        when(incus.isVm("test")).thenReturn(false);

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
        when(incus.isVm("test")).thenReturn(false);

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
        when(incus.isVm("test")).thenReturn(true);

        assertTrue(InstanceLifecycle.fixStaticIpIfNeeded(incus, "test"));

        verify(incus, never()).filePush(any(), eq("test"), any());
    }

    @Test
    void findStaleSubnetInstances_detectsStaleIps() {
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("172.21.0.1/24");
        when(incus.list()).thenReturn(List.of(
                Map.of("name", "good", "status", "Stopped", "type", "container"),
                Map.of("name", "stale", "status", "Stopped", "type", "container"),
                Map.of("name", "airgap", "status", "Stopped", "type", "container")));
        when(incus.configGet("good", Metadata.STATIC_IP)).thenReturn("172.21.0.5");
        when(incus.configGet("stale", Metadata.STATIC_IP)).thenReturn("172.20.0.5");
        when(incus.configGet("airgap", Metadata.STATIC_IP)).thenReturn("");

        var stale = InstanceLifecycle.findStaleSubnetInstances(incus);
        assertEquals(List.of("stale"), stale);
    }

    @Test
    void migrateAllInstancesToNewSubnet_handlesFailureGracefully() {
        var incus = mock(IncusClient.class);
        when(incus.list()).thenReturn(List.of(
                Map.of("name", "fail-instance", "status", "Stopped", "type", "container"),
                Map.of("name", "ok-instance", "status", "Stopped", "type", "container")));

        // First instance: stale IP that will fail during fix
        when(incus.configGet("fail-instance", Metadata.STATIC_IP)).thenReturn("172.20.0.2");
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("172.21.0.1/24");
        when(incus.findNicDeviceName("fail-instance", "incusbr0"))
                .thenThrow(new RuntimeException("no NIC"));

        // Second instance: already on correct subnet
        when(incus.configGet("ok-instance", Metadata.STATIC_IP)).thenReturn("172.21.0.3");

        int migrated = InstanceLifecycle.migrateAllInstancesToNewSubnet(incus);
        assertEquals(0, migrated);
    }
}
