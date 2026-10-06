package dev.incusspawn.util;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Collection;
import java.util.Iterator;

/**
 * Shared terminal output helpers for build and branch operations.
 *
 * <p>Step output sits under a header at {@link #indent()}: {@link #STEP_INDENT} (4 spaces),
 * plus two more inside a {@link #group}. Slow operations use {@link #stepStart} /
 * {@link #stepDone}: on an ANSI terminal the step line animates a spinner until it is done
 * and then turns into a green ✓ (with the elapsed time once that is worth reading), so a
 * step that is still busy never looks finished. Without a terminal the line is printed
 * {@code "label... done."}, as a log would want it. {@link #step} prints a complete line.
 *
 * <p>While a step is live, anything else written to {@code System.out} or {@code System.err}
 * first freezes the step line and moves below it, so a warning or error printed mid-step is
 * never drawn over. Code that hands the terminal to a child process (inherited IO, or a
 * {@code sudo} that may prompt on the tty) calls {@link #releaseTerminal} first, since that
 * output bypasses the Java streams.
 *
 * <p>Colour is only written to an ANSI terminal without {@code NO_COLOR}
 * ({@link TerminalProgress#isAnsiTerminal}), and warnings and notes go to stderr, so piped
 * output holds no escapes and stdout holds no diagnostics.
 */
public final class BuildOutput {

    private BuildOutput() {}

    public static final String BOLD = "\033[1m";
    public static final String DIM  = "\033[2m";
    public static final String RED = "\033[31m";
    public static final String GREEN = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String CYAN = "\033[36m";
    private static final String RESET = "\033[0m";

    /** Green check that marks a finished step. */
    public static final String CHECK = GREEN + "✓" + RESET;
    /** Red cross that marks a failed step. */
    public static final String CROSS = RED + "✗" + RESET;

    private static final java.util.regex.Pattern ANSI_PATTERN =
            java.util.regex.Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");

    /** Whether to style output: on an ANSI terminal without {@code NO_COLOR}, or as a test forces. */
    private static boolean ansi() {
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

    public static final String STEP_INDENT = "    ";
    private static final String GROUP_INDENT = "  ";

    /** Steps longer than this show their elapsed time on the ✓ line. */
    private static final long SHOW_ELAPSED_MS = 2000;

    private static volatile int depth;
    /** Set when a closing {@link Group} printed a blank line, so the next block does not add a second. */
    private static volatile boolean blankLineEnded;

    /** The indent for a step line at the current nesting: inside a {@link #group}, one level deeper. */
    public static String indent() {
        return STEP_INDENT + GROUP_INDENT.repeat(depth);
    }

    /** Separate a new block with a blank line, unless a closing group just printed one. */
    private static void blankLine() {
        if (!blankLineEnded) System.out.println();
        blankLineEnded = false;
    }

    /**
     * Print a section title at the left margin, preceded by a blank line for visual
     * separation. Use this to introduce a block of work (e.g. "Refreshing 8 host repos:")
     * that isn't a bold-bullet {@link #header} for a single named operation.
     */
    public static void section(String msg) {
        blankLine();
        System.out.println(msg);
    }

    /**
     * Open a titled group of steps: {@code ▸ Tools  maven-3, mx}, bold with a dim detail,
     * after a blank line. Steps printed until the group is closed are indented one level
     * under it, and closing it leaves a blank line, so the next top-level step reads as
     * outside the group. Use with try-with-resources.
     */
    public static Group group(String title, String detail) {
        var sb = new StringBuilder(indent()).append(styled(CYAN, "▸ ")).append(styled(BOLD, title));
        if (detail != null && !detail.isEmpty()) sb.append("  ").append(styled(DIM, detail));
        blankLine();
        System.out.println(sb);
        var group = new Group(depth);
        depth = depth + 1;
        return group;
    }

    /** A {@link #group} being printed; closing it returns to the enclosing indent. */
    public static final class Group implements AutoCloseable {
        private final int previousDepth;
        private boolean closed;

        private Group(int previousDepth) { this.previousDepth = previousDepth; }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            depth = previousDepth;
            if (!blankLineEnded) System.out.println();
            blankLineEnded = true;
        }
    }

