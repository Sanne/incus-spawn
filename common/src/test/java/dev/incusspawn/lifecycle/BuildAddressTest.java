package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.InstanceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A build container is served its template's accounts only if the proxy can tell it apart from
 * host traffic, which it does by static address (#903).
 */
@ExtendWith(TempHome.class)
class BuildAddressTest {

    private static final String PIN = Metadata.accountKey("github");

    @Test
    void aBuildContainerIsKnownToTheProxyByItsTemplatesPins() {
        var daemon = new FakeIncusDaemon().container("tpl-rebuilding", Map.of());
        var incus = daemon.client();

        var ip = InstanceLifecycle.assignBuildAddress(incus, "tpl-rebuilding",
                config -> Map.of(PIN, "bot"));

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
        var ip = InstanceLifecycle.assignBuildAddress(incus, "tpl-rebuilding",
                config -> Map.of(PIN, "bot"));

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
        assertEquals(ip, InstanceLifecycle.assignBuildAddress(incus, "next-rebuilding", config -> Map.of()));
    }

    @Test
    void aNicTheTemplateOverridesItselfKeepsItsOwnSettings() {
        var daemon = new FakeIncusDaemon().container("tpl-rebuilding", Map.of())
                .device("tpl-rebuilding", "eth0",
                        Map.of("type", "nic", "network", "incusbr0", "name", "eth0", "mtu", "1400"));
        var incus = daemon.client();
        InstanceLifecycle.assignBuildAddress(incus, "tpl-rebuilding", config -> Map.of());

        InstanceLifecycle.releaseBuildAddress(incus, "tpl-rebuilding");

        var nic = daemon.instance("tpl-rebuilding").path("devices").path("eth0");
        assertEquals("1400", nic.path("mtu").asText());
        assertFalse(nic.has("ipv4.address"));
        assertFalse(nic.has("security.ipv4_filtering"));
    }
}
