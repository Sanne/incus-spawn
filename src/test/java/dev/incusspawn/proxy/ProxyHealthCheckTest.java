package dev.incusspawn.proxy;

import com.sun.net.httpserver.HttpServer;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProxyHealthCheckTest {

    private static final IncusClient.ExecResult OK_EMPTY = new IncusClient.ExecResult(0, "", "");

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
        var incus = mock(IncusClient.class);
        when(incus.exec("network", "get", "incusbr0", "ipv4.address"))
                .thenReturn(new IncusClient.ExecResult(0, "10.0.0.1/24", ""));
        when(incus.exec("network", "get", "incusbr0", "raw.dnsmasq"))
                .thenReturn(OK_EMPTY);

        var status = ProxyHealthCheck.check(incus);
        assertEquals(ProxyHealthCheck.ProxyStatus.NOT_RUNNING, status);
    }

    @Test
    void checkReturnsStaleDnsWhenDnsOverridesPresent() {
        var incus = mock(IncusClient.class);
        when(incus.exec("network", "get", "incusbr0", "ipv4.address"))
                .thenReturn(new IncusClient.ExecResult(0, "10.0.0.1/24", ""));
        when(incus.exec("network", "get", "incusbr0", "raw.dnsmasq"))
                .thenReturn(new IncusClient.ExecResult(0,
                        "address=/api.anthropic.com/10.0.0.1\naddress=/github.com/10.0.0.1", ""));

        var status = ProxyHealthCheck.check(incus);
        assertEquals(ProxyHealthCheck.ProxyStatus.STALE_DNS, status);
    }

    @Test
    void formatErrorContainsActionableCommand() {
        var notRunning = ProxyHealthCheck.formatError(ProxyHealthCheck.ProxyStatus.NOT_RUNNING);
        assertTrue(notRunning.contains("isx proxy"));
        assertTrue(notRunning.contains("not running"));

        var staleDns = ProxyHealthCheck.formatError(ProxyHealthCheck.ProxyStatus.STALE_DNS);
        assertTrue(staleDns.contains("isx proxy"));
        assertTrue(staleDns.contains("Stale DNS"));
    }

    @Test
    void formatErrorReturnsEmptyForRunning() {
        assertEquals("", ProxyHealthCheck.formatError(ProxyHealthCheck.ProxyStatus.RUNNING));
    }
}
