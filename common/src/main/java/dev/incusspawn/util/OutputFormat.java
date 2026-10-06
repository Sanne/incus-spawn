package dev.incusspawn.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * How a query command prints its results: {@code --format=table|plain|json} (#1036).
 *
 * <p>{@code table} is for people and may change. {@code plain} and {@code json} are a contract
 * with scripts: fields may be added, never renamed, removed or reordered. Neither carries
 * colour, glyphs, padding, headers or prose, and no results means empty output (or {@code []}),
 * never a sentence. A command builds each record once, as an ordered map of field name to value,
 * and both machine formats print that same map, so they cannot drift apart. Values are strings,
 * numbers, booleans or lists of strings; times are ISO-8601 and sizes are bytes, never "3h ago"
 * or "2.1G". A list is a JSON array, and in {@code plain} its elements joined by {@code ,}.
 *
 * <p>Both may be read on a terminal, so neither lets a value write a control character to it
 * raw (#1118): a value can be a stamp someone set by hand, escape sequence and all. {@code json}
 * keeps every value exact, writing each control character as a JSON unicode escape. {@code plain}
 * is one line per record and lossy: each control character becomes a space ({@link #oneLine}),
 * as the table shows it. A script that needs a value exactly reads {@code json}.
 */
public enum OutputFormat {
    TABLE, PLAIN, JSON;

    /**
     * What a terminal may act on or break a line at: C0 (tab, line breaks and ESC included), DEL,
     * C1 (U+009B is an 8-bit CSI), and the Unicode line and paragraph separators.
     */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");
    private static final ObjectMapper JSON_WRITER = new ObjectMapper(
            new JsonFactory().setCharacterEscapes(new ControlEscapes()))
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** The value of a {@code --format} option; {@code null} (not given) is {@link #TABLE}. */
    public static OutputFormat parse(String value) {
        if (value == null) return TABLE;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "table" -> TABLE;
            case "plain" -> PLAIN;
            case "json" -> JSON;
            default -> throw new IllegalArgumentException(
                    "unknown format '" + value + "': expected table, plain or json");
        };
    }

    /**
     * The format a command's {@code --format} and {@code --plain} options ask for together:
     * {@code --plain} is {@code --format=plain}, and contradicting it is an error.
     */
    public static OutputFormat resolve(String format, boolean plain) {
        var parsed = parse(format);
        if (!plain) return parsed;
        if (format != null && parsed != PLAIN) {
            throw new IllegalArgumentException("--plain is --format=plain; it cannot be combined with --format=" + format);
        }
        return PLAIN;
    }

    /**
     * Print {@code records} in this machine format: one line each in {@code plain}, an array in
     * {@code json}. {@code table} is the command's own and never comes here.
     */
    public void print(PrintStream out, List<? extends Map<String, ?>> records) {
        switch (this) {
            case PLAIN -> printPlain(out, records);
            case JSON -> printJson(out, records);
            case TABLE -> throw new IllegalStateException("table output is the command's own");
        }
    }

    /** Print the one record of a single-item command: one line in {@code plain}, an object in {@code json}. */
    public void printOne(PrintStream out, Map<String, ?> record) {
        switch (this) {
            case PLAIN -> printPlain(out, List.of(record));
            case JSON -> printJson(out, record);
            case TABLE -> throw new IllegalStateException("table output is the command's own");
        }
    }

    /**
     * One record per line, its values tab-separated in field order, {@code -} for an empty one.
     * A control character inside a value, tab and line break included, becomes a space
     * ({@link #oneLine}), so a record is always one line and safe to show on a terminal.
     */
    public static void printPlain(PrintStream out, List<? extends Map<String, ?>> records) {
        for (var record : records) {
            out.println(record.values().stream().map(OutputFormat::plainField)
                    .collect(Collectors.joining("\t")));
        }
    }

    static String plainField(Object value) {
        var text = value == null ? ""
                : value instanceof List<?> list ? list.stream().map(String::valueOf).collect(Collectors.joining(","))
                : value.toString();
        return text.isEmpty() ? "-" : oneLine(text);
    }

    /**
     * {@code value} as one line that cannot drive a terminal: every control character becomes a
     * space. The {@code plain} format's rule, and the one for any table or TUI cell showing a
     * value isx did not write itself.
     */
    public static String oneLine(String value) {
        return CONTROL.matcher(value).replaceAll(" ");
    }

    /** {@code value} -- a list of records, or one record -- as JSON, then a line break. */
    public static void printJson(PrintStream out, Object value) {
        try {
            out.println(JSON_WRITER.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Jackson escapes C0 itself (JSON requires it) but writes DEL, C1 and U+2028/U+2029 raw;
     * these escape them as well, so json keeps every value exact without a terminal acting on it.
     */
    private static final class ControlEscapes extends CharacterEscapes {
        private final int[] ascii = standardAsciiEscapesForJSON();

        ControlEscapes() {
            ascii[0x7F] = ESCAPE_STANDARD;
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return ascii;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            return (ch >= 0x80 && ch <= 0x9F) || ch == 0x2028 || ch == 0x2029
                    ? new SerializedString(String.format("\\u%04X", ch)) : null;
        }
    }
}
