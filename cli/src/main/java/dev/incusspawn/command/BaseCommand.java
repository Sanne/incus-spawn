package dev.incusspawn.command;

import dev.incusspawn.util.BuildOutput;
import org.aesh.command.Command;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

import java.io.Console;
import java.io.PrintStream;

public abstract class BaseCommand implements Command<CommandInvocation> {

    protected CommandInvocation commandInvocation;

    @Override
    public CommandResult execute(CommandInvocation invocation) throws InterruptedException {
        this.commandInvocation = invocation;
        try {
            return doExecute();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            BuildOutput.abandonStep();
            System.err.println("Error: " + e.getMessage());
            return CommandResult.valueOf(1);
        } finally {
            // A step an exception cut short must not stay animated, nor keep the streams guarded
            // for a TUI that resumes in this process after the command.
            BuildOutput.abandonStep();
        }
    }

    protected CommandResult doExecute() throws Exception {
        return CommandResult.SUCCESS;
    }

    /**
     * Reads a yes/no response, accepting an empty response as the supplied default.
     * EOF declines by default; callers can opt into a different EOF result where needed.
     * Callers decide how to handle an unavailable console.
     */
    protected static boolean askConfirmation(Console console, String prompt, boolean defaultValue) {
        return askConfirmation(console, System.out, prompt, defaultValue, false);
    }

    protected static boolean askConfirmation(Console console, String prompt,
                                             boolean defaultValue, boolean eofValue) {
        return askConfirmation(console, System.out, prompt, defaultValue, eofValue);
    }

    protected static boolean askConfirmation(Console console, PrintStream output,
                                             String prompt, boolean defaultValue) {
        return askConfirmation(console, output, prompt, defaultValue, false);
    }

    protected static boolean askConfirmation(Console console, PrintStream output,
                                             String prompt, boolean defaultValue, boolean eofValue) {
        return askConfirmation(Prompts.of(console), output, prompt, defaultValue, eofValue);
    }

    protected static boolean askConfirmation(Prompts prompts, String prompt, boolean defaultValue) {
        return askConfirmation(prompts, System.out, prompt, defaultValue, false);
    }

    protected static boolean askConfirmation(Prompts prompts, String prompt,
                                             boolean defaultValue, boolean eofValue) {
        return askConfirmation(prompts, System.out, prompt, defaultValue, eofValue);
    }

    protected static boolean askConfirmation(Prompts prompts, PrintStream output,
                                             String prompt, boolean defaultValue, boolean eofValue) {
        while (true) {
            output.print(prompt + (defaultValue ? " (Y/n): " : " (y/N): "));
            var answer = prompts.readLine();
            if (answer == null) return eofValue;

            var parsed = parseConfirmation(answer, defaultValue);
            if (parsed != null) return parsed;
            output.println("Please answer y or n.");
        }
    }

    /**
     * Ask before a disruptive but recoverable action (a restart, a resize). With no terminal it
     * proceeds; anything that deletes data uses {@link #confirmDestructive} instead.
     */
    protected static boolean confirm(String prompt, boolean skipConfirmation) {
        if (skipConfirmation) return true;
        var console = System.console();
        if (console == null) return true;
        if (!askConfirmation(console, prompt, false)) {
            System.out.println("Aborted.");
            return false;
        }
        return true;
    }

    /**
     * {@link #confirm} for an action that deletes data. With no terminal to ask on, {@code confirm}
     * proceeds without reading stdin, so {@code echo n | isx ...} deletes anyway; this refuses
     * instead, by throwing {@link NoTerminalException} (which {@link #execute} reports as an error
     * and exit status 1). Declining at the prompt returns {@code false}, which is not an error.
     *
     * @param skipFlag the option that skips the question, named in the refusal
     */
    protected static boolean confirmDestructive(String prompt, boolean skipConfirmation, String skipFlag) {
        return confirmDestructive(prompt, skipConfirmation, skipFlag, System.console());
    }

    static boolean confirmDestructive(String prompt, boolean skipConfirmation, String skipFlag,
                                      Console console) {
        if (skipConfirmation) return true;
        // Since JDK 22 System.console() can be non-null with stdin redirected; isTerminal() says which.
        if (console == null || !console.isTerminal()) throw new NoTerminalException(skipFlag);
        if (!askConfirmation(console, prompt, false)) {
            System.out.println("Aborted.");
            return false;
        }
        return true;
    }

    static final class NoTerminalException extends RuntimeException {
        NoTerminalException(String skipFlag) {
            super("this deletes data and there is no terminal to confirm it on. Re-run with "
                    + skipFlag + " to proceed without asking.");
        }
    }

    static Boolean parseConfirmation(String answer, boolean defaultValue) {
        if (answer == null) return null;
        var normalized = answer.strip();
        if (normalized.isEmpty()) return defaultValue;
        if (normalized.equalsIgnoreCase("y")) return true;
        if (normalized.equalsIgnoreCase("n")) return false;
        return null;
    }
}
