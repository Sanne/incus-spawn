package dev.incusspawn.tui;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.lifecycle.BranchFlow;
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
import dev.tamboui.widgets.checkbox.CheckboxState;
import dev.tamboui.widgets.input.TextInput;
import dev.tamboui.widgets.input.TextInputState;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.select.SelectState;

import java.util.ArrayList;

/**
 * The TUI's branch dialog (F4, or Enter on a template): the new instance's name, a VM's limits,
 * the GUI, KVM, network and inbox toggles, and the credential accounts. It owns its fields, their
 * keys and its rendering; the {@link Tui} confirms it (the proxy, credential and still-exists
 * checks) and creates the branch once the terminal is released, from {@link #request}.
 */
final class BranchModal {

    /** What a key did: nothing the TUI must act on, close the dialog, or confirm the branch. */
    enum Outcome { HANDLED, CLOSE, CONFIRM }

    private final ModalRenderer modal;
    private final TuiTheme theme;
    private final String branchSourceName;


    private TextInputState branchNameInput;

    /** The branch dialog's credential account rows; empty when there is nothing to choose. */
    private BranchAccountChoices branchAccounts;

    private CheckboxState branchGuiCheck;

    /** What the GUI box started as: left there, the request leaves GUI to BranchFlow's default. */
    private boolean branchGuiDefault;

    private CheckboxState branchKvmCheck;

    private NetworkMode[] branchNetworkModes;

    private SelectState branchNetworkSelect;

    private CheckboxState branchInboxCheck;

    private TextInputState branchInboxInput;

    private boolean branchSourceIsVm;

    private TextInputState vmCpuInput;

    private TextInputState vmMemoryInput;

    private TextInputState vmDiskInput;

    private int branchFieldIndex;

    BranchModal(ModalRenderer modal, TuiTheme theme, String sourceName, String suggestedName,
                BranchFlow.Defaults defaults, BranchAccountChoices accounts) {
        this.modal = modal;
        this.theme = theme;
        this.branchSourceName = sourceName;
        branchNameInput = new TextInputState(suggestedName);
        branchGuiCheck = new CheckboxState(defaults.gui());
        branchGuiDefault = defaults.gui();
        branchKvmCheck = new CheckboxState(defaults.kvm());
        branchNetworkModes = NetworkMode.values();
        branchNetworkSelect = new SelectState(java.util.Arrays.stream(branchNetworkModes)
                .map(NetworkMode::label).toArray(String[]::new));
        branchInboxCheck = new CheckboxState(false);
        branchInboxInput = new TextInputState("");
        branchSourceIsVm = defaults.machineType() == MachineType.VM;
        // Only a VM's limits are fields: a container's would be worked out (a sysctl fork on macOS) and ignored
        vmCpuInput = new TextInputState(branchSourceIsVm ? String.valueOf(defaults.cpu()) : "");
        vmMemoryInput = new TextInputState(branchSourceIsVm ? defaults.memory() : "");
        vmDiskInput = new TextInputState(branchSourceIsVm ? defaults.disk() : "");
        branchAccounts = accounts;
        branchFieldIndex = 0;
    }

    /** The name typed so far, stripped. */
    String name() {
        return branchNameInput.text().strip();
    }

    /** The credential accounts pinned in the dialog, as {@link BranchAccountChoices#overrides()} gives them. */
    java.util.List<String> accountOverrides() {
        return branchAccounts.overrides();
    }

    NetworkMode branchNetworkMode() {
        return branchNetworkModes[branchNetworkSelect.selectedIndex()];
    }

