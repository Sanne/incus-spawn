package dev.incusspawn.tui;

import org.aesh.terminal.detect.TerminalCapabilities;
import org.aesh.terminal.detect.TerminalTheme;

public final class TerminalThemeDetector {

    private TerminalThemeDetector() {
    }

    public static TuiTheme detect() {
        return isDark() ? TuiTheme.dark() : TuiTheme.light();
    }

    static boolean isDark() {
        var isxTheme = System.getenv("ISX_THEME");
        if (isxTheme != null && !isxTheme.isBlank()) {
            return !"light".equalsIgnoreCase(isxTheme.strip());
        }
        try {
            var caps = TerminalCapabilities.detect();
            var theme = caps.theme();
            if (theme == TerminalTheme.LIGHT) return false;
            if (theme == TerminalTheme.DARK) return true;
        } catch (Exception ignored) {
        }
        return true;
    }
}
