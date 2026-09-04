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

    private static ToolSetup loadToolSetup(String yaml) throws Exception {
        var def = ToolDef.loadFromStream(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return new YamlToolSetup(def);
    }
}
