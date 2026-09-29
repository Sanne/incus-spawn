package dev.incusspawn.proxy;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ProxyConfig#isBridgeDnsComplete} against what Incus holds (#839). */
class BridgeDnsCompletenessTest {

    private static final Set<String> DOMAINS = Set.of("github.com", "api.anthropic.com");

    private static FakeIncusDaemon bridge(String rawDnsmasq) {
        return new FakeIncusDaemon().network("incusbr0",
                Map.of("ipv4.address", "10.1.2.1/24", "raw.dnsmasq", rawDnsmasq));
    }

    @Test
    void noOverridesAtAllIsNotComplete() {
        var incus = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", "10.1.2.1/24")).client();
        assertFalse(ProxyConfig.isBridgeDnsComplete(incus, DOMAINS));
    }

    @Test
    void onlyUserLinesIsNotComplete() {
        var incus = bridge("server=/corp.example/10.0.0.53").client();
        assertFalse(ProxyConfig.isBridgeDnsComplete(incus, DOMAINS));
    }

    @Test
    void everyDomainOverriddenIsComplete() {
        var incus = bridge(BridgeDns.render("", DOMAINS, "10.1.2.1")).client();
        assertTrue(ProxyConfig.isBridgeDnsComplete(incus, DOMAINS));
    }

    @Test
    void overridesForAnOldGatewayAreNotComplete() {
        // The bridge moved from 10.0.0.1 to 10.1.2.1 since the overrides were written (#840).
        var incus = bridge(BridgeDns.render("", DOMAINS, "10.0.0.1")).client();
        assertFalse(ProxyConfig.isBridgeDnsComplete(incus, DOMAINS));
    }

    @Test
    void anUnreadableBridgeIsNotReportedAsComplete() {
        var incus = new FakeIncusDaemon().withoutNetwork("incusbr0").client();
        assertThrows(IncusException.class, () -> ProxyConfig.isBridgeDnsComplete(incus, DOMAINS));
        assertEquals("", ProxyConfig.getDnsOverrides(incus), "the lenient reader keeps its contract");
    }
}
