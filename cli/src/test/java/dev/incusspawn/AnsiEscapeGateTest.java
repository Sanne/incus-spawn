package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Escapes reach the terminal only through the one gate, {@code BuildOutput.ansi()}: colour via
 * {@code BuildOutput.styled()}, hyperlinks via {@code TerminalLink.link()}, and neither off an
 * ANSI terminal or under {@code NO_COLOR} (#1037, #1082). Any other raw escape literal (CSI
 * {@code \033[} or OSC {@code \033]}) must be on a path that only ever draws on a terminal (an
 * animated line, a window title behind {@code hasTerminal()}), and says so with a
 * {@value #MARKER} comment on that same line, so a marker never covers a line it was not written
 * for. {@code \\033} in a Java literal is text for a shell's {@code printf} inside the instance,
 * not an escape isx writes, and is not matched. Only the files below are exempt as a whole.
 */
class AnsiEscapeGateTest {

    private static final Pattern RAW_ESCAPE = Pattern.compile("(?<!\\\\)(\\\\033|\\\\u001[bB])[\\[\\]]");
    private static final String MARKER = "raw ANSI:";

    private static final Map<String, String> EXEMPT = Map.of(
            "BuildOutput.java", "the colour gate and its live step",
            "TerminalLink.java", "the hyperlink gate",
            "TerminalProgress.java", "animated lines and the terminal check itself",
            "ShellStatusBar.java", "drawn inside an interactive shell, which needs a terminal");

    @Test
    void rawEscapesStayBehindTheGate() throws IOException {
        var offenders = new StringBuilder();
        for (var root : new String[] {"src/main/java", "../common/src/main/java", "../proxy/src/main/java"}) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    if (EXEMPT.containsKey(file.getFileName().toString())) continue;
                    var lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        var line = lines.get(i);
                        if (RAW_ESCAPE.matcher(line).find() && !line.contains(MARKER)) {
                            offenders.append(file).append(':').append(i + 1).append('\n');
                        }
                    }
                }
            }
        }
        assertEquals("", offenders.toString(),
                "style through BuildOutput.styled() or link through TerminalLink.link(); a path that"
                        + " only ever draws on a terminal says why with a '// " + MARKER + "' comment");
    }
}
