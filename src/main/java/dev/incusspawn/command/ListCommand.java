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

    private enum Mode { BROWSE, CONFIRM_DELETE, CREATE_CLONE }
    private Mode mode = Mode.BROWSE;
    private String pendingDeleteName;
    private String pendingDeleteType;
    private TextInputState cloneNameInput;
    private String cloneSourceName;
    private String statusMessage;

    private enum PendingAction { NONE, SHELL, CREATE_CLONE }
    private PendingAction pendingAction = PendingAction.NONE;
    private String pendingActionTarget;

    private List<InstanceInfo> entries;
    private List<Row> tableRows;
    private List<InstanceInfo> rowToEntry; // null for group header rows

    @Override
    public void run() {
        entries = collectEntries();
        if (entries.isEmpty()) {
            System.out.println("No incus-spawn environments found.");
            System.out.println("Run 'incus-spawn build golden-minimal' to create your first golden image.");
            return;
        }

        if (plain) {
            printPlain(entries);
        } else {
            runTuiLoop();
        }
    }

    // --- TUI lifecycle ---

    private void runTuiLoop() {
        while (true) {
            entries = collectEntries();
            if (entries.isEmpty()) {
                System.out.println("No incus-spawn environments found.");
                return;
            }
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
                        createClone(cloneSourceName, pendingActionTarget);
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
            case CREATE_CLONE -> handleCreateCloneEvent(key, tui, tableState);
        };
    }

    private boolean handleBrowseEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        if (key.isChar('q') || key.isChar('Q') || key.isCtrlC()
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
        if (key.isKey(KeyCode.ENTER) || key.isChar('s')) {
            pendingAction = PendingAction.SHELL;
            pendingActionTarget = selected.name;
            tui.quit();
            return true;
        }
        if (key.isChar('c') && isCloneable(selected)) {
            cloneSourceName = selected.name;
            cloneNameInput = new TextInputState();
            mode = Mode.CREATE_CLONE;
            return true;
        }
        if (key.isChar('S') && isRunning(selected)) {
            execWithStatus(tableState, "Stopping", "Stopped", "Failed to stop",
                    selected.name, () -> incus.stop(selected.name));
            return true;
        }
        if (key.isChar('R') && isRunning(selected)) {
            execWithStatus(tableState, "Restarting", "Restarted", "Failed to restart",
                    selected.name, () -> incus.restart(selected.name));
            return true;
        }
        if (key.isChar('r')) {
            refreshData(tableState);
            return true;
        }
        return false;
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
            if (entries.isEmpty()) {
                tui.quit();
                return true;
            }
            buildRowData();
            selectFirstDataRow(tableState);
        }
        mode = Mode.BROWSE;
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
        if (key.isKey(KeyCode.BACKSPACE)) { cloneNameInput.deleteBackward(); return true; }
        if (key.isKey(KeyCode.DELETE))    { cloneNameInput.deleteForward(); return true; }
        if (key.isKey(KeyCode.LEFT))      { cloneNameInput.moveCursorLeft(); return true; }
        if (key.isKey(KeyCode.RIGHT))     { cloneNameInput.moveCursorRight(); return true; }
        if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
            char ch = key.character();
            if (Character.isLetterOrDigit(ch) || ch == '-') {
                cloneNameInput.insert(ch);
            }
            return true;
        }
        return true;
    }

    // --- Rendering ---

    private void render(dev.tamboui.terminal.Frame frame, TableState tableState) {
        var area = frame.area();
        boolean hasStatus = (mode == Mode.BROWSE && statusMessage != null);
        int footerHeight = (mode == Mode.BROWSE) ? (hasStatus ? 2 : 1) : 2;
        var chunks = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(footerHeight))
                .split(area);

        renderTable(frame, chunks.get(0), tableState);
        renderFooter(frame, chunks.get(1), tableState, hasStatus);
    }

    private void renderTable(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area, TableState tableState) {
        var table = Table.builder()
                .header(Row.from("NAME", "STATUS", "TYPE", "RUNTIME", "AGE")
                        .style(Style.EMPTY.bold().fg(Color.CYAN)))
                .rows(tableRows)
                .widths(Constraint.fill(), Constraint.length(12),
                        Constraint.length(12), Constraint.length(12), Constraint.length(10))
                .highlightStyle(Style.EMPTY.bg(Color.DARK_GRAY).fg(Color.WHITE))
                .highlightSymbol("\u25b8 ")
                .block(Block.builder()
                        .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                        .title(" incus-spawn environments ")
                        .borderStyle(Style.EMPTY.fg(Color.CYAN)).build())
                .build();
        frame.renderStatefulWidget(table, area, tableState);
    }

    private void renderFooter(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                               TableState tableState, boolean hasStatus) {
        switch (mode) {
            case BROWSE -> {
                var selected = selectedEntry(tableState);
                var helpSpans = new ArrayList<Span>();
                addKey(helpSpans, "q", "Quit");
                if (selected != null) {
                    addKey(helpSpans, "Enter/s", "Shell");
                    addKey(helpSpans, "d", "Destroy");
                    if (isCloneable(selected)) addKey(helpSpans, "c", "Clone");
                    if (isRunning(selected)) {
                        addKey(helpSpans, "S", "Stop");
                        addKey(helpSpans, "R", "Restart");
                    }
                }
                addKey(helpSpans, "r", "Refresh");

                if (hasStatus) {
                    var rows = splitVertical(area, 1, 1);
                    var msgColor = statusMessage.startsWith("Failed") || statusMessage.startsWith("Invalid")
                            ? Color.RED : Color.GREEN;
                    frame.renderWidget(
                            Paragraph.from(Line.styled(" " + statusMessage, Style.EMPTY.fg(msgColor))), rows.get(0));
                    frame.renderWidget(Paragraph.from(Line.from(helpSpans)), rows.get(1));
                } else {
                    frame.renderWidget(Paragraph.from(Line.from(helpSpans)), area);
                }
            }
            case CONFIRM_DELETE -> {
                var rows = splitVertical(area, 1, 1);
                boolean isGolden = Metadata.TYPE_BASE.equals(pendingDeleteType)
                        || Metadata.TYPE_PROJECT.equals(pendingDeleteType);
                var warning = isGolden
                        ? " Destroy golden image '" + pendingDeleteName + "'? This affects all derived clones! (y/N)"
                        : " Destroy '" + pendingDeleteName + "'? (y/N)";
                frame.renderWidget(
                        Paragraph.from(Line.styled(warning, Style.EMPTY.bold().fg(Color.RED))), rows.get(0));
                var confirmSpans = new ArrayList<Span>();
                addKey(confirmSpans, "y", "Confirm");
                addKey(confirmSpans, "any key", "Cancel");
                frame.renderWidget(Paragraph.from(Line.from(confirmSpans)), rows.get(1));
            }
            case CREATE_CLONE -> {
                var rows = splitVertical(area, 1, 1);
                frame.renderWidget(Paragraph.from(Line.from(List.of(
                        Span.styled(" Clone from '", Style.EMPTY.fg(Color.GRAY)),
                        Span.styled(cloneSourceName, Style.EMPTY.bold().fg(Color.CYAN)),
                        Span.styled("' \u2014 name: ", Style.EMPTY.fg(Color.GRAY))))), rows.get(0));
                TextInput.builder()
                        .placeholder("clone-name")
                        .style(Style.EMPTY.fg(Color.WHITE))
                        .build()
                        .renderWithCursor(rows.get(1), frame.buffer(), cloneNameInput, frame);
            }
        }
    }

    private static void addKey(List<Span> spans, String key, String label) {
        spans.add(Span.styled(" " + key, Style.EMPTY.bold().fg(Color.YELLOW)));
        spans.add(Span.styled(" " + label + "  ", Style.EMPTY.fg(Color.GRAY)));
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

    private void execWithStatus(TableState tableState, String progressVerb, String doneVerb,
                                 String failVerb, String name, Runnable action) {
        statusMessage = progressVerb + " " + name + "...";
        try {
            action.run();
            statusMessage = doneVerb + " " + name;
        } catch (Exception e) {
            statusMessage = failVerb + " " + name;
        }
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
            tableRows.add(Row.from(group.getKey(), "", "", "", "")
                    .style(Style.EMPTY.bold().fg(Color.YELLOW)));
            rowToEntry.add(null);

            for (var entry : group.getValue()) {
                var age = entry.created.isEmpty() ? "-" : Metadata.ageDescription(entry.created);
                var statusStyle = switch (entry.status.toUpperCase()) {
                    case "RUNNING" -> Style.EMPTY.fg(Color.GREEN);
                    case "STOPPED" -> Style.EMPTY.fg(Color.RED);
                    default -> Style.EMPTY;
                };
                tableRows.add(Row.from("  " + entry.name, entry.status, entry.type, entry.runtime, age)
                        .style(statusStyle));
                rowToEntry.add(entry);
            }
        }
    }

    private void createClone(String source, String name) {
        if (incus.exists(name)) {
            System.err.println("Error: an instance named '" + name + "' already exists.");
            return;
        }

        System.out.println("Creating clone '" + name + "' from '" + source + "'...");
        incus.copy(source, name);

        var cpu = dev.incusspawn.incus.ResourceLimits.adaptiveCpuLimit();
        var memory = dev.incusspawn.incus.ResourceLimits.adaptiveMemoryLimit();
        var disk = dev.incusspawn.incus.ResourceLimits.defaultDiskLimit();
        System.out.println("Applying resource limits: " + cpu + " CPUs, " + memory + " memory, " + disk + " disk");
        incus.configSet(name, "limits.cpu", String.valueOf(cpu));
        incus.configSet(name, "limits.memory", memory);
        incus.exec("config", "device", "set", name, "root", "size=" + disk);

        incus.start(name);
        waitForReady(name);

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

        incus.configSet(name, Metadata.TYPE, Metadata.TYPE_CLONE);
        incus.configSet(name, Metadata.PROJECT, source);
        incus.configSet(name, Metadata.CREATED, Metadata.today());

        System.out.println("Clone '" + name + "' is ready.");
        System.out.println("Connecting to " + name + "...\n");
        incus.interactiveShell(name, "agentuser");
        System.out.println();
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

    private List<InstanceInfo> collectEntries() {
        var instances = incus.list();
        var entryList = new ArrayList<InstanceInfo>();
        for (var instance : instances) {
            var name = instance.get("name");
            var type = getConfigOrDefault(name, Metadata.TYPE, "");
            if (type.isEmpty()) continue;
            entryList.add(new InstanceInfo(name, instance.get("status"), type,
                    getConfigOrDefault(name, Metadata.PROJECT, "-"),
                    getConfigOrDefault(name, Metadata.PROFILE, "-"),
                    getConfigOrDefault(name, Metadata.CREATED, ""),
                    instance.get("type")));
        }
        return entryList;
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
        var fmt = "  %-" + nameWidth + "s  %-10s  %-10s  %-10s  %s%n";

        for (var group : grouped.entrySet()) {
            System.out.println("\n" + group.getKey());
            System.out.printf(fmt, "NAME", "STATUS", "TYPE", "RUNTIME", "AGE");
            System.out.printf(fmt, "-".repeat(nameWidth), "----------", "----------", "----------", "---");
            for (var entry : group.getValue()) {
                var age = entry.created.isEmpty() ? "-" : Metadata.ageDescription(entry.created);
                System.out.printf(fmt, entry.name, entry.status, entry.type, entry.runtime, age);
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
                                String runtime) {}
}
