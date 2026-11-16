package dev.incusspawn.proxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void upstreamRateLimitsReadBackAsTheProxyServedThem() {
        var t = new ProxyActivity.Throttle(7, 3, Instant.ofEpochMilli(1000), Instant.ofEpochMilli(2000), true);
        var read = ProxyActivity.parse(new ProxyActivity(Map.of()).throttledBy(Map.of("repo.example", t)).toJson());
        assertEquals(Map.of("repo.example", t), read.throttled());
        // A proxy older than #2303 reports none
        assertEquals(Map.of(), ProxyActivity.parse(new ProxyActivity(Map.of()).toJson().replace("\"throttled\":{},", "")).throttled());
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
