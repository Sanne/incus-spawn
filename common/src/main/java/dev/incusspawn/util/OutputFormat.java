package dev.incusspawn.util;

import com.fasterxml.jackson.core.JsonProcessingException;
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
 */
public enum OutputFormat {
    TABLE, PLAIN, JSON;

    private static final Pattern LINE_BREAKING = Pattern.compile("[\t\r\n]");
    private static final ObjectMapper JSON_WRITER = new ObjectMapper()
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
     * A tab or line break inside a value becomes a space, so a record is always one line.
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
        return text.isEmpty() ? "-" : LINE_BREAKING.matcher(text).replaceAll(" ");
    }

    /** {@code value} -- a list of records, or one record -- as JSON, then a line break. */
    public static void printJson(PrintStream out, Object value) {
        try {
            out.println(JSON_WRITER.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write JSON: " + e.getMessage(), e);
        }
    }
}
