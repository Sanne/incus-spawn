package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqValues;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.lifecycle.TempHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolProxyResolverTest {

    @Test
    @ExtendWith(TempHome.class)
    void proxyToolSetupsReadTheSearchPathsOfTheConfigTheyAreGiven() throws Exception {
        // #890: the loader re-read config.yaml for its search paths, so the proxy served the
        // tools of a later read than the one it fingerprinted. Here config.yaml has none.
        var home = Path.of(System.getProperty("user.home"));
        var tools = Files.createDirectories(home.resolve("dotfiles/tools"));
        Files.writeString(tools.resolve("foo.yaml"), """
                name: foo
                proxy:
                  configuration:
                    token:
                      value: "t"
                  auth:
                    - domains:
                        - foo.example
                      type: bearer
                      token: "${token}"
                """);
        var config = new SpawnConfig();
        config.setSearchPaths(List.of(home.resolve("dotfiles").toString()));

        assertTrue(ToolProxyResolver.proxyToolSetups(config).containsKey("foo"));
    }

    @Test
    void fingerprintEmptyListReturnsEmpty() {
        assertEquals("", ToolProxyResolver.fingerprint(List.of()));
        assertEquals("", ToolProxyResolver.fingerprint(null));
    }

    @Test
    void fingerprintIsDeterministic() {
        var proxies = List.of(
                new ResolvedToolProxy("gh", "github.com",
                        authDef("bearer"), Map.of("token", "my-token")),
                new ResolvedToolProxy("openai", "api.openai.com",
                        authDef("bearer"), Map.of("api-key", "sk-123"))
        );
        var fp1 = ToolProxyResolver.fingerprint(proxies);
        var fp2 = ToolProxyResolver.fingerprint(proxies);
        assertEquals(fp1, fp2);
        assertFalse(fp1.isEmpty());
        assertEquals(64, fp1.length());
    }

    @Test
    void fingerprintChangesWhenCredentialChanges() {
        var proxies1 = List.of(
                new ResolvedToolProxy("gh", "github.com",
                        authDef("bearer"), Map.of("token", "token-A"))
        );
        var proxies2 = List.of(
                new ResolvedToolProxy("gh", "github.com",
                        authDef("bearer"), Map.of("token", "token-B"))
        );
        assertNotEquals(ToolProxyResolver.fingerprint(proxies1),
                ToolProxyResolver.fingerprint(proxies2));
    }

    @Test
    void fingerprintChangesWhenDomainChanges() {
        var proxies1 = List.of(
                new ResolvedToolProxy("gh", "github.com",
                        authDef("bearer"), Map.of("token", "t"))
        );
        var proxies2 = List.of(
                new ResolvedToolProxy("gh", "api.github.com",
                        authDef("bearer"), Map.of("token", "t"))
        );
        assertNotEquals(ToolProxyResolver.fingerprint(proxies1),
                ToolProxyResolver.fingerprint(proxies2));
    }

    @Test
    void fingerprintOrderIndependent() {
        var a = new ResolvedToolProxy("gh", "github.com",
                authDef("bearer"), Map.of("token", "t1"));
        var b = new ResolvedToolProxy("openai", "api.openai.com",
                authDef("bearer"), Map.of("key", "t2"));
        assertEquals(ToolProxyResolver.fingerprint(List.of(a, b)),
                ToolProxyResolver.fingerprint(List.of(b, a)));
    }

    @Test
    void fingerprintChangesWhenToolAdded() {
        var a = new ResolvedToolProxy("gh", "github.com",
                authDef("bearer"), Map.of("token", "t1"));
        var b = new ResolvedToolProxy("openai", "api.openai.com",
                authDef("bearer"), Map.of("key", "t2"));
        assertNotEquals(ToolProxyResolver.fingerprint(List.of(a)),
                ToolProxyResolver.fingerprint(List.of(a, b)));
    }

    // --- navigateConfigPath tests ---

    @Test
    void navigateConfigPathSimpleDotPath() {
        var tree = JqValues.parse("{\"github\":{\"token\":\"ghp_abc123\"}}");
        assertEquals("ghp_abc123", ToolProxyResolver.navigateConfigPath(tree, "github.token"));
    }

    @Test
    void navigateConfigPathTopLevelKey() {
        var tree = JqValues.parse("{\"apiKey\":\"sk-test\"}");
        assertEquals("sk-test", ToolProxyResolver.navigateConfigPath(tree, "apiKey"));
    }

    @Test
    void navigateConfigPathMissingIntermediateReturnsEmpty() {
        var tree = JqValues.parse("{\"other\":\"value\"}");
        assertEquals("", ToolProxyResolver.navigateConfigPath(tree, "github.token"));
    }

    @Test
    void navigateConfigPathMissingLeafReturnsEmpty() {
        var tree = JqValues.parse("{\"github\":{\"user\":\"octocat\"}}");
        assertEquals("", ToolProxyResolver.navigateConfigPath(tree, "github.token"));
    }

    @Test
    void navigateConfigPathNumberLeafReturnsText() {
        var tree = JqValues.parse("{\"github\":{\"count\":42}}");
        assertEquals("42", ToolProxyResolver.navigateConfigPath(tree, "github.count"));
    }

    @Test
    void navigateConfigPathBooleanLeafReturnsText() {
        var tree = JqValues.parse("{\"bob\":{\"licenseConsent\":true}}");
        assertEquals("true", ToolProxyResolver.navigateConfigPath(tree, "bob.licenseConsent"));
    }

    @Test
    void navigateConfigPathNullTreeReturnsEmpty() {
        assertEquals("", ToolProxyResolver.navigateConfigPath(null, "github.token"));
    }

    @Test
    void navigateConfigPathObjectLeafReturnsEmpty() {
        var tree = JqValues.parse("{\"github\":{\"nested\":{\"deep\":\"value\"}}}");
        assertEquals("", ToolProxyResolver.navigateConfigPath(tree, "github.nested"));
    }

    // --- resolveDomains tests ---

    @Test
    void resolveDomainsStaticPassthrough() {
        var result = ToolProxyResolver.resolveDomains(
                List.of("api.example.com", "cdn.example.com"), Map.of());
        assertEquals(List.of("api.example.com", "cdn.example.com"), result);
    }

    @Test
    void resolveDomainsSingleReference() {
        var result = ToolProxyResolver.resolveDomains(
                List.of("${site}"), Map.of("site", "myorg.atlassian.net"));
        assertEquals(List.of("myorg.atlassian.net"), result);
    }

    @Test
    void resolveDomainsCommaSeparatedValue() {
        var result = ToolProxyResolver.resolveDomains(
                List.of("${instances}"),
                Map.of("instances", "dev.example.com, prod.example.com"));
        assertEquals(List.of("dev.example.com", "prod.example.com"), result);
    }

    @Test
    void resolveDomainsMixedStaticAndDynamic() {
        var result = ToolProxyResolver.resolveDomains(
                List.of("api.fixed.com", "${site}"),
                Map.of("site", "custom.example.com"));
        assertEquals(List.of("api.fixed.com", "custom.example.com"), result);
    }

    @Test
    void resolveDomainsUnresolvedReferenceDropped() {
        var result = ToolProxyResolver.resolveDomains(
                List.of("api.fixed.com", "${missing}"), Map.of());
        assertEquals(List.of("api.fixed.com"), result);
    }

    @Test
    void resolveDomainsNullAndBlankSkipped() {
        var result = ToolProxyResolver.resolveDomains(
                List.of("api.example.com", "", "   "), Map.of());
        assertEquals(List.of("api.example.com"), result);
    }

    @Test
    void resolveDomainsNullListReturnsEmpty() {
        assertEquals(List.of(), ToolProxyResolver.resolveDomains(null, Map.of()));
    }

    @Test
    void resolveDomainsPrefixSuffixAroundReference() {
        var result = ToolProxyResolver.resolveDomains(
                List.of("*.${base}"), Map.of("base", "example.com"));
        assertEquals(List.of("*.example.com"), result);
    }

    private static ToolDef.AuthDef authDef(String type) {
        var auth = new ToolDef.AuthDef();
        auth.setType(type);
        return auth;
    }
}
