package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who gets the proxy's PID in {@code /health}: the host, never a container. */
class HealthCallerTest {

    @Test
    void theHostOverTheBridgeAddressIsTheHost() {
        // The CLI on Linux connects to the bridge gateway, so the source is that same address.
        assertTrue(MitmProxy.isHostCaller("10.166.11.1", "10.166.11.1"));
    }

    @Test
    void loopbackIsTheHost() {
        // macOS: the CLI reaches the proxy on 127.0.0.1.
        assertTrue(MitmProxy.isHostCaller("127.0.0.1", "127.0.0.1"));
        assertTrue(MitmProxy.isHostCaller("::1", "::1"));
    }

    @Test
    void aContainerIsNot() {
        assertFalse(MitmProxy.isHostCaller("10.166.11.5", "10.166.11.1"));
    }

    @Test
    void unknownIsNot() {
        assertFalse(MitmProxy.isHostCaller(null, "10.166.11.1"));
        assertFalse(MitmProxy.isHostCaller("", "10.166.11.1"));
    }

    @Test
    void activityIsTheHostsAndNothingOnTheHealthServerWrites() {
        var get = io.vertx.core.http.HttpMethod.GET;
        var post = io.vertx.core.http.HttpMethod.POST;
        assertEquals(MitmProxy.HealthRoute.CREDENTIAL_PROOFS, MitmProxy.healthRoute(get, "/credential-proofs", () -> true));
        assertEquals(MitmProxy.HealthRoute.CREDENTIAL_PROOFS,
                MitmProxy.healthRoute(io.vertx.core.http.HttpMethod.HEAD, "/credential-proofs", () -> true));
        assertEquals(MitmProxy.HealthRoute.NOT_FOUND, MitmProxy.healthRoute(get, "/credential-proofs", () -> false));
        assertEquals(MitmProxy.HealthRoute.NOT_FOUND, MitmProxy.healthRoute(post, "/credential-proofs", () -> false));
        assertEquals(MitmProxy.HealthRoute.METHOD_NOT_ALLOWED, MitmProxy.healthRoute(post, "/credential-proofs", () -> true));
        assertEquals(MitmProxy.HealthRoute.ACTIVITY, MitmProxy.healthRoute(get, "/activity", () -> true));
        assertEquals(MitmProxy.HealthRoute.NOT_FOUND, MitmProxy.healthRoute(get, "/activity", () -> false));
        // A container learns nothing of the path, whatever it sends
        assertEquals(MitmProxy.HealthRoute.NOT_FOUND, MitmProxy.healthRoute(post, "/activity", () -> false));
        assertEquals(MitmProxy.HealthRoute.METHOD_NOT_ALLOWED, MitmProxy.healthRoute(post, "/activity", () -> true));
        assertEquals(MitmProxy.HealthRoute.HEALTH, MitmProxy.healthRoute(get, "/health", () -> false));
        assertEquals(MitmProxy.HealthRoute.HEALTH, MitmProxy.healthRoute(io.vertx.core.http.HttpMethod.HEAD, "/health", () -> false),
                "a probe's HEAD reads too");
        assertEquals(MitmProxy.HealthRoute.METHOD_NOT_ALLOWED,
                MitmProxy.healthRoute(io.vertx.core.http.HttpMethod.DELETE, "/health", () -> true));
        assertEquals(MitmProxy.HealthRoute.NOT_FOUND, MitmProxy.healthRoute(get, null, () -> true));
    }

    /**
     * A reason {@code /health} carries may hold any control character. RFC 8259 forbids each one raw
     * inside a string, so a strict reader of {@code /health}, such as a script's, would reject it.
     */
    @Test
    void everyControlCharacterIsEscapedSoStrictJsonReadsTheText() {
        var text = "line one\nline\u0001two\u001f \"quoted\" \\ end";
        var escaped = MitmProxy.escapeJson(text);
        assertTrue(escaped.chars().noneMatch(c -> c < 0x20), "no control character left raw: " + escaped);
        var info = ProxyHealthCheck.parseProxyInfo("{\"status\":\"ok\",\"version\":\"1\",\"authError\":\""
                + escaped + "\"}");
        assertEquals(text, info.authError(), "and the CLI reads it back unchanged");
    }
}
