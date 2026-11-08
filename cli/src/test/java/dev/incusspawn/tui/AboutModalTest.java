package dev.incusspawn.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The F1 dialog on its own, with fixed versions in place of this build's and the host's. */
class AboutModalTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    private static AboutModal dialog() {
        return dialog(false);
    }

    private static AboutModal dialog(boolean macOS) {
        return new AboutModal(new ModalRenderer(THEME), THEME, new AboutModal.Source() {
            @Override public String version() { return "1.2.3"; }
            @Override public String gitSha() { return "0000000"; }
            @Override public String incusClient() { return "REST API"; }
            @Override public String incusServer() { return "6.0"; }
            @Override public String runtime() { return "Java 25"; }
            @Override public String kernelInfo() { return "Linux 6.1"; }
            @Override public boolean isMacOS() { return macOS; }
        });
    }

    @Test
    void itKeepsItsScrollKeysAndHandsBackClosingAndAiHelp() {
        var dialog = dialog();
        assertEquals(AboutModal.Outcome.HANDLED, dialog.handleKey(KeyEvent.ofKey(KeyCode.DOWN)));
        assertEquals(AboutModal.Outcome.HANDLED, dialog.handleKey(KeyEvent.ofChar('x')), "it swallows every other key");
        assertEquals(AboutModal.Outcome.CLOSE, dialog.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertEquals(AboutModal.Outcome.CLOSE, dialog.handleKey(KeyEvent.ofKey(KeyCode.F1)));
        assertEquals(AboutModal.Outcome.HELP_CHAT, dialog.handleKey(KeyEvent.ofChar('?')));
    }

    @Test
    void rendersTheTop() {
        var dialog = dialog();
        TuiSnapshot.assertMatches("about-modal-80x24", TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
    }

    /** Every shortcut, with the digits that stand in for the F-keys and how to get Alt on macOS (#1824). */
    @Test
    void rendersEveryShortcutOnMacOs() {
        var dialog = dialog(true);
        TuiSnapshot.assertMatches("about-modal-macos-80x60", TuiSnapshot.render(80, 60, f -> dialog.render(f, f.area())));
    }

    /**
     * A 70-column split pane shows the number-row rule and the macOS directions whole: the dialog does
     * not wrap. A short pane scrolls, and its scrollbar takes one more column from the text.
     */
    @Test
    void aSeventyColumnPaneShowsTheNumberRowRuleAndTheOptionKeyPathsWhole() {
        var tall = dialog(true);
        assertShowsWhole(TuiSnapshot.toText(TuiSnapshot.render(70, 60, f -> tall.render(f, f.area()))));
        var shortPane = dialog(true);
        var scrolled = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            scrolled.append(TuiSnapshot.toText(TuiSnapshot.render(70, 24, f -> shortPane.render(f, f.area()))));
            shortPane.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
        }
        assertShowsWhole(scrolled.toString());
    }

    private static void assertShowsWhole(String text) {
        for (var expected : List.of(
                "No F-keys? Use the number row instead:",
                "1–9, 0          stand in for F1–F9, F10  (so 2 = F2, 0 = F10)",
                "Alt+1–9, Alt+0  stand in for ⇧F1–⇧F10    (so Alt+7 = ⇧F7)",
                "Terminal.app  Settings > Profiles > Keyboard >",
                "\"Use Option as Meta key\"",
                "iTerm2        Settings > Profiles > Keys > General >",
                "Left Option key: Esc+",
                "Ghostty       macos-option-as-alt = true in the config",
                "Jump to bottom",
                "Join its task")) {
            assertTrue(text.contains(expected), "missing or clipped: " + expected + "\n" + text);
        }
    }
}
