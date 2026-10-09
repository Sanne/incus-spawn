package dev.incusspawn.config;

import dev.incusspawn.util.OutputFormat;
import io.hyperfoil.tools.jjq.mapper.JqMapperException;
import io.hyperfoil.tools.jjq.mapper.ShapeMismatchException;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYamlException;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Transforms raw parser error messages into human-friendly diagnostics.
 */
public final class YamlErrors {

    private YamlErrors() {}

    private static final Pattern LINE_COL = Pattern.compile(
            "line:? (\\d+), column:? (\\d+)");

    private static final Pattern DUPLICATE_FIELD = Pattern.compile(
            "Duplicate field '([^']+)'");

    /**
     * Produce a one-line, human-readable error from a YAML parse exception.
     *
     * @param filename the YAML file that failed to parse (for context)
     * @param ex       the exception thrown while parsing
     * @return a friendly error string, never null
     */
    public static String friendly(String filename, Exception ex) {
        // It quotes the file's name, keys and parser text, which a project-local file controls (#1133).
        return OutputFormat.oneLine(describe(filename, ex));
    }

    private static String describe(String filename, Exception ex) {
        // jjq failures are wrapped in IOException at the parse entry points
        // (see Jjq) to preserve caller contracts; render the structured cause.
        if (ex instanceof IOException && ex.getCause() instanceof JqMapperException) {
            return describe(filename, (Exception) ex.getCause());
        }
        if (ex instanceof IOException && ex.getCause() instanceof JqYamlException) {
            return describe(filename, (Exception) ex.getCause());
        }
        if (ex instanceof IOException
                && ex.getCause() instanceof io.hyperfoil.tools.jjq.yaml.YamlParseException) {
            return describe(filename, (Exception) ex.getCause());
        }
        if (ex instanceof io.hyperfoil.tools.jjq.yaml.YamlParseException nativeParse) {
            return friendlyNative(filename, nativeParse);
        }
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

        if (raw.contains("tabs are not allowed for indentation")
                || raw.contains("tabs cannot separate")) {
            return prefix + "tabs are not allowed in YAML — use spaces for indentation";
        }

        if (raw.contains("\\t(TAB)") || raw.contains("found character '\\t'") || raw.contains("found character '\t'")) {
            return prefix + "tabs are not allowed in YAML — use spaces for indentation";
        }

        if (raw.contains("could not find expected ':'")) {
            return prefix + "expected a ':' after a key — this usually means wrong indentation or a missing key-value separator";
        }

        if (raw.contains("unexpected indentation")) {
            return prefix + "unexpected indentation — check that nested items use consistent spaces (not tabs)";
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

        if (ex instanceof JqYamlException dupKey && dupKey.key() != null) {
            var dupPrefix = dupKey.line() > 0 ? filename + ":" + dupKey.line() + ": " : filename + ": ";
            return dupPrefix + "duplicate key '" + dupKey.key()
                    + "' — merge into a single '" + dupKey.key() + "' section or remove the duplicate";
        }

        if (ex instanceof ShapeMismatchException mismatch) {
            var shape = describeJjqMismatch(mismatch);
            if (shape != null) return prefix + shape;
        }

        // Fallback: extract the first meaningful message line
        var firstLine = extractFirstMeaningfulLine(raw);
        return prefix + firstLine;
    }

    /**
     * The native jjq parser reports structured messages with the line appended
     * ({@code <message> at line N}). Render with the file:line prefix and the
     * line suffix trimmed, plus guidance for the common mistakes.
     */
    private static String friendlyNative(String filename,
                                         io.hyperfoil.tools.jjq.yaml.YamlParseException ex) {
        var raw = String.valueOf(ex.getMessage()).replaceAll(" at line \\d+$", "");
        var prefix = ex.line() > 0 ? filename + ":" + ex.line() + ": " : filename + ": ";
        if (raw.contains("tabs are not allowed for indentation")
                || raw.contains("tabs cannot separate")) {
            return prefix + "tabs are not allowed in YAML — use spaces for indentation";
        }
        if (raw.contains("unexpected indentation")) {
            return prefix + "unexpected indentation — check that nested items use consistent spaces (not tabs)";
        }
        if (raw.contains("expected mapping key") || raw.contains("expected '-' sequence entry")
                || raw.contains("expected end of line")) {
            return prefix + raw + " — check indentation is consistent (YAML is whitespace-sensitive)";
        }
        return prefix + raw;
    }

    /**
     * The jjq equivalent of a shape mismatch, read structurally off
     * {@code ShapeMismatchException} (see Hyperfoil/jjq#86) — no message
     * parsing. Non-mismatch {@code JqMapperException}s (e.g. converter
     * validation errors, already user-friendly) never reach here and fall
     * through to the generic path.
     */
    private static String describeJjqMismatch(ShapeMismatchException ex) {
        var expected = shapeName(ex.expected());
        var received = receivedName(ex.actual());
        if (expected == null || received == null || expected.equals(received)) return null;
        var path = ex.path();
        if (path == null || path.isEmpty()) return null;
        if (path.startsWith("$.")) path = path.substring(2);
        if (path.equals("$")) return null;
        return mismatchMessage(path, expected, received, leafName(path));
    }

    private static String shapeName(JqValue.Type type) {
        return switch (type) {
            case ARRAY -> "a list";
            case OBJECT -> "a mapping";
            case STRING, NUMBER, BOOLEAN -> "a single value";
            case NULL -> "an empty value";
        };
    }

    private static String receivedName(JqValue.Type type) {
        if (type == null) return null;
        return shapeName(type);
    }

    private static String mismatchMessage(String path, String expected, String received, String leaf) {
        var field = "'" + path + "'";
        var hint = switch (received) {
            case "a list" -> expected.equals("a single value")
                    ? " — write it on one line, e.g. '" + leaf + ": value', without a '- ' item"
                    : "";
            case "a single value" -> expected.equals("a list")
                    ? " — put each item on its own line starting with '- '"
                    : "";
            default -> "";
        };
        return field + " expects " + expected + ", but got " + received + hint;
    }

    /** Last dotted segment of a {@code $.a[0].b}-style path, without indices. */
    static String leafName(String path) {
        var leaf = path;
        var dot = leaf.lastIndexOf('.');
        if (dot >= 0) leaf = leaf.substring(dot + 1);
        var bracket = leaf.indexOf('[');
        if (bracket >= 0) leaf = leaf.substring(0, bracket);
        return leaf.isEmpty() ? "key" : leaf;
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
