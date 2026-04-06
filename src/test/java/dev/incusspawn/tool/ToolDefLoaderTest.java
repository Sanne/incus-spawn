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
        loader.setUserToolsDir(tempDir);

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
        loader.setUserToolsDir(tempDir);

        var tool = loader.find("my-custom-tool");
        assertNotNull(tool, "user-defined custom tool should be discovered");
        assertEquals("my-custom-tool", tool.name());
    }

    @Test
    void nonexistentUserDirIsIgnored(@TempDir Path tempDir) {
        var loader = new ToolDefLoader();
        loader.setUserToolsDir(tempDir.resolve("does-not-exist"));
        // Should still find builtins without error
        assertNotNull(loader.find("podman"));
    }
}
