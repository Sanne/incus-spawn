package dev.incusspawn.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.util.Map;
import dev.incusspawn.Environment;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Global incus-spawn configuration stored in ~/.config/incus-spawn/config.yaml
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpawnConfig {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ClaudeConfig claude = new ClaudeConfig();
    private GitHubConfig github = new GitHubConfig();
    private java.util.List<String> searchPaths = java.util.List.of();
    @JsonProperty("host-path")
    private String hostPath = "";
    @JsonProperty("repo-paths")
    private Map<String, String> repoPaths = Map.of();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClaudeConfig {
        private boolean useVertex;
        private String cloudMlRegion = "";
        private String vertexProjectId = "";
        private String apiKey = "";

        public boolean isUseVertex() { return useVertex; }
        public void setUseVertex(boolean useVertex) { this.useVertex = useVertex; }
        public String getCloudMlRegion() { return cloudMlRegion; }
        public void setCloudMlRegion(String cloudMlRegion) { this.cloudMlRegion = cloudMlRegion == null ? "" : cloudMlRegion; }
        public String getVertexProjectId() { return vertexProjectId; }
        public void setVertexProjectId(String vertexProjectId) { this.vertexProjectId = vertexProjectId == null ? "" : vertexProjectId; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitHubConfig {
        private String token = "";

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token == null ? "" : token; }
    }

    public ClaudeConfig getClaude() { return claude; }
    public void setClaude(ClaudeConfig claude) { this.claude = claude; }
    public GitHubConfig getGithub() { return github; }
    public void setGithub(GitHubConfig github) { this.github = github; }
    public java.util.List<String> getSearchPaths() { return searchPaths; }
    public void setSearchPaths(java.util.List<String> searchPaths) { this.searchPaths = searchPaths == null ? java.util.List.of() : searchPaths; }
    public String getHostPath() { return hostPath; }
    public void setHostPath(String hostPath) { this.hostPath = hostPath == null ? "" : hostPath; }
    public Map<String, String> getRepoPaths() { return repoPaths; }
    public void setRepoPaths(Map<String, String> repoPaths) { this.repoPaths = repoPaths == null ? Map.of() : repoPaths; }

    public static Path configDir() {
        return Environment.configDir();
    }

    /**
     * Check whether the given image (or any unbuilt ancestor) requires auth credentials
     * that have not been configured. Returns a non-empty error message if credentials
     * are missing, or empty string if everything is configured.
     *
     * @param imageDef the image to check
     * @param allDefs  all known image definitions (for parent resolution)
     * @param existsCheck  predicate to test whether an image already exists (skip parent check if built)
     */
    public static String checkCredentials(ImageDef imageDef, java.util.Map<String, ImageDef> allDefs,
                                           java.util.function.Predicate<String> existsCheck) {
        var config = load();
        var missing = new java.util.ArrayList<String>();

        // Collect tools from this image and any unbuilt ancestors
        var tools = new java.util.HashSet<String>();
        var current = imageDef;
        while (current != null) {
            tools.addAll(current.getTools());
            if (current.isRoot() || existsCheck.test(current.getParent())) break;
            current = allDefs.get(current.getParent());
        }

        if (tools.contains("claude")) {
            if (!config.getClaude().isUseVertex() && config.getClaude().getApiKey().isBlank()) {
                missing.add("Claude API key (or Vertex AI)");
            }
        }
        if (tools.contains("gh")) {
            if (config.getGithub().getToken().isBlank()) {
                missing.add("GitHub token");
            }
        }

        if (missing.isEmpty()) return "";
        return "Missing credentials: " + String.join(", ", missing) + ". Run 'isx init' to configure.";
    }

    public static SpawnConfig load() {
        var configFile = configDir().resolve("config.yaml");
        if (!Files.exists(configFile)) {
            return new SpawnConfig();
        }
        try {
            return YAML.readValue(configFile.toFile(), SpawnConfig.class);
        } catch (IOException e) {
            System.err.println("Warning: could not read config: " + e.getMessage());
            return new SpawnConfig();
        }
    }

    public void save() {
        try {
            var configFile = configDir().resolve("config.yaml");
            Files.createDirectories(configFile.getParent());
            YAML.writeValue(configFile.toFile(), this);
            // Restrict permissions - config contains tokens
            configFile.toFile().setReadable(false, false);
            configFile.toFile().setReadable(true, true);
            configFile.toFile().setWritable(false, false);
            configFile.toFile().setWritable(true, true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config: " + e.getMessage(), e);
        }
    }
}
