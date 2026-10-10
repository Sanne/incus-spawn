package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
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

/**
 * The F1 dialog: what this build is and runs on, and the keyboard shortcuts. It owns its scroll
 * position, its keys and its rendering; the {@link Tui} closes it and opens AI Help from it.
 * What it says about the host comes through {@link Source}, so it can be rendered headlessly.
 */
final class AboutModal {

    /** What a key did: nothing the TUI must act on, close the dialog, or open AI Help. */
    enum Outcome { HANDLED, CLOSE, HELP_CHAT }

    /** What the dialog reads about this build and the host: {@link BuildInfo} and the platform. */
    interface Source {
        String version();

        String gitSha();

        String incusClient();

        String incusServer();

        String runtime();

        String kernelInfo();

        boolean isMacOS();
    }

    private final ModalRenderer modal;
    private final TuiTheme theme;
    private final Source source;

    private int infoScrollOffset;

    AboutModal(ModalRenderer modal, TuiTheme theme, Source source) {
        this.modal = modal;
        this.theme = theme;
        this.source = source;
    }

    /** Back at the top, as each F1 opens it. */
    void open() {
        infoScrollOffset = 0;
    }

    Outcome handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC() || key.isKey(KeyCode.F1)) {
            return Outcome.CLOSE;
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            infoScrollOffset++;
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            if (infoScrollOffset > 0) infoScrollOffset--;
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.HOME) || key.isChar('g')) {
            infoScrollOffset = 0;
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.END) || key.isChar('G')) {
            infoScrollOffset = Integer.MAX_VALUE;
            return Outcome.HANDLED;
        }
        if (key.isChar('?')) {
            return Outcome.HELP_CHAT;
        }
        return Outcome.HANDLED;
    }

    void render(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        var info = source;
        var lines = new ArrayList<>(List.of(
                Line.from(List.of(
                        Span.styled("incus-spawn", Style.EMPTY.bold().fg(modal.accent()).bg(modal.bg())),
                        Span.styled(" (isx) ", Style.EMPTY.fg(modal.fg()).bg(modal.bg())),
                        Span.styled(info.version(), Style.EMPTY.fg(theme.statusSuccess()).bg(modal.bg())))),
                Line.styled("Commit " + info.gitSha(),
                        Style.EMPTY.fg(theme.textDim()).bg(modal.bg())),
                Line.styled("Incus  client " + info.incusClient() + ", server " + info.incusServer(),
                        Style.EMPTY.fg(theme.textDim()).bg(modal.bg())),
                Line.styled(info.runtime(),
                        Style.EMPTY.fg(theme.textDim()).bg(modal.bg()))));
        var kernelInfo = info.kernelInfo();
        if (!kernelInfo.isEmpty()) {
            var kernelLabel = source.isMacOS() ? "VM     " : "Host   ";
            lines.add(Line.styled(kernelLabel + kernelInfo,
                    Style.EMPTY.fg(theme.textDim()).bg(modal.bg())));
        }
        lines.addAll(List.of(
                Line.styled("", Style.EMPTY),
                Line.styled("Copyright 2026 Sanne Grinovero",
                        Style.EMPTY.fg(modal.fg()).bg(modal.bg())),
                Line.styled("Licensed under the Apache License 2.0",
                        Style.EMPTY.fg(theme.textDim()).bg(modal.bg())),
                Line.styled("github.com/Sanne/incus-spawn",
                        Style.EMPTY.fg(modal.accent()).bg(modal.bg()))
                        .hyperlink("https://github.com/Sanne/incus-spawn"),
                Line.styled("", Style.EMPTY),
                Line.styled("Manage isolated Incus development environments.",
                        Style.EMPTY.fg(modal.fg()).bg(modal.bg())),
                Line.styled("Templates define base images; Instances are", Style.EMPTY.fg(modal.fg()).bg(modal.bg())),
                Line.styled("lightweight copy-on-write branches of them.", Style.EMPTY.fg(modal.fg()).bg(modal.bg())),
                Line.styled("", Style.EMPTY),
                Line.styled("Keyboard shortcuts:", Style.EMPTY.fg(modal.fg()).bg(modal.bg())),
                Line.styled("", Style.EMPTY),
                shortcutRow("Enter", "Default instance action", null, null),
                shortcutRow("?", "AI Help — ask a question", null, null),
                shortcutRow("Tab", "Switch panels", "⇧Tab", "Reverse"),
                shortcutRow("F1", "This dialog", null, null),
                shortcutRow("F2", "Shell into instance", null, null),
                shortcutRow("F3", "View details", null, null),
                shortcutRow("F4", "Branch", null, null),
                shortcutRow("F5", "Build menu", null, null),
                shortcutRow("F6", "Rename instance", null, null),
                shortcutRow("F7", "Stop instance", "⇧F7", "Restart"),
                shortcutRow("F8/Del", "Destroy", "⇧F8/Del", "Destroy all"),
                shortcutRow("F9", "Tool actions", null, null),
                shortcutRow("F10", "Quit", null, null),
                shortcutRow("a", "Credential accounts", null, null),
                shortcutRow("C", "Clean pool storage", null, null),
                shortcutRow("r", "Refresh", null, null),
                shortcutRow(WarningsModal.KEY, "Warnings", null, null),
                shortcutRow("n", "New template…", null, null),
                shortcutRow("/", "Search / filter", null, null),
                shortcutRow("g/Home", "Jump to top", "G/End", "Jump to bottom")));
        if (source.isMacOS()) {
            lines.add(Line.styled("", Style.EMPTY));
            lines.add(Line.styled("macOS shortcuts:", Style.EMPTY.fg(modal.fg()).bg(modal.bg())));
            lines.add(shortcutRow("Fn+←/→", "Home / End", "Fn+↑/↓", "PgUp / PgDn"));
        }

        int width = 60;
        int maxHeight = screen.height() - 2;
        int modalHeight = Math.min(lines.size() + 4, maxHeight);

        var modalArea = ModalRenderer.centerRect(screen, width, modalHeight);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(modal.styledTitle(" About incus-spawn ", modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        modal.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var rows = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);

        infoScrollOffset = modal.renderScrollableContent(frame, rows.get(0), lines, infoScrollOffset);

        var hintSpans = new ArrayList<Span>();
        modal.addKey(hintSpans, "F1/Esc", "Close");
        modal.addKey(hintSpans, "?", "AI-assisted help");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(1));
    }

    private Line shortcutRow(String key, String desc, String shiftKey, String shiftDesc) {
        var spans = new ArrayList<Span>();
        var keyStr = key != null ? key : "";
        var descStr = desc != null ? desc : "";
        spans.add(Span.styled(String.format("  %-8s", keyStr), Style.EMPTY.bold().fg(modal.accent()).bg(modal.bg())));
        spans.add(Span.styled(String.format("%-18s", descStr), Style.EMPTY.fg(modal.fg()).bg(modal.bg())));
        if (shiftKey != null) {
            spans.add(Span.styled(String.format("%-9s", shiftKey), Style.EMPTY.bold().fg(modal.accent()).bg(modal.bg())));
            spans.add(Span.styled(shiftDesc, Style.EMPTY.fg(modal.fg()).bg(modal.bg())));
        }
        return Line.from(spans);
    }
}
