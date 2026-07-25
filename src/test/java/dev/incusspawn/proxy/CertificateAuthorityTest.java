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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HexFormat;

import static dev.incusspawn.DerEncoder.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the upgrade of CA certificates generated before Subject Key Identifiers
 * were added — the case every existing install lands in, and the one CI's
 * always-fresh {@code isx init} never exercises.
 */
class CertificateAuthorityTest {

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
    void freshCaHasSki() {
        var ca = CertificateAuthority.loadOrCreate();
        assertNotNull(ca.caCert().getExtensionValue(CertificateAuthority.OID_SKI),
                "a newly generated CA cert must carry a Subject Key Identifier");
        assertEquals("", CertificateAuthority.supersededCaFingerprint(),
                "a CA that was never re-issued has no superseded fingerprint");
    }

    @Test
    void legacyCaIsReissuedOverTheSameKey() throws Exception {
        var legacy = writeLegacyCa();
        var legacyCert = legacy.cert();

        var ca = CertificateAuthority.loadOrCreate();
        var upgraded = ca.caCert();

        assertNotNull(upgraded.getExtensionValue(CertificateAuthority.OID_SKI),
                "the re-issued CA cert must carry a Subject Key Identifier");
        assertArrayEquals(legacyCert.getPublicKey().getEncoded(), upgraded.getPublicKey().getEncoded(),
                "the CA key pair must be reused, so existing leaf certs stay valid");
        assertEquals(legacyCert.getSubjectX500Principal(), upgraded.getSubjectX500Principal());
        assertEquals(legacyCert.getNotBefore(), upgraded.getNotBefore(),
                "re-issuing must not reset the validity window");
        assertEquals(legacyCert.getNotAfter(), upgraded.getNotAfter());
        // Self-signed roots are exempt from AKI (RFC 5280 §4.2.1.1) and OpenSSL's
        // strict mode only demands SKI of them.
        assertNull(upgraded.getExtensionValue(CertificateAuthority.OID_AKI));
        upgraded.verify(upgraded.getPublicKey());
    }

