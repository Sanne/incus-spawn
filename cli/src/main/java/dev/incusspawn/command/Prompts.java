package dev.incusspawn.command;

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
        var console = System.console();
        return console == null ? null : of(console);
    }
}
