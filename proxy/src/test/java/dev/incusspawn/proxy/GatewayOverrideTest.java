package dev.incusspawn.proxy;

import dev.incusspawn.Platform;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code --gateway-ip} decides where the health endpoint listens too, without a second bridge
 * lookup that would ignore the override or throw when the bridge cannot be read (#892).
 */
class GatewayOverrideTest {

    private static final String OVERRIDE = "10.99.0.1";

    @TempDir
    Path home;
    private String originalHome;

    @BeforeEach
    void isolateHome() {
        // No config.yaml, so no cached incusBridgeGateway to fall back on.
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

    @Test
    void healthBindsToTheOverrideRatherThanTheDetectedBridge() {
        var bridgeReads = new AtomicInteger();
        var incus = new IncusClient() {
            @Override
            public String networkConfigGet(String networkName, String key) {
                bridgeReads.incrementAndGet();
                return "ipv4.address".equals(key) ? "10.166.11.1/24" : "";
            }
        };

        var addresses = ProxyMain.resolveAddresses(OVERRIDE, incus);

        assertEquals(OVERRIDE, addresses.gateway());
        assertEquals(expectedHealthBind(), addresses.healthBind());
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
}
