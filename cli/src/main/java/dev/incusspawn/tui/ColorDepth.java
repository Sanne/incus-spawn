package dev.incusspawn.tui;

import dev.tamboui.style.Color;

/**
 * How many colours the terminal is known to draw. tamboui writes an RGB colour as a 24-bit escape
 * whatever the terminal supports, and a terminal that does not read it draws something else
 * (#1681), so every RGB colour is fitted to the depth before it is written: {@link #fit}.
 *
 * <p>Truecolor is assumed only on proof. Without it the nearest 256-palette entry is used, which
 * every terminal isx has met draws correctly.
 */
enum ColorDepth {
    /** 24-bit colour, proven by the environment or asked for with {@code ISX_COLORS}. */
    TRUECOLOR,
    /** The 256-colour palette: the fallback, and what an SSH session without {@code COLORTERM} gets. */
    INDEXED,
    /** The sixteen ANSI colours only: {@code TERM=linux} and the like. */
    ANSI;

    /** The variable that overrides detection: {@code truecolor}, {@code 256} or {@code 16}. */
    static final String OVERRIDE = "ISX_COLORS";

    /** The depth {@code ISX_COLORS} names, or null when it names none. */
    static ColorDepth named(String value) {
        if (value == null) return null;
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "truecolor", "24bit", "rgb" -> TRUECOLOR;
            case "256" -> INDEXED;
            case "16", "ansi" -> ANSI;
            default -> null;
        };
    }

    /** Whether a blend of two colours shows as a blend: false where only sixteen colours exist. */
    boolean blends() {
        return this != ANSI;
    }

    /**
     * {@code color} as this depth can draw it: unchanged unless it is RGB and the terminal is not
     * known to draw RGB. A named or palette colour is the terminal's own and is never touched.
     */
    Color fit(Color color) {
        if (this == TRUECOLOR || !(color instanceof Color.Rgb rgb)) return color;
        return this == INDEXED ? Color.indexed(nearestIndexed(rgb.r(), rgb.g(), rgb.b()))
                : nearestAnsi(rgb.r(), rgb.g(), rgb.b());
    }

    private static final int[] CUBE = {0, 95, 135, 175, 215, 255};

    /** The 256-palette entry nearest to an RGB colour: from the 6x6x6 cube or the grey ramp, never 0-15. */
    static int nearestIndexed(int r, int g, int b) {
        int cr = cubeStep(r), cg = cubeStep(g), cb = cubeStep(b);
        int cube = 16 + 36 * cr + 6 * cg + cb;
        int cubeDistance = distance(r, g, b, CUBE[cr], CUBE[cg], CUBE[cb]);
        int step = Math.clamp(Math.round(((r + g + b) / 3f - 8) / 10f), 0, 23);
        int level = 8 + 10 * step;
        return distance(r, g, b, level, level, level) < cubeDistance ? 232 + step : cube;
    }

    private static int cubeStep(int value) {
        int best = 0;
        for (int i = 1; i < CUBE.length; i++) {
            if (Math.abs(CUBE[i] - value) < Math.abs(CUBE[best] - value)) best = i;
        }
        return best;
    }

    private static final Color[] ANSI_COLORS = {
            Color.BLACK, Color.RED, Color.GREEN, Color.YELLOW, Color.BLUE, Color.MAGENTA, Color.CYAN, Color.GRAY,
            Color.DARK_GRAY, Color.LIGHT_RED, Color.LIGHT_GREEN, Color.LIGHT_YELLOW, Color.LIGHT_BLUE,
            Color.LIGHT_MAGENTA, Color.LIGHT_CYAN, Color.WHITE};

    // xterm's defaults: a terminal's own sixteen differ, which is why this depth is the last resort
    private static final int[][] ANSI_RGB = {
            {0, 0, 0}, {205, 0, 0}, {0, 205, 0}, {205, 205, 0}, {0, 0, 238}, {205, 0, 205}, {0, 205, 205},
            {229, 229, 229}, {127, 127, 127}, {255, 0, 0}, {0, 255, 0}, {255, 255, 0}, {92, 92, 255},
            {255, 0, 255}, {0, 255, 255}, {255, 255, 255}};

    private static Color nearestAnsi(int r, int g, int b) {
        int best = 0;
        for (int i = 1; i < ANSI_RGB.length; i++) {
            if (distance(r, g, b, ANSI_RGB[i][0], ANSI_RGB[i][1], ANSI_RGB[i][2])
                    < distance(r, g, b, ANSI_RGB[best][0], ANSI_RGB[best][1], ANSI_RGB[best][2])) best = i;
        }
        return ANSI_COLORS[best];
    }

    private static int distance(int r, int g, int b, int r2, int g2, int b2) {
        return (r - r2) * (r - r2) + (g - g2) * (g - g2) + (b - b2) * (b - b2);
    }

    /** {@code from} moved {@code amount} (0 to 1) of the way to {@code to}, as RGB. */
    static Color.Rgb mix(Color from, Color to, double amount) {
        var a = from.toRgb();
        var b = to.toRgb();
        double t = Math.clamp(amount, 0, 1);
        return new Color.Rgb((int) Math.round(a.r() + (b.r() - a.r()) * t),
                (int) Math.round(a.g() + (b.g() - a.g()) * t),
                (int) Math.round(a.b() + (b.b() - a.b()) * t));
    }
}
