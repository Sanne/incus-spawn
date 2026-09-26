package dev.incusspawn.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A container picks the SNI names the proxy is asked for, so neither the proxy's heap nor
 * the host's cert directory may grow with them without bound.
 */
class InterceptedCertOptionsLimitsTest {

    private static final Set<String> DOMAINS = Set.of("githubusercontent.com");

    @TempDir
    Path tempHome;

    private String savedHome;
    private CertificateAuthority ca;

    @BeforeEach
    void redirectHome() {
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        ca = CertificateAuthority.loadOrCreate();
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", savedHome);
    }

    @Test
    void distinctNamesAreCappedEvenWhenOneWildcardServesThemAll() throws Exception {
        // Vert.x caches an SSL context per answered name, so the count that matters is names,
        // not certs: every rN here is served by the one pre-minted *.githubusercontent.com.
        var mapper = new InterceptedCertOptions(ca, DOMAINS, 512, 3).keyManagerFactoryMapper(null);
        assertNotNull(mapper.apply("r1.githubusercontent.com"));
        assertNotNull(mapper.apply("r2.githubusercontent.com"));
        assertNotNull(mapper.apply("r3.githubusercontent.com"));
        assertNull(mapper.apply("r4.githubusercontent.com"), "past the cap a new name is refused");
        assertNotNull(mapper.apply("R1.githubusercontent.com"), "an already-served name still is");
    }

    @Test
    void onDemandCertsOnDiskAreCappedAcrossRestarts() throws Exception {
        var first = new InterceptedCertOptions(ca, DOMAINS, 2, 4096).keyManagerFactoryMapper(null);
        assertNotNull(first.apply("x.a1.githubusercontent.com"));
        assertNotNull(first.apply("x.a2.githubusercontent.com"));
        assertNull(first.apply("x.a3.githubusercontent.com"));
        // Pre-minted names never count against the cap.
        assertNotNull(first.apply("raw.githubusercontent.com"));

        // A reload or restart builds a new instance; the count comes from disk, not zero.
        var second = new InterceptedCertOptions(ca, DOMAINS, 2, 4096).keyManagerFactoryMapper(null);
        assertNull(second.apply("x.a4.githubusercontent.com"),
                "a fresh instance must not grant another batch of certs");
        assertNotNull(second.apply("y.a1.githubusercontent.com"),
                "a cert already on disk is served without counting as new");
        assertFalse(new CertStore(ca).storedNames().contains("*.a4.githubusercontent.com"));
    }
}
