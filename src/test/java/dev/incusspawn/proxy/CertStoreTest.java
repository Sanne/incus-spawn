package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;

import static dev.incusspawn.DerEncoder.*;
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

    @Test
    void leafCertHasAkiAndSki() throws Exception {
        var ca = CertificateAuthority.loadOrCreate();
        var entry = new CertStore(ca).get("aki-ski.example.com");
        var cert = entry.cert();

        assertNotNull(cert.getExtensionValue("2.5.29.14"),
                "leaf cert must have Subject Key Identifier");
        assertNotNull(cert.getExtensionValue("2.5.29.35"),
                "leaf cert must have Authority Key Identifier");

        // The AKI key identifier must match the SHA-1 of the CA's public key
        var caKeyId = CertificateAuthority.computeKeyIdentifier(ca.caCert().getPublicKey());
        var akiRaw = cert.getExtensionValue("2.5.29.35");
        assertNotNull(akiRaw);
        // akiRaw is an OCTET STRING wrapping the extension value;
        // verify it contains the CA key identifier bytes
        var akiHex = java.util.HexFormat.of().formatHex(akiRaw);
        var caKeyIdHex = java.util.HexFormat.of().formatHex(caKeyId);
        assertTrue(akiHex.contains(caKeyIdHex),
                "AKI must contain the CA's key identifier");
    }

    @Test
    void caCertHasSki() throws Exception {
        var ca = CertificateAuthority.loadOrCreate();
        assertNotNull(ca.caCert().getExtensionValue("2.5.29.14"),
                "CA cert must have Subject Key Identifier");
    }

    @Test
    void remintsLegacyCertWithoutAki() throws Exception {
        var ca = CertificateAuthority.loadOrCreate();

        // Build a leaf cert the way the old code did: basicConstraints + SAN only,
        // no AKI/SKI. This simulates what's on disk after an upgrade.
        var domain = "legacy.example.com";
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var keyPair = keyGen.generateKeyPair();
        var notBefore = new Date(System.currentTimeMillis() - 86400_000L);
        var expiry = new Date(notBefore.getTime() + 366L * 24 * 60 * 60 * 1000);
        var serial = new BigInteger(128, new SecureRandom());
        var algId = sha256WithRsaAid();
        var tbsCert = derSequence(concat(
                derExplicit(0, derInteger(BigInteger.valueOf(2))),
                derInteger(serial),
                algId,
                ca.caCert().getSubjectX500Principal().getEncoded(),
                derSequence(concat(derUtcTime(notBefore), derUtcTime(expiry))),
                derDistinguishedName(domain),
                keyPair.getPublic().getEncoded(),
                derExplicit(3, derSequence(concat(
                        derExtension(oidBasicConstraints(), true,
                                derSequence(new byte[]{0x01, 0x01, 0x00})),
                        derExtension(oidSubjectAltName(), false,
                                derSequence(derDnsName(domain)))
                )))
        ));
        var sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(ca.caKey());
        sig.update(tbsCert);
        var certDer = derSequence(concat(tbsCert, algId, derBitString(sig.sign())));
        var legacyCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certDer));

        // Sanity: the handcrafted cert has no AKI or SKI
        assertNull(legacyCert.getExtensionValue("2.5.29.35"));
        assertNull(legacyCert.getExtensionValue("2.5.29.14"));

        // Persist it to the cert store directory, mimicking the old proxy
        var certsDir = SpawnConfig.configDir().resolve("certs");
        Files.createDirectories(certsDir);
        Files.writeString(certsDir.resolve(domain + ".crt"),
                toPem("CERTIFICATE", legacyCert.getEncoded()));
        Files.writeString(certsDir.resolve(domain + ".key"),
                toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));

        // A fresh CertStore must reject the legacy cert and re-mint
        var reminted = new CertStore(ca).get(domain);
        assertNotEquals(serial, reminted.cert().getSerialNumber(),
                "legacy cert without AKI/SKI must be re-minted, not reused");
        assertNotNull(reminted.cert().getExtensionValue("2.5.29.35"),
                "re-minted cert must have AKI");
        assertNotNull(reminted.cert().getExtensionValue("2.5.29.14"),
                "re-minted cert must have SKI");
    }
}
