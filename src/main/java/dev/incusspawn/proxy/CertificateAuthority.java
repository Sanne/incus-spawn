package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

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

    private static final Path CA_KEY_FILE = SpawnConfig.configDir().resolve("ca.key");
    private static final Path CA_CERT_FILE = SpawnConfig.configDir().resolve("ca.crt");

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
            if (Files.exists(CA_KEY_FILE) && Files.exists(CA_CERT_FILE)) {
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
        return Files.exists(CA_KEY_FILE) && Files.exists(CA_CERT_FILE);
    }

    /**
     * Generate a TLS certificate for the given domain, signed by this CA.
     * The certificate includes the domain as both CN and a SAN DNS entry.
     */
    public CertEntry generateDomainCert(String domain) {
        try {
            // Generate key pair for this domain
            var domainKeyFile = Files.createTempFile("incus-spawn-domain-", ".key");
            var domainCertFile = Files.createTempFile("incus-spawn-domain-", ".crt");
            var csrFile = Files.createTempFile("incus-spawn-domain-", ".csr");
            var extFile = Files.createTempFile("incus-spawn-domain-", ".ext");

            try {
                // Write SAN extension config
                Files.writeString(extFile,
                        "subjectAltName=DNS:" + domain + "\n" +
                        "basicConstraints=CA:FALSE\n");

                // Generate domain key
                run("openssl", "genrsa", "-out", domainKeyFile.toString(), "2048");

                // Generate CSR
                run("openssl", "req", "-new",
                        "-key", domainKeyFile.toString(),
                        "-out", csrFile.toString(),
                        "-subj", "/CN=" + domain);

                // Sign with CA
                run("openssl", "x509", "-req",
                        "-in", csrFile.toString(),
                        "-CA", CA_CERT_FILE.toString(),
                        "-CAkey", CA_KEY_FILE.toString(),
                        "-CAcreateserial",
                        "-out", domainCertFile.toString(),
                        "-days", "365",
                        "-sha256",
                        "-extfile", extFile.toString());

                // Load the generated key and cert into Java objects
                var key = loadPrivateKey(domainKeyFile);
                var cert = loadCertificate(domainCertFile);

                return new CertEntry(key, cert);
            } finally {
                Files.deleteIfExists(domainKeyFile);
                Files.deleteIfExists(domainCertFile);
                Files.deleteIfExists(csrFile);
                Files.deleteIfExists(extFile);
            }
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
            return Files.readString(CA_CERT_FILE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read CA cert: " + e.getMessage(), e);
        }
    }

    public PrivateKey caKey() {
        return caKey;
    }

    public X509Certificate caCert() {
        return caCert;
    }

    // --- Private helpers ---

    private static CertificateAuthority load() throws Exception {
        var key = loadPrivateKey(CA_KEY_FILE);
        var cert = loadCertificate(CA_CERT_FILE);
        return new CertificateAuthority(key, cert);
    }

    private static CertificateAuthority generate() throws Exception {
        System.out.println("Generating MITM CA certificate...");
        Files.createDirectories(CA_KEY_FILE.getParent());

        // Generate CA key
        run("openssl", "genrsa", "-out", CA_KEY_FILE.toString(), "2048");
        Files.setPosixFilePermissions(CA_KEY_FILE,
                PosixFilePermissions.fromString("rw-------"));

        // Generate self-signed CA cert
        run("openssl", "req", "-x509", "-new", "-nodes",
                "-key", CA_KEY_FILE.toString(),
                "-sha256", "-days", "3650",
                "-out", CA_CERT_FILE.toString(),
                "-subj", "/CN=incus-spawn MITM CA/O=incus-spawn",
                "-addext", "basicConstraints=critical,CA:TRUE",
                "-addext", "keyUsage=critical,keyCertSign,cRLSign");
        Files.setPosixFilePermissions(CA_CERT_FILE,
                PosixFilePermissions.fromString("rw-------"));

        System.out.println("  CA certificate saved to " + CA_CERT_FILE);
        System.out.println("  CA private key saved to " + CA_KEY_FILE);

        var key = loadPrivateKey(CA_KEY_FILE);
        var cert = loadCertificate(CA_CERT_FILE);
        return new CertificateAuthority(key, cert);
    }

    private static PrivateKey loadPrivateKey(Path path) throws Exception {
        // openssl genrsa outputs PKCS#1 (traditional) format.
        // Convert to PKCS#8 in memory for Java's KeyFactory.
        var pb = new ProcessBuilder("openssl", "pkcs8", "-topk8", "-nocrypt",
                "-in", path.toString(), "-outform", "DER");
        pb.redirectErrorStream(true);
        var process = pb.start();
        var derBytes = process.getInputStream().readAllBytes();
        var exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to convert key to PKCS#8: exit code " + exitCode);
        }
        var keySpec = new PKCS8EncodedKeySpec(derBytes);
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
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
