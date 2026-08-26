package dev.incusspawn.proxy;

import dev.incusspawn.tool.ToolDef;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResolvedToolProxyTest {

    @Test
    void basicAuthProducesBase64Header() {
        var auth = new ToolDef.AuthDef();
        auth.setType("basic");
        auth.setUsername("${email}");
        auth.setPassword("${api-token}");

        var proxy = new ResolvedToolProxy("test", "api.example.com", auth,
                Map.of("email", "user@test.com", "api-token", "secret"));

        assertEquals("Authorization", proxy.headerName());
        var expected = "Basic " + Base64.getEncoder().encodeToString("user@test.com:secret".getBytes());
        assertEquals(expected, proxy.computeHeaderValue());
    }

    @Test
    void bearerAuthProducesTokenHeader() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("${api-key}");

        var proxy = new ResolvedToolProxy("test", "api.example.com", auth,
                Map.of("api-key", "my-secret-token"));

        assertEquals("Authorization", proxy.headerName());
        assertEquals("Bearer my-secret-token", proxy.computeHeaderValue());
    }

    @Test
    void headerAuthUsesCustomNameAndTemplate() {
        var auth = new ToolDef.AuthDef();
        auth.setType("header");
        auth.setName("X-Custom-Auth");
        auth.setValue("token-${secret}");

        var proxy = new ResolvedToolProxy("test", "api.example.com", auth,
                Map.of("secret", "abc123"));

        assertEquals("X-Custom-Auth", proxy.headerName());
        assertEquals("token-abc123", proxy.computeHeaderValue());
    }

    @Test
    void bearerWithBlankTokenReturnsNull() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("${api-key}");

        var proxy = new ResolvedToolProxy("test", "api.example.com", auth,
                Map.of("api-key", ""));

        assertNull(proxy.computeHeaderValue());
    }

    @Test
    void basicAuthWithLiteralUsername() {
        var auth = new ToolDef.AuthDef();
        auth.setType("basic");
        auth.setUsername("x-access-token");
        auth.setPassword("${token}");

        var proxy = new ResolvedToolProxy("test", "github.com", auth,
                Map.of("token", "ghp_abc123"));

        var expected = "Basic " + Base64.getEncoder().encodeToString("x-access-token:ghp_abc123".getBytes());
        assertEquals(expected, proxy.computeHeaderValue());
    }

    @Test
    void nullAuthReturnsNull() {
        var proxy = new ResolvedToolProxy("test", "api.example.com", null, Map.of());
        assertNull(proxy.headerName());
        assertNull(proxy.computeHeaderValue());
    }
}
