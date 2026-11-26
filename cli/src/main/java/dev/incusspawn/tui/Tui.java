package dev.incusspawn.tui;

import dev.incusspawn.command.BaseCommand;
import dev.incusspawn.command.RunCommand;
import dev.incusspawn.command.AskCommand;
import dev.incusspawn.command.Prompts;
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
import dev.incusspawn.lifecycle.HostIntegration;
import dev.incusspawn.lifecycle.InstanceDestroyer;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.lifecycle.RuntimeSetup;
import dev.incusspawn.mcp.TaskJoin;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Output;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.Report;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyLog;
import dev.incusspawn.lifecycle.ZmxSocketForward;
import dev.incusspawn.incus.ShellSession;
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
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
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
import java.util.regex.Pattern;

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
    private static final Pattern INSTANCE_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9-]*");

    // What bare `isx` said before the TUI takes the terminal; handed over just before it does.
    private PreTuiOutput preTui;

    IncusClient incus;

    ToolDefLoader toolDefLoader;
    java.util.List<ToolSetup> cdiTools;

    BackgroundTaskManager backgroundTasks;

    InstanceLockManager lockManager;

    final TuiTheme theme = TerminalThemeDetector.detect();
    /** What moves on screen; still until a runner starts it. */
    final Motion motion = new Motion(theme);
    private final ModalRenderer modal = new ModalRenderer(theme, motion);
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
                @Override public dev.incusspawn.command.MissingDefinition missingDefinition(String template) {
                    return missingDefinitions.get(template);
                }
            }, java.time.LocalDateTime::now);

    private final InstanceDetailView instanceDetail = new InstanceDetailView(modal, theme, java.time.LocalDateTime::now);

    private final ActionsMenu actionsMenu = new ActionsMenu(modal, theme);

    private final TaskPicker taskPicker = new TaskPicker(modal, theme);

    private final AboutModal about = new AboutModal(modal, theme, new AboutModal.Source() {
        @Override public String version() { return Tui.this.displayVersion(); }
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
    // their own cadences and never run per event. See design/terminal.md "Keeping a long-lived TUI current".
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
    /** Read from host state when the details open, never while they draw. */
    private List<String> detailRegisteredWith = List.of();
    /** What the instance the details show may control over MCP, when it is a coordinator (#1812). */
    private dev.incusspawn.config.McpScope detailMcpScope;

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
        CONFIRM_BUILD_FOR_BRANCH(false), BUILD_MENU(true), BRANCH(true), EDIT(true), TEMPLATE_DETAIL(false),
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
    // Edit dialog state (#2621)
    /** The instance F6 or p edits, kept while the dialog is open and through the stop-first confirm. */
    private String renameSourceName;
    /** The edit dialog: its name and purpose; set when it opens, kept through the stop-first confirm. */
    private EditDialog edit;
    /** The purpose when the dialog opened, to skip a write that changes nothing. */
    private String purposeBefore;
    /** Where the edit dialog returns: the table, or the details it was opened from. */
    private Mode editReturn = Mode.BROWSE;
    /** The new-template dialog; set when it opens, and read once more to create the template after it closes. */
    private NewTemplateModal newTemplate;
    String statusMessage;
    private String progressMessage;
    // Search/filter state
    boolean searchActive = false;
    TextInputState searchInput;
    List<TemplateInfo> allTemplateEntries;
    List<InstanceInfo> allEntries;
    // AI Help modal state
    /** Kept after closing, so reopening preselects the account used last. */
    private volatile HelpChatModal helpChat;
    /** A question every account failed, to ask the one added outside the TUI; null otherwise. */
    private HelpRetry helpRetry;

    private record HelpRetry(String question, boolean attach, List<AiHelpClient.Target> failed) {}
    // Actions cache (computed once per data refresh, not per render)
    java.util.Map<String, java.util.List<ToolAction>> actionsCache = new java.util.HashMap<>();
    // Default action reference per instance (from ImageDef default-action field)
    java.util.Map<String, String> defaultActionRef = new java.util.HashMap<>();

    private boolean deferredBuildForBranch;

    enum PendingAction { NONE, SHELL, SHELL_NEW_SESSION, SHELL_WITH_COMMAND, BRANCH, BUILD_TEMPLATE, BUILD_THEN_BRANCH, EDIT_TEMPLATE, NEW_TEMPLATE, EXECUTE_ACTION, ADD_AI_HELP_ACCOUNT }
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

    /**
     * The tabs of the main screen, in the order the tab bar shows them. Each names the panels it
     * draws: the overview both, stacked as before tabs existed, the others one at full height. A
     * view with rows of its own draws neither, and gets its own rendering and keys. Work is the
     * instance table with the overview's columns (#2241), so its rows, marks and F8 are the
     * table's own.
     */
    enum View {
        OVERVIEW("Overview", Panel.TEMPLATES, Panel.INSTANCES),
        TEMPLATES("Templates", Panel.TEMPLATES),
        INSTANCES("Instances", Panel.INSTANCES),
        WORK("Work", Panel.INSTANCES);

        final String label;
        private final List<Panel> panels;

        View(String label, Panel... panels) {
            this.label = label;
            this.panels = List.of(panels);
        }

        boolean shows(Panel panel) {
            return panels.contains(panel);
        }
    }

    /** The tab showing; every session of one TUI run keeps it. */
    View view = View.OVERVIEW;

    /** Switch to a tab. The focus follows when the tab does not draw the focused panel. */
    void show(View target) {
        // Entering the Work tab is asking for its columns: they are read then, as on o
        if (target == View.WORK && view != View.WORK) readOverview();
        view = target;
        if (!target.panels.isEmpty() && !target.shows(focusedPanel)) focusedPanel = target.panels.get(0);
    }

    /**
     * Focus a panel. A tab that does not draw it gives way to the one that draws it alone, so the
     * focused panel is always on screen.
     */
    void focus(Panel panel) {
        focusedPanel = panel;
        if (!view.shows(panel)) view = panel == Panel.TEMPLATES ? View.TEMPLATES : View.INSTANCES;
    }

    /** Tab and Shift+Tab: the other panel, which in a one-panel tab is the other such tab. */
    private void focusOtherPanel() {
        focus(focusedPanel == Panel.TEMPLATES ? Panel.INSTANCES : Panel.TEMPLATES);
    }

    /** Left and Right: the neighbouring tab, stopping at either end. */
    private void showNeighbour(int step) {
        var views = View.values();
        int target = view.ordinal() + step;
        if (target >= 0 && target < views.length) show(views[target]);
    }

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
    /** Why each built template with no loaded definition has none (#2057): its row says so, and nothing rebuilds it. */
    java.util.Map<String, dev.incusspawn.command.MissingDefinition> missingDefinitions = java.util.Map.of();
    /** Whether a row shows the ◌ of a template with no definition, which the legend then explains. */
    boolean anyMissingDefinition;
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

    /**
     * Whether the instance table shows the overview's columns (#2241, {@code o}): last use, tokens,
     * active days and GitHub, from the proxy's event log, in place of IP, parent, runtime, age and
     * disk. The rows, the marks and F8 stay the table's own.
     */
    boolean overview;
    /** The event log the overview last read, or null until a read has finished; a refresh keeps it until the next is read. */
    volatile dev.incusspawn.proxy.EventLogReader.Log overviewLog;
    /**
     * The work states read in the guests, GitHub's answer and the merged summaries, each updated
     * as it arrives (#2625), or null before the first read started. A refresh keeps the last
     * read's values until each is read again.
     */
    volatile dev.incusspawn.command.OverviewCommand.Reading overviewReading;
    /** The running instances whose read of the latest refresh has not ended, by name. */
    volatile java.util.Set<String> overviewPending = java.util.Set.of();
    /** How many running instances the latest refresh reads: the title's {@code reading 3/12 instances}. */
    volatile int overviewReadingOf;
    /** The pull requests GitHub answered merged in this session, not asked about again. */
    final dev.incusspawn.command.PullRequestStates.Settled overviewSettled = new dev.incusspawn.command.PullRequestStates.Settled();
    /** GitHub, asked whether the pull requests were merged; tests answer for it. */
    dev.incusspawn.command.PullRequestStates.GitHub overviewGitHub = dev.incusspawn.command.PullRequestStates.api();
    /** The host's config, for each instance's GitHub account; tests give their own. */
    java.util.function.Supplier<SpawnConfig> overviewConfig = SpawnConfig::load;
    /** The model that merges several sessions' closing messages, on each instance's own account; tests answer for it. */
    dev.incusspawn.command.SessionMerge.Model overviewModel = dev.incusspawn.command.SessionMerge.api();
    /** Where merged summaries are kept on the host. */
    java.util.function.Supplier<dev.incusspawn.command.SessionMerge.Cache> overviewCache = dev.incusspawn.command.SessionMerge.Cache::host;

    /** Whether the instance table shows the overview's columns: on {@code o}, or in the Work tab. */
    boolean overviewShown() {
        return overview || view == View.WORK;
    }
    /** Reads the event log; tests give their own. */
    java.util.function.Supplier<dev.incusspawn.proxy.EventLogReader.Log> eventLogReader = new java.util.function.Supplier<>() {
        /** Kept for the session, so a refresh parses only what the log gained (#2625). */
        private dev.incusspawn.proxy.EventLogReader.Incremental log;

        @Override
        public synchronized dev.incusspawn.proxy.EventLogReader.Log get() {
            if (log == null) log = new dev.incusspawn.proxy.EventLogReader.Incremental(dev.incusspawn.Environment.proxyEventLogFile());
            return log.read();
        }
    };
    /** The zone the overview's days and last use are shown in; tests fix it. */
    java.time.ZoneId overviewZone = java.time.ZoneId.systemDefault();
    /** Where the read runs: never the UI thread, which a large log would stall; tests run it in place. */
    java.util.concurrent.Executor overviewExecutor = r -> Thread.ofVirtual().name("isx-overview").start(r);

    // Storage-pool usage for the top gauge; refreshed in reloadData (not per-frame,
    // since it costs an API call). Null when no pool usage is available.
    IncusClient.PoolUsage poolUsage;
    // The root template that owns the base-image weight this reload — either folded into it
    // (foldBaseWeightIntoRootTemplate) or attributed via its own rfer (applyReferencedTemplateDeltas),
    // or null when it couldn't be attributed. Drives the CoW delete note and the ~ marker.
    String baseTemplateName;
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
                .keyboardProtocol(dev.tamboui.terminal.KeyboardProtocol.LEGACY)
                .tickRate(Duration.ofMillis(100));
    }

    /**
     * One session on {@code backend}: draws and handles keys until the runner quits. Every frame
     * ends in {@link ColorFit}, and {@link Motion} runs only here.
     */
    void drawUntilQuit(dev.tamboui.terminal.Backend backend) throws Exception {
        try (var runner = TuiRunner.create(runnerConfig().backend(backend)
                .postRenderProcessor(new ColorFit(theme.depth())).build())) {
            motion.start();
            runner.run((event, tui) -> {
                boolean redraw = event == Motion.FrameEvent.NEXT ? motion.frameDue() : handleEvent(event, tui);
                motion.pump(tui);
                return redraw;
            }, this::render);
        } finally {
            motion.stop();
        }
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
            try (var ignored = BuildOutput.bind(drawing)) {
                drawUntilQuit(new LegacyKeyboardBackend());
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
                    case SHELL_NEW_SESSION -> {
                        returnToInstance = pendingActionTarget.name();
                        shellLaunch.shellInto(pendingActionTarget, ShellSession.NEW);
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
                            statusMessage = "Failed to build: " + e.getMessage();
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
                        } catch (Exception e) { statusMessage = "Failed to edit the template: " + e.getMessage(); }
                    }
                    case EXECUTE_ACTION -> {
                        // As isx run reports it: a failure is an error, which the log keeps too
                        RunCommand.report(pendingToolAction.execute(pendingActionTarget));
                        if (pendingToolAction instanceof YamlToolAction yamlAction && !yamlAction.shouldAutoReturn()) {
                            // Its output stays on screen until the user is ready
                            PreTuiOutput.waitForEnter("Press Enter to continue...");
                        }
                    }
                    case ADD_AI_HELP_ACCOUNT -> {
                        try {
                            var config = SpawnConfig.load();
                            var added = helpRetry == null ? AskCommand.answering(config, Prompts.terminal())
                                    : AskCommand.answeringInstead(config, Prompts.terminal(), helpRetry.failed());
                            if (added == null) {
                                // The offer said why and the log keeps its error: the TUI waits,
                                // or it would draw over the offer's last lines at once
                                PreTuiOutput.waitForEnter("Press Enter to continue...");
                            }
                        } catch (Exception e) {
                            // Said where the offer was, and kept in the log, before the TUI draws over it
                            BuildOutput.error("could not add an account for AI Help: " + BaseCommand.failure(e));
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

        // The question to ask again belongs to this return alone, whether or not the reload worked
        var retry = previousAction == PendingAction.ADD_AI_HELP_ACCOUNT ? helpRetry : null;
        if (previousAction == PendingAction.ADD_AI_HELP_ACCOUNT) helpRetry = null;
        if (previousAction == PendingAction.ADD_AI_HELP_ACCOUNT && reloadError == null) {
            var config = SpawnConfig.load();
            var targets = AiHelpClient.targets(config);
            if (retry == null) {
                if (!targets.isEmpty()) openHelpChat(config);
            } else {
                // The account added answers the question the others failed, as isx ask asks it
                targets.stream().filter(t -> !retry.failed().contains(t)).findFirst().ifPresent(added -> {
                    openHelpChat(config);
                    helpChat.askAgain(retry.question(), retry.attach(), added, retry.failed());
                });
            }
        }
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
            focus(Panel.INSTANCES);
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
            focus(Panel.TEMPLATES);
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
                setStatusMessage("Failed to restart the proxy. Try: isx proxy start");
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
            case EDIT -> handleEditEvent(key, tableState);
            case NEW_TEMPLATE -> handleNewTemplateEvent(key, tui);
            case TEMPLATE_DETAIL -> handleTemplateDetailEvent(key, tui);
            case INSTANCE_DETAIL -> handleInstanceDetailEvent(key, tui);
            case ACCOUNTS -> handleAccountsEvent(key);
            case INFO -> handleInfoEvent(key, tui);
            case WARNINGS -> {
                if (!warningsModal.handleKey(key)) mode = Mode.BROWSE;
                yield true;
            }
            case HELP_CHAT -> {
                if (!helpChat.handleKey(key)) {
                    mode = Mode.BROWSE;
                    if (helpChat.addRequested()) {
                        // Every account failed the question: add one outside the TUI, then ask it again (#2605)
                        helpRetry = new HelpRetry(helpChat.question(), helpChat.attached(), helpChat.failed());
                        pendingAction = PendingAction.ADD_AI_HELP_ACCOUNT;
                        tui.quit();
                    }
                }
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
            focusOtherPanel();
            return true;
        }
        // Left and Right (h and l, beside j and k): switch tabs
        boolean plain = !key.hasCtrl() && !key.hasAlt();
        if (key.isKey(KeyCode.LEFT) || (plain && key.isChar('h'))) {
            showNeighbour(-1);
            return true;
        }
        if (key.isKey(KeyCode.RIGHT) || (plain && key.isChar('l'))) {
            showNeighbour(1);
            return true;
        }
        // Refresh data. 'r' is the TUI-idiomatic binding (k9s/lazygit/btop);
        // Ctrl+L is kept as an alias for the terminal "redraw screen" reflex; handleEvent clears.
        if ((!key.hasCtrl() && key.isCharIgnoreCase('r')) || (key.hasCtrl() && key.isCharIgnoreCase('l'))) {
            // An explicit reload reports definition problems again, so a warning that is still
            // true is announced again rather than silently deduplicated.
            Warnings.forgetReported(drawing);
            refreshData(tableState);
            if (overviewShown()) readOverview();
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
                statusMessage = "Failed to clean the pool: " + e.getMessage();
                return true;
            }
            progressMessage = null;
            if (cleanScan == null) {
                statusMessage = "No copy-on-write storage pool found.";
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
            openHelpChat(SpawnConfig.load(), tui);
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
            statusMessage = busy(template);
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
                statusMessage = "Template " + template.name() + " is not built, so there is nothing to destroy.";
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
        // o: the overview's columns, or the table's own again (#2241)
        if (key.isChar('o') && !key.hasCtrl()) {
            if (view == View.WORK) {
                show(View.INSTANCES);
                overview = false;
                return true;
            }
            overview = !overview;
            if (overview) readOverview();
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
            detailRegisteredWith = dev.incusspawn.lifecycle.HostIntegration.registeredWith(selected.name());
            detailMcpScope = mcpScopeFor(selected);
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
            statusMessage = busy(selected);
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
        // F2: a shell; n: a shell in a new session, independent of the others (#2059)
        if (key.isKey(KeyCode.F2) || key.isChar('n')) {
            var target = new ActionContext(selected.name(), selected.machineType());
            if (showProxyErrorIfNeeded(target)) return true;
            pendingAction = key.isChar('n') ? PendingAction.SHELL_NEW_SESSION : PendingAction.SHELL;
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
        if (key.isChar('p') && !key.hasCtrl()) {
            openEdit(selected, EditDialog.Field.PURPOSE, Mode.BROWSE);
            return true;
        }
        if (key.isKey(KeyCode.F6)) {
            openEdit(selected, EditDialog.Field.NAME, Mode.BROWSE);
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
            statusMessage = busy(busy.get());
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
            statusMessage = busy(busy.get());
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
            statusMessage = busy(busy.get());
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
                () -> HostIntegration.stopAll(incus, instance.name(), drawing, () -> incus.stop(instance.name())));
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
        return isDestroyKey(key) || key.isKey(KeyCode.F2) || key.isChar('n')
                || key.isKey(KeyCode.ENTER) || key.isKey(KeyCode.F4) || key.isKey(KeyCode.F6)
                || key.isKey(KeyCode.F9) || key.isChar('a') || (key.isChar('p') && !key.hasCtrl()) || (key.isKey(KeyCode.F7) && isRunning(selected));
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
        var purposeProblem = branch.purpose().isEmpty() ? null : Metadata.purposeProblem(branch.purpose());
        if (purposeProblem != null) {
            statusMessage = refusal(purposeProblem);
            mode = Mode.BROWSE;
            return true;
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
        // The name is used as typed: 'tpl-' is a convention, never added
        var name = newTemplate.name();
        if (name.isEmpty()) return false;
        var invalid = TemplatesCommand.newTemplateNameProblem(name);
        if (invalid != null) {
            statusMessage = invalid;
            mode = Mode.BROWSE;
            return true;
        }
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

    /** Open the edit dialog on {@code selected}'s name or purpose; Esc or Enter go back to {@code back}. */
    private void openEdit(InstanceInfo selected, EditDialog.Field field, Mode back) {
        renameSourceName = selected.name();
        purposeBefore = selected.purpose();
        editReturn = back;
        edit = new EditDialog(modal, theme, selected.name(), selected.purpose(), field);
        mode = Mode.EDIT;
    }

    private boolean handleEditEvent(KeyEvent key, TableState tableState) {
        switch (edit.handleKey(key)) {
            case HANDLED -> { }
            case CLOSE -> mode = editReturn;
            case CONFIRM -> confirmEdit(tableState);
        }
        return true;
    }

    /** A {@code Metadata.purposeProblem} as a status sentence. */
    private static String refusal(String problem) {
        return problem.substring(0, 1).toUpperCase() + problem.substring(1) + ".";
    }

    /**
     * Enter in the edit dialog: check the name and the purpose, then apply both, or ask to stop
     * the instance first when a new name is for a running one. Nothing is written before both
     * pass, and a purpose alone never stops the instance (#2621).
     */
    private void confirmEdit(TableState tableState) {
        var newName = edit.name();
        var purpose = edit.purpose();
        mode = editReturn;
        var problem = purpose.isEmpty() ? null : Metadata.purposeProblem(purpose);
        if (problem != null) {
            statusMessage = refusal(problem);
            return;
        }
        boolean renaming = editRenames();
        if (renaming) {
            var validation = validateInstanceName(newName);
            if (validation != null) {
                statusMessage = validation;
                return;
            }
        }
        if (!renaming && !editChangesPurpose()) return;
        if (vanished(renameSourceName)) return;
        if (renaming && isRunning(renameSourceName)) {
            mode = Mode.CONFIRM_STOP_FOR_RENAME;
            return;
        }
        applyEdit(tableState);
    }

    /** Whether the edit dialog holds a new name: Enter and the stop-first confirm ask the same. */
    private boolean editRenames() {
        return !edit.name().isEmpty() && !edit.name().equals(renameSourceName);
    }

    /** Whether the edit dialog's purpose differs from the one it opened with. */
    private boolean editChangesPurpose() {
        return !edit.purpose().equals(purposeBefore == null ? "" : purposeBefore);
    }

    /** Whether the listing shows {@code name} running. */
    private boolean isRunning(String name) {
        var entries = allEntries != null ? allEntries : this.entries;
        return entries != null && entries.stream().anyMatch(e -> e.name().equals(name) && isRunning(e));
    }

    /**
     * Write the edit dialog's purpose, when it changed, then rename the instance, when its name
     * changed; a rename needs it stopped. One metadata write for the purpose, none when unchanged
     * (#2239). A failed purpose write is reported and the rename still made, so a stop is never wasted.
     */
    private void applyEdit(TableState tableState) {
        var newName = edit.name();
        var purpose = edit.purpose();
        String purposeDone = null;
        String purposeFailed = null;
        if (editChangesPurpose()) {
            try {
                if (purpose.isEmpty()) incus.configUnset(renameSourceName, Metadata.PURPOSE);
                else incus.configSet(renameSourceName, Metadata.PURPOSE, purpose);
                purposeDone = (purpose.isEmpty() ? "Cleared the purpose of " : "Set the purpose of ") + renameSourceName;
            } catch (Exception e) {
                // The rename still goes ahead: a stop the person agreed to must not be for nothing
                purposeFailed = "Failed to set the purpose: " + e.getMessage();
            }
        }
        if (editRenames()) {
            boolean renamed = rename(newName);
            // The details followed the old name, which is gone once the rename succeeded
            if (renamed && mode == Mode.INSTANCE_DETAIL) mode = Mode.BROWSE;
            if (purposeFailed != null) {
                statusMessage = purposeFailed + ". " + statusMessage;
            } else if (purposeDone != null && renamed) {
                statusMessage += (purpose.isEmpty() ? ", and cleared its purpose" : ", and set its purpose");
            } else if (purposeDone != null) {
                statusMessage = purposeDone + ". " + statusMessage;
            }
        } else {
            statusMessage = purposeFailed != null ? purposeFailed : purposeDone;
        }
        refreshData(tableState);
    }


    /** Rename the stopped instance the edit dialog is on to {@code newName}, setting the status line; whether it was renamed. */
    private boolean rename(String newName) {
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
            return true;
        } catch (Exception e) {
            statusMessage = "Failed to rename: " + e.getMessage();
            return false;
        }
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
                execInBackground("Destroying " + target, "Destroyed " + target, target, "Destroyed " + target,
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
     * and stopping at the first failure, which the summary keeps; the proxy is refreshed once at the end.
     */
    private void destroyBatch(List<String> names, DeleteConfirm.Kind what) {
        int count = names.size();
        var kind = what.noun;
        backgroundTasks.submit("Destroying " + Report.count(count, kind),
                "Destroyed " + Report.count(count, kind), null, () -> {
            int destroyed = 0;
            int busyHere = 0;
            int lockedElsewhere = 0;
            String failure = null;
            // One settle of the pool's accounting at the end, not one per instance (#2061)
            try (var batch = incus.deleteBatch()) {
                for (var name : names) {
                    if (!backgroundTasks.tryClaim(name)) {
                        busyHere++;
                        continue;
                    }
                    Optional<InstanceLockManager.LockHandle> lockOpt;
                    try {
                        lockOpt = lockManager.tryAcquire(name, Metadata.OP_DELETING);
                    } catch (java.io.UncheckedIOException e) {
                        backgroundTasks.releaseClaim(name);
                        failure = "Failed to take the lock of " + name + ": " + e.getCause().getMessage();
                        break;
                    }
                    if (lockOpt.isEmpty()) {
                        backgroundTasks.releaseClaim(name);
                        lockedElsewhere++;
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
                                failure = "Failed to destroy " + name + ": " + e.getMessage();
                                // Now, so the failure stays on the line should clearing the mark throw too
                                setStatusMessage(failure);
                                incus.clearPendingOperation(name);
                                refreshDataAfterBackground();
                                break;
                            }
                        }
                    } finally {
                        backgroundTasks.releaseClaim(name);
                    }
                }
            }
            if (destroyed > 0) InstanceDestroyer.refreshProxy();
            var msg = destroyBatchSummary(kind, destroyed, busyHere, lockedElsewhere, failure);
            if (msg != null) setStatusMessage(msg);
            refreshDataAfterBackground();
        });
    }

    /**
     * What a batch destroy did, or null when it neither destroyed nor skipped anything: a name this
     * TUI's own task still holds is busy here, one whose lock another process holds is in use there.
     * The skip leads when nothing was destroyed, and the {@code failure} that stopped the batch, when
     * there was one, leads all: the line then reads as an error and says why the rest was not destroyed.
     */
    static String destroyBatchSummary(String kind, int destroyed, int busyHere, int lockedElsewhere,
                                      String failure) {
        var done = destroyBatchSummary(kind, destroyed, busyHere, lockedElsewhere);
        if (failure == null) return done;
        if (done == null) return failure;
        var lead = failure.stripTrailing();
        return (lead.endsWith(".") ? lead.substring(0, lead.length() - 1) : lead) + ". " + done;
    }

    static String destroyBatchSummary(String kind, int destroyed, int busyHere, int lockedElsewhere) {
        var skips = new java.util.ArrayList<String>();
        if (busyHere > 0) skips.add(Report.count(busyHere, kind) + " busy with another change");
        if (lockedElsewhere > 0) skips.add(Report.count(lockedElsewhere, kind) + " in use by another isx process");
        if (skips.isEmpty()) return destroyed > 0 ? "Destroyed " + Report.count(destroyed, kind) : null;
        var skipped = "skipped " + String.join(" and ", skips);
        return destroyed > 0
                ? "Destroyed " + Report.count(destroyed, kind) + "; " + skipped
                : "S" + skipped.substring(1);
    }

    private void openBuildMenu(TemplateInfo template) {
        buildMenu = new BuildMenu(modal, theme, template, imageDefs, templateEntries, templatesOutOfSync,
                List.copyOf(markedTemplates), missingDefinitions);
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
            statusMessage = busy(busy.get());
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

    /**
     * The template panel's height where both panels show: half of what there is, so the screen is
     * split in its middle whatever the lists hold. Only a list that does not fit in its half
     * takes rows from the other, and only rows the other leaves empty but for one, so neither panel
     * is ever squeezed below what it needs or below its half (design/terminal.md "The main screen's tabs").
     */
    static int templatePanelHeight(int templateIdeal, int instanceIdeal, int available) {
        int half = available / 2;
        int otherHalf = available - half;
        boolean templatesFit = templateIdeal <= half;
        boolean instancesFit = instanceIdeal <= otherHalf;
        if (templatesFit == instancesFit) return half;
        // The lighter panel keeps one empty row under its list, which says the list ends there
        if (instancesFit) return Math.max(half, Math.min(templateIdeal, available - instanceIdeal - 1));
        return Math.min(half, Math.max(templateIdeal + 1, available - instanceIdeal));
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
            statusMessage = "Failed to clean the pool: " + e.getMessage();
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
            mode = editReturn;
            progressMessage = "Stopping " + renameSourceName + "...";
            tui.draw(frame -> render(frame, tableState));
            try {
                // As any stop isx makes: an open herdr must not start it again before the rename (#2041)
                var source = renameSourceName;
                HostIntegration.stopAll(incus, source, drawing, () -> incus.stop(source));
                progressMessage = null;
                applyEdit(tableState);
            } catch (Exception e) {
                progressMessage = null;
                statusMessage = "Failed to stop " + renameSourceName + ": " + e.getMessage();
                mode = editReturn;
            }
        } else {
            // Back where the edit was opened from: the table, or the details (#2621)
            mode = editReturn;
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
        motion.beginFrame();
        // Apply background task state BEFORE layout/rendering so this frame uses fresh data
        backgroundTasks.cleanupCompleted(TASK_DISPLAY_DURATION);
        if (detailAccountsStale.getAndSet(false) && mode == Mode.INSTANCE_DETAIL) {
            var selected = selectedEntry(instanceTableState);
            detailAccountUses = selected == null ? List.of() : accountUsesFor(selected);
            detailMcpScope = selected == null ? null : mcpScopeFor(selected);
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
        // A rule ends the last panel, so the messages and keys under it are not read as its rows
        int footerHeight = (hasStatus ? 3 : 2) + (area.height() >= MainScreen.FOOTER_RULE_MIN_HEIGHT ? 1 : 0);
        // The header band is always present — it anchors the app identity (brand chip) and
        // carries the storage gauge on the right when pool usage is available.
        int headerHeight = 1;
        boolean showLegend = anyTemplateOutdated || anyDefinitionChanged || anyParentRebuilt || anyMissingDefinition;
        int legendHeight = showLegend ? 1 : 0;
        // A panel's own rows: its title rule and the table's header
        int templateIdeal = templateEntries.size() + MainScreen.PANEL_ROWS + legendHeight;
        int instanceIdeal = entries.size() + MainScreen.PANEL_ROWS;
        int tabsHeight = 1;
        int available = area.height() - footerHeight - headerHeight - tabsHeight;
        int templatePanelHeight;
        if (!view.shows(Panel.INSTANCES)) {
            templatePanelHeight = available;
        } else if (!view.shows(Panel.TEMPLATES)) {
            templatePanelHeight = 0;
        } else {
            templatePanelHeight = templatePanelHeight(templateIdeal, instanceIdeal, available);
        }
        var chunks = Layout.vertical()
                .constraints(
                        Constraint.length(headerHeight),
                        Constraint.length(tabsHeight),
                        Constraint.length(templatePanelHeight),
                        Constraint.fill(),
                        Constraint.length(footerHeight))
                .split(area);

        mainScreen.renderHeader(frame, chunks.get(0));
        mainScreen.renderTabs(frame, chunks.get(1));
        if (view.shows(Panel.TEMPLATES)) mainScreen.renderTemplateTable(frame, chunks.get(2));
        if (view.shows(Panel.INSTANCES)) mainScreen.renderInstanceTable(frame, chunks.get(3), tableState);
        mainScreen.renderToolbar(frame, chunks.get(4), tableState, hasStatus);

        if (mode != Mode.BROWSE) {
            renderModal(frame, area, tableState);
        }

        // Progress overlay — rendered on top of everything else, regardless of mode
        if (progressMessage != null) {
            modal.renderProgressOverlay(frame, area, progressMessage);
        }
        motion.process(frame.buffer());
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
                        "Instance is running. Stop it to rename it?", modal.border(),
                        "Stop & rename");
            }
            case BRANCH -> branch.render(frame, screen);
            case EDIT -> edit.render(frame, screen);
            case NEW_TEMPLATE -> newTemplate.render(frame, screen);
            case TEMPLATE_DETAIL -> {
                var template = selectedTemplate();
                if (template != null) templateDetail.render(frame, screen, template);
            }
            case INSTANCE_DETAIL -> {
                var selected = selectedEntry(instanceTableState);
                // The overview's last read, if there was one (#2241): pure, from the parked reading
                var reading = overviewReading;
                var log = overviewLog;
                // Assessed from the same snapshot as its tag row: pure, and a few map lookups
                if (selected != null) {
                    var effort = log == null ? null : dev.incusspawn.command.OverviewCommand.effortOf(log, selected);
                    var github = InstanceDetailView.githubRows(effort, effort == null || reading == null ? List.of()
                            : dev.incusspawn.command.OverviewCommand.statesOf(reading, selected, effort));
                    instanceDetail.render(frame, screen, selected, detailAccountUses,
                            detailRegisteredWith, BaseImageAge.assess(selected.metadata(), imageDefs), detailMcpScope,
                            github, reading == null ? null : reading.summaryOf(selected));
                }
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
                    statusMessage = busy(selected);
                    yield true;
                }
                openAccountsModal(selected.name(), Mode.INSTANCE_DETAIL);
                yield true;
            }
            case PURPOSE -> {
                var selected = selectedEntry(instanceTableState);
                if (selected == null || vanished(selected.name())) yield true;
                if (isBusy(selected)) {
                    statusMessage = busy(selected);
                    yield true;
                }
                openEdit(selected, EditDialog.Field.PURPOSE, Mode.INSTANCE_DETAIL);
                yield true;
            }
            case JOIN_TASK -> {
                var selected = selectedEntry(instanceTableState);
                if (selected == null || vanished(selected.name())) yield true;
                if (isBusy(selected)) {
                    statusMessage = busy(selected);
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

    private boolean handleInfoEvent(KeyEvent key, TuiRunner tui) {
        switch (about.handleKey(key)) {
            case HANDLED -> {}
            case CLOSE -> mode = Mode.BROWSE;
            case HELP_CHAT -> openHelpChat(SpawnConfig.load(), tui);
        }
        return true;
    }

    /**
     * Opens AI Help, or, when no configured account can answer, leaves the TUI to ask for one
     * where the person needs it, and opens AI Help on return once one can (#2541).
     */
    private void openHelpChat(SpawnConfig config, TuiRunner tui) {
        if (AiHelpClient.targets(config).isEmpty()) {
            pendingAction = PendingAction.ADD_AI_HELP_ACCOUNT;
            mode = Mode.BROWSE;
            tui.quit();
            return;
        }
        openHelpChat(config);
    }

    private void openHelpChat(SpawnConfig config) {
        var targets = AiHelpClient.targets(config);
        // Reading every definition takes a moment, so build them once, off the UI thread: the
        // same string sizes the checkbox and is what gets sent when it is ticked.
        var definitions = java.util.concurrent.CompletableFuture.supplyAsync(
                HelpContext::definitions, Thread::startVirtualThread);
        var previous = helpChat;
        // The account chosen last in this TUI while it can still answer, else the one that last answered (#2607)
        var initial = previous != null && targets.contains(previous.target()) ? previous.target()
                : dev.incusspawn.ai.AiHelpChoice.preferred(targets).orElse(null);
        var chat = new HelpChatModal(modal, theme, targets, initial,
                AiHelpClient.subscriptionAccounts(config),
                (target, question, attach) -> {
                    var response = AiHelpClient.ask(question, attach
                            ? List.of(HelpContext.documentation(), definitions.join())
                            : List.of(HelpContext.documentation()), config, target);
                    // Only an answer with text makes this the account asked first next time
                    if (!response.content().isBlank()) dev.incusspawn.ai.AiHelpChoice.answered(target);
                    return response;
                },
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
            statusMessage = busy(instance);
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
     * What a coordinator may control, judged as {@code isx mcp} judges it: its lineage from the
     * listing, its template's definition and {@code mcp.yaml} as they are now; null for an instance
     * that is no coordinator, or when {@code mcp.yaml} or the definitions cannot be read.
     */
    private dev.incusspawn.config.McpScope mcpScopeFor(InstanceInfo info) {
        if (!info.mcpCaller() || info.metadata() == null) return null;
        try {
            var configs = new java.util.HashMap<String, io.hyperfoil.tools.jjq.value.JqValue>();
            for (var entry : allEntries == null ? List.<InstanceInfo>of() : allEntries) {
                if (entry.metadata() != null) configs.put(entry.name(), entry.metadata().path("config"));
            }
            var lineage = dev.incusspawn.proxy.InstanceRegistry.lineageOf(info.metadata().path("config"), configs);
            return dev.incusspawn.config.McpScope.coordinator(lineage,
                    dev.incusspawn.config.ImageDef.loadTrusted(), dev.incusspawn.config.McpConfig.load().templates());
        } catch (RuntimeException e) {
            // As accountUsesFor: the details are worth showing without it (mcp.yaml unusable, a load failing)
            return null;
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
            statusMessage = "Failed to read the credential accounts: " + e.getMessage();
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
            statusMessage = busy(instance);
            return true;
        }
        var described = describeAccountChanges(changes);
        backgroundTasks.submit("Switching accounts of " + instance, "Switched accounts of " + instance,
                instance, () -> {
                    try {
                        var change = InstanceLifecycle.changeAccounts(incus, instance, config, setups, changes,
                                drawing);
                        // A program already running inside keeps what a re-derive rewrote (agy's mode)
                        change.restarts().forEach(restart -> drawing.warn(restart));
                        setStatusMessage(instance + ": " + described);
                    } catch (RuntimeException e) {
                        setStatusMessage("Failed to change the accounts of " + instance + ": " + e.getMessage());
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
            detailMcpScope = selected == null ? null : mcpScopeFor(selected);
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
            var hostPath = GitRemoteUtils.resolveHostRepoPath(repoName, cloneUrl, config);
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
     * The POM version templates are judged outdated against; overridable so a test can give
     * one that no build changes.
     */
    String currentVersion() {
        return BuildInfo.instance().version();
    }

    /** The version shown in the header and About dialog, including a snapshot's commit count. */
    String displayVersion() {
        return BuildInfo.instance().displayVersion();
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

    /** The status line for {@code name} while a change to it is under way. */
    static String busy(String name) {
        return busy(name, "");
    }

    private static String busy(InstanceInfo instance) {
        return busy(instance.name(), instance.pendingOp());
    }

    private static String busy(TemplateInfo template) {
        return busy(template.name(), template.pendingOp());
    }

    /**
     * {@code name} is refused while an operation is under way: what is being done to it when its mark
     * ({@code op}, a {@code Metadata.OP_*}) says, as {@code isx mcp} words it, else that it is busy.
     */
    static String busy(String name, String op) {
        var done = Metadata.opDone(op);
        return name + (done == null ? " is busy with another change." : " is being " + done + ".")
                + " Try again when it is done.";
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
            statusMessage = busy(targetName);
            return;
        }

        backgroundTasks.submit(displayName, completedDisplayName, targetName, () -> {
            Optional<InstanceLockManager.LockHandle> lockOpt;
            try {
                lockOpt = lockManager.tryAcquire(targetName, pendingOp);
            } catch (java.io.UncheckedIOException e) {
                backgroundTasks.releaseClaim(targetName);
                setStatusMessage("Failed to take the lock of " + targetName + ": " + e.getCause().getMessage());
                refreshDataAfterBackground();
                throw e;
            }
            if (lockOpt.isEmpty()) {
                backgroundTasks.releaseClaim(targetName);
                setStatusMessage(targetName + " is in use by another isx process. Try again when it is done.");
                refreshDataAfterBackground();
                throw new IllegalStateException("In use by another isx process");
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
        if (name.length() > 63) return "Invalid name: longer than 63 characters";
        if (!INSTANCE_NAME.matcher(name).matches())
            return "Invalid name: start it with a letter, and use only letters, digits and hyphens";
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
            focusOtherPanel();
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

    /** The instance under the cursor, or null. */
    InstanceInfo selectedInstance() {
        return selectedEntry(instanceTableState);
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

    /**
     * Reads the overview's columns off the UI thread (#2625). The event log, the running guests
     * and the host's config are read at once; GitHub is asked as soon as the log is in, and each
     * guest's row, with its cached merged summary, is shown as soon as that guest answers, while a
     * merge the model must make is asked for then. A stopped instance's row is shown at once.
     * Until a value is read again the table keeps the last read's, and the title counts the
     * guests still being read. Only on {@code o}, entering the Work tab and {@code r}, never on
     * a live refresh, so nothing is read or asked unless a person asks.
     */
    private void readOverview() {
        var reader = eventLogReader;
        var client = incus;
        var github = overviewGitHub;
        var configs = overviewConfig;
        var model = overviewModel;
        var caches = overviewCache;
        var settled = overviewSettled;
        var rows = entries == null ? List.<InstanceInfo>of() : List.copyOf(entries);
        var running = viewportFirst(rows.stream().filter(i -> "running".equalsIgnoreCase(i.status())).toList());
        var stopped = new java.util.HashMap<String, dev.incusspawn.command.GuestRead>();
        for (var row : rows) {
            if (!"running".equalsIgnoreCase(row.status())) stopped.put(row.name(), dev.incusspawn.command.GuestRead.NOT_READ);
        }
        // Only the latest read is kept: an earlier one that ends later would show older values
        long read;
        synchronized (overviewLock) {
            read = overviewReads.incrementAndGet();
            var pending = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
            running.forEach(i -> pending.add(i.name()));
            overviewPending = pending;
            overviewReadingOf = running.size();
            var base = overviewReading != null ? overviewReading : new dev.incusspawn.command.OverviewCommand.Reading(
                    overviewLog != null ? overviewLog : dev.incusspawn.proxy.EventLogReader.Log.EMPTY,
                    java.util.Map.of(), java.util.Map.of());
            overviewReading = base.withGuests(stopped);
        }
        needsRepaint.set(true);
        overviewExecutor.execute(() -> {
            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                // The config is for GitHub and the merges alone: neither the log nor a guest waits for it
                var setup = new java.util.concurrent.CompletableFuture<OverviewSetup>();
                pool.submit(() -> {
                    try {
                        var host = configs.get();
                        if (host != null) {
                            // A cache that cannot be opened costs the merges alone: GitHub is still asked
                            dev.incusspawn.command.SessionMerge.Merger merger = null;
                            try {
                                merger = new dev.incusspawn.command.SessionMerge.Merger(host, model, caches.get(), warningLog::add);
                            } catch (RuntimeException e) {
                                warningLog.add("Could not merge the instances' sessions: " + e.getMessage());
                            }
                            setup.complete(new OverviewSetup(host, merger));
                        }
                    } catch (RuntimeException e) {
                        warningLog.add("Could not read the host's config: " + e.getMessage());
                    } finally {
                        // Whatever went wrong, nothing waits for it for ever
                        setup.complete(new OverviewSetup(null, null));
                    }
                });
                pool.submit(() -> {
                    dev.incusspawn.proxy.EventLogReader.Log log;
                    try {
                        log = reader.get();
                    } catch (RuntimeException e) {
                        log = dev.incusspawn.proxy.EventLogReader.Log.EMPTY;
                        warningLog.add("Could not read the proxy's event log: " + e.getMessage());
                    }
                    var got = log;
                    synchronized (overviewLock) {
                        if (overviewReads.get() != read) return;
                        overviewLog = got;
                        overviewReading = overviewReading.withLog(got);
                    }
                    needsRepaint.set(true);
                    var config = setup.join().config();
                    if (config == null) return;
                    try {
                        var pulls = dev.incusspawn.command.PullRequestStates.read(rows, got, config, github, warningLog::add, settled);
                        publishOverview(read, r -> r.withPulls(pulls));
                    } catch (RuntimeException e) {
                        warningLog.add("Could not ask GitHub whether pull requests were merged: " + e.getMessage());
                    }
                });
                pool.submit(() -> {
                    var byName = new java.util.HashMap<String, InstanceInfo>();
                    running.forEach(i -> byName.put(i.name(), i));
                    dev.incusspawn.command.GuestRead.readEach(client, running, (name, guest) -> {
                        synchronized (overviewLock) {
                            if (overviewReads.get() != read) return;
                            overviewPending.remove(name);
                            overviewReading = overviewReading.withGuests(java.util.Map.of(name, guest));
                        }
                        needsRepaint.set(true);
                        var merger = setup.join().merger();
                        if (merger == null) return;
                        // The exact columns are shown first: a model call never holds them back
                        try {
                            var step = merger.step(byName.get(name), guest);
                            var merged = step.call() == null ? step.known() : step.call().get();
                            publishOverview(read, r -> r.withMerge(name, merged));
                        } catch (RuntimeException e) {
                            warningLog.add("Could not merge " + name + "'s sessions: " + e.getMessage());
                        }
                    });
                });
                // A stopped instance's last merged summary is on disk: shown without waiting for any guest
                pool.submit(() -> {
                    var merger = setup.join().merger();
                    if (merger == null) return;
                    for (var row : rows) {
                        if (!stopped.containsKey(row.name())) continue;
                        var known = merger.step(row, dev.incusspawn.command.GuestRead.NOT_READ).known();
                        publishOverview(read, r -> r.withMerge(row.name(), known));
                    }
                });
            }
        });
    }

    /** The host's config for one read of the overview, and the merger made with it; both null when it could not be read. */
    private record OverviewSetup(SpawnConfig config, dev.incusspawn.command.SessionMerge.Merger merger) {}

    /** Changes the shown reading, unless a later read has started since read {@code read} did. */
    private void publishOverview(long read,
                                 java.util.function.UnaryOperator<dev.incusspawn.command.OverviewCommand.Reading> change) {
        synchronized (overviewLock) {
            if (overviewReads.get() != read) return;
            overviewReading = change.apply(overviewReading);
        }
        needsRepaint.set(true);
    }

    /**
     * {@code running} in the table's order from its first visible row, then the rows above it,
     * then those the table does not show, so the rows on screen take the first read slots.
     */
    private List<InstanceInfo> viewportFirst(List<InstanceInfo> running) {
        var wanted = new java.util.LinkedHashSet<>(running);
        var ordered = new java.util.LinkedHashSet<InstanceInfo>();
        int size = rowToEntry == null ? 0 : rowToEntry.size();
        int first = instanceTableState == null ? 0 : Math.max(0, Math.min(instanceTableState.offset(), size));
        for (int k = 0; k < size; k++) {
            var instance = rowToEntry.get((first + k) % size);
            if (instance != null && wanted.contains(instance)) ordered.add(instance);
        }
        ordered.addAll(wanted);
        return List.copyOf(ordered);
    }

    /**
     * Whether the instance's guest is still being read with nothing worth showing yet: no
     * earlier read, or one from before it started. Its WORK and DONE cells then say {@code …}.
     */
    boolean overviewAwaits(InstanceInfo instance) {
        if (!overviewPending.contains(instance.name())) return false;
        var reading = overviewReading;
        var guest = reading == null ? null : reading.guests().get(instance.name());
        return guest == null || guest.work().state() == dev.incusspawn.command.WorkState.State.NOT_READ;
    }

    /** Guards the read-modify-write of {@link #overviewReading} by the reads' threads. */
    private final Object overviewLock = new Object();

    /** How many reads of the event log were started, so a read that ends after a later one is dropped. */
    private final java.util.concurrent.atomic.AtomicLong overviewReads = new java.util.concurrent.atomic.AtomicLong();

    /** The instance on this row of the table, or null. */
    InstanceInfo instanceOnRow(int row) {
        return rowToEntry != null && row < rowToEntry.size() ? rowToEntry.get(row) : null;
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
     * Run a data reload while keeping both panels' selections on the same names. When the selected
     * instance disappeared, as a deleted one does, the cursor goes to its nearest neighbour still
     * listed: the next one, or the previous one when it was the last (#2622).
     */
    private void preservingSelection(TableState tableState, Runnable reload) {
        var selectedInstance = selectedEntry(tableState);
        var selectedInstanceName = selectedInstance != null ? selectedInstance.name() : null;
        // A reload builds a new row list, so this one still holds the rows as they were
        var rowsBefore = rowToEntry;
        var rowBefore = tableState.selected();
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

        // Restore instance selection, else the nearest neighbour still listed: below it, then above
        if (selectedInstanceName == null) {
            selectFirstDataRow(tableState);
            return;
        }
        if (selectInstanceNamed(tableState, selectedInstanceName)) return;
        for (int i = rowBefore + 1; i < rowsBefore.size(); i++) {
            if (rowsBefore.get(i) != null && selectInstanceNamed(tableState, rowsBefore.get(i).name())) return;
        }
        for (int i = rowBefore - 1; i >= 0; i--) {
            if (rowsBefore.get(i) != null && selectInstanceNamed(tableState, rowsBefore.get(i).name())) return;
        }
        selectFirstDataRow(tableState);
    }

    private boolean selectInstanceNamed(TableState tableState, String name) {
        for (int i = 0; i < rowToEntry.size(); i++) {
            if (rowToEntry.get(i) != null && rowToEntry.get(i).name().equals(name)) {
                tableState.select(i);
                return true;
            }
        }
        return false;
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
                statusMessage = "Failed to refresh from Incus: " + snapshot.error().getMessage();
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
            case CONFIRM_STOP_FOR_RENAME, EDIT -> renameSourceName;
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
        anyMissingDefinition = false;
        var defChanged = new java.util.HashSet<String>();
        var parentRebuilt = new java.util.HashSet<String>();
        var outOfSync = new java.util.LinkedHashSet<String>();

        // Every template's row is made, the search's or not: the columns are as wide as the widest
        // cell of them all, so they hold still while a search narrows the list (review of #2511)
        var every = new ArrayList<TemplateInfo>(allTemplateEntries != null ? allTemplateEntries : templateEntries);
        var known = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<TemplateInfo, Boolean>());
        known.addAll(every);
        for (var t : templateEntries) if (known.add(t)) every.add(t);
        var rows = new java.util.IdentityHashMap<TemplateInfo, Row>();
        var natural = new int[TEMPLATE_COLUMNS];

        var built = every.stream().filter(TemplateInfo::isBuilt)
                .map(t -> new TemplateStaleness.Built(t.name(), t.buildStatus(), t.buildVersion(), t.definitionSha(),
                        MachineType.fromIncus(t.runtime())))
                .toList();
        var staleness = TemplateStaleness.assess(built, imageDefs, storedSourceTemplates,
                () -> TemplateStaleness.toolFingerprints(imageDefs.values(), toolDefLoader),
                currentVersion());

        for (var t : every) {
            var statusDisplay = "not built".equals(t.buildStatus()) ? "not built" : Metadata.ageDescription(t.buildStatus());
            var statusStyle = Style.EMPTY.fg("not built".equals(t.buildStatus()) ? theme.textFaint() : theme.textDim());
            var rowStyle = Style.EMPTY;
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
                // Nothing rebuilds a template with no definition, so --out-of-sync does not count it
                if (stale.outOfSync() && !missingDefinitions.containsKey(t.name())) outOfSync.add(t.name());
                if (missingDefinitions.containsKey(t.name())) {
                    symbols.append('◌');
                    anyMissingDefinition = true;
                }
                if (!symbols.isEmpty()) {
                    statusDisplay += " " + symbols;
                    statusStyle = Style.EMPTY.fg(theme.statusWarning());
                }
            }
            var desc = t.description() == null ? "" : t.description();

            // Apply pending operation visual indicators
            if (Metadata.OP_DELETING.equals(t.pendingOp())) {
                rowStyle = rowStyle.addModifier(dev.tamboui.style.Modifier.DIM)
                        .addModifier(dev.tamboui.style.Modifier.ITALIC);
            } else if (Metadata.OP_STOPPING.equals(t.pendingOp()) || Metadata.OP_RESTARTING.equals(t.pendingOp())) {
                rowStyle = rowStyle.addModifier(dev.tamboui.style.Modifier.DIM);
            }

            boolean isBuilt = !"not built".equals(t.buildStatus());
            var quiet = Style.EMPTY.fg(theme.textDim());
            var row = Row.from(cell(t.name(), isBuilt ? rowStyle : rowStyle.fg(theme.textDim())),
                    cell(statusDisplay, statusStyle), cell(diskCell(t.diskUsage()), quiet), cell(desc, quiet)).style(rowStyle);
            rows.put(t, row);
            for (int c = 0; c < natural.length; c++) natural[c] = Math.max(natural[c], row.cells().get(c).width());
        }
        for (var t : templateEntries) templateRows.add(rows.get(t));
        templateNatural = natural;
        templatesDefChanged = defChanged;
        templatesParentRebuilt = parentRebuilt;
        templatesOutOfSync = outOfSync;
    }

    /** Whether any instance is owned, by an isx mcp session or another tool (#1720): then the table may show OWNER. */
    volatile boolean anyOwner;

    /** Whether any instance has a purpose (#2239): then the table may show PURPOSE. */
    volatile boolean anyPurpose;

    private void buildRowData() {
        tableRows = new ArrayList<>();
        rowToEntry = new ArrayList<>();
        // From every instance, not the search's: the column must not come and go, and every other
        // column reflow, as a search narrows the list (review of #1820)
        anyPurpose = (allEntries != null ? allEntries : entries).stream().anyMatch(e -> e.purpose() != null);
        anyOwner = (allEntries != null ? allEntries : entries).stream().anyMatch(e -> e.mcp() != null || e.owner() != null);

        // Sort: running first, then stopped, alphabetically within each group
        var sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> {
            var aRunning = isRunning(a);
            var bRunning = isRunning(b);
            if (aRunning != bRunning) return aRunning ? -1 : 1;
            return a.name().compareToIgnoreCase(b.name());
        });

        // The widths come from every instance, not the search's, for the same reason: a column
        // must not change its width as a search narrows the list
        var rows = new java.util.IdentityHashMap<InstanceInfo, Row>();
        for (var entry : allEntries != null ? allEntries : entries) rows.put(entry, instanceRow(entry));
        for (var entry : entries) rows.computeIfAbsent(entry, this::instanceRow);
        var natural = new int[INSTANCE_COLUMNS];
        for (var measured : rows.entrySet()) {
            var row = measured.getValue();
            for (int c = 1; c < natural.length; c++) natural[c] = Math.max(natural[c], row.cells().get(c).width());
            natural[0] = Math.max(natural[0], nameWidth(measured.getKey()));
        }
        instanceNatural = natural;
        measuredRows = rows;

        for (var entry : sorted) {
            var before = statusSeen.put(entry.name(), entry.status() + "/" + entry.pendingOp());
            if (statusBaseline && !(entry.status() + "/" + entry.pendingOp()).equals(before)) {
                motion.flash(MainScreen.instanceKey(entry.name()));
            }

            tableRows.add(rows.get(entry));
            rowToEntry.add(entry);
        }
        statusSeen.keySet().retainAll((allEntries != null ? allEntries : entries).stream().map(InstanceInfo::name)
                .collect(java.util.stream.Collectors.toSet()));
        statusBaseline = true;
    }

    /** The widest cell of each template column over every template, the search's or not (see {@link ColumnFit}). */
    static final int TEMPLATE_COLUMNS = 4;
    volatile int[] templateNatural = new int[TEMPLATE_COLUMNS];

    /** Every instance's row, the search's or not: what the Work tab measures its columns over. */
    volatile java.util.Map<InstanceInfo, Row> measuredRows = Map.of();

    /** The cells of an instance's row, and the widest of each over every instance (see {@link ColumnFit}). */
    static final int INSTANCE_COLUMNS = 8;
    volatile int[] instanceNatural = new int[INSTANCE_COLUMNS];

    /** An instance's row: OWNER and PURPOSE last, since MainScreen keeps the cells of the columns it has room for. */
    private Row instanceRow(InstanceInfo entry) {
        var age = entry.created().isEmpty() ? "-" : Metadata.ageDescription(entry.created());
        var parent = entry.parent().isEmpty() ? "-" : OutputFormat.oneLine(entry.parent());
        boolean running = isRunning(entry);
        // A row's own style holds only what an operation under way adds; each cell has its colour
        var rowStyle = Style.EMPTY;
        if (Metadata.OP_DELETING.equals(entry.pendingOp())) {
            rowStyle = rowStyle.addModifier(dev.tamboui.style.Modifier.DIM)
                    .addModifier(dev.tamboui.style.Modifier.ITALIC);
        } else if (Metadata.OP_STOPPING.equals(entry.pendingOp()) || Metadata.OP_RESTARTING.equals(entry.pendingOp())) {
            rowStyle = rowStyle.addModifier(dev.tamboui.style.Modifier.DIM);
        }
        var name = Line.from(stateGlyph(entry), Span.styled(entry.name(), running ? Style.EMPTY : Style.EMPTY.fg(theme.textDim())));
        var word = unusualState(entry);
        if (word != null) name = Line.from(name.spans().getFirst(), name.spans().get(1), Span.styled(" " + word, Style.EMPTY.fg(theme.textDim())));
        var quiet = Style.EMPTY.fg(theme.textDim());

        // OWNER and PURPOSE last: MainScreen keeps the cells of the columns it has room for
        return Row.from(dev.tamboui.widgets.table.Cell.from(name), cell(entry.ipv4(), quiet),
                cell(parent, quiet), cell(entry.runtime(), quiet), cell(age, quiet),
                cell(diskCell(entry.diskUsage()), quiet),
                cell(dev.incusspawn.command.ListCommand.ownerCell(entry), quiet),
                cell(dev.incusspawn.command.ListCommand.purposeCell(entry), quiet)).style(rowStyle);
    }

    /**
     * The state glyph before an instance's name, and the space after it, in the state's colour
     * (design/visual-language.md "Glyphs"): its shape says the state where there is no colour.
     */
    Span stateGlyph(InstanceInfo entry) {
        boolean busy = !entry.pendingOp().isEmpty();
        boolean running = isRunning(entry);
        boolean stopped = "STOPPED".equalsIgnoreCase(entry.status());
        var color = busy ? theme.statusWarning() : running ? theme.statusRunning()
                : stopped ? theme.statusStopped() : theme.statusWarning();
        var glyph = busy ? Motion.SPINNER_STILL : running ? Glyphs.RUNNING : stopped ? Glyphs.STOPPED : Glyphs.BETWEEN;
        return Span.styled(glyph + " ", Style.EMPTY.fg(color));
    }

    /**
     * The word after an instance's name, only for a state the glyph alone does not say: the
     * operation under way ({@code stopping}), or a status neither running nor stopped
     * ({@code frozen}, {@code error}). Null for a running or a stopped instance.
     */
    static String unusualState(InstanceInfo entry) {
        if (!entry.pendingOp().isEmpty()) return entry.pendingOp();
        if (isRunning(entry) || "STOPPED".equalsIgnoreCase(entry.status())) return null;
        return OutputFormat.oneLine(entry.status()).toLowerCase(java.util.Locale.ROOT);
    }

    /** The cells an instance's name takes with its glyph, the word after it left out, so an operation starting moves no column. */
    static int nameWidth(InstanceInfo entry) {
        return 2 + dev.tamboui.text.CharWidth.of(entry.name());
    }

    /** Each instance's status and operation as the rows last showed them: a change flashes its row. */
    private final Map<String, String> statusSeen = new java.util.HashMap<>();
    /** False until the rows were built once: the first listing flashes nothing. */
    private boolean statusBaseline;

    private static dev.tamboui.widgets.table.Cell cell(String text, Style style) {
        return dev.tamboui.widgets.table.Cell.from(dev.tamboui.text.Span.styled(text == null ? "" : text, style));
    }

    private volatile boolean proxyRestartInProgress;
    private volatile boolean dnsVerified;

    private void tryFixStaleDns() {
        if (dnsVerified) return;
        dnsVerified = true;
        var config = SpawnConfig.load();
        try {
            if (ProxyConfig.isBridgeDnsComplete(incus, config)) return;
        } catch (Exception e) {
            // Unreadable is not incomplete: a write would fail the same way.
            return;
        }
        setStatusMessage("Updating bridge DNS overrides...");
        var thread = new Thread(() -> {
            try {
                ProxyConfig.writeBridgeDns(incus, config);
                setStatusMessage("Bridge DNS overrides updated");
            } catch (Exception e) {
                setStatusMessage("Failed to update the bridge DNS overrides: " + e.getMessage());
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
                        setStatusMessage("Failed to restart the proxy. Check: isx proxy status");
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

    /** Whether the Incus bridge's subnet overlaps a host network; its own method so a test need not ask the host. */
    boolean bridgeSubnetConflicts() {
        return BridgeSubnetCheck.detectConflictDiagnostic(incus) != null;
    }

    private void showSubnetWarning() {
        try {
            // Into the log, which the next key does not clear and w lists
            if (bridgeSubnetConflicts()) {
                drawing.warn("The Incus bridge's subnet overlaps a network of this host",
                        "Run " + (Platform.isLinux() ? "'isx doctor'" : "'isx init'") + " to fix it.");
            }
        } catch (Exception ignored) {
        }
    }

}
