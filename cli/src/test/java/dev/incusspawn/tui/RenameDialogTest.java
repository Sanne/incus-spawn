package dev.incusspawn.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The rename dialog on its own: which keys it keeps and which it hands back. */
class RenameDialogTest {

    private static RenameDialog dialog() {
        return new RenameDialog(new ModalRenderer(TuiTheme.dark()), "dev-1");
    }

    @Test
    void escapeClosesAndEnterIsTheTuisToConfirm() {
        assertEquals(RenameDialog.Outcome.CLOSE, dialog().handleKey(KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertEquals(RenameDialog.Outcome.CONFIRM, dialog().handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
    }

    @Test
    void theNameStartsAsTheOldOneAndTakesLettersDigitsAndHyphensOnly() {
        var dialog = dialog();
        assertEquals("dev-1", dialog.name());
        for (var ch : new char[] {'x', '_', '-', '2'}) {
            assertEquals(RenameDialog.Outcome.HANDLED, dialog.handleKey(KeyEvent.ofChar(ch)));
        }
        assertEquals(RenameDialog.Outcome.HANDLED, dialog.handleKey(KeyEvent.ofKey(KeyCode.BACKSPACE)));
        assertEquals("dev-1x-", dialog.name());
    }

    @Test
    void renders() {
        var dialog = dialog();
        TuiSnapshot.assertMatches("rename-dialog-80x24", TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
    }
}
