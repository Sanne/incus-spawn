package dev.incusspawn.proxy;

import com.sun.net.httpserver.HttpServer;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import static org.mockito.Mockito.*;

class ProxyHealthCheckTest {

    @AfterEach
    void clearCache() {
        ProxyHealthCheck.invalidateCache();
    }

    @Test
    void isHealthyReturnsTrueWhenServerResponds() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            var body = "{\"status\":\"ok\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            assertTrue(ProxyHealthCheck.isHealthy("127.0.0.1", port));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void isHealthyReturnsFalseWhenNothingListening() {
        assertFalse(ProxyHealthCheck.isHealthy("127.0.0.1", 1));
    }

    @Test
    void checkReturnsNotRunningWhenNoProxyNoDns() {
        assumeFalse(ProxyHealthCheck.isHealthy("127.0.0.1"),
                "A real proxy is running on localhost — cannot test NOT_RUNNING");
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("10.0.0.1/24");
        when(incus.networkConfigGet("incusbr0", "raw.dnsmasq")).thenReturn("");

        var status = ProxyHealthCheck.check(incus);
        assertEquals(ProxyHealthCheck.ProxyStatus.NOT_RUNNING, status);
    }

    @Test
    void checkReturnsStaleDnsWhenDnsOverridesPresent() {
        assumeFalse(ProxyHealthCheck.isHealthy("127.0.0.1"),
                "A real proxy is running on localhost — cannot test STALE_DNS");
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("10.0.0.1/24");
        when(incus.networkConfigGet("incusbr0", "raw.dnsmasq"))
                .thenReturn("address=/api.anthropic.com/10.0.0.1\naddress=/github.com/10.0.0.1");

        var status = ProxyHealthCheck.check(incus);
        assertEquals(ProxyHealthCheck.ProxyStatus.STALE_DNS, status);
    }

    @Test
    void aProxyStillListeningOnTheOldGatewayIsNotReportedAsNotRunning() throws Exception {
        // #919: incusbr0's address changed under a running proxy. Probing the new gateway finds
        // nothing, and the old address is gone from the bridge so it does not answer either,
        // but the proxy's socket on it is still there. 127.0.0.2 routes to lo but is not assigned.
        assertEquals(ProxyHealthCheck.ProxyStatus.STALE_GATEWAY, checkWithHealthListenerOn("127.0.0.2"));
    }

    @Test
    void aProxyOnAnAddressTheHostStillHasIsNotOnAnOldGateway() throws Exception {
        // A proxy started with --gateway-ip on another host address (#933): restarting it
        // would bind the same address again, so the restart advice would be wrong.
        assertEquals(ProxyHealthCheck.ProxyStatus.STALE_DNS, checkWithHealthListenerOn("127.0.0.1"));
    }

    @Test
    void aStaleForegroundProxyIsNotJoinedByTheService() {
        // The service would start beside it on the new gateway, and the stale proxy would write
        // its old address back into the bridge DNS on its next reload.
        var stale = ProxyHealthCheck.ProxyStatus.STALE_GATEWAY;
        assertFalse(ProxyHealthCheck.serviceRestartCanHelp(stale, true, () -> false));
        assertTrue(ProxyHealthCheck.serviceRestartCanHelp(stale, true, () -> true));
        assertFalse(ProxyHealthCheck.serviceRestartCanHelp(stale, false, () -> true));
        // A proxy that is down is started whether or not the service was active, without asking.
        for (var down : java.util.List.of(ProxyHealthCheck.ProxyStatus.NOT_RUNNING, ProxyHealthCheck.ProxyStatus.STALE_DNS)) {
            assertTrue(ProxyHealthCheck.serviceRestartCanHelp(down, true,
                    () -> fail("a stopped proxy must not cost an is-active query")));
            assertFalse(ProxyHealthCheck.serviceRestartCanHelp(down, false, () -> true));
        }
    }

    /** The status with the bridge at 10.0.0.1, its overrides at {@code ip}, and a socket on that health port. */
    private static ProxyHealthCheck.ProxyStatus checkWithHealthListenerOn(String ip) throws Exception {
        assumeTrue(Files.isReadable(Path.of("/proc/net/tcp")), "needs Linux /proc");
        assumeFalse(ProxyHealthCheck.isHealthy("127.0.0.1"), "A real proxy is running on localhost");
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("10.0.0.1/24");
        when(incus.networkConfigGet("incusbr0", "raw.dnsmasq"))
                .thenReturn(BridgeDns.render("", Set.of("github.com"), ip));
        try (var listener = new ServerSocket()) {
            try {
                listener.bind(new InetSocketAddress(ip, ProxyConfig.DEFAULT_HEALTH_PORT));
            } catch (IOException e) {
                assumeTrue(false, "cannot bind " + ip + ":" + ProxyConfig.DEFAULT_HEALTH_PORT);
            }
            return ProxyHealthCheck.check(incus);
        }
    }

    @Test
    void listensOnReadsListenersFromBothTables(@TempDir Path procNet)
            throws Exception {
        assumeTrue(java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN,
                "the fixture is in little-endian word order");
        var header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n";
        Files.writeString(procNet.resolve("tcp"), header
                + "   0: 0200000A:4A37 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 1\n"
                + "   1: 0300000A:4A37 0100000A:D431 01 00000000:00000000 00:00000000 00000000  1000        0 2\n");
        Files.writeString(procNet.resolve("tcp6"), header
                + "   0: 0000000000000000FFFF00000400000A:4A37 00000000000000000000000000000000:0000 0A"
                + " 00000000:00000000 00:00000000 00000000  1000        0 3\n");

        assertTrue(ProxyHealthCheck.listensOn("10.0.0.2", 0x4A37, procNet));
        assertTrue(ProxyHealthCheck.listensOn("10.0.0.4", 0x4A37, procNet), "dual-stack socket in tcp6");
        assertFalse(ProxyHealthCheck.listensOn("10.0.0.3", 0x4A37, procNet), "established, not listening");
        assertFalse(ProxyHealthCheck.listensOn("10.0.0.2", 18080, procNet));
        assertFalse(ProxyHealthCheck.listensOn("::", 0x4A37, procNet));
        assertFalse(ProxyHealthCheck.listensOn("not-an-ip", 0x4A37, procNet));
        assertFalse(ProxyHealthCheck.listensOn("10.0.0.2", 0x4A37, procNet.resolve("absent")));
    }

    @Test
    void overridesStillPointingAtTheCurrentGatewayAreStaleDns() {
        // Nothing listens there, so the proxy is down, not bound elsewhere.
        assumeFalse(ProxyHealthCheck.isHealthy("127.0.0.1"),
                "A real proxy is running on localhost — cannot test STALE_DNS");
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("10.0.0.1/24");
        when(incus.networkConfigGet("incusbr0", "raw.dnsmasq")).thenReturn(BridgeDns.render(
                "", Set.of("github.com"), "10.0.0.1"));
        assertEquals(ProxyHealthCheck.ProxyStatus.STALE_DNS, ProxyHealthCheck.check(incus));
    }

    @Test
    void formatErrorContainsActionableCommand() {
        var notRunning = ProxyHealthCheck.formatError(ProxyHealthCheck.ProxyStatus.NOT_RUNNING);
        assertTrue(notRunning.contains("isx proxy"));
        assertTrue(notRunning.contains("not running"));

        var staleDns = ProxyHealthCheck.formatError(ProxyHealthCheck.ProxyStatus.STALE_DNS);
        assertTrue(staleDns.contains("isx proxy"));
        assertTrue(staleDns.contains("DNS overrides"));

        var staleGateway = ProxyHealthCheck.formatError(ProxyHealthCheck.ProxyStatus.STALE_GATEWAY);
        assertTrue(staleGateway.contains("isx proxy stop && isx proxy start"));
        assertFalse(staleGateway.contains("not running"));
    }

    @Test
    void formatErrorReturnsEmptyForRunning() {
        assertEquals("", ProxyHealthCheck.formatError(ProxyHealthCheck.ProxyStatus.RUNNING));
    }

    @Test
    void parseProxyInfoExtractsAllFields() {
        var info = ProxyHealthCheck.parseProxyInfo(
                "{\"status\":\"ok\",\"version\":\"0.1.10\",\"gitSha\":\"abc1234\",\"runtime\":\"native (GraalVM 23.1)\",\"caFingerprint\":\"deadbeef\"}");
        assertEquals("0.1.10", info.version());
        assertEquals("abc1234", info.gitSha());
        assertEquals("native (GraalVM 23.1)", info.runtime());
        assertEquals("deadbeef", info.caFingerprint());
        assertFalse(info.isLegacy());
    }

    @Test
    void parseProxyInfoHandlesOldFormat() {
        var info = ProxyHealthCheck.parseProxyInfo("{\"status\":\"ok\"}");
        assertEquals("", info.version());
        assertEquals("", info.gitSha());
        assertEquals("", info.runtime());
        assertTrue(info.isLegacy());
    }

    @Test
    void parseProxyInfoHandlesMalformedJson() {
        var info = ProxyHealthCheck.parseProxyInfo("not json at all");
        assertTrue(info.isLegacy());
    }

    @Test
    void checkVersionDriftReturnsEmptyWhenMatching() {
        var cliInfo = BuildInfo.instance();
        var proxyInfo = new ProxyHealthCheck.ProxyInfo(cliInfo.version(), cliInfo.gitSha(), cliInfo.runtime(), "somefp", false, true, "");
        assertEquals("", ProxyHealthCheck.checkVersionDrift(proxyInfo));
    }

    @Test
    void checkVersionDriftDetectsMismatch() {
        var proxyInfo = new ProxyHealthCheck.ProxyInfo("0.0.1", "old1234567", "JVM", "somefp", false, true, "");
        var drift = ProxyHealthCheck.checkVersionDrift(proxyInfo);
        assertFalse(drift.isEmpty());
        assertTrue(drift.contains("0.0.1"));
    }

    @Test
    void checkVersionDriftDetectsLegacy() {
        var proxyInfo = new ProxyHealthCheck.ProxyInfo("", "", "", "", false, true, "");
        var drift = ProxyHealthCheck.checkVersionDrift(proxyInfo);
        assertTrue(drift.contains("pre-versioning"));
    }

    @Test
    void checkVersionDriftReturnsEmptyForNull() {
        assertEquals("", ProxyHealthCheck.checkVersionDrift(null));
    }

    @Test
    void proxyInfoIsLegacyWhenVersionEmpty() {
        assertTrue(new ProxyHealthCheck.ProxyInfo("", "sha", "runtime", "fp", false, true, "").isLegacy());
        assertTrue(new ProxyHealthCheck.ProxyInfo(null, "sha", "runtime", "fp", false, true, "").isLegacy());
        assertFalse(new ProxyHealthCheck.ProxyInfo("1.0", "sha", "runtime", "fp", false, true, "").isLegacy());
    }

    @Test
    void parseProxyInfoDefaultsDnsConfiguredToTrueWhenMissing() {
        var info = ProxyHealthCheck.parseProxyInfo("{\"status\":\"ok\",\"version\":\"0.2.5\"}");
        assertTrue(info.dnsConfigured());
    }

    @Test
    void parseProxyInfoReadsDnsConfiguredFalse() {
        var info = ProxyHealthCheck.parseProxyInfo(
                "{\"status\":\"ok\",\"version\":\"0.2.5\",\"dnsConfigured\":false}");
        assertFalse(info.dnsConfigured());
    }

    @Test
    void parseProxyInfoReadsDnsConfiguredTrue() {
        var info = ProxyHealthCheck.parseProxyInfo(
                "{\"status\":\"ok\",\"version\":\"0.2.5\",\"dnsConfigured\":true}");
        assertTrue(info.dnsConfigured());
    }

    @Test
    void parseProxyInfoReadsAuthError() {
        var info = ProxyHealthCheck.parseProxyInfo(
                "{\"status\":\"ok\",\"version\":\"0.2.5\",\"authError\":\"gcloud token expired\"}");
        assertTrue(info.hasAuthError());
        assertEquals("gcloud token expired", info.authError());
    }

    @Test
    void authRemediationHintDistinguishesVertexFromOauth() {
        var vertex = new ProxyHealthCheck.ProxyInfo("1.0", "", "", "", false, true,
                "gcloud auth print-access-token failed (exit 1): ERROR");
        assertEquals("gcloud auth login", vertex.authRemediationHint());

        var oauth = new ProxyHealthCheck.ProxyInfo("1.0", "", "", "", false, true,
                "Claude OAuth token rejected (HTTP 401).");
        assertEquals("isx init", oauth.authRemediationHint());
    }

    @Test
    void parseProxyInfoNoAuthErrorWhenAbsent() {
        var info = ProxyHealthCheck.parseProxyInfo(
                "{\"status\":\"ok\",\"version\":\"0.2.5\"}");
        assertFalse(info.hasAuthError());
    }

    @Test
    void parseProxyInfoRejectsMalformedDnsConfigured() {
        // String "false" should be rejected (not a boolean), defaulting to false
        var info1 = ProxyHealthCheck.parseProxyInfo(
                "{\"status\":\"ok\",\"version\":\"0.2.5\",\"dnsConfigured\":\"false\"}");
        assertFalse(info1.dnsConfigured());

        // Number 0 should be rejected (not a boolean), defaulting to false
        var info2 = ProxyHealthCheck.parseProxyInfo(
                "{\"status\":\"ok\",\"version\":\"0.2.5\",\"dnsConfigured\":0}");
        assertFalse(info2.dnsConfigured());

        // Number 1 should be rejected (not a boolean), defaulting to false
        var info3 = ProxyHealthCheck.parseProxyInfo(
                "{\"status\":\"ok\",\"version\":\"0.2.5\",\"dnsConfigured\":1}");
        assertFalse(info3.dnsConfigured());
    }

    @Test
    void parsesTheReportedPid() {
        var info = ProxyHealthCheck.parseProxyInfo("{\"status\":\"ok\",\"version\":\"1.0\",\"pid\":4242}");
        assertEquals(4242, info.pid());
    }

    @Test
    void aProxyThatReportsNoPidReadsAsUnknown() {
        // Older proxies, and any response a container gets, have no pid.
        assertEquals(-1, ProxyHealthCheck.parseProxyInfo("{\"status\":\"ok\",\"version\":\"1.0\"}").pid());
        assertEquals(-1, ProxyHealthCheck.parseProxyInfo("not json").pid());
    }

    private static final DriftRestartRecord.Stamp TRIED =
            new DriftRestartRecord.Stamp("0.0.2", "cli7654321", "/opt/isx-proxy", 1000L, 42L);

    private static ProxyHealthCheck.ProxyInfo running(String version, String sha, boolean configDrifted) {
        return new ProxyHealthCheck.ProxyInfo(version, sha, "JVM", "fp", configDrifted, true, "");
    }

    @Test
    void versionDriftRestartsOnceOntoAnInstalledBinary() {
        var report = ProxyHealthCheck.assessDrift(running("0.0.1", "old1234567", false), () -> null);
        assertFalse(report.isEmpty());
        assertNull(report.futileReason());
        assertTrue(report.restartHelps());
    }

    @Test
    void versionDriftThatSurvivedARestartOntoTheSameBinaryDoesNotRestartAgain() {
        // The #798 loop: the installed proxy is not this CLI's build, so every restart
        // brought the same drift straight back.
        var report = ProxyHealthCheck.assessDrift(running("0.0.1", "old1234567", false), () -> TRIED);
        assertFalse(report.restartHelps());
        assertTrue(report.futileReason().contains("/opt/isx-proxy"));
    }

    @Test
    void configDriftAlwaysRestartsWithoutReadingTheRecord() {
        var report = ProxyHealthCheck.assessDrift(running("0.0.1", "old1234567", true),
                () -> fail("config drift must not read the record"));
        assertTrue(report.restartHelps());
    }

    @Test
    void aLegacyProxyAlwaysRestarts() {
        var report = ProxyHealthCheck.assessDrift(running("", "", false), () -> TRIED);
        assertTrue(report.restartHelps());
    }

    @Test
    void noDriftNeverReadsTheRecord() {
        var cli = BuildInfo.instance();
        var report = ProxyHealthCheck.assessDrift(running(cli.version(), cli.gitSha(), false),
                () -> fail("no drift must not read the record"));
        assertTrue(report.isEmpty());
        assertFalse(report.restartHelps());
        assertTrue(ProxyHealthCheck.assessDrift(null, () -> TRIED).isEmpty());
    }
}
