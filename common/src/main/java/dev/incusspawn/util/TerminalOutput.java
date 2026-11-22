package dev.incusspawn.util;

import java.io.PrintStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static dev.incusspawn.util.BuildOutput.BOLD;
import static dev.incusspawn.util.BuildOutput.CHECK;
import static dev.incusspawn.util.BuildOutput.CROSS;
import static dev.incusspawn.util.BuildOutput.CYAN;
import static dev.incusspawn.util.BuildOutput.DIM;
import static dev.incusspawn.util.BuildOutput.GREEN;
import static dev.incusspawn.util.BuildOutput.RED;
import static dev.incusspawn.util.BuildOutput.RESET;
import static dev.incusspawn.util.BuildOutput.STEP_INDENT;
import static dev.incusspawn.util.BuildOutput.YELLOW;
import static dev.incusspawn.util.BuildOutput.safeLines;
import static dev.incusspawn.util.BuildOutput.styled;

/**
 * The {@link Output} that prints to a terminal, or to whatever stdout and stderr are.
 *
 * <p>Step output sits under a header at {@link #indent()}: {@link BuildOutput#STEP_INDENT} (4
 * spaces), plus two more inside a {@link #group}. Slow operations use {@link #stepStart} /
 * {@link #stepDone}: on an ANSI terminal the step line animates a spinner until it is done
 * and then turns into a green ✓ (with the elapsed time once that is worth reading), so a
 * step that is still busy never looks finished. Without a terminal the line is printed
 * {@code "label... done."}, as a log would want it. A label is bare in source: the ellipsis and
 * the glyphs are this class's. {@link #step} prints a complete line.
 *
 * <p>While a step is live, anything else written to {@code System.out} or {@code System.err}
 * first freezes the step line and moves below it, so a warning or error printed mid-step is
 * never drawn over. Code that hands the terminal to a child process (inherited IO, or a
 * {@code sudo} that may prompt on the tty) calls {@link #releaseTerminal} first, since that
 * output bypasses the Java streams.
 *
 * <p>Colour is only written to an ANSI terminal without {@code NO_COLOR}
 * ({@link BuildOutput#ansi}), and warnings and notes go to stderr, so piped output holds no
 * escapes and stdout holds no diagnostics. The streams are read as each line is printed, so a
 * stream replaced meanwhile (a live step's guard, the pre-TUI watch) is the one written to.
 */
public final class TerminalOutput implements Output {

    private static final String GROUP_INDENT = "  ";
    /** The column a block opens at with its header, and closes at with its success line. */
    private static final String HEADER_INDENT = "  ";

    /** Steps longer than this show their elapsed time on the ✓ line. */
    private static final long SHOW_ELAPSED_MS = 2000;

    private final Supplier<PrintStream> out;
    private final Supplier<PrintStream> err;

    private volatile int depth;
    /** Set when a closing group printed a blank line, so the next block does not add a second. */
    private volatile boolean blankLineEnded;

    /** Results on stdout, diagnostics on stderr: the one {@link BuildOutput#terminal()}, since a live step guards the process's streams. */
    TerminalOutput() {
        this(() -> System.out, () -> System.err);
    }

    private TerminalOutput(Supplier<PrintStream> out, Supplier<PrintStream> err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Results on {@code out}, diagnostics on {@code err}: what a terminal receives, for a test that
     * reads it without swapping the process's streams. A live step still guards those streams.
     */
    public static TerminalOutput to(PrintStream out, PrintStream err) {
        return new TerminalOutput(() -> out, () -> err);
    }

    /** Everything on stderr: for {@code isx mcp}, whose stdout is the protocol. */
    public static TerminalOutput stderrOnly() {
        return new TerminalOutput(() -> System.err, () -> System.err);
    }

    @Override
    public String indent() {
        return STEP_INDENT + GROUP_INDENT.repeat(depth);
    }

    /** Separate a new block with a blank line, unless a closing group just printed one. */
    private void blankLine() {
        if (!blankLineEnded) out.get().println();
        blankLineEnded = false;
    }

    @Override
    public void section(String msg) {
        blankLine();
        out.get().println(msg);
    }

    @Override
    public Group group(String title, String detail) {
        var sb = new StringBuilder(indent()).append(styled(CYAN, "▸ ")).append(styled(BOLD, title));
        if (detail != null && !detail.isEmpty()) sb.append("  ").append(styled(DIM, detail));
        blankLine();
        out.get().println(sb);
        int previousDepth = depth;
        depth = previousDepth + 1;
        var closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            depth = previousDepth;
            if (!blankLineEnded) out.get().println();
            blankLineEnded = true;
        };
    }

