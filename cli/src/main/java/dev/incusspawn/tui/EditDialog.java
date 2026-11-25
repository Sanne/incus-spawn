package dev.incusspawn.tui;

import dev.incusspawn.incus.Metadata;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.input.TextInput;
import dev.tamboui.widgets.input.TextInputState;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;

/**
 * The TUI's edit dialog for an instance (#2621): its name and its purpose, each starting from the
 * current one. F6 opens it on the name and {@code p} on the purpose; Tab and the arrows move
 * between them. It owns the inputs, their keys and their rendering; the {@link Tui} checks both on
 * Enter, writes the purpose, and renames the instance, stopping it first when it runs.
 */
final class EditDialog {

    /** What a key did: nothing the TUI must act on, close the dialog, or confirm the edit. */
    enum Outcome { HANDLED, CLOSE, CONFIRM }

    /** Which field the cursor is in. */
    enum Field { NAME, PURPOSE }

    private final ModalRenderer modal;
    private final TuiTheme theme;
    private final String instanceName;
    private final TextInputState nameInput;
    private final TextInputState purposeInput;
    private Field field;

    EditDialog(ModalRenderer modal, TuiTheme theme, String instanceName, String purpose, Field field) {
        this.modal = modal;
        this.theme = theme;
        this.instanceName = instanceName;
        nameInput = new TextInputState(instanceName);
        purposeInput = new TextInputState(purpose == null ? "" : purpose);
        this.field = field;
    }

    /** The name typed so far, stripped. */
    String name() {
        return nameInput.text().strip();
    }

    /** The purpose typed so far, stripped; empty to clear it. */
    String purpose() {
        return purposeInput.text().strip();
    }

    Field field() {
        return field;
    }

    /** Whether {@code ch} may be typed after {@code text} in a purpose: no control character, and at most {@link Metadata#PURPOSE_MAX} characters. */
    static boolean acceptsPurpose(String text, char ch) {
        return !dev.incusspawn.util.OutputFormat.isControl(ch) && text.codePointCount(0, text.length()) < Metadata.PURPOSE_MAX;
    }

    /** A key while the dialog is open; Enter is the TUI's to confirm. */
    Outcome handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) return Outcome.CLOSE;
        if (key.isKey(KeyCode.ENTER)) return Outcome.CONFIRM;
        // Two fields, so forward and backward both land on the other one
        if (ShiftTabBindings.isShiftTab(key) || key.isKey(KeyCode.TAB)
                || key.isKey(KeyCode.UP) || key.isKey(KeyCode.DOWN)) {
            field = field == Field.NAME ? Field.PURPOSE : Field.NAME;
            return Outcome.HANDLED;
        }
        var input = field == Field.NAME ? nameInput : purposeInput;
        if (key.isKey(KeyCode.BACKSPACE)) { input.deleteBackward(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.DELETE))    { input.deleteForward(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.LEFT))      { input.moveCursorLeft(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.RIGHT))     { input.moveCursorRight(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.HOME))      { input.moveCursorToStart(); return Outcome.HANDLED; }
        if (key.isKey(KeyCode.END))       { input.moveCursorToEnd(); return Outcome.HANDLED; }
        if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
            char ch = key.character();
            if (field == Field.NAME) {
                if (Character.isLetterOrDigit(ch) || ch == '-') input.insert(ch);
            } else if (acceptsPurpose(input.text(), ch)) {
                // Free text, one line: what Metadata.purposeProblem would refuse is left out as it is typed
                input.insert(ch);
            }
        }
        return Outcome.HANDLED;
    }

    void render(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        var modalArea = ModalRenderer.centerRect(screen, 54, 8);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(modal.styledTitle(" Edit '" + instanceName + "' ", modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        modal.renderBlock(frame, block, modalArea);
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1), Constraint.length(1),
                        Constraint.length(1), Constraint.fill())
                .split(block.inner(modalArea));
        renderField(frame, rows.get(0), rows.get(1), "Name (renaming a running one asks to stop it):",
                instanceName, nameInput, field == Field.NAME);
        renderField(frame, rows.get(2), rows.get(3), "Purpose (empty removes it):",
                "what this instance is for", purposeInput, field == Field.PURPOSE);
        var hintSpans = new ArrayList<Span>();
        modal.addKey(hintSpans, "Enter", "Confirm");
        modal.addKey(hintSpans, "Tab", "Next field");
        modal.addKey(hintSpans, "Esc", "Cancel");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(5));
    }

    private void renderField(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect labelRow,
                             dev.tamboui.layout.Rect inputRow, String label, String placeholder,
                             TextInputState input, boolean focused) {
        frame.renderWidget(Paragraph.from(Line.styled(label, Style.EMPTY.fg(modal.fg()).bg(modal.bg()))), labelRow);
        if (focused) {
            TextInput.builder()
                    .placeholder(placeholder)
                    .style(Style.EMPTY.fg(theme.focusedLabel()).bg(modal.inputBg()))
                    .build()
                    .renderWithCursor(inputRow, frame.buffer(), input, frame);
        } else {
            // An empty field shows what goes there, as the focused one does
            // in the placeholder's colour, so an empty field does not read as a value
            var empty = input.text().isEmpty();
            frame.renderWidget(Paragraph.from(Line.styled(empty ? placeholder : input.text(),
                    Style.EMPTY.fg(empty ? modal.placeholderFg() : theme.textDim()).bg(modal.inputBg()))), inputRow);
        }
    }
}