    @Test
    void reissuedCaIsPersistedAndSupersedesTheOldOne() throws Exception {
        var legacyCert = writeLegacyCa().cert();
        var legacyFp = fingerprint(legacyCert);

        var ca = CertificateAuthority.loadOrCreate();

        assertEquals(legacyFp, CertificateAuthority.supersededCaFingerprint(),
                "the replaced cert must be recorded so images built against it are recognised");
        assertNotEquals(legacyFp, ca.caFingerprint());
        // caCertPem() reads from disk while caFingerprint() reads memory: containers must
        // not be handed a cert that disagrees with the fingerprint stamped on them.
        var onDisk = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(ca.caCertPem().getBytes()));
        assertEquals(ca.caFingerprint(), fingerprint(onDisk));
    }

    @Test
    void reissueIsDeterministicAndIdempotent() throws Exception {
        writeLegacyCa();

        var first = CertificateAuthority.loadOrCreate().caFingerprint();
        var second = CertificateAuthority.loadOrCreate().caFingerprint();

        assertEquals(first, second,
                "re-issuing is a pure function of the old cert and key, so concurrent "
                        + "migrations converge and a second load is a no-op");
    }

    @Test
    void certsSignedBeforeTheReissueStillVerifyAfterIt() throws Exception {
        var legacy = writeLegacyCa();
        // A leaf issued by the CA as it stood before the upgrade.
        var preUpgradeLeaf = signLeaf(legacy, "example.com");

        var upgraded = CertificateAuthority.loadOrCreate().caCert();

        assertDoesNotThrow(() -> preUpgradeLeaf.verify(upgraded.getPublicKey()),
                "the key pair is reused, so leaf certs survive the upgrade and are not re-minted");
    }

    @Test
    void rotatingTheCaClearsTheSupersededRecord() throws Exception {
        writeLegacyCa();
        var upgraded = CertificateAuthority.loadOrCreate();
        assertNotEquals("", CertificateAuthority.supersededCaFingerprint());

        // A genuine rotation: drop the CA so a new key pair is generated.
        Files.delete(SpawnConfig.configDir().resolve("ca.crt"));
        Files.delete(SpawnConfig.configDir().resolve("ca.key"));
        var rotated = CertificateAuthority.loadOrCreate();

        assertNotEquals(upgraded.caFingerprint(), rotated.caFingerprint());
        assertEquals("", CertificateAuthority.supersededCaFingerprint(),
                "a rotation must not leave a record that makes stale images look repairable");
    }

    @Test
    void corruptCaCertSurfacesAsAnError() throws Exception {
        writeLegacyCa();
        var certFile = SpawnConfig.configDir().resolve("ca.crt");
        Files.writeString(certFile, "-----BEGIN CERTIFICATE-----\nnot a cert\n-----END CERTIFICATE-----\n");

        assertThrows(RuntimeException.class, CertificateAuthority::loadOrCreate,
                "a corrupt CA must surface as an error, not be silently regenerated");
        assertEquals("", CertificateAuthority.supersededCaFingerprint(),
                "nothing was superseded, so no backup must be left behind");
    }

    // --- Helpers ---

    /** Sign a minimal leaf with the legacy CA's key, as the CA would have before the upgrade. */
    private X509Certificate signLeaf(LegacyCa ca, String domain) throws Exception {
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var leafKey = keyGen.generateKeyPair();
        var notBefore = new Date(System.currentTimeMillis() - 86400_000L);
        var algId = sha256WithRsaAid();
        var tbs = derSequence(concat(
                derExplicit(0, derInteger(BigInteger.valueOf(2))),
                derInteger(new BigInteger(128, new SecureRandom())),
                algId,
                ca.cert().getSubjectX500Principal().getEncoded(),
                derSequence(concat(derUtcTime(notBefore),
                        derUtcTime(new Date(notBefore.getTime() + 366L * 24 * 60 * 60 * 1000)))),
                derDistinguishedName(domain),
                leafKey.getPublic().getEncoded(),
                derExplicit(3, derSequence(concat(
                        derExtension(oidBasicConstraints(), true,
                                derSequence(new byte[]{0x01, 0x01, 0x00})),
                        derExtension(oidSubjectAltName(), false, derSequence(derDnsName(domain)))
                )))
        ));
        var sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(ca.keyPair().getPrivate());
        sig.update(tbs);
        var der = derSequence(concat(tbs, algId, derBitString(sig.sign())));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    /** Write a CA key + cert in the pre-SKI shape: basicConstraints + keyUsage only. */
    private LegacyCa writeLegacyCa() throws Exception {
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var keyPair = keyGen.generateKeyPair();
        var notBefore = new Date(System.currentTimeMillis() - 86400_000L);
        var expiry = new Date(notBefore.getTime() + 3650L * 24 * 60 * 60 * 1000);
        var serial = new BigInteger(128, new SecureRandom());
        var subject = derDistinguishedName("incus-spawn MITM CA");
        var keyUsageBits = new byte[]{0x03, 0x02, 0x01, 0x06};
        var algId = sha256WithRsaAid();

        var tbsCert = derSequence(concat(
                derExplicit(0, derInteger(BigInteger.valueOf(2))),
                derInteger(serial),
                algId,
                subject,
                derSequence(concat(derUtcTime(notBefore), derUtcTime(expiry))),
                subject,
                keyPair.getPublic().getEncoded(),
                derExplicit(3, derSequence(concat(
                        derExtension(oidBasicConstraints(), true,
                                derSequence(new byte[]{0x01, 0x01, (byte) 0xff})),
                        derExtension(oidKeyUsage(), true, keyUsageBits)
                )))
        ));
        var sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(tbsCert);
        var certDer = derSequence(concat(tbsCert, algId, derBitString(sig.sign())));

        var dir = SpawnConfig.configDir();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ca.key"), toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        Files.writeString(dir.resolve("ca.crt"), toPem("CERTIFICATE", certDer));

        var cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certDer));
        assertNull(cert.getExtensionValue(CertificateAuthority.OID_SKI),
                "sanity: the handcrafted legacy CA must have no SKI");
        return new LegacyCa(keyPair, cert);
    }

    private static String fingerprint(X509Certificate cert) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    }

    private record LegacyCa(KeyPair keyPair, X509Certificate cert) {}
}
