package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.tool.ToolDef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
    void bothHostPathAndHostPathsThrowsException() throws Exception {
        var yaml = """
                host-path: ~/projects
                host-paths:
                  - ~/workspace
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        var exception = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(exception.getMessage().contains("Cannot specify both"));
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

    @Nested
    class CheckCredentialsTest {

        @TempDir
        Path tempDir;
        private String originalUserHome;

        @BeforeEach
        void setup() {
            originalUserHome = System.getProperty("user.home");
            System.setProperty("user.home", tempDir.toString());
        }

        @AfterEach
        void tearDown() {
            if (originalUserHome != null) {
                System.setProperty("user.home", originalUserHome);
            }
        }

        private ImageDef imageWithTools(ToolDef.ToolRef... refs) {
            var def = new ImageDef();
            def.setName("test-image");
            def.setTools(List.of(refs));
            return def;
        }

        @Test
        void noToolsReturnsEmpty() {
            var def = imageWithTools();
            assertEquals("", SpawnConfig.checkCredentials(def, Map.of(), n -> false));
        }

        @Test
        void claudeToolRequiresAnthropicAuth() {
            var def = imageWithTools(new ToolDef.ToolRef("claude"));
            var result = SpawnConfig.checkCredentials(def, Map.of(), n -> false);
            assertTrue(result.contains("Anthropic"), result);
        }

        @Test
        void claudeToolPassesWhenApiKeyConfigured() {
            var config = SpawnConfig.load();
            config.getClaude().setApiKey("sk-ant-test");
            config.save();

            var def = imageWithTools(new ToolDef.ToolRef("claude"));
            assertEquals("", SpawnConfig.checkCredentials(def, Map.of(), n -> false));
        }

        @Test
        void ghToolRequiresGithubToken() {
            var def = imageWithTools(new ToolDef.ToolRef("gh"));
            var result = SpawnConfig.checkCredentials(def, Map.of(), n -> false);
            assertTrue(result.contains("GitHub"), result);
        }

        @Test
        void piDefaultProviderRequiresAnthropicAuth() {
            var def = imageWithTools(new ToolDef.ToolRef("pi"));
            var result = SpawnConfig.checkCredentials(def, Map.of(), n -> false);
            assertTrue(result.contains("Anthropic"), result);
        }

        @Test
        void piOpenaiProviderRequiresOpenaiAuth() {
            var def = imageWithTools(new ToolDef.ToolRef("pi", Map.of("provider", "openai")));
            var result = SpawnConfig.checkCredentials(def, Map.of(), n -> false);
            assertTrue(result.contains("OpenAI"), result);
            assertFalse(result.contains("Anthropic"), "Should not require Anthropic for openai provider");
        }

        @Test
        void piOpenaiProviderPassesWhenKeyConfigured() {
            var config = SpawnConfig.load();
            config.getOpenai().setApiKey("sk-test");
            config.save();

            var def = imageWithTools(new ToolDef.ToolRef("pi", Map.of("provider", "openai")));
            assertEquals("", SpawnConfig.checkCredentials(def, Map.of(), n -> false));
        }

        @Test
        void piVertexProviderRequiresVertexConfig() {
            var def = imageWithTools(new ToolDef.ToolRef("pi", Map.of("provider", "vertex")));
            var result = SpawnConfig.checkCredentials(def, Map.of(), n -> false);
            assertTrue(result.contains("Vertex"), result);
        }

        @Test
        void claudeAndPiDeduplicateAnthropicRequirement() {
            var def = imageWithTools(new ToolDef.ToolRef("claude"), new ToolDef.ToolRef("pi"));
            var result = SpawnConfig.checkCredentials(def, Map.of(), n -> false);
            // "Anthropic" should appear only once thanks to LinkedHashSet dedup
            int count = result.split("Anthropic").length - 1;
            assertEquals(1, count, "Anthropic requirement should not be duplicated: " + result);
        }

        @Test
        void claudeAndPiOpenaiRequireBothCredentials() {
            var def = imageWithTools(
                    new ToolDef.ToolRef("claude"),
                    new ToolDef.ToolRef("pi", Map.of("provider", "openai")));
            var result = SpawnConfig.checkCredentials(def, Map.of(), n -> false);
            assertTrue(result.contains("Anthropic"), "Should require Anthropic for claude: " + result);
            assertTrue(result.contains("OpenAI"), "Should require OpenAI for pi: " + result);
        }
    }
}
