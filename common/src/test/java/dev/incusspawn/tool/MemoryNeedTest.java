package dev.incusspawn.tool;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What a template's tools are expected to need, and the warning when an instance gets less (#2355). */
class MemoryNeedTest {

    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024 * MIB;

    private static ToolDef tool(String yaml) throws Exception {
        return ToolDef.loadFromStream(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String IDE = """
            name: ide
            parameters:
              memory:
                type: string
                default: "2g"
            resources:
              memory:
                resident: { parameter: memory, plus: 768MiB }
            """;

    private static final String INDEXER = """
            name: indexer
            resources:
              memory:
                resident: 256MiB
                peak: 3GiB
            """;

    private static final String BUILDER = """
            name: builder
            resources:
              memory:
                peak: 1GiB
            """;

    private static MemoryNeed.Declared declared(ToolDef def, Map<String, String> params) {
        return new MemoryNeed.Declared(def.getName(), def.getResources(), params);
    }

    @Test
    void residentMemoryAddsUpAndOnlyTheLargestPeakCounts() throws Exception {
        var need = MemoryNeed.of(List.of(
                declared(tool(IDE), Map.of("memory", "2g")),
                declared(tool(INDEXER), Map.of()),
                declared(tool(BUILDER), Map.of())), w -> fail(w));

        // baseline + (2 GiB + 768 MiB) + 256 MiB, and the indexer's 3 GiB peak, not the builder's as well
        assertEquals(MemoryNeed.GUEST_BASELINE + 2 * GIB + 768 * MIB + 256 * MIB + 3 * GIB, need.total());
    }

    @Test
    void aParameterSetByTheTemplateChangesTheFigure() throws Exception {
        var small = MemoryNeed.of(List.of(declared(tool(IDE), Map.of("memory", "2g"))), w -> fail(w));
        var large = MemoryNeed.of(List.of(declared(tool(IDE), Map.of("memory", "8g"))), w -> fail(w));
        assertEquals(6 * GIB, large.total() - small.total());
    }

    @Test
    void toolsThatDeclareNothingGiveNoFigure() throws Exception {
        assertNull(MemoryNeed.of(List.of(declared(tool("name: plain\n"), Map.of())), w -> fail(w)));
    }

    @Test
    void aParameterThatHoldsNoSizeIsNotCountedAndSaysSo() throws Exception {
        var said = new ArrayList<String>();
        assertNull(MemoryNeed.of(List.of(declared(tool(IDE), Map.of("memory", "plenty"))), said::add));
        assertEquals(1, said.size());
        assertTrue(said.getFirst().contains("'memory'"), said.getFirst());
    }

    @Test
    void theStampKeepsEachToolSoABranchCanNameThem() throws Exception {
        var need = MemoryNeed.of(List.of(
                declared(tool(IDE), Map.of("memory", "4g")),
                declared(tool(INDEXER), Map.of())), w -> fail(w));
        assertEquals(need, MemoryNeed.fromStamp(need.stamp()));
    }

    @Test
    void aStampIsxDidNotWriteIsNoFigure() {
        assertNull(MemoryNeed.fromStamp(""));
        assertNull(MemoryNeed.fromStamp("4GiB"));
        assertNull(MemoryNeed.fromStamp("{\"baseline\":1}"));
        assertNull(MemoryNeed.fromStamp("{\"baseline\":1,\"tools\":[{\"name\":\"x\",\"resident\":-5,\"peak\":0}]}"));
    }

    @Test
    void lessMemoryThanTheNeedWarnsWithTheToolsAndTheSizeToPass() throws Exception {
        var need = MemoryNeed.of(List.of(
                declared(tool(IDE), Map.of("memory", "4g")),
                declared(tool(INDEXER), Map.of())), w -> fail(w));
        var out = new RecordingOutput();

        need.warnIfShort(out, "This branch of 'tpl-ide'", 2 * GIB, "2GiB", null);

        var warning = out.events().getFirst();
        assertEquals(RecordingOutput.Kind.WARN, warning.kind());
        assertTrue(warning.msg().startsWith("This branch of 'tpl-ide' gets 2GiB of memory"), warning.msg());
        var details = String.join("\n", warning.details());
        assertTrue(details.contains("ide 4.8 GiB") && details.contains("indexer 3.3 GiB"), details);
        assertTrue(details.contains("--memory 9GiB"), details);
        assertTrue(details.contains("estimate"), details);
    }

    @Test
    void enoughMemoryOrAnUnreadableSizeSaysNothing() throws Exception {
        var need = MemoryNeed.of(List.of(declared(tool(INDEXER), Map.of())), w -> fail(w));
        need.warnIfShort(RecordingOutput.FAIL_ON_WARN, "x", need.total(), "enough", null);
        need.warnIfShort(RecordingOutput.FAIL_ON_WARN, "x", -1, "max", null);
    }
}
