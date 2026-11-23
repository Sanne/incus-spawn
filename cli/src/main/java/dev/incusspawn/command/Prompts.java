package dev.incusspawn.command;

import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Headless;
import dev.incusspawn.util.TerminalStreams;

import java.io.Console;

/**
 * Where an interactive command reads its answers from.
 *
 * <p>Interactive code takes this rather than a {@link Console}, which is final and so cannot be
 * stood in for: a method that accepts a {@code Console} is reachable only by a human at a
 * keyboard, and that is how {@code isx init}'s credential flows shipped bugs no test could have
 * caught (#772). Production reads the terminal through {@link #of(Console)}; tests hand in
 * canned answers.
 *
 * <p>Both methods answer {@code null} at EOF, exactly as {@code Console} does, so every caller
 * keeps deciding for itself what running out of input means.
 *
 * <p>The question is shown here too ({@link #show}, {@link #ask}), with the lines said only
 * because one is asked ({@link #say}: its options, a re-ask), so what a flow asks and what it
 * reads live in one place, and a test sees both (#1703). The terminal's are shown through the
 * bound output ({@code BuildOutput.prompt}, a {@code step} for a said line), at its step
 * indent, so a question asked in a group sits in it. What a flow reports between questions is
 * not the conversation: it goes through {@code BuildOutput}.
 */
public interface Prompts {

    /** One line of input, or {@code null} at EOF. */
    String readLine();

    /**
     * One line of input that must not be echoed -- a token, a key -- or {@code null} at EOF.
     * Kept distinct from {@link #readLine()} so that a secret prompt quietly turning into an
     * echoing one is something a test can notice.
     */
    char[] readPassword();

    /**
     * Shows {@code question} where its answer is typed: through the bound output
     * ({@code BuildOutput.prompt}), at the step indent, with no line end.
     */
    default void show(String question) {
        BuildOutput.prompt(question);
    }

    /** A line said only because a question is asked, such as an option or a re-ask: a step of the bound output. */
    default void say(String line) {
        BuildOutput.step(line);
    }

    /** {@link #show}s the question and reads its answer: {@code null} at EOF. */
    default String ask(String question) {
        show(question);
        return readLine();
    }


    /**
     * Asks a question with several choices, laid out one way for every wizard (#2492): a blank
     * line, the lead-in asking the question and saying to choose one, the options numbered
     * and indented as a group (a newline in an option continues it under its text), a note
     * that holds for several options once, and a final prompt that says what Enter does.
     * The lines are said, the prompt is shown, so a test reads the whole conversation.
     *
     * @param question  what is asked, without "Choose one": that is added
     * @param options   one entry per choice, one or two lines each
     * @param note      a line after the list, or {@code null}
     * @param enterDoes what an empty answer does, as it completes "Enter to ...": e.g. "skip";
     *                  null when an empty answer chooses nothing, so the caller asks again
     * @return the answer as typed, {@code null} at EOF
     */
    default String choose(String question, java.util.List<String> options, String note, String enterDoes) {
        BuildOutput.gap();
        say(question + " Choose one:");
        say("");
        var width = String.valueOf(options.size()).length() + 2;
        for (int i = 0; i < options.size(); i++) {
            var lines = options.get(i).split("\n");
            var number = (i + 1) + ".";
            say("  " + number + " " + lines[0]);
            for (int l = 1; l < lines.length; l++) say("  " + " ".repeat(width) + lines[l]);
        }
        if (note != null) {
            say("");
            say(note);
        }
        say("");
        return ask(enterDoes == null ? "Choice: " : "Choice (Enter to " + enterDoes + "): ");
    }

    /** {@link #show}s the question and reads its answer without echoing it: {@code null} at EOF. */
    default char[] askSecret(String question) {
        show(question);
        return readPassword();
    }

    static Prompts of(Console console) {
        return new Prompts() {
            @Override
            public String readLine() {
                return console.readLine();
            }

            @Override
            public char[] readPassword() {
                return console.readPassword();
            }

        };
    }

    /** The process's terminal, or {@code null} when there is none (piped or detached). */
    static Prompts console() {
        // Headless (isx mcp): stdin is the protocol stream, and a prompt would eat it.
        if (Headless.active()) return null;
        var console = System.console();
        return console == null ? null : of(console);
    }

    /**
     * {@link #console}, but null unless stdin and stdout are a terminal ({@link TerminalStreams#terminal}),
     * where a question would otherwise read the caller's piped input as its answer.
     */
    static Prompts terminal() {
        if (Headless.active()) return null;
        var console = TerminalStreams.terminal();
        return console == null ? null : of(console);
    }
}
