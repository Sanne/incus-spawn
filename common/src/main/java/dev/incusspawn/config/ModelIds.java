package dev.incusspawn.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The AI model IDs isx ships, read from {@code models.yaml} (#2227): the ones it sends itself, the
 * defaults it gives a coding agent and the examples in its help text. A vendor retires or
 * supersedes an ID without isx noticing, so the file is the one place the upstream watch reads
 * and {@code ModelIdsGateTest} keeps every other source file free of a model ID literal.
 */
public final class ModelIds {

    private static final String RESOURCE = "models.yaml";
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[a-z][a-z0-9.]*(-[a-z0-9.@]+)+");
    private static final Map<String, String> IDS = load();

    private ModelIds() {}

    /** The ID declared under {@code key}; a key the file lacks is a programming error. */
    public static String get(String key) {
        var id = IDS.get(key);
        if (id == null) throw new IllegalArgumentException("models.yaml declares no '" + key + "'");
        return id;
    }

    /** Every key and its ID, in file order. */
    public static Map<String, String> all() {
        return IDS;
    }

    /** Reads {@code key: id} lines, skipping blanks and comments. */
    static Map<String, String> parse(String text) {
        var map = new LinkedHashMap<String, String>();
        for (var line : text.split("\n")) {
            var trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0) throw new IllegalStateException(RESOURCE + ": not 'key: id': " + line);
            var key = trimmed.substring(0, colon).strip();
            var id = trimmed.substring(colon + 1).strip();
            // Not YAML: a quoted value or a trailing comment would be sent to a vendor as part of the ID.
            if (!ID.matcher(id).matches()) throw new IllegalStateException(RESOURCE + ": '" + key + "' is not a model ID: " + id);
            if (map.put(key, id) != null) {
                throw new IllegalStateException(RESOURCE + ": empty or repeated key: " + key);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> load() {
        try (var in = ModelIds.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is missing from the classpath");
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
