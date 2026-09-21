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
