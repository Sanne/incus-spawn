package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.config.SpawnConfig.ClaudeAccountType;
import dev.incusspawn.proxy.ProxyCredentials;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backwards compatibility for config.yaml files written before {@code claude.accounts} existed.
 *
 * <p>The inputs here are spelled out the way an older isx actually wrote them, which is not the
 * minimal form a hand-written file would use: {@code ClaudeConfig} had no {@code @JsonInclude},
 * so every field was written even when empty, and {@code isOauthMode()} was auto-detected as an
 * is-getter and persisted as a phantom {@code oauthMode} key. Both must stay readable.
 *
 * <p>The round-trip assertions matter as much as the resolution ones: a file is only migrated
 * into the accounts shape when credentials next change through {@code isx init}, so merely
 * loading and saving an old config (which many commands do) must leave its shape alone.
 */
class ClaudeLegacyConfigCompatTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** A flat config as an older isx wrote it: all fields present, plus the phantom getter key. */
    private static String legacyFile(String body) {
        return """
                host-paths:
                  - ~/projects
                repo-paths:
                  quarkus: ~/work/quarkus
                github:
                  token: "ghp_legacy"
                claude:
                """ + body;
    }

    private static SpawnConfig load(String yaml) throws Exception {
        return YAML.readValue(yaml, SpawnConfig.class);
    }

    @Test
    void legacyApiKeyFileResolvesAndKeepsItsShape() throws Exception {
        var config = load(legacyFile("""
                  useVertex: false
                  cloudMlRegion: ""
                  vertexProjectId: ""
                  apiKey: "sk-ant-api03-real"
                  oauthToken: ""
                  oauthMode: false
                """));
        var claude = config.getClaude();

        assertTrue(claude.hasAuth());
        assertFalse(claude.isOauthMode());
        assertFalse(claude.isUseVertex());
        assertEquals("sk-ant-api03-real", claude.getApiKey());
        assertEquals(ClaudeAccountType.API_KEY, claude.account().effectiveType());

        // Unrelated sections are untouched by the account model.
        assertEquals("ghp_legacy", config.getGithub().getToken());
        assertEquals(java.util.List.of("~/projects"), config.getHostPaths());

        assertStillFlat(config);
    }

    @Test
    void legacyOauthFileResolvesAndKeepsItsShape() throws Exception {
        var config = load(legacyFile("""
                  useVertex: false
                  apiKey: ""
                  oauthToken: "sk-ant-oat01-real"
                  oauthMode: true
                """));
        var claude = config.getClaude();

        assertTrue(claude.isOauthMode());
        assertEquals("sk-ant-oat01-real", claude.getOauthToken());
        assertEquals("", claude.getApiKey());
        assertStillFlat(config);
    }

    @Test
    void legacyVertexFileResolvesAndKeepsItsShape() throws Exception {
        var config = load(legacyFile("""
                  useVertex: true
                  cloudMlRegion: "europe-west1"
                  vertexProjectId: "acme-prod"
                  apiKey: ""
                  oauthToken: ""
                  oauthMode: false
                """));
        var claude = config.getClaude();

        assertTrue(claude.isUseVertex());
        assertEquals("europe-west1", claude.getCloudMlRegion());
        assertEquals("acme-prod", claude.getVertexProjectId());
        assertStillFlat(config);
    }

    /**
     * Vertex outranked both other credentials before the accounts change
     * ({@code isOauthMode()} was {@code !useVertex && ...}), and every consumer branches
     * vertex-then-oauth-then-key. A file carrying leftovers from an earlier auth mode must
     * therefore resolve to Vertex, exactly as it used to.
     */
    @Test
    void legacyPriorityIsUnchangedWhenSeveralCredentialsLinger() throws Exception {
        var vertexWins = load(legacyFile("""
                  useVertex: true
                  cloudMlRegion: "europe-west1"
                  vertexProjectId: "acme-prod"
                  apiKey: "sk-ant-api03-stale"
                  oauthToken: "sk-ant-oat01-stale"
                """)).getClaude();
        assertTrue(vertexWins.isUseVertex());
        assertFalse(vertexWins.isOauthMode());

        var oauthWins = load(legacyFile("""
                  useVertex: false
                  apiKey: "sk-ant-api03-stale"
                  oauthToken: "sk-ant-oat01-real"
                """)).getClaude();
        assertTrue(oauthWins.isOauthMode());
        assertEquals("sk-ant-oat01-real", oauthWins.getOauthToken());
    }

    /** The proxy is the consumer that actually ships these values into instances. */
    @Test
    void legacyFilesReachTheProxyUnchanged() throws Exception {
        var apiKey = ProxyCredentials.fromConfig(load(legacyFile("""
                  apiKey: "sk-ant-api03-real"
                """)));
        assertEquals("sk-ant-api03-real", apiKey.anthropicApiKey());
        assertEquals("", apiKey.oauthToken());
        assertFalse(apiKey.useVertex());

        var vertex = ProxyCredentials.fromConfig(load(legacyFile("""
                  useVertex: true
                  cloudMlRegion: "europe-west1"
                  vertexProjectId: "acme-prod"
                """)));
        assertTrue(vertex.useVertex());
        assertEquals("europe-west1", vertex.vertexRegion());
        assertEquals("acme-prod", vertex.vertexProjectId());
    }

    @Test
    void anEmptyClaudeSectionIsStillUnconfigured() throws Exception {
        var claude = load(legacyFile("""
                  useVertex: false
                  cloudMlRegion: ""
                  vertexProjectId: ""
                  apiKey: ""
                  oauthToken: ""
                  oauthMode: false
                """)).getClaude();
        assertFalse(claude.hasAuth());
        assertNull(claude.account());
        assertEquals("", claude.accountName());
    }

    /**
     * Saving a legacy file must not migrate it: no accounts block appears, and the credential
     * still round-trips through the flat keys.
     */
    private static void assertStillFlat(SpawnConfig config) throws Exception {
        var written = YAML.writeValueAsString(config);
        assertFalse(written.contains("accounts:"), "legacy file was silently migrated:\n" + written);
        assertFalse(written.contains("default:"), "legacy file gained an account default:\n" + written);

        var reloaded = YAML.readValue(written, SpawnConfig.class).getClaude();
        var original = config.getClaude();
        assertEquals(original.isUseVertex(), reloaded.isUseVertex());
        assertEquals(original.isOauthMode(), reloaded.isOauthMode());
        assertEquals(original.getApiKey(), reloaded.getApiKey());
        assertEquals(original.getOauthToken(), reloaded.getOauthToken());
        assertEquals(original.getCloudMlRegion(), reloaded.getCloudMlRegion());
        assertEquals(original.getVertexProjectId(), reloaded.getVertexProjectId());
        assertEquals(original.accountName(), reloaded.accountName());
    }
}
