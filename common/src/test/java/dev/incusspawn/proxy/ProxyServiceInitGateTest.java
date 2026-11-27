package dev.incusspawn.proxy;

import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.Environment;
import dev.incusspawn.Platform;
import dev.incusspawn.incus.FakeIncusDaemon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    private final List<String> commands = new ArrayList<>();
    private Predicate<String[]> savedRunner;

    @BeforeEach
    void isolate() {
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        // A regression here must not restart whatever proxy service the test machine runs.
        savedRunner = ProxyService.replaceCommandRunner(command -> {
            commands.add(String.join(" ", command));
            return false;
        });
    }

    @AfterEach
    void restore() {
        ProxyService.replaceCommandRunner(savedRunner);
        System.setProperty("user.home", savedHome);
    }

    @Test
    void installRefusesBeforeInitAndAsksForIt() {
        assertAsksForInit(RecordingOutput.during(() -> assertFalse(ProxyService.install())));
    }

    @Test
    void installRefusesWithAMarkerFromAnOlderInit() throws Exception {
        markerFromAnOlderInit();

        assertAsksForInit(RecordingOutput.during(() -> assertFalse(ProxyService.install())));
    }

    @Test
    void restartKeepsTheRunningProxyWithAMarkerFromAnOlderInit() throws Exception {
        markerFromAnOlderInit();

        assertRefusedWithoutTouchingTheService(RecordingOutput.during(() -> assertFalse(ProxyService.restart())));
    }

    /** The automatic paths report to the caller's output, which the TUI routes to its warning log. */
    @Test
    void restartReportsItsRefusalToTheCallersLog() throws Exception {
        markerFromAnOlderInit();
        var log = new RecordingOutput();

        var bound = RecordingOutput.during(() -> assertFalse(ProxyService.restart(log)));
        assertRefusedWithoutTouchingTheService(log);
        assertEquals(List.of(), bound.events(), "nothing reported past the caller's output");
    }

    @Test
    void restartIfUnhealthyRefusesWithAMarkerFromAnOlderInit() throws Exception {
        markerFromAnOlderInit();

        var log = new RecordingOutput();
        assertFalse(ProxyService.restartIfUnhealthy("127.0.0.1", unusedPort(), log));
        assertRefusedWithoutTouchingTheService(log);
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

        assertRefusedWithoutTouchingTheService(
                RecordingOutput.during(() -> assertFalse(ProxyService.reinstallIfChanged(null, null))));
        assertEquals("stale", Files.readString(unit));
    }

    /**
     * The drift warning has already said that isx init restarts the service, so its reinstall
     * refuses without "Run 'isx init' first", which would contradict it (#2673 review).
     */
    @Test
    void reinstallAfterTheDriftWarningRefusesWithoutAnError() throws Exception {
        markerFromAnOlderInit();
        var unit = staleUnitFile();

        var output = RecordingOutput.during(() -> assertFalse(ProxyService.reinstallIfChanged(null, null, true)));
        assertEquals(List.of(), output.events(), "nothing printed");
        assertEquals(List.of(), commands, "no systemctl call");
        assertEquals("stale", Files.readString(unit));
    }

    @Test
    void upgradeIfNeededLeavesTheServiceAndItsFilesAlone() throws Exception {
        markerFromAnOlderInit();
        var unit = staleUnitFile();

        assertRefusedWithoutTouchingTheService(RecordingOutput.during(() -> assertFalse(ProxyService.upgradeIfNeeded())));
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

        var log = new RecordingOutput();
        var bound = RecordingOutput.during(() -> assertFalse(ProxyHealthCheck.tryAutoRestart(incus, log)));
        assertRefusedWithoutTouchingTheService(log);
        assertEquals(List.of(), bound.events(), "nothing reported past the caller's output");
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

    private void assertRefusedWithoutTouchingTheService(RecordingOutput output) {
        assertRefusalIsAnError(output);
        assertEquals(List.of(), commands, "no systemctl call");
    }

    private void assertAsksForInit(RecordingOutput output) {
        assertRefusalIsAnError(output);
        assertFalse(Files.exists(Environment.proxyServiceFile()), "no service file before init");
        assertFalse(Files.exists(Environment.configDir().resolve("proxy.lock")), "no proxy lock taken");
    }

    /** The refusal is why the command failed: an error, not a warning or a note a log would drop. */
    private static void assertRefusalIsAnError(RecordingOutput output) {
        var errors = output.events(RecordingOutput.Kind.ERROR);
        assertEquals(1, errors.size(), output.events()::toString);
        assertTrue(errors.getFirst().msg().contains("Run 'isx init' first"), errors.getFirst()::msg);
    }
}
