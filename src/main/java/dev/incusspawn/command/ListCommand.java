package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.git.AutoRemoteService;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.ResourceLimits;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.MitmProxy;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.YamlToolSetup;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Flex;
import dev.tamboui.layout.Layout;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
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
import dev.tamboui.widgets.scrollbar.Scrollbar;
import dev.tamboui.widgets.scrollbar.ScrollbarOrientation;
import dev.tamboui.widgets.scrollbar.ScrollbarState;
import dev.tamboui.widgets.table.TableState;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    ToolDefLoader toolDefLoader;

    @Inject
    picocli.CommandLine.IFactory factory;

    private enum Mode { BROWSE, CONFIRM_DELETE, CONFIRM_STOP_FOR_RENAME, CONFIRM_BUILD, BRANCH, RENAME, TEMPLATE_DETAIL, INSTANCE_DETAIL, INFO, ERROR }
    private Mode mode = Mode.BROWSE;
    private String errorMessage;
    private String pendingDeleteName;
    private String pendingBuildName; // template name or "--all" for CONFIRM_BUILD modal
    // Branch modal state
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
    private String progressMessage;
    // Template detail modal state
    private boolean detailViewCompact = true;
    private int detailScrollOffset;
    // Instance detail modal state
    private int instanceDetailScrollOffset;
    // Info modal state
    private int infoScrollOffset;

    private enum PendingAction { NONE, SHELL, BRANCH, BUILD_TEMPLATE, EDIT_TEMPLATE }
    private PendingAction pendingAction = PendingAction.NONE;
    private String pendingActionTarget;
    // After returning from a shell/branch, focus this instance in the instances panel
    private String returnToInstance;
    private String returnToTemplate;

    private static final int PAGE_SIZE = 10;

    // Two-panel focus
    private enum Panel { TEMPLATES, INSTANCES }
    private Panel focusedPanel = Panel.TEMPLATES;

    // Template panel data (top)
    private Map<String, dev.incusspawn.config.ImageDef> imageDefs;
    private List<TemplateInfo> templateEntries;
    private List<Row> templateRows;
    private boolean anyTemplateOutdated;
    private boolean anyDefinitionChanged;
    private boolean anyParentRebuilt;
    private java.util.Set<String> templatesDefChanged = java.util.Set.of();
    private java.util.Set<String> templatesParentRebuilt = java.util.Set.of();
    private java.util.Set<String> storedSourceTemplates = java.util.Set.of();
    private TableState templateTableState;

    // Instance panel data (bottom)
    private List<InstanceInfo> entries;
    private List<Row> tableRows;
    private List<InstanceInfo> rowToEntry;
    private TableState instanceTableState;

    @Override
    public void run() {
        reloadData();
        if (plain) {
            if (entries.isEmpty() && templateEntries.stream().noneMatch(t -> !"not built".equals(t.buildStatus))) {
                System.out.println("No incus-spawn environments found.");
                System.out.println("Run 'isx build tpl-java' to create your first template.");
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
            reloadData();
            mode = Mode.BROWSE;
            pendingAction = PendingAction.NONE;

            templateTableState = new TableState();
            instanceTableState = new TableState();

            // Restore template selection by name
            boolean templateRestored = false;
            if (returnToTemplate != null) {
                for (int i = 0; i < templateEntries.size(); i++) {
                    if (templateEntries.get(i).name.equals(returnToTemplate)) {
                        templateTableState.select(i);
                        templateRestored = true;
                        break;
                    }
                }
            }
            if (!templateRestored) {
                if (!templateEntries.isEmpty()) templateTableState.select(0);
            }
            returnToTemplate = null;

            // If returning from a shell/branch, focus the target instance
            if (returnToInstance != null) {
                focusedPanel = Panel.INSTANCES;
                boolean found = false;
                for (int i = 0; i < rowToEntry.size(); i++) {
                    if (rowToEntry.get(i) != null && rowToEntry.get(i).name.equals(returnToInstance)) {
                        instanceTableState.select(i);
                        found = true;
                        break;
                    }
                }
                if (!found) selectFirstDataRow(instanceTableState);
                returnToInstance = null;
            } else {
                selectFirstDataRow(instanceTableState);
                focusedPanel = Panel.TEMPLATES;
            }

            try (var runner = TuiRunner.create()) {
                runner.run(
                        (event, tui) -> handleEvent(event, tui, instanceTableState),
                        frame -> render(frame, instanceTableState));
            } catch (Exception e) {
                printPlain(entries);
                return;
            }

            // Remember template selection for when we re-enter the TUI
            var tpl = selectedTemplate();
            if (tpl != null) returnToTemplate = tpl.name;

            switch (pendingAction) {
                case SHELL -> {
                    returnToInstance = pendingActionTarget;
                    shellInto(pendingActionTarget);
                }
                case BRANCH -> {
                    returnToInstance = pendingActionTarget;
                    try {
                        createBranch(branchSourceName, pendingActionTarget,
                                branchEnableGui, branchNetworkMode,
                                branchEnableInbox ? branchInboxInput.text().strip() : null,
                                branchSourceIsVm);
                        statusMessage = "Created branch " + pendingActionTarget;
                    } catch (Exception e) {
                        statusMessage = "Failed to create branch " + pendingActionTarget + ": " + e.getMessage();
                    }
                }
                case BUILD_TEMPLATE -> {
                    returnToTemplate = pendingActionTarget;
                    try {
                        int exitCode = new picocli.CommandLine(BuildCommand.class, factory)
                                .execute(pendingActionTarget, "--yes");
                        statusMessage = exitCode == 0
                                ? "Built " + pendingActionTarget + " successfully"
                                : "Failed to build " + pendingActionTarget
                                        + ". Check instance '" + pendingActionTarget + "-failed-build' for inspection.";
                    } catch (Exception e) {
                        statusMessage = "Failed to build " + pendingActionTarget + ": " + e.getMessage();
                    }
                }
                case EDIT_TEMPLATE -> {
                    returnToTemplate = pendingActionTarget;
                    new picocli.CommandLine(TemplatesCommand.Edit.class, factory)
                            .execute(pendingActionTarget);
                }
                case NONE -> { return; }
            }
        }
    }

    /**
     * Reload all data from Incus and image definitions. Populates both the
     * template panel (from ImageDef + Incus state) and the instance panel
     * (non-template instances only).
     */
    private void reloadData() {
        var allInstances = collectEntries();
        imageDefs = dev.incusspawn.config.ImageDef.loadAll();

        // Build template panel data by merging ImageDef definitions with Incus state
        templateEntries = new ArrayList<>();
        var templateNames = new java.util.HashSet<String>();
        for (var def : imageDefs.values()) {
            var name = def.getName();
            // Find matching Incus instance
            InstanceInfo match = null;
            for (var inst : allInstances) {
                if (inst.name.equals(name)) {
                    match = inst;
                    break;
                }
            }
            if (match != null) {
                templateEntries.add(new TemplateInfo(name, def.getDescription(),
                        match.created.isEmpty() ? "built" : match.created, match.runtime,
                        match.buildVersion, match.definitionSha));
                templateNames.add(name);
            } else {
                templateEntries.add(new TemplateInfo(name, def.getDescription(), "not built", "", "", ""));
            }
        }
        // Add out-of-scope templates (built but not in current definition scope)
        var storedNames = new java.util.HashSet<String>();
        for (var inst : allInstances) {
            if (templateNames.contains(inst.name)) continue;
            if (!Metadata.TYPE_BASE.equals(inst.type)) continue;

            var buildSource = BuildSource.fromJson(inst.buildSourceJson);
            if (buildSource == null) continue;

            for (var entry : buildSource.getDefinitions().entrySet()) {
                imageDefs.putIfAbsent(entry.getKey(), entry.getValue());
            }
            toolDefLoader.addFallbacks(buildSource.getTools());

            templateEntries.add(new TemplateInfo(inst.name, buildSource.descriptionFor(inst.name),
                    inst.created.isEmpty() ? "built" : inst.created, inst.runtime,
                    inst.buildVersion, inst.definitionSha));
            templateNames.add(inst.name);
            storedNames.add(inst.name);
        }
        storedSourceTemplates = storedNames;

        buildTemplateRowData();

        // Instance panel: exclude template instances (they're shown in the template panel)
        entries = new ArrayList<>();
        for (var inst : allInstances) {
            if (!templateNames.contains(inst.name)) {
                entries.add(inst);
            }
        }
        buildRowData();
    }

    // --- Event handling ---

    private boolean handleEvent(Event event, TuiRunner tui, TableState tableState) {
        if (!(event instanceof KeyEvent key)) return false;
        return switch (mode) {
            case BROWSE -> handleBrowseEvent(key, tui, tableState);
            case CONFIRM_DELETE -> handleConfirmDeleteEvent(key, tui, tableState);
            case CONFIRM_BUILD -> handleConfirmBuildEvent(key, tui);
            case CONFIRM_STOP_FOR_RENAME -> handleConfirmStopForRenameEvent(key, tui, tableState);
            case BRANCH -> handleBranchEvent(key, tui, tableState);
            case RENAME -> handleRenameEvent(key, tui, tableState);
            case TEMPLATE_DETAIL -> handleTemplateDetailEvent(key, tui);
            case INSTANCE_DETAIL -> handleInstanceDetailEvent(key, tui);
            case INFO -> handleInfoEvent(key);
            case ERROR -> { mode = Mode.BROWSE; yield true; }
        };
    }

    private boolean handleBrowseEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        // Global keys (both panels)
        if (key.isKey(KeyCode.F10) || key.isCtrlC()
                || key.isChar('q') || (key.hasCtrl() && key.isCharIgnoreCase('q'))) {
            tui.quit();
            return true;
        }
        statusMessage = null;

        if (key.isKey(KeyCode.F1)) {
            infoScrollOffset = 0;
            mode = Mode.INFO;
            return true;
        }
        if (key.isKey(KeyCode.TAB)) {
            focusedPanel = (focusedPanel == Panel.TEMPLATES) ? Panel.INSTANCES : Panel.TEMPLATES;
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('l')) {
            refreshData(tableState);
            return true;
        }

        return (focusedPanel == Panel.TEMPLATES)
                ? handleTemplateBrowseEvent(key, tui, tableState)
                : handleInstanceBrowseEvent(key, tui, tableState);
    }

    private boolean handleTemplateBrowseEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        // Navigation within template panel
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            var idx = templateTableState.selected();
            if (idx != null && idx < templateEntries.size() - 1) templateTableState.select(idx + 1);
            return true;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            var idx = templateTableState.selected();
            if (idx != null && idx > 0) templateTableState.select(idx - 1);
            return true;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            var idx = templateTableState.selected();
            if (idx != null) templateTableState.select(Math.min(idx + PAGE_SIZE, templateEntries.size() - 1));
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            var idx = templateTableState.selected();
            if (idx != null) templateTableState.select(Math.max(idx - PAGE_SIZE, 0));
            return true;
        }
        if (key.isKey(KeyCode.HOME)) {
            if (!templateEntries.isEmpty()) templateTableState.select(0);
            return true;
        }
        if (key.isKey(KeyCode.END)) {
            if (!templateEntries.isEmpty()) templateTableState.select(templateEntries.size() - 1);
            return true;
        }

        var template = selectedTemplate();
        if (template == null) return false;

        // F3: Show template details
        if (key.isKey(KeyCode.F3)) {
            detailViewCompact = true;
            detailScrollOffset = 0;
            mode = Mode.TEMPLATE_DETAIL;
            return true;
        }

        // Shift+F5: Build all templates
        if (key.isKey(KeyCode.F5) && key.hasShift()) {
            var anyNotBuilt = templateEntries.stream()
                    .anyMatch(t -> "not built".equals(t.buildStatus));
            if (anyNotBuilt) {
                if (showProxyError()) return true;
                pendingAction = PendingAction.BUILD_TEMPLATE;
                pendingActionTarget = "--missing";
                tui.quit();
            } else {
                pendingBuildName = "--all";
                mode = Mode.CONFIRM_BUILD;
            }
            return true;
        }

        // F5: Build/rebuild selected template
        if (key.isKey(KeyCode.F5) && !key.hasShift()) {
            var def = imageDefs.get(template.name);
            if (def != null) {
                var credError = dev.incusspawn.config.SpawnConfig.checkCredentials(def, imageDefs, incus::exists);
                if (!credError.isEmpty()) {
                    statusMessage = credError;
                    return true;
                }
            }
            if (!"not built".equals(template.buildStatus)) {
                // Already built — confirm rebuild
                pendingBuildName = template.name;
                mode = Mode.CONFIRM_BUILD;
            } else {
                if (showProxyError()) return true;
                pendingAction = PendingAction.BUILD_TEMPLATE;
                pendingActionTarget = template.name;
                tui.quit();
            }
            return true;
        }

        // Enter/F4: Branch from template (only if built)
        if (key.isKey(KeyCode.ENTER) || key.isKey(KeyCode.F4)) {
            if ("not built".equals(template.buildStatus)) {
                statusMessage = "Template not built. Press F5 to build it first.";
                return true;
            }
            openBranchModal(template.name, template.runtime);
            return true;
        }

        // Shift+F8: Destroy all built templates
        if (key.isKey(KeyCode.F8) && key.hasShift()) {
            var anyBuilt = templateEntries.stream()
                    .anyMatch(t -> !"not built".equals(t.buildStatus));
            if (!anyBuilt) {
                statusMessage = "No templates are built.";
                return true;
            }
            pendingDeleteName = "--all";
            mode = Mode.CONFIRM_DELETE;
            return true;
        }

        // F8: Destroy template
        if (key.isKey(KeyCode.F8)) {
            if ("not built".equals(template.buildStatus)) {
                statusMessage = "Template is not built.";
                return true;
            }
            pendingDeleteName = template.name;
            mode = Mode.CONFIRM_DELETE;
            return true;
        }

        return false;
    }

    private boolean handleInstanceBrowseEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        // Navigation within instance panel
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) { selectNextDataRow(tableState, 1); return true; }
        if (key.isKey(KeyCode.UP) || key.isChar('k'))   { selectNextDataRow(tableState, -1); return true; }
        if (key.isKey(KeyCode.PAGE_DOWN))                { for (int n = 0; n < PAGE_SIZE; n++) selectNextDataRow(tableState, 1); return true; }
        if (key.isKey(KeyCode.PAGE_UP))                  { for (int n = 0; n < PAGE_SIZE; n++) selectNextDataRow(tableState, -1); return true; }
        if (key.isKey(KeyCode.HOME))                     { selectFirstDataRow(tableState); return true; }
        if (key.isKey(KeyCode.END))                      { selectLastDataRow(tableState); return true; }

        var selected = selectedEntry(tableState);
        if (selected == null) return false;

        // Shift+F8: Destroy all instances
        if (key.isKey(KeyCode.F8) && key.hasShift()) {
            if (entries.isEmpty()) {
                statusMessage = "No instances to destroy.";
                return true;
            }
            pendingDeleteName = "--all-instances";
            mode = Mode.CONFIRM_DELETE;
            return true;
        }
        if (key.isKey(KeyCode.F8)) {
            pendingDeleteName = selected.name;
            mode = Mode.CONFIRM_DELETE;
            return true;
        }
        if (key.isKey(KeyCode.ENTER) || key.isKey(KeyCode.F2)) {
            if (showProxyErrorIfNeeded(selected.name)) return true;
            pendingAction = PendingAction.SHELL;
            pendingActionTarget = selected.name;
            tui.quit();
            return true;
        }
        if (key.isKey(KeyCode.F3)) {
            instanceDetailScrollOffset = 0;
            mode = Mode.INSTANCE_DETAIL;
            return true;
        }
        if (key.isKey(KeyCode.F4)) {
            openBranchModal(selected.name, selected.runtime);
            return true;
        }
        if (key.isKey(KeyCode.F7) && !key.hasShift() && isRunning(selected)) {
            execWithFeedback(tui, tableState, "Stopping", "Stopped", "Failed to stop",
                    selected.name, () -> incus.stop(selected.name));
            return true;
        }
        if (key.isKey(KeyCode.F7) && key.hasShift() && isRunning(selected)) {
            execWithFeedback(tui, tableState, "Restarting", "Restarted", "Failed to restart",
                    selected.name, () -> incus.restart(selected.name));
            return true;
        }
        if (key.isKey(KeyCode.F6)) {
            renameSourceName = selected.name;
            if (isRunning(selected)) {
                mode = Mode.CONFIRM_STOP_FOR_RENAME;
            } else {
                renameInput = new TextInputState(selected.name);
                mode = Mode.RENAME;
            }
            return true;
        }
        return false;
    }

    private void openBranchModal(String sourceName, String runtime) {
        branchSourceName = sourceName;
        branchNameInput = new TextInputState(suggestBranchName(sourceName));
        branchEnableGui = false;
        branchNetworkMode = NetworkMode.FULL;
        branchEnableInbox = false;
        branchInboxInput = new TextInputState("");
        branchSourceIsVm = runtime.toUpperCase().contains("VIRTUAL");
        var adaptiveCpu = String.valueOf(ResourceLimits.adaptiveCpuLimit());
        var adaptiveMemory = ResourceLimits.adaptiveMemoryLimit();
        var adaptiveDisk = ResourceLimits.defaultDiskLimit();
        vmCpuInput = new TextInputState(adaptiveCpu);
        vmMemoryInput = new TextInputState(adaptiveMemory);
        vmDiskInput = new TextInputState(adaptiveDisk);
        branchFieldIndex = 0;
        mode = Mode.BRANCH;
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
            if (branchNetworkMode != NetworkMode.AIRGAP && showProxyError()) return true;
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

    private boolean handleConfirmDeleteEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        if (key.isChar('y') || key.isChar('Y')) {
            mode = Mode.BROWSE;
            if ("--all".equals(pendingDeleteName)) {
                // Destroy all built templates in reverse order (children before parents)
                var allNames = new java.util.ArrayList<>(imageDefs.keySet());
                java.util.Collections.reverse(allNames);
                int destroyed = 0;
                for (var name : allNames) {
                    if (incus.exists(name)) {
                        progressMessage = "Destroying " + name + "...";
                        tui.draw(frame -> render(frame, tableState));
                        try {
                            incus.delete(name, true);
                            AutoRemoteService.removeRemotes(name, msg -> statusMessage = msg);
                            destroyed++;
                        } catch (Exception e) {
                            statusMessage = "Failed to destroy " + name + ": " + e.getMessage();
                            break;
                        }
                    }
                }
                if (statusMessage == null || statusMessage.isEmpty()) {
                    statusMessage = "Destroyed " + destroyed + " template(s)";
                }
            } else if ("--all-instances".equals(pendingDeleteName)) {
                // Destroy all instances
                int destroyed = 0;
                for (var entry : entries) {
                    progressMessage = "Destroying " + entry.name() + "...";
                    tui.draw(frame -> render(frame, tableState));
                    try {
                        incus.delete(entry.name(), true);
                        AutoRemoteService.removeRemotes(entry.name(), msg -> statusMessage = msg);
                        destroyed++;
                    } catch (Exception e) {
                        statusMessage = "Failed to destroy " + entry.name() + ": " + e.getMessage();
                        break;
                    }
                }
                if (statusMessage == null || statusMessage.isEmpty()) {
                    statusMessage = "Destroyed " + destroyed + " instance(s)";
                }
            } else {
                progressMessage = "Destroying " + pendingDeleteName + "...";
                tui.draw(frame -> render(frame, tableState));
                try {
                    incus.delete(pendingDeleteName, true);
                    AutoRemoteService.removeRemotes(pendingDeleteName, msg -> statusMessage = msg);
                    statusMessage = "Destroyed " + pendingDeleteName;
                } catch (Exception e) {
                    statusMessage = "Failed to destroy " + pendingDeleteName + ": " + e.getMessage();
                }
            }
            progressMessage = null;
            refreshData(tableState);
        }
        mode = Mode.BROWSE;
        return true;
    }

    private boolean handleConfirmBuildEvent(KeyEvent key, TuiRunner tui) {
        if (key.isChar('y') || key.isChar('Y')) {
            if (showProxyError()) return true;
            pendingAction = PendingAction.BUILD_TEMPLATE;
            pendingActionTarget = pendingBuildName;
            tui.quit();
        }
        mode = Mode.BROWSE;
        return true;
    }

    private boolean handleConfirmStopForRenameEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        if (key.isChar('y') || key.isChar('Y')) {
            mode = Mode.BROWSE;
            progressMessage = "Stopping " + renameSourceName + "...";
            tui.draw(frame -> render(frame, tableState));
            try {
                incus.stop(renameSourceName);
                progressMessage = null;
                refreshData(tableState);
                renameInput = new TextInputState(renameSourceName);
                mode = Mode.RENAME;
            } catch (Exception e) {
                progressMessage = null;
                statusMessage = "Failed to stop " + renameSourceName + ": " + e.getMessage();
                mode = Mode.BROWSE;
            }
        } else {
            mode = Mode.BROWSE;
        }
        return true;
    }

    // --- Rendering ---

    private void render(dev.tamboui.terminal.Frame frame, TableState tableState) {
        var area = frame.area();
        boolean hasStatus = statusMessage != null;
        int footerHeight = hasStatus ? 3 : 2;
        boolean showLegend = anyTemplateOutdated || anyDefinitionChanged || anyParentRebuilt;
        int legendHeight = showLegend ? 1 : 0;
        int templateIdeal = templateEntries.size() + 3 + legendHeight;
        int instanceIdeal = entries.size() + 3;
        int available = area.height() - footerHeight;
        int templatePanelHeight;
        if (templateIdeal + instanceIdeal <= available) {
            templatePanelHeight = templateIdeal;
        } else {
            int minPanel = 5;
            int templateShare = Math.max(minPanel, available * templateIdeal / (templateIdeal + instanceIdeal));
            templatePanelHeight = Math.min(templateIdeal, templateShare);
            templatePanelHeight = Math.max(templatePanelHeight, minPanel);
        }
        var chunks = Layout.vertical()
                .constraints(
                        Constraint.length(templatePanelHeight),
                        Constraint.fill(),
                        Constraint.length(footerHeight))
                .split(area);

        renderTemplateTable(frame, chunks.get(0));
        renderInstanceTable(frame, chunks.get(1), tableState);
        renderToolbar(frame, chunks.get(2), tableState, hasStatus);

        if (mode != Mode.BROWSE) {
            renderModal(frame, area, tableState);
        }

        // Progress overlay — rendered on top of everything else, regardless of mode
        if (progressMessage != null) {
            ModalRenderer.renderProgressOverlay(frame, area, progressMessage);
        }
    }

    private void renderTemplateTable(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        boolean focused = focusedPanel == Panel.TEMPLATES;
        var borderColor = focused ? Color.CYAN : Color.DARK_GRAY;

        if (templateEntries.isEmpty()) {
            var block = Block.builder()
                    .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                    .title(" Templates ")
                    .borderStyle(Style.EMPTY.fg(borderColor)).build();
            frame.renderWidget(block, area);
            var inner = block.inner(area);
            if (inner.height() > 0) {
                frame.renderWidget(Paragraph.from(
                        Line.styled("  No template definitions found.",
                                Style.EMPTY.fg(Color.GRAY))), inner);
            }
            return;
        }

        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(" Templates ")
                .borderStyle(Style.EMPTY.fg(borderColor)).build();
        frame.renderWidget(block, area);
        var inner = block.inner(area);

        boolean showLegend = anyTemplateOutdated || anyDefinitionChanged || anyParentRebuilt;
        dev.tamboui.layout.Rect tableArea;
        if (showLegend && inner.height() > 2) {
            var parts = splitVertical(inner, inner.height() - 1, 1);
            tableArea = parts.get(0);
            renderLegend(frame, parts.get(1));
        } else {
            tableArea = inner;
        }

        int visibleRows = Math.max(tableArea.height() - 1, 1);
        boolean needsScroll = templateRows.size() > visibleRows;
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
                .header(Row.from("NAME", "BUILT", "DESCRIPTION")
                        .style(Style.EMPTY.bold().fg(focused ? Color.CYAN : Color.DARK_GRAY)))
                .rows(templateRows)
                .widths(Constraint.length(20), Constraint.length(20), Constraint.fill())
                .highlightSymbol(focused ? "\u25b8 " : "  ");

        if (focused) {
            tableBuilder.highlightStyle(Style.EMPTY.bg(Color.DARK_GRAY).fg(Color.WHITE));
        } else {
            tableBuilder.highlightStyle(Style.EMPTY);
        }

        frame.renderStatefulWidget(tableBuilder.build(), actualTableArea, templateTableState);

        if (scrollArea != null) {
            var scrollbar = Scrollbar.builder()
                    .orientation(ScrollbarOrientation.VERTICAL_RIGHT)
                    .thumbStyle(Style.EMPTY.fg(borderColor))
                    .trackStyle(Style.EMPTY.fg(Color.rgb(60, 62, 84)))
                    .build();
            var scrollState = new ScrollbarState()
                    .contentLength(templateRows.size())
                    .viewportContentLength(visibleRows)
                    .position(templateTableState.offset());
            frame.renderStatefulWidget(scrollbar, scrollArea, scrollState);
        }
    }

    private void renderLegend(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        var text = "! = outdated  \u25b3 = changed  \u2191 = parent rebuilt ";
        var padding = Math.max(0, area.width() - text.length());
        var style = Style.EMPTY.fg(Color.GRAY);
        frame.renderWidget(Paragraph.from(Line.from(
                Span.styled(" ".repeat(padding) + text, style))), area);
    }

    private void renderInstanceTable(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                                      TableState tableState) {
        boolean focused = focusedPanel == Panel.INSTANCES;
        var borderColor = focused ? Color.CYAN : Color.DARK_GRAY;

        if (entries.isEmpty()) {
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
                                Style.EMPTY.fg(Color.GRAY))), hint.get(1));
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
        boolean needsScroll = tableRows.size() > visibleRows;
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
                .header(Row.from("NAME", "STATUS", "IP", "PARENT", "RUNTIME", "AGE")
                        .style(Style.EMPTY.bold().fg(focused ? Color.CYAN : Color.DARK_GRAY)))
                .rows(tableRows)
                .widths(Constraint.fill(), Constraint.length(9),
                        Constraint.length(16), Constraint.length(14),
                        Constraint.length(12), Constraint.length(10))
                .highlightSymbol(focused ? "\u25b8 " : "  ");

        if (focused) {
            tableBuilder.highlightStyle(Style.EMPTY.bg(Color.DARK_GRAY).fg(Color.WHITE));
        } else {
            tableBuilder.highlightStyle(Style.EMPTY);
        }

        frame.renderStatefulWidget(tableBuilder.build(), actualArea, tableState);

        if (scrollArea != null) {
            var scrollbar = Scrollbar.builder()
                    .orientation(ScrollbarOrientation.VERTICAL_RIGHT)
                    .thumbStyle(Style.EMPTY.fg(borderColor))
                    .trackStyle(Style.EMPTY.fg(Color.rgb(60, 62, 84)))
                    .build();
            var scrollState = new ScrollbarState()
                    .contentLength(tableRows.size())
                    .viewportContentLength(visibleRows)
                    .position(tableState.offset());
            frame.renderStatefulWidget(scrollbar, scrollArea, scrollState);
        }
    }

    private record KeyItem(Line line, int width) {}

    private void renderToolbar(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                                TableState tableState, boolean hasStatus) {
        fillBackground(frame, area, BAR_BG);

        var template = selectedTemplate();
        boolean hasTemplate = template != null;
        boolean isBuilt = hasTemplate && !"not built".equals(template.buildStatus);
        var selected = selectedEntry(tableState);
        boolean hasInstance = selected != null;
        boolean running = hasInstance && isRunning(selected);
        boolean onTemplates = focusedPanel == Panel.TEMPLATES;

        var items = new ArrayList<KeyItem>();
        items.add(makeKey("F1", "Info", false));
        items.add(makeKey("F2", "Shell", !hasInstance || onTemplates));
        items.add(makeKey("F3", "Details", onTemplates ? !hasTemplate : !hasInstance));
        items.add(makeKey("F4", "Branch\u2026", onTemplates ? !isBuilt : !hasInstance));
        items.add(makeKey("F5", "Build", !hasTemplate || !onTemplates));
        items.add(makeKey("F6", "Rename\u2026", !hasInstance || onTemplates));
        items.add(makeKey("F7", "Stop", !running || onTemplates));
        items.add(makeKey("F8", "Destroy\u2026", onTemplates ? !isBuilt : !hasInstance));
        items.add(makeKey("F10", "Quit", false));

        var contextLine = buildContextLine(template, selected, onTemplates);

        if (hasStatus) {
            var rows = splitVertical(area, 1, 1, 1);
            var isError = statusMessage.startsWith("Failed") || statusMessage.startsWith("Invalid")
                    || statusMessage.startsWith("Template");
            var statusBg = Color.rgb(0, 0, 80);
            var msgFg = isError ? Color.LIGHT_RED : Color.WHITE;
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.styled(" " + statusMessage,
                                    Style.EMPTY.bold().fg(msgFg))))
                            .style(Style.EMPTY.bg(statusBg))
                            .build(), rows.get(0));
            renderContextLine(frame, rows.get(1), contextLine);
            renderKeyItems(frame, rows.get(2), items);
        } else {
            var rows = splitVertical(area, 1, 1);
            renderContextLine(frame, rows.get(0), contextLine);
            renderKeyItems(frame, rows.get(1), items);
        }
    }

    private static final Color CONTEXT_BG = Color.rgb(30, 30, 50);

    private Line buildContextLine(TemplateInfo template, InstanceInfo instance, boolean onTemplates) {
        var bg = CONTEXT_BG;
        if (onTemplates && template != null) {
            var spans = new ArrayList<Span>();
            spans.add(Span.styled(" " + template.name, Style.EMPTY.bold().fg(Color.WHITE).bg(bg)));
            boolean hasWarning = false;
            if (!"not built".equals(template.buildStatus)) {
                var warnStyle = Style.EMPTY.fg(Color.YELLOW).bg(bg);
                var currentVersion = BuildInfo.instance().version();
                if (!template.buildVersion.isEmpty() && !template.buildVersion.equals(currentVersion)) {
                    spans.add(Span.styled("  ! built with isx v" + template.buildVersion
                            + " (current: v" + currentVersion + ")", warnStyle));
                    hasWarning = true;
                } else if (template.buildVersion.isEmpty()) {
                    spans.add(Span.styled("  ! built before isx version tracking", warnStyle));
                    hasWarning = true;
                }
                if (templatesDefChanged.contains(template.name)) {
                    spans.add(Span.styled("  △ definition changed since last build", warnStyle));
                    hasWarning = true;
                }
                if (templatesParentRebuilt.contains(template.name)) {
                    var parentName = imageDefs.get(template.name).getParent();
                    spans.add(Span.styled("  ↑ parent " + parentName + " was rebuilt since last build", warnStyle));
                    hasWarning = true;
                }
            }
            if (!hasWarning && template.description != null && !template.description.isEmpty()) {
                spans.add(Span.styled("  " + template.description, Style.EMPTY.fg(Color.GRAY).bg(bg)));
            }
            return Line.from(spans);
        }
        if (!onTemplates && instance != null) {
            var spans = new ArrayList<Span>();
            spans.add(Span.styled(" " + instance.name, Style.EMPTY.bold().fg(Color.WHITE).bg(bg)));
            if (!instance.parent.isEmpty() && !"-".equals(instance.parent)) {
                spans.add(Span.styled("  from " + instance.parent, Style.EMPTY.fg(Color.GRAY).bg(bg)));
            }
            if (!instance.ipv4.isEmpty()) {
                spans.add(Span.styled("  " + instance.ipv4, Style.EMPTY.fg(Color.LIGHT_CYAN).bg(bg)));
            }
            if (!instance.networkMode.isEmpty()) {
                spans.add(Span.styled("  [" + instance.networkMode.toLowerCase() + "]",
                        Style.EMPTY.fg(Color.GRAY).bg(bg)));
            }
            return Line.from(spans);
        }
        return Line.styled("", Style.EMPTY);
    }

    private void renderContextLine(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area, Line line) {
        fillBackground(frame, area, CONTEXT_BG);
        frame.renderWidget(Paragraph.from(line), area);
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

    // --- Modal dialogs (centered overlay) ---

    private void renderModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen,
                              TableState tableState) {
        switch (mode) {
            case CONFIRM_DELETE -> {
                var isAllTemplates = "--all".equals(pendingDeleteName);
                var isAllInstances = "--all-instances".equals(pendingDeleteName);
                var isAll = isAllTemplates || isAllInstances;
                var title = isAllTemplates ? " Destroy all templates "
                        : isAllInstances ? " Destroy all instances "
                        : " Destroy '" + pendingDeleteName + "' ";
                var message = isAllTemplates ? "This will destroy all built templates."
                        : isAllInstances ? "This will destroy all instances."
                        : "This action cannot be undone.";
                ModalRenderer.renderConfirmModal(frame, screen, title, message, ModalRenderer.WARN);
            }
            case CONFIRM_BUILD -> {
                var isAll = "--all".equals(pendingBuildName);
                var title = isAll ? " Rebuild all templates " : " Rebuild '" + pendingBuildName + "' ";
                var message = isAll
                        ? "This will delete and rebuild all templates."
                        : "This will delete and rebuild " + pendingBuildName + ".";
                ModalRenderer.renderConfirmModal(frame, screen, title, message, ModalRenderer.WARN);
            }
            case CONFIRM_STOP_FOR_RENAME -> {
                ModalRenderer.renderConfirmModal(frame, screen,
                        " Rename '" + renameSourceName + "' ",
                        "Instance is running. Stop it first?", ModalRenderer.BORDER,
                        "Stop & rename");
            }
            case BRANCH -> renderBranchModal(frame, screen);
            case RENAME -> ModalRenderer.renderInputModal(frame, screen,
                    "Rename '" + renameSourceName + "'", "New name:", renameSourceName, renameInput);
            case TEMPLATE_DETAIL -> renderTemplateDetailModal(frame, screen);
            case INSTANCE_DETAIL -> renderInstanceDetailModal(frame, screen);
            case INFO -> renderInfoModal(frame, screen);
            case ERROR -> ModalRenderer.renderErrorModal(frame, screen, errorMessage);
            default -> {}
        }
    }

    private void renderBranchModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        int height = 8;
        if (branchSourceIsVm) height += 2;
        if (branchEnableInbox) height += 1;
        var modalArea = ModalRenderer.centerRect(screen, 54, height);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(" Branch from '" + branchSourceName + "' ")
                .borderStyle(Style.EMPTY.fg(ModalRenderer.BORDER))
                .style(Style.EMPTY.bg(ModalRenderer.BG))
                .build();
        ModalRenderer.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var constraints = new ArrayList<Constraint>();
        constraints.add(Constraint.length(1));
        constraints.add(Constraint.length(1));
        if (branchSourceIsVm) {
            constraints.add(Constraint.length(1));
            constraints.add(Constraint.length(1));
        }
        constraints.add(Constraint.length(1));
        constraints.add(Constraint.length(1));
        constraints.add(Constraint.length(1));
        constraints.add(Constraint.length(1));
        if (branchEnableInbox) {
            constraints.add(Constraint.length(1));
        }
        constraints.add(Constraint.fill());

        var rows = Layout.vertical()
                .constraints(constraints.toArray(new Constraint[0]))
                .split(inner);

        int row = 0;
        frame.renderWidget(Paragraph.from(Line.styled(
                "Name:", Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG))), rows.get(row++));
        if (branchFieldIndex == 0) {
            TextInput.builder()
                    .placeholder("branch-name")
                    .style(Style.EMPTY.fg(Color.WHITE).bg(ModalRenderer.INPUT_BG))
                    .build()
                    .renderWithCursor(rows.get(row++), frame.buffer(), branchNameInput, frame);
        } else {
            frame.renderWidget(Paragraph.from(Line.styled(
                    branchNameInput.text(), Style.EMPTY.fg(Color.GRAY).bg(ModalRenderer.INPUT_BG))),
                    rows.get(row++));
        }

        if (branchSourceIsVm) {
            row++;
            renderVmResourceFields(frame, rows.get(row++));
        }

        row++;
        ModalRenderer.renderToggle(frame, rows.get(row++), "Alt-g", "GUI passthrough", branchEnableGui);
        ModalRenderer.renderNetworkModeRadio(frame, rows.get(row++), "Alt-n", branchNetworkMode);
        ModalRenderer.renderToggle(frame, rows.get(row++), "Alt-i", "Inbox mount", branchEnableInbox);

        if (branchEnableInbox) {
            var inboxRow = rows.get(row++);
            frame.renderWidget(Paragraph.from(Line.styled(
                    "  Path:", Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG))), inboxRow);
            var pathArea = new dev.tamboui.layout.Rect(
                    inboxRow.x() + 8, inboxRow.y(), inboxRow.width() - 8, 1);
            if (branchFieldIndex == inboxFieldIndex()) {
                TextInput.builder()
                        .placeholder("/path/to/dir")
                        .style(Style.EMPTY.fg(Color.WHITE).bg(ModalRenderer.INPUT_BG))
                        .build()
                        .renderWithCursor(pathArea, frame.buffer(), branchInboxInput, frame);
            } else {
                var display = branchInboxInput.text().isEmpty() ? "/path/to/dir" : branchInboxInput.text();
                var fg = branchInboxInput.text().isEmpty() ? ModalRenderer.PLACEHOLDER_FG : Color.GRAY;
                frame.renderWidget(Paragraph.from(Line.styled(
                        display, Style.EMPTY.fg(fg).bg(ModalRenderer.INPUT_BG))), pathArea);
            }
        }

        var hintSpans = new ArrayList<Span>();
        ModalRenderer.addKey(hintSpans, "Enter", "Confirm");
        ModalRenderer.addKey(hintSpans, "Esc", "Cancel");
        if (branchSourceIsVm || branchEnableInbox) {
            ModalRenderer.addKey(hintSpans, "Tab", "Next field");
        }
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(row));
    }

    private void renderVmResourceFields(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        var labelStyle = Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG);

        var spans = new ArrayList<Span>();
        spans.add(Span.styled("  ", Style.EMPTY.bg(ModalRenderer.BG)));
        spans.add(Span.styled("CPU ", labelStyle));
        ModalRenderer.renderInlineField(spans, vmCpuInput.text(), false, branchFieldIndex == 1);
        spans.add(Span.styled("  ", Style.EMPTY.bg(ModalRenderer.BG)));
        spans.add(Span.styled("RAM ", labelStyle));
        ModalRenderer.renderInlineField(spans, vmMemoryInput.text(), false, branchFieldIndex == 2);
        spans.add(Span.styled("  ", Style.EMPTY.bg(ModalRenderer.BG)));
        spans.add(Span.styled("Disk ", labelStyle));
        ModalRenderer.renderInlineField(spans, vmDiskInput.text(), false, branchFieldIndex == 3);
        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }

    // --- Template detail modal ---

    private boolean handleTemplateDetailEvent(KeyEvent key, TuiRunner tui) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC() || key.isKey(KeyCode.F3)) {
            mode = Mode.BROWSE;
            return true;
        }
        if (key.isKey(KeyCode.F4)) {
            var template = selectedTemplate();
            if (template != null) {
                pendingAction = PendingAction.EDIT_TEMPLATE;
                pendingActionTarget = template.name;
                mode = Mode.BROWSE;
                tui.quit();
            }
            return true;
        }
        if (key.isKey(KeyCode.TAB)) {
            detailViewCompact = !detailViewCompact;
            detailScrollOffset = 0;
            return true;
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            detailScrollOffset++;
            return true;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            if (detailScrollOffset > 0) detailScrollOffset--;
            return true;
        }
        if (key.isKey(KeyCode.HOME)) {
            detailScrollOffset = 0;
            return true;
        }
        if (key.isKey(KeyCode.END)) {
            detailScrollOffset = Integer.MAX_VALUE; // capped during render
            return true;
        }
        return false;
    }

    // --- Instance detail modal ---

    private boolean handleInstanceDetailEvent(KeyEvent key, TuiRunner tui) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC() || key.isKey(KeyCode.F3)) {
            mode = Mode.BROWSE;
            return true;
        }
        if (key.isKey(KeyCode.F2) || key.isKey(KeyCode.ENTER)) {
            var selected = selectedEntry(instanceTableState);
            if (selected != null) {
                if (showProxyErrorIfNeeded(selected.name)) return true;
                pendingAction = PendingAction.SHELL;
                pendingActionTarget = selected.name;
                mode = Mode.BROWSE;
                tui.quit();
            }
            return true;
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            instanceDetailScrollOffset++;
            return true;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            if (instanceDetailScrollOffset > 0) instanceDetailScrollOffset--;
            return true;
        }
        if (key.isKey(KeyCode.HOME)) {
            instanceDetailScrollOffset = 0;
            return true;
        }
        if (key.isKey(KeyCode.END)) {
            instanceDetailScrollOffset = Integer.MAX_VALUE; // capped during render
            return true;
        }
        return false;
    }

    private boolean handleInfoEvent(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC() || key.isKey(KeyCode.F1)) {
            mode = Mode.BROWSE;
            return true;
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            infoScrollOffset++;
            return true;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            if (infoScrollOffset > 0) infoScrollOffset--;
            return true;
        }
        if (key.isKey(KeyCode.HOME)) {
            infoScrollOffset = 0;
            return true;
        }
        if (key.isKey(KeyCode.END)) {
            infoScrollOffset = Integer.MAX_VALUE;
            return true;
        }
        return true;
    }

    private void renderInfoModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        var info = dev.incusspawn.BuildInfo.instance();
        var lines = List.of(
                Line.from(List.of(
                        Span.styled("incus-spawn", Style.EMPTY.bold().fg(ModalRenderer.ACCENT).bg(ModalRenderer.BG)),
                        Span.styled(" (isx) ", Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG)),
                        Span.styled(info.version(), Style.EMPTY.fg(Color.GREEN).bg(ModalRenderer.BG)))),
                Line.styled("Commit " + info.gitSha(),
                        Style.EMPTY.fg(Color.GRAY).bg(ModalRenderer.BG)),
                Line.styled("Incus  client " + info.incusClient() + ", server " + info.incusServer(),
                        Style.EMPTY.fg(Color.GRAY).bg(ModalRenderer.BG)),
                Line.styled(info.runtime(),
                        Style.EMPTY.fg(Color.GRAY).bg(ModalRenderer.BG)),
                Line.styled("", Style.EMPTY),
                Line.styled("Copyright 2026 Sanne Grinovero",
                        Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG)),
                Line.styled("Licensed under the Apache License 2.0",
                        Style.EMPTY.fg(Color.GRAY).bg(ModalRenderer.BG)),
                Line.styled("github.com/Sanne/incus-spawn",
                        Style.EMPTY.fg(ModalRenderer.ACCENT).bg(ModalRenderer.BG))
                        .hyperlink("https://github.com/Sanne/incus-spawn"),
                Line.styled("", Style.EMPTY),
                Line.styled("Manage isolated Incus development environments.",
                        Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG)),
                Line.styled("Templates define base images; Instances are", Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG)),
                Line.styled("lightweight copy-on-write branches of them.", Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG)),
                Line.styled("", Style.EMPTY),
                Line.styled("Keyboard shortcuts:", Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG)),
                Line.styled("", Style.EMPTY),
                shortcutRow("Tab", "Switch panels", null, null),
                shortcutRow("F1", "This dialog", null, null),
                shortcutRow("F2", "Shell into instance", null, null),
                shortcutRow("F3", "View details", null, null),
                shortcutRow("F4", "Branch", null, null),
                shortcutRow("F5", "Build template", "⇧F5", "Build all"),
                shortcutRow("F6", "Rename instance", null, null),
                shortcutRow("F7", "Stop instance", "⇧F7", "Restart"),
                shortcutRow("F8", "Destroy", "⇧F8", "Destroy all"),
                shortcutRow("F10", "Quit", null, null));

        int width = 52;
        int maxHeight = screen.height() - 2;
        int modalHeight = Math.min(lines.size() + 4, maxHeight);

        var modalArea = ModalRenderer.centerRect(screen, width, modalHeight);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(" About incus-spawn ")
                .borderStyle(Style.EMPTY.fg(ModalRenderer.BORDER))
                .style(Style.EMPTY.bg(ModalRenderer.BG))
                .build();
        ModalRenderer.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var rows = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);

        infoScrollOffset = renderScrollableContent(frame, rows.get(0), lines, infoScrollOffset);

        var hintSpans = new ArrayList<Span>();
        ModalRenderer.addKey(hintSpans, "F1/Esc", "Close");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(1));
    }

    private int renderScrollableContent(dev.tamboui.terminal.Frame frame,
                                        dev.tamboui.layout.Rect contentArea,
                                        List<Line> contentLines, int scrollOffset) {
        boolean needsScroll = contentLines.size() > contentArea.height();
        dev.tamboui.layout.Rect textArea;
        dev.tamboui.layout.Rect scrollbarArea;
        if (needsScroll) {
            var cols = Layout.horizontal()
                    .constraints(Constraint.fill(), Constraint.length(1))
                    .split(contentArea);
            textArea = cols.get(0);
            scrollbarArea = cols.get(1);
        } else {
            textArea = contentArea;
            scrollbarArea = null;
        }

        int visibleHeight = textArea.height();
        int maxScroll = Math.max(0, contentLines.size() - visibleHeight);
        scrollOffset = Math.min(scrollOffset, maxScroll);

        var visibleLines = contentLines.subList(
                scrollOffset,
                Math.min(scrollOffset + visibleHeight, contentLines.size()));
        frame.renderWidget(Paragraph.from(Text.from(visibleLines)), textArea);

        if (scrollbarArea != null) {
            var scrollbar = Scrollbar.builder()
                    .orientation(ScrollbarOrientation.VERTICAL_RIGHT)
                    .thumbStyle(Style.EMPTY.fg(ModalRenderer.ACCENT))
                    .trackStyle(Style.EMPTY.fg(Color.rgb(60, 62, 84)))
                    .style(Style.EMPTY.bg(ModalRenderer.BG))
                    .build();
            var state = new ScrollbarState()
                    .contentLength(contentLines.size())
                    .viewportContentLength(visibleHeight)
                    .position(scrollOffset);
            frame.renderStatefulWidget(scrollbar, scrollbarArea, state);
        }
        return scrollOffset;
    }

    private static Line shortcutRow(String key, String desc, String shiftKey, String shiftDesc) {
        var spans = new ArrayList<Span>();
        spans.add(Span.styled(String.format("  %-5s", key), Style.EMPTY.bold().fg(ModalRenderer.ACCENT).bg(ModalRenderer.BG)));
        spans.add(Span.styled(String.format("%-18s", desc), Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG)));
        if (shiftKey != null) {
            spans.add(Span.styled(String.format("%-5s", shiftKey), Style.EMPTY.bold().fg(ModalRenderer.ACCENT).bg(ModalRenderer.BG)));
            spans.add(Span.styled(shiftDesc, Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG)));
        }
        return Line.from(spans);
    }

    private void renderTemplateDetailModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        var template = selectedTemplate();
        if (template == null) return;

        var contentLines = detailViewCompact
                ? buildCompactDetailLines(template.name)
                : buildTreeDetailLines(template.name);

        int maxLineWidth = 0;
        for (var line : contentLines) {
            int w = line.spans().stream().mapToInt(s -> s.content().length()).sum();
            if (w > maxLineWidth) maxLineWidth = w;
        }
        int modalWidth = Math.min(maxLineWidth + 4, screen.width() - 4); // +2 border +2 padding
        int maxHeight = screen.height() - 2;
        int modalHeight = Math.min(contentLines.size() + 4, maxHeight); // +2 border +1 spacer +1 hints

        var viewLabel = detailViewCompact ? "Compact" : "Tree";
        var modalArea = ModalRenderer.centerRect(screen, modalWidth, modalHeight);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(" " + template.name + " \u2014 " + viewLabel + " ")
                .borderStyle(Style.EMPTY.fg(ModalRenderer.BORDER))
                .style(Style.EMPTY.bg(ModalRenderer.BG))
                .build();
        ModalRenderer.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var rows = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);

        detailScrollOffset = renderScrollableContent(frame, rows.get(0), contentLines, detailScrollOffset);

        var hintSpans = new ArrayList<Span>();
        ModalRenderer.addKey(hintSpans, "Tab", detailViewCompact ? "Tree view" : "Compact view");
        ModalRenderer.addKey(hintSpans, "F4", "Edit");
        ModalRenderer.addKey(hintSpans, "F3/Esc", "Close");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(1));
    }

    private void renderInstanceDetailModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        var selected = selectedEntry(instanceTableState);
        if (selected == null) return;

        var contentLines = buildInstanceDetailLines(selected);

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
                .borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .title(" " + selected.name + " ")
                .borderStyle(Style.EMPTY.fg(ModalRenderer.BORDER))
                .style(Style.EMPTY.bg(ModalRenderer.BG))
                .build();
        ModalRenderer.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var rows = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);

        instanceDetailScrollOffset = renderScrollableContent(frame, rows.get(0), contentLines, instanceDetailScrollOffset);

        var hintSpans = new ArrayList<Span>();
        ModalRenderer.addKey(hintSpans, "F2", "Shell");
        ModalRenderer.addKey(hintSpans, "F3/Esc", "Close");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(1));
    }

    private List<Line> buildInstanceDetailLines(InstanceInfo info) {
        var lines = new ArrayList<Line>();
        var lineStyle = Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG);
        var labelStyle = Style.EMPTY.fg(ModalRenderer.ACCENT).bg(ModalRenderer.BG);
        var dimStyle = Style.EMPTY.fg(Color.GRAY).bg(ModalRenderer.BG);

        var statusColor = isRunning(info) ? Color.GREEN : Color.GRAY;
        lines.add(Line.from(List.of(
                Span.styled("Status:         ", labelStyle),
                Span.styled(info.status, Style.EMPTY.fg(statusColor).bg(ModalRenderer.BG)))));

        lines.add(Line.from(List.of(
                Span.styled("Type:           ", labelStyle),
                Span.styled(info.runtime, lineStyle))));

        if (!info.architecture.isEmpty()) {
            lines.add(Line.from(List.of(
                    Span.styled("Architecture:   ", labelStyle),
                    Span.styled(info.architecture, lineStyle))));
        }

        lines.add(Line.from(List.of(
                Span.styled("Parent:         ", labelStyle),
                Span.styled(info.parent.isEmpty() ? "-" : info.parent, lineStyle))));

        if (!info.created.isEmpty()) {
            var age = Metadata.ageDescription(info.created);
            lines.add(Line.from(List.of(
                    Span.styled("Created:        ", labelStyle),
                    Span.styled(info.created, lineStyle),
                    Span.styled("  (" + age + ")", dimStyle))));
        }

        lines.add(Line.styled("", lineStyle));

        var networkLabel = info.networkMode.isEmpty() ? "Full internet"
                : formatNetworkMode(info.networkMode);
        lines.add(Line.from(List.of(
                Span.styled("Network:        ", labelStyle),
                Span.styled(networkLabel, lineStyle))));

        lines.add(Line.from(List.of(
                Span.styled("IP address:     ", labelStyle),
                Span.styled(info.ipv4.isEmpty() ? "-" : info.ipv4, lineStyle))));

        lines.add(Line.styled("", lineStyle));
        lines.add(Line.from(List.of(Span.styled("Resource limits:", labelStyle))));

        lines.add(Line.from(List.of(
                Span.styled("  CPU:          ", labelStyle),
                Span.styled(info.limitsCpu.isEmpty() ? "-" : info.limitsCpu, lineStyle))));

        lines.add(Line.from(List.of(
                Span.styled("  Memory:       ", labelStyle),
                Span.styled(info.limitsMemory.isEmpty() ? "-" : info.limitsMemory, lineStyle))));

        lines.add(Line.from(List.of(
                Span.styled("  Disk:         ", labelStyle),
                Span.styled(info.rootSize.isEmpty() ? "-" : info.rootSize, lineStyle))));

        lines.add(Line.styled("", lineStyle));

        lines.add(Line.from(List.of(
                Span.styled("Project:        ", labelStyle),
                Span.styled(info.project, lineStyle))));

        lines.add(Line.from(List.of(
                Span.styled("Profile:        ", labelStyle),
                Span.styled(info.profile, lineStyle))));

        return lines;
    }

    private static String formatNetworkMode(String mode) {
        try {
            return NetworkMode.valueOf(mode).label();
        } catch (IllegalArgumentException e) {
            return mode;
        }
    }

    private List<dev.incusspawn.config.ImageDef> getInheritanceChain(String templateName) {
        var chain = new ArrayList<dev.incusspawn.config.ImageDef>();
        var current = imageDefs.get(templateName);
        while (current != null) {
            chain.add(0, current); // prepend so root is first
            if (current.isRoot()) break;
            current = imageDefs.get(current.getParent());
        }
        return chain;
    }

    private List<Line> buildCompactDetailLines(String templateName) {
        var chain = getInheritanceChain(templateName);
        if (chain.isEmpty()) return List.of();

        var lines = new ArrayList<Line>();
        var current = chain.get(chain.size() - 1);
        var lineStyle = Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG);
        var labelStyle = Style.EMPTY.fg(ModalRenderer.ACCENT).bg(ModalRenderer.BG);
        var dimStyle = Style.EMPTY.fg(Color.GRAY).bg(ModalRenderer.BG);

        // Description
        if (!current.getDescription().isEmpty()) {
            lines.add(Line.styled(current.getDescription(), lineStyle));
        }
        lines.add(Line.styled("", lineStyle));

        // Source
        lines.add(Line.from(List.of(
                Span.styled("Source:     ", labelStyle),
                Span.styled(current.getSource(), dimStyle))));

        // Base image
        var root = chain.get(0);
        lines.add(Line.from(List.of(
                Span.styled("Base image: ", labelStyle),
                Span.styled(root.getImage(), lineStyle))));

        // Inheritance chain
        if (chain.size() > 1) {
            var names = new ArrayList<String>();
            for (var def : chain) names.add(def.getName());
            lines.add(Line.from(List.of(
                    Span.styled("Inherits:   ", labelStyle),
                    Span.styled(String.join(" \u2192 ", names), lineStyle))));
        }
        lines.add(Line.styled("", lineStyle));

        // Collect all packages
        var allPackages = new ArrayList<String>();
        for (var def : chain) allPackages.addAll(def.getPackages());
        addDetailSection(lines, "Packages", allPackages, labelStyle, lineStyle, dimStyle);

        // Collect all tools
        var allTools = new ArrayList<String>();
        for (var def : chain) allTools.addAll(def.getTools());
        addDetailSection(lines, "Tools", allTools, labelStyle, lineStyle, dimStyle);

        // Collect auto-added dependencies (transitive requires not already in explicit list)
        var autoDeps = collectAutoDeps(allTools);
        if (!autoDeps.isEmpty()) {
            addDetailSection(lines, "Dependencies (auto)", autoDeps, labelStyle, lineStyle, dimStyle);
        }

        // Collect all repos
        var allRepos = new ArrayList<String>();
        for (var def : chain) {
            for (var repo : def.getRepos()) {
                var repoDesc = repo.getUrl() + " \u2192 " + repo.getPath();
                if (repo.getPrime() != null && !repo.getPrime().isBlank()) {
                    repoDesc += "  (prime: " + repo.getPrime() + ")";
                }
                allRepos.add(repoDesc);
            }
        }
        addDetailSection(lines, "Repos", allRepos, labelStyle, lineStyle, dimStyle);

        // Collect all host-resources
        var allHostResources = new ArrayList<String>();
        for (var def : chain) {
            for (var hr : def.getHostResources()) {
                var containerPath = HostResourceSetup.resolveContainerPath(hr.getSource(), hr.getPath());
                allHostResources.add(hr.getSource() + " → " + containerPath + "  (" + hr.getMode() + ")");
            }
        }
        addDetailSection(lines, "Host Resources", allHostResources, labelStyle, lineStyle, dimStyle);

        return lines;
    }

    private void addDetailSection(List<Line> lines, String label, List<String> items,
                                   Style labelStyle, Style lineStyle, Style dimStyle) {
        if (items.isEmpty()) {
            lines.add(Line.from(List.of(
                    Span.styled(label + ": ", labelStyle),
                    Span.styled("(none)", dimStyle))));
        } else {
            lines.add(Line.styled(label + ":", labelStyle));
            for (var item : items) {
                lines.add(Line.styled("  " + item, lineStyle));
            }
        }
        lines.add(Line.styled("", lineStyle));
    }

    private List<String> collectAutoDeps(List<String> explicitTools) {
        var explicit = new java.util.LinkedHashSet<>(explicitTools);
        var allDeps = new java.util.LinkedHashSet<String>();
        for (var toolName : explicitTools) {
            collectTransitiveDeps(toolName, allDeps, new java.util.HashSet<>());
        }
        allDeps.removeAll(explicit);
        return new ArrayList<>(allDeps);
    }

    private void collectTransitiveDeps(String name, java.util.Set<String> collected, java.util.Set<String> visiting) {
        if (collected.contains(name) || !visiting.add(name)) return;
        var tool = toolDefLoader.find(name);
        if (tool == null) return;
        for (var dep : tool.requires()) {
            collectTransitiveDeps(dep, collected, visiting);
            collected.add(dep);
        }
        visiting.remove(name);
    }

    private List<Line> buildTreeDetailLines(String templateName) {
        var chain = getInheritanceChain(templateName);
        if (chain.isEmpty()) return List.of();

        var lines = new ArrayList<Line>();
        var lineStyle = Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG);
        var labelStyle = Style.EMPTY.fg(ModalRenderer.ACCENT).bg(ModalRenderer.BG);
        var nameStyle = Style.EMPTY.bold().fg(ModalRenderer.ACCENT).bg(ModalRenderer.BG);
        var dimStyle = Style.EMPTY.fg(Color.GRAY).bg(ModalRenderer.BG);

        for (int i = 0; i < chain.size(); i++) {
            var def = chain.get(i);
            var indent = "  ".repeat(i);
            var connector = i == 0 ? "" : "\u2514 ";
            var contentIndent = i == 0 ? "  " : "  ".repeat(i) + "  ";

            // Name line
            var nameSpans = new ArrayList<Span>();
            if (!indent.isEmpty() || !connector.isEmpty()) {
                nameSpans.add(Span.styled(indent + connector, dimStyle));
            }
            nameSpans.add(Span.styled(def.getName(), nameStyle));
            if (def.isRoot()) {
                nameSpans.add(Span.styled("  " + def.getImage(), dimStyle));
            }
            lines.add(Line.from(nameSpans));

            // Source
            lines.add(Line.styled(contentIndent + def.getSource(), dimStyle));

            // Description
            if (!def.getDescription().isEmpty()) {
                lines.add(Line.styled(contentIndent + def.getDescription(), lineStyle));
            }

            // Packages
            if (!def.getPackages().isEmpty()) {
                lines.add(Line.from(List.of(
                        Span.styled(contentIndent + "Packages: ", labelStyle),
                        Span.styled(String.join(", ", def.getPackages()), lineStyle))));
            }

            // Tools
            if (!def.getTools().isEmpty()) {
                var toolSpans = new ArrayList<Span>();
                toolSpans.add(Span.styled(contentIndent + "Tools: ", labelStyle));
                toolSpans.add(Span.styled(String.join(", ", def.getTools()), lineStyle));
                var levelAutoDeps = collectAutoDeps(def.getTools());
                if (!levelAutoDeps.isEmpty()) {
                    toolSpans.add(Span.styled("  (+" + String.join(", ", levelAutoDeps) + ")", dimStyle));
                }
                lines.add(Line.from(toolSpans));
            }

            // Repos
            if (!def.getRepos().isEmpty()) {
                for (var repo : def.getRepos()) {
                    var repoSpans = new ArrayList<Span>();
                    repoSpans.add(Span.styled(contentIndent + "Repo: ", labelStyle));
                    repoSpans.add(Span.styled(repo.getUrl() + " \u2192 " + repo.getPath(), lineStyle));
                    if (repo.getPrime() != null && !repo.getPrime().isBlank()) {
                        repoSpans.add(Span.styled("  (prime: " + repo.getPrime() + ")", dimStyle));
                    }
                    lines.add(Line.from(repoSpans));
                }
            }

            // Host Resources
            if (!def.getHostResources().isEmpty()) {
                for (var hr : def.getHostResources()) {
                    var containerPath = HostResourceSetup.resolveContainerPath(hr.getSource(), hr.getPath());
                    lines.add(Line.from(List.of(
                            Span.styled(contentIndent + "Host: ", labelStyle),
                            Span.styled(hr.getSource() + " \u2192 " + containerPath, lineStyle),
                            Span.styled("  (" + hr.getMode() + ")", dimStyle))));
                }
            }

            if (i < chain.size() - 1) {
                lines.add(Line.styled("", lineStyle));
            }
        }

        return lines;
    }

    private String suggestBranchName(String sourceName) {
        var base = sourceName.startsWith("tpl-") ? sourceName.substring(4) : sourceName;
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
    private static final Color BAR_SEPARATOR_FG = Color.rgb(0, 140, 150);

    private KeyItem makeKey(String key, String label, boolean disabled) {
        var spans = new ArrayList<Span>();
        spans.add(Span.styled("│", Style.EMPTY.fg(BAR_SEPARATOR_FG).bg(BAR_BG)));
        if (disabled) {
            spans.add(Span.styled(key, Style.EMPTY.fg(BAR_DISABLED_FG).bg(BAR_BG)));
            spans.add(Span.styled(label, Style.EMPTY.fg(BAR_DISABLED_FG).bg(BAR_BG)));
        } else {
            spans.add(Span.styled(key, Style.EMPTY.bold().fg(BAR_KEY_FG).bg(BAR_BG)));
            spans.add(Span.styled(label, Style.EMPTY.fg(BAR_LABEL_FG).bg(BAR_BG)));
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

    // --- Helpers ---

    private static boolean isRunning(InstanceInfo entry) {
        return "RUNNING".equalsIgnoreCase(entry.status);
    }

    private void execWithFeedback(TuiRunner tui, TableState tableState, String progressVerb,
                                    String doneVerb, String failVerb, String name, Runnable action) {
        progressMessage = progressVerb + " " + name + "...";
        tui.draw(frame -> render(frame, tableState));
        try {
            action.run();
            statusMessage = doneVerb + " " + name;
        } catch (Exception e) {
            statusMessage = failVerb + " " + name;
        }
        progressMessage = null;
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

    private TemplateInfo selectedTemplate() {
        var idx = templateTableState != null ? templateTableState.selected() : null;
        if (idx == null || idx < 0 || idx >= templateEntries.size()) return null;
        return templateEntries.get(idx);
    }

    private void refreshData(TableState tableState) {
        // Remember selections by name
        var selectedInstance = selectedEntry(tableState);
        var selectedInstanceName = selectedInstance != null ? selectedInstance.name : null;
        var selectedTpl = selectedTemplate();
        var selectedTplName = selectedTpl != null ? selectedTpl.name : null;

        reloadData();

        // Restore template selection
        if (selectedTplName != null) {
            for (int i = 0; i < templateEntries.size(); i++) {
                if (templateEntries.get(i).name.equals(selectedTplName)) {
                    templateTableState.select(i);
                    break;
                }
            }
        }

        // Restore instance selection
        boolean reselected = false;
        if (selectedInstanceName != null) {
            for (int i = 0; i < rowToEntry.size(); i++) {
                if (rowToEntry.get(i) != null && rowToEntry.get(i).name.equals(selectedInstanceName)) {
                    tableState.select(i);
                    reselected = true;
                    break;
                }
            }
        }
        if (!reselected) selectFirstDataRow(tableState);
    }

    // --- Data ---

    private void buildTemplateRowData() {
        templateRows = new ArrayList<>();
        anyTemplateOutdated = false;
        anyDefinitionChanged = false;
        anyParentRebuilt = false;
        var defChanged = new java.util.HashSet<String>();
        var parentRebuilt = new java.util.HashSet<String>();

        var currentVersion = BuildInfo.instance().version();
        var toolFpCache = computeAllToolFingerprints();

        var timestamps = new java.util.HashMap<String, java.time.LocalDateTime>();
        for (var t : templateEntries) {
            if (!"not built".equals(t.buildStatus)) {
                var ts = parseTimestamp(t.buildStatus);
                if (ts != null) timestamps.put(t.name, ts);
            }
        }

        for (var t : templateEntries) {
            var statusDisplay = "not built".equals(t.buildStatus) ? "not built" : Metadata.ageDescription(t.buildStatus);
            var statusStyle = "not built".equals(t.buildStatus)
                    ? Style.EMPTY.fg(Color.GRAY)
                    : Style.EMPTY.fg(Color.GREEN);
            if (!"not built".equals(t.buildStatus)) {
                var symbols = new StringBuilder();
                if (!t.buildVersion.isEmpty() && !t.buildVersion.equals(currentVersion)) {
                    symbols.append('!');
                    anyTemplateOutdated = true;
                } else if (t.buildVersion.isEmpty()) {
                    symbols.append('!');
                    anyTemplateOutdated = true;
                }
                if (!t.definitionSha.isEmpty() && !storedSourceTemplates.contains(t.name)) {
                    var def = imageDefs.get(t.name);
                    if (def != null && !t.definitionSha.equals(def.contentFingerprint(toolFpCache))) {
                        symbols.append('△');
                        anyDefinitionChanged = true;
                        defChanged.add(t.name);
                    }
                }
                var def = imageDefs.get(t.name);
                if (def != null && !def.isRoot()) {
                    var parentTs = timestamps.get(def.getParent());
                    var childTs = timestamps.get(t.name);
                    if (parentTs != null && childTs != null && parentTs.isAfter(childTs)) {
                        symbols.append('↑');
                        anyParentRebuilt = true;
                        parentRebuilt.add(t.name);
                    }
                }
                if (!symbols.isEmpty()) {
                    statusDisplay += " " + symbols;
                    statusStyle = Style.EMPTY.fg(Color.YELLOW);
                }
            }
            var desc = t.description == null ? "" : t.description;
            templateRows.add(Row.from(t.name, statusDisplay, desc).style(statusStyle));
        }
        templatesDefChanged = defChanged;
        templatesParentRebuilt = parentRebuilt;
    }

    private java.util.Map<String, String> computeAllToolFingerprints() {
        var rawFps = new java.util.TreeMap<String, String>();
        var depMap = new java.util.TreeMap<String, java.util.List<String>>();
        var visited = new java.util.HashSet<String>();
        for (var def : imageDefs.values()) {
            for (var toolName : def.getTools()) {
                collectToolFps(toolName, rawFps, depMap, visited);
            }
        }
        return dev.incusspawn.tool.ToolDef.compositeFingerprints(rawFps, depMap);
    }

    private void collectToolFps(String name, java.util.Map<String, String> rawFps,
                                 java.util.Map<String, java.util.List<String>> depMap,
                                 java.util.Set<String> visited) {
        if (!visited.add(name)) return;
        var tool = toolDefLoader.find(name);
        if (tool instanceof YamlToolSetup yts) {
            for (var dep : yts.toolDef().getRequires()) {
                collectToolFps(dep, rawFps, depMap, visited);
            }
            rawFps.put(name, yts.toolDef().contentFingerprint());
            depMap.put(name, yts.toolDef().getRequires());
        }
    }

    private static java.time.LocalDateTime parseTimestamp(String ts) {
        try {
            if (ts.contains("T")) {
                return java.time.LocalDateTime.parse(ts, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            return java.time.LocalDate.parse(ts, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }

    private void buildRowData() {
        tableRows = new ArrayList<>();
        rowToEntry = new ArrayList<>();

        // Sort: running first, then stopped, alphabetically within each group
        var sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> {
            var aRunning = isRunning(a);
            var bRunning = isRunning(b);
            if (aRunning != bRunning) return aRunning ? -1 : 1;
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
            tableRows.add(Row.from(entry.name, entry.status, entry.ipv4,
                    parent, entry.runtime, age).style(statusStyle));
            rowToEntry.add(entry);
        }
    }

    private void createBranch(String source, String name, boolean gui, NetworkMode networkMode,
                               String inboxPath, boolean vm) {
        if (incus.exists(name)) {
            throw new RuntimeException("an instance named '" + name + "' already exists.");
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

        var hrJson = incus.configGet(name, Metadata.HOST_RESOURCES);
        var hostResources = HostResourceSetup.deserialize(hrJson);
        if (!hostResources.isEmpty()) {
            System.out.println("Applying host-resource devices...");
            HostResourceSetup.applyForInstance(incus, name, hostResources);
        }

        incus.start(name);
        waitForReady(name);

        if (networkMode == NetworkMode.PROXY_ONLY) {
            BranchCommand.applyProxyOnlyFirewall(incus, name);
        }

        if (gui && !configureGui(name)) {
            System.err.println("Continuing without GUI passthrough.");
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

        // Fix ownership of home dir itself (not recursively — files inside already
        // have correct ownership from the template, and -R would be very slow on
        // large images with many pre-built dependencies)
        incus.shellExec(name, "chown", String.valueOf(getUid()) + ":" + String.valueOf(getUid()), "/home/agentuser");

        incus.configSet(name, Metadata.PARENT, source);
        incus.configSet(name, Metadata.CREATED, Metadata.today());

        BranchCommand.injectSshKeyIfAvailable(incus, name);
        AutoRemoteService.addRemotes(incus, name);

        System.out.println("Branch '" + name + "' is ready.");
        System.out.println("Connecting to " + name + "...\n");
        incus.interactiveShell(name, "agentuser");
        System.out.println();
    }

    private boolean configureGui(String name) {
        var xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        var waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        if (xdgRuntimeDir == null || waylandDisplay == null) {
            System.err.println("Error: GUI passthrough requires WAYLAND_DISPLAY and XDG_RUNTIME_DIR.");
            System.err.println("Make sure you are running isx from a Wayland graphical session.");
            return false;
        }
        var hostSocket = xdgRuntimeDir + "/" + waylandDisplay;
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(hostSocket))) {
            System.err.println("Error: Wayland socket not found at " + hostSocket);
            System.err.println("Make sure you are running isx from a Wayland graphical session.");
            return false;
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
        return true;
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

    private boolean showProxyError() {
        var proxyStatus = ProxyHealthCheck.check(incus);
        if (proxyStatus == ProxyHealthCheck.ProxyStatus.RUNNING) return false;
        if (proxyStatus == ProxyHealthCheck.ProxyStatus.STALE_DNS) {
            ProxyHealthCheck.clearStaleDns(incus);
            errorMessage = "The MITM proxy is not running, but DNS overrides\n"
                    + "are still active from a previous session.\n"
                    + "\n"
                    + "Stale DNS overrides have been cleared.\n"
                    + "Start the proxy in a separate terminal:\n"
                    + "\n"
                    + "  isx proxy start";
        } else {
            errorMessage = "The MITM proxy is not running.\n"
                    + "\n"
                    + "The proxy provides authentication for Claude,\n"
                    + "GitHub, and caches Maven/Docker artifacts.\n"
                    + "\n"
                    + "Start it in a separate terminal:\n"
                    + "  isx proxy start\n"
                    + "\n"
                    + "Or install it as a service (auto-starts on boot):\n"
                    + "  isx init";
        }
        mode = Mode.ERROR;
        return true;
    }

    private boolean showProxyErrorIfNeeded(String containerName) {
        var networkModeStr = incus.configGet(containerName, Metadata.NETWORK_MODE);
        if (NetworkMode.AIRGAP.name().equals(networkModeStr)) return false;
        if (showProxyError()) return true;
        fixCaMismatchIfNeeded(containerName);
        return false;
    }

    private void fixCaMismatchIfNeeded(String containerName) {
        var info = incus.exec("list", containerName, "--format=csv", "--columns=s");
        if (info.success() && info.stdout().strip().equalsIgnoreCase("STOPPED")) {
            incus.start(containerName);
            waitForReady(containerName);
        }
        CertificateAuthority.fixContainerCaIfNeeded(incus, containerName);
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

                // Extract first global IPv4 address from state.network
                var ipv4 = "";
                var network = node.path("state").path("network");
                for (var ifaces = network.fields(); ifaces.hasNext(); ) {
                    var iface = ifaces.next();
                    if (iface.getKey().equals("lo")) continue;
                    for (var addr : iface.getValue().path("addresses")) {
                        if ("inet".equals(addr.path("family").asText())
                                && "global".equals(addr.path("scope").asText())) {
                            ipv4 = addr.path("address").asText();
                            break;
                        }
                    }
                    if (!ipv4.isEmpty()) break;
                }

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
                        rootSize,
                        ipv4,
                        configVal(config, Metadata.NETWORK_MODE, ""),
                        node.path("architecture").asText(""),
                        configVal(config, Metadata.BUILD_VERSION, ""),
                        configVal(config, Metadata.DEFINITION_SHA, ""),
                        type,
                        Metadata.TYPE_BASE.equals(type)
                                ? configVal(config, Metadata.BUILD_SOURCE, "") : ""));
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
        var fmt = "  %-" + nameWidth + "s  %-10s  %-15s  %-20s  %-10s  %s%n";

        System.out.printf(fmt, "NAME", "STATUS", "IP", "PARENT", "RUNTIME", "AGE");
        System.out.printf(fmt, "-".repeat(nameWidth), "----------", "---------------",
                "--------------------", "----------", "---");
        for (var entry : items) {
            var age = entry.created.isEmpty() ? "-" : Metadata.ageDescription(entry.created);
            var parent = entry.parent.isEmpty() ? "-" : entry.parent;
            var ip = entry.ipv4.isEmpty() ? "-" : entry.ipv4;
            System.out.printf(fmt, entry.name, entry.status, ip, parent, entry.runtime, age);
        }
        System.out.println();
    }

    private record TemplateInfo(String name, String description,
                                String buildStatus, String runtime, String buildVersion,
                                String definitionSha) {}

    private record InstanceInfo(String name, String status,
                                String project, String profile, String created,
                                String runtime, String parent,
                                String limitsCpu, String limitsMemory, String rootSize,
                                String ipv4, String networkMode, String architecture,
                                String buildVersion, String definitionSha,
                                String type, String buildSourceJson) {}
}
