package dev.incusspawn.tui;

import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.tamboui.widgets.checkbox.CheckboxState;
import dev.tamboui.widgets.select.SelectState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dialogs ModalRenderer draws itself, for the TUI's error and progress states. */
class ModalRendererTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    /** The proxy's error, in the words a command's refusal uses, wrapped to the dialog (#2542). */
    @Test
    void rendersAnError() {
        var modal = new ModalRenderer(THEME);
        var message = String.join("\n", ProxyHealthCheck.explanation(ProxyHealthCheck.ProxyStatus.NOT_RUNNING));
        TuiSnapshot.assertMatches("error-modal-80x24", TuiSnapshot.render(80, 24,
                f -> modal.renderErrorModal(f, f.area(), message)));
    }

    @Test
    void rendersProgressOverTheScreen() {
        var modal = new ModalRenderer(THEME);
        TuiSnapshot.assertMatches("progress-overlay-80x24", TuiSnapshot.render(80, 24,
                f -> modal.renderProgressOverlay(f, f.area(), "Scanning the storage pool")));
    }

    /** 30 ideographs: 30 UTF-16 units, 60 cells (#1412). */
    private static final String WIDE = "日本語".repeat(10);

    @Test
    void dialogsAreAsWideAsTheCellsTheirTextTakes() {
        var modal = new ModalRenderer(THEME);
        assertShowsWhole(TuiSnapshot.render(100, 24, f -> modal.renderErrorModal(f, f.area(), WIDE)));
        assertShowsWhole(TuiSnapshot.render(100, 24, f -> modal.renderProgressOverlay(f, f.area(), WIDE)));
        // A confirm dialog widens for a word its 48 cells cannot hold.
        assertShowsWhole(TuiSnapshot.render(100, 24, f -> modal.renderConfirmModal(
                f, f.area(), " Confirm ", "Delete " + WIDE + "?", THEME.modalWarn(), "Delete", "y")));
    }

    @Test
    void aToggleNoteStartsAfterTheCellsItsLabelTakes() {
        var modal = new ModalRenderer(THEME);
        var text = TuiSnapshot.toText(TuiSnapshot.render(100, 1, f -> modal.renderToggle(
                f, f.area(), WIDE, new CheckboxState(false), false, "why")));
        assertTrue(text.replace(" ", "").contains(WIDE + "—why"), text);
    }

    @Test
    void aSelectStartsAfterTheCellsItsLabelTakes() {
        var modal = new ModalRenderer(THEME);
        var text = TuiSnapshot.toText(TuiSnapshot.render(100, 1, f -> modal.renderSelect(
                f, f.area(), WIDE, new SelectState("pick"), false)));
        assertTrue(text.replace(" ", "").contains(WIDE), text);
    }

    private static void assertShowsWhole(dev.tamboui.buffer.Buffer buffer) {
        var text = TuiSnapshot.toText(buffer);
        assertTrue(text.replace(" ", "").contains(WIDE), text);
    }
}
