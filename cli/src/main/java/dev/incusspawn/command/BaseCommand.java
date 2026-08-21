package dev.incusspawn.command;

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
            System.err.println("Error: " + e.getMessage());
            return CommandResult.valueOf(1);
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
        while (true) {
            output.print(prompt + (defaultValue ? " (Y/n): " : " (y/N): "));
            var answer = console.readLine();
            if (answer == null) return eofValue;

            var parsed = parseConfirmation(answer, defaultValue);
            if (parsed != null) return parsed;
            output.println("Please answer y or n.");
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
