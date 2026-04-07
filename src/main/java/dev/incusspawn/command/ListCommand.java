package dev.incusspawn.command;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.ResourceLimits;
import dev.incusspawn.proxy.MitmProxy;
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
import java.util.List;

@Command(
        name = "list",
        description = "List all incus-spawn environments",
        mixinStandardHelpOptions = true
)
public class ListCommand implements Runnable {

    @Option(names = "--plain", description = "Plain text output (no TUI)")
    boolean plain;

    @Inject
    IncusClient incus;

    @Inject
    picocli.CommandLine.IFactory factory;

    private enum Mode { BROWSE, CONFIRM_DELETE, CONFIRM_STOP_FOR_RENAME, BRANCH, RENAME, BUILD_IMAGE }
    private Mode mode = Mode.BROWSE;
    private String pendingDeleteName;
    // Branch modal state (unified: replaces old clone + branch)
    private String branchSourceName;
    private TextInputState branchNameInput;
    private boolean branchEnableGui;
    private NetworkMode branchNetworkMode;
    private boolean branchEnableInbox;
    private TextInputState branchInboxInput;
    private boolean branchSourceIsVm;
    private TextInputState vmCpuInput;
    private TextInputState vmMemoryInput;
    private TextInputState vmDiskInput;
    private int branchFieldIndex; // 0=name, 1=cpu, 2=memory, 3=disk
    // Rename modal state
    private TextInputState renameInput;
    private String renameSourceName;
    private String statusMessage;
    private String activeButton;

    private enum PendingAction { NONE, SHELL, BRANCH, BUILD_GOLDEN }
    private PendingAction pendingAction = PendingAction.NONE;
    private String pendingActionTarget;

    private List<InstanceInfo> entries;
    private List<Row> tableRows;
    private List<InstanceInfo> rowToEntry;
    private List<dev.incusspawn.config.ImageDef> buildImageDefs;
    private int buildImageSelected;

    @Override
    public void run() {
        entries = collectEntries();
        if (plain) {
            if (entries.isEmpty()) {
                System.out.println("No incus-spawn environments found.");
                System.out.println("Run 'isx build golden-java' to create your first image.");
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
                case BRANCH -> {
                    try {
                        createBranch(branchSourceName, pendingActionTarget,
                                branchEnableGui, branchNetworkMode,
                                branchEnableInbox ? branchInboxInput.text().strip() : null,
                                branchSourceIsVm);
                    } catch (Exception e) {
                        System.err.println("Branch failed: " + e.getMessage());
                        System.err.println("Press Enter to return to the list...");
                        try { System.in.read(); } catch (Exception ignored) {}
                    }
                }
                case BUILD_GOLDEN -> {
                    try {
                        new picocli.CommandLine(BuildCommand.class, factory)
                                .execute(pendingActionTarget);
                    } catch (Exception e) {
                        System.err.println("Build failed: " + e.getMessage());
                    }
                    System.err.println("Press Enter to return to the list...");
                    try { System.in.read(); } catch (Exception ignored) {}
                }
                case NONE -> { return; }
            }
        }
    }

    private static Terminal createExecTerminal() throws IOException {
        java.util.logging.Logger.getLogger("org.jline").setLevel(java.util.logging.Level.SEVERE);
        var provider = new ExecTerminalProvider();
        return provider.sysTerminal("isx", System.getenv("TERM"), false,
                StandardCharsets.UTF_8, false, Terminal.SignalHandler.SIG_DFL,
                false, SystemStream.Output);
    }

