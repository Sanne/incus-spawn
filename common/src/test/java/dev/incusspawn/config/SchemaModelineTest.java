package dev.incusspawn.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The first line every YAML file isx writes opens with, so an editor completes and checks it (design/templates.md
 * "A schema for editors"). Each kind is the address {@code site/schema/} is published at.
 */
class SchemaModelineTest {

    @Test
    void eachKindNamesItsSchema() {
        assertEquals("# yaml-language-server: $schema=https://isx.run/schema/template.json", SchemaModeline.TEMPLATE.line());
        assertEquals("# yaml-language-server: $schema=https://isx.run/schema/tool.json", SchemaModeline.TOOL.line());
        assertEquals("# yaml-language-server: $schema=https://isx.run/schema/config.json", SchemaModeline.CONFIG.line());
        assertEquals("# yaml-language-server: $schema=https://isx.run/schema/mcp.json", SchemaModeline.MCP.line());
    }

    @Test
    void aFileWithoutOneGetsItFirst() {
        assertEquals(SchemaModeline.TOOL.line() + "\nname: x\n", SchemaModeline.TOOL.applyTo("name: x\n"));
        assertEquals(SchemaModeline.TOOL.line() + "\n# a note\nname: x\n", SchemaModeline.TOOL.applyTo("# a note\nname: x\n"));
    }

    @Test
    void aFileThatOpensWithOneKeepsIt() {
        var own = "# yaml-language-server: $schema=./my-template.json\nname: x\n";
        assertEquals(own, SchemaModeline.TEMPLATE.applyTo(own));
        var afterNotes = "# my template\n\n# yaml-language-server: $schema=https://isx.run/schema/template.json\nname: x\n";
        assertEquals(afterNotes, SchemaModeline.TEMPLATE.applyTo(afterNotes));
    }

    @Test
    void aModelineAfterContentIsNotOne() {
        var late = "name: x\n# yaml-language-server: $schema=./other.json\n";
        assertEquals(SchemaModeline.TEMPLATE.line() + "\n" + late, SchemaModeline.TEMPLATE.applyTo(late));
    }

    @Test
    void writingAConfigPutsItFirstAndItReadsBack(@TempDir Path dir) throws Exception {
        var file = dir.resolve("config.yaml");
        var config = new SpawnConfig();
        config.setFeatures(java.util.List.of("shell-status-bar"));
        Jjq.writeYaml(file, config, SchemaModeline.CONFIG);
        assertEquals(SchemaModeline.CONFIG.line(), Files.readAllLines(file).getFirst());
        assertEquals(java.util.List.of("shell-status-bar"), Jjq.spawnConfig(file).getFeatures());
    }
}
