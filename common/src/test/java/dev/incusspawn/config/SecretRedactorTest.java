package dev.incusspawn.config;

import dev.incusspawn.tool.BobSetup;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.CodexSetup;
import dev.incusspawn.tool.GhSetup;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.util.BuildOutput;
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
        // apiKey is unset: it must not come out as a marker. (Unset keys are omitted outright
        // now that no namespace writes its empty fields back, so absence is the evidence.)
        assertFalse(result.yaml().contains("<isx:redacted:claude.apiKey>"), "actual yaml:\n" + result.yaml());
        assertFalse(result.yaml().contains("<isx:redacted:bob.apiKey>"), "actual yaml:\n" + result.yaml());
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

    /**
     * A typed namespace keeps keys it does not declare, Claude's and its accounts' included
     * (#1876), so the name backstop covers them as it covers the catch-all extras.
     */
    @Test
    void undeclaredSecretInATypedNamespaceIsRedactedByNameBackstop() throws Exception {
        var config = Jjq.spawnConfig("""
                claude:
                  password: claude-plain-value-0001
                  region: eu
                  accounts:
                    w: { apiKey: k-declared-0001, secret: account-plain-value-0001 }
                github:
                  accounts:
                    work: { password: gh-plain-value-0001 }
                """);

        var result = SecretRedactor.redactConfig(config, List.of());
        assertFalse(result.yaml().contains("claude-plain-value-0001"), result.yaml());
        assertFalse(result.yaml().contains("account-plain-value-0001"), result.yaml());
        assertFalse(result.yaml().contains("gh-plain-value-0001"), result.yaml());
        assertTrue(result.yaml().contains("region: eu"), "a setting is diagnostic:\n" + result.yaml());
        assertEquals(List.of("claude.password", "claude.accounts.w.apiKey", "claude.accounts.w.secret",
                        "github.accounts.work.password"),
                result.redactedPaths());
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
        assertTrue(result.yaml().contains("default: work"), "the account names stay readable");
        assertTrue(result.yaml().contains("type: oauth"), "the account type is diagnostic");
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

    /** A key may itself contain dots or brackets: the value under it is still the one replaced. */
    @Test
    void aSecretUnderAKeyWithDotsIsRedactedWhereItIs() {
        var config = new SpawnConfig();
        config.setExtra("gh2", new java.util.LinkedHashMap<>(Map.of(
                "hosts.github.com.oauth_token", "gho-FAKE-dotted-key-0001")));
        config.setExtra("mirror.example.com", new java.util.LinkedHashMap<>(Map.of(
                "token", "mirror-FAKE-dotted-parent-0002")));

        var result = SecretRedactor.redactConfig(config, List.of());
        assertFalse(result.yaml().contains("gho-FAKE-dotted-key-0001"), result.yaml());
        assertFalse(result.yaml().contains("mirror-FAKE-dotted-parent-0002"), result.yaml());
        assertEquals(java.util.Set.of("gh2[\"hosts.github.com.oauth_token\"]", "[\"mirror.example.com\"].token"),
                java.util.Set.copyOf(result.redactedPaths()), "labels say which key, dots and all");
    }

    @Test
    void aKeyWithBracketsNeitherFailsTheBundleNorKeepsItsSecret() {
        var config = new SpawnConfig();
        config.setExtra("odd", new java.util.LinkedHashMap<>(Map.of(
                "token[x]", "odd-FAKE-bracket-key-0003", "token[0]", "odd-FAKE-bracket-key-0004")));

        var result = SecretRedactor.redactConfig(config, List.of());
        assertFalse(result.yaml().contains("odd-FAKE-bracket-key-0003"), result.yaml());
        assertFalse(result.yaml().contains("odd-FAKE-bracket-key-0004"), result.yaml());
    }

    /** "a.token" as one key and "token" under "a" read the same as dotted text; both are secrets. */
    @Test
    void twoSecretsWhosePathsReadTheSameAreBothRedacted() {
        var config = new SpawnConfig();
        config.setExtra("a.token", "flat-FAKE-same-path-0005");
        config.setExtra("a", new java.util.LinkedHashMap<>(Map.of("token", "nested-FAKE-same-path-0006")));

        var result = SecretRedactor.redactConfig(config, List.of());
        assertFalse(result.yaml().contains("flat-FAKE-same-path-0005"), result.yaml());
        assertFalse(result.yaml().contains("nested-FAKE-same-path-0006"), result.yaml());
        assertEquals(2, result.secretValues().size());
        assertEquals(java.util.Set.of("[\"a.token\"]", "a.token"), java.util.Set.copyOf(result.redactedPaths()),
                "the manifest tells the two apart");
        assertEquals("[\"a.token\"]", result.secretValues().get("flat-FAKE-same-path-0005"));
        assertEquals("a.token", result.secretValues().get("nested-FAKE-same-path-0006"));
    }

    /** A declared top-level key has no namespace for the deep pass to find it in: the exact path must hold. */
    @Test
    void aDeclaredSecretAtTheTopLevelIsRedacted() {
        var config = new SpawnConfig();
        config.setExtra("license", "lic-FAKE-top-level-0007");

        var result = SecretRedactor.redactConfig(config,
                List.of(new SecretRegistry.SecretLocation("license", "acme", "Acme licence")));
        assertFalse(result.yaml().contains("lic-FAKE-top-level-0007"), result.yaml());
        assertEquals(List.of("license"), result.redactedPaths());
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

    @Test
    void secretShapeIsRecognisedInsideOtherText() {
        assertTrue(SecretRedactor.hasSecretShape(GH_TOKEN));
        assertTrue(SecretRedactor.hasSecretShape("token=" + OAUTH + ";"));
        assertFalse(SecretRedactor.hasSecretShape("/home/agentuser/.claude/settings.json"));
        assertFalse(SecretRedactor.hasSecretShape(""));
        assertFalse(SecretRedactor.hasSecretShape(null));
    }

    @Test
    void manyKeyHeadersWithNoEndAreScrubbedInLinearTime() {
        // Guest output reaches the scrub (a build's log): the lazy match from each header to an end
        // that never comes made 400 KB of headers take seconds, so crafted output stalled a build
        var headers = "-----BEGIN PRIVATE KEY-----\n".repeat(15_000);
        var scrubbed = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> SecretRedactor.scrubText(headers, java.util.Map.of()).text());
        // Each header with nothing after it before the next is a key cut short: redacted, all of them
        org.junit.jupiter.api.Assertions.assertFalse(scrubbed.contains("BEGIN PRIVATE KEY"), scrubbed.substring(0, 200));
    }

    @Test
    void aTruncatedKeyFollowedByAnotherIsScrubbedWithIt() {
        // head -c 900 id_rsa; cat key2: the first key's body is as secret as the second's
        // The armour is put together at run time, so a secret scanner does not take this for a key
        var armour = "-----";
        var text = armour + "BEGIN PRIVATE KEY" + armour + "\ntruncated\n"
                + armour + "BEGIN RSA PRIVATE KEY" + armour + "\nMIIEsecret\n" + armour + "END RSA PRIVATE KEY" + armour + "\n";
        var scrubbed = SecretRedactor.scrubText(text, java.util.Map.of()).text();
        org.junit.jupiter.api.Assertions.assertFalse(scrubbed.contains("MIIEsecret"), scrubbed);
        org.junit.jupiter.api.Assertions.assertFalse(scrubbed.contains("truncated"), scrubbed);
        org.junit.jupiter.api.Assertions.assertEquals(2, SecretRedactor.scrubText(text, java.util.Map.of()).hits().get("private-key"));
    }

    @Test
    void aLoneTruncatedKeyIsScrubbedToTheEndOfTheText() {
        var armour = "-----";
        var text = "before\n" + armour + "BEGIN OPENSSH PRIVATE KEY" + armour + "\nb3BlbnNzaC1rZXktdjEAAAAAsecret";
        var scrubbed = SecretRedactor.scrubText(text, java.util.Map.of()).text();
        org.junit.jupiter.api.Assertions.assertEquals("before\n<isx:redacted:private-key>", scrubbed);
    }

    @Test
    void anotherKindOfBlockInsideAKeyDoesNotEndIt() {
        // Only another key's header ends one early; main's .*? took all of this too
        var armour = "-----";
        var text = armour + "BEGIN PRIVATE KEY" + armour + "\nAAA\n" + armour + "BEGIN CERTIFICATE" + armour + "\nCCC\n"
                + armour + "END CERTIFICATE" + armour + "\nBBBSECRET\n" + armour + "END PRIVATE KEY" + armour + "\nafter";
        org.junit.jupiter.api.Assertions.assertEquals("<isx:redacted:private-key>\nafter",
                SecretRedactor.scrubText(text, java.util.Map.of()).text());
    }

    @Test
    void aKeyHeaderWithManyWordsEndsTheKeyBeforeIt() {
        // A key's header is the same wherever it is matched: one that opens a key also ends a
        // truncated key before it, however many words it has
        var armour = "-----";
        var text = armour + "BEGIN PRIVATE KEY" + armour + "\ntruncated\n"
                + armour + "BEGIN " + "LONG ".repeat(10) + "PRIVATE KEY" + armour + "\nsecret\n";
        var result = SecretRedactor.scrubText(text, java.util.Map.of());
        org.junit.jupiter.api.Assertions.assertFalse(result.text().contains("secret"), result.text());
        org.junit.jupiter.api.Assertions.assertFalse(result.text().contains("truncated"), result.text());
        org.junit.jupiter.api.Assertions.assertEquals(2, result.hits().get("private-key"));
    }

    // ---- Guest text: redacted raw and as shown (#1878) ----

    @Test
    void aGuestsTextKeepsNoSecretThatShowingItHides() {
        // The armour is put together at run time, so a secret scanner does not take this for a key
        var armour = "-----";
        // A control byte becomes a space and ends the password; the raw text still shows the URL
        assertEquals("https://u:<isx:redacted:url-credentials>@host/x",
                SecretRedactor.scrubGuest("https://u:hunter2\u0001secretpw@host/x"));
        // A carriage return drops the key's BEGIN line; the raw text still shows where it starts
        var key = SecretRedactor.scrubGuest(armour + "BEGIN PRIVATE KEY" + armour + "\rcloning...\nbody\n" + armour + "END PRIVATE KEY" + armour + "\nafter\n");
        assertEquals("<isx:redacted:private-key>\nafter\n", key);
    }

    @Test
    void aGuestsTextKeepsNoTokenItsColourCutShort() {
        // Raw, the token stops at the escape; shown, the proof is whole: neither half is kept
        var half = "0123456789abcdef";
        var scrubbed = SecretRedactor.scrubGuest("T=sk-ant-oat01-isx_" + half + "\u001b[m\u001b[K" + half + " next");
        assertEquals("T=<isx:redacted:anthropic-key> next", scrubbed);
    }

    @Test
    void aGuestsTextIsScrubbedAsItIsShownWhenTheRawTextHidesNothing() {
        // Without a control, or when the shown scrub already covers the raw one, it is scrubText's
        var plain = "GH_TOKEN=gho_isx_" + "0123456789abcdef".repeat(2) + "\nAuthorization: Bearer abcdefgh12345\n";
        assertEquals(SecretRedactor.scrubText(plain, Map.of()).text(), SecretRedactor.scrubGuest(plain));
        var coloured = "GH_TOKEN=gho_\u001b[01;31m\u001b[Kisx\u001b[m\u001b[K_" + "0123456789abcdef".repeat(2);
        assertEquals("GH_TOKEN=gho_<isx:redacted:isx-proof>", SecretRedactor.scrubGuest(coloured));
        assertEquals("", SecretRedactor.scrubGuest());
    }

    @Test
    void aGuestsTextInPiecesIsScrubbedAsOneText() {
        // The armour is put together at run time, so a secret scanner does not take this for a key
        var armour = "-----";
        // A key printed on stdout with its end on stderr is one key; each piece is shown on its own
        assertEquals("$ cat\n<isx:redacted:private-key>\n",
                SecretRedactor.scrubGuest("$ cat\n", armour + "BEGIN PRIVATE KEY" + armour + "\nbody\n", armour + "END PRIVATE KEY" + armour + "\n"));
    }

    @Test
    void aControlInAnyPieceStillGetsTheRawScrub() {
        // Only text shown as printed skips the raw scrub: a control in a later piece does not
        assertEquals("plain\nhttps://u:<isx:redacted:url-credentials>@host\n",
                SecretRedactor.scrubGuest("plain\n", null, "https://u:hunter2\u0001secretpw@host\n"));
        assertEquals("plain\n", SecretRedactor.scrubGuest("plain\n", null));
    }

    @Test
    void aGuestsTextKeepsNoTokenOneLoneControlSplits() {
        // Raw, the byte ends the token; shown, the space it becomes does. Dropped, it is whole (#1888)
        var head = "ghp_" + "A".repeat(15);
        var tail = "A".repeat(16);
        for (var control : new String[] {"\u0000", "\u000e", "\u000f", "\b", "\u007f", "\u0085"}) {
            assertEquals("x <isx:redacted:github-token>\n", SecretRedactor.scrubGuest("x " + head + control + tail + "\n"));
        }
        // In pieces, a control ending one piece still joins it to the next
        assertEquals("x <isx:redacted:github-token>\n", SecretRedactor.scrubGuest("x " + head + "\u0000", tail + "\n"));
        // A few controls in a row are dropped together
        assertEquals("x <isx:redacted:github-token>\n", SecretRedactor.scrubGuest("x " + head + "\u000e\u000f" + tail + "\n"));
    }

    @Test
    void aGuestsTextKeepsNoTokenABackspaceOverstrikes() {
        // As man prints bold and underline: each character struck over, or an underscore under it.
        // Dropping the backspaces doubles every character; applying them shows the token (#1888)
        var token = "ghp_" + "A".repeat(20);
        var bold = new StringBuilder();
        var underlined = new StringBuilder();
        for (var c : token.toCharArray()) {
            bold.append(c).append('\b').append(c);
            underlined.append('_').append('\b').append(c);
        }
        assertEquals("x <isx:redacted:github-token> y", SecretRedactor.scrubGuest("x " + bold + " y"));
        assertEquals("x <isx:redacted:github-token> y", SecretRedactor.scrubGuest("x " + underlined + " y"));
        // Dropped, a backspace that struck out a character of the token still shows it
        assertEquals("x <isx:redacted:github-token> y", SecretRedactor.scrubGuest("x gh\bhp_" + "A".repeat(20) + " y"));
        assertEquals("x <isx:redacted:github-token> y", SecretRedactor.scrubGuest("x gh\bp_" + "A".repeat(20) + " y"));
        // A backspace moves the cursor back and leaves the cells to its right: the terminal shows
        // the dash overwritten and the token whole (#1895 review)
        assertEquals("x <isx:redacted:github-token>\n",
                SecretRedactor.scrubGuest("x ghp_" + "A".repeat(10) + "-" + "B".repeat(10) + "\b".repeat(11) + "A\n"));
        // What follows overwrites the cells after it, as on the terminal: " y" takes the first B,
        // which leaves ghp_ and eleven A, too short for a token, on the screen as in the view
        var overwritten = "x ghp_" + "A".repeat(10) + "-" + "B".repeat(10) + "\b".repeat(11) + "A y\n";
        assertEquals(BuildOutput.guestText(overwritten), SecretRedactor.scrubGuest(overwritten));
        // A tab moves to the next stop without writing, so the cursor counts it as the cells it skips
        assertEquals("x <isx:redacted:github-token>\n",
                SecretRedactor.scrubGuest("x ghp_AA\t" + "\b".repeat(8) + "A".repeat(20) + "\n"));
        // A backspace at the start of a line moves nothing: the token stays split, as it is shown
        var lineStart = SecretRedactor.scrubGuest("g\n\bhp_" + "A".repeat(20) + " y");
        assertEquals("g\n hp_" + "A".repeat(20) + " y", lineStart);
        // A character after a surrogate pair overwrites the whole pair, which a span then redacts.
        // U+10400 takes one cell, as most of the supplementary planes outside emoji and CJK do
        assertEquals("x <isx:redacted:github-token>",
                SecretRedactor.scrubGuest("x ghp_" + "A".repeat(10) + "\uD801\uDC00" + "B".repeat(10) + "\b".repeat(11) + "A"));
        // A combining mark shares the cell of the character before it: overwriting the dash takes
        // its accent too, and the token shows whole (#1895 review)
        assertEquals("x <isx:redacted:github-token>\n",
                SecretRedactor.scrubGuest("x ghp_" + "A".repeat(10) + "-\u0301" + "B".repeat(10) + "\b".repeat(11) + "A\n"));
        assertEquals("x <isx:redacted:github-token>\n",
                SecretRedactor.scrubGuest("x ghp_" + "A".repeat(10) + "-\u20DD" + "B".repeat(10) + "\b".repeat(11) + "A\n"));
        // One left on a character of the token draws no cell of its own, so the token is whole (#1900)
        assertEquals("x <isx:redacted:github-token>\n",
                SecretRedactor.scrubGuest("x ghp_" + "A".repeat(10) + "-" + "B".repeat(10) + "\b".repeat(11) + "A\u0301\n"));
        // A span the view alone finds that ends on a pair redacts both its halves, never one. The
        // armour is put together at run time, so a secret scanner does not take this for a key
        var armour = "-----";
        assertEquals("<isx:redacted:private-key>",
                SecretRedactor.scrubGuest(armour + "BEGIN RSA PRIVATE KEY--X\b---\nMIIabc\uD83D\uDE00"));
    }

    @Test
    void aGuestsTextKeepsNoTokenACursorSequenceSplits() {
        // Escapes are dropped from the shown text, but a terminal moves its cursor on these, and
        // what follows overwrites the dash: the token shows whole (#1900)
        var head = "x ghp_" + "A".repeat(10);
        var tail = "B".repeat(10);
        var token = "x <isx:redacted:github-token>\n";
        // Cursor left, as the issue found
        assertEquals(token, SecretRedactor.scrubGuest(head + "-\u001b[1D" + tail + "\n"));
        assertEquals(token, SecretRedactor.scrubGuest(head + "-\u009bD" + tail + "\n"));
        // To column 17, the dash's, from the end of the line
        assertEquals(token, SecretRedactor.scrubGuest(head + "-" + tail + "\u001b[17GA\n"));
        // From the start of the line, sixteen cells right. Shown, the line is its last frame, the
        // A that completes the token on the screen
        assertEquals("<isx:redacted:github-token>\n", SecretRedactor.scrubGuest(head + "-" + tail + "\r\u001b[16CA\n"));
        // Back to a saved position, in both forms
        assertEquals(token, SecretRedactor.scrubGuest(head + "\u001b7-" + tail + "\u001b8A\n"));
        assertEquals(token, SecretRedactor.scrubGuest(head + "\u001b[s-" + tail + "\u001b[uA\n"));
        // Up a line, and to the dash's column there
        assertEquals("x <isx:redacted:github-token>\n",
                SecretRedactor.scrubGuest(head + "-" + tail + "\nnext\u001b[1A\u001b[17GA\n"));
        // Back a tab stop, the dash's, and the 8-bit reverse index up to the token's line
        assertEquals(token, SecretRedactor.scrubGuest(head + "-\u001b[Z" + tail + "\n"));
        assertEquals(token, SecretRedactor.scrubGuest(head + "\n\u008d\u001b[17G" + tail + "\n"));
        // However far the cursor moved before or after, only written cells are kept, and all modelled
        var far = "\u001b[4000Gx\n".repeat(3);
        var before = SecretRedactor.scrubGuest(far + head + "-\u001b[1D" + tail + "\n");
        assertFalse(before.contains("AAAAAAAAAA") || before.contains("BBBBBBBBBB"), before);
        var after = SecretRedactor.scrubGuest(head + "-\u001b[1D" + tail + "\n" + far);
        assertFalse(after.contains("AAAAAAAAAA") || after.contains("BBBBBBBBBB"), after);
        // DECBI moves back a column, as xterm does
        assertEquals(token, SecretRedactor.scrubGuest(head + "-\u001b6" + tail + "\n"));
        // However far down the cursor went, a line stays its own: a later one, on the next row,
        // leaves the dash's cell to the backspaced A and the token whole
        var deep = "\u009b4096B".repeat(270_000) + "\n";
        var piled = SecretRedactor.scrubGuest(deep + head + "-" + tail + "\b".repeat(11) + "A\n" + "Q".repeat(16) + "-\n");
        assertFalse(piled.contains("AAAAAAAAAA") || piled.contains("BBBBBBBBBB"), piled.substring(0, Math.min(200, piled.length())));
        // REP draws the character last drawn again: nineteen A and one repeat make the token
        assertEquals("x <isx:redacted:github-token>\n", SecretRedactor.scrubGuest("x ghp_" + "A".repeat(19) + "\u001b[1b\n"));
        // A colour code moves nothing: the dash still splits the token, as on the screen
        var coloured = head + "-\u001b[1m" + tail + "\n";
        assertEquals(BuildOutput.guestText(coloured), SecretRedactor.scrubGuest(coloured));
    }

    @Test
    void aGuestsTextKeepsNoTokenAWideCharacterHides() {
        // A wide character takes two cells, so two backspaces after it put the cursor on it, and
        // what follows overwrites it: the token shows whole (#1900)
        assertEquals("x <isx:redacted:github-token>\n",
                SecretRedactor.scrubGuest("x ghp_" + "A".repeat(9) + "\u4e00\b\bAA" + "B".repeat(9) + "\n"));
        // Twelve backspaces put it on the second cell of the wide character, not on the dash:
        // the dash stays and splits the token, as on the screen (#1895 review)
        var dash = "x ghp_" + "A".repeat(10) + "-\u4e00" + "B".repeat(10) + "\b".repeat(12) + "AA\n";
        assertEquals(BuildOutput.guestText(dash), SecretRedactor.scrubGuest(dash));
    }

    @Test
    void aGuestsTextKeepsNoTokenAZeroWidthCharacterSplits() {
        // A zero-width format character or a combining mark draws no cell, so a terminal shows
        // one unbroken word, with or without a control byte around it (#1900)
        var half = "A".repeat(18);
        for (var zeroWidth : new String[] {"\u200b", "\u200c", "\u200d", "\u2060", "\ufeff", "\u0301", "\ud7b0"}) {
            assertEquals("x <isx:redacted:github-token>\n",
                    SecretRedactor.scrubGuest("x ghp_" + half + zeroWidth + half + "\n"), zeroWidth);
            // The backspace then counts no cell for it, and the dash is what it overwrites
            assertEquals("x <isx:redacted:github-token>\n",
                    SecretRedactor.scrubGuest("x ghp_" + "A".repeat(10) + "-" + zeroWidth + "B".repeat(10) + "\b".repeat(11) + "A\n"), zeroWidth);
        }
        // A mark written right after a backspace: wherever a terminal puts it, the token is whole
        assertEquals("x <isx:redacted:github-token>\n",
                SecretRedactor.scrubGuest("x ghp_" + "A".repeat(10) + "-\b\u0301A" + "B".repeat(10) + "\n"));
        // A soft hyphen is drawn one cell wide on most terminals and hidden on some: redacted
        assertEquals("x <isx:redacted:github-token>\n",
                SecretRedactor.scrubGuest("x ghp_" + "A".repeat(10) + "\u00ad" + "B".repeat(10) + "\n"));
    }

    @Test
    void aSecretTheShownTextDropsWholeLeavesNothingToRedact() {
        // Only what is shown can be kept: a URL a carriage return drew over is gone either way
        assertEquals("done", SecretRedactor.scrubGuest("https://u:hunter2\u0001pw@host\rdone"));
    }

    // ---- What a pattern's groups keep: fail closed (#1882 review) ----

    @Test
    void aKeepingMatchKeepsOnlyWhatItsGroupsFrame() {
        assertArrayEquals(new int[] {7, 13}, secretIn("(pre:)secret(@)", "xx pre:secret@ yy", 3));
        assertArrayEquals(new int[] {7, 13}, secretIn("(pre:)secret", "xx pre:secret yy", 3));
    }

    @Test
    void aKeepingMatchWhoseGroup1DoesNotOpenItIsRedactedWhole() {
        // A group 1 that starts later, or took no part, leaves no kept prefix to trust
        assertArrayEquals(new int[] {0, 11}, secretIn("x(pre:)secret", "xpre:secret", 0));
        assertArrayEquals(new int[] {0, 6}, secretIn("(pre:)?secret", "secret", 0));
    }

    @Test
    void aKeepingMatchWhoseGroup2DoesNotCloseItIsRedactedWhole() {
        // An inner group, a group 2 followed by more secret, or one that took no part
        assertArrayEquals(new int[] {0, 9}, secretIn("(pr(e))secret", "presecret!", 0, 9));
        assertArrayEquals(new int[] {0, 14}, secretIn("(pre)secret(@)tail", "presecret@tail", 0));
        assertArrayEquals(new int[] {0, 9}, secretIn("(pre)secret(@)?", "presecret", 0));
    }

    @Test
    void aKeepingMatchThatFramesNothingIsRedactedWhole() {
        // An empty secret would insert a marker and redact nothing
        assertArrayEquals(new int[] {0, 4}, secretIn("(pre)[a-z]*(@)", "pre@", 0));
    }

    @Test
    void aWholePatternRefusesGroupsWhereverItIsCompiled() {
        // ProofToken.ANY_PROOF is compiled in another file: a group added there must fail here
        assertThrows(IllegalArgumentException.class,
                () -> SecretRedactor.ScrubPattern.whole("isx-proof", java.util.regex.Pattern.compile("isx_([0-9a-f]{32})")));
        assertThrows(IllegalArgumentException.class, () -> SecretRedactor.ScrubPattern.whole("x", "(a)b"));
        assertEquals(0, SecretRedactor.ScrubPattern.whole("isx-proof", dev.incusspawn.proxy.ProofToken.ANY_PROOF)
                .pattern().matcher("").groupCount());
    }

    /** The secret {@code regex}'s first match from {@code from} holds in {@code text}, as start and end. */
    private static int[] secretIn(String regex, String text, int from) {
        return secretIn(regex, text, from, text.length());
    }

    private static int[] secretIn(String regex, String text, int from, int to) {
        var matcher = java.util.regex.Pattern.compile(regex).matcher(text).region(from, to);
        assertTrue(matcher.find(), regex);
        return SecretRedactor.ScrubPattern.secret(matcher);
    }

    @Test
    void scrubbingIsLinearInItsMatches() {
        // A support bundle or failure report can hold a whole file of tokens: each pattern's
        // spans are merged with the earlier ones in one pass, never rescanned per match (#1882 review)
        int each = 100_000;
        var text = new StringBuilder();
        for (int i = 0; i < each; i++) {
            text.append("a sk-ant-").append("A".repeat(25)).append('\n');
            text.append("b ghp_").append("B".repeat(25)).append('\n');
        }
        long work = SecretRedactor.scrubWork(text.toString());
        // Each anthropic span once, then the merge visits every anthropic and github one: 1.5 visits
        // per match. At least one per match, so a merge that stops counting cannot pass for linear
        assertTrue(work >= 2L * each && work <= 2L * 2 * each, "visited " + work + " spans for " + 2 * each + " matches");
        var scrubbed = SecretRedactor.scrubText(text.toString(), Map.of());
        assertEquals(Map.of("anthropic-key", each, "github-token", each), scrubbed.hits());
    }

    @Test
    void aMatchThatTakesInAMarkerReplacesItsSpan() {
        // The URL pattern runs after the token one and swallows its marker: one span is left
        assertEquals("https://u:<isx:redacted:url-credentials>@host",
                SecretRedactor.scrubGuest("https://u:pre\u0001ghp_" + "C".repeat(25) + "@host"));
        assertEquals("https://u:<isx:redacted:url-credentials>@host",
                SecretRedactor.scrubText("https://u:pre-ghp_" + "C".repeat(25) + "-post@host", Map.of()).text());
    }
}