    private static JLineBackend createBackend(Terminal terminal) throws Exception {
        var backend = new JLineBackend();
        var clazz = JLineBackend.class;
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
            case BRANCH -> handleBranchEvent(key, tui, tableState);
            case RENAME -> handleRenameEvent(key, tui, tableState);
            case BUILD_IMAGE -> handleBuildImageEvent(key, tui, tableState);
        };
    }

    private boolean handleBrowseEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        if (key.isKey(KeyCode.F10) || key.isCtrlC()
                || key.isChar('q') || (key.hasCtrl() && key.isCharIgnoreCase('q'))) {
            tui.quit();
            return true;
        }
        statusMessage = null;

        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) { selectNextDataRow(tableState, 1); return true; }
        if (key.isKey(KeyCode.UP) || key.isChar('k'))   { selectNextDataRow(tableState, -1); return true; }
        if (key.isKey(KeyCode.HOME))                     { selectFirstDataRow(tableState); return true; }
        if (key.isKey(KeyCode.END))                      { selectLastDataRow(tableState); return true; }

        if (key.isKey(KeyCode.F2)) {
            buildImageSelected = 0;
            mode = Mode.BUILD_IMAGE;
            return true;
        }

        var selected = selectedEntry(tableState);
        if (selected == null) return false;

        if (key.isKey(KeyCode.F8)) {
            pendingDeleteName = selected.name;
            mode = Mode.CONFIRM_DELETE;
            return true;
        }
        if (key.isKey(KeyCode.ENTER) || key.isKey(KeyCode.F3)) {
            pendingAction = PendingAction.SHELL;
            pendingActionTarget = selected.name;
            tui.quit();
            return true;
        }
        if (key.isKey(KeyCode.F4)) {
            branchSourceName = selected.name;
            branchNameInput = new TextInputState(suggestBranchName(selected.name));
            branchEnableGui = false;
            branchNetworkMode = NetworkMode.FULL;
            branchEnableInbox = false;
            branchInboxInput = new TextInputState("");
            branchSourceIsVm = selected.runtime.toUpperCase().contains("VIRTUAL");
            var adaptiveCpu = String.valueOf(ResourceLimits.adaptiveCpuLimit());
            var adaptiveMemory = ResourceLimits.adaptiveMemoryLimit();
            var adaptiveDisk = ResourceLimits.defaultDiskLimit();
            vmCpuInput = new TextInputState(selected.limitsCpu.isEmpty() ? adaptiveCpu : selected.limitsCpu);
            vmMemoryInput = new TextInputState(selected.limitsMemory.isEmpty() ? adaptiveMemory : selected.limitsMemory);
            vmDiskInput = new TextInputState(selected.rootSize.isEmpty() ? adaptiveDisk : selected.rootSize);
            branchFieldIndex = 0;
            mode = Mode.BRANCH;
            return true;
        }
        if (key.isKey(KeyCode.F6) && isRunning(selected)) {
            execWithFeedback(tui, tableState, "F6", "Stopped", "Failed to stop",
                    selected.name, () -> incus.stop(selected.name));
            return true;
        }
        if (key.isKey(KeyCode.F7) && isRunning(selected)) {
            execWithFeedback(tui, tableState, "F7", "Restarted", "Failed to restart",
                    selected.name, () -> incus.restart(selected.name));
            return true;
        }
        if (key.isKey(KeyCode.F5)) {
            renameSourceName = selected.name;
            if (isRunning(selected)) {
                mode = Mode.CONFIRM_STOP_FOR_RENAME;
            } else {
                renameInput = new TextInputState(selected.name);
                mode = Mode.RENAME;
            }
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('l')) {
            refreshData(tableState);
            return true;
        }
        return false;
    }

    private boolean handleBranchEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) {
            mode = Mode.BROWSE;
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            var name = branchNameInput.text().strip();
            if (name.isEmpty()) return false;
            var validation = validateInstanceName(name);
            if (validation != null) {
                statusMessage = validation;
                mode = Mode.BROWSE;
                return true;
            }
            pendingAction = PendingAction.BRANCH;
            pendingActionTarget = name;
            tui.quit();
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('g')) {
            branchEnableGui = !branchEnableGui;
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('n')) {
            branchNetworkMode = branchNetworkMode.next();
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('i')) {
            branchEnableInbox = !branchEnableInbox;
            if (!branchEnableInbox && branchFieldIndex == inboxFieldIndex()) {
                branchFieldIndex = 0;
            }
            return true;
        }
        if (key.isKey(KeyCode.TAB)) {
            branchFieldIndex = (branchFieldIndex + 1) % (maxBranchField() + 1);
            return true;
        }

        var activeInput = activeBranchInput();
        if (key.isKey(KeyCode.BACKSPACE)) { activeInput.deleteBackward(); return true; }
        if (key.isKey(KeyCode.DELETE))    { activeInput.deleteForward(); return true; }
        if (key.isKey(KeyCode.LEFT))      { activeInput.moveCursorLeft(); return true; }
        if (key.isKey(KeyCode.RIGHT))     { activeInput.moveCursorRight(); return true; }
        if (key.isKey(KeyCode.HOME))      { activeInput.moveCursorToStart(); return true; }
        if (key.isKey(KeyCode.END))       { activeInput.moveCursorToEnd(); return true; }
        if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
            char ch = key.character();
            if (branchFieldIndex == 0) {
                // Name field: letters, digits, hyphens
                if (Character.isLetterOrDigit(ch) || ch == '-') activeInput.insert(ch);
            } else if (branchFieldIndex == inboxFieldIndex()) {
                // Inbox path: allow path characters
                if (Character.isLetterOrDigit(ch) || ch == '/' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                    activeInput.insert(ch);
                }
            } else {
                // VM resource fields: alphanumeric (e.g. "6GB")
                if (Character.isLetterOrDigit(ch)) activeInput.insert(ch);
            }
            return true;
        }
        return true;
    }

    private int inboxFieldIndex() {
        return branchSourceIsVm ? 4 : 1;
    }

    private int maxBranchField() {
        int max = branchSourceIsVm ? 3 : 0;
        if (branchEnableInbox) max = branchSourceIsVm ? 4 : 1;
        return max;
    }

    private TextInputState activeBranchInput() {
        if (branchFieldIndex == inboxFieldIndex() && branchEnableInbox) return branchInboxInput;
        return switch (branchFieldIndex) {
            case 1 -> vmCpuInput;
            case 2 -> vmMemoryInput;
            case 3 -> vmDiskInput;
            default -> branchNameInput;
        };
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

    private boolean handleBuildImageEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) {
            mode = Mode.BROWSE;
            return true;
        }
        if (buildImageDefs == null) {
            buildImageDefs = new ArrayList<>(dev.incusspawn.config.ImageDef.loadBuiltins().values());
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            buildImageSelected = Math.min(buildImageSelected + 1, buildImageDefs.size() - 1);
            return true;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            buildImageSelected = Math.max(buildImageSelected - 1, 0);
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            if (trySelectBuildImage(buildImageSelected, tui)) return true;
            return true;
        }
        if (key.code() == KeyCode.CHAR) {
            int idx = key.character() - '1';
            if (idx >= 0 && idx < buildImageDefs.size()) {
                if (trySelectBuildImage(idx, tui)) return true;
            }
        }
        return true;
    }

    private boolean trySelectBuildImage(int idx, TuiRunner tui) {
        var allDefs = new java.util.LinkedHashMap<String, dev.incusspawn.config.ImageDef>();
        for (var d : buildImageDefs) allDefs.put(d.getName(), d);
        var selected = buildImageDefs.get(idx);
        var credError = dev.incusspawn.config.SpawnConfig.checkCredentials(selected, allDefs, incus::exists);
        if (!credError.isEmpty()) {
            statusMessage = credError;
            mode = Mode.BROWSE;
            return true;
        }
        pendingAction = PendingAction.BUILD_GOLDEN;
        pendingActionTarget = selected.getName();
        tui.quit();
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
            activeButton = "F5";
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

    // --- Rendering ---

    private boolean hasAnyInstances() {
        return !entries.isEmpty();
    }

    private void render(dev.tamboui.terminal.Frame frame, TableState tableState) {
        var area = frame.area();
        boolean hasStatus = statusMessage != null;
        int footerHeight = hasStatus ? 2 : 1;
        boolean showBanner = !hasAnyInstances();
        int bannerHeight = showBanner ? 5 : 0;
        var chunks = Layout.vertical()
                .constraints(Constraint.length(bannerHeight), Constraint.fill(), Constraint.length(footerHeight))
                .split(area);

        if (showBanner) {
            renderBuildBanner(frame, chunks.get(0));
        }
        renderTable(frame, chunks.get(1), tableState);
        renderToolbar(frame, chunks.get(2), tableState, hasStatus);

        if (mode != Mode.BROWSE) {
            renderModal(frame, area, tableState);
        }
    }

    private static final Color BANNER_BG = Color.rgb(30, 30, 46);
    private static final Color BANNER_FG = Color.rgb(205, 214, 244);
    private static final Color BANNER_BORDER = Color.YELLOW;

    private void renderBuildBanner(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(" Getting Started ")
                .borderStyle(Style.EMPTY.fg(BANNER_BORDER))
                .style(Style.EMPTY.bg(BANNER_BG))
                .build();
        frame.renderWidget(block, area);
        var inner = block.inner(area);
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.fill())
                .split(inner);
        frame.renderWidget(Paragraph.from(Line.styled(
                " No images found. Press F2 to build one.",
                Style.EMPTY.fg(BANNER_FG).bg(BANNER_BG))), rows.get(0));
    }

    private void renderTable(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area, TableState tableState) {
        if (entries.isEmpty()) {
            var block = Block.builder()
                    .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                    .title(" incus-spawn ")
                    .borderStyle(Style.EMPTY.fg(Color.CYAN)).build();
            frame.renderWidget(block, area);
            var inner = block.inner(area);
            if (inner.height() > 1) {
                var hint = Layout.vertical()
                        .constraints(Constraint.length(inner.height() / 2), Constraint.length(1))
                        .split(inner);
                frame.renderWidget(Paragraph.from(
                        Line.styled("  No environments found. Press F2 to build an image.",
                                Style.EMPTY.fg(Color.GRAY))), hint.get(1));
            }
            return;
        }
        var table = Table.builder()
                .header(Row.from("NAME", "STATUS", "PARENT", "RUNTIME", "AGE")
                        .style(Style.EMPTY.bold().fg(Color.CYAN)))
                .rows(tableRows)
                .widths(Constraint.fill(), Constraint.length(12),
                        Constraint.length(20), Constraint.length(12),
                        Constraint.length(10))
                .highlightStyle(Style.EMPTY.bg(Color.DARK_GRAY).fg(Color.WHITE))
                .highlightSymbol("\u25b8 ")
                .block(Block.builder()
                        .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                        .title(" incus-spawn ")
                        .borderStyle(Style.EMPTY.fg(Color.CYAN)).build())
                .build();
        frame.renderStatefulWidget(table, area, tableState);
    }

    private void renderToolbar(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                                TableState tableState, boolean hasStatus) {
        fillBackground(frame, area, BAR_BG);

        var selected = selectedEntry(tableState);
        boolean hasSelection = selected != null;
        boolean running = hasSelection && isRunning(selected);

        var helpSpans = new ArrayList<Span>();
        addKey(helpSpans, "F2", "Build\u2026", false);
        addKey(helpSpans, "F3", "Shell", !hasSelection);
        addKey(helpSpans, "F4", "Branch\u2026", !hasSelection);
        addKey(helpSpans, "F5", "Rename\u2026", !hasSelection);
        addKey(helpSpans, "F6", "Stop", !running);
        addKey(helpSpans, "F7", "Restart", !running);
        addKey(helpSpans, "F8", "Destroy\u2026", !hasSelection);
        addKey(helpSpans, "F10", "Quit", false);

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
                        "This action cannot be undone.",
                        Style.EMPTY.fg(MODAL_WARN).bg(MODAL_BG))), rows.get(1));
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
            case BRANCH -> renderBranchModal(frame, screen);
            case RENAME -> renderInputModal(frame, screen,
                    "Rename '" + renameSourceName + "'", "New name:", renameSourceName, renameInput);
            case BUILD_IMAGE -> renderBuildImageModal(frame, screen);
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

    private void renderBuildImageModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        if (buildImageDefs == null) {
            buildImageDefs = new ArrayList<>(dev.incusspawn.config.ImageDef.loadBuiltins().values());
        }
        int height = buildImageDefs.size() + 5; // header + options + spacer + hints + border
        // " ▸ N label  (description)" — compute width from longest entry, capped at 70
        int maxLabel = buildImageDefs.stream()
                .mapToInt(d -> d.getName().length()
                        + (d.getDescription().isEmpty() ? 0 : d.getDescription().length() + 4))
                .max().orElse(30);
        int width = Math.min(Math.max(maxLabel + 10, 40), 70);
        var modalArea = centerRect(screen, width, height);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(" Build Image ")
                .borderStyle(Style.EMPTY.fg(MODAL_BORDER))
                .style(Style.EMPTY.bg(MODAL_BG))
                .build();
        frame.renderWidget(block, modalArea);
        var inner = block.inner(modalArea);

        var constraints = new ArrayList<Constraint>();
        constraints.add(Constraint.length(1)); // header
        for (int i = 0; i < buildImageDefs.size(); i++) {
            constraints.add(Constraint.length(1));
        }
        constraints.add(Constraint.fill()); // hints
        var rows = Layout.vertical()
                .constraints(constraints.toArray(new Constraint[0]))
                .split(inner);

        frame.renderWidget(Paragraph.from(Line.styled(
                " Select an image to build:",
                Style.EMPTY.fg(MODAL_FG).bg(MODAL_BG))), rows.get(0));
        for (int i = 0; i < buildImageDefs.size(); i++) {
            var def = buildImageDefs.get(i);
            var label = def.getName();
            if (def.getDescription() != null && !def.getDescription().isEmpty()) {
                label += "  (" + def.getDescription() + ")";
            }
            var selected = (i == buildImageSelected);
            var prefix = selected ? " \u25b8 " : "   ";
            var style = selected
                    ? Style.EMPTY.bold().fg(MODAL_ACCENT).bg(MODAL_BG)
                    : Style.EMPTY.fg(MODAL_FG).bg(MODAL_BG);
            frame.renderWidget(Paragraph.from(Line.from(List.of(
                    Span.styled(prefix + (i + 1) + " ", Style.EMPTY.bold().fg(MODAL_ACCENT).bg(MODAL_BG)),
                    Span.styled(label, style)))), rows.get(i + 1));
        }
        var hintSpans = new ArrayList<Span>();
        addModalKey(hintSpans, "\u2191\u2193", "Select");
        addModalKey(hintSpans, "Enter", "Confirm");
        addModalKey(hintSpans, "Esc", "Cancel");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(rows.size() - 1));
    }

    private void renderBranchModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        // Calculate dynamic height based on what's shown
        int height = 8; // name + toggles + hints
        if (branchSourceIsVm) height += 2; // VM resource row + spacer
        if (branchEnableInbox) height += 1; // inbox path field
        var modalArea = centerRect(screen, 54, height);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(" Branch from '" + branchSourceName + "' ")
                .borderStyle(Style.EMPTY.fg(MODAL_BORDER))
                .style(Style.EMPTY.bg(MODAL_BG))
                .build();
        frame.renderWidget(block, modalArea);
        var inner = block.inner(modalArea);

        var constraints = new ArrayList<Constraint>();
        constraints.add(Constraint.length(1)); // name label
        constraints.add(Constraint.length(1)); // name input
        if (branchSourceIsVm) {
            constraints.add(Constraint.length(1)); // spacer
            constraints.add(Constraint.length(1)); // vm resources
        }
        constraints.add(Constraint.length(1)); // spacer
        constraints.add(Constraint.length(1)); // gui toggle
        constraints.add(Constraint.length(1)); // network mode radio
        constraints.add(Constraint.length(1)); // inbox toggle
        if (branchEnableInbox) {
            constraints.add(Constraint.length(1)); // inbox path
        }
        constraints.add(Constraint.fill()); // hints

        var rows = Layout.vertical()
                .constraints(constraints.toArray(new Constraint[0]))
                .split(inner);

        int row = 0;
        // Name field
        frame.renderWidget(Paragraph.from(Line.styled(
                "Name:", Style.EMPTY.fg(MODAL_FG).bg(MODAL_BG))), rows.get(row++));
        if (branchFieldIndex == 0) {
            TextInput.builder()
                    .placeholder("branch-name")
                    .style(Style.EMPTY.fg(Color.WHITE).bg(Color.rgb(49, 50, 68)))
                    .build()
                    .renderWithCursor(rows.get(row++), frame.buffer(), branchNameInput, frame);
        } else {
            frame.renderWidget(Paragraph.from(Line.styled(
                    branchNameInput.text(), Style.EMPTY.fg(Color.GRAY).bg(Color.rgb(49, 50, 68)))),
                    rows.get(row++));
        }

        // VM resources
        if (branchSourceIsVm) {
            row++; // spacer
            renderVmResourceFields(frame, rows.get(row++));
        }

        row++; // spacer
        renderToggle(frame, rows.get(row++), "Alt-g", "GUI passthrough", branchEnableGui);
        renderNetworkModeRadio(frame, rows.get(row++), "Alt-n", branchNetworkMode);
        renderToggle(frame, rows.get(row++), "Alt-i", "Inbox mount", branchEnableInbox);

        if (branchEnableInbox) {
            var inboxRow = rows.get(row++);
            frame.renderWidget(Paragraph.from(Line.styled(
                    "  Path:", Style.EMPTY.fg(MODAL_FG).bg(MODAL_BG))), inboxRow);
            var pathArea = new dev.tamboui.layout.Rect(
                    inboxRow.x() + 8, inboxRow.y(), inboxRow.width() - 8, 1);
            if (branchFieldIndex == inboxFieldIndex()) {
                TextInput.builder()
                        .placeholder("/path/to/dir")
                        .style(Style.EMPTY.fg(Color.WHITE).bg(Color.rgb(49, 50, 68)))
                        .build()
                        .renderWithCursor(pathArea, frame.buffer(), branchInboxInput, frame);
            } else {
                var display = branchInboxInput.text().isEmpty() ? "/path/to/dir" : branchInboxInput.text();
                var fg = branchInboxInput.text().isEmpty() ? Color.rgb(80, 80, 100) : Color.GRAY;
                frame.renderWidget(Paragraph.from(Line.styled(
                        display, Style.EMPTY.fg(fg).bg(Color.rgb(49, 50, 68)))), pathArea);
            }
        }

        var hintSpans = new ArrayList<Span>();
        addModalKey(hintSpans, "Enter", "Confirm");
        addModalKey(hintSpans, "Esc", "Cancel");
        if (branchSourceIsVm || branchEnableInbox) {
            addModalKey(hintSpans, "Tab", "Next field");
        }
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(row));
    }

    private void renderVmResourceFields(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        var labelStyle = Style.EMPTY.fg(MODAL_FG).bg(MODAL_BG);
        var inputBg = Color.rgb(49, 50, 68);
        var inactiveBg = Color.rgb(40, 40, 55);
        var disabledFg = Color.rgb(80, 80, 100);

        var spans = new ArrayList<Span>();
        spans.add(Span.styled("  ", Style.EMPTY.bg(MODAL_BG)));
        spans.add(Span.styled("CPU ", labelStyle));
        renderInlineField(spans, vmCpuInput.text(), false, branchFieldIndex == 1, inputBg, inactiveBg, disabledFg);
        spans.add(Span.styled("  ", Style.EMPTY.bg(MODAL_BG)));
        spans.add(Span.styled("RAM ", labelStyle));
        renderInlineField(spans, vmMemoryInput.text(), false, branchFieldIndex == 2, inputBg, inactiveBg, disabledFg);
        spans.add(Span.styled("  ", Style.EMPTY.bg(MODAL_BG)));
        spans.add(Span.styled("Disk ", labelStyle));
        renderInlineField(spans, vmDiskInput.text(), false, branchFieldIndex == 3, inputBg, inactiveBg, disabledFg);
        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }

    private static void renderInlineField(List<Span> spans, String value, boolean disabled,
                                           boolean active, Color inputBg, Color inactiveBg, Color disabledFg) {
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
        var check = enabled ? "\u25c9" : "\u25cb";
        var checkColor = enabled ? Color.GREEN : Color.GRAY;
        frame.renderWidget(Paragraph.from(Line.from(List.of(
                Span.styled(" " + shortcut + " ", Style.EMPTY.fg(MODAL_ACCENT).bg(MODAL_BG)),
                Span.styled(check + " ", Style.EMPTY.fg(checkColor).bg(MODAL_BG)),
                Span.styled(label, Style.EMPTY.fg(MODAL_FG).bg(MODAL_BG))))), area);
    }

    private void renderNetworkModeRadio(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                                        String shortcut, NetworkMode selected) {
        var spans = new ArrayList<Span>();
        spans.add(Span.styled(" " + shortcut + " ", Style.EMPTY.fg(MODAL_ACCENT).bg(MODAL_BG)));
        for (NetworkMode mode : NetworkMode.values()) {
            boolean isSelected = (mode == selected);
            var symbol = isSelected ? "\u25c9" : "\u25cb"; // ◉ vs ○
            var color = isSelected ? Color.GREEN : Color.GRAY;
            spans.add(Span.styled(symbol + " ", Style.EMPTY.fg(color).bg(MODAL_BG)));
            var labelStyle = isSelected
                    ? Style.EMPTY.bold().fg(MODAL_FG).bg(MODAL_BG)
                    : Style.EMPTY.fg(Color.GRAY).bg(MODAL_BG);
            spans.add(Span.styled(mode.label(), labelStyle));
            spans.add(Span.styled("  ", Style.EMPTY.bg(MODAL_BG)));
        }
        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
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

    private String suggestBranchName(String sourceName) {
        var base = sourceName.startsWith("golden-") ? sourceName.substring(7) : sourceName;
        var existingNames = entries.stream().map(e -> e.name).collect(java.util.stream.Collectors.toSet());
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
            spans.add(Span.styled(" " + key + " ", Style.EMPTY.bold().fg(BAR_LABEL_FG).bg(BAR_ACTIVE_BG)));
            spans.add(Span.styled(label + " ", Style.EMPTY.fg(BAR_LABEL_FG).bg(BAR_ACTIVE_BG)));
        } else if (disabled) {
            spans.add(Span.styled(" " + key + " ", Style.EMPTY.fg(BAR_DISABLED_FG).bg(BAR_BG)));
            spans.add(Span.styled(label + " ", Style.EMPTY.fg(BAR_DISABLED_FG).bg(BAR_BG)));
        } else {
            spans.add(Span.styled(" " + key + " ", Style.EMPTY.bold().fg(BAR_KEY_FG).bg(BAR_BG)));
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
        tableRows = new ArrayList<>();
        rowToEntry = new ArrayList<>();

        // Sort entries: stopped first (templates), then running, alphabetically within each group
        var sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> {
            var aRunning = isRunning(a);
            var bRunning = isRunning(b);
            if (aRunning != bRunning) return aRunning ? 1 : -1;
            return a.name.compareToIgnoreCase(b.name);
        });

        for (var entry : sorted) {
            var age = entry.created.isEmpty() ? "-" : Metadata.ageDescription(entry.created);
            var parent = entry.parent.isEmpty() ? "-" : entry.parent;
            var statusStyle = switch (entry.status.toUpperCase()) {
                case "RUNNING" -> Style.EMPTY.fg(Color.GREEN);
                case "STOPPED" -> Style.EMPTY.fg(Color.GRAY);
                default -> Style.EMPTY;
            };
            tableRows.add(Row.from(entry.name, entry.status, parent,
                    entry.runtime, age).style(statusStyle));
            rowToEntry.add(entry);
        }
    }

    private void createBranch(String source, String name, boolean gui, NetworkMode networkMode,
                               String inboxPath, boolean vm) {
        if (incus.exists(name)) {
            System.err.println("Error: an instance named '" + name + "' already exists.");
            return;
        }

        System.out.println("Branching '" + name + "' from '" + source + "'...");
        incus.copy(source, name);

        // Apply resource limits
        String cpuStr, memory, disk;
        if (vm) {
            cpuStr = vmCpuInput.text().strip();
            memory = vmMemoryInput.text().strip();
            disk = vmDiskInput.text().strip();
        } else {
            cpuStr = String.valueOf(ResourceLimits.adaptiveCpuLimit());
            memory = ResourceLimits.adaptiveMemoryLimit();
            disk = ResourceLimits.defaultDiskLimit();
        }
        System.out.println("Applying resource limits: " + cpuStr + " CPUs, " + memory + " memory, " + disk + " disk");
        incus.configSet(name, "limits.cpu", cpuStr);
        incus.configSet(name, "limits.memory", memory);
        incus.exec("config", "device", "set", name, "root", "size=" + disk);

        switch (networkMode) {
            case PROXY_ONLY -> configureProxyOnly(name);
            case AIRGAP -> configureAirgap(name);
            case FULL -> {}
        }

        incus.start(name);
        waitForReady(name);

        // Auth is handled transparently by the host MITM proxy — no container-side
        // configuration needed. DNS overrides and CA cert are baked into golden images.

        if (networkMode == NetworkMode.PROXY_ONLY) {
            BranchCommand.applyProxyOnlyFirewall(incus, name);
        }

        if (gui) {
            configureGui(name);
        }

        if (inboxPath != null && !inboxPath.isEmpty()) {
            var path = java.nio.file.Path.of(inboxPath);
            if (java.nio.file.Files.isDirectory(path)) {
                System.out.println("Mounting inbox: " + path.toAbsolutePath() + " -> /home/agentuser/inbox (read-only)");
                incus.deviceAdd(name, "inbox", "disk",
                        "source=" + path.toAbsolutePath(),
                        "path=/home/agentuser/inbox",
                        "readonly=true");
            } else {
                System.err.println("Warning: inbox path '" + inboxPath + "' is not a directory, skipping.");
            }
        }

        // Fix home dir ownership after all device mounts
        incus.shellExec(name, "chown", "-R", String.valueOf(getUid()) + ":" + String.valueOf(getUid()), "/home/agentuser");

        incus.configSet(name, Metadata.PARENT, source);
        incus.configSet(name, Metadata.CREATED, Metadata.today());

        System.out.println("Branch '" + name + "' is ready.");
        System.out.println("Connecting to " + name + "...\n");
        incus.interactiveShell(name, "agentuser");
        System.out.println();
    }

    private void configureGui(String name) {
        var xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        var waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        if (xdgRuntimeDir == null || waylandDisplay == null) {
            System.err.println("Warning: WAYLAND_DISPLAY or XDG_RUNTIME_DIR not set, skipping GUI passthrough.");
            return;
        }
        var hostSocket = xdgRuntimeDir + "/" + waylandDisplay;
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(hostSocket))) {
            System.err.println("Warning: Wayland socket not found at " + hostSocket + ", skipping.");
            return;
        }

        System.out.println("Enabling GUI passthrough...");
        var uid = String.valueOf(getUid());

        incus.deviceAdd(name, "gpu", "gpu");
        incus.deviceAdd(name, "xdg-runtime", "disk",
                "source=" + xdgRuntimeDir,
                "path=/run/user/" + uid);

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

    private void configureProxyOnly(String name) {
        System.out.println("Configuring proxy-only network...");
        var gatewayIp = MitmProxy.resolveGatewayIp(incus);
        incus.configSet(name, Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name());
        incus.configSet(name, Metadata.PROXY_GATEWAY, gatewayIp);
    }

    private void configureAirgap(String name) {
        System.out.println("Enabling network airgap...");
        var result = incus.exec("network", "detach", "incusbr0", name);
        if (!result.success()) {
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
                // Include any instance that has incus-spawn metadata
                var parent = configVal(config, Metadata.PARENT, "");
                var created = configVal(config, Metadata.CREATED, "");
                var type = configVal(config, Metadata.TYPE, "");
                // Only show instances managed by incus-spawn (have any metadata)
                if (type.isEmpty() && parent.isEmpty() && created.isEmpty()) continue;

                var expandedDevices = node.path("expanded_devices");
                var rootSize = expandedDevices.path("root").path("size").asText("");

                entryList.add(new InstanceInfo(
                        node.path("name").asText(),
                        node.path("status").asText(),
                        configVal(config, Metadata.PROJECT, "-"),
                        configVal(config, Metadata.PROFILE, "-"),
                        created,
                        node.path("type").asText(),
                        parent,
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

    private void printPlain(List<InstanceInfo> items) {
        var nameWidth = Math.max(20, items.stream().mapToInt(e -> e.name.length()).max().orElse(20));
        var fmt = "  %-" + nameWidth + "s  %-10s  %-20s  %-10s  %s%n";

        System.out.printf(fmt, "NAME", "STATUS", "PARENT", "RUNTIME", "AGE");
        System.out.printf(fmt, "-".repeat(nameWidth), "----------",
                "--------------------", "----------", "---");
        for (var entry : items) {
            var age = entry.created.isEmpty() ? "-" : Metadata.ageDescription(entry.created);
            var parent = entry.parent.isEmpty() ? "-" : entry.parent;
            System.out.printf(fmt, entry.name, entry.status, parent, entry.runtime, age);
        }
        System.out.println();
    }

    private record InstanceInfo(String name, String status,
                                String project, String profile, String created,
                                String runtime, String parent,
                                String limitsCpu, String limitsMemory, String rootSize) {}
}
