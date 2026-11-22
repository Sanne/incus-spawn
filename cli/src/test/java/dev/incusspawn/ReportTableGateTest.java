package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A command lays out its report with {@code Report} (common/util), never with a padded format
 * string of its own. Each command once had its own widths, header and rule, so no two tables
 * looked alike and none fitted the terminal (design/terminal.md "Reports").
 */
class ReportTableGateTest {

    /** A padded conversion ({@code %-20s}, {@code "%-" + width}) or a rule drawn with repeated dashes. */
    private static final Pattern HAND_LAID = Pattern.compile("%-\\d*s|\"%-\"|\"[-─=]\"\\s*\\.repeat\\(");

    /**
     * Files that pad by hand, each with what it lays out: {@code isx init}'s frames, which are
     * its own, and pairs of a label and a value, which {@code Report} has no helper for yet.
     */
    private static final Map<String, String> PADS_BY_HAND = Map.of(
            "InitCommand.java", "isx init's banner and step boxes, drawn through BuildOutput.frame",
            "CleanCommand.java", "what each cache holds, a path and its size",
            "ResetCommand.java", "what a reset deletes, a path and its size",
            "ProxyCommand.java", "isx proxy status and the foreground proxy's banner, label and value pairs");

    @Test
    void noCommandPadsATableByHand() throws IOException {
        var offenders = new StringBuilder();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/dev/incusspawn/command"))) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                if (PADS_BY_HAND.containsKey(file.getFileName().toString())) continue;
                var lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (HAND_LAID.matcher(lines.get(i)).find()) offenders.append(file).append(':').append(i + 1).append('\n');
                }
            }
        }
        assertEquals("", offenders.toString(), "lay a report out with Report.table(), which fits columns and the terminal");
    }

    @Test
    void theGateSeesAPaddedFormat() {
        assertTrue(HAND_LAID.matcher("out.printf(\"%-20s  %s%n\", name, status);").find());
        assertTrue(HAND_LAID.matcher("var fmt = \"%-\" + width + \"s\";").find());
        assertTrue(HAND_LAID.matcher("out.println(\"-\".repeat(width));").find());
    }

    @Test
    void everyExemptFileStillPadsByHand() throws IOException {
        for (var name : PADS_BY_HAND.keySet()) {
            var text = Files.readString(Path.of("src/main/java/dev/incusspawn/command", name));
            assertTrue(HAND_LAID.matcher(text).find(), name + " no longer pads by hand: remove it from PADS_BY_HAND");
        }
    }
}
