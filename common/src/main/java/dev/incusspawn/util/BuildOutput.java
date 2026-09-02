package dev.incusspawn.util;

import java.util.Collection;
import java.util.Iterator;

/**
 * Shared terminal output helpers for build and branch operations.
 *
 * <p>All step output is indented by {@link #STEP_INDENT} (4 spaces) so it
 * sits visually under a header line. Slow operations use the print/flush/done
 * pattern: {@link #stepStart} prints an incomplete line, and the caller
 * prints {@code " done.\n"} on completion via {@link #stepDone}. Fast or
 * informational steps use {@link #step} for a complete line.
 */
public final class BuildOutput {

    private BuildOutput() {}

    private static final String LIST_INDENT = "      ";

    private static final String BOLD = "[1m";
    private static final String DIM  = "[2m";
    private static final String GREEN = "[32m";
    private static final String RESET = "[0m";

    private static final java.util.regex.Pattern ANSI_PATTERN =
            java.util.regex.Pattern.compile("\\[[0-9;]*m");

    /** Strip ANSI escape sequences from a string. */
    public static String stripAnsi(String s) {
        return ANSI_PATTERN.matcher(s).replaceAll("");
    }

    public static final String STEP_INDENT = "    ";

    /** Print a complete indented step line. */
    public static void step(String msg) {
        System.out.println(STEP_INDENT + msg);
    }

    /**
     * Print a header line followed by a comma-separated list of items that wraps
     * at the terminal width with a hanging indent (6 spaces).
     */
    public static void stepWithList(String header, Collection<String> items) {
        step(header);
        int width = TerminalProgress.terminalWidth();
        var sb = new StringBuilder(LIST_INDENT);
        int col = LIST_INDENT.length();
        Iterator<String> it = items.iterator();
        while (it.hasNext()) {
            var item = it.next();
            var suffix = it.hasNext() ? ", " : "";
            var chunk = item + suffix;
            if (col + chunk.length() > width && col > LIST_INDENT.length()) {
                System.out.println(sb);
                sb.setLength(0);
                sb.append(LIST_INDENT);
                col = LIST_INDENT.length();
            }
            sb.append(chunk);
            col += chunk.length();
        }
        if (col > LIST_INDENT.length()) {
            System.out.println(sb);
        }
    }

    /** Start a slow step — prints an incomplete line (no newline). Call {@link #stepDone} when finished. */
    public static void stepStart(String msg) {
        System.out.print(STEP_INDENT + msg);
        System.out.flush();
    }

    /** Complete a line started by {@link #stepStart}. */
    public static void stepDone() {
        System.out.println(" done.");
    }

    /**
     * Complete a line started by {@link #stepStart}, reporting a result value
     * inline instead of on a second line — e.g. {@code Extracting root disk... done (4.0G).}
     */
    public static void stepDone(String detail) {
        System.out.println(" done (" + detail + ").");
    }

    /** Print a newline to close an incomplete {@link #stepStart} line before an error. */
    public static void stepBreak() {
        System.out.println();
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
     * Print a bold bullet header for a top-level operation: {@code  ● Resizing VM data disk}.
     * Preceded by a blank line. Use this to frame any multi-step command; the
     * build/branch-specific {@link #buildHeader}/{@link #branchHeader} build on the same style.
     */
    public static void header(String msg) {
        System.out.println();
        System.out.println("  " + BOLD + "● " + msg + RESET);
    }

    /**
     * Header with a dim secondary detail: {@code  ● Resizing VM data disk  60.0G → 120.0G}.
     * The detail is rendered dim, mirroring {@link #branchHeader}'s source styling, so every
     * header's secondary part reads the same.
     */
    public static void header(String msg, String detail) {
        System.out.println();
        System.out.println("  " + BOLD + "● " + msg + RESET + " " + DIM + detail + RESET);
    }

    /** Print a bold bullet header: {@code  ● Building tpl-dev  [1/3]} */
    public static void buildHeader(String name, int index, int total) {
        if (total > 1) {
            System.out.println();
            var counter = "[" + index + "/" + total + "]";
            var label = "Building " + name;
            int gap = Math.max(2, 62 - 4 - label.length() - counter.length());
            System.out.println("  " + BOLD + "● " + label + RESET
                    + " ".repeat(gap) + DIM + counter + RESET);
        } else {
            header("Building " + name);
        }
    }

    /** Print a bold bullet header for a branch: {@code  ● my-instance  ← tpl-dev} */
    public static void branchHeader(String name, String source) {
        System.out.println();
        System.out.println("  " + BOLD + "● " + name + RESET
                + " " + DIM + "← " + source + RESET);
    }

    private static final String YELLOW = "[33m";

    /** Print a yellow warning line. Blank messages are skipped. */
    public static void warn(String msg) {
        if (msg == null || msg.isBlank()) return;
        System.out.println(STEP_INDENT + YELLOW + "⚠ " + msg + RESET);
    }

    /** Print an indented dim note (informational, not a warning). Blank messages are skipped. */
    public static void note(String msg) {
        if (msg == null || msg.isBlank()) return;
        System.out.println(STEP_INDENT + DIM + msg + RESET);
    }

    /** Print a green checkmark success line. */
    public static void success(String msg) {
        System.out.println();
        System.out.println(STEP_INDENT + GREEN + "✓" + RESET + " " + msg);
    }
}
