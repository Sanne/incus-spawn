package dev.incusspawn.command;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.mcp.McpStanding;
import dev.incusspawn.tui.TuiTheme;
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
import dev.tamboui.widgets.paragraph.Paragraph;
import java.util.ArrayList;
import java.util.List;
import dev.incusspawn.command.InstanceListing.InstanceInfo;

import static dev.incusspawn.command.UsageFormat.gib;
import static dev.incusspawn.command.Tui.isRunning;

/**
 * The F3 instance details dialog: status, type, network, limits, disk, how an isx mcp session
 * holds it, and its credential accounts. It owns its scroll position, its scroll keys and its
 * rendering; the {@link Tui} acts on the keys it hands back (close, the shell, the accounts
 * dialog, the default action), and resolves the credential accounts it shows.
 */
final class InstanceDetailView {

    /** What a key did: a scroll key, one the view does not own, or one the TUI must act on. */
    enum Outcome { HANDLED, UNHANDLED, CLOSE, ACCOUNTS, SHELL, DEFAULT_ACTION }

    private final ModalRenderer modal;
    private final TuiTheme theme;

    private int instanceDetailScrollOffset;

    InstanceDetailView(ModalRenderer modal, TuiTheme theme) {
        this.modal = modal;
        this.theme = theme;
    }

    /** Back at the top, as each F3 opens it. */
    void open() {
        instanceDetailScrollOffset = 0;
    }

