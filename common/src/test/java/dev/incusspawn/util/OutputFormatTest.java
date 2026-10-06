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
}
