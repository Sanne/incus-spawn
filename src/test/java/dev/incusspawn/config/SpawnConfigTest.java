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
}