    /** A key while the dialog is open; Enter is the TUI's to confirm. */
    Outcome handleKey(KeyEvent key) {
        // An open dropdown takes every key, Esc and Enter included: they close it, not the dialog.
        if (branchAccounts.isOpen()) {
            branchAccounts.handleOpenKey(key);
            return Outcome.HANDLED;
        }
        if (isAccountField(branchFieldIndex)) {
            var row = branchFieldIndex - accountFieldBase();
            // Space opens it, as Space toggles every other field; Enter still confirms the branch.
            if (key.isChar(' ')) {
                branchAccounts.open(row);
                return Outcome.HANDLED;
            }
            if (key.isKey(KeyCode.RIGHT) || key.isKey(KeyCode.LEFT)) {
                branchAccounts.cycle(row, key.isKey(KeyCode.RIGHT) ? 1 : -1);
                return Outcome.HANDLED;
            }
        }
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) {
            return Outcome.CLOSE;
        }
        if (key.isKey(KeyCode.ENTER)) {
            return Outcome.CONFIRM;
        }
        // Space: toggle/cycle when on a toggle field
        if (key.code() == KeyCode.CHAR && key.character() == ' ' && isToggleField(branchFieldIndex)) {
            if (branchFieldIndex == guiFieldIndex()) {
                branchGuiCheck.toggle();
            } else if (branchFieldIndex == kvmFieldIndex()) {
                branchKvmCheck.toggle();
            } else if (branchFieldIndex == networkFieldIndex()) {
                branchNetworkSelect.selectNext();
            } else if (branchFieldIndex == inboxFieldIndex()) {
                branchInboxCheck.toggle();
            }
            return Outcome.HANDLED;
        }
        // Shift+Tab / Up: cycle backward (check Shift+Tab before Tab to avoid matching TAB+Shift)
        if (ShiftTabBindings.isShiftTab(key) || key.isKey(KeyCode.UP)) {
            int max = maxBranchField();
            branchFieldIndex = (branchFieldIndex - 1 + max + 1) % (max + 1);
            return Outcome.HANDLED;
        }
        // Tab / Down: cycle forward
        if (key.isKey(KeyCode.TAB) || key.isKey(KeyCode.DOWN)) {
            branchFieldIndex = (branchFieldIndex + 1) % (maxBranchField() + 1);
            return Outcome.HANDLED;
        }

