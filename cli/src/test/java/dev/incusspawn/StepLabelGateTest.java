package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A label is bare in source, and the terminal decorates it (#1791).
 * {@code TerminalOutput} adds the ellipsis on a plain terminal and the spinner and the ✓ on an
 * ANSI one, so {@code "Launching x..."} is right for neither: the ✓ line has to undecorate it.
 * For each {@code .step(}, {@code .stepStart(}, {@code .liveStep(}, {@code .ok(}, {@code .success(},
 * {@code .note(} and {@code .stepFail(} call in main source of the three modules, the call's last
 * string literal may not end in {@code ...} or {@code …}; for {@code liveStep}, whose work follows
 * its label, the last literal of its first argument.
 * A live step's, an {@code ok}'s and a {@code success}'s may not end in a period either: they are
 * labels, while a step, a note or a failure can be a sentence of advice.
 */
class StepLabelGateTest {

    private static final Pattern CALL = Pattern.compile("\\.\\s*(step|stepStart|liveStep|ok|success|note|stepFail)\\s*\\(");
    /** The calls whose label may not end in a period either. */
    private static final Set<String> NO_PERIOD = Set.of("stepStart", "liveStep", "ok", "success");

    @Test
    void labelsAreBare() throws IOException {
        var failures = new StringBuilder();
        for (var source : RawOutputGateTest.sources("src/main/java").entrySet()) {
            var lines = find(source.getValue());
            if (!lines.isEmpty()) failures.append(source.getKey()).append(": decorated labels at lines ").append(lines).append('\n');
        }
        assertEquals("", failures.toString(), "write the label bare (\"Launching x\", not \"Launching x...\"):"
                + " the terminal adds the ellipsis or the ✓ (design/terminal.md \"Terminal Output Visual Language\")");
    }

    @Test
    void findsDecoratedLabelsInCodeOnly() {
        var source = """
                class A {
                    void f() {
                        BuildOutput.step("Configuring network...");
                        out.stepStart("Launching " + name + "…");
                        BuildOutput.success(name + " built successfully.");
                        output.ok(String.format("%s (%d)", a, b) + ".");
                        // BuildOutput.step("in a comment...");
                        BuildOutput.step("Base image is up to date.");
                        BuildOutput.note("Run isx init to set it up.");
                        BuildOutput.stepStart("Waiting for network");
                        BuildOutput.step(join(x, "..."), "label");
                        BuildOutput.note("a (paren) in it" + x + "\\u2026 ");
                        BuildOutput.success(
                                "two lines" +
                                "...");
                        BuildOutput.ok("x" + y.replace('.', '_'));
                        BuildOutput.liveStep("Installing " + name + "...", () -> runHost("sudo", "dnf") == 0);
                        BuildOutput.liveStep("Adding rules.", () -> ran("a"));
                        BuildOutput.liveStep("Enabling it", () -> ran("x", "y..."));
                        BuildOutput.stepFail("timed out...");
                        BuildOutput.stepFail("Run: sudo loginctl enable-linger.");
                    }
                }
                """;
        assertEquals(List.of(3, 4, 5, 6, 12, 13, 17, 18, 20), find(source));
    }

    /** One-based lines of each call whose label is decorated. */
    static List<Integer> find(String source) {
        // Decoded first, as the compiler reads it: each offset of the blanked code is then one of the source
        source = RawOutputGateTest.unicodeDecoded(source);
        var code = RawOutputGateTest.blankDecoded(source, false);
        var lines = RawOutputGateTest.lines(CALL, code).iterator();
        var found = new ArrayList<Integer>();
        var matcher = CALL.matcher(code);
        while (matcher.find()) {
            int line = lines.next();
            var label = lastLiteral(source, code, matcher.end(), matcher.group(1).equals("liveStep"));
            if (label != null && decorated(label, NO_PERIOD.contains(matcher.group(1)))) found.add(line);
        }
        return found;
    }

    private static boolean decorated(String label, boolean noPeriod) {
        label = label.stripTrailing();
        return label.endsWith("...") || label.endsWith("…")
                || noPeriod && label.endsWith(".");
    }

    /**
     * The contents of the last string literal between {@code open}, just after a call's opening
     * parenthesis, and the parenthesis that closes it, or the comma that ends its first argument
     * when {@code firstArgument}; null if there is none. {@code code} is
     * {@code source} {@link RawOutputGateTest#blank blanked}, offset for offset, so a parenthesis
     * or a quote in a literal or a comment is not counted.
     */
    private static String lastLiteral(String source, String code, int open, boolean firstArgument) {
        int depth = 1;
        int close = open;
        for (; close < code.length() && depth > 0; close++) {
            char c = code.charAt(close);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 1 && firstArgument) break;
        }
        int end = code.lastIndexOf('"', close);
        if (end < open) return null;
        int start = code.lastIndexOf('"', end - 1);
        if (start < open) return null;
        return source.substring(start + 1, end);
    }
}
