package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Definition text a project-local template or tool ships is untrusted (#765), so the human
 * tables show it through {@code OutputFormat.oneLine}, as {@code --format=plain} does (#1133):
 * an ESC, C1 CSI, DEL, line break or bidi override never reaches the terminal raw.
 */
class DefinitionTextTableTest {

    /** ESC, a C1 CSI (U+009B), DEL, a newline, a bidi override and isolate, a line separator. */
    private static final String HOSTILE = "a\u001b[2J\u009b2J\u007fb\nc\u202ed\u2066e\u2028f";

    @Test
    void templatesTableShowsDefinitionTextOnOneSafeLine() throws Exception {
        var def = ImageDef.parseYaml("name: tpl-evil\ndescription: x\n");
        def.setDescription(HOSTILE);
        def.setSource("/tmp/p\u001b]0;t\u0007/.incus-spawn/images/tpl-evil.yaml");
        var defs = Map.of("tpl\u001b[31m-evil", def);

        assertSafe(print(out -> TemplatesCommand.ListSub.printTable(out, defs, true)), 2);
        assertSafe(print(out -> TemplatesCommand.ListSub.printTable(out, defs, false)), 2);
    }

    @Test
    void toolsTableShowsDefinitionTextOnOneSafeLine() throws Exception {
        Map<String, ToolSetup> tools = Map.of("tool\u001b[31m", tool());

        assertSafe(print(out -> ToolsCommand.ListSub.printTable(out, tools, name -> "/tmp/p\u009b2J", true)), 2);
        assertSafe(print(out -> ToolsCommand.ListSub.printTable(out, tools, name -> "/tmp/p", false)), 1);
    }

    @Test
    void toolsShowShowsDefinitionTextOnSafeLines() throws Exception {
        var tool = tool();
        var output = print(out -> ToolsCommand.Show.print(out, tool, "/tmp/p\u001b[2J"));
        // One line per field, parameter line, action, download and domain, and one per heading.
        assertSafe(output, 15);
        assertTrue(output.contains("  Description:  a [2J 2J b c d e f"), output);
        assertTrue(output.contains("      first second [2J"), output);
        assertTrue(output.contains("    Open now [2J (url )"), output);
    }

    @Test
    void templateValidationShowsDefinitionTextSafely(@TempDir Path dir) throws Exception {
        var file = dir.resolve("tpl-evil.yaml");
        Files.writeString(file, """
                name: "evil\\e[2J"
                parent: "tpl-\\x9b2J\\nx"
                host-resources:
                  - source: /tmp
                    path: /opt/evil
                    mode: "copy\\e]0;t\\a"
                  - source: "/tmp/s\\e[2J\\nTemplate is valid."
                    path: "/etc/evil\\nok"
                    mode: readonly
                tools:
                  - "dup\\e[2J"
                  - "dup\\e[2J"
                """);
        var report = new RecordingOutput();
        assertFalse(TemplatesCommand.validateAndReport(file, Map.of(), List.of(), report));

        // The host-resource mode and the duplicate tool, a warning each.
        var warnings = String.join("\n", report.events(RecordingOutput.Kind.WARN).stream().map(RecordingOutput.Event::msg).toList());
        assertSafe(warnings + "\n", 2);
        assertTrue(warnings.contains("mode 'copy ]0;t '"), warnings);
        assertTrue(warnings.contains("tool 'dup [2J'"), warnings);
        // The name and the parent no instance can have, and the forbidden mount target, an error each: its message
        // is isx's multi-line advice, each line made safe, and the quoted source and path add no line of their own.
        var errorEvents = report.events(RecordingOutput.Kind.ERROR).stream().map(RecordingOutput.Event::msg).toList();
        assertEquals(3, errorEvents.size(), errorEvents::toString);
        var errors = String.join("\n", errorEvents);
        assertSafe(errors + "\n", 6);
        assertTrue(errors.contains("template name 'evil [2J' is not a valid Incus instance name"), errors);
        assertTrue(errors.contains("parent 'tpl- 2J x' is not a valid Incus instance name"), errors);
        assertTrue(errors.contains("Host-resource '/tmp/s [2J Template is valid.' would be mounted"
                + " (readonly) at /etc/evil ok, a system directory."), errors);
        assertEquals(List.of(), report.events(RecordingOutput.Kind.OK), "not reported valid");
    }

    /**
     * A YAML tool with actions, so {@code tools show} prints every section: parameters with a
     * description and options, actions and downloads besides what every tool has.
     */
    private static ToolSetup tool() throws IOException {
        var yaml = """
                name: "tool\\e[31m"
                description: "a\\e[2J\\x9b2J\\x7fb\\nc\\u202ed\\u2066e\\Lf"
                requires: ["dep\\e[2J"]
                packages: ["pkg\\e[2J"]
                parameters:
                  "mode\\e[2J":
                    type: "enum\\x9b"
                    default: "on\\e[2J"
                    description: "first\\nsecond\\e[2J"
                    options: ["on\\e[2J", "off"]
                actions:
                  - label: "Open\\nnow\\e[2J"
                    type: "url\\x9b"
                downloads:
                  - url: "https://example.com/t\\e[2J.tar.gz"
                    arch: "x86_64\\x9b"
                proxy:
                  auth:
                    - type: "bearer\\e[2J"
                      domains: ["api.example.com\\e[2J"]
                """;
        var def = ToolDef.loadFromStream(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return new YamlToolSetup(def);
    }

    private static String print(Consumer<PrintStream> printer) {
        var bytes = new ByteArrayOutputStream();
        printer.accept(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** {@code lines} lines, and nothing a terminal acts on but their line ends. */
    private static void assertSafe(String output, long lines) {
        assertEquals(lines, output.lines().count(), output);
        output.lines().forEach(line -> assertFalse(line.codePoints().anyMatch(c ->
                c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == 0x2028 || c == 0x2029
                        || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069)),
                "control character in: " + line));
    }
}
