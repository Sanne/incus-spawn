package dev.incusspawn.tui;

import dev.tamboui.style.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TuiThemeTest {

    @Test
    void darkThemeMatchesOriginalHardcodedColors() {
        var dark = TuiTheme.dark();

        // Panel chrome
        assertSame(Color.CYAN, dark.panelBorderFocused());
        assertSame(Color.DARK_GRAY, dark.panelBorderUnfocused());
        assertSame(Color.DARK_GRAY, dark.highlightBg());
        assertSame(Color.WHITE, dark.highlightFg());
        assertEquals(Color.rgb(60, 62, 84), dark.scrollbarTrack());

        // Text
        assertSame(Color.GRAY, dark.textDim());

        // Context / status bar
        assertEquals(Color.rgb(30, 30, 50), dark.contextBg());
        assertSame(Color.WHITE, dark.contextPrimaryFg());
        assertSame(Color.GRAY, dark.contextSecondaryFg());
        assertSame(Color.LIGHT_CYAN, dark.contextAccentFg());
        assertEquals(Color.rgb(0, 0, 80), dark.statusBarBg());
        assertSame(Color.LIGHT_RED, dark.statusBarErrorFg());

        // Modal
        assertEquals(Color.rgb(55, 60, 95), dark.modalBg());
        assertEquals(Color.rgb(210, 218, 248), dark.modalFg());
        assertSame(Color.CYAN, dark.modalBorder());
        assertSame(Color.LIGHT_CYAN, dark.modalAccent());
        assertSame(Color.LIGHT_RED, dark.modalWarn());
        assertEquals(Color.rgb(70, 74, 110), dark.inputBg());
        assertEquals(Color.rgb(48, 52, 78), dark.inputInactiveBg());
        assertEquals(Color.rgb(90, 94, 120), dark.placeholderFg());
        assertEquals(Color.rgb(8, 8, 16), dark.modalShadow());

        // Function key bar
        assertSame(Color.CYAN, dark.barBg());
        assertSame(Color.WHITE, dark.barKeyFg());
        assertSame(Color.BLACK, dark.barLabelFg());
        assertEquals(Color.rgb(0, 100, 110), dark.barDisabledFg());
        assertEquals(Color.rgb(0, 140, 150), dark.barSeparatorFg());

        // Semantic
        assertSame(Color.GREEN, dark.statusRunning());
        assertSame(Color.GRAY, dark.statusStopped());
        assertSame(Color.YELLOW, dark.statusWarning());
        assertSame(Color.GREEN, dark.statusSuccess());
        assertSame(Color.RED, dark.statusFailure());
        assertSame(Color.GREEN, dark.checkEnabled());
        assertSame(Color.GRAY, dark.checkDisabled());
        assertSame(Color.WHITE, dark.focusedLabel());
    }

    @Test
    void lightThemeHasDifferentPalette() {
        var light = TuiTheme.light();
        var dark = TuiTheme.dark();

        assertNotEquals(dark.modalBg(), light.modalBg());
        assertNotEquals(dark.modalFg(), light.modalFg());
        assertNotEquals(dark.contextBg(), light.contextBg());
        assertNotEquals(dark.highlightBg(), light.highlightBg());
        assertNotEquals(dark.inputBg(), light.inputBg());
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
