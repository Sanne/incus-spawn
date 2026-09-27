package dev.incusspawn.config;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Transforms raw Jackson/SnakeYAML error messages into human-friendly diagnostics.
 */
public final class YamlErrors {

    private YamlErrors() {}

    private static final Pattern LINE_COL = Pattern.compile(
            "line:? (\\d+), column:? (\\d+)");

    private static final Pattern DUPLICATE_FIELD = Pattern.compile(
            "Duplicate field '([^']+)'");

    private static final Pattern RECEIVED_SHAPE = Pattern.compile(
            "from (Array|Object|String|Integer|Floating-point|Boolean|Null) value");

    /**
     * Produce a one-line, human-readable error from a YAML parse exception.
     *
     * @param filename the YAML file that failed to parse (for context)
     * @param ex       the exception thrown by Jackson/SnakeYAML
     * @return a friendly error string, never null
     */
    public static String friendly(String filename, Exception ex) {
        var raw = ex.getMessage();
        if (raw == null) return filename + ": unknown YAML error";

        var line = extractLine(raw);
        var prefix = line > 0 ? filename + ":" + line + ": " : filename + ": ";

        var dup = DUPLICATE_FIELD.matcher(raw);
        if (dup.find()) {
            var key = dup.group(1);
            return prefix + "duplicate key '" + key
                    + "' — merge into a single '" + key + "' section or remove the duplicate";
        }

        if (raw.contains("mapping values are not allowed here")) {
            return prefix + "indentation error — check that nested items use consistent spaces (not tabs)";
        }

        if (raw.contains("\\t(TAB)") || raw.contains("found character '\\t'") || raw.contains("found character '\t'")) {
            return prefix + "tabs are not allowed in YAML — use spaces for indentation";
        }

        if (raw.contains("could not find expected ':'")) {
            return prefix + "expected a ':' after a key — this usually means wrong indentation or a missing key-value separator";
        }

        if (raw.contains("expected <block end>")) {
            return prefix + "unexpected content — check indentation is consistent (YAML is whitespace-sensitive)";
        }

        if (raw.contains("while parsing a block mapping")) {
            return prefix + "invalid block structure — check that all keys at the same level have the same indentation";
        }

        if (raw.contains("while scanning a simple key")) {
            return prefix + "malformed key — check for missing ':' or incorrect indentation";
        }

        if (ex instanceof MismatchedInputException mismatch) {
            var shape = describeMismatch(mismatch, raw);
            if (shape != null) return prefix + shape;
        }

        // Fallback: extract the first meaningful SnakeYAML message line
        var firstLine = extractFirstMeaningfulLine(raw);
        if (ex instanceof JsonMappingException mapping) {
            var path = fieldPath(mapping);
            if (!path.isEmpty()) return prefix + "'" + path + "': " + firstLine;
        }
        return prefix + firstLine;
    }

    /**
     * Explain a value whose YAML shape (list, mapping, scalar) does not match what the
     * field expects, naming the field. Returns null when the mismatch cannot be described.
     */
    private static String describeMismatch(MismatchedInputException ex, String raw) {
        var path = fieldPath(ex);
        var target = ex.getTargetType();
        var m = RECEIVED_SHAPE.matcher(raw);
        if (path.isEmpty() || target == null || !m.find()) return null;

        var expected = shapeOf(target);
        var received = switch (m.group(1)) {
            case "Array" -> "a list";
            case "Object" -> "a mapping";
            case "Null" -> "an empty value";
            default -> "a single value";
        };
        if (expected.equals(received)) return null;

        var field = "'" + path + "'";
        var hint = switch (received) {
            case "a list" -> expected.equals("a single value")
                    ? " — write it on one line, e.g. '" + leafName(ex) + ": value', without a '- ' item"
                    : "";
            case "a single value" -> expected.equals("a list")
                    ? " — put each item on its own line starting with '- '"
                    : "";
            default -> "";
        };
        return field + " expects " + expected + ", but got " + received + hint;
    }

    private static String shapeOf(Class<?> type) {
        if (type.isArray() || Collection.class.isAssignableFrom(type)) return "a list";
        if (Map.class.isAssignableFrom(type)) return "a mapping";
        if (type.isPrimitive() || type.isEnum() || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type) || type == Boolean.class) {
            return "a single value";
        }
        return "a mapping";
    }

    /** The YAML path of the failing value, e.g. {@code repos[1].url}. */
    static String fieldPath(JsonMappingException ex) {
        var sb = new StringBuilder();
        for (var ref : ex.getPath()) {
            if (ref.getFieldName() != null) {
                if (!sb.isEmpty()) sb.append('.');
                sb.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }

    private static String leafName(JsonMappingException ex) {
        var refs = ex.getPath();
        for (int i = refs.size() - 1; i >= 0; i--) {
            if (refs.get(i).getFieldName() != null) return refs.get(i).getFieldName();
        }
        return "key";
    }

    static int extractLine(String message) {
        var m = LINE_COL.matcher(message);
        if (m.find()) return Integer.parseInt(m.group(1));
        return -1;
    }

    private static String extractFirstMeaningfulLine(String raw) {
        for (var line : raw.split("\n")) {
            var trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("in 'reader'")) continue;
            if (trimmed.startsWith("at [")) continue;
            if (trimmed.startsWith("^")) continue;
            return trimmed;
        }
        return raw.lines().findFirst().orElse("parse error");
    }
}
