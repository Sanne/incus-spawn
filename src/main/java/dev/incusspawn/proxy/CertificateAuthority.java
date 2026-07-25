package dev.incusspawn.proxy;

import dev.incusspawn.ClientLog;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;

import static dev.incusspawn.DerEncoder.*;

/**
 * Manages a self-signed CA for the MITM TLS proxy.
 * <p>
 * The CA key and certificate are stored at {@code ~/.config/incus-spawn/ca.key}
 * and {@code ~/.config/incus-spawn/ca.crt} with owner-only permissions.
 * <p>
 * Per-domain certificates are generated on-the-fly using pure Java DER encoding,
 * signed by this CA, and held in memory only.
 */
public class CertificateAuthority {

    /**
     * How far back {@code notBefore} is set on generated certs, to tolerate clock
     * skew between the host that mints a cert and a (possibly lagging) container
     * that validates it. Leaf certs are persisted and reused across proxy restarts
     * (see {@link CertStore}), so this margin only matters at the rare moments a
     * cert is freshly minted: first install, CA rotation, and near-expiry renewal.
     */
    private static final long BACKDATE_MS = 2L * 24 * 60 * 60 * 1000;

    private static Path caKeyFile() { return SpawnConfig.configDir().resolve("ca.key"); }
    private static Path caCertFile() { return SpawnConfig.configDir().resolve("ca.crt"); }

    /**
     * The CA certificate that {@link #reissueWithSki} replaced, kept so that images
     * built against it can be recognised as carrying a superseded — not a rotated — CA.
     */
    private static Path caSupersededCertFile() {
        return SpawnConfig.configDir().resolve("ca-superseded.crt");
    }

    /** Set once the SKI upgrade has failed, so it is attempted at most once per process. */
    private static volatile boolean reissueFailed;

    private final PrivateKey caKey;
    private final X509Certificate caCert;

    private CertificateAuthority(PrivateKey caKey, X509Certificate caCert) {
        this.caKey = caKey;
        this.caCert = caCert;
    }

    /**
     * Load existing CA from disk, or generate a new one if none exists.
     */
    public static CertificateAuthority loadOrCreate() {
        try {
            if (Files.exists(caKeyFile()) && Files.exists(caCertFile())) {
                return load();
            }
            return generate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load or create CA: " + e.getMessage(), e);
        }
    }

    /**
     * Check whether a CA certificate already exists on disk.
     */
    public static boolean exists() {
        return Files.exists(caKeyFile()) && Files.exists(caCertFile());
    }

