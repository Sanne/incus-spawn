package dev.incusspawn.tool;

import dev.incusspawn.config.CredentialCheck;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** How a tool's secret declares its check, and where a check may send it (#2566). */
class SecretCheckTest {

    /**
     * The tools whose own step in {@code isx init} checks their credential in code, so their
     * entries declare nothing: Claude's (Anthropic's messages endpoint) and GitHub's ({@code /user}).
     */
    private static final Set<String> CHECKED_BY_OWN_STEP = Set.of("claude", "gh");

    /** Every key the first run accepts is checked, or says why it cannot be. */
    @Test
    void everyBuiltInSecretDeclaresASoundCheck() {
        var missing = new ArrayList<String>();
        new ToolDefLoader().allToolSetups().forEach((name, tool) -> {
            if (!CredentialCheck.hasCredentialStep(tool) || CHECKED_BY_OWN_STEP.contains(name)) return;
            var proxy = tool.proxy();
            proxy.getConfiguration().forEach((key, entry) -> {
                if (!entry.isSecret()) return;
                var problem = SecretCheck.problem(proxy, key);
                if (problem != null) missing.add(name + "." + key + ": " + problem);
            });
        });
        assertEquals(List.of(), missing);
    }

    @Test
    void openAiAndGeminiKeysAreCheckedOnTheirOwnAuthDomain() {
        var codex = SecretCheck.request("codex", new CodexSetup().proxy(), "api-key", Map.of("api-key", "sk-x"));
        assertEquals("https://api.openai.com/v1/models", codex.orElseThrow().uri().toString());
        assertEquals("Authorization", codex.get().header());
        assertEquals("Bearer sk-x", codex.get().value());

        var gemini = SecretCheck.request("gemini-cli", new GeminiSetup().proxy(), "api-key", Map.of("api-key", "AIza-x"));
        assertEquals("generativelanguage.googleapis.com", gemini.orElseThrow().host());
        assertEquals("x-goog-api-key", gemini.get().header());
        assertEquals("AIza-x", gemini.get().value());
        assertFalse(gemini.get().toString().contains("AIza-x"), "the key is never printed");
    }

    @Test
    void aCheckDeclaringNoneSendsNothing() {
        assertNull(SecretCheck.problem(new BobSetup().proxy(), "api-key"));
        assertTrue(SecretCheck.request("bob", new BobSetup().proxy(), "api-key", Map.of("api-key", "k")).isEmpty());
    }

    private static ToolDef.ProxyDef proxy(String url, String... domains) {
        var key = new ToolDef.ConfigEntry();
        key.setConfigPath("apiKey");
        key.setSecret(true);
        key.setCheck(SecretCheck.url(url));
        var auth = new ToolDef.AuthDef();
        auth.setDomains(List.of(domains));
        auth.setType("bearer");
        auth.setToken("${api-key}");
        var proxy = new ToolDef.ProxyDef();
        proxy.setConfigNamespace("acme");
        proxy.setConfiguration(Map.of("api-key", key));
        proxy.setAuth(List.of(auth));
        return proxy;
    }

    /** A check sends the key only where the tool's own auth entry already injects it. */
    @Test
    void aCheckIsRefusedAnywhereTheProxyWouldNotSendTheKey() {
        assertNull(SecretCheck.problem(proxy("https://api.acme.test/v1/me", "api.acme.test"), "api-key"));
        assertNotNull(SecretCheck.problem(proxy("https://evil.test/v1/me", "api.acme.test"), "api-key"));
        assertNotNull(SecretCheck.problem(proxy("http://api.acme.test/v1/me", "api.acme.test"), "api-key"));
        assertNotNull(SecretCheck.problem(proxy("https://u@api.acme.test/v1/me", "api.acme.test"), "api-key"));
        // A wildcard or a configured domain names no one host the definition chose
        assertNotNull(SecretCheck.problem(proxy("https://api.acme.test/v1/me", "*.acme.test"), "api-key"));
        assertNotNull(SecretCheck.problem(proxy("https://api.acme.test/v1/me", "${host}"), "api-key"));
        assertTrue(SecretCheck.request("acme", proxy("https://evil.test/", "api.acme.test"), "api-key",
                Map.of("api-key", "k")).isEmpty());
        // The proxy injects on the default port only
        assertNotNull(SecretCheck.problem(proxy("https://api.acme.test:8443/v1/me", "api.acme.test"), "api-key"));
        assertNull(SecretCheck.problem(proxy("https://api.acme.test:443/v1/me", "api.acme.test"), "api-key"));
    }

    /** An auth entry scoped to paths injects the key under them alone, so a check goes nowhere else. */
    @Test
    void aCheckStaysWithinTheAuthEntrysPaths() {
        var scoped = proxy("https://api.acme.test/v1/me", "api.acme.test");
        scoped.getAuth().getFirst().setPaths(List.of("/v1"));
        assertNull(SecretCheck.problem(scoped, "api-key"));
        var outside = proxy("https://api.acme.test/other/me", "api.acme.test");
        outside.getAuth().getFirst().setPaths(List.of("/v1"));
        assertNotNull(SecretCheck.problem(outside, "api-key"));
        assertTrue(SecretCheck.request("acme", outside, "api-key", Map.of("api-key", "k")).isEmpty());
    }

    @Test
    void aCheckNeedsExactlyOneOfUrlOrNone() {
        var proxy = proxy("https://api.acme.test/", "api.acme.test");
        proxy.getConfiguration().get("api-key").getCheck().setNone("why");
        assertNotNull(SecretCheck.problem(proxy, "api-key"));
        proxy.getConfiguration().get("api-key").setCheck(new ToolDef.CheckDef());
        assertNotNull(SecretCheck.problem(proxy, "api-key"));
    }

    @Test
    void theValidatorRefusesAnUnsoundCheckInAToolDefinition() {
        var result = ToolDefValidator.validate("""
                name: acme
                env:
                  - name: ACME_KEY
                    value: acme-placeholder
                proxy:
                  config-namespace: acme
                  placeholders:
                    - env: ACME_KEY
                      prefix: acme-
                  configuration:
                    api-key:
                      config-path: apiKey
                      description: Acme key
                      secret: true
                      check:
                        url: https://elsewhere.test/
                  auth:
                    - domains: [api.acme.test]
                      type: bearer
                      token: "${api-key}"
                """, "acme.yaml");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("elsewhere.test")), result.errors().toString());
    }
}
