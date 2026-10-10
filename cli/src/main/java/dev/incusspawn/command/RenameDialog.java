package dev.incusspawn.command;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextInputState;

/**
 * The TUI's rename dialog (F6 on a stopped instance): the new name, starting from the old one.
 * It owns the input, its keys and its rendering; the {@link Tui} checks the name and renames
 * the instance on Enter.
 */
final class RenameDialog {

    /** What a key did: nothing the TUI must act on, close the dialog, or confirm the rename. */
    enum Outcome { HANDLED, CLOSE, CONFIRM }

    private final ModalRenderer modal;
    private final String renameSourceName;
    private final TextInputState renameInput;

    RenameDialog(ModalRenderer modal, String sourceName) {
        this.modal = modal;
        this.renameSourceName = sourceName;
        renameInput = new TextInputState(sourceName);
    }

    /** The name typed so far, stripped. */
    String name() {
        return renameInput.text().strip();
    }

    /** A key while the dialog is open; Enter is the TUI's to confirm. */
    Outcome handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) {
            return Outcome.CLOSE;
        }
        if (key.isKey(KeyCode.ENTER)) {
            return Outcome.CONFIRM;
        }
        if (key.isKey(KeyCode.BACKSPACE)) { renameInput.deleteBackward(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.DELETE))    { renameInput.deleteForward(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.LEFT))      { renameInput.moveCursorLeft(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.RIGHT))     { renameInput.moveCursorRight(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.HOME))      { renameInput.moveCursorToStart(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.END))       { renameInput.moveCursorToEnd(); return Outcome.HANDLED; }
        if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
            char ch = key.character();
            if (Character.isLetterOrDigit(ch) || ch == '-') {
                renameInput.insert(ch);
            }
            return Outcome.HANDLED;
        }
        return Outcome.HANDLED;
    }

    void render(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        modal.renderInputModal(frame, screen,
                "Rename '" + renameSourceName + "'", "New name:", renameSourceName, renameInput);
    }
}
