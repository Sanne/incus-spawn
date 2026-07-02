package dev.incusspawn.command;

import java.util.ArrayList;
import java.util.List;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.tui.TuiTheme;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Padding;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.input.TextInput;
import dev.tamboui.widgets.input.TextInputState;
import dev.tamboui.widgets.paragraph.Paragraph;

final class ModalRenderer {

    private final TuiTheme theme;

    ModalRenderer(TuiTheme theme) {
        this.theme = theme;
    }

    Color bg() { return theme.modalBg(); }
    Color fg() { return theme.modalFg(); }
    Color border() { return theme.modalBorder(); }
    Color accent() { return theme.modalAccent(); }
    Color warn() { return theme.modalWarn(); }
    Color inputBg() { return theme.inputBg(); }
    Color inputInactiveBg() { return theme.inputInactiveBg(); }
    Color placeholderFg() { return theme.placeholderFg(); }

    static Rect centerRect(Rect screen, int width, int height) {
        int w = Math.min(width, screen.width());
        int h = Math.min(height, screen.height());
        int x = screen.x() + (screen.width() - w) / 2;
        int y = screen.y() + (screen.height() - h) / 2;
        return new Rect(x, y, w, h);
    }

    void renderScrim(Frame frame, Rect screen) {
        var buf = frame.buffer();
        var dimStyle = Style.EMPTY.dim();
        for (int y = screen.y(); y < screen.bottom(); y++) {
            for (int x = screen.x(); x < screen.right(); x++) {
                var cell = buf.get(x, y);
                if (!cell.isContinuation()) {
                    buf.set(x, y, cell.patchStyle(dimStyle));
                }
            }
        }
    }

    void renderBlock(Frame frame, Block block, Rect modalArea) {
        renderShadow(frame, modalArea);
        frame.buffer().fill(modalArea, new Cell(" ", Style.EMPTY.bg(theme.modalBg())));
        frame.renderWidget(block, modalArea);
    }

    private void renderShadow(Frame frame, Rect area) {
        var buf = frame.buffer();
        var shadowCell = new Cell(" ", Style.EMPTY.bg(theme.modalShadow()));
        var bufArea = buf.area();
        int shadowRight = area.right();
        int shadowBottom = area.bottom();
        for (int y = area.y() + 1; y <= shadowBottom && y < bufArea.bottom(); y++) {
            for (int dx = 0; dx < 2; dx++) {
                int x = shadowRight + dx;
                if (x < bufArea.right()) buf.set(x, y, shadowCell);
            }
        }
        for (int x = area.x() + 2; x < shadowRight + 2 && x < bufArea.right(); x++) {
            if (shadowBottom < bufArea.bottom()) buf.set(x, shadowBottom, shadowCell);
        }
    }

    Title styledTitle(String text, Color color) {
        return Title.from(Span.styled(text, Style.EMPTY.bold().fg(color)));
    }

    void addKey(List<Span> spans, String key, String label) {
        spans.add(Span.styled(" " + key, Style.EMPTY.bold().fg(theme.modalAccent()).bg(theme.modalBg())));
        spans.add(Span.styled(" " + label + "  ", Style.EMPTY.fg(theme.modalFg()).bg(theme.modalBg())));
    }

    void renderToggle(Frame frame, Rect area,
                              String label, boolean enabled, boolean focused) {
        var check = enabled ? "☑" : "☐";
        var checkColor = enabled ? theme.checkEnabled() : theme.checkDisabled();
        var prefix = focused ? "▸" : " ";
        var labelColor = focused ? theme.focusedLabel() : theme.modalFg();
        frame.renderWidget(Paragraph.from(Line.from(List.of(
                Span.styled(" " + prefix + " ", Style.EMPTY.fg(theme.modalAccent()).bg(theme.modalBg())),
                Span.styled(check + " ", Style.EMPTY.fg(checkColor).bg(theme.modalBg())),
                Span.styled(label, Style.EMPTY.fg(labelColor).bg(theme.modalBg()))))), area);
    }

