package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import dev.incusspawn.Platform;
import dev.incusspawn.incus.FakeIncusDaemon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code isx-proxy} exits {@code EXIT_CONFIG} until init has completed, so installing its service
 * before then only produces "proxy is not responding" (issue #968). Installing refuses first and
 * names {@code isx init}, before it writes a service file or touches systemd or launchd.
 * <p>
 * Restarting refuses the same way (#1048): after an upgrade that raised {@code INIT_VERSION}, the
 * proxy still running is a working one, and a restart would replace it with one that refuses to
 * start. Every restart path leaves the service and its files alone until {@code isx init} runs.
 */
class ProxyServiceInitGateTest {

    @TempDir
    Path home;
    private String savedHome;
    private PrintStream savedErr;
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final List<String> commands = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private Predicate<String[]> savedRunner;

    @BeforeEach
    void isolate() {
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        savedErr = System.err;
        System.setErr(new PrintStream(err, true));
        // A regression here must not restart whatever proxy service the test machine runs.
        savedRunner = ProxyService.replaceCommandRunner(command -> {
            commands.add(String.join(" ", command));
            return false;
        });
    }

    @AfterEach
    void restore() {
        ProxyService.replaceCommandRunner(savedRunner);
        System.setErr(savedErr);
        System.setProperty("user.home", savedHome);
    }

    @Test
    void installRefusesBeforeInitAndAsksForIt() {
        assertFalse(ProxyService.install());
        assertAsksForInit();
    }

    @Test
    void installRefusesWithAMarkerFromAnOlderInit() throws Exception {
        markerFromAnOlderInit();

        assertFalse(ProxyService.install());
        assertAsksForInit();
    }

    @Test
    void restartKeepsTheRunningProxyWithAMarkerFromAnOlderInit() throws Exception {
        markerFromAnOlderInit();

        assertFalse(ProxyService.restart());
        assertRefusedWithoutTouchingTheService(err.toString());
    }

    /** The automatic paths report through a sink, which the TUI routes to its warning log. */
    @Test
    void restartReportsItsRefusalToTheCallersLog() throws Exception {
        markerFromAnOlderInit();

        assertFalse(ProxyService.restart(log::add));
        assertRefusedWithoutTouchingTheService(String.join("\n", log));
        assertEquals("", err.toString(), "nothing printed past the sink");
    }

    @Test
    void restartIfUnhealthyRefusesWithAMarkerFromAnOlderInit() throws Exception {
        markerFromAnOlderInit();

        assertFalse(ProxyService.restartIfUnhealthy("127.0.0.1", unusedPort(), log::add));
        assertRefusedWithoutTouchingTheService(String.join("\n", log));
    }

    /**
     * Drift right after an upgrade is exactly when this runs. The files are left alone as well:
     * rewritten without the restart, they would compare equal on the run of {@code isx init} that
     * could restart onto them.
     */
    @Test
    void reinstallIfChangedLeavesTheServiceAndItsFilesAlone() throws Exception {
        markerFromAnOlderInit();
        var unit = staleUnitFile();

        assertFalse(ProxyService.reinstallIfChanged(null, null));
        assertRefusedWithoutTouchingTheService(err.toString());
        assertEquals("stale", Files.readString(unit));
    }

    @Test
    void upgradeIfNeededLeavesTheServiceAndItsFilesAlone() throws Exception {
        markerFromAnOlderInit();
        var unit = staleUnitFile();

        assertFalse(ProxyService.upgradeIfNeeded());
        assertRefusedWithoutTouchingTheService(err.toString());
        assertEquals("stale", Files.readString(unit));
    }

    /** Reached from the TUI's listing, so it reports through the sink, and skips the wait for health. */
    @Test
    void autoRestartRefusesWithAMarkerFromAnOlderInit() throws Exception {
        assumeTrue(Platform.isLinux(), "the service is installed by its systemd unit here");
        markerFromAnOlderInit();
        staleUnitFile();

        // A loopback gateway nothing listens on: the proxy reads as unhealthy at once.
        var incus = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", "127.0.0.2/8")).client();

        assertFalse(ProxyHealthCheck.tryAutoRestart(incus, log::add));
        assertRefusedWithoutTouchingTheService(String.join("\n", log));
        assertEquals("", err.toString(), "nothing printed past the sink");
    }

    private static void markerFromAnOlderInit() throws Exception {
        Files.createDirectories(Environment.configDir());
        Files.writeString(Environment.initCompleteMarker(), String.valueOf(Environment.INIT_VERSION - 1));
    }

    private static Path staleUnitFile() throws Exception {
        var unit = Environment.proxyServiceFile();
        Files.createDirectories(unit.getParent());
        Files.writeString(unit, "stale");
        return unit;
    }

    private static int unusedPort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void assertRefusedWithoutTouchingTheService(String output) {
        assertTrue(output.contains("Run 'isx init' first"), output);
        assertEquals(List.of(), commands, "no systemctl call");
    }

    private void assertAsksForInit() {
        assertTrue(err.toString().contains("Run 'isx init' first"), err::toString);
        assertFalse(Files.exists(Environment.proxyServiceFile()), "no service file before init");
        assertFalse(Files.exists(Environment.configDir().resolve("proxy.lock")), "no proxy lock taken");
    }
}
