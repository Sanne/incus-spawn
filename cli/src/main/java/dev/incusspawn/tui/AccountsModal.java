package dev.incusspawn.tui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolSetup;
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
import dev.tamboui.widgets.select.SelectState;

/**
 * The TUI's "Credential accounts" dialog: which account one instance uses per credential
 * namespace, and changing it -- {@code isx account set/unset} in a form. Independent of Incus and
 * of {@link Tui}, so it can be rendered and driven headlessly in tests; the caller checks
 * and applies {@link #changes()}.
 *
 * <p>The first option of every row is "follow the default", which is what an unpinned namespace
 * does. It is spelled out with the account the default currently resolves to, because pinning
 * that same account looks identical today and behaves differently once the default changes.
 */
final class AccountsModal {

    /**
     * One credential namespace.
     *
     * @param accounts     usable accounts, in file order
     * @param defaultName  what the default resolves to now, or {@code ""}
     * @param pinned       the instance's pin, or {@code ""} when it follows the default
     * @param descriptions {@link ToolSetup#describeAccount} per account
     */
    record Row(String namespace, List<String> accounts, String defaultName, String pinned,
               Map<String, String> descriptions) {}

    enum Outcome { STAY, CLOSE, APPLY }

    /** The selector, what the account is, and where it would come from. */
    private static final int LINES_PER_ROW = 3;

    private final ModalRenderer modal;
    private final TuiTheme theme;
    private final String instance;
    private final List<Row> rows;
    /** Per row: index 0 follows the default, then {@link #options}'s accounts. */
    private final List<SelectState> selects = new ArrayList<>();
    private final List<List<String>> options = new ArrayList<>();
    private int field;
    private String error = "";

    AccountsModal(ModalRenderer modal, TuiTheme theme, String instance, List<Row> rows) {
        this.modal = modal;
        this.theme = theme;
        this.instance = instance;
        this.rows = List.copyOf(rows);
        for (var row : rows) {
            var accounts = new ArrayList<>(row.accounts());
            // A pin to an account that is gone must still be representable, or opening the dialog
            // would show a different selection than the instance has -- and applying it unchanged
            // would silently re-point the instance.
            if (!row.pinned().isEmpty() && !accounts.contains(row.pinned())) accounts.add(row.pinned());
            options.add(accounts);
            var labels = new ArrayList<String>();
            labels.add(row.defaultName().isEmpty() ? "global default (none configured)"
                    : "global default (" + row.defaultName() + ")");
            for (var account : accounts) {
                labels.add(row.accounts().contains(account) ? account : account + " (not configured)");
            }
            selects.add(new SelectState(labels,
                    row.pinned().isEmpty() ? 0 : accounts.indexOf(row.pinned()) + 1));
        }
    }

    /**
     * The rows for one instance: every namespace with a usable account, plus any the instance
     * pins. Incomplete accounts are left out -- pinning one is refused anyway.
     */
    static List<Row> rowsFor(SpawnConfig config, Map<String, ToolSetup> setups, Map<String, String> pins) {
        var tree = config.tree();
        var result = new ArrayList<Row>();
        for (var entry : setups.entrySet()) {
            var namespace = entry.getKey();
            var setup = entry.getValue();
            var shape = setup.accountShape();
            var accounts = AccountResolver.usableAccountNames(tree, namespace, shape);
            var pinned = pins.getOrDefault(namespace, "");
            if (accounts.isEmpty() && pinned.isEmpty()) continue;
            var descriptions = new LinkedHashMap<String, String>();
            for (var account : accounts) {
                var description = setup.describeAccount(config, account);
                descriptions.put(account, description == null ? "" : description);
            }
            result.add(new Row(namespace, accounts,
                    AccountResolver.effectiveAccount(tree, namespace, shape, null), pinned, descriptions));
        }
        return result;
    }

    String instance() {
        return instance;
    }

    /** Shown in the dialog -- a refusal is best read while the choice is still on screen. */
    void setError(String message) {
        error = message == null ? "" : message;
    }

