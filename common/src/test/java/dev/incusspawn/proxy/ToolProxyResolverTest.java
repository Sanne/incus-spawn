package dev.incusspawn.proxy;

import dev.incusspawn.config.Jjq;
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
    void aChatGptSessionsApiKeyEntryIsNotAGap() throws Exception {
        // #1332: the account's credential is a host-side session, not the key the codex tool's
        // entry names -- isx doctor and the proxy's startup warning read this
        var chatgpt = Jjq.spawnConfig("""
                openai:
                  accounts:
                    default: { type: chatgpt }
                    work: {}
                  default: default
                """).tree();
        assertTrue(ToolProxyResolver.findUnresolved(chatgpt,
                Map.of("codex", new dev.incusspawn.tool.CodexSetup())).isEmpty(),
                "a ChatGPT account is a configured credential");

        var keyed = Jjq.spawnConfig("""
                openai:
                  accounts:
                    default: { apiKey: "sk-x" }
                  default: default
                """).tree();
        assertTrue(ToolProxyResolver.findUnresolved(keyed,
                Map.of("codex", new dev.incusspawn.tool.CodexSetup())).isEmpty());

        var none = Jjq.spawnConfig("""
                openai:
                  accounts:
                    default: { type: chatgpt }
                  default: default
                bob:
                  apiKey: ""
                """).tree();
        // The codex entry is not a gap; bob's missing key is, and stays named
        var gaps = ToolProxyResolver.findUnresolved(none, Map.of(
                "codex", new dev.incusspawn.tool.CodexSetup(),
                "bob", new dev.incusspawn.tool.BobSetup()));
        assertTrue(gaps.stream().noneMatch(g -> g.namespace().equals("openai")), gaps.toString());

        // The branch-time check asks the same, against the selection a branch would pin
        // (CredentialCheck.missingSecrets): no gap for the session's account, one for a pin to
        // an account that is neither
        var tools = Map.<String, dev.incusspawn.tool.ToolSetup>of("codex", new dev.incusspawn.tool.CodexSetup());
        assertTrue(ToolProxyResolver.missingSecrets(chatgpt, tools, java.util.List.of("codex"),
                Map.of()).isEmpty(), "the session is the credential");
        var pinnedAway = ToolProxyResolver.missingSecrets(chatgpt, tools, java.util.List.of("codex"),
                Map.of("openai", "work"));
        assertEquals(1, pinnedAway.size(), pinnedAway.toString());
        assertEquals("OpenAI API key", pinnedAway.getFirst().description());
    }

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
                List.of("mcp.${base}"), Map.of("base", "example.com"));
        assertEquals(List.of("mcp.example.com"), result);
    }

    @Test
    void aConfiguredDomainIsNeverAWildcard() {
        assertEquals(List.of(), ToolProxyResolver.resolveDomains(List.of("*.${base}"), Map.of("base", "example.com")),
                "not even when the definition writes the '*.'");
        assertEquals(List.of(), ToolProxyResolver.resolveDomains(List.of("${d}"), Map.of("d", "*.example.com")));
    }

    @Test
    void aConfiguredValueThatIsNotAHostNameIsDropped() {
        var result = ToolProxyResolver.resolveDomains(List.of("${instances}"),
                Map.of("instances", "good.example.com, evil.example.com/path, a b.example.com, *.example.com, localhost"));
        assertEquals(List.of("good.example.com"), result);
    }

    @Test
    void aConfiguredValueIsNotSearchedForReferences() {
        var result = ToolProxyResolver.resolveDomains(List.of("${a}.example.com"),
                Map.of("a", "${b}", "b", "x"));
        assertEquals(List.of(), result, "'${b}.example.com' is no host name, never 'x.example.com'");
    }

    @Test
    void aConfiguredDomainIsLowerCased() {
        assertEquals(List.of("ge.example.com"),
                ToolProxyResolver.resolveDomains(List.of("${d}"), Map.of("d", "GE.Example.COM")));
    }

    @Test
    void aConfiguredDomainNeverLandsOnOneAlreadyServed() {
        var gh = new ToolDef.ProxyDef();
        var auth = authDef("bearer");
        auth.setDomains(List.of("github.com", "*.githubusercontent.com"));
        gh.setAuth(List.of(auth));
        var taken = ToolProxyResolver.takenDomains(Map.of("gh", new ProxyOnly(gh)));
        var result = ToolProxyResolver.resolveDomains("develocity", List.of("${d}"), Map.of("d",
                "github.com, api.github.com, raw.githubusercontent.com, api.anthropic.com, repo1.maven.org,"
                        + " chatgpt.com, oauth2.googleapis.com, mcp.isx.internal, controller.internal, ge.example.com"),
                taken);
        assertEquals(List.of("ge.example.com"), result);
    }

    @Test
    void aCommaInAValueWithTextAroundItIsNoHostName() {
        assertEquals(List.of(), ToolProxyResolver.resolveDomains(List.of("${sub}.example.com"),
                Map.of("sub", "evil.org,dev")), "never 'evil.org', outside the definition's suffix");
        assertEquals(List.of("a.com", "b.com"),
                ToolProxyResolver.resolveDomains(List.of("${d}"), Map.of("d", "a.com, b.com")));
    }

    @Test
    void aConfiguredIpAddressIsNoHostName() {
        assertEquals(List.of(), ToolProxyResolver.resolveDomains(List.of("${d}"),
                Map.of("d", "169.254.169.254, 10.0.0.5")));
    }

    /** A tool that declares only a proxy, for {@link ToolProxyResolver#takenDomains}. */
    private record ProxyOnly(ToolDef.ProxyDef proxy) implements dev.incusspawn.tool.ToolSetup {
        @Override public String name() { return "proxy-only"; }
        @Override public void install(dev.incusspawn.incus.Container container, Map<String, String> params) {}
    }

    private static ToolDef.AuthDef authDef(String type) {
        var auth = new ToolDef.AuthDef();
        auth.setType(type);
        return auth;
    }
}
