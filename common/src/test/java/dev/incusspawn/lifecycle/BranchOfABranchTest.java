package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A branch copied from another branch must not start with its source's address: Incus accepts
 * the conflicting copy, and the proxy would map the address to whichever instance it lists last.
 */
class BranchOfABranchTest {

    private static InstanceLifecycle.BranchSettings settings(String parent) {
        return new InstanceLifecycle.BranchSettings(
                null, "8GiB", "20GiB", NetworkMode.FULL, parent, Map.of(), false);
    }

    @Test
    void theCopyCarriesNoAddressUntilItClaimsItsOwn() {
        var daemon = new FakeIncusDaemon().container("dev-1", Map.of());
        var incus = daemon.client();
        InstanceLifecycle.configureBranch(incus, "dev-1", settings("tpl"));
        assertEquals("10.166.11.2", daemon.instance("dev-1").path("devices").path("eth0")
                .path("ipv4.address").asText());

        daemon.clearRequests();
        incus.copy("dev-1", "dev-2");
        // The source read planCopy already made, the pools, the POST and its wait: nothing more
        var requests = daemon.requests();
        assertEquals(4, requests.size(), () -> String.join("\n", requests));
        assertEquals(List.of("GET /1.0/instances/dev-1", "GET /1.0/storage-pools?recursion=1",
                "POST /1.0/instances"), requests.subList(0, 3));

        var copy = daemon.instance("dev-2");
        var nic = copy.path("devices").path("eth0");
        assertEquals("nic", nic.path("type").asText(), "the NIC itself is kept");
        assertEquals("true", nic.path("security.ipv4_filtering").asText());
        assertFalse(nic.has("ipv4.address"), "the copy took its source's address");
        assertFalse(copy.path("config").has(Metadata.STATIC_IP),
                "the proxy would identify the source's traffic as the copy's");
        assertFalse(copy.path("config").has(Metadata.STATIC_GATEWAY));
        assertEquals("10.166.11.2", daemon.instance("dev-1").path("config")
                .path(Metadata.STATIC_IP).asText(), "the source keeps its own");

        InstanceLifecycle.configureBranch(incus, "dev-2", settings("dev-1"));
        assertEquals("10.166.11.3", daemon.instance("dev-2").path("config")
                .path(Metadata.STATIC_IP).asText());
    }

    @Test
    void aTemplateCopySendsNoDevices() {
        var daemon = new FakeIncusDaemon().container("tpl", Map.of());
        daemon.client().copy("tpl", "dev-1");
        var copy = daemon.instance("dev-1");
        assertEquals(0, copy.path("devices").size(), "the profile's NIC stays the profile's");
        assertEquals("nic", copy.path("expanded_devices").path("eth0").path("type").asText());
    }
}
