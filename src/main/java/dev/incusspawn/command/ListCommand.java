package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.AutoRemoteService;
import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.ResourceLimits;
import dev.incusspawn.lifecycle.GuiPassthrough;
import dev.incusspawn.lifecycle.KvmPassthrough;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.lifecycle.InstanceType;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.MitmProxy;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tui.BackgroundTaskManager;
import dev.incusspawn.tui.InstanceLockManager;
import dev.incusspawn.tool.ToolAction;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.YamlToolAction;
import dev.incusspawn.tool.YamlToolSetup;
import dev.incusspawn.tui.ShiftTabBindings;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Flex;
import dev.tamboui.layout.Layout;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.input.TextInput;
import dev.tamboui.widgets.input.TextInputState;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import dev.tamboui.widgets.scrollbar.Scrollbar;
import dev.tamboui.widgets.scrollbar.ScrollbarOrientation;
import dev.tamboui.widgets.scrollbar.ScrollbarState;
import dev.tamboui.widgets.table.TableState;
import dev.incusspawn.RuntimeServices;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@CommandDefinition(
        name = "list",
        description = "List all incus-spawn environments",
        generateHelp = true
)
public class ListCommand extends BaseCommand {

    @Option(name = "plain", hasValue = false, description = "Plain text output (no TUI)")
    boolean plain;

    private IncusClient incus;

    private ToolDefLoader toolDefLoader;

    private BackgroundTaskManager backgroundTasks;

    private InstanceLockManager lockManager;

    // Background operation state
    private final AtomicBoolean needsRefresh = new AtomicBoolean(false);
    private final AtomicReference<String> pendingStatusMessage = new AtomicReference<>();
    private long lastRefreshTime = 0;
    private static final long REFRESH_DEBOUNCE_MS = 1000;
    private static final Duration TASK_DISPLAY_DURATION = Duration.ofSeconds(5);

    private enum Mode { BROWSE, CONFIRM_DELETE, CONFIRM_STOP_FOR_RENAME, CONFIRM_BUILD_FOR_BRANCH, BUILD_MENU, BRANCH, RENAME, TEMPLATE_DETAIL, INSTANCE_DETAIL, INFO, ERROR, ACTIONS }
    private Mode mode = Mode.BROWSE;
    private String errorMessage;
    private String pendingDeleteName;
    // Build menu state (computed once when F5 opens the menu)
    private record BuildMenuOption(String label, String description, String badge, String[] buildArgs, boolean enabled) {}
    private java.util.List<BuildMenuOption> buildMenuOptions;
    private int buildMenuSelectedIndex;
    private String[] pendingBuildArgs;
    // Branch modal state
    private String branchSourceName;
    private TextInputState branchNameInput;
    private boolean branchEnableGui;
    private boolean branchEnableKvm;
    private NetworkMode branchNetworkMode;
    private boolean branchEnableInbox;
    private TextInputState branchInboxInput;
    private boolean branchSourceIsVm;
    private TextInputState vmCpuInput;
    private TextInputState vmMemoryInput;
    private TextInputState vmDiskInput;
    private int branchFieldIndex;
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
    // Actions modal state
    private java.util.List<ToolAction> actionsList;
    private int actionsSelectedIndex;
    private int actionsScrollOffset;
    private ActionContext actionsContext;
    // Actions cache (computed once per data refresh, not per render)
    private java.util.Map<String, java.util.List<ToolAction>> actionsCache = new java.util.HashMap<>();

    private enum PendingAction { NONE, SHELL, BRANCH, BUILD_TEMPLATE, BUILD_THEN_BRANCH, EDIT_TEMPLATE, EXECUTE_ACTION }
    private PendingAction pendingAction = PendingAction.NONE;
    private String pendingActionTarget;
    private ToolAction pendingToolAction;
    private ActionContext pendingToolActionContext;
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
    private java.util.Set<String> templatesOutOfSync = java.util.Set.of();
    private java.util.Set<String> storedSourceTemplates = java.util.Set.of();
    private TableState templateTableState;

    // Instance panel data (bottom)
    private List<InstanceInfo> entries;
    private List<Row> tableRows;
    private List<InstanceInfo> rowToEntry;
    private TableState instanceTableState;

    public void executeDirect() {
        try { doExecute(); } catch (Exception e) { System.err.println("Error: " + e.getMessage()); }
    }

    @Override
    protected CommandResult doExecute() {
        this.incus = RuntimeServices.incus();
        this.toolDefLoader = RuntimeServices.toolDefLoader();
        this.backgroundTasks = RuntimeServices.backgroundTasks();
        this.lockManager = RuntimeServices.lockManager();
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
        return CommandResult.SUCCESS;
    }

    // --- TUI lifecycle ---

