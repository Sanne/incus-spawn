package dev.incusspawn.proxy;

import dev.incusspawn.DerEncoder;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HexFormat;
import java.util.TreeMap;

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
        assertNotNull(ca.caCert().getExtensionValue(DerEncoder.SKI_OID),
                "a newly generated CA cert must carry a Subject Key Identifier");
        assertEquals("", CertificateAuthority.supersededCaFingerprint(),
                "a CA that was never re-issued has no superseded fingerprint");
    }

    @Test
    void legacyCaIsReissuedOverTheSameKey() throws Exception {
        var legacyCert = writeLegacyCa();

        var ca = CertificateAuthority.loadOrCreate();
        var upgraded = ca.caCert();

        assertNotNull(upgraded.getExtensionValue(DerEncoder.SKI_OID),
                "the re-issued CA cert must carry a Subject Key Identifier");
        assertArrayEquals(legacyCert.getPublicKey().getEncoded(), upgraded.getPublicKey().getEncoded(),
                "the CA key pair must be reused, so existing leaf certs stay valid");
        assertEquals(legacyCert.getSubjectX500Principal(), upgraded.getSubjectX500Principal());
        assertEquals(legacyCert.getNotBefore(), upgraded.getNotBefore(),
                "re-issuing must not reset the validity window");
        assertEquals(legacyCert.getNotAfter(), upgraded.getNotAfter());
        // Self-signed roots are exempt from AKI (RFC 5280 §4.2.1.1) and OpenSSL's
        // strict mode only demands SKI of them.
        assertNull(upgraded.getExtensionValue(DerEncoder.AKI_OID));
        upgraded.verify(upgraded.getPublicKey());
    }

    @Test
    void reissuedCaIsPersistedAndSupersedesTheOldOne() throws Exception {
        var legacyCert = writeLegacyCa();
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
    void trustSnapshotClassifiesStoredFingerprints() throws Exception {
        var legacyFp = fingerprint(writeLegacyCa());
        var mitmFp = CertificateAuthority.loadOrCreate().caFingerprint();
        var combinedFp = CertificateAuthority.trustFingerprint(CertificateAuthority.collectTrustAnchors());

        var trust = CertificateAuthority.CaTrust.snapshot();
        assertEquals(CertificateAuthority.CaStatus.CURRENT, trust.classify(combinedFp),
                "a template stamped with the combined trust-set fingerprint is current");
        assertEquals(CertificateAuthority.CaStatus.REPAIRABLE, trust.classify(mitmFp),
                "a template stamped with the old MITM-only fingerprint is repairable");
        assertEquals(CertificateAuthority.CaStatus.REPAIRABLE, trust.classify(legacyFp),
                "an image built against the cert we re-issued is repairable, not foreign");
        assertEquals(CertificateAuthority.CaStatus.FOREIGN, trust.classify("deadbeef"));
        assertEquals(CertificateAuthority.CaStatus.UNKNOWN, trust.classify(""));
        assertEquals(CertificateAuthority.CaStatus.UNKNOWN, trust.classify(null));
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

    // --- Trust anchors ---

    @Test
    void collectTrustAnchorsIncludesMitmCa() {
        var ca = CertificateAuthority.loadOrCreate();
        var anchors = CertificateAuthority.collectTrustAnchors();
        var expectedKey = "isx-" + ca.caFingerprint() + ".crt";
        assertTrue(anchors.containsKey(expectedKey),
                "trust anchors must include the MITM CA keyed by its DER fingerprint");
        assertEquals(ca.caCertPem().strip(), anchors.get(expectedKey));
    }

    @Test
    void collectTrustAnchorsKeysAreSha256Filenames() {
        CertificateAuthority.loadOrCreate();
        var anchors = CertificateAuthority.collectTrustAnchors();
        for (var key : anchors.keySet()) {
            assertTrue(key.matches("isx-[0-9a-f]{64}\\.crt"),
                    "anchor key must be isx-<sha256>.crt, got: " + key);
        }
    }

    @Test
    void trustFingerprintEmptyForNoAnchors() {
        assertEquals("", CertificateAuthority.trustFingerprint(new TreeMap<>()));
    }

    @Test
    void trustFingerprintChangesWithContent() {
        var a = new TreeMap<String, String>();
        a.put("isx-aaa.crt", "ONE");
        var fp1 = CertificateAuthority.trustFingerprint(a);
        assertFalse(fp1.isEmpty());

        a.put("isx-bbb.crt", "TWO");
        var fp2 = CertificateAuthority.trustFingerprint(a);
        assertNotEquals(fp1, fp2);
    }

    @Test
    void trustFingerprintIsDeterministic() {
        CertificateAuthority.loadOrCreate();
        var fp1 = CertificateAuthority.trustFingerprint(CertificateAuthority.collectTrustAnchors());
        var fp2 = CertificateAuthority.trustFingerprint(CertificateAuthority.collectTrustAnchors());
        assertEquals(fp1, fp2);
    }

    @Test
    void buildReconcileScriptCleansOldNamesAndWritesNewOnes() {
        var anchors = new TreeMap<String, String>();
        anchors.put("isx-abc.crt", "-----BEGIN CERTIFICATE-----\nFAKE\n-----END CERTIFICATE-----");
        var script = CertificateAuthority.buildReconcileScript(anchors);
        assertTrue(script.contains("rm -f"));
        assertTrue(script.contains("incus-spawn-mitm.crt"));
        assertTrue(script.contains("isx-*.crt"));
        assertTrue(script.contains("isx-abc.crt"));
        assertTrue(script.contains("update-ca-trust"));
    }

    // --- Helpers ---

    /** Write a CA key + cert in the pre-SKI shape: basicConstraints + keyUsage only. */
    private X509Certificate writeLegacyCa() throws Exception {
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
        assertNull(cert.getExtensionValue(DerEncoder.SKI_OID),
                "sanity: the handcrafted legacy CA must have no SKI");
        return cert;
    }

    private static String fingerprint(X509Certificate cert) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    }
}
