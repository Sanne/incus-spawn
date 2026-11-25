package dev.incusspawn.command;

import dev.incusspawn.util.OutputFormat;

/**
 * What isx says around a shell in an instance (#2528). Leaving one printed only the guest's
 * {@code logout}, and nothing said that the instance goes on running or how to come back: a
 * person who had just opened their first one was left at their own prompt with no word. The
 * line costs no request: it says what isx does, not what it read.
 *
 * <p>Said by the commands that open a shell for a person at a terminal ({@code isx branch},
 * {@code isx shell}, {@code isx run}), not where they all meet in {@code InteractiveShell}: the
 * TUI comes back to its own screen, and a task's conversation ends with the guest's own word.
 */
final class ShellFarewell {

    private ShellFarewell() {}

    /**
     * After a shell in {@code name} ends: that isx leaves the instance running, and the two
     * commands that follow from that. It says what isx did, never the state it did not read,
     * since something run inside the shell may have stopped the instance.
     */
    static String left(String name) {
        var shown = OutputFormat.oneLine(name);
        return "Left " + shown + ", which isx leaves running. Return: isx shell " + shown + ". Stop it: isx stop " + shown + ".";
    }

    /**
     * Before the prompt of the first shell a person opens, in the first run's words: where they
     * are, and how to leave.
     */
    static String entering(String name) {
        return "You are now inside " + OutputFormat.oneLine(name) + ", an isx machine. Type 'exit' to leave it.";
    }
}
