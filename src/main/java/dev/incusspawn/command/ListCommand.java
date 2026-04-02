package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.tamboui.backend.jline3.JLineBackend;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.input.TextInput;
import dev.tamboui.widgets.input.TextInputState;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import jakarta.inject.Inject;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.exec.ExecTerminalProvider;
import org.jline.terminal.spi.SystemStream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Command(
        name = "list",
        description = "List all incus-spawn environments, grouped by project",
        mixinStandardHelpOptions = true
)
public class ListCommand implements Runnable {

    @Option(names = "--plain", description = "Plain text output (no TUI)")
    boolean plain;

    @Inject
    IncusClient incus;

    private enum Mode { BROWSE, CONFIRM_DELETE, CONFIRM_STOP_FOR_RENAME, CREATE_CLONE, RENAME, BRANCH }
    private Mode mode = Mode.BROWSE;
    private String pendingDeleteName;
    private String pendingDeleteType;
    private TextInputState cloneNameInput;
    private String cloneSourceName;
    private boolean cloneEnableWayland;
    private boolean cloneEnableAirgap;
    private boolean cloneSourceIsVm;
    private TextInputState vmCpuInput;
    private TextInputState vmMemoryInput;
    private TextInputState vmDiskInput;
    private int cloneFieldIndex; // 0=name, 1=cpu, 2=memory, 3=disk
    private TextInputState renameInput;
    private String renameSourceName;
    private TextInputState branchInput;
    private String branchSourceName;
    private String statusMessage;
    private String activeButton; // key label of the button currently executing (for highlight feedback)

    private enum PendingAction { NONE, SHELL, CREATE_CLONE }
    private PendingAction pendingAction = PendingAction.NONE;
    private String pendingActionTarget;

    private List<InstanceInfo> entries;
    private List<Row> tableRows;
    private List<InstanceInfo> rowToEntry; // null for group header rows

    @Override
    public void run() {
        entries = collectEntries();
        if (plain) {
            if (entries.isEmpty()) {
                System.out.println("No incus-spawn environments found.");
                System.out.println("Run 'isx build golden-base' to create your first golden image.");
            } else {
                printPlain(entries);
            }
        } else {
            runTuiLoop();
        }
    }

    // --- TUI lifecycle ---

    private void runTuiLoop() {
        while (true) {
            entries = collectEntries();
            buildRowData();
            mode = Mode.BROWSE;
            pendingAction = PendingAction.NONE;

            var tableState = new TableState();
            selectFirstDataRow(tableState);

            try {
                var terminal = createExecTerminal();
                var backend = createBackend(terminal);
                try (var runner = TuiRunner.create(TuiConfig.builder().backend(backend).build())) {
                    runner.run(
                            (event, tui) -> handleEvent(event, tui, tableState),
                            frame -> render(frame, tableState));
                }
                terminal.close();
            } catch (Exception e) {
                printPlain(entries);
                return;
            }

            switch (pendingAction) {
                case SHELL -> shellInto(pendingActionTarget);
                case CREATE_CLONE -> {
                    try {
                        createClone(cloneSourceName, pendingActionTarget, cloneEnableWayland, cloneEnableAirgap, cloneSourceIsVm);
                    } catch (Exception e) {
                        System.err.println("Clone failed: " + e.getMessage());
                        System.err.println("Press Enter to return to the list...");
                        try { System.in.read(); } catch (Exception ignored) {}
                    }
                }
                case NONE -> { return; }
            }
        }
    }

    /**
     * Creates a JLine terminal using the exec provider directly.
     * TerminalBuilder's provider discovery is broken in native image,
     * but the exec provider itself works fine when instantiated directly.
     */
    private static Terminal createExecTerminal() throws IOException {
        java.util.logging.Logger.getLogger("org.jline").setLevel(java.util.logging.Level.SEVERE);
        var provider = new ExecTerminalProvider();
        return provider.sysTerminal("isx", System.getenv("TERM"), false,
                StandardCharsets.UTF_8, false, Terminal.SignalHandler.SIG_DFL,
                false, SystemStream.Output);
    }

    /**
     * Creates a JLineBackend wired to the given terminal.
     * JLineBackend's constructor hardcodes .jansi(true) which fails in native image,
     * so we let it create a throwaway terminal, then swap in ours via reflection.
     */
    private static JLineBackend createBackend(Terminal terminal) throws Exception {
        var backend = new JLineBackend();
        var clazz = JLineBackend.class;
        // Close the constructor's terminal and replace with ours
        getField(clazz, "terminal").set(backend, terminal);
        getField(clazz, "writer").set(backend, terminal.writer());
        getField(clazz, "reader").set(backend, terminal.reader());
        return backend;
    }

    private static Field getField(Class<?> clazz, String name) throws NoSuchFieldException {
        var f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    // --- Event handling ---

    private boolean handleEvent(Event event, TuiRunner tui, TableState tableState) {
        if (!(event instanceof KeyEvent key)) return false;
        return switch (mode) {
            case BROWSE -> handleBrowseEvent(key, tui, tableState);
            case CONFIRM_DELETE -> handleConfirmDeleteEvent(key, tui, tableState);
            case CONFIRM_STOP_FOR_RENAME -> handleConfirmStopForRenameEvent(key, tui, tableState);
            case CREATE_CLONE -> handleCreateCloneEvent(key, tui, tableState);
            case RENAME -> handleRenameEvent(key, tui, tableState);
            case BRANCH -> handleBranchEvent(key, tui, tableState);
        };
    }

    private boolean handleBrowseEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        if (key.isChar('q') || key.isCtrlC()
                || key.isKey(KeyCode.ESCAPE)
                || (key.hasCtrl() && key.isCharIgnoreCase('q'))) {
            tui.quit();
            return true;
        }
        statusMessage = null;

        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) { selectNextDataRow(tableState, 1); return true; }
        if (key.isKey(KeyCode.UP) || key.isChar('k'))   { selectNextDataRow(tableState, -1); return true; }
        if (key.isKey(KeyCode.HOME))                     { selectFirstDataRow(tableState); return true; }
        if (key.isKey(KeyCode.END))                      { selectLastDataRow(tableState); return true; }

