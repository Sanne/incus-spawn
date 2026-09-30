package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.InstanceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A build container is served its template's accounts only if the proxy can tell it apart from
 * host traffic, which it does by static address (#903).
 */
@ExtendWith(TempHome.class)
class BuildAddressTest {

    private static final String PIN = Metadata.accountKey("github");

    private static String assign(IncusClient incus, String name, Map<String, String> config) {
        return InstanceLifecycle.assignBuildAddress(incus, name, incus.instanceMetadata(name), config);
    }

    @Test
    void aBuildContainerIsKnownToTheProxyByItsTemplatesPins() {
        var daemon = new FakeIncusDaemon().container("tpl-rebuilding", Map.of());
        var incus = daemon.client();

        var ip = assign(incus, "tpl-rebuilding", Map.of(PIN, "bot"));

        var instance = daemon.instance("tpl-rebuilding");
        var nic = instance.path("devices").path("eth0");
        assertEquals(ip, nic.path("ipv4.address").asText());
        assertEquals("true", nic.path("security.ipv4_filtering").asText(),
                "without filtering the address identifies nothing");
        assertEquals(ip, instance.path("config").path(Metadata.STATIC_IP).asText());
        assertNull(daemon.pushedMode("tpl-rebuilding", "/etc/systemd/network/10-eth0.network"),
                "a build keeps DHCP: the NIC's address is what Incus's DHCP server hands out");

        var registry = new InstanceRegistry(incus);
        registry.refresh();
        var served = registry.lookup(ip);
        assertEquals("tpl-rebuilding", served.instanceName());
        assertEquals(Map.of("github", "bot"), served.accountsByNamespace());
    }

    @Test
    void theStoppedTemplateGivesItsAddressBack() {
        var daemon = new FakeIncusDaemon().container("tpl-rebuilding", Map.of());
        var incus = daemon.client();
        var ip = assign(incus, "tpl-rebuilding", Map.of(PIN, "bot"));

        InstanceLifecycle.releaseBuildAddress(incus, "tpl-rebuilding");

        var instance = daemon.instance("tpl-rebuilding");
        assertEquals(0, instance.path("devices").size(), "the NIC is the profile's again");
        assertEquals("nic", instance.path("expanded_devices").path("eth0").path("type").asText());
        assertFalse(instance.path("config").has(Metadata.STATIC_IP));
        assertEquals("bot", instance.path("config").path(PIN).asText(),
                "the pins travel to every branch");
        var registry = new InstanceRegistry(incus);
        registry.refresh();
        assertNull(registry.lookup(ip));

        // The address is free for the next claimant
        daemon.container("next-rebuilding", Map.of());
        assertEquals(ip, assign(incus, "next-rebuilding", Map.of()));
    }

    @Test
    void aNicTheTemplateOverridesItselfKeepsItsOwnSettings() {
        var daemon = new FakeIncusDaemon().container("tpl-rebuilding", Map.of())
                .device("tpl-rebuilding", "eth0",
                        Map.of("type", "nic", "network", "incusbr0", "name", "eth0", "mtu", "1400"));
        var incus = daemon.client();
        assign(incus, "tpl-rebuilding", Map.of());

        InstanceLifecycle.releaseBuildAddress(incus, "tpl-rebuilding");

        var nic = daemon.instance("tpl-rebuilding").path("devices").path("eth0");
        assertEquals("1400", nic.path("mtu").asText());
        assertFalse(nic.has("ipv4.address"));
        assertFalse(nic.has("security.ipv4_filtering"));
    }

    @Test
    void anAddressTheBridgeHasLeasedIsSkipped() {
        // The parent's build just gave .2 back, but dnsmasq keeps its MAC's lease on it and
        // would not hand .2 to this build's guest
        var daemon = new FakeIncusDaemon().container("tpl-child-rebuilding", Map.of())
                .lease("10.166.11.2");

        assertEquals("10.166.11.3", assign(daemon.client(), "tpl-child-rebuilding", Map.of()));
    }

    @Test
    void aGuestHoldingItsAddressPasses() {
        var daemon = new FakeIncusDaemon().container("tpl-rebuilding", Map.of());
        var incus = daemon.client();
        var ip = assign(incus, "tpl-rebuilding", Map.of());
        incus.start("tpl-rebuilding");

        InstanceLifecycle.requireBuildAddress(incus, "tpl-rebuilding", ip, Duration.ZERO);
    }

    @Test
    void aGuestDhcpGaveAnotherAddressFailsTheBuildSayingWhy() {
        var daemon = new FakeIncusDaemon().container("tpl-rebuilding", Map.of())
                .dhcpAnswers("tpl-rebuilding", "10.166.11.87");
        var incus = daemon.client();
        var ip = assign(incus, "tpl-rebuilding", Map.of());
        incus.start("tpl-rebuilding");

        var e = assertThrows(IncusException.class,
                () -> InstanceLifecycle.requireBuildAddress(incus, "tpl-rebuilding", ip, Duration.ZERO));
        assertTrue(e.getMessage().contains(ip) && e.getMessage().contains("10.166.11.87"), e.getMessage());
        assertTrue(e.getMessage().contains("lease"), e.getMessage());
    }

    @Test
    void anotherInterfacesAddressIsNotTheDhcpAnswer() {
        // A parent that enabled docker: its bridge is up, but the guest holds its own address
        var daemon = new FakeIncusDaemon().container("tpl-rebuilding", Map.of())
                .guestInterface("tpl-rebuilding", "172.17.0.1");
        var incus = daemon.client();
        var ip = assign(incus, "tpl-rebuilding", Map.of());
        incus.start("tpl-rebuilding");

        InstanceLifecycle.requireBuildAddress(incus, "tpl-rebuilding", ip, Duration.ZERO);
    }

    @Test
    void aGuestWithOnlyAnotherInterfaceIsLeftToTheNetworkWaits() {
        var daemon = new FakeIncusDaemon().container("tpl-rebuilding", Map.of())
                .guestInterface("tpl-rebuilding", "172.17.0.1")
                .dhcpAnswers("tpl-rebuilding", "");
        var incus = daemon.client();
        var ip = assign(incus, "tpl-rebuilding", Map.of());
        incus.start("tpl-rebuilding");

        InstanceLifecycle.requireBuildAddress(incus, "tpl-rebuilding", ip, Duration.ZERO);
    }

    @Test
    void releaseTouchesOnlyTheNicTheAddressWasClaimedOn() {
        var other = Map.of("type", "nic", "network", "lab", "name", "eth1",
                "ipv4.address", "192.168.50.7", "security.ipv4_filtering", "true");
        var daemon = new FakeIncusDaemon().container("tpl-rebuilding", Map.of())
                .device("tpl-rebuilding", "eth1", other);
        var incus = daemon.client();
        assign(incus, "tpl-rebuilding", Map.of());

        InstanceLifecycle.releaseBuildAddress(incus, "tpl-rebuilding");

        var devices = daemon.instance("tpl-rebuilding").path("devices");
        assertFalse(devices.has("eth0"), "the claimed NIC is the profile's again");
        assertEquals("192.168.50.7", devices.path("eth1").path("ipv4.address").asText());
        assertEquals("true", devices.path("eth1").path("security.ipv4_filtering").asText());
    }
}
