package dev.incusspawn.vm;

import dev.incusspawn.Environment;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Date;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import static dev.incusspawn.DerEncoder.*;

/**
 * One-time setup of the Incus HTTPS remote for macOS.
 * <p>
 * The VM reads the host's client certificate directly from the virtio-fs
 * shared directory ({@code /host/.config/incus-spawn/vm/client.crt}) and
 * adds it to the Incus trust store on every boot. This class generates
 * the client certificate, saves the server certificate, and writes the
 * local Incus client config.
 */
public final class IncusRemoteSetup {

    private IncusRemoteSetup() {}

    private static final String REMOTE_NAME = "isx-vm";
    private static final int INCUS_PORT = 8443;

    public static boolean isConfigured() {
        if (!Files.exists(Environment.incusConfigFile())) return false;
        if (!Files.exists(Environment.incusClientCert())) return false;
        if (!Files.exists(Environment.incusClientKey())) return false;
        try {
            var content = Files.readString(Environment.incusConfigFile());
            return content.contains(REMOTE_NAME);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Ensure the client certificate exists, generating it if needed.
     * Call this before starting the VM so the cert is available via
     * virtio-fs when the VM boots.
     */
    public static void ensureCertExists() throws IOException {
        Files.createDirectories(Environment.incusConfigDir());
        if (!Files.exists(Environment.incusClientCert())) {
            generateClientCertificate();
        }
    }

    /**
     * Configure the Incus remote for the VM at the given IP.
     * The VM must already be running with HTTPS enabled on port 8443.
     * The VM trusts our client cert via virtio-fs, so no trust token
     * exchange is needed.
     */
    public static void configure(String vmIp) throws IOException {
        System.err.println("Configuring Incus remote for VM at " + vmIp + "...");

        Files.createDirectories(Environment.incusConfigDir());
        Files.createDirectories(Environment.incusServerCertsDir());

        ensureCertExists();

        System.err.println("  Waiting for Incus HTTPS API on " + vmIp + ":" + INCUS_PORT + "...");
        if (!waitForPort(vmIp, INCUS_PORT, 60)) {
            throw new IOException("Incus HTTPS API not reachable at " + vmIp + ":" + INCUS_PORT
                    + " after 60s. Check 'isx vm console' for boot logs.");
        }

        saveServerCert(vmIp);

        writeClientConfig(vmIp);

        var baseUrl = "https://" + vmIp + ":" + INCUS_PORT;
        System.err.println("  Incus remote '" + REMOTE_NAME + "' configured at " + baseUrl);
    }

    public static void updateVmIp(String newIp) throws IOException {
        writeClientConfig(newIp);
        System.err.println("  Updated Incus remote IP to " + newIp);
    }

    // --- Certificate generation (pure Java, no external tools) ---

    static void generateClientCertificate() throws IOException {
        System.err.println("  Generating Incus client certificate...");

        var certFile = Environment.incusClientCert();
        var keyFile = Environment.incusClientKey();

        try {
            var keyGen = KeyPairGenerator.getInstance("EC");
            keyGen.initialize(new ECGenParameterSpec("secp384r1"));
            var keyPair = keyGen.generateKeyPair();

            var yesterday = new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000);
            var expiry = new Date(yesterday.getTime() + 3651L * 24 * 60 * 60 * 1000);
            var serial = new BigInteger(128, new SecureRandom());
            var subject = derDistinguishedName("incus-spawn");

            // v1 cert (no version tag needed, v1 is the default) — no extensions
            var algId = sha384WithEcdsaAid();
            var tbsCert = derSequence(concat(
                    derInteger(serial),
                    algId,
                    subject,                                            // issuer = subject (self-signed)
                    derSequence(concat(derUtcTime(yesterday), derUtcTime(expiry))),
                    subject,
                    keyPair.getPublic().getEncoded()                    // SubjectPublicKeyInfo
            ));

            var sig = java.security.Signature.getInstance("SHA384withECDSA");
            sig.initSign(keyPair.getPrivate());
            sig.update(tbsCert);

            var certDer = derSequence(concat(
                    tbsCert,
                    algId,
                    derBitString(sig.sign())
            ));

            Files.writeString(certFile, toPem("CERTIFICATE", certDer));
            Files.writeString(keyFile, toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));

            setOwnerOnly(certFile);
            setOwnerOnly(keyFile);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("Failed to generate client certificate", e);
        }
    }

    // --- Server cert & config ---

    private static void saveServerCert(String vmIp) {
        try {
            var sslContext = SSLContext.getInstance("TLS");
            var capturingTm = new CertCapturingTrustManager();
            sslContext.init(null, new TrustManager[]{capturingTm}, null);

            var client = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + vmIp + ":" + INCUS_PORT + "/1.0"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());

            if (capturingTm.captured != null) {
                var certPath = Environment.incusServerCertsDir().resolve(REMOTE_NAME + ".crt");
                Files.writeString(certPath, toPem("CERTIFICATE", capturingTm.captured.getEncoded()));
            }
        } catch (Exception e) {
            System.err.println("  Warning: could not save server certificate: " + e.getMessage());
        }
    }

    private static void writeClientConfig(String vmIp) throws IOException {
        var configFile = Environment.incusConfigFile();
        var config = "default-remote: " + REMOTE_NAME + "\n"
                + "remotes:\n"
                + "  " + REMOTE_NAME + ":\n"
                + "    addr: https://" + vmIp + ":" + INCUS_PORT + "\n"
                + "    protocol: lxd\n"
                + "  images:\n"
                + "    addr: https://images.linuxcontainers.org\n"
                + "    protocol: simplestreams\n";
        Files.writeString(configFile, config);
    }

    // --- Helpers ---

    private static boolean waitForPort(String host, int port, int maxWaitSeconds) {
        for (int i = 0; i < maxWaitSeconds; i++) {
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1000);
                return true;
            } catch (IOException ignored) {}
            try { Thread.sleep(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void setOwnerOnly(java.nio.file.Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {}
    }

    private static class CertCapturingTrustManager extends X509ExtendedTrustManager {
        volatile X509Certificate captured;
        @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String a) {
            if (chain != null && chain.length > 0) captured = chain[0];
        }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, java.net.Socket s) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String a, java.net.Socket s) {
            checkServerTrusted(chain, a);
        }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String a, javax.net.ssl.SSLEngine e) {
            checkServerTrusted(chain, a);
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
