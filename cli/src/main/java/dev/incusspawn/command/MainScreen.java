package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.incus.IncusClient;
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
import static dev.incusspawn.command.UsageFormat.bar;
import static dev.incusspawn.command.UsageFormat.gibShort;
import static dev.incusspawn.command.UsageFormat.runningSummary;
import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.command.InstanceListing.TemplateInfo;

/**
 * The TUI's main screen below its dialogs: the header with the storage gauge, the templates and
 * instances tables, the legend, the search bar, the context line and the key bar. It draws what
 * {@link Tui} holds and changes none of it.
 */
final class MainScreen {

    private final Tui tui;

    MainScreen(Tui tui) {
        this.tui = tui;
    }

    // Compact storage gauge width targets for the header's right-hand side.
    private static final int HEADER_BAR_MIN = 10;  // floor for the bar fill (excl. borders)

    private static final int HEADER_BAR_MAX = 32;  // cap so the bar stays a gauge, not a ruler

    /**
     * Render the always-present header band above the panels: a bold accent "brand chip" on the
     * left for app identity, and — when pool usage is available — a compact storage gauge
     * right-aligned on the same row. The gauge's fill colour signals the threshold
     * (green → amber → red) while the segment glyphs carry the level independently of colour, so
     * it reads on mono terminals too. Both the gauge and (last) its bar drop out on narrow
     * terminals so the brand always survives.
     */
    void renderHeader(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        fillBackground(frame, area, tui.theme.contextBg());
        if (area.width() <= 0) return;
        var bg = tui.theme.contextBg();

        // Left: brand chip (reverse-video accent tag) + dim version. Stands out, ~5 cells.
        var left = new ArrayList<Span>();
        left.add(Span.styled(" isx ", Style.EMPTY.bold().fg(bg).bg(tui.theme.contextAccentFg())));
        int leftW = 5;
        var version = BuildInfo.instance().version();
        if (version != null && !version.isBlank()) {
            var vtext = "  " + version;
            left.add(Span.styled(vtext, Style.EMPTY.fg(tui.theme.contextSecondaryFg()).bg(bg)));
            leftW += vtext.length();
        }

        // Right: compact storage gauge, only when we have pool usage and room for it. Built first
        // so the centre badge can claim the leftover gap without ever evicting the gauge.
        var gauge = new ArrayList<Span>();
        int gaugeW = 0;
        if (tui.poolUsage != null && tui.poolUsage.totalBytes() > 0) {
            int percent = tui.poolUsage.percent();
            Color fillColor = percent >= IncusClient.PoolUsage.CRIT_PERCENT ? tui.theme.statusFailure()
                    : percent >= Tui.STORAGE_WARN_PERCENT ? tui.theme.statusWarning()
                    : tui.theme.statusRunning();
            var glabel = percent >= IncusClient.PoolUsage.CRIT_PERCENT ? "⚠ Storage " : "Storage ";
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
                gauge.add(Span.styled(glabel, Style.EMPTY.bold().fg(tui.theme.contextPrimaryFg()).bg(bg)));
                if (barInner >= 1) {
                    gauge.add(Span.styled("▕", Style.EMPTY.fg(fillColor).bg(bg)));
                    gauge.add(Span.styled(bar(percent, barInner), Style.EMPTY.fg(fillColor).bg(bg)));
                    gauge.add(Span.styled("▏", Style.EMPTY.fg(fillColor).bg(bg)));
                }
                gauge.add(Span.styled(readout, Style.EMPTY.fg(fillColor).bg(bg)));
                if (percent >= Tui.STORAGE_WARN_PERCENT) {
                    var hint = "  C:clean";
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

    void renderTemplateTable(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        boolean focused = tui.focusedPanel == Tui.Panel.TEMPLATES;
        var borderColor = focused ? tui.theme.panelBorderFocused() : tui.theme.panelBorderUnfocused();

        if (tui.templateEntries.isEmpty()) {
            var block = Block.builder()
                    .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                    .title(" Templates ")
                    .borderStyle(Style.EMPTY.fg(borderColor)).build();
            frame.renderWidget(block, area);
            var inner = block.inner(area);
            if (inner.height() > 0) {
                frame.renderWidget(Paragraph.from(
                        Line.styled("  No template definitions found.",
                                Style.EMPTY.fg(tui.theme.textDim()))), inner);
            }
            return;
        }

        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(" Templates ")
                .borderStyle(Style.EMPTY.fg(borderColor)).build();
        frame.renderWidget(block, area);
        var inner = block.inner(area);

        boolean showLegend = tui.anyTemplateOutdated || tui.anyDefinitionChanged || tui.anyParentRebuilt;
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

        var tableBuilder = Table.builder()
                .header(Row.from("NAME", "BUILT", "DISK", "DESCRIPTION")
                        .style(Style.EMPTY.bold().fg(focused ? tui.theme.panelBorderFocused() : tui.theme.panelBorderUnfocused())))
                .rows(tui.templateRows)
                .widths(Constraint.min(14), Constraint.length(20), Constraint.length(8), Constraint.fill())
                .highlightSymbol(focused ? "\u25b8 " : "  ");

        if (focused) {
            var highlightStyle = Style.EMPTY.bg(tui.theme.highlightBg()).fg(tui.theme.highlightFg());
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

        if (scrollArea != null) {
            var scrollbar = Scrollbar.builder()
                    .orientation(ScrollbarOrientation.VERTICAL_RIGHT)
                    .thumbStyle(Style.EMPTY.fg(borderColor))
                    .trackStyle(Style.EMPTY.fg(tui.theme.scrollbarTrack()))
                    .build();
            var scrollState = new ScrollbarState()
                    .contentLength(tui.templateRows.size())
                    .viewportContentLength(visibleRows)
                    .position(tui.templateTableState.offset());
            frame.renderStatefulWidget(scrollbar, scrollArea, scrollState);
        }
    }

    private void renderLegend(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        var text = "! = outdated  \u25b3 = changed  \u2191 = parent rebuilt ";
        var padding = Math.max(0, area.width() - text.length());
        var style = Style.EMPTY.fg(tui.theme.textDim());
        frame.renderWidget(Paragraph.from(Line.from(
                Span.styled(" ".repeat(padding) + text, style))), area);
    }

    void renderInstanceTable(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                                      TableState tableState) {
        boolean focused = tui.focusedPanel == Tui.Panel.INSTANCES;
        var borderColor = focused ? tui.theme.panelBorderFocused() : tui.theme.panelBorderUnfocused();

        if (tui.entries.isEmpty()) {
            var block = Block.builder()
                    .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                    .title(" Instances ")
                    .borderStyle(Style.EMPTY.fg(borderColor)).build();
            frame.renderWidget(block, area);
            var inner = block.inner(area);
            if (inner.height() > 1) {
                var hint = Layout.vertical()
                        .constraints(Constraint.length(inner.height() / 2), Constraint.length(1))
                        .split(inner);
                frame.renderWidget(Paragraph.from(
                        Line.styled("  No instances. Select a template and press Enter to create one.",
                                Style.EMPTY.fg(tui.theme.textDim()))), hint.get(1));
            }
            return;
        }

        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(" Instances ")
                .borderStyle(Style.EMPTY.fg(borderColor)).build();
        frame.renderWidget(block, area);
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

        var tableBuilder = Table.builder()
                .header(Row.from("NAME", "STATUS", "IP", "PARENT", "RUNTIME", "AGE", "DISK")
                        .style(Style.EMPTY.bold().fg(focused ? tui.theme.panelBorderFocused() : tui.theme.panelBorderUnfocused())))
                .rows(tui.tableRows)
                .widths(Constraint.min(10), Constraint.min(7),
                        Constraint.min(11), Constraint.min(10),
                        Constraint.length(10), Constraint.min(8), Constraint.length(6))
                .highlightSymbol(focused ? "\u25b8 " : "  ");

        if (focused) {
            var highlightStyle = Style.EMPTY.bg(tui.theme.highlightBg()).fg(tui.theme.highlightFg());
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

        if (scrollArea != null) {
            var scrollbar = Scrollbar.builder()
                    .orientation(ScrollbarOrientation.VERTICAL_RIGHT)
                    .thumbStyle(Style.EMPTY.fg(borderColor))
                    .trackStyle(Style.EMPTY.fg(tui.theme.scrollbarTrack()))
                    .build();
            var scrollState = new ScrollbarState()
                    .contentLength(tui.tableRows.size())
                    .viewportContentLength(visibleRows)
                    .position(tableState.offset());
            frame.renderStatefulWidget(scrollbar, scrollArea, scrollState);
        }
    }

    private record KeyItem(Line line, int width) {}

    void renderToolbar(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                                TableState tableState, boolean hasStatus) {
        fillBackground(frame, area, tui.theme.barBg());

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
        items.add(makeKey("F6", "Rename\u2026", !hasInstance || onTemplates));
        items.add(makeKey("F7", "Stop", !running || onTemplates));
        items.add(makeKey("F8", "Destroy\u2026", onTemplates ? !isBuilt : !hasInstance));
        boolean hasActions = hasInstance && !onTemplates && tui.hasActionsForInstance(selected);
        items.add(makeKey("F9", "Actions", !hasActions));
        items.add(makeKey("F10", "Quit", false));

        var contextLine = buildContextLine(template, selected, onTemplates);

        if (hasStatus) {
            var rows = splitVertical(area, 1, 1, 1);
            var singleLine = tui.statusMessage.replaceAll("[\\n\\r]+", " ").strip();
            var isError = singleLine.startsWith("Failed") || singleLine.startsWith("Invalid")
                    || singleLine.startsWith("Template");
            var isWarning = singleLine.startsWith("⚠") || singleLine.startsWith("Warning");
            var statusBg = tui.theme.statusBarBg();
            var msgFg = isError ? tui.theme.statusBarErrorFg()
                    : isWarning ? tui.theme.statusWarning() : tui.theme.contextPrimaryFg();
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.styled(" " + singleLine,
                                    Style.EMPTY.bold().fg(msgFg))))
                            .style(Style.EMPTY.bg(statusBg))
                            .build(), rows.get(0));
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
                var currentVersion = BuildInfo.instance().version();
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
                if (tui.templatesParentRebuilt.contains(template.name())) {
                    var parentName = tui.imageDefs.get(template.name()).getParent();
                    spans.add(Span.styled("  ↑ parent " + parentName + " was rebuilt since last build", warnStyle));
                    hasWarning = true;
                }
            }
            if (!hasWarning && template.description() != null && !template.description().isEmpty()) {
                spans.add(Span.styled("  " + template.description(), Style.EMPTY.fg(tui.theme.contextSecondaryFg()).bg(bg)));
            }
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
            return Line.from(spans);
        }
        return Line.styled("", Style.EMPTY);
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
            width += 15; // " Running: X "
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
            spans.add(Span.styled(" Running: " + running.size() + " ",
                    Style.EMPTY.fg(tui.theme.statusWarning()).bg(tui.theme.contextBg())));
            for (var task : running.stream().limit(2).toList()) {
                spans.add(Span.styled(" " + task.displayName() + "... ",
                        Style.EMPTY.fg(tui.theme.contextPrimaryFg()).bg(tui.theme.contextBg())));
            }
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

    private void renderKeyItems(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                                 List<KeyItem> items) {
        var constraints = items.stream()
                .map(item -> Constraint.ratio(1, items.size()))
                .toArray(Constraint[]::new);
        var cells = Layout.horizontal()
                .constraints(constraints)
                .split(area);
        for (int i = 0; i < items.size(); i++) {
            frame.renderWidget(Paragraph.from(items.get(i).line()), cells.get(i));
        }
    }

    private KeyItem makeKey(String key, String label, boolean disabled) {
        var spans = new ArrayList<Span>();
        spans.add(Span.styled("│", Style.EMPTY.fg(tui.theme.barSeparatorFg()).bg(tui.theme.barBg())));
        if (disabled) {
            spans.add(Span.styled(key, Style.EMPTY.fg(tui.theme.barDisabledFg()).bg(tui.theme.barBg())));
            spans.add(Span.styled(label, Style.EMPTY.fg(tui.theme.barDisabledFg()).bg(tui.theme.barBg())));
        } else {
            spans.add(Span.styled(key, Style.EMPTY.bold().fg(tui.theme.barKeyFg()).bg(tui.theme.barBg())));
            spans.add(Span.styled(label, Style.EMPTY.fg(tui.theme.barLabelFg()).bg(tui.theme.barBg())));
        }
        return new KeyItem(Line.from(spans), 1 + key.length() + label.length());
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
