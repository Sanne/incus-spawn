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
    void unknownTypeIsRejected() {
        var yaml = """
                claude:
                  accounts:
                    weird:
                      type: carrier-pigeon
                      apiKey: "x"
                """;
        var e = assertThrows(Exception.class, () -> YAML.readValue(yaml, SpawnConfig.class));
        assertTrue(e.getMessage().contains("carrier-pigeon"), e.getMessage());
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
}
