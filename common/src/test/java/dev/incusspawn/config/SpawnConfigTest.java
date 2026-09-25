package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpawnConfigTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void deserializeWithHostPath() throws Exception {
        var yaml = """
                host-path: ~/projects
                repo-paths:
                  quarkus: ~/work/quarkus
                  hibernate: /opt/hibernate
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        assertEquals("~/projects", config.getHostPath());
        assertEquals(java.util.List.of("~/projects"), config.getHostPaths());
        assertEquals(2, config.getRepoPaths().size());
        assertEquals("~/work/quarkus", config.getRepoPaths().get("quarkus"));
        assertEquals("/opt/hibernate", config.getRepoPaths().get("hibernate"));
    }

    @Test
    void deserializeWithHostPaths() throws Exception {
        var yaml = """
                host-paths:
                  - ~/projects
                  - ~/workspace
                repo-paths:
                  quarkus: ~/work/quarkus
                  hibernate: /opt/hibernate
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        assertEquals(2, config.getHostPaths().size());
        assertEquals("~/projects", config.getHostPaths().get(0));
        assertEquals("~/workspace", config.getHostPaths().get(1));
        assertEquals(java.util.List.of("~/projects", "~/workspace"), config.getHostPaths());
        assertEquals(2, config.getRepoPaths().size());
        assertEquals("~/work/quarkus", config.getRepoPaths().get("quarkus"));
        assertEquals("/opt/hibernate", config.getRepoPaths().get("hibernate"));
    }

    @Test
    void deserializeWithoutNewFields() throws Exception {
        var yaml = """
                claude:
                  apiKey: test-key
                github:
                  token: gh-token
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        assertEquals("", config.getHostPath());
        assertTrue(config.getHostPaths().isEmpty());
        assertTrue(config.getRepoPaths().isEmpty());
        assertEquals("test-key", config.getClaude().getApiKey());
        assertEquals("gh-token", config.getGithub().getToken());
    }

    @Test
    void deserializeEmptyYaml() throws Exception {
        var config = YAML.readValue("{}", SpawnConfig.class);
        assertEquals("", config.getHostPath());
        assertTrue(config.getHostPaths().isEmpty());
        assertTrue(config.getRepoPaths().isEmpty());
    }

    @Test
    void settersHandleNull() {
        var config = new SpawnConfig();
        config.setHostPath(null);
        assertEquals("", config.getHostPath());
        config.setHostPaths(null);
        assertTrue(config.getHostPaths().isEmpty());
        config.setRepoPaths(null);
        assertTrue(config.getRepoPaths().isEmpty());
    }

    @Test
    void bothHostPathAndHostPathsMergedOnDeserialize() throws Exception {
        var yaml = """
                host-path: ~/projects
                host-paths:
                  - ~/workspace
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        config.migrateHostPath();
        assertEquals("", config.getHostPath());
        assertEquals(java.util.List.of("~/projects", "~/workspace"), config.getHostPaths());
    }

    @Test
    void deserializeIncusBridgeGateway() throws Exception {
        var yaml = """
                incus-bridge-gateway: "10.166.11.1"
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        assertEquals("10.166.11.1", config.getIncusBridgeGateway());
    }

    @Test
    void incusBridgeGatewayDefaultsToEmpty() throws Exception {
        var config = YAML.readValue("{}", SpawnConfig.class);
        assertEquals("", config.getIncusBridgeGateway());
    }

    @Test
    void incusBridgeGatewaySetterHandlesNull() {
        var config = new SpawnConfig();
        config.setIncusBridgeGateway(null);
        assertEquals("", config.getIncusBridgeGateway());
        config.setIncusBridgeGateway("10.1.2.3");
        assertEquals("10.1.2.3", config.getIncusBridgeGateway());
    }

    @Test
    void tuiLiveRefreshDefaultsToOn() throws Exception {
        assertTrue(YAML.readValue("{}", SpawnConfig.class).tuiLiveRefreshEnabled());
    }

    @Test
    void tuiLiveRefreshCanBeTurnedOff() throws Exception {
        assertFalse(YAML.readValue("tui-live-refresh: false", SpawnConfig.class).tuiLiveRefreshEnabled());
        assertTrue(YAML.readValue("tui-live-refresh: true", SpawnConfig.class).tuiLiveRefreshEnabled());
    }

    @Test
    void tuiLiveRefreshCanBeRemoved() throws Exception {
        // removeConfigPath rebuilds the object via copyFrom; a field it skips keeps its old value,
        // so removing the switch would silently leave live refresh off.
        var config = YAML.readValue("""
                tui-live-refresh: false
                incus-bridge-gateway: "10.1.2.3"
                """, SpawnConfig.class);
        config.removeConfigPath("incus-bridge-gateway");
        assertFalse(config.tuiLiveRefreshEnabled(), "an unrelated removal must not touch it");
        config.removeConfigPath("tui-live-refresh");
        assertTrue(config.tuiLiveRefreshEnabled(), "removing it restores the default");
    }

    @Test
    void tuiLiveRefreshDefaultIsNotWrittenBack() throws Exception {
        assertFalse(YAML.writeValueAsString(new SpawnConfig()).contains("live-refresh"));
        var off = new SpawnConfig();
        off.setTuiLiveRefresh(false);
        var yaml = YAML.writeValueAsString(off);
        assertTrue(yaml.contains("tui-live-refresh: false"), yaml);
        assertFalse(YAML.readValue(yaml, SpawnConfig.class).tuiLiveRefreshEnabled());
    }

    @Test
    void deserializeFeatures() throws Exception {
        var yaml = """
                features:
                  - openai
                  - aider
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        assertEquals(java.util.List.of("openai", "aider"), config.getFeatures());
        assertTrue(config.isFeatureEnabled("openai"));
        assertTrue(config.isFeatureEnabled("aider"));
        assertFalse(config.isFeatureEnabled("unknown"));
    }

    @Test
    void featuresDefaultsToEmpty() throws Exception {
        var config = YAML.readValue("{}", SpawnConfig.class);
        assertTrue(config.getFeatures().isEmpty());
        assertFalse(config.isFeatureEnabled("openai"));
    }

    @Test
    void openaiFeatureImplicitlyEnabledWhenKeyConfigured() throws Exception {
        var yaml = """
                openai:
                  apiKey: sk-test-key
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        assertTrue(config.getFeatures().isEmpty());
        assertTrue(config.isFeatureEnabled("openai"));
    }

    @Test
    void openaiFeatureNotImplicitlyEnabledWithoutKey() throws Exception {
        var config = YAML.readValue("{}", SpawnConfig.class);
        assertFalse(config.isFeatureEnabled("openai"));
    }

    @Test
    void featuresSetterHandlesNull() {
        var config = new SpawnConfig();
        config.setFeatures(null);
        assertTrue(config.getFeatures().isEmpty());
    }

    @Test
    void deserializeSearchPaths() throws Exception {
        var yaml = """
                searchPaths:
                  - ~/my-templates
                  - /absolute/path
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        var paths = config.getSearchPaths();
        assertEquals(2, paths.size());
        assertEquals("~/my-templates", paths.get(0));
        assertEquals("/absolute/path", paths.get(1));
    }

    @Test
    void credentialSettersStripWhitespace() {
        var config = new SpawnConfig();
        config.getClaude().setOauthToken("  sk-ant-oat01-abc\n");
        config.getGithub().setToken("\tghp_abc ");
        assertEquals("sk-ant-oat01-abc", config.getClaude().getOauthToken());
        assertEquals("ghp_abc", config.getGithub().getToken());
    }

    @Test
    void deserializeRepairsPaddedCredential() throws Exception {
        // A config.yaml written by an isx that did not trim pasted secrets is healed on load,
        // so the proxy never injects a credential with stray whitespace.
        var yaml = """
                claude:
                  oauthToken: "sk-ant-oat01-abc "
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        assertEquals("sk-ant-oat01-abc", config.getClaude().getOauthToken());
    }

    @Test
    void placeholderOauthTokenCarriesThePrefix() {
        assertTrue(SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN
                .startsWith(SpawnConfig.ClaudeConfig.OAUTH_TOKEN_PREFIX));
    }

    @Test
    void setConfigByPathUpdatesTypedFields() {
        var config = new SpawnConfig();
        config.setConfigByPath("bob.apiKey", "test-key");
        assertEquals("test-key", config.getBob().getApiKey());
        assertTrue(config.getBob().hasAuth());
    }

    @Test
    void setConfigByPathUpdatesExtras() {
        var config = new SpawnConfig();
        config.setConfigByPath("myTool.secret", "s3cret");
        assertEquals("s3cret", config.getExtras().get("myTool")
                instanceof java.util.Map m ? m.get("secret") : null);
    }

    // --- host-path migration tests ---

    @Test
    void migrateHostPathMovesToHostPaths() {
        var config = new SpawnConfig();
        config.setHostPath("~/projects");
        config.migrateHostPath();
        assertEquals("", config.getHostPath());
        assertEquals(java.util.List.of("~/projects"), config.getHostPaths());
    }

    @Test
    void migrateHostPathNoOpWhenAlreadyEmpty() {
        var config = new SpawnConfig();
        config.migrateHostPath();
        assertEquals("", config.getHostPath());
        assertTrue(config.getHostPaths().isEmpty());
    }

    @Test
    void migrateHostPathNoOpWhenHostPathsAlreadySet() {
        var config = new SpawnConfig();
        config.setHostPaths(java.util.List.of("~/workspace"));
        config.migrateHostPath();
        assertEquals("", config.getHostPath());
        assertEquals(java.util.List.of("~/workspace"), config.getHostPaths());
    }

    @Test
    void migrateHostPathMergesBothWithDedup() {
        var config = new SpawnConfig();
        config.setHostPath("~/old");
        config.setHostPaths(java.util.List.of("~/new"));
        config.migrateHostPath();
        assertEquals("", config.getHostPath());
        assertEquals(java.util.List.of("~/old", "~/new"), config.getHostPaths());
    }

    @Test
    void migrateHostPathDeduplicatesWhenAlreadyInList() {
        var config = new SpawnConfig();
        config.setHostPath("~/same");
        config.setHostPaths(java.util.List.of("~/same", "~/other"));
        config.migrateHostPath();
        assertEquals("", config.getHostPath());
        assertEquals(java.util.List.of("~/same", "~/other"), config.getHostPaths());
    }

    @Test
    void serializationOmitsHostPath() throws Exception {
        var config = new SpawnConfig();
        config.setHostPaths(java.util.List.of("~/projects"));
        var yaml = YAML.writeValueAsString(config);
        assertFalse(yaml.contains("host-path:"), "host-path (singular) should not appear in output");
        assertTrue(yaml.contains("host-paths:"), "host-paths (plural) should appear in output");
    }

    @Test
    void legacyHostPathRoundTripsToHostPaths() throws Exception {
        var yaml = """
                host-path: ~/projects
                incus-bridge-gateway: "10.166.11.1"
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        assertEquals("~/projects", config.getHostPath());
        assertEquals(java.util.List.of("~/projects"), config.getHostPaths());

        config.migrateHostPath();
        assertEquals("", config.getHostPath());
        assertEquals(java.util.List.of("~/projects"), config.getHostPaths());

        var written = YAML.writeValueAsString(config);
        assertFalse(written.contains("host-path:"), "re-serialized config should not contain host-path");
        assertTrue(written.contains("host-paths:"));

        var reloaded = YAML.readValue(written, SpawnConfig.class);
        assertEquals("", reloaded.getHostPath());
        assertEquals(java.util.List.of("~/projects"), reloaded.getHostPaths());
        assertEquals("10.166.11.1", reloaded.getIncusBridgeGateway());
    }

    @Test
    void modernHostPathsRoundTripsCleanly() throws Exception {
        var yaml = """
                host-paths:
                  - ~/projects
                  - ~/workspace
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        config.migrateHostPath();
        var written = YAML.writeValueAsString(config);
        assertFalse(written.contains("host-path:"), "should not produce host-path singular");
        assertTrue(written.contains("host-paths:"));

        var reloaded = YAML.readValue(written, SpawnConfig.class);
        assertEquals(java.util.List.of("~/projects", "~/workspace"), reloaded.getHostPaths());
    }

}
