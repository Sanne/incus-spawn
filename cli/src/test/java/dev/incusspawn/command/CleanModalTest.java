package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.tui.TuiSnapshot;
import dev.incusspawn.tui.TuiTheme;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The pool-cleanup dialog on its own: which keys it keeps, and which it hands back to the TUI. */
class CleanModalTest {

    private static final TuiTheme THEME = TuiTheme.dark();
    private static final long GIB = 1L << 30;

    /** A scan with a failed build and an unused image; 10% of the pool used, below the resize tip. */
    private static CleanModal dialog() {
        return dialog(new CleanCommand.CleanScan("default", new IncusClient.PoolUsage(10 * GIB, 100 * GIB),
                List.of("tpl-broken"), List.of(new IncusClient.ImageInfo("0123456789abcdef", 2 * GIB, List.of())),
                List.of(), false));
    }

    private static CleanModal dialog(CleanCommand.CleanScan scan) {
        return new CleanModal(new ModalRenderer(THEME), THEME, scan);
    }

    private static CleanModal.Outcome press(CleanModal dialog, KeyEvent... keys) {
        var outcome = CleanModal.Outcome.HANDLED;
        for (var key : keys) outcome = dialog.handleKey(key);
        return outcome;
    }

    @Test
    void escapeCloses() {
        assertEquals(CleanModal.Outcome.CLOSE, press(dialog(), KeyEvent.ofKey(KeyCode.ESCAPE)));
    }

    @Test
    void enterWithSomethingCheckedIsTheTuisToClean() {
        // Failed builds and unused images start checked.
        assertEquals(CleanModal.Outcome.CLEAN, press(dialog(), KeyEvent.ofKey(KeyCode.ENTER)));
    }

    @Test
    void enterWithNothingCheckedCloses() {
        // Space unchecks failed builds, Down moves to unused images, Space unchecks them.
        assertEquals(CleanModal.Outcome.CLOSE, press(dialog(), KeyEvent.ofChar(' '), KeyEvent.ofKey(KeyCode.DOWN),
                KeyEvent.ofChar(' '), KeyEvent.ofKey(KeyCode.ENTER)));
    }

    @Test
    void aCleanPoolOnlyCloses() {
        var dialog = dialog(new CleanCommand.CleanScan("default", null, List.of(), List.of(), List.of(), false));
        assertEquals(CleanModal.Outcome.HANDLED, press(dialog, KeyEvent.ofChar(' ')));
        assertEquals(CleanModal.Outcome.CLOSE, press(dialog, KeyEvent.ofKey(KeyCode.ENTER)));
    }

    @Test
    void rendersTheConfirmDialog() {
        var dialog = dialog();
        TuiSnapshot.assertMatches("clean-modal-confirm-80x24",
                TuiSnapshot.render(80, 24, f -> dialog.renderCleanConfirmModal(f, f.area())));
    }
}