    @Override
    public void step(String msg) {
        blankLineEnded = false;
        out.get().println(indent() + msg);
    }

    @Override
    public void stepNote(String msg) {
        if (msg == null || msg.isBlank()) return;
        blankLineEnded = false;
        out.get().println(indent() + GROUP_INDENT + styled(DIM, safeLines(msg)));
    }

    @Override
    public void stepWarn(String msg) {
        if (msg == null || msg.isBlank()) return;
        // Each line under the step: a failed step's reason can carry its output's last lines
        var lines = safeLines(msg).split("\n", -1);
        var at = indent() + GROUP_INDENT;
        err.get().println(at + styled(YELLOW, "⚠ " + lines[0]));
        for (int i = 1; i < lines.length; i++) err.get().println(at + "  " + styled(YELLOW, lines[i]));
        endWithBlankLine();
    }

    @Override
    public void ok(String msg) {
        blankLineEnded = false;
        out.get().println(indent() + styled(GREEN, "✓") + " " + msg);
    }

    /**
     * As given, its leading blank line included, also after a block that ended on a blank line.
     * A trailing blank line ends the block, as a group's does.
     */
    @Override
    public void frame(String... lines) {
        if (lines.length == 0) return;
        var stdout = out.get();
        for (var line : lines) stdout.println(line);
        blankLineEnded = lines[lines.length - 1].isEmpty();
    }

    @Override
    public void gap() {
        blankLine();
    }

    @Override
    public void prompt(String question) {
        blankLineEnded = false;
        var stdout = out.get();
        stdout.print(indent() + question);
        stdout.flush();
    }

    @Override
    public void windowTitle(String title) {
        if (!onATerminal()) return;
        var stdout = out.get();
        stdout.print("\033]0;" + OutputFormat.oneLine(title) + "\007");
        stdout.flush();
    }

    /**
     * Whether stdin and stdout are a terminal, or as a test forces escapes: a title is no colour,
     * so {@code NO_COLOR} keeps it. It asks {@link TerminalStreams#terminal}, as
     * {@code IncusClient.hasTerminal()} does before a shell, so a shell's title is written wherever the shell runs.
     */
    private static boolean onATerminal() {
        if (BuildOutput.forceAnsi != null) return BuildOutput.forceAnsi;
        return !Headless.active() && TerminalStreams.terminal() != null;
    }

    /** Its stdout, as it is now: a live step's guard included, so a result first freezes the step's line. */
    @Override
    public PrintStream results() {
        return out.get();
    }

    @Override
    public void list(Collection<String> items) {
        var lineIndent = indent();
        int width = TerminalProgress.terminalWidth();
        var sb = new StringBuilder(lineIndent);
        int col = lineIndent.length();
        Iterator<String> it = items.iterator();
        while (it.hasNext()) {
            var item = it.next();
            var suffix = it.hasNext() ? ", " : "";
            var chunk = item + suffix;
            if (col + chunk.length() > width && col > lineIndent.length()) {
                out.get().println(sb);
                sb.setLength(0);
                sb.append(lineIndent);
                col = lineIndent.length();
            }
            sb.append(chunk);
            col += chunk.length();
        }
        if (col > lineIndent.length()) {
            out.get().println(sb);
        }
    }

    // ---- Live steps ----

    private final Object lock = new Object();
    /** The step between {@link #stepStart} and its end; guarded by {@link #lock}. */
    private LiveStep live;
    private ScheduledExecutorService ticker;

    private static final class LiveStep {
        final String label;
        final String indent;
        final long startNanos = System.nanoTime();
        final boolean ansi;
        final PrintStream raw;
        final PrintStream previousOut;
        final PrintStream previousErr;
        PrintStream guardedOut;
        PrintStream guardedErr;
        ScheduledFuture<?> animation;
        volatile Supplier<String> detail;
        /** Terminal width, measured by the spinner thread on its first frame; -1 until then. */
        int width = -1;
        /** Whether the terminal cursor still sits on this step's line. */
        boolean onLine = true;
        int frame;

        LiveStep(String msg, String indent, boolean ansi, PrintStream raw) {
            this.label = msg;
            this.indent = indent;
            this.ansi = ansi;
            this.raw = raw;
            this.previousOut = System.out;
            this.previousErr = System.err;
        }

        /** The line a plain terminal prints while the step runs. */
        String plainLine() {
            return indent + label + "...";
        }