    /** Print a complete step line. */
    public static void step(String msg) {
        blankLineEnded = false;
        System.out.println(indent() + msg);
    }

    /** Print a dim note about the step above, one level under it (e.g. the version it verified). */
    public static void stepNote(String msg) {
        if (msg == null || msg.isBlank()) return;
        blankLineEnded = false;
        System.out.println(indent() + GROUP_INDENT + styled(DIM, msg));
    }

    /**
     * Print a yellow warning about the step above, one level under it, then a blank line
     * separating it from the regular flow. Like every warning, to stderr.
     */
    public static void stepWarn(String msg) {
        if (msg == null || msg.isBlank()) return;
        System.err.println(indent() + GROUP_INDENT + styled(YELLOW, "⚠ " + msg));
        endWithBlankLine();
    }

    /** Print a finished step: {@code ✓ msg}. For a result known at once, without a live step. */
    public static void ok(String msg) {
        blankLineEnded = false;
        System.out.println(indent() + styled(GREEN, "✓") + " " + msg);
    }

    /**
     * Print {@code items} comma-separated at the current indent, wrapping at the terminal width
     * with the same indent. Pair it with a {@link #group} header to list what the group covers.
     */
    public static void list(Collection<String> items) {
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
                System.out.println(sb);
                sb.setLength(0);
                sb.append(lineIndent);
                col = lineIndent.length();
            }
            sb.append(chunk);
            col += chunk.length();
        }
        if (col > lineIndent.length()) {
            System.out.println(sb);
        }
    }

    // ---- Live steps ----

    private static final Object LOCK = new Object();
    /** The step between {@link #stepStart} and its end; guarded by {@link #LOCK}. */
    private static LiveStep live;
    private static java.util.concurrent.ScheduledExecutorService ticker;
    /** Tests set this to exercise the animated rendering without a console; null means detect. */
    static volatile Boolean forceAnsi;

    private static final class LiveStep {
        final String msg;
        final String label;
        final String indent;
        final long startNanos = System.nanoTime();
        final boolean ansi;
        final PrintStream raw;
        final PrintStream previousOut;
        final PrintStream previousErr;
        PrintStream guardedOut;
        PrintStream guardedErr;
        java.util.concurrent.ScheduledFuture<?> animation;
        volatile String detail;
        /** Terminal width, measured by the spinner thread on its first frame; -1 until then. */
        int width = -1;
        /** Whether the terminal cursor still sits on this step's line. */
        boolean onLine = true;
        int frame;

        LiveStep(String msg, boolean ansi, PrintStream out, PrintStream err) {
            this.msg = msg;
            this.label = trimEllipsis(msg);
            this.indent = indent();
            this.ansi = ansi;
            this.raw = out;
            this.previousOut = out;
            this.previousErr = err;
        }

        String runningLine(int width) {
            var sb = new StringBuilder(indent)
                    .append(TerminalProgress.SPINNER[frame % TerminalProgress.SPINNER.length])
                    .append(' ').append(label);
            var d = detail;
            if (d != null && !d.isEmpty()) sb.append("  ").append(DIM).append(d).append(RESET);
            return TerminalProgress.truncateToWidth(sb.toString(), width);
        }

        long elapsedMillis() {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }
    }

    /** {@code "Installing foo..."} reads as a pending action in a log line; on a ✓ line it is noise. */
    static String trimEllipsis(String msg) {
        var s = msg.stripTrailing();
        if (s.endsWith("...")) return s.substring(0, s.length() - 3).stripTrailing();
        if (s.endsWith("…")) return s.substring(0, s.length() - 1).stripTrailing();
        return s;
    }

    /** Re-measured once per step (on the spinner thread, never the caller's), so a terminal
     *  narrowed between steps does not make the spinner line wrap and leave stale rows. */
    private static int width(LiveStep step) {
        if (forceAnsi != null) return 200;
        if (step.width < 0) step.width = TerminalProgress.terminalWidth();
        return step.width;
    }

    /**
     * Start a slow step. On an ANSI terminal the line animates until {@link #stepDone} (or
     * {@link #stepBreak} on failure); otherwise it is printed without a newline for
     * {@code stepDone} to complete. A trailing {@code "..."} in {@code msg} is dropped from the
     * animated line.
     */
    public static void stepStart(String msg) {
        synchronized (LOCK) {
            if (live != null) endLocked(live, null);
            blankLineEnded = false;
            var ansi = ansi();
            var step = new LiveStep(msg, ansi, System.out, System.err);
            if (!ansi) {
                step.raw.print(step.indent + msg);
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
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            live = step;
        }
    }

    /**
     * Show what a live step is doing right now, dim after its label (e.g. {@code verifying}).
     * Ignored without a terminal or when no step is live.
     */
    public static void stepProgress(String detail) {
        synchronized (LOCK) {
            if (live != null) live.detail = detail;
        }
    }

    /** Complete the step started by {@link #stepStart}. */
    public static void stepDone() {
        finish(null, true);
    }

    /**
     * Complete the step started by {@link #stepStart}, reporting a result value
     * inline instead of on a second line — e.g. {@code ✓ Extracting root disk (4.0G)}.
     */
    public static void stepDone(String detail) {
        finish(detail, true);
    }

    /**
     * End the step started by {@link #stepStart} as failed, before reporting the error: the
     * animated line turns into {@code ✗ label}, a plain one is terminated.
     */
    public static void stepBreak() {
        finish(null, false);
    }

    /**
     * Fail a slow step started by {@link #stepStart}: close the dangling line, then
     * write {@code msg} to stderr. Centralizes the "terminate the step line before any
     * stderr output" contract so callers don't each have to remember it.
     */
    public static void stepFail(String msg) {
        stepBreak();
        System.err.println(msg);
    }

    /**
     * Stop drawing a live step before a child process writes to the terminal directly
     * (inherited IO, or a {@code sudo} password prompt on the tty), which the stream guard
     * cannot see. The step keeps running: its {@link #stepDone} prints the ✓ on a new line.
     */
    public static void releaseTerminal() {
        synchronized (LOCK) {
            if (live != null) freezeLocked(live);
        }
    }

    /**
     * End a step that is still live as failed, if there is one: a command that stops on an
     * exception thrown mid-step calls this before reporting it, so the step shows ✗ and the
     * streams are no longer guarded. A no-op when no step is live.
     */
    public static void abandonStep() {
        synchronized (LOCK) {
            if (live != null) endLocked(live, null);
        }
    }

    private static void finish(String detail, boolean success) {
        synchronized (LOCK) {
            var step = live;
            if (step == null) {
                // No live step (its start was never printed): still close the line the caller expects.
                if (success) System.out.println(detail == null ? " done." : " done (" + detail + ").");
                else System.out.println();
                return;
            }
            endLocked(step, success ? new Outcome(detail) : null);
        }
    }

    private record Outcome(String detail) {}

    /** Stop animating {@code step}, restore the streams and print its final line;
     *  {@code outcome} null means failed (or superseded by another step). */
    private static void endLocked(LiveStep step, Outcome outcome) {
        if (step.animation != null) step.animation.cancel(false);
        if (System.out == step.guardedOut) System.setOut(step.previousOut);
        if (System.err == step.guardedErr) System.setErr(step.previousErr);
        live = null;
        var out = step.raw;
        if (step.ansi) {
            String text;
            if (outcome != null) {
                var sb = new StringBuilder(step.indent).append(CHECK).append(' ').append(step.label);
                if (outcome.detail() != null) sb.append(' ').append(DIM).append('(').append(outcome.detail()).append(')').append(RESET);
                var ms = step.elapsedMillis();
                if (ms >= SHOW_ELAPSED_MS) sb.append("  ").append(DIM).append(formatElapsed(ms)).append(RESET);
                text = sb.toString();
            } else {
                text = step.indent + CROSS + " " + step.label;
            }
            out.print((step.onLine ? "\r\033[2K" : "") + text + "\n");
        } else if (outcome != null) {
            var done = outcome.detail() == null ? " done." : " done (" + outcome.detail() + ").";
            out.println(step.onLine ? done : step.indent + step.msg + done);
        } else if (step.onLine) {
            out.println();
        }
        out.flush();
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

    private static void redraw(LiveStep step) {
        int width = width(step); // outside the lock: measuring runs `stty`
        synchronized (LOCK) {
            if (live != step || !step.onLine) return;
            step.raw.print("\r\033[2K" + step.runningLine(width));
            step.raw.flush();
            step.frame++;
        }
    }

    public static String formatElapsed(long ms) {
        if (ms < 10_000) return String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0);
        long s = Math.round(ms / 1000.0);
        if (s < 60) return s + "s";
        return (s / 60) + "m" + String.format(java.util.Locale.ROOT, "%02d", s % 60) + "s";
    }

    private static java.util.concurrent.ScheduledExecutorService scheduler() {
        if (ticker == null) {
            ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "step-spinner");
                t.setDaemon(true);
                return t;
            });
        }
        return ticker;
    }

    /** A stream that freezes the live step line before letting anything else reach the terminal. */
    private static PrintStream guard(PrintStream target) {
        var guarded = new OutputStream() {
            @Override public void write(int b) {
                releaseTerminal();
                target.write(b);
            }
            @Override public void write(byte[] b, int off, int len) {
                if (len == 0) return;
                releaseTerminal();
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
        depth = 0;
        blankLine();
        System.out.println("  " + styled(BOLD, "● " + msg));
    }

    /**
     * Header with a dim secondary detail: {@code  ● Resizing VM data disk  60.0G → 120.0G}.
     * The detail is rendered dim, mirroring {@link #branchHeader}'s source styling, so every
     * header's secondary part reads the same.
     */
    public static void header(String msg, String detail) {
        depth = 0;
        blankLine();
        System.out.println("  " + styled(BOLD, "● " + msg) + " " + styled(DIM, detail));
    }

    /** Print a bold bullet header: {@code  ● Building tpl-dev  [1/3]} */
    public static void buildHeader(String name, int index, int total) {
        depth = 0;
        if (total > 1) {
            blankLine();
            var counter = "[" + index + "/" + total + "]";
            var label = "Building " + name;
            int gap = Math.max(2, 62 - 4 - label.length() - counter.length());
            System.out.println("  " + styled(BOLD, "● " + label)
                    + " ".repeat(gap) + styled(DIM, counter));
        } else {
            header("Building " + name);
        }
    }

    /** Print a bold bullet header for a branch: {@code  ● my-instance  ← tpl-dev} */
    public static void branchHeader(String name, String source) {
        depth = 0;
        blankLine();
        System.out.println("  " + styled(BOLD, "● " + name) + " " + styled(DIM, "← " + source));
    }

    /**
     * Print a yellow warning line and any dim {@code details} under it (e.g. what to do about
     * it), then a blank line separating them from the regular flow. Blank messages are skipped.
     * To stderr, so stdout holds only what a command reports.
     */
    public static void warn(String msg, String... details) {
        if (msg == null || msg.isBlank()) return;
        System.err.println(indent() + styled(YELLOW, "⚠ " + msg));
        for (var detail : details) {
            System.err.println(indent() + GROUP_INDENT + styled(DIM, detail));
        }
        endWithBlankLine();
    }

    /** Close a warning with a blank line, which a following group or section does not repeat. */
    private static void endWithBlankLine() {
        System.err.println();
        blankLineEnded = true;
    }

    /**
     * Print an indented dim note (informational, not a warning) to stderr, beside the warnings.
     * Blank messages are skipped.
     */
    public static void note(String msg) {
        if (msg == null || msg.isBlank()) return;
        blankLineEnded = false;
        System.err.println(indent() + styled(DIM, msg));
    }

    /** Print a green checkmark success line. */
    public static void success(String msg) {
        blankLine();
        System.out.println(STEP_INDENT + styled(GREEN, "✓") + " " + msg);
    }

    /**
     * Print a yellow-bordered warning banner to stderr with a bold title and optional body lines.
     * Use for diagnostic warnings that need to stand out (subnet conflicts, CA mismatches, etc.).
     */
    public static void warnBanner(String title, String... lines) {
        var sep = styled(YELLOW, "─".repeat(60));
        System.err.println(sep);
        System.err.println(styled(BOLD + YELLOW, title));
        for (var line : lines) {
            System.err.println(line);
        }
        System.err.println(sep);
    }
}
