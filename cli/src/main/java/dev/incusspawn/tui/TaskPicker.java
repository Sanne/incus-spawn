package dev.incusspawn.tui;

import dev.incusspawn.mcp.TaskJoin;
import dev.incusspawn.tool.ActionContext;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The Shift+F2 picker, for an instance holding several delegated tasks: which conversation to
 * join, never guessed. The {@link Tui} joins the one chosen ({@link #selected}) in {@link #target}.
 */
final class TaskPicker {

    /** What a key did: moved the selection, a key the picker does not own, close, or join the selected task. */
    enum Outcome { HANDLED, UNHANDLED, CLOSE, JOIN }

    private final ModalRenderer modal;
    private final TuiTheme theme;

    private List<TaskJoin.Entry> entries = List.of();
    private int selected;
    private int scrollOffset;
    private ActionContext target;
    private Tui.Mode returnMode = Tui.Mode.BROWSE;

    TaskPicker(ModalRenderer modal, TuiTheme theme) {
        this.modal = modal;
        this.theme = theme;
    }

    /** Offer {@code entries} of the instance {@code target} names, the first one selected; Esc goes back to {@code returnMode}. */
    void open(List<TaskJoin.Entry> entries, ActionContext target, Tui.Mode returnMode) {
        this.entries = List.copyOf(entries);
        this.target = target;
        this.returnMode = returnMode;
        selected = 0;
        scrollOffset = 0;
    }

    /** The instance the tasks are in; null before the picker first opens. */
    ActionContext target() {
        return target;
    }

    /** The dialog the picker was opened from: the table, or an instance's details. */
    Tui.Mode returnMode() {
        return returnMode;
    }

    TaskJoin.Entry selected() {
        return entries.get(selected);
    }

    Outcome handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) return Outcome.CLOSE;
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            if (selected < entries.size() - 1) selected++;
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            if (selected > 0) selected--;
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.ENTER)) return Outcome.JOIN;
        return Outcome.UNHANDLED;
    }

    void render(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        if (entries.isEmpty()) return;
        var now = Instant.now();
        var lines = new ArrayList<Line>();
        for (int i = 0; i < entries.size(); i++) {
            var style = i == selected
                    ? Style.EMPTY.bold().fg(theme.focusedLabel()).bg(modal.bg())
                    : Style.EMPTY.fg(modal.fg()).bg(modal.bg());
            lines.add(Line.from(Span.styled((i == selected ? " > " : "   ") + TaskJoin.describe(entries.get(i), now), style)));
        }
        var hintSpans = new ArrayList<Span>();
        modal.addKey(hintSpans, "Enter", "Join");
        modal.addKey(hintSpans, "Esc", "Close");

        int width = ModalRenderer.hintWidth(hintSpans);
        for (var line : lines) width = Math.max(width, line.width());
        int modalWidth = Math.min(width + 4, screen.width() - 4);
        int modalHeight = Math.min(lines.size() + 4, screen.height() - 2);
        var modalArea = ModalRenderer.centerRect(screen, modalWidth, modalHeight);
        var block = dev.tamboui.widgets.block.Block.builder()
                .borders(dev.tamboui.widgets.block.Borders.ALL)
                .borderType(dev.tamboui.widgets.block.BorderType.ROUNDED)
                .title(modal.styledTitle(" Join a task — " + target.instanceName() + " ", modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        modal.renderBlock(frame, block, modalArea);
        var rows = dev.tamboui.layout.Layout.vertical()
                .constraints(dev.tamboui.layout.Constraint.fill(), dev.tamboui.layout.Constraint.length(1))
                .split(block.inner(modalArea));
        int contentHeight = rows.get(0).height();
        if (selected < scrollOffset) scrollOffset = selected;
        else if (selected >= scrollOffset + contentHeight) scrollOffset = selected - contentHeight + 1;
        scrollOffset = modal.renderScrollableContent(frame, rows.get(0), lines, scrollOffset);
        frame.renderWidget(dev.tamboui.widgets.paragraph.Paragraph.from(Line.from(hintSpans)), rows.get(1));
    }
}
