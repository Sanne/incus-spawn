package dev.incusspawn.util;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * How isx reports: every message goes through these static methods to the bound {@link Output}
 * (#1703), a {@link TerminalOutput} unless the owner of the terminal bound another
 * ({@link #bind}). Code that reports never chooses where its lines go, so a warning raised while
 * the TUI draws reaches its log instead of being drawn over.
 *
 * <p>The rest are the helpers every output shares: the colour gate ({@link #ansi},
 * {@link #styled}), and making text that isx did not write safe to show ({@link #guestText}).
 */
public final class BuildOutput {

    private BuildOutput() {}

    public static final String BOLD = "\033[1m";
    public static final String DIM  = "\033[2m";
    public static final String RED = "\033[31m";
    public static final String GREEN = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String CYAN = "\033[36m";
    public static final String RESET = "\033[0m";

    /** Green check that marks a finished step. */
    public static final String CHECK = GREEN + "✓" + RESET;
    /** Red cross that marks a failed step. */
    public static final String CROSS = RED + "✗" + RESET;

    private static final java.util.regex.Pattern ANSI_PATTERN =
            java.util.regex.Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");
    /**
     * What a terminal acts on within one line, dropped whole from guest output, left to right:
     * <ul>
     *   <li>a control string ended by BEL or ST, in its 7-bit or C1 form: an operating system
     *       command (a title, a link, a clipboard write), DCS, SOS, PM or APC.
     *       Its payload stops at every introducer, so a line of them is matched in linear time,
     *       not scanned to its end from each one;</li>
     *   <li>a control sequence, 7-bit or C1, with its parameters, intermediates and final byte
     *       ({@code \e[0 q}, {@code \e[>4;2m}), not only colour;</li>
     *   <li>any other escape ({@code \e(B}, {@code \e7}), including an introducer whose control
     *       string a later escape cut short, as a terminal does.</li>
     * </ul>
     */
    private static final java.util.regex.Pattern GUEST_CONTROL_PATTERN = java.util.regex.Pattern.compile(
            "(?:\u001B[\\]PX^_]|[\u0090\u0098\u009D\u009E\u009F])"
                    + "[^\u0007\u001B\u009C\u0090\u0098\u009D\u009E\u009F\n]*(?:\u0007|\u001B\\\\|\u009C)"
                    + "|(?:\u001B\\[|\u009B)[0-?]*[ -/]*[@-~]"
                    + "|\u001B[ -/]*[0-~]");

    /**
     * Whether to write escapes (colour, {@link TerminalLink} hyperlinks): on an ANSI terminal
     * without {@code NO_COLOR}, or as a test forces. The one gate for both.
     */
    public static boolean ansi() {
        return forceAnsi != null ? forceAnsi : TerminalProgress.isAnsiTerminal();
    }

    /**
     * {@code text} in {@code style} (one or more of the constants above) on an ANSI terminal,
     * unchanged otherwise. Every escape written outside a live step goes through here.
     */
    public static String styled(String style, String text) {
        return ansi() ? style + text + RESET : text;
    }

    /** Strip ANSI escape sequences (colour, cursor, erase) from a string. */
    public static String stripAnsi(String s) {
        return ANSI_PATTERN.matcher(s).replaceAll("");
    }

    /**
     * {@code text} a guest command printed, as text that cannot drive the terminal it is shown on
     * (#1588): a repository's prime or a package's install script decides what it holds. Complete
     * control strings, control sequences and escapes are dropped, each line keeps the last text a
     * carriage return left on it (a progress bar's final frame), and every other control becomes a space
     * ({@link TerminalLink#plainText}).
     */
    public static String guestText(String text) {
        if (showsAsPrinted(text)) return text;
        return shownGuestText(text).text();
    }

    /**
     * {@link #guestText} of a guest's {@code raw} text, with where each of its characters came from:
     * {@code source[i]} is the index in {@code raw} of the character {@code text} shows at {@code i},
     * ascending, as guestText only drops characters and turns a control into a space. A redaction
     * of the raw text is found again in what is shown through it (#1878).
     */
    public record Shown(String text, int[] source) {}

    /**
     * The control strings, sequences and escapes {@link #guestText} drops whole from {@code raw},
     * for a reader that must know what each did to the cursor (#1900).
     */
    public static java.util.regex.Matcher guestControls(CharSequence raw) {
        return GUEST_CONTROL_PATTERN.matcher(raw);
    }

    /** Whether {@link #guestText} spaces {@code c}: a control other than a line feed or a tab. */
    public static boolean actsOnTerminal(int c) {
        return TerminalLink.actsOnTerminal(c);
    }

    /** Whether {@link #guestText} shows {@code text} as printed: it holds nothing a terminal acts on. */
    public static boolean showsAsPrinted(String text) {
        return text.codePoints().noneMatch(TerminalLink::actsOnTerminal);
    }

    /** {@link #guestText} of {@code raw}, with the source of each character it keeps ({@link Shown}). */
    public static Shown shownGuestText(String raw) {
        if (showsAsPrinted(raw)) {
            return new Shown(raw, java.util.stream.IntStream.range(0, raw.length()).toArray());
        }
        // The control strings, sequences and escapes, dropped whole
        var kept = new StringBuilder(raw.length());
        var from = new int[raw.length()];
        var matcher = GUEST_CONTROL_PATTERN.matcher(raw);
        int pos = 0;
        while (true) {
            int end = matcher.find() ? matcher.start() : raw.length();
            for (int i = pos; i < end; i++) {
                from[kept.length()] = i;
                kept.append(raw.charAt(i));
            }
            if (end == raw.length()) break;
            pos = matcher.end();
        }
        // Each line's last frame with text in it, when a carriage return drew over it
        var framed = new StringBuilder(kept.length());
        var framedFrom = new int[kept.length()];
        if (kept.indexOf("\r") >= 0) {
            for (int line = 0; line <= kept.length(); ) {
                int lineEnd = kept.indexOf("\n", line);
                if (lineEnd < 0) lineEnd = kept.length();
                int frameStart = line, frameEnd = line;
                for (int f = line; f <= lineEnd; ) {
                    // Bounded by the line: a search past it would rescan the rest of the text per line
                    int fEnd = f;
                    while (fEnd < lineEnd && kept.charAt(fEnd) != '\r') fEnd++;
                    if (!kept.substring(f, fEnd).isBlank()) {
                        frameStart = f;
                        frameEnd = fEnd;
                    }
                    f = fEnd + 1;
                }
                for (int i = frameStart; i < frameEnd; i++) {
                    framedFrom[framed.length()] = from[i];
                    framed.append(kept.charAt(i));
                }
                if (lineEnd == kept.length()) break;
                framedFrom[framed.length()] = from[lineEnd];
                framed.append('\n');
                line = lineEnd + 1;
            }
        } else {
            framed = kept;
            framedFrom = from;
        }
        // Every other control, a space ({@link TerminalLink#plainText})
        var shown = new StringBuilder(framed.length());
        var source = new int[framed.length()];
        for (int i = 0; i < framed.length(); ) {
            int c = framed.codePointAt(i);
            int width = Character.charCount(c);
            if (TerminalLink.actsOnTerminal(c)) {
                source[shown.length()] = framedFrom[i];
                shown.append(' ');
            } else {
                for (int j = i; j < i + width; j++) {
                    source[shown.length()] = framedFrom[j];
                    shown.append(framed.charAt(j));
                }
            }
            i += width;
        }
        return new Shown(shown.toString(), Arrays.copyOf(source, shown.length()));
    }

    /**
     * The lines of what a guest command printed, each stream in turn, as {@link #guestText}: the
     * details of a warning that shows why the command failed. Blank streams add nothing.
     */
    public static String[] guestLines(String... texts) {
        return Arrays.stream(texts)
                .filter(t -> t != null && !t.isBlank())
                .flatMap(t -> guestText(t).lines())
                .toArray(String[]::new);
    }

    /**
     * The streams {@link #guestLines} shows, raw, for a scrub that must see what showing them
     * hides (#1878): each that shows a line, ending in a line break once shown. One of escapes
     * alone shows nothing, so it adds no line, as in guestLines.
     */
    public static String[] guestStreams(String... texts) {
        var streams = new java.util.ArrayList<String>(texts.length);
        for (var t : texts) {
            if (t == null || t.isBlank()) continue;
            var shown = guestText(t);
            if (shown.isEmpty()) continue;
            streams.add(shown.endsWith("\n") ? t : t + "\n");
        }
        return streams.toArray(String[]::new);
    }

    /** How many lines of a failed command's output a warning shows: the end, where it says why. */
    public static final int FAILED_OUTPUT_LINES = 40;

    /**
     * The last {@link #FAILED_OUTPUT_LINES} of {@code lines}, after a line saying how many came
     * before: a verbose npm log runs to thousands, which a warning in the TUI's log must not hold.
     * One line over is shown whole, since the marker would take its place.
     */
    public static String[] lastOf(String[] lines) {
        // One line more is shown whole: the marker would take its place and save nothing
        if (lines.length <= FAILED_OUTPUT_LINES + 1) return lines;
        var kept = new String[FAILED_OUTPUT_LINES + 1];
        kept[0] = "(" + (lines.length - FAILED_OUTPUT_LINES) + " earlier lines not shown)";
        System.arraycopy(lines, lines.length - FAILED_OUTPUT_LINES, kept, 1, FAILED_OUTPUT_LINES);
        return kept;
    }

    public static final String STEP_INDENT = "    ";

    private static final Output TERMINAL = new TerminalOutput();
    private static volatile Output bound = TERMINAL;

    /** The process's terminal output, which is bound unless something else is. */
    public static Output terminal() {
        return TERMINAL;
    }

    /**
     * Whatever is bound when each message is reported: what a CLI command hands shared code
     * that takes an {@link Output}, so whoever owns the terminal decides, as for these methods.
     */
    public static Output bound() {
        return BoundOutput.INSTANCE;
    }

    /** Where messages go now. */
    public static Output current() {
        return bound;
    }

    /** The handle of {@link #bind}: closing it puts the previous output back. */
    @FunctionalInterface
    public interface Binding extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Send every message to {@code output} until the returned handle is closed. The owner of
     * the terminal binds: the TUI while it draws and while it lends the terminal out, the pre-TUI
     * window, {@code isx mcp}, the proxy. Nothing else does.
     */
    public static Binding bind(Output output) {
        var previous = bound;
        bound = output;
        return () -> {
            // Bindings nest: closing an outer one first would put back an output already gone.
            if (bound != output) throw new IllegalStateException("output bindings closed out of order");
            bound = previous;
        };
    }

    /** The indent for a step line at the current nesting: inside a {@link #group}, one level deeper. */
    public static String indent() {
        return bound.indent();
    }

    /**
     * Print a section title at the left margin, preceded by a blank line for visual
     * separation. Use this to introduce a block of work (e.g. "Refreshing 8 host repos:")
     * that isn't a bold-bullet {@link #header} for a single named operation.
     */
    public static void section(String msg) {
        bound.section(msg);
    }

    /**
     * Open a titled group of steps: {@code ▸ Tools  maven-3, mx}, bold with a dim detail,
     * after a blank line. Steps printed until the group is closed are indented one level
     * under it, and closing it leaves a blank line, so the next top-level step reads as
     * outside the group. Use with try-with-resources.
     */
    public static Output.Group group(String title, String detail) {
        return bound.group(title, detail);
    }

    /** Print a complete step line. */
    public static void step(String msg) {
        bound.step(msg);
    }

    /** Print a dim note about the step above, one level under it (e.g. the version it verified). */
    public static void stepNote(String msg) {
        bound.stepNote(msg);
    }

    /**
     * Print a yellow warning about the step above, one level under it, then a blank line
     * separating it from the regular flow. Like every warning, to stderr.
     */
    public static void stepWarn(String msg) {
        bound.stepWarn(msg);
    }

    /** Print a finished step: {@code ✓ msg}. For a result known at once, without a live step. */
    public static void ok(String msg) {
        bound.ok(msg);
    }

    /**
     * Print {@code items} comma-separated at the current indent, wrapping at the terminal width
     * with the same indent. Pair it with a {@link #group} header to list what the group covers.
     */
    public static void list(Collection<String> items) {
        bound.list(items);
    }

    /**
     * Where the command's answer goes ({@link Output#results()}): stdout on a terminal, nowhere
     * while the TUI draws. Query commands and status reports print here, and hand it to
     * {@link OutputFormat} for scripts.
     */
    public static java.io.PrintStream results() {
        return bound.results();
    }

    /** A block laid out by the caller, drawn as given ({@link Output#frame}): {@code isx init}'s boxes. */
    public static void frame(String... lines) {
        bound.frame(lines);
    }

    /** A question at the step indent, its answer typed after it ({@link Output#prompt}). */
    public static void prompt(String question) {
        bound.prompt(question);
    }

    /** Set the terminal window's title ({@link Output#windowTitle}): only on a terminal. */
    public static void windowTitle(String title) {
        bound.windowTitle(title);
    }

    /** Tests set this to exercise the animated rendering without a console; null means detect. */
    static volatile Boolean forceAnsi;

    /**
     * Start a slow step. On an ANSI terminal the line animates until {@link #stepDone} (or
     * {@link #stepBreak} on failure); otherwise it is printed {@code "label..."} without a newline
     * for {@code stepDone} to complete. {@code msg} is a bare label: the terminal adds the ellipsis
     * or the spinner.
     */
    public static void stepStart(String msg) {
        bound.stepStart(msg);
    }

    /**
     * Show what a live step is doing right now, dim after its label (e.g. {@code verifying}).
     * Ignored without a terminal or when no step is live.
     */
    public static void stepProgress(String detail) {
        bound.stepProgress(() -> detail);
    }

    /**
     * Show what a live step is doing, read again on every frame ({@link Output#stepProgress}): for
     * a value that keeps changing, such as a download's rate.
     */
    public static void stepProgress(java.util.function.Supplier<String> detail) {
        bound.stepProgress(detail);
    }

    /**
     * {@code label} as a live step around {@code work}: a ✓ once it returned true, a ✗ when it
     * returned false or threw. What the work reports meanwhile freezes the step's line and is shown
     * below it; the work must not start a step of its own, which would end this one.
     */
    public static boolean liveStep(String label, java.util.function.BooleanSupplier work) {
        stepStart(label);
        boolean ok = false;
        try {
            ok = work.getAsBoolean();
        } finally {
            if (ok) stepDone();
            else abandonStep();
        }
        return ok;
    }

    /** Complete the step started by {@link #stepStart}. */
    public static void stepDone() {
        bound.stepDone(null);
    }

    /**
     * Complete the step started by {@link #stepStart}, reporting a result value
     * inline instead of on a second line — e.g. {@code ✓ Extracting root disk (4.0G)}.
     */
    public static void stepDone(String detail) {
        bound.stepDone(detail);
    }

    /**
     * End the step started by {@link #stepStart} as failed, before reporting the error: the
     * animated line turns into {@code ✗ label}, a plain one is terminated.
     */
    public static void stepBreak() {
        bound.stepBreak();
    }

    /** The last {@code n} lines of a command's output, without surrounding blank space. */
    public static String lastLines(String text, int n) {
        var lines = text.strip().split("\n");
        return String.join("\n", Arrays.asList(lines).subList(Math.max(0, lines.length - n), lines.length));
    }

    /**
     * Fail a slow step started by {@link #stepStart}: its line ends in {@code ✗ label}, and
     * {@code msg} is a warning under it, on stderr ({@link #stepBreak} then {@link #stepWarn}).
     */
    public static void stepFail(String msg) {
        bound.stepFail(msg);
    }

    /**
     * Stop drawing a live step before a child process writes to the terminal directly
     * (inherited IO, or a {@code sudo} password prompt on the tty), which the stream guard
     * cannot see. The step keeps running: its {@link #stepDone} prints the ✓ on a new line.
     */
    public static void releaseTerminal() {
        bound.releaseTerminal();
    }

    /**
     * End a step that is still live as failed, if there is one: a command that stops on an
     * exception thrown mid-step calls this before reporting it, so the step shows ✗ and the
     * streams are no longer guarded. A no-op when no step is live.
     */
    public static void abandonStep() {
        bound.abandonStep();
    }

    public static String formatElapsed(long ms) {
        if (ms < 10_000) return String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0);
        long s = Math.round(ms / 1000.0);
        if (s < 60) return s + "s";
        return (s / 60) + "m" + String.format(java.util.Locale.ROOT, "%02d", s % 60) + "s";
    }

    /** A stream that passes everything on to {@code target}, running {@code beforeWrite} first. */
    public static PrintStream onWrite(PrintStream target, Runnable beforeWrite) {
        var guarded = new OutputStream() {
            @Override public void write(int b) {
                beforeWrite.run();
                target.write(b);
            }
            @Override public void write(byte[] b, int off, int len) {
                if (len == 0) return;
                beforeWrite.run();
                target.write(b, off, len);
            }
            @Override public void flush() {
                target.flush();
            }
        };
        return new PrintStream(guarded, true, target.charset());
    }

    /**
     * Print a bold bullet header for a top-level operation: {@code  ● Resizing VM data disk}.
     * Preceded by a blank line. Use this to frame any multi-step command; the
     * build/branch-specific {@link #buildHeader}/{@link #branchHeader} build on the same style.
     */
    public static void header(String msg) {
        bound.header(msg, null);
    }

    /**
     * Header with a dim secondary detail: {@code  ● Resizing VM data disk  60.0G → 120.0G}.
     * The detail is rendered dim, mirroring {@link #branchHeader}'s source styling, so every
     * header's secondary part reads the same.
     */
    public static void header(String msg, String detail) {
        bound.header(msg, detail);
    }

    /**
     * Print a bold bullet header: {@code  ● Building tpl-dev  [1/3]}. The name is a template's,
     * which a cloned repository's {@code .incus-spawn/} can choose, so it is shown as one line
     * ({@link OutputFormat#oneLine}, #1228).
     */
    public static void buildHeader(String name, int index, int total) {
        bound.buildHeader(name, index, total);
    }

    /** Print a bold bullet header for a branch: {@code  ● my-instance  ← tpl-dev} */
    public static void branchHeader(String name, String source) {
        bound.branchHeader(name, source);
    }

    /**
     * Print a yellow warning line and any dim {@code details} under it (e.g. what to do about
     * it), then a blank line separating them from the regular flow. Blank messages are skipped.
     * To stderr, so stdout holds only what a command reports. A message that marks itself
     * ({@code "Warning: ..."}, for a sink that may print it as it comes) loses that marker for
     * the ⚠, as the TUI's log drops it.
     */
    public static void warn(String msg, String... details) {
        bound.warn(msg, details);
    }

    /**
     * A warning beside the command's flow: on stderr alone on a terminal, so a query's stdout
     * holds only its results.
     */
    public static void warnAside(String msg) {
        bound.warnAside(msg);
    }

    /** Print why the command failed, in red on stderr, and any lines of advice under it. */
    public static void error(String msg, String... details) {
        bound.error(msg, details);
    }

    private static final java.util.regex.Pattern WARNING_MARKER = java.util.regex.Pattern.compile("^Warning:\\s*");

    /**
     * A warning's text without its own {@code "Warning:"} marker (a sink that prints as it comes may
     * mark its own, and a ⚠ or the TUI's log says so already); null when it is blank.
     */
    static String warningText(String msg) {
        if (msg == null || msg.isBlank()) return null;
        return WARNING_MARKER.matcher(msg.strip()).replaceFirst("");
    }

    /**
     * {@code text} with each line made safe ({@link OutputFormat#oneLine}): a warning keeps the
     * line breaks of the advice isx writes into it, but what it quotes cannot drive the terminal.
     */
    static String safeLines(String text) {
        return Arrays.stream(String.valueOf(text).split("\n", -1)).map(OutputFormat::oneLine).collect(Collectors.joining("\n"));
    }

    /**
     * Print an indented dim note (informational, not a warning) to stderr, beside the warnings.
     * Blank messages are skipped. A note is one line (advice over several lines is several
     * notes), and it quotes template text (a repository's path and URL, a host-resource source),
     * so it is printed through {@link OutputFormat#oneLine}: nothing in it can start a line of its
     * own or drive the terminal (#1235).
     */
    public static void note(String msg) {
        bound.note(msg);
    }

    /**
     * The line that closes a block: a green ✓ and the bold message at the header's column, after
     * a blank line, worded like any ✓ line ({@code "tpl-dev built"}, no period).
     */
    public static void success(String msg) {
        bound.success(msg);
    }

    /**
     * Print a yellow-bordered warning banner to stderr with a bold title and optional body lines.
     * Use for diagnostic warnings that need to stand out (subnet conflicts, CA mismatches, etc.).
     */
    public static void warnBanner(String title, String... lines) {
        bound.warnBanner(title, lines);
    }
}
