package dev.incusspawn.command;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Canned answers for an interactive flow, in the order it asks for them.
 *
 * <p>Each answer is scripted either as a plain {@link #line} or as a {@link #secret}, and handing
 * one to the other kind of prompt fails the test: a token prompt that quietly started reading
 * through an echoing {@code readLine()} would put the credential on screen, and nothing else
 * would notice. Once the script runs out every read answers {@code null}, as a closed stdin
 * does, so a flow can be driven to its end the way {@code isx init </dev/null} drives it in CI.
 */
final class ScriptedPrompts implements Prompts {

    private record Answer(String value, boolean secret) {}

    private final Deque<Answer> answers = new ArrayDeque<>();
    private int readsPastEnd;

    /** Plain lines only; the common case for menus. */
    static ScriptedPrompts lines(String... values) {
        return new ScriptedPrompts().line(values);
    }

    ScriptedPrompts line(String... values) {
        for (var value : values) answers.add(new Answer(value, false));
        return this;
    }

    ScriptedPrompts secret(String value) {
        answers.add(new Answer(value, true));
        return this;
    }

    @Override
    public String readLine() {
        return next(false);
    }

    @Override
    public char[] readPassword() {
        var value = next(true);
        return value == null ? null : value.toCharArray();
    }

    private String next(boolean secret) {
        var answer = answers.poll();
        if (answer == null) {
            readsPastEnd++;
            return null;
        }
        if (answer.secret() != secret) {
            throw new AssertionError(secret
                    ? "a secret prompt read '" + answer.value() + "', which the script expects to be an ordinary line"
                    : "an echoing prompt read '" + answer.value() + "', which the script expects to be entered as a secret");
        }
        return answer.value();
    }

    /** Every scripted answer was asked for, and nothing was asked beyond them. */
    void assertFullyConsumed() {
        if (!answers.isEmpty()) {
            throw new AssertionError("the flow ended without asking for: " + answers);
        }
        if (readsPastEnd > 0) {
            throw new AssertionError("the flow asked " + readsPastEnd + " more question(s) than scripted");
        }
    }
}