        var selected = selectedEntry(tableState);
        if (selected == null) return false;

        if (key.isChar('d') || key.isKey(KeyCode.DELETE)) {
            pendingDeleteName = selected.name;
            pendingDeleteType = selected.type;
            mode = Mode.CONFIRM_DELETE;
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            pendingAction = PendingAction.SHELL;
            pendingActionTarget = selected.name;
            tui.quit();
            return true;
        }
        if (key.isChar('c') && isCloneable(selected)) {
            cloneSourceName = selected.name;
            cloneNameInput = new TextInputState(suggestCloneName(selected.name));
            cloneEnableWayland = false;
            cloneEnableAirgap = false;
            cloneSourceIsVm = selected.runtime.toUpperCase().contains("VIRTUAL");
            vmCpuInput = new TextInputState(selected.limitsCpu.isEmpty() ? "4" : selected.limitsCpu);
            vmMemoryInput = new TextInputState(selected.limitsMemory.isEmpty() ? "6GB" : selected.limitsMemory);
            vmDiskInput = new TextInputState(selected.rootSize.isEmpty() ? "10GB" : selected.rootSize);
            cloneFieldIndex = 0;
            mode = Mode.CREATE_CLONE;
            return true;
        }
        if (key.isChar('s') && isRunning(selected)) {
            execWithFeedback(tui, tableState, "s", "Stopped", "Failed to stop",
                    selected.name, () -> incus.stop(selected.name));
            return true;
        }
        if (key.isChar('r') && isRunning(selected)) {
            execWithFeedback(tui, tableState, "r", "Restarted", "Failed to restart",
                    selected.name, () -> incus.restart(selected.name));
            return true;
        }
        if (key.isChar('n')) {
            renameSourceName = selected.name;
            if (isRunning(selected)) {
                mode = Mode.CONFIRM_STOP_FOR_RENAME;
            } else {
                renameInput = new TextInputState(selected.name);
                mode = Mode.RENAME;
            }
            return true;
        }
        if (key.isChar('b') && isCloneable(selected)) {
            branchSourceName = selected.name;
            branchInput = new TextInputState(selected.name + "-branch");
            mode = Mode.BRANCH;
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('l')) {
            refreshData(tableState);
            return true;
        }
        return false;
    }

    private boolean handleRenameEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) {
            mode = Mode.BROWSE;
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            var newName = renameInput.text().strip();
            if (newName.isEmpty() || newName.equals(renameSourceName)) {
                mode = Mode.BROWSE;
                return true;
            }
            var validation = validateInstanceName(newName);
            if (validation != null) {
                statusMessage = validation;
                mode = Mode.BROWSE;
                return true;
            }
            try {
                incus.rename(renameSourceName, newName);
                statusMessage = "Renamed " + renameSourceName + " to " + newName;
            } catch (Exception e) {
                statusMessage = "Failed to rename: " + e.getMessage();
            }
            refreshData(tableState);
            mode = Mode.BROWSE;
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE)) { renameInput.deleteBackward(); return true; }
        if (key.isKey(KeyCode.DELETE))    { renameInput.deleteForward(); return true; }
        if (key.isKey(KeyCode.LEFT))      { renameInput.moveCursorLeft(); return true; }
        if (key.isKey(KeyCode.RIGHT))     { renameInput.moveCursorRight(); return true; }
        if (key.isKey(KeyCode.HOME))      { renameInput.moveCursorToStart(); return true; }
        if (key.isKey(KeyCode.END))       { renameInput.moveCursorToEnd(); return true; }
        if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
            char ch = key.character();
            if (Character.isLetterOrDigit(ch) || ch == '-') {
                renameInput.insert(ch);
            }
            return true;
        }
        return true;
    }

    private boolean handleBranchEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) {
            mode = Mode.BROWSE;
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            var newName = branchInput.text().strip();
            if (newName.isEmpty() || newName.equals(branchSourceName)) {
                mode = Mode.BROWSE;
                return true;
            }
            var validation = validateInstanceName(newName);
            if (validation != null) {
                statusMessage = validation;
                mode = Mode.BROWSE;
                return true;
            }
            try {
                incus.copy(branchSourceName, newName);
                var profile = getConfigOrDefault(branchSourceName, Metadata.PROFILE, "minimal");
                incus.configSet(newName, Metadata.TYPE, Metadata.TYPE_BASE);
                incus.configSet(newName, Metadata.PROFILE, profile);
                incus.configSet(newName, Metadata.PARENT, branchSourceName);
                incus.configSet(newName, Metadata.CREATED, Metadata.today());
                statusMessage = "Branched '" + newName + "' from '" + branchSourceName + "'";
            } catch (Exception e) {
                statusMessage = "Failed to branch: " + e.getMessage();
            }
            refreshData(tableState);
            mode = Mode.BROWSE;
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE)) { branchInput.deleteBackward(); return true; }
        if (key.isKey(KeyCode.DELETE))    { branchInput.deleteForward(); return true; }
        if (key.isKey(KeyCode.LEFT))      { branchInput.moveCursorLeft(); return true; }
        if (key.isKey(KeyCode.RIGHT))     { branchInput.moveCursorRight(); return true; }
        if (key.isKey(KeyCode.HOME))      { branchInput.moveCursorToStart(); return true; }
        if (key.isKey(KeyCode.END))       { branchInput.moveCursorToEnd(); return true; }
        if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
            char ch = key.character();
            if (Character.isLetterOrDigit(ch) || ch == '-') {
                branchInput.insert(ch);
            }
            return true;
        }
        return true;
    }

    private boolean handleConfirmDeleteEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        if (key.isChar('y') || key.isChar('Y')) {
            try {
                incus.delete(pendingDeleteName, true);
                statusMessage = "Destroyed " + pendingDeleteName;
            } catch (Exception e) {
                statusMessage = "Failed to destroy " + pendingDeleteName + ": " + e.getMessage();
            }
            entries = collectEntries();
            buildRowData();
            selectFirstDataRow(tableState);
        }
        mode = Mode.BROWSE;
        return true;
    }

    private boolean handleConfirmStopForRenameEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        if (key.isChar('y') || key.isChar('Y')) {
            activeButton = "n";
            tui.draw(frame -> render(frame, tableState));
            try {
                incus.stop(renameSourceName);
                activeButton = null;
                refreshData(tableState);
                renameInput = new TextInputState(renameSourceName);
                mode = Mode.RENAME;
            } catch (Exception e) {
                activeButton = null;
                statusMessage = "Failed to stop " + renameSourceName + ": " + e.getMessage();
                mode = Mode.BROWSE;
            }
        } else {
            mode = Mode.BROWSE;
        }
        return true;
    }

    private boolean handleCreateCloneEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) {
            mode = Mode.BROWSE;
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            var name = cloneNameInput.text().strip();
            if (name.isEmpty()) return false;
            var validation = validateInstanceName(name);
            if (validation != null) {
                statusMessage = validation;
                mode = Mode.BROWSE;
                return true;
            }
            pendingAction = PendingAction.CREATE_CLONE;
            pendingActionTarget = name;
            tui.quit();
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('w')) {
            cloneEnableWayland = !cloneEnableWayland;
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('a')) {
            cloneEnableAirgap = !cloneEnableAirgap;
            return true;
        }
        if (key.isKey(KeyCode.TAB)) {
            if (cloneSourceIsVm) {
                cloneFieldIndex = (cloneFieldIndex + 1) % 4;
            }
            return true;
        }

        // Route editing keys to the active field
        var activeInput = activeCloneInput();
        if (key.isKey(KeyCode.BACKSPACE)) { activeInput.deleteBackward(); return true; }
        if (key.isKey(KeyCode.DELETE))    { activeInput.deleteForward(); return true; }
        if (key.isKey(KeyCode.LEFT))      { activeInput.moveCursorLeft(); return true; }
        if (key.isKey(KeyCode.RIGHT))     { activeInput.moveCursorRight(); return true; }
        if (key.isKey(KeyCode.HOME))      { activeInput.moveCursorToStart(); return true; }
        if (key.isKey(KeyCode.END))       { activeInput.moveCursorToEnd(); return true; }
        if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
            char ch = key.character();
            if (cloneFieldIndex == 0) {
                if (Character.isLetterOrDigit(ch) || ch == '-') activeInput.insert(ch);
            } else {
                if (Character.isLetterOrDigit(ch)) activeInput.insert(ch);
            }
            return true;
        }
        return true;
    }

    private TextInputState activeCloneInput() {
        return switch (cloneFieldIndex) {
            case 1 -> vmCpuInput;
            case 2 -> vmMemoryInput;
            case 3 -> vmDiskInput;
            default -> cloneNameInput;
        };
    }

    // --- Rendering ---

    private void render(dev.tamboui.terminal.Frame frame, TableState tableState) {
        var area = frame.area();
        boolean hasStatus = statusMessage != null;
        int footerHeight = hasStatus ? 2 : 1;
        var chunks = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(footerHeight))
                .split(area);

        renderTable(frame, chunks.get(0), tableState);
        renderToolbar(frame, chunks.get(1), tableState, hasStatus);

        // Render centered modal overlay for dialog modes
        if (mode != Mode.BROWSE) {
            renderModal(frame, area, tableState);
        }
    }

    private void renderTable(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area, TableState tableState) {
        if (entries.isEmpty()) {
            var block = Block.builder()
                    .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                    .title(" incus-spawn environments ")
                    .borderStyle(Style.EMPTY.fg(Color.CYAN)).build();
            frame.renderWidget(block, area);
            var inner = block.inner(area);
            if (inner.height() > 1) {
                var hint = Layout.vertical()
                        .constraints(Constraint.length(inner.height() / 2), Constraint.length(1))
                        .split(inner);
                frame.renderWidget(Paragraph.from(
                        Line.styled("  No environments found. Run: isx build golden-base",
                                Style.EMPTY.fg(Color.GRAY))), hint.get(1));
            }
            return;
        }
        var table = Table.builder()
                .header(Row.from("NAME", "STATUS", "TYPE", "DERIVED FROM", "RUNTIME", "AGE")
                        .style(Style.EMPTY.bold().fg(Color.CYAN)))
                .rows(tableRows)
                .widths(Constraint.fill(), Constraint.length(12),
                        Constraint.length(12), Constraint.length(20),
                        Constraint.length(12), Constraint.length(10))
                .highlightStyle(Style.EMPTY.bg(Color.DARK_GRAY).fg(Color.WHITE))
                .highlightSymbol("\u25b8 ")
                .block(Block.builder()
                        .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                        .title(" incus-spawn environments ")
                        .borderStyle(Style.EMPTY.fg(Color.CYAN)).build())
                .build();
        frame.renderStatefulWidget(table, area, tableState);
    }

    private void renderToolbar(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                                TableState tableState, boolean hasStatus) {
        fillBackground(frame, area, BAR_BG);

        var selected = selectedEntry(tableState);
        boolean hasSelection = selected != null;
        boolean cloneable = hasSelection && isCloneable(selected);
        boolean running = hasSelection && isRunning(selected);

        var helpSpans = new ArrayList<Span>();
        addKey(helpSpans, "q", "Quit", false);
        addKey(helpSpans, "Enter", "Shell", !hasSelection);
        addKey(helpSpans, "d", "Destroy", !hasSelection);
        addKey(helpSpans, "n", "Rename", !hasSelection);
        addKey(helpSpans, "c", "Clone", !cloneable);
        addKey(helpSpans, "b", "Branch", !cloneable);
        addKey(helpSpans, "s", "Stop", !running);
        addKey(helpSpans, "r", "Restart", !running);

        if (hasStatus) {
            var rows = splitVertical(area, 1, 1);
            var isError = statusMessage.startsWith("Failed") || statusMessage.startsWith("Invalid");
            var msgFg = isError ? Color.LIGHT_RED : Color.rgb(0, 60, 60);
            frame.renderWidget(
                    Paragraph.from(Line.styled(" " + statusMessage,
                            Style.EMPTY.bold().fg(msgFg).bg(BAR_BG))), rows.get(0));
            frame.renderWidget(Paragraph.from(Line.from(helpSpans)), rows.get(1));
        } else {
            frame.renderWidget(Paragraph.from(Line.from(helpSpans)), area);
        }
    }

    // --- Modal dialogs (centered overlay) ---

    private static final Color MODAL_BG = Color.rgb(30, 30, 46);
    private static final Color MODAL_FG = Color.rgb(205, 214, 244);
    private static final Color MODAL_BORDER = Color.CYAN;
    private static final Color MODAL_ACCENT = Color.LIGHT_CYAN;
    private static final Color MODAL_WARN = Color.LIGHT_RED;

    private void renderModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen,
                              TableState tableState) {
        switch (mode) {
            case CONFIRM_DELETE -> {
                boolean isGolden = Metadata.TYPE_BASE.equals(pendingDeleteType)
                        || Metadata.TYPE_PROJECT.equals(pendingDeleteType);
                var message = isGolden
                        ? "Existing clones are not affected."
                        : "This action cannot be undone.";
                var modalArea = centerRect(screen, 50, 7);
                var block = Block.builder()
                        .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                        .title(" Destroy '" + pendingDeleteName + "' ")
                        .borderStyle(Style.EMPTY.fg(MODAL_WARN))
                        .style(Style.EMPTY.bg(MODAL_BG))
                        .build();
                frame.renderWidget(block, modalArea);
                var inner = block.inner(modalArea);
                var rows = Layout.vertical()
                        .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1), Constraint.fill())
                        .split(inner);
                frame.renderWidget(Paragraph.from(Line.styled(
                        message, Style.EMPTY.fg(MODAL_WARN).bg(MODAL_BG))), rows.get(1));
                var btnSpans = new ArrayList<Span>();
                addModalKey(btnSpans, "y", "Confirm");
                addModalKey(btnSpans, "any key", "Cancel");
                frame.renderWidget(Paragraph.from(Line.from(btnSpans)), rows.get(3));
            }
            case CONFIRM_STOP_FOR_RENAME -> {
                var modalArea = centerRect(screen, 52, 7);
                var block = Block.builder()
                        .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                        .title(" Rename '" + renameSourceName + "' ")
                        .borderStyle(Style.EMPTY.fg(MODAL_BORDER))
                        .style(Style.EMPTY.bg(MODAL_BG))
                        .build();
                frame.renderWidget(block, modalArea);
                var inner = block.inner(modalArea);
                var rows = Layout.vertical()
                        .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1), Constraint.fill())
                        .split(inner);
                frame.renderWidget(Paragraph.from(Line.styled(
                        "Instance is running. Stop it first?",
                        Style.EMPTY.fg(MODAL_FG).bg(MODAL_BG))), rows.get(1));
                var btnSpans = new ArrayList<Span>();
                addModalKey(btnSpans, "y", "Stop & rename");
                addModalKey(btnSpans, "any key", "Cancel");
                frame.renderWidget(Paragraph.from(Line.from(btnSpans)), rows.get(3));
            }
            case CREATE_CLONE -> renderCloneModal(frame, screen);
            case RENAME -> renderInputModal(frame, screen,
                    "Rename '" + renameSourceName + "'", "New name:", renameSourceName, renameInput);
            case BRANCH -> renderInputModal(frame, screen,
                    "Branch '" + branchSourceName + "'", "Name:", branchSourceName + "-branch", branchInput);
            default -> {}
        }
    }

    private void renderInputModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen,
                                   String title, String label, String placeholder,
                                   TextInputState inputState) {
        var modalArea = centerRect(screen, 54, 7);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(" " + title + " ")
                .borderStyle(Style.EMPTY.fg(MODAL_BORDER))
                .style(Style.EMPTY.bg(MODAL_BG))
                .build();
        frame.renderWidget(block, modalArea);
        var inner = block.inner(modalArea);
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1), Constraint.fill())
                .split(inner);
        frame.renderWidget(Paragraph.from(Line.styled(
                label, Style.EMPTY.fg(MODAL_FG).bg(MODAL_BG))), rows.get(0));
        TextInput.builder()
                .placeholder(placeholder)
                .style(Style.EMPTY.fg(Color.WHITE).bg(Color.rgb(49, 50, 68)))
                .build()
                .renderWithCursor(rows.get(1), frame.buffer(), inputState, frame);
        var hintSpans = new ArrayList<Span>();
        addModalKey(hintSpans, "Enter", "Confirm");
        addModalKey(hintSpans, "Esc", "Cancel");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(3));
    }

    private void renderCloneModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        int height = cloneSourceIsVm ? 12 : 10;
        var modalArea = centerRect(screen, 54, height);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(" Clone " + (cloneSourceIsVm ? "VM" : "container") + " '" + cloneSourceName + "' ")
                .borderStyle(Style.EMPTY.fg(MODAL_BORDER))
                .style(Style.EMPTY.bg(MODAL_BG))
                .build();
        frame.renderWidget(block, modalArea);
        var inner = block.inner(modalArea);

        if (cloneSourceIsVm) {
            var rows = Layout.vertical()
                    .constraints(
                            Constraint.length(1), // 0: name label
                            Constraint.length(1), // 1: name input
                            Constraint.length(1), // 2: spacer
                            Constraint.length(1), // 3: vm resources
                            Constraint.length(1), // 4: spacer
                            Constraint.length(1), // 5: wayland toggle
                            Constraint.length(1), // 6: airgap toggle
                            Constraint.length(1), // 7: spacer
                            Constraint.fill())     // 8: hints
                    .split(inner);

            renderCloneNameField(frame, rows.get(0), rows.get(1));
            renderVmResourceFields(frame, rows.get(3));
            renderToggle(frame, rows.get(5), "Alt-w", "Wayland passthrough", cloneEnableWayland);
            renderToggle(frame, rows.get(6), "Alt-a", "Network airgap", cloneEnableAirgap);

            var hintSpans = new ArrayList<Span>();
            addModalKey(hintSpans, "Enter", "Confirm");
            addModalKey(hintSpans, "Esc", "Cancel");
            addModalKey(hintSpans, "Tab", "Next field");
            frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(8));
        } else {
            var rows = Layout.vertical()
                    .constraints(
                            Constraint.length(1), // 0: name label
                            Constraint.length(1), // 1: name input
                            Constraint.length(1), // 2: spacer
                            Constraint.length(1), // 3: wayland toggle
                            Constraint.length(1), // 4: airgap toggle
                            Constraint.length(1), // 5: spacer
                            Constraint.fill())     // 6: hints
                    .split(inner);

            renderCloneNameField(frame, rows.get(0), rows.get(1));
            renderToggle(frame, rows.get(3), "Alt-w", "Wayland passthrough", cloneEnableWayland);
            renderToggle(frame, rows.get(4), "Alt-a", "Network airgap", cloneEnableAirgap);

            var hintSpans = new ArrayList<Span>();
            addModalKey(hintSpans, "Enter", "Confirm");
            addModalKey(hintSpans, "Esc", "Cancel");
            frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(6));
        }
    }

    private void renderCloneNameField(dev.tamboui.terminal.Frame frame,
                                       dev.tamboui.layout.Rect labelArea, dev.tamboui.layout.Rect inputArea) {
        frame.renderWidget(Paragraph.from(Line.styled(
                "Name:", Style.EMPTY.fg(MODAL_FG).bg(MODAL_BG))), labelArea);
        if (cloneFieldIndex == 0) {
            TextInput.builder()
                    .placeholder("clone-name")
                    .style(Style.EMPTY.fg(Color.WHITE).bg(Color.rgb(49, 50, 68)))
                    .build()
                    .renderWithCursor(inputArea, frame.buffer(), cloneNameInput, frame);
        } else {
            frame.renderWidget(Paragraph.from(Line.styled(
                    cloneNameInput.text(), Style.EMPTY.fg(Color.GRAY).bg(Color.rgb(49, 50, 68)))),
                    inputArea);
        }
    }

    private void renderVmResourceFields(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        var disabled = false; // always enabled when shown (only rendered for VM sources)
        var disabledFg = Color.rgb(80, 80, 100);
        var labelStyle = Style.EMPTY.fg(disabled ? disabledFg : MODAL_FG).bg(MODAL_BG);
        var inputBg = Color.rgb(49, 50, 68);
        var inactiveBg = Color.rgb(40, 40, 55);

        var spans = new ArrayList<Span>();
        spans.add(Span.styled("  ", Style.EMPTY.bg(MODAL_BG)));

        // CPU field
        spans.add(Span.styled("CPU ", labelStyle));
        renderInlineField(spans, vmCpuInput.text(), disabled, cloneFieldIndex == 1, inputBg, inactiveBg, disabledFg);
        spans.add(Span.styled("  ", Style.EMPTY.bg(MODAL_BG)));

        // Memory field
        spans.add(Span.styled("RAM ", labelStyle));
        renderInlineField(spans, vmMemoryInput.text(), disabled, cloneFieldIndex == 2, inputBg, inactiveBg, disabledFg);
        spans.add(Span.styled("  ", Style.EMPTY.bg(MODAL_BG)));

        // Disk field
        spans.add(Span.styled("Disk ", labelStyle));
        renderInlineField(spans, vmDiskInput.text(), disabled, cloneFieldIndex == 3, inputBg, inactiveBg, disabledFg);

        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }

    private static void renderInlineField(List<Span> spans, String value, boolean disabled,
                                           boolean active, Color inputBg, Color inactiveBg, Color disabledFg) {
        // Pad value to minimum width for visual consistency
        var display = String.format("%-6s", value);
        if (disabled) {
            spans.add(Span.styled(display, Style.EMPTY.fg(disabledFg).bg(inactiveBg)));
        } else if (active) {
            spans.add(Span.styled(display, Style.EMPTY.bold().fg(Color.WHITE).bg(inputBg)));
        } else {
            spans.add(Span.styled(display, Style.EMPTY.fg(Color.GRAY).bg(inactiveBg)));
        }
    }

    private void renderToggle(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                               String shortcut, String label, boolean enabled) {
        var check = enabled ? "\u25c9" : "\u25cb";  // ◉ / ○
        var checkColor = enabled ? Color.GREEN : Color.GRAY;
        frame.renderWidget(Paragraph.from(Line.from(List.of(
                Span.styled(" " + shortcut + " ", Style.EMPTY.fg(MODAL_ACCENT).bg(MODAL_BG)),
                Span.styled(check + " ", Style.EMPTY.fg(checkColor).bg(MODAL_BG)),
                Span.styled(label, Style.EMPTY.fg(MODAL_FG).bg(MODAL_BG))))), area);
    }

    private static dev.tamboui.layout.Rect centerRect(dev.tamboui.layout.Rect screen, int width, int height) {
        int w = Math.min(width, screen.width());
        int h = Math.min(height, screen.height());
        int x = screen.x() + (screen.width() - w) / 2;
        int y = screen.y() + (screen.height() - h) / 2;
        return new dev.tamboui.layout.Rect(x, y, w, h);
    }

    private void addModalKey(List<Span> spans, String key, String label) {
        spans.add(Span.styled(" " + key, Style.EMPTY.bold().fg(MODAL_ACCENT).bg(MODAL_BG)));
        spans.add(Span.styled(" " + label + "  ", Style.EMPTY.fg(MODAL_FG).bg(MODAL_BG)));
    }

    private String suggestCloneName(String sourceName) {
        // Strip "golden-" prefix for a cleaner base name
        var base = sourceName.startsWith("golden-") ? sourceName.substring(7) : sourceName;
        // Collect existing instance names
        var existingNames = entries.stream().map(e -> e.name).collect(java.util.stream.Collectors.toSet());
        // Find next available number
        for (int i = 1; ; i++) {
            var candidate = base + "-" + i;
            if (!existingNames.contains(candidate)) return candidate;
        }
    }

    // Midnight Commander-inspired toolbar palette
    private static final Color BAR_BG = Color.CYAN;
    private static final Color BAR_KEY_FG = Color.WHITE;
    private static final Color BAR_LABEL_FG = Color.BLACK;
    private static final Color BAR_DISABLED_FG = Color.rgb(0, 100, 110);
    private static final Color BAR_ACTIVE_BG = Color.WHITE;

    private void addKey(List<Span> spans, String key, String label, boolean disabled) {
        if (key.equals(activeButton)) {
            spans.add(Span.styled(" " + key, Style.EMPTY.bold().fg(BAR_LABEL_FG).bg(BAR_ACTIVE_BG)));
            spans.add(Span.styled(label + " ", Style.EMPTY.fg(BAR_LABEL_FG).bg(BAR_ACTIVE_BG)));
        } else if (disabled) {
            spans.add(Span.styled(" " + key, Style.EMPTY.fg(BAR_DISABLED_FG).bg(BAR_BG)));
            spans.add(Span.styled(label + " ", Style.EMPTY.fg(BAR_DISABLED_FG).bg(BAR_BG)));
        } else {
            spans.add(Span.styled(" " + key, Style.EMPTY.bold().fg(BAR_KEY_FG).bg(BAR_BG)));
            spans.add(Span.styled(label + " ", Style.EMPTY.fg(BAR_LABEL_FG).bg(BAR_BG)));
        }
    }

    private static void fillBackground(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area, Color bg) {
        frame.buffer().setStyle(area, Style.EMPTY.bg(bg));
    }

    private static List<dev.tamboui.layout.Rect> splitVertical(dev.tamboui.layout.Rect area, int... heights) {
        var constraints = new Constraint[heights.length];
        for (int i = 0; i < heights.length; i++) constraints[i] = Constraint.length(heights[i]);
        return Layout.vertical().constraints(constraints).split(area);
    }

    // --- Helpers ---

    private static boolean isCloneable(InstanceInfo entry) {
        return entry.type.equals(Metadata.TYPE_BASE) || entry.type.equals(Metadata.TYPE_PROJECT);
    }

    private static boolean isRunning(InstanceInfo entry) {
        return "RUNNING".equalsIgnoreCase(entry.status);
    }

    private void execWithFeedback(TuiRunner tui, TableState tableState, String buttonKey,
                                    String doneVerb, String failVerb, String name, Runnable action) {
        activeButton = buttonKey;
        tui.draw(frame -> render(frame, tableState));
        try {
            action.run();
            statusMessage = doneVerb + " " + name;
        } catch (Exception e) {
            statusMessage = failVerb + " " + name;
        }
        activeButton = null;
        refreshData(tableState);
    }

    private static String validateInstanceName(String name) {
        if (name.length() > 63) return "Name too long (max 63 characters)";
        if (!name.matches("[a-zA-Z][a-zA-Z0-9-]*"))
            return "Invalid name: must start with a letter, only alphanumeric and hyphens allowed";
        return null;
    }

    // --- Navigation ---

    private void selectNextDataRow(TableState state, int direction) {
        var current = state.selected();
        if (current == null) { selectFirstDataRow(state); return; }
        int i = current + direction;
        while (i >= 0 && i < rowToEntry.size()) {
            if (rowToEntry.get(i) != null) { state.select(i); return; }
            i += direction;
        }
    }

    private void selectFirstDataRow(TableState state) {
        for (int i = 0; i < rowToEntry.size(); i++)
            if (rowToEntry.get(i) != null) { state.select(i); return; }
    }

    private void selectLastDataRow(TableState state) {
        for (int i = rowToEntry.size() - 1; i >= 0; i--)
            if (rowToEntry.get(i) != null) { state.select(i); return; }
    }

    private InstanceInfo selectedEntry(TableState state) {
        var idx = state.selected();
        if (idx == null || idx < 0 || idx >= rowToEntry.size()) return null;
        return rowToEntry.get(idx);
    }

    private void refreshData(TableState tableState) {
        var selected = selectedEntry(tableState);
        var selectedName = selected != null ? selected.name : null;
        entries = collectEntries();
        buildRowData();
        boolean reselected = false;
        if (selectedName != null) {
            for (int i = 0; i < rowToEntry.size(); i++) {
                if (rowToEntry.get(i) != null && rowToEntry.get(i).name.equals(selectedName)) {
                    tableState.select(i);
                    reselected = true;
                    break;
                }
            }
        }
        if (!reselected) selectFirstDataRow(tableState);
    }

    // --- Data ---

    private void buildRowData() {
        var grouped = groupEntries(entries);
        tableRows = new ArrayList<>();
        rowToEntry = new ArrayList<>();

        for (var group : grouped.entrySet()) {
            tableRows.add(Row.from(group.getKey(), "", "", "", "", "")
                    .style(Style.EMPTY.bold().fg(Color.YELLOW)));
            rowToEntry.add(null);

            for (var entry : group.getValue()) {
                var age = entry.created.isEmpty() ? "-" : Metadata.ageDescription(entry.created);
                var derivedFrom = derivedFrom(entry);
                var statusStyle = switch (entry.status.toUpperCase()) {
                    case "RUNNING" -> Style.EMPTY.fg(Color.GREEN);
                    case "STOPPED" -> Style.EMPTY.fg(Color.GRAY);
                    default -> Style.EMPTY;
                };
                tableRows.add(Row.from("  " + entry.name, entry.status, entry.type,
                        derivedFrom, entry.runtime, age).style(statusStyle));
                rowToEntry.add(entry);
            }
        }
    }

    private static String derivedFrom(InstanceInfo entry) {
        if (!entry.parent.isEmpty()) return entry.parent;
        if (entry.type.equals(Metadata.TYPE_CLONE) && !"-".equals(entry.project)) return entry.project;
        return "-";
    }

    private void createClone(String source, String name, boolean wayland, boolean airgap, boolean vm) {
        if (incus.exists(name)) {
            System.err.println("Error: an instance named '" + name + "' already exists.");
            return;
        }

        System.out.println("Creating " + (vm ? "VM" : "container") + " clone '" + name + "' from '" + source + "'...");
        incus.copy(source, name);

        String cpuStr, memory, disk;
        if (vm) {
            cpuStr = vmCpuInput.text().strip();
            memory = vmMemoryInput.text().strip();
            disk = vmDiskInput.text().strip();
        } else {
            cpuStr = String.valueOf(dev.incusspawn.incus.ResourceLimits.adaptiveCpuLimit());
            memory = dev.incusspawn.incus.ResourceLimits.adaptiveMemoryLimit();
            disk = dev.incusspawn.incus.ResourceLimits.defaultDiskLimit();
        }
        System.out.println("Applying resource limits: " + cpuStr + " CPUs, " + memory + " memory, " + disk + " disk");
        incus.configSet(name, "limits.cpu", cpuStr);
        incus.configSet(name, "limits.memory", memory);
        incus.exec("config", "device", "set", name, "root", "size=" + disk);

        if (airgap) {
            configureAirgap(name);
        }

        incus.start(name);
        waitForReady(name);

        if (wayland) {
            configureWayland(name);
        }

        var config = dev.incusspawn.config.SpawnConfig.load();
        if (config.getClaude().isUseVertex()) {
            var gcloudDir = java.nio.file.Path.of(System.getProperty("user.home"), ".config", "gcloud");
            if (java.nio.file.Files.isDirectory(gcloudDir)) {
                System.out.println("Mounting gcloud credentials (read-only) for Vertex AI auth...");
                incus.deviceAdd(name, "gcloud", "disk",
                        "source=" + gcloudDir.toAbsolutePath(),
                        "path=/home/agentuser/.config/gcloud",
                        "readonly=true");
            }
        }

        // Fix home dir ownership after all device mounts (gcloud, wayland, etc. may create dirs as root)
        incus.shellExec(name, "chown", "-R", String.valueOf(getUid()) + ":" + String.valueOf(getUid()), "/home/agentuser");

        incus.configSet(name, Metadata.TYPE, Metadata.TYPE_CLONE);
        incus.configSet(name, Metadata.PROJECT, source);
        incus.configSet(name, Metadata.PARENT, source);
        incus.configSet(name, Metadata.CREATED, Metadata.today());

        System.out.println("Clone '" + name + "' is ready.");
        System.out.println("Connecting to " + name + "...\n");
        incus.interactiveShell(name, "agentuser");
        System.out.println();
    }

    private void configureWayland(String name) {
        var xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        var waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        if (xdgRuntimeDir == null || waylandDisplay == null) {
            System.err.println("Warning: WAYLAND_DISPLAY or XDG_RUNTIME_DIR not set, skipping Wayland passthrough.");
            return;
        }
        var hostSocket = xdgRuntimeDir + "/" + waylandDisplay;
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(hostSocket))) {
            System.err.println("Warning: Wayland socket not found at " + hostSocket + ", skipping.");
            return;
        }

        System.out.println("Enabling Wayland passthrough...");
        var uid = String.valueOf(getUid());

        // GPU device for hardware-accelerated rendering
        incus.deviceAdd(name, "gpu", "gpu");

        // Bind-mount the host's XDG runtime dir into the container.
        // This exposes the Wayland socket (and PipeWire/PulseAudio) directly.
        incus.deviceAdd(name, "xdg-runtime", "disk",
                "source=" + xdgRuntimeDir,
                "path=/run/user/" + uid);

        // Write env vars to /etc/profile.d/ so they survive 'su -' login shells
        incus.shellExec(name, "sh", "-c",
                "cat > /etc/profile.d/wayland.sh << 'ENVEOF'\n" +
                "export WAYLAND_DISPLAY=" + waylandDisplay + "\n" +
                "export XDG_RUNTIME_DIR=/run/user/" + uid + "\n" +
                "export GDK_BACKEND=wayland\n" +
                "export QT_QPA_PLATFORM=wayland\n" +
                "export SDL_VIDEODRIVER=wayland\n" +
                "export MOZ_ENABLE_WAYLAND=1\n" +
                "export ELECTRON_OZONE_PLATFORM_HINT=wayland\n" +
                "ENVEOF\n" +
                "chmod 644 /etc/profile.d/wayland.sh");
    }

    private void configureAirgap(String name) {
        System.out.println("Enabling network airgap...");
        // Detach the instance from the network bridge
        var result = incus.exec("network", "detach", "incusbr0", name);
        if (!result.success()) {
            // Fallback: try removing the eth0 device directly
            incus.exec("config", "device", "override", name, "eth0");
            incus.exec("config", "device", "remove", name, "eth0");
        }
    }

    private static int getUid() {
        try {
            var pb = new ProcessBuilder("id", "-u");
            var p = pb.start();
            var output = new String(p.getInputStream().readAllBytes()).strip();
            p.waitFor();
            return Integer.parseInt(output);
        } catch (Exception e) {
            return 1000;
        }
    }

    private void shellInto(String name) {
        var info = incus.exec("list", name, "--format=csv", "--columns=s");
        if (info.success() && info.stdout().strip().equalsIgnoreCase("STOPPED")) {
            System.out.println("Starting " + name + "...");
            incus.start(name);
            waitForReady(name);
        }
        System.out.println("Connecting to " + name + "...\n");
        incus.interactiveShell(name, "agentuser");
        System.out.println();
    }

    private void waitForReady(String name) {
        for (int i = 0; i < 30; i++) {
            if (incus.shellExec(name, "true").success()) break;
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private List<InstanceInfo> collectEntries() {
        try {
            var jsonStr = incus.listJson();
            var nodes = JSON.readTree(jsonStr);
            var entryList = new ArrayList<InstanceInfo>();
            for (var node : nodes) {
                var config = node.path("config");
                var type = configVal(config, Metadata.TYPE, "");
                if (type.isEmpty()) continue;

                var expandedDevices = node.path("expanded_devices");
                var rootSize = expandedDevices.path("root").path("size").asText("");

                entryList.add(new InstanceInfo(
                        node.path("name").asText(),
                        node.path("status").asText(),
                        type,
                        configVal(config, Metadata.PROJECT, "-"),
                        configVal(config, Metadata.PROFILE, "-"),
                        configVal(config, Metadata.CREATED, ""),
                        node.path("type").asText(),
                        configVal(config, Metadata.PARENT, ""),
                        configVal(config, "limits.cpu", ""),
                        configVal(config, "limits.memory", ""),
                        rootSize));
            }
            return entryList;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String configVal(JsonNode config, String key, String defaultValue) {
        var val = config.path(key).asText("");
        return val.isEmpty() ? defaultValue : val;
    }

    private Map<String, List<InstanceInfo>> groupEntries(List<InstanceInfo> items) {
        Map<String, List<InstanceInfo>> grouped = new LinkedHashMap<>();
        for (var entry : items)
            if (entry.type.equals(Metadata.TYPE_BASE))
                grouped.computeIfAbsent("Base Images", k -> new ArrayList<>()).add(entry);
        for (var entry : items)
            if (entry.type.equals(Metadata.TYPE_PROJECT))
                grouped.computeIfAbsent("Project: " + entry.name, k -> new ArrayList<>()).add(entry);
        for (var entry : items)
            if (entry.type.equals(Metadata.TYPE_CLONE))
                grouped.computeIfAbsent("Project: " + entry.project, k -> new ArrayList<>()).add(entry);
        return grouped;
    }

    private void printPlain(List<InstanceInfo> items) {
        var grouped = groupEntries(items);
        var nameWidth = Math.max(20, items.stream().mapToInt(e -> e.name.length()).max().orElse(20));
        var fmt = "  %-" + nameWidth + "s  %-10s  %-10s  %-20s  %-10s  %s%n";

        for (var group : grouped.entrySet()) {
            System.out.println("\n" + group.getKey());
            System.out.printf(fmt, "NAME", "STATUS", "TYPE", "DERIVED FROM", "RUNTIME", "AGE");
            System.out.printf(fmt, "-".repeat(nameWidth), "----------", "----------",
                    "--------------------", "----------", "---");
            for (var entry : group.getValue()) {
                var age = entry.created.isEmpty() ? "-" : Metadata.ageDescription(entry.created);
                System.out.printf(fmt, entry.name, entry.status, entry.type,
                        derivedFrom(entry), entry.runtime, age);
            }
        }
        System.out.println();
    }

    private String getConfigOrDefault(String name, String key, String defaultValue) {
        try {
            var value = incus.configGet(name, key);
            return value.isEmpty() ? defaultValue : value;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private record InstanceInfo(String name, String status, String type,
                                String project, String profile, String created,
                                String runtime, String parent,
                                String limitsCpu, String limitsMemory, String rootSize) {}
}
