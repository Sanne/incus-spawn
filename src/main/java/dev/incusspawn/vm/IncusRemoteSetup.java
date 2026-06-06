package dev.incusspawn.vm;

import dev.incusspawn.Environment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * One-time setup of the Incus HTTPS remote for macOS.
 * <p>
 * The Incus daemon inside the VM enables HTTPS on port 8443 and prints a
 * trust token to the serial console (vm.log) during boot. This class
 * generates client certificates, reads that token, adds our cert to the
 * daemon's trust store, and writes the local Incus client config.
 */
public final class IncusRemoteSetup {

    private IncusRemoteSetup() {}

    private static final String REMOTE_NAME = "isx-vm";
    private static final int INCUS_PORT = 8443;
    private static final Pattern TRUST_TOKEN_PATTERN = Pattern.compile("ISX_TRUST_TOKEN=(.+)");

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
     * Configure the Incus remote for the VM at the given IP.
     * The VM must already be running with HTTPS enabled on port 8443
     * and a trust token printed to the VM log.
     */
    public static void configure(String vmIp) throws IOException {
        System.err.println("Configuring Incus remote for VM at " + vmIp + "...");

        Files.createDirectories(Environment.incusConfigDir());
        Files.createDirectories(Environment.incusServerCertsDir());

        // Step 1: Generate client certificate if not present
        if (!Files.exists(Environment.incusClientCert())) {
            generateClientCertificate();
        }

        // Step 2: Wait for the Incus HTTPS API to be reachable
        System.err.println("  Waiting for Incus HTTPS API on " + vmIp + ":" + INCUS_PORT + "...");
        if (!waitForPort(vmIp, INCUS_PORT, 60)) {
            throw new IOException("Incus HTTPS API not reachable at " + vmIp + ":" + INCUS_PORT
                    + " after 60s. Check 'isx vm console' for boot logs.");
        }

        // Step 3: Read trust token from VM log
        var trustToken = readTrustToken();
        if (trustToken == null) {
            throw new IOException("No trust token found in VM log (" + Environment.vmLogFile() + ").\n"
                    + "The VM appliance may need to be rebuilt with the latest incus-spawn-vm-init.");
        }
        System.err.println("  Trust token found.");

        // Step 4: Add our client certificate using the trust token
        var baseUrl = "https://" + vmIp + ":" + INCUS_PORT;
        addClientTrust(baseUrl, trustToken);

        // Step 5: Save the server certificate for future TLS verification
        saveServerCert(vmIp);

        // Step 6: Write the client config
        writeClientConfig(vmIp);

        System.err.println("  Incus remote '" + REMOTE_NAME + "' configured at " + baseUrl);
    }

    public static void updateVmIp(String newIp) throws IOException {
        writeClientConfig(newIp);
        System.err.println("  Updated Incus remote IP to " + newIp);
    }

    // --- Certificate generation ---

    private static void generateClientCertificate() throws IOException {
        System.err.println("  Generating Incus client certificate...");
        try {
            generateCertWithOpenssl();
        } catch (IOException e) {
            generateCertWithKeytool();
        }
    }

