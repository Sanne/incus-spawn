package dev.incusspawn.config;

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

        // Fallback: extract the first meaningful SnakeYAML message line
        var firstLine = extractFirstMeaningfulLine(raw);
        return prefix + firstLine;
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
