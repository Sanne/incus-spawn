package dev.incusspawn.tui;

import dev.tamboui.style.Color;

/**
 * The TUI's colours, by the role each plays (design/visual-language.md "Colour"). A theme is made
 * for one terminal: every colour is fitted to the depth that terminal is known to draw
 * ({@link ColorDepth#fit}), so no RGB escape reaches a terminal that has not proven it reads one
 * (#1681), and its surfaces are tinted from the terminal's own background when that is known.
 */
public record TuiTheme(
        // Panel chrome
        Color panelBorderFocused,
        Color panelBorderUnfocused,
        Color highlightBg,
        Color highlightFg,
        Color scrollbarTrack,

        // Text
        Color textDim,
        Color textFaint,

        // Brand
        Color accent,
        Color accentAlt,
        Color onAccent,

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
        Color focusedLabel,

        // What the theme was made for: the terminal's background as far as it is known, for
        // blending only and never drawn, then dark or light and the colour depth
        Color canvas,
        boolean darkTerminal,
        ColorDepth depth
) {

    /** The dark theme for a terminal of unknown background that draws the 256-colour palette. */
    public static TuiTheme dark() {
        return of(true, null, ColorDepth.INDEXED);
    }

    /** The light theme for a terminal of unknown background that draws the 256-colour palette. */
    public static TuiTheme light() {
        return of(false, null, ColorDepth.INDEXED);
    }

    /** The background assumed when the terminal does not say its own. */
    static final Color.Rgb DARK_BACKGROUND = new Color.Rgb(0x14, 0x16, 0x1b);
    static final Color.Rgb LIGHT_BACKGROUND = new Color.Rgb(0xff, 0xff, 0xff);

    /**
     * The theme for a terminal: dark or light, on {@code background} (its own, as RGB, or null when
     * it did not say), every colour fitted to {@code depth}.
     */
    static TuiTheme of(boolean dark, int[] background, ColorDepth depth) {
        Color.Rgb base = background != null && background.length == 3
                ? new Color.Rgb(clamp(background[0]), clamp(background[1]), clamp(background[2]))
                : dark ? DARK_BACKGROUND : LIGHT_BACKGROUND;
        // Surfaces are the terminal's background moved towards its opposite: a raised one a little,
        // an input more, so each stays apart from the one it sits on after fitting to 256 colours
        Color lift = dark ? Color.rgb(255, 255, 255) : Color.rgb(0, 0, 0);
        Color accent = dark ? Color.hex("#5fd7e6") : Color.hex("#0e7490");
        Color accentAlt = dark ? Color.hex("#a78bfa") : Color.hex("#7c3aed");
        Color ok = dark ? Color.hex("#8bd5a0") : Color.hex("#15803d");
        Color warn = dark ? Color.hex("#f2c97d") : Color.hex("#b45309");
        Color danger = dark ? Color.hex("#f28b82") : Color.hex("#b91c1c");
        Color text = dark ? Color.hex("#e6e9ef") : Color.hex("#1f2328");
        Color muted = dark ? Color.hex("#9aa3b2") : Color.hex("#57606a");
        Color faint = dark ? Color.hex("#626b7a") : Color.hex("#8c959f");
        Color border = ColorDepth.mix(base, lift, dark ? 0.22 : 0.20);
        Color raised = ColorDepth.mix(base, lift, dark ? 0.08 : 0.05);
        Color inputInactive = ColorDepth.mix(base, lift, dark ? 0.13 : 0.09);
        Color input = ColorDepth.mix(base, lift, dark ? 0.20 : 0.14);
        Color selection = ColorDepth.mix(base, accent, dark ? 0.24 : 0.18);
        Color onAccent = dark ? Color.hex("#0b1014") : Color.hex("#ffffff");
        if (depth == ColorDepth.ANSI) {
            // Sixteen colours hold no tint: a surface is the terminal's own background, told apart
            // by its border, and the selection is the one filled row
            raised = Color.RESET;
            input = dark ? Color.DARK_GRAY : Color.GRAY;
            inputInactive = Color.RESET;
            selection = dark ? Color.DARK_GRAY : Color.GRAY;
            border = dark ? Color.DARK_GRAY : Color.GRAY;
            onAccent = dark ? Color.BLACK : Color.WHITE;
        }
        return new TuiTheme(
                // Panel chrome
                depth.fit(accent),
                depth.fit(border),
                depth.fit(selection),
                depth.fit(text),
                depth.fit(border),

                // Text
                depth.fit(muted),
                depth.fit(faint),

                // Brand
                depth.fit(accent),
                depth.fit(accentAlt),
                depth.fit(onAccent),

                // Context / status bar: neither is a band
                Color.RESET,
                depth.fit(text),
                depth.fit(muted),
                depth.fit(accent),
                Color.RESET,
                depth.fit(danger),

                // Modal
                depth.fit(raised),
                depth.fit(text),
                depth.fit(ColorDepth.mix(base, accent, 0.55)),
                depth.fit(accent),
                depth.fit(danger),
                depth.fit(input),
                depth.fit(inputInactive),
                depth.fit(faint),

                // Function key bar
                Color.RESET,
                depth.fit(accent),
                depth.fit(muted),
                depth.fit(faint),
                depth.fit(border),

                // Semantic
                depth.fit(ok),
                depth.fit(faint),
                depth.fit(warn),
                depth.fit(ok),
                depth.fit(danger),
                depth.fit(ok),
                depth.fit(faint),
                depth.fit(text),

                base,
                dark,
                depth);
    }

    private static int clamp(int channel) {
        return Math.clamp(channel, 0, 255);
    }
}
