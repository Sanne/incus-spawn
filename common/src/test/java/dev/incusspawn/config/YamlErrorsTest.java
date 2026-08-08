package dev.incusspawn.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class YamlErrorsTest {

    @Test
    void duplicateKeyProducesFriendlyError(@TempDir Path dir) throws Exception {
        var file = dir.resolve("dup.yaml");
        Files.writeString(file, """
                name: tpl-test
                tools:
                  - graalvm
                tools:
                  - claude
                """);
        var ex = assertThrows(Exception.class, () -> ImageDef.parseFile(file));
        var msg = YamlErrors.friendly("dup.yaml", ex);
        assertTrue(msg.contains("duplicate key"), "Should mention duplicate key: " + msg);
        assertTrue(msg.contains("tools"), "Should name the duplicated field: " + msg);
        assertTrue(msg.startsWith("dup.yaml"), "Should start with filename: " + msg);
    }

    @Test
    void tabCharacterProducesFriendlyError(@TempDir Path dir) throws Exception {
        var file = dir.resolve("tabs.yaml");
        Files.writeString(file, "name: tpl-test\n\tpackages:\n\t  - htop\n");
        var ex = assertThrows(Exception.class, () -> ImageDef.parseFile(file));
        var msg = YamlErrors.friendly("tabs.yaml", ex);
        assertTrue(msg.contains("tabs"), "Should mention tabs: " + msg);
        assertTrue(msg.contains("spaces"), "Should suggest spaces: " + msg);
    }

    @Test
    void badIndentationProducesFriendlyError(@TempDir Path dir) throws Exception {
        var file = dir.resolve("indent.yaml");
        Files.writeString(file, """
                name: tpl-test
                packages:
                  - valid
                  bad indentation here
                """);
        var ex = assertThrows(Exception.class, () -> ImageDef.parseFile(file));
        var msg = YamlErrors.friendly("indent.yaml", ex);
        assertTrue(msg.startsWith("indent.yaml"), "Should start with filename: " + msg);
        assertTrue(msg.contains(":"), "Should contain line number separator: " + msg);
    }

    @Test
    void extractLineFindsLineNumber() {
        var msg = "while scanning a simple key\n in 'reader', line 4, column 1:\n    bad\n    ^";
        assertEquals(4, YamlErrors.extractLine(msg));
    }

    @Test
    void extractLineReturnsMinusOneWhenMissing() {
        assertEquals(-1, YamlErrors.extractLine("some other error"));
    }

    @Test
    void friendlyIncludesLineNumberInOutput() {
        var ex = new java.io.IOException(
                "Duplicate field 'tools'\n at [Source: (StringReader); line: 4, column: 6]");
        var msg = YamlErrors.friendly("test.yaml", ex);
        assertTrue(msg.contains("test.yaml:4:"), "Should include line number: " + msg);
    }

    @Test
    void friendlyHandlesNullMessage() {
        var ex = new java.io.IOException((String) null);
        var msg = YamlErrors.friendly("test.yaml", ex);
        assertNotNull(msg);
        assertTrue(msg.contains("test.yaml"));
    }

    @Test
    void duplicateKeyDetectedInImageDef(@TempDir Path dir) throws Exception {
        var file = dir.resolve("dup.yaml");
        Files.writeString(file, """
                name: tpl-test
                packages:
                  - alpha
                packages:
                  - beta
                """);
        assertThrows(Exception.class, () -> ImageDef.parseFile(file));
    }

    @Test
    void duplicateKeyDetectedInToolDef(@TempDir Path dir) throws Exception {
        var file = dir.resolve("tool.yaml");
        Files.writeString(file, """
                name: my-tool
                packages:
                  - foo
                packages:
                  - bar
                """);
        var result = dev.incusspawn.tool.ToolDefValidator.validate(file);
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("duplicate key")));
    }
}
