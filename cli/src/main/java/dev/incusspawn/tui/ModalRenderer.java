package dev.incusspawn.tui;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Padding;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.checkbox.Checkbox;
import dev.tamboui.widgets.checkbox.CheckboxState;
import dev.tamboui.widgets.input.TextInput;
import dev.tamboui.widgets.input.TextInputState;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.select.Select;
import dev.tamboui.widgets.select.SelectState;
import dev.tamboui.widgets.scrollbar.Scrollbar;
import dev.tamboui.widgets.scrollbar.ScrollbarOrientation;
import dev.tamboui.widgets.scrollbar.ScrollbarState;

final class ModalRenderer {

    private final TuiTheme theme;
    private final Checkbox checkbox;
    private final Select select;

    ModalRenderer(TuiTheme theme) {
        this.theme = theme;
        this.checkbox = Checkbox.builder()
                .checkedColor(theme.checkEnabled())
                .uncheckedColor(theme.checkDisabled())
                .style(Style.EMPTY.bg(theme.modalBg()))
                .build();
        this.select = Select.builder()
                .selectedColor(theme.focusedLabel())
                .indicatorColor(theme.modalAccent())
                .style(Style.EMPTY.bg(theme.modalBg()))
                .build();
    }

    Checkbox checkbox() { return checkbox; }
    Select select() { return select; }

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

    /** The columns a hint line built with {@link #addKey} takes, without the padding after its last label. */
    static int hintWidth(List<Span> spans) {
        if (spans.isEmpty()) return 0;
        var last = spans.getLast().content();
        return spans.stream().mapToInt(Span::width).sum() - (last.length() - last.stripTrailing().length());
    }

    /** Width of the focus-marker column (" ▸ ") that precedes every form field. */
    static final int FIELD_PREFIX = 3;

    /** Column where a toggle's label text starts: past the focus marker and the checkbox. */
    int toggleLabelColumn() {
        return FIELD_PREFIX + checkbox.width() + 1;
    }

    /** A field label with the focus marker, for fields whose input is rendered separately. */
    void renderLabel(Frame frame, Rect area, String label, boolean focused) {
        frame.renderWidget(Paragraph.from(Line.from(
                Span.styled(focused ? " ▸ " : "   ", Style.EMPTY.fg(theme.modalAccent()).bg(theme.modalBg())),
                Span.styled(label, Style.EMPTY.fg(focused ? theme.focusedLabel() : theme.modalFg())
                        .bg(theme.modalBg())))), area);
    }

    /** A read-only field: its label aligned with the other fields, then its value. */
    void renderValue(Frame frame, Rect area, String label, String value) {
        frame.renderWidget(Paragraph.from(Line.from(
                Span.styled(" ".repeat(FIELD_PREFIX) + label + " ", Style.EMPTY.fg(theme.modalFg()).bg(theme.modalBg())),
                Span.styled(value, Style.EMPTY.fg(theme.modalAccent()).bg(theme.modalBg())))), area);
    }

    void renderToggle(Frame frame, Rect area,
                              String label, CheckboxState state, boolean focused) {
        var cols = Layout.horizontal()
                .constraints(Constraint.length(FIELD_PREFIX), Constraint.length(toggleLabelColumn() - FIELD_PREFIX), Constraint.fill())
                .split(area);
        var prefix = focused ? "▸" : " ";
        var labelColor = focused ? theme.focusedLabel() : theme.modalFg();
        frame.renderWidget(Paragraph.from(Line.styled(" " + prefix + " ",
                Style.EMPTY.fg(theme.modalAccent()).bg(theme.modalBg()))), cols.get(0));
        frame.renderStatefulWidget(checkbox, cols.get(1), state);
        frame.renderWidget(Paragraph.from(Line.styled(label,
                Style.EMPTY.fg(labelColor).bg(theme.modalBg()))), cols.get(2));
    }

