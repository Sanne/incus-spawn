package dev.incusspawn.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TemplateValidatorTest {

    private Map<String, ImageDef> knownTemplates() {
        return ImageDef.loadAll(List.of());
    }

    @Test
    void validTemplate(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                description: A test template
                parent: tpl-dev
                packages:
                  - htop
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void missingName(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                description: No name
                parent: tpl-dev
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("'name'")));
    }

    @Test
    void blankName(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: ""
                description: Blank name
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("'name'")));
    }

    @Test
    void invalidYaml(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                packages:
                  - valid
                  bad indentation here
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("test.yaml")));
    }

    @Test
    void missingTplPrefix(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-template
                description: No tpl- prefix
                parent: tpl-dev
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("tpl-")));
    }

    @Test
    void unknownParent(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-nonexistent
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("tpl-nonexistent")));
    }

    @Test
    void rootWithoutImage(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-bare
                image: ""
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("'image'")));
    }

    @Test
    void duplicateKeyIsError(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-dev
                tools:
                  - graalvm
                tools:
                  - claude
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("duplicate key")));
    }

    @Test
    void invalidHostResourceMode(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-dev
                host-resources:
                  - source: ~/.m2/repository
                    path: /home/user/.m2/repository
                    mode: readwrite
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("readwrite")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("readonly, overlay, copy")));
    }

    @Test
    void validHostResourceModes(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-dev
                host-resources:
                  - source: ~/.m2
                    path: /home/user/.m2
                    mode: readonly
                  - source: ~/.gradle
                    path: /home/user/.gradle
                    mode: overlay
                  - source: ~/.gitconfig
                    path: /home/user/.gitconfig
                    mode: copy
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void duplicateToolsWarning(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-dev
                tools:
                  - maven-3
                  - podman
                  - maven-3
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("maven-3") && w.contains("more than once")));
    }

    @Test
    void unknownFieldsIgnored(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-dev
                some_future_field: value
                another_unknown: true
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void validRootTemplate(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-mybase
                description: Custom root
                image: images:ubuntu/24.04
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }
}
