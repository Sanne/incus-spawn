package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A choice's prompt is built by {@code Prompts} alone (#2593).
 * A prompt says what Enter does in one of two forms, {@code Choice [1]:} or
 * {@code Choice, or Enter to skip:}, and {@code Choice:} where Enter does nothing
 * ({@code .claude/rules/interface-text.md}).
 * {@code Prompts.choiceEnterTakes} and {@code Prompts.choiceEnterDoes} write them, so no string
 * literal in main source of the three modules starts with {@code Choice} outside
 * {@code Prompts.java}: one written by hand could drift from the forms, and rewording them would
 * again be an edit at every site.
 */
class ChoicePromptGateTest {

    private static final Pattern CHOICE_LITERAL = Pattern.compile("\"(?:\"\"\\s*)?(?:\\s|\\\\[nt]|%n)*Choice\\b");
    private static final String PROMPTS = "cli/src/main/java/dev/incusspawn/command/Prompts.java";

    @Test
    void choicePromptsAreBuiltByPrompts() throws IOException {
        var failures = new StringBuilder();
        for (var source : RawOutputGateTest.sources("src/main/java").entrySet()) {
            if (source.getKey().equals(PROMPTS)) continue;
            var lines = find(source.getValue());
            if (!lines.isEmpty()) failures.append(source.getKey()).append(": a \"Choice\" prompt at lines ").append(lines).append('\n');
        }
        assertEquals("", failures.toString(), "build the prompt with Prompts.choiceEnterTakes, Prompts.choiceEnterDoes or Prompts.choice,"
                + " so it says what Enter does in the one form (.claude/rules/interface-text.md)");
    }

    @Test
    void findsChoiceLiteralsInCodeOnly() {
        var source = """
                class A {
                    void f() {
                        prompts.ask("Choice [Y]: ");
                        // prompts.ask("Choice: ");
                        prompts.ask("Choice, or Enter to " + what + ": ");
                        prompts.ask(Prompts.choiceEnterDoes(null));
                        BuildOutput.step("Your Choice matters");
                        prompts.ask("Cho\\u0069ce: ");
                        prompts.ask("Choices: ");
                        prompts.ask("  Choice: ");
                        prompts.ask("\\nChoice: ");
                        prompts.ask("\\tChoice: ");
                        prompts.ask("%nChoice [1]: ");
                        prompts.ask(\"""
                                Choice [1]:\""");
                    }
                }
                """;
        assertEquals(List.of(3, 5, 8, 10, 11, 12, 13, 14), find(source));
    }

    /** One-based lines of each string literal or text block whose text starts with {@code Choice}, after any blanks, {@code \\n}, {@code \\t} or {@code %n}, comments left out. */
    static List<Integer> find(String source) {
        return RawOutputGateTest.lines(CHOICE_LITERAL, RawOutputGateTest.blankDecoded(RawOutputGateTest.unicodeDecoded(source), true));
    }
}
