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

/**
 * Manages a self-signed CA for the MITM TLS proxy.
 * <p>
 * The CA key and certificate are stored at {@code ~/.config/incus-spawn/ca.key}
 * and {@code ~/.config/incus-spawn/ca.crt} with owner-only permissions.
 * <p>
 * Per-domain certificates are generated on-the-fly via {@code openssl},
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
            var serial = new BigInteger(128, SecureRandom.getInstanceStrong());

            var tbsCert = derSequence(concat(
                    derExplicit(0, derInteger(BigInteger.valueOf(2))),   // v3
                    derInteger(serial),
                    SHA256_WITH_RSA_AID,
                    caCert.getSubjectX500Principal().getEncoded(),      // issuer
                    derSequence(concat(derUtcTime(yesterday), derUtcTime(expiry))),
                    derDistinguishedName(domain),                       // subject
                    keyPair.getPublic().getEncoded(),                   // SubjectPublicKeyInfo
                    derExplicit(3, derSequence(concat(                  // extensions
                            derExtension(OID_BASIC_CONSTRAINTS, true,
                                    derSequence(new byte[]{0x01, 0x01, 0x00})),
                            derExtension(OID_SUBJECT_ALT_NAME, false,
                                    derSequence(derDnsName(domain)))
                    )))
            ));

            var sig = java.security.Signature.getInstance("SHA256withRSA");
            sig.initSign(caKey);
            sig.update(tbsCert);

            var certDer = derSequence(concat(
                    tbsCert,
                    SHA256_WITH_RSA_AID,
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

        // Generate CA key
        run("openssl", "genrsa", "-out", caKeyFile().toString(), "2048");
        Files.setPosixFilePermissions(caKeyFile(),
                PosixFilePermissions.fromString("rw-------"));

        // Generate self-signed CA cert
        run("openssl", "req", "-x509", "-new", "-nodes",
                "-key", caKeyFile().toString(),
                "-sha256", "-days", "3650",
                "-out", caCertFile().toString(),
                "-subj", "/CN=incus-spawn MITM CA/O=incus-spawn",
                "-addext", "basicConstraints=critical,CA:TRUE",
                "-addext", "keyUsage=critical,keyCertSign,cRLSign");
        Files.setPosixFilePermissions(caCertFile(),
                PosixFilePermissions.fromString("rw-------"));

        System.out.println("  CA certificate saved to " + caCertFile());
        System.out.println("  CA private key saved to " + caKeyFile());

        var key = loadPrivateKey(caKeyFile());
        var cert = loadCertificate(caCertFile());
        return new CertificateAuthority(key, cert);
    }

    private static PrivateKey loadPrivateKey(Path path) throws Exception {
        var pem = Files.readString(path);
        var base64 = pem
                .replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s", "");
        var der = Base64.getDecoder().decode(base64);

        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            // PKCS#1 (openssl genrsa output) — wrap in PKCS#8 envelope
            der = wrapPkcs1InPkcs8(der);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    // --- DER encoding helpers (X.509 cert building + PKCS#1→PKCS#8 wrapping) ---

    // SHA256withRSA AlgorithmIdentifier: SEQUENCE { OID 1.2.840.113549.1.1.11, NULL }
    private static final byte[] SHA256_WITH_RSA_AID = derSequence(concat(
            new byte[]{0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                    (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b},
            new byte[]{0x05, 0x00}));

    // Extension OIDs
    private static final byte[] OID_BASIC_CONSTRAINTS =
            {0x06, 0x03, 0x55, 0x1d, 0x13};  // 2.5.29.19
    private static final byte[] OID_SUBJECT_ALT_NAME =
            {0x06, 0x03, 0x55, 0x1d, 0x11};  // 2.5.29.17

    /** Wraps a PKCS#1 RSA key in a PKCS#8 envelope so Java's KeyFactory can parse it. */
    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        byte[] rsaOid = {0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01};
        byte[] algId = derSequence(concat(rsaOid, new byte[]{0x05, 0x00}));
        return derSequence(concat(derInteger(BigInteger.ZERO), algId, derOctetString(pkcs1)));
    }

    private static byte[] derDistinguishedName(String cn) {
        // CN OID: 2.5.4.3
        byte[] cnOid = {0x06, 0x03, 0x55, 0x04, 0x03};
        byte[] cnValue = cn.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] cnUtf8 = concat(new byte[]{0x0c}, derLength(cnValue.length), cnValue);
        return derSequence(derSet(derSequence(concat(cnOid, cnUtf8))));
    }

    private static byte[] derExtension(byte[] oid, boolean critical, byte[] value) {
        var parts = critical
                ? concat(oid, new byte[]{0x01, 0x01, (byte) 0xff}, derOctetString(value))
                : concat(oid, derOctetString(value));
        return derSequence(parts);
    }

    private static byte[] derDnsName(String name) {
        // Context-tagged [2] implicit IA5String
        byte[] ascii = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return concat(new byte[]{(byte) 0x82}, derLength(ascii.length), ascii);
    }

    private static byte[] derUtcTime(Date date) {
        var sdf = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        byte[] ascii = sdf.format(date).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return concat(new byte[]{0x17}, derLength(ascii.length), ascii);
    }

    private static byte[] derInteger(BigInteger val) {
        byte[] bytes = val.toByteArray();
        return concat(new byte[]{0x02}, derLength(bytes.length), bytes);
    }

    private static byte[] derBitString(byte[] content) {
        // BIT STRING with 0 unused bits
        return concat(new byte[]{0x03}, derLength(content.length + 1),
                new byte[]{0x00}, content);
    }

    private static byte[] derSequence(byte[] content) {
        return concat(new byte[]{0x30}, derLength(content.length), content);
    }

    private static byte[] derSet(byte[] content) {
        return concat(new byte[]{0x31}, derLength(content.length), content);
    }

    private static byte[] derOctetString(byte[] content) {
        return concat(new byte[]{0x04}, derLength(content.length), content);
    }

    private static byte[] derExplicit(int tag, byte[] content) {
        return concat(new byte[]{(byte) (0xa0 | tag)}, derLength(content.length), content);
    }

    private static byte[] derLength(int length) {
        if (length < 128) return new byte[]{(byte) length};
        if (length < 256) return new byte[]{(byte) 0x81, (byte) length};
        return new byte[]{(byte) 0x82, (byte) (length >> 8), (byte) length};
    }

    private static byte[] concat(byte[]... arrays) {
        int len = 0;
        for (var a : arrays) len += a.length;
        var result = new byte[len];
        int pos = 0;
        for (var a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    private static X509Certificate loadCertificate(Path path) throws Exception {
        var certPem = Files.readAllBytes(path);
        var certFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certFactory.generateCertificate(
                new ByteArrayInputStream(certPem));
    }

    private static void run(String... command) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes());
        var exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", command) + "\n" + output);
        }
    }

    /**
     * A generated domain certificate and its private key.
     */
    public record CertEntry(PrivateKey key, X509Certificate cert) {}
}
