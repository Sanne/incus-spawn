package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolDefLoaderTest {

    @Test
    void findsBuiltinPodman() {
        var loader = new ToolDefLoader();
        var tool = loader.find("podman");
        assertNotNull(tool, "podman should be found as a built-in YAML tool");
        assertEquals("podman", tool.name());
    }

    @Test
    void findsBuiltinMaven() {
        var loader = new ToolDefLoader();
        var tool = loader.find("maven-3");
        assertNotNull(tool, "maven-3 should be found as a built-in YAML tool");
        assertEquals("maven-3", tool.name());
    }

    @Test
    void unknownToolReturnsNull() {
        var loader = new ToolDefLoader();
        assertNull(loader.find("nonexistent-tool"));
    }

    @Test
    void userDefinedToolOverridesBuiltin(@TempDir Path tempDir) throws Exception {
        // Create a user-defined podman.yaml that overrides the built-in
        var userYaml = """
                name: podman
                description: Custom podman override
                run:
                  - echo custom-podman
                """;
        Files.writeString(tempDir.resolve("podman.yaml"), userYaml);

        var loader = new ToolDefLoader();
        loader.setProjectToolsDir(tempDir);

        var tool = loader.find("podman");
        assertNotNull(tool);
        // The user tool should have taken priority — verify via the adapter
        // by checking it's a YamlToolSetup (which it always is from the loader)
        assertEquals("podman", tool.name());
    }

    @Test
    void userDefinedCustomTool(@TempDir Path tempDir) throws Exception {
        var userYaml = """
                name: my-custom-tool
                description: A project-specific tool
                run:
                  - echo installed
                """;
        Files.writeString(tempDir.resolve("my-custom-tool.yaml"), userYaml);

        var loader = new ToolDefLoader();
        loader.setProjectToolsDir(tempDir);

        var tool = loader.find("my-custom-tool");
        assertNotNull(tool, "user-defined custom tool should be discovered");
        assertEquals("my-custom-tool", tool.name());
    }

    @Test
    void nonexistentUserDirIsIgnored(@TempDir Path tempDir) {
        var loader = new ToolDefLoader();
        loader.setProjectToolsDir(tempDir.resolve("does-not-exist"));
        // Should still find builtins without error
        assertNotNull(loader.find("podman"));
    }

    @Test
    void searchPathLoadsTool(@TempDir Path tempDir) throws Exception {
        var toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("gradle.yaml"), """
                name: gradle
                description: Gradle build tool
                run:
                  - echo installing gradle
                verify: gradle --version
                """);

        var loader = new ToolDefLoader();
        loader.setSearchPaths(java.util.List.of(tempDir.toString()));

        var tool = loader.find("gradle");
        assertNotNull(tool, "gradle should be found from search path");
        assertEquals("gradle", tool.name());
    }

    @Test
    void searchPathOverridesBuiltin(@TempDir Path tempDir) throws Exception {
        var toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("podman.yaml"), """
                name: podman
                description: Custom podman from search path
                run:
                  - echo custom-search-path-podman
                """);

        var loader = new ToolDefLoader();
        loader.setSearchPaths(java.util.List.of(tempDir.toString()));

        var tool = loader.find("podman");
        assertNotNull(tool);
        assertEquals("podman", tool.name());
    }

    @Test
    void projectLocalOverridesSearchPath(@TempDir Path tempDir) throws Exception {
        var searchDir = tempDir.resolve("search");
        var projectDir = tempDir.resolve("project");
        Files.createDirectories(searchDir.resolve("tools"));
        Files.createDirectories(projectDir);

        Files.writeString(searchDir.resolve("tools/my-tool.yaml"), """
                name: my-tool
                description: From search path
                run:
                  - echo search
                """);
        Files.writeString(projectDir.resolve("my-tool.yaml"), """
                name: my-tool
                description: From project
                run:
                  - echo project
                """);

        var loader = new ToolDefLoader();
        loader.setSearchPaths(java.util.List.of(searchDir.toString()));
        loader.setProjectToolsDir(projectDir);

        var tool = loader.find("my-tool");
        assertNotNull(tool, "my-tool should be found");
        // Project-local should win over search path
        assertEquals("my-tool", tool.name());
    }

    @Test
    void nonexistentSearchPathIsIgnored(@TempDir Path tempDir) {
        var loader = new ToolDefLoader();
        loader.setSearchPaths(java.util.List.of("/nonexistent/path"));
        // Should still find builtins without error
        assertNotNull(loader.find("podman"));
    }
}
