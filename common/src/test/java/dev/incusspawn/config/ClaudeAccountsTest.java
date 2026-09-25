package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.config.SpawnConfig.ClaudeAccount;
import dev.incusspawn.config.SpawnConfig.ClaudeAccountType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeAccountsTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void legacyFlatConfigStillResolves() throws Exception {
        var yaml = """
                claude:
                  useVertex: false
                  oauthToken: "sk-ant-oat01-abc"
                """;
        var claude = YAML.readValue(yaml, SpawnConfig.class).getClaude();
        assertTrue(claude.hasAuth());
        assertTrue(claude.isOauthMode());
        assertEquals("sk-ant-oat01-abc", claude.getOauthToken());
        assertEquals(SpawnConfig.ClaudeConfig.LEGACY_ACCOUNT_NAME, claude.accountName());
    }

    @Test
    void legacyVertexConfigStillResolves() throws Exception {
        var yaml = """
                claude:
                  useVertex: true
                  cloudMlRegion: europe-west1
                  vertexProjectId: acme
                """;
        var claude = YAML.readValue(yaml, SpawnConfig.class).getClaude();
        assertTrue(claude.isUseVertex());
        assertEquals("europe-west1", claude.getCloudMlRegion());
        assertEquals("acme", claude.getVertexProjectId());
    }

    @Test
    void namedAccountsResolveByDefault() throws Exception {
        var yaml = """
                claude:
                  accounts:
                    personal:
                      type: oauth
                      oauthToken: "sk-ant-oat01-abc"
                    work:
                      type: vertex
                      cloudMlRegion: europe-west1
                      vertexProjectId: acme
                  default: personal
                """;
        var claude = YAML.readValue(yaml, SpawnConfig.class).getClaude();
        assertEquals("personal", claude.accountName());
        assertTrue(claude.isOauthMode());
        assertEquals(2, claude.effectiveAccounts().size());
    }

    @Test
    void directApiFallsBackPastAnOauthDefault() throws Exception {
        var yaml = """
                claude:
                  accounts:
                    personal:
                      type: oauth
                      oauthToken: "sk-ant-oat01-abc"
                    console:
                      type: api-key
                      apiKey: "sk-ant-api03-xyz"
                  default: personal
                """;
        var claude = YAML.readValue(yaml, SpawnConfig.class).getClaude();
        // Instances keep the default account...
        assertEquals("personal", claude.accountName());
        // ...while a direct API call uses the first account that can actually serve one.
        assertEquals("console", claude.accountNameFor(ClaudeAccount::servesDirectApi));
    }

    @Test
    void oauthOnlyConfigServesNoDirectApi() throws Exception {
        var yaml = """
                claude:
                  accounts:
                    personal:
                      type: oauth
                      oauthToken: "sk-ant-oat01-abc"
                """;
        var claude = YAML.readValue(yaml, SpawnConfig.class).getClaude();
        assertNull(claude.accountFor(ClaudeAccount::servesDirectApi));
    }

    @Test
    void typeIsInferredWhenOmitted() throws Exception {
        var yaml = """
                claude:
                  accounts:
                    console:
                      apiKey: "sk-ant-api03-xyz"
                """;
        var claude = YAML.readValue(yaml, SpawnConfig.class).getClaude();
        assertEquals(ClaudeAccountType.API_KEY, claude.account().effectiveType());
        assertEquals("sk-ant-api03-xyz", claude.getApiKey());
    }

    @Test
    void incompleteAccountsAreIgnored() throws Exception {
        var yaml = """
                claude:
                  accounts:
                    broken:
                      type: vertex
                      cloudMlRegion: europe-west1
                    console:
                      type: api-key
                      apiKey: "sk-ant-api03-xyz"
                """;
        var claude = YAML.readValue(yaml, SpawnConfig.class).getClaude();
        assertEquals(1, claude.effectiveAccounts().size());
        assertEquals("console", claude.accountName());
    }

    @Test
    void allAccountsIncludesIncompleteEntries() throws Exception {
        var yaml = """
                claude:
                  accounts:
                    broken:
                      type: vertex
                      cloudMlRegion: europe-west1
                    console:
                      type: api-key
                      apiKey: "sk-ant-api03-xyz"
                """;
        var claude = YAML.readValue(yaml, SpawnConfig.class).getClaude();
        var all = claude.allAccounts();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("broken"));
        assertTrue(all.containsKey("console"));
        assertFalse(all.get("broken").isComplete());
        assertTrue(all.get("console").isComplete());
    }

    @Test
    void allAccountsIncludesUnknownTypeEntries() throws Exception {
        var yaml = """
                claude:
                  accounts:
                    weird:
                      type: carrier-pigeon
                    console:
                      type: api-key
                      apiKey: "sk-ant-api03-xyz"
                """;
        var claude = YAML.readValue(yaml, SpawnConfig.class).getClaude();
        assertEquals(1, claude.effectiveAccounts().size());
        assertEquals(2, claude.allAccounts().size());
        assertEquals("incomplete account", claude.allAccounts().get("weird").describe());
    }

    /**
     * #742: an incomplete account is easy to miss in 'isx init', so pinning one must say it is
     * incomplete, and why -- not that it does not exist. The same answer comes back whichever
     * way the pin is checked: the typed API, or the resolver every namespace shares.
     */
    @Test
    void pinningAnIncompleteAccountSaysWhyRatherThanThatItIsMissing() throws Exception {
        var config = YAML.readValue("""
                claude:
                  accounts:
                    broken:
                      type: vertex
                      cloudMlRegion: europe-west1
                    console:
                      type: api-key
                      apiKey: "sk-ant-api03-xyz"
                  default: broken
                """, SpawnConfig.class);
        assertEquals("console", config.getClaude().accountName(), "an unusable default is skipped");

        var typed = assertThrows(AccountResolver.UnknownAccountException.class,
                () -> config.getClaude().accountNamed("broken"));
        var generic = assertThrows(AccountResolver.UnknownAccountException.class,
                () -> AccountSelection.validate(config, java.util.Map.of("claude", "broken")));
        for (var e : java.util.List.of(typed, generic)) {
            assertTrue(e.getMessage().contains("incomplete"), e.getMessage());
            assertTrue(e.getMessage().contains("vertex"), "names what is wrong: " + e.getMessage());
        }

        var missing = assertThrows(AccountResolver.UnknownAccountException.class,
                () -> config.getClaude().accountNamed("ghost"));
        assertTrue(missing.getMessage().contains("not configured"), missing.getMessage());
        assertFalse(missing.getMessage().contains("incomplete"), missing.getMessage());
    }

    /** #773: the flat credential is 'default' to the shared resolver too, not only to ClaudeConfig. */
    @Test
    void aFlatClaudeCredentialIsTheDefaultAccountEverywhere() throws Exception {
        var config = YAML.readValue("""
                claude:
                  apiKey: "sk-ant-api03-flat"
                """, SpawnConfig.class);
        assertDoesNotThrow(() -> AccountSelection.validate(config, java.util.Map.of("claude", "default")));
        var listing = AccountSelection.listAccounts(config, "claude");
        assertEquals(java.util.List.of("default"), listing.names());
        assertEquals("default", listing.defaultName());
        assertEquals("sk-ant-api03-flat", config.getClaude().accountNamed("default").getApiKey());
    }

    @Test
    void writingAnAccountDropsTheLegacyLayout() throws Exception {
        var yaml = """
                claude:
                  oauthToken: "sk-ant-oat01-abc"
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        config.getClaude().setSingleAccount("personal", ClaudeAccount.ofOauth("sk-ant-oat01-abc"));
        var written = YAML.writeValueAsString(config);
        assertTrue(written.contains("accounts:"), written);
        assertTrue(written.contains("personal:"), written);
        assertTrue(written.contains("default: \"personal\"") || written.contains("default: personal"), written);
        // The pre-accounts keys must not linger alongside the new shape. The account's own
        // oauthToken is nested deeper, so match the two-space indent directly under 'claude:'.
        assertFalse(written.contains("\n  oauthToken:"), written);
        assertFalse(written.contains("\n  useVertex:"), written);
    }

    @Test
    void accountsRoundTrip() throws Exception {
        var config = new SpawnConfig();
        config.getClaude().putAccount("personal", ClaudeAccount.ofOauth("sk-ant-oat01-abc"));
        config.getClaude().putAccount("console", ClaudeAccount.ofApiKey("sk-ant-api03-xyz"));
        var reloaded = YAML.readValue(YAML.writeValueAsString(config), SpawnConfig.class).getClaude();
        assertEquals("personal", reloaded.accountName());
        assertEquals("console", reloaded.accountNameFor(ClaudeAccount::servesDirectApi));
        assertEquals(ClaudeAccountType.OAUTH, reloaded.account().effectiveType());
    }

    @Test
    void theTypeFieldIsWrittenNotJustInferred() throws Exception {
        // ClaudeAccount has no getType() -- 'type' reaches the file only through
        // @JsonAutoDetect(fieldVisibility = ANY). Round-tripping alone would not catch its
        // loss, because effectiveType() re-infers OAUTH from the token either way.
        var config = new SpawnConfig();
        config.getClaude().putAccount("personal", ClaudeAccount.ofOauth("sk-ant-oat01-abc"));
        config.getClaude().putAccount("console", ClaudeAccount.ofApiKey("sk-ant-api03-xyz"));
        var written = YAML.writeValueAsString(config);
        assertTrue(written.contains("type: \"oauth\"") || written.contains("type: oauth"), written);
        assertTrue(written.contains("type: \"api-key\"") || written.contains("type: api-key"), written);
    }

    @Test
    void derivedAccessorsAreNotSerialized() throws Exception {
        // isOauthMode() and hasAuth() describe the resolved account; they are not state.
        // Before getterVisibility = NONE, 'oauthMode' was auto-detected as an is-getter and
        // written into every config.yaml. Re-detecting it would resurrect that phantom key.
        var config = new SpawnConfig();
        config.getClaude().putAccount("personal", ClaudeAccount.ofOauth("sk-ant-oat01-abc"));
        var written = YAML.writeValueAsString(config);
        assertFalse(written.contains("oauthMode"), written);
        assertFalse(written.contains("auth:"), written);
    }

    @Test
    void aPhantomOauthModeKeyFromAnOlderIsxIsIgnored() throws Exception {
        // Configs written before getterVisibility = NONE carry 'oauthMode'. It must stay inert
        // rather than failing the load and discarding the file.
        var yaml = """
                claude:
                  oauthMode: true
                  apiKey: "sk-ant-api03-xyz"
                """;
        var claude = YAML.readValue(yaml, SpawnConfig.class).getClaude();
        assertEquals(ClaudeAccountType.API_KEY, claude.account().effectiveType());
        assertFalse(claude.isOauthMode());
    }

    @Test
    void addingAnAccountKeepsAPreAccountsCredential() throws Exception {
        // Regression: 'add another account' used to wipe the flat credential and re-point the
        // default at the newcomer, losing the token and silently switching every instance.
        var yaml = """
                claude:
                  oauthToken: "sk-ant-oat01-abc"
                """;
        var claude = YAML.readValue(yaml, SpawnConfig.class).getClaude();
        claude.putAccount("console", ClaudeAccount.ofApiKey("sk-ant-api03-xyz"));

        assertEquals(2, claude.effectiveAccounts().size());
        assertEquals(SpawnConfig.ClaudeConfig.LEGACY_ACCOUNT_NAME, claude.accountName(),
                "the pre-existing credential must stay the default");
        assertEquals("sk-ant-oat01-abc", claude.getOauthToken());
        assertEquals("console", claude.accountNameFor(ClaudeAccount::servesDirectApi));
    }

    @Test
    void addingASecondNamedAccountDoesNotRepointTheDefault() {
        var claude = new SpawnConfig().getClaude();
        claude.putAccount("personal", ClaudeAccount.ofOauth("sk-ant-oat01-abc"));
        claude.putAccount("console", ClaudeAccount.ofApiKey("sk-ant-api03-xyz"));
        assertEquals("personal", claude.accountName());
    }

    @Test
    void incompleteLegacyVertexStaysDiagnosable() throws Exception {
        // ProxyMain reports "Vertex AI enabled but region or project ID not configured" off
        // isUseVertex(); filtering this account out would turn that into "no credentials".
        var yaml = """
                claude:
                  useVertex: true
                  cloudMlRegion: europe-west1
                """;
        var claude = YAML.readValue(yaml, SpawnConfig.class).getClaude();
        assertTrue(claude.hasAuth());
        assertTrue(claude.isUseVertex());
        assertEquals("", claude.getVertexProjectId());
        // ...but it cannot answer a direct API call, so it is never selected for one.
        assertFalse(claude.account().isComplete());
        assertNull(claude.accountFor(ClaudeAccount::servesDirectApi));
    }

    @Test
    void unknownTypeIsIgnoredRatherThanDiscardingTheConfig() throws Exception {
        // A typo in one account's type must not make Jackson reject the document: load() falls
        // back to a blank SpawnConfig, and the next save would overwrite the real file.
        var yaml = """
                claude:
                  accounts:
                    typo:
                      type: apikey
                      apiKey: "sk-ant-api03-xyz"
                github:
                  token: "ghp_keepme"
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        assertEquals("ghp_keepme", config.getGithub().getToken(), "the rest of the config must survive");
        // The fields still identify it, so the account stays usable.
        assertEquals(ClaudeAccountType.API_KEY, config.getClaude().account().effectiveType());
    }

    @Test
    void unknownTypeWithNothingToInferFromIsDropped() throws Exception {
        var yaml = """
                claude:
                  accounts:
                    weird:
                      type: carrier-pigeon
                """;
        var claude = YAML.readValue(yaml, SpawnConfig.class).getClaude();
        assertTrue(claude.effectiveAccounts().isEmpty());
    }
}
