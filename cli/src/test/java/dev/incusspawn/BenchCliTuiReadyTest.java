package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code bench/cli.sh} collects the release's CLI profile by driving the TUI, and presses its keys
 * only once the screen shows a running instance. Nothing runs that collection before a release, so
 * when the TUI stopped printing a STATUS column the word it waited for never came, and the v0.6.1
 * release failed. The bench's pattern is held here to the TUI's own rendering of a running instance,
 * so a change to that rendering fails this test, not the release.
 */
class BenchCliTuiReadyTest {

    private static final Path SCRIPT = Path.of("../bench/cli.sh").toAbsolutePath();
    private static final Path RUNNING_SCREEN = Path.of("src/test/resources/tui-snapshots/tui-main-instances-80x24.txt");
    private static final Pattern READY = Pattern.compile("^TUI_READY = re\\.compile\\(rb\"(.*)\"\\)$", Pattern.MULTILINE);
    private static final Pattern BYTE_ESCAPE = Pattern.compile("(?:\\\\x[0-9a-fA-F]{2})+");

    @Test
    void waitsForWhatTheTuiShowsOfARunningInstance() throws Exception {
        var ready = benchPattern();
        var screen = Files.readString(RUNNING_SCREEN);
        assertTrue(ready.matcher(screen).find(), "bench/cli.sh's TUI_READY does not match a running instance on " + RUNNING_SCREEN);
        var header = screen.lines().findFirst().orElseThrow();
        assertTrue(header.contains("container running"), "the snapshot's header changed: " + header);
        assertFalse(ready.matcher(header).find(), "TUI_READY matches the header's count, before any instance row is drawn");
    }

    /** The Python bytes pattern as a Java one: its {@code \xHH} runs are UTF-8, the rest is the same syntax. */
    private static Pattern benchPattern() throws Exception {
        var matcher = READY.matcher(Files.readString(SCRIPT));
        assertTrue(matcher.find(), "no TUI_READY in " + SCRIPT);
        var escapes = BYTE_ESCAPE.matcher(matcher.group(1));
        var java = new StringBuilder();
        while (escapes.find()) {
            var bytes = new ByteArrayOutputStream();
            var run = escapes.group();
            for (int i = 0; i < run.length(); i += 4) bytes.write(Integer.parseInt(run.substring(i + 2, i + 4), 16));
            escapes.appendReplacement(java, Matcher.quoteReplacement(Pattern.quote(bytes.toString(StandardCharsets.UTF_8))));
        }
        escapes.appendTail(java);
        return Pattern.compile(java.toString()); // regex at run time: the pattern is read from bench/cli.sh
    }
}
