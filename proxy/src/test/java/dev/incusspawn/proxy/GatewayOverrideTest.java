package dev.incusspawn.proxy;

import dev.incusspawn.Platform;
import dev.incusspawn.config.SpawnConfig;
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
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code --gateway-ip} decides where the health endpoint listens and where bridge DNS points,
 * without a second bridge lookup that would ignore the override or fail when the bridge has no
 * address to read (#892). It may only name the address the CLI looks for the proxy at (#933).
 * On macOS neither follows the gateway (health is on localhost, DNS inside the VM points at the
 * VM's own bridge), so those cases guard the Linux behaviour; what the override may be is
 * checked for both platforms on every platform.
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
    void healthBindsToTheOverrideWithoutReadingTheBridgeAgain() {
        var bridgeReads = new AtomicInteger();
        var incus = bridgeAt(OVERRIDE + "/24", bridgeReads);

        var addresses = ProxyMain.resolveAddresses(OVERRIDE, incus);

        assertEquals(OVERRIDE, addresses.gateway());
        assertEquals(expectedHealthBind(), addresses.healthBind());
        assertEquals(expectedBridgeDns(), addresses.bridgeDns());
        assertEquals(0, bridgeReads.get(), "the check already compared the override with the bridge");
    }

    /** With no bridge address to read, the proxy and the CLI both find the gateway {@code isx init} cached. */
    @Test
    void withNoBridgeAddressTheProxyAndTheCliAgreeOnTheCachedGateway() {
        if (Platform.isMacOS()) return;
        var cached = "10.166.11.1";
        var config = SpawnConfig.load();
        config.setIncusBridgeGateway(cached);
        config.save();
        var incus = bridgeWithNoAddress(new ArrayList<>());

        var addresses = ProxyMain.resolveAddresses(null, incus);

        assertEquals(cached, ProxyHealthCheck.healthAddress(incus));
        assertEquals(cached, addresses.gateway());
        assertEquals(cached, addresses.healthBind());
        assertEquals(0, ProxyMain.checkGatewayOverride(cached, incus));
        assertEquals(ProxyService.EXIT_CONFIG, ProxyMain.checkGatewayOverride(OVERRIDE, incus),
                "the CLI would look for the proxy at the cached gateway");
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
        var incus = bridgeWithNoAddress(writes);
        var addresses = ProxyMain.resolveAddresses(OVERRIDE, incus);
        if (Platform.isMacOS()) return;

        addresses.configureBridgeDns(incus, Set.of("github.com"), java.util.List.of());

        assertEquals(1, writes.size());
        assertTrue(writes.getFirst().contains("address=/github.com/" + OVERRIDE), writes.getFirst());
    }

    /**
     * The exposed host ports' name points where the proxy listens and relays them (#2117): on
     * Linux the bridge gateway, the block's own address; on macOS the Mac's end of the VM link,
     * which the block's domains do not point at. Never while nothing is exposed.
     */
    @Test
    void theExposedHostNamePointsWhereTheProxyListens() {
        var linux = new ProxyMain.Addresses("10.1.2.1", "10.1.2.1", "10.1.2.1");
        assertEquals("10.1.2.1", linux.hostAddress(java.util.List.of(11434)));
        var macOS = new ProxyMain.Addresses("192.168.64.1", "192.168.64.1", null);
        assertEquals("192.168.64.1", macOS.hostAddress(java.util.List.of(11434)));
        assertNull(macOS.hostAddress(java.util.List.of()));

        var writes = new ArrayList<String>();
        var incus = bridgeWithNoAddress(writes);
        var addresses = ProxyMain.resolveAddresses(OVERRIDE, incus);
        if (Platform.isMacOS()) return;
        addresses.configureBridgeDns(incus, Set.of("github.com"), java.util.List.of(11434));
        assertTrue(writes.getFirst().contains("address=/host.isx.internal/" + OVERRIDE), writes.getFirst());
    }

    @Test
    void refusesAnOverrideThatCannotBeTheBridgeGatewayAsAConfigError() {
        assertEquals(0, checkOnLinux(null));
        assertEquals(0, checkOnLinux(" "), "blank means no override, as before");
        // Each would be written verbatim into every address=/<domain>/ line: not IPv4, not
        // this host's bridge, or not the canonical form dnsmasq and Vert.x would both read the
        // same way. ::ffff:10.99.0.1 parses as IPv4 but dnsmasq would serve it as AAAA.
        for (var bad : List.of("0.0.0.0", "::", "169.254.1.1", "fd42::1",
                "::ffff:10.99.0.1", "10.1", "010.099.000.001", "224.0.0.1", "255.255.255.255",
                "10.99.0.1/24", " 10.99.0.1", "gw.local")) {
            assertEquals(ProxyService.EXIT_CONFIG, checkOnLinux(bad), bad);
        }
    }

    /**
     * With no bridge address to read, the override stands in for it (#892). It is also the
     * credential listener's bind address, so it may then only be somewhere other hosts cannot
     * route to: a private range or loopback (#1022).
     */
    @Test
    void onLinuxWithNoBridgeAddressAcceptsPrivateOrLoopback() {
        for (var local : List.of("10.99.0.1", "172.16.0.5", "172.31.255.254", "192.168.1.20", "127.0.0.1")) {
            assertEquals(0, checkOnLinux(local, null), local);
        }
        for (var routable : List.of("8.8.8.8", "100.64.10.2", "172.32.0.1", "192.169.1.1", "11.0.0.1")) {
            assertEquals(1, checkOnLinux(routable, null), routable);
        }
    }

    /** Where the CLI looks while the bridge cannot be read: the gateway {@code isx init} cached (#933). */
    @Test
    void onLinuxWithNoBridgeAddressAnOverrideMustMatchTheCachedGateway() {
        var cached = "10.166.11.1";
        assertEquals(0, checkOnLinux(cached, null, cached));
        for (var other : List.of(OVERRIDE, "127.0.0.1", "8.8.8.8")) {
            assertEquals(ProxyService.EXIT_CONFIG, checkOnLinux(other, null, cached),
                    other + ": a config error, never the transient exit 1");
        }
    }

    /**
     * The CLI finds the proxy at the bridge's address, so an override that differs from a bridge
     * it can read would put the proxy where no {@code isx} command looks for it (#933).
     */
    @Test
    void onLinuxAnOverrideOtherThanAReadableBridgeIsRefused() {
        var bridge = "10.166.11.1";
        for (var other : List.of("10.99.0.1", "192.168.1.20", "127.0.0.1", "8.8.8.8", "10.166.11.2")) {
            assertEquals(ProxyService.EXIT_CONFIG, checkOnLinux(other, bridge), other);
        }
        assertEquals(0, checkOnLinux(bridge, bridge));
        assertEquals(0, checkOnLinux("100.64.10.1", "100.64.10.1"), "a bridge outside the private ranges is still the bridge");
    }

    @Test
    void onLinuxANonPrivateOverrideThatCannotBeCheckedIsRefused() {
        // Exit 1, as without an override: Incus not up yet is transient, not a config error.
        assertEquals(1, checkOnLinux("100.64.10.1", null),
                "with no bridge address to compare against, nothing is known safe");
    }

    /** On macOS the override is the credential listener's bind address (#937). */
    @Test
    void onMacOsAcceptsOnlyTheVmBridgeAddress() {
        var bridge = "192.168.64.1";
        Supplier<String> noLookup = () -> fail("no override, nothing to check");
        assertEquals(0, ProxyMain.checkGatewayOverride(null, true, noLookup, noLookup));
        assertEquals(0, ProxyMain.checkGatewayOverride(" ", true, noLookup, noLookup), "blank means no override, as before");
        assertEquals(0, checkOnMacOs(bridge, bridge));
        for (var bad : List.of("0.0.0.0", "::", "192.168.1.20", "10.99.0.1", "8.8.8.8",
                "127.0.0.1", "192.168.064.001", "::ffff:192.168.64.1", "192.168.64.1/24",
                " 192.168.64.1", "gw.local")) {
            assertEquals(ProxyService.EXIT_CONFIG, checkOnMacOs(bad, bridge), bad);
        }
    }

    @Test
    void onMacOsAnOverrideThatCannotBeCheckedIsRefused() {
        // Exit 1, as without an override: a VM not up yet is transient, not a config error.
        assertEquals(1, checkOnMacOs("192.168.64.1", null),
                "with no VM bridge to compare against, nothing is known safe");
    }

    /** What the proxy compares an override with: the bridge as Incus reports it now. */
    @Test
    void onLinuxTheBridgeIsReadFromIncusWithoutTheCachedGateway() {
        if (Platform.isMacOS()) return;
        var reads = new AtomicInteger();
        assertEquals(0, ProxyMain.checkGatewayOverride("100.64.10.1", bridgeAt("100.64.10.1/24", reads)));
        assertEquals(ProxyService.EXIT_CONFIG,
                ProxyMain.checkGatewayOverride("100.64.10.2", bridgeAt("100.64.10.1/24", reads)));
        assertEquals(ProxyService.EXIT_CONFIG,
                ProxyMain.checkGatewayOverride("192.168.1.20", bridgeAt("100.64.10.1/24", reads)));
        assertEquals(3, reads.get());

        // A cached gateway must not stand in for a bridge that cannot be read.
        var config = SpawnConfig.load();
        config.setIncusBridgeGateway("100.64.10.1");
        config.save();
        assertEquals("100.64.10.1", ProxyConfig.resolveGatewayIp(bridgeWithNoAddress(new ArrayList<>())),
                "the cache is in place");
        assertEquals(1, ProxyMain.checkGatewayOverride("100.64.10.1", bridgeWithNoAddress(new ArrayList<>())));
    }

    private static int checkOnLinux(String option) {
        Supplier<String> noLookup = () -> fail("no lookup needed for " + option);
        return ProxyMain.checkGatewayOverride(option, false, noLookup, noLookup);
    }

    /** {@code bridge} null: it cannot be read, and {@code isx init} cached no gateway. */
    private static int checkOnLinux(String option, String bridge) {
        return checkOnLinux(option, bridge, "");
    }

    private static int checkOnLinux(String option, String bridge, String cached) {
        return ProxyMain.checkGatewayOverride(option, false, () -> bridge, () -> cached);
    }

    private static int checkOnMacOs(String option, String bridge) {
        return ProxyMain.checkGatewayOverride(option, true, () -> bridge, () -> fail("no cache on macOS"));
    }

    @Test
    void aReloadPointsBridgeDnsWhereStartupDid() {
        var writes = new ArrayList<String>();
        var incus = bridgeWithNoAddress(writes);
        var addresses = ProxyMain.resolveAddresses(OVERRIDE, incus);
        if (Platform.isMacOS()) return;
        var proxy = new MitmProxy(null, addresses.gateway(), 0, 0, addresses.healthBind(),
                ConfigFingerprint.load());
        proxy.setBridgeDnsWriter(domains -> addresses.writeBridgeDns(incus, domains, java.util.List.of()));

        proxy.reload();

        assertEquals(1, writes.size());
        assertTrue(writes.getFirst().contains("address=/api.anthropic.com/" + OVERRIDE), writes.getFirst());
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

    /** A bridge with no IPv4 address to read, recording the raw.dnsmasq values written to it. */
    private static IncusClient bridgeWithNoAddress(List<String> writes) {
        return new IncusClient() {
            @Override
            public String networkConfigGet(String networkName, String key) {
                if ("ipv4.address".equals(key)) throw new IncusException("Bridge incusbr0 has no ipv4.address configured");
                return "";
            }

            @Override
            public void networkConfigSet(String networkName, String key, String value) {
                writes.add(value);
            }
        };
    }
}