    /** {@link #renderToggle}, then {@code note} dimmed after the label: why the box starts as it does. */
    void renderToggle(Frame frame, Rect area, String label, CheckboxState state, boolean focused, String note) {
        renderToggle(frame, area, label, state, focused);
        if (note == null) return;
        var cols = Layout.horizontal()
                .constraints(Constraint.length(toggleLabelColumn() + CharWidth.of(label)), Constraint.fill())
                .split(area);
        frame.renderWidget(Paragraph.from(Line.styled(" — " + note,
                Style.EMPTY.fg(theme.textDim()).bg(theme.modalBg()))), cols.get(1));
    }

    void renderDisabledLine(Frame frame, Rect area, String label, String reason) {
        var cols = Layout.horizontal()
                .constraints(Constraint.length(FIELD_PREFIX), Constraint.length(toggleLabelColumn() - FIELD_PREFIX), Constraint.fill())
                .split(area);
        frame.renderWidget(Paragraph.from(Line.styled(label + " — " + reason,
                Style.EMPTY.fg(theme.textDim()).bg(theme.modalBg()))), cols.get(2));
    }

    void renderSelect(Frame frame, Rect area,
                              String label, SelectState state, boolean focused) {
        var cols = Layout.horizontal()
                .constraints(Constraint.length(FIELD_PREFIX), Constraint.length(CharWidth.of(label) + 1), Constraint.fill())
                .split(area);
        var prefix = focused ? "▸" : " ";
        var labelColor = focused ? theme.focusedLabel() : theme.modalFg();
        frame.renderWidget(Paragraph.from(Line.styled(" " + prefix + " ",
                Style.EMPTY.fg(theme.modalAccent()).bg(theme.modalBg()))), cols.get(0));
        frame.renderWidget(Paragraph.from(Line.styled(label + " ",
                Style.EMPTY.fg(labelColor).bg(theme.modalBg()))), cols.get(1));
        frame.renderStatefulWidget(select, cols.get(2), state);
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
        var wrappedLines = confirmLines(message, screen);
        int textWidth = wrappedLines.stream().mapToInt(CharWidth::of).max().orElse(0);
        var modalArea = centerRect(screen, Math.max(CONFIRM_TEXT_WIDTH, textWidth) + 6, confirmModalHeight(wrappedLines.size()));
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

    /** The width of a confirm dialog's text: the dialog is six cells wider, for its frame and padding. */
    static final int CONFIRM_TEXT_WIDTH = 48;

    /** A confirm dialog's height for {@code textLines} of wrapped text: frame, spacing and buttons add six. */
    static int confirmModalHeight(int textLines) {
        return 2 + 1 + textLines + 1 + 2;
    }

    /**
     * The confirm dialog's text, wrapped to {@link #CONFIRM_TEXT_WIDTH}; a word too long for that,
     * or text too tall for the screen, widens it as far as the screen allows. Text that still does
     * not fit keeps the buttons on screen and says how much is hidden, rather than clipping silently.
     */
    static List<String> confirmLines(String message, Rect screen) {
        var lines = wrapText(message, CONFIRM_TEXT_WIDTH);
        int widest = lines.stream().mapToInt(CharWidth::of).max().orElse(0);
        int widestAllowed = screen.width() - 10;   // the frame and padding, and a margin each side
        if ((widest > CONFIRM_TEXT_WIDTH || confirmModalHeight(lines.size()) > screen.height())
                && widestAllowed > CONFIRM_TEXT_WIDTH) {
            lines = wrapText(message, widestAllowed);
        }
        int room = screen.height() - confirmModalHeight(0);
        if (lines.size() > room && room > 0) {
            int hidden = lines.size() - room + 1;
            lines = new ArrayList<>(lines.subList(0, room - 1));
            lines.add(moreLinesHint(hidden));
        }
        return lines;
    }

    /** Within {@link #CONFIRM_TEXT_WIDTH}, so it is not cut on a screen too narrow to widen the dialog. */
    static String moreLinesHint(int hidden) {
        return "… " + hidden + " more lines; enlarge the terminal";
    }

    /**
     * A confirm dialog's message naming every one of {@code names} (#1482): {@code heading} and a
     * colon, then one name per line while the dialog fits {@code screenHeight}, else {@code heading}
     * and the names wrapped as one list; never "and N more". {@code tail} follows as its own paragraph.
     */
    static String listMessage(String heading, List<String> names, String tail, int screenHeight) {
        var after = tail.isEmpty() ? "" : "\n\n" + tail;
        var oneLineEach = heading + ":\n  " + String.join("\n  ", names) + after;
        if (confirmModalHeight(wrapText(oneLineEach, CONFIRM_TEXT_WIDTH).size()) <= screenHeight) return oneLineEach;
        return heading + " " + String.join(", ", names) + "." + after;
    }

    /**
     * Word-wrap each line of {@code text} to {@code width}. A line's leading indentation is kept
     * on it and on its continuation lines, so an indented snippet (a YAML fix) keeps its shape.
     */
    static List<String> wrapText(String text, int width) {
        var result = new ArrayList<String>();
        for (var paragraph : text.split("\n")) {
            if (paragraph.isBlank()) {
                result.add("");
                continue;
            }
            var indent = paragraph.substring(0, paragraph.length() - paragraph.stripLeading().length());
            if (indent.length() > width / 2) indent = "";
            for (var wrapped : wrapWords(paragraph.strip(), width - indent.length())) {
                result.add(indent + wrapped);
            }
        }
        return result;
    }

    /**
     * Greedy word wrap measured in terminal cells (#1412): a CJK ideograph or an emoji takes two.
     * A word longer than {@code width} is left whole for the view to clip.
     */
    static List<String> wrapWords(String paragraph, int width) {
        var result = new ArrayList<String>();
        var words = paragraph.split("\\s+");
        var line = new StringBuilder();
        int lineWidth = 0;
        for (var word : words) {
            int wordWidth = CharWidth.of(word);
            if (lineWidth + wordWidth + (line.length() > 0 ? 1 : 0) > width) {
                if (line.length() > 0) {
                    result.add(line.toString());
                    line = new StringBuilder();
                    lineWidth = 0;
                }
                // If a single word is longer than width, add it anyway
                if (wordWidth > width) {
                    result.add(word);
                    continue;
                }
            }
            if (line.length() > 0) {
                line.append(" ");
                lineWidth++;
            }
            line.append(word);
            lineWidth += wordWidth;
        }
        if (line.length() > 0) {
            result.add(line.toString());
        }
        return result;
    }

    void renderErrorModal(Frame frame, Rect screen, String message) {
        var lines = message.lines().toList();
        int height = lines.size() + 5;
        int width = Math.min(lines.stream().mapToInt(CharWidth::of).max().orElse(30) + 6, screen.width() - 4);
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
        int width = Math.min(CharWidth.of(message) + 8, screen.width() - 4);
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

    int renderScrollableContent(Frame frame, Rect contentArea,
                                List<Line> contentLines, int scrollOffset) {
        boolean needsScroll = contentLines.size() > contentArea.height();
        Rect textArea;
        Rect scrollbarArea;
        if (needsScroll) {
            var cols = Layout.horizontal()
                    .constraints(Constraint.fill(), Constraint.length(1))
                    .split(contentArea);
            textArea = cols.get(0);
            scrollbarArea = cols.get(1);
        } else {
            textArea = contentArea;
            scrollbarArea = null;
        }

        int visibleHeight = textArea.height();
        int maxScroll = Math.max(0, contentLines.size() - visibleHeight);
        scrollOffset = Math.min(scrollOffset, maxScroll);

        var visibleLines = contentLines.subList(
                scrollOffset,
                Math.min(scrollOffset + visibleHeight, contentLines.size()));
        frame.renderWidget(Paragraph.from(Text.from(visibleLines)), textArea);

        if (scrollbarArea != null) {
            var scrollbar = Scrollbar.builder()
                    .orientation(ScrollbarOrientation.VERTICAL_RIGHT)
                    .thumbStyle(Style.EMPTY.fg(accent()))
                    .trackStyle(Style.EMPTY.fg(theme.scrollbarTrack()))
                    .style(Style.EMPTY.bg(bg()))
                    .build();
            var state = new ScrollbarState()
                    .contentLength(contentLines.size())
                    .viewportContentLength(visibleHeight)
                    .position(scrollOffset);
            frame.renderStatefulWidget(scrollbar, scrollbarArea, state);
        }
        return scrollOffset;
    }
}