    private static void generateCertWithOpenssl() throws IOException {
        var certFile = Environment.incusClientCert();
        var keyFile = Environment.incusClientKey();

        var yesterday = java.time.Instant.now().minus(java.time.Duration.ofDays(1));
        var notBefore = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(yesterday);

        var pb = new ProcessBuilder(
                "openssl", "req", "-x509", "-newkey", "ec",
                "-pkeyopt", "ec_paramgen_curve:P-384",
                "-nodes", "-sha384",
                "-days", "3651",
                "-not_before", notBefore,
                "-subj", "/CN=incus-spawn",
                "-keyout", keyFile.toString(),
                "-out", certFile.toString()
        );
        pb.redirectErrorStream(true);
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes());
        try {
            if (process.waitFor() != 0) {
                throw new IOException("openssl failed: " + output.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during cert generation");
        }

        setOwnerOnly(certFile);
        setOwnerOnly(keyFile);
    }

    private static void generateCertWithKeytool() throws IOException {
        var certFile = Environment.incusClientCert();
        var keyFile = Environment.incusClientKey();
        var ksFile = Environment.incusConfigDir().resolve("client.p12");

        try {
            // Generate keypair in a PKCS12 keystore
            run("keytool", "-genkeypair",
                    "-alias", "incus-client",
                    "-keyalg", "EC", "-groupname", "secp384r1",
                    "-validity", "3651",
                    "-startdate", "-1d",
                    "-dname", "CN=incus-spawn",
                    "-storetype", "PKCS12",
                    "-storepass", "changeit",
                    "-keypass", "changeit",
                    "-keystore", ksFile.toString());

            // Export certificate as PEM
            var certPem = runCapture("keytool", "-exportcert",
                    "-alias", "incus-client",
                    "-keystore", ksFile.toString(),
                    "-storepass", "changeit",
                    "-rfc");
            Files.writeString(certFile, certPem);

            // Export private key (keytool can't do this alone, use openssl)
            var keyOutput = runCapture("openssl", "pkcs12",
                    "-in", ksFile.toString(),
                    "-nocerts", "-nodes",
                    "-passin", "pass:changeit");
            var keyStart = keyOutput.indexOf("-----BEGIN");
            if (keyStart >= 0) {
                keyOutput = keyOutput.substring(keyStart);
            }
            Files.writeString(keyFile, keyOutput);

            setOwnerOnly(certFile);
            setOwnerOnly(keyFile);
        } finally {
            Files.deleteIfExists(ksFile);
        }
    }

    // --- Trust token ---

    static String readTrustToken() {
        var logFile = Environment.vmLogFile();
        if (!Files.exists(logFile)) return null;
        try {
            var content = Files.readString(logFile);
            var matcher = TRUST_TOKEN_PATTERN.matcher(content);
            if (matcher.find()) {
                return matcher.group(1).strip();
            }
        } catch (IOException ignored) {}
        return null;
    }

    // --- Incus API ---

    private static void addClientTrust(String baseUrl, String trustToken) throws IOException {
        var body = "{\"type\":\"client\",\"name\":\"incus-spawn\""
                + ",\"trust_token\":\"" + trustToken + "\"}";

        var client = buildClientCertHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/1.0/certificates"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400
                    && !response.body().contains("already exists")
                    && !response.body().contains("already trusted")) {
                throw new IOException("Failed to add client certificate: HTTP "
                        + response.statusCode() + " — " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

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
                var pem = "-----BEGIN CERTIFICATE-----\n"
                        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(
                                capturingTm.captured.getEncoded())
                        + "\n-----END CERTIFICATE-----\n";
                Files.writeString(certPath, pem);
            }
        } catch (Exception e) {
            System.err.println("  Warning: could not save server certificate: " + e.getMessage());
        }
    }

    private static void writeClientConfig(String vmIp) throws IOException {
        var config = "default-remote: " + REMOTE_NAME + "\n"
                + "remotes:\n"
                + "  " + REMOTE_NAME + ":\n"
                + "    addr: https://" + vmIp + ":" + INCUS_PORT + "\n"
                + "    protocol: lxd\n"
                + "  images:\n"
                + "    addr: https://images.linuxcontainers.org\n"
                + "    protocol: simplestreams\n";
        Files.writeString(Environment.incusConfigFile(), config);
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

    private static void run(String... command) throws IOException {
        var pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes());
        try {
            if (process.waitFor() != 0) {
                throw new IOException(command[0] + " failed: " + output.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted running " + command[0]);
        }
    }

    private static String runCapture(String... command) throws IOException {
        var pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes());
        try {
            if (process.waitFor() != 0) {
                throw new IOException(command[0] + " failed: " + output.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted running " + command[0]);
        }
        return output;
    }

    private static HttpClient buildClientCertHttpClient() {
        try {
            var certPath = Environment.incusClientCert();
            var keyPath = Environment.incusClientKey();

            var certFactory = java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.cert.Certificate clientCert;
            try (var is = Files.newInputStream(certPath)) {
                clientCert = certFactory.generateCertificate(is);
            }

            var keyPem = Files.readString(keyPath);
            var keyB64 = keyPem
                    .replaceAll("-----BEGIN[^-]+-----", "")
                    .replaceAll("-----END[^-]+-----", "")
                    .replaceAll("\\s+", "");
            var keyBytes = Base64.getDecoder().decode(keyB64);
            var keySpec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
            java.security.PrivateKey privateKey;
            try {
                privateKey = java.security.KeyFactory.getInstance("EC").generatePrivate(keySpec);
            } catch (Exception e) {
                privateKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec);
            }

            var ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("incus-client", privateKey, new char[0],
                    new java.security.cert.Certificate[]{clientCert});
            var kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                    javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, new char[0]);

            var sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), new TrustManager[]{new PermissiveTrustManager()}, null);

            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(10))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
        } catch (Exception e) {
            throw new VmException("Failed to create HTTP client with client certificate: " + e.getMessage());
        }
    }

    private static class PermissiveTrustManager extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
        @Override public void checkClientTrusted(X509Certificate[] c, String a, java.net.Socket s) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a, java.net.Socket s) {}
        @Override public void checkClientTrusted(X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
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
