package dev.incusspawn.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.incusspawn.config.AccountOrigin;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tui.TuiTheme;
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

/**
 * The credential account rows of the TUI's branch dialog: one dropdown per credential the
 * template uses, each offering "inherit" -- what the branch gets with no override, and so what
 * {@code isx branch} without {@code --account} does -- and then every usable account as an
 * explicit pin, the inherited one included: pinning the account that is the global default is a
 * different choice from following the default, since the pin stays when the default moves.
 *
 * <p>Independent of Incus and of {@link Tui}, so it can be driven and rendered headlessly;
 * the dialog passes keys in while a row is focused and reads {@link #overrides()} when it
 * branches.
 */
final class BranchAccountChoices {

    /**
     * An account a row can pin.
     *
     * @param description   {@link ToolSetup#describeAccount}, never a credential
     * @param requiredBuild what the template would have to be built for to use it (a Claude auth
     *                      mode), or {@code ""} when it can be used as it is
     */
    record Option(String account, String description, String requiredBuild) {
        boolean usable() {
            return requiredBuild.isEmpty();
        }
    }

    /**
     * One credential.
     *
     * @param inheritedAccount the account the branch gets without an override, or {@code ""}
     * @param inheritedSource  where that comes from, in words
     * @param inheritedPinned  whether inheriting pins it (a template's or the source's choice)
     *                         rather than following the global default
     * @param inheritedProblem why the inherited account cannot be served, or {@code ""}
     */
    record Row(String namespace, String inheritedAccount, String inheritedSource, boolean inheritedPinned,
               String inheritedProblem, List<Option> options) {}

    private final ModalRenderer modal;
    private final TuiTheme theme;
    private final List<Row> rows;
    /** Per row: -1 inherits, otherwise the index of the pinned {@link Option}. */
    private final int[] selected;
    /** The row whose dropdown is open, or -1. */
    private int openRow = -1;
    /** In the open dropdown: 0 is "inherit", i + 1 is option i. */
    private int highlight;

    BranchAccountChoices(ModalRenderer modal, TuiTheme theme, List<Row> rows) {
        this.modal = modal;
        this.theme = theme;
        this.rows = List.copyOf(rows);
        this.selected = new int[rows.size()];
        java.util.Arrays.fill(selected, -1);
    }

    /**
     * The rows for a branch of a source: every credential the template's tools use or the
     * inherited selection mentions, where there is a choice to make -- at least two usable
     * accounts, or an inherited pin that no longer resolves and needs replacing. A credential
     * with a single account has nothing to decide and gets no row, keeping the dialog short.
     *
     * @param namespaces      the credentials the template's tools use
     *                        ({@link AccountSelection#templateNamespaces})
     * @param bakedIdentities the source's {@code account-identity} stamps
     */
    static List<Row> rowsFor(SpawnConfig config, Map<String, ToolSetup> setups, Set<String> namespaces,
                             BranchFlow.Inherited inherited, Map<String, String> bakedIdentities) {
        var all = new java.util.LinkedHashSet<>(namespaces);
        all.addAll(inherited.accounts().keySet());
        var tree = config.tree();
        var rows = new ArrayList<Row>();
        for (var namespace : all) {
            var setup = setups.get(namespace);
            var shape = setup != null ? setup.accountShape() : AccountResolver.shapeOf(config, namespace);
            var usable = AccountResolver.usableAccountNames(tree, namespace, shape);

            var pin = inherited.accounts().getOrDefault(namespace, "");
            var problem = "";
            String account;
            try {
                account = AccountResolver.effectiveAccount(tree, namespace, shape, pin);
            } catch (AccountResolver.UnknownAccountException e) {
                account = pin;
                problem = e.getMessage();
            }
            if (usable.size() < 2 && problem.isEmpty()) continue;

            var options = new ArrayList<Option>();
            for (var name : usable) {
                var description = setup == null ? "" : setup.describeAccount(config, name);
                options.add(new Option(name, description == null ? "" : description,
                        AccountSelection.requiredRebuild(config, setup, namespace, name, bakedIdentities)));
            }
            rows.add(new Row(namespace, account,
                    inheritedSource(pin, inherited.origins().get(namespace), inherited.template()),
                    !pin.isEmpty(), problem, options));
        }
        return rows;
    }

