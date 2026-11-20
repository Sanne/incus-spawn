package dev.incusspawn.tui;

import org.aesh.terminal.detect.TerminalCapabilities;
import org.aesh.terminal.detect.TerminalTheme;

/** Reads what the theme is made from: dark or light, the terminal's background and its colour depth. */
public final class TerminalThemeDetector {

    private TerminalThemeDetector() {
    }

    public static TuiTheme detect() {
        TerminalCapabilities caps = null;
        try {
            caps = TerminalCapabilities.detect();
        } catch (Exception ignored) {
            // No terminal to ask: the defaults below
        }
        return TuiTheme.of(isDark(caps), background(caps), depth(caps, System.getenv(ColorDepth.OVERRIDE)));
    }

    static boolean isDark() {
        try {
            return isDark(TerminalCapabilities.detect());
        } catch (Exception ignored) {
            return isDark(null);
        }
    }

    private static boolean isDark(TerminalCapabilities caps) {
        var isxTheme = System.getenv("ISX_THEME");
        if (isxTheme != null && !isxTheme.isBlank()) {
            return !"light".equalsIgnoreCase(isxTheme.strip());
        }
        try {
            var theme = caps == null ? null : caps.theme();
            if (theme == TerminalTheme.LIGHT) return false;
            if (theme == TerminalTheme.DARK) return true;
        } catch (Exception ignored) {
        }
        return true;
    }

    /** The terminal's own background, or null when it did not answer. */
    private static int[] background(TerminalCapabilities caps) {
        try {
            return caps == null ? null : caps.backgroundRGB();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * The depth to draw at: what {@code ISX_COLORS} names, else truecolor only where the terminal
     * is known to draw it, else the 256-colour palette. SSH does not pass {@code COLORTERM} on, so
     * most remote sessions get the palette, which is the safe answer (#1681).
     */
    static ColorDepth depth(TerminalCapabilities caps, String override) {
        var named = ColorDepth.named(override);
        if (named != null) return named;
        try {
            if (caps != null && caps.supportsTrueColor()) return ColorDepth.TRUECOLOR;
            if (caps != null && !caps.supports256Colors()) return ColorDepth.ANSI;
        } catch (Exception ignored) {
        }
        return ColorDepth.INDEXED;
    }
}
