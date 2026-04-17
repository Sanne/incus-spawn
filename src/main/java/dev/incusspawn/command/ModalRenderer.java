package dev.incusspawn.command;

import java.util.ArrayList;
import java.util.List;

import dev.incusspawn.config.NetworkMode;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.input.TextInput;
import dev.tamboui.widgets.input.TextInputState;
import dev.tamboui.widgets.paragraph.Paragraph;

final class ModalRenderer {

    static final Color BG = Color.rgb(40, 42, 64);
    static final Color FG = Color.rgb(205, 214, 244);
    static final Color BORDER = Color.CYAN;
    static final Color ACCENT = Color.LIGHT_CYAN;
    static final Color WARN = Color.LIGHT_RED;
    static final Color INPUT_BG = Color.rgb(49, 50, 68);
    static final Color INPUT_INACTIVE_BG = Color.rgb(40, 40, 55);
    static final Color PLACEHOLDER_FG = Color.rgb(80, 80, 100);

    private ModalRenderer() {
    }

    static Rect centerRect(Rect screen, int width, int height) {
        int w = Math.min(width, screen.width());
        int h = Math.min(height, screen.height());
        int x = screen.x() + (screen.width() - w) / 2;
        int y = screen.y() + (screen.height() - h) / 2;
        return new Rect(x, y, w, h);
    }

    static void renderBlock(Frame frame, Block block, Rect modalArea) {
        frame.buffer().fill(modalArea, new Cell(" ", Style.EMPTY.bg(BG)));
        frame.renderWidget(block, modalArea);
    }

    static void addKey(List<Span> spans, String key, String label) {
        spans.add(Span.styled(" " + key, Style.EMPTY.bold().fg(ACCENT).bg(BG)));
        spans.add(Span.styled(" " + label + "  ", Style.EMPTY.fg(FG).bg(BG)));
    }

    static void renderToggle(Frame frame, Rect area,
                              String shortcut, String label, boolean enabled) {
        var check = enabled ? "\u25c9" : "\u25cb";
        var checkColor = enabled ? Color.GREEN : Color.GRAY;
        frame.renderWidget(Paragraph.from(Line.from(List.of(
                Span.styled(" " + shortcut + " ", Style.EMPTY.fg(ACCENT).bg(BG)),
                Span.styled(check + " ", Style.EMPTY.fg(checkColor).bg(BG)),
                Span.styled(label, Style.EMPTY.fg(FG).bg(BG))))), area);
    }

    static void renderNetworkModeRadio(Frame frame, Rect area,
                                        String shortcut, NetworkMode selected) {
        var spans = new ArrayList<Span>();
        spans.add(Span.styled(" " + shortcut + " ", Style.EMPTY.fg(ACCENT).bg(BG)));
        for (NetworkMode mode : NetworkMode.values()) {
            boolean isSelected = (mode == selected);
            var symbol = isSelected ? "\u25c9" : "\u25cb";
            var color = isSelected ? Color.GREEN : Color.GRAY;
            spans.add(Span.styled(symbol + " ", Style.EMPTY.fg(color).bg(BG)));
            var labelStyle = isSelected
                    ? Style.EMPTY.bold().fg(FG).bg(BG)
                    : Style.EMPTY.fg(Color.GRAY).bg(BG);
            spans.add(Span.styled(mode.label(), labelStyle));
            spans.add(Span.styled("  ", Style.EMPTY.bg(BG)));
        }
        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }

    static void renderInlineField(List<Span> spans, String value, boolean disabled,
                                   boolean active) {
        var display = String.format("%-6s", value);
        if (disabled) {
            spans.add(Span.styled(display, Style.EMPTY.fg(PLACEHOLDER_FG).bg(INPUT_INACTIVE_BG)));
        } else if (active) {
            spans.add(Span.styled(display, Style.EMPTY.bold().fg(Color.WHITE).bg(INPUT_BG)));
        } else {
            spans.add(Span.styled(display, Style.EMPTY.fg(Color.GRAY).bg(INPUT_INACTIVE_BG)));
        }
    }

    static void renderInputModal(Frame frame, Rect screen,
                                  String title, String label, String placeholder,
                                  TextInputState inputState) {
        var modalArea = centerRect(screen, 54, 7);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(" " + title + " ")
                .borderStyle(Style.EMPTY.fg(BORDER))
                .style(Style.EMPTY.bg(BG))
                .build();
        renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1), Constraint.fill())
                .split(inner);
        frame.renderWidget(Paragraph.from(Line.styled(
                label, Style.EMPTY.fg(FG).bg(BG))), rows.get(0));
        TextInput.builder()
                .placeholder(placeholder)
                .style(Style.EMPTY.fg(Color.WHITE).bg(INPUT_BG))
                .build()
                .renderWithCursor(rows.get(1), frame.buffer(), inputState, frame);
        var hintSpans = new ArrayList<Span>();
        addKey(hintSpans, "Enter", "Confirm");
        addKey(hintSpans, "Esc", "Cancel");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(3));
    }

    static void renderConfirmModal(Frame frame, Rect screen,
                                    String title, String message, Color borderColor) {
        renderConfirmModal(frame, screen, title, message, borderColor, "Confirm");
    }

    static void renderConfirmModal(Frame frame, Rect screen,
                                    String title, String message, Color borderColor,
                                    String confirmLabel) {
        var modalArea = centerRect(screen, 54, 7);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(title)
                .borderStyle(Style.EMPTY.fg(borderColor))
                .style(Style.EMPTY.bg(BG))
                .build();
        renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1), Constraint.fill())
                .split(inner);
        frame.renderWidget(Paragraph.from(Line.styled(
                message, Style.EMPTY.fg(borderColor).bg(BG))), rows.get(1));
        var btnSpans = new ArrayList<Span>();
        addKey(btnSpans, "y", confirmLabel);
        addKey(btnSpans, "any key", "Cancel");
        frame.renderWidget(Paragraph.from(Line.from(btnSpans)), rows.get(3));
    }

    static void renderProgressOverlay(Frame frame, Rect screen, String message) {
        int width = Math.min(message.length() + 6, screen.width() - 4);
        var modalArea = centerRect(screen, width, 3);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(ACCENT))
                .style(Style.EMPTY.bg(BG))
                .build();
        renderBlock(frame, block, modalArea);
        frame.renderWidget(Paragraph.from(Line.styled(
                " " + message,
                Style.EMPTY.fg(FG).bg(BG))), block.inner(modalArea));
    }
}
