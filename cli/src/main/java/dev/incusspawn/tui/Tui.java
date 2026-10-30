package dev.incusspawn.tui;

import dev.incusspawn.command.BaseCommand;
import dev.incusspawn.command.RunCommand;
import dev.incusspawn.command.BaseImageAge;
import dev.incusspawn.command.TemplateStaleness;
import dev.incusspawn.command.ListCommand;
import dev.incusspawn.command.TemplatesCommand;
import dev.incusspawn.command.CleanCommand;
import dev.incusspawn.command.BuildCommand;
import dev.incusspawn.ai.AiHelpClient;
import dev.incusspawn.ai.HelpContext;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.Platform;
import dev.incusspawn.Warnings;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.AutoRemoteService;
import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.FirewallDetector;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.lifecycle.InstanceDestroyer;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.lifecycle.RuntimeSetup;
import dev.incusspawn.mcp.TaskJoin;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Output;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyLog;
import dev.incusspawn.lifecycle.ZmxSocketForward;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.tool.ToolAction;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolAction;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.style.Style;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.input.TextInputState;
import dev.tamboui.widgets.table.Row;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import dev.tamboui.widgets.table.TableState;
import dev.incusspawn.RuntimeServices;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import static dev.incusspawn.tui.DiskUsageModel.hasDescendant;
import static dev.incusspawn.tui.UsageFormat.bar;
import static dev.incusspawn.tui.UsageFormat.diskCell;

import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.command.InstanceListing.TemplateInfo;

/**
 * The TUI that bare {@code isx} opens: a templates panel and an instances panel, the dialogs that
 * act on them, and the event loop that hands the terminal to a shell or a build and takes it back.
 * {@code isx list} is {@link ListCommand}, which prints the listing and never opens this.
 */
public class Tui {

    // What bare `isx` said before the TUI takes the terminal; handed over just before it does.
    private PreTuiOutput preTui;

    IncusClient incus;

    ToolDefLoader toolDefLoader;
    java.util.List<ToolSetup> cdiTools;

    BackgroundTaskManager backgroundTasks;

    InstanceLockManager lockManager;

    final TuiTheme theme = TerminalThemeDetector.detect();
    private final ModalRenderer modal = new ModalRenderer(theme);
    /** Warnings from any operation while the TUI runs; the status line holds only one. */
    final WarningLog warningLog = new WarningLog();
    /**
     * Where every warning goes while the TUI draws or reloads (#872, #1703): its log, and progress
     * nowhere, since a line printed under the TUI is drawn over. One output for the TUI's life, so
     * what {@link Warnings} reported is remembered across sessions, while a build run on the
     * released terminal still prints the same warning.
     */
    final Output drawing = Output.diagnosticsTo(warningLog::add);
    private final WarningsModal warningsModal = new WarningsModal(modal, theme, warningLog,
            () -> Warnings.forgetReported(drawing));
    /** The status line last set by a warning announcement, which a newer one may replace. */
    private String warningAnnouncement;
    /**
     * Whether the status line is held until the next key (#1497): set when a confirmed batch stop
     * names the instances it no longer acts on, which the batch's own messages, often posted before
     * the next frame, would otherwise replace before anyone saw it.
     */
    private boolean holdingStatus;
    /** The latest status message that arrived while {@link #holdingStatus}; shown after the next key. */
    private String deferredStatus;
    private final TemplateDetailView templateDetail = new TemplateDetailView(modal, theme,
            new TemplateDetailView.Source() {
                @Override public Map<String, dev.incusspawn.config.ImageDef> imageDefs() { return imageDefs; }
                @Override public List<String> autoDeps(List<String> explicitTools) { return instanceActions.collectAutoDeps(explicitTools); }
                @Override public java.nio.file.Path hostRepoMatch(String cloneUrl) {
                    return resolveHostRepoMatch(cloneUrl, SpawnConfig.load());
                }
                @Override public boolean definitionChanged(String template) { return templatesDefChanged.contains(template); }
                @Override public boolean parentRebuilt(String template) { return templatesParentRebuilt.contains(template); }
                @Override public String overriddenSource(String template) { return imageLayers.overriddenSource(template); }
                @Override public String builtFrom(String template) { return Tui.this.loader.builtFrom(template); }
                @Override public BuildSource buildSource(String template) { return Tui.this.loader.buildSource(template); }
                @Override public String currentVersion() { return Tui.this.currentVersion(); }
            }, java.time.LocalDateTime::now);

    private final InstanceDetailView instanceDetail = new InstanceDetailView(modal, theme, java.time.LocalDateTime::now);

    private final ActionsMenu actionsMenu = new ActionsMenu(modal, theme);

    private final TaskPicker taskPicker = new TaskPicker(modal, theme);

    private final AboutModal about = new AboutModal(modal, theme, new AboutModal.Source() {
        @Override public String version() { return Tui.this.currentVersion(); }
        @Override public String gitSha() { return BuildInfo.instance().gitSha(); }
        @Override public String incusClient() { return BuildInfo.instance().incusClient(); }
        @Override public String incusServer() { return BuildInfo.instance().incusServer(); }
        @Override public String runtime() { return BuildInfo.instance().runtime(); }
        @Override public String kernelInfo() { return BuildInfo.instance().kernelInfo(); }
        @Override public boolean isMacOS() { return Platform.isMacOS(); }
    });

    private final MainScreen mainScreen = new MainScreen(this);
    private final ShellLaunch shellLaunch = new ShellLaunch(this);
    final ListingLoader loader = new ListingLoader(this);
    final InstanceActions instanceActions =
            new InstanceActions(() -> this.imageDefs, () -> this.toolDefLoader, () -> this.cdiTools);

    // Background operation state
    private final AtomicBoolean needsRefresh = new AtomicBoolean(false);
    /** An account change finished in the background; an open detail dialog re-reads its accounts. */
    private final AtomicBoolean detailAccountsStale = new AtomicBoolean(false);
    /** Redraw on the next tick without reloading data -- for state that changed off the UI thread. */
    private final AtomicBoolean needsRepaint = new AtomicBoolean(false);
    private final AtomicReference<String> pendingStatusMessage = new AtomicReference<>();
    private long lastRefreshTime = 0;
    private static final long REFRESH_DEBOUNCE_MS = 1000;

    // Live refresh: changes made outside this TUI (another terminal, a script, `incus` itself)
    // arrive through the Incus event feed and trigger a *light* refresh -- just the instance
    // listing (plus the pool gauge), fetched off the UI thread and swapped in on it. The heavy
    // parts of reloadData() (definitions on disk, btrfs accounting probes, proxy health) keep
    // their own cadences and never run per event. See DESIGN.md "Keeping a long-lived TUI current".
    private InstanceEventWatcher eventWatcher;
    // `tui-live-refresh: false` in config.yaml: no subscription and no polling -- only the
    // manual `r` and the reloads the TUI already did (re-entry, its own background tasks).
    private boolean liveRefreshEnabled;
    final AtomicBoolean liveRefreshRequested = new AtomicBoolean(false);
    private final AtomicBoolean liveRefreshInFlight = new AtomicBoolean(false);
    private final AtomicReference<LiveSnapshot> pendingLiveSnapshot = new AtomicReference<>();
    // Due times (epoch ms) of follow-up refreshes scheduled after a start: the instance's IPv4
    // shows up a few seconds after the start event, and nothing announces it.
    private final java.util.concurrent.ConcurrentSkipListSet<Long> followUpRefreshes =
            new java.util.concurrent.ConcurrentSkipListSet<>();
    private static final long[] START_FOLLOW_UP_DELAYS_MS = {3_000, 10_000};
    // Bumped by every full reload, so a light fetch that started before it can't overwrite it.
    long dataGeneration;
    private long lastLiveRefreshMs;
    long lastDataLoadMs;
    // Only while the event feed is down (older daemon, flaky appliance): poll slowly instead.
    private static final long FALLBACK_POLL_MS = 60_000;
    private boolean liveRefreshErrorShown;
    // The minute the age column ("3h ago") was last rendered for -- see Metadata.ageRefreshKey.
    private java.time.LocalDateTime rowsAgeKey;
    // Every instance name in the last listing (templates included): what "still exists" means
    // for the action guards and for closing a dialog whose target was deleted elsewhere.
    java.util.Set<String> liveInstanceNames = java.util.Set.of();
    // The instance the F3 detail dialog was opened on (it renders the current selection).
    private String detailInstanceName;
    /** The credential account dialog; null while it is closed. */
    private AccountsModal accountsModal;
    /** Mode to return to when it closes: it opens from the instance list and from its details. */
    private Mode accountsReturnMode = Mode.BROWSE;
    /** The detail dialog's "Credential accounts" section, resolved once per opening. */
    private List<dev.incusspawn.config.AccountUsage.Use> detailAccountUses = List.of();

    /** Result of a light refresh, produced off the UI thread. Exactly one of instances/error is set. */
    private record LiveSnapshot(long generation, List<InstanceInfo> instances,
                                IncusClient.PoolUsage poolUsage, RuntimeException error) {}
    private static final Duration TASK_DISPLAY_DURATION = Duration.ofSeconds(5);

    /**
     * What the TUI shows, and whether it reads digits itself: a text field takes them as text and the
     * build menu as its options' numbers. Elsewhere a digit is its F-key ({@link DigitKeys}, #1824).
     */
    enum Mode {
        BROWSE(false), CONFIRM_DELETE(false), CONFIRM_MARKED_STOP(false), CONFIRM_STOP_FOR_RENAME(false),
        CONFIRM_BUILD_FOR_BRANCH(false), BUILD_MENU(true), BRANCH(true), RENAME(true), TEMPLATE_DETAIL(false),
        INSTANCE_DETAIL(false), ACCOUNTS(false), INFO(false), ERROR(false), ACTIONS(false), NEW_TEMPLATE(true),
        CLEAN_CONFIRM(false), CLEAN_RESULT(false), HELP_CHAT(true), WARNINGS(false), TASK_PICKER(false);

        private final boolean ownsDigits;

        Mode(boolean ownsDigits) {
            this.ownsDigits = ownsDigits;
        }

        /** Whether the mode reads digits itself, so they stay digits rather than F-keys. */
        boolean ownsDigits() {
            return ownsDigits;
        }
    }
    private Mode mode = Mode.BROWSE;
    private String errorMessage;
    /** The delete confirmation; set when it opens, and read again by the destroy it confirms. */
    private DeleteConfirm deleteConfirm;
    /** The confirmation before F7 / Shift+F7 on the marked instances; set when it opens. */
    private MarkedStopConfirm markedStopConfirm;
    /** The pool-cleanup dialog; set when 'c' finds a pool, and kept for the result it shows after the clean. */
    private CleanModal clean;
    /** The F5 build menu; set when it opens. */
    private BuildMenu buildMenu;
    private String[] pendingBuildArgs;
    // Branch modal state
    String branchSourceName;
    /** The branch dialog; set when it opens, and read once more to create the branch after it closes. */
    BranchModal branch;
    // Rename modal state
    /** The instance F6 renames, kept through the stop-first confirm and while the dialog is open. */
    private String renameSourceName;
    /** The rename dialog; set when it opens. */
    private RenameDialog rename;
    /** The new-template dialog; set when it opens, and read once more to create the template after it closes. */
    private NewTemplateModal newTemplate;
    String statusMessage;
    private String progressMessage;
    // Search/filter state
    boolean searchActive = false;
    TextInputState searchInput;
    private List<TemplateInfo> allTemplateEntries;
    List<InstanceInfo> allEntries;
    // AI Help modal state
    /** Kept after closing, so reopening preselects the account used last. */
    private volatile HelpChatModal helpChat;
    // Actions cache (computed once per data refresh, not per render)
    java.util.Map<String, java.util.List<ToolAction>> actionsCache = new java.util.HashMap<>();
    // Default action reference per instance (from ImageDef default-action field)
    java.util.Map<String, String> defaultActionRef = new java.util.HashMap<>();

    private boolean deferredBuildForBranch;

    enum PendingAction { NONE, SHELL, SHELL_WITH_COMMAND, BRANCH, BUILD_TEMPLATE, BUILD_THEN_BRANCH, EDIT_TEMPLATE, NEW_TEMPLATE, EXECUTE_ACTION }
    private PendingAction pendingAction = PendingAction.NONE;
    private ActionContext pendingActionTarget;
    private String pendingShellCommand;
    /** Whether {@link #pendingShellCommand} runs outside the instance's tmux or zmx session: a joined task's does. */
    private boolean pendingCommandOnly;
    private ToolAction pendingToolAction;
    // After returning from a shell/branch, focus this instance in the instances panel
    private String returnToInstance;
    private String returnToTemplate;

    private static final int PAGE_SIZE = 10;

    // Two-panel focus
    enum Panel { TEMPLATES, INSTANCES }
    Panel focusedPanel = Panel.TEMPLATES;

    // Template panel data (top)
    Map<String, dev.incusspawn.config.ImageDef> imageDefs;
    List<TemplateInfo> templateEntries;
    List<Row> templateRows;
    boolean anyTemplateOutdated;
    boolean anyDefinitionChanged;
    boolean anyParentRebuilt;
    java.util.Set<String> templatesDefChanged = java.util.Set.of();
    /** Where each image definition came from, for what it overrides. */
    dev.incusspawn.config.LayeredDefinitions<dev.incusspawn.config.ImageDef> imageLayers =
            new dev.incusspawn.config.LayeredDefinitions<>("image");
    java.util.Set<String> templatesParentRebuilt = java.util.Set.of();
    private java.util.Set<String> templatesOutOfSync = java.util.Set.of();
    java.util.Set<String> storedSourceTemplates = java.util.Set.of();
    /**
     * Templates marked with Space for one build from F5 (#1131), by name in the order marked: in
     * memory only, they outlive refreshes and the TUI's return from a build or a shell, lose a
     * template that is gone, and clear on Esc or once a build is launched from them.
     */
    private final java.util.SequencedSet<String> markedTemplates = new java.util.LinkedHashSet<>();
    /**
     * Instances marked with Space (#1482), kept like {@link #markedTemplates}: F8 destroys them, F7
     * stops and Shift+F7 restarts the running ones, and launching any of those clears them.
     */
    private final java.util.SequencedSet<String> markedInstances = new java.util.LinkedHashSet<>();
    TableState templateTableState;