    /**
     * Generate a TLS certificate for the given domain, signed by this CA.
     * The certificate includes the domain as both CN and a SAN DNS entry.
     * Uses pure Java DER encoding — no openssl processes, no JDK internal APIs.
     */
    public CertEntry generateDomainCert(String domain) {
        try {
            var keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            var keyPair = keyGen.generateKeyPair();

            var notBefore = new Date(System.currentTimeMillis() - BACKDATE_MS);
            var expiry = new Date(notBefore.getTime() + 366L * 24 * 60 * 60 * 1000);
            var serial = new BigInteger(128, new SecureRandom());

            var algId = sha256WithRsaAid();
            var tbsCert = derSequence(concat(
                    derExplicit(0, derInteger(BigInteger.valueOf(2))),   // v3
                    derInteger(serial),
                    algId,
                    caCert.getSubjectX500Principal().getEncoded(),      // issuer
                    derSequence(concat(derUtcTime(notBefore), derUtcTime(expiry))),
                    derDistinguishedName(domain),                       // subject
                    keyPair.getPublic().getEncoded(),                   // SubjectPublicKeyInfo
                    derExplicit(3, derSequence(concat(                  // extensions
                            derExtension(oidBasicConstraints(), true,
                                    derSequence(new byte[]{0x01, 0x01, 0x00})),
                            derExtension(oidSubjectAltName(), false,
                                    derSequence(derDnsName(domain))),
                            derExtension(oidSubjectKeyIdentifier(), false,
                                    derOctetString(computeKeyIdentifier(keyPair.getPublic()))),
                            derExtension(oidAuthorityKeyIdentifier(), false,
                                    derAuthorityKeyIdentifier(computeKeyIdentifier(caCert.getPublicKey())))
                    )))
            ));

            var cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(signAndWrap(tbsCert, caKey)));
            return new CertEntry(keyPair.getPrivate(), cert);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate cert for " + domain + ": " + e.getMessage(), e);
        }
    }

    /**
     * Return the CA certificate in PEM format, suitable for installing
     * in a container's trust store.
     */
    public String caCertPem() {
        try {
            return Files.readString(caCertFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read CA cert: " + e.getMessage(), e);
        }
    }

    public String caFingerprint() {
        try {
            return fingerprintOf(caCert);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute CA fingerprint", e);
        }
    }

    public static String currentCaFingerprint() {
        if (!exists()) return "";
        return loadOrCreate().caFingerprint();
    }

    /** How an image's stored CA fingerprint relates to the CA on this host. */
    public enum CaStatus {
        /** Built against the current CA cert. */
        CURRENT,
        /** Built against a cert this host re-issued over the same key: stale, but repairable. */
        REPAIRABLE,
        /** Built against a CA this host has no record of — a different key, or another machine. */
        FOREIGN,
        /** No fingerprint stored, or no CA on this host, so nothing can be concluded. */
        UNKNOWN
    }

    /**
     * The fingerprints an image can be compared against, read once so callers that
     * classify many images do not re-read and re-hash the CA per image.
     */
    public record CaTrust(String current, String superseded) {

        public static CaTrust snapshot() {
            return new CaTrust(currentCaFingerprint(), supersededCaFingerprint());
        }

        public CaStatus classify(String storedFingerprint) {
            if (storedFingerprint == null || storedFingerprint.isEmpty() || current.isEmpty()) {
                return CaStatus.UNKNOWN;
            }
            if (storedFingerprint.equals(current)) return CaStatus.CURRENT;
            if (storedFingerprint.equals(superseded)) return CaStatus.REPAIRABLE;
            return CaStatus.FOREIGN;
        }
    }

    /**
     * Fingerprint of the CA certificate replaced by {@link #reissueWithSki}, or {@code ""}
     * if this CA was never re-issued.
     * <p>
     * Single-slot on purpose: it bridges images built before key identifiers existed, and a
     * second key-preserving re-issue would overwrite it. That is acceptable only because
     * there is exactly one such upgrade; a recurring need would call for stamping the CA's
     * key identifier onto images instead of comparing certificate fingerprints.
     */
    static String supersededCaFingerprint() {
        try {
            if (!Files.exists(caSupersededCertFile())) return "";
            return fingerprintOf(loadCertificate(caSupersededCertFile()));
        } catch (Exception e) {
            return "";
        }
    }

    private static String fingerprintOf(X509Certificate cert) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    }

    /**
     * Check whether a container's stored CA fingerprint matches the current CA.
     * If mismatched, push the current cert into the container and update metadata.
     * Returns true if a fix was applied, false if no action was needed.
     * Containers without a stored fingerprint (pre-versioning) are skipped.
     */
    public static boolean fixContainerCaIfNeeded(IncusClient incus, String container) {
        var imageCaFp = incus.configGet(container, Metadata.CA_FINGERPRINT);
        if (imageCaFp.isEmpty()) return false;
        var ca = loadOrCreate();
        if (imageCaFp.equals(ca.caFingerprint())) return false;

        incus.shellExec(container, "sh", "-c",
                "cat > /etc/pki/ca-trust/source/anchors/incus-spawn-mitm.crt << 'CERTEOF'\n" +
                ca.caCertPem() +
                "CERTEOF");
        incus.shellExec(container, "update-ca-trust");
        incus.configSet(container, Metadata.CA_FINGERPRINT, ca.caFingerprint());
        return true;
    }

    public PrivateKey caKey() {
        return caKey;
    }

    public X509Certificate caCert() {
        return caCert;
    }

    // --- Private helpers ---

    /**
     * Compute the key identifier per RFC 5280 §4.2.1.2 method 1: the SHA-1 of the
     * subjectPublicKey BIT STRING contents from SubjectPublicKeyInfo.
     * <p>
     * For RSA those contents are the PKCS#1 {@code RSAPublicKey} sequence, which is
     * cheaper to re-encode from the key than to locate by parsing the DER. This class
     * mints RSA keys throughout, so the cast holds — and fails loudly if that changes.
     */
    static byte[] computeKeyIdentifier(PublicKey key) throws Exception {
        var rsa = (RSAPublicKey) key;
        var subjectPublicKey = derSequence(concat(
                derInteger(rsa.getModulus()), derInteger(rsa.getPublicExponent())));
        return MessageDigest.getInstance("SHA-1").digest(subjectPublicKey);
    }

    private static CertificateAuthority load() throws Exception {
        var key = loadPrivateKey(caKeyFile());
        var cert = loadCertificate(caCertFile());
        if (cert.getExtensionValue(SKI_OID) == null && !reissueFailed) {
            cert = reissueWithSki(key, cert);
        }
        return new CertificateAuthority(key, cert);
    }

    /**
     * Re-issue a pre-SKI CA certificate over its existing key pair, adding the
     * Subject Key Identifier extension.
     * <p>
     * Strict TLS validators (OpenSSL 3.5, and so Python 3.13+, which enables
     * {@code VERIFY_X509_STRICT} by default) reject a chain whose CA certificate has
     * no SKI — adding AKI/SKI to the leaves alone is not enough, because the trust
     * anchor itself is checked. CAs generated before that fix therefore have to be
     * re-issued, and only the certificate can change: the key pair is reused, so the
     * subject, the public key, and hence every leaf's Authority Key Identifier stay
     * valid. Already-issued leaf certs keep verifying and are not re-minted.
     * <p>
     * The re-issued certificate is a pure function of the old one and the key — same
     * subject, serial, and validity window, and RSA PKCS#1 v1.5 signatures are
     * deterministic — so two processes migrating concurrently write identical bytes.
     * Reusing the serial leaves the old and new certificates sharing an issuer/serial
     * pair, which is harmless here: the old one is superseded, and determinism is
     * worth more than uniqueness for a private self-signed root.
     * <p>
     * Best-effort: if the new certificate cannot be persisted, the old one is kept, so
     * the in-memory CA never disagrees with what containers are handed by
     * {@link #caCertPem()}. A failure is not retried — {@code loadOrCreate()} runs several
     * times per command, and re-signing and re-warning on each would only add noise.
     */
    private static X509Certificate reissueWithSki(PrivateKey key, X509Certificate old) {
        try {
            var der = selfSignedCaCertDer(old.getPublicKey(), key,
                    old.getSubjectX500Principal().getEncoded(),
                    old.getNotBefore(), old.getNotAfter(), old.getSerialNumber());

            writeAtomically(caSupersededCertFile(), toPem("CERTIFICATE", old.getEncoded()));
            writeAtomically(caCertFile(), toPem("CERTIFICATE", der));

            // File-only: this runs under the TUI too, where stderr lands mid-render.
            ClientLog.warn("Upgraded the MITM CA certificate to include a Subject Key Identifier "
                    + "(required by strict TLS validators). The CA key is unchanged.");
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            reissueFailed = true;
            ClientLog.warn("Could not upgrade the MITM CA certificate: " + e.getMessage());
            return old;
        }
    }

    /** Write {@code content} to {@code path} via a temp file, so readers never see a partial cert. */
    private static void writeAtomically(Path path, String content) throws IOException {
        var tmp = Files.createTempFile(path.getParent(), ".isx-ca-", ".tmp");
        try {
            Files.writeString(tmp, content);
            CertStore.trySetPerms(tmp, "rw-------");
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /**
     * Build the DER of a self-signed CA certificate: v3, basicConstraints CA:TRUE,
     * keyCertSign + cRLSign, and a Subject Key Identifier. Shared by first-time
     * generation and by {@link #reissueWithSki}, so both produce the same shape.
     */
    private static byte[] selfSignedCaCertDer(PublicKey pub, PrivateKey signingKey, byte[] subject,
                                              Date notBefore, Date notAfter, BigInteger serial)
            throws Exception {
        // keyCertSign (bit 5) | cRLSign (bit 6) → byte value 0x06, with 1 unused trailing bit
        var keyUsageBits = new byte[]{0x03, 0x02, 0x01, 0x06};

        var algId = sha256WithRsaAid();
        var tbsCert = derSequence(concat(
                derExplicit(0, derInteger(BigInteger.valueOf(2))),   // v3
                derInteger(serial),
                algId,
                subject,                                            // issuer = subject (self-signed)
                derSequence(concat(derUtcTime(notBefore), derUtcTime(notAfter))),
                subject,
                pub.getEncoded(),                                   // SubjectPublicKeyInfo
                derExplicit(3, derSequence(concat(
                        derExtension(oidBasicConstraints(), true,
                                derSequence(new byte[]{0x01, 0x01, (byte) 0xff})),
                        derExtension(oidKeyUsage(), true, keyUsageBits),
                        // No AKI: RFC 5280 §4.2.1.1 exempts self-signed certs.
                        derExtension(oidSubjectKeyIdentifier(), false,
                                derOctetString(computeKeyIdentifier(pub)))
                )))
        ));

        return signAndWrap(tbsCert, signingKey);
    }

    /** Sign a TBSCertificate and wrap it into the outer Certificate SEQUENCE. */
    private static byte[] signAndWrap(byte[] tbsCert, PrivateKey signingKey) throws Exception {
        var sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(signingKey);
        sig.update(tbsCert);
        return derSequence(concat(tbsCert, sha256WithRsaAid(), derBitString(sig.sign())));
    }

    private static CertificateAuthority generate() throws Exception {
        System.out.println("Generating MITM CA certificate...");
        Files.createDirectories(caKeyFile().getParent());
        // A new key pair is a real rotation, not an SKI upgrade: drop any record of a
        // superseded cert so images built against it are not mistaken for repairable.
        Files.deleteIfExists(caSupersededCertFile());

        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var keyPair = keyGen.generateKeyPair();

        var notBefore = new Date(System.currentTimeMillis() - BACKDATE_MS);
        var expiry = new Date(notBefore.getTime() + 3650L * 24 * 60 * 60 * 1000);
        var serial = new BigInteger(128, new SecureRandom());
        var subject = derDistinguishedName("incus-spawn MITM CA");

        var certDer = selfSignedCaCertDer(keyPair.getPublic(), keyPair.getPrivate(),
                subject, notBefore, expiry, serial);

        Files.writeString(caKeyFile(), toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        Files.setPosixFilePermissions(caKeyFile(),
                PosixFilePermissions.fromString("rw-------"));

        Files.writeString(caCertFile(), toPem("CERTIFICATE", certDer));
        Files.setPosixFilePermissions(caCertFile(),
                PosixFilePermissions.fromString("rw-------"));

        System.out.println("  CA certificate saved to " + caCertFile());
        System.out.println("  CA private key saved to " + caKeyFile());

        var cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certDer));
        return new CertificateAuthority(keyPair.getPrivate(), cert);
    }

    private static PrivateKey loadPrivateKey(Path path) throws Exception {
        var pem = Files.readString(path);
        var base64 = pem
                .replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s", "");
        var der = Base64.getDecoder().decode(base64);

        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            // PKCS#1 (legacy openssl genrsa output) — wrap in PKCS#8 envelope
            der = wrapPkcs1InPkcs8(der);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    // Backward compat: existing CAs generated by older versions used openssl genrsa (PKCS#1)
    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        byte[] rsaOid = {0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01};
        byte[] algId = derSequence(concat(rsaOid, new byte[]{0x05, 0x00}));
        return derSequence(concat(derInteger(BigInteger.ZERO), algId, derOctetString(pkcs1)));
    }

    private static X509Certificate loadCertificate(Path path) throws Exception {
        var certPem = Files.readAllBytes(path);
        var certFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certFactory.generateCertificate(
                new ByteArrayInputStream(certPem));
    }

    /**
     * A generated domain certificate and its private key.
     */
    public record CertEntry(PrivateKey key, X509Certificate cert) {}
}