    private static String inheritedSource(String pin, AccountOrigin origin, String template) {
        if (pin.isEmpty()) return "global default, follows it";
        if (origin == null) return "as the source has it";
        return switch (origin.kind()) {
            case TEMPLATE -> "as " + origin.source() + " chooses";
            case EXPLICIT, COPIED -> "as " + origin.source() + " was set";
            case UNKNOWN -> "as the source has it";
        };
    }

    int size() {
        return rows.size();
    }

    boolean isOpen() {
        return openRow >= 0;
    }

    /** {@code namespace=account} for every row set to an explicit pin, as {@code --account} takes them. */
    List<String> overrides() {
        var overrides = new ArrayList<String>();
        for (int i = 0; i < rows.size(); i++) {
            if (selected[i] >= 0) {
                overrides.add(rows.get(i).namespace() + "=" + rows.get(i).options().get(selected[i]).account());
            }
        }
        return overrides;
    }

    /** Opens row {@code i}'s dropdown on its current choice. */
    void open(int i) {
        openRow = i;
        highlight = selected[i] + 1;
    }

    /** Steps row {@code i}'s choice without opening it, skipping options it cannot use. */
    void cycle(int i, int direction) {
        var count = rows.get(i).options().size() + 1;
        var index = selected[i] + 1;
        for (int step = 0; step < count; step++) {
            index = Math.floorMod(index + direction, count);
            if (index == 0 || rows.get(i).options().get(index - 1).usable()) {
                selected[i] = index - 1;
                return;
            }
        }
    }

