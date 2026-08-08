package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolDefValidatorTest {

    @Test
    void validToolPasses(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                description: A test tool
                packages:
                  - htop
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void missingNameIsError(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                description: No name tool
                packages:
                  - htop
                """);
        var result = ToolDefValidator.validate(file);
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("'name'")));
    }

    @Test
    void duplicateKeyIsError(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                packages:
                  - foo
                packages:
                  - bar
                """);
        var result = ToolDefValidator.validate(file);
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("duplicate key")));
    }

    @Test
    void downloadWithoutUrlWarns(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                downloads:
                  - sha256: abc123
                    extract: /opt
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("url")));
    }

    @Test
    void invalidParameterTypeWarns(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                parameters:
                  size:
                    type: float
                    default: "1.5"
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("float") && w.contains("type")));
    }

    @Test
    void enumWithoutOptionsWarns(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                parameters:
                  color:
                    type: enum
                    default: red
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("enum") && w.contains("options")));
    }

    @Test
    void integerMinGreaterThanMaxWarns(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                parameters:
                  count:
                    type: integer
                    min: 100
                    max: 10
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("min") && w.contains("max")));
    }

    @Test
    void validParametersPasses(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                parameters:
                  memory:
                    type: string
                    default: "2g"
                    pattern: "^[0-9]+[gGmM]$"
                  count:
                    type: integer
                    min: 1
                    max: 100
                  color:
                    type: enum
                    default: red
                    options:
                      - red
                      - blue
                  debug:
                    type: boolean
                    default: "false"
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void builtinToolsPassValidation() throws Exception {
        var builtins = new String[]{
                "tools/maven-3.yaml", "tools/idea-backend.yaml", "tools/starship.yaml",
                "tools/podman.yaml", "tools/sshd.yaml", "tools/tmux.yaml"
        };
        for (var resource : builtins) {
            try (var is = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (is == null) continue;
                var def = ToolDef.loadFromStream(is);
                var errors = new java.util.ArrayList<String>();
                var warnings = new java.util.ArrayList<String>();
                ToolDefValidator.validateDef(def, errors, warnings);
                assertTrue(errors.isEmpty(), resource + " has validation errors: " + errors);
            }
        }
    }
}
