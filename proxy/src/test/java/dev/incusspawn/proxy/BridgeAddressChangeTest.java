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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * On Linux the proxy listens on the gateway it read from the bridge at startup. When the
 * bridge's address changes under it, nothing can reach that address any more, so it must not
 * point bridge DNS back at it on reload, and it stops to be restarted on the new one (#966).
 * An explicit {@code --gateway-ip} is the user's choice and is never "corrected" (#933).
 */
class BridgeAddressChangeTest {

    private static final String OLD = "10.166.11.1";
    private static final String NEW = "10.166.22.1";

    @TempDir
    Path home;
    private String originalHome;

    @BeforeEach
    void isolateHome() {
        // On macOS the gateway is the VM link, which never follows the Incus bridge.
        assumeFalse(Platform.isMacOS());
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        if (originalHome != null) System.setProperty("user.home", originalHome);
    }

    @Test
    void aReloadDoesNotPointBridgeDnsBackAtAnAddressTheBridgeGaveUp() {
        var bridge = new AtomicReference<>(OLD + "/24");
        var writes = new ArrayList<String>();
        var incus = bridge(bridge, writes);
        var addresses = ProxyMain.resolveAddresses(null, incus);
        assertEquals(OLD, addresses.gateway());

        bridge.set(NEW + "/24");
        try {
            addresses.writeBridgeDns(incus, Set.of("github.com"));
        } catch (RuntimeException refused) {
            // Refusing is the point; what must not happen is the write below.
        }

        assertTrue(writes.stream().noneMatch(w -> w.contains("/" + OLD)),
                "bridge DNS pointed back at the address the bridge gave up: " + writes);
    }

    @Test
    void aReloadAfterTheBridgeMovedStopsTheProxyForItsServiceToRestart() {
        var bridge = new AtomicReference<>(OLD + "/24");
        var writes = new ArrayList<String>();
        var incus = bridge(bridge, writes);
        var exitCode = new AtomicInteger();
        var proxy = proxyFollowing(incus, exitCode);

        bridge.set(NEW + "/24");
        proxy.reload();

        assertEquals(1, exitCode.get(), "a non-zero exit, which Restart=on-failure restarts");
        assertEquals(List.of(), writes);
    }

    @Test
    void aStopFromAReloadBeforeStartIsNotLost() throws Exception {
        var bridge = new AtomicReference<>(OLD + "/24");
        var incus = bridge(bridge, new ArrayList<>());
        var exitCode = new AtomicInteger();
        var proxy = proxyFollowing(incus, exitCode);

        // The config watcher and SIGHUP handler are live before start(): a reload can come first.
        bridge.set(NEW + "/24");
        proxy.reload();
        var ready = new AtomicInteger();
        proxy.start(ready::incrementAndGet);

        assertEquals(1, exitCode.get());
        assertEquals(0, ready.get(), "start() returned without binding, rather than waiting forever");
    }

    @Test
    void aReloadOnAnUnchangedBridgeRewritesDnsAsBefore() {
        var bridge = new AtomicReference<>(OLD + "/24");
        var writes = new ArrayList<String>();
        var incus = bridge(bridge, writes);
        var exitCode = new AtomicInteger();
        var proxy = proxyFollowing(incus, exitCode);

        proxy.reload();

        assertEquals(0, exitCode.get());
        assertEquals(1, writes.size());
        assertTrue(writes.getFirst().contains("address=/api.anthropic.com/" + OLD), writes.getFirst());
    }

    @Test
    void anExplicitGatewayIsNeverCorrectedToTheBridges() {
        var override = "10.99.0.1";
        var reads = new AtomicInteger();
        var writes = new ArrayList<String>();
        var incus = bridge(new AtomicReference<>(NEW + "/24"), writes, reads);
        var addresses = ProxyMain.resolveAddresses(override, incus);

        assertFalse(addresses.followsBridge());
        assertTrue(addresses.movedTo(incus).isEmpty());
        addresses.writeBridgeDns(incus, Set.of("github.com"));
        assertTrue(writes.getLast().contains("address=/github.com/" + override), writes.getLast());
        assertEquals(0, reads.get(), "an override is never compared with the bridge");
    }

    @Test
    void aBridgeThatCannotBeReadOrHasNoAddressIsNotAMove() {
        var bridge = new AtomicReference<>(OLD + "/24");
        var incus = bridge(bridge, new ArrayList<>());
        var addresses = ProxyMain.resolveAddresses(null, incus);

        bridge.set("");
        assertTrue(addresses.movedTo(incus).isEmpty(), "no address: a restart would find nothing better");
        bridge.set(null);
        assertTrue(addresses.movedTo(incus).isEmpty(), "unreadable: transient, not a move");
        bridge.set(NEW + "/24");
        assertTrue(addresses.movedTo(incus).isPresent());
    }

    /** A proxy whose gateway came from {@code incus}'s bridge, wired for reloads as ProxyMain does. */
    private static MitmProxy proxyFollowing(IncusClient incus, AtomicInteger exitCode) {
        var addresses = ProxyMain.resolveAddresses(null, incus);
        var proxy = new MitmProxy(null, addresses.gateway(), 0, 0, addresses.healthBind(),
                ConfigFingerprint.load());
        proxy.setBridgeDnsWriter(ProxyMain.reloadDnsWriter(addresses, incus, proxy, exitCode));
        return proxy;
    }

    private static IncusClient bridge(AtomicReference<String> address, List<String> writes) {
        return bridge(address, writes, new AtomicInteger());
    }

    /**
     * A bridge at {@code address} (null: unreadable), recording the raw.dnsmasq values written
     * to it and counting reads of its address.
     */
    private static IncusClient bridge(AtomicReference<String> address, List<String> writes, AtomicInteger reads) {
        return new IncusClient() {
            @Override
            public String networkConfigGet(String networkName, String key) {
                if (!"ipv4.address".equals(key)) return "";
                reads.incrementAndGet();
                var current = address.get();
                if (current == null) throw new IncusException("Incus is not reachable");
                return current;
            }

            @Override
            public void networkConfigSet(String networkName, String key, String value) {
                writes.add(value);
            }
        };
    }
}