        // Text-editing keys only apply to text input fields
        if (!isToggleField(branchFieldIndex)) {
            var activeInput = activeBranchInput();
            if (key.isKey(KeyCode.BACKSPACE)) { activeInput.deleteBackward(); return Outcome.HANDLED; }
            if (key.isKey(KeyCode.DELETE))    { activeInput.deleteForward(); return Outcome.HANDLED; }
            if (key.isKey(KeyCode.LEFT))      { activeInput.moveCursorLeft(); return Outcome.HANDLED; }
            if (key.isKey(KeyCode.RIGHT))     { activeInput.moveCursorRight(); return Outcome.HANDLED; }
            if (key.isKey(KeyCode.HOME))      { activeInput.moveCursorToStart(); return Outcome.HANDLED; }
            if (key.isKey(KeyCode.END))       { activeInput.moveCursorToEnd(); return Outcome.HANDLED; }
            if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
                char ch = key.character();
                if (branchFieldIndex == 0) {
                    if (Character.isLetterOrDigit(ch) || ch == '-') activeInput.insert(ch);
                } else if (branchFieldIndex == inboxPathFieldIndex()) {
                    if (Character.isLetterOrDigit(ch) || ch == '/' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                        activeInput.insert(ch);
                    }
                } else {
                    if (Character.isLetterOrDigit(ch)) activeInput.insert(ch);
                }
                return Outcome.HANDLED;
            }
        }
        return Outcome.HANDLED;
    }

    private int guiFieldIndex() {
        return branchSourceIsVm ? 4 : 1;
    }

    private int kvmFieldIndex() {
        return guiFieldIndex() + 1;
    }

    private int networkFieldIndex() {
        return guiFieldIndex() + 2;
    }

    private int inboxFieldIndex() {
        return guiFieldIndex() + 3;
    }

    private int inboxPathFieldIndex() {
        return inboxFieldIndex() + 1;
    }

    private boolean isToggleField(int fieldIndex) {
        return (fieldIndex >= guiFieldIndex() && fieldIndex <= inboxFieldIndex()) || isAccountField(fieldIndex);
    }

    /** The first account row's field index: after the inbox, and its path when that is shown. */
    private int accountFieldBase() {
        return (branchInboxCheck.isChecked() ? inboxPathFieldIndex() : inboxFieldIndex()) + 1;
    }

    private boolean isAccountField(int fieldIndex) {
        return fieldIndex >= accountFieldBase() && fieldIndex < accountFieldBase() + branchAccounts.size();
    }

    private int maxBranchField() {
        return accountFieldBase() - 1 + branchAccounts.size();
    }

    private TextInputState activeBranchInput() {
        if (branchFieldIndex == inboxPathFieldIndex() && branchInboxCheck.isChecked()) return branchInboxInput;
        return switch (branchFieldIndex) {
            case 1 -> vmCpuInput;
            case 2 -> vmMemoryInput;
            case 3 -> vmDiskInput;
            default -> branchNameInput;
        };
    }

    void render(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        var accountRows = branchAccounts.size();
        // A blank line and a heading, then a row per credential with a choice to make.
        int accountLines = accountRows == 0 ? 0 : 2 + accountRows;
        // Nine field lines, the key hints, and the two borders. It was 11, which left the hints
        // a zero-height line: they were never shown.
        int height = 12 + accountLines;
        var modalArea = ModalRenderer.centerRect(screen, accountRows == 0 ? 54 : 64, height);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(modal.styledTitle(" Branch from '" + branchSourceName + "' ", modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        modal.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var constraints = new ArrayList<Constraint>();
        constraints.add(Constraint.length(1));
        constraints.add(Constraint.length(1));
        constraints.add(Constraint.length(1));
        constraints.add(Constraint.length(1));
        constraints.add(Constraint.length(1));
        constraints.add(Constraint.length(1));
        constraints.add(Constraint.length(1));
        constraints.add(Constraint.length(1));
        constraints.add(Constraint.length(1));
        for (int i = 0; i < accountLines; i++) constraints.add(Constraint.length(1));
        constraints.add(Constraint.fill());

        var rows = Layout.vertical()
                .constraints(constraints.toArray(new Constraint[0]))
                .split(inner);

        int row = 0;
        frame.renderWidget(Paragraph.from(Line.styled(
                "Name:", Style.EMPTY.fg(modal.fg()).bg(modal.bg()))), rows.get(row++));
        if (branchFieldIndex == 0) {
            TextInput.builder()
                    .placeholder("branch-name")
                    .style(Style.EMPTY.fg(theme.focusedLabel()).bg(modal.inputBg()))
                    .build()
                    .renderWithCursor(rows.get(row++), frame.buffer(), branchNameInput, frame);
        } else {
            frame.renderWidget(Paragraph.from(Line.styled(
                    branchNameInput.text(), Style.EMPTY.fg(theme.textDim()).bg(modal.inputBg()))),
                    rows.get(row++));
        }

        row++;
        renderResourceFields(frame, rows.get(row++));

        row++;
        modal.renderToggle(frame, rows.get(row++), "GUI passthrough", branchGuiCheck, branchFieldIndex == guiFieldIndex());
        modal.renderToggle(frame, rows.get(row++), "KVM passthrough", branchKvmCheck, branchFieldIndex == kvmFieldIndex());
        modal.renderSelect(frame, rows.get(row++), "Network", branchNetworkSelect, branchFieldIndex == networkFieldIndex());
        renderInboxField(frame, rows.get(row++));

        dev.tamboui.layout.Rect openRowArea = null;
        if (accountRows > 0) {
            row++;
            frame.renderWidget(Paragraph.from(Line.styled("Credential accounts:",
                    Style.EMPTY.fg(modal.fg()).bg(modal.bg()))), rows.get(row++));
            for (int i = 0; i < accountRows; i++) {
                var area = rows.get(row++);
                var focused = branchFieldIndex == accountFieldBase() + i;
                branchAccounts.renderRow(frame, area, i, focused);
                if (focused) openRowArea = area;
            }
        }

        var hintSpans = new ArrayList<Span>();
        if (branchAccounts.isOpen()) {
            modal.addKey(hintSpans, "Enter", "Choose");
            modal.addKey(hintSpans, "Esc", "Close");
            modal.addKey(hintSpans, "↑↓", "Move");
        } else if (isAccountField(branchFieldIndex)) {
            modal.addKey(hintSpans, "Enter", "Confirm");
            modal.addKey(hintSpans, "Space", "Choose account");
            modal.addKey(hintSpans, "←→", "Cycle");
        } else {
            modal.addKey(hintSpans, "Enter", "Confirm");
            modal.addKey(hintSpans, "Esc", "Cancel");
            modal.addKey(hintSpans, "↑↓/Tab", "Navigate");
            modal.addKey(hintSpans, "Space", "Toggle");
        }
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(row));

        // Last, so the dropdown is drawn over everything below its row.
        if (openRowArea != null) branchAccounts.renderOpen(frame, screen, openRowArea);
    }

    private void renderResourceFields(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        var labelStyle = Style.EMPTY.fg(modal.fg()).bg(modal.bg());

        var spans = new ArrayList<Span>();
        spans.add(Span.styled("  ", Style.EMPTY.bg(modal.bg())));
        spans.add(Span.styled("CPU ", labelStyle));
        if (branchSourceIsVm) {
            modal.renderInlineField(spans, vmCpuInput.text(), false, branchFieldIndex == 1);
        } else {
            modal.renderInlineField(spans, "(all)", true, false);
        }
        spans.add(Span.styled("  ", Style.EMPTY.bg(modal.bg())));
        spans.add(Span.styled("RAM ", labelStyle));
        modal.renderInlineField(spans, vmMemoryInput.text(), !branchSourceIsVm, branchFieldIndex == 2);
        spans.add(Span.styled("  ", Style.EMPTY.bg(modal.bg())));
        spans.add(Span.styled("Disk ", labelStyle));
        modal.renderInlineField(spans, vmDiskInput.text(), !branchSourceIsVm, branchFieldIndex == 3);
        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }

    private void renderInboxField(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        boolean toggleFocused = branchFieldIndex == inboxFieldIndex();
        boolean pathFocused = branchFieldIndex == inboxPathFieldIndex();

        int toggleWidth = 3 + modal.checkbox().width() + 1 + 6;
        var cols = Layout.horizontal()
                .constraints(Constraint.length(toggleWidth), Constraint.fill())
                .split(area);
        modal.renderToggle(frame, cols.get(0), "Inbox", branchInboxCheck, toggleFocused);

        var pathArea = cols.get(1);
        if (pathFocused && branchInboxCheck.isChecked()) {
            TextInput.builder()
                    .placeholder("/path/to/dir")
                    .style(Style.EMPTY.fg(theme.focusedLabel()).bg(modal.inputBg()))
                    .build()
                    .renderWithCursor(pathArea, frame.buffer(), branchInboxInput, frame);
        } else {
            var display = branchInboxInput.text().isEmpty() ? "/path/to/dir" : branchInboxInput.text();
            var inputBg = branchInboxCheck.isChecked() ? modal.inputBg() : modal.inputInactiveBg();
            var fg = branchInboxInput.text().isEmpty() ? modal.placeholderFg()
                    : branchInboxCheck.isChecked() ? theme.textDim() : modal.placeholderFg();
            frame.renderWidget(Paragraph.from(Line.styled(display, Style.EMPTY.fg(fg).bg(inputBg))), pathArea);
        }
    }

    private static String blankToNull(String text) {
        var stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** The branch the dialog describes, named {@code name}; a VM's CPU limit must be a number. */
    BranchFlow.Request request(String name) {
        var source = branchSourceName;
        var inboxText = branchInboxCheck.isChecked() ? blankToNull(branchInboxInput.text()) : null;
        Integer cpu = null;
        String memory = null, disk = null;
        if (branchSourceIsVm) {
            var cpuText = blankToNull(vmCpuInput.text());
            if (cpuText != null) {
                try {
                    cpu = Integer.valueOf(cpuText);
                } catch (NumberFormatException e) {
                    throw new BranchFlow.BranchException("CPU limit '" + cpuText + "' is not a number.");
                }
            }
            memory = blankToNull(vmMemoryInput.text());
            disk = blankToNull(vmDiskInput.text());
        }
        // An untouched box is the default, so create() applies it and says why it is off, as for isx branch
        var gui = branchGuiCheck.isChecked() == branchGuiDefault ? null : branchGuiCheck.isChecked();
        var request = new BranchFlow.Request(source, name, gui,
                branchKvmCheck.isChecked(), branchNetworkMode(),
                inboxText == null ? null : java.nio.file.Path.of(inboxText),
                cpu, memory, disk, branchAccounts.overrides(), true, java.util.Map.of());
        return request;
    }
}
