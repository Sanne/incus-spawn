package dev.incusspawn.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The TUI's motion (design/visual-language.md "Motion"): a shimmer over text that names work under
 * way, a spinner beside it, and a flash on a row whose state changed. Nothing moves in answer to
 * a key: what a key opens is drawn whole in its first frame, since an entrance, however short,
 * reads as a slower TUI.
 *
 * <p>Motion is drawn over a frame that is already complete, so it never delays what the frame
 * shows or a key. It asks for frames only while something moves ({@link #pump}): an idle TUI draws
 * nothing. It is off until a runner starts it, so a test's rendering holds still, and
 * {@code ISX_MOTION=off} keeps it off.
 */
final class Motion {

    /** The variable that turns motion off ({@code off}). */
    static final String SWITCH = "ISX_MOTION";

    /** The event that asks for one more frame while something moves. */
    enum FrameEvent implements Event { NEXT }

    static final long FRAME_MS = 40;
    static final long FLASH_MS = 900;
    static final long SHIMMER_PERIOD_MS = 1800;
    static final int SHIMMER_BAND = 8;
    /** How lit a cell is before it stops being dimmed with the rest of its row. */
    static final double SHIMMER_UNDIM = 0.1;
    static final long SPINNER_FRAME_MS = 80;

    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    /** What stands where the spinner would turn, while motion is off. */
    static final String SPINNER_STILL = "◐";

    private final TuiTheme theme;
    private final LongSupplier millis;
    private final boolean allowed;
    private boolean running;

    private final List<Rect> shimmers = new ArrayList<>();
    private final Map<String, Rect> rows = new HashMap<>();
    private final Map<String, Long> flashes = new HashMap<>();
    private boolean spinnerShown;

    private ScheduledFuture<?> driver;
    private boolean movedLastFrame;

    Motion(TuiTheme theme) {
        this(theme, () -> System.nanoTime() / 1_000_000, System.getenv(SWITCH));
    }

    Motion(TuiTheme theme, LongSupplier millis, String setting) {
        this.theme = theme;
        this.millis = millis;
        var value = setting == null ? "" : setting.strip().toLowerCase(java.util.Locale.ROOT);
        this.allowed = !value.equals("off") && !value.equals("0") && !value.equals("false");
    }

    /** Whether anything moves: true only between {@link #start} and {@link #stop}, and never with {@code ISX_MOTION=off}. */
    boolean on() {
        return allowed && running;
    }

    /** A runner is about to draw: motion may run. */
    void start() {
        running = true;
    }

    /** The runner is gone: nothing asks for frames any more. */
    void stop() {
        running = false;
        if (driver != null) driver.cancel(false);
        driver = null;
        flashes.clear();
        movedLastFrame = false;
    }

    // --- What a frame registers while it is drawn ---

    /** A frame starts: what the last one registered is forgotten. */
    void beginFrame() {
        shimmers.clear();
        rows.clear();
        spinnerShown = false;
    }

    /** The text in {@code area} names work under way: a band of light crosses it. */
    void shimmer(Rect area) {
        if (on() && theme.depth().blends() && area.width() > 0 && area.height() > 0) shimmers.add(area);
    }

    /** The spinner's glyph for this frame, one cell wide; a still glyph while motion is off. */
    String spinner() {
        if (!on()) return SPINNER_STILL;
        spinnerShown = true;
        return SPINNER[(int) (millis.getAsLong() / SPINNER_FRAME_MS % SPINNER.length)];
    }

    /** Where the row named {@code key} is drawn in this frame, for a flash that follows it. */
    void row(String key, Rect area) {
        rows.put(key, area);
    }

    /** The row named {@code key} changed state: it is tinted, and the tint fades. */
    void flash(String key) {
        if (on() && theme.depth().blends()) flashes.put(key, millis.getAsLong());
    }

    // --- Drawing over the finished frame ---

    /** Draws what moves over the frame, which is complete without it. */
    void process(Buffer buffer) {
        if (!on()) return;
        long now = millis.getAsLong();
        for (var area : shimmers) drawShimmer(buffer, area, now);
        flashes.values().removeIf(start -> now - start >= FLASH_MS);
        for (var flash : flashes.entrySet()) {
            var area = rows.get(flash.getKey());
            if (area != null) drawFlash(buffer, area, (now - flash.getValue()) / (double) FLASH_MS);
        }
    }

    private void drawShimmer(Buffer buffer, Rect area, long now) {
        var clip = area.intersection(buffer.area());
        double phase = (now % SHIMMER_PERIOD_MS) / (double) SHIMMER_PERIOD_MS;
        double head = clip.x() - SHIMMER_BAND + phase * (clip.width() + 2 * SHIMMER_BAND);
        for (int y = clip.y(); y < clip.bottom(); y++) {
            for (int x = clip.x(); x < clip.right(); x++) {
                var cell = buffer.get(x, y);
                if (cell.isContinuation() || cell.symbol().isBlank()) continue;
                double near = Math.max(0, 1 - Math.abs(x - head) / SHIMMER_BAND);
                // Smooth at both ends of the band, so it has no edge
                double light = near * near * (3 - 2 * near);
                // Towards the caution colour, which already says "under way" beside it: a change
                // of hue survives fitting to 256 colours, where grey to white is four steps. A
                // dimmed row (stopping, restarting) is undimmed under the band, or it dims the band
                var lit = Style.EMPTY.fg(ColorDepth.mix(theme.textDim(), theme.statusWarning(), light));
                buffer.set(x, y, cell.patchStyle(light > SHIMMER_UNDIM ? lit.notDim() : lit));
            }
        }
    }

    private void drawFlash(Buffer buffer, Rect area, double progress) {
        var clip = area.intersection(buffer.area());
        double strength = 0.38 * (1 - progress) * (1 - progress);
        for (int y = clip.y(); y < clip.bottom(); y++) {
            for (int x = clip.x(); x < clip.right(); x++) {
                var cell = buffer.get(x, y);
                var under = cell.style().bg().filter(c -> !(c instanceof Color.Reset)).orElse(canvas());
                buffer.set(x, y, cell.patchStyle(Style.EMPTY.bg(ColorDepth.mix(under, theme.accent(), strength))));
            }
        }
    }

    /** The terminal's background as far as the theme knows it: what a tint is blended over. */
    private Color canvas() {
        return theme.canvas();
    }

    // --- Asking for frames ---

    /** Whether the last frame drew anything that moves, so another is owed. */
    boolean moving() {
        if (!on()) return false;
        return !shimmers.isEmpty() || spinnerShown || !flashes.isEmpty();
    }

    /**
     * Starts or stops the frames that motion needs, after an event was handled: they run only while
     * something moves, at {@link #FRAME_MS}, and stop with it.
     */
    void pump(TuiRunner runner) {
        if (!on()) return;
        if (moving() && driver == null) {
            driver = runner.scheduler().scheduleAtFixedRate(() -> runner.dispatch(FrameEvent.NEXT),
                    FRAME_MS, FRAME_MS, TimeUnit.MILLISECONDS);
        } else if (!moving() && !movedLastFrame && driver != null) {
            driver.cancel(false);
            driver = null;
        }
    }

    /**
     * Whether a frame event draws: while something moves, and once more after it stopped, so the
     * last tint is drawn away.
     */
    boolean frameDue() {
        boolean due = moving() || movedLastFrame;
        movedLastFrame = moving();
        return due;
    }
}
