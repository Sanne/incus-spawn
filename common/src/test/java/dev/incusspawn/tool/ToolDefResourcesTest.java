package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** A tool's {@code resources:} as read from its definition (#2355). */
class ToolDefResourcesTest {

    private static ToolDef tool(String yaml) throws Exception {
        return ToolDef.loadFromStream(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsASizeAndAParameterPlusASize() throws Exception {
        var def = tool("""
                name: ide
                parameters:
                  memory: { type: string, default: "2g" }
                resources:
                  memory:
                    resident: { parameter: memory, plus: 768MiB }
                    peak: 1GiB
                """);
        var resources = def.getResources();
        assertEquals(new ToolDef.MemoryAmount(768L << 20, "memory"), resources.resident());
        assertEquals(new ToolDef.MemoryAmount(1L << 30, null), resources.peak());
        assertTrue(def.unknownKeyWarnings().isEmpty(), def.unknownKeyWarnings().toString());
        assertNull(ToolDefValidator.refusal(def));
    }

    @Test
    void aToolWithoutResourcesDeclaresNone() throws Exception {
        assertFalse(tool("name: plain\n").getResources().declaresMemory());
    }

    @Test
    void aSizeIsxCannotReadRefusesTheDefinition() {
        var e = assertThrows(Exception.class, () -> tool("""
                name: ide
                resources:
                  memory:
                    resident: a lot
                """));
        assertTrue(String.valueOf(e.getMessage()).contains("resources.memory.resident"), e.getMessage());
    }

    @Test
    void aMisspelledKeyIsNamedNotDropped() throws Exception {
        var def = tool("""
                name: ide
                resources:
                  memory:
                    resitent: 1GiB
                  cpu: 2
                """);
        var warnings = String.join("\n", def.unknownKeyWarnings());
        assertTrue(warnings.contains("resources.memory.resitent"), warnings);
        assertTrue(warnings.contains("resources.cpu"), warnings);
    }

    @Test
    void anAmountMayOnlyReadAParameterTheToolDeclares() throws Exception {
        var undeclared = tool("""
                name: ide
                resources:
                  memory:
                    resident: { parameter: heap }
                """);
        assertTrue(ToolDefValidator.refusal(undeclared).contains("'heap'"), ToolDefValidator.refusal(undeclared));

        var notASize = tool("""
                name: ide
                parameters:
                  heap: { type: string, default: "big" }
                resources:
                  memory:
                    resident: { parameter: heap }
                """);
        assertTrue(ToolDefValidator.refusal(notASize).contains("not a size"), ToolDefValidator.refusal(notASize));

        var noDefault = tool("""
                name: ide
                parameters:
                  heap: { type: string, optional: true }
                resources:
                  memory:
                    resident: { parameter: heap }
                """);
        assertTrue(ToolDefValidator.refusal(noDefault).contains("no default"), ToolDefValidator.refusal(noDefault));
    }

    @Test
    void onlyADeclaredNeedChangesTheFingerprint() throws Exception {
        var plain = tool("name: ide\nrun: [echo]\n");
        var same = tool("name: ide\nrun: [echo]\nresources: {}\n");
        var declared = tool("name: ide\nrun: [echo]\nresources:\n  memory:\n    resident: 1GiB\n");
        assertEquals(plain.contentFingerprint(), same.contentFingerprint());
        assertNotEquals(plain.contentFingerprint(), declared.contentFingerprint());
    }
}
