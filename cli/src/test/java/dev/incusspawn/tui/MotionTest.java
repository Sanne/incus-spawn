package dev.incusspawn.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** What moves, and above all when nothing does (design/visual-language.md "Motion"). */
class MotionTest {

    private static final TuiTheme THEME = TuiTheme.of(true, null, ColorDepth.TRUECOLOR);
    private static final Rect ROW = new Rect(0, 0, 12, 1);

    private final AtomicLong now = new AtomicLong(1_000);

    private Motion motion(String setting) {
        return new Motion(THEME, now::get, setting);
    }

    private static Buffer row(String text) {
        var buffer = Buffer.empty(ROW);
        buffer.setString(0, 0, text, Style.EMPTY);
        return buffer;
    }

    private Motion started(String setting) {
        var motion = motion(setting);
        motion.start();
        motion.beginFrame();
        return motion;
    }

    @Test
    void nothingMovesUntilARunnerStartsIt() {
        var motion = motion(null);
        motion.beginFrame();
        motion.shimmer(ROW);
        motion.flash("a");
        var buffer = row("bench-native");
        motion.process(buffer);
        assertEquals(row("bench-native"), buffer, "a test's rendering holds still");
        assertEquals(Motion.SPINNER_STILL, motion.spinner());
        assertFalse(motion.moving());
        assertFalse(motion.frameDue());
    }

    @Test
    void offKeepsItStillEvenWhileARunnerDraws() {
        var motion = motion("off");
        motion.start();
        motion.beginFrame();
        motion.shimmer(ROW);
        motion.flash("a");
        var buffer = row("bench-native");
        motion.process(buffer);
        assertEquals(row("bench-native"), buffer);
        assertEquals(Motion.SPINNER_STILL, motion.spinner());
        assertFalse(motion.moving());
    }

    @Test
    void anIdleScreenAsksForNoFrames() {
        var motion = started(null);
        assertFalse(motion.moving(), "nothing under way, nothing moves");
        assertFalse(motion.frameDue());
    }

    /** An entrance, however short, read as a slower TUI: what opens is whole in its first frame. */
    @Test
    void aSessionAndADialogOpenWholeInTheirFirstFrame() {
        var motion = motion(null);
        motion.start();
        motion.beginFrame();
        var buffer = row("isx");
        motion.process(buffer);
        assertEquals(row("isx"), buffer);
        assertFalse(motion.moving());
    }

    @Test
    void theShimmerCrossesTheTextAndLeavesBlanksAlone() {
        var motion = started(null);
        motion.beginFrame();
        motion.shimmer(ROW);
        var first = row("bench native");
        motion.process(first);
        assertTrue(first.get(0, 0).style().fg().isPresent(), "text is recoloured");
        assertEquals(Cell.EMPTY.style(), first.get(5, 0).style(), "a blank is not");
        assertTrue(motion.moving());

        now.addAndGet(Motion.SHIMMER_PERIOD_MS / 3);
        motion.beginFrame();
        motion.shimmer(ROW);
        var later = row("bench native");
        motion.process(later);
        assertNotEquals(first, later, "the band has moved");

        motion.beginFrame();
        assertFalse(motion.moving(), "a frame that registers no shimmer ends it");
    }

    /** Grey to white was four steps of the grey ramp at 256 colours, and a dimmed row dimmed those. */
    @Test
    void theShimmerShowsAt256ColoursAndOnADimmedRow() {
        var theme = TuiTheme.of(true, null, ColorDepth.INDEXED);
        var motion = new Motion(theme, now::get, null);
        motion.start();
        now.set(Motion.SHIMMER_PERIOD_MS / 2);
        motion.beginFrame();
        motion.shimmer(ROW);
        var buffer = Buffer.empty(ROW);
        buffer.setString(0, 0, "bench-native", Style.EMPTY.dim());
        motion.process(buffer);
        new ColorFit(ColorDepth.INDEXED).fit(buffer);

        var peak = buffer.get(ROW.width() / 2, 0).style();
        assertEquals(theme.statusWarning(), peak.fg().orElseThrow(), "the band's middle is the caution colour");
        assertFalse(peak.effectiveModifiers().contains(dev.tamboui.style.Modifier.DIM), "and is not dimmed");
        var colours = new java.util.HashSet<Color>();
        for (int x = 0; x < ROW.width(); x++) colours.add(buffer.get(x, 0).style().fg().orElseThrow());
        assertTrue(colours.size() >= 3, "the band has a slope after fitting: " + colours);
    }

