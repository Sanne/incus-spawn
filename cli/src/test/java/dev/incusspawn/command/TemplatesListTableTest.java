package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code isx templates list} says what each template is for, so a newcomer can tell which to build (#1441). */
class TemplatesListTableTest {

    private static String table(boolean verbose) throws Exception {
        var defs = new LinkedHashMap<String, ImageDef>();
        defs.put("tpl-minimal", ImageDef.parseYaml("name: tpl-minimal\ndescription: The base OS\n"));
        var dev = ImageDef.parseYaml("name: tpl-dev\nparent: tpl-minimal\ndescription: Dev tools\n");
        dev.setSource("built-in");
        defs.put("tpl-dev", dev);
        var bytes = new ByteArrayOutputStream();
        TemplatesCommand.ListSub.printTable(new PrintStream(bytes, true, StandardCharsets.UTF_8), defs, verbose);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theDefaultTableShowsEachDescription() throws Exception {
        assertEquals(List.of(
                "NAME         DESCRIPTION",
                "tpl-minimal  The base OS",
                "tpl-dev      Dev tools"), table(false).lines().toList());
    }

    @Test
    void verboseAddsTheSource() throws Exception {
        var lines = table(true).lines().toList();
        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.getFirst().matches("NAME +SOURCE +DESCRIPTION"), lines.getFirst());
        assertTrue(lines.get(2).matches("tpl-dev +built-in +Dev tools"), lines.get(2));
    }
}
