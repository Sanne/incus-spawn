package dev.incusspawn.proxy;

import dev.incusspawn.tool.ToolDef;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
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

    @Test
    void matchesPathWithNoPaths() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        assertTrue(proxy.matchesPath("/anything"));
        assertTrue(proxy.matchesPath("/foo/bar"));
        assertTrue(proxy.matchesPath("/"));
    }

    @Test
    void matchesPathExact() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        auth.setPaths(List.of("/mcp"));
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        assertTrue(proxy.matchesPath("/mcp"));
        assertTrue(proxy.matchesPath("/mcp/"));
        assertTrue(proxy.matchesPath("/mcp/messages"));
        assertFalse(proxy.matchesPath("/mcpadmin"));
        assertFalse(proxy.matchesPath("/api/build-scan"));
        assertFalse(proxy.matchesPath("/"));
    }

    @Test
    void matchesPathWithQueryString() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        auth.setPaths(List.of("/mcp"));
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        assertTrue(proxy.matchesPath("/mcp?query=1"));
        assertTrue(proxy.matchesPath("/mcp/sub?query=1"));
        assertFalse(proxy.matchesPath("/api?path=/mcp"));
    }

    @Test
    void matchesPathMultiplePrefixes() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        auth.setPaths(List.of("/mcp", "/api/v1"));
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        assertTrue(proxy.matchesPath("/mcp"));
        assertTrue(proxy.matchesPath("/api/v1"));
        assertTrue(proxy.matchesPath("/api/v1/data"));
        assertFalse(proxy.matchesPath("/api/v2"));
        assertFalse(proxy.matchesPath("/other"));
    }

    @Test
    void matchesPathNullAuth() {
        var proxy = new ResolvedToolProxy("test", "example.com", null, Map.of());
        assertTrue(proxy.matchesPath("/anything"));
    }

    @Test
    void matchesPathTrailingSlashPrefix() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        auth.setPaths(List.of("/mcp/"));
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        assertTrue(proxy.matchesPath("/mcp"), "trailing slash normalized: exact match");
        assertTrue(proxy.matchesPath("/mcp/"), "trailing slash normalized: with slash");
        assertTrue(proxy.matchesPath("/mcp/messages"), "trailing slash normalized: subpath");
        assertFalse(proxy.matchesPath("/mcpadmin"), "trailing slash normalized: no boundary");
    }

    @Test
    void matchesPathRootScope() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        auth.setPaths(List.of("/"));
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        assertTrue(proxy.matchesPath("/"), "root scope matches root");
        assertTrue(proxy.matchesPath("/foo"), "root scope matches any path");
        assertTrue(proxy.matchesPath("/foo/bar"), "root scope matches nested path");
    }
}
