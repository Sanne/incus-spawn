package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The test helper's own guarantees: it catches a secret read through the wrong prompt, and
 * saying so never prints the secret -- a failing CI run would otherwise leak it into the log.
 */
class ScriptedPromptsTest {

    private static final String PLANTED = "sk-ant-api03-plantedvalue";

    @Test
    void aSecretReadThroughAnEchoingPromptFailsWithoutPrintingIt() {
        var prompts = new ScriptedPrompts().line("n").secret(PLANTED);
        prompts.readLine();

        var failure = assertThrows(AssertionError.class, prompts::readLine);
        assertTrue(failure.getMessage().contains("secret #2"), failure.getMessage());
        assertFalse(failure.getMessage().contains(PLANTED), failure.getMessage());
    }

    @Test
    void anUnaskedSecretIsReportedWithoutPrintingIt() {
        var prompts = new ScriptedPrompts().line("n").secret(PLANTED);
        prompts.readLine();

        var failure = assertThrows(AssertionError.class, prompts::assertFullyConsumed);
        assertTrue(failure.getMessage().contains("secret #2"), failure.getMessage());
        assertFalse(failure.getMessage().contains(PLANTED), failure.getMessage());
    }

    /** Plain lines are keystrokes, and naming them is what makes a failure readable. */
    @Test
    void aLineReadThroughASecretPromptIsNamed() {
        var prompts = ScriptedPrompts.lines("a");

        var failure = assertThrows(AssertionError.class, prompts::readPassword);
        assertTrue(failure.getMessage().contains("line #1 'a'"), failure.getMessage());
    }

    @Test
    void exhaustedInputAnswersEofAndCountsAsAnExtraQuestion() {
        var prompts = new ScriptedPrompts();
        assertNull(prompts.readLine());
        assertNull(prompts.readPassword());

        var failure = assertThrows(AssertionError.class, prompts::assertFullyConsumed);
        assertTrue(failure.getMessage().contains("2 more question(s)"), failure.getMessage());
    }
}
