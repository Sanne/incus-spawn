package dev.incusspawn.tui;

import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.ForcedAnsi;
import org.junit.jupiter.api.Test;

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
            for (var values : java.util.List.of(Map.of("COLORTERM", "truecolor"), Map.of("TERM", "xterm-256color"),
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

    /** The gradient runs from one end to the other, and a space between the letters takes no colour. */
    @Test
    void theGradientRunsFromOneEndToTheOther() {
        try (var ansi = ForcedAnsi.on()) {
            var row = Logo.forTerminal(env(Map.of("COLORTERM", "truecolor"))).getFirst();
            assertTrue(row.contains("38;2;" + Logo.FROM[0] + ";" + Logo.FROM[1] + ";" + Logo.FROM[2] + "m"), row);
            assertTrue(row.contains("38;2;" + Logo.TO[0] + ";" + Logo.TO[1] + ";" + Logo.TO[2] + "m"), row);
            assertFalse(row.contains("m \u001b[0m"), "a space is coloured: " + row.replace("\u001b", "ESC"));
        }
    }
}