    void renderNetworkModeRadio(Frame frame, Rect area,
                                        NetworkMode selected, boolean focused) {
        var spans = new ArrayList<Span>();
        var prefix = focused ? "▸" : " ";
        spans.add(Span.styled(" " + prefix + " ", Style.EMPTY.fg(theme.modalAccent()).bg(theme.modalBg())));
        for (NetworkMode mode : NetworkMode.values()) {
            boolean isSelected = (mode == selected);
            var symbol = isSelected ? "◉" : "○";
            var color = isSelected ? theme.checkEnabled() : theme.checkDisabled();
            spans.add(Span.styled(symbol + " ", Style.EMPTY.fg(color).bg(theme.modalBg())));
            var labelStyle = isSelected
                    ? Style.EMPTY.bold().fg(focused ? theme.focusedLabel() : theme.modalFg()).bg(theme.modalBg())
                    : Style.EMPTY.fg(theme.checkDisabled()).bg(theme.modalBg());
            spans.add(Span.styled(mode.label(), labelStyle));
            spans.add(Span.styled("  ", Style.EMPTY.bg(theme.modalBg())));
        }
        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }

    void renderInlineField(List<Span> spans, String value, boolean disabled,
                                   boolean active) {
        var display = String.format("%-6s", value);
        if (disabled) {
            spans.add(Span.styled(display, Style.EMPTY.fg(theme.placeholderFg()).bg(theme.inputInactiveBg())));
        } else if (active) {
            spans.add(Span.styled(display, Style.EMPTY.bold().fg(theme.focusedLabel()).bg(theme.inputBg())));
        } else {
            spans.add(Span.styled(display, Style.EMPTY.fg(theme.checkDisabled()).bg(theme.inputInactiveBg())));
        }
    }