    /**
     * What the user changed: namespace to account, or to {@code null} to follow the default.
     * Rows left as they were are absent, so applying never touches a namespace nobody changed.
     */
    Map<String, String> changes() {
        var changes = new LinkedHashMap<String, String>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var index = selects.get(i).selectedIndex();
            var chosen = index == 0 ? "" : options.get(i).get(index - 1);
            if (chosen.equals(row.pinned())) continue;
            changes.put(row.namespace(), chosen.isEmpty() ? null : chosen);
        }
        return changes;
    }

    Outcome handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) return Outcome.CLOSE;
        if (key.isKey(KeyCode.ENTER)) return changes().isEmpty() ? Outcome.CLOSE : Outcome.APPLY;
        if (rows.isEmpty()) return Outcome.STAY;
        if (ShiftTabBindings.isShiftTab(key) || key.isKey(KeyCode.UP) || key.isChar('k')) {
            field = (field - 1 + rows.size()) % rows.size();
            return Outcome.STAY;
        }
        if (key.isKey(KeyCode.TAB) || key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            field = (field + 1) % rows.size();
            return Outcome.STAY;
        }
        var select = selects.get(field);
        if (key.isKey(KeyCode.RIGHT) || key.isChar(' ') || key.isChar('l')) {
            select.selectNext();
            error = "";
        } else if (key.isKey(KeyCode.LEFT) || key.isChar('h')) {
            select.selectPrevious();
            error = "";
        }
        return Outcome.STAY;
    }

    void render(Frame frame, Rect screen) {
        var lines = rows.size() * LINES_PER_ROW;
        int width = Math.min(screen.width() - 4, 76);
        // Borders and padding; a refusal explains itself at length, so it wraps rather than clips.
        var errorText = error.isEmpty() ? List.<String>of() : wrap(error, Math.max(10, width - 4));
        var errorLines = errorText.isEmpty() ? 0 : 1 + errorText.size();
        int height = Math.min(screen.height() - 2, 2 + 2 + lines + errorLines + 2);
        var area = ModalRenderer.centerRect(screen, width, height);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(modal.styledTitle(" Credential accounts: " + instance + " ", modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        modal.renderBlock(frame, block, area);
        var inner = block.inner(area);

        var constraints = new ArrayList<Constraint>();
        constraints.add(Constraint.length(1));           // intro
        constraints.add(Constraint.length(1));           // blank
        for (int i = 0; i < lines; i++) constraints.add(Constraint.length(1));
        for (int i = 0; i < errorLines; i++) constraints.add(Constraint.length(1));
        constraints.add(Constraint.fill());
        constraints.add(Constraint.length(1));           // hints
        var cells = Layout.vertical().constraints(constraints.toArray(new Constraint[0])).split(inner);

        var dim = Style.EMPTY.fg(theme.textDim()).bg(modal.bg());
        int cell = 0;
        frame.renderWidget(Paragraph.from(Line.styled(rows.isEmpty()
                ? "No credential accounts are configured. Run 'isx init' to add one."
                : "Takes effect on the next request; nothing inside restarts.", dim)), cells.get(cell++));
        cell++;

        int labelWidth = rows.stream().mapToInt(r -> r.namespace().length()).max().orElse(0);
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var label = row.namespace() + " ".repeat(labelWidth - row.namespace().length());
            modal.renderSelect(frame, cells.get(cell++), label, selects.get(i), i == field);
            var pad = " ".repeat(labelWidth + 4);
            frame.renderWidget(Paragraph.from(Line.styled(pad + description(i), dim)), cells.get(cell++));
            frame.renderWidget(Paragraph.from(Line.styled(pad + source(i), dim)), cells.get(cell++));
        }
        if (errorLines > 0) {
            cell++;
            for (var text : errorText) {
                frame.renderWidget(Paragraph.from(Line.styled(text,
                        Style.EMPTY.fg(theme.modalWarn()).bg(modal.bg()))), cells.get(cell++));
            }
        }

        var hints = new ArrayList<Span>();
        modal.addKey(hints, "Enter", "Apply");
        modal.addKey(hints, "Esc", "Cancel");
        modal.addKey(hints, "↑↓", "Credential");
        modal.addKey(hints, "←→/Space", "Account");
        frame.renderWidget(Paragraph.from(Line.from(hints)), cells.get(cells.size() - 1));
    }

    static List<String> wrap(String text, int width) {
        var out = new ArrayList<String>();
        var line = new StringBuilder();
        for (var word : text.split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** Under a row: what the selected account is -- its type, or whose identity. */
    private String description(int i) {
        var row = rows.get(i);
        var account = selectedAccount(i);
        if (account.isEmpty()) account = row.defaultName();
        return row.descriptions().getOrDefault(account, "");
    }

    /** Under a row: where the account would come from, and so what would change it. */
    private String source(int i) {
        var row = rows.get(i);
        var account = selectedAccount(i);
        if (account.isEmpty()) {
            return row.defaultName().isEmpty() ? "no account is configured"
                    : "not pinned: changes if the global default is changed";
        }
        if (!row.accounts().contains(account)) return "not configured: requests fail until it is";
        return account.equals(row.defaultName())
                ? "pinned to this instance: kept even if the global default is changed"
                : "pinned to this instance";
    }

    /** The account a row's selection pins, or {@code ""} for the global default. */
    private String selectedAccount(int i) {
        var index = selects.get(i).selectedIndex();
        return index == 0 ? "" : options.get(i).get(index - 1);
    }
}
