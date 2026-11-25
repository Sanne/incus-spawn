package dev.incusspawn.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The edit dialog on its own: which keys it keeps, which it hands back, and which field takes them. */
class EditDialogTest {

    private static EditDialog dialog(EditDialog.Field field) {
        return new EditDialog(new ModalRenderer(TuiTheme.dark()), TuiTheme.dark(), "dev-1", "review", field);
    }

    @Test
    void escapeClosesAndEnterIsTheTuisToConfirm() {
        assertEquals(EditDialog.Outcome.CLOSE, dialog(EditDialog.Field.NAME).handleKey(KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertEquals(EditDialog.Outcome.CONFIRM, dialog(EditDialog.Field.NAME).handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
    }

    @Test
    void theNameStartsAsTheOldOneAndTakesLettersDigitsAndHyphensOnly() {
        var dialog = dialog(EditDialog.Field.NAME);
        assertEquals("dev-1", dialog.name());
        for (var ch : new char[] {'x', '_', '-', '2'}) {
            assertEquals(EditDialog.Outcome.HANDLED, dialog.handleKey(KeyEvent.ofChar(ch)));
        }
        assertEquals(EditDialog.Outcome.HANDLED, dialog.handleKey(KeyEvent.ofKey(KeyCode.BACKSPACE)));
        assertEquals("dev-1x-", dialog.name());
        assertEquals("review", dialog.purpose(), "the purpose is left alone");
    }

    @Test
    void tabMovesToThePurposeWhichTakesFreeText() {
        var dialog = dialog(EditDialog.Field.NAME);
        dialog.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        assertEquals(EditDialog.Field.PURPOSE, dialog.field());
        for (var ch : " of #12_".toCharArray()) dialog.handleKey(KeyEvent.ofChar(ch));
        assertEquals("review of #12_", dialog.purpose());
        assertEquals("dev-1", dialog.name());
        dialog.handleKey(KeyEvent.ofKey(KeyCode.UP));
        assertEquals(EditDialog.Field.NAME, dialog.field(), "and back");
    }

    @Test
    void thePurposeTakesNoControlCharacterAndStopsAtItsLimit() {
        var dialog = dialog(EditDialog.Field.PURPOSE);
        dialog.handleKey(KeyEvent.ofChar('\u001b'));
        assertEquals("review", dialog.purpose());
        for (int i = 0; i < dev.incusspawn.incus.Metadata.PURPOSE_MAX + 5; i++) dialog.handleKey(KeyEvent.ofChar('x'));
        assertEquals(dev.incusspawn.incus.Metadata.PURPOSE_MAX, dialog.purpose().length());
    }

    @Test
    void renders() {
        var dialog = dialog(EditDialog.Field.PURPOSE);
        TuiSnapshot.assertMatches("edit-dialog-80x24", TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
    }
}
