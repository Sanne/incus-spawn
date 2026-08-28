package dev.incusspawn.util;

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

    private static final String BOLD = "\u001B[1m";
    private static final String DIM  = "\u001B[2m";
    private static final String GREEN = "\u001B[32m";
    private static final String RESET = "\u001B[0m";

    public static final String STEP_INDENT = "    ";

    /** Print a complete indented step line. */
    public static void step(String msg) {
        System.out.println(STEP_INDENT + msg);
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

    /** Print a newline to close an incomplete {@link #stepStart} line before an error. */
    public static void stepBreak() {
        System.out.println();
    }

    /** Print a bold bullet header: {@code  ● Building tpl-dev  [1/3]} */
    public static void buildHeader(String name, int index, int total) {
        System.out.println();
        if (total > 1) {
            var counter = "[" + index + "/" + total + "]";
            var label = "Building " + name;
            int gap = Math.max(2, 62 - 4 - label.length() - counter.length());
            System.out.println("  " + BOLD + "● " + label + RESET
                    + " ".repeat(gap) + DIM + counter + RESET);
        } else {
            System.out.println("  " + BOLD + "● Building " + name + RESET);
        }
    }

    /** Print a bold bullet header for a branch: {@code  ● my-instance  ← tpl-dev} */
    public static void branchHeader(String name, String source) {
        System.out.println();
        System.out.println("  " + BOLD + "● " + name + RESET
                + " " + DIM + "← " + source + RESET);
    }

    /** Print an indented dim note (informational, not a warning). */
    public static void note(String msg) {
        System.out.println(STEP_INDENT + DIM + msg + RESET);
    }

    /** Print a green checkmark success line. */
    public static void success(String msg) {
        System.out.println();
        System.out.println(STEP_INDENT + GREEN + "✓" + RESET + " " + msg);
    }
}
