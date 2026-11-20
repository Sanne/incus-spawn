package dev.incusspawn.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TuiThemeTest {

    private static List<TuiTheme> themes(ColorDepth depth) {
        return List.of(TuiTheme.of(true, null, depth), TuiTheme.of(false, null, depth),
                TuiTheme.of(true, new int[]{0, 0, 0}, depth), TuiTheme.of(false, new int[]{253, 246, 227}, depth));
    }

    /** Termius draws every RGB colour in one colour (#1681): none is drawn unless the terminal proved it reads them. */
    @Test
    void aThemeHoldsNoRgbColourBelowTruecolor() throws Exception {
        for (var depth : List.of(ColorDepth.INDEXED, ColorDepth.ANSI)) {
            for (var theme : themes(depth)) {
                for (var component : TuiTheme.class.getRecordComponents()) {
                    // The canvas is blended from and never drawn
                    if (component.getName().equals("canvas")) continue;
                    var colour = component.getAccessor().invoke(theme);
                    assertFalse(colour instanceof Color.Rgb, depth + " " + component.getName() + " is " + colour);
                }
            }
        }
    }

    @Test
    void theDefaultThemesAreThePaletteOnes() {
        assertEquals(ColorDepth.INDEXED, TuiTheme.dark().depth());
        assertEquals(ColorDepth.INDEXED, TuiTheme.light().depth());
        assertTrue(TuiTheme.dark().darkTerminal());
        assertFalse(TuiTheme.light().darkTerminal());
    }

    /** Fitting to 256 colours must not merge two surfaces that sit on each other. */
    @Test
    void surfacesStayApartInThePalette() {
        for (var theme : themes(ColorDepth.INDEXED)) {
            var canvas = ColorDepth.INDEXED.fit(theme.canvas());
            assertNotEquals(canvas, theme.modalBg(), "a dialog on the screen");
            assertNotEquals(theme.modalBg(), theme.inputBg(), "an input on a dialog");
            assertNotEquals(theme.modalBg(), theme.inputInactiveBg(), "an inactive input on a dialog");
            assertNotEquals(theme.inputBg(), theme.inputInactiveBg(), "the two inputs");
            assertNotEquals(canvas, theme.highlightBg(), "the selected row on the screen");
            assertNotEquals(theme.textDim(), theme.textFaint(), "the two quiet texts");
        }
    }

    @Test
    void lightThemeHasDifferentPalette() {
        var light = TuiTheme.light();
        var dark = TuiTheme.dark();

        assertNotEquals(dark.modalBg(), light.modalBg());
        assertNotEquals(dark.modalFg(), light.modalFg());
        assertNotEquals(dark.accent(), light.accent());
        assertNotEquals(dark.highlightBg(), light.highlightBg());
        assertNotEquals(dark.inputBg(), light.inputBg());
    }

    @Test
    void anRgbColourIsFittedToTheNearestPaletteEntry() {
        assertEquals(Color.indexed(16), ColorDepth.INDEXED.fit(Color.rgb(0, 0, 0)));
        assertEquals(Color.indexed(231), ColorDepth.INDEXED.fit(Color.rgb(255, 255, 255)));
        assertEquals(Color.indexed(196), ColorDepth.INDEXED.fit(Color.rgb(250, 5, 5)));
        // A grey is nearer the grey ramp than the cube
        assertEquals(Color.indexed(235), ColorDepth.INDEXED.fit(Color.rgb(38, 38, 38)));
        // Truecolor keeps it, and a colour that is the terminal's own is never touched
        assertEquals(Color.rgb(1, 2, 3), ColorDepth.TRUECOLOR.fit(Color.rgb(1, 2, 3)));
        assertSame(Color.CYAN, ColorDepth.INDEXED.fit(Color.CYAN));
        assertEquals(Color.indexed(60), ColorDepth.ANSI.fit(Color.indexed(60)));
    }

    @Test
    void theOverrideNamesADepth() {
        assertEquals(ColorDepth.TRUECOLOR, ColorDepth.named("truecolor"));
        assertEquals(ColorDepth.INDEXED, ColorDepth.named(" 256 "));
        assertEquals(ColorDepth.ANSI, ColorDepth.named("16"));
        assertNull(ColorDepth.named("many"));
        assertNull(ColorDepth.named(null));
    }

    /** The frame's last step: whatever a blend drew, no RGB colour is left for the backend to write. */
    @Test
    void theLastStepOfAFrameLeavesNoRgbColour() {
        var buffer = Buffer.empty(new Rect(0, 0, 3, 1));
        buffer.set(0, 0, new Cell("a", Style.EMPTY.fg(Color.rgb(10, 200, 30)).bg(Color.rgb(1, 1, 1))));
        buffer.set(1, 0, new Cell("b", Style.EMPTY.underlined().underlineColor(Color.rgb(9, 9, 200))));
        buffer.set(2, 0, new Cell("c", Style.EMPTY.fg(Color.GREEN)));
        new ColorFit(ColorDepth.INDEXED).fit(buffer);
        for (int x = 0; x < 3; x++) {
            var style = buffer.get(x, 0).style();
            assertFalse(style.fg().orElse(null) instanceof Color.Rgb, "fg of cell " + x);
            assertFalse(style.bg().orElse(null) instanceof Color.Rgb, "bg of cell " + x);
            assertFalse(style.underlineColor().orElse(null) instanceof Color.Rgb, "underline of cell " + x);
        }
        assertEquals(Color.GREEN, buffer.get(2, 0).style().fg().orElseThrow());

        var kept = Buffer.empty(new Rect(0, 0, 1, 1));
        kept.set(0, 0, new Cell("a", Style.EMPTY.fg(Color.rgb(10, 200, 30))));
        new ColorFit(ColorDepth.TRUECOLOR).fit(kept);
        assertEquals(Color.rgb(10, 200, 30), kept.get(0, 0).style().fg().orElseThrow());
    }

    @Test
    void detectReturnsNonNull() {
        assertNotNull(TerminalThemeDetector.detect());
    }

    @Test
    void isDarkDefaultsWithoutTerminal() {
        // In a test environment (no real terminal), should not throw
        // and should return a definite answer (defaults to dark)
        assertTrue(TerminalThemeDetector.isDark());
    }
}
