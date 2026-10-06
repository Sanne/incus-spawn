package dev.incusspawn.proxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProxyActivityFetchTest {

    @Test
    void aProxyWithoutActivityIsOutdatedNotMerelyUnavailable() throws Exception {
        assertThrows(ProxyActivity.Outdated.class, () -> fetchAnswering(404));
        // Any other failure may pass: the MCP error code tells a retry it can help.
        var other = assertThrows(ProxyActivity.Unavailable.class, () -> fetchAnswering(503));
        assertFalse(other instanceof ProxyActivity.Outdated);
    }

    private static void fetchAnswering(int status) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/activity", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            ProxyActivity.fetch("127.0.0.1", server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
    }
}
