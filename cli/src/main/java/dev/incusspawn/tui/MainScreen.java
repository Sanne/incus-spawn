package dev.incusspawn.tui;

import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.OutputFormat;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
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
        renderMarks(frame, actualTableArea, tui.templateTableState.offset(), tui.templateRows.size(), tui::templateRowMarked);

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
     * Draws {@code ●} on each visible marked row (#1131, #1482) in the second cell of the cursor's
     * gutter, which is always reserved and otherwise blank, so marking a row moves nothing else.
     * The table has one header line and single-line rows.
     */
    private void renderMarks(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect tableArea, int offset,
                             int rows, java.util.function.IntPredicate marked) {
        var buffer = frame.buffer();
        int x = tableArea.left() + 1;
        for (int row = offset; row < rows; row++) {
            int y = tableArea.top() + 1 + row - offset;
            if (y >= tableArea.bottom()) break;
            if (!marked.test(row)) continue;
            var style = buffer.get(x, y).style().patch(Style.EMPTY.fg(tui.theme.contextAccentFg()));
            buffer.setString(x, y, "\u25cf", style);
        }
    }

    /**
     * What moves on the instance rows: each row says where it is, for a flash that follows it, and
     * a row with an operation under way turns its still glyph into the spinner and shimmers.
     */
    private void renderRowMotion(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect tableArea, int offset) {
        var buffer = frame.buffer();
        for (int row = offset; row < tui.tableRows.size(); row++) {
            int y = tableArea.top() + 1 + row - offset;
            if (y >= tableArea.bottom()) break;
            var instance = tui.instanceOnRow(row);
            if (instance == null) continue;
            var area = new dev.tamboui.layout.Rect(tableArea.left(), y, tableArea.width(), 1);
            tui.motion.row(instanceKey(instance.name()), area);
            if (instance.pendingOp().isEmpty() || !tui.motion.on()) continue;
            for (int x = area.left(); x < area.right(); x++) {
                var cell = buffer.get(x, y);
                if (Motion.SPINNER_STILL.equals(cell.symbol())) buffer.set(x, y, cell.symbol(tui.motion.spinner()));
            }
            // The name, which is what the operation is about: the cursor gutter is not text
            tui.motion.shimmer(new dev.tamboui.layout.Rect(area.left() + 2, y,
                    Math.min(instance.name().length(), Math.max(0, area.width() - 2)), 1));
        }
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

        int visibleRows = Math.max(inner.height() - 1, 1);
        boolean needsScroll = tui.tableRows.size() > visibleRows;
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
        if (tui.overviewShown()) {
            var columns = overviewColumns(actualArea.width());
            widths = columns.widths();
            headers = columns.headers();
            shownRows = columns.rows();
        } else {
            var natural = tui.instanceNatural;
            // The name is never cut (Sanne, review of the column widths). Where the table does
            // not fit, the purpose is cut first, then whole columns are left out: RUNTIME, IP,
            // DISK and PARENT in that order, then OWNER, PURPOSE and AGE. Only then is the status
            // cut, and the name when nothing else is left.
            var all = new ArrayList<>(List.of(
                    new ColumnFit.Column("NAME", 10, natural[0], 0, false),
                    new ColumnFit.Column("STATUS", 9, natural[1], 8, false),
                    // Room for an address is kept once the columns stand apart, so they
                    // hold still when an instance starts and gets one
                    new ColumnFit.Column("IP", 7, natural[2], 6, false).reserving(IP_WIDTH).dropping(2),
                    new ColumnFit.Column("PARENT", 8, natural[3], 3, false).dropping(4),
                    new ColumnFit.Column("RUNTIME", 9, natural[4], 2, false).dropping(1),
                    new ColumnFit.Column("AGE", 6, natural[5], 5, false).dropping(7),
                    ColumnFit.Column.fixed("DISK", Math.max(4, natural[6])).dropping(3)));
            // The cells of the columns drawn, from each row's: OWNER is the eighth, PURPOSE the ninth
            var source = new java.util.ArrayList<>(IntStream.range(0, all.size()).boxed().toList());
            boolean showOwner = tui.anyOwner && actualArea.width() >= OWNER_COLUMN_MIN_WIDTH;
            if (showOwner) {
                all.add(new ColumnFit.Column("OWNER", 9, natural[7], 4, false).dropping(5));
                source.add(7);
            }
            if (tui.anyPurpose && actualArea.width() >= (showOwner ? PURPOSE_COLUMN_MIN_WIDTH : PURPOSE_ALONE_MIN_WIDTH)) {
                all.add(new ColumnFit.Column("PURPOSE", 12, natural[8], 1, true).dropping(6));
                source.add(8);
            }
            var shown = ColumnFit.kept(all, actualArea.width() - HIGHLIGHT_WIDTH);
            var columns = shown.stream().map(all::get).toList();
            var cells = shown.stream().map(source::get).toList();
            var fitted = ColumnFit.widths(columns, actualArea.width() - HIGHLIGHT_WIDTH);
            widths = ColumnFit.constraints(fitted);
            headers = columns.stream().map(ColumnFit.Column::header).toList();
            var kept = cells.size() == tui.tableRows.get(0).cells().size() ? tui.tableRows
                    : tui.tableRows.stream().map(row -> Row.from(cells.stream().map(row.cells()::get).toList()).style(row.style())).toList();
            shownRows = ColumnFit.clipped(kept, fitted);
        }
        var tableBuilder = Table.builder()
                .header(Row.from(headers.toArray(String[]::new))
                        .style(headerStyle()))
                .rows(shownRows)
                .widths(widths)
                .highlightSymbol(focused ? "\u25b8 " : "  ")
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

        frame.renderStatefulWidget(tableBuilder.build(), actualArea, tableState);
        renderMarks(frame, actualArea, tableState.offset(), tui.tableRows.size(), tui::instanceRowMarked);
        renderRowMotion(frame, actualArea, tableState.offset());

        if (scrollArea != null) {
            var scrollbar = Scrollbar.builder()
                    .orientation(ScrollbarOrientation.VERTICAL_RIGHT)
                    .thumbStyle(Style.EMPTY.fg(tui.theme.textFaint()))
                    .trackStyle(Style.EMPTY.fg(tui.theme.scrollbarTrack()))
                    .build();
            var scrollState = new ScrollbarState()
                    .contentLength(tui.tableRows.size())
                    .viewportContentLength(visibleRows)
                    .position(tableState.offset());
            frame.renderStatefulWidget(scrollbar, scrollArea, scrollState);
        }
    }

    /** The narrowest table that shows PURPOSE beside the overview's columns and DONE: a 150-column terminal. */
    static final int OVERVIEW_PURPOSE_MIN_WIDTH = 146;
    /** The narrowest table that shows DONE, what the newest agent session said it did: a 120-column terminal. */
    static final int OVERVIEW_DONE_MIN_WIDTH = 116;
    /** The narrowest table that shows ACTIVE beside WORK: a 100-column terminal. */
    static final int OVERVIEW_ACTIVE_MIN_WIDTH = 96;

    private record Columns(List<Constraint> widths, List<String> headers, List<Row> rows) {}

    /** The width of most IPv4 addresses on the bridge, {@code 10.166.11.123}. */
    static final int IP_WIDTH = 13;

    /** The cells a table keeps at its left for the cursor and a row's mark. */
    static final int HIGHLIGHT_WIDTH = 2;

    /**
     * The overview's columns (#2241, {@code o} and the Work tab): NAME and STATUS from the table's
     * rows, then what each guest's clones hold (WORK), what the proxy's event log says each
     * instance did, and ACTIVE, DONE (what the newest agent session said it did) and PURPOSE where
     * there is room. The cells are made from the last
     * read, so they follow it without a reload; a cell says {@code …} while its read is under way.
     */
    private Columns overviewColumns(int width) {
        boolean active = width >= OVERVIEW_ACTIVE_MIN_WIDTH;
        var natural = tui.instanceNatural;
        // WORK, DONE and PURPOSE get their content's width once the rows are made, below
        var columns = new ArrayList<>(List.of(
                // Never cut: GITHUB takes its width from what the name leaves
                new ColumnFit.Column("NAME", Math.max(10, natural[0]), natural[0], 0, false),
                // Down to 7 as before, so a pull request stays whole at 80 columns (#2292)
                new ColumnFit.Column("STATUS", 7, natural[1], 6, false),
                ColumnFit.Column.fixed("LAST USED", 12),
                new ColumnFit.Column("WORK", 12, 12, 4, false),
                ColumnFit.Column.fixed("TOKENS", 7)));
        if (active) columns.add(ColumnFit.Column.fixed("ACTIVE", 13));
        int githubColumn = columns.size();
        boolean done = width >= OVERVIEW_DONE_MIN_WIDTH;
        if (done) columns.add(new ColumnFit.Column("DONE", 20, 20, 1, true));
        boolean purpose = tui.anyPurpose && width >= OVERVIEW_PURPOSE_MIN_WIDTH;
        if (purpose) columns.add(new ColumnFit.Column("PURPOSE", 24, 24, 2, true));
        var log = tui.overviewLog;
        var reading = tui.overviewReading;
        var zone = tui.overviewZone;
        // GITHUB is as wide as its widest row needs, to show each reference whole (#2292), within
        // what the other columns leave at their least: the highlight mark, one cell between columns
        int room = width - HIGHLIGHT_WIDTH - columns.size();
        for (var column : columns) room -= column.floor();
        int githubMost = Math.max(GITHUB_MIN_WIDTH, Math.min(GITHUB_MAX_WIDTH, room));
        var efforts = new ArrayList<dev.incusspawn.proxy.EventLogReader.Effort>();
        var github = new ArrayList<List<OverviewCommand.GitHubPart>>();
        int githubWidth = GITHUB_MIN_WIDTH;
        // Every instance's row is made, the search's or not: the columns are as wide as the widest
        // cell of them all, so they hold still while a search narrows the list (review of #2511)
        var instanceRows = tui.measuredRows;
        var pool = List.copyOf(instanceRows.keySet());
        for (int i = 0; i < pool.size(); i++) {
            var instance = pool.get(i);
            if (log == null) {
                efforts.add(null);
                github.add(null);
                continue;
            }
            var effort = OverviewCommand.effortOf(log, instance);
            efforts.add(effort);
            var parts = OverviewCommand.githubParts(effort, reading == null
                    ? List.of() : OverviewCommand.statesOf(reading, instance, effort));
            github.add(parts);
            githubWidth = Math.max(githubWidth, OverviewCommand.fitGithub(parts, githubMost).stream()
                    .mapToInt(piece -> OverviewCommand.width(piece.text())).sum());
        }
        columns.add(githubColumn, ColumnFit.Column.fixed("GITHUB", githubWidth));
        var every = new ArrayList<Row>();
        var made = new java.util.IdentityHashMap<InstanceInfo, Row>();
        for (int i = 0; i < pool.size(); i++) {
            var instance = pool.get(i);
            var row = instanceRows.get(instance);
            var cells = new ArrayList<>(List.of(row.cells().get(0), row.cells().get(1)));
            cells.add(dev.tamboui.widgets.table.Cell.from(OverviewCommand.lastUsedCell(instance)));
            boolean awaits = reading == null || tui.overviewAwaits(instance);
            cells.add(dev.tamboui.widgets.table.Cell.from(awaits ? "\u2026" : reading.workOf(instance).cell()));
            if (log == null) {
                for (int c = active ? 0 : 1; c < 3; c++) cells.add(dev.tamboui.widgets.table.Cell.from("\u2026"));
            } else {
                var effort = efforts.get(i);
                cells.add(dev.tamboui.widgets.table.Cell.from(OverviewCommand.tokensCell(effort)));
                if (active) cells.add(dev.tamboui.widgets.table.Cell.from(OverviewCommand.activeCell(effort, zone)));
                cells.add(dev.tamboui.widgets.table.Cell.from(githubLine(github.get(i), githubWidth)));
            }
            if (done) {
                var summary = awaits ? null : reading.summaryOf(instance);
                // Only the event log can say an instance with no session was not untouched
                cells.add(dev.tamboui.widgets.table.Cell.from(summary == null || log == null && "untouched".equals(summary.from())
                        ? "\u2026" : OverviewCommand.doneCell(summary, 120)));
            }
            if (purpose) cells.add(row.cells().get(8));
            var overviewRow = Row.from(cells).style(row.style());
            every.add(overviewRow);
            made.put(instance, overviewRow);
        }
        var rows = new ArrayList<Row>();
        for (int i = 0; i < tui.tableRows.size(); i++) {
            var instance = tui.instanceOnRow(i);
            var row = instance == null ? null : made.get(instance);
            rows.add(row != null ? row : tui.tableRows.get(i));
        }
        for (int c = 0; c < columns.size(); c++) {
            var column = columns.get(c);
            if (column.give() == 0 || c < 2) continue;
            columns.set(c, column.holding(Math.max(column.floor(), ColumnFit.natural(every, c))));
        }
        var fitted = ColumnFit.widths(columns, width - HIGHLIGHT_WIDTH);
        return new Columns(ColumnFit.constraints(fitted), columns.stream().map(ColumnFit.Column::header).toList(),
                ColumnFit.clipped(rows, fitted));
    }

    /** The GITHUB column's least width, and the most it takes for the references of one row. */
    static final int GITHUB_MIN_WIDTH = 16;
    static final int GITHUB_MAX_WIDTH = 40;

    /**
     * A GITHUB cell: each reference whole within {@code width} (#2292), a pull request underlined
     * and linked to its page, so a click in a terminal that supports links opens it.
     */
    private Line githubLine(List<OverviewCommand.GitHubPart> parts, int width) {
        if (parts.isEmpty()) return Line.from("-");
        var spans = new ArrayList<Span>();
        for (var piece : OverviewCommand.fitGithub(parts, width)) {
            spans.add(piece.url() == null ? Span.raw(piece.text())
                    : Span.styled(piece.text(), Style.EMPTY.underlined().hyperlink(piece.url())));
        }
        return Line.from(spans);
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
        if (log == null) return name + ", reading the proxy's event log" + readingCount(" and ") + back;
        var reading = readingCount(", reading ");
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
        var cols = Layout.horizontal()
                .constraints(Constraint.length(3), Constraint.fill())
                .split(area);
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
            spans.add(Span.styled("  ● " + marked + " marked, Esc clears", Style.EMPTY.fg(tui.theme.contextAccentFg()).bg(bg)));
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
