package dev.incusspawn.command;

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
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.StaticIpAllocator;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.lifecycle.GuiPassthrough;
import dev.incusspawn.lifecycle.InstanceDestroyer;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.lifecycle.TemplateLock;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyLog;
import dev.incusspawn.lifecycle.ZmxSocketForward;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.tui.BackgroundTaskManager;
import dev.incusspawn.tui.InstanceEventWatcher;
import dev.incusspawn.tui.InstanceLockManager;
import dev.incusspawn.tool.ShellMenu;
import dev.incusspawn.tool.ToolAction;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolAction;
import dev.incusspawn.tui.PreTuiOutput;
import dev.incusspawn.tui.ShiftTabBindings;
import dev.incusspawn.tui.TerminalThemeDetector;
import dev.incusspawn.tui.TuiTheme;
import dev.incusspawn.tui.WarningLog;
import dev.tamboui.backend.panama.PanamaBackend;
import dev.incusspawn.vm.VmManager;
import dev.tamboui.layout.Constraint;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import dev.tamboui.widgets.scrollbar.Scrollbar;
import dev.tamboui.widgets.scrollbar.ScrollbarOrientation;
import dev.tamboui.widgets.scrollbar.ScrollbarState;
import dev.tamboui.widgets.table.TableState;
import dev.incusspawn.RuntimeServices;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.incusspawn.command.DiskUsageModel.fillMissingReferenced;
import static dev.incusspawn.command.DiskUsageModel.hasDescendant;
import static dev.incusspawn.command.DiskUsageModel.hasSuspiciousStamps;
import static dev.incusspawn.command.DiskUsageModel.hasUnstampedBuiltTemplate;
import static dev.incusspawn.command.DiskUsageModel.nearestStampedAncestorRfer;
import static dev.incusspawn.command.DiskUsageModel.referencedDelta;
import static dev.incusspawn.command.DiskUsageModel.restampFromLive;
import static dev.incusspawn.command.DiskUsageModel.sharedBaseBytes;
import static dev.incusspawn.command.UsageFormat.bar;
import static dev.incusspawn.command.UsageFormat.diskCell;
import static dev.incusspawn.command.UsageFormat.gibShort;
import static dev.incusspawn.command.UsageFormat.runningSummary;

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

    private IncusClient incus;

    private ToolDefLoader toolDefLoader;
    private java.util.List<ToolSetup> cdiTools;

    private BackgroundTaskManager backgroundTasks;

    private InstanceLockManager lockManager;

    private final TuiTheme theme = TerminalThemeDetector.detect();
    private final ModalRenderer modal = new ModalRenderer(theme);
    /** Warnings from any operation while the TUI runs; the status line holds only one. */
    private final WarningLog warningLog = new WarningLog();
    /**
     * Where {@link Warnings} go while the TUI draws or reloads (#872). Its own channel, so what it
     * has reported is remembered across TUI sessions, while a build run on the released terminal
     * still prints the same warning on stderr.
     */
    private final Warnings.Channel warningChannel = new Warnings.Channel(warningLog::add);
    private final WarningsModal warningsModal = new WarningsModal(modal, theme, warningLog,
            warningChannel::forgetReported);
    /** The status line last set by a warning announcement, which a newer one may replace. */
    private String warningAnnouncement;
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
                @Override public String builtFrom(String template) { return Tui.this.builtFrom(template); }
                @Override public String currentVersion() { return BuildInfo.instance().version(); }
            }, java.time.LocalDateTime::now);

    private final InstanceDetailView instanceDetail = new InstanceDetailView(modal, theme);

    private final InstanceActions instanceActions =
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
    private final AtomicBoolean liveRefreshRequested = new AtomicBoolean(false);
    private final AtomicBoolean liveRefreshInFlight = new AtomicBoolean(false);
    private final AtomicReference<LiveSnapshot> pendingLiveSnapshot = new AtomicReference<>();
    // Due times (epoch ms) of follow-up refreshes scheduled after a start: the instance's IPv4
    // shows up a few seconds after the start event, and nothing announces it.
    private final java.util.concurrent.ConcurrentSkipListSet<Long> followUpRefreshes =
            new java.util.concurrent.ConcurrentSkipListSet<>();
    private static final long[] START_FOLLOW_UP_DELAYS_MS = {3_000, 10_000};
    // Bumped by every full reload, so a light fetch that started before it can't overwrite it.
    private long dataGeneration;
    private long lastLiveRefreshMs;
    private long lastDataLoadMs;
    // Only while the event feed is down (older daemon, flaky appliance): poll slowly instead.
    private static final long FALLBACK_POLL_MS = 60_000;
    private boolean liveRefreshErrorShown;
    // Live rfer values backfilled in memory by the last full reload; re-applied by light
    // refreshes, which don't probe, so an unstamped template keeps its figure between them.
    private java.util.Map<String, Long> backfilledReferenced = java.util.Map.of();
    // The minute the age column ("3h ago") was last rendered for -- see Metadata.ageRefreshKey.
    private java.time.LocalDateTime rowsAgeKey;
    // Every instance name in the last listing (templates included): what "still exists" means
    // for the action guards and for closing a dialog whose target was deleted elsewhere.
    private java.util.Set<String> liveInstanceNames = java.util.Set.of();
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

    enum Mode { BROWSE, CONFIRM_DELETE, CONFIRM_STOP_FOR_RENAME, CONFIRM_BUILD_FOR_BRANCH, BUILD_MENU, BRANCH, RENAME, TEMPLATE_DETAIL, INSTANCE_DETAIL, ACCOUNTS, INFO, ERROR, ACTIONS, NEW_TEMPLATE, CLEAN_CONFIRM, CLEAN_RESULT, HELP_CHAT, WARNINGS }
    private Mode mode = Mode.BROWSE;
    private String errorMessage;
    /** The delete confirmation; set when it opens, and read again by the destroy it confirms. */
    private DeleteConfirm deleteConfirm;
    /** The pool-cleanup dialog; set when 'c' finds a pool, and kept for the result it shows after the clean. */
    private CleanModal clean;
    /** The F5 build menu; set when it opens. */
    private BuildMenu buildMenu;
    private String[] pendingBuildArgs;
    // Branch modal state
    private String branchSourceName;
    /** The branch dialog; set when it opens, and read once more to create the branch after it closes. */
    private BranchModal branch;
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
    private boolean searchActive = false;
    private TextInputState searchInput;
    private List<TemplateInfo> allTemplateEntries;
    private List<InstanceInfo> allEntries;
    // Info modal state
    private int infoScrollOffset;
    // AI Help modal state
    /** Kept after closing, so reopening preselects the account used last. */
    private volatile HelpChatModal helpChat;
    // Actions modal state
    private java.util.List<ToolAction> actionsList;
    private int actionsSelectedIndex;
    private int actionsScrollOffset;
    private ActionContext actionsContext;
    // Actions cache (computed once per data refresh, not per render)
    private java.util.Map<String, java.util.List<ToolAction>> actionsCache = new java.util.HashMap<>();
    // Default action reference per instance (from ImageDef default-action field)
    private java.util.Map<String, String> defaultActionRef = new java.util.HashMap<>();

    private boolean deferredBuildForBranch;

    enum PendingAction { NONE, SHELL, SHELL_WITH_COMMAND, BRANCH, BUILD_TEMPLATE, BUILD_THEN_BRANCH, EDIT_TEMPLATE, NEW_TEMPLATE, EXECUTE_ACTION }
    private PendingAction pendingAction = PendingAction.NONE;
    private ActionContext pendingActionTarget;
    private String pendingShellCommand;
    private ToolAction pendingToolAction;
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
    /** Where each image definition came from, for what it overrides. */
    private dev.incusspawn.config.LayeredDefinitions<dev.incusspawn.config.ImageDef> imageLayers =
            new dev.incusspawn.config.LayeredDefinitions<>("image");
    /** Per built template, the file its build stamped, and the stamp it was read from. */
    private Map<String, StampedFile> templatesBuiltFrom = Map.of();

    private record StampedFile(String stamp, String file) {}
    private java.util.Set<String> templatesParentRebuilt = java.util.Set.of();
    private java.util.Set<String> templatesOutOfSync = java.util.Set.of();
    private java.util.Set<String> storedSourceTemplates = java.util.Set.of();
    private TableState templateTableState;

    // Instance panel data (bottom)
    private List<InstanceInfo> entries;
    private List<Row> tableRows;
    private List<InstanceInfo> rowToEntry;
    private TableState instanceTableState;

    // Storage-pool usage for the top gauge; refreshed in reloadData (not per-frame,
    // since it costs an API call). Null when no pool usage is available.
    private IncusClient.PoolUsage poolUsage;
    // Resolved once and cached: the pool name is constant for a TUI session, so we
    // avoid re-probing the storage-pool list (an extra HTTP call) on every reload.
    private String usagePoolName;
    // Whether the resolved pool is the CoW (btrfs/zfs) pool. Only then do per-row usage
    // figures mean "exclusive bytes" and the shared base fold below make sense.
    private boolean usagePoolIsCow;
    // Whether the resolved pool is specifically btrfs. Only btrfs exposes per-subvolume referenced
    // (rfer) accounting, so the referenced-delta model is gated on this (see canUseReferencedModel).
    private boolean usagePoolIsBtrfs;
    // The root template that owns the base-image weight this reload — either folded into it
    // (foldBaseWeightIntoRootTemplate) or attributed via its own rfer (applyReferencedTemplateDeltas),
    // or null when it couldn't be attributed. Drives the CoW delete note and the ~ marker.
    private String baseTemplateName;
    // The kernel's view of the btrfs pool's qgroup accounting, refreshed each reload (cheap: a sysfs
    // read, or one agent round trip on macOS). While it reads `untrusted()` every size the pool
    // reports — stamped or live, rfer or exclusive — is frozen at some stale value, so no stamp is
    // backfilled and no base weight folded from it; refreshAccountingStatus() also kicks off the
    // background repair. Null only when there is no btrfs pool to ask about (non-btrfs pool, or the
    // pool name isn't resolved yet); a status that simply couldn't be read is a non-null UNAVAILABLE.
    // Both cases are treated as trusted, so sizes are taken at face value as before the check existed.
    private dev.incusspawn.incus.BtrfsUsage.QgroupStatus qgroupStatus;
    // True from the moment inconsistent accounting is seen until it reads consistent again — the
    // transition is what triggers a one-off re-validation of the stamps (revalidateStampsIfNeeded),
    // since any stamp recorded while the counters were frozen is wrong. Deliberately latched across
    // a spell of unreadable status (a flaky agent): we still don't know the repair landed, so the
    // eventual consistent read must still trigger revalidation. It does NOT keep driving the fast
    // poll cadence though — see refreshAccountingStatus.
    private boolean accountingRepairPending;
    private boolean stampsSuspect;
    // The "a derived template's stamp equals its parent's" heuristic runs at most once per session:
    // it's how a pool poisoned before this check existed gets healed, but a live probe that comes
    // back identical must not be repeated every refresh.
    private boolean poisonHeuristicSpent;
    private boolean accountingWarningShown;
    // Reloads can come ~1/s during activity, and on macOS the status read is an agent round trip
    // (up to the client's 5s watchdog if the agent is wedged) — so re-read it on a cadence, not
    // per reload: quickly while a repair is pending (to notice the flag clearing), rarely otherwise.
    private long accountingCheckMs;
    private static final long ACCOUNTING_CHECK_INTERVAL_MS = 30_000;
    private static final long ACCOUNTING_REPAIR_POLL_MS = 2_000;
    // Amber threshold for the storage gauge (percent of pool used).
    static final int STORAGE_WARN_PERCENT = 75;
    // Set true once we've shown the low-space warning for the current session,
    // so the reminder doesn't clobber every other status message on each refresh.
    private boolean storageWarningShown;
    private volatile ProxyHealthCheck.ProxyInfo proxyInfo;
    private volatile String applianceSkewMessage;
    private boolean applianceSkewFirstLoad = true;
    // Defer the first proxy health check so the TUI renders immediately instead of
    // stalling up to 500ms on the connect timeout when the proxy isn't running.
    private static final long PROXY_AUTH_INITIAL_DEFER_MS = 500;
    private long proxyAuthCheckMs = System.currentTimeMillis()
            - PROXY_AUTH_CHECK_INTERVAL_MS + PROXY_AUTH_INITIAL_DEFER_MS;

    /** Bare {@code isx}: takes over the terminal until the user quits. */
    public void run(PreTuiOutput preTui) {
        this.preTui = preTui;
        try { open(); } catch (Exception e) { System.err.println("Error: " + e.getMessage()); }
    }

    private void open() {
        // Before anything else, so no failure here can skip the pause, and before the
        // first reload, so a pause for the user does not leave the listing stale.
        if (preTui != null) {
            preTui.handOver(warningChannel::report, this::waitForUser);
            preTui = null;
        }
        this.incus = RuntimeServices.incus();
        this.toolDefLoader = RuntimeServices.toolDefLoader();
        this.cdiTools = RuntimeServices.toolSetups();
        this.backgroundTasks = RuntimeServices.backgroundTasks();
        this.lockManager = RuntimeServices.lockManager();
        runTuiLoop();
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

    /** Draws the TUI until the user quits; overridable so a test can stand in for the terminal. */
    void runTuiLoop() {
        try {
            runTuiLoopUntilQuit();
        } finally {
            printUnreadWarnings();
        }
    }

    /**
     * Whether the status line is free for a warning announcement: it is empty, or holds an older
     * announcement. An action's result (a failed build, a created template) is not replaced; the
     * warnings wait, counted in the header, until the next key clears the line.
     */
    private boolean canAnnounceWarnings() {
        return statusMessage == null || statusMessage.equals(warningAnnouncement);
    }

    /** The TUI has closed: warnings nobody opened the dialog for must not vanish with it. */
    private void printUnreadWarnings() {
        for (var entry : warningLog.unreadEntries()) {
            System.err.println("Warning: " + entry.message());
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
            // Anything reporting through Warnings (definition loaders built deep in shared code)
            // goes to the warning log, like everything raised while the runner below draws, but
            // not while the terminal is released to a build or a shell, which print their own.
            try (var ignored = Warnings.redirect(warningChannel)) {
                reloadData();
            } catch (IncusException e) {
                reloadError = e.getMessage();
                templateEntries = List.of();
                entries = List.of();
                rowToEntry = List.of();
            }
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

            ProxyLog.setSuppressStderr(true);
            try (var ignored = Warnings.redirect(warningChannel);
                 var runner = TuiRunner.create(TuiConfig.builder()
                    .backend(new PanamaBackend())
                    .bindings(ShiftTabBindings.createWithBacktab())
                    .tickRate(Duration.ofMillis(100))
                    .build())) {
                runner.run(
                        (event, tui) -> handleEvent(event, tui, instanceTableState),
                        frame -> render(frame, instanceTableState));
            } catch (Exception e) {
                System.err.println("TUI unavailable: " + e.getMessage());
                ListCommand.printTable(entries, System.out);
                return;
            } finally {
                ProxyLog.setSuppressStderr(false);
            }

            // Remember template selection for when we re-enter the TUI
            var tpl = selectedTemplate();
            if (tpl != null) returnToTemplate = tpl.name();

            switch (pendingAction) {
                case SHELL -> {
                    returnToInstance = pendingActionTarget.name();
                    shellInto(pendingActionTarget);
                }
                case SHELL_WITH_COMMAND -> {
                    returnToInstance = pendingActionTarget.name();
                    shellInto(pendingActionTarget, pendingShellCommand);
                }
                case BRANCH -> {
                    returnToInstance = pendingActionTarget.name();
                    try {
                        createBranchFromModal(pendingActionTarget.name());
                        statusMessage = "Created branch " + pendingActionTarget.name();
                    } catch (Exception e) {
                        statusMessage = "Failed to create branch " + pendingActionTarget.name() + ": " + e.getMessage();
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
                        // Delete any stale report so freshFailureReport cannot mistake it for this build's.
                        // The build writes a new one on failure; on success no report should be shown.
                        // If the delete fails, suppress the report path entirely (buildStart = null)
                        // rather than risk showing a stale report from the same filesystem second.
                        java.time.Instant buildStart = null;
                        if (!buildTarget.startsWith("--")) {
                            try {
                                java.nio.file.Files.deleteIfExists(Environment.buildFailureLogFile(buildTarget));
                                buildStart = java.time.Instant.now();
                            } catch (Exception ignored) {}
                        } else {
                            buildStart = java.time.Instant.now();
                        }
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
                        TemplatesCommand.editLoop(targetPath, pendingActionTarget.name(), false);
                        statusMessage = "Created template " + pendingActionTarget.name();
                    } catch (Exception e) {
                        statusMessage = "Failed to create template: " + e.getMessage();
                    }
                }
                case EDIT_TEMPLATE -> {
                    returnToTemplate = pendingActionTarget.name();
                    try { var editCmd = new TemplatesCommand.Edit(); editCmd.name = pendingActionTarget.name(); editCmd.doExecute(); }
                    catch (Exception e) { statusMessage = "Edit failed: " + e.getMessage(); }
                }
                case EXECUTE_ACTION -> {
                    var result = pendingToolAction.execute(pendingActionTarget);
                    System.out.println(result.message());
                    if (pendingToolAction instanceof YamlToolAction yamlAction && !yamlAction.shouldAutoReturn()) {
                        waitForKeypress();
                    }
                }
                case NONE -> { return; }
            }
            // An action that failed mid-step (a branch whose copy or start threw) must not leave the
            // step animating over the TUI we are about to redraw, nor System.out/err guarded.
            BuildOutput.abandonStep();
        }
    }

    /** Pause after a suspended-TUI subcommand so its output stays on screen until the user is ready. */
    private static void waitForKeypress() {
        System.out.println("\nPress any key to continue...");
        try {
            System.in.read();
        } catch (java.io.IOException ignored) {}
    }

    /**
     * Reload all data from Incus and image definitions. Populates both the
     * template panel (from ImageDef + Incus state) and the instance panel
     * (non-template instances only).
     */
    private void reloadData() {
        // This reload reads everything, so it satisfies every request raised before it. Requests
        // raised while it runs (events arriving mid-reload) set the flag again and still get served.
        // Start follow-ups stay scheduled: they exist for an IPv4 that appears after this read.
        liveRefreshRequested.set(false);
        dataGeneration++;
        lastDataLoadMs = System.currentTimeMillis();
        // The instance listing (GET /1.0/instances?recursion=2) is the heaviest single call.
        // Run it in the background while the main thread does filesystem I/O and pool probes.
        var instancesFuture = java.util.concurrent.CompletableFuture.supplyAsync(this::collectEntries);

        // Re-read tool defs from disk alongside the image defs below, so edited tool YAML
        // is reflected in the "△ definition changed" flag. Must precede addFallbacks(),
        // which re-populates the freshly cleared cache. Its warnings reach the log itself.
        toolDefLoader.reload();
        // Through Warnings rather than straight into the log: each reload finds the same
        // problems again, and Warnings reports a message once until the user presses 'r'.
        imageLayers = dev.incusspawn.config.ImageDef.loadLayers(Warnings::warn);
        imageDefs = imageLayers.defs();
        // Tool conflicts don't flow through image loadAll; surface them here too so
        // the TUI warns about duplicate tool names instead of only failing at build.
        for (var conflict : toolDefLoader.conflicts()) {
            Warnings.warn(conflict.shortMessage());
        }

        // Pool usage runs here (before the merge) so the base-image weight can be attributed
        // to the root template before the row lists are snapshotted for rendering.
        refreshPoolUsage();

        List<InstanceInfo> allInstances;
        try {
            allInstances = instancesFuture.join();
        } catch (java.util.concurrent.CompletionException e) {
            var cause = e.getCause();
            if (cause instanceof Error err) throw err;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }

        allInstances = clearStalePendingOps(allInstances);
        mergeInstances(allInstances);

        refreshProxyAuthError();
        refreshApplianceSkew();
        refreshAccountingStatus();
        applyDiskModel(true);
        publishRows();
    }

    /**
     * Swap in the instance listing from a light refresh (see {@link #startLiveRefresh}). Unlike
     * {@link #reloadData()} it re-reads nothing from disk and runs no probes: the definitions
     * can't have changed because an instance did, and the accounting reads keep their cadence.
     */
    private void applyLiveInstances(List<InstanceInfo> allInstances, IncusClient.PoolUsage usage) {
        lastDataLoadMs = System.currentTimeMillis();
        if (usage != null) applyPoolUsage(usage);
        mergeInstances(allInstances);
        applyDiskModel(false);
        publishRows();
    }

    /**
     * Clear a pending-op marker no process holds the lock for: the process that set it crashed.
     * Returns the listing with those markers blanked, so the UI doesn't render stale indicators
     * until the next reload. Safe off the UI thread (lock files and Incus calls only).
     */
    private List<InstanceInfo> clearStalePendingOps(List<InstanceInfo> allInstances) {
        var clearedInstances = new java.util.HashSet<String>();
        for (var inst : allInstances) {
            if (!inst.pendingOp().isEmpty()
                    && !backgroundTasks.hasRunningTask(inst.name())
                    && !lockManager.isHeldByOther(inst.name())) {
                try {
                    var cleanupLock = lockManager.tryAcquire(inst.name(), "cleanup");
                    if (cleanupLock.isPresent()) {
                        try (var lock = cleanupLock.get()) {
                            incus.clearPendingOperation(inst.name());
                            clearedInstances.add(inst.name());
                        }
                    }
                } catch (java.io.UncheckedIOException ignored) {}
            }
        }
        // Override pendingOp in allInstances for entries we just cleared,
        // so the UI doesn't render stale indicators until the next reload.
        if (!clearedInstances.isEmpty()) {
            allInstances = allInstances.stream()
                    .map(inst -> clearedInstances.contains(inst.name())
                            ? new InstanceInfo(inst.name(), inst.status(), inst.project(), inst.profile(),
                                    inst.created(), inst.runtime(), inst.parent(), inst.limitsCpu(),
                                    inst.limitsMemory(), inst.rootSize(), inst.ipv4(), inst.networkMode(),
                                    inst.architecture(), inst.buildVersion(), inst.definitionSha(),
                                    inst.type(), inst.buildSourceJson(), "", inst.defaultAction(),
                                    inst.diskUsage(), inst.referencedBytes(), inst.instanceMode(),
                                    inst.kvmEnabled(), inst.mcp(), inst.mcpCaller())
                            : inst)
                    .toList();
        }

        return allInstances;
    }

    /** The file a built template's build stamped, or null. */
    String builtFrom(String template) {
        var stamped = templatesBuiltFrom.get(template);
        return stamped == null ? null : stamped.file();
    }

    /** Merge the Incus listing with the image definitions into the two panels' entry lists. */
    // Package-private, with buildTemplateRowData and buildContextLine, for the wiring tests
    void mergeInstances(List<InstanceInfo> allInstances) {
        // Build template panel data by merging ImageDef definitions with Incus state
        templateEntries = new ArrayList<>();
        var templateNames = new java.util.HashSet<String>();
        var builtFrom = new java.util.HashMap<String, StampedFile>();
        for (var def : imageDefs.values()) {
            var name = def.getName();
            // Find matching Incus instance
            InstanceInfo match = null;
            for (var inst : allInstances) {
                if (inst.name().equals(name)) {
                    match = inst;
                    break;
                }
            }
            if (match != null) {
                templateEntries.add(new TemplateInfo(name, def.getDescription(),
                        match.created().isEmpty() ? "built" : match.created(), match.runtime(),
                        match.buildVersion(), match.definitionSha(), match.pendingOp(),
                        match.parent(), match.diskUsage(), match.referencedBytes(), match.instanceMode()));
                templateNames.add(name);
                // A live refresh brings the same stamp again: parse it only when it changed
                var json = match.buildSourceJson();
                var previous = templatesBuiltFrom.get(name);
                builtFrom.put(name, previous != null && java.util.Objects.equals(json, previous.stamp())
                        ? previous : new StampedFile(json, BuildSource.sourceOf(json, name)));
            } else {
                templateEntries.add(new TemplateInfo(name, def.getDescription(), "not built", "", "", "", "", "", -1, -1, ""));
            }
        }
        // Add out-of-scope templates (built but not in current definition scope)
        var storedNames = new java.util.HashSet<String>();
        for (var inst : allInstances) {
            if (templateNames.contains(inst.name())) continue;
            if (inst.name().endsWith(BuildCommand.REBUILDING_SUFFIX)) continue;
            if (!Metadata.TYPE_BASE.equals(inst.type())) continue;

            var buildSource = BuildSource.fromJson(inst.buildSourceJson());
            if (buildSource == null) continue;

            for (var entry : buildSource.getDefinitions().entrySet()) {
                imageDefs.putIfAbsent(entry.getKey(), entry.getValue());
            }
            toolDefLoader.addFallbacks(buildSource.getTools());

            templateEntries.add(new TemplateInfo(inst.name(), buildSource.descriptionFor(inst.name()),
                    inst.created().isEmpty() ? "built" : inst.created(), inst.runtime(),
                    inst.buildVersion(), inst.definitionSha(), inst.pendingOp(), inst.parent(), inst.diskUsage(),
                    inst.referencedBytes(), inst.instanceMode()));
            templateNames.add(inst.name());
            storedNames.add(inst.name());
        }
        storedSourceTemplates = storedNames;
        templatesBuiltFrom = builtFrom;

        // Instance panel: exclude template instances (they're shown in the template panel)
        entries = new ArrayList<>();
        actionsCache = new java.util.HashMap<>();
        defaultActionRef = new java.util.HashMap<>();
        for (var inst : allInstances) {
            if (!templateNames.contains(inst.name())) {
                entries.add(inst);
                actionsCache.put(inst.name(), instanceActions.resolveActionsForInstance(inst));
                var defAction = instanceActions.resolveDefaultActionRef(inst);
                if (defAction != null) {
                    defaultActionRef.put(inst.name(), defAction);
                }
            }
        }

        liveInstanceNames = allInstances.stream().map(InstanceInfo::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Attribute disk weight to the rows. {@code probe} allows the live reads (stamp revalidation,
     * rfer backfill); a light refresh passes false and reuses the last backfill instead.
     */
    private void applyDiskModel(boolean probe) {
        // Stamps taken from consistent accounting stay valid whatever the flag says now (templates
        // are immutable), so the delta model keeps running through a repair. What must wait for the
        // counters to be trustworthy is anything *read live*: backfilling a missing stamp, healing a
        // suspect one, or folding the pool's shared remainder onto the root.
        boolean accountingTrusted = qgroupStatus == null || !qgroupStatus.untrusted();
        if (probe) {
            backfilledReferenced = java.util.Map.of();
            if (accountingTrusted) {
                revalidateStampsIfNeeded();
                fillMissingReferencedSizes();
            }
        } else if (accountingTrusted && !backfilledReferenced.isEmpty()) {
            templateEntries = fillMissingReferenced(templateEntries, backfilledReferenced);
        }
        if (canUseReferencedModel()) {
            applyReferencedTemplateDeltas();
        } else if (accountingTrusted) {
            foldBaseWeightIntoRootTemplate();
        } else {
            baseTemplateName = null;
        }
    }

    private void publishRows() {
        allTemplateEntries = new ArrayList<>(templateEntries);
        allEntries = new ArrayList<>(entries);
        rebuildRowData();
    }

    /**
     * Refresh the cached storage-pool usage for the gauge, and raise a one-shot
     * status warning when the pool crosses the critical threshold. Prefers a CoW
     * pool but falls back to any usable pool so the gauge also works on dir pools.
     */
    private void refreshPoolUsage() {
        try {
            if (usagePoolName == null) {
                var probe = incus.probeCowPool();
                var cow = probe.poolName();
                usagePoolName = cow != null ? cow : incus.findUsablePool();
                usagePoolIsCow = cow != null;
                usagePoolIsBtrfs = probe.isBtrfs();
            }
            applyPoolUsage(usagePoolName == null ? null : incus.getPoolUsageBytes(usagePoolName));
        } catch (Exception e) {
            applyPoolUsage(null);
        }
    }

    private void applyPoolUsage(IncusClient.PoolUsage usage) {
        poolUsage = usage;
        if (poolUsage == null || poolUsage.totalBytes() == 0) {
            storageWarningShown = false;
            return;
        }
        if (poolUsage.percent() >= IncusClient.PoolUsage.CRIT_PERCENT) {
            if (!storageWarningShown && statusMessage == null) {
                statusMessage = "⚠ Storage " + poolUsage.percent()
                        + "% full — press C to reclaim space (stale templates, unused images, caches)"
                        + (Platform.isMacOS() ? ", or grow it with 'isx vm resize'" : "");
                storageWarningShown = true;
            }
        } else {
            storageWarningShown = false;
        }
    }

    private static final long PROXY_AUTH_CHECK_INTERVAL_MS = 30_000;

    private void refreshProxyAuthError() {
        long now = System.currentTimeMillis();
        if (now - proxyAuthCheckMs < PROXY_AUTH_CHECK_INTERVAL_MS) return;
        proxyAuthCheckMs = now;
        try {
            proxyInfo = ProxyHealthCheck.fetchProxyInfo(ProxyHealthCheck.healthAddress(incus), 500);
        } catch (Exception e) {
            proxyInfo = null;
        }
    }

    private void refreshApplianceSkew() {
        if (!Platform.isMacOS()) { applianceSkewMessage = null; return; }
        if (applianceSkewFirstLoad) { applianceSkewFirstLoad = false; return; }
        try {
            var skew = VmManager.applianceSkew();
            applianceSkewMessage = skew == null ? null
                    : "Appliance " + skew.running() + " — restart VM for " + skew.installed();
        } catch (Exception e) {
            applianceSkewMessage = null;
        }
    }

    private Thread startAuthTitleMonitor(String containerName) {
        var baseTitle = "isx:" + containerName;
        String healthAddr;
        try {
            healthAddr = ProxyHealthCheck.healthAddress(incus);
        } catch (Exception e) {
            return Thread.currentThread(); // no-op: interrupt is harmless on current thread
        }
        var thread = new Thread(() -> {
            boolean wasError = false;
            while (!Thread.interrupted()) {
                try { Thread.sleep(PROXY_AUTH_CHECK_INTERVAL_MS); }
                catch (InterruptedException e) { break; }
                try {
                    var info = ProxyHealthCheck.fetchProxyInfo(healthAddr, 500);
                    boolean isError = info != null && info.hasAuthError();
                    if (isError && !wasError) {
                        setTerminalTitle("⚠ " + info.authRemediationHint() + " — " + baseTitle);
                    } else if (!isError && wasError) {
                        setTerminalTitle(baseTitle);
                    }
                    wasError = isError;
                } catch (Exception ignored) {}
            }
        }, "auth-title-monitor");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void setTerminalTitle(String title) {
        System.out.print("\033]0;" + title + "\007"); // raw ANSI: window title, set while the TUI owns the terminal
        System.out.flush();
    }

    /**
     * Attribute the pool's shared base weight to the root template. btrfs reports each row's usage
     * as <em>exclusive</em> (blocks unique to that one subvolume), so the imported base image and
     * every block shared down a CoW chain belong to no row — they surface only in the pool total.
     * We fold that remainder ({@code pool.used} minus the sum of every row's unique usage) into the
     * single root template (the built template with no parent), so the base reads at its real weight
     * and the rows roughly reconcile with the gauge. Only for CoW pools; skipped when usage is
     * unknown or the root is ambiguous (zero or several templates with no parent), rather than
     * misattributing the bytes to the wrong row.
     */
    private void foldBaseWeightIntoRootTemplate() {
        baseTemplateName = null;
        if (!usagePoolIsCow || poolUsage == null || poolUsage.usedBytes() <= 0) return;

        long unique = 0;
        for (var t : templateEntries) if (t.diskUsage() > 0) unique += t.diskUsage();
        for (var e : entries) if (e.diskUsage() > 0) unique += e.diskUsage();
        long base = sharedBaseBytes(poolUsage.usedBytes(), unique);
        if (base <= 0) return;

        int rootIdx = -1;
        for (int i = 0; i < templateEntries.size(); i++) {
            var t = templateEntries.get(i);
            if (t.diskUsage() < 0) continue;                            // not built — no subvolume yet
            if (!t.isRoot()) continue;                                  // derived — not a root
            if (rootIdx >= 0) return;                                   // ambiguous: >1 root, don't guess
            rootIdx = i;
        }
        if (rootIdx < 0) return;

        var r = templateEntries.get(rootIdx);
        long folded = (r.diskUsage() < 0 ? 0 : r.diskUsage()) + base;
        templateEntries.set(rootIdx, r.withDiskUsage(folded));
        baseTemplateName = r.name();
    }

    /**
     * Attribute disk weight using each template's stamped btrfs <em>referenced</em> size (rfer),
     * shown as a delta from its parent: {@code delta = rfer(node) − rfer(parent)}, and for the root
     * template (no template parent) the delta is its own rfer — which is the base-image weight, so
     * the base reads at its real size and derived templates show only what they added (e.g. a tools
     * layer). Unlike {@link #foldBaseWeightIntoRootTemplate}, no shared remainder is dumped onto the
     * root: rfer already distributes it correctly down the chain. Instances are left on their
     * exclusive usage — for a branch with no descendants that already equals its delta from the
     * template, and it comes free from the Incus API.
     *
     * <p>Only used when every built template carries the {@link Metadata#DISK_REFERENCED} stamp (see
     * {@link #canUseReferencedModel}); otherwise the fold fallback runs. Sets {@link #baseTemplateName}
     * to the root template so the CoW delete note and {@code ~} marker still apply.
     */
    private void applyReferencedTemplateDeltas() {
        baseTemplateName = null;
        var rferOf = new java.util.HashMap<String, Long>();
        for (var t : templateEntries) if (t.referencedBytes() >= 0) rferOf.put(t.name(), t.referencedBytes());

        // Definitional parent links (from on-disk YAML) survive template deletion, unlike the built
        // rows: if an intermediate template is deleted, its immediate parent row is gone but the chain
        // is still walkable, so we can subtract the nearest *surviving* ancestor instead of over-
        // subtracting a phantom parent. See nearestStampedAncestorRfer.
        var defParentOf = new java.util.HashMap<String, String>();
        if (imageDefs != null) {
            for (var e : imageDefs.entrySet()) defParentOf.put(e.getKey(), e.getValue().getParent());
        }

        String rootName = null;
        boolean rootAmbiguous = false;
        for (int i = 0; i < templateEntries.size(); i++) {
            var t = templateEntries.get(i);
            // Unstamped: either not built, or a built derived template whose stamp is missing (e.g. a
            // transient btrfs read failure at build time). Leave its diskUsage on the exclusive value
            // set at load — a per-row fallback, rather than dropping the whole model to the fold.
            if (t.referencedBytes() < 0) continue;
            var p = t.parent();
            boolean isRoot = t.isRoot();
            if (isRoot) {
                if (rootName != null) rootAmbiguous = true;
                rootName = t.name();
            }
            Long parentRfer = isRoot ? null : nearestStampedAncestorRfer(p, rferOf, defParentOf);
            long delta = referencedDelta(t.referencedBytes(), parentRfer, isRoot);
            templateEntries.set(i, t.withDiskUsage(delta));
        }
        if (!rootAmbiguous) baseTemplateName = rootName;
    }

    private boolean canUseReferencedModel() {
        // usagePoolIsBtrfs already implies a named CoW pool (it's set from probe.isBtrfs()).
        return DiskUsageModel.canUseReferencedModel(usagePoolIsBtrfs, templateEntries);
    }

    /**
     * Backfill any built template that's missing its {@link Metadata#DISK_REFERENCED} stamp with a
     * single <em>live</em> btrfs read, so the referenced-delta model still applies to that row (and,
     * if the missing one is the root, to the whole panel) instead of falling back to the shared-base
     * fold. This heals both pre-feature templates (built before rfer stamping) and the rare build
     * whose stamp failed to record — with no rebuild required.
     *
     * <p>Deliberately lazy and light, to honour the "privileged read is rare, not per-refresh"
     * posture: it runs the probe <em>only</em> when there is an actual gap (an unstamped built
     * template), and uses the non-sync flavour ({@link dev.incusspawn.incus.BtrfsUsage#probe(String,
     * boolean)} with {@code sync=false}) — no forced filesystem commit, cheap enough for a refresh
     * cadence. An existing stamp is never overwritten (templates are immutable, so the stamp is
     * authoritative and free), and the overlay is in-memory only (a rebuild re-stamps permanently).
     * If the read is unavailable (dir pool, no sudoers rule, agent down) the gap stays and the fold
     * fallback handles it.
     */
    private void fillMissingReferencedSizes() {
        if (!usagePoolIsBtrfs || usagePoolName == null) return;
        if (!hasUnstampedBuiltTemplate(templateEntries)) return;       // no gap — skip the probe entirely
        var live = dev.incusspawn.incus.BtrfsUsage.probe(usagePoolName, false);   // non-sync: light enough for refresh
        if (live.isEmpty()) return;
        backfilledReferenced = live;
        templateEntries = fillMissingReferenced(templateEntries, live);
    }

    /**
     * Read the pool's qgroup accounting status and, if it's inconsistent, start the repair — a
     * background {@code btrfs quota rescan}, throttled inside {@code BtrfsUsage} so calling this on
     * every reload is fine. The kernel clears the flag when the rescan completes (seconds on a
     * developer-sized pool); the reload that first sees it consistent again marks the stamps
     * suspect, because any recorded while the counters were frozen are wrong.
     */
    private void refreshAccountingStatus() {
        if (!usagePoolIsBtrfs || usagePoolName == null) {
            qgroupStatus = null;
            return;
        }
        long now = System.currentTimeMillis();
        // Poll fast only while a repair is pending AND the last read actually told us something.
        // If the status has become unreadable (a wedged agent on macOS, each read costing up to its
        // 5s watchdog), polling every 2s buys nothing and just hammers a failing channel — so back
        // off to the normal cadence until it answers again.
        boolean canObserveRepair = accountingRepairPending && qgroupStatus != null && qgroupStatus.available();
        long interval = canObserveRepair ? ACCOUNTING_REPAIR_POLL_MS : ACCOUNTING_CHECK_INTERVAL_MS;
        if (qgroupStatus != null && now - accountingCheckMs < interval) return;
        accountingCheckMs = now;
        qgroupStatus = dev.incusspawn.incus.BtrfsUsage.repairIfInconsistent(usagePoolName);
        if (qgroupStatus.untrusted()) {
            accountingRepairPending = true;
            if (!accountingWarningShown && statusMessage == null) {
                statusMessage = "Repairing disk accounting (btrfs quota rescan) — sizes may be stale for a moment";
                accountingWarningShown = true;
            }
        } else if (accountingRepairPending && qgroupStatus.available()) {
            accountingRepairPending = false;
            stampsSuspect = true;
        }
    }

    /**
     * Re-validate stamped referenced sizes against one live read and re-stamp any that differ.
     * Runs only when there's reason to doubt them: right after a repair observed this session
     * (the stamps may have been recorded from frozen counters), or — at most once per session —
     * when they <em>look</em> poisoned: a built derived template stamped with exactly its parent's
     * value (see {@link #hasSuspiciousStamps}). That second trigger is what heals a pool that went
     * inconsistent before this check existed, without a rebuild. Corrected stamps are persisted so
     * the next launch doesn't probe again; the privileged read stays out of the healthy path.
     */
    private void revalidateStampsIfNeeded() {
        if (!usagePoolIsBtrfs || usagePoolName == null) return;
        boolean run = stampsSuspect;
        if (!run && !poisonHeuristicSpent && hasSuspiciousStamps(templateEntries)) {
            run = true;
            poisonHeuristicSpent = true;
        }
        if (!run) return;
        stampsSuspect = false;
        var live = dev.incusspawn.incus.BtrfsUsage.probe(usagePoolName, false);
        if (live.isEmpty()) return;
        var corrected = restampFromLive(templateEntries, live);
        for (int i = 0; i < corrected.size(); i++) {
            var before = templateEntries.get(i);
            var after = corrected.get(i);
            if (after.referencedBytes() == before.referencedBytes()) continue;
            try {
                incus.configSet(after.name(), Metadata.DISK_REFERENCED, String.valueOf(after.referencedBytes()));
            } catch (Exception ignored) {
                // Best-effort persistence: the corrected value is used for this session regardless.
            }
        }
        templateEntries = corrected;
    }

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
        if (searchActive) return handleSearchEvent(key, tableState);
        return switch (mode) {
            case BROWSE -> handleBrowseEvent(key, tui, tableState);
            case CONFIRM_DELETE -> handleConfirmDeleteEvent(key, tui, tableState);
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
        // Refresh data. 'r' is the TUI-idiomatic binding (k9s/lazygit/btop);
        // Ctrl+L is kept as an alias for the terminal "redraw screen" reflex.
        if ((!key.hasCtrl() && key.isCharIgnoreCase('r')) || (key.hasCtrl() && key.isCharIgnoreCase('l'))) {
            // An explicit reload reports definition problems again, so a warning that is still
            // true is announced again rather than silently deduplicated.
            warningChannel.forgetReported();
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

        // F3: Show template details
        if (key.isKey(KeyCode.F3)) {
            templateDetail.open();
            mode = Mode.TEMPLATE_DETAIL;
            return true;
        }

        // Block all actions if there's a pending operation
        if (hasPendingOp(template) || backgroundTasks.hasRunningTask(template.name())) {
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
        if ((key.isKey(KeyCode.F8) || key.isKey(KeyCode.DELETE)) && key.hasShift()) {
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
        if (key.isKey(KeyCode.F8) || key.isKey(KeyCode.DELETE)) {
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

        // F3: Show instance details (always accessible, even during operations)
        if (key.isKey(KeyCode.F3)) {
            instanceDetail.open();
            detailInstanceName = selected.name();
            detailAccountUses = accountUsesFor(selected);
            mode = Mode.INSTANCE_DETAIL;
            return true;
        }

        // Shift+F8 or Shift+Delete: Destroy all instances
        if ((key.isKey(KeyCode.F8) || key.isKey(KeyCode.DELETE)) && key.hasShift()) {
            if (entries.isEmpty()) {
                statusMessage = "No instances to destroy.";
                return true;
            }
            openDeleteConfirm("--all-instances");
            return true;
        }
        // Block actions that mutate the selected instance if there's a pending operation
        if (hasPendingOp(selected) || backgroundTasks.hasRunningTask(selected.name())) {
            statusMessage = "Operation in progress for " + selected.name();
            return true;
        }
        if (isInstanceActionKey(key, selected) && vanished(selected.name())) return true;

        // F8 or Delete: Destroy instance
        if (key.isKey(KeyCode.F8) || key.isKey(KeyCode.DELETE)) {
            openDeleteConfirm(selected.name());
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
        if (key.isKey(KeyCode.F7) && !key.hasShift() && isRunning(selected)) {
            execInBackground("Stopping " + selected.name(),
                    "Stopped " + selected.name(),
                    selected.name(),
                    "Stopped " + selected.name(),
                    Metadata.OP_STOPPING,
                    () -> incus.stop(selected.name()));
            return true;
        }
        if (key.isKey(KeyCode.F7) && key.hasShift() && isRunning(selected)) {
            execInBackground("Restarting " + selected.name(),
                    "Restarted " + selected.name(),
                    selected.name(),
                    "Restarted " + selected.name(),
                    Metadata.OP_RESTARTING,
                    () -> InstanceLifecycle.restartForUse(incus, selected.name(), selected.machineType(),
                            selected.mcpCaller()));
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
            actionsList = actions;
            actionsSelectedIndex = 0;
            actionsScrollOffset = 0;
            actionsContext = instanceActions.buildActionContext(selected);
            mode = Mode.ACTIONS;
            return true;
        }
        return false;
    }

    /** Keys that act on the selected instance (as opposed to navigating or viewing it). */
    private boolean isInstanceActionKey(KeyEvent key, InstanceInfo selected) {
        return key.isKey(KeyCode.F8) || key.isKey(KeyCode.DELETE) || key.isKey(KeyCode.F2)
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
        if (branch.branchNetworkMode() != NetworkMode.AIRGAP) {
            if (showProxyError()) return true;
            // With the accounts chosen above, from what the dialog read when it opened: no
            // Incus round trip on the event thread. BranchFlow.preflight re-checks live state.
            var credError = branchInherited == null ? "" : BranchFlow.credentialProblem(
                    branchInherited, branch.accountOverrides(), imageDefs, toolDefLoader);
            if (!credError.isEmpty()) {
                statusMessage = credError;
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
     * The account rows for a branch of {@code source}: what it would inherit, and every account
     * each credential its template's tools use could be pinned to instead. Best effort -- the
     * dialog is still worth opening without them, and {@code BranchFlow} validates what it gets.
     */
    /** What the open branch dialog's source inherits, or null when it could not be read. */
    private BranchFlow.Inherited branchInherited;

    private BranchAccountChoices branchAccountChoicesFor(String source, JsonNode sourceInstance) {
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
            incus.rename(renameSourceName, newName);
            try {
                InstanceLifecycle.removeHostIntegration(renameSourceName);
                AutoRemoteService.addRemotes(incus, newName, msg -> {});
                // The zmx device still points at the old name's directory,
                // which removeHostIntegration just deleted — re-point it.
                if (ZmxSocketForward.isZmxInstalled(
                        incus.configGet(newName, Metadata.BUILD_SOURCE))) {
                    ZmxSocketForward.configure(incus, newName);
                }
                if (InstanceLifecycle.hasSshCapability(incus, newName)) {
                    SshKeyManager.addHostEntry(newName);
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
            mode = Mode.BROWSE;
            if ("--all".equals(deleteConfirm.name())) {
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
                                    InstanceLifecycle.removeHostIntegration(name);
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
                    String msg = "Destroyed " + destroyed + " template(s)";
                    if (skipped > 0) msg += " (" + skipped + " skipped, locked)";
                    if (destroyed > 0 || skipped > 0) setStatusMessage(msg);
                    refreshDataAfterBackground();
                });
            } else if ("--all-instances".equals(deleteConfirm.name())) {
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
                                InstanceLifecycle.removeHostIntegration(entry.name());
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
                    if (destroyed > 0) InstanceDestroyer.refreshProxy();
                    String msg = "Destroyed " + destroyed + " instance(s)";
                    if (skipped > 0) msg += " (" + skipped + " skipped, locked)";
                    if (destroyed > 0 || skipped > 0) setStatusMessage(msg);
                    refreshDataAfterBackground();
                });
            } else if (!vanished(deleteConfirm.name())) {
                execInBackground("Deleting " + deleteConfirm.name(),
                        "Deleted " + deleteConfirm.name(),
                        deleteConfirm.name(),
                        "Destroyed " + deleteConfirm.name(),
                        Metadata.OP_DELETING,
                        () -> {
                            incus.delete(deleteConfirm.name(), true);
                            InstanceLifecycle.removeHostIntegration(deleteConfirm.name());
                            InstanceDestroyer.refreshProxy();
                        });
            }
        }
        mode = Mode.BROWSE;
        return true;
    }

    private void openBuildMenu(TemplateInfo template) {
        buildMenu = new BuildMenu(modal, theme, template, imageDefs, templateEntries, templatesOutOfSync);
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
        pendingAction = PendingAction.BUILD_TEMPLATE;
        pendingBuildArgs = option.buildArgs();
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
            statusMessage = pending;
        }
        // Announce warnings that arrived since the last frame, from whichever thread raised them.
        // Unless the dialog is open, which shows them already.
        if (mode != Mode.WARNINGS) {
            if (canAnnounceWarnings()) {
                var announced = WarningLog.statusLine(warningLog.takeUnannounced());
                if (announced != null) statusMessage = warningAnnouncement = announced;
            }
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

        renderHeader(frame, chunks.get(0));
        renderTemplateTable(frame, chunks.get(1));
        renderInstanceTable(frame, chunks.get(2), tableState);
        renderToolbar(frame, chunks.get(3), tableState, hasStatus);

        if (mode != Mode.BROWSE) {
            renderModal(frame, area, tableState);
        }

        // Progress overlay — rendered on top of everything else, regardless of mode
        if (progressMessage != null) {
            modal.renderProgressOverlay(frame, area, progressMessage);
        }
    }

    // Compact storage gauge width targets for the header's right-hand side.
    private static final int HEADER_BAR_MIN = 10;  // floor for the bar fill (excl. borders)
    private static final int HEADER_BAR_MAX = 32;  // cap so the bar stays a gauge, not a ruler

    /**
     * Render the always-present header band above the panels: a bold accent "brand chip" on the
     * left for app identity, and — when pool usage is available — a compact storage gauge
     * right-aligned on the same row. The gauge's fill colour signals the threshold
     * (green → amber → red) while the segment glyphs carry the level independently of colour, so
     * it reads on mono terminals too. Both the gauge and (last) its bar drop out on narrow
     * terminals so the brand always survives.
     */
    private void renderHeader(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        fillBackground(frame, area, theme.contextBg());
        if (area.width() <= 0) return;
        var bg = theme.contextBg();

        // Left: brand chip (reverse-video accent tag) + dim version. Stands out, ~5 cells.
        var left = new ArrayList<Span>();
        left.add(Span.styled(" isx ", Style.EMPTY.bold().fg(bg).bg(theme.contextAccentFg())));
        int leftW = 5;
        var version = BuildInfo.instance().version();
        if (version != null && !version.isBlank()) {
            var vtext = "  " + version;
            left.add(Span.styled(vtext, Style.EMPTY.fg(theme.contextSecondaryFg()).bg(bg)));
            leftW += vtext.length();
        }

        // Right: compact storage gauge, only when we have pool usage and room for it. Built first
        // so the centre badge can claim the leftover gap without ever evicting the gauge.
        var gauge = new ArrayList<Span>();
        int gaugeW = 0;
        if (poolUsage != null && poolUsage.totalBytes() > 0) {
            int percent = poolUsage.percent();
            Color fillColor = percent >= IncusClient.PoolUsage.CRIT_PERCENT ? theme.statusFailure()
                    : percent >= STORAGE_WARN_PERCENT ? theme.statusWarning()
                    : theme.statusRunning();
            var glabel = percent >= IncusClient.PoolUsage.CRIT_PERCENT ? "⚠ Storage " : "Storage ";
            var readout = "  " + percent + "%  " + gibShort(poolUsage.usedBytes())
                    + "/" + gibShort(poolUsage.totalBytes());

            int fixed = glabel.length() + readout.length();       // gauge text, sans bar/borders
            int avail = area.width() - leftW - 1;                 // -1 keeps at least one filler cell
            // Grow the bar with the terminal (up to HEADER_BAR_MAX) so it stays substantial on wide
            // screens and the whole gauge sits closer to centre instead of hugging the far edge.
            int idealBar = Math.max(HEADER_BAR_MIN, Math.min(HEADER_BAR_MAX, area.width() / 5));
            int barInner = Math.min(idealBar, avail - fixed - 2 /*borders*/);
            int candidateW = fixed + (barInner >= 1 ? barInner + 2 : 0);

            if (avail - candidateW >= 0) {                        // gauge (maybe sans bar) fits
                gaugeW = candidateW;
                gauge.add(Span.styled(glabel, Style.EMPTY.bold().fg(theme.contextPrimaryFg()).bg(bg)));
                if (barInner >= 1) {
                    gauge.add(Span.styled("▕", Style.EMPTY.fg(fillColor).bg(bg)));
                    gauge.add(Span.styled(bar(percent, barInner), Style.EMPTY.fg(fillColor).bg(bg)));
                    gauge.add(Span.styled("▏", Style.EMPTY.fg(fillColor).bg(bg)));
                }
                gauge.add(Span.styled(readout, Style.EMPTY.fg(fillColor).bg(bg)));
                if (percent >= STORAGE_WARN_PERCENT) {
                    var hint = "  C:clean";
                    if (avail - gaugeW >= hint.length()) {
                        gauge.add(Span.styled(hint, Style.EMPTY.fg(theme.textDim()).bg(bg)));
                        gaugeW += hint.length();
                    }
                }
            }
        }

        // Centre: auth-error warning (highest priority) or a quiet "N running" badge,
        // filling the gap between the version and the gauge.
        var centre = new ArrayList<Span>();
        int centreW = 0;
        var pi = proxyInfo;
        if (pi != null && pi.hasAuthError()) {
            var label = "  ⚠ Auth expired — run: " + pi.authRemediationHint();
            int need = label.length();
            if (area.width() - leftW - gaugeW - need >= 1) {
                centre.add(Span.styled(label, Style.EMPTY.bold().fg(theme.statusWarning()).bg(bg)));
                centreW = need;
            }
        }
        if (centreW == 0) {
            var skew = applianceSkewMessage;
            if (skew != null) {
                var label = "  !! " + skew;
                int need = label.length();
                if (area.width() - leftW - gaugeW - need >= 1) {
                    centre.add(Span.styled(label, Style.EMPTY.bold().fg(theme.statusWarning()).bg(bg)));
                    centreW = need;
                }
            }
        }
        if (centreW == 0) {
            int unread = warningLog.unread();
            if (unread > 0) {
                var label = "  ⚠ " + unread + (unread == 1 ? " warning" : " warnings")
                        + " (" + WarningsModal.KEY + ")";
                int need = label.length();
                if (area.width() - leftW - gaugeW - need >= 1) {
                    centre.add(Span.styled(label, Style.EMPTY.bold().fg(theme.statusWarning()).bg(bg)));
                    centreW = need;
                }
            }
        }
        if (centreW == 0) {
            var badge = runningSummary(runningCounts(BadgeKind.CONTAINER), runningCounts(BadgeKind.VM));
            if (!badge.isEmpty()) {
                int need = 4 + badge.length();                    // "  ● " prefix + text
                if (area.width() - leftW - gaugeW - need >= 1) {  // keep >=1 filler cell
                    centre.add(Span.styled("  ● ", Style.EMPTY.fg(theme.statusRunning()).bg(bg)));
                    centre.add(Span.styled(badge, Style.EMPTY.fg(theme.contextSecondaryFg()).bg(bg)));
                    centreW = need;
                }
            }
        }

        // Assemble left → centre → filler → gauge.
        var spans = new ArrayList<Span>(left);
        spans.addAll(centre);
        int fillerW = area.width() - leftW - centreW - gaugeW;
        if (fillerW > 0) spans.add(Span.styled(" ".repeat(fillerW), Style.EMPTY.bg(bg)));
        spans.addAll(gauge);
        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }

    private enum BadgeKind { CONTAINER, VM }

    /** Count running instances of one kind, across the unfiltered set. */
    private int runningCounts(BadgeKind kind) {
        var pool = allEntries != null ? allEntries : entries;
        if (pool == null) return 0;
        int n = 0;
        for (var e : pool) {
            if (!isRunning(e)) continue;
            boolean vm = "virtual-machine".equals(e.runtime());
            if ((kind == BadgeKind.VM) == vm) n++;
        }
        return n;
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

    private void renderTemplateTable(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        boolean focused = focusedPanel == Panel.TEMPLATES;
        var borderColor = focused ? theme.panelBorderFocused() : theme.panelBorderUnfocused();

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
                                Style.EMPTY.fg(theme.textDim()))), inner);
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
                .header(Row.from("NAME", "BUILT", "DISK", "DESCRIPTION")
                        .style(Style.EMPTY.bold().fg(focused ? theme.panelBorderFocused() : theme.panelBorderUnfocused())))
                .rows(templateRows)
                .widths(Constraint.min(14), Constraint.length(20), Constraint.length(8), Constraint.fill())
                .highlightSymbol(focused ? "\u25b8 " : "  ");

        if (focused) {
            var highlightStyle = Style.EMPTY.bg(theme.highlightBg()).fg(theme.highlightFg());
            // Preserve modifiers from selected row if it has a pending operation
            var selected = selectedTemplate();
            if (selected != null && !selected.pendingOp().isEmpty()) {
                if (Metadata.OP_DELETING.equals(selected.pendingOp())) {
                    highlightStyle = highlightStyle.addModifier(dev.tamboui.style.Modifier.DIM)
                            .addModifier(dev.tamboui.style.Modifier.ITALIC);
                } else if (Metadata.OP_STOPPING.equals(selected.pendingOp()) || Metadata.OP_RESTARTING.equals(selected.pendingOp())) {
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
                    .trackStyle(Style.EMPTY.fg(theme.scrollbarTrack()))
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
        var style = Style.EMPTY.fg(theme.textDim());
        frame.renderWidget(Paragraph.from(Line.from(
                Span.styled(" ".repeat(padding) + text, style))), area);
    }

    private void renderInstanceTable(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area,
                                      TableState tableState) {
        boolean focused = focusedPanel == Panel.INSTANCES;
        var borderColor = focused ? theme.panelBorderFocused() : theme.panelBorderUnfocused();

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
                                Style.EMPTY.fg(theme.textDim()))), hint.get(1));
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
                .header(Row.from("NAME", "STATUS", "IP", "PARENT", "RUNTIME", "AGE", "DISK")
                        .style(Style.EMPTY.bold().fg(focused ? theme.panelBorderFocused() : theme.panelBorderUnfocused())))
                .rows(tableRows)
                .widths(Constraint.min(10), Constraint.min(7),
                        Constraint.min(11), Constraint.min(10),
                        Constraint.length(10), Constraint.min(8), Constraint.length(6))
                .highlightSymbol(focused ? "\u25b8 " : "  ");

        if (focused) {
            var highlightStyle = Style.EMPTY.bg(theme.highlightBg()).fg(theme.highlightFg());
            // Preserve modifiers from selected row if it has a pending operation
            var selected = selectedEntry(tableState);
            if (selected != null && !selected.pendingOp().isEmpty()) {
                if (Metadata.OP_DELETING.equals(selected.pendingOp())) {
                    highlightStyle = highlightStyle.addModifier(dev.tamboui.style.Modifier.DIM)
                            .addModifier(dev.tamboui.style.Modifier.ITALIC);
                } else if (Metadata.OP_STOPPING.equals(selected.pendingOp()) || Metadata.OP_RESTARTING.equals(selected.pendingOp())) {
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
                    .trackStyle(Style.EMPTY.fg(theme.scrollbarTrack()))
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
        fillBackground(frame, area, theme.barBg());

        var template = selectedTemplate();
        boolean hasTemplate = template != null;
        boolean isBuilt = hasTemplate && !"not built".equals(template.buildStatus());
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
            var isWarning = singleLine.startsWith("⚠") || singleLine.startsWith("Warning");
            var statusBg = theme.statusBarBg();
            var msgFg = isError ? theme.statusBarErrorFg()
                    : isWarning ? theme.statusWarning() : theme.contextPrimaryFg();
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.styled(" " + singleLine,
                                    Style.EMPTY.bold().fg(msgFg))))
                            .style(Style.EMPTY.bg(statusBg))
                            .build(), rows.get(0));
            if (searchActive) {
                renderSearchBar(frame, rows.get(1));
            } else {
                renderContextLine(frame, rows.get(1), contextLine);
            }
            renderKeyItems(frame, rows.get(2), items);
        } else {
            var rows = splitVertical(area, 1, 1);
            if (searchActive) {
                renderSearchBar(frame, rows.get(0));
            } else {
                renderContextLine(frame, rows.get(0), contextLine);
            }
            renderKeyItems(frame, rows.get(1), items);
        }
    }

    private void renderSearchBar(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area) {
        fillBackground(frame, area, theme.contextBg());
        var cols = Layout.horizontal()
                .constraints(Constraint.length(3), Constraint.fill())
                .split(area);
        frame.renderWidget(Paragraph.from(Line.styled(" / ",
                Style.EMPTY.bold().fg(theme.contextAccentFg()).bg(theme.contextBg()))), cols.get(0));
        TextInput.builder()
                .placeholder("type to filter…")
                .style(Style.EMPTY.fg(theme.contextPrimaryFg()).bg(theme.contextBg()))
                .placeholderStyle(Style.EMPTY.fg(theme.textDim()).bg(theme.contextBg()))
                .build()
                .renderWithCursor(cols.get(1), frame.buffer(), searchInput, frame);
    }


    Line buildContextLine(TemplateInfo template, InstanceInfo instance, boolean onTemplates) {
        var bg = theme.contextBg();
        if (onTemplates && template != null) {
            var spans = new ArrayList<Span>();
            spans.add(Span.styled(" " + template.name(), Style.EMPTY.bold().fg(theme.contextPrimaryFg()).bg(bg)));
            boolean hasWarning = false;
            if (!"not built".equals(template.buildStatus())) {
                var warnStyle = Style.EMPTY.fg(theme.statusWarning()).bg(bg);
                var currentVersion = BuildInfo.instance().version();
                if (!template.buildVersion().isEmpty() && !template.buildVersion().equals(currentVersion)) {
                    spans.add(Span.styled("  ! built with isx v" + template.buildVersion()
                            + " (current: v" + currentVersion + ")", warnStyle));
                    hasWarning = true;
                } else if (template.buildVersion().isEmpty()) {
                    spans.add(Span.styled("  ! built before isx version tracking", warnStyle));
                    hasWarning = true;
                }
                if (templatesDefChanged.contains(template.name())) {
                    // The file name only: a full path could push the warnings after it off the bar
                    var def = imageDefs.get(template.name());
                    var builtFrom = TemplateDetailView.otherBuildFile(builtFrom(template.name()), def);
                    spans.add(Span.styled("  △ definition changed since last build"
                            + (builtFrom != null ? " (built from "
                                    + TemplateDetailView.shortSourceLabel(builtFrom, def.getSource())
                                    + ")" : ""),
                            warnStyle));
                    hasWarning = true;
                }
                if (templatesParentRebuilt.contains(template.name())) {
                    var parentName = imageDefs.get(template.name()).getParent();
                    spans.add(Span.styled("  ↑ parent " + parentName + " was rebuilt since last build", warnStyle));
                    hasWarning = true;
                }
            }
            if (!hasWarning && template.description() != null && !template.description().isEmpty()) {
                spans.add(Span.styled("  " + template.description(), Style.EMPTY.fg(theme.contextSecondaryFg()).bg(bg)));
            }
            return Line.from(spans);
        }
        if (!onTemplates && instance != null) {
            var spans = new ArrayList<Span>();
            spans.add(Span.styled(" " + instance.name(), Style.EMPTY.bold().fg(theme.contextPrimaryFg()).bg(bg)));
            if (!instance.parent().isEmpty() && !"-".equals(instance.parent())) {
                spans.add(Span.styled("  from " + OutputFormat.oneLine(instance.parent()), Style.EMPTY.fg(theme.contextSecondaryFg()).bg(bg)));
            }
            if (!instance.ipv4().isEmpty()) {
                spans.add(Span.styled("  " + instance.ipv4(), Style.EMPTY.fg(theme.contextAccentFg()).bg(bg)));
            }
            if (!instance.networkMode().isEmpty()) {
                spans.add(Span.styled("  [" + instance.networkMode().toLowerCase() + "]",
                        Style.EMPTY.fg(theme.contextSecondaryFg()).bg(bg)));
            }
            return Line.from(spans);
        }
        return Line.styled("", Style.EMPTY);
    }

    private void renderContextLine(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect area, Line line) {
        fillBackground(frame, area, theme.contextBg());

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
                    Style.EMPTY.fg(theme.statusWarning()).bg(theme.contextBg())));
            for (var task : running.stream().limit(2).toList()) {
                spans.add(Span.styled(" " + task.displayName() + "... ",
                        Style.EMPTY.fg(theme.contextPrimaryFg()).bg(theme.contextBg())));
            }
            if (running.size() > 2) {
                spans.add(Span.styled(" +" + (running.size() - 2) + " more ",
                        Style.EMPTY.fg(theme.contextSecondaryFg()).bg(theme.contextBg())));
            }
        }

        // Show completed tasks
        for (var task : completed) {
            if (task instanceof dev.incusspawn.tui.BackgroundTask.Completed completedTask) {
                var symbol = task.status() == dev.incusspawn.tui.BackgroundTask.TaskStatus.SUCCESS ? "✓" : "✗";
                var color = task.status() == dev.incusspawn.tui.BackgroundTask.TaskStatus.SUCCESS ? theme.statusSuccess() : theme.statusFailure();
                spans.add(Span.styled(" " + symbol + " " + completedTask.getDisplayText() + " ",
                        Style.EMPTY.fg(color).bg(theme.contextBg())));
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
        modal.renderScrim(frame, screen);
        switch (mode) {
            case CONFIRM_DELETE -> deleteConfirm.render(frame, screen);
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
                if (selected != null) instanceDetail.render(frame, screen, selected, detailAccountUses);
            }
            case ACCOUNTS -> accountsModal.render(frame, screen);
            case INFO -> renderInfoModal(frame, screen);
            case WARNINGS -> warningsModal.render(frame, screen);
            case HELP_CHAT -> helpChat.render(frame, screen);
            case ACTIONS -> renderActionsModal(frame, screen);
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
                if (hasPendingOp(selected) || backgroundTasks.hasRunningTask(selected.name())) {
                    statusMessage = "Operation in progress for " + selected.name();
                    yield true;
                }
                openAccountsModal(selected.name(), Mode.INSTANCE_DETAIL);
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
        if (key.isKey(KeyCode.HOME) || key.isChar('g')) {
            infoScrollOffset = 0;
            return true;
        }
        if (key.isKey(KeyCode.END) || key.isChar('G')) {
            infoScrollOffset = Integer.MAX_VALUE;
            return true;
        }
        if (key.isChar('?')) {
            openHelpChat(SpawnConfig.load());
            return true;
        }
        return true;
    }

    /** Opens AI Help, or explains in the status bar why no configured account can answer. */
    private void openHelpChat(SpawnConfig config) {
        var targets = AiHelpClient.targets(config);
        if (targets.isEmpty()) {
            statusMessage = AiHelpClient.noProviderMessage(config);
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
        if (key.isKey(KeyCode.HOME) || key.isChar('g')) {
            actionsSelectedIndex = 0;
            return true;
        }
        if (key.isKey(KeyCode.END) || key.isChar('G')) {
            actionsSelectedIndex = actionsList.size() - 1;
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            if (vanished(actionsContext.instanceName())) return true;
            var action = actionsList.get(actionsSelectedIndex);
            mode = Mode.BROWSE;
            if (dispatchAction(action, actionsContext)) tui.quit();
            return true;
        }
        return false;
    }

    private void renderInfoModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        var info = dev.incusspawn.BuildInfo.instance();
        var lines = new ArrayList<>(List.of(
                Line.from(List.of(
                        Span.styled("incus-spawn", Style.EMPTY.bold().fg(modal.accent()).bg(modal.bg())),
                        Span.styled(" (isx) ", Style.EMPTY.fg(modal.fg()).bg(modal.bg())),
                        Span.styled(info.version(), Style.EMPTY.fg(theme.statusSuccess()).bg(modal.bg())))),
                Line.styled("Commit " + info.gitSha(),
                        Style.EMPTY.fg(theme.textDim()).bg(modal.bg())),
                Line.styled("Incus  client " + info.incusClient() + ", server " + info.incusServer(),
                        Style.EMPTY.fg(theme.textDim()).bg(modal.bg())),
                Line.styled(info.runtime(),
                        Style.EMPTY.fg(theme.textDim()).bg(modal.bg()))));
        var kernelInfo = info.kernelInfo();
        if (!kernelInfo.isEmpty()) {
            var kernelLabel = Platform.isMacOS() ? "VM     " : "Host   ";
            lines.add(Line.styled(kernelLabel + kernelInfo,
                    Style.EMPTY.fg(theme.textDim()).bg(modal.bg())));
        }
        lines.addAll(List.of(
                Line.styled("", Style.EMPTY),
                Line.styled("Copyright 2026 Sanne Grinovero",
                        Style.EMPTY.fg(modal.fg()).bg(modal.bg())),
                Line.styled("Licensed under the Apache License 2.0",
                        Style.EMPTY.fg(theme.textDim()).bg(modal.bg())),
                Line.styled("github.com/Sanne/incus-spawn",
                        Style.EMPTY.fg(modal.accent()).bg(modal.bg()))
                        .hyperlink("https://github.com/Sanne/incus-spawn"),
                Line.styled("", Style.EMPTY),
                Line.styled("Manage isolated Incus development environments.",
                        Style.EMPTY.fg(modal.fg()).bg(modal.bg())),
                Line.styled("Templates define base images; Instances are", Style.EMPTY.fg(modal.fg()).bg(modal.bg())),
                Line.styled("lightweight copy-on-write branches of them.", Style.EMPTY.fg(modal.fg()).bg(modal.bg())),
                Line.styled("", Style.EMPTY),
                Line.styled("Keyboard shortcuts:", Style.EMPTY.fg(modal.fg()).bg(modal.bg())),
                Line.styled("", Style.EMPTY),
                shortcutRow("Enter", "Default instance action", null, null),
                shortcutRow("?", "AI Help — ask a question", null, null),
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
                shortcutRow("F10", "Quit", null, null),
                shortcutRow("a", "Credential accounts", null, null),
                shortcutRow("C", "Clean pool storage", null, null),
                shortcutRow("r", "Refresh", null, null),
                shortcutRow(WarningsModal.KEY, "Warnings", null, null),
                shortcutRow("n", "New template…", null, null),
                shortcutRow("/", "Search / filter", null, null),
                shortcutRow("g/Home", "Jump to top", "G/End", "Jump to bottom")));
        if (Platform.isMacOS()) {
            lines.add(Line.styled("", Style.EMPTY));
            lines.add(Line.styled("macOS shortcuts:", Style.EMPTY.fg(modal.fg()).bg(modal.bg())));
            lines.add(shortcutRow("Fn+←/→", "Home / End", "Fn+↑/↓", "PgUp / PgDn"));
        }

        int width = 60;
        int maxHeight = screen.height() - 2;
        int modalHeight = Math.min(lines.size() + 4, maxHeight);

        var modalArea = ModalRenderer.centerRect(screen, width, modalHeight);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(modal.styledTitle(" About incus-spawn ", modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        modal.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var rows = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);

        infoScrollOffset = modal.renderScrollableContent(frame, rows.get(0), lines, infoScrollOffset);

        var hintSpans = new ArrayList<Span>();
        modal.addKey(hintSpans, "F1/Esc", "Close");
        modal.addKey(hintSpans, "?", "AI-assisted help");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(1));
    }

    static String buildStatusMessage(String[] args, boolean success, java.time.Instant buildStart) {
        var firstArg = args[0];
        // isx build takes several templates (#1130): name them all.
        var targets = String.join(", ", java.util.Arrays.stream(args).filter(a -> !a.startsWith("--")).toList());
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

    private Line shortcutRow(String key, String desc, String shiftKey, String shiftDesc) {
        var spans = new ArrayList<Span>();
        var keyStr = key != null ? key : "";
        var descStr = desc != null ? desc : "";
        spans.add(Span.styled(String.format("  %-8s", keyStr), Style.EMPTY.bold().fg(modal.accent()).bg(modal.bg())));
        spans.add(Span.styled(String.format("%-18s", descStr), Style.EMPTY.fg(modal.fg()).bg(modal.bg())));
        if (shiftKey != null) {
            spans.add(Span.styled(String.format("%-9s", shiftKey), Style.EMPTY.bold().fg(modal.accent()).bg(modal.bg())));
            spans.add(Span.styled(shiftDesc, Style.EMPTY.fg(modal.fg()).bg(modal.bg())));
        }
        return Line.from(spans);
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
            statusMessage = "No credential accounts configured. Run 'isx init' to add one.";
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
                                msg -> { }, warningLog::add);
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

    private boolean hasActionsForInstance(InstanceInfo instance) {
        return getActionsForInstance(instance).stream()
                .anyMatch(a -> !a.requiresRunning() || isRunning(instance));
    }

    private java.util.List<ToolAction> getActionsForInstance(InstanceInfo instance) {
        return actionsCache.getOrDefault(instance.name(), java.util.List.of());
    }

    private java.util.Optional<ToolAction> findDefaultAction(String ref, InstanceInfo instance) {
        var parsed = InstanceActions.parseActionRef(ref);
        var actions = getActionsForInstance(instance);
        var matching = actions.stream()
                .filter(a -> parsed.toolName().equals(a.toolName()))
                .toList();

        if (matching.isEmpty()) {
            statusMessage = "default-action '" + ref + "': tool not found";
            return java.util.Optional.empty();
        }

        var result = InstanceActions.resolveActionByRef(parsed, matching);
        if (result.isEmpty()) {
            if (parsed.actionId() != null) {
                statusMessage = "default-action '" + ref + "': action id not found or ambiguous";
            } else {
                statusMessage = "default-action '" + ref + "': ambiguous, use tool:action-id";
            }
        }
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
     * render and drive keys into. Call {@link #useDefinitions} first.
     */
    void startSession(IncusClient incus, BackgroundTaskManager backgroundTasks, InstanceLockManager lockManager,
                      List<InstanceInfo> instances) {
        this.incus = incus;
        this.backgroundTasks = backgroundTasks;
        this.lockManager = lockManager;
        mergeInstances(instances);
        publishRows();
        mode = Mode.BROWSE;
        pendingAction = PendingAction.NONE;
        templateTableState = new TableState();
        instanceTableState = new TableState();
        if (!templateEntries.isEmpty()) templateTableState.select(0);
        selectFirstDataRow(instanceTableState);
        focusedPanel = Panel.TEMPLATES;
    }

    /** One frame, as the runner draws it. */
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

    PendingAction pendingAction() {
        return pendingAction;
    }

    boolean dispatchAction(ToolAction action, ActionContext context) {
        var cmd = action.shellCommand(context);
        if (cmd.isPresent()) {
            pendingAction = PendingAction.SHELL_WITH_COMMAND;
            pendingShellCommand = cmd.get();
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

    private ShellMenu shellMenu(String instanceName, IncusClient.ShellPrep prep) {
        // Read fresh, as isx shell does, not from the list's cache: its entry can predate the
        // instance (a branch just made from the dialog) or its start (no IP yet), and its parent
        // is the direct one where the bar shows the leaf template.
        return new ActionResolver(incus, toolDefLoader, cdiTools, imageDefs)
                .shellMenu(instanceName, prep.templateName(), prep.workdir());
    }

    private String suggestBranchName(String sourceName) {
        var base = sourceName.startsWith("tpl-") ? sourceName.substring(4) : sourceName;
        var existingNames = entries.stream().map(e -> e.name()).collect(java.util.stream.Collectors.toSet());
        for (int i = 1; ; i++) {
            var candidate = base + "-" + i;
            if (!existingNames.contains(candidate)) return candidate;
        }
    }


    private KeyItem makeKey(String key, String label, boolean disabled) {
        var spans = new ArrayList<Span>();
        spans.add(Span.styled("│", Style.EMPTY.fg(theme.barSeparatorFg()).bg(theme.barBg())));
        if (disabled) {
            spans.add(Span.styled(key, Style.EMPTY.fg(theme.barDisabledFg()).bg(theme.barBg())));
            spans.add(Span.styled(label, Style.EMPTY.fg(theme.barDisabledFg()).bg(theme.barBg())));
        } else {
            spans.add(Span.styled(key, Style.EMPTY.bold().fg(theme.barKeyFg()).bg(theme.barBg())));
            spans.add(Span.styled(label, Style.EMPTY.fg(theme.barLabelFg()).bg(theme.barBg())));
        }
        return new KeyItem(Line.from(spans), 1 + key.length() + label.length());
    }

    private void renderActionsModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        if (actionsList == null || actionsList.isEmpty()) return;

        var lines = new ArrayList<Line>();
        for (int i = 0; i < actionsList.size(); i++) {
            var action = actionsList.get(i);
            var selected = (i == actionsSelectedIndex);
            var prefix = selected ? " > " : "   ";
            var style = selected
                    ? Style.EMPTY.bold().fg(theme.focusedLabel()).bg(modal.bg())
                    : Style.EMPTY.fg(modal.fg()).bg(modal.bg());
            if (action instanceof YamlToolAction ya && ya.isUrl()) {
                var url = ya.resolveUrl(actionsContext);
                if (url != null && !url.isBlank()) {
                    style = style.hyperlink(url);
                }
            }
            var toolStyle = Style.EMPTY.fg(theme.textDim()).bg(modal.bg());
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
                .title(modal.styledTitle(" Actions — " + instanceName + " ", modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        modal.renderBlock(frame, block, modalArea);
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

        actionsScrollOffset = modal.renderScrollableContent(frame, rows.get(0), lines, actionsScrollOffset);

        var hintSpans = new ArrayList<Span>();
        modal.addKey(hintSpans, "Enter", "Run");
        modal.addKey(hintSpans, "F9/Esc", "Close");
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

    static boolean isRunning(InstanceInfo entry) {
        return "RUNNING".equalsIgnoreCase(entry.status());
    }

    private static boolean hasPendingOp(InstanceInfo entry) {
        return entry != null && !entry.pendingOp().isEmpty();
    }

    private static boolean hasPendingOp(TemplateInfo template) {
        return template != null && !template.pendingOp().isEmpty();
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
        try {
            preservingSelection(tableState, this::reloadData);
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
        var pool = usagePoolName;
        Thread.ofVirtual().name("tui-live-refresh").start(() -> {
            LiveSnapshot snapshot;
            try {
                var instances = clearStalePendingOps(collectEntries());
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
        preservingSelection(tableState, () -> applyLiveInstances(snapshot.instances(), snapshot.poolUsage()));
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
            case ACTIONS -> actionsContext != null ? actionsContext.instanceName() : null;
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
                BuildInfo.instance().version());

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

    private void buildRowData() {
        tableRows = new ArrayList<>();
        rowToEntry = new ArrayList<>();

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

            tableRows.add(Row.from(entry.name(), entry.status(), entry.ipv4(),
                    parent, entry.runtime(), age, diskCell(entry.diskUsage())).style(statusStyle));
            rowToEntry.add(entry);
        }
    }

    /**
     * Branch through {@link BranchFlow}, as {@code isx branch} does, so a TUI branch gets the same
     * account selection, proxy refresh, CA and identity repairs (#800). Only the inputs (the
     * modal's fields) and the shell that follows are the TUI's own.
     */
    private void createBranchFromModal(String name) {
        var source = branchSourceName;
        var request = branch.request(name);

        BranchFlow.Preflight preflight;
        InstanceLifecycle.RuntimeConfig prefetched;
        // Held through the branch's start, so a rebuild cannot swap the template away meanwhile (#1212).
        // Plain stdout: the TUI has released the terminal for the branch and the shell after it.
        try (var held = TemplateLock.reading(source, System.out::println)) {
            preflight = BranchFlow.preflight(incus, request, imageDefs);
            prefetched = BranchFlow.create(incus, preflight);
        }

        BuildOutput.success(name + " is ready.");
        var shellPrep = prefetched.toShellPrep();
        var defaultCmd = new ActionResolver(incus, toolDefLoader, cdiTools, preflight.defs())
                .defaultCommandForBranch(preflight.template(), preflight.sourceInstance());
        if (defaultCmd != null) {
            shellPrep = shellPrep.withActionCommand(defaultCmd);
        }
        incus.interactiveShell(name, "agentuser", shellPrep, shellMenu(name, shellPrep));
        System.out.println();
    }

    private volatile boolean proxyRestartInProgress;
    private volatile boolean dnsVerified;

    private void tryFixStaleDns() {
        if (dnsVerified) return;
        dnsVerified = true;
        var toolProxyDomains = dev.incusspawn.proxy.ToolProxyResolver.resolvedDomains(SpawnConfig.load());
        var allDomains = ProxyConfig.interceptedDomains(toolProxyDomains);
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
                    if (ProxyHealthCheck.tryAutoRestart(incus, msg -> {})) {
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
            showSubnetWarning();
            fixStaticIpIfNeeded(target.name(), target.machineType());
            fixCaMismatchIfNeeded(target.name(), target.machineType());
            fixResolvConfIfNeeded(target.name());
            return false;
        } catch (IncusException e) {
            errorMessage = "Cannot reach Incus: " + e.getMessage();
            mode = Mode.ERROR;
            return true;
        }
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

    private void fixStaticIpIfNeeded(String name, MachineType machineType) {
        if (!"Stopped".equalsIgnoreCase(incus.getInstanceStatus(name))) return;
        // Runs on the TUI's own screen: printing would draw over it. A warning (spoofing
        // protection refused, an unusable allocation lock) goes to the warning log; progress
        // does not, since nothing can render until this returns.
        var output = new StaticIpAllocator.Output(msg -> {}, warningLog::add);
        try {
            if (InstanceLifecycle.fixStaticIpIfNeeded(incus, name, output, machineType)) {
                statusMessage = "Static IP reassigned to current bridge subnet";
            }
        } catch (Exception ignored) {
        }
    }

    private void fixResolvConfIfNeeded(String name) {
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(name))) return;
        try {
            ProxyConfig.fixResolvConfIfNeeded(incus, name);
        } catch (Exception ignored) {
        }
    }

    private void fixCaMismatchIfNeeded(String containerName, MachineType machineType) {
        JsonNode started = null;
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(containerName))) {
            // Runs on the TUI's own screen, where stderr would be drawn over: a mount dropped
            // because its host directory is gone must not go unannounced (#852), so it goes to
            // the warning log, which the status line announces when the shell returns to the TUI.
            // Through InstanceLifecycle, as isx shell's start is: the prep re-arms IP spoofing
            // protection (#905), the start falls back where the host cannot enforce it, and the
            // instance gets its new secret (#934).
            started = InstanceLifecycle.startForUse(incus, containerName, machineType, warningLog::add);
        }
        // The start's own read of the instance, when there was one: no need to read it again
        CertificateAuthority.fixContainerCaIfNeeded(incus, containerName, started);
    }

    private void shellInto(ActionContext target) {
        shellInto(target, null);
    }

    private void shellInto(ActionContext target, String commandOverride) {
        var name = target.name();
        var machineType = target.machineType();
        // Read after fixStaticIpIfNeeded, so a VM it just reassigned is seen to owe its file
        var instance = incus.instanceMetadata(name);
        var status = instance.path("status").asText("");
        // An empty status means any failed lookup, not just a missing instance, so confirm before
        // giving up: a daemon hiccup must not cancel a shell on an instance that's still there.
        if (status.isEmpty() && !incus.exists(name)) {
            // Deleted between the TUI's check and now: report it back in the TUI (which reloads
            // on re-entry) instead of failing on a start or exec against a missing instance.
            statusMessage = name + " no longer exists";
            return;
        }
        // Runs after the TUI has released the terminal, so plain stdout is safe here.
        InstanceLifecycle.ensureReady(incus, name, instance, machineType, System.out::println);
        ZmxSocketForward.ensureSymlink(name);
        checkGuiHealth(name);
        System.out.println("Connecting to " + name + "...\n");
        var titleMonitor = startAuthTitleMonitor(name);
        try {
            var prep = IncusClient.ShellPrep.from(incus, name);
            if (commandOverride != null) prep = prep.withActionCommand(commandOverride);
            incus.interactiveShell(name, "agentuser", prep, shellMenu(name, prep));
        } finally {
            titleMonitor.interrupt();
        }
        System.out.println();
    }

    private void checkGuiHealth(String name) {
        GuiPassthrough.checkGuiHealth(incus, name);
    }

    private List<InstanceInfo> collectEntries() {
        return InstanceListing.collectEntries(incus.listJson());
    }

}