    void renderInputModal(Frame frame, Rect screen,
                                  String title, String label, String placeholder,
                                  TextInputState inputState) {
        var modalArea = centerRect(screen, 54, 7);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(styledTitle(" " + title + " ", theme.modalBorder()))
                .borderStyle(Style.EMPTY.fg(theme.modalBorder()))
                .style(Style.EMPTY.bg(theme.modalBg()))
                .padding(Padding.horizontal(1))
                .build();
        renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1), Constraint.fill())
                .split(inner);
        frame.renderWidget(Paragraph.from(Line.styled(
                label, Style.EMPTY.fg(theme.modalFg()).bg(theme.modalBg()))), rows.get(0));
        TextInput.builder()
                .placeholder(placeholder)
                .style(Style.EMPTY.fg(theme.focusedLabel()).bg(theme.inputBg()))
                .build()
                .renderWithCursor(rows.get(1), frame.buffer(), inputState, frame);
        var hintSpans = new ArrayList<Span>();
        addKey(hintSpans, "Enter", "Confirm");
        addKey(hintSpans, "Esc", "Cancel");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(3));
    }

    void renderConfirmModal(Frame frame, Rect screen,
                                    String title, String message, Color borderColor) {
        renderConfirmModal(frame, screen, title, message, borderColor, "Confirm");
    }

    void renderConfirmModal(Frame frame, Rect screen,
                                    String title, String message, Color borderColor,
                                    String confirmLabel) {
        renderConfirmModal(frame, screen, title, message, borderColor, confirmLabel, "y");
    }

    void renderConfirmModal(Frame frame, Rect screen,
                                    String title, String message, Color borderColor,
                                    String confirmLabel, String confirmKey) {
        int modalWidth = 54;
        int innerWidth = modalWidth - 6;
        var wrappedLines = wrapText(message, innerWidth);
        int modalHeight = 2 + 1 + wrappedLines.size() + 1 + 2;
        var modalArea = centerRect(screen, modalWidth, modalHeight);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(styledTitle(title, borderColor))
                .borderStyle(Style.EMPTY.fg(borderColor))
                .style(Style.EMPTY.bg(theme.modalBg()))
                .padding(Padding.horizontal(1))
                .build();
        renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var constraints = new ArrayList<Constraint>();
        constraints.add(Constraint.length(1)); // top spacing
        for (int i = 0; i < wrappedLines.size(); i++) {
            constraints.add(Constraint.length(1)); // one line per message line
        }
        constraints.add(Constraint.length(1)); // spacer
        constraints.add(Constraint.fill()); // buttons

        var rows = Layout.vertical().constraints(constraints.toArray(Constraint[]::new)).split(inner);

        // Render message lines
        for (int i = 0; i < wrappedLines.size(); i++) {
            frame.renderWidget(Paragraph.from(Line.styled(
                    wrappedLines.get(i), Style.EMPTY.fg(borderColor).bg(theme.modalBg()))), rows.get(1 + i));
        }

        var btnSpans = new ArrayList<Span>();
        addKey(btnSpans, confirmKey, confirmLabel);
        addKey(btnSpans, "any key", "Cancel");
        frame.renderWidget(Paragraph.from(Line.from(btnSpans)), rows.get(rows.size() - 1));
    }

    private static List<String> wrapText(String text, int width) {
        var result = new ArrayList<String>();
        for (var paragraph : text.split("\n")) {
            if (paragraph.isEmpty()) {
                result.add("");
                continue;
            }
            var words = paragraph.split("\\s+");
            var line = new StringBuilder();
            for (var word : words) {
                if (line.length() + word.length() + (line.length() > 0 ? 1 : 0) > width) {
                    if (line.length() > 0) {
                        result.add(line.toString());
                        line = new StringBuilder();
                    }
                    // If a single word is longer than width, add it anyway
                    if (word.length() > width) {
                        result.add(word);
                        continue;
                    }
                }
                if (line.length() > 0) line.append(" ");
                line.append(word);
            }
            if (line.length() > 0) {
                result.add(line.toString());
            }
        }
        return result;
    }

    void renderErrorModal(Frame frame, Rect screen, String message) {
        var lines = message.lines().toList();
        int height = lines.size() + 5;
        int width = Math.min(lines.stream().mapToInt(String::length).max().orElse(30) + 6, screen.width() - 4);
        var modalArea = centerRect(screen, Math.max(width, 40), height);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(styledTitle(" Error ", theme.modalWarn()))
                .borderStyle(Style.EMPTY.fg(theme.modalWarn()))
                .style(Style.EMPTY.bg(theme.modalBg()))
                .padding(Padding.horizontal(1))
                .build();
        renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);
        var constraints = new ArrayList<Constraint>();
        for (int i = 0; i < lines.size(); i++) constraints.add(Constraint.length(1));
        constraints.add(Constraint.length(1));
        constraints.add(Constraint.fill());
        var rows = Layout.vertical().constraints(constraints).split(inner);
        for (int i = 0; i < lines.size(); i++) {
            frame.renderWidget(Paragraph.from(Line.styled(
                    " " + lines.get(i), Style.EMPTY.fg(theme.modalFg()).bg(theme.modalBg()))), rows.get(i));
        }
        var hintSpans = new ArrayList<Span>();
        addKey(hintSpans, "any key", "Dismiss");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(rows.size() - 1));
    }

    void renderProgressOverlay(Frame frame, Rect screen, String message) {
        int width = Math.min(message.length() + 8, screen.width() - 4);
        var modalArea = centerRect(screen, width, 3);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .borderStyle(Style.EMPTY.fg(theme.modalAccent()))
                .style(Style.EMPTY.bg(theme.modalBg()))
                .padding(Padding.horizontal(1))
                .build();
        renderBlock(frame, block, modalArea);
        frame.renderWidget(Paragraph.from(Line.styled(
                " " + message,
                Style.EMPTY.fg(theme.modalFg()).bg(theme.modalBg()))), block.inner(modalArea));
    }
}
