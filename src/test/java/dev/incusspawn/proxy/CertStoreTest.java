package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CertStoreTest {

    @TempDir
    Path tempHome;

    private String savedHome;

    @BeforeEach
    void redirectHome() {
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreHome() {
        if (savedHome != null) System.setProperty("user.home", savedHome);
    }

    @Test
    void fileNameEncodesWildcards() {
        assertEquals("github.com", CertStore.fileName("github.com"));
        assertEquals("_wildcard.github.com", CertStore.fileName("*.github.com"));
    }

    @Test
    void reuseKeepsSameCertAndNotBefore() {
        var ca = CertificateAuthority.loadOrCreate();

        var first = new CertStore(ca).get("example.com");
        // A fresh store instance (cold in-memory cache) must reload from disk,
        // not re-mint — this is what keeps notBefore stable across proxy restarts.
        var second = new CertStore(ca).get("example.com");

        assertEquals(first.cert().getSerialNumber(), second.cert().getSerialNumber(),
                "expected the persisted cert to be reused, not re-minted");
        assertEquals(first.cert().getNotBefore(), second.cert().getNotBefore());

        assertTrue(Files.exists(SpawnConfig.configDir().resolve("certs").resolve("example.com.crt")));
        assertTrue(Files.exists(SpawnConfig.configDir().resolve("certs").resolve("example.com.key")));
    }

    @Test
    void remintsWhenCaRotates() throws Exception {
        var ca1 = CertificateAuthority.loadOrCreate();
        var original = new CertStore(ca1).get("example.com");

        // Rotate the CA: drop the on-disk CA so loadOrCreate() generates a new one.
        Files.delete(SpawnConfig.configDir().resolve("ca.crt"));
        Files.delete(SpawnConfig.configDir().resolve("ca.key"));
        var ca2 = CertificateAuthority.loadOrCreate();
        assertNotEquals(ca1.caFingerprint(), ca2.caFingerprint());

        var reissued = new CertStore(ca2).get("example.com");
        assertNotEquals(original.cert().getSerialNumber(), reissued.cert().getSerialNumber(),
                "a leaf signed by the old CA must be re-minted under the new CA");
        // The reissued leaf must verify against the new CA.
        assertDoesNotThrow(() -> reissued.cert().verify(ca2.caCert().getPublicKey()));
    }

    @Test
    void notBeforeIsBackdatedForFreshMint() {
        var ca = CertificateAuthority.loadOrCreate();
        var entry = new CertStore(ca).get("fresh.example.com");
        // Freshly minted certs are backdated to tolerate clock skew, so notBefore
        // is comfortably in the past relative to now.
        assertTrue(entry.cert().getNotBefore().toInstant().isBefore(java.time.Instant.now().minusSeconds(3600)),
                "notBefore should be backdated well before now");
    }
}