    // Instance panel data (bottom)
    List<InstanceInfo> entries;
    List<Row> tableRows;
    private List<InstanceInfo> rowToEntry;
    private TableState instanceTableState;

    // Storage-pool usage for the top gauge; refreshed in reloadData (not per-frame,
    // since it costs an API call). Null when no pool usage is available.
    IncusClient.PoolUsage poolUsage;
    // The root template that owns the base-image weight this reload — either folded into it
    // (foldBaseWeightIntoRootTemplate) or attributed via its own rfer (applyReferencedTemplateDeltas),
    // or null when it couldn't be attributed. Drives the CoW delete note and the ~ marker.
    String baseTemplateName;
    // Amber threshold for the storage gauge (percent of pool used).
    static final int STORAGE_WARN_PERCENT = 75;
    volatile ProxyHealthCheck.ProxyInfo proxyInfo;
    volatile String applianceSkewMessage;
    /**
     * Bare {@code isx}: takes over the terminal until the user quits.
     * Returns false when it failed, having reported why, so the command exits non-zero (#1785).
     */
    public boolean run(PreTuiOutput preTui) {
        this.preTui = preTui;
        try {
            open();
            return true;
        } catch (Exception e) {
            BuildOutput.error(BaseCommand.failure(e));
            return false;
        }
    }

    private void open() {
        // Before anything else, so no failure here can skip the pause, and before the
        // first reload, so a pause for the user does not leave the listing stale.
        if (preTui != null) {
            preTui.handOver(warning -> Warnings.warn(drawing, warning), this::waitForUser);
            preTui = null;
        }
        try {
            loadServices();
        } catch (RuntimeException e) {
            // The hand-over moved the held warnings into the log, which nobody will now see
            printUnreadWarnings();
            throw e;
        }
        runTuiLoop();
    }

    /** Overridable so a test can fail here, after the hand-over and before the loop. */
    void loadServices() {
        this.incus = RuntimeServices.incus();
        this.toolDefLoader = RuntimeServices.toolDefLoader();
        this.cdiTools = RuntimeServices.toolSetups();
        this.backgroundTasks = RuntimeServices.backgroundTasks();
        this.lockManager = RuntimeServices.lockManager();
    }

    // --- TUI lifecycle ---

    /** Let the user read what was printed before the TUI opens; a seam for tests. */
    void waitForUser() {
        PreTuiOutput.waitForEnter();
    }

    /** The TUI's warnings, oldest first, read-only; for tests. */
    List<String> warningMessages() {
        return warningLog.entries().stream().map(WarningLog.Entry::message).toList();
    }

    /** The runner's settings, less its backend. */
    static TuiConfig.Builder runnerConfig() {
        return TuiConfig.builder()
                .tickRate(Duration.ofMillis(100));
    }

