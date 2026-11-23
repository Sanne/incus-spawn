package dev.incusspawn.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reading the token from what {@code claude setup-token} draws (#2543). The fixtures follow how
 * Claude Code 2.1.330 prints it: a {@code <Text color="warning">} in a column with a gap, hard
 * wrapped at the window's width, words placed by {@code ESC [ n G} rather than spaces, and lines
 * ended by {@code \r\r\n} through the pseudo-terminal.
 */
class SetupTokenCaptureTest {

    /** 108 characters, as real tokens run. */
    static final String TOKEN = "sk-ant-oat01-" + "Ab3_dE-fGh1jK2lMn4oPq5rSt6uVw7xYz8".repeat(3).substring(0, 93) + "AA";

    private static final String ESC = "\u001b";

    /** The success screen at {@code width} columns, as the renderer writes it. */
    static String successScreen(int width) {
        var first = TOKEN.substring(0, Math.min(width, TOKEN.length()));
        var rest = TOKEN.substring(first.length());
        return ESC + "[?25l" + ESC + "[32m✓" + ESC + "[39m" + ESC + "[3GLong-lived" + ESC + "[14Gauthentication"
                + ESC + "[29Gtoken" + ESC + "[35Gcreated\r\r\n\r\r\n"
                + "Your" + ESC + "[6GOAuth" + ESC + "[12Gtoken" + ESC + "[18G(valid" + ESC + "[25Gfor" + ESC + "[29G1" + ESC + "[31Gyear):\r\r\n"
                + ESC + "[33m" + first + ESC + "[39m\r\r\n"
                + (rest.isEmpty() ? "" : ESC + "[33m" + rest + ESC + "[39m\r\r\n")
                + "\r\r\n"
                + ESC + "[2mStore" + ESC + "[7Gthis" + ESC + "[12Gtoken" + ESC + "[18Gsecurely." + ESC + "[22m\r\r\n"
                + ESC + "[2mUse" + ESC + "[5Gthis" + ESC + "[10Gtoken" + ESC + "[16Gby" + ESC + "[19Gsetting:"
                + ESC + "[28Gexport" + ESC + "[35GCLAUDE_CODE_OAUTH_TOKEN=<token>" + ESC + "[22m\r\r\n"
                + ESC + "]8;;https://claude.ai/settings\u0007claude.ai" + ESC + "]8;;\u0007\r\r\n"
                + ESC + "[?25h";
    }

    private static List<String> read(String output) {
        var screen = new SetupTokenCapture.Screen();
        var bytes = output.getBytes(StandardCharsets.UTF_8);
        screen.feed(bytes, 0, bytes.length);
        return screen.tokens();
    }

    @Test
    void readsATokenWrappedAtTheWindowsWidth() {
        for (int width : List.of(40, 80, 100, 120, 200)) {
            var tokens = read(successScreen(width));
            assertFalse(tokens.isEmpty(), "width " + width);
            assertEquals(TOKEN, tokens.getFirst(), "width " + width);
        }
    }

    @Test
    void readsTheTokenWhateverHowTheBytesArriveInChunks() {
        var bytes = successScreen(80).getBytes(StandardCharsets.UTF_8);
        var screen = new SetupTokenCapture.Screen();
        for (byte b : bytes) screen.feed(new byte[] {b}, 0, 1);
        assertEquals(TOKEN, screen.tokens().getFirst());
    }

    /** A renderer that writes only the cells that changed skips the ones the old frame already held. */
    @Test
    void readsATokenWhoseRedrawSkipsUnchangedCells() {
        var oldFrame = "Paste code here if prompted\r\r\n" + TOKEN.substring(0, 20) + "x".repeat(60) + "\r\r\n";
        var redraw = ESC + "[2A" + "\r" + "Your OAuth token (valid for 1 year):" + ESC + "[K\r\r\n"
                + ESC + "[20C" + TOKEN.substring(20, 80) + "\r\r\n" + TOKEN.substring(80) + "\r\r\n\r\r\nStore" + ESC + "[7Gthis\r\r\n";
        assertEquals(TOKEN, read(oldFrame + redraw).getFirst());
    }

    /** A token drawn and then cleared, as a program does when it exits, is still read. */
    @Test
    void readsATokenThatIsClearedAfterwards() {
        var output = successScreen(80) + ESC + "[2J" + ESC + "[H" + "Goodbye\r\n";
        assertEquals(TOKEN, read(output).getFirst());
    }

    /** A frame erased line by line from the bottom, as Ink's eraseLines does, still leaves the wrapped token. */
    @Test
    void readsAWrappedTokenErasedBottomUp() {
        var frame = successScreen(80);
        long lines = frame.chars().filter(c -> c == '\n').count();
        var erase = new StringBuilder();
        for (long i = 0; i <= lines; i++) erase.append(ESC).append("[2K").append(i < lines ? ESC + "[1A" : "");
        assertEquals(TOKEN, read(frame + erase + ESC + "[GGoodbye\r\n").getFirst());
    }

    /** The token ending at the row's end does not swallow the next line's word: both readings stay. */
    @Test
    void aWrongJoinStillLeavesTheTokenItself() {
        var tokens = read(TOKEN + "\r\nStore\r\n");
        assertEquals(List.of(TOKEN + "Store", TOKEN), tokens);
    }

    @Test
    void nothingThatIsNotAWholeTokenIsRead() {
        assertEquals(List.of(), read("no token here\r\n"));
        assertEquals(List.of(), read("sk-ant-oat01-short\r\n"));
        assertEquals(List.of(), read(ESC + "]8;;" + TOKEN + "\u0007link" + ESC + "]8;;\u0007\r\n"),
                "a link's target is not on the screen");
    }

    /** A script that rejects the options prints only to stderr: it failed, and the command never drew. */
    @Test
    void aScriptThatFailsBeforeTheCommandDrawsIsTold() {
        assertTrue(new SetupTokenCapture.Run(1, List.of(), false).scriptFailed());
        assertFalse(new SetupTokenCapture.Run(130, List.of(), true).scriptFailed(), "the command ran and failed");
        assertFalse(new SetupTokenCapture.Run(null, List.of(), false).scriptFailed(), "a timeout is not a usage error");
        assertFalse(new SetupTokenCapture.Run(0, List.of(), false).scriptFailed());
    }

    @Test
    void theCommandLogsToDevNullOnBothPlatforms() {
        assertEquals(List.of("script", "-q", "/dev/null", "claude", "setup-token"), SetupTokenCapture.command(true));
        assertEquals(List.of("script", "-q", "-e", "-f", "-c", "claude setup-token", "/dev/null"),
                SetupTokenCapture.command(false));
    }

    /**
     * The real {@code script} running a stand-in {@code claude}: every byte reaches the screen, the
     * token is read, and nothing is written beside it (no {@code typescript} file).
     */
    @Test
    void readsTheTokenThroughTheRealScript(@TempDir Path dir) throws Exception {
        var mac = System.getProperty("os.name").toLowerCase().contains("mac");
        assumeTrue(Stream.of(System.getenv("PATH").split(File.pathSeparator))
                .anyMatch(p -> Files.isExecutable(Path.of(p, "script"))), "script(1) is not installed");
        var bin = Files.createDirectory(dir.resolve("bin"));
        var output = Files.writeString(dir.resolve("screen.txt"), successScreen(80));
        var claude = bin.resolve("claude");
        Files.writeString(claude, "#!/bin/sh\n[ \"$1\" = setup-token ] || exit 2\ncat '" + output + "'\n");
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwx------"));
        var work = Files.createDirectory(dir.resolve("work"));
        var command = new java.util.ArrayList<>(List.of("env", "PATH=" + bin + File.pathSeparator + System.getenv("PATH")));
        command.addAll(SetupTokenCapture.command(mac));

        var screen = new ByteArrayOutputStream();
        var run = SetupTokenCapture.run(command, ProcessBuilder.Redirect.from(new File("/dev/null")), screen,
                work.toFile(), Duration.ofSeconds(30));

        assertEquals(0, run.exitCode());
        assertEquals(TOKEN, run.tokens().getFirst());
        assertTrue(screen.toString(StandardCharsets.UTF_8).contains("CLAUDE_CODE_OAUTH_TOKEN=<token>"), "relayed to the screen");
        try (var left = Files.list(work)) {
            assertEquals(List.of(), left.toList(), "script wrote nothing to disk");
        }
    }
}
