package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
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

    private static Path caKeyFile() { return SpawnConfig.configDir().resolve("ca.key"); }
    private static Path caCertFile() { return SpawnConfig.configDir().resolve("ca.crt"); }

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

            var yesterday = new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000);
            var expiry = new Date(yesterday.getTime() + 366L * 24 * 60 * 60 * 1000);
            var serial = new BigInteger(128, new SecureRandom());

            var algId = sha256WithRsaAid();
            var tbsCert = derSequence(concat(
                    derExplicit(0, derInteger(BigInteger.valueOf(2))),   // v3
                    derInteger(serial),
                    algId,
                    caCert.getSubjectX500Principal().getEncoded(),      // issuer
                    derSequence(concat(derUtcTime(yesterday), derUtcTime(expiry))),
                    derDistinguishedName(domain),                       // subject
                    keyPair.getPublic().getEncoded(),                   // SubjectPublicKeyInfo
                    derExplicit(3, derSequence(concat(                  // extensions
                            derExtension(oidBasicConstraints(), true,
                                    derSequence(new byte[]{0x01, 0x01, 0x00})),
                            derExtension(oidSubjectAltName(), false,
                                    derSequence(derDnsName(domain)))
                    )))
            ));

            var sig = java.security.Signature.getInstance("SHA256withRSA");
            sig.initSign(caKey);
            sig.update(tbsCert);

            var certDer = derSequence(concat(
                    tbsCert,
                    algId,
                    derBitString(sig.sign())
            ));

            var cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certDer));
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
            var md = java.security.MessageDigest.getInstance("SHA-256");
            var digest = md.digest(caCert.getEncoded());
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute CA fingerprint", e);
        }
    }

    public static String currentCaFingerprint() {
        if (!exists()) return "";
        return loadOrCreate().caFingerprint();
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

    private static CertificateAuthority load() throws Exception {
        var key = loadPrivateKey(caKeyFile());
        var cert = loadCertificate(caCertFile());
        return new CertificateAuthority(key, cert);
    }

    private static CertificateAuthority generate() throws Exception {
        System.out.println("Generating MITM CA certificate...");
        Files.createDirectories(caKeyFile().getParent());

        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var keyPair = keyGen.generateKeyPair();

        var yesterday = new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000);
        var expiry = new Date(yesterday.getTime() + 3650L * 24 * 60 * 60 * 1000);
        var serial = new BigInteger(128, new SecureRandom());
        var subject = derDistinguishedName("incus-spawn MITM CA");

        // keyCertSign (bit 5) | cRLSign (bit 6) → byte value 0x06, with 1 unused trailing bit
        var keyUsageBits = new byte[]{0x03, 0x02, 0x01, 0x06};

        var algId = sha256WithRsaAid();
        var tbsCert = derSequence(concat(
                derExplicit(0, derInteger(BigInteger.valueOf(2))),   // v3
                derInteger(serial),
                algId,
                subject,                                            // issuer = subject (self-signed)
                derSequence(concat(derUtcTime(yesterday), derUtcTime(expiry))),
                subject,
                keyPair.getPublic().getEncoded(),                   // SubjectPublicKeyInfo
                derExplicit(3, derSequence(concat(
                        derExtension(oidBasicConstraints(), true,
                                derSequence(new byte[]{0x01, 0x01, (byte) 0xff})),
                        derExtension(oidKeyUsage(), true, keyUsageBits)
                )))
        ));

        var sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(tbsCert);

        var certDer = derSequence(concat(
                tbsCert,
                algId,
                derBitString(sig.sign())
        ));

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
