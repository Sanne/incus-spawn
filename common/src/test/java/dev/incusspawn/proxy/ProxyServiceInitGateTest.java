package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx-proxy} exits {@code EXIT_CONFIG} until init has completed, so installing its service
 * before then only produces "proxy is not responding" (issue #968). Installing refuses first and
 * names {@code isx init}, before it writes a service file or touches systemd or launchd.
 */
class ProxyServiceInitGateTest {

    @TempDir
    Path home;
    private String savedHome;
    private PrintStream savedErr;
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void isolate() {
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        savedErr = System.err;
        System.setErr(new PrintStream(err, true));
    }

    @AfterEach
    void restore() {
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
        Files.createDirectories(Environment.configDir());
        Files.writeString(Environment.initCompleteMarker(), String.valueOf(Environment.INIT_VERSION - 1));

        assertFalse(ProxyService.install());
        assertAsksForInit();
    }

    private void assertAsksForInit() {
        assertTrue(err.toString().contains("Run 'isx init' first"), err::toString);
        assertFalse(Files.exists(Environment.proxyServiceFile()), "no service file before init");
        assertFalse(Files.exists(Environment.configDir().resolve("proxy.lock")), "no proxy lock taken");
    }
}
