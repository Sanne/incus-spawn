package dev.incusspawn.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Per-project configuration stored in incus-spawn.yaml in the project repo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectConfig {

    private static final String CONFIG_FILENAME = "incus-spawn.yaml";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private String name;
    private String parent = "golden-java";
    private List<String> repos = List.of();
    private String preBuild;
    private GitConfig git = new GitConfig();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitConfig {
        private String sshKey;
        private String token;

        public String getSshKey() { return sshKey; }
        public void setSshKey(String sshKey) { this.sshKey = sshKey; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }
    public List<String> getRepos() { return repos; }
    public void setRepos(List<String> repos) { this.repos = repos; }
    public String getPreBuild() { return preBuild; }
    public void setPreBuild(String preBuild) { this.preBuild = preBuild; }
    public GitConfig getGit() { return git; }
    public void setGit(GitConfig git) { this.git = git; }

    /**
     * Try to find incus-spawn.yaml in the given directory or its parents.
     */
    public static ProjectConfig findInDirectory(Path dir) {
        var current = dir.toAbsolutePath();
        while (current != null) {
            var configFile = current.resolve(CONFIG_FILENAME);
            if (Files.exists(configFile)) {
                return load(configFile);
            }
            current = current.getParent();
        }
        return null;
    }

    public static ProjectConfig load(Path file) {
        try {
            return YAML.readValue(file.toFile(), ProjectConfig.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read project config: " + file + ": " + e.getMessage(), e);
        }
    }
}
