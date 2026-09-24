package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolSetup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The point of the whole feature: two instances pinned to different accounts must resolve to
 * different real credentials from the one shared proxy.
 */
class PerInstanceCredentialsTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final String TWO_CLAUDE_ACCOUNTS = """
            claude:
              accounts:
                personal:
                  type: api-key
                  apiKey: "sk-ant-api03-personal"
                work:
                  type: api-key
                  apiKey: "sk-ant-api03-work"
              default: personal
            """;

    @Test
    void selectionPicksTheNamedClaudeAccount() throws Exception {
        var config = YAML.readValue(TWO_CLAUDE_ACCOUNTS, SpawnConfig.class);

        assertEquals("sk-ant-api03-personal",
                ProxyCredentials.forAccounts(config, Map.of()).anthropicApiKey());
        assertEquals("sk-ant-api03-work",
                ProxyCredentials.forAccounts(config, Map.of("claude", "work")).anthropicApiKey());
        assertEquals("sk-ant-api03-personal",
                ProxyCredentials.forAccounts(config, Map.of("claude", "personal")).anthropicApiKey());
    }

    @Test
    void selectionCanPickAVertexAccountAlongsideAnApiKeyDefault() throws Exception {
        var config = YAML.readValue("""
                claude:
                  accounts:
                    console:
                      type: api-key
                      apiKey: "sk-ant-api03-xyz"
                    gcp:
                      type: vertex
                      cloudMlRegion: europe-west1
                      vertexProjectId: acme-prod
                  default: console
                """, SpawnConfig.class);

        var vertex = ProxyCredentials.forAccounts(config, Map.of("claude", "gcp"));
        assertTrue(vertex.useVertex());
        assertEquals("europe-west1", vertex.vertexRegion());
        assertEquals("acme-prod", vertex.vertexProjectId());
        assertEquals("", vertex.anthropicApiKey());

        // The default is untouched by another instance's choice.
        assertFalse(ProxyCredentials.fromConfig(config).useVertex());
    }

    /** Fail closed: a pin to a deleted account must not quietly serve the default. */
    @Test
    void pinningToAMissingAccountThrowsRatherThanFallingBack() throws Exception {
        var config = YAML.readValue(TWO_CLAUDE_ACCOUNTS, SpawnConfig.class);
        var e = assertThrows(AccountResolver.UnknownAccountException.class,
                () -> ProxyCredentials.forAccounts(config, Map.of("claude", "deleted")));
        assertEquals("deleted", e.accountName());
    }

    /**
     * A pre-accounts config.yaml has no `claude.accounts` in the serialized tree, but
     * ClaudeConfig still presents one synthesized account named `default` -- which is what
     * `isx account list` shows and what a user would therefore pin to. Resolving that name
     * generically as well as typed would fail closed and 502 every request.
     */
    @Test
    void pinningTheSynthesizedAccountOnAFlatConfigResolves() throws Exception {
        var config = YAML.readValue("""
                claude:
                  oauthToken: "sk-ant-oat01-legacy"
                """, SpawnConfig.class);
        var name = SpawnConfig.ClaudeConfig.LEGACY_ACCOUNT_NAME;
        assertEquals(name, config.getClaude().accountName());

        var creds = ProxyCredentials.forAccounts(config, Map.of("claude", name), Map.of());
        assertEquals("sk-ant-oat01-legacy", creds.oauthToken());
    }

    // ── the generic path: any namespace, no per-tool code ────────────────────────

    /** A tool that exists only as a config-namespace declaration, as a YAML tool would be. */
    private static ToolSetup toolWithNamespace(String namespace) {
        var token = new ToolDef.ConfigEntry();
        token.setConfigPath("token");
        token.setSecret(true);

        var auth = new ToolDef.AuthDef();
        auth.setDomains(List.of("api.example.com"));
        auth.setType("bearer");
        auth.setToken("${token}");

        var proxyDef = new ToolDef.ProxyDef();
        proxyDef.setConfigNamespace(namespace);
        proxyDef.setConfiguration(Map.of("token", token));
        proxyDef.setAuth(List.of(auth));

        return new ToolSetup() {
            @Override public String name() { return namespace; }
            @Override public ToolDef.ProxyDef proxy() { return proxyDef; }
            @Override public void install(dev.incusspawn.incus.Container container,
                                          Map<String, String> resolvedParams) {}
        };
    }

    @Test
    void namedAccountsWorkForAnyNamespaceWithoutPerToolCode() throws Exception {
        var config = YAML.readValue("""
                github:
                  accounts:
                    personal:
                      token: "ghp_personal"
                    acme:
                      token: "ghp_acme"
                  default: personal
                """, SpawnConfig.class);
        var tools = Map.of("github", toolWithNamespace("github"));

        var byDefault = ToolProxyResolver.resolve(config, tools, Map.of());
        assertEquals("Bearer ghp_personal", byDefault.get(0).computeHeaderValue());

        var pinned = ToolProxyResolver.resolve(config, tools, Map.of("github", "acme"));
        assertEquals("Bearer ghp_acme", pinned.get(0).computeHeaderValue());
    }

    /** A pre-accounts flat namespace keeps working untouched. */
    @Test
    void flatNamespaceStillResolves() throws Exception {
        var config = YAML.readValue("""
                github:
                  token: "ghp_flat"
                """, SpawnConfig.class);
        var resolved = ToolProxyResolver.resolve(config, Map.of("github", toolWithNamespace("github")), Map.of());
        assertEquals("Bearer ghp_flat", resolved.get(0).computeHeaderValue());
    }

    /**
     * A namespace may hold some keys per account and others globally; an account that omits a
     * key falls through to the flat value rather than resolving to blank.
     */
    @Test
    void accountOmittingAKeyFallsBackToTheFlatValue() throws Exception {
        var config = YAML.readValue("""
                github:
                  token: "ghp_shared"
                  accounts:
                    acme:
                      email: "bot@acme.example"
                """, SpawnConfig.class);
        var resolved = ToolProxyResolver.resolve(config,
                Map.of("github", toolWithNamespace("github")), Map.of("github", "acme"));
        assertEquals("Bearer ghp_shared", resolved.get(0).computeHeaderValue());
    }

    /**
     * Interception is global -- it drives certificates and bridge DNS, which cannot vary per
     * caller. Resolving it from the default account alone drops a tool whose credential exists
     * only under a named account, so the domain would not be intercepted and a pinned
     * instance's request would be relayed through with nothing injected.
     */
    @Test
    void domainsResolvableOnlyUnderANamedAccountAreStillIntercepted() throws Exception {
        var config = YAML.readValue("""
                github:
                  accounts:
                    personal:
                      email: "me@example.com"
                    acme:
                      token: "ghp_acme"
                  default: personal
                """, SpawnConfig.class);
        var tools = Map.of("github", toolWithNamespace("github"));

        // The default account has no token, so the default resolution finds nothing.
        assertTrue(ToolProxyResolver.resolve(config, tools, Map.of()).isEmpty());

        // Across accounts, the domain is still known and must be intercepted.
        var across = ToolProxyResolver.resolveAcrossAccounts(config, tools);
        assertEquals(1, across.size());
        assertEquals("api.example.com", across.get(0).domain());
    }

    /**
     * A tool that borrows another namespace's credential -- CopilotSetup points at
     * {@code github.token} and declares no namespace of its own -- must follow the same
     * account selection as the tool it borrows from. Otherwise gh authenticates as the pinned
     * account while copilot keeps using the flat token: two GitHub identities in one container.
     */
    @Test
    void aToolBorrowingAnotherNamespaceFollowsThatNamespacesAccount() throws Exception {
        var config = YAML.readValue("""
                github:
                  token: "ghp_flat"
                  accounts:
                    acme:
                      token: "ghp_acme"
                """, SpawnConfig.class);

        var borrower = toolBorrowing("github.token");
        var tools = Map.of("gh", toolWithNamespace("github"), "copilot", borrower);

        var pinned = ToolProxyResolver.resolve(config, tools, Map.of("github", "acme"));
        assertFalse(pinned.isEmpty());
        for (var resolved : pinned) {
            assertEquals("Bearer ghp_acme", resolved.computeHeaderValue(),
                    resolved.toolName() + " should use the pinned account's token");
        }
    }

    @Test
    void aBorrowedNamespacesAccountsStillWidenTheInterceptedDomainSet() throws Exception {
        var config = YAML.readValue("""
                github:
                  accounts:
                    personal:
                      email: "me@example.com"
                    acme:
                      token: "ghp_acme"
                  default: personal
                """, SpawnConfig.class);
        // The default account carries no token, so only the across-accounts pass can see
        // that this domain is worth intercepting at all.
        var tools = Map.of("copilot", toolBorrowing("github.token"));
        assertTrue(ToolProxyResolver.resolve(config, tools, Map.of()).isEmpty());
        assertEquals(1, ToolProxyResolver.resolveAcrossAccounts(config, tools).size());
    }

    /**
     * End to end for the real gh tool, not a synthetic one: two GitHub accounts, and the
     * instance's pin decides which token is injected into both the basic-auth (git over HTTPS)
     * and bearer (API) entries.
     */
    @Test
    void theRealGhToolResolvesPerAccount() throws Exception {
        var config = YAML.readValue("""
                github:
                  accounts:
                    personal:
                      token: "ghp_personal"
                    acme:
                      token: "ghp_acme"
                  default: personal
                """, SpawnConfig.class);
        var tools = Map.<String, ToolSetup>of("gh", new dev.incusspawn.tool.GhSetup());

        assertAllTokens(ToolProxyResolver.resolve(config, tools, Map.of()), "ghp_personal");
        assertAllTokens(ToolProxyResolver.resolve(config, tools, Map.of("github", "acme")), "ghp_acme");
    }

    /** A pre-accounts github block keeps working for the real tool too. */
    @Test
    void theRealGhToolStillResolvesAFlatToken() throws Exception {
        var config = YAML.readValue("github:\n  token: \"ghp_flat\"\n", SpawnConfig.class);
        var tools = Map.<String, ToolSetup>of("gh", new dev.incusspawn.tool.GhSetup());
        assertAllTokens(ToolProxyResolver.resolve(config, tools, Map.of()), "ghp_flat");
    }

    private static void assertAllTokens(List<ResolvedToolProxy> resolved, String expectedToken) {
        assertFalse(resolved.isEmpty(), "gh should contribute proxy entries");
        for (var entry : resolved) {
            var header = entry.computeHeaderValue();
            if (header.startsWith("Bearer ")) {
                assertEquals("Bearer " + expectedToken, header);
            } else {
                // Basic auth for git over HTTPS: x-access-token:<pat>, base64-encoded.
                var decoded = new String(java.util.Base64.getDecoder()
                        .decode(header.substring("Basic ".length())));
                assertEquals("x-access-token:" + expectedToken, decoded);
            }
        }
    }

    /** A tool naming another namespace's key outright, the way CopilotSetup shares gh's PAT. */
    private static ToolSetup toolBorrowing(String fullConfigPath) {
        var token = new ToolDef.ConfigEntry();
        token.setConfigPath(fullConfigPath);
        token.setSecret(true);

        var auth = new ToolDef.AuthDef();
        auth.setDomains(List.of("api.borrower.example"));
        auth.setType("bearer");
        auth.setToken("${token}");

        var proxyDef = new ToolDef.ProxyDef();
        // Deliberately no config-namespace: that is what makes it a borrower.
        proxyDef.setConfiguration(Map.of("token", token));
        proxyDef.setAuth(List.of(auth));

        return new ToolSetup() {
            @Override public String name() { return "borrower"; }
            @Override public ToolDef.ProxyDef proxy() { return proxyDef; }
            @Override public void install(dev.incusspawn.incus.Container container,
                                          Map<String, String> resolvedParams) {}
        };
    }

    @Test
    void pinningAnUnknownAccountInAToolNamespaceFailsClosed() throws Exception {
        var config = YAML.readValue("""
                github:
                  accounts:
                    personal:
                      token: "ghp_personal"
                """, SpawnConfig.class);
        assertThrows(AccountResolver.UnknownAccountException.class,
                () -> ToolProxyResolver.resolve(config,
                        Map.of("github", toolWithNamespace("github")), Map.of("github", "gone")));
    }
}
