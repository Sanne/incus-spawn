package dev.incusspawn.tui;

import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.ForcedAnsi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** The logo outside the TUI: one shape for both, and no colour a terminal is not known to draw. */
class LogoTest {

    private static UnaryOperator<String> env(Map<String, String> values) {
        return values::get;
    }

    @Test
    void theTuiAndTheWelcomeDrawTheSameRows() {
        assertSame(Logo.ROWS, Glyphs.LOGO);
        for (var row : Logo.ROWS) assertEquals(Logo.WIDTH, row.length(), row);
    }

    /** Whatever the colours, what the terminal draws is the rows themselves. */
    @Test
    void everyDepthDrawsTheRowsThemselves() {
        try (var ansi = ForcedAnsi.on()) {
            for (var values : List.of(Map.of("COLORTERM", "truecolor"), Map.of("TERM", "xterm-256color"),
                    Map.of("TERM", "linux"), Map.<String, String>of())) {
                var rows = Logo.forTerminal(env(values));
                assertEquals(Logo.ROWS.length, rows.size());
                for (int i = 0; i < rows.size(); i++) assertEquals(Logo.ROWS[i], BuildOutput.stripAnsi(rows.get(i)), values::toString);
            }
        }
    }

    /** 24-bit colour only on proof of it, as in the TUI (#1681). */
    @Test
    void theGradientIsTwentyFourBitOnlyOnProof() {
        try (var ansi = ForcedAnsi.on()) {
            assertTrue(Logo.forTerminal(env(Map.of("COLORTERM", "truecolor"))).getFirst().contains("[1;38;2;"));
            assertTrue(Logo.forTerminal(env(Map.of("COLORTERM", "24bit"))).getFirst().contains("[1;38;2;"));
            var palette = Logo.forTerminal(env(Map.of("TERM", "xterm-256color"))).getFirst();
            assertTrue(palette.contains("[1;38;5;"), palette);
            assertFalse(palette.contains("38;2;"), palette);
            // Neither proof nor palette: the terminal's own cyan, the colour of the first run's frames
            var plainTerminal = Logo.forTerminal(env(Map.of("TERM", "linux"))).getFirst();
            assertFalse(plainTerminal.contains("38;2;") || plainTerminal.contains("38;5;"), plainTerminal);
            assertTrue(plainTerminal.contains(BuildOutput.CYAN), plainTerminal);
        }
    }

    @Test
    void isxColorsOverridesWhatTheEnvironmentSays() {
        try (var ansi = ForcedAnsi.on()) {
            var forced = Logo.forTerminal(env(Map.of(ColorDepth.OVERRIDE, "16", "COLORTERM", "truecolor"))).getFirst();
            assertFalse(forced.contains("38;2;"), forced);
            assertEquals(ColorDepth.TRUECOLOR, Logo.depth(env(Map.of(ColorDepth.OVERRIDE, "truecolor", "TERM", "linux"))));
        }
    }

    /**
     * Beside the logo only for a person at a terminal: what a script reads from a pipe or a
     * command substitution is the lines as they always were, since that output is a contract.
     */
    @Test
    void theLogoIsBesideTheLinesOnlyOnATerminal() {
        var lines = List.of("isx 1.2.3 (abc1234)", "incus client 6, server 6", "Java 25");
        assertSame(lines, Logo.beside(lines, env(Map.of("COLORTERM", "truecolor"))), "off a terminal the lines are untouched");

        try (var ansi = ForcedAnsi.on()) {
            var beside = Logo.beside(lines, env(Map.of("TERM", "linux")));
            assertEquals(3, beside.size());
            for (int i = 0; i < 3; i++) {
                assertEquals(Logo.ROWS[i] + "    " + lines.get(i), BuildOutput.stripAnsi(beside.get(i)));
            }
        }
    }

    /** More lines than rows continue in the text's column, and an empty line leaves no trailing space. */
    @Test
    void linesBeyondTheLogoKeepTheirColumnAndAnEmptyLineEndsAtTheLogo() {
        try (var ansi = ForcedAnsi.on()) {
            var beside = Logo.beside(List.of("", "a description", "", "a fourth line"), env(Map.of()));
            assertEquals(Logo.ROWS[0], BuildOutput.stripAnsi(beside.get(0)));
            assertEquals(Logo.ROWS[1] + "    a description", BuildOutput.stripAnsi(beside.get(1)));
            assertEquals(Logo.ROWS[2], BuildOutput.stripAnsi(beside.get(2)));
            assertEquals(" ".repeat(Logo.WIDTH) + "    a fourth line", beside.get(3));
        }
    }

    /** The gradient runs from one end to the other, and a space between the letters takes no colour. */
    @Test
    void theGradientRunsFromOneEndToTheOther() {
        try (var ansi = ForcedAnsi.on()) {
            var row = Logo.forTerminal(env(Map.of("COLORTERM", "truecolor"))).getFirst();
            assertTrue(row.contains("38;2;" + Logo.FROM.r() + ";" + Logo.FROM.g() + ";" + Logo.FROM.b() + "m"), row);
            assertTrue(row.contains("38;2;" + Logo.TO.r() + ";" + Logo.TO.g() + ";" + Logo.TO.b() + "m"), row);
            assertFalse(row.contains("m \u001b[0m"), "a space is coloured: " + row.replace("\u001b", "ESC"));
        }
    }

    /** The welcome keeps the logo with colour off, drawn plain, where a command's output drops it. */
    @Test
    void theWelcomeKeepsTheLogoWithColourOff() {
        var lines = List.of("Welcome to isx.", "second", "third");
        var beside = Logo.rowsBeside(lines, env(Map.of()));
        for (int i = 0; i < 3; i++) assertEquals(Logo.ROWS[i] + "    " + lines.get(i), beside.get(i));
        assertSame(lines, Logo.beside(lines, env(Map.of())));
    }
}