    @Test
    void aFlashFadesOnItsRowAndAsksForOneLastFrame() {
        var motion = started(null);
        motion.flash("a");
        motion.beginFrame();
        motion.row("a", ROW);
        var early = row("work-1");
        motion.process(early);
        var tint = early.get(0, 0).style().bg().orElseThrow();
        assertTrue(motion.frameDue());

        now.addAndGet(Motion.FLASH_MS / 2);
        motion.beginFrame();
        motion.row("a", ROW);
        var mid = row("work-1");
        motion.process(mid);
        assertNotEquals(tint, mid.get(0, 0).style().bg().orElseThrow(), "the tint fades");

        now.addAndGet(Motion.FLASH_MS);
        motion.beginFrame();
        motion.row("a", ROW);
        var over = row("work-1");
        motion.process(over);
        assertEquals(row("work-1"), over, "and is gone");
        assertTrue(motion.frameDue(), "one more frame draws the last tint away");
        assertFalse(motion.frameDue(), "then none");
    }

    /** Lit while the cursor keeps moving, faded once after its last key: it never flickers. */
    @Test
    void aSettleHoldsWhileKeysKeepComingAndFadesSoonerThanAFlash() {
        var motion = started(null);
        motion.settle("title");
        motion.beginFrame();
        motion.row("title", ROW);
        var first = row("work-1");
        motion.process(first);
        var lit = first.get(0, 0).style().bg().orElseThrow();

        // A held key: each move starts the fade again, so every frame is as lit as the first
        for (int key = 0; key < 5; key++) {
            now.addAndGet(Motion.SETTLE_MS / 2);
            motion.settle("title");
            motion.beginFrame();
            motion.row("title", ROW);
            var held = row("work-1");
            motion.process(held);
            assertEquals(lit, held.get(0, 0).style().bg().orElseThrow(), "held at its first strength");
        }

        now.addAndGet(Motion.SETTLE_MS);
        motion.beginFrame();
        motion.row("title", ROW);
        var over = row("work-1");
        motion.process(over);
        assertEquals(row("work-1"), over, "gone a settle's time after the last key");
        assertTrue(Motion.SETTLE_MS < Motion.FLASH_MS && Motion.SETTLE_STRENGTH < Motion.FLASH_STRENGTH,
                "quieter than what isx saw change: the person caused it");
    }

    @Test
    void aFlashForARowNotOnScreenDrawsNothing() {
        var motion = started(null);
        motion.flash("scrolled-away");
        motion.beginFrame();
        var buffer = row("work-1");
        motion.process(buffer);
        assertEquals(row("work-1"), buffer);
    }

    @Test
    void theSpinnerTurnsWhileShownAndStopsWhenNot() {
        var motion = started(null);
        motion.beginFrame();
        var first = motion.spinner();
        assertNotEquals(Motion.SPINNER_STILL, first);
        assertTrue(motion.moving());
        now.addAndGet(Motion.SPINNER_FRAME_MS);
        assertNotEquals(first, motion.spinner());
        motion.beginFrame();
        assertFalse(motion.moving());
    }

    @Test
    void sixteenColoursGetTheSpinnerAlone() {
        var motion = new Motion(TuiTheme.of(true, null, ColorDepth.ANSI), now::get, null);
        motion.start();
        motion.beginFrame();
        motion.shimmer(ROW);
        motion.flash("a");
        motion.row("a", ROW);
        var buffer = row("work-1");
        motion.process(buffer);
        assertEquals(row("work-1"), buffer, "nothing blends there");
        assertNotEquals(Motion.SPINNER_STILL, motion.spinner());
    }
}
