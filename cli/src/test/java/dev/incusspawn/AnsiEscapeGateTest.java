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
 * Colour reaches the terminal only through {@code BuildOutput.styled()}, which writes none off an
 * ANSI terminal or under {@code NO_COLOR} (#1037, #1082). A raw SGR literal ({@code \033[...m})
 * elsewhere would bypass that gate, so the sources that may hold one are listed here, each with
 * the reason it is safe.
 */
class AnsiEscapeGateTest {

    private static final Pattern RAW_SGR = Pattern.compile("(\\\\033|\\\\u001[bB])\\[[0-9;]*m");

    private static final Map<String, String> ALLOWED = Map.of(
            "BuildOutput.java", "the gate itself, and its live step, drawn only on an ANSI terminal",
            "TerminalProgress.java", "animated lines, drawn only on an ANSI terminal",
            "BuildCommand.java", "the formatters of animated TerminalProgress lines",
            "HostRepoRefresh.java", "the formatter of animated TerminalProgress lines",
            "ShellStatusBar.java", "drawn inside an interactive shell, which needs a terminal",
            "ClaudeSetup.java", "a status-line script that runs inside the instance");

    @Test
    void rawColourEscapesStayBehindTheGate() throws IOException {
        var offenders = new StringBuilder();
        for (var root : new String[] {"src/main/java", "../common/src/main/java", "../proxy/src/main/java"}) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    if (ALLOWED.containsKey(file.getFileName().toString())) continue;
                    var lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        if (RAW_SGR.matcher(lines.get(i)).find()) {
                            offenders.append(file).append(':').append(i + 1).append('\n');
                        }
                    }
                }
            }
        }
        assertEquals("", offenders.toString(), "style through BuildOutput.styled() instead");
    }
}
