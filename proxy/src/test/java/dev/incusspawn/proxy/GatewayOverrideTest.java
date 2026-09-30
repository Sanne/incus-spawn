package dev.incusspawn.proxy;

import dev.incusspawn.Platform;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --gateway-ip} decides where the health endpoint listens and where bridge DNS points,
 * without a second bridge lookup that would ignore the override or fail when the bridge has no
 * address to read (#892). On macOS neither follows the gateway (health is on localhost, DNS
 * inside the VM points at the VM's own bridge), so these cases guard the Linux behaviour.
 */
class GatewayOverrideTest {

    private static final String OVERRIDE = "10.99.0.1";

    @TempDir
    Path home;
    private String originalHome;

    @BeforeEach
    void isolateHome() {
        // No config.yaml, so no cached incusBridgeGateway to fall back on: a bridge lookup the
        // override should have skipped fails here instead of quietly finding a cached gateway.
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalHome);
    }

    private static String expectedHealthBind() {
        return Platform.isMacOS() ? "127.0.0.1" : OVERRIDE;
    }

    private static String expectedBridgeDns() {
        return Platform.isMacOS() ? null : OVERRIDE;
    }

    @Test
    void healthBindsToTheOverrideRatherThanTheDetectedBridge() {
        var bridgeReads = new AtomicInteger();
        var incus = bridgeAt("10.166.11.1/24", bridgeReads);

        var addresses = ProxyMain.resolveAddresses(OVERRIDE, incus);

        assertEquals(OVERRIDE, addresses.gateway());
        assertEquals(expectedHealthBind(), addresses.healthBind());
        assertEquals(expectedBridgeDns(), addresses.bridgeDns());
        assertEquals(0, bridgeReads.get(), "the override leaves nothing to look up");
    }

    @Test
    void overrideWorksAroundAnUnreadableBridge() {
        var incus = new IncusClient() {
            @Override
            public String networkConfigGet(String networkName, String key) {
                throw new IncusException("Incus is not reachable");
            }
        };

        var addresses = ProxyMain.resolveAddresses(OVERRIDE, incus);

        assertEquals(OVERRIDE, addresses.gateway());
        assertEquals(expectedHealthBind(), addresses.healthBind());
    }

    @Test
    void bridgeDnsPointsAtTheOverrideOnABridgeWithNoAddress() {
        var writes = new ArrayList<String>();
        var incus = new IncusClient() {
            @Override
            public String networkConfigGet(String networkName, String key) {
                if ("ipv4.address".equals(key)) throw new IncusException("Bridge incusbr0 has no ipv4.address configured");
                return "";
            }

            @Override
            public void networkConfigSet(String networkName, String key, String value) {
                writes.add(key + "=" + value);
            }
        };
        var addresses = ProxyMain.resolveAddresses(OVERRIDE, incus);
        if (Platform.isMacOS()) return;

        addresses.writeBridgeDns(incus, Set.of("github.com"));

        assertEquals(1, writes.size());
        assertTrue(writes.getFirst().contains("address=/github.com/" + OVERRIDE), writes.getFirst());
    }

    @Test
    void refusesAnOverrideThatCannotBeTheBridgeGateway() {
        var incus = bridgeAt("10.166.11.1/24", new AtomicInteger());
        if (Platform.isMacOS()) return;
        for (var bad : List.of("0.0.0.0", "::", "127.0.0.1", "169.254.1.1", "fd42::1",
                " 10.99.0.1", "gw.local")) {
            assertNull(ProxyMain.resolveAddresses(bad, incus), bad);
        }
    }

    private static IncusClient bridgeAt(String ipv4Address, AtomicInteger reads) {
        return new IncusClient() {
            @Override
            public String networkConfigGet(String networkName, String key) {
                reads.incrementAndGet();
                return "ipv4.address".equals(key) ? ipv4Address : "";
            }
        };
    }
}
