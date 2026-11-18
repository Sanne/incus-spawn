package dev.incusspawn.util;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.incusspawn.DerEncoder;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.proxy.CertificateAuthority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.KeyFactory;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real macOS Keychain (#1467): a local HTTPS server whose CA is trusted, or not, in the System
 * keychain, the way {@code sudo security add-trusted-cert -d} and TLS-inspection agents do it.
 * <p>
 * It changes the machine's trust settings, so it runs only where the CI job that owns a disposable
 * runner sets {@code ISX_TEST_MUTATE_SYSTEM_KEYCHAIN=1}, never on a developer's Mac. Each test adds
 * a CA made for it and deletes the certificate again, by fingerprint, whatever the outcome. Its
 * trust settings are never changed or removed: on a runner that waited for an authorization
 * nobody gives (#1515), and a CA no test uses again needs neither.
 * <p>
 * It goes through {@code SystemTrust.sslContext()} alone, the CLI's entry point, so the same class
 * runs against main's code to show the failure this fixes. A CA trusted in the user's own domain
 * cannot be set up here: that waits for an authorization dialog a runner never answers.
 */
@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "ISX_TEST_MUTATE_SYSTEM_KEYCHAIN", matches = "1")
class KeychainTrustMacTest {

    private static final String SECURITY = "/usr/bin/security";
    private static final String SYSTEM_KEYCHAIN = "/Library/Keychains/System.keychain";

    @TempDir
    Path temp;

    private Path caPem;
    private String caSha1;
    private X509Certificate ca;
    private HttpsServer server;
    /** What to delete from the System keychain after the test, by SHA-1. */
    private final List<String> toDelete = new ArrayList<>();

    /**
     * A CA and a leaf for localhost from openssl, as an inspection proxy's would be. Each test's CA
     * has its own subject: with the same subject every time, as CertificateAuthority makes them,
     * add-trusted-cert hung on the runner after the first.
     */
    @BeforeEach
    void startServer() throws Exception {
        var cnf = Files.writeString(temp.resolve("x509.cnf"), """
                [req]
                distinguished_name = dn
                prompt = no
                [dn]
                CN = placeholder
                [ca]
                basicConstraints = critical,CA:TRUE
                keyUsage = critical,keyCertSign,cRLSign
                subjectKeyIdentifier = hash
                [leaf]
                basicConstraints = critical,CA:FALSE
                keyUsage = critical,digitalSignature,keyEncipherment
                extendedKeyUsage = serverAuth
                subjectAltName = DNS:localhost
                """);
        caPem = temp.resolve("ca.pem");
        var caKey = temp.resolve("ca.key");
        var leafKey = temp.resolve("leaf.key");
        var leafCsr = temp.resolve("leaf.csr");
        var leafPem = temp.resolve("leaf.pem");
        openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-keyout", caKey.toString(), "-out", caPem.toString(),
                "-subj", "/CN=isx keychain test " + System.nanoTime(), "-config", cnf.toString(), "-extensions", "ca");
        openssl("req", "-newkey", "rsa:2048", "-nodes", "-keyout", leafKey.toString(), "-out", leafCsr.toString(),
                "-subj", "/CN=localhost", "-config", cnf.toString());
        openssl("x509", "-req", "-in", leafCsr.toString(), "-CA", caPem.toString(), "-CAkey", caKey.toString(), "-CAcreateserial",
                "-days", "1", "-out", leafPem.toString(), "-extfile", cnf.toString(), "-extensions", "leaf");

        var factory = CertificateFactory.getInstance("X.509");
        X509Certificate leaf;
        try (var in = Files.newInputStream(caPem)) { ca = (X509Certificate) factory.generateCertificate(in); }
        try (var in = Files.newInputStream(leafPem)) { leaf = (X509Certificate) factory.generateCertificate(in); }
        caSha1 = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(ca.getEncoded()));
        var pkcs8 = Files.readString(leafKey).replaceAll("-----[A-Z ]+-----|\\s", "");
        var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8)));

        var keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("leaf", key, new char[0], new Certificate[] {leaf, ca});
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, new char[0]);
        var tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("localhost"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void removeTheCa() throws Exception {
        if (server != null) server.stop(0);
        if (caSha1 != null) toDelete.add(caSha1);
        for (var sha1 : toDelete) run(List.of("sudo", "-n", SECURITY, "delete-certificate", "-Z", sha1, SYSTEM_KEYCHAIN));
    }

    @Test
    void aCaAnAdministratorTrustsIsTrusted() throws Exception {
        assertThrows(SSLHandshakeException.class, this::get, "trusted before the CA was added");
        adminTrust("-r", "trustRoot");
        assertEquals(204, get());
    }

    @Test
    void aCaAnAdministratorTrustsForSslIsTrusted() throws Exception {
        adminTrust("-r", "trustRoot", "-p", "ssl");
        assertEquals(204, get());
    }

    @Test
    void aCaTrustedForAnotherPolicyIsNot() throws Exception {
        adminTrust("-r", "trustRoot", "-p", "smime");
        assertThrows(SSLHandshakeException.class, this::get);
    }

    @Test
    void aCaTrustedForOneHostNameIsNot() throws Exception {
        adminTrust("-r", "trustRoot", "-p", "ssl", "-s", "intranet.example");
        assertThrows(SSLHandshakeException.class, this::get);
    }

    @Test
    void aCaAnAdministratorDeniedIsNot() throws Exception {
        adminTrust("-r", "deny");
        assertThrows(SSLHandshakeException.class, this::get);
    }

    @Test
    void aCaInTheSystemKeychainWithoutTrustIsNot() throws Exception {
        sudo(SECURITY, "add-certificates", "-k", SYSTEM_KEYCHAIN, caPem.toString());
        assertThrows(SSLHandshakeException.class, this::get);
    }

    @Test
    void aChainThroughAnIntermediateAnAdministratorDeniedIsNot() throws Exception {
        // A deny only removed anchors, and an intermediate is never one (#1552)
        var chain = CertChain.create("isx keychain test " + System.nanoTime(), "localhost");
        var rootPem = Files.writeString(temp.resolve("chain-root.pem"), DerEncoder.toPem("CERTIFICATE", chain.root().getEncoded()));
        var intermediatePem = Files.writeString(temp.resolve("chain-intermediate.pem"),
                DerEncoder.toPem("CERTIFICATE", chain.intermediate().getEncoded()));
        toDelete.add(MacKeychainTrust.sha1(chain.root()));
        toDelete.add(MacKeychainTrust.sha1(chain.intermediate()));
        var chainServer = chain.serve();
        try {
            sudo(SECURITY, "add-trusted-cert", "-d", "-r", "trustRoot", "-k", SYSTEM_KEYCHAIN, rootPem.toString());
            assertEquals(204, get(chainServer));
            sudo(SECURITY, "add-trusted-cert", "-d", "-r", "deny", "-k", SYSTEM_KEYCHAIN, intermediatePem.toString());
            assertThrows(SSLHandshakeException.class, () -> get(chainServer));
        } finally {
            chainServer.stop(0);
        }
    }

    @Test
    void aChainThroughADeniedRootsCrossSignedCopyIsNot() throws Exception {
        // api.github.com sends Sectigo's Root E46 cross-signed by USERTrust ECC: a deny of the self-signed
        // E46 matched by hash alone never met the chain (#1552). Here older stands for USERTrust ECC and denied for E46 (#1631)
        var older = CertChain.create("isx keychain test older " + System.nanoTime(), "localhost");
        var denied = CertChain.create("isx keychain test denied " + System.nanoTime(), "localhost");
        var olderPem = Files.writeString(temp.resolve("older-root.pem"), DerEncoder.toPem("CERTIFICATE", older.root().getEncoded()));
        var deniedPem = Files.writeString(temp.resolve("denied-root.pem"), DerEncoder.toPem("CERTIFICATE", denied.root().getEncoded()));
        toDelete.add(MacKeychainTrust.sha1(older.root()));
        toDelete.add(MacKeychainTrust.sha1(denied.root()));
        var chainServer = denied.serve(denied.rootCrossSignedBy(older));
        try {
            sudo(SECURITY, "add-trusted-cert", "-d", "-r", "trustRoot", "-k", SYSTEM_KEYCHAIN, olderPem.toString());
            assertEquals(204, get(chainServer));
            sudo(SECURITY, "add-trusted-cert", "-d", "-r", "deny", "-k", SYSTEM_KEYCHAIN, deniedPem.toString());
            assertThrows(SSLHandshakeException.class, () -> get(chainServer));
        } finally {
            chainServer.stop(0);
        }
    }

    @Test
    void hostCaPropagationHandsGuestsWhatAnAdministratorTrusts() throws Exception {
        // #1530: the set host-ca.propagate installs in instances, from the real Keychain
        var anchor = "isx-" + CertificateAuthority.fingerprintOf(ca) + ".crt";
        var savedHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temp.toString());
            Files.createDirectories(SpawnConfig.configDir());
            Files.writeString(SpawnConfig.configDir().resolve("config.yaml"), "host-ca:\n  propagate: true\n");
            assertFalse(CertificateAuthority.collectTrustAnchors().files().containsKey(anchor), "propagated before the CA was added");
            adminTrust("-r", "trustRoot");
            assertTrue(CertificateAuthority.collectTrustAnchors().files().containsKey(anchor));
        } finally {
            System.setProperty("user.home", savedHome);
        }
    }

    private void adminTrust(String... args) throws Exception {
        var command = new ArrayList<>(List.of(SECURITY, "add-trusted-cert", "-d"));
        command.addAll(List.of(args));
        command.addAll(List.of("-k", SYSTEM_KEYCHAIN, caPem.toString()));
        sudo(command.toArray(String[]::new));
    }

    private void openssl(String... args) throws Exception {
        var command = new ArrayList<>(List.of("openssl"));
        command.addAll(List.of(args));
        assertEquals(0, run(command), "exit status of " + String.join(" ", command));
    }

    private static void sudo(String... command) throws Exception {
        var full = new ArrayList<>(List.of("sudo", "-n"));
        full.addAll(List.of(command));
        assertEquals(0, run(full), "exit status of " + String.join(" ", full));
    }

    /** Runs a command with a time limit, its output into the test's log; -1 when it ran out of time. */
    private static int run(List<String> command) throws Exception {
        var process = new ProcessBuilder(command).inheritIO()
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    private int get() throws Exception {
        return get(server);
    }

    private static int get(HttpsServer server) throws Exception {
        try (var client = HttpClient.newBuilder().sslContext(SystemTrust.sslContext()).build()) {
            var request = HttpRequest.newBuilder(URI.create("https://localhost:" + server.getAddress().getPort() + "/")).build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
