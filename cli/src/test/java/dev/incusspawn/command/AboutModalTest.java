package dev.incusspawn.command;

import dev.incusspawn.tui.TuiSnapshot;
import dev.incusspawn.tui.TuiTheme;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The F1 dialog on its own, with fixed versions in place of this build's and the host's. */
class AboutModalTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    private static AboutModal dialog() {
        return new AboutModal(new ModalRenderer(THEME), THEME, new AboutModal.Source() {
            @Override public String version() { return "1.2.3"; }
            @Override public String gitSha() { return "0000000"; }
            @Override public String incusClient() { return "REST API"; }
            @Override public String incusServer() { return "6.0"; }
            @Override public String runtime() { return "Java 25"; }
            @Override public String kernelInfo() { return "Linux 6.1"; }
            @Override public boolean isMacOS() { return false; }
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
}