    private void runTuiLoop() {
        while (true) {
            reloadData();
            var previousAction = pendingAction;
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

            if (previousAction == PendingAction.BUILD_THEN_BRANCH) {
                var tpl = templateEntries.stream()
                        .filter(t -> t.name.equals(branchSourceName))
                        .findFirst().orElse(null);
                if (tpl != null && !"not built".equals(tpl.buildStatus)) {
                    openBranchModal(tpl.name, tpl.runtime);
                }
            }

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

            try (var runner = TuiRunner.create(TuiConfig.builder()
                    .bindings(ShiftTabBindings.createWithBacktab())
                    .tickRate(Duration.ofMillis(100))
                    .build())) {
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
                                branchEnableGui, branchEnableKvm, branchNetworkMode,
                                branchEnableInbox ? branchInboxInput.text().strip() : null,
                                branchSourceIsVm);
                        statusMessage = "Created branch " + pendingActionTarget;
                    } catch (Exception e) {
                        statusMessage = "Failed to create branch " + pendingActionTarget + ": " + e.getMessage();
                    }
                }
                case BUILD_TEMPLATE, BUILD_THEN_BRANCH -> {
                    var args = java.util.Arrays.copyOf(pendingBuildArgs, pendingBuildArgs.length + 1);
                    args[args.length - 1] = "--yes";
                    var buildTarget = pendingBuildArgs[0];
                    if (!buildTarget.startsWith("--")) {
                        returnToTemplate = buildTarget;
                    }
                    try {
                        var buildResult = org.aesh.AeshRuntimeRunner.builder().command(BuildCommand.class).args(args).execute();
                        int exitCode = buildResult != null ? buildResult.getResultValue() : 1;
                        statusMessage = exitCode == 0
                                ? buildStatusMessage(pendingBuildArgs, true)
                                : buildStatusMessage(pendingBuildArgs, false);
                        if (exitCode != 0) pendingAction = PendingAction.BUILD_TEMPLATE;
                    } catch (Exception e) {
                        statusMessage = "Build failed: " + e.getMessage();
                        pendingAction = PendingAction.BUILD_TEMPLATE;
                    }
                }
                case EDIT_TEMPLATE -> {
                    returnToTemplate = pendingActionTarget;
                    try { var editCmd = new TemplatesCommand.Edit(); editCmd.name = pendingActionTarget; editCmd.doExecute(); }
                    catch (Exception e) { statusMessage = "Edit failed: " + e.getMessage(); }
                }
                case EXECUTE_ACTION -> {
                    var result = pendingToolAction.execute(pendingToolActionContext);
                    System.out.println(result.message());
                    if (pendingToolAction instanceof YamlToolAction yamlAction && !yamlAction.shouldAutoReturn()) {
                        System.out.println("\nPress any key to continue...");
                        try {
                            System.in.read();
                        } catch (java.io.IOException ignored) {}
                    }
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

        // Stale metadata cleanup: if pending-op is set but no process holds the lock,
        // a previous process crashed — clear the stale marker.
        // Acquire the lock before clearing to avoid racing with another process.
        var clearedInstances = new java.util.HashSet<String>();
        for (var inst : allInstances) {
            if (!inst.pendingOp.isEmpty()
                    && !backgroundTasks.hasRunningTask(inst.name)
                    && !lockManager.isHeldByOther(inst.name)) {
                try {
                    var cleanupLock = lockManager.tryAcquire(inst.name, "cleanup");
                    if (cleanupLock.isPresent()) {
                        try (var lock = cleanupLock.get()) {
                            incus.clearPendingOperation(inst.name);
                            clearedInstances.add(inst.name);
                        }
                    }
                } catch (java.io.UncheckedIOException ignored) {}
            }
        }
        // Override pendingOp in allInstances for entries we just cleared,
        // so the UI doesn't render stale indicators until the next reload.
        if (!clearedInstances.isEmpty()) {
            allInstances = allInstances.stream()
                    .map(inst -> clearedInstances.contains(inst.name)
                            ? new InstanceInfo(inst.name, inst.status, inst.project, inst.profile,
                                    inst.created, inst.runtime, inst.parent, inst.limitsCpu,
                                    inst.limitsMemory, inst.rootSize, inst.ipv4, inst.networkMode,
                                    inst.architecture, inst.buildVersion, inst.definitionSha,
                                    inst.type, inst.buildSourceJson, "")
                            : inst)
                    .toList();
        }

        var loadWarnings = new ArrayList<String>();
        imageDefs = dev.incusspawn.config.ImageDef.loadAll(loadWarnings::add);
        if (!loadWarnings.isEmpty()) {
            statusMessage = loadWarnings.get(0);
        }

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
                        match.buildVersion, match.definitionSha, match.pendingOp));
                templateNames.add(name);
            } else {
                templateEntries.add(new TemplateInfo(name, def.getDescription(), "not built", "", "", "", ""));
            }
        }
        // Add out-of-scope templates (built but not in current definition scope)
        var storedNames = new java.util.HashSet<String>();
        for (var inst : allInstances) {
            if (templateNames.contains(inst.name)) continue;
            if (inst.name.endsWith(BuildCommand.REBUILDING_SUFFIX)) continue;
            if (!Metadata.TYPE_BASE.equals(inst.type)) continue;

            var buildSource = BuildSource.fromJson(inst.buildSourceJson);
            if (buildSource == null) continue;

            for (var entry : buildSource.getDefinitions().entrySet()) {
                imageDefs.putIfAbsent(entry.getKey(), entry.getValue());
            }
            toolDefLoader.addFallbacks(buildSource.getTools());

            templateEntries.add(new TemplateInfo(inst.name, buildSource.descriptionFor(inst.name),
                    inst.created.isEmpty() ? "built" : inst.created, inst.runtime,
                    inst.buildVersion, inst.definitionSha, inst.pendingOp));
            templateNames.add(inst.name);
            storedNames.add(inst.name);
        }
        storedSourceTemplates = storedNames;

        buildTemplateRowData();

        // Instance panel: exclude template instances (they're shown in the template panel)
        entries = new ArrayList<>();
        actionsCache = new java.util.HashMap<>();
        for (var inst : allInstances) {
            if (!templateNames.contains(inst.name)) {
                entries.add(inst);
                // Pre-compute actions for each instance
                actionsCache.put(inst.name, resolveActionsForInstance(inst));
            }
        }
        buildRowData();
    }

    // --- Event handling ---

    private boolean handleEvent(Event event, TuiRunner tui, TableState tableState) {
        if (event instanceof TickEvent) {
            return needsRefresh.get() || pendingStatusMessage.get() != null
                    || !backgroundTasks.getActiveTasks().isEmpty();
        }
        if (!(event instanceof KeyEvent key)) return false;
        return switch (mode) {
            case BROWSE -> handleBrowseEvent(key, tui, tableState);
            case CONFIRM_DELETE -> handleConfirmDeleteEvent(key, tui, tableState);
            case BUILD_MENU -> handleBuildMenuEvent(key, tui);
            case CONFIRM_STOP_FOR_RENAME -> handleConfirmStopForRenameEvent(key, tui, tableState);
            case CONFIRM_BUILD_FOR_BRANCH -> handleConfirmBuildForBranchEvent(key, tui);
            case BRANCH -> handleBranchEvent(key, tui, tableState);
            case RENAME -> handleRenameEvent(key, tui, tableState);
            case TEMPLATE_DETAIL -> handleTemplateDetailEvent(key, tui);
            case INSTANCE_DETAIL -> handleInstanceDetailEvent(key, tui);
            case INFO -> handleInfoEvent(key);
            case ERROR -> { mode = Mode.BROWSE; yield true; }
            case ACTIONS -> handleActionsEvent(key, tui);
        };
    }

    private boolean handleBrowseEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        // Global keys (both panels)
        if (key.isKey(KeyCode.F10) || key.isCtrlC() || (key.hasCtrl() && key.isChar('d'))
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
        // Tab or Shift+Tab: switch panels
        if (key.isKey(KeyCode.TAB) || ShiftTabBindings.isShiftTab(key)) {
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

        // Block all actions if there's a pending operation
        if (hasPendingOp(template) || backgroundTasks.hasRunningTask(template.name)) {
            statusMessage = "Operation in progress for " + template.name;
            return true;
        }

        // F5: Open build menu
        if (key.isKey(KeyCode.F5)) {
            if (checkBuildPreconditions(template.name)) return true;
            openBuildMenu(template);
            return true;
        }

        // Enter/F4: Branch from template (only if built)
        if (key.isKey(KeyCode.ENTER) || key.isKey(KeyCode.F4)) {
            if ("not built".equals(template.buildStatus)) {
                branchSourceName = template.name;
                mode = Mode.CONFIRM_BUILD_FOR_BRANCH;
                return true;
            }
            openBranchModal(template.name, template.runtime);
            return true;
        }

        // Shift+F8 or Shift+Delete: Destroy all built templates
        if ((key.isKey(KeyCode.F8) || key.isKey(KeyCode.DELETE)) && key.hasShift()) {
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

        // F8 or Delete: Destroy template
        if (key.isKey(KeyCode.F8) || key.isKey(KeyCode.DELETE)) {
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

        // F3: Show instance details (always accessible, even during operations)
        if (key.isKey(KeyCode.F3)) {
            instanceDetailScrollOffset = 0;
            mode = Mode.INSTANCE_DETAIL;
            return true;
        }

        // Shift+F8 or Shift+Delete: Destroy all instances
        if ((key.isKey(KeyCode.F8) || key.isKey(KeyCode.DELETE)) && key.hasShift()) {
            if (entries.isEmpty()) {
                statusMessage = "No instances to destroy.";
                return true;
            }
            pendingDeleteName = "--all-instances";
            mode = Mode.CONFIRM_DELETE;
            return true;
        }
        // Block actions that mutate the selected instance if there's a pending operation
        if (hasPendingOp(selected) || backgroundTasks.hasRunningTask(selected.name)) {
            statusMessage = "Operation in progress for " + selected.name;
            return true;
        }

        // F8 or Delete: Destroy instance
        if (key.isKey(KeyCode.F8) || key.isKey(KeyCode.DELETE)) {
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
        if (key.isKey(KeyCode.F4)) {
            openBranchModal(selected.name, selected.runtime);
            return true;
        }
        if (key.isKey(KeyCode.F7) && !key.hasShift() && isRunning(selected)) {
            execInBackground("Stopping " + selected.name,
                    "Stopped " + selected.name,
                    selected.name,
                    "Stopped " + selected.name,
                    Metadata.OP_STOPPING,
                    () -> incus.stop(selected.name));
            return true;
        }
        if (key.isKey(KeyCode.F7) && key.hasShift() && isRunning(selected)) {
            execInBackground("Restarting " + selected.name,
                    "Restarted " + selected.name,
                    selected.name,
                    "Restarted " + selected.name,
                    Metadata.OP_RESTARTING,
                    () -> incus.restart(selected.name));
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
        if (key.isKey(KeyCode.F9)) {
            var actions = getActionsForInstance(selected);
            if (actions.isEmpty()) {
                statusMessage = "No actions available for " + selected.name;
                return true;
            }
            actionsList = actions;
            actionsSelectedIndex = 0;
            actionsScrollOffset = 0;
            actionsContext = buildActionContext(selected);
            mode = Mode.ACTIONS;
            return true;
        }
        return false;
    }

    private void openBuildMenu(TemplateInfo template) {
        var options = new java.util.ArrayList<BuildMenuOption>();
        var def = imageDefs.get(template.name);
        boolean isBuilt = !"not built".equals(template.buildStatus);

        // Option 1: Build/Rebuild single template
        if (isBuilt) {
            options.add(new BuildMenuOption(
                    "Rebuild " + template.name,
                    "Deletes and rebuilds this template",
                    null, new String[]{template.name}, true));
        } else {
            options.add(new BuildMenuOption(
                    "Build " + template.name,
                    "Builds this template for the first time",
                    null, new String[]{template.name}, true));
        }

        // Option 2: Rebuild with parents (only for non-root templates)
        if (def != null && !def.isRoot()) {
            var chain = new java.util.ArrayList<String>();
            BuildCommand.collectAllRecursive(def, imageDefs, chain, new java.util.LinkedHashSet<>());
            var chainStr = String.join(" → ", chain);
            options.add(new BuildMenuOption(
                    "Rebuild " + template.name + " with parents",
                    "Rebuilds " + chainStr,
                    null, new String[]{template.name, "--with-parents"}, true));
        }

        // Option: Rebuild with descendants (only when template has descendants)
        if (def != null) {
            var descChain = new java.util.ArrayList<String>();
            var descSeen = new java.util.LinkedHashSet<String>();
            BuildCommand.collectDescendants(template.name, imageDefs, descChain, descSeen);
            if (!descChain.isEmpty()) {
                var fullChain = new java.util.ArrayList<String>();
                fullChain.add(template.name);
                fullChain.addAll(descChain);
                var chainStr = String.join(" → ", fullChain);
                options.add(new BuildMenuOption(
                        "Rebuild " + template.name + " with descendants",
                        "Rebuilds " + chainStr,
                        null, new String[]{template.name, "--with-descendants"}, true));
            }
        }

        // Option 3: Build missing templates (only if there are missing ones)
        long missingCount = templateEntries.stream()
                .filter(t -> "not built".equals(t.buildStatus)).count();
        if (missingCount > 0) {
            options.add(new BuildMenuOption(
                    "Build templates not yet built",
                    "Builds all templates that haven't been built yet",
                    missingCount + (missingCount == 1 ? " template" : " templates"),
                    new String[]{"--missing"}, true));
        }

        // Option 4: Rebuild out of sync templates (uses cached data from buildTemplateRowData)
        if (templatesOutOfSync.isEmpty()) {
            options.add(new BuildMenuOption(
                    "Rebuild out of sync templates",
                    "All templates match their current definitions",
                    "all in sync", new String[]{"--out-of-sync"}, false));
        } else {
            options.add(new BuildMenuOption(
                    "Rebuild out of sync templates",
                    "Rebuilds all templates whose definition changed\n"
                            + "since last build, or built with an older version",
                    templatesOutOfSync.size() + (templatesOutOfSync.size() == 1 ? " template" : " templates"),
                    new String[]{"--out-of-sync"}, true));
        }

        // Option 5: (Re)build all templates
        options.add(new BuildMenuOption(
                "(Re)build all templates",
                "Deletes and rebuilds every template",
                null, new String[]{"--all"}, true));

        buildMenuOptions = options;
        buildMenuSelectedIndex = 0;
        mode = Mode.BUILD_MENU;
    }

    private void openBranchModal(String sourceName, String runtime) {
        branchSourceName = sourceName;
        branchNameInput = new TextInputState(suggestBranchName(sourceName));
        var def = imageDefs.get(sourceName);
        branchEnableGui = (def != null && def.isGui())
                || "true".equals(incus.configGet(sourceName, Metadata.GUI_ENABLED));
        branchEnableKvm = false;
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
        // Space: toggle/cycle when on a toggle field
        if (key.code() == KeyCode.CHAR && key.character() == ' ' && isToggleField(branchFieldIndex)) {
            if (branchFieldIndex == guiFieldIndex()) {
                branchEnableGui = !branchEnableGui;
            } else if (branchFieldIndex == kvmFieldIndex()) {
                branchEnableKvm = !branchEnableKvm;
            } else if (branchFieldIndex == networkFieldIndex()) {
                branchNetworkMode = branchNetworkMode.next();
            } else if (branchFieldIndex == inboxFieldIndex()) {
                branchEnableInbox = !branchEnableInbox;
            }
            return true;
        }
        // Shift+Tab / Up: cycle backward (check Shift+Tab before Tab to avoid matching TAB+Shift)
        if (ShiftTabBindings.isShiftTab(key) || key.isKey(KeyCode.UP)) {
            int max = maxBranchField();
            branchFieldIndex = (branchFieldIndex - 1 + max + 1) % (max + 1);
            return true;
        }
        // Tab / Down: cycle forward
        if (key.isKey(KeyCode.TAB) || key.isKey(KeyCode.DOWN)) {
            branchFieldIndex = (branchFieldIndex + 1) % (maxBranchField() + 1);
            return true;
        }

        // Text-editing keys only apply to text input fields
        if (!isToggleField(branchFieldIndex)) {
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
                    if (Character.isLetterOrDigit(ch) || ch == '-') activeInput.insert(ch);
                } else if (branchFieldIndex == inboxPathFieldIndex()) {
                    if (Character.isLetterOrDigit(ch) || ch == '/' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                        activeInput.insert(ch);
                    }
                } else {
                    if (Character.isLetterOrDigit(ch)) activeInput.insert(ch);
                }
                return true;
            }
        }
        return true;
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
        return fieldIndex >= guiFieldIndex() && fieldIndex <= inboxFieldIndex();
    }

    private int maxBranchField() {
        return branchEnableInbox ? inboxPathFieldIndex() : inboxFieldIndex();
    }

    private TextInputState activeBranchInput() {
        if (branchFieldIndex == inboxPathFieldIndex() && branchEnableInbox) return branchInboxInput;
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
                var allNames = new java.util.ArrayList<>(imageDefs.keySet());
                java.util.Collections.reverse(allNames);
                int count = allNames.size();
                backgroundTasks.submit("Deleting " + count + " template(s)",
                        "Deleted " + count + " template(s)", null, () -> {
                    int destroyed = 0;
                    int skipped = 0;
                    for (var name : allNames) {
                        if (!backgroundTasks.tryClaim(name)) {
                            skipped++;
                            continue;
                        }
                        Optional<InstanceLockManager.LockHandle> lockOpt;
                        try {
                            lockOpt = lockManager.tryAcquire(name, Metadata.OP_DELETING);
                        } catch (java.io.UncheckedIOException e) {
                            backgroundTasks.releaseClaim(name);
                            setStatusMessage("Lock error for " + name + ": " + e.getCause().getMessage());
                            break;
                        }
                        if (lockOpt.isEmpty()) {
                            backgroundTasks.releaseClaim(name);
                            skipped++;
                            continue;
                        }
                        try (var lock = lockOpt.get()) {
                            if (incus.exists(name)) {
                                incus.setPendingOperation(name, Metadata.OP_DELETING);
                                refreshDataAfterBackground();
                                try {
                                    incus.delete(name, true);
                                    AutoRemoteService.removeRemotes(name, msg -> {});
                                    SshKeyManager.cleanupInstance(name);
                                    destroyed++;
                                } catch (Exception e) {
                                    setStatusMessage("Failed to destroy " + name + ": " + e.getMessage());
                                    incus.clearPendingOperation(name);
                                    refreshDataAfterBackground();
                                    break;
                                }
                            }
                        } finally {
                            backgroundTasks.releaseClaim(name);
                        }
                    }
                    String msg = "Destroyed " + destroyed + " template(s)";
                    if (skipped > 0) msg += " (" + skipped + " skipped, locked)";
                    if (destroyed > 0 || skipped > 0) setStatusMessage(msg);
                    refreshDataAfterBackground();
                });
            } else if ("--all-instances".equals(pendingDeleteName)) {
                var allEntries = new java.util.ArrayList<>(entries);
                int count = allEntries.size();
                backgroundTasks.submit("Deleting " + count + " instance(s)",
                        "Deleted " + count + " instance(s)", null, () -> {
                    int destroyed = 0;
                    int skipped = 0;
                    for (var entry : allEntries) {
                        if (!backgroundTasks.tryClaim(entry.name())) {
                            skipped++;
                            continue;
                        }
                        Optional<InstanceLockManager.LockHandle> lockOpt;
                        try {
                            lockOpt = lockManager.tryAcquire(entry.name(), Metadata.OP_DELETING);
                        } catch (java.io.UncheckedIOException e) {
                            backgroundTasks.releaseClaim(entry.name());
                            setStatusMessage("Lock error for " + entry.name() + ": " + e.getCause().getMessage());
                            break;
                        }
                        if (lockOpt.isEmpty()) {
                            backgroundTasks.releaseClaim(entry.name());
                            skipped++;
                            continue;
                        }
                        try (var lock = lockOpt.get()) {
                            incus.setPendingOperation(entry.name(), Metadata.OP_DELETING);
                            refreshDataAfterBackground();
                            try {
                                incus.delete(entry.name(), true);
                                AutoRemoteService.removeRemotes(entry.name(), msg -> {});
                                SshKeyManager.cleanupInstance(entry.name());
                                destroyed++;
                            } catch (Exception e) {
                                setStatusMessage("Failed to destroy " + entry.name() + ": " + e.getMessage());
                                incus.clearPendingOperation(entry.name());
                                refreshDataAfterBackground();
                                break;
                            }
                        } finally {
                            backgroundTasks.releaseClaim(entry.name());
                        }
                    }
                    String msg = "Destroyed " + destroyed + " instance(s)";
                    if (skipped > 0) msg += " (" + skipped + " skipped, locked)";
                    if (destroyed > 0 || skipped > 0) setStatusMessage(msg);
                    refreshDataAfterBackground();
                });
            } else {
                execInBackground("Deleting " + pendingDeleteName,
                        "Deleted " + pendingDeleteName,
                        pendingDeleteName,
                        "Destroyed " + pendingDeleteName,
                        Metadata.OP_DELETING,
                        () -> {
                            incus.delete(pendingDeleteName, true);
                            AutoRemoteService.removeRemotes(pendingDeleteName, msg -> {});
                            SshKeyManager.cleanupInstance(pendingDeleteName);
                        });
            }
        }
        mode = Mode.BROWSE;
        return true;
    }

    private boolean handleBuildMenuEvent(KeyEvent key, TuiRunner tui) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) {
            mode = Mode.BROWSE;
            return true;
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            for (int i = buildMenuSelectedIndex + 1; i < buildMenuOptions.size(); i++) {
                if (buildMenuOptions.get(i).enabled()) {
                    buildMenuSelectedIndex = i;
                    break;
                }
            }
            return true;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            for (int i = buildMenuSelectedIndex - 1; i >= 0; i--) {
                if (buildMenuOptions.get(i).enabled()) {
                    buildMenuSelectedIndex = i;
                    break;
                }
            }
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            var option = buildMenuOptions.get(buildMenuSelectedIndex);
            if (!option.enabled()) return true;
            executeBuildOption(option, tui);
            return true;
        }
        if (key.code() == KeyCode.CHAR && key.character() >= '1' && key.character() <= '9') {
            int index = key.character() - '1';
            if (index < buildMenuOptions.size()) {
                var option = buildMenuOptions.get(index);
                if (!option.enabled()) return true;
                buildMenuSelectedIndex = index;
                executeBuildOption(option, tui);
            }
            return true;
        }
        return false;
    }

    private void executeBuildOption(BuildMenuOption option, TuiRunner tui) {
        pendingAction = PendingAction.BUILD_TEMPLATE;
        pendingBuildArgs = option.buildArgs();
        mode = Mode.BROWSE;
        tui.quit();
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

    private boolean handleConfirmBuildForBranchEvent(KeyEvent key, TuiRunner tui) {
        if (key.isChar('y') || key.isChar('Y') || key.isKey(KeyCode.ENTER)) {
            if (checkBuildPreconditions(branchSourceName)) {
                mode = Mode.BROWSE;
                return true;
            }
            pendingAction = PendingAction.BUILD_THEN_BRANCH;
            pendingBuildArgs = new String[]{branchSourceName};
            returnToTemplate = branchSourceName;
            mode = Mode.BROWSE;
            tui.quit();
        } else {
            mode = Mode.BROWSE;
        }
        return true;
    }

    private boolean checkBuildPreconditions(String templateName) {
        if (showProxyError()) return true;
        var def = imageDefs.get(templateName);
        if (def != null) {
            var credError = dev.incusspawn.config.SpawnConfig.checkCredentials(def, imageDefs, incus::exists);
            if (!credError.isEmpty()) {
                statusMessage = credError;
                return true;
            }
        }
        return false;
    }

    // --- Rendering ---

    private void render(dev.tamboui.terminal.Frame frame, TableState tableState) {
        // Apply background task state BEFORE layout/rendering so this frame uses fresh data
        backgroundTasks.cleanupCompleted(TASK_DISPLAY_DURATION);
        if (needsRefresh.get()) {
            long now = System.currentTimeMillis();
            if (now - lastRefreshTime > REFRESH_DEBOUNCE_MS) {
                needsRefresh.set(false);
                refreshData(tableState);
                lastRefreshTime = now;
            }
        }
        String pending = pendingStatusMessage.getAndSet(null);
        if (pending != null) {
            statusMessage = pending;
        }

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
            var highlightStyle = Style.EMPTY.bg(Color.DARK_GRAY).fg(Color.WHITE);
            // Preserve modifiers from selected row if it has a pending operation
            var selected = selectedTemplate();
            if (selected != null && !selected.pendingOp.isEmpty()) {
                if (Metadata.OP_DELETING.equals(selected.pendingOp)) {
                    highlightStyle = highlightStyle.addModifier(dev.tamboui.style.Modifier.DIM)
                            .addModifier(dev.tamboui.style.Modifier.ITALIC);
                } else if (Metadata.OP_STOPPING.equals(selected.pendingOp) || Metadata.OP_RESTARTING.equals(selected.pendingOp)) {
                    highlightStyle = highlightStyle.addModifier(dev.tamboui.style.Modifier.DIM);
                }
            }
            tableBuilder.highlightStyle(highlightStyle);
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
            var highlightStyle = Style.EMPTY.bg(Color.DARK_GRAY).fg(Color.WHITE);
            // Preserve modifiers from selected row if it has a pending operation
            var selected = selectedEntry(tableState);
            if (selected != null && !selected.pendingOp.isEmpty()) {
                if (Metadata.OP_DELETING.equals(selected.pendingOp)) {
                    highlightStyle = highlightStyle.addModifier(dev.tamboui.style.Modifier.DIM)
                            .addModifier(dev.tamboui.style.Modifier.ITALIC);
                } else if (Metadata.OP_STOPPING.equals(selected.pendingOp) || Metadata.OP_RESTARTING.equals(selected.pendingOp)) {
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
        items.add(makeKey("F5", "Build…", !hasTemplate || !onTemplates));
        items.add(makeKey("F6", "Rename\u2026", !hasInstance || onTemplates));
        items.add(makeKey("F7", "Stop", !running || onTemplates));
        items.add(makeKey("F8", "Destroy\u2026", onTemplates ? !isBuilt : !hasInstance));
        boolean hasActions = hasInstance && !onTemplates && hasActionsForInstance(selected);
        items.add(makeKey("F9", "Actions", !hasActions));
        items.add(makeKey("F10", "Quit", false));

        var contextLine = buildContextLine(template, selected, onTemplates);

        if (hasStatus) {
            var rows = splitVertical(area, 1, 1, 1);
            var singleLine = statusMessage.replaceAll("[\\n\\r]+", " ").strip();
            var isError = singleLine.startsWith("Failed") || singleLine.startsWith("Invalid")
                    || singleLine.startsWith("Template");
            var statusBg = Color.rgb(0, 0, 80);
            var msgFg = isError ? Color.LIGHT_RED : Color.WHITE;
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.styled(" " + singleLine,
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

        // Reserve right side for background tasks if any exist
        var tasks = backgroundTasks.getActiveTasks();
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
                    Style.EMPTY.fg(Color.YELLOW).bg(CONTEXT_BG)));
            for (var task : running.stream().limit(2).toList()) {
                spans.add(Span.styled(" " + task.displayName() + "... ",
                        Style.EMPTY.fg(Color.WHITE).bg(CONTEXT_BG)));
            }
            if (running.size() > 2) {
                spans.add(Span.styled(" +" + (running.size() - 2) + " more ",
                        Style.EMPTY.fg(Color.GRAY).bg(CONTEXT_BG)));
            }
        }

        // Show completed tasks
        for (var task : completed) {
            if (task instanceof dev.incusspawn.tui.BackgroundTask.Completed completedTask) {
                var symbol = task.status() == dev.incusspawn.tui.BackgroundTask.TaskStatus.SUCCESS ? "✓" : "✗";
                var color = task.status() == dev.incusspawn.tui.BackgroundTask.TaskStatus.SUCCESS ? Color.GREEN : Color.RED;
                spans.add(Span.styled(" " + symbol + " " + completedTask.getDisplayText() + " ",
                        Style.EMPTY.fg(color).bg(CONTEXT_BG)));
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

    // --- Modal dialogs (centered overlay) ---

    private void renderModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen,
                              TableState tableState) {
        ModalRenderer.renderScrim(frame, screen);
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
            case BUILD_MENU -> renderBuildMenu(frame, screen);
            case CONFIRM_BUILD_FOR_BRANCH -> {
                ModalRenderer.renderConfirmModal(frame, screen,
                        " Branch from '" + branchSourceName + "' ",
                        "Template is not built yet. Build it first?", ModalRenderer.BORDER,
                        "Build", "y/Enter");
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
            case ACTIONS -> renderActionsModal(frame, screen);
            case ERROR -> ModalRenderer.renderErrorModal(frame, screen, errorMessage);
            default -> {}
        }
    }

    private void renderBranchModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        int height = 9;
        if (branchSourceIsVm) height += 2;
        var modalArea = ModalRenderer.centerRect(screen, 54, height);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(ModalRenderer.styledTitle(" Branch from '" + branchSourceName + "' ", ModalRenderer.BORDER))
                .borderStyle(Style.EMPTY.fg(ModalRenderer.BORDER))
                .style(Style.EMPTY.bg(ModalRenderer.BG))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
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
        constraints.add(Constraint.length(1));
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
        ModalRenderer.renderToggle(frame, rows.get(row++), "GUI passthrough", branchEnableGui, branchFieldIndex == guiFieldIndex());
        ModalRenderer.renderToggle(frame, rows.get(row++), "KVM passthrough", branchEnableKvm, branchFieldIndex == kvmFieldIndex());
        ModalRenderer.renderNetworkModeRadio(frame, rows.get(row++), branchNetworkMode, branchFieldIndex == networkFieldIndex());
        renderInboxField(frame, rows.get(row++));

        var hintSpans = new ArrayList<Span>();
        ModalRenderer.addKey(hintSpans, "Enter", "Confirm");
        ModalRenderer.addKey(hintSpans, "Esc", "Cancel");
        ModalRenderer.addKey(hintSpans, "↑↓/Tab", "Navigate");
        ModalRenderer.addKey(hintSpans, "Space", "Toggle");
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

    private void renderInboxField(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        boolean toggleFocused = branchFieldIndex == inboxFieldIndex();
        boolean pathFocused = branchFieldIndex == inboxPathFieldIndex();
        var check = branchEnableInbox ? "☑" : "☐";
        var checkColor = branchEnableInbox ? Color.GREEN : Color.GRAY;
        var prefix = toggleFocused ? "▸" : " ";
        var labelColor = toggleFocused ? Color.WHITE : ModalRenderer.FG;

        int labelWidth = 18;
        var labelArea = new dev.tamboui.layout.Rect(area.x(), area.y(), labelWidth, 1);
        frame.renderWidget(Paragraph.from(Line.from(List.of(
                Span.styled(" " + prefix + " ", Style.EMPTY.fg(ModalRenderer.ACCENT).bg(ModalRenderer.BG)),
                Span.styled(check + " ", Style.EMPTY.fg(checkColor).bg(ModalRenderer.BG)),
                Span.styled("Inbox", Style.EMPTY.fg(labelColor).bg(ModalRenderer.BG))))), labelArea);

        var pathArea = new dev.tamboui.layout.Rect(
                area.x() + labelWidth, area.y(), area.width() - labelWidth, 1);
        if (pathFocused && branchEnableInbox) {
            TextInput.builder()
                    .placeholder("/path/to/dir")
                    .style(Style.EMPTY.fg(Color.WHITE).bg(ModalRenderer.INPUT_BG))
                    .build()
                    .renderWithCursor(pathArea, frame.buffer(), branchInboxInput, frame);
        } else {
            var display = branchInboxInput.text().isEmpty() ? "/path/to/dir" : branchInboxInput.text();
            var inputBg = branchEnableInbox ? ModalRenderer.INPUT_BG : ModalRenderer.INPUT_INACTIVE_BG;
            var fg = branchInboxInput.text().isEmpty() ? ModalRenderer.PLACEHOLDER_FG
                    : branchEnableInbox ? Color.GRAY : ModalRenderer.PLACEHOLDER_FG;
            frame.renderWidget(Paragraph.from(Line.styled(display, Style.EMPTY.fg(fg).bg(inputBg))), pathArea);
        }
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
        // Tab or Shift+Tab: toggle view mode
        if (key.isKey(KeyCode.TAB) || ShiftTabBindings.isShiftTab(key)) {
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

    private boolean handleActionsEvent(KeyEvent key, TuiRunner tui) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC() || key.isKey(KeyCode.F9)) {
            mode = Mode.BROWSE;
            return true;
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            if (actionsSelectedIndex < actionsList.size() - 1) {
                actionsSelectedIndex++;
            }
            return true;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            if (actionsSelectedIndex > 0) {
                actionsSelectedIndex--;
            }
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            var action = actionsList.get(actionsSelectedIndex);
            // Commands need deferred execution (quit TUI, run, optionally wait for key)
            if (action instanceof YamlToolAction yamlAction && yamlAction.needsDeferredExecution()) {
                pendingAction = PendingAction.EXECUTE_ACTION;
                pendingToolAction = action;
                pendingToolActionContext = actionsContext;
                mode = Mode.BROWSE;
                tui.quit();
                return true;
            }
            // Inline execution for URL and copy-to-clipboard
            var result = action.execute(actionsContext);
            statusMessage = result.message();
            mode = Mode.BROWSE;
            return true;
        }
        return false;
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
                shortcutRow("F10", "Quit", null, null));

        int width = 60;
        int maxHeight = screen.height() - 2;
        int modalHeight = Math.min(lines.size() + 4, maxHeight);

        var modalArea = ModalRenderer.centerRect(screen, width, modalHeight);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(ModalRenderer.styledTitle(" About incus-spawn ", ModalRenderer.BORDER))
                .borderStyle(Style.EMPTY.fg(ModalRenderer.BORDER))
                .style(Style.EMPTY.bg(ModalRenderer.BG))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
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

    private static String buildStatusMessage(String[] args, boolean success) {
        var firstArg = args[0];
        boolean hasWithParents = java.util.Arrays.asList(args).contains("--with-parents");
        boolean hasWithDescendants = java.util.Arrays.asList(args).contains("--with-descendants");
        if (firstArg.equals("--all")) {
            return success ? "Rebuilt all templates successfully" : "Some templates failed to build";
        } else if (firstArg.equals("--out-of-sync")) {
            return success ? "Rebuilt out of sync templates successfully" : "Some templates failed to build";
        } else if (firstArg.equals("--missing")) {
            return success ? "Built missing templates successfully" : "Some templates failed to build";
        } else if (hasWithParents) {
            return success ? "Rebuilt " + firstArg + " with parents successfully"
                    : "Failed to build " + firstArg + " with parents";
        } else if (hasWithDescendants) {
            return success ? "Rebuilt " + firstArg + " with descendants successfully"
                    : "Failed to build " + firstArg + " with descendants";
        } else {
            return success ? "Built " + firstArg + " successfully"
                    : "Failed to build " + firstArg
                            + ". Check instance '" + firstArg + "-failed-build' for inspection.";
        }
    }

    private static Line shortcutRow(String key, String desc, String shiftKey, String shiftDesc) {
        var spans = new ArrayList<Span>();
        var keyStr = key != null ? key : "";
        var descStr = desc != null ? desc : "";
        spans.add(Span.styled(String.format("  %-8s", keyStr), Style.EMPTY.bold().fg(ModalRenderer.ACCENT).bg(ModalRenderer.BG)));
        spans.add(Span.styled(String.format("%-18s", descStr), Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG)));
        if (shiftKey != null) {
            spans.add(Span.styled(String.format("%-9s", shiftKey), Style.EMPTY.bold().fg(ModalRenderer.ACCENT).bg(ModalRenderer.BG)));
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
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(ModalRenderer.styledTitle(" " + template.name + " \u2014 " + viewLabel + " ", ModalRenderer.BORDER))
                .borderStyle(Style.EMPTY.fg(ModalRenderer.BORDER))
                .style(Style.EMPTY.bg(ModalRenderer.BG))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
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
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(ModalRenderer.styledTitle(" " + selected.name + " ", ModalRenderer.BORDER))
                .borderStyle(Style.EMPTY.fg(ModalRenderer.BORDER))
                .style(Style.EMPTY.bg(ModalRenderer.BG))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
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
        var allToolsFormatted = new ArrayList<String>();
        var allToolNames = new ArrayList<String>();
        for (var def : chain) {
            for (var toolRef : def.getTools()) {
                allToolsFormatted.add(formatToolWithParams(toolRef));
                allToolNames.add(toolRef.getName());
            }
        }
        addDetailSection(lines, "Tools", allToolsFormatted, labelStyle, lineStyle, dimStyle);

        // Collect auto-added dependencies (transitive requires not already in explicit list)
        var autoDeps = collectAutoDeps(allToolNames);
        if (!autoDeps.isEmpty()) {
            addDetailSection(lines, "Dependencies (auto)", autoDeps, labelStyle, lineStyle, dimStyle);
        }

        // Collect all repos
        var spawnConfig = SpawnConfig.load();
        var allRepos = new ArrayList<dev.incusspawn.config.ImageDef.RepoEntry>();
        for (var def : chain) allRepos.addAll(def.getRepos());
        if (allRepos.isEmpty()) {
            lines.add(Line.from(List.of(
                    Span.styled("Repos: ", labelStyle),
                    Span.styled("(none)", dimStyle))));
        } else {
            lines.add(Line.styled("Repos:", labelStyle));
            for (var repo : allRepos) {
                lines.add(Line.styled("  " + repo.getUrl() + " \u2192 " + repo.getPath(), lineStyle));
                if (repo.getPrime() != null && !repo.getPrime().isBlank()) {
                    lines.add(Line.from(List.of(
                            Span.styled("    prime: ", labelStyle),
                            Span.styled(repo.getPrime(), lineStyle))));
                }
                var hostMatch = resolveHostRepoMatch(repo.getUrl(), spawnConfig);
                lines.add(Line.styled(hostMatch != null
                        ? "    Linked to host repository at " + hostMatch
                        : "    No matching host checkout found", dimStyle));
            }
        }
        lines.add(Line.styled("", lineStyle));

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

    private String formatToolWithParams(dev.incusspawn.tool.ToolDef.ToolRef toolRef) {
        if (toolRef.getParams().isEmpty()) {
            return toolRef.getName();
        }
        var paramStr = toolRef.getParams().entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .map(e -> e.getKey() + ": " + e.getValue())
            .collect(java.util.stream.Collectors.joining(", "));
        return toolRef.getName() + " (" + paramStr + ")";
    }

    private static java.nio.file.Path resolveHostRepoMatch(String cloneUrl, SpawnConfig config) {
        try {
            var repoName = GitRemoteUtils.repoNameFromUrl(cloneUrl);
            if (repoName.isEmpty()) return null;
            var hostPath = GitRemoteUtils.resolveHostRepoPath(repoName, config);
            if (hostPath == null || !java.nio.file.Files.isDirectory(hostPath) || !GitRemoteUtils.isGitRepo(hostPath))
                return null;
            return GitRemoteUtils.anyRemoteMatches(hostPath, cloneUrl) ? hostPath : null;
        } catch (IllegalStateException e) {
            return null;
        }
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

    private boolean hasActionsForInstance(InstanceInfo instance) {
        return !getActionsForInstance(instance).isEmpty();
    }

    private java.util.List<ToolAction> getActionsForInstance(InstanceInfo instance) {
        return actionsCache.getOrDefault(instance.name, java.util.List.of());
    }

    private void addActionIfAllowed(java.util.List<ToolAction> actions,
                                     ToolAction action,
                                     InstanceInfo instance) {
        if (!action.requiresRunning() || isRunning(instance)) {
            actions.add(action);
        }
    }

    private java.util.List<ToolAction> resolveActionsForInstance(InstanceInfo instance) {
        var actions = new ArrayList<ToolAction>();
        var tools = collectInstalledTools(instance);
        java.util.List<ActionContext.RepoInfo> repos = null;

        // YAML-declared actions
        for (var toolName : tools) {
            var setup = toolDefLoader.find(toolName);
            if (setup instanceof YamlToolSetup yts) {
                var toolDef = yts.toolDef();
                for (var entry : toolDef.getActions()) {
                    if (YamlToolAction.EXPAND_REPOS.equals(entry.getExpand())) {
                        // Expand per repo (compute repos once)
                        if (repos == null) repos = collectRepos(instance);
                        for (var repo : repos) {
                            var action = new YamlToolAction(toolName, entry, repo);
                            addActionIfAllowed(actions, action, instance);
                        }
                    } else {
                        // Single action
                        var action = new YamlToolAction(toolName, entry);
                        addActionIfAllowed(actions, action, instance);
                    }
                }
            }
        }

        return actions;
    }

    private java.util.Set<String> collectInstalledTools(InstanceInfo instance) {
        var tools = new java.util.LinkedHashSet<String>();
        var parent = instance.parent;
        if (parent == null || parent.isEmpty() || "-".equals(parent)) return tools;
        var chain = getInheritanceChain(parent);
        for (var def : chain) {
            for (var toolRef : def.getTools()) {
                tools.add(toolRef.getName());
            }
        }
        // Add transitive deps
        var allDeps = new java.util.LinkedHashSet<String>();
        for (var toolName : new ArrayList<>(tools)) {
            collectTransitiveDeps(toolName, allDeps, new java.util.HashSet<>());
        }
        tools.addAll(allDeps);
        return tools;
    }

    private java.util.List<ActionContext.RepoInfo> collectRepos(InstanceInfo instance) {
        var repos = new ArrayList<ActionContext.RepoInfo>();
        var parent = instance.parent;
        if (parent == null || parent.isEmpty() || "-".equals(parent)) return repos;
        var chain = getInheritanceChain(parent);
        for (var def : chain) {
            for (var repo : def.getRepos()) {
                var repoPath = repo.getPath().startsWith("~/")
                        ? "/home/agentuser" + repo.getPath().substring(1) : repo.getPath();
                var name = repoPath.substring(repoPath.lastIndexOf('/') + 1);
                repos.add(new ActionContext.RepoInfo(name, repoPath, repo.getUrl()));
            }
        }
        return repos;
    }

    private ActionContext buildActionContext(InstanceInfo instance) {
        var tools = collectInstalledTools(instance);
        var repos = collectRepos(instance);
        return new ActionContext(
                instance.name, instance.ipv4, instance.status,
                instance.parent, tools, instance.networkMode, repos);
    }

    private List<Line> buildTreeDetailLines(String templateName) {
        var chain = getInheritanceChain(templateName);
        if (chain.isEmpty()) return List.of();

        var lines = new ArrayList<Line>();
        var lineStyle = Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG);
        var labelStyle = Style.EMPTY.fg(ModalRenderer.ACCENT).bg(ModalRenderer.BG);
        var nameStyle = Style.EMPTY.bold().fg(ModalRenderer.ACCENT).bg(ModalRenderer.BG);
        var dimStyle = Style.EMPTY.fg(Color.GRAY).bg(ModalRenderer.BG);
        var spawnConfig = SpawnConfig.load();

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
                var toolNames = def.getTools().stream()
                    .map(dev.incusspawn.tool.ToolDef.ToolRef::getName)
                    .collect(java.util.stream.Collectors.toList());
                var toolDisplay = def.getTools().stream()
                    .map(this::formatToolWithParams)
                    .collect(java.util.stream.Collectors.toList());
                toolSpans.add(Span.styled(String.join(", ", toolDisplay), lineStyle));
                var levelAutoDeps = collectAutoDeps(toolNames);
                if (!levelAutoDeps.isEmpty()) {
                    toolSpans.add(Span.styled("  (+" + String.join(", ", levelAutoDeps) + ")", dimStyle));
                }
                lines.add(Line.from(toolSpans));
            }

            // Repos
            if (!def.getRepos().isEmpty()) {
                for (var repo : def.getRepos()) {
                    lines.add(Line.from(List.of(
                            Span.styled(contentIndent + "Repo: ", labelStyle),
                            Span.styled(repo.getUrl() + " \u2192 " + repo.getPath(), lineStyle))));
                    if (repo.getPrime() != null && !repo.getPrime().isBlank()) {
                        lines.add(Line.from(List.of(
                                Span.styled(contentIndent + "  prime: ", labelStyle),
                                Span.styled(repo.getPrime(), lineStyle))));
                    }
                    var hostMatch = resolveHostRepoMatch(repo.getUrl(), spawnConfig);
                    lines.add(Line.styled(contentIndent + (hostMatch != null
                            ? "  Linked to host repository at " + hostMatch
                            : "  No matching host checkout found"), dimStyle));
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

    private void renderBuildMenu(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        if (buildMenuOptions == null || buildMenuOptions.isEmpty()) return;

        var lines = new ArrayList<Line>();
        for (int i = 0; i < buildMenuOptions.size(); i++) {
            var opt = buildMenuOptions.get(i);
            var selected = (i == buildMenuSelectedIndex);
            var prefix = selected ? " ▶ " : "   ";
            var numPrefix = "[" + (i + 1) + "] ";

            var labelStyle = !opt.enabled()
                    ? Style.EMPTY.fg(Color.GRAY).bg(ModalRenderer.BG)
                    : selected
                        ? Style.EMPTY.bold().fg(Color.WHITE).bg(ModalRenderer.BG)
                        : Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG);
            var spans = new ArrayList<Span>();
            spans.add(Span.styled(prefix + numPrefix + opt.label(), labelStyle));
            if (opt.badge() != null) {
                var badgeStyle = Style.EMPTY.fg(opt.enabled() ? ModalRenderer.ACCENT : Color.GRAY).bg(ModalRenderer.BG);
                spans.add(Span.styled("  " + opt.badge(), badgeStyle));
            }
            lines.add(Line.from(spans));

            // Description lines (may be multi-line via \n)
            var descStyle = Style.EMPTY.fg(Color.GRAY).bg(ModalRenderer.BG);
            for (var descLine : opt.description().split("\n")) {
                lines.add(Line.styled("       " + descLine, descStyle));
            }

            // Blank separator between options
            if (i < buildMenuOptions.size() - 1) {
                lines.add(Line.styled("", Style.EMPTY.bg(ModalRenderer.BG)));
            }
        }

        int modalWidth = Math.min(60, screen.width() - 4);
        int modalHeight = Math.min(lines.size() + 4, screen.height() - 2);

        var modalArea = ModalRenderer.centerRect(screen, modalWidth, modalHeight);
        var block = dev.tamboui.widgets.block.Block.builder()
                .borders(dev.tamboui.widgets.block.Borders.ALL)
                .borderType(dev.tamboui.widgets.block.BorderType.DOUBLE)
                .title(ModalRenderer.styledTitle(" Build Templates ", ModalRenderer.BORDER))
                .borderStyle(Style.EMPTY.fg(ModalRenderer.BORDER))
                .style(Style.EMPTY.bg(ModalRenderer.BG))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        ModalRenderer.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var rows = dev.tamboui.layout.Layout.vertical()
                .constraints(dev.tamboui.layout.Constraint.length(1),
                        dev.tamboui.layout.Constraint.fill(),
                        dev.tamboui.layout.Constraint.length(1))
                .split(inner);

        // Top spacing
        frame.renderWidget(dev.tamboui.widgets.paragraph.Paragraph.from(
                Line.styled("", Style.EMPTY.bg(ModalRenderer.BG))), rows.get(0));

        renderScrollableContent(frame, rows.get(1), lines, 0);

        var hintSpans = new ArrayList<Span>();
        ModalRenderer.addKey(hintSpans, "1-" + buildMenuOptions.size(), "Select");
        ModalRenderer.addKey(hintSpans, "Enter", "Build");
        ModalRenderer.addKey(hintSpans, "Esc", "Cancel");
        frame.renderWidget(dev.tamboui.widgets.paragraph.Paragraph.from(Line.from(hintSpans)), rows.get(2));
    }

    private void renderActionsModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        if (actionsList == null || actionsList.isEmpty()) return;

        var lines = new ArrayList<Line>();
        for (int i = 0; i < actionsList.size(); i++) {
            var action = actionsList.get(i);
            var selected = (i == actionsSelectedIndex);
            var prefix = selected ? " > " : "   ";
            var style = selected
                    ? Style.EMPTY.bold().fg(Color.WHITE).bg(ModalRenderer.BG)
                    : Style.EMPTY.fg(ModalRenderer.FG).bg(ModalRenderer.BG);
            if (action instanceof YamlToolAction ya && ya.isUrl()) {
                var url = ya.resolveUrl(actionsContext);
                if (url != null && !url.isBlank()) {
                    style = style.hyperlink(url);
                }
            }
            var toolStyle = Style.EMPTY.fg(Color.GRAY).bg(ModalRenderer.BG);
            lines.add(Line.from(List.of(
                    Span.styled(prefix + action.label(), style),
                    Span.styled("  (" + action.toolName() + ")", toolStyle))));
        }

        int modalWidth = Math.min(80, screen.width() - 4);
        int modalHeight = Math.min(lines.size() + 4, screen.height() - 2);

        var instanceName = actionsContext != null ? actionsContext.instanceName() : "";
        var modalArea = ModalRenderer.centerRect(screen, modalWidth, modalHeight);
        var block = dev.tamboui.widgets.block.Block.builder()
                .borders(dev.tamboui.widgets.block.Borders.ALL)
                .borderType(dev.tamboui.widgets.block.BorderType.DOUBLE)
                .title(ModalRenderer.styledTitle(" Actions — " + instanceName + " ", ModalRenderer.BORDER))
                .borderStyle(Style.EMPTY.fg(ModalRenderer.BORDER))
                .style(Style.EMPTY.bg(ModalRenderer.BG))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        ModalRenderer.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var rows = dev.tamboui.layout.Layout.vertical()
                .constraints(dev.tamboui.layout.Constraint.fill(), dev.tamboui.layout.Constraint.length(1))
                .split(inner);

        // Keep the selected item visible
        int contentHeight = rows.get(0).height();
        if (actionsSelectedIndex < actionsScrollOffset) {
            actionsScrollOffset = actionsSelectedIndex;
        } else if (actionsSelectedIndex >= actionsScrollOffset + contentHeight) {
            actionsScrollOffset = actionsSelectedIndex - contentHeight + 1;
        }

        actionsScrollOffset = renderScrollableContent(frame, rows.get(0), lines, actionsScrollOffset);

        var hintSpans = new ArrayList<Span>();
        ModalRenderer.addKey(hintSpans, "Enter", "Run");
        ModalRenderer.addKey(hintSpans, "F9/Esc", "Close");
        frame.renderWidget(dev.tamboui.widgets.paragraph.Paragraph.from(Line.from(hintSpans)), rows.get(1));
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

    private static boolean hasPendingOp(InstanceInfo entry) {
        return entry != null && !entry.pendingOp.isEmpty();
    }

    private static boolean hasPendingOp(TemplateInfo template) {
        return template != null && !template.pendingOp.isEmpty();
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

    /**
     * Execute an operation in the background using a virtual thread.
     * Two coordination layers:
     * 1. In-process: {@code backgroundTasks.tryClaim} (immediate, on event thread)
     * 2. Cross-process: {@code lockManager.tryAcquire} (flock, inside virtual thread)
     * Incus metadata is set/cleared under the cross-process lock for display only.
     */
    private void execInBackground(String displayName, String completedDisplayName,
                                  String targetName, String successMessage, String pendingOp,
                                  Runnable action) {
        if (!backgroundTasks.tryClaim(targetName)) {
            statusMessage = "Operation already in progress for " + targetName;
            return;
        }

        backgroundTasks.submit(displayName, completedDisplayName, targetName, () -> {
            Optional<InstanceLockManager.LockHandle> lockOpt;
            try {
                lockOpt = lockManager.tryAcquire(targetName, pendingOp);
            } catch (java.io.UncheckedIOException e) {
                backgroundTasks.releaseClaim(targetName);
                setStatusMessage("Lock error for " + targetName + ": " + e.getCause().getMessage());
                refreshDataAfterBackground();
                throw e;
            }
            if (lockOpt.isEmpty()) {
                backgroundTasks.releaseClaim(targetName);
                setStatusMessage("Instance " + targetName + " is locked by another process");
                refreshDataAfterBackground();
                throw new IllegalStateException("Locked by another process");
            }

            try (var lock = lockOpt.get()) {
                if (pendingOp != null) {
                    incus.setPendingOperation(targetName, pendingOp);
                    refreshDataAfterBackground();
                }
                try {
                    action.run();
                    setStatusMessage(successMessage);
                } catch (Throwable t) {
                    setStatusMessage("Failed: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
                    throw t;
                } finally {
                    incus.clearPendingOperation(targetName);
                    refreshDataAfterBackground();
                }
            } finally {
                backgroundTasks.releaseClaim(targetName);
                refreshDataAfterBackground();
            }
        });
    }

    /**
     * Signal that data should be refreshed after a background task completes.
     * The refresh will happen in the render loop (debounced).
     */
    private void refreshDataAfterBackground() {
        needsRefresh.set(true);
    }

    /**
     * Set a status message from a background thread.
     * The message will be applied in the render loop.
     */
    private void setStatusMessage(String msg) {
        pendingStatusMessage.set(msg);
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
        var versionOutdated = new java.util.HashSet<String>();
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
                    versionOutdated.add(t.name);
                } else if (t.buildVersion.isEmpty()) {
                    symbols.append('!');
                    anyTemplateOutdated = true;
                    versionOutdated.add(t.name);
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

            // Apply pending operation visual indicators
            if (!t.pendingOp.isEmpty()) {
                if (Metadata.OP_DELETING.equals(t.pendingOp)) {
                    statusStyle = statusStyle.addModifier(dev.tamboui.style.Modifier.DIM)
                            .addModifier(dev.tamboui.style.Modifier.ITALIC);
                } else if (Metadata.OP_STOPPING.equals(t.pendingOp) || Metadata.OP_RESTARTING.equals(t.pendingOp)) {
                    statusStyle = statusStyle.addModifier(dev.tamboui.style.Modifier.DIM);
                }
            }

            templateRows.add(Row.from(t.name, statusDisplay, desc).style(statusStyle));
        }
        templatesDefChanged = defChanged;
        templatesParentRebuilt = parentRebuilt;
        var outOfSync = new java.util.LinkedHashSet<String>();
        outOfSync.addAll(versionOutdated);
        outOfSync.addAll(defChanged);
        templatesOutOfSync = outOfSync;
    }

    private java.util.Map<String, String> computeAllToolFingerprints() {
        var rawFps = new java.util.TreeMap<String, String>();
        var depMap = new java.util.TreeMap<String, java.util.List<String>>();
        var visited = new java.util.HashSet<String>();
        for (var def : imageDefs.values()) {
            for (var toolRef : def.getTools()) {
                collectToolFps(toolRef.getName(), rawFps, depMap, visited);
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
            for (var depRef : yts.toolDef().getRequires()) {
                collectToolFps(depRef.getName(), rawFps, depMap, visited);
            }
            rawFps.put(name, yts.toolDef().contentFingerprint());
            var depNames = yts.toolDef().getRequires().stream()
                .map(dev.incusspawn.tool.ToolDef.ToolRef::getName)
                .toList();
            depMap.put(name, depNames);
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

            // Apply pending operation visual indicators
            if (!entry.pendingOp.isEmpty()) {
                if (Metadata.OP_DELETING.equals(entry.pendingOp)) {
                    statusStyle = statusStyle.addModifier(dev.tamboui.style.Modifier.DIM)
                            .addModifier(dev.tamboui.style.Modifier.ITALIC);
                } else if (Metadata.OP_STOPPING.equals(entry.pendingOp) || Metadata.OP_RESTARTING.equals(entry.pendingOp)) {
                    statusStyle = statusStyle.addModifier(dev.tamboui.style.Modifier.DIM);
                }
            }

            tableRows.add(Row.from(entry.name, entry.status, entry.ipv4,
                    parent, entry.runtime, age).style(statusStyle));
            rowToEntry.add(entry);
        }
    }

    private void createBranch(String source, String name, boolean gui, boolean kvm,
                               NetworkMode networkMode, String inboxPath, boolean vm) {
        if (incus.exists(name)) {
            throw new RuntimeException("an instance named '" + name + "' already exists.");
        }

        System.out.println("Branching '" + name + "' from '" + source + "'...");
        incus.copy(source, name);

        String cpu, memory, disk;
        if (vm) {
            cpu = vmCpuInput.text().strip();
            memory = vmMemoryInput.text().strip();
            disk = vmDiskInput.text().strip();
        } else {
            cpu = String.valueOf(ResourceLimits.adaptiveCpuLimit());
            memory = ResourceLimits.adaptiveMemoryLimit();
            disk = ResourceLimits.defaultDiskLimit();
        }
        System.out.println("Applying resource limits: " + cpu + " CPUs, " + memory + " memory, " + disk + " disk");
        InstanceLifecycle.applyResourceLimits(incus, name, cpu, memory, disk);
        InstanceLifecycle.configureNetwork(incus, name, networkMode);
        InstanceLifecycle.tagMetadata(incus, name, Metadata.TYPE_CLONE, source);
        InstanceLifecycle.integrateWithHost(incus, name, InstanceType.INSTANCE);

        // Configure GUI before start so environment.* keys are visible to init
        if (gui) {
            if (GuiPassthrough.configureGui(incus, name)) {
                incus.configSet(name, Metadata.GUI_ENABLED, "true");
            } else {
                GuiPassthrough.removeGui(incus, name);
                System.err.println("Continuing without GUI passthrough.");
            }
        } else {
            GuiPassthrough.removeGui(incus, name);
        }

        if (kvm) {
            if (!KvmPassthrough.configureKvm(incus, name)) {
                System.err.println("Continuing without KVM — VMs inside this branch will not work.");
            }
        } else {
            KvmPassthrough.removeKvm(incus, name);
        }

        var prefetched = InstanceLifecycle.prefetchRuntimeConfig(incus, name);
        InstanceLifecycle.injectSshKeyIfAvailable(incus, name, prefetched.hasSshKeys());
        InstanceLifecycle.pushTerminfoIfNeeded(incus, name, prefetched.terminfo());
        incus.start(name);

        var inbox = (inboxPath != null && !inboxPath.isEmpty()) ? java.nio.file.Path.of(inboxPath) : null;
        InstanceLifecycle.setupRuntime(incus, name, networkMode, inbox, prefetched);

        System.out.println("Branch '" + name + "' is ready.");
        System.out.println("Connecting to " + name + "...\n");
        incus.interactiveShell(name, "agentuser", prefetched.toShellPrep());
        System.out.println();
    }

    private volatile boolean proxyRestartInProgress;
    private volatile boolean dnsVerified;

    private void tryFixStaleDns() {
        if (dnsVerified) return;
        dnsVerified = true;
        if (MitmProxy.isBridgeDnsComplete(incus)) return;
        setStatusMessage("Updating bridge DNS overrides...");
        var thread = new Thread(() -> {
            try {
                MitmProxy.writeBridgeDns(incus);
                setStatusMessage("Bridge DNS overrides updated");
            } catch (Exception e) {
                setStatusMessage("DNS update failed: " + e.getMessage());
                dnsVerified = false;
            }
        }, "dns-auto-heal");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean showProxyError() {
        var proxyStatus = ProxyHealthCheck.check(incus);
        if (proxyStatus == ProxyHealthCheck.ProxyStatus.RUNNING) {
            tryFixStaleDns();
            return false;
        }
        if (proxyStatus == ProxyHealthCheck.ProxyStatus.WAITING_FOR_DNS) {
            setStatusMessage("Proxy running, waiting for DNS configuration...");
            return true;
        }
        if (proxyRestartInProgress) {
            setStatusMessage("Proxy restarting...");
            return true;
        }
        if (ProxyService.isInstalled()) {
            proxyRestartInProgress = true;
            setStatusMessage("Proxy not running, restarting service...");
            var thread = new Thread(() -> {
                try {
                    if (ProxyHealthCheck.tryAutoRestart(incus)) {
                        setStatusMessage("Proxy restarted, waiting for DNS...");
                    } else {
                        setStatusMessage("Proxy restart failed. Check: isx proxy status");
                    }
                } finally {
                    proxyRestartInProgress = false;
                }
            }, "proxy-auto-restart");
            thread.setDaemon(true);
            thread.start();
            return true;
        }
        if (proxyStatus == ProxyHealthCheck.ProxyStatus.STALE_DNS) {
            errorMessage = "The MITM proxy is not running, but DNS overrides\n"
                    + "are still active from a previous session.\n"
                    + "\n"
                    + "Start the proxy to restore connectivity:\n"
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
        showSubnetWarning();
        fixCaMismatchIfNeeded(containerName);
        return false;
    }

    private void showSubnetWarning() {
        try {
            var diagnostic = BridgeSubnetCheck.detectConflictDiagnostic(incus);
            if (diagnostic != null) {
                statusMessage = "Bridge subnet conflict detected — run 'isx init' to fix";
            }
        } catch (Exception ignored) {
        }
    }

    private void fixCaMismatchIfNeeded(String containerName) {
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(containerName))) {
            HostResourceSetup.removeStaleDevices(incus, containerName);
            incus.start(containerName);
            incus.waitForReady(containerName);
        }
        CertificateAuthority.fixContainerCaIfNeeded(incus, containerName);
    }

    private void shellInto(String name) {
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(name))) {
            System.out.println("Starting " + name + "...");
            HostResourceSetup.removeStaleDevices(incus, name);
            incus.start(name);
            incus.waitForReady(name);
        }
        checkGuiHealth(name);
        System.out.println("Connecting to " + name + "...\n");
        incus.interactiveShell(name, "agentuser");
        System.out.println();
    }

    private void checkGuiHealth(String name) {
        GuiPassthrough.checkGuiHealth(incus, name);
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

                var extracted = IncusClient.extractIpv4(node.path("state").path("network"));
                var ipv4 = extracted != null ? extracted : "";

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
                                ? configVal(config, Metadata.BUILD_SOURCE, "") : "",
                        configVal(config, Metadata.PENDING_OP, "")));
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
                                String definitionSha, String pendingOp) {}

    private record InstanceInfo(String name, String status,
                                String project, String profile, String created,
                                String runtime, String parent,
                                String limitsCpu, String limitsMemory, String rootSize,
                                String ipv4, String networkMode, String architecture,
                                String buildVersion, String definitionSha,
                                String type, String buildSourceJson, String pendingOp) {}
}
