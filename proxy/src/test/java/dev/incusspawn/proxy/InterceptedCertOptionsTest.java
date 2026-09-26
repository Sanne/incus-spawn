package dev.incusspawn.proxy;

import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;

import io.vertx.core.Vertx;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * dnsmasq sends every name under an intercepted domain to the proxy, at any depth, so the
 * proxy must present a certificate that verifies for each of them (#783).
 */
class InterceptedCertOptionsTest {

    @TempDir
    static Path tempHome;

    static String origHome;
    static Vertx vertx;
    static MitmProxy proxy;
    static int mitmPort;
    static TrustManagerFactory containerTrust;

    @BeforeAll
    static void startProxy() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));
        var ca = CertificateAuthority.loadOrCreate();

        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("${token}");
        var credentials = new ProxyCredentials("", "", false, "", "", List.of(
                new ResolvedToolProxy("gh", "*.githubusercontent.com", auth, Map.of("token", "t"))));
        vertx = Vertx.vertx();
        mitmPort = freePort();
        proxy = new MitmProxy(vertx, "127.0.0.1", mitmPort, freePort(), "127.0.0.1", credentials);
        var ready = new CountDownLatch(1);
        var thread = new Thread(() -> {
            try {
                proxy.start(ready::countDown);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "test-proxy");
        thread.setDaemon(true);
        thread.start();
        assertTrue(ready.await(30, TimeUnit.SECONDS), "Proxy did not start in time");

        // What a container has: the isx CA as its only trust anchor.
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("isx", ca.caCert());
        containerTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        containerTrust.init(trust);
    }

    @AfterAll
    static void stopProxy() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "githubusercontent.com",
            "raw.githubusercontent.com",
            "results-receiver.actions.githubusercontent.com", // the name from #783
            "a.b.c.githubusercontent.com",
            "cdn01.quay.io",
            "x.y.repo1.maven.org",
    })
    void handshakeVerifiesForEveryDepthUnderAnInterceptedDomain(String host) throws Exception {
        var peer = handshake(host);
        assertEquals("CN=incus-spawn MITM CA", peer.getIssuerX500Principal().getName());
    }

    @Test
    void nameOutsideEveryInterceptedDomainFailsTheHandshakeInsteadOfGettingAnUnrelatedCert() {
        // Before #783 this got whichever keystore entry came first -- a cert for some other
        // intercepted domain, which is how GitHub hosts ended up offered *.api.openai.com.
        assertThrows(SSLException.class, () -> handshake("not-intercepted.example"));
        assertThrows(SSLException.class, () -> handshake("evilgithubusercontent.com"));
    }

    @Test
    void clientWithoutSniIsRefusedRatherThanGivenAnArbitraryCert() {
        assertThrows(SSLException.class, () -> handshake(null));
    }

    /**
     * The rule behind the handshake test, for every domain the proxy intercepts out of the
     * box -- built-ins and every bundled tool's proxy entries -- so a newly added domain is
     * covered without anyone remembering to list it here.
     */
    @Test
    void everyInterceptedDomainYieldsAMatchingCertAtAnyDepth() {
        var domains = shippedInterceptedDomains();
        assertTrue(domains.contains("githubusercontent.com"), "gh's domains should be in the set: " + domains);
        for (var d : domains) {
            for (var host : List.of(d, "x." + d, "x.y." + d, "x.y.z." + d)) {
                var certName = InterceptedCertOptions.certNameFor(host, domains);
                assertNotNull(certName, "no cert for " + host);
                assertTrue(matches(certName, host), certName + " does not verify for " + host);
            }
        }
    }

    @Test
    void certNameNormalizesAndRejects() {
        var domains = Set.of("github.com", "githubusercontent.com");
        assertEquals("github.com", InterceptedCertOptions.certNameFor("GitHub.com.", domains));
        assertEquals("*.github.com", InterceptedCertOptions.certNameFor("api.github.com", domains));
        assertEquals("*.actions.githubusercontent.com", InterceptedCertOptions.certNameFor(
                "results-receiver.actions.githubusercontent.com", domains));
        assertNull(InterceptedCertOptions.certNameFor("notgithub.com", domains));
        assertNull(InterceptedCertOptions.certNameFor("example.org", domains));
        assertNull(InterceptedCertOptions.certNameFor("../x.github.com", domains));
        assertNull(InterceptedCertOptions.certNameFor("a..github.com", domains));
        assertNull(InterceptedCertOptions.certNameFor("a/b.github.com", domains));
        assertNull(InterceptedCertOptions.certNameFor(null, domains));
    }

    private static X509Certificate handshake(String host) throws Exception {
        // A fresh context per handshake, like a fresh curl: a shared one would resume an
        // earlier test's session and skip the certificate exchange this test is about.
        var clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, containerTrust.getTrustManagers(), null);
        try (var socket = (SSLSocket) clientTls.getSocketFactory().createSocket("127.0.0.1", mitmPort)) {
            // A refusal must be prompt: a hang surfaces as SocketTimeoutException, not SSLException.
            socket.setSoTimeout(5_000);
            var params = socket.getSSLParameters();
            params.setServerNames(host == null ? List.of() : List.of(new SNIHostName(host)));
            // Hostname verification against the SNI name, as curl and Go's net/http do.
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.startHandshake();
            return (X509Certificate) socket.getSession().getPeerCertificates()[0];
        }
    }

    private static Set<String> shippedInterceptedDomains() {
        var toolDomains = new HashSet<String>();
        for (var setup : new ToolDefLoader().allToolSetups().values()) {
            var proxyDef = setup.proxy();
            if (proxyDef == null || proxyDef.getAuth() == null) continue;
            for (var auth : proxyDef.getAuth()) toolDomains.addAll(auth.getDomains());
        }
        return ProxyConfig.interceptedDomains(toolDomains);
    }

    /** RFC 6125 matching: a leading {@code *} stands for exactly one label. */
    private static boolean matches(String certName, String host) {
        if (!certName.startsWith("*.")) return certName.equals(host);
        var dot = host.indexOf('.');
        return dot > 0 && host.substring(dot + 1).equals(certName.substring(2));
    }

    private static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