    /** Keys while a dropdown is open; every key is consumed. */
    void handleOpenKey(KeyEvent key) {
        var row = rows.get(openRow);
        var count = row.options().size() + 1;
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) {
            openRow = -1;
        } else if (key.isKey(KeyCode.DOWN) || key.isChar('j') || key.isKey(KeyCode.TAB)) {
            highlight = Math.floorMod(highlight + 1, count);
        } else if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            highlight = Math.floorMod(highlight - 1, count);
        } else if (key.isKey(KeyCode.HOME) || key.isChar('g')) {
            highlight = 0;
        } else if (key.isKey(KeyCode.END) || key.isChar('G')) {
            highlight = count - 1;
        } else if (key.isKey(KeyCode.ENTER) || key.isChar(' ')) {
            // An option the template cannot honour stays highlighted with its reason; choosing
            // it would only be refused when the branch is created.
            if (highlight == 0 || row.options().get(highlight - 1).usable()) {
                selected[openRow] = highlight - 1;
                openRow = -1;
            }
        }
    }

    // ── rendering ────────────────────────────────────────────────────────────

    private int labelWidth() {
        return rows.stream().mapToInt(r -> r.namespace().length()).max().orElse(0);
    }

    /** Row {@code i} as a field: the account in effect, and whether it is inherited or pinned. */
    void renderRow(Frame frame, Rect area, int i, boolean focused) {
        var row = rows.get(i);
        var bg = modal.bg();
        var spans = new ArrayList<Span>();
        spans.add(Span.styled(focused ? " ▸ " : "   ", Style.EMPTY.fg(modal.accent()).bg(bg)));
        var label = row.namespace() + " ".repeat(labelWidth() - row.namespace().length()) + "  ";
        spans.add(Span.styled(label, Style.EMPTY.fg(focused ? theme.focusedLabel() : modal.fg()).bg(bg)));
        if (selected[i] < 0) {
            var account = row.inheritedAccount().isEmpty() ? "(none)" : row.inheritedAccount();
            spans.add(Span.styled(account, Style.EMPTY.fg(row.inheritedProblem().isEmpty()
                    ? modal.fg() : theme.modalWarn()).bg(bg)));
            spans.add(Span.styled(" · " + (row.inheritedProblem().isEmpty()
                    ? row.inheritedSource() : "not configured"), Style.EMPTY.fg(theme.textDim()).bg(bg)));
        } else {
            spans.add(Span.styled(row.options().get(selected[i]).account(),
                    Style.EMPTY.bold().fg(modal.accent()).bg(bg)));
            spans.add(Span.styled(" · pinned", Style.EMPTY.fg(modal.accent()).bg(bg)));
        }
        spans.add(Span.styled(" ▾", Style.EMPTY.fg(theme.textDim()).bg(bg)));
        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }

    /** The open dropdown, drawn over the dialog just below its row (above it when there is no room). */
    void renderOpen(Frame frame, Rect screen, Rect rowArea) {
        if (openRow < 0) return;
        var row = rows.get(openRow);
        var lines = new ArrayList<Line>();
        var nameWidth = Math.max("inherit".length(),
                row.options().stream().mapToInt(o -> o.account().length()).max().orElse(0));
        lines.add(entry(0, "inherit", pad(row.inheritedAccount().isEmpty() ? "(none)" : row.inheritedAccount(), 0)
                + " · " + (row.inheritedProblem().isEmpty() ? row.inheritedSource() : "not configured"), true, nameWidth));
        // Said once rather than on every entry: everything below it pins, and the space is
        // better spent on what each account is.
        lines.add(Line.styled("  or pin for this branch:", Style.EMPTY.fg(theme.textDim()).bg(modal.bg())));
        for (int k = 0; k < row.options().size(); k++) {
            var option = row.options().get(k);
            lines.add(entry(k + 1, option.account(), optionDetail(row, option), option.usable(), nameWidth));
        }

        int contentWidth = lines.stream().mapToInt(l -> l.spans().stream().mapToInt(s -> s.content().length()).sum())
                .max().orElse(20);
        int width = Math.min(screen.width() - 2, contentWidth + 4);
        int height = Math.min(screen.height() - 2, lines.size() + 2);
        int x = Math.max(screen.x(), Math.min(rowArea.x() + 2 + labelWidth() + 2, screen.x() + screen.width() - width));
        int below = rowArea.y() + 1;
        int y = below + height <= screen.y() + screen.height() ? below : Math.max(screen.y(), rowArea.y() - height);
        var area = new Rect(x, y, width, height);

        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(modal.accent()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        frame.renderWidget(dev.tamboui.widgets.Clear.INSTANCE, area);
        frame.renderWidget(block, area);
        frame.renderWidget(Paragraph.from(dev.tamboui.text.Text.from(lines.toArray(new Line[0]))), block.inner(area));
    }

    /** What pinning {@code option} means, or why it cannot be chosen. */
    private static String optionDetail(Row row, Option option) {
        // The reason, not the internal auth-mode name ('api-key') -- nor the description, which
        // for Vertex runs long enough to push the reason off the screen.
        if (!option.usable()) return "not usable: this template was built for another auth mode";
        var parts = new ArrayList<String>();
        if (!option.description().isEmpty()) parts.add(option.description());
        // The one pin that looks like inheriting today, and is not once the default moves.
        if (option.account().equals(row.inheritedAccount()) && !row.inheritedPinned()) {
            parts.add("stays if the default changes");
        }
        return String.join(" · ", parts);
    }

    private Line entry(int index, String name, String detail, boolean usable, int nameWidth) {
        var bg = modal.bg();
        var current = index == highlight;
        var nameStyle = !usable ? Style.EMPTY.fg(theme.textDim()).bg(bg)
                : current ? Style.EMPTY.bold().fg(theme.focusedLabel()).bg(bg)
                : Style.EMPTY.fg(modal.fg()).bg(bg);
        return Line.from(List.of(
                Span.styled(current ? "▸ " : "  ", Style.EMPTY.fg(modal.accent()).bg(bg)),
                Span.styled(pad(name, nameWidth) + "  ", nameStyle),
                Span.styled(detail, Style.EMPTY.fg(theme.textDim()).bg(bg))));
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
