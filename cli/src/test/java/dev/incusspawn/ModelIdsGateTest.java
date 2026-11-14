package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An AI model ID is declared in {@code common/src/main/resources/models.yaml} and nowhere else
 * (#2227): that file is what the upstream watch reads to learn that a vendor retired an ID or
 * published its successor, so an ID written into a source file would go stale unnoticed.
 */
class ModelIdsGateTest {

    /** A Claude, OpenAI or Gemini model ID inside a string literal. */
    private static final Pattern LITERAL = Pattern.compile(
            "\"[^\"\\n]*\\b(claude-(sonnet|opus|haiku|fable)|gpt-[0-9]|gemini-[0-9]|gpt-oss|o[134]-(mini|pro))[a-z0-9.@-]*[^\"\\n]*\"");

    /** A lookup's key names an ID ("example-claude-opus"), it is not one. */
    private static final Pattern LOOKUP = Pattern.compile("ModelIds\\.get\\(\"[^\"]*\"\\)");

    /** Files that may name a model ID, each with why. */
    private static final java.util.Map<String, String> ALLOWED = java.util.Map.of(
            "ModelIds.java", "reads the file; its documentation names the file, not an ID");

    @Test
    void noSourceFileNamesAModelId() throws IOException {
        var found = new ArrayList<String>();
        for (var root : new String[] {"../cli/src/main", "../common/src/main", "../proxy/src/main"}) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (var file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    if (ALLOWED.containsKey(file.getFileName().toString())) continue;
                    int n = 0;
                    for (var line : Files.readAllLines(file)) {
                        n++;
                        var t = line.strip();
                        if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) continue;
                        if (LITERAL.matcher(LOOKUP.matcher(line).replaceAll("")).find()) found.add(file + ":" + n + ": " + t);
                    }
                }
            }
        }
        assertTrue(found.isEmpty(),
                "a model ID belongs in common/src/main/resources/models.yaml, read through ModelIds:\n"
                        + String.join("\n", found));
    }
}
