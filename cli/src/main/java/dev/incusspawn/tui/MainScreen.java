package dev.incusspawn.tui;

import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.OutputFormat;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.input.TextInput;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.scrollbar.Scrollbar;
import dev.tamboui.widgets.scrollbar.ScrollbarOrientation;
import dev.tamboui.widgets.scrollbar.ScrollbarState;
import dev.tamboui.widgets.table.TableState;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import static dev.incusspawn.tui.UsageFormat.gibShort;
import static dev.incusspawn.tui.UsageFormat.runningSummary;
import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.command.OverviewCommand;
import dev.incusspawn.command.InstanceListing.TemplateInfo;

/**
 * The TUI's main screen below its dialogs: the header with the storage gauge, the templates and
 * instances tables, the legend, the tab bar, the search bar, the context line and the key bar. It
 * draws what {@link Tui} holds and changes none of it.
 */
final class MainScreen {
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\n\\r]+");

    private final Tui tui;

    MainScreen(Tui tui) {
        this.tui = tui;
    }

    // Compact storage gauge width targets for the header's right-hand side.
    private static final int HEADER_BAR_MIN = 10;  // floor for the bar fill (excl. borders)

    private static final int HEADER_BAR_MAX = 32;  // cap so the bar stays a gauge, not a ruler

    /**
     * The header row above the panels: the brand chip on the left and, when pool usage is known, a
     * compact storage gauge at the right. The gauge's colour says the threshold (good, caution,
     * bad) and its line's weight the level, so it reads without colour. The count of running
     * instances, then the gauge's line, then the gauge drop out on a narrow terminal, so the brand
     * always stays.
     */
    void renderHeader(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        // The first thing drawn in a frame: no preview divider until the Work tab draws one
        previewDividerX = -1;
        if (area.width() <= 0) return;
        // The header is not a band: only the chip is filled
        var bg = Color.RESET;

        // Left: brand chip (accent tag) + dim version. Stands out, ~5 cells.
        var left = new ArrayList<Span>();
        left.add(Span.styled(" isx ", Style.EMPTY.bold().fg(tui.theme.onAccent()).bg(tui.theme.accent())));
        int leftW = 5;
        var version = tui.displayVersion();
        if (version != null && !version.isBlank()) {
            var vtext = "  " + version;
            left.add(Span.styled(vtext, Style.EMPTY.fg(tui.theme.textFaint()).bg(bg)));
            leftW += vtext.length();
        }

        // Right: compact storage gauge, only when we have pool usage and room for it. Built first
        // so the centre badge can claim the leftover gap without ever evicting the gauge.
        var gauge = new ArrayList<Span>();
        int gaugeW = 0;
        if (tui.poolUsage != null && tui.poolUsage.totalBytes() > 0) {
            int percent = tui.poolUsage.percent();
            boolean critical = tui.poolUsage.isCritical();
            boolean low = tui.poolUsage.isLow();
            Color fillColor = critical ? tui.theme.statusFailure()
                    : low ? tui.theme.statusWarning()
                    : tui.theme.statusRunning();
            var glabel = critical ? "⚠ Storage " : "Storage ";
            var readout = "  " + percent + "%  " + gibShort(tui.poolUsage.usedBytes())
                    + "/" + gibShort(tui.poolUsage.totalBytes());

            int fixed = glabel.length() + readout.length();       // gauge text, sans bar/borders
            int avail = area.width() - leftW - 1;                 // -1 keeps at least one filler cell
            // Grow the bar with the terminal (up to HEADER_BAR_MAX) so it stays substantial on wide
            // screens and the whole gauge sits closer to centre instead of hugging the far edge.
            int idealBar = Math.max(HEADER_BAR_MIN, Math.min(HEADER_BAR_MAX, area.width() / 5));
            int barInner = Math.min(idealBar, avail - fixed - 2 /*borders*/);
            int candidateW = fixed + (barInner >= 1 ? barInner + 2 : 0);

            if (avail - candidateW >= 0) {                        // gauge (maybe sans bar) fits
                gaugeW = candidateW;
                gauge.add(Span.styled(glabel, Style.EMPTY.fg(critical ? fillColor : tui.theme.textDim()).bg(bg)));
                if (barInner >= 1) {
                    // A line, heavy as far as the pool is used and light beyond: the weight says the
                    // level where there is no colour
                    int cells = barInner + 2;
                    int used = Math.clamp(Math.round(percent / 100f * cells), percent > 0 ? 1 : 0, cells);
                    gauge.add(Span.styled("━".repeat(used), Style.EMPTY.fg(fillColor).bg(bg)));
                    gauge.add(Span.styled("─".repeat(cells - used), Style.EMPTY.fg(tui.theme.panelBorderUnfocused()).bg(bg)));
                }
                gauge.add(Span.styled(readout, Style.EMPTY.fg(fillColor).bg(bg)));
                if (low) {
                    // From the hint level the Work tab is named too, where it fits (#2563)
                    var hint = "  C:clean";
                    var withWork = hint + "  Work tab";
                    if (tui.poolUsage.isHint() && avail - gaugeW >= withWork.length()) hint = withWork;
                    if (avail - gaugeW >= hint.length()) {
                        gauge.add(Span.styled(hint, Style.EMPTY.fg(tui.theme.textDim()).bg(bg)));
                        gaugeW += hint.length();
                    }
                }
            }
        }

        // Centre: auth-error warning (highest priority) or a quiet "N running" badge,
        // filling the gap between the version and the gauge.
        var centre = new ArrayList<Span>();
        int centreW = 0;
        var pi = tui.proxyInfo;
        if (pi != null && pi.hasAuthError()) {
            var label = "  ⚠ Auth expired — run: " + pi.authRemediationHint();
            int need = label.length();
            if (area.width() - leftW - gaugeW - need >= 1) {
                centre.add(Span.styled(label, Style.EMPTY.bold().fg(tui.theme.statusWarning()).bg(bg)));
                centreW = need;
            }
        }
        if (centreW == 0) {
            var skew = tui.applianceSkewMessage;
            if (skew != null) {
                var label = "  !! " + skew;
                int need = label.length();
                if (area.width() - leftW - gaugeW - need >= 1) {
                    centre.add(Span.styled(label, Style.EMPTY.bold().fg(tui.theme.statusWarning()).bg(bg)));
                    centreW = need;
                }
            }
        }
        if (centreW == 0) {
            int unread = tui.warningLog.unread();
            if (unread > 0) {
                var label = "  ⚠ " + unread + (unread == 1 ? " warning" : " warnings")
                        + " (" + WarningsModal.KEY + ")";
                int need = label.length();
                if (area.width() - leftW - gaugeW - need >= 1) {
                    centre.add(Span.styled(label, Style.EMPTY.bold().fg(tui.theme.statusWarning()).bg(bg)));
                    centreW = need;
                }
            }
        }
        if (centreW == 0) {
            var badge = runningSummary(runningCounts(BadgeKind.CONTAINER), runningCounts(BadgeKind.VM));
            if (!badge.isEmpty()) {
                int need = 4 + badge.length();                    // "  ● " prefix + text
                if (area.width() - leftW - gaugeW - need >= 1) {  // keep >=1 filler cell
                    centre.add(Span.styled("  ● ", Style.EMPTY.fg(tui.theme.statusRunning()).bg(bg)));
                    centre.add(Span.styled(badge, Style.EMPTY.fg(tui.theme.contextSecondaryFg()).bg(bg)));
                    centreW = need;
                }
            }
        }

        // Assemble left → centre → filler → gauge.
        var spans = new ArrayList<Span>(left);
        spans.addAll(centre);
        int fillerW = area.width() - leftW - centreW - gaugeW;
        if (fillerW > 0) spans.add(Span.styled(" ".repeat(fillerW), Style.EMPTY.bg(bg)));
        spans.addAll(gauge);
        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }

    /** What the tab bar says of its keys, where there is room after the tabs. */
    static final String TABS_HINT = "←/→ Switch view ";

    /**
     * The tab bar: every {@link Tui.View} in order, the showing one in brackets as well as in
     * colour so it reads on a mono terminal. A tab that draws one list carries its row count,
     * which is what says how much the other tabs hold while they are out of sight.
     */
    void renderTabs(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        if (area.width() <= 0 || area.height() <= 0) return;
        var spans = new ArrayList<Span>();
        int width = 0;
        for (var view : Tui.View.values()) {
            boolean showing = view == tui.view;
            // Each tab is a filled cell, so where a label begins and ends is drawn and not guessed
            // from the gaps: the showing one in the selection's fill, which says "you are here"
            // for a row too, and bold, so it reads where a terminal has no tint
            spans.add(Span.styled(" " + tabLabel(view) + " ", showing
                    ? Style.EMPTY.bold().fg(tui.theme.accent()).bg(tui.theme.highlightBg())
                    : Style.EMPTY.fg(tui.theme.textDim()).bg(tui.theme.modalBg())));
            spans.add(Span.raw(" "));
            // Where the tab is, for the tint that says its count changed
            tui.motion.row(Tui.tabKey(view), new dev.tamboui.layout.Rect(area.left() + width, area.top(),
                    Math.min(2 + tabLabel(view).length(), Math.max(0, area.width() - width)), 1));
            width += 3 + tabLabel(view).length();
        }
        int gap = area.width() - width - TABS_HINT.length();
        if (gap >= 2) {
            spans.add(Span.raw(" ".repeat(gap)));
            spans.add(Span.styled(TABS_HINT, Style.EMPTY.fg(tui.theme.textFaint())));
        }
        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }

    private String tabLabel(Tui.View view) {
        return switch (view) {
            case OVERVIEW -> view.label;
            case TEMPLATES -> view.label + " " + tui.templateEntries.size();
            case INSTANCES -> view.label + " " + tui.entries.size();
            case WORK -> view.label;
        };
    }

    private enum BadgeKind { CONTAINER, VM }

    /** Count running instances of one kind, across the unfiltered set. */
    private int runningCounts(BadgeKind kind) {
        var pool = tui.allEntries != null ? tui.allEntries : tui.entries;
        if (pool == null) return 0;
        int n = 0;
        for (var e : pool) {
            if (!Tui.isRunning(e)) continue;
            boolean vm = "virtual-machine".equals(e.runtime());
            if ((kind == BadgeKind.VM) == vm) n++;
        }
        return n;
    }

    /** The rows a panel takes beside its table's rows: its title rule and the table's header. */
    static final int PANEL_ROWS = 2;

    /**
     * A panel: a rule across its top that carries its title, and nothing at its sides or below.
     * The focused panel's title is in the accent; the cursor says the same without colour. The
     * padding keeps the table as wide as it was inside a frame, so the column thresholds hold.
     */
    private Block panel(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area, String title, boolean focused) {
        var block = Block.builder()
                .borders(java.util.EnumSet.of(Borders.TOP)).borderType(BorderType.PLAIN)
                .borderStyle(Style.EMPTY.fg(tui.theme.panelBorderUnfocused()))
                .padding(dev.tamboui.layout.Padding.horizontal(1)).build();
        frame.renderWidget(block, area);
        // Drawn after the rule: a block gives its title the rule's colour
        if (area.height() > 0) {
            frame.buffer().setLine(area.left(), area.top(), Line.from(Span.styled(title, focused
                    ? Style.EMPTY.bold().fg(tui.theme.accent()) : Style.EMPTY.fg(tui.theme.textDim()))), area.width());
        }
        return block;
    }

    /** A table's header: quiet, since the rows are what is read. */
    private Style headerStyle() {
        return Style.EMPTY.fg(tui.theme.textFaint());
    }

    /** The row under the cursor: the one filled row on the screen. */
    private Style selectionStyle() {
        return Style.EMPTY.bg(tui.theme.highlightBg()).bold();
    }

    /** The key a row's flash is found by: an instance's name. */
    static String instanceKey(String name) {
        return "instance:" + name;
    }

    void renderTemplateTable(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        boolean focused = tui.focusedPanel == Tui.Panel.TEMPLATES;

        if (tui.templateEntries.isEmpty()) {
            var block = panel(frame, area, " Templates ", focused);
            var inner = block.inner(area);
            if (inner.height() > 0) {
                frame.renderWidget(Paragraph.from(
                        Line.styled(searchHidesAll(tui.allTemplateEntries)
                                ? "  No template matches the search." : "  No template definitions found.",
                                Style.EMPTY.fg(tui.theme.textDim()))), inner);
            }
            return;
        }

        var block = panel(frame, area, " Templates ", focused);
        var inner = block.inner(area);

        boolean showLegend = tui.anyTemplateOutdated || tui.anyDefinitionChanged || tui.anyParentRebuilt
                || tui.anyMissingDefinition;
        dev.tamboui.layout.Rect tableArea;
        if (showLegend && inner.height() > 2) {
            var parts = splitVertical(inner, inner.height() - 1, 1);
            tableArea = parts.get(0);
            renderLegend(frame, parts.get(1));
        } else {
            tableArea = inner;
        }

        int visibleRows = Math.max(tableArea.height() - 1, 1);
        boolean needsScroll = tui.templateRows.size() > visibleRows;
        dev.tamboui.layout.Rect actualTableArea;
        dev.tamboui.layout.Rect scrollArea;
        if (needsScroll) {
            var cols = Layout.horizontal()
                    .constraints(Constraint.fill(), Constraint.length(1))
                    .split(tableArea);
            actualTableArea = cols.get(0);
            scrollArea = cols.get(1);
        } else {
            actualTableArea = tableArea;
            scrollArea = null;
        }

        // The description is cut first and the name last; a name is as wide as the longest of every
        // template, so the columns hold still while a search narrows the list
        var templateWidths = ColumnFit.widths(List.of(
                new ColumnFit.Column("NAME", 12, tui.templateNatural[0], 0, false),
                new ColumnFit.Column("BUILT", 9, tui.templateNatural[1], 2, false),
                ColumnFit.Column.fixed("DISK", Math.max(4, tui.templateNatural[2])),
                new ColumnFit.Column("DESCRIPTION", 10, tui.templateNatural[3], 1, true)),
                actualTableArea.width() - HIGHLIGHT_WIDTH);
        var tableBuilder = Table.builder()
                .header(Row.from("NAME", "BUILT", "DISK", "DESCRIPTION")
                        .style(headerStyle()))
                .rows(ColumnFit.clipped(tui.templateRows, templateWidths))
                .widths(ColumnFit.constraints(templateWidths))
                .highlightSymbol(focused ? "\u25b8 " : "  ")
                // Always reserved, so renderMarks can draw into it
                .highlightSpacing(Table.HighlightSpacing.ALWAYS);

        if (focused) {
            var highlightStyle = selectionStyle();
            // Preserve modifiers from selected row if it has a pending operation
            var selected = tui.selectedTemplate();
            if (selected != null && !selected.pendingOp().isEmpty()) {
                if (Metadata.OP_DELETING.equals(selected.pendingOp())) {
                    highlightStyle = highlightStyle.addModifier(dev.tamboui.style.Modifier.DIM)
                            .addModifier(dev.tamboui.style.Modifier.ITALIC);
                } else if (Metadata.OP_STOPPING.equals(selected.pendingOp()) || Metadata.OP_RESTARTING.equals(selected.pendingOp())) {
                    highlightStyle = highlightStyle.addModifier(dev.tamboui.style.Modifier.DIM);
                }
            }
            tableBuilder.highlightStyle(highlightStyle);
        } else {
            tableBuilder.highlightStyle(Style.EMPTY);
        }

        frame.renderStatefulWidget(tableBuilder.build(), actualTableArea, tui.templateTableState);
        renderMarks(frame, actualTableArea, tui.templateTableState.offset(), tui.templateRows, tui::templateRowMarked);

        if (scrollArea != null) {
            var scrollbar = Scrollbar.builder()
                    .orientation(ScrollbarOrientation.VERTICAL_RIGHT)
                    .thumbStyle(Style.EMPTY.fg(tui.theme.textFaint()))
                    .trackStyle(Style.EMPTY.fg(tui.theme.scrollbarTrack()))
                    .build();
            var scrollState = new ScrollbarState()
                    .contentLength(tui.templateRows.size())
                    .viewportContentLength(visibleRows)
                    .position(tui.templateTableState.offset());
            frame.renderStatefulWidget(scrollbar, scrollArea, scrollState);
        }
    }

    /**
     * Draws {@link Glyphs#MARKED} on each visible marked row (#1131, #1482) in the second cell of the cursor's
     * gutter, which is always reserved and otherwise blank, so marking a row moves nothing else.
     * The table has one header line; a row of two lines is marked on its first.
     */
    private void renderMarks(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect tableArea, int offset,
                             List<Row> rows, java.util.function.IntPredicate marked) {
        var buffer = frame.buffer();
        int x = tableArea.left() + 1;
        var tops = rowTops(rows, offset, tableArea);
        for (int row = 0; row < tops.length; row++) {
            int y = tops[row];
            if (y < 0 || !marked.test(row)) continue;
            var style = buffer.get(x, y).style().patch(Style.EMPTY.fg(tui.theme.contextAccentFg()));
            buffer.setString(x, y, Glyphs.MARKED, style);
        }
    }

    /**
     * The screen line each row starts on below the header, or -1 for a row off screen: rows may be
     * two lines tall, and the table's offset counts lines, from a row's first.
     */
    static int[] rowTops(List<Row> rows, int offset, dev.tamboui.layout.Rect tableArea) {
        var tops = new int[rows.size()];
        int line = 0;
        // As the table draws them: the first row the offset does not pass is drawn whole from the
        // top, even when the offset cuts it, as tamboui's own scrolling does in a viewport
        // shorter than a row; each next row follows it
        int y = tableArea.top() + 1;
        for (int row = 0; row < rows.size(); row++) {
            int height = rows.get(row).height();
            if (line + height <= offset || y >= tableArea.bottom()) {
                tops[row] = -1;
            } else {
                tops[row] = y;
                y += height;
            }
            line += height;
        }
        return tops;
    }

    /**
     * The table's offset, in lines, at a row's first line: as near {@code offset} as keeps the whole
     * selected row on screen, and no further down than the last rows need. The table draws a row
     * cut by the offset whole, so an offset inside a row would push the selected row's second
     * line off the screen.
     */
    static int alignedOffset(int offset, List<Row> rows, Integer selected, int visible) {
        int n = rows.size();
        var starts = new int[n + 1];
        for (int i = 0; i < n; i++) starts[i + 1] = starts[i] + rows.get(i).height();
        int total = starts[n];
        int at = 0;
        for (int i = 0; i < n; i++) if (starts[i] <= offset) at = i;
        if (selected != null && selected >= 0 && selected < n) {
            if (starts[selected] < starts[at]) at = selected;
            while (at < selected && starts[selected + 1] > starts[at] + visible) at++;
        }
        while (at > 0 && total - starts[at - 1] <= visible) at--;
        return n == 0 ? 0 : starts[at];
    }

    /**
     * What moves on the instance rows: each row says where it is, for a flash that follows it, and
     * a row with an operation under way turns its still glyph into the spinner and shimmers.
     */
    private void renderRowMotion(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect tableArea, int offset,
                                 List<Row> rows, int nameColumn, boolean work) {
        var buffer = frame.buffer();
        var tops = rowTops(rows, offset, tableArea);
        for (int row = 0; row < tops.length; row++) {
            int y = tops[row];
            if (y < 0) continue;
            var instance = tui.instanceOnRow(row);
            if (instance == null) continue;
            int height = Math.min(rows.get(row).height(), tableArea.bottom() - y);
            var area = new dev.tamboui.layout.Rect(tableArea.left(), y, tableArea.width(), height);
            tui.motion.row(instanceKey(instance.name()), area);
            if (instance.pendingOp().isEmpty() || !tui.motion.on()) continue;
            for (int x = area.left(); x < area.right(); x++) {
                var cell = buffer.get(x, y);
                if (Motion.SPINNER_STILL.equals(cell.symbol())) buffer.set(x, y, cell.symbol(tui.motion.spinner()));
            }
            // The name as drawn, which is what the operation is about: past the cursor gutter and the
            // state glyph, and never past the NAME cell, onto the word after it or the next column
            tui.motion.shimmer(new dev.tamboui.layout.Rect(area.left() + 4, y,
                    shimmerWidth(instance, nameColumn, work, area.width()), 1));
        }
    }

    /** The cells the shimmer crosses: the name as its NAME cell of {@code nameColumn} cells draws it. */
    static int shimmerWidth(InstanceInfo instance, int nameColumn, boolean work, int rowWidth) {
        int drawn = CharWidth.of(drawnName(instance, nameColumn, work));
        return Math.max(0, Math.min(drawn, Math.min(nameColumn - 2, rowWidth - 4)));
    }

    /**
     * The name a NAME cell {@code nameColumn} cells wide draws after its glyph: on the Work tab cut
     * in its middle to leave room for an unusual state's word, on the Instances tab whole, or cut
     * at the cell's end where even that table has no room.
     */
    static String drawnName(InstanceInfo instance, int nameColumn, boolean work) {
        if (work) {
            var word = Tui.unusualState(instance);
            int room = nameColumn - 2 - (word == null ? 0 : 1 + CharWidth.of(word));
            return WorkTable.middleCut(instance.name(), Math.max(1, room));
        }
        int room = Math.max(0, nameColumn - 2);
        return CharWidth.of(instance.name()) <= room ? instance.name() : CharWidth.substringByWidth(instance.name(), room);
    }

    /** No instance yet: the logo, and the one thing to do next, in the middle of the panel. */
    private void renderEmptyInstances(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect inner) {
        var lines = new ArrayList<Line>();
        if (inner.height() >= Glyphs.LOGO.length + 4) {
            for (var row : Glyphs.LOGO) lines.add(gradient(row));
            lines.add(Line.from(""));
        }
        // A search that matches none is not a pool with none: say which, and how to get back
        boolean searched = searchHidesAll(tui.allEntries);
        lines.add(Line.styled(searched ? "No instance matches the search" : "No instances yet", Style.EMPTY.bold()));
        if (searched && inner.height() >= 2) {
            lines.add(Line.from(
                    Span.styled("Press ", Style.EMPTY.fg(tui.theme.textDim())),
                    Span.styled("Esc", Style.EMPTY.bold().fg(tui.theme.accent())),
                    Span.styled(" to show them all", Style.EMPTY.fg(tui.theme.textDim()))));
        } else if (inner.height() >= 2) {
            lines.add(Line.from(
                    Span.styled("Select a template and press ", Style.EMPTY.fg(tui.theme.textDim())),
                    Span.styled("Enter", Style.EMPTY.bold().fg(tui.theme.accent())),
                    Span.styled(" to branch one", Style.EMPTY.fg(tui.theme.textDim()))));
        }
        int top = inner.top() + Math.max(0, (inner.height() - lines.size()) / 2);
        for (int i = 0; i < lines.size() && top + i < inner.bottom(); i++) {
            frame.renderWidget(Paragraph.builder().text(Text.from(lines.get(i)))
                    .alignment(dev.tamboui.layout.Alignment.CENTER).build(),
                    new dev.tamboui.layout.Rect(inner.left(), top + i, inner.width(), 1));
        }
    }

    /** Whether a search, and not an empty listing, is why a panel has no row: {@code all} is the listing before it. */
    private boolean searchHidesAll(List<?> all) {
        return tui.searchActive && all != null && !all.isEmpty();
    }

    /** {@code text} drawn from the accent to its second colour, cell by cell: for the logo alone. */
    private Line gradient(String text) {
        var spans = new ArrayList<Span>();
        int last = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            var colour = tui.theme.depth().blends()
                    ? tui.theme.depth().fit(ColorDepth.mix(tui.theme.accent(), tui.theme.accentAlt(), i / (double) last))
                    : tui.theme.accent();
            spans.add(Span.styled(String.valueOf(text.charAt(i)), Style.EMPTY.bold().fg(colour)));
        }
        return Line.from(spans);
    }

    private void renderLegend(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        var text = "! outdated   \u25b3 changed   \u2191 parent rebuilt"
                + (tui.anyMissingDefinition ? "   \u25cc no definition" : "");
        var padding = Math.max(0, area.width() - text.length());
        var style = Style.EMPTY.fg(tui.theme.textFaint());
        frame.renderWidget(Paragraph.from(Line.from(
                Span.styled(" ".repeat(padding) + text, style))), area);
    }

    /**
     * The narrowest table that shows OWNER (#1720), the inner width of a 100-column terminal
     * (Sanne, review of #1820). Narrower, the column is left out; the instance details show the
     * owner either way. The width was chosen when columns shared the table by minimum widths, and
     * OWNER clipped the others below it. {@link ColumnFit#kept} would now show OWNER wherever it
     * fits, at the cost of RUNTIME, IP, DISK and PARENT, which it leaves out first. The threshold
     * remains so that a table narrower than this keeps those four before it shows an owner.
     */
    static final int OWNER_COLUMN_MIN_WIDTH = 98;

    /**
     * The narrowest table that shows PURPOSE (#2239), the inner width of a 120-column terminal, or
     * of a 110-column one when no OWNER column is shown beside it. Narrower, the column is left out;
     * the instance details show the purpose either way.
     */
    static final int PURPOSE_COLUMN_MIN_WIDTH = 118;

    /**
     * The same without OWNER, the inner width of a 110-column terminal. Like the two above it
     * remains from the layout by minimum widths, for the same reason: narrower, the table keeps
     * RUNTIME, IP, DISK and PARENT before it shows a purpose.
     */
    static final int PURPOSE_ALONE_MIN_WIDTH = 108;

    void renderInstanceTable(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                                      TableState tableState) {
        boolean focused = tui.focusedPanel == Tui.Panel.INSTANCES;

        if (tui.entries.isEmpty()) {
            var block = panel(frame, area, " Instances ", focused);
            renderEmptyInstances(frame, block.inner(area));
            return;
        }

        var block = panel(frame, area, tui.overviewShown() ? overviewTitle() : " Instances ", focused);
        var inner = block.inner(area);
        if (!tui.overviewShown()) {
            renderRows(frame, inner, tableState, focused, null);
            return;
        }
        // While the title says a read is under way, the spinner turns before it, in the caution colour
        for (int at = area.left(), end = Math.min(area.right(), area.left() + CharWidth.of(overviewTitle())); at < end; at++) {
            var cell = frame.buffer().get(at, area.top());
            if (!Motion.SPINNER_STILL.equals(cell.symbol())) continue;
            frame.buffer().set(at, area.top(), cell.symbol(tui.motion.spinner())
                    .patchStyle(Style.EMPTY.fg(tui.theme.statusWarning())));
        }

        // The Work tab: its table, and the selected instance's preview beside it or under it
        var selected = tui.selectedEntry(tableState);
        var tableArea = inner;
        dev.tamboui.layout.Rect previewArea = null;
        var border = Style.EMPTY.fg(tui.theme.panelBorderUnfocused());
        var buffer = frame.buffer();
        boolean beside = area.width() >= WorkTable.SIDE_PREVIEW_MIN_WIDTH;
        // The one test of room for the preview, which the hint and the merge follow (Tui.previewDrawn)
        if (tui.view == Tui.View.WORK) tui.previewFits = beside || inner.height() >= PREVIEW_BELOW_LEAST_ROWS;
        if (tui.previewDrawn() && selected != null) {
            if (beside) {
                int previewWidth = WorkTable.previewWidth(inner.width());
                int tableWidth = inner.width() - previewWidth - 1;
                tableArea = new dev.tamboui.layout.Rect(inner.left(), inner.top(), tableWidth, inner.height());
                int x = inner.left() + tableWidth;
                previewDividerX = x;
                previewArea = new dev.tamboui.layout.Rect(x + 1, inner.top(), previewWidth, inner.height());
                // The title rule meets the divider, and the instance's name stands on it; a title
                // longer than the table keeps all it says, the name after it
                int titleEnd = area.left() + CharWidth.of(overviewTitle());
                int nameAt;
                if (titleEnd <= x) {
                    buffer.setString(x, area.top(), "┬─ ", border);
                    nameAt = x + 3;
                } else {
                    buffer.setString(titleEnd, area.top(), "─ ", border);
                    nameAt = titleEnd + 2;
                }
                previewName(buffer, nameAt, area.top(), selected, Math.min(area.right() - nameAt - 2, previewWidth - 6));
                for (int y = inner.top(); y < inner.bottom(); y++) buffer.setString(x, y, "│", border);
            } else {
                int previewHeight = Math.min(WorkTable.PREVIEW_BELOW_MOST, inner.height() / 2);
                tableArea = new dev.tamboui.layout.Rect(inner.left(), inner.top(), inner.width(), inner.height() - previewHeight);
                int ruleY = tableArea.bottom();
                buffer.setString(inner.left(), ruleY, "─".repeat(inner.width()), border);
                buffer.setString(inner.left(), ruleY, "─ ", border);
                previewName(buffer, inner.left() + 2, ruleY, selected, inner.width() - 6);
                previewArea = new dev.tamboui.layout.Rect(inner.left(), ruleY + 1, inner.width(), previewHeight - 1);
            }
        }
        renderRows(frame, tableArea, tableState, focused, workColumns());
        if (previewArea != null && beside && focused) linkSelectedRow(buffer, previewDividerX, inner);
        if (previewArea != null) renderPreview(frame, previewArea, selected);
    }

    /**
     * The preview's instance on a rule, from {@code x}, within {@code room} cells: its state glyph
     * as its row has it, then its name in the accent. The same mark is then at both ends, the row
     * and its preview. A cursor that lands on another instance lights it ({@link Tui#PREVIEW_TITLE_KEY}).
     */
    private void previewName(Buffer buffer, int x, int y, InstanceInfo selected, int room) {
        if (room <= 2) return;
        var glyph = tui.stateGlyph(selected);
        buffer.setString(x, y, glyph.content(), glyph.style());
        if (!selected.pendingOp().isEmpty() && tui.motion.on()) {
            buffer.set(x, y, buffer.get(x, y).symbol(tui.motion.spinner()));
        }
        var name = WorkTable.middleCut(selected.name(), room - 2);
        buffer.setString(x + 2, y, name + " ", Style.EMPTY.bold().fg(tui.theme.accent()));
        tui.motion.row(Tui.PREVIEW_TITLE_KEY, new dev.tamboui.layout.Rect(x, y, 2 + CharWidth.of(name), 1));
    }

    /**
     * Where the divider passes the selected row it is a heavy line in the accent, on the row's own
     * fill where the fill reaches it: the row opens into its preview, and the eye crosses there.
     * Heavy against light reads with no colour.
     */
    private void linkSelectedRow(Buffer buffer, int x, dev.tamboui.layout.Rect inner) {
        int top = Math.max(selectedTop, inner.top());
        int bottom = Math.min(selectedTop + selectedLines, inner.bottom());
        if (selectedTop < 0 || x <= inner.left() || bottom <= top) return;
        for (int y = top; y < bottom; y++) {
            var style = Style.EMPTY.bold().fg(tui.theme.accent());
            var fill = buffer.get(x - 1, y).style().bg();
            if (fill.isPresent()) style = style.bg(fill.get());
            buffer.setString(x, y, Glyphs.LINK, style);
        }
        tui.motion.row(Tui.PREVIEW_LINK_KEY, new dev.tamboui.layout.Rect(x, top, 1, bottom - top));
    }

    /** The lines the selected row takes in this frame's instance table, from {@code selectedTop}; -1 for none on screen. */
    private int selectedTop = -1;
    private int selectedLines;

    /** The fewest rows the panel needs for the preview under its table. */
    static final int PREVIEW_BELOW_LEAST_ROWS = 8;

    /**
     * Where the Work tab's preview divider stands in this frame, for the footer rule's {@code ┴},
     * or -1 with no preview beside the table.
     */
    int previewDividerX = -1;

    /** The instance table's rows in {@code area}: the Instances tab's columns, or the Work tab's when {@code work} is given. */
    private void renderRows(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect inner, TableState tableState,
                            boolean focused, WorkColumns work) {
        var heights = work != null ? work.rows() : tui.tableRows;
        selectedTop = -1;
        int visibleRows = Math.max(inner.height() - 1, 1);
        int lines = heights.stream().mapToInt(Row::height).sum();
        boolean needsScroll = lines > visibleRows;
        dev.tamboui.layout.Rect actualArea;
        dev.tamboui.layout.Rect scrollArea;
        if (needsScroll) {
            var cols = Layout.horizontal()
                    .constraints(Constraint.fill(), Constraint.length(1))
                    .split(inner);
            actualArea = cols.get(0);
            scrollArea = cols.get(1);
        } else {
            actualArea = inner;
            scrollArea = null;
        }

        List<Constraint> widths;
        List<String> headers;
        List<Row> shownRows;
        int nameColumn;
        if (work != null) {
            var fitted = work.fit(actualArea.width() - HIGHLIGHT_WIDTH);
            nameColumn = fitted.widths()[0];
            widths = ColumnFit.constraints(fitted.widths());
            headers = fitted.headers();
            shownRows = fitted.rows();
        } else {
            var natural = tui.instanceNatural;
            // The name is never cut (Sanne, review of the column widths), and its state glyph is
            // before it, so there is no STATUS column. Where the table does not fit, the purpose
            // is cut first, then whole columns are left out: RUNTIME, IP, DISK and PARENT in that
            // order, then OWNER, PURPOSE and AGE. Only then is the name cut.
            var all = new ArrayList<>(List.of(
                    new ColumnFit.Column("NAME", 10, natural[0], 0, false),
                    // Room for an address is kept once the columns stand apart, so they
                    // hold still when an instance starts and gets one
                    new ColumnFit.Column("IP", 7, natural[1], 6, false).reserving(IP_WIDTH).dropping(2),
                    new ColumnFit.Column("PARENT", 8, natural[2], 3, false).dropping(4),
                    new ColumnFit.Column("RUNTIME", 9, natural[3], 2, false).dropping(1),
                    new ColumnFit.Column("AGE", 6, natural[4], 5, false).dropping(7),
                    ColumnFit.Column.fixed("DISK", Math.max(4, natural[5])).dropping(3)));
            // The cells of the columns drawn, from each row's: OWNER is the seventh, PURPOSE the eighth
            var source = new java.util.ArrayList<>(IntStream.range(0, all.size()).boxed().toList());
            boolean showOwner = tui.anyOwner && actualArea.width() >= OWNER_COLUMN_MIN_WIDTH;
            if (showOwner) {
                all.add(new ColumnFit.Column("OWNER", 9, natural[6], 4, false).dropping(5));
                source.add(6);
            }
            if (tui.anyPurpose && actualArea.width() >= (showOwner ? PURPOSE_COLUMN_MIN_WIDTH : PURPOSE_ALONE_MIN_WIDTH)) {
                all.add(new ColumnFit.Column("PURPOSE", 12, natural[7], 1, true).dropping(6));
                source.add(7);
            }
            var shown = ColumnFit.kept(all, actualArea.width() - HIGHLIGHT_WIDTH);
            // Spare room is kept for an operation's word after the widest name, so a stop starting
            // neither moves the columns nor has its word cut to a stub; it never leaves a column out
            var columns = new ArrayList<>(shown.stream().map(all::get).toList());
            columns.set(0, columns.getFirst().reserving(tui.widestName + Tui.OPERATION_WORD));
            var cells = shown.stream().map(source::get).toList();
            var fitted = ColumnFit.widths(columns, actualArea.width() - HIGHLIGHT_WIDTH);
            nameColumn = fitted[0];
            widths = ColumnFit.constraints(fitted);
            headers = columns.stream().map(ColumnFit.Column::header).toList();
            var kept = cells.size() == tui.tableRows.get(0).cells().size() ? tui.tableRows
                    : tui.tableRows.stream().map(row -> Row.from(cells.stream().map(row.cells()::get).toList()).style(row.style())).toList();
            shownRows = ColumnFit.clipped(kept, fitted);
        }
        // NAME stands over the name, past its state glyph
        var labels = new ArrayList<>(headers);
        if (!labels.isEmpty()) labels.set(0, "  " + labels.getFirst());
        var tableBuilder = Table.builder()
                .header(Row.from(labels.toArray(String[]::new))
                        .style(headerStyle()))
                .rows(shownRows)
                .widths(widths)
                .highlightSymbol(focused ? "▸ " : "  ")
                // Always reserved, so renderMarks can draw into it
                .highlightSpacing(Table.HighlightSpacing.ALWAYS);

        if (focused) {
            var highlightStyle = selectionStyle();
            // Preserve modifiers from selected row if it has a pending operation
            var selected = tui.selectedEntry(tableState);
            if (selected != null && !selected.pendingOp().isEmpty()) {
                if (Metadata.OP_DELETING.equals(selected.pendingOp())) {
                    highlightStyle = highlightStyle.addModifier(dev.tamboui.style.Modifier.DIM)
                            .addModifier(dev.tamboui.style.Modifier.ITALIC);
                } else if (Metadata.OP_STOPPING.equals(selected.pendingOp()) || Metadata.OP_RESTARTING.equals(selected.pendingOp())) {
                    highlightStyle = highlightStyle.addModifier(dev.tamboui.style.Modifier.DIM);
                }
            }
            tableBuilder.highlightStyle(highlightStyle);
        } else {
            tableBuilder.highlightStyle(Style.EMPTY);
        }

        // A row is drawn whole from its first line, so the whole selected instance stays on screen
        tableState.setOffset(alignedOffset(tableState.offset(), shownRows, tableState.selected(), visibleRows));
        frame.renderStatefulWidget(tableBuilder.build(), actualArea, tableState);
        int offset = tableState.offset();
        var tops = rowTops(shownRows, offset, actualArea);
        int first = 0;
        while (first < tops.length && tops[first] < 0) first++;
        tui.instanceFirstRow = first;
        var at = tableState.selected();
        if (at != null && at >= 0 && at < tops.length && tops[at] >= 0) {
            selectedTop = tops[at];
            selectedLines = Math.min(shownRows.get(at).height(), actualArea.bottom() - tops[at]);
        }
        if (work != null) renderSecondLines(frame, actualArea, tops, work);
        renderMarks(frame, actualArea, offset, shownRows, tui::instanceRowMarked);
        renderRowMotion(frame, actualArea, offset, shownRows, nameColumn, work != null);

        if (scrollArea != null) {
            var scrollbar = Scrollbar.builder()
                    .orientation(ScrollbarOrientation.VERTICAL_RIGHT)
                    .thumbStyle(Style.EMPTY.fg(tui.theme.textFaint()))
                    .trackStyle(Style.EMPTY.fg(tui.theme.scrollbarTrack()))
                    .build();
            var scrollState = new ScrollbarState()
                    .contentLength(lines)
                    .viewportContentLength(visibleRows)
                    .position(offset);
            frame.renderStatefulWidget(scrollbar, scrollArea, scrollState);
        }
    }

    /**
     * Each Work row's second line, under its first, from {@link WorkTable#SECOND_LINE_INDENT}:
     * across the whole table, which a cell's second line could not be, in the row's own style, so
     * the selection's fill covers it.
     */
    private void renderSecondLines(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area, int[] tops, WorkColumns work) {
        var buffer = frame.buffer();
        int x = area.left() + WorkTable.SECOND_LINE_INDENT;
        // One cell short of the edge, so a reference never touches the preview's divider
        int room = area.right() - x - 1;
        if (room <= 0) return;
        for (int row = 0; row < tops.length; row++) {
            if (tops[row] < 0 || tops[row] + 1 >= area.bottom()) continue;
            var line = WorkTable.secondLine(work.purposes().get(row), work.refs().get(row), room, tui.theme);
            if (line == null) continue;
            int y = tops[row] + 1;
            int at = x;
            for (var span : line.spans()) {
                if (at >= area.right()) break;
                var text = CharWidth.substringByWidth(span.content(), area.right() - at);
                buffer.setString(at, y, text, buffer.get(at, y).style().patch(span.style()));
                at += CharWidth.of(text);
            }
        }
    }

    /** The selected instance's preview: as many of its lines as fit, from where J and K scrolled it. */
    private void renderPreview(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area, InstanceInfo selected) {
        var log = tui.overviewLog;
        var reading = tui.overviewReading;
        // The spinner is asked for only while it is shown, or an idle preview would ask for frames for ever
        int merging = tui.mergingSessions(selected.name());
        var subject = new WorkPreview.Subject(selected, tui.stateGlyph(selected), stateWord(selected),
                log == null ? null : OverviewCommand.effortOf(log, selected), reading, tui.overviewAwaits(selected),
                merging, merging > 0 ? tui.motion.spinner() : Motion.SPINNER_STILL, tui.overviewClock.get(), tui.overviewZone);
        var preview = WorkPreview.preview(subject, area.width(), tui.theme);
        var lines = preview.lines();
        tui.previewSays(selected.name(), preview.blocks());
        // A preview that does not all fit says so on its last row, and J and K move it
        boolean cut = lines.size() > area.height();
        int shown = Math.max(0, cut ? area.height() - 1 : area.height());
        int most = cut ? lines.size() - shown : 0;
        tui.previewScrollMost = most;
        int offset = Math.max(0, Math.min(tui.previewScroll, most));
        // A preview that got shorter keeps no scroll past its end, so K moves it at its first press
        tui.previewScroll = offset;
        for (int i = 0; i < shown && offset + i < lines.size(); i++) {
            frame.buffer().setLine(area.left(), area.top() + i, lines.get(offset + i), area.width());
        }
        // The state glyph of its first line turns with the one on the title rule and on the row
        if (offset == 0 && shown > 0 && !selected.pendingOp().isEmpty() && tui.motion.on()) {
            var glyph = frame.buffer().get(area.left() + 1, area.top());
            if (Motion.SPINNER_STILL.equals(glyph.symbol())) {
                frame.buffer().set(area.left() + 1, area.top(), glyph.symbol(tui.motion.spinner()));
            }
        }
        for (var part : preview.blocks()) {
            int top = Math.max(0, part.from() - offset);
            int bottom = Math.min(shown, part.to() - offset);
            if (bottom > top) {
                tui.motion.row(Tui.previewKey(selected.name(), part.key()),
                        new dev.tamboui.layout.Rect(area.left(), area.top() + top, area.width(), bottom - top));
            }
        }
        if (cut && area.height() > 0) {
            frame.buffer().setLine(area.left(), area.bottom() - 1,
                    WorkPreview.cutLine(lines.size() - offset - shown, offset, !tui.searchActive, tui.theme), area.width());
        }
    }

    /** The instance's state in a word: {@code Running}, {@code Stopped}, the operation under way, or its status. */
    private static String stateWord(InstanceInfo instance) {
        var word = Tui.unusualState(instance);
        if (word != null) return word;
        return Tui.isRunning(instance) ? "Running" : "Stopped";
    }

    /** The cells a table keeps at its left for the cursor and a row's mark. */
    static final int HIGHLIGHT_WIDTH = 2;

    /** The width of most IPv4 addresses on the bridge, {@code 10.166.11.123}. */
    static final int IP_WIDTH = 13;

    /**
     * The Work tab's rows, before they are fitted to a width: one per row of the table, two lines
     * tall where it has a purpose or a reference to show, with what each column needs measured
     * over every instance, the search's or not, so the columns hold still while a search narrows
     * the list (review of #2511).
     */
    private record WorkColumns(List<InstanceInfo> instances, List<Row> rows, List<WorkTable.Work> work,
                               List<String> idle, List<WorkTable.Age> ages, List<String> tokens, List<String> purposes,
                               List<List<WorkTable.Ref>> refs, int nameNatural, int workNatural, MainScreen screen) {

        record Fitted(int[] widths, List<String> headers, List<Row> rows) {}

        /**
         * NAME, IDLE, WORK and TOKENS within {@code width}: NAME is cut in its middle, the one
         * column that gives width up, so IDLE, WORK and TOKENS are never cut.
         */
        Fitted fit(int width) {
            var columns = List.of(
                    // Spare room is kept for an operation's word, as on the Instances tab, so a name
                    // shown whole is not cut while F7 runs
                    new ColumnFit.Column("NAME", Math.min(nameNatural, 2 + WorkTable.NAME_LEAST), nameNatural, 1, false)
                            .reserving(nameNatural + Tui.OPERATION_WORD),
                    ColumnFit.Column.fixed("IDLE", WorkTable.IDLE_WIDTH),
                    ColumnFit.Column.fixed("WORK", workNatural),
                    ColumnFit.Column.fixed("TOKENS", WorkTable.TOKENS_WIDTH));
            var widths = ColumnFit.widths(columns, width);
            var theme = screen.tui.theme;
            var shown = new ArrayList<Row>();
            for (int i = 0; i < rows.size(); i++) {
                var instance = instances.get(i);
                if (instance == null) {
                    shown.add(rows.get(i));
                    continue;
                }
                var w = work.get(i);
                var tone = switch (w.tone()) {
                    case OK -> Style.EMPTY.fg(theme.statusSuccess());
                    case WARN -> Style.EMPTY.fg(theme.statusWarning());
                    case FAINT -> Style.EMPTY.fg(theme.textFaint());
                    case DIM -> Style.EMPTY.fg(theme.textDim());
                };
                var spent = tokens.get(i);
                var cells = List.of(
                        dev.tamboui.widgets.table.Cell.from(screen.nameLine(instance, widths[0])),
                        // Graded by age, so the table is scanned for what is live
                        dev.tamboui.widgets.table.Cell.from(Span.styled(rightAligned(idle.get(i), WorkTable.IDLE_WIDTH),
                                switch (ages.get(i)) {
                                    case HOUR -> Style.EMPTY;
                                    case DAY -> Style.EMPTY.fg(theme.textDim());
                                    case OLDER -> Style.EMPTY.fg(theme.textFaint());
                                })),
                        dev.tamboui.widgets.table.Cell.from(Span.styled(w.text(), tone)),
                        dev.tamboui.widgets.table.Cell.from(Span.styled(rightAligned(spent, WorkTable.TOKENS_WIDTH),
                                "-".equals(spent) ? Style.EMPTY.fg(theme.textFaint()) : Style.EMPTY)));
                shown.add(Row.from(cells).style(rows.get(i).style()).height(rows.get(i).height()));
            }
            var headers = List.of("NAME", rightAligned("IDLE", WorkTable.IDLE_WIDTH), "WORK",
                    rightAligned("TOKENS", WorkTable.TOKENS_WIDTH));
            return new Fitted(widths, headers, shown);
        }
    }

    private static String rightAligned(String text, int width) {
        return " ".repeat(Math.max(0, width - CharWidth.of(text))) + text;
    }

    /**
     * A Work row's NAME cell within {@code width} cells: the state glyph, the name cut in its
     * middle where it does not fit, so its distinguishing end stays, and the word of an unusual
     * state, which is never cut.
     */
    Line nameLine(InstanceInfo instance, int width) {
        var word = Tui.unusualState(instance);
        var nameStyle = Tui.isRunning(instance) && instance.pendingOp().isEmpty() ? Style.EMPTY : Style.EMPTY.fg(tui.theme.textDim());
        var spans = new ArrayList<Span>(List.of(tui.stateGlyph(instance),
                Span.styled(drawnName(instance, width, true), nameStyle)));
        if (word != null) spans.add(Span.styled(" " + word, Style.EMPTY.fg(tui.theme.textDim())));
        return Line.from(spans);
    }

    /**
     * The Work tab's rows (#2241): NAME, IDLE (since the newer of the last start and the last line
     * the event log holds), WORK (what the clones hold) and TOKENS, and under them, where there is
     * any, the purpose and the pull requests and pushed branches. The cells are made from the last
     * read, so they follow it without a reload; a cell says {@code …} while its read is under way.
     */
    private WorkColumns workColumns() {
        var log = tui.overviewLog;
        var reading = tui.overviewReading;
        var now = tui.overviewClock.get();
        int nameNatural = 2 + WorkTable.NAME_LEAST;
        int workNatural = CharWidth.of("WORK");
        for (var instance : tui.measuredRows.keySet()) {
            nameNatural = Math.max(nameNatural, Math.min(Tui.nameWidth(instance), 2 + WorkTable.NAME_MOST));
            if (reading != null && !tui.overviewAwaits(instance)) {
                workNatural = Math.max(workNatural, CharWidth.of(WorkTable.work(reading.workOf(instance), false).text()));
            }
        }
        var instances = new ArrayList<InstanceInfo>();
        var rows = new ArrayList<Row>();
        var work = new ArrayList<WorkTable.Work>();
        var idle = new ArrayList<String>();
        var ages = new ArrayList<WorkTable.Age>();
        var tokens = new ArrayList<String>();
        var purposes = new ArrayList<String>();
        var refs = new ArrayList<List<WorkTable.Ref>>();
        for (int i = 0; i < tui.tableRows.size(); i++) {
            var instance = tui.instanceOnRow(i);
            var base = tui.tableRows.get(i);
            instances.add(instance);
            if (instance == null) {
                rows.add(base);
                work.add(null);
                idle.add(null);
                ages.add(null);
                tokens.add(null);
                purposes.add(null);
                refs.add(List.of());
                continue;
            }
            var effort = log == null ? null : OverviewCommand.effortOf(log, instance);
            boolean awaits = reading == null || tui.overviewAwaits(instance);
            work.add(WorkTable.work(awaits ? null : reading.workOf(instance), awaits));
            var active = WorkTable.lastActive(instance, effort);
            var since = active == null ? null : java.time.Duration.between(active, now);
            idle.add(WorkTable.idle(since));
            ages.add(WorkTable.age(since));
            tokens.add(effort == null ? ColumnFit.ELLIPSIS : OverviewCommand.tokensCell(effort));
            var references = effort == null ? List.<WorkTable.Ref>of() : WorkTable.references(effort,
                    reading == null ? List.of() : OverviewCommand.statesOf(reading, instance, effort));
            refs.add(references);
            // What a finished read says, for the tint on a row a later read changes
            if (!awaits) {
                tui.workSays(instance.name(), "clones", work.getLast().text());
                if (log != null && reading.pulls().containsKey(instance.name())) {
                    tui.workSays(instance.name(), "pulls", references.stream().filter(ref -> !ref.branch())
                            .map(ref -> ref.text() + ref.state()).collect(java.util.stream.Collectors.joining(" ")));
                }
            }
            purposes.add(instance.purpose());
            boolean twoLines = !references.isEmpty() || instance.purpose() != null && !instance.purpose().isBlank();
            rows.add(base.height(twoLines ? 2 : 1));
        }
        return new WorkColumns(instances, rows, work, idle, ages, tokens, purposes, refs, nameNatural, workNatural, this);
    }

    /** How far the latest refresh has read the running guests, after {@code lead} ({@code , reading 3/12 instances}), or nothing once it has. */
    private String readingCount(String lead) {
        int left = tui.overviewPending.size();
        int of = tui.overviewReadingOf;
        if (left == 0 || of == 0) return "";
        return lead + (of - left) + "/" + of + (of == 1 ? " instance" : " instances");
    }

    /** The overview's title: what its counts cover, read from the event log. */
    private String overviewTitle() {
        var log = tui.overviewLog;
        var name = tui.view == Tui.View.WORK ? " Work" : " Instances: overview";
        var back = tui.view == Tui.View.WORK ? " " : " (o: back) ";
        var turning = Motion.SPINNER_STILL + " reading ";
        if (log == null) return name + ", " + turning + "the proxy's event log" + readingCount(" and ") + back;
        var reading = readingCount(", " + turning);
        if (!log.files() || log.since() == null) return name + ", the proxy's event log is empty" + reading + back;
        var lost = log.droppedLines() + log.unreadableLines();
        return name + " since "
                + OverviewCommand.DAY.format(log.since().atZone(tui.overviewZone))
                + (lost > 0 ? ", " + lost + (lost == 1 ? " line lost" : " lines lost") : "") + reading + back;
    }

    /**
     * The shortest terminal whose footer starts with a rule. Shorter, the row is the lists'.
     */
    static final int FOOTER_RULE_MIN_HEIGHT = 16;

    void renderToolbar(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                                TableState tableState, boolean hasStatus) {
        var template = tui.selectedTemplate();
        boolean hasTemplate = template != null;
        boolean isBuilt = hasTemplate && !"not built".equals(template.buildStatus());
        var selected = tui.selectedEntry(tableState);
        boolean hasInstance = selected != null;
        boolean running = hasInstance && Tui.isRunning(selected);
        boolean onTemplates = tui.focusedPanel == Tui.Panel.TEMPLATES;

        var items = new ArrayList<KeyItem>();
        items.add(makeKey("F1", "Info", false));
        items.add(makeKey("F2", "Shell", !hasInstance || onTemplates));
        items.add(makeKey("F3", "Details", onTemplates ? !hasTemplate : !hasInstance));
        items.add(makeKey("F4", "Branch\u2026", onTemplates ? !isBuilt : !hasInstance));
        items.add(makeKey("F5", "Build…", !hasTemplate || !onTemplates));
        items.add(makeKey("F6", "Edit\u2026", !hasInstance || onTemplates));
        // With marks, F7 and F8 act on the marked rows rather than the cursor's
        // (only on a row: with none, as when the search hides them all, no key acts)
        boolean stops = tui.markedInstances().isEmpty() ? running : hasInstance && tui.anyMarkedInstanceRunning();
        items.add(makeKey("F7", "Stop", onTemplates || !stops));
        items.add(makeKey("F8", "Destroy\u2026", onTemplates ? !(isBuilt || !tui.markedTemplates().isEmpty()) : !hasInstance));
        boolean hasActions = hasInstance && !onTemplates && tui.hasActionsForInstance(selected);
        items.add(makeKey("F9", "Actions", !hasActions));
        items.add(makeKey("F10", "Quit", false));

        var contextLine = buildContextLine(template, selected, onTemplates);

        // The rule that ends the last panel, where the layout left a row for it
        if (area.height() > (hasStatus ? 3 : 2)) {
            frame.renderWidget(Block.builder().borders(java.util.EnumSet.of(Borders.TOP)).borderType(BorderType.PLAIN)
                    .borderStyle(Style.EMPTY.fg(tui.theme.panelBorderUnfocused())).build(),
                    new dev.tamboui.layout.Rect(area.left(), area.top(), area.width(), 1));
            // The Work tab's preview divider ends on it
            if (previewDividerX >= area.left() && previewDividerX < area.right()) {
                frame.buffer().setString(previewDividerX, area.top(), "┴", Style.EMPTY.fg(tui.theme.panelBorderUnfocused()));
            }
            area = new dev.tamboui.layout.Rect(area.left(), area.top() + 1, area.width(), area.height() - 1);
        }

        if (hasStatus) {
            var rows = splitVertical(area, 1, 1, 1);
            var singleLine = LINE_BREAKS.matcher(tui.statusMessage).replaceAll(" ").strip();
            var isError = singleLine.startsWith("Failed") || singleLine.startsWith("Invalid")
                    || singleLine.startsWith("Template");
            var isWarning = singleLine.startsWith("⚠") || singleLine.startsWith("Warning");
            // A message is told from the screen by its colour and its mark, never by a filled band
            var msgFg = isError ? tui.theme.statusBarErrorFg()
                    : isWarning ? tui.theme.statusWarning() : tui.theme.accent();
            var mark = isError ? Glyphs.FAILED + " " : isWarning ? "" : "";
            frame.renderWidget(Paragraph.from(Line.styled(" " + mark + singleLine,
                    Style.EMPTY.fg(msgFg))), rows.get(0));
            if (tui.searchActive) {
                renderSearchBar(frame, rows.get(1));
            } else {
                renderContextLine(frame, rows.get(1), contextLine);
            }
            renderKeyItems(frame, rows.get(2), items);
        } else {
            var rows = splitVertical(area, 1, 1);
            if (tui.searchActive) {
                renderSearchBar(frame, rows.get(0));
            } else {
                renderContextLine(frame, rows.get(0), contextLine);
            }
            renderKeyItems(frame, rows.get(1), items);
        }
    }

    private void renderSearchBar(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        fillBackground(frame, area, tui.theme.contextBg());
        // The search bar stands where the context line was, so it keeps that line's hint for
        // joining the selected instance's task (#2736): the search finds it, Shift+F2 joins it
        var selected = tui.focusedPanel == Tui.Panel.INSTANCES ? tui.selectedInstance() : null;
        var join = selected != null && selected.mcp() != null ? Line.from(
                Span.styled("⇧F2", Style.EMPTY.bold().fg(tui.theme.contextAccentFg()).bg(tui.theme.contextBg())),
                Span.styled(" Join task ", Style.EMPTY.fg(tui.theme.contextSecondaryFg()).bg(tui.theme.contextBg()))) : null;
        int joinWidth = join == null || area.width() < 40 ? 0 : join.width();
        var cols = Layout.horizontal()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(joinWidth))
                .split(area);
        if (joinWidth > 0) frame.renderWidget(Paragraph.from(join), cols.get(2));
        frame.renderWidget(Paragraph.from(Line.styled(" / ",
                Style.EMPTY.bold().fg(tui.theme.contextAccentFg()).bg(tui.theme.contextBg()))), cols.get(0));
        TextInput.builder()
                .placeholder("type to filter…")
                .style(Style.EMPTY.fg(tui.theme.contextPrimaryFg()).bg(tui.theme.contextBg()))
                .placeholderStyle(Style.EMPTY.fg(tui.theme.textDim()).bg(tui.theme.contextBg()))
                .build()
                .renderWithCursor(cols.get(1), frame.buffer(), tui.searchInput, frame);
    }

    Line buildContextLine(TemplateInfo template, InstanceInfo instance, boolean onTemplates) {
        var bg = tui.theme.contextBg();
        if (onTemplates && template != null) {
            var spans = new ArrayList<Span>();
            spans.add(Span.styled(" " + template.name(), Style.EMPTY.bold().fg(tui.theme.contextPrimaryFg()).bg(bg)));
            boolean hasWarning = false;
            if (!"not built".equals(template.buildStatus())) {
                var warnStyle = Style.EMPTY.fg(tui.theme.statusWarning()).bg(bg);
                var currentVersion = tui.currentVersion();
                if (!template.buildVersion().isEmpty() && !template.buildVersion().equals(currentVersion)) {
                    spans.add(Span.styled("  ! built with isx v" + template.buildVersion()
                            + " (current: v" + currentVersion + ")", warnStyle));
                    hasWarning = true;
                } else if (template.buildVersion().isEmpty()) {
                    spans.add(Span.styled("  ! built before isx version tracking", warnStyle));
                    hasWarning = true;
                }
                if (tui.templatesDefChanged.contains(template.name())) {
                    // The file name only: a full path could push the warnings after it off the bar
                    var def = tui.imageDefs.get(template.name());
                    var builtFrom = TemplateDetailView.otherBuildFile(tui.loader.builtFrom(template.name()), def);
                    spans.add(Span.styled("  △ definition changed since last build"
                            + (builtFrom != null ? " (built from "
                                    + TemplateDetailView.shortSourceLabel(builtFrom, def.getSource())
                                    + ")" : ""),
                            warnStyle));
                    hasWarning = true;
                }
                var missing = tui.missingDefinitions.get(template.name());
                if (missing != null) {
                    spans.add(Span.styled("  \u25cc no definition: " + missing.reason(), warnStyle));
                    hasWarning = true;
                }
                if (tui.templatesParentRebuilt.contains(template.name())) {
                    var parentName = tui.imageDefs.get(template.name()).getParent();
                    spans.add(Span.styled("  ↑ parent " + parentName + " was rebuilt since last build", warnStyle));
                    hasWarning = true;
                }
            }
            if (!hasWarning && template.description() != null && !template.description().isEmpty()) {
                spans.add(Span.styled("  " + template.description(), Style.EMPTY.fg(tui.theme.contextSecondaryFg()).bg(bg)));
            }
            addMarkHints(spans, tui.markedTemplates().size(), bg);
            return Line.from(spans);
        }
        if (!onTemplates && instance != null) {
            var spans = new ArrayList<Span>();
            spans.add(Span.styled(" " + instance.name(), Style.EMPTY.bold().fg(tui.theme.contextPrimaryFg()).bg(bg)));
            if (!instance.parent().isEmpty() && !"-".equals(instance.parent())) {
                spans.add(Span.styled("  from " + OutputFormat.oneLine(instance.parent()), Style.EMPTY.fg(tui.theme.contextSecondaryFg()).bg(bg)));
            }
            if (!instance.ipv4().isEmpty()) {
                spans.add(Span.styled("  " + instance.ipv4(), Style.EMPTY.fg(tui.theme.contextAccentFg()).bg(bg)));
            }
            if (!instance.networkMode().isEmpty()) {
                spans.add(Span.styled("  [" + instance.networkMode().toLowerCase() + "]",
                        Style.EMPTY.fg(tui.theme.contextSecondaryFg()).bg(bg)));
            }
            // Only an isx mcp instance can hold a delegated task to join (#1509); the key bar has no room.
            if (instance.mcp() != null) {
                spans.add(Span.styled("  ⇧F2", Style.EMPTY.bold().fg(tui.theme.contextAccentFg()).bg(bg)));
                spans.add(Span.styled(" Join task", Style.EMPTY.fg(tui.theme.contextSecondaryFg()).bg(bg)));
            }
            if (tui.view == Tui.View.WORK && tui.previewFits) {
                // p is the Work tab's own key for the preview, wherever there is room to draw it
                spans.add(Span.styled("  p", Style.EMPTY.bold().fg(tui.theme.contextAccentFg()).bg(bg)));
                spans.add(Span.styled(tui.previewDrawn() ? " Hide preview" : " Preview",
                        Style.EMPTY.fg(tui.theme.contextSecondaryFg()).bg(bg)));
            }
            addMarkHints(spans, tui.markedInstances().size(), bg);
            return Line.from(spans);
        }
        return Line.styled("", Style.EMPTY);
    }

    /**
     * The key bar has no room left at 80 columns, so Space is hinted at the end of the context line,
     * and the marks are counted after it: a mark only adds to the end of the line and moves nothing.
     */
    private void addMarkHints(List<Span> spans, int marked, Color bg) {
        spans.add(Span.styled("  Space", Style.EMPTY.bold().fg(tui.theme.contextAccentFg()).bg(bg)));
        spans.add(Span.styled(" Mark", Style.EMPTY.fg(tui.theme.contextSecondaryFg()).bg(bg)));
        if (marked > 0) {
            spans.add(Span.styled("  " + Glyphs.MARKED + " " + marked + " marked, Esc clears", Style.EMPTY.fg(tui.theme.contextAccentFg()).bg(bg)));
        }
    }

    private void renderContextLine(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area, Line line) {
        fillBackground(frame, area, tui.theme.contextBg());

        // Reserve right side for background tasks if any exist
        var tasks = tui.backgroundTasks.getActiveTasks();
        if (!tasks.isEmpty()) {
            // Estimate width needed for background tasks
            int taskWidth = estimateBackgroundTaskWidth(tasks);
            if (taskWidth > 0 && area.width() > 30) {
                int allocatedWidth = Math.min(taskWidth, area.width() / 2);
                var parts = Layout.horizontal()
                        .constraints(Constraint.fill(), Constraint.length(allocatedWidth))
                        .split(area);
                frame.renderWidget(Paragraph.from(line), parts.get(0));
                renderBackgroundTasksInline(frame, parts.get(1), tasks);
                return;
            }
        }

        frame.renderWidget(Paragraph.from(line), area);
    }

    private int estimateBackgroundTaskWidth(List<dev.incusspawn.tui.BackgroundTask> tasks) {
        var running = tasks.stream()
                .filter(t -> t.status() == dev.incusspawn.tui.BackgroundTask.TaskStatus.RUNNING)
                .toList();
        var completed = tasks.stream()
                .filter(t -> t.status() != dev.incusspawn.tui.BackgroundTask.TaskStatus.RUNNING)
                .toList();

        int width = 0;
        if (!running.isEmpty()) {
            width += 17; // " ⠋ Running: X "
            int toShow = Math.min(running.size(), 2);
            for (int i = 0; i < toShow; i++) {
                width += running.get(i).displayName().length() + 5; // name + "... "
            }
            if (running.size() > 2) {
                width += 10; // " +X more "
            }
        }
        for (var task : completed) {
            if (task instanceof dev.incusspawn.tui.BackgroundTask.Completed completedTask) {
                width += completedTask.getDisplayText().length() + 4; // symbol + spaces
            }
        }
        return Math.min(width, 80); // Cap at reasonable width
    }

    private void renderBackgroundTasksInline(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                                             List<dev.incusspawn.tui.BackgroundTask> tasks) {
        var running = tasks.stream()
                .filter(t -> t.status() == dev.incusspawn.tui.BackgroundTask.TaskStatus.RUNNING)
                .toList();
        var completed = tasks.stream()
                .filter(t -> t.status() != dev.incusspawn.tui.BackgroundTask.TaskStatus.RUNNING)
                .toList();

        var spans = new ArrayList<Span>();

        // Show running tasks
        if (!running.isEmpty()) {
            spans.add(Span.styled(" " + tui.motion.spinner() + " Running: " + running.size() + " ",
                    Style.EMPTY.fg(tui.theme.statusWarning()).bg(tui.theme.contextBg())));
            int namesFrom = area.left() + 14 + String.valueOf(running.size()).length();
            int namesWidth = 0;
            for (var task : running.stream().limit(2).toList()) {
                var name = " " + task.displayName() + "... ";
                spans.add(Span.styled(name,
                        Style.EMPTY.fg(tui.theme.contextSecondaryFg()).bg(tui.theme.contextBg())));
                namesWidth += name.length();
            }
            tui.motion.shimmer(new dev.tamboui.layout.Rect(namesFrom, area.top(),
                    Math.max(0, Math.min(namesWidth, area.right() - namesFrom)), 1));
            if (running.size() > 2) {
                spans.add(Span.styled(" +" + (running.size() - 2) + " more ",
                        Style.EMPTY.fg(tui.theme.contextSecondaryFg()).bg(tui.theme.contextBg())));
            }
        }

        // Show completed tasks
        for (var task : completed) {
            if (task instanceof dev.incusspawn.tui.BackgroundTask.Completed completedTask) {
                var symbol = task.status() == dev.incusspawn.tui.BackgroundTask.TaskStatus.SUCCESS ? "✓" : "✗";
                var color = task.status() == dev.incusspawn.tui.BackgroundTask.TaskStatus.SUCCESS ? tui.theme.statusSuccess() : tui.theme.statusFailure();
                spans.add(Span.styled(" " + symbol + " " + completedTask.getDisplayText() + " ",
                        Style.EMPTY.fg(color).bg(tui.theme.contextBg())));
            }
        }

        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }

    /**
     * The key bar, in the widest form that fits: each key and its label apart ({@code F1 Info})
     * and spread over the row, the two together ({@code F1Info}), or, on a terminal too narrow for
     * every label, equal cells that cut the labels. It is not a band: a key is told from its label
     * by colour and weight.
     */
    private void renderKeyItems(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                                 List<KeyItem> items) {
        int spaced = items.stream().mapToInt(item -> item.width() + 1).sum();
        int packed = items.stream().mapToInt(KeyItem::width).sum();
        int gaps = items.size() - 1;
        boolean apart = spaced + 2 * gaps + 1 <= area.width();
        if (!apart && packed + gaps + 1 > area.width()) {
            var constraints = items.stream()
                    .map(item -> Constraint.ratio(1, items.size()))
                    .toArray(Constraint[]::new);
            var cells = Layout.horizontal().constraints(constraints).split(area);
            for (int i = 0; i < items.size(); i++) {
                var cell = cells.get(i);
                // One cell less than its share, so a cut label does not run into the next key
                frame.renderWidget(Paragraph.from(items.get(i).line(false)), new dev.tamboui.layout.Rect(
                        cell.x() + (i == 0 ? 1 : 0), cell.y(), Math.max(0, cell.width() - 1), cell.height()));
            }
            return;
        }
        int used = apart ? spaced : packed;
        // What is left over goes between the keys, evenly, up to a width that still reads as one bar
        int gap = Math.min(4, (area.width() - 1 - used) / gaps);
        var spans = new ArrayList<Span>();
        spans.add(Span.raw(" "));
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) spans.add(Span.raw(" ".repeat(gap)));
            spans.addAll(items.get(i).line(apart).spans());
        }
        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }

    private record KeyItem(String key, String label, boolean disabled, TuiTheme theme) {
        /** The cells the key and its label take together, with nothing between them. */
        int width() {
            return key.length() + label.length();
        }

        Line line(boolean apart) {
            var spans = new ArrayList<Span>();
            var between = apart ? " " : "";
            if (disabled) {
                spans.add(Span.styled(key + between + label, Style.EMPTY.fg(theme.barDisabledFg())));
            } else {
                // The digit that stands in for the F-key is the bold part, so "F7" reads as "press 7" too
                // (#1824): F10's is its 0
                spans.add(Span.styled(key.substring(0, key.length() - 1), Style.EMPTY.fg(theme.barKeyFg())));
                spans.add(Span.styled(key.substring(key.length() - 1), Style.EMPTY.bold().fg(theme.barKeyFg())));
                spans.add(Span.styled(between + label, Style.EMPTY.fg(theme.barLabelFg())));
            }
            return Line.from(spans);
        }
    }

    private KeyItem makeKey(String key, String label, boolean disabled) {
        return new KeyItem(key, label, disabled, tui.theme);
    }

    private static void fillBackground(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area, Color bg) {
        frame.buffer().setStyle(area, Style.EMPTY.bg(bg));
    }

    private static List<dev.tamboui.layout.Rect> splitVertical(dev.tamboui.layout.Rect area, int... heights) {
        var constraints = new Constraint[heights.length];
        for (int i = 0; i < heights.length; i++) constraints[i] = Constraint.length(heights[i]);
        return Layout.vertical().constraints(constraints).split(area);
    }
}
