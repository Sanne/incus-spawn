package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolProxyResolutionTest {

    @Test
    void resolveConfigPathCredential() throws Exception {
        var tool = loadToolSetup("""
                name: gh
                proxy:
                  config-namespace: github
                  configuration:
                    token:
                      config-path: "token"
                      secret: true
                  auth:
                    - domains:
                        - github.com
                      type: bearer
                      token: "${token}"
                """);
        var config = new SpawnConfig();
        config.getGithub().setToken("my-gh-token");

        var resolved = ToolProxyResolver.resolve(config, Map.of("gh", tool));
        assertEquals(1, resolved.size());
        assertEquals("my-gh-token", resolved.get(0).configValues().get("token"));
        assertEquals("Bearer my-gh-token", resolved.get(0).computeHeaderValue());
    }

    @Test
    void resolveValueCredential() throws Exception {
        var tool = loadToolSetup("""
                name: gh
                proxy:
                  config-namespace: github
                  configuration:
                    username:
                      value: "x-access-token"
                    token:
                      config-path: "token"
                      secret: true
                  auth:
                    - domains:
                        - github.com
                      type: basic
                      username: "${username}"
                      password: "${token}"
                """);
        var config = new SpawnConfig();
        config.getGithub().setToken("ghp_test123");

        var resolved = ToolProxyResolver.resolve(config, Map.of("gh", tool));
        assertEquals(1, resolved.size());
        assertEquals("x-access-token", resolved.get(0).configValues().get("username"));
        assertEquals("ghp_test123", resolved.get(0).configValues().get("token"));
        assertNotNull(resolved.get(0).computeHeaderValue());
        assertTrue(resolved.get(0).computeHeaderValue().startsWith("Basic "));
    }

    @Test
    void resolveLiteralUsername() throws Exception {
        var tool = loadToolSetup("""
                name: gh
                proxy:
                  config-namespace: github
                  configuration:
                    token:
                      config-path: "token"
                      secret: true
                  auth:
                    - domains:
                        - github.com
                      type: basic
                      username: "x-access-token"
                      password: "${token}"
                """);
        var config = new SpawnConfig();
        config.getGithub().setToken("ghp_test123");

        var resolved = ToolProxyResolver.resolve(config, Map.of("gh", tool));
        assertEquals(1, resolved.size());
        assertTrue(resolved.get(0).computeHeaderValue().startsWith("Basic "));
    }

    @Test
    void resolveToolCredential() throws Exception {
        var tool = loadToolSetup("""
                name: atlassian-mcp
                proxy:
                  config-namespace: atlassian
                  configuration:
                    email:
                      config-path: "email"
                      description: Atlassian email
                    api-token:
                      config-path: "apiToken"
                      description: API token
                      secret: true
                  auth:
                    - domains:
                        - mcp.atlassian.com
                      type: basic
                      username: "${email}"
                      password: "${api-token}"
                """);
        var config = new SpawnConfig();
        config.setConfigByPath("atlassian.email", "user@test.com");
        config.setConfigByPath("atlassian.apiToken", "secret123");

        var resolved = ToolProxyResolver.resolve(config, Map.of("atlassian-mcp", tool));
        assertEquals(1, resolved.size());
        assertEquals("user@test.com", resolved.get(0).configValues().get("email"));
        assertEquals("secret123", resolved.get(0).configValues().get("api-token"));
    }

    @Test
    void skipEntryWithMissingCredentials() throws Exception {
        var tool = loadToolSetup("""
                name: atlassian-mcp
                proxy:
                  config-namespace: atlassian
                  configuration:
                    email:
                      config-path: "email"
                      description: Atlassian email
                    api-token:
                      config-path: "apiToken"
                      description: API token
                      secret: true
                  auth:
                    - domains:
                        - mcp.atlassian.com
                      type: basic
                      username: "${email}"
                      password: "${api-token}"
                """);
        var config = new SpawnConfig();

        var resolved = ToolProxyResolver.resolve(config, Map.of("atlassian-mcp", tool));
        assertTrue(resolved.isEmpty());
    }

    @Test
    void wildcardAndExactDomainLookup() throws Exception {
        var ghTool = loadToolSetup("""
                name: gh
                proxy:
                  config-namespace: github
                  configuration:
                    token:
                      config-path: "token"
                  auth:
                    - domains:
                        - github.com
                      type: basic
                      username: "${token}"
                      password: "${token}"
                    - domains:
                        - "*.github.com"
                      type: bearer
                      token: "${token}"
                """);
        var config = new SpawnConfig();
        config.getGithub().setToken("ghp_test");

        var resolved = ToolProxyResolver.resolve(config, Map.of("gh", ghTool));
        assertEquals(2, resolved.size());

        var creds = new ProxyCredentials("", "", false, "", "", resolved);
        var proxy = new MitmProxy(null, "127.0.0.1", 18443, 18080, "127.0.0.1", creds);

        var exact = proxy.findToolProxy("github.com");
        assertNotNull(exact);
        assertEquals("basic", exact.auth().getType());

        var wildcard = proxy.findToolProxy("api.github.com");
        assertNotNull(wildcard);
        assertEquals("bearer", wildcard.auth().getType());

        var alsoWildcard = proxy.findToolProxy("codeload.github.com");
        assertNotNull(alsoWildcard);
        assertEquals("bearer", alsoWildcard.auth().getType());

        assertNull(proxy.findToolProxy("notgithub.com"));
    }

    @Test
    void allInterceptedDomainsIncludesToolProxies() throws Exception {
        var tool = loadToolSetup("""
                name: custom
                proxy:
                  config-namespace: custom
                  configuration:
                    token:
                      value: "tok"
                  auth:
                    - domains:
                        - custom.example.com
                      type: bearer
                      token: "${token}"
                """);
        var config = new SpawnConfig();
        var resolved = ToolProxyResolver.resolve(config, Map.of("custom", tool));

        var creds = new ProxyCredentials("", "", false, "", "", resolved);
        var proxy = new MitmProxy(null, "127.0.0.1", 18443, 18080, "127.0.0.1", creds);

        assertTrue(proxy.allInterceptedDomains().contains("custom.example.com"));
        assertTrue(proxy.allInterceptedDomains().contains("api.anthropic.com"));
    }

    @Test
    void pathScopedAuthParsesAndFilters() throws Exception {
        var tool = loadToolSetup("""
                name: develocity-mcp
                proxy:
                  config-namespace: develocity
                  configuration:
                    token:
                      value: "my-token"
                  auth:
                    - domains:
                        - develocity.example.com
                      paths:
                        - /mcp
                      type: bearer
                      token: "${token}"
                """);
        var config = new SpawnConfig();
        var resolved = ToolProxyResolver.resolve(config, Map.of("develocity-mcp", tool));
        assertEquals(1, resolved.size());

        var tp = resolved.get(0);
        assertEquals("develocity.example.com", tp.domain());
        assertNotNull(tp.auth().getPaths());
        assertEquals(1, tp.auth().getPaths().size());
        assertEquals("/mcp", tp.auth().getPaths().get(0));

        var creds = new ProxyCredentials("", "", false, "", "", resolved);
        var proxy = new MitmProxy(null, "127.0.0.1", 18443, 18080, "127.0.0.1", creds);

        assertNotNull(proxy.findToolProxy("develocity.example.com", "/mcp"),
                "should match exact path");
        assertNotNull(proxy.findToolProxy("develocity.example.com", "/mcp/sub"),
                "should match subpath");
        assertNull(proxy.findToolProxy("develocity.example.com", "/api/build-scan"),
                "should not match unscoped path");
        assertNull(proxy.findToolProxy("develocity.example.com", "/"),
                "should not match root");
    }

    @Test
    void pathScopedAuthDomainStillIntercepted() throws Exception {
        var tool = loadToolSetup("""
                name: develocity-mcp
                proxy:
                  config-namespace: develocity
                  configuration:
                    token:
                      value: "my-token"
                  auth:
                    - domains:
                        - develocity.example.com
                      paths:
                        - /mcp
                      type: bearer
                      token: "${token}"
                """);
        var config = new SpawnConfig();
        var resolved = ToolProxyResolver.resolve(config, Map.of("develocity-mcp", tool));

        var creds = new ProxyCredentials("", "", false, "", "", resolved);
        var proxy = new MitmProxy(null, "127.0.0.1", 18443, 18080, "127.0.0.1", creds);

        assertTrue(proxy.allInterceptedDomains().contains("develocity.example.com"),
                "domain must be intercepted even when paths are scoped");
        assertNotNull(proxy.findToolProxy("develocity.example.com"),
                "domain lookup must still find the proxy entry for generic queries");
    }

    @Test
    void noPathScopeMatchesEverything() throws Exception {
        var tool = loadToolSetup("""
                name: gh
                proxy:
                  config-namespace: github
                  configuration:
                    token:
                      config-path: "token"
                  auth:
                    - domains:
                        - github.com
                      type: bearer
                      token: "${token}"
                """);
        var config = new SpawnConfig();
        config.getGithub().setToken("ghp_test");
        var resolved = ToolProxyResolver.resolve(config, Map.of("gh", tool));

        var creds = new ProxyCredentials("", "", false, "", "", resolved);
        var proxy = new MitmProxy(null, "127.0.0.1", 18443, 18080, "127.0.0.1", creds);

        assertNotNull(proxy.findToolProxy("github.com", "/any/path"),
                "no paths = match everything");
        assertNotNull(proxy.findToolProxy("github.com", "/"),
                "no paths = match root");
    }

    @Test
    void twoToolsSameDomainDifferentPaths() throws Exception {
        var mcpTool = loadToolSetup("""
                name: develocity-mcp
                proxy:
                  config-namespace: develocity
                  configuration:
                    token:
                      value: "mcp-token"
                  auth:
                    - domains:
                        - develocity.example.com
                      paths:
                        - /mcp
                      type: bearer
                      token: "${token}"
                """);
        var cacheTool = loadToolSetup("""
                name: develocity-cache
                proxy:
                  config-namespace: devcache
                  configuration:
                    token:
                      value: "cache-token"
                  auth:
                    - domains:
                        - develocity.example.com
                      paths:
                        - /cache
                      type: bearer
                      token: "${token}"
                """);
        var config = new SpawnConfig();
        var resolved = new java.util.ArrayList<>(ToolProxyResolver.resolve(config,
                Map.of("develocity-mcp", mcpTool, "develocity-cache", cacheTool)));

        var creds = new ProxyCredentials("", "", false, "", "", resolved);
        var proxy = new MitmProxy(null, "127.0.0.1", 18443, 18080, "127.0.0.1", creds);

        var mcp = proxy.findToolProxy("develocity.example.com", "/mcp");
        assertNotNull(mcp);
        assertEquals("Bearer mcp-token", mcp.computeHeaderValue());

        var cache = proxy.findToolProxy("develocity.example.com", "/cache");
        assertNotNull(cache);
        assertEquals("Bearer cache-token", cache.computeHeaderValue());

        assertNull(proxy.findToolProxy("develocity.example.com", "/api"),
                "no entry matches /api");
    }

    @Test
    void pathScopedAndUnscopedSameDomain() throws Exception {
        var mcpTool = loadToolSetup("""
                name: develocity-mcp
                proxy:
                  config-namespace: develocity
                  configuration:
                    token:
                      value: "mcp-token"
                  auth:
                    - domains:
                        - develocity.example.com
                      paths:
                        - /mcp
                      type: bearer
                      token: "${token}"
                """);
        var broadTool = loadToolSetup("""
                name: develocity-broad
                proxy:
                  config-namespace: devbroad
                  configuration:
                    token:
                      value: "broad-token"
                  auth:
                    - domains:
                        - develocity.example.com
                      type: bearer
                      token: "${token}"
                """);
        var config = new SpawnConfig();
        var resolved = new java.util.ArrayList<>(ToolProxyResolver.resolve(config,
                Map.of("develocity-mcp", mcpTool, "develocity-broad", broadTool)));

        var creds = new ProxyCredentials("", "", false, "", "", resolved);
        var proxy = new MitmProxy(null, "127.0.0.1", 18443, 18080, "127.0.0.1", creds);

        var mcp = proxy.findToolProxy("develocity.example.com", "/mcp");
        assertNotNull(mcp);
        assertEquals("Bearer mcp-token", mcp.computeHeaderValue(),
                "path-scoped entry wins on its path");

        var api = proxy.findToolProxy("develocity.example.com", "/api");
        assertNotNull(api);
        assertEquals("Bearer broad-token", api.computeHeaderValue(),
                "unscoped entry serves the rest");
    }

    @Test
    void wildcardScopedAndUnscopedDifferentTools() throws Exception {
        var scopedTool = loadToolSetup("""
                name: scoped-tool
                proxy:
                  config-namespace: scoped
                  configuration:
                    token:
                      value: "scoped-token"
                  auth:
                    - domains:
                        - "*.example.com"
                      paths:
                        - /mcp
                      type: bearer
                      token: "${token}"
                """);
        var broadTool = loadToolSetup("""
                name: broad-tool
                proxy:
                  config-namespace: broad
                  configuration:
                    token:
                      value: "broad-token"
                  auth:
                    - domains:
                        - "*.example.com"
                      type: bearer
                      token: "${token}"
                """);
        var config = new SpawnConfig();
        var resolved = new java.util.ArrayList<>(ToolProxyResolver.resolve(config,
                Map.of("scoped-tool", scopedTool, "broad-tool", broadTool)));

        var creds = new ProxyCredentials("", "", false, "", "", resolved);
        var proxy = new MitmProxy(null, "127.0.0.1", 18443, 18080, "127.0.0.1", creds);

        var mcp = proxy.findToolProxy("api.example.com", "/mcp");
        assertNotNull(mcp, "scoped entry serves its path");
        assertEquals("Bearer scoped-token", mcp.computeHeaderValue());

        var api = proxy.findToolProxy("api.example.com", "/api");
        assertNotNull(api, "unscoped entry serves other paths");
        assertEquals("Bearer broad-token", api.computeHeaderValue());
    }

    @Test
    void wildcardScopedAndUnscopedReverseOrder() throws Exception {
        var broadTool = loadToolSetup("""
                name: broad-tool
                proxy:
                  config-namespace: broad
                  configuration:
                    token:
                      value: "broad-token"
                  auth:
                    - domains:
                        - "*.example.com"
                      type: bearer
                      token: "${token}"
                """);
        var scopedTool = loadToolSetup("""
                name: scoped-tool
                proxy:
                  config-namespace: scoped
                  configuration:
                    token:
                      value: "scoped-token"
                  auth:
                    - domains:
                        - "*.example.com"
                      paths:
                        - /mcp
                      type: bearer
                      token: "${token}"
                """);
        var config = new SpawnConfig();
        var resolved = new java.util.ArrayList<>(ToolProxyResolver.resolve(config,
                Map.of("broad-tool", broadTool, "scoped-tool", scopedTool)));

        var creds = new ProxyCredentials("", "", false, "", "", resolved);
        var proxy = new MitmProxy(null, "127.0.0.1", 18443, 18080, "127.0.0.1", creds);

        var mcp = proxy.findToolProxy("api.example.com", "/mcp");
        assertNotNull(mcp, "scoped entry wins on its path regardless of registration order");
        assertEquals("Bearer scoped-token", mcp.computeHeaderValue());

        var api = proxy.findToolProxy("api.example.com", "/api");
        assertNotNull(api, "unscoped entry serves other paths");
        assertEquals("Bearer broad-token", api.computeHeaderValue());
    }

    private static ToolSetup loadToolSetup(String yaml) throws Exception {
        var def = ToolDef.loadFromStream(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return new YamlToolSetup(def);
    }
}