    Outcome handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC() || key.isKey(KeyCode.F3)) {
            return Outcome.CLOSE;
        }
        if (key.isChar('a')) {
            return Outcome.ACCOUNTS;
        }
        if (key.isKey(KeyCode.F2)) {
            return Outcome.SHELL;
        }
        if (key.isKey(KeyCode.ENTER)) {
            return Outcome.DEFAULT_ACTION;
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            instanceDetailScrollOffset++;
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            if (instanceDetailScrollOffset > 0) instanceDetailScrollOffset--;
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.HOME) || key.isChar('g')) {
            instanceDetailScrollOffset = 0;
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.END) || key.isChar('G')) {
            instanceDetailScrollOffset = Integer.MAX_VALUE; // capped during render
            return Outcome.HANDLED;
        }
        return Outcome.UNHANDLED;
    }

    /** Render the details of {@code selected}, with the credential accounts the TUI resolved for it. */
    void render(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen, InstanceInfo selected,
                List<dev.incusspawn.config.AccountUsage.Use> detailAccountUses) {
        var contentLines = buildInstanceDetailLines(selected, detailAccountUses);

        int maxLineWidth = 0;
        for (var line : contentLines) {
            int w = line.spans().stream().mapToInt(s -> s.content().length()).sum();
            if (w > maxLineWidth) maxLineWidth = w;
        }
        int modalWidth = Math.min(maxLineWidth + 4, screen.width() - 4);
        int maxHeight = screen.height() - 2;
        int modalHeight = Math.min(contentLines.size() + 4, maxHeight);

        var modalArea = ModalRenderer.centerRect(screen, modalWidth, modalHeight);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(modal.styledTitle(" " + selected.name() + " ", modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        modal.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var rows = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);

        instanceDetailScrollOffset = modal.renderScrollableContent(frame, rows.get(0), contentLines, instanceDetailScrollOffset);

        var hintSpans = new ArrayList<Span>();
        modal.addKey(hintSpans, "F2", "Shell");
        modal.addKey(hintSpans, "a", "Accounts");
        modal.addKey(hintSpans, "F3/Esc", "Close");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(1));
    }

    /** A row of the detail pane; a null label explains the row above. */
    record DetailRow(String label, String value) {}

    /** The detail pane's rows for an instance an isx mcp session made; none for any other. */
    static List<DetailRow> mcpDetailRows(McpStanding mcp) {
        var rows = new ArrayList<DetailRow>();
        if (mcp == null) return rows;
        switch (mcp.state()) {
            case HELD -> rows.add(new DetailRow("MCP:",
                    "held by " + (mcp.holder() == null ? "an isx mcp session" : mcp.holder())
                            + (mcp.client() == null ? "" : " (" + OutputFormat.oneLine(mcp.client()) + ")")));
            case ORPHANED -> {
                rows.add(new DetailRow("MCP:", "orphaned"
                        + (mcp.orphanedSince() == null ? "" : " since " + mcp.orphanedSince())
                        + (mcp.dormantSince() == null ? "" : ", stopped as dormant since " + mcp.dormantSince())));
                if (mcp.dormantSince() == null) {
                    rows.add(new DetailRow(null, "its session ended: another may adopt it, and one destroys it"));
                    rows.add(new DetailRow(null, "after mcp.orphan-grace-hours unless someone is working in it"));
                } else {
                    rows.add(new DetailRow(null, "its delegate never finished and stopped moving: another session"));
                    rows.add(new DetailRow(null, "may adopt it, or destroys it after mcp.dormant-grace-hours"));
                }
            }
            case KEPT -> {
                rows.add(new DetailRow("MCP:", "kept"));
                rows.add(new DetailRow(null, "an agent handed it to you: no session adopts or destroys it"));
            }
        }
        if (mcp.purpose() != null) rows.add(new DetailRow("  Purpose:", OutputFormat.oneLine(mcp.purpose())));
        if (mcp.cwd() != null) rows.add(new DetailRow("  Session cwd:", OutputFormat.oneLine(mcp.cwd())));
        return rows;
    }

    private List<Line> buildInstanceDetailLines(InstanceInfo info,
                                             List<dev.incusspawn.config.AccountUsage.Use> detailAccountUses) {
        var lines = new ArrayList<Line>();
        var lineStyle = Style.EMPTY.fg(modal.fg()).bg(modal.bg());
        var labelStyle = Style.EMPTY.fg(modal.accent()).bg(modal.bg());
        var dimStyle = Style.EMPTY.fg(theme.textDim()).bg(modal.bg());

        var statusColor = isRunning(info) ? theme.statusRunning() : theme.statusStopped();
        lines.add(Line.from(List.of(
                Span.styled("Status:         ", labelStyle),
                Span.styled(info.status(), Style.EMPTY.fg(statusColor).bg(modal.bg())))));

        lines.add(Line.from(List.of(
                Span.styled("Type:           ", labelStyle),
                Span.styled(info.runtime(), lineStyle))));

        if (!"virtual-machine".equals(info.runtime())) {
            lines.add(Line.from(List.of(
                    Span.styled("KVM:            ", labelStyle),
                    Span.styled(info.kvmEnabled() ? "enabled (/dev/kvm passed through)" : "disabled", lineStyle))));
        }

        if (!info.architecture().isEmpty()) {
            lines.add(Line.from(List.of(
                    Span.styled("Architecture:   ", labelStyle),
                    Span.styled(info.architecture(), lineStyle))));
        }

        lines.add(Line.from(List.of(
                Span.styled("Parent:         ", labelStyle),
                Span.styled(info.parent().isEmpty() ? "-" : OutputFormat.oneLine(info.parent()), lineStyle))));

        if (!info.created().isEmpty()) {
            var age = Metadata.ageDescription(info.created());
            lines.add(Line.from(List.of(
                    Span.styled("Created:        ", labelStyle),
                    Span.styled(OutputFormat.oneLine(info.created()), lineStyle),
                    Span.styled("  (" + age + ")", dimStyle))));
        }

        // #1053: no column for it in the instance table, which has no width to spare.
        for (var row : mcpDetailRows(info.mcp())) {
            lines.add(row.label() == null
                    ? Line.from(List.of(Span.styled(" ".repeat(16) + row.value(), dimStyle)))
                    : Line.from(List.of(Span.styled(String.format("%-16s", row.label()), labelStyle),
                            Span.styled(row.value(), lineStyle))));
        }

        lines.add(Line.styled("", lineStyle));

        var networkLabel = info.networkMode().isEmpty() ? "Full internet"
                : formatNetworkMode(info.networkMode());
        lines.add(Line.from(List.of(
                Span.styled("Network:        ", labelStyle),
                Span.styled(networkLabel, lineStyle))));

        lines.add(Line.from(List.of(
                Span.styled("IP address:     ", labelStyle),
                Span.styled(info.ipv4().isEmpty() ? "-" : info.ipv4(), lineStyle))));

        lines.add(Line.styled("", lineStyle));
        lines.add(Line.from(List.of(Span.styled("Resource limits:", labelStyle))));

        lines.add(Line.from(List.of(
                Span.styled("  CPU:          ", labelStyle),
                Span.styled(info.limitsCpu().isEmpty() ? "-" : info.limitsCpu(), lineStyle))));

        lines.add(Line.from(List.of(
                Span.styled("  Memory:       ", labelStyle),
                Span.styled(info.limitsMemory().isEmpty() ? "-" : info.limitsMemory(), lineStyle))));

        lines.add(Line.from(List.of(
                Span.styled("  Disk limit:   ", labelStyle),
                Span.styled(info.rootSize().isEmpty() ? "-" : info.rootSize(), lineStyle))));

        if (info.diskUsage() >= 0) {
            lines.add(Line.from(List.of(
                    Span.styled("  Disk used:    ", labelStyle),
                    Span.styled(gib(info.diskUsage()), lineStyle),
                    Span.styled("  (approx)", dimStyle))));
            var hasParent = !info.parent().isEmpty() && !"-".equals(info.parent());
            lines.add(Line.from(List.of(Span.styled(
                    hasParent
                        ? "    thin-provisioned; shares blocks with " + OutputFormat.oneLine(info.parent())
                        : "    thin-provisioned; copy-on-write",
                    dimStyle))));
        }

        lines.add(Line.styled("", lineStyle));

        if (!detailAccountUses.isEmpty()) {
            lines.add(Line.from(List.of(Span.styled("Credential accounts:", labelStyle),
                    Span.styled("  (a to change)", dimStyle))));
            int nsWidth = detailAccountUses.stream().mapToInt(u -> u.namespace().length()).max().orElse(0);
            for (var use : detailAccountUses) {
                var ns = use.namespace() + " ".repeat(nsWidth - use.namespace().length());
                var spans = new ArrayList<Span>();
                spans.add(Span.styled("  " + ns + "  ", labelStyle));
                spans.add(Span.styled(use.account().isEmpty() ? "(none)" : use.account(),
                        use.problem().isEmpty() ? lineStyle : Style.EMPTY.fg(theme.modalWarn()).bg(modal.bg())));
                if (!use.description().isEmpty()) spans.add(Span.styled(" -- " + use.description(), dimStyle));
                lines.add(Line.from(spans));
                var template = InstanceActions.resolveTemplateName(info);
                lines.add(Line.from(List.of(Span.styled("    " + dev.incusspawn.config.AccountUsage.explainSource(
                        use, template == null ? "-" : template, info.name().equals(template)), dimStyle))));
                if (!use.problem().isEmpty()) {
                    lines.add(Line.from(List.of(Span.styled("    not configured: requests fail until it is,"
                            + " or it is changed", Style.EMPTY.fg(theme.modalWarn()).bg(modal.bg())))));
                } else if (!use.refusal().isEmpty()) {
                    lines.add(Line.from(List.of(Span.styled("    refused: built for another auth mode;"
                            + " press a to pin one it can use", Style.EMPTY.fg(theme.modalWarn()).bg(modal.bg())))));
                } else if (!use.templateProblem().isEmpty()) {
                    lines.add(Line.from(List.of(Span.styled("    the template names '" + use.templateAccount()
                            + "', which is not configured", dimStyle))));
                } else if (use.differsFromTemplate()) {
                    lines.add(Line.from(List.of(Span.styled("    the template now names '"
                            + use.templateAccount() + "'", dimStyle))));
                }
            }
            lines.add(Line.styled("", lineStyle));
        }

        lines.add(Line.from(List.of(
                Span.styled("Project:        ", labelStyle),
                Span.styled(info.project(), lineStyle))));

        lines.add(Line.from(List.of(
                Span.styled("Profile:        ", labelStyle),
                Span.styled(info.profile(), lineStyle))));

        return lines;
    }

    private static String formatNetworkMode(String mode) {
        try {
            return NetworkMode.valueOf(mode).label();
        } catch (IllegalArgumentException e) {
            return mode;
        }
    }
}
