package dev.incusspawn.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuildOutputTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        var sink = new PrintStream(captured, true);
        System.setOut(sink);
        System.setErr(sink);
    }

    @AfterEach
    void restore() {
        BuildOutput.forceAnsi = null;
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private String text() {
        return captured.toString();
    }

    @Test
    void aWindowTitleIsOneLineInAnEscapeAndOnlyOnATerminal() {
        BuildOutput.forceAnsi = false;
        BuildOutput.windowTitle("isx:dev-1");
        assertEquals("", text(), "no escape off a terminal");
        BuildOutput.forceAnsi = true;
        BuildOutput.windowTitle("⚠ log in\u0007\u001b]0;not isx");
        assertEquals("\u001b]0;⚠ log in  ]0;not isx\u0007", text(), "nothing in the title ends its escape");
    }

    @Test
    void plainStepCompletesItsLine() {
        BuildOutput.stepStart("Waiting for network");
        BuildOutput.stepDone();
        BuildOutput.stepStart("Extracting root disk");
        BuildOutput.stepDone("4.0G");
        assertEquals("""
                    Waiting for network... done.
                    Extracting root disk... done (4.0G).
                """, text());
    }

    /** A block opens at column 2 with its header, so the line that closes it is drawn there too. */
    @Test
    void successClosesTheBlockAtTheHeadersColumn() {
        BuildOutput.forceAnsi = false;
        BuildOutput.step("before");
        BuildOutput.header("Building t", null);
        BuildOutput.step("x");
        BuildOutput.success("t built");
        assertEquals("    before\n\n  ● Building t\n    x\n\n  ✓ t built\n", text());
    }

    /** A slow step's time is in a plain terminal's log too, as on the ✓ line. */
    @Test
    void aPlainTerminalSaysHowLongASlowStepTook() throws Exception {
        BuildOutput.forceAnsi = false;
        BuildOutput.stepStart("Importing base image");
        Thread.sleep(2100);
        BuildOutput.stepDone();
        assertTrue(text().matches("    Importing base image\\.\\.\\. done in 2(\\.\\d)?s\\.\n"), text());
    }

    /** A label is bare in source, and a plain terminal adds the ellipsis. */
    @Test
    void aPlainTerminalAddsTheEllipsisToABareLabel() {
        BuildOutput.forceAnsi = false;
        BuildOutput.stepStart("Waiting");
        BuildOutput.stepDone();
        System.out.println("between");
        BuildOutput.stepStart("Waiting");
        System.err.println("mid-step");
        BuildOutput.stepDone();
        assertEquals("""
                    Waiting... done.
                between
                    Waiting...
                mid-step
                    Waiting... done.
                """, text());
    }

    /** A failed step ends in its cross, and why it failed is a warning under it. */
    @Test
    void stepFailReportsUnderTheCross() {
        BuildOutput.forceAnsi = true;
        BuildOutput.stepStart("Verifying DNS resolution");
        BuildOutput.stepFail("no answer from 10.0.0.1\nlast line of its output");
        assertEquals(List.of("    ✗ Verifying DNS resolution", "      ⚠ no answer from 10.0.0.1",
                "        last line of its output", ""), lastLines());
    }

    /** A live step's detail is read again on every frame, so a value that keeps changing (a download's rate) is current. */
    @Test
    void stepProgressReadsItsSupplierPerFrame() throws Exception {
        BuildOutput.forceAnsi = true;
        var value = new java.util.concurrent.atomic.AtomicReference<>("1 MB/s");
        BuildOutput.stepStart("Downloading image");
        BuildOutput.stepProgress(value::get);
        awaitScreen("Downloading image  1 MB/s");
        value.set("2 MB/s");
        awaitScreen("Downloading image  2 MB/s");
        BuildOutput.stepDone();
        assertEquals("    ✓ Downloading image", lastLines().getLast());
    }

    /** A detail that cannot be read this frame shows none, and the spinner keeps drawing. */
    @Test
    void aThrowingDetailDoesNotStopTheSpinner() throws Exception {
        BuildOutput.forceAnsi = true;
        var value = new java.util.concurrent.atomic.AtomicReference<String>();
        BuildOutput.stepStart("Downloading image");
        BuildOutput.stepProgress(() -> java.util.Objects.requireNonNull(value.get()));
        Thread.sleep(200);
        value.set("1 MB/s\u001b[2J");
        awaitScreen("Downloading image  1 MB/s [2J");
        BuildOutput.stepDone();
    }

    /** Wait for the spinner to draw {@code expected}, failing after a few seconds. */
    private void awaitScreen(String expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!BuildOutput.stripAnsi(text()).contains(expected)) {
            if (System.nanoTime() > deadline) fail("never drew '" + expected + "': " + text());
            Thread.sleep(10);
        }
    }

    @Test
    void plainOutputPrintedMidStepMovesBelowTheStepLine() {
        BuildOutput.stepStart("Installing foo");
        System.err.println("    Warning: something");
        BuildOutput.stepDone();
        assertEquals("""
                    Installing foo...
                    Warning: something
                    Installing foo... done.
                """, text());
    }

    /** A result is printed as given, at column 0, and one written mid-step moves below the step's line. */
    @Test
    void resultsArePrintedAsGivenBelowALiveStep() {
        BuildOutput.results().println("Proxy is running.");
        BuildOutput.stepStart("Checking");
        BuildOutput.results().println("Version: 1.0");
        BuildOutput.stepDone();
        assertEquals("""
                Proxy is running.
                    Checking...
                Version: 1.0
                    Checking... done.
                """, text());
    }

    /** A frame is drawn as laid out, at column 0; a question sits at the step indent, its answer typed after it. */
    @Test
    void aFrameIsDrawnAsGivenAndAQuestionAtTheStepIndent() {
        BuildOutput.frame("╭──╮", "│ 1│", "╰──╯");
        BuildOutput.prompt("Install services? (Y/n): ");
        try (var group = BuildOutput.group("Approve", null)) {
            BuildOutput.prompt("tpl-agent? (y/N): ");
        }
        assertEquals("╭──╮\n│ 1│\n╰──╯\n    Install services? (Y/n): \n    ▸ Approve\n      tpl-agent? (y/N): \n", text());
    }

    /** A frame that ends on a blank line ends its block, as a group does: the group after it adds no second one. */
    @Test
    void aFrameEndingOnABlankLineIsNotFollowedByAnother() {
        BuildOutput.frame("", "╭──╮", "╰──╯", "");
        try (var group = BuildOutput.group("Approve", null)) {
            BuildOutput.step("tpl-agent");
        }
        assertEquals("\n╭──╮\n╰──╯\n\n    ▸ Approve\n      tpl-agent\n\n", text());
    }

    /**
     * A frame keeps its leading blank line after a block that ended on one, since a raw line
     * printed between them, past the output, cannot be seen: the frame after it stays apart.
     */
    @Test
    void aFrameKeepsItsLeadingBlankLineAfterARawLine() {
        BuildOutput.frame("", "╭──╮", "╰──╯", "");
        System.out.println("  raw step line");
        BuildOutput.frame("", "╭──╮", "╰──╯", "");
        assertEquals("\n╭──╮\n╰──╯\n\n  raw step line\n\n╭──╮\n╰──╯\n\n", text());
    }

    @Test
    void streamsAreRestoredWhenTheStepEnds() {
        var out = System.out;
        var err = System.err;
        BuildOutput.stepStart("Working");
        assertNotSame(out, System.out, "a live step guards stdout");
        BuildOutput.stepDone();
        assertSame(out, System.out);
        assertSame(err, System.err);
    }

    @Test
    void ansiStepTurnsIntoACheck() {
        BuildOutput.forceAnsi = true;
        BuildOutput.stepStart("Deriving from parent image 'tpl-dev'");
        BuildOutput.stepDone();
        var lines = lastLines();
        assertEquals("    ✓ Deriving from parent image 'tpl-dev'", lines.getLast());
    }

    @Test
    void ansiFailedStepTurnsIntoACross() {
        BuildOutput.forceAnsi = true;
        BuildOutput.stepStart("Verifying DNS resolution");
        BuildOutput.stepBreak();
        assertEquals("    ✗ Verifying DNS resolution", lastLines().getLast());
    }

    @Test
    void ansiOutputPrintedMidStepFreezesTheLineAndStopsDrawingIt() throws Exception {
        BuildOutput.forceAnsi = true;
        BuildOutput.stepStart("Installing foo");
        System.err.println("    ⚠ careful");
        var afterWarning = text().length();
        Thread.sleep(250); // several spinner ticks
        assertEquals(afterWarning, text().length(), "the spinner must not draw over later output");
        BuildOutput.stepDone();
        assertEquals(List.of("    … Installing foo", "    ⚠ careful", "    ✓ Installing foo"), lastLines());
    }

    @Test
    void progressDetailShowsWhileRunning() throws Exception {
        BuildOutput.forceAnsi = true;
        BuildOutput.stepStart("Installing mx");
        BuildOutput.stepProgress("verifying: mx version");
        awaitScreen("Installing mx  verifying: mx version");
        BuildOutput.stepDone();
        assertEquals("    ✓ Installing mx", lastLines().getLast(), "the detail is gone once done");
    }

    @Test
    void groupIndentsItsStepsAndEndsWithABlankLine() {
        try (var group = BuildOutput.group("Tools", "maven-3, mx")) {
            BuildOutput.step("inside");
            BuildOutput.list(List.of("a", "b"));
        }
        BuildOutput.step("outside");
        assertEquals("""

                    ▸ Tools  maven-3, mx
                      inside
                      a, b

                    outside
                """, BuildOutput.stripAnsi(text()));
    }

    @Test
    void consecutiveGroupsAreSeparatedByOneBlankLine() {
        try (var packages = BuildOutput.group("Packages", null)) {
            BuildOutput.step("p");
        }
        try (var tools = BuildOutput.group("Tools", null)) {
            BuildOutput.step("t");
            BuildOutput.stepNote("version 1");
        }
        BuildOutput.success("built");
        assertEquals("""

                    ▸ Packages
                      p

                    ▸ Tools
                      t
                        version 1

                  ✓ built
                """, BuildOutput.stripAnsi(text()));
    }

    @Test
    void warningIsFollowedByOneBlankLine() {
        try (var tools = BuildOutput.group("Verifying", null)) {
            BuildOutput.step("mvn");
            BuildOutput.stepWarn("broken");
            BuildOutput.step("mx");
            BuildOutput.stepWarn("broken too");
        }
        BuildOutput.warn("top-level", "what to do");
        BuildOutput.step("next");
        // The blank line before the group depends on how the previous test's output ended.
        assertEquals("""
                    ▸ Verifying
                      mvn
                        ⚠ broken

                      mx
                        ⚠ broken too

                    ⚠ top-level
                      what to do

                    next
                """, BuildOutput.stripAnsi(text()).replaceFirst("^\n", ""));
    }

    @Test
    void headerResetsAGroupLeftOpenByAFailure() {
        BuildOutput.group("Packages", null);
        BuildOutput.header("Next");
        BuildOutput.step("top level");
        assertTrue(text().endsWith("\n    top level\n"));
    }

    @Test
    void plainOutputHasNoEscapes() {
        BuildOutput.forceAnsi = false;
        BuildOutput.header("Building tpl-dev");
        BuildOutput.header("Resizing", "1G → 2G");
        BuildOutput.buildHeader("tpl-dev", 1, 2);
        BuildOutput.branchHeader("dev-1", "tpl-dev");
        try (var group = BuildOutput.group("Tools", "maven-3")) {
            BuildOutput.stepNote("version 1");
            BuildOutput.stepWarn("broken");
        }
        BuildOutput.ok("verified");
        BuildOutput.note("a note");
        BuildOutput.warn("careful", "what to do");
        BuildOutput.success("built");
        BuildOutput.warnBanner("Subnet conflict", "body");
        assertEquals(BuildOutput.stripAnsi(text()), text(), "no escape off an ANSI terminal");
        assertTrue(text().contains("● Building tpl-dev"));
    }

    /** A note quotes template text, which must not start a line or drive the terminal (#1235). */
    @Test
    void noteIsOneLineThatCannotDriveTheTerminal() {
        BuildOutput.forceAnsi = false;
        BuildOutput.note("Host repo /p/\u001b[2Jx\u009b31m\r✓ built\nTemplate is valid.\u0007");
        BuildOutput.note("\u001b\u0007");
        assertEquals("Host repo /p/ [2Jx 31m ✓ built Template is valid. \n", text().stripLeading());
    }

    /** A step's text quotes template text too: its start, note and failure keep isx's line breaks only (#1235). */
    @Test
    void stepStartCannotDriveTheTerminal() {
        BuildOutput.forceAnsi = false;
        BuildOutput.stepStart("Skipping /p/\u001b[2Jx\u009b31m");
        BuildOutput.stepDone();
        assertFalse(text().contains("\u001b") || text().contains("\u009b"), text());
        assertTrue(text().contains("Skipping /p/ [2Jx 31m"), text());
    }

    @Test
    void stepNoteCannotDriveTheTerminal() {
        BuildOutput.forceAnsi = false;
        BuildOutput.stepNote("verify said \u001b[2Jx\nsecond line\rforged");
        assertFalse(text().contains("\u001b") || text().contains("\r"), text());
        assertTrue(text().contains("verify said  [2Jx\n") && text().contains("second line forged"), text());
    }

    @Test
    void stepFailCannotDriveTheTerminal() {
        BuildOutput.forceAnsi = false;
        BuildOutput.stepFail("failed at /p/\u001b[2Jx\u009b31m\nadvice stays");
        assertFalse(text().contains("\u001b") || text().contains("\u009b"), text());
        assertTrue(text().contains("failed at /p/ [2Jx 31m\n        advice stays"), text());
    }

    /** A warning keeps isx's own line breaks, but what it quotes cannot drive the terminal (#1235). */
    @Test
    void warningLinesCannotDriveTheTerminal() {
        BuildOutput.forceAnsi = false;
        BuildOutput.warn("Mount of /p/\u001b[2Jx refused.\nFix the definition.", "detail \u009b31m");
        BuildOutput.stepWarn("step /p/\u001b[2J");
        assertFalse(text().contains("\u001b") || text().contains("\u009b"), text());
        assertTrue(text().contains("Mount of /p/ [2Jx refused.\nFix the definition."), text());
    }

    /** A message marked for a sink that prints it as it comes gets the ⚠ alone, here and in the copy. */
    @Test
    void aWarningThatMarksItselfIsMarkedOnce() {
        var copied = new java.util.ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.copying(BuildOutput.terminal(), Output.diagnosticsTo(copied::add)))) {
            BuildOutput.warn("Warning: filtering disabled on box");
        }
        assertEquals(List.of("filtering disabled on box"), copied);
        assertTrue(text().contains("⚠ filtering disabled on box"), text());
        assertFalse(text().contains("Warning:"), text());
    }

    /** Why a command failed is an error on stderr, red on a terminal, with no blank line on stdout after it. */
    @Test
    void anErrorGoesToStderrAloneAndSaysSo() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        BuildOutput.forceAnsi = false;
        BuildOutput.error("no appliance kernel", "Run 'isx vm start' again once it is downloaded.");
        assertEquals("", out.toString());
        assertEquals("""
                    Error: no appliance kernel
                      Run 'isx vm start' again once it is downloaded.
                """, err.toString());
    }

    @Test
    void warningsAndNotesGoToStderr() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        BuildOutput.forceAnsi = false;
        BuildOutput.step("result");
        BuildOutput.warn("careful", "what to do");
        BuildOutput.note("a note");
        BuildOutput.stepWarn("broken");
        assertEquals("    result\n\n\n", out.toString(), "stdout holds the step, and the blank line ending each warning");
        assertEquals("""
                    ⚠ careful
                      what to do
                    a note
                      ⚠ broken
                """, err.toString());
    }

    @Test
    void aHeaderAfterAWarningKeepsOneBlankLineOnStdout() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        BuildOutput.forceAnsi = false;
        BuildOutput.step("before");
        BuildOutput.warn("careful");
        BuildOutput.header("Next");
        assertEquals("    before\n\n  ● Next\n", out.toString(), "stdout alone, as in isx build >out.log");
    }

    @Test
    void elapsedTimeFormatting() {
        assertEquals("2.5s", BuildOutput.formatElapsed(2_500));
        assertEquals("41s", BuildOutput.formatElapsed(41_000));
        assertEquals("2m05s", BuildOutput.formatElapsed(125_000));
    }

    /** The rendered screen: each carriage return / erase-line rewrites the current line. */
    private List<String> lastLines() {
        var screen = new java.util.ArrayList<String>();
        var current = new StringBuilder();
        var plain = text().replace("\033[2K", "");
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (c == '\r') current.setLength(0);
            else if (c == '\n') { screen.add(BuildOutput.stripAnsi(current.toString())); current.setLength(0); }
            else current.append(c);
        }
        return screen;
    }

    @Test
    void guestTextCannotDriveTheTerminal() {
        // What a guest command printed is shown as text (#1588): colour dropped, a progress
        // line as the terminal leaves it after its last carriage return, other controls spaces.
        assertEquals("red\ntab\tend\n100%\ndone\n",
                BuildOutput.guestText("\033[31mred\033[0m\r\n\033]52;c;eA==\007tab\tend\n"
                        + " 10%\r 50%\r100%\r\ndone\n"));
        assertEquals("", BuildOutput.guestText("\u009b2J"));
        assertEquals("cursor keys", BuildOutput.guestText("cursor\033[0 q keys\033[>4;2m"));
        assertEquals("", BuildOutput.guestText("\r\r"));
        assertEquals("0;unterminated", BuildOutput.guestText("\033]0;unterminated"));
        // A terminal still shows the text before a carriage return that only colour follows.
        assertEquals("npm error", BuildOutput.guestText("npm error\r\033[0m"));
        assertEquals("text", BuildOutput.guestText("\u009d0;title\u009ctext\033Pq#0\033\\"));
        // An unterminated control string cannot swallow the log: it shows as text.
        assertEquals("0;open\nnext", BuildOutput.guestText("\033]0;open\nnext"));
        // A terminal ends a control string at the next escape and shows the text after it.
        assertEquals("0;titlevisible error ", BuildOutput.guestText("\033]0;title\033[31mvisible error\007"));
        // tput sgr0 on xterm: a charset designation, then a reset.
        assertEquals("done", BuildOutput.guestText("done\033(B\033[m"));
        assertSame("plain\ttext\n", BuildOutput.guestText("plain\ttext\n"));
    }

    @Test
    void guestTextShowsASequenceAControlInterruptsAsATerminalDoes() {
        // A terminal acts on a C0 control inside a control sequence or escape and carries on with
        // the sequence (#1920): the control is shown as it is outside one, the sequence is dropped
        assertEquals("a b", BuildOutput.guestText("a\033[1\007Db"));
        assertEquals("a\nb", BuildOutput.guestText("a\033[\n31mb"));
        assertEquals("a\nb", BuildOutput.guestText("a\u009b1;\n2Hb"));
        assertEquals("a\nb", BuildOutput.guestText("a\033\n7b"));
        assertEquals("a\tb", BuildOutput.guestText("a\033\t[Kb"));
        // A carriage return inside one starts the line's last frame, as one outside does
        assertEquals("new", BuildOutput.guestText("old\033[\r0mnew"));
        // DEL is ignored there, and CAN, SUB and ESC end the sequence, so what follows is text
        assertEquals("ab", BuildOutput.guestText("a\033[1\u007fmb"));
        assertEquals("a 1Db", BuildOutput.guestText("a\033[\u00181Db"));
        // A control string runs to its terminator across lines, ignoring the controls in it
        assertEquals("ab", BuildOutput.guestText("a\033]0;\n\007b"));
        assertEquals("ab", BuildOutput.guestText("a\033]0;x\r\ny\033\\b"));
        // CAN and SUB abort a control string, as they do a sequence: a later terminator ends nothing
        assertEquals("a0;x\n y \nb", BuildOutput.guestText("a\033]0;x\n\u0018y\007\nb"));
        // A control before a control string's introducer is acted on, as within an escape
        assertEquals("a\nb", BuildOutput.guestText("a\033\n]0;t\n\007b"));
    }

    @Test
    void guestTextTakesLinearTimeOnALineOfIntroducers() {
        // A guest decides what it prints, so one long line must not stall isx: scanned to its
        // end from each introducer, 80k of them took 31 s; 200k would take minutes.
        var introducers = "\u009d".repeat(200_000);
        var mixed = "\u0090x\u0098x\u009dx\u009ex\u009fx\033]x".repeat(50_000);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            assertEquals(" ".repeat(200_000), BuildOutput.guestText(introducers));
            assertEquals(" x x x x xx".repeat(50_000), BuildOutput.guestText(mixed));
            assertEquals("", BuildOutput.guestText("\033[".repeat(100_000)));
            assertEquals("", BuildOutput.guestText("\033[" + "0;".repeat(100_000) + " ".repeat(100_000) + "m"));
            assertEquals(" 0".repeat(100_000), BuildOutput.guestText("\u009b0".repeat(100_000)));
            // Controls within a sequence, and a control string across lines, that never end (#1920)
            assertEquals("\n".repeat(100_000), BuildOutput.guestText("\033[" + "\n".repeat(100_000)));
            assertEquals("\n".repeat(100_000), BuildOutput.guestText("\033[\n".repeat(100_000)));
            assertEquals("\n".repeat(100_000), BuildOutput.guestText("\033]\n".repeat(100_000)));
            assertEquals(" " + "\n".repeat(100_000), BuildOutput.guestText("\033" + "\n".repeat(100_000)));
        });
    }

    /** One line over the cap is shown whole: a marker in its place would save nothing. */
    @Test
    void oneLineOverTheFailedOutputCapIsShownWhole() {
        var lines = new String[BuildOutput.FAILED_OUTPUT_LINES + 1];
        java.util.Arrays.setAll(lines, i -> "line " + i);
        assertEquals(java.util.List.of(lines), java.util.List.of(BuildOutput.lastOf(lines)));
        var more = new String[BuildOutput.FAILED_OUTPUT_LINES + 2];
        java.util.Arrays.setAll(more, i -> "line " + i);
        assertEquals("(2 earlier lines not shown)", BuildOutput.lastOf(more)[0]);
    }

    @Test
    void whatIsShownOfAGuestsTextSaysWhereEachCharacterCameFrom() {
        // A redaction of the raw text is found again in what is shown through it (#1878)
        var raw = "\033[31mred\033[0m 1%\r2%\r\n\033]0;t\007a\u0001b\u009b2Jc\ttab \uD83D\uDE00\n  \r\r";
        var shown = BuildOutput.shownGuestText(raw);
        assertEquals("2%\na bc\ttab \uD83D\uDE00\n", shown.text());
        assertEquals(BuildOutput.guestText(raw), shown.text());
        assertSourcesFit(raw, shown);
    }

    @Test
    void showingAGuestsTextIsWhatItWasBeforeItSaidWhereFrom() {
        // The same text as the one-pass form it replaced, over a seeded corpus of what terminals act on
        var alphabet = new String[] {"a", "b", " ", "\n", "\r", "\t", "\033", "[", "]", "m", "0", ";", "\\",
                "\007", "\u009b", "\u009d", "\u009c", "\u0001", "\u200e", "P", "(", "\uD83D\uDE00", "\u0018", "\u007f", "\b"};
        var random = new java.util.Random(1878);
        for (int n = 0; n < 20_000; n++) {
            var raw = new StringBuilder();
            for (int i = random.nextInt(24); i > 0; i--) raw.append(alphabet[random.nextInt(alphabet.length)]);
            var text = raw.toString();
            var shown = BuildOutput.shownGuestText(text);
            assertEquals(oneShot(text), shown.text(), () -> "for " + text.codePoints().mapToObj(Integer::toHexString).toList());
            assertSourcesFit(text, shown);
        }
    }

    @Test
    void showingManyLinesWithOneCarriageReturnAtTheEndIsLinear() {
        // Each line's frames are looked for within it: a search for the next carriage return past
        // the line rescanned the rest of the text once per line
        var raw = "progress line\n".repeat(200_000) + "done\r";
        var shown = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> BuildOutput.shownGuestText(raw));
        assertEquals(raw.length() - 1, shown.text().length());
    }

    @Test
    void theStreamsAGuestPrintedEndInALineBreakOnceShown() {
        assertArrayEquals(new String[] {"out\n", "err\r\n", "x\n\r"},
                BuildOutput.guestStreams("out", " \n", null, "err\r", "x\n\r"));
        // A stream of escapes alone shows no line, so it adds none, as guestLines (#1882 review)
        assertArrayEquals(new String[] {"out\n"}, BuildOutput.guestStreams("\033[0m", "out"));
        for (var streams : List.of(new String[] {"\033[0m", "a"}, new String[] {"  \033[0m", "\033[0m\n"}, new String[] {"x\n\r", "\r\r"})) {
            var shown = new StringBuilder();
            for (var stream : BuildOutput.guestStreams(streams)) shown.append(BuildOutput.guestText(stream));
            var lines = BuildOutput.guestLines(streams);
            assertEquals(lines.length == 0 ? "" : String.join("\n", lines) + "\n", shown.toString(), List.of(streams).toString());
        }
    }

    /** Each shown character is the raw one it came from, or a space for a control, in order. */
    private static void assertSourcesFit(String raw, BuildOutput.Shown shown) {
        assertEquals(shown.text().length(), shown.source().length);
        for (int i = 0; i < shown.source().length; i++) {
            int from = shown.source()[i];
            assertTrue(i == 0 || from > shown.source()[i - 1], "ascending at " + i);
            char c = shown.text().charAt(i);
            assertTrue(c == raw.charAt(from) || c == ' ', "at " + i);
        }
    }

    private static final String WITHIN = "\u0000-\u0017\u0019\u001C-\u001F\u007F";
    private static final java.util.regex.Pattern GUEST_CONTROL = java.util.regex.Pattern.compile(
            "(?:\u001B[" + WITHIN + "]*[\\]PX^_]|[\u0090\u0098\u009D\u009E\u009F])"
                    + "[^\u0007\u0018\u001A\u001B\u009C\u0090\u0098\u009D\u009E\u009F]*(?:\u0007|\u001B\\\\|\u009C)"
                    + "|(?:\u001B[" + WITHIN + "]*\\[|\u009B)[0-?" + WITHIN + "]*+[ -/" + WITHIN + "]*+[@-~]"
                    + "|\u001B[ -/" + WITHIN + "]*[0-~]");

    /** guestText as it was written before it kept sources (#1588), with the controls within a sequence (#1920). */
    private static String oneShot(String text) {
        if (text.codePoints().noneMatch(TerminalLink::actsOnTerminal)) return text;
        text = GUEST_CONTROL.matcher(text).replaceAll(match -> {
            // A control string's payload ignores the C0 controls in it; a sequence acts on them
            var control = match.group();
            boolean string = control.matches("(?s)(?:\u001B[" + WITHIN + "]*[\\]PX^_]|[\u0090\u0098\u009D-\u009F]).+");
            var acted = new StringBuilder();
            for (char c : control.toCharArray()) {
                if (string && (c == ']' || c == 'P' || c == 'X' || c == '^' || c == '_' || c >= 0x90)) break;
                if (c < 0x20 && c != 0x18 && c != 0x1A && c != 0x1B) acted.append(c);
            }
            return java.util.regex.Matcher.quoteReplacement(acted.toString());
        });
        if (text.indexOf('\r') >= 0) {
            text = java.util.Arrays.stream(text.split("\n", -1)).map(line -> {
                var frames = line.split("\r");
                for (int i = frames.length - 1; i >= 0; i--) {
                    if (!frames[i].isBlank()) return frames[i];
                }
                return "";
            }).collect(java.util.stream.Collectors.joining("\n"));
        }
        return TerminalLink.plainText(text);
    }
}
