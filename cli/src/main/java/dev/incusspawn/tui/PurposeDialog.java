package dev.incusspawn.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextInputState;

/**
 * The TUI's purpose dialog ({@code p} on an instance, #2239): what the instance is for, starting
 * from its current purpose. An empty text removes it. It owns the input, its keys and its
 * rendering; the {@link Tui} checks the text and writes it on Enter, to a running or a stopped
 * instance alike.
 */
final class PurposeDialog {

    /** What a key did: nothing the TUI must act on, close the dialog, or confirm the purpose. */
    enum Outcome { HANDLED, CLOSE, CONFIRM }

    private final ModalRenderer modal;
    private final String instanceName;
    private final TextInputState input;

    PurposeDialog(ModalRenderer modal, String instanceName, String current) {
        this.modal = modal;
        this.instanceName = instanceName;
        input = new TextInputState(current == null ? "" : current);
    }

    /** The purpose typed so far, stripped; empty to clear it. */
    String text() {
        return input.text().strip();
    }

    /** A key while the dialog is open; Enter is the TUI's to confirm. */
    Outcome handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) return Outcome.CLOSE;
        if (key.isKey(KeyCode.ENTER)) return Outcome.CONFIRM;
        if (key.isKey(KeyCode.BACKSPACE)) { input.deleteBackward(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.DELETE))    { input.deleteForward(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.LEFT))      { input.moveCursorLeft(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.RIGHT))     { input.moveCursorRight(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.HOME))      { input.moveCursorToStart(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.END))       { input.moveCursorToEnd(); return Outcome.HANDLED; }
        if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
            // Free text, one line: a control character is left out, as Metadata.purposeProblem would refuse it
            if (!Character.isISOControl(key.character())) input.insert(key.character());
        }
        return Outcome.HANDLED;
    }

    void render(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        modal.renderInputModal(frame, screen, "Purpose of '" + instanceName + "'",
                "What it is for (empty removes it):", "what this instance is for", input);
    }
}