        String runningLine(int width, String d) {
            var sb = new StringBuilder(indent)
                    .append(TerminalProgress.SPINNER[frame % TerminalProgress.SPINNER.length])
                    .append(' ').append(label);
            if (d != null && !d.isEmpty()) sb.append("  ").append(DIM).append(d).append(RESET);
            return TerminalProgress.truncateToWidth(sb.toString(), width);
        }

        long elapsedMillis() {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }
    }

    /** Re-measured once per step (on the spinner thread, never the caller's), so a terminal
     *  narrowed between steps does not make the spinner line wrap and leave stale rows. */
    private static int width(LiveStep step) {
        if (BuildOutput.forceAnsi != null) return 200;
        if (step.width < 0) step.width = TerminalProgress.terminalWidth();
        return step.width;
    }

    @Override
    public void stepStart(String msg) {
        msg = safeLines(msg);
        synchronized (lock) {
            if (live != null) endLocked(live, null);
            blankLineEnded = false;
            var ansi = BuildOutput.ansi();
            var step = new LiveStep(msg, indent(), ansi, out.get());
            if (!ansi) {
                step.raw.print(step.plainLine());
                step.raw.flush();
            }
            // An animated line is first drawn by the spinner thread: finding the terminal width
            // runs `stty`, which must not delay a branch's own steps.
            step.guardedOut = guard(step.previousOut);
            step.guardedErr = guard(step.previousErr);
            System.setOut(step.guardedOut);
            System.setErr(step.guardedErr);
            if (ansi) {
                step.animation = scheduler().scheduleAtFixedRate(() -> redraw(step), 0, 80,
                        TimeUnit.MILLISECONDS);
            }
            live = step;
        }
    }

    @Override
    public void stepProgress(Supplier<String> detail) {
        synchronized (lock) {
            if (live != null) live.detail = detail;
        }
    }

    @Override
    public void stepDone(String detail) {
        finish(detail, true);
    }

    @Override
    public void stepBreak() {
        finish(null, false);
    }

    @Override
    public void releaseTerminal() {
        synchronized (lock) {
            if (live != null) freezeLocked(live);
        }
    }

    @Override
    public void abandonStep() {
        synchronized (lock) {
            if (live != null) endLocked(live, null);
        }
    }

    private void finish(String detail, boolean success) {
        synchronized (lock) {
            var step = live;
            if (step == null) {
                // No live step (its start was never printed): still close the line the caller expects.
                if (success) out.get().println(detail == null ? " done." : " done (" + detail + ").");
                else out.get().println();
                return;
            }
            endLocked(step, success ? new Outcome(detail) : null);
        }
    }

    private record Outcome(String detail) {}

    /** Stop animating {@code step}, restore the streams and print its final line;
     *  {@code outcome} null means failed (or superseded by another step). */
    private void endLocked(LiveStep step, Outcome outcome) {
        if (step.animation != null) step.animation.cancel(false);
        if (System.out == step.guardedOut) System.setOut(step.previousOut);
        if (System.err == step.guardedErr) System.setErr(step.previousErr);
        live = null;
        var raw = step.raw;
        if (step.ansi) {
            String text;
            if (outcome != null) {
                var sb = new StringBuilder(step.indent).append(CHECK).append(' ').append(step.label);
                if (outcome.detail() != null) sb.append(' ').append(DIM).append('(').append(outcome.detail()).append(')').append(RESET);
                var ms = step.elapsedMillis();
                if (ms >= SHOW_ELAPSED_MS) sb.append("  ").append(DIM).append(BuildOutput.formatElapsed(ms)).append(RESET);
                text = sb.toString();
            } else {
                text = step.indent + CROSS + " " + step.label;
            }
            raw.print((step.onLine ? "\r\033[2K" : "") + text + "\n");
        } else if (outcome != null) {
            // The elapsed time too, as the ✓ line has it: a log is where a slow step is looked for
            var ms = step.elapsedMillis();
            var done = " done" + (outcome.detail() == null ? "" : " (" + outcome.detail() + ")")
                    + (ms >= SHOW_ELAPSED_MS ? " in " + BuildOutput.formatElapsed(ms) : "") + ".";
            raw.println(step.onLine ? done : step.plainLine() + done);
        } else if (step.onLine) {
            raw.println();
        }
        raw.flush();
    }

    /** Leave the step's line as it is and move below it, so other output can be written. */
    private static void freezeLocked(LiveStep step) {
        if (!step.onLine) return;
        step.onLine = false;
        if (step.animation != null) step.animation.cancel(false);
        if (step.ansi) {
            step.raw.print("\r\033[2K" + step.indent + DIM + "… " + step.label + RESET + "\n");
        } else {
            step.raw.println();
        }
        step.raw.flush();
    }

