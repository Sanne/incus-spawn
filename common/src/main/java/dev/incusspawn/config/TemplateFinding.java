package dev.incusspawn.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One thing the template check found (#2076): how serious it is, the field at fault and what is
 * wrong. A form marks the field, an agent corrects its own mistake, a person reads the message.
 *
 * @param field the key path at fault, such as {@code name}, {@code repos[1].depth} or
 *              {@code coordinator.control-over}; null when the finding is about the whole file
 *              (it does not parse)
 */
public record TemplateFinding(Severity severity, String field, String message) {

    public enum Severity {
        /** The definition is refused: it is not written, and a build would not take it. */
        ERROR,
        /** Worth knowing, but the definition is taken as it is. */
        WARNING;

        /** As {@code --format=json} and {@code plain} print it: lower case. */
        public String label() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public static TemplateFinding error(String field, String message) {
        return new TemplateFinding(Severity.ERROR, field, message);
    }

    public static TemplateFinding warning(String field, String message) {
        return new TemplateFinding(Severity.WARNING, field, message);
    }

    /** Whether this finding refuses the definition. */
    public boolean isError() { return severity == Severity.ERROR; }

    /** A message that starts by quoting the key it is about: {@code unknown key 'x'} or {@code 'x' is set by isx}. */
    private static final Pattern QUOTED_KEY = Pattern.compile("^(?:unknown key )?'([^']+)'");

    /**
     * The field a warning from the definition's own checks names, else {@code fallback}: the
     * unknown-key walk quotes the key it found, so that key is the field.
     */
    static String fieldOf(String message, String fallback) {
        var m = QUOTED_KEY.matcher(message);
        return m.find() ? m.group(1) : fallback;
    }

    /** The fields of a finding in {@code --format=json}, in order: add to the end, never rename. */
    public Map<String, Object> record() {
        var record = new LinkedHashMap<String, Object>();
        record.put("severity", severity.label());
        record.put("field", field);
        record.put("message", message);
        return record;
    }
}
