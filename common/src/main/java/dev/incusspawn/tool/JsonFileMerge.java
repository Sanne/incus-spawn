package dev.incusspawn.tool;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusException;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.util.Map;

/**
 * A tool's {@code files:} entry with {@code merge: json}: its content deep-merged into the JSON
 * file already at its path, so a tool adds its own entries to a config another tool, an earlier
 * layer or the user also writes, instead of replacing the file whole.
 * <p>
 * Objects merge key by key, recursively; anywhere else the entry's value wins, so an array is
 * replaced, not appended to. Keys the entry does not name are kept where they were. A file that
 * does not exist, or is empty, is written as the entry stands.
 */
final class JsonFileMerge {

    /** The read's exit status when the file is not there, which {@code cat} never returns. */
    private static final int ABSENT = 3;

    private JsonFileMerge() {}

    /**
     * The text to write at {@code path}: {@code entry} merged into what the file holds now, or
     * {@code entry} as it stands when there is no file. {@code tool} names the definition in errors.
     */
    static String contentFor(Container container, String tool, String path, String entry) {
        var quoted = Container.shellQuote(path);
        var read = container.sh("[ -e " + quoted + " ] || exit " + ABSENT + "; cat " + quoted);
        if (read.exitCode() != ABSENT && !read.success()) {
            throw new IncusException("tool '" + tool + "': could not read " + path + " to merge into it: "
                    + read.stderr().strip());
        }
        try {
            return merged(read.exitCode() == ABSENT ? null : read.stdout(), tool, path, entry);
        } catch (IllegalArgumentException e) {
            throw new IncusException(e.getMessage());
        }
    }

    /**
     * {@code entry} deep-merged into {@code existing}, or {@code entry} alone when {@code existing}
     * is null (no file) or blank, as {@code ClaudeSetup.mergeClaudeJson} takes one ({@code touch}
     * makes such a file). Both must be JSON objects; the error names the tool, the path and which
     * side is not.
     */
    static String merged(String existing, String tool, String path, String entry) {
        var add = object(entry, "tool '" + tool + "': the content it merges into " + path);
        if (existing == null || existing.isBlank()) return JqValues.toPrettyJsonString(add) + "\n";
        var base = object(existing, "tool '" + tool + "': " + path + ", which it merges into,");
        return JqValues.toPrettyJsonString(base.deepMerge(add)) + "\n";
    }

    /**
     * Why {@code content}, a {@code merge: json} entry as written in the definition, cannot be
     * merged, or null when it can. A declared parameter stands for a number here, since
     * {@code ${param_port}} may be one; inside a string it stays a string either way.
     */
    static String invalidEntry(String content, Map<String, ?> parameters) {
        var text = content == null ? "" : content;
        for (var name : parameters.keySet()) {
            text = text.replace("${param_" + name + "}", "0");
        }
        try {
            object(text, "its content");
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private static JqObject object(String json, String what) {
        if (Jjq.parseForeign(json, what) instanceof JqObject object) return object;
        throw new IllegalArgumentException(what + " is not a JSON object");
    }
}
