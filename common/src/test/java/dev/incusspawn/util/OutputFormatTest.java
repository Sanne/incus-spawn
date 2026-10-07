package dev.incusspawn.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OutputFormatTest {

    @Test
    void parsesTheThreeFormatsAndDefaultsToTable() {
        assertEquals(OutputFormat.TABLE, OutputFormat.parse(null));
        assertEquals(OutputFormat.PLAIN, OutputFormat.parse("plain"));
        assertEquals(OutputFormat.JSON, OutputFormat.parse(" JSON "));
        var e = assertThrows(IllegalArgumentException.class, () -> OutputFormat.parse("yaml"));
        assertTrue(e.getMessage().contains("table, plain or json"), e.getMessage());
    }

    @Test
    void aPlainRecordIsAlwaysOneLineOfTabSeparatedFields() {
        var record = new LinkedHashMap<String, Object>();
        record.put("name", "a");
        record.put("empty", "");
        record.put("absent", null);
        record.put("size", 42L);
        record.put("note", "two\twords\nand a line");
        var bytes = new ByteArrayOutputStream();
        OutputFormat.printPlain(new PrintStream(bytes, true, StandardCharsets.UTF_8), List.of(record));
        assertEquals("a\t-\t-\t42\ttwo words and a line\n", bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void jsonKeepsFieldOrderAndNulls() {
        var record = new LinkedHashMap<String, Object>();
        record.put("b", 1L);
        record.put("a", null);
        var bytes = new ByteArrayOutputStream();
        OutputFormat.printJson(new PrintStream(bytes, true, StandardCharsets.UTF_8), List.of(record, Map.of()));
        assertEquals("[ {\n  \"b\" : 1,\n  \"a\" : null\n}, { } ]\n",
                bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void aListIsCommaJoinedInPlainAndAnArrayInJson() {
        var record = new LinkedHashMap<String, Object>();
        record.put("name", "a");
        record.put("tags", List.of("x", "y"));
        record.put("none", List.of());
        var plain = new ByteArrayOutputStream();
        OutputFormat.PLAIN.printOne(new PrintStream(plain, true, StandardCharsets.UTF_8), record);
        assertEquals("a\tx,y\t-\n", plain.toString(StandardCharsets.UTF_8));
        var json = new ByteArrayOutputStream();
        OutputFormat.JSON.printOne(new PrintStream(json, true, StandardCharsets.UTF_8), record);
        assertEquals("{\n  \"name\" : \"a\",\n  \"tags\" : [ \"x\", \"y\" ],\n  \"none\" : [ ]\n}\n",
                json.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void noControlCharacterInAValueReachesTheTerminalInPlain() {
        // #1118: an escape sequence a value carries, 7-bit (ESC [) or 8-bit (U+009B CSI), DEL,
        // or a Unicode line separator, becomes a space like a tab does; everything else is kept.
        var record = new LinkedHashMap<String, Object>();
        record.put("esc", "a\u001b[2Jb");
        record.put("csi", "a\u009b2Jb");
        record.put("others", "\u0000\u0007\u007f\u0085\u2028\u2029x");
        record.put("kept", "caf\u00e9 \\ \uD83D\uDC69\u200D\uD83D\uDCBB");
        record.put("list", List.of("x\u001b]0;t\u0007", "y"));
        var bytes = new ByteArrayOutputStream();
        OutputFormat.PLAIN.printOne(new PrintStream(bytes, true, StandardCharsets.UTF_8), record);
        assertEquals("a [2Jb\ta 2Jb\t      x\tcaf\u00e9 \\ \uD83D\uDC69\u200D\uD83D\uDCBB\tx ]0;t ,y\n",
                bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void noBidiControlInAValueReordersWhatPlainShows() {
        // #1118 review: an embedding, override or isolate (U+202A-U+202E, U+2066-U+2069) makes a
        // terminal draw the rest of the line out of order, so plain spaces it like a control.
        var record = new LinkedHashMap<String, Object>();
        record.put("bidi", "a\u202Ab\u202Bc\u202Cd\u202De\u202Ef\u2066g\u2067h\u2068i\u2069j");
        record.put("kept", "\u200E\u200F\u05D0\u0627");
        var bytes = new ByteArrayOutputStream();
        OutputFormat.PLAIN.printOne(new PrintStream(bytes, true, StandardCharsets.UTF_8), record);
        assertEquals("a b c d e f g h i j\t\u200E\u200F\u05D0\u0627\n", bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void jsonKeepsAControlCharacterExactButNeverRaw() throws Exception {
        // #1118: json escapes every isControl character, exactly; Jackson alone escapes only C0.
        var value = "a\u001b[2J\u007f\u009b\u0085\u2028\u2029\u00e9\u202A\u202E\u2066\u2069\u200Fb";
        var bytes = new ByteArrayOutputStream();
        OutputFormat.JSON.printOne(new PrintStream(bytes, true, StandardCharsets.UTF_8), Map.of("v", value));
        var text = bytes.toString(StandardCharsets.UTF_8);
        assertEquals("{\n  \"v\" : \"a\\u001B[2J\\u007F\\u009B\\u0085\\u2028\\u2029\u00e9"
                        + "\\u202A\\u202E\\u2066\\u2069\u200Fb\"\n}\n",
                text.replace("\r\n", "\n"));
        assertTrue(text.codePoints().noneMatch(c -> c >= 0x202A && c <= 0x202E || c >= 0x2066 && c <= 0x2069),
                "no raw bidi control: " + text);
        assertEquals(value, new com.fasterxml.jackson.databind.ObjectMapper().readTree(text).get("v").asText());
    }
}
