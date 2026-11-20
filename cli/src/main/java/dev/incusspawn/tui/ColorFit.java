package dev.incusspawn.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.PostRenderProcessor;

import java.util.HashMap;
import java.util.Map;

/**
 * The last step of every frame: fits each RGB colour left in it to the terminal's
 * {@link ColorDepth}. The theme is fitted when it is made, so what this catches is a blend drawn
 * while rendering ({@link Motion}, a gradient). With it in the runner, no 24-bit escape reaches a
 * terminal that has not proven it reads one (#1681), whatever a widget draws.
 */
final class ColorFit implements PostRenderProcessor {
    private static final int CACHE_LIMIT = 4096;

    private final ColorDepth depth;
    private final Map<Style, Style> fitted = new HashMap<>();

    ColorFit(ColorDepth depth) {
        this.depth = depth;
    }

    @Override
    public void process(Frame frame) {
        fit(frame.buffer());
    }

    void fit(Buffer buffer) {
        if (depth == ColorDepth.TRUECOLOR) return;
        if (fitted.size() > CACHE_LIMIT) fitted.clear();
        var area = buffer.area();
        for (int y = area.y(); y < area.bottom(); y++) {
            for (int x = area.x(); x < area.right(); x++) {
                var cell = buffer.get(x, y);
                var style = cell.style();
                if (!hasRgb(style)) continue;
                buffer.set(x, y, cell.style(fitted.computeIfAbsent(style, this::fit)));
            }
        }
    }

    private static boolean hasRgb(Style style) {
        return style.fg().orElse(null) instanceof Color.Rgb || style.bg().orElse(null) instanceof Color.Rgb
                || style.underlineColor().orElse(null) instanceof Color.Rgb;
    }

    private Style fit(Style style) {
        var result = style;
        if (style.fg().orElse(null) instanceof Color.Rgb fg) result = result.fg(depth.fit(fg));
        if (style.bg().orElse(null) instanceof Color.Rgb bg) result = result.bg(depth.fit(bg));
        if (style.underlineColor().orElse(null) instanceof Color.Rgb line) result = result.underlineColor(depth.fit(line));
        return result;
    }
}
