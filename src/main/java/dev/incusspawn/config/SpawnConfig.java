package dev.incusspawn.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Global incus-spawn configuration stored in ~/.config/incus-spawn/config.yaml
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpawnConfig {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "incus-spawn");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.yaml");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ClaudeConfig claude = new ClaudeConfig();
    private GitHubConfig github = new GitHubConfig();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClaudeConfig {
        private boolean useVertex;
        private String cloudMlRegion;
        private String vertexProjectId;
        private String apiKey;

        public boolean isUseVertex() { return useVertex; }
        public void setUseVertex(boolean useVertex) { this.useVertex = useVertex; }
        public String getCloudMlRegion() { return cloudMlRegion; }
        public void setCloudMlRegion(String cloudMlRegion) { this.cloudMlRegion = cloudMlRegion; }
        public String getVertexProjectId() { return vertexProjectId; }
        public void setVertexProjectId(String vertexProjectId) { this.vertexProjectId = vertexProjectId; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitHubConfig {
        private String token;

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public ClaudeConfig getClaude() { return claude; }
    public void setClaude(ClaudeConfig claude) { this.claude = claude; }
    public GitHubConfig getGithub() { return github; }
    public void setGithub(GitHubConfig github) { this.github = github; }

    public static Path configDir() {
        return CONFIG_DIR;
    }

    public static SpawnConfig load() {
        if (!Files.exists(CONFIG_FILE)) {
            return new SpawnConfig();
        }
        try {
            return YAML.readValue(CONFIG_FILE.toFile(), SpawnConfig.class);
        } catch (IOException e) {
            System.err.println("Warning: could not read config: " + e.getMessage());
            return new SpawnConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            YAML.writeValue(CONFIG_FILE.toFile(), this);
            // Restrict permissions - config contains tokens
            CONFIG_FILE.toFile().setReadable(false, false);
            CONFIG_FILE.toFile().setReadable(true, true);
            CONFIG_FILE.toFile().setWritable(false, false);
            CONFIG_FILE.toFile().setWritable(true, true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config: " + e.getMessage(), e);
        }
    }
}
