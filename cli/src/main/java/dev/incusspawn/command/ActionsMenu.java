package dev.incusspawn.command;

import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.ToolAction;
import dev.incusspawn.tool.YamlToolAction;
import dev.incusspawn.tui.TuiTheme;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The F9 actions dialog: the tool actions an instance offers, one of which Enter runs. It owns
 * the list, the selection, its keys and its rendering; the {@link Tui} checks the instance
 * still exists and runs the chosen action ({@link #selectedAction}) with {@link #context}.
 */
final class ActionsMenu {

    /** What a key did: moved the selection, a key the menu does not own, close, or run the selected action. */
    enum Outcome { HANDLED, UNHANDLED, CLOSE, RUN }

    private final ModalRenderer modal;
    private final TuiTheme theme;

    private java.util.List<ToolAction> actionsList;

    private int actionsSelectedIndex;

    private int actionsScrollOffset;

    private ActionContext actionsContext;

    ActionsMenu(ModalRenderer modal, TuiTheme theme) {
        this.modal = modal;
        this.theme = theme;
    }

    /** Offer {@code actions} for the instance {@code context} describes, the first one selected. */
    void open(java.util.List<ToolAction> actions, ActionContext context) {
        actionsList = actions;
        actionsSelectedIndex = 0;
        actionsScrollOffset = 0;
        actionsContext = context;
    }

    /** What the actions run against; null before the menu first opens. */
    ActionContext context() {
        return actionsContext;
    }

    ToolAction selectedAction() {
        return actionsList.get(actionsSelectedIndex);
    }

    Outcome handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC() || key.isKey(KeyCode.F9)) {
            return Outcome.CLOSE;
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            if (actionsSelectedIndex < actionsList.size() - 1) {
                actionsSelectedIndex++;
            }
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            if (actionsSelectedIndex > 0) {
                actionsSelectedIndex--;
            }
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.HOME) || key.isChar('g')) {
            actionsSelectedIndex = 0;
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.END) || key.isChar('G')) {
            actionsSelectedIndex = actionsList.size() - 1;
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.ENTER)) {
            return Outcome.RUN;
        }
        return Outcome.UNHANDLED;
    }

    void render(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        if (actionsList == null || actionsList.isEmpty()) return;

        var lines = new ArrayList<Line>();
        for (int i = 0; i < actionsList.size(); i++) {
            var action = actionsList.get(i);
            var selected = (i == actionsSelectedIndex);
            var prefix = selected ? " > " : "   ";
            var style = selected
                    ? Style.EMPTY.bold().fg(theme.focusedLabel()).bg(modal.bg())
                    : Style.EMPTY.fg(modal.fg()).bg(modal.bg());
            if (action instanceof YamlToolAction ya && ya.isUrl()) {
                var url = ya.resolveUrl(actionsContext);
                if (url != null && !url.isBlank()) {
                    style = style.hyperlink(url);
                }
            }
            var toolStyle = Style.EMPTY.fg(theme.textDim()).bg(modal.bg());
            lines.add(Line.from(List.of(
                    Span.styled(prefix + action.label(), style),
                    Span.styled("  (" + action.toolName() + ")", toolStyle))));
        }

        int modalWidth = Math.min(80, screen.width() - 4);
        int modalHeight = Math.min(lines.size() + 4, screen.height() - 2);

        var instanceName = actionsContext != null ? actionsContext.instanceName() : "";
        var modalArea = ModalRenderer.centerRect(screen, modalWidth, modalHeight);
        var block = dev.tamboui.widgets.block.Block.builder()
                .borders(dev.tamboui.widgets.block.Borders.ALL)
                .borderType(dev.tamboui.widgets.block.BorderType.DOUBLE)
                .title(modal.styledTitle(" Actions — " + instanceName + " ", modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        modal.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var rows = dev.tamboui.layout.Layout.vertical()
                .constraints(dev.tamboui.layout.Constraint.fill(), dev.tamboui.layout.Constraint.length(1))
                .split(inner);

        // Keep the selected item visible
        int contentHeight = rows.get(0).height();
        if (actionsSelectedIndex < actionsScrollOffset) {
            actionsScrollOffset = actionsSelectedIndex;
        } else if (actionsSelectedIndex >= actionsScrollOffset + contentHeight) {
            actionsScrollOffset = actionsSelectedIndex - contentHeight + 1;
        }

        actionsScrollOffset = modal.renderScrollableContent(frame, rows.get(0), lines, actionsScrollOffset);

        var hintSpans = new ArrayList<Span>();
        modal.addKey(hintSpans, "Enter", "Run");
        modal.addKey(hintSpans, "F9/Esc", "Close");
        frame.renderWidget(dev.tamboui.widgets.paragraph.Paragraph.from(Line.from(hintSpans)), rows.get(1));
    }
}
