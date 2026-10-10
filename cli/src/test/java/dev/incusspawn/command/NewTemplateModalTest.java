package dev.incusspawn.command;

import dev.incusspawn.tui.TuiSnapshot;
import dev.incusspawn.tui.TuiTheme;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The new-template dialog on its own: which keys it keeps, which it hands back, and what it was told. */
class NewTemplateModalTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    private static NewTemplateModal dialog() {
        return new NewTemplateModal(new ModalRenderer(THEME), THEME, "tpl-dev", List.of(
                new NewTemplateModal.TemplateLocation("Project (.incus-spawn/images/)", Path.of("/p/images")),
                new NewTemplateModal.TemplateLocation("User (~/.config/incus-spawn/images/)", Path.of("/u/images"))));
    }

    @Test
    void escapeClosesAndEnterIsTheTuisToConfirm() {
        assertEquals(NewTemplateModal.Outcome.CLOSE, dialog().handleKey(KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertEquals(NewTemplateModal.Outcome.CONFIRM, dialog().handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
    }

    @Test
    void theFieldsTakeNamesAndTheLocationCycles() {
        var dialog = dialog();
        for (var ch : new char[] {'m', '_', 'y'}) dialog.handleKey(KeyEvent.ofChar(ch));
        assertEquals("my", dialog.name());
        assertEquals("tpl-dev", dialog.parent());
        assertEquals(Path.of("/p/images"), dialog.locationDir());
        // Tab twice to the location, then Space picks the next one.
        dialog.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        dialog.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        assertEquals(NewTemplateModal.Outcome.HANDLED, dialog.handleKey(KeyEvent.ofChar(' ')));
        assertEquals(Path.of("/u/images"), dialog.locationDir());
    }

    @Test
    void aKeyNoFieldTakesIsLeftUnhandled() {
        var dialog = dialog();
        dialog.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        dialog.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        assertEquals(NewTemplateModal.Outcome.UNHANDLED, dialog.handleKey(KeyEvent.ofChar('x')));
    }

    @Test
    void renders() {
        var dialog = dialog();
        TuiSnapshot.assertMatches("new-template-modal-80x24", TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
    }
}
