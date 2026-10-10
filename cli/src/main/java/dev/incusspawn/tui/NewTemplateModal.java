package dev.incusspawn.tui;

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
import dev.tamboui.widgets.select.SelectState;

import java.util.ArrayList;

/**
 * The TUI's new-template dialog: the template's name, its parent, and where to save it. It owns
 * its fields, their keys and its rendering; the {@link Tui} lists the locations, checks the name
 * and parent on Enter, and creates the template once the terminal is released, from
 * {@link #parent()} and {@link #locationDir()}.
 */
final class NewTemplateModal {

    /** What a key did: nothing the TUI must act on, nothing at all, close the dialog, or confirm it. */
    enum Outcome { HANDLED, UNHANDLED, CLOSE, CONFIRM }

    /** A directory the template can be saved to, and how the dialog names it. */
    record TemplateLocation(String label, java.nio.file.Path dir) {}

    private final ModalRenderer modal;
    private final TuiTheme theme;
    private final TextInputState newTemplateNameInput;
    private final TextInputState newTemplateParentInput;
    private final java.util.List<TemplateLocation> newTemplateLocations;
    private final SelectState newTemplateLocationSelect;
    private int newTemplateFieldIndex;

    NewTemplateModal(ModalRenderer modal, TuiTheme theme, String parentName,
                     java.util.List<TemplateLocation> locations) {
        this.modal = modal;
        this.theme = theme;
        newTemplateNameInput = new TextInputState("");
        newTemplateParentInput = new TextInputState(parentName);
        newTemplateLocations = locations;
        newTemplateLocationSelect = new SelectState(locations.stream().map(TemplateLocation::label).toArray(String[]::new));
        newTemplateFieldIndex = 0;
    }

    /** The name typed so far, stripped. */
    String name() {
        return newTemplateNameInput.text().strip();
    }

    /** The parent typed so far, stripped; empty for none. */
    String parent() {
        return newTemplateParentInput.text().strip();
    }

    /** The directory chosen under "Save to". */
    java.nio.file.Path locationDir() {
        return newTemplateLocations.get(newTemplateLocationSelect.selectedIndex()).dir();
    }

    /** A key while the dialog is open; Enter is the TUI's to confirm. */
    Outcome handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) {
            return Outcome.CLOSE;
        }
        if (key.isKey(KeyCode.ENTER)) {
            return Outcome.CONFIRM;
        }
        // Location field: Space/Down/j cycle forward, Up/k cycle backward
        if (newTemplateFieldIndex == 2) {
            if (key.isKey(KeyCode.DOWN) || key.isChar('j')
                    || (key.code() == KeyCode.CHAR && key.character() == ' ')) {
                newTemplateLocationSelect.selectNext();
                return Outcome.HANDLED;
            }
            if (key.isKey(KeyCode.UP) || key.isChar('k')) {
                newTemplateLocationSelect.selectPrevious();
                return Outcome.HANDLED;
            }
        }
        // Tab: next field, Shift+Tab: previous field
        if (key.isKey(KeyCode.TAB) || (newTemplateFieldIndex < 2 && key.isKey(KeyCode.DOWN))) {
            newTemplateFieldIndex = (newTemplateFieldIndex + 1) % 3;
            return Outcome.HANDLED;
        }
        if (ShiftTabBindings.isShiftTab(key) || (newTemplateFieldIndex < 2 && key.isKey(KeyCode.UP))) {
            newTemplateFieldIndex = (newTemplateFieldIndex + 2) % 3;
            return Outcome.HANDLED;
        }
        // Text input for name (field 0) and parent (field 1)
        if (newTemplateFieldIndex < 2) {
            var input = newTemplateFieldIndex == 0 ? newTemplateNameInput : newTemplateParentInput;
            if (key.isKey(KeyCode.BACKSPACE)) { input.deleteBackward(); return Outcome.HANDLED; }
            if (key.isKey(KeyCode.DELETE))    { input.deleteForward();  return Outcome.HANDLED; }
            if (key.isKey(KeyCode.LEFT))      { input.moveCursorLeft(); return Outcome.HANDLED; }
            if (key.isKey(KeyCode.RIGHT))     { input.moveCursorRight(); return Outcome.HANDLED; }
            if (key.isKey(KeyCode.HOME))       { input.moveCursorToStart(); return Outcome.HANDLED; }
            if (key.isKey(KeyCode.END))        { input.moveCursorToEnd(); return Outcome.HANDLED; }
            if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
                char ch = key.character();
                if (Character.isLetterOrDigit(ch) || ch == '-') {
                    input.insert(ch);
                }
                return Outcome.HANDLED;
            }
        }
        return Outcome.UNHANDLED;
    }

    private void renderLabeledTextField(dev.tamboui.terminal.Frame frame,
            dev.tamboui.layout.Rect labelRow, dev.tamboui.layout.Rect inputRow,
            String label, String placeholder, TextInputState inputState, boolean focused) {
        frame.renderWidget(Paragraph.from(Line.styled(
                label, Style.EMPTY.fg(modal.fg()).bg(modal.bg()))), labelRow);
        if (focused) {
            TextInput.builder()
                    .placeholder(placeholder)
                    .style(Style.EMPTY.fg(theme.focusedLabel()).bg(modal.inputBg()))
                    .build()
                    .renderWithCursor(inputRow, frame.buffer(), inputState, frame);
        } else {
            frame.renderWidget(Paragraph.from(Line.styled(
                    inputState.text(), Style.EMPTY.fg(theme.textDim()).bg(modal.inputBg()))),
                    inputRow);
        }
    }

    void render(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        var modalArea = ModalRenderer.centerRect(screen, 54, 9);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(modal.styledTitle(" New Template ", modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        modal.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var rows = Layout.vertical()
                .constraints(
                        Constraint.length(1), // Name label
                        Constraint.length(1), // Name input
                        Constraint.length(1), // Parent label
                        Constraint.length(1), // Parent input
                        Constraint.length(1), // Location select
                        Constraint.fill())     // hint bar
                .split(inner);

        renderLabeledTextField(frame, rows.get(0), rows.get(1), "Name:", "my-app",
                newTemplateNameInput, newTemplateFieldIndex == 0);
        renderLabeledTextField(frame, rows.get(2), rows.get(3), "Parent:", "tpl-dev",
                newTemplateParentInput, newTemplateFieldIndex == 1);

        modal.renderSelect(frame, rows.get(4), "Save to", newTemplateLocationSelect,
                newTemplateFieldIndex == 2);

        var hintSpans = new ArrayList<Span>();
        modal.addKey(hintSpans, "Enter", "Confirm");
        modal.addKey(hintSpans, "Esc", "Cancel");
        modal.addKey(hintSpans, "↑↓/Tab", "Navigate");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(5));
    }
}
