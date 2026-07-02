package dev.incusspawn.tui;

import dev.tamboui.style.Color;

public record TuiTheme(
        // Panel chrome
        Color panelBorderFocused,
        Color panelBorderUnfocused,
        Color highlightBg,
        Color highlightFg,
        Color scrollbarTrack,

        // Text
        Color textDim,

        // Context / status bar
        Color contextBg,
        Color contextPrimaryFg,
        Color contextSecondaryFg,
        Color contextAccentFg,
        Color statusBarBg,
        Color statusBarErrorFg,

        // Modal
        Color modalBg,
        Color modalFg,
        Color modalBorder,
        Color modalAccent,
        Color modalWarn,
        Color inputBg,
        Color inputInactiveBg,
        Color placeholderFg,
        Color modalShadow,

        // Function key bar
        Color barBg,
        Color barKeyFg,
        Color barLabelFg,
        Color barDisabledFg,
        Color barSeparatorFg,

        // Semantic
        Color statusRunning,
        Color statusStopped,
        Color statusWarning,
        Color statusSuccess,
        Color statusFailure,
        Color checkEnabled,
        Color checkDisabled,
        Color focusedLabel
) {

    public static TuiTheme dark() {
        return new TuiTheme(
                // Panel chrome
                Color.CYAN,
                Color.DARK_GRAY,
                Color.DARK_GRAY,
                Color.WHITE,
                Color.rgb(60, 62, 84),

                // Text
                Color.GRAY,

                // Context / status bar
                Color.rgb(30, 30, 50),
                Color.WHITE,
                Color.GRAY,
                Color.LIGHT_CYAN,
                Color.rgb(0, 0, 80),
                Color.LIGHT_RED,

                // Modal
                Color.rgb(55, 60, 95),
                Color.rgb(210, 218, 248),
                Color.CYAN,
                Color.LIGHT_CYAN,
                Color.LIGHT_RED,
                Color.rgb(70, 74, 110),
                Color.rgb(48, 52, 78),
                Color.rgb(90, 94, 120),
                Color.rgb(8, 8, 16),

                // Function key bar
                Color.CYAN,
                Color.WHITE,
                Color.BLACK,
                Color.rgb(0, 100, 110),
                Color.rgb(0, 140, 150),

                // Semantic
                Color.GREEN,
                Color.GRAY,
                Color.YELLOW,
                Color.GREEN,
                Color.RED,
                Color.GREEN,
                Color.GRAY,
                Color.WHITE
        );
    }

    public static TuiTheme light() {
        return new TuiTheme(
                // Panel chrome
                Color.BLUE,
                Color.GRAY,
                Color.rgb(180, 190, 220),
                Color.BLACK,
                Color.rgb(180, 185, 200),

                // Text
                Color.DARK_GRAY,

                // Context / status bar
                Color.rgb(210, 215, 230),
                Color.BLACK,
                Color.DARK_GRAY,
                Color.BLUE,
                Color.rgb(200, 210, 240),
                Color.RED,

                // Modal
                Color.rgb(235, 235, 240),
                Color.rgb(30, 30, 40),
                Color.BLUE,
                Color.BLUE,
                Color.RED,
                Color.rgb(220, 222, 230),
                Color.rgb(240, 240, 245),
                Color.rgb(150, 155, 170),
                Color.rgb(180, 180, 190),

                // Function key bar
                Color.BLUE,
                Color.WHITE,
                Color.WHITE,
                Color.rgb(130, 150, 200),
                Color.rgb(100, 130, 190),

                // Semantic
                Color.GREEN,
                Color.DARK_GRAY,
                Color.YELLOW,
                Color.GREEN,
                Color.RED,
                Color.GREEN,
                Color.DARK_GRAY,
                Color.BLACK
        );
    }
}