    private void redraw(LiveStep step) {
        int width = width(step); // outside the lock: measuring runs `stty`
        var text = detail(step); // outside the lock too: the caller's code
        synchronized (lock) {
            if (live != step || !step.onLine) return;
            step.raw.print("\r\033[2K" + step.runningLine(width, text));
            step.raw.flush();
            step.frame++;
        }
    }

    /**
     * The live step's detail as it reads now, as one safe line. A supplier that throws shows none
     * this frame: an exception would end the spinner's schedule for good, and the step would look hung.
     */
    private static String detail(LiveStep step) {
        var detail = step.detail;
        if (detail == null) return null;
        try {
            var text = detail.get();
            return text == null ? null : OutputFormat.oneLine(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ScheduledExecutorService scheduler() {
        if (ticker == null) {
            ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "step-spinner");
                t.setDaemon(true);
                return t;
            });
        }
        return ticker;
    }

    /** A stream that freezes the live step line before letting anything else reach the terminal. */
    private PrintStream guard(PrintStream target) {
        return BuildOutput.onWrite(target, this::releaseTerminal);
    }

    @Override
    public void header(String msg, String detail) {
        depth = 0;
        blankLine();
        var line = HEADER_INDENT + styled(BOLD, "● " + msg);
        out.get().println(detail == null ? line : line + " " + styled(DIM, detail));
    }

    @Override
    public void buildHeader(String name, int index, int total) {
        name = OutputFormat.oneLine(name);
        depth = 0;
        if (total > 1) {
            blankLine();
            var counter = "[" + index + "/" + total + "]";
            var label = "Building " + name;
            int gap = Math.max(2, 62 - 4 - label.length() - counter.length());
            out.get().println(HEADER_INDENT + styled(BOLD, "● " + label)
                    + " ".repeat(gap) + styled(DIM, counter));
        } else {
            header("Building " + name, null);
        }
    }

    @Override
    public void branchHeader(String name, String source) {
        depth = 0;
        blankLine();
        out.get().println(HEADER_INDENT + styled(BOLD, "● " + name) + " " + styled(DIM, "← " + source));
    }

    @Override
    public void warn(String msg, String... details) {
        msg = BuildOutput.warningText(msg);
        if (msg == null) return;
        err.get().println(indent() + styled(YELLOW, "⚠ " + safeLines(msg)));
        for (var detail : details) {
            err.get().println(indent() + GROUP_INDENT + styled(DIM, safeLines(detail)));
        }
        endWithBlankLine();
    }

    /** On stderr, red, and no closing blank line: the command stops here, there is no flow to separate. */
    @Override
    public void error(String msg, String... details) {
        if (msg == null || msg.isBlank()) return;
        blankLineEnded = false;
        err.get().println(indent() + styled(RED, "Error: " + safeLines(msg.strip())));
        for (var detail : details) {
            err.get().println(indent() + GROUP_INDENT + styled(DIM, safeLines(detail)));
        }
    }

    /** One line on stderr, and no closing blank line, which would go to stdout. */
    @Override
    public void warnAside(String msg) {
        msg = BuildOutput.warningText(msg);
        if (msg == null) return;
        blankLineEnded = false;
        err.get().println(indent() + styled(YELLOW, "⚠ " + safeLines(msg)));
    }

    /**
     * Close a warning with a blank line, which a following group or section does not repeat.
     * The blank line goes to stdout: it separates the warning from the regular flow there, and
     * keeps that flow's layout whether or not stderr is redirected (on a terminal, one stream
     * each would print two).
     */
    private void endWithBlankLine() {
        out.get().println();
        blankLineEnded = true;
    }

    @Override
    public void note(String msg) {
        if (msg == null) return;
        var line = OutputFormat.oneLine(msg);
        if (line.isBlank()) return;
        blankLineEnded = false;
        err.get().println(indent() + styled(DIM, line));
    }

    @Override
    public void success(String msg) {
        blankLine();
        out.get().println(HEADER_INDENT + styled(GREEN, "✓") + " " + styled(BOLD, msg));
    }

    @Override
    public void warnBanner(String title, String... lines) {
        var sep = styled(YELLOW, "─".repeat(60));
        var e = err.get();
        e.println(sep);
        e.println(styled(BOLD + YELLOW, title));
        for (var line : lines) {
            e.println(line);
        }
        e.println(sep);
    }
}
