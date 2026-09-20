package dev.incusspawn.config;

import dev.incusspawn.tool.BobSetup;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.CodexSetup;
import dev.incusspawn.tool.GhSetup;
import dev.incusspawn.tool.ToolSetup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecretRedactorTest {

    private static final String OAUTH = "sk-ant-oat01-verySecretOauthTokenValue000";
    private static final String GH_TOKEN = "ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789";

    /** The declarations a real run sees: every built-in tool that injects a credential. */
    private static List<SecretRegistry.SecretLocation> builtInLocations() {
        Map<String, ToolSetup> tools = Map.of("claude", new ClaudeSetup(), "gh", new GhSetup(),
                "bob", new BobSetup(), "codex", new CodexSetup());
        return SecretRegistry.locations(tools);
    }

    private static SpawnConfig configuredConfig() {
        var config = new SpawnConfig();
        config.getClaude().setOauthToken(OAUTH);
        config.getGithub().setToken(GH_TOKEN);
        config.getGithub().setEmail("dev@example.com");
        config.getClaude().setVertexProjectId("my-gcp-project");
        return config;
    }

    // ---- Structural config redaction ----

    @Test
    void configuredSecretBecomesAMarkerAndTheValueIsGone() {
        var result = SecretRedactor.redactConfig(configuredConfig(), builtInLocations());
        assertFalse(result.yaml().contains(OAUTH), "the OAuth token must not survive");
        assertFalse(result.yaml().contains(GH_TOKEN), "the GitHub token must not survive");
        assertTrue(result.yaml().contains("<isx:redacted:claude.oauthToken>"));
        assertTrue(result.yaml().contains("<isx:redacted:github.token>"));
        assertEquals(List.of("claude.oauthToken", "github.token"), result.redactedPaths());
    }

    @Test
    void unsetSecretStaysEmptySoItIsDistinguishableFromARemovedOne() {
        var result = SecretRedactor.redactConfig(configuredConfig(), builtInLocations());
        assertFalse(result.redactedPaths().contains("claude.apiKey"),
                "an unconfigured key was never redacted, so it must not be reported as such");
        // apiKey is unset: it must serialize as empty, not as a marker.
        assertTrue(result.yaml().contains("apiKey: \"\""), "actual yaml:\n" + result.yaml());
    }

    @Test
    void nonSecretValuesSurviveVerbatim() {
        var result = SecretRedactor.redactConfig(configuredConfig(), builtInLocations());
        assertTrue(result.yaml().contains("dev@example.com"), "email is not a credential");
        assertTrue(result.yaml().contains("my-gcp-project"), "project id is not a credential");
    }

    @Test
    void noCredentialShapedKeyIsLeftHoldingAValue() {
        // The property the bundle depends on, over the whole serialized tree: every
        // secret-shaped key comes out either empty (never set) or marked (set and removed).
        var config = configuredConfig();
        config.setExtra("typesafe", new java.util.LinkedHashMap<>(Map.of("apiKey", "ts-live-secret")));
        config.getBob().setApiKey("bob-live-secret-value");

        var yaml = SecretRedactor.redactConfig(config, builtInLocations()).yaml();
        for (var line : yaml.lines().toList()) {
            var colon = line.indexOf(':');
            if (colon < 0) continue;
            var key = line.substring(0, colon).strip().replaceAll("^\"|\"$", "");
            if (!SecretRedactor.looksSecret(key)) continue;
            var value = line.substring(colon + 1).strip().replaceAll("^\"|\"$", "");
            assertTrue(value.isEmpty() || value.startsWith(SecretRedactor.MARKER_PREFIX),
                    "secret-shaped key left a value in the bundle: " + line);
        }
    }

    @Test
    void secretValuesAreReportedForScrubbingElsewhere() {
        var result = SecretRedactor.redactConfig(configuredConfig(), builtInLocations());
        assertEquals("claude.oauthToken", result.secretValues().get(OAUTH));
        assertEquals("github.token", result.secretValues().get(GH_TOKEN));
    }

    @Test
    void undeclaredSecretInExtrasIsRedactedByNameBackstop() {
        // A tool-contributed key lands in SpawnConfig's catch-all extras. Even with no
        // declaration in scope, a credential-shaped name must not be written out.
        var config = new SpawnConfig();
        config.setExtra("typesafe", new java.util.LinkedHashMap<>(Map.of("apiKey", "ts-live-secret-value")));

        var result = SecretRedactor.redactConfig(config, List.of());
        assertFalse(result.yaml().contains("ts-live-secret-value"));
        assertTrue(result.yaml().contains("<isx:redacted:typesafe.apiKey>"));
        assertEquals(List.of("typesafe.apiKey"), result.redactedPaths());
    }

    @Test
    void undeclaredSecretNestedInAListIsStillRedacted() {
        var config = new SpawnConfig();
        config.setExtra("registries", List.of(
                new java.util.LinkedHashMap<>(Map.of("url", "https://one.example.com",
                        "token", "registry-FAKE-token-0001"))));

        var result = SecretRedactor.redactConfig(config, List.of());
        assertFalse(result.yaml().contains("registry-FAKE-token-0001"));
        assertTrue(result.yaml().contains("https://one.example.com"), "the url is diagnostic");
        assertEquals(List.of("registries[0].token"), result.redactedPaths());
    }

    @Test
    void everyClaudeAccountsTokenIsRedacted() {
        // Credentials became a named map (claude.accounts.<name>.apiKey). An exact-path-only
        // redactor wrote every account's token into the bundle while reporting that it found
        // no credentials at all — a declared leaf has to hold wherever it appears.
        var config = new SpawnConfig();
        config.getClaude().setAccounts(new java.util.LinkedHashMap<>(Map.of(
                "work", SpawnConfig.ClaudeAccount.ofOauth(OAUTH),
                "personal", SpawnConfig.ClaudeAccount.ofApiKey("sk-ant-api03-secondAccountKey01"))));
        config.getClaude().setDefaultAccount("work");

        var result = SecretRedactor.redactConfig(config, builtInLocations());
        assertFalse(result.yaml().contains(OAUTH), "account OAuth token must not survive");
        assertFalse(result.yaml().contains("sk-ant-api03-secondAccountKey01"));
        assertTrue(result.redactedPaths().contains("claude.accounts.work.oauthToken"),
                "actual: " + result.redactedPaths());
        assertTrue(result.redactedPaths().contains("claude.accounts.personal.apiKey"));
        assertTrue(result.yaml().contains("default: \"work\""), "the account names stay readable");
        assertTrue(result.yaml().contains("type: \"oauth\""), "the account type is diagnostic");
    }

    @Test
    void aDuplicatedSecretIsLabelledWithItsDeclaredPath() {
        // The same literal in two places: the log scrubber must name the key a reader can
        // look up, not whichever node happened to be visited last.
        var config = configuredConfig();
        config.setExtra("mytool", new java.util.LinkedHashMap<>(Map.of("token", GH_TOKEN)));

        var result = SecretRedactor.redactConfig(config, builtInLocations());
        assertEquals("github.token", result.secretValues().get(GH_TOKEN));
        assertTrue(result.redactedPaths().contains("mytool.token"),
                "both keys are still listed in the manifest: " + result.redactedPaths());
    }

    @Test
    void secretNamedListIsRedactedElementByElement() {
        // A credential stored as a structure is still a credential. Skipping containers left
        // it in the bundle AND out of the manifest, so nobody would have known.
        var config = new SpawnConfig();
        config.setExtra("mytool", new java.util.LinkedHashMap<>(
                Map.of("tokens", List.of("plainsecretvalue0001"))));

        var result = SecretRedactor.redactConfig(config, List.of());
        assertFalse(result.yaml().contains("plainsecretvalue0001"));
        assertEquals(List.of("mytool.tokens[0]"), result.redactedPaths());
    }

    @Test
    void anOrdinaryConfigBlockUnderASecretShapedNameSurvives() {
        // "auth" reads as a credential, but an auth *block* is ordinary configuration. Taking
        // the whole object cost every field and, worse, treated the endpoint as a secret
        // value — scrubbing it out of every log in the archive.
        var auth = new java.util.LinkedHashMap<String, Object>();
        auth.put("enabled", true);
        auth.put("endpoint", "https://acme.example.com/v1/registry");
        auth.put("timeoutMs", 30000);
        var config = new SpawnConfig();
        config.setExtra("acme", new java.util.LinkedHashMap<>(Map.of("auth", auth)));

        var result = SecretRedactor.redactConfig(config, List.of());
        assertTrue(result.redactedPaths().isEmpty(), "actual: " + result.redactedPaths());
        assertTrue(result.secretValues().isEmpty(), "nothing here may reach the log scrubber");
        assertTrue(result.yaml().contains("https://acme.example.com/v1/registry"));
        assertTrue(result.yaml().contains("enabled: true"));
    }

    @Test
    void aSecretNamedFieldInsideThatBlockIsStillRedacted() {
        // The inner names decide, so a real credential beside them is still caught.
        var auth = new java.util.LinkedHashMap<String, Object>();
        auth.put("endpoint", "https://acme.example.com/v1/registry");
        auth.put("apiKey", "acme-live-secret-0001");
        var config = new SpawnConfig();
        config.setExtra("acme", new java.util.LinkedHashMap<>(Map.of("auth", auth)));

        var result = SecretRedactor.redactConfig(config, List.of());
        assertEquals(List.of("acme.auth.apiKey"), result.redactedPaths());
        assertTrue(result.yaml().contains("https://acme.example.com/v1/registry"));
    }

    @Test
    void aDeclaredStructuredSecretIsStillTakenWhole() {
        // A declaration is authoritative where a name is only a guess: a tool that stores a
        // structured credential gets it redacted whole, inner names notwithstanding.
        var config = new SpawnConfig();
        config.setExtra("acme", new java.util.LinkedHashMap<>(Map.of("token",
                new java.util.LinkedHashMap<>(Map.of("value", "supersecret123456")))));

        var result = SecretRedactor.redactConfig(config,
                List.of(new SecretRegistry.SecretLocation("acme.token", "acme", "Acme token")));
        assertFalse(result.yaml().contains("supersecret123456"));
        assertEquals(List.of("acme.token.value"), result.redactedPaths());
    }

    @Test
    void aMalformedDeclaredPathDoesNotFailTheBundle() {
        // A tool YAML with config-path "." used to throw out of redactConfig, so one bad tool
        // definition meant no archive at all.
        var result = SecretRedactor.redactConfig(configuredConfig(),
                List.of(new SecretRegistry.SecretLocation(".", "broken", "malformed")));
        assertFalse(result.yaml().isBlank());
    }

    @Test
    void declaredSecretHoldingAStructureIsStillRedacted() {
        // A tool may declare a path that holds an object; it must not slip through silently.
        var config = new SpawnConfig();
        config.setExtra("acme", new java.util.LinkedHashMap<>(
                Map.of("handle", new java.util.LinkedHashMap<>(Map.of("id", "structured-secret-1")))));

        var result = SecretRedactor.redactConfig(config,
                List.of(new SecretRegistry.SecretLocation("acme.handle", "acme", "Acme handle")));
        assertFalse(result.yaml().contains("structured-secret-1"));
        assertEquals(List.of("acme.handle.id"), result.redactedPaths());
    }

    @Test
    void userChosenMapKeysAreNotTreatedAsConfigKeys() {
        // repo-paths keys are repository names, not config keys. Running the name heuristic
        // over them redacted a repo called "secret-sauce" and then scrubbed its path out of
        // every log line — destroying the diagnostics the bundle exists to carry.
        var config = new SpawnConfig();
        config.setRepoPaths(Map.of("secret-sauce", "/home/me/src/secret-sauce"));

        var result = SecretRedactor.redactConfig(config, builtInLocations());
        assertTrue(result.redactedPaths().isEmpty(), "actual: " + result.redactedPaths());
        assertTrue(result.secretValues().isEmpty(), "a repo path must never reach the log scrubber");
        assertTrue(result.yaml().contains("/home/me/src/secret-sauce"));
    }

    @Test
    void everyCredentialShapedKeyInTheSchemaIsDeclaredBySomeBuiltInTool() {
        // The backstop only covers extras, so the typed fields must be covered by
        // declarations. If someone adds a typed secret field without declaring it on a tool,
        // nothing would redact it — this fails instead.
        var declared = SecretRegistry.locations(
                new dev.incusspawn.tool.ToolDefLoader().allToolSetups()).stream()
                .map(SecretRegistry.SecretLocation::path).toList();
        var yaml = SecretRedactor.redactConfig(new SpawnConfig(), List.of()).yaml();

        var section = "";
        for (var line : yaml.lines().toList()) {
            var colon = line.indexOf(':');
            if (colon < 0) continue;
            var key = line.substring(0, colon).strip().replaceAll("^\"|\"$", "");
            if (line.charAt(0) != ' ') {
                section = key;
                continue;
            }
            if (!SecretRedactor.looksSecret(key)) continue;
            assertTrue(declared.contains(section + "." + key),
                    "undeclared credential-shaped field in SpawnConfig: " + section + "." + key);
        }
    }

    @Test
    void credentialShapedBooleansKeepTheirValue() {
        // A flag cannot be a credential, and blanking it would cost a diagnostic.
        var config = new SpawnConfig();
        config.setExtra("mytool", new java.util.LinkedHashMap<>(Map.of("authEnabled", true)));

        var result = SecretRedactor.redactConfig(config, List.of());
        assertTrue(result.yaml().contains("authEnabled: true"), result.yaml());
        assertTrue(result.redactedPaths().isEmpty());
    }

    @Test
    void declaredSecretIsNotReportedTwiceByTheBackstop() {
        var result = SecretRedactor.redactConfig(configuredConfig(), builtInLocations());
        assertEquals(result.redactedPaths().size(),
                result.redactedPaths().stream().distinct().count(),
                "a path redacted by declaration must not be counted again by name");
    }

    @Test
    void emptyConfigRedactsNothing() {
        var result = SecretRedactor.redactConfig(new SpawnConfig(), builtInLocations());
        assertTrue(result.redactedPaths().isEmpty());
        assertTrue(result.secretValues().isEmpty());
        assertFalse(result.yaml().isBlank(), "structure is still useful to a reader");
    }

    // ---- Name heuristic (backstop for undeclared keys) ----

    @Test
    void credentialShapedNamesLookSecret() {
        assertTrue(SecretRedactor.looksSecret("apiKey"));
        assertTrue(SecretRedactor.looksSecret("api-key"));
        assertTrue(SecretRedactor.looksSecret("API_KEY"));
        assertTrue(SecretRedactor.looksSecret("oauthToken"));
        assertTrue(SecretRedactor.looksSecret("token"));
        assertTrue(SecretRedactor.looksSecret("password"));
        assertTrue(SecretRedactor.looksSecret("clientSecret"));
        assertTrue(SecretRedactor.looksSecret("authHeader"));
    }

    @Test
    void ordinaryNamesDoNotLookSecret() {
        // Substring matching would blank these out and take real diagnostics with them.
        assertFalse(SecretRedactor.looksSecret("monkey"));
        assertFalse(SecretRedactor.looksSecret("keyboardLayout"));
        assertFalse(SecretRedactor.looksSecret("licenseConsent"));
        assertFalse(SecretRedactor.looksSecret("email"));
        assertFalse(SecretRedactor.looksSecret("searchPaths"));
        assertFalse(SecretRedactor.looksSecret(""));
        assertFalse(SecretRedactor.looksSecret(null));
    }

    // ---- Text scrubbing ----

    @Test
    void knownSecretValueIsScrubbedFromLogText() {
        var log = "2026-09-20 injecting credentials: " + GH_TOKEN + " for github.com\n";
        var scrubbed = SecretRedactor.scrubText(log, Map.of(GH_TOKEN, "github.token"));
        assertFalse(scrubbed.text().contains(GH_TOKEN));
        assertTrue(scrubbed.text().contains("<isx:redacted:github.token>"));
        assertTrue(scrubbed.text().contains("for github.com"), "surrounding context survives");
        assertEquals(1, scrubbed.hits().get("github.token"));
    }

    @Test
    void shortValuesAreNotScrubbedSoTheyCannotMassReplace() {
        var log = "region eu-de, instance eu-de-1, pool eu-de\n";
        var scrubbed = SecretRedactor.scrubText(log, Map.of("eu-de", "some.key"));
        assertEquals(log, scrubbed.text());
        assertTrue(scrubbed.hits().isEmpty());
    }

    @Test
    void anthropicKeyShapeIsCaughtWithoutKnowingTheValue() {
        var scrubbed = SecretRedactor.scrubText(
                "x-api-key: sk-ant-api03-AbCdEfGhIjKlMnOpQrSt\n", Map.of());
        assertFalse(scrubbed.text().contains("sk-ant-api03"));
        assertTrue(scrubbed.text().contains("<isx:redacted:anthropic-key>"));
    }

    @Test
    void githubAndOpenaiKeyShapesAreCaught() {
        var scrubbed = SecretRedactor.scrubText(
                "gh=" + GH_TOKEN + " pat=github_pat_11ABCDEFG0123456789abcdefg"
                        + " openai=sk-proj0123456789abcdefghij\n", Map.of());
        assertFalse(scrubbed.text().contains(GH_TOKEN));
        assertFalse(scrubbed.text().contains("github_pat_11"));
        assertFalse(scrubbed.text().contains("sk-proj0123456789"));
        assertEquals(2, scrubbed.hits().get("github-token"));
        assertEquals(1, scrubbed.hits().get("openai-key"));
    }

    @Test
    void authorizationHeadersKeepTheirShapeButLoseTheCredential() {
        var scrubbed = SecretRedactor.scrubText("""
                > Authorization: Bearer ya29.a0AfB_byC3nT0k3n-value
                > Authorization: Basic eC1hY2Nlc3MtdG9rZW46Z2hwXw==
                """, Map.of());
        assertFalse(scrubbed.text().contains("ya29.a0AfB_byC3nT0k3n-value"));
        assertFalse(scrubbed.text().contains("eC1hY2Nlc3MtdG9rZW46Z2hwXw"));
        assertTrue(scrubbed.text().contains("Authorization: Bearer <isx:redacted:bearer-token>"));
        assertTrue(scrubbed.text().contains("Authorization: Basic <isx:redacted:basic-auth>"));
    }

    @Test
    void privateKeyBlocksAreRemovedWhole() {
        var scrubbed = SecretRedactor.scrubText("""
                before
                -----BEGIN RSA PRIVATE KEY-----
                MIIEowIBAAKCAQEAxyz
                more base64 here
                -----END RSA PRIVATE KEY-----
                after
                """, Map.of());
        assertFalse(scrubbed.text().contains("MIIEowIBAAKCAQEAxyz"));
        assertFalse(scrubbed.text().contains("BEGIN RSA PRIVATE KEY"));
        assertTrue(scrubbed.text().contains("before"));
        assertTrue(scrubbed.text().contains("after"));
        assertEquals(1, scrubbed.hits().get("private-key"));
    }

    @Test
    void credentialsEmbeddedInUrlsAreRemoved() {
        var scrubbed = SecretRedactor.scrubText(
                "fatal: could not read https://x-access-token:ghp_secretvalue@github.com/o/r\n", Map.of());
        assertFalse(scrubbed.text().contains("ghp_secretvalue"));
        assertTrue(scrubbed.text().contains("https://x-access-token:<isx:redacted:url-credentials>@github.com"));
    }

    @Test
    void aKnownValueInsideAUrlKeepsTheNameOfItsKey() {
        // Both mechanisms match here; the one that knows which key it was must win.
        var scrubbed = SecretRedactor.scrubText(
                "clone https://x-access-token:" + GH_TOKEN + "@github.com/o/r\n",
                Map.of(GH_TOKEN, "github.token"));
        assertTrue(scrubbed.text().contains("<isx:redacted:github.token>@github.com"),
                scrubbed.text());
        assertFalse(scrubbed.text().contains("url-credentials"),
                "an already-marked value must not be relabelled");
    }

    @Test
    void cleanTextIsReturnedUnchanged() {
        var log = "2026-09-20 proxy started on 10.166.11.1:18443\n";
        var scrubbed = SecretRedactor.scrubText(log, Map.of(GH_TOKEN, "github.token"));
        assertEquals(log, scrubbed.text());
        assertTrue(scrubbed.hits().isEmpty());
    }

    @Test
    void redactedConfigSurvivesASecondPassUnchanged() {
        // The bundle sends every file, the config included, through scrubbing. Markers must
        // not be mangled by the patterns that run over them.
        var redaction = SecretRedactor.redactConfig(configuredConfig(), builtInLocations());
        var scrubbed = SecretRedactor.scrubText(redaction.yaml(), redaction.secretValues());
        assertEquals(redaction.yaml(), scrubbed.text());
        assertTrue(scrubbed.hits().isEmpty());
    }
}
