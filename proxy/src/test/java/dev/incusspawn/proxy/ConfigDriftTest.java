package dev.incusspawn.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@code /health} reports as {@code configDrifted}, against a real config directory. */
class ConfigDriftTest {

    @TempDir
    Path home;

    private String originalHome;
    private Path configDir;

    @BeforeEach
    void isolateHome() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        configDir = Files.createDirectories(home.resolve(".config/incus-spawn"));
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalHome);
    }

    private static MitmProxy proxy(ConfigFingerprint fingerprint) {
        var creds = new ProxyCredentials("", "", false, "", "", List.of());
        return new MitmProxy(null, "127.0.0.1", 0, 0, "127.0.0.1",
                creds, creds.toolProxies(), fingerprint);
    }

    @Test
    void aFreshProxyIsNotDriftedByAFutureMtime() throws Exception {
        // #818: a config.yaml dated an hour ahead read as drifted after every restart,
        // so every command restarted the proxy until the clock caught up.
        var config = Files.writeString(configDir.resolve("config.yaml"), "a: 1\n");
        Files.setLastModifiedTime(config, FileTime.from(Instant.now().plus(Duration.ofHours(1))));

        assertFalse(proxy(ConfigFingerprint.load().fingerprint()).hasConfigChangedSinceLoad());
    }

    @Test
    void anEditAfterLoadIsDrift() throws Exception {
        var config = Files.writeString(configDir.resolve("config.yaml"), "a: 1\n");
        var proxy = proxy(ConfigFingerprint.load().fingerprint());

        // Dated in the past, as a restored backup or a stepped-back clock would leave it.
        Files.writeString(config, "a: 2\n");
        Files.setLastModifiedTime(config, FileTime.from(Instant.now().minus(Duration.ofDays(1))));

        assertTrue(proxy.hasConfigChangedSinceLoad());
    }

    @Test
    void aMissingFingerprintIsRefused() {
        // Null compares unequal to every capture: the proxy would report drift forever.
        assertThrows(NullPointerException.class, () -> proxy(null));
    }
}
