package dev.incusspawn.command;

import dev.incusspawn.tui.TuiTheme;
import dev.incusspawn.tui.WarningLog;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code w} dialog: every warning in the {@link WarningLog}, newest first, in full.
 * <p>
 * The status line shows one warning on one line; this is where the rest are read, including
 * the multi-line ones (a YAML fix to copy, a command to run).
 */
final class WarningsModal {

    /** The browse-mode key that opens this dialog, also named in the header's warning badge. */
    static final String KEY = "w";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_WIDTH = 100;

    private final ModalRenderer modal;
    private final TuiTheme theme;
    private final WarningLog log;
    private final Runnable onClear;
    private int scrollOffset;

    /** {@code onClear} runs after the log is cleared, so warnings still true can come back. */
    WarningsModal(ModalRenderer modal, TuiTheme theme, WarningLog log, Runnable onClear) {
        this.modal = modal;
        this.theme = theme;
        this.log = log;
        this.onClear = onClear;
    }

    /** Called when the dialog opens: start at the newest, and count everything as read. */
    void open() {
        scrollOffset = 0;
        log.markRead();
    }

    /** Handles a key; false means close the dialog. */
    boolean handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC() || key.isChar('q') || key.isChar(KEY.charAt(0))) {
            return false;
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            scrollOffset++;
        } else if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            if (scrollOffset > 0) scrollOffset--;
        } else if (key.isKey(KeyCode.HOME) || key.isChar('g')) {
            scrollOffset = 0;
        } else if (key.isKey(KeyCode.END) || key.isChar('G')) {
            scrollOffset = Integer.MAX_VALUE; // capped during render
        } else if (key.isChar('c')) {
            log.clear();
            onClear.run();
            scrollOffset = 0;
        }
        return true;
    }

    void render(Frame frame, Rect screen) {
        var entries = log.entries();
        int width = Math.min(MAX_WIDTH, screen.width() - 4);
        int textWidth = width - 4; // borders and horizontal padding
        var lines = entries.isEmpty()
                ? List.of(Line.styled("No warnings.", Style.EMPTY.fg(theme.textDim()).bg(modal.bg())))
                : lines(entries, textWidth);

        int modalHeight = Math.min(lines.size() + 4, screen.height() - 2);
        var modalArea = ModalRenderer.centerRect(screen, width, modalHeight);
        var title = entries.isEmpty() ? " Warnings "
                : " Warnings (" + entries.size() + ") ";
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(modal.styledTitle(title, modal.warn()))
                .borderStyle(Style.EMPTY.fg(modal.warn()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        modal.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);
        var rows = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);

        scrollOffset = modal.renderScrollableContent(frame, rows.get(0), lines, scrollOffset);

        var hints = new ArrayList<Span>();
        modal.addKey(hints, "Esc", "Close");
        if (!entries.isEmpty()) modal.addKey(hints, "c", "Clear");
        frame.renderWidget(Paragraph.from(Line.from(hints)), rows.get(1));
    }

    /** Newest first: a timestamped first line, the rest indented under it, a gap between. */
    private List<Line> lines(List<WarningLog.Entry> entries, int textWidth) {
        var lines = new ArrayList<Line>();
        var stamp = Style.EMPTY.fg(theme.textDim()).bg(modal.bg());
        var text = Style.EMPTY.fg(modal.fg()).bg(modal.bg());
        var marker = Style.EMPTY.fg(modal.warn()).bg(modal.bg());
        var indent = " ".repeat(TIME.format(entries.get(0).time()).length() + 3);
        for (int i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i);
            var wrapped = ModalRenderer.wrapText(entry.message(), textWidth - indent.length());
            for (int j = 0; j < wrapped.size(); j++) {
                if (j == 0) {
                    lines.add(Line.from(List.of(
                            Span.styled(TIME.format(entry.time()) + " ", stamp),
                            Span.styled("⚠ ", marker),
                            Span.styled(wrapped.get(0), text))));
                } else {
                    lines.add(Line.styled(indent + wrapped.get(j), text));
                }
            }
            if (i > 0) lines.add(Line.styled("", Style.EMPTY));
        }
        return lines;
    }
}
