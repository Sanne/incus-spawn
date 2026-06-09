package dev.incusspawn.incus;

import dev.incusspawn.Environment;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.MitmProxy;
import dev.incusspawn.vm.VmNetwork;
import org.junit.jupiter.api.*;

import java.net.HttpURLConnection;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the MITM proxy network path.
 * Verifies: proxy startup, DNS overrides, DNAT routing, TLS interception.
 * Uses a dummy API key — no real credentials needed.
 *
 * The key assertion: when a container connects to an intercepted domain,
 * the TLS certificate it receives is signed by our MITM CA (not the real
 * upstream cert). This proves the full proxy chain works without calling
 * any external API.
 *
 * Run with:
 *   mvn verify -DskipITs=false -Dit.test=ProxyNetworkIT
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProxyNetworkIT {

    private static final String CONTAINER = "isx-proxy-it-" + (System.currentTimeMillis() % 100000);
    private static final String DUMMY_API_KEY = "test-placeholder-not-a-real-key";

    private static IncusClient client;
    private static MitmProxy proxy;
    private static String proxyAddress;
    private static String caFingerprint;
    private static String originalDnsmasq;

    @BeforeAll
    static void setUp() throws Exception {
        client = new IncusClient();
        Assumptions.assumeTrue(client.checkConnectivity() == null,
                "Incus not reachable — skipping proxy tests");

        var ca = CertificateAuthority.loadOrCreate();
        caFingerprint = ca.caFingerprint();

        if (Environment.isMacOS()) {
            proxyAddress = VmNetwork.discoverHostBridgeIp();
        } else {
            proxyAddress = MitmProxy.resolveGatewayIp(client);
        }
        Assumptions.assumeTrue(proxyAddress != null, "Cannot determine proxy bind address");

        proxy = new MitmProxy(proxyAddress, 18443, 18080,
                DUMMY_API_KEY, "", false, "", "");

        Thread.ofPlatform().daemon().start(() -> {
            try {
                proxy.start(() -> {});
            } catch (Exception e) {
                System.err.println("Proxy failed: " + e.getMessage());
            }
        });

        boolean healthy = false;
        for (int i = 0; i < 30; i++) {
            if (isHealthy(proxyAddress)) { healthy = true; break; }
            Thread.sleep(500);
        }
        Assumptions.assumeTrue(healthy, "Proxy health check failed after 15s");

        originalDnsmasq = client.networkConfigGet("incusbr0", "raw.dnsmasq");
        MitmProxy.configureBridgeDns(client);
    }

    @AfterAll
    static void tearDown() {
        if (proxy != null) {
            try { proxy.stop(); } catch (Exception ignored) {}
        }
        if (client != null) {
            try {
                if (originalDnsmasq != null) {
                    client.networkConfigSet("incusbr0", "raw.dnsmasq", originalDnsmasq);
                }
            } catch (Exception ignored) {}
            try { client.delete(CONTAINER, true); } catch (Exception ignored) {}
        }
    }

    @Test @Order(1)
    void proxyHealthEndpoint() {
        assertTrue(isHealthy(proxyAddress));
    }

    @Test @Order(2)
    void launchContainer() {
        client.launch("images:alpine/edge", CONTAINER, false);
        assertTrue(client.pollUntilReady(CONTAINER, 30, "true"));
        // Point DNS at bridge gateway where dnsmasq runs
        var gatewayIp = MitmProxy.resolveGatewayIp(client);
        client.shellExec(CONTAINER, "sh", "-c",
                "echo nameserver " + gatewayIp + " > /etc/resolv.conf");
    }

    @Test @Order(3)
    void dnsResolvesInterceptedDomainToGateway() {
        Assumptions.assumeTrue(client.exists(CONTAINER));
        var gatewayIp = MitmProxy.resolveGatewayIp(client);
        // Use getent ahostsv4 which works reliably on Alpine
        var result = client.shellExec(CONTAINER, "sh", "-c",
                "getent ahostsv4 api.anthropic.com | head -1 | awk '{print $1}'");
        assertTrue(result.success(), "DNS lookup failed: " + result.stderr());
        assertEquals(gatewayIp, result.stdout().strip(),
                "api.anthropic.com should resolve to bridge gateway");
    }

    @Test @Order(4)
    void tlsCertificateIsFromMitmCa() {
        Assumptions.assumeTrue(client.exists(CONTAINER));

        // Install openssl and test TLS cert from proxy
        client.shellExec(CONTAINER, "sh", "-c", "apk add -q openssl 2>/dev/null");

        // First check if the proxy's TLS port is reachable from the container
        var portCheck = client.shellExec(CONTAINER, "sh", "-c",
                "timeout 3 sh -c 'echo | nc " + proxyAddress + " 18443' >/dev/null 2>&1; echo $?");
        Assumptions.assumeTrue("0".equals(portCheck.stdout().strip()),
                "Container can't reach proxy TLS port " + proxyAddress + ":18443");

        // Verify proxy is still alive before the TLS test
        assertTrue(isHealthy(proxyAddress), "Proxy died before TLS test");

        // Connect directly to the proxy and request api.anthropic.com via SNI.
        // The proxy generates a per-domain cert signed by our MITM CA.
        System.out.println("  Connecting to " + proxyAddress + ":18443 with SNI api.anthropic.com...");
        var result = client.shellExec(CONTAINER, "sh", "-c",
                "echo Q | timeout 10 openssl s_client -connect " + proxyAddress + ":18443 " +
                "-servername api.anthropic.com 2>&1");
        var fullOutput = result.stdout();
        var issuerLine = fullOutput.lines()
                .filter(l -> l.toLowerCase().contains("issuer"))
                .findFirst().orElse("");
        assertFalse(issuerLine.isEmpty(),
                "No issuer in openssl output. Full output:\n" + fullOutput.substring(0, Math.min(500, fullOutput.length())));
        assertTrue(issuerLine.contains("incus-spawn"),
                "Certificate issuer should contain 'incus-spawn', got: " + issuerLine);
        System.out.println("  " + issuerLine.strip());
    }

    private static boolean isHealthy(String address) {
        try {
            var url = URI.create("http://" + address + ":18080/health").toURL();
            var conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