    /**
     * Opens the runner on a cleared screen. Blank cells are never sent, so without the clear a
     * terminal that does not give a fresh alternate screen shows its old output through them (#1681).
     */
    static TuiRunner openRunner(TuiConfig config) throws Exception {
        var runner = TuiRunner.create(config);
        try {
            runner.terminal().clear();
        } catch (RuntimeException e) {
            try {
                runner.close();
            } catch (RuntimeException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
        return runner;
    }

    /** Draws the TUI until the user quits; overridable so a test can stand in for the terminal. */
    void runTuiLoop() {
        try {
            runTuiLoopUntilQuit();
        } finally {
            printUnreadWarnings();
        }
    }

    /** Puts the warnings that arrived since the last announcement on the status line; false if none did. */
    private boolean announceWarnings() {
        var announced = WarningLog.statusLine(warningLog.takeUnannounced());
        if (announced != null) statusMessage = warningAnnouncement = announced;
        return announced != null;
    }

    /** A key has been pressed, so the held line was seen: what waited behind it takes the line (#1497). */
    private void releaseHeldStatus() {
        statusMessage = deferredStatus;
        holdingStatus = false;
        deferredStatus = null;
    }

    /**
     * Whether the status line is free for a warning announcement: it is empty, or holds an older
     * announcement, and is not held. An action's result (a failed build, a created template) is not
     * replaced; the warnings wait, counted in the header, until the next key clears the line.
     */

    private boolean canAnnounceWarnings() {
        return !holdingStatus && (statusMessage == null || statusMessage.equals(warningAnnouncement));
    }

    /** The TUI has closed: warnings nobody opened the dialog for must not vanish with it. */
    private void printUnreadWarnings() {
        for (var entry : warningLog.unreadEntries()) {
            BuildOutput.warn(entry.message());
        }
    }

    private void runTuiLoopUntilQuit() {
        liveRefreshEnabled = SpawnConfig.load().tuiLiveRefreshEnabled();
        if (!liveRefreshEnabled) {
            runTuiSessions();
            return;
        }
        // One subscription for the whole session, including while the TUI is suspended for a
        // shell: an event then just leaves a refresh request, which the full reload on the next
        // TUI entry clears (reloadData drops requests raised before it started).
        eventWatcher = new InstanceEventWatcher(incus::openLifecycleEvents,
                this::onInstanceEvent, () -> liveRefreshRequested.set(true),
                () -> pendingStatusMessage.set("Live updates unavailable -- refreshing every "
                        + FALLBACK_POLL_MS / 1000 + "s instead")).start();
        try {
            runTuiSessions();
        } finally {
            eventWatcher.close();
            eventWatcher = null;
        }
    }

    private void onInstanceEvent(InstanceEventWatcher.Change change) {
        liveRefreshRequested.set(true);
        if (change.isStart()) {
            long now = System.currentTimeMillis();
            for (long delay : START_FOLLOW_UP_DELAYS_MS) followUpRefreshes.add(now + delay);
        }
    }

    private void runTuiSessions() {
        while (true) {
            String reloadError = null;
            // Every warning goes to the log while the TUI reloads or draws; a build or a shell
            // on the released terminal binds its own output.
            try (var ignored = BuildOutput.bind(drawing)) {
                loader.reloadData();
            } catch (IncusException e) {
                reloadError = e.getMessage();
                templateEntries = List.of();
                entries = List.of();
                rowToEntry = List.of();
            }
            enterSession(reloadError);

            ProxyLog.setSuppressStderr(true);
            try (var ignored = BuildOutput.bind(drawing);
                 var runner = openRunner(runnerConfig().backend(new LegacyKeyboardBackend()).build())) {
                runner.run(this::handleEvent, this::render);
            } catch (Exception e) {
                // The runner is closed and the drawing binding with it: this reaches the terminal
                BuildOutput.warn("TUI unavailable: " + BaseCommand.failure(e));
                ListCommand.printTable(entries, BuildOutput.results());
                return;
            } finally {
                ProxyLog.setSuppressStderr(false);
            }

            // Remember template selection for when we re-enter the TUI
            var tpl = selectedTemplate();
            if (tpl != null) returnToTemplate = tpl.name();

            // The terminal is lent out to the action, and the TUI redraws over whatever it prints
            // once it returns: every warning is printed and kept in the log (#1646, #1703).
            try (var released = BuildOutput.bind(Output.copying(BuildOutput.terminal(), drawing))) {
                switch (pendingAction) {
                    case SHELL -> {
                        returnToInstance = pendingActionTarget.name();
                        shellLaunch.shellInto(pendingActionTarget);
                    }
                    case SHELL_WITH_COMMAND -> {
                        returnToInstance = pendingActionTarget.name();
                        shellLaunch.shellInto(pendingActionTarget, pendingShellCommand, pendingCommandOnly);
                    }
                    case BRANCH -> {
                        returnToInstance = pendingActionTarget.name();
                        try {
                            shellLaunch.createBranchFromModal(pendingActionTarget.name());
                            statusMessage = "Created branch " + pendingActionTarget.name();
                        } catch (Exception e) {
                            statusMessage = "Failed to create branch " + pendingActionTarget.name() + ": " + e.getMessage();
                        }
                    }
                    case BUILD_TEMPLATE, BUILD_THEN_BRANCH -> {
                        var args = java.util.Arrays.copyOf(pendingBuildArgs, pendingBuildArgs.length + 1);
                        args[args.length - 1] = "--yes";
                        // The one template the build names, if just one: a batch has no report to show
                        var buildTarget = singleBuildTarget(pendingBuildArgs);
                        if (buildTarget != null) returnToTemplate = buildTarget;
                        try {
                            // Delete any stale report so freshFailureReport cannot mistake it for this build's.
                            // The build writes a new one on failure; on success no report should be shown.
                            // If the delete fails, suppress the report path entirely (buildStart = null)
                            // rather than risk showing a stale report from the same filesystem second.
                            java.time.Instant buildStart = null;
                            if (buildTarget != null) {
                                try {
                                    java.nio.file.Files.deleteIfExists(Environment.buildFailureLogFile(buildTarget));
                                    buildStart = java.time.Instant.now();
                                } catch (Exception ignored) {}
                            } else {
                                buildStart = java.time.Instant.now();
                            }
                            // The build's warnings reach the log through the released terminal's binding
                            // (a deprecated parent, #1195; dropped tools)
                            var buildResult = org.aesh.AeshRuntimeRunner.builder().command(BuildCommand.class).args(args).execute();
                            int exitCode = buildResult != null ? buildResult.getResultValue() : 1;
                            statusMessage = buildStatusMessage(pendingBuildArgs, exitCode == 0, buildStart);
                            if (exitCode != 0) pendingAction = PendingAction.BUILD_TEMPLATE;
                        } catch (Exception e) {
                            statusMessage = "Build failed: " + e.getMessage();
                            pendingAction = PendingAction.BUILD_TEMPLATE;
                        }
                    }
                    case NEW_TEMPLATE -> {
                        returnToTemplate = pendingActionTarget.name();
                        try {
                            var parent = newTemplate.parent();
                            var dir = newTemplate.locationDir();
                            var targetPath = TemplatesCommand.createTemplateFile(pendingActionTarget.name(), parent, dir);
                            var valid = TemplatesCommand.editLoop(targetPath, pendingActionTarget.name(), false);
                            // Not valid when the file was left broken, or the editor never started
                            statusMessage = "Created template " + pendingActionTarget.name()
                                    + (valid ? "" : ", but its edit did not end with a valid file");
                        } catch (Exception e) {
                            statusMessage = "Failed to create template: " + e.getMessage();
                        }
                    }
                    case EDIT_TEMPLATE -> {
                        returnToTemplate = pendingActionTarget.name();
                        try {
                            var editCmd = new TemplatesCommand.Edit();
                            editCmd.name = pendingActionTarget.name();
                            // The edit loop ends invalid when the editor could not start or the file was left broken
                            if (editCmd.doExecute().getResultValue() != 0) {
                                statusMessage = "Editing template " + pendingActionTarget.name() + " did not end with a valid file";
                            }
                        } catch (Exception e) { statusMessage = "Edit failed: " + e.getMessage(); }
                    }
                    case EXECUTE_ACTION -> {
                        // As isx run reports it: a failure is an error, which the log keeps too
                        RunCommand.report(pendingToolAction.execute(pendingActionTarget));
                        if (pendingToolAction instanceof YamlToolAction yamlAction && !yamlAction.shouldAutoReturn()) {
                            // Its output stays on screen until the user is ready
                            PreTuiOutput.waitForEnter("Press Enter to continue...");
                        }
                    }
                    case NONE -> { return; }
                }
                // An action that failed mid-step (a branch whose copy or start threw) must not leave the
                // step animating over the TUI we are about to redraw, nor System.out/err guarded.
                BuildOutput.abandonStep();
            }
        }
    }

    /**
     * The state a TUI session starts in, after a reload: browse mode (or the error, if the reload
     * failed), nothing pending, and the selection restored to what the last session left.
     */
    private void enterSession(String reloadError) {
        var previousAction = pendingAction;
        mode = Mode.BROWSE;
        if (reloadError != null) {
            errorMessage = reloadError;
            mode = Mode.ERROR;
        }
        pendingAction = PendingAction.NONE;

        templateTableState = new TableState();
        instanceTableState = new TableState();

        // Restore template selection by name
        boolean templateRestored = false;
        if (returnToTemplate != null) {
            for (int i = 0; i < templateEntries.size(); i++) {
                if (templateEntries.get(i).name().equals(returnToTemplate)) {
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
                    .filter(t -> t.name().equals(branchSourceName))
                    .findFirst().orElse(null);
            if (tpl != null && !"not built".equals(tpl.buildStatus())) {
                openBranchModal(tpl.name());
            }
        }

        // If returning from a shell/branch, focus the target instance
        if (returnToInstance != null) {
            focusedPanel = Panel.INSTANCES;
            boolean found = false;
            for (int i = 0; i < rowToEntry.size(); i++) {
                if (rowToEntry.get(i) != null && rowToEntry.get(i).name().equals(returnToInstance)) {
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
    }

    void publishRows() {
        var names = templateEntries.stream().map(TemplateInfo::name).collect(java.util.stream.Collectors.toSet());
        markedTemplates.retainAll(names);
        markedInstances.retainAll(entries.stream().map(InstanceInfo::name).collect(java.util.stream.Collectors.toSet()));
        allTemplateEntries = new ArrayList<>(templateEntries);
        allEntries = new ArrayList<>(entries);
        rebuildRowData();
    }

    static final long PROXY_AUTH_CHECK_INTERVAL_MS = 30_000;

    // --- Event handling ---

    private boolean handleEvent(Event event, TuiRunner tui, TableState tableState) {
        if (event instanceof TickEvent) {
            if (deferredBuildForBranch && !proxyRestartInProgress) {
                deferredBuildForBranch = false;
                if (ProxyHealthCheck.check(incus) == ProxyHealthCheck.ProxyStatus.RUNNING) {
                    pendingAction = PendingAction.BUILD_THEN_BRANCH;
                    pendingBuildArgs = new String[]{branchSourceName};
                    returnToTemplate = branchSourceName;
                    tui.quit();
                    return true;
                }
                setStatusMessage("Proxy restart failed. Try: isx proxy start");
            }
            boolean repaint = needsRepaint.getAndSet(false);
            repaint |= tickLiveRefresh(tableState);
            return repaint || needsRefresh.get() || pendingStatusMessage.get() != null
                    || (warningLog.hasUnannounced() && canAnnounceWarnings())
                    || !backgroundTasks.getActiveTasks().isEmpty()
                    || (helpChat != null && helpChat.isLoading());
        }
        if (!(event instanceof KeyEvent key)) return false;
        // Browse mode releases the hold itself, before the key sets its own message
        if (holdingStatus && (searchActive || mode != Mode.BROWSE)) releaseHeldStatus();
        // Ctrl+L redraws the whole screen in any mode, wiping anything else that wrote to the
        // terminal (#1681). In browse mode it reloads first, so the screen is not blank meanwhile.
        if (key.hasCtrl() && key.isCharIgnoreCase('l')) {
            if (!searchActive && mode == Mode.BROWSE) handleBrowseEvent(key, tui, tableState);
            tui.terminal().clear();
            return true;
        }
        if (searchActive) return handleSearchEvent(key, tableState);
        if (!mode.ownsDigits()) key = DigitKeys.asFunctionKey(key);
        return switch (mode) {
            case BROWSE -> handleBrowseEvent(key, tui, tableState);
            case CONFIRM_DELETE -> handleConfirmDeleteEvent(key, tui, tableState);
            case CONFIRM_MARKED_STOP -> handleConfirmMarkedStopEvent(key);
            case BUILD_MENU -> handleBuildMenuEvent(key, tui);
            case CONFIRM_STOP_FOR_RENAME -> handleConfirmStopForRenameEvent(key, tui, tableState);
            case CONFIRM_BUILD_FOR_BRANCH -> handleConfirmBuildForBranchEvent(key, tui);
            case BRANCH -> handleBranchEvent(key, tui, tableState);
            case RENAME -> handleRenameEvent(key, tui, tableState);
            case NEW_TEMPLATE -> handleNewTemplateEvent(key, tui);
            case TEMPLATE_DETAIL -> handleTemplateDetailEvent(key, tui);
            case INSTANCE_DETAIL -> handleInstanceDetailEvent(key, tui);
            case ACCOUNTS -> handleAccountsEvent(key);
            case INFO -> handleInfoEvent(key);
            case WARNINGS -> {
                if (!warningsModal.handleKey(key)) mode = Mode.BROWSE;
                yield true;
            }
            case HELP_CHAT -> {
                if (!helpChat.handleKey(key)) mode = Mode.BROWSE;
                yield true;
            }
            case ERROR -> { mode = Mode.BROWSE; yield true; }
            case CLEAN_CONFIRM -> handleCleanConfirmEvent(key, tui, tableState);
            case CLEAN_RESULT -> { mode = Mode.BROWSE; yield true; }
            case ACTIONS -> handleActionsEvent(key, tui);
            case TASK_PICKER -> handleTaskPickerEvent(key, tui);
        };
    }

    private boolean handleBrowseEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        // Global keys (both panels)
        if (key.isKey(KeyCode.F10) || key.isCtrlC() || (key.hasCtrl() && key.isChar('d'))
                || key.isChar('q') || (key.hasCtrl() && key.isCharIgnoreCase('q'))) {
            tui.quit();
            return true;
        }
        // A held line has been seen now: what waited behind it takes the line, until this key sets its own
        if (holdingStatus) releaseHeldStatus();
        else statusMessage = null;

        // Esc has no other meaning in browse mode: it drops the marks on both tables
        int marks = markedTemplates.size() + markedInstances.size();
        if (key.isKey(KeyCode.ESCAPE) && marks > 0) {
            // Said, since the other table's marks are easy to miss
            statusMessage = "Cleared " + marks + (marks == 1 ? " mark" : " marks");
            markedTemplates.clear();
            markedInstances.clear();
            return true;
        }
        if (key.isKey(KeyCode.F1)) {
            about.open();
            mode = Mode.INFO;
            return true;
        }
        // Tab or Shift+Tab: switch panels
        if (key.isKey(KeyCode.TAB) || ShiftTabBindings.isShiftTab(key)) {
            focusedPanel = (focusedPanel == Panel.TEMPLATES) ? Panel.INSTANCES : Panel.TEMPLATES;
            return true;
        }
        // Refresh data. 'r' is the TUI-idiomatic binding (k9s/lazygit/btop);
        // Ctrl+L is kept as an alias for the terminal "redraw screen" reflex; handleEvent clears.
        if ((!key.hasCtrl() && key.isCharIgnoreCase('r')) || (key.hasCtrl() && key.isCharIgnoreCase('l'))) {
            // An explicit reload reports definition problems again, so a warning that is still
            // true is announced again rather than silently deduplicated.
            Warnings.forgetReported(drawing);
            refreshData(tableState);
            return true;
        }
        if (!key.hasCtrl() && key.isChar(WarningsModal.KEY.charAt(0))) {
            warningsModal.open();
            mode = Mode.WARNINGS;
            return true;
        }
        if (!key.hasCtrl() && key.isCharIgnoreCase('c')) {
            progressMessage = "Scanning pool...";
            tui.draw(frame -> render(frame, tableState));
            CleanCommand.CleanScan cleanScan;
            try {
                cleanScan = CleanCommand.scanPool(incus);
            } catch (Exception e) {
                progressMessage = null;
                statusMessage = "Clean failed: " + e.getMessage();
                return true;
            }
            progressMessage = null;
            if (cleanScan == null) {
                statusMessage = "No CoW storage pool found.";
            } else {
                clean = new CleanModal(modal, theme, cleanScan);
                mode = Mode.CLEAN_CONFIRM;
            }
            return true;
        }
        if (key.isChar('/')) {
            activateSearch();
            return true;
        }
        if (!key.hasCtrl() && key.isChar('?')) {
            openHelpChat(SpawnConfig.load());
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
        if (key.isKey(KeyCode.HOME) || key.isChar('g')) {
            if (!templateEntries.isEmpty()) templateTableState.select(0);
            return true;
        }
        if (key.isKey(KeyCode.END) || key.isChar('G')) {
            if (!templateEntries.isEmpty()) templateTableState.select(templateEntries.size() - 1);
            return true;
        }

        var template = selectedTemplate();
        if (template == null) return false;

        // Space: mark or unmark the template for a build of several from F5
        if (key.isChar(' ')) {
            if (!markedTemplates.remove(template.name())) markedTemplates.add(template.name());
            return true;
        }

        // F3: Show template details
        if (key.isKey(KeyCode.F3)) {
            templateDetail.open();
            mode = Mode.TEMPLATE_DETAIL;
            return true;
        }

        // F5 with marks: the row under the cursor may not be built at all, so each option checks
        // the templates it builds instead
        if (key.isKey(KeyCode.F5) && !markedTemplates.isEmpty()) {
            if (showProxyError()) return true;
            openBuildMenu(template);
            return true;
        }

        // F8 with marks: destroy the marked built templates, whatever the row under the cursor
        if (isDestroyKey(key) && !key.hasShift() && !markedTemplates.isEmpty()) {
            confirmDestroyMarkedTemplates();
            return true;
        }

        // Block all actions if there's a pending operation
        if (isBusy(template)) {
            statusMessage = "Operation in progress for " + template.name();
            return true;
        }

        // F5: Open build menu
        if (key.isKey(KeyCode.F5)) {
            if (showProxyError()) return true;
            openBuildMenu(template);
            return true;
        }

        // Enter/F4: Branch from template (only if built)
        if (key.isKey(KeyCode.ENTER) || key.isKey(KeyCode.F4)) {
            if ("not built".equals(template.buildStatus())) {
                branchSourceName = template.name();
                mode = Mode.CONFIRM_BUILD_FOR_BRANCH;
                return true;
            }
            if (vanished(template.name())) return true;
            openBranchModal(template.name());
            return true;
        }

        // Shift+F8 or Shift+Delete: Destroy all built templates
        if (isDestroyKey(key) && key.hasShift()) {
            var anyBuilt = templateEntries.stream()
                    .anyMatch(t -> !"not built".equals(t.buildStatus()));
            if (!anyBuilt) {
                statusMessage = "No templates are built.";
                return true;
            }
            openDeleteConfirm("--all");
            return true;
        }

        // F8 or Delete: Destroy template
        if (isDestroyKey(key)) {
            if ("not built".equals(template.buildStatus())) {
                statusMessage = "Template is not built.";
                return true;
            }
            if (vanished(template.name())) return true;
            openDeleteConfirm(template.name());
            return true;
        }

        // n: New child template
        if (key.isChar('n')) {
            openNewTemplateModal(template.name());
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
        if (key.isKey(KeyCode.HOME) || key.isChar('g'))    { selectFirstDataRow(tableState); return true; }
        if (key.isKey(KeyCode.END) || key.isChar('G'))   { selectLastDataRow(tableState); return true; }

        var selected = selectedEntry(tableState);
        if (selected == null) return false;

        // Space: mark or unmark the instance for F8, F7 or Shift+F7 on several
        if (key.isChar(' ')) {
            if (!markedInstances.remove(selected.name())) markedInstances.add(selected.name());
            return true;
        }
        // With marks, F8, F7 and Shift+F7 act on the marked instances and leave the cursor row alone
        if (!markedInstances.isEmpty() && ((isDestroyKey(key) && !key.hasShift()) || key.isKey(KeyCode.F7))) {
            actOnMarkedInstances(key);
            return true;
        }

        // F3: Show instance details (always accessible, even during operations)
        if (key.isKey(KeyCode.F3)) {
            instanceDetail.open();
            detailInstanceName = selected.name();
            detailAccountUses = accountUsesFor(selected);
            mode = Mode.INSTANCE_DETAIL;
            return true;
        }

        // Shift+F8 or Shift+Delete: Destroy all instances
        if (isDestroyKey(key) && key.hasShift()) {
            if (entries.isEmpty()) {
                statusMessage = "No instances to destroy.";
                return true;
            }
            openDeleteConfirm("--all-instances");
            return true;
        }
        // Block actions that mutate the selected instance if there's a pending operation
        if (isBusy(selected)) {
            statusMessage = "Operation in progress for " + selected.name();
            return true;
        }
        if (isInstanceActionKey(key, selected) && vanished(selected.name())) return true;

        // F8 or Delete: Destroy instance
        if (isDestroyKey(key)) {
            openDeleteConfirm(selected.name());
            return true;
        }
        if (key.isKey(KeyCode.F2) && key.hasShift()) {
            if (joinTask(selected)) tui.quit();
            return true;
        }
        if (key.isKey(KeyCode.F2)) {
            var target = new ActionContext(selected.name(), selected.machineType());
            if (showProxyErrorIfNeeded(target)) return true;
            pendingAction = PendingAction.SHELL;
            pendingActionTarget = target;
            tui.quit();
            return true;
        }
        if (key.isChar('a')) {
            openAccountsModal(selected.name(), Mode.BROWSE);
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            if (showProxyErrorIfNeeded(new ActionContext(selected.name(), selected.machineType()))) return true;
            if (dispatchDefaultAction(selected)) tui.quit();
            return true;
        }
        if (key.isKey(KeyCode.F4)) {
            openBranchModal(selected.name());
            return true;
        }
        if (key.isKey(KeyCode.F7) && isRunning(selected)) {
            if (key.hasShift()) restartInBackground(selected);
            else stopInBackground(selected);
            return true;
        }
        if (key.isKey(KeyCode.F6)) {
            renameSourceName = selected.name();
            if (isRunning(selected)) {
                mode = Mode.CONFIRM_STOP_FOR_RENAME;
            } else {
                rename = new RenameDialog(modal, selected.name());
                mode = Mode.RENAME;
            }
            return true;
        }
        if (key.isKey(KeyCode.F9)) {
            var actions = getActionsForInstance(selected).stream()
                    .filter(a -> !a.requiresRunning() || isRunning(selected))
                    .toList();
            if (actions.isEmpty()) {
                statusMessage = "No actions available for " + selected.name();
                return true;
            }
            actionsMenu.open(actions, instanceActions.buildActionContext(selected));
            mode = Mode.ACTIONS;
            return true;
        }
        return false;
    }

    private static boolean isDestroyKey(KeyEvent key) {
        return key.isKey(KeyCode.F8) || key.isKey(KeyCode.DELETE);
    }

    /**
     * {@code names} with each one after everything derived from it, by the parents the rows record
     * (a template's, or the template or instance an instance was branched from), so a CoW source is
     * destroyed after its copies; otherwise in the order given.
     */
    List<String> derivedFirst(List<String> names) {
        var parentOf = new java.util.HashMap<String, String>();
        for (var t : allTemplateEntries != null ? allTemplateEntries : templateEntries) parentOf.put(t.name(), t.parent());
        for (var i : allEntries != null ? allEntries : entries) parentOf.put(i.name(), i.parent());
        var depth = new java.util.HashMap<String, Integer>();
        for (var name : names) {
            var ancestors = new java.util.HashSet<String>();
            var parent = parentOf.get(name);
            while (parent != null && !parent.isEmpty() && ancestors.add(parent)) parent = parentOf.get(parent);
            depth.put(name, ancestors.size());
        }
        var ordered = new ArrayList<>(names);
        ordered.sort(java.util.Comparator.comparing(depth::get, java.util.Comparator.reverseOrder()));
        return ordered;
    }

    /** Opens the destroy confirmation for the marked built templates, derived ones first. */
    private void confirmDestroyMarkedTemplates() {
        var templates = allTemplateEntries != null ? allTemplateEntries : templateEntries;
        var marked = templates.stream().filter(t -> markedTemplates.contains(t.name())).toList();
        var busy = marked.stream().filter(this::isBusy).findFirst();
        if (busy.isPresent()) {
            statusMessage = "Operation in progress for " + busy.get().name();
            return;
        }
        var built = marked.stream().filter(TemplateInfo::isBuilt).map(TemplateInfo::name).toList();
        if (built.isEmpty()) {
            statusMessage = "No marked template is built";
            return;
        }
        deleteConfirm = DeleteConfirm.ofSeveral(modal, derivedFirst(built), DeleteConfirm.Kind.TEMPLATE);
        mode = Mode.CONFIRM_DELETE;
    }

    /** F8, F7 or Shift+F7 on the marked instances; refused while any of them has an operation under way. */
    private void actOnMarkedInstances(KeyEvent key) {
        var marked = markedInstanceEntries();
        var busy = marked.stream()
                .filter(this::isBusy)
                .findFirst();
        if (busy.isPresent()) {
            statusMessage = "Operation in progress for " + busy.get().name();
            return;
        }
        if (marked.stream().anyMatch(e -> vanished(e.name()))) return;
        if (isDestroyKey(key)) {
            deleteConfirm = DeleteConfirm.ofSeveral(modal, derivedFirst(marked.stream().map(InstanceInfo::name).toList()),
                    DeleteConfirm.Kind.INSTANCE);
            mode = Mode.CONFIRM_DELETE;
            return;
        }
        var running = marked.stream().filter(Tui::isRunning).toList();
        if (running.isEmpty()) {
            statusMessage = "No marked instance is running";
            return;
        }
        // Asked first (Sanne, #1482): the marks may be out of sight, and a stop ends what runs there
        markedStopConfirm = new MarkedStopConfirm(modal, running.stream().map(InstanceInfo::name).toList(),
                marked.stream().filter(e -> !isRunning(e)).map(InstanceInfo::name).toList(), key.hasShift());
        mode = Mode.CONFIRM_MARKED_STOP;
    }

    /** y stops or restarts the instances the confirmation lists, those still running; any other key cancels. */
    private boolean handleConfirmMarkedStopEvent(KeyEvent key) {
        mode = Mode.BROWSE;
        if (markedStopConfirm.handleKey(key) != MarkedStopConfirm.Outcome.CONFIRM) return true;
        // Checked again: a refresh may have changed them while the dialog was open
        var names = markedStopConfirm.names();
        var targets = markedInstanceEntries().stream().filter(e -> names.contains(e.name()) && isRunning(e)).toList();
        var busy = targets.stream().filter(this::isBusy).findFirst();
        if (busy.isPresent()) {
            statusMessage = "Operation in progress for " + busy.get().name();
            return true;
        }
        // The user confirmed a longer list: say which of it is left out, on the status line until the
        // next key, and in the warning log
        var targetNames = targets.stream().map(InstanceInfo::name).collect(java.util.stream.Collectors.toSet());
        var dropped = names.stream().filter(n -> !targetNames.contains(n)).toList();
        if (!dropped.isEmpty()) {
            warningLog.add((markedStopConfirm.restart() ? "Not restarted" : "Not stopped") + ", "
                    + dropped.size() + " no longer running or gone: " + String.join(", ", dropped));
            // Announced now and held until the next key, so the stops' own messages wait behind it
            holdingStatus = announceWarnings();
        }
        if (targets.isEmpty()) return true;   // the marks stay, for another try
        markedInstances.clear();
        for (var instance : targets) {
            if (markedStopConfirm.restart()) restartInBackground(instance);
            else stopInBackground(instance);
        }
        return true;
    }

    private void stopInBackground(InstanceInfo instance) {
        execInBackground("Stopping " + instance.name(),
                "Stopped " + instance.name(),
                instance.name(),
                "Stopped " + instance.name(),
                Metadata.OP_STOPPING,
                () -> incus.stop(instance.name()));
    }

    private void restartInBackground(InstanceInfo instance) {
        execInBackground("Restarting " + instance.name(),
                "Restarted " + instance.name(),
                instance.name(),
                "Restarted " + instance.name(),
                Metadata.OP_RESTARTING,
                // Its warnings (a dropped inbox, spoofing protection the host refused) to the log
                () -> InstanceLifecycle.restartForUse(incus, instance.name(), instance.machineType(),
                        instance.mcpCaller(), instance.metadata(), drawing));
    }

    /** Keys that act on the selected instance (as opposed to navigating or viewing it). */
    private boolean isInstanceActionKey(KeyEvent key, InstanceInfo selected) {
        return isDestroyKey(key) || key.isKey(KeyCode.F2)
                || key.isKey(KeyCode.ENTER) || key.isKey(KeyCode.F4) || key.isKey(KeyCode.F6)
                || key.isKey(KeyCode.F9) || key.isChar('a') || (key.isKey(KeyCode.F7) && isRunning(selected));
    }

    private void openNewTemplateModal(String parentName) {
        var locations = new ArrayList<NewTemplateModal.TemplateLocation>();
        for (var sp : SpawnConfig.load().getSearchPaths()) {
            var expanded = dev.incusspawn.config.HostResourceSetup.expandHostTilde(sp);
            var dir = java.nio.file.Path.of(expanded).resolve("images");
            locations.add(new NewTemplateModal.TemplateLocation(sp + "/images/", dir));
        }
        locations.add(new NewTemplateModal.TemplateLocation("Project (.incus-spawn/images/)",
                dev.incusspawn.config.ImageDef.projectImagesDir()));
        locations.add(new NewTemplateModal.TemplateLocation("User (~/.config/incus-spawn/images/)",
                dev.incusspawn.config.ImageDef.userImagesDir()));
        newTemplate = new NewTemplateModal(modal, theme, parentName, locations);
        mode = Mode.NEW_TEMPLATE;
    }

    private void openBranchModal(String sourceName) {
        branchSourceName = sourceName;
        var suggestedName = suggestBranchName(sourceName);
        // BranchFlow's own defaults, so the dialog offers what 'isx branch' would do (#869)
        // One read of the source serves the defaults and the account rows
        var source = incus.instanceMetadataOrThrow(sourceName);
        if (source == null) {
            mode = Mode.BROWSE;
            statusMessage = sourceName + " no longer exists";
            return;
        }
        var defaults = BranchFlow.defaultsFor(sourceName, source, imageDefs);
        var refusal = BranchFlow.vmUnsupportedOnMacOs(sourceName, defaults.machineType());
        if (refusal != null) {
            mode = Mode.BROWSE;
            refuseAtLength(refusal);
            return;
        }
        branch = new BranchModal(modal, theme, sourceName, suggestedName, defaults,
                branchAccountChoicesFor(sourceName, source));
        mode = Mode.BRANCH;
    }

    private boolean handleBranchEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        return switch (branch.handleKey(key)) {
            case HANDLED -> true;
            case CLOSE -> {
                mode = Mode.BROWSE;
                yield true;
            }
            case CONFIRM -> confirmBranch(tui);
        };
    }

    /** Enter in the branch dialog: what must hold before the TUI releases the terminal to create it. */
    private boolean confirmBranch(TuiRunner tui) {
        var name = branch.name();
        if (name.isEmpty()) return false;
        var validation = validateInstanceName(name);
        if (validation != null) {
            statusMessage = validation;
            mode = Mode.BROWSE;
            return true;
        }
        // As preflight would, but before the terminal is handed back, like the credential check below
        var refusal = BranchFlow.kvmUnsupportedOnMacOs(branch.kvm());
        if (refusal != null) {
            refuseAtLength(refusal);
            mode = Mode.BROWSE;
            return true;
        }
        if (branch.branchNetworkMode() != NetworkMode.AIRGAP) {
            if (showProxyError()) return true;
            // With the accounts chosen above, from what the dialog read when it opened: no
            // Incus round trip on the event thread. BranchFlow.preflight re-checks live state.
            var credError = branchInherited == null ? "" : BranchFlow.credentialProblem(
                    branchInherited, branch.accountOverrides(), imageDefs, toolDefLoader);
            if (!credError.isEmpty()) {
                refuseAtLength(credError);
                mode = Mode.BROWSE;
                return true;
            }
        }
        if (vanished(branchSourceName)) return true;
        pendingAction = PendingAction.BRANCH;
        pendingActionTarget = new ActionContext(name, null);
        tui.quit();
        return true;
    }

    /**
     * A refusal longer than the one-line status bar holds, such as a missing credential's remedy and
     * tool note (#1759) or a branch macOS cannot make: its first sentence on the status bar, all of
     * it in the warning log.
     */
    private void refuseAtLength(String message) {
        // Announced by this line, so not again, cut off, after the next key
        warningLog.addAnnounced(message);
        int end = message.indexOf(". ");
        statusMessage = end < 0 ? message
                : message.substring(0, end + 1) + " Press " + WarningsModal.KEY + " for the rest.";
    }

    /**
     * The account rows for a branch of {@code source}: what it would inherit, and every account
     * each credential its template's tools use could be pinned to instead. Best effort -- the
     * dialog is still worth opening without them, and {@code BranchFlow} validates what it gets.
     */
    /** What the open branch dialog's source inherits, or null when it could not be read. */
    private BranchFlow.Inherited branchInherited;

    private BranchAccountChoices branchAccountChoicesFor(String source, io.hyperfoil.tools.jjq.value.JqValue sourceInstance) {
        List<BranchAccountChoices.Row> rows;
        branchInherited = null;
        try {
            var config = SpawnConfig.load();
            // The TUI's own loader: a fresh one would re-read the tool definitions from disk.
            var setups = dev.incusspawn.config.AccountSelection.namespaceSetups(config, toolDefLoader);
            var inherited = BranchFlow.inheritedAccounts(sourceInstance, source, imageDefs);
            branchInherited = inherited;
            // allToolSetups, not find(): find() knows only YAML tools, and claude and gh are Java.
            var namespaces = dev.incusspawn.config.AccountSelection.templateNamespaces(
                    imageDefs.get(inherited.template()), imageDefs, toolDefLoader.allToolSetups()::get);
            rows = BranchAccountChoices.rowsFor(config, setups, namespaces, inherited,
                    IncusClient.configByPrefix(sourceInstance, Metadata.ACCOUNT_IDENTITY_PREFIX));
        } catch (RuntimeException e) {
            rows = List.of();
        }
        return new BranchAccountChoices(modal, theme, rows);
    }

    private boolean handleNewTemplateEvent(KeyEvent key, TuiRunner tui) {
        return switch (newTemplate.handleKey(key)) {
            case HANDLED -> true;
            case UNHANDLED -> false;
            case CLOSE -> {
                mode = Mode.BROWSE;
                yield true;
            }
            case CONFIRM -> confirmNewTemplate(tui);
        };
    }

    /** Enter in the new-template dialog: check the name and parent, then leave the creation pending. */
    private boolean confirmNewTemplate(TuiRunner tui) {
        var rawName = newTemplate.name();
        if (rawName.isEmpty()) return false;
        var name = TemplatesCommand.normalizeName(rawName);
        if (imageDefs.containsKey(name)) {
            statusMessage = "Template '" + name + "' already exists.";
            mode = Mode.BROWSE;
            return true;
        }
        var parent = newTemplate.parent();
        if (!parent.isEmpty() && !imageDefs.containsKey(parent)) {
            statusMessage = "Parent template '" + parent + "' not found.";
            mode = Mode.BROWSE;
            return true;
        }
        pendingAction = PendingAction.NEW_TEMPLATE;
        pendingActionTarget = new ActionContext(name, null);
        mode = Mode.BROWSE;
        tui.quit();
        return true;
    }

    private boolean handleRenameEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        return switch (rename.handleKey(key)) {
            case HANDLED -> true;
            case CLOSE -> {
                mode = Mode.BROWSE;
                yield true;
            }
            case CONFIRM -> confirmRename(tableState);
        };
    }

    /** Enter in the rename dialog: check the new name and rename the instance. */
    private boolean confirmRename(TableState tableState) {
        var newName = rename.name();
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
        if (vanished(renameSourceName)) return true;
        try {
            var instance = incus.rename(renameSourceName, newName);
            try {
                InstanceLifecycle.removeHostIntegration(renameSourceName, drawing);
                var config = instance.path("config");
                var buildSource = config.pathText(Metadata.BUILD_SOURCE, "");
                // First, so a failure below cannot leave the instance without the block
                // removeHostIntegration just deleted.
                if (SshKeyManager.exists()
                        && RuntimeSetup.hasSshCapability(config.pathText(Metadata.SSH_SETUP, ""), buildSource)) {
                    // The instance is stopped, so its address is the one it is stamped with.
                    // Gateway resolves Hostname before it runs the ProxyCommand (#211, #1612).
                    SshKeyManager.addHostEntry(newName, config.pathText(Metadata.STATIC_IP, ""));
                }
                AutoRemoteService.addRemotes(newName, instance, drawing);
                // The zmx device still points at the old name's directory,
                // which removeHostIntegration just deleted — re-point it.
                if (ZmxSocketForward.isZmxInstalled(buildSource)) {
                    ZmxSocketForward.configure(incus, newName, drawing);
                }
            } catch (Exception ignore) {
                // best-effort: instance is renamed, host integration may partially fail
            }
            statusMessage = "Renamed " + renameSourceName + " to " + newName;
        } catch (Exception e) {
            statusMessage = "Failed to rename: " + e.getMessage();
        }
        refreshData(tableState);
        mode = Mode.BROWSE;
        return true;
    }

    private boolean handleConfirmDeleteEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        if (deleteConfirm.handleKey(key) == DeleteConfirm.Outcome.CONFIRM) {
            // Read the target now: the destroy runs later, after another delete dialog may have
            // replaced deleteConfirm (#1248).
            var target = deleteConfirm.name();
            mode = Mode.BROWSE;
            if (!deleteConfirm.several().isEmpty()) {
                if (deleteConfirm.kind() == DeleteConfirm.Kind.TEMPLATE) markedTemplates.clear();
                else markedInstances.clear();
                destroyBatch(deleteConfirm.several(), deleteConfirm.kind());
            } else if ("--all".equals(target)) {
                var allNames = new java.util.ArrayList<>(imageDefs.keySet());
                java.util.Collections.reverse(allNames);
                destroyBatch(allNames, DeleteConfirm.Kind.TEMPLATE);
            } else if ("--all-instances".equals(target)) {
                destroyBatch(entries.stream().map(InstanceInfo::name).toList(), DeleteConfirm.Kind.INSTANCE);
            } else if (!vanished(target)) {
                execInBackground("Deleting " + target, "Deleted " + target, target, "Destroyed " + target,
                        Metadata.OP_DELETING,
                        () -> {
                            incus.delete(target, true);
                            InstanceLifecycle.removeHostIntegration(target, drawing);
                            InstanceDestroyer.refreshProxy();
                        });
            }
        }
        mode = Mode.BROWSE;
        return true;
    }

    /**
     * Destroys {@code names} in order in one background task, skipping any that are gone or locked
     * and stopping at the first failure; the proxy is refreshed once at the end.
     */
    private void destroyBatch(List<String> names, DeleteConfirm.Kind what) {
        int count = names.size();
        var kind = what.noun;
        backgroundTasks.submit("Deleting " + count + " " + kind + "(s)",
                "Deleted " + count + " " + kind + "(s)", null, () -> {
            int destroyed = 0;
            int skipped = 0;
            for (var name : names) {
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
                            InstanceLifecycle.removeHostIntegration(name, drawing);
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
            if (destroyed > 0) InstanceDestroyer.refreshProxy();
            String msg = "Destroyed " + destroyed + " " + kind + "(s)";
            if (skipped > 0) msg += " (" + skipped + " skipped, locked)";
            if (destroyed > 0 || skipped > 0) setStatusMessage(msg);
            refreshDataAfterBackground();
        });
    }

    private void openBuildMenu(TemplateInfo template) {
        buildMenu = new BuildMenu(modal, theme, template, imageDefs, templateEntries, templatesOutOfSync,
                List.copyOf(markedTemplates));
        mode = Mode.BUILD_MENU;
    }

    private boolean handleBuildMenuEvent(KeyEvent key, TuiRunner tui) {
        return switch (buildMenu.handleKey(key)) {
            case HANDLED -> true;
            case IGNORED -> false;
            case CLOSE -> {
                mode = Mode.BROWSE;
                yield true;
            }
            case BUILD -> {
                executeBuildOption(buildMenu.selected(), tui);
                yield true;
            }
        };
    }

    private void executeBuildOption(BuildMenu.BuildMenuOption option, TuiRunner tui) {
        // An option for the marks checks what it rebuilds; any other also the row under the cursor,
        // which F5 checks only while nothing is marked
        var checked = new java.util.HashSet<>(option.rebuilds());
        var cursor = selectedTemplate();
        if (!option.marked() && cursor != null) checked.add(cursor.name());
        var busy = (allTemplateEntries != null ? allTemplateEntries : templateEntries).stream()
                .filter(t -> checked.contains(t.name()))
                .filter(this::isBusy)
                .findFirst();
        if (busy.isPresent()) {
            statusMessage = "Operation in progress for " + busy.get().name();
            mode = Mode.BROWSE;
            return;
        }
        pendingAction = PendingAction.BUILD_TEMPLATE;
        pendingBuildArgs = option.buildArgs();
        if (option.marked()) markedTemplates.clear();
        mode = Mode.BROWSE;
        tui.quit();
    }

    private boolean handleCleanConfirmEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        return switch (clean.handleKey(key)) {
            case HANDLED -> true;
            case CLOSE -> {
                mode = Mode.BROWSE;
                yield true;
            }
            case CLEAN -> cleanPool(tui, tableState);
        };
    }

    /** Enter in the cleanup dialog with something checked: clean it, with the progress overlay, and show what it did. */
    private boolean cleanPool(TuiRunner tui, TableState tableState) {
        progressMessage = "Cleaning pool...";
        tui.draw(frame -> render(frame, tableState));
        CleanCommand.CleanResult cleanResult;
        try {
            cleanResult = clean.clean(incus);
        } catch (Exception e) {
            progressMessage = null;
            statusMessage = "Clean failed: " + e.getMessage();
            mode = Mode.BROWSE;
            return true;
        }
        progressMessage = null;
        if (cleanResult == null) {
            mode = Mode.BROWSE;
        } else {
            if (cleanResult.found()) refreshData(tableState);
            mode = Mode.CLEAN_RESULT;
        }
        return true;
    }

    private boolean handleConfirmStopForRenameEvent(KeyEvent key, TuiRunner tui, TableState tableState) {
        if (key.isChar('y') || key.isChar('Y')) {
            if (vanished(renameSourceName)) return true;
            mode = Mode.BROWSE;
            progressMessage = "Stopping " + renameSourceName + "...";
            tui.draw(frame -> render(frame, tableState));
            try {
                incus.stop(renameSourceName);
                progressMessage = null;
                refreshData(tableState);
                rename = new RenameDialog(modal, renameSourceName);
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
            if (showProxyError()) {
                if (mode == Mode.ERROR) {
                    return true;
                }
                if (proxyRestartInProgress) {
                    deferredBuildForBranch = true;
                }
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

    // --- Rendering ---

    private void render(dev.tamboui.terminal.Frame frame, TableState tableState) {
        // Apply background task state BEFORE layout/rendering so this frame uses fresh data
        backgroundTasks.cleanupCompleted(TASK_DISPLAY_DURATION);
        if (detailAccountsStale.getAndSet(false) && mode == Mode.INSTANCE_DETAIL) {
            var selected = selectedEntry(instanceTableState);
            detailAccountUses = selected == null ? List.of() : accountUsesFor(selected);
        }
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
            if (holdingStatus) deferredStatus = pending;
            else statusMessage = pending;
        }
        // Announce warnings that arrived since the last frame, from whichever thread raised them.
        // Unless the dialog is open, which shows them already.
        if (mode != Mode.WARNINGS) {
            if (canAnnounceWarnings()) announceWarnings();
        } else {
            warningLog.markRead();
            warningLog.takeUnannounced();
        }

        var area = frame.area();
        boolean hasStatus = statusMessage != null;
        int footerHeight = hasStatus ? 3 : 2;
        // The header band is always present — it anchors the app identity (brand chip) and
        // carries the storage gauge on the right when pool usage is available.
        int headerHeight = 1;
        boolean showLegend = anyTemplateOutdated || anyDefinitionChanged || anyParentRebuilt;
        int legendHeight = showLegend ? 1 : 0;
        int templateIdeal = templateEntries.size() + 3 + legendHeight;
        int instanceIdeal = entries.size() + 3;
        int available = area.height() - footerHeight - headerHeight;
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
                        Constraint.length(headerHeight),
                        Constraint.length(templatePanelHeight),
                        Constraint.fill(),
                        Constraint.length(footerHeight))
                .split(area);

        mainScreen.renderHeader(frame, chunks.get(0));
        mainScreen.renderTemplateTable(frame, chunks.get(1));
        mainScreen.renderInstanceTable(frame, chunks.get(2), tableState);
        mainScreen.renderToolbar(frame, chunks.get(3), tableState, hasStatus);

        if (mode != Mode.BROWSE) {
            renderModal(frame, area, tableState);
        }

        // Progress overlay — rendered on top of everything else, regardless of mode
        if (progressMessage != null) {
            modal.renderProgressOverlay(frame, area, progressMessage);
        }
    }

    /**
     * A copy-on-write caveat appended to a single-target delete confirmation:
     * because a branch (or a template built from a parent template) shares blocks
     * with its parent, deleting it may reclaim far less than its reported size.
     * Searches instances first, then templates. Returns "" when the target isn't a
     * CoW child we have usage for.
     */
    /**
     * Open the delete-confirmation modal for {@code name}, computing the CoW caveat note once now
     * rather than on every frame the modal is rendered (it depends only on {@code name} and the row
     * data, both fixed while the dialog is up).
     */
    private void openDeleteConfirm(String name) {
        deleteConfirm = new DeleteConfirm(modal, name, cowDeleteNote(name));
        mode = Mode.CONFIRM_DELETE;
    }

    private String cowDeleteNote(String name) {
        if (name == null) return "";
        // The root template carries the folded shared base image (see foldBaseWeightIntoRootTemplate):
        // most of its reported size is shared with everything derived from it and won't come back.
        if (name.equals(baseTemplateName)) {
            return " Most of this is the shared base image; deleting frees little while instances"
                    + " or templates derived from it remain.";
        }
        // The target has its own CoW descendants — e.g. another instance was branched from this
        // instance. Its exclusive usage reads ~0 because the shared blocks live in the descendants,
        // so deleting it now reclaims little; the space comes back only once they are gone too.
        // (Deletion is still safe: the survivors read live exclusive usage, so their rows absorb
        // these blocks on the next reload.) Checked before the parent note because being depended
        // upon is the more consequential caveat when deleting.
        if (hasDescendant(name, rowParentNames())) {
            return " Branches were derived from " + name + "; deleting it frees little now —"
                    + " those blocks are reclaimed only once the derived instances or templates are gone.";
        }
        String parent = null;
        long diskUsage = -1;
        var pool = allEntries != null ? allEntries : entries;
        if (pool != null) {
            for (var inst : pool) {
                if (inst.name().equals(name)) { parent = inst.parent(); diskUsage = inst.diskUsage(); break; }
            }
        }
        if (parent == null) {
            var templates = allTemplateEntries != null ? allTemplateEntries : templateEntries;
            if (templates != null) {
                for (var t : templates) {
                    if (t.name().equals(name)) { parent = t.parent(); diskUsage = t.diskUsage(); break; }
                }
            }
        }
        if (parent == null) return "";
        var hasParent = !parent.isEmpty() && !"-".equals(parent);
        if (!hasParent || diskUsage < 0) return "";
        return " Space reclaimed may be less than " + diskCell(diskUsage)
                + " — blocks shared with " + OutputFormat.oneLine(parent)
                + " stay until the parent is removed.";
    }

    /** The recorded parent name of every currently-listed instance and template (blanks included). */
    private java.util.List<String> rowParentNames() {
        var parents = new java.util.ArrayList<String>();
        var insts = allEntries != null ? allEntries : entries;
        if (insts != null) for (var i : insts) parents.add(i.parent());
        var tpls = allTemplateEntries != null ? allTemplateEntries : templateEntries;
        if (tpls != null) for (var t : tpls) parents.add(t.parent());
        return parents;
    }


    // --- Modal dialogs (centered overlay) ---

    private void renderModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen,
                              TableState tableState) {
        modal.renderScrim(frame, screen);
        switch (mode) {
            case CONFIRM_DELETE -> deleteConfirm.render(frame, screen);
            case CONFIRM_MARKED_STOP -> markedStopConfirm.render(frame, screen);
            case BUILD_MENU -> buildMenu.renderBuildMenu(frame, screen);
            case CONFIRM_BUILD_FOR_BRANCH -> {
                modal.renderConfirmModal(frame, screen,
                        " Branch from '" + branchSourceName + "' ",
                        "Template is not built yet. Build it first?", modal.border(),
                        "Build", "y/Enter");
            }
            case CONFIRM_STOP_FOR_RENAME -> {
                modal.renderConfirmModal(frame, screen,
                        " Rename '" + renameSourceName + "' ",
                        "Instance is running. Stop it first?", modal.border(),
                        "Stop & rename");
            }
            case BRANCH -> branch.render(frame, screen);
            case RENAME -> rename.render(frame, screen);
            case NEW_TEMPLATE -> newTemplate.render(frame, screen);
            case TEMPLATE_DETAIL -> {
                var template = selectedTemplate();
                if (template != null) templateDetail.render(frame, screen, template);
            }
            case INSTANCE_DETAIL -> {
                var selected = selectedEntry(instanceTableState);
                // Assessed from the same snapshot as its tag row: pure, and a few map lookups
                if (selected != null) instanceDetail.render(frame, screen, selected, detailAccountUses,
                        BaseImageAge.assess(selected.metadata(), imageDefs));
            }
            case ACCOUNTS -> accountsModal.render(frame, screen);
            case INFO -> about.render(frame, screen);
            case WARNINGS -> warningsModal.render(frame, screen);
            case HELP_CHAT -> helpChat.render(frame, screen);
            case ACTIONS -> actionsMenu.render(frame, screen);
            case TASK_PICKER -> taskPicker.render(frame, screen);
            case ERROR -> modal.renderErrorModal(frame, screen, errorMessage);
            case CLEAN_CONFIRM -> clean.renderCleanConfirmModal(frame, screen);
            case CLEAN_RESULT -> clean.renderCleanResultModal(frame, screen);
            default -> {}
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
                pendingActionTarget = new ActionContext(template.name(), null);
                mode = Mode.BROWSE;
                tui.quit();
            }
            return true;
        }
        if (key.isChar('n')) {
            var template = selectedTemplate();
            if (template != null) {
                openNewTemplateModal(template.name());
            }
            return true;
        }
        return templateDetail.handleKey(key);
    }

    // --- Instance detail modal ---

    private boolean handleInstanceDetailEvent(KeyEvent key, TuiRunner tui) {
        return switch (instanceDetail.handleKey(key)) {
            case HANDLED -> true;
            case UNHANDLED -> false;
            case CLOSE -> {
                mode = Mode.BROWSE;
                yield true;
            }
            case ACCOUNTS -> {
                var selected = selectedEntry(instanceTableState);
                if (selected == null || vanished(selected.name())) yield true;
                if (isBusy(selected)) {
                    statusMessage = "Operation in progress for " + selected.name();
                    yield true;
                }
                openAccountsModal(selected.name(), Mode.INSTANCE_DETAIL);
                yield true;
            }
            case JOIN_TASK -> {
                var selected = selectedEntry(instanceTableState);
                if (selected == null || vanished(selected.name())) yield true;
                if (isBusy(selected)) {
                    statusMessage = "Operation in progress for " + selected.name();
                    yield true;
                }
                if (joinTask(selected)) {
                    mode = Mode.BROWSE;
                    tui.quit();
                }
                yield true;
            }
            case SHELL -> {
                var selected = selectedEntry(instanceTableState);
                if (selected != null) {
                    if (vanished(selected.name())) yield true;
                    var target = new ActionContext(selected.name(), selected.machineType());
                    if (showProxyErrorIfNeeded(target)) yield true;
                    pendingAction = PendingAction.SHELL;
                    pendingActionTarget = target;
                    mode = Mode.BROWSE;
                    tui.quit();
                }
                yield true;
            }
            case DEFAULT_ACTION -> {
                var selected = selectedEntry(instanceTableState);
                if (selected != null) {
                    if (vanished(selected.name())) yield true;
                    if (showProxyErrorIfNeeded(new ActionContext(selected.name(), selected.machineType()))) yield true;
                    mode = Mode.BROWSE;
                    if (dispatchDefaultAction(selected)) tui.quit();
                }
                yield true;
            }
        };
    }

    private boolean handleInfoEvent(KeyEvent key) {
        switch (about.handleKey(key)) {
            case HANDLED -> {}
            case CLOSE -> mode = Mode.BROWSE;
            case HELP_CHAT -> openHelpChat(SpawnConfig.load());
        }
        return true;
    }

    /**
     * Opens AI Help, or explains why no configured account can answer: the cause in the status
     * bar, all of it in the warning log.
     */
    private void openHelpChat(SpawnConfig config) {
        var targets = AiHelpClient.targets(config);
        if (targets.isEmpty()) {
            // Its advice and the credits URL would fall off the status bar (#1765)
            refuseAtLength(AiHelpClient.noProvider(config).message());
            mode = Mode.BROWSE;
            return;
        }
        // Reading every definition takes a moment, so build them once, off the UI thread: the
        // same string sizes the checkbox and is what gets sent when it is ticked.
        var definitions = java.util.concurrent.CompletableFuture.supplyAsync(
                HelpContext::definitions, Thread::startVirtualThread);
        var previous = helpChat;
        var chat = new HelpChatModal(modal, theme, targets, previous != null ? previous.target() : null,
                AiHelpClient.subscriptionAccounts(config),
                (target, question, attach) -> AiHelpClient.ask(question, attach
                        ? List.of(HelpContext.documentation(), definitions.join())
                        : List.of(HelpContext.documentation()), config, target),
                Thread::startVirtualThread, () -> needsRepaint.set(true));
        definitions.thenAccept(text -> {
            chat.setAttachmentBytes(text.getBytes(StandardCharsets.UTF_8).length);
            needsRepaint.set(true);
        });
        helpChat = chat;
        mode = Mode.HELP_CHAT;
    }

    /**
     * Shift+F2: join the delegated task in {@code selected} (#1509), as {@code isx shell --task}
     * does. One task leaves its conversation pending (true: the caller quits the runner), several
     * open the picker, and anything else says why on the status line. The tasks are read here, on
     * the keypress, never while rendering the table.
     */
    private boolean joinTask(InstanceInfo selected) {
        if (selected.mcp() == null) {
            statusMessage = selected.name() + " was not made by isx mcp: it has no delegated task to join";
            return false;
        }
        if (!isRunning(selected)) {
            statusMessage = selected.name() + " is stopped; isx shell " + selected.name() + " --task starts it and joins its task";
            return false;
        }
        var target = new ActionContext(selected.name(), selected.machineType());
        List<TaskJoin.Entry> tasks;
        try {
            tasks = TaskJoin.read(incus, selected.name());
        } catch (RuntimeException e) {
            statusMessage = e.getMessage();
            return false;
        }
        var outcome = TaskJoin.resolve(tasks, null);
        if (outcome instanceof TaskJoin.Outcome.Join join) return joinPending(join.entry(), target);
        if (outcome instanceof TaskJoin.Outcome.Several several) {
            taskPicker.open(several.entries(), target, mode);
            mode = Mode.TASK_PICKER;
        } else {
            statusMessage = TaskJoin.refusal(outcome, selected.name());
        }
        return false;
    }

    /**
     * Leave {@code entry}'s conversation pending in {@code target}, once the proxy check passes
     * (only now: a refusal needs no proxy); whether it is pending.
     */
    private boolean joinPending(TaskJoin.Entry entry, ActionContext target) {
        // Again here, for the picker: a stop or destroy may have started since it opened.
        var instance = (allEntries != null ? allEntries : entries).stream()
                .filter(e -> e.name().equals(target.instanceName())).findFirst().orElse(null);
        if (instance != null && isBusy(instance)) {
            statusMessage = "Operation in progress for " + instance.name();
            return false;
        }
        if (showProxyErrorIfNeeded(target)) return false;
        pendingAction = PendingAction.SHELL_WITH_COMMAND;
        pendingShellCommand = entry.guestCommand();
        pendingCommandOnly = true;
        pendingActionTarget = target;
        return true;
    }

    private boolean handleTaskPickerEvent(KeyEvent key, TuiRunner tui) {
        return switch (taskPicker.handleKey(key)) {
            case HANDLED -> true;
            case UNHANDLED -> false;
            case CLOSE -> {
                mode = taskPicker.returnMode();
                yield true;
            }
            case JOIN -> {
                var target = taskPicker.target();
                if (vanished(target.instanceName())) yield true;
                mode = taskPicker.returnMode();
                // Read again: a turn may have started since the picker opened.
                TaskJoin.Outcome outcome;
                try {
                    outcome = TaskJoin.resolve(TaskJoin.read(incus, target.instanceName()), taskPicker.selected().id());
                } catch (RuntimeException e) {
                    statusMessage = e.getMessage();
                    yield true;
                }
                if (outcome instanceof TaskJoin.Outcome.Join join) {
                    if (joinPending(join.entry(), target)) {
                        mode = Mode.BROWSE;
                        tui.quit();
                    }
                } else {
                    statusMessage = TaskJoin.refusal(outcome, target.instanceName());
                }
                yield true;
            }
        };
    }

    private boolean handleActionsEvent(KeyEvent key, TuiRunner tui) {
        return switch (actionsMenu.handleKey(key)) {
            case HANDLED -> true;
            case UNHANDLED -> false;
            case CLOSE -> {
                mode = Mode.BROWSE;
                yield true;
            }
            case RUN -> {
                var actionsContext = actionsMenu.context();
                if (vanished(actionsContext.instanceName())) yield true;
                var action = actionsMenu.selectedAction();
                mode = Mode.BROWSE;
                if (dispatchAction(action, actionsContext)) tui.quit();
                yield true;
            }
        };
    }

    /**
     * The one template a build names: the cursor goes to it afterwards, and its failure report is
     * the one to show. A batch of several (marked templates, #1131) or a flag-only build has none,
     * so the cursor stays where it was.
     */
    static String singleBuildTarget(String[] args) {
        var names = buildTargetNames(args);
        return names.size() == 1 ? names.getFirst() : null;
    }

    /** The templates {@code isx build} arguments name, without the flags. */
    private static List<String> buildTargetNames(String[] args) {
        return java.util.Arrays.stream(args).filter(a -> !a.startsWith("--")).toList();
    }

    static String buildStatusMessage(String[] args, boolean success, java.time.Instant buildStart) {
        var firstArg = args[0];
        // isx build takes several templates (#1130): name them all.
        var targets = String.join(", ", buildTargetNames(args));
        boolean hasWithParents = java.util.Arrays.asList(args).contains("--with-parents");
        boolean hasWithDescendants = java.util.Arrays.asList(args).contains("--with-descendants");
        if (firstArg.equals("--all")) {
            return success ? "Rebuilt all templates successfully" : "Some templates failed to build";
        } else if (firstArg.equals("--out-of-sync")) {
            return success ? "Rebuilt out of sync templates successfully" : "Some templates failed to build";
        } else if (firstArg.equals("--missing")) {
            return success ? "Built missing templates successfully" : "Some templates failed to build";
        } else if (hasWithParents || hasWithDescendants) {
            var with = hasWithParents ? " with parents" : " with descendants";
            return success ? "Rebuilt " + targets + with + " successfully" : "Failed to build " + targets + with;
        } else {
            if (success) return "Built " + targets + " successfully";
            // A report is per template: with several, which one failed is in the build output.
            var report = targets.equals(firstArg) ? freshFailureReport(firstArg, buildStart) : null;
            return "Failed to build " + targets + (report != null ? ". Details: " + report : ".");
        }
    }

    /**
     * The template's host failure report, but only if this build wrote it. The build runs through
     * AeshRuntimeRunner, so all that comes back is an exit code -- and a non-zero exit need not
     * come from a failure that writes a report (conflicting definitions, a missing parent). An
     * older report left from an unrelated failure would then be named as the explanation.
     * {@code since} is floored to the second so a coarse-mtime filesystem cannot hide a report
     * written in the same second the build started.
     */
    static java.nio.file.Path freshFailureReport(String template, java.time.Instant since) {
        if (since == null) return null;
        var report = Environment.buildFailureLogFile(template);
        try {
            var written = java.nio.file.Files.getLastModifiedTime(report).toInstant();
            return written.isBefore(since.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)) ? null : report;
        } catch (java.io.IOException e) {
            return null; // no report at all
        }
    }

    /**
     * What {@code isx account show} reports for this instance, for the detail dialog. Best effort:
     * the dialog is worth opening without it, so a failure leaves the section out.
     */
    private List<dev.incusspawn.config.AccountUsage.Use> accountUsesFor(InstanceInfo info) {
        try {
            var config = SpawnConfig.load();
            var template = InstanceActions.resolveTemplateName(info);
            var templateDef = template == null ? null : imageDefs.get(template);
            var templateAccounts = templateDef == null ? Map.<String, String>of()
                    : dev.incusspawn.config.ImageDef.resolveAccounts(templateDef, imageDefs);
            // One instance read for the pins and who chose them.
            var metadata = incus.configByPrefix(info.name(), Metadata.PREFIX);
            var pins = new java.util.LinkedHashMap<String, String>();
            var pinPrefix = Metadata.ACCOUNT_PREFIX.substring(Metadata.PREFIX.length());
            metadata.forEach((k, v) -> {
                if (k.startsWith(pinPrefix) && v != null && !v.isBlank()) pins.put(k.substring(pinPrefix.length()), v.strip());
            });
            return dev.incusspawn.config.AccountUsage.of(config,
                    dev.incusspawn.config.AccountSelection.namespaceSetups(config, toolDefLoader),
                    pins, dev.incusspawn.config.AccountSelection.originsFromMetadata(metadata), templateAccounts,
                    info.name(), identities(metadata));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static Map<String, String> identities(Map<String, String> metadata) {
        var prefix = Metadata.ACCOUNT_IDENTITY_PREFIX.substring(Metadata.PREFIX.length());
        var identities = new java.util.LinkedHashMap<String, String>();
        metadata.forEach((k, v) -> {
            if (k.startsWith(prefix) && v != null && !v.isBlank()) identities.put(k.substring(prefix.length()), v.strip());
        });
        return identities;
    }

    private void openAccountsModal(String instance, Mode returnTo) {
        List<AccountsModal.Row> rows;
        try {
            var config = SpawnConfig.load();
            rows = AccountsModal.rowsFor(config, dev.incusspawn.config.AccountSelection.namespaceSetups(config, toolDefLoader),
                    dev.incusspawn.config.AccountSelection.read(incus, instance));
        } catch (RuntimeException e) {
            statusMessage = "Couldn't read credential accounts: " + e.getMessage();
            return;
        }
        if (rows.isEmpty()) {
            statusMessage = "No credential accounts configured. Run 'isx account add' to add one.";
            return;
        }
        accountsModal = new AccountsModal(modal, theme, instance, rows);
        accountsReturnMode = returnTo;
        mode = Mode.ACCOUNTS;
    }

    private boolean handleAccountsEvent(KeyEvent key) {
        switch (accountsModal.handleKey(key)) {
            case STAY -> { return true; }
            case CLOSE -> {
                closeAccountsModal();
                return true;
            }
            case APPLY -> { }
        }
        var instance = accountsModal.instance();
        var changes = accountsModal.changes();
        var config = SpawnConfig.load();
        // The TUI's own loader: a fresh one would re-read the tool definitions from disk, on this
        // thread and again on the background one.
        var setups = dev.incusspawn.config.AccountSelection.namespaceSetups(config, toolDefLoader);
        // Checked here, on the event thread, so a refusal is read in the dialog while the user
        // is still choosing; applying -- which may re-derive the git identity inside the
        // instance -- then runs in the background.
        try {
            InstanceLifecycle.checkAccountChange(incus, instance, config, setups, changes);
        } catch (dev.incusspawn.config.AccountSelection.InvalidSelectionException
                 | dev.incusspawn.config.AccountResolver.UnknownAccountException e) {
            accountsModal.setError(e.getMessage());
            return true;
        } catch (RuntimeException e) {
            accountsModal.setError("Couldn't check the change: " + e.getMessage());
            return true;
        }
        closeAccountsModal();
        if (!backgroundTasks.tryClaim(instance)) {
            statusMessage = "Operation already in progress for " + instance;
            return true;
        }
        var described = describeAccountChanges(changes);
        backgroundTasks.submit("Switching accounts of " + instance, "Switched accounts of " + instance,
                instance, () -> {
                    try {
                        InstanceLifecycle.changeAccounts(incus, instance, config, setups, changes,
                                drawing);
                        setStatusMessage(instance + ": " + described);
                    } catch (RuntimeException e) {
                        setStatusMessage("Couldn't change accounts of " + instance + ": " + e.getMessage());
                        throw e;
                    } finally {
                        backgroundTasks.releaseClaim(instance);
                        detailAccountsStale.set(true);
                        refreshDataAfterBackground();
                    }
                });
        return true;
    }

    private void closeAccountsModal() {
        mode = accountsReturnMode;
        if (mode == Mode.INSTANCE_DETAIL) {
            var selected = selectedEntry(instanceTableState);
            detailAccountUses = selected == null ? List.of() : accountUsesFor(selected);
        }
        accountsModal = null;
    }

    static String describeAccountChanges(Map<String, String> changes) {
        var parts = new ArrayList<String>();
        changes.forEach((ns, account) -> parts.add(account == null ? ns + " follows the default" : ns + "=" + account));
        return String.join(", ", parts);
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

    boolean hasActionsForInstance(InstanceInfo instance) {
        return getActionsForInstance(instance).stream()
                .anyMatch(a -> !a.requiresRunning() || isRunning(instance));
    }

    private java.util.List<ToolAction> getActionsForInstance(InstanceInfo instance) {
        return actionsCache.getOrDefault(instance.name(), java.util.List.of());
    }

    private Optional<ToolAction> findDefaultAction(String ref, InstanceInfo instance) {
        var actions = getActionsForInstance(instance);
        var result = ActionResolver.findActionByRef(ref, actions);
        if (result.isEmpty()) statusMessage = ActionResolver.unresolvedReason(ref, actions);
        return result;
    }

    private boolean dispatchDefaultAction(InstanceInfo selected) {
        var ref = defaultActionRef.get(selected.name());
        if (ref == null || ref.isBlank()) {
            pendingAction = PendingAction.SHELL;
            pendingActionTarget = new ActionContext(selected.name(), selected.machineType());
            return true;
        }
        var result = findDefaultAction(ref, selected);
        if (result.isEmpty()) {
            return false;
        }
        return dispatchAction(result.get(), instanceActions.buildActionContext(selected));
    }

    /** The definitions and tools {@link #doExecute} would load, for tests that drive action resolution. */
    void useDefinitions(Map<String, dev.incusspawn.config.ImageDef> imageDefs, ToolDefLoader toolDefLoader,
                        java.util.List<ToolSetup> cdiTools) {
        this.imageDefs = imageDefs;
        this.toolDefLoader = toolDefLoader;
        this.cdiTools = cdiTools;
    }

    /**
     * The TUI as a session starts on {@code instances}, with its services given rather than taken
     * from {@code RuntimeServices}, and without a terminal: what the characterisation tests (#959)
     * render and drive keys into. It enters the session through {@link #enterSession}, as every
     * real session does after its reload. Call {@link #useDefinitions} first.
     */
    void startSession(IncusClient incus, BackgroundTaskManager backgroundTasks, InstanceLockManager lockManager,
                      List<InstanceInfo> instances) {
        this.incus = incus;
        this.backgroundTasks = backgroundTasks;
        this.lockManager = lockManager;
        loader.mergeInstances(instances);
        publishRows();
        enterSession(null);
    }

    /** One frame, as the runner draws it on the instances table's state. */
    void render(dev.tamboui.terminal.Frame frame) {
        render(frame, instanceTableState);
    }

    /** One event, as the runner hands it over; {@code tui} is only asked to quit or draw. */
    boolean handleEvent(Event event, TuiRunner tui) {
        return handleEvent(event, tui, instanceTableState);
    }

    Mode mode() {
        return mode;
    }

    /** The delete confirmation last opened, for a test to read what it is for. */
    DeleteConfirm deleteConfirm() {
        return deleteConfirm;
    }

    /**
     * The version of isx running, which the header names and templates are judged outdated
     * against; overridable so a test can give one that no build changes.
     */
    String currentVersion() {
        return BuildInfo.instance().version();
    }

    PendingAction pendingAction() {
        return pendingAction;
    }

    /** The command a pending {@link PendingAction#SHELL_WITH_COMMAND} runs, for a test to read. */
    String pendingShellCommand() {
        return pendingShellCommand;
    }

    /** The {@code isx build} arguments a build left pending will run with, for a test to read. */
    String[] pendingBuildArgs() {
        return pendingBuildArgs;
    }

    /** The marked templates, which the context line counts and a test reads. */
    java.util.SequencedSet<String> markedTemplates() {
        return markedTemplates;
    }

    /** The marked instances, which the context line counts and a test reads. */
    java.util.SequencedSet<String> markedInstances() {
        return markedInstances;
    }

    /** Whether F7 on the marked instances would stop or restart any: one of them is running. */
    boolean anyMarkedInstanceRunning() {
        return markedInstanceEntries().stream().anyMatch(Tui::isRunning);
    }

    /** The rows of the marked instances, whether the search shows them or not. */
    private List<InstanceInfo> markedInstanceEntries() {
        return (allEntries != null ? allEntries : entries).stream()
                .filter(e -> markedInstances.contains(e.name())).toList();
    }

    /** Whether the template on row {@code row} of the templates table is marked. */
    boolean templateRowMarked(int row) {
        return row < templateEntries.size() && markedTemplates.contains(templateEntries.get(row).name());
    }

    /** Whether the instance on row {@code row} of the instances table is marked. */
    boolean instanceRowMarked(int row) {
        return row < rowToEntry.size() && rowToEntry.get(row) != null
                && markedInstances.contains(rowToEntry.get(row).name());
    }

    boolean dispatchAction(ToolAction action, ActionContext context) {
        var cmd = action.shellCommand(context);
        if (cmd.isPresent()) {
            pendingAction = PendingAction.SHELL_WITH_COMMAND;
            pendingShellCommand = cmd.get();
            pendingCommandOnly = false;
            pendingActionTarget = context;
            return true;
        }
        if (action.needsDeferredExecution()) {
            pendingAction = PendingAction.EXECUTE_ACTION;
            pendingToolAction = action;
            pendingActionTarget = context;
            return true;
        }
        // The TUI still owns the terminal: an action that would ask on stdin says what to do instead.
        var execResult = action.executeWithoutPrompting(context);
        statusMessage = execResult.message();
        return false;
    }

    private String suggestBranchName(String sourceName) {
        var base = sourceName.startsWith("tpl-") ? sourceName.substring(4) : sourceName;
        var existingNames = entries.stream().map(e -> e.name()).collect(java.util.stream.Collectors.toSet());
        for (int i = 1; ; i++) {
            var candidate = base + "-" + i;
            if (!existingNames.contains(candidate)) return candidate;
        }
    }


    // --- Helpers ---

    static boolean isRunning(InstanceInfo entry) {
        return "RUNNING".equalsIgnoreCase(entry.status());
    }

    private static boolean hasPendingOp(InstanceInfo entry) {
        return entry != null && !entry.pendingOp().isEmpty();
    }

    private static boolean hasPendingOp(TemplateInfo template) {
        return template != null && !template.pendingOp().isEmpty();
    }

    /** Whether an operation on {@code template} is under way, here or in another isx, so it must not be built now. */
    private boolean isBusy(TemplateInfo template) {
        return hasPendingOp(template) || backgroundTasks.hasRunningTask(template.name());
    }

    private boolean isBusy(InstanceInfo instance) {
        return hasPendingOp(instance) || backgroundTasks.hasRunningTask(instance.name());
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

    // --- Search / filter ---

    private void activateSearch() {
        searchActive = true;
        searchInput = new TextInputState();
    }

    private void deactivateSearch() {
        var selectedTpl = selectedTemplate();
        var selectedTplName = selectedTpl != null ? selectedTpl.name() : null;
        var selectedInst = selectedEntry(instanceTableState);
        var selectedInstName = selectedInst != null ? selectedInst.name() : null;

        searchActive = false;
        searchInput = null;
        templateEntries = allTemplateEntries;
        entries = allEntries;
        buildTemplateRowData();
        buildRowData();

        if (selectedTplName != null) {
            for (int i = 0; i < templateEntries.size(); i++) {
                if (templateEntries.get(i).name().equals(selectedTplName)) {
                    templateTableState.select(i);
                    break;
                }
            }
        }
        if (selectedInstName != null) {
            for (int i = 0; i < rowToEntry.size(); i++) {
                if (rowToEntry.get(i) != null && rowToEntry.get(i).name().equals(selectedInstName)) {
                    instanceTableState.select(i);
                    break;
                }
            }
        }
    }

    private boolean handleSearchEvent(KeyEvent key, TableState tableState) {
        if (key.isKey(KeyCode.ENTER) || key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) {
            deactivateSearch();
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE)) { searchInput.deleteBackward(); applySearchFilter(); return true; }
        if (key.isKey(KeyCode.DELETE))    { searchInput.deleteForward(); applySearchFilter(); return true; }
        if (key.isKey(KeyCode.LEFT))      { searchInput.moveCursorLeft(); return true; }
        if (key.isKey(KeyCode.RIGHT))     { searchInput.moveCursorRight(); return true; }
        if (key.isKey(KeyCode.HOME))      { searchInput.moveCursorToStart(); return true; }
        if (key.isKey(KeyCode.END))       { searchInput.moveCursorToEnd(); return true; }
        if (key.isKey(KeyCode.DOWN)) {
            if (focusedPanel == Panel.TEMPLATES) {
                var idx = templateTableState.selected();
                if (idx != null && idx < templateEntries.size() - 1) templateTableState.select(idx + 1);
            } else {
                selectNextDataRow(tableState, 1);
            }
            return true;
        }
        if (key.isKey(KeyCode.UP)) {
            if (focusedPanel == Panel.TEMPLATES) {
                var idx = templateTableState.selected();
                if (idx != null && idx > 0) templateTableState.select(idx - 1);
            } else {
                selectNextDataRow(tableState, -1);
            }
            return true;
        }
        if (key.isKey(KeyCode.TAB) || ShiftTabBindings.isShiftTab(key)) {
            focusedPanel = (focusedPanel == Panel.TEMPLATES) ? Panel.INSTANCES : Panel.TEMPLATES;
            return true;
        }
        if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
            searchInput.insert(key.character());
            applySearchFilter();
            return true;
        }
        return true;
    }

    static boolean matchesSearch(String query, String... fields) {
        if (query == null || query.isEmpty()) return true;
        if (fields == null) return false;
        var lower = query.toLowerCase(java.util.Locale.ROOT);
        for (var field : fields) {
            if (field != null && field.toLowerCase(java.util.Locale.ROOT).contains(lower)) return true;
        }
        return false;
    }

    private void applySearchFilter() {
        var query = searchInput != null ? searchInput.text() : "";
        templateEntries = allTemplateEntries.stream()
                .filter(t -> matchesSearch(query, t.name(), t.description()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        entries = allEntries.stream()
                .filter(e -> matchesSearch(query, e.name(), e.parent(), e.ipv4()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        buildTemplateRowData();
        buildRowData();
        if (templateTableState.selected() == null || templateTableState.selected() >= templateEntries.size()) {
            if (!templateEntries.isEmpty()) templateTableState.select(0);
        }
        if (instanceTableState.selected() == null || instanceTableState.selected() >= rowToEntry.size()) {
            selectFirstDataRow(instanceTableState);
        }
    }

    private void rebuildRowData() {
        rowsAgeKey = Metadata.ageRefreshKey(java.time.LocalDateTime.now());
        if (searchActive && allTemplateEntries != null) {
            applySearchFilter();
        } else {
            buildTemplateRowData();
            buildRowData();
        }
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

    InstanceInfo selectedEntry(TableState state) {
        var idx = state.selected();
        if (idx == null || idx < 0 || idx >= rowToEntry.size()) return null;
        return rowToEntry.get(idx);
    }

    TemplateInfo selectedTemplate() {
        var idx = templateTableState != null ? templateTableState.selected() : null;
        if (idx == null || idx < 0 || idx >= templateEntries.size()) return null;
        return templateEntries.get(idx);
    }

    private void refreshData(TableState tableState) {
        try {
            preservingSelection(tableState, loader::reloadData);
        } catch (IncusException e) {
            errorMessage = e.getMessage();
            mode = Mode.ERROR;
            return;
        }
        closeDialogIfTargetVanished();
    }

    /**
     * Run a data reload while keeping both panels' selections on the same names. A row that
     * disappeared falls back to the first instance.
     */
    private void preservingSelection(TableState tableState, Runnable reload) {
        var selectedInstance = selectedEntry(tableState);
        var selectedInstanceName = selectedInstance != null ? selectedInstance.name() : null;
        var selectedTpl = selectedTemplate();
        var selectedTplName = selectedTpl != null ? selectedTpl.name() : null;

        reload.run();

        // Restore template selection
        if (selectedTplName != null) {
            for (int i = 0; i < templateEntries.size(); i++) {
                if (templateEntries.get(i).name().equals(selectedTplName)) {
                    templateTableState.select(i);
                    break;
                }
            }
        }

        // Restore instance selection
        boolean reselected = false;
        if (selectedInstanceName != null) {
            for (int i = 0; i < rowToEntry.size(); i++) {
                if (rowToEntry.get(i) != null && rowToEntry.get(i).name().equals(selectedInstanceName)) {
                    tableState.select(i);
                    reselected = true;
                    break;
                }
            }
        }
        if (!reselected) selectFirstDataRow(tableState);
    }

    // --- Live refresh ---

    /**
     * Tick-time upkeep for a long-lived session: re-render ages when the minute moves on, turn
     * due follow-ups and (while the event feed is down) the fallback poll into refresh requests,
     * start a light refresh when one is requested, and swap in any finished one.
     * Returns true when the screen needs redrawing.
     */
    private boolean tickLiveRefresh(TableState tableState) {
        boolean repaint = false;
        var ageKey = Metadata.ageRefreshKey(java.time.LocalDateTime.now());
        if (rowsAgeKey != null && !ageKey.equals(rowsAgeKey)) {
            rebuildRowData();
            repaint = true;
        }

        long now = System.currentTimeMillis();
        var due = followUpRefreshes.headSet(now, true);
        if (!due.isEmpty()) {
            due.clear();
            liveRefreshRequested.set(true);
        }
        if (liveRefreshEnabled && !eventWatcher.isConnected() && now - lastDataLoadMs >= FALLBACK_POLL_MS) {
            liveRefreshRequested.set(true);
        }
        // Debounced like the background-task refresh, so a burst of events (isx clean, a
        // build's create/rename/delete) collapses into one listing.
        if (liveRefreshEnabled && liveRefreshRequested.get() && !liveRefreshInFlight.get()
                && now - lastLiveRefreshMs >= REFRESH_DEBOUNCE_MS) {
            liveRefreshRequested.set(false);
            lastLiveRefreshMs = now;
            startLiveRefresh();
        }

        var snapshot = pendingLiveSnapshot.getAndSet(null);
        if (snapshot != null) {
            applyLiveSnapshot(snapshot, tableState);
            repaint = true;
        }
        return repaint;
    }

    /** Fetch the instance listing and pool usage off the UI thread; the tick applies the result. */
    private void startLiveRefresh() {
        liveRefreshInFlight.set(true);
        long generation = dataGeneration;
        var pool = loader.usagePoolName;
        Thread.ofVirtual().name("tui-live-refresh").start(() -> {
            LiveSnapshot snapshot;
            try {
                var instances = loader.clearStalePendingOps(loader.collectEntries());
                IncusClient.PoolUsage usage = null;
                if (pool != null) {
                    try { usage = incus.getPoolUsageBytes(pool); } catch (Exception ignored) {}
                }
                snapshot = new LiveSnapshot(generation, instances, usage, null);
            } catch (RuntimeException e) {
                snapshot = new LiveSnapshot(generation, null, null, e);
            }
            pendingLiveSnapshot.set(snapshot);
            liveRefreshInFlight.set(false);
            needsRepaint.set(true);
        });
    }

    private void applyLiveSnapshot(LiveSnapshot snapshot, TableState tableState) {
        // A full reload ran while this was in flight: its data is at least as new, keep it.
        if (snapshot.generation() != dataGeneration) return;
        if (snapshot.error() != null) {
            // Automatic refreshes stay quiet: no error dialog every minute while Incus is down,
            // just one status line until a refresh succeeds again.
            lastDataLoadMs = System.currentTimeMillis();
            if (!liveRefreshErrorShown) {
                statusMessage = "Couldn't refresh from Incus: " + snapshot.error().getMessage();
                liveRefreshErrorShown = true;
            }
            return;
        }
        liveRefreshErrorShown = false;
        preservingSelection(tableState, () -> loader.applyLiveInstances(snapshot.instances(), snapshot.poolUsage()));
        closeDialogIfTargetVanished();
    }

    /**
     * The instance an open dialog acts on, or null for dialogs that aren't about one. The
     * detail and actions dialogs follow the table selection, which a refresh moves off a
     * deleted row -- so without this they would silently switch to showing another instance.
     */
    private String dialogTarget() {
        return switch (mode) {
            case CONFIRM_DELETE -> deleteConfirm.name() != null && !deleteConfirm.name().startsWith("--")
                    ? deleteConfirm.name() : null;
            case CONFIRM_STOP_FOR_RENAME, RENAME -> renameSourceName;
            case BRANCH -> branchSourceName;
            case INSTANCE_DETAIL -> detailInstanceName;
            case ACCOUNTS -> accountsModal != null ? accountsModal.instance() : null;
            case ACTIONS -> actionsMenu.context() != null ? actionsMenu.context().instanceName() : null;
            case TASK_PICKER -> taskPicker.target() != null ? taskPicker.target().instanceName() : null;
            default -> null;
        };
    }

    /** Close a dialog whose instance no longer exists, saying why rather than acting on nothing. */
    private void closeDialogIfTargetVanished() {
        var target = dialogTarget();
        if (target == null || liveInstanceNames.contains(target)) return;
        mode = Mode.BROWSE;
        statusMessage = target + " no longer exists";
    }

    /**
     * Whether the listing on screen reflects every event received: subscribed, and no refresh
     * requested, running or waiting to be applied. Then it can answer "does X exist" itself.
     */
    private boolean listIsCurrent() {
        return eventWatcher != null && eventWatcher.isConnected()
                && !liveRefreshRequested.get() && !liveRefreshInFlight.get()
                && pendingLiveSnapshot.get() == null;
    }

    /**
     * Last-moment check before acting on an instance. While {@link #listIsCurrent()} the listing
     * answers, with no Incus call, so a wedged daemon can't freeze the UI on a keypress (the only
     * gap left is an event still in transit, which the action's own error handling -- and
     * shellInto's re-check -- cover). Otherwise one existence check. On a vanished instance, say so
     * and refresh instead of letting the action fail with a raw Incus error. A failed check lets
     * the action proceed, so an Incus hiccup never blocks the UI.
     */
    private boolean vanished(String name) {
        boolean exists;
        if (listIsCurrent()) {
            exists = liveInstanceNames.contains(name);
        } else {
            try {
                exists = incus.exists(name);
            } catch (RuntimeException e) {
                return false;
            }
        }
        if (exists) return false;
        mode = Mode.BROWSE;
        statusMessage = name + " no longer exists";
        if (liveRefreshEnabled) liveRefreshRequested.set(true);
        else needsRefresh.set(true);
        return true;
    }

    // --- Data ---

    void buildTemplateRowData() {
        templateRows = new ArrayList<>();
        anyTemplateOutdated = false;
        anyDefinitionChanged = false;
        anyParentRebuilt = false;
        var defChanged = new java.util.HashSet<String>();
        var parentRebuilt = new java.util.HashSet<String>();
        var outOfSync = new java.util.LinkedHashSet<String>();

        var built = templateEntries.stream().filter(TemplateInfo::isBuilt)
                .map(t -> new TemplateStaleness.Built(t.name(), t.buildStatus(), t.buildVersion(), t.definitionSha(),
                        MachineType.fromIncus(t.runtime())))
                .toList();
        var staleness = TemplateStaleness.assess(built, imageDefs, storedSourceTemplates,
                () -> TemplateStaleness.toolFingerprints(imageDefs.values(), toolDefLoader),
                currentVersion());

        for (var t : templateEntries) {
            var statusDisplay = "not built".equals(t.buildStatus()) ? "not built" : Metadata.ageDescription(t.buildStatus());
            var statusStyle = "not built".equals(t.buildStatus())
                    ? Style.EMPTY.fg(theme.statusStopped())
                    : Style.EMPTY.fg(theme.statusRunning());
            var stale = staleness.get(t.name());
            if (stale != null) {
                var symbols = new StringBuilder();
                if (stale.versionOutdated()) {
                    symbols.append('!');
                    anyTemplateOutdated = true;
                }
                if (stale.definitionChanged()) {
                    symbols.append('△');
                    anyDefinitionChanged = true;
                    defChanged.add(t.name());
                }
                if (stale.parentRebuilt()) {
                    symbols.append('↑');
                    anyParentRebuilt = true;
                    parentRebuilt.add(t.name());
                }
                if (stale.outOfSync()) outOfSync.add(t.name());
                if (!symbols.isEmpty()) {
                    statusDisplay += " " + symbols;
                    statusStyle = Style.EMPTY.fg(theme.statusWarning());
                }
            }
            var desc = t.description() == null ? "" : t.description();

            // Apply pending operation visual indicators
            if (!t.pendingOp().isEmpty()) {
                if (Metadata.OP_DELETING.equals(t.pendingOp())) {
                    statusStyle = statusStyle.addModifier(dev.tamboui.style.Modifier.DIM)
                            .addModifier(dev.tamboui.style.Modifier.ITALIC);
                } else if (Metadata.OP_STOPPING.equals(t.pendingOp()) || Metadata.OP_RESTARTING.equals(t.pendingOp())) {
                    statusStyle = statusStyle.addModifier(dev.tamboui.style.Modifier.DIM);
                }
            }

            templateRows.add(Row.from(t.name(), statusDisplay, diskCell(t.diskUsage()), desc).style(statusStyle));
        }
        templatesDefChanged = defChanged;
        templatesParentRebuilt = parentRebuilt;
        templatesOutOfSync = outOfSync;
    }

    /** Whether any instance is owned, by an isx mcp session or another tool (#1720): then the table may show OWNER. */
    volatile boolean anyOwner;

    private void buildRowData() {
        tableRows = new ArrayList<>();
        rowToEntry = new ArrayList<>();
        // From every instance, not the search's: the column must not come and go, and every other
        // column reflow, as a search narrows the list (review of #1820)
        anyOwner = (allEntries != null ? allEntries : entries).stream().anyMatch(e -> e.mcp() != null || e.owner() != null);

        // Sort: running first, then stopped, alphabetically within each group
        var sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> {
            var aRunning = isRunning(a);
            var bRunning = isRunning(b);
            if (aRunning != bRunning) return aRunning ? -1 : 1;
            return a.name().compareToIgnoreCase(b.name());
        });

        for (var entry : sorted) {
            var age = entry.created().isEmpty() ? "-" : Metadata.ageDescription(entry.created());
            var parent = entry.parent().isEmpty() ? "-" : OutputFormat.oneLine(entry.parent());
            var statusStyle = switch (entry.status().toUpperCase()) {
                case "RUNNING" -> Style.EMPTY.fg(theme.statusRunning());
                case "STOPPED" -> Style.EMPTY.fg(theme.statusStopped());
                default -> Style.EMPTY;
            };

            // Apply pending operation visual indicators
            if (!entry.pendingOp().isEmpty()) {
                if (Metadata.OP_DELETING.equals(entry.pendingOp())) {
                    statusStyle = statusStyle.addModifier(dev.tamboui.style.Modifier.DIM)
                            .addModifier(dev.tamboui.style.Modifier.ITALIC);
                } else if (Metadata.OP_STOPPING.equals(entry.pendingOp()) || Metadata.OP_RESTARTING.equals(entry.pendingOp())) {
                    statusStyle = statusStyle.addModifier(dev.tamboui.style.Modifier.DIM);
                }
            }

            // OWNER last: the table draws it only when the panel has room (MainScreen), and draws
            // as many cells as it has columns
            tableRows.add(Row.from(entry.name(), entry.status(), entry.ipv4(),
                    parent, entry.runtime(), age, diskCell(entry.diskUsage()),
                    dev.incusspawn.command.ListCommand.ownerCell(entry)).style(statusStyle));
            rowToEntry.add(entry);
        }
    }

    private volatile boolean proxyRestartInProgress;
    private volatile boolean dnsVerified;

    private void tryFixStaleDns() {
        if (dnsVerified) return;
        dnsVerified = true;
        var allDomains = ProxyConfig.bridgeDnsDomains(SpawnConfig.load());
        try {
            if (ProxyConfig.isBridgeDnsComplete(incus, allDomains)) return;
        } catch (Exception e) {
            // Unreadable is not incomplete: a write would fail the same way.
            return;
        }
        setStatusMessage("Updating bridge DNS overrides...");
        var thread = new Thread(() -> {
            try {
                ProxyConfig.writeBridgeDns(incus, allDomains);
                setStatusMessage("Bridge DNS overrides updated");
            } catch (Exception e) {
                setStatusMessage("DNS update failed: " + e.getMessage());
                dnsVerified = false;
            }
        }, "dns-auto-heal");
        thread.setDaemon(true);
        thread.start();
    }

    // Package-private so the characterisation tests can stand in for the host's proxy (#959).
    boolean showProxyError() {
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
        if (ProxyHealthCheck.serviceRestartCanHelp(proxyStatus)) {
            proxyRestartInProgress = true;
            setStatusMessage(proxyStatus == ProxyHealthCheck.ProxyStatus.STALE_GATEWAY
                    ? "Proxy on an old bridge address, restarting service..."
                    : "Proxy not running, restarting service...");
            var thread = new Thread(() -> {
                try {
                    // Why a restart failed to the log; the status line says that it did
                    if (ProxyHealthCheck.tryAutoRestart(incus, drawing)) {
                        setStatusMessage("Proxy restarted, waiting for DNS...");
                    } else if (!Environment.hasBeenInitialized()) {
                        // The restart was refused, not failed: it would only start a proxy that refuses to (#1048).
                        setStatusMessage("Proxy not restarted: run 'isx init' first.");
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
        if (proxyStatus == ProxyHealthCheck.ProxyStatus.STALE_GATEWAY) {
            errorMessage = "The MITM proxy is running, but on an old address\n"
                    + "of the Incus bridge that instances cannot reach.\n"
                    + "\n"
                    + "Restart it to bind the current address:\n"
                    + "\n"
                    + "  isx proxy stop && isx proxy start";
        } else if (proxyStatus == ProxyHealthCheck.ProxyStatus.STALE_DNS) {
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

    private boolean showProxyErrorIfNeeded(ActionContext target) {
        try {
            var networkModeStr = incus.configGet(target.name(), Metadata.NETWORK_MODE);
            if (NetworkMode.AIRGAP.name().equals(networkModeStr)) return false;
            if (showProxyError()) return true;
            // As isx shell warns, but into the log: the TUI still owns the terminal here
            warnIfFirewallStopped((title, diagnostic) -> warningLog.add(title + "\n" + diagnostic));
            shellLaunch.repairBeforeUse(target.name(), target.machineType());
            // After the repairs, which report on the status line too: this is the one to act on
            showSubnetWarning();
            return false;
        } catch (IncusException e) {
            errorMessage = "Cannot reach Incus: " + e.getMessage();
            mode = Mode.ERROR;
            return true;
        }
    }

    /** The host-firewall advisory {@code isx shell} prints; its own method so a test need not ask the host. */
    void warnIfFirewallStopped(BiConsumer<String, String> banner) {
        FirewallDetector.warnIfNotRunning(banner);
    }

    private void showSubnetWarning() {
        try {
            var diagnostic = BridgeSubnetCheck.detectConflictDiagnostic(incus);
            if (diagnostic != null) {
                statusMessage = "Bridge subnet conflict detected — run "
                        + (Platform.isLinux() ? "'isx doctor'" : "'isx init'") + " to fix";
            }
        } catch (Exception ignored) {
        }
    }

}
