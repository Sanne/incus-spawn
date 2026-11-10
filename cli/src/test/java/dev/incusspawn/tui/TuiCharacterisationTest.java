package dev.incusspawn.tui;

import dev.incusspawn.Environment;
import dev.incusspawn.ai.AiHelpClient;
import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The TUI as a whole: the main screen rendered at two sizes, and what each key leads to, from the
 * mode a dialog opens in to the action left pending when the runner quits. Each dialog's own
 * rendering is snapshotted by its own test.
 */
@ExtendWith({IsolatedHome.class, dev.incusspawn.lifecycle.LinuxBranching.class})
class TuiCharacterisationTest {

    private static final KeyEvent ESC = KeyEvent.ofKey(KeyCode.ESCAPE);
    private static final KeyEvent TAB = KeyEvent.ofKey(KeyCode.TAB);
    private static final KeyEvent ENTER = KeyEvent.ofKey(KeyCode.ENTER);
    private static final KeyEvent DOWN = KeyEvent.ofKey(KeyCode.DOWN);

    private TuiRunner runner;

    /** The host firewall's banner title, as the stood-in check reports it; null for a running one. */
    private String stoppedFirewall;

    /** The tools tpl-minimal declares; none unless a test says. */
    private List<ToolDef.ToolRef> minimalTools = List.of();

    @BeforeEach
    void runner() throws Exception {
        runner = TuiRunner.create(TuiConfig.builder().backend(new HeadlessBackend(80, 24))
                .shutdownHook(false).noTick().build());
    }

    @AfterEach
    void close() {
        runner.close();
    }

    private static final String VERSION = "1.2.3";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Minutes before now, as a creation stamp; half an hour from a unit boundary, so its age is stable. */
    private static String ago(long minutes) {
        return LocalDateTime.now().minusMinutes(minutes).format(STAMP);
    }

    private static ImageDef def(String name, String parent, String description) {
        var def = new ImageDef();
        def.setName(name);
        def.setImage(parent == null ? "fedora-44" : null);
        def.setParent(parent);
        def.setDescription(description);
        return def;
    }

    private static Map<String, String> template(String name, String parent) {
        var config = new HashMap<String, String>();
        config.put(Metadata.TYPE, Metadata.TYPE_BASE);
        config.put(Metadata.PROFILE, name);
        config.put(Metadata.CREATED, ago(3 * 24 * 60 + 30));
        config.put(Metadata.BUILD_VERSION, VERSION);
        if (parent != null) config.put(Metadata.PARENT, parent);
        return config;
    }

    private static Map<String, String> branch(String parent, long minutesAgo) {
        var config = new HashMap<String, String>();
        config.put(Metadata.TYPE, "branch");
        config.put(Metadata.PROFILE, parent);
        config.put(Metadata.PARENT, parent);
        config.put(Metadata.CREATED, ago(minutesAgo));
        config.put(Metadata.NETWORK_MODE, "proxy");
        return config;
    }

    /** Two templates (one built), a running container branch and a stopped VM branch. */
    private Tui tui(boolean withInstances) {
        return tui(withInstances, new FakeIncusDaemon());
    }

    private Tui tui(boolean withInstances, FakeIncusDaemon daemon) {
        var defs = new LinkedHashMap<String, ImageDef>();
        var minimal = def("tpl-minimal", null, "Minimal Fedora");
        minimal.setTools(minimalTools);
        defs.put("tpl-minimal", minimal);
        defs.put("tpl-dev", def("tpl-dev", "tpl-minimal", "Development tools"));
        daemon.container("tpl-minimal", template("tpl-minimal", null));
        if (withInstances) {
            daemon.instance("work-1", "container", "Running", branch("tpl-minimal", 3 * 60 + 30))
                    .instance("vm-1", "virtual-machine", "Stopped", branch("tpl-minimal", 2 * 24 * 60 + 30));
        }
        // The proxy check reads the host's proxy, and may start a DNS repair: not this test's to run.
        var tui = new Tui() {
            @Override
            boolean showProxyError() {
                return false;
            }

            // The host's firewall neither: the test says whether it is stopped.
            @Override
            void warnIfFirewallStopped(BiConsumer<String, String> banner) {
                if (stoppedFirewall != null) banner.accept(stoppedFirewall, "Fix:\n  sudo systemctl enable --now firewalld");
            }

            // A version no build has, so the header and the outdated marks never move with a release.
            @Override
            String currentVersion() {
                return VERSION;
            }

            @Override
            String displayVersion() {
                return VERSION;
            }
        };
        tui.useDefinitions(defs, new ToolDefLoader(List.of()), List.of());
        tui.startSession(daemon.client(), new BackgroundTaskManager(), new FlockInstanceLockManager(),
                InstanceListing.collectEntries(daemon.client().listJson()));
        return tui;
    }

    private Tui tui() {
        return tui(true);
    }

    private void press(Tui tui, KeyEvent... keys) {
        for (var key : keys) tui.handleEvent(key, runner);
    }

    private void typeText(Tui tui, String text) {
        for (char c : text.toCharArray()) press(tui, KeyEvent.ofChar(c));
    }

    private static void snapshot(String name, Tui tui) {
        snapshotInContext(name, tui);
        TuiSnapshot.assertMatches("tui-" + name + "-120x40", TuiSnapshot.render(120, 40, tui::render));
    }

    /**
     * One size only, for a rendering that exists only over the main screen: each rendering keeps
     * one snapshot, and the main screen itself is pinned at both sizes above (#1251).
     */
    private static void snapshotInContext(String name, Tui tui) {
        TuiSnapshot.assertMatches("tui-" + name + "-80x24", TuiSnapshot.render(80, 24, tui::render));
    }

    private void opensAndCloses(String name, Tui tui, Tui.Mode expected, KeyEvent... keys) {
        opensAndCloses(name, tui, expected, open -> {}, keys);
    }

    /** As above, with {@code whileOpen} checking what the dialog was opened on. */
    private void opensAndCloses(String name, Tui tui, Tui.Mode expected, java.util.function.Consumer<Tui> whileOpen,
                                KeyEvent... keys) {
        press(tui, keys);
        assertEquals(expected, tui.mode(), name);
        whileOpen.accept(tui);
        press(tui, ESC);
        assertEquals(Tui.Mode.BROWSE, tui.mode(), name + " closes on Esc");
    }

    @Test
    void mainScreenTemplatesFocused() {
        snapshot("main-templates", tui());
    }

    @Test
    void mainScreenInstancesFocused() {
        var tui = tui();
        press(tui, TAB);
        snapshot("main-instances", tui);
    }

    @Test
    void mainScreenSecondRowsSelected() {
        var tui = tui();
        press(tui, DOWN, TAB, DOWN);
        snapshot("main-second-rows", tui);
    }

    @Test
    void mainScreenWithoutInstances() {
        snapshot("main-empty", tui(false));
    }

    @Test
    void mainScreenWithGaugeLegendAndStatus() {
        // Another isx built tpl-dev, so it is outdated and the legend shows; the pool is 63% full.
        var old = template("tpl-dev", "tpl-minimal");
        old.put(Metadata.BUILD_VERSION, VERSION + "-older");
        var tui = tui(true, new FakeIncusDaemon().container("tpl-dev", old));
        tui.poolUsage = new IncusClient.PoolUsage(63L << 30, 100L << 30);
        tui.statusMessage = "Created branch work-2";
        snapshotInContext("main-gauge-legend-status", tui);
    }

    @Test
    void searching() {
        var tui = tui();
        press(tui, KeyEvent.ofChar('/'));
        typeText(tui, "work");
        snapshot("search", tui);
        press(tui, ESC);
        // Closing the search restores the screen it started from, so it needs no golden of its own.
        var fresh = tui();
        for (int[] size : new int[][] {{80, 24}, {120, 40}}) {
            assertEquals(TuiSnapshot.toText(TuiSnapshot.render(size[0], size[1], fresh::render)),
                    TuiSnapshot.toText(TuiSnapshot.render(size[0], size[1], tui::render)),
                    "closing the search restores the screen at " + size[0] + "x" + size[1]);
        }
    }

    @Test
    void shortcutsHelp() {
        opensAndCloses("info", tui(), Tui.Mode.INFO, KeyEvent.ofKey(KeyCode.F1));
    }

    @Test
    void buildMenu() {
        opensAndCloses("build-menu", tui(), Tui.Mode.BUILD_MENU, KeyEvent.ofKey(KeyCode.F5));
    }

    @Test
    void branchFromATemplate() {
        opensAndCloses("branch-template", tui(), Tui.Mode.BRANCH, open -> {
            var request = open.branch.request("x");
            assertEquals("tpl-minimal", request.source());
            assertFalse(open.branch.sourceIsVm(), "a container's branch has no limit fields");
        }, ENTER);
    }

    @Test
    void branchFromAVm() {
        var tui = tui();
        press(tui, TAB);
        // The VM is the second instance row.
        press(tui, DOWN);
        opensAndCloses("branch-vm", tui, Tui.Mode.BRANCH, open -> {
            var request = open.branch.request("x");
            assertEquals("vm-1", request.source());
            assertTrue(open.branch.sourceIsVm(), "a VM's branch offers its limits");
        }, KeyEvent.ofKey(KeyCode.F4));
    }

    @Test
    void branchFieldsAreWalkedWithTab() {
        var tui = tui();
        // Name, then GUI, then KVM, which Space toggles.
        press(tui, ENTER, TAB, TAB, KeyEvent.ofChar(' '));
        assertEquals(Tui.Mode.BRANCH, tui.mode());
        var request = tui.branch.request("x");
        assertEquals(Boolean.TRUE, request.kvm());
        assertNull(request.gui(), "GUI was passed over, so it keeps its default");
    }

    @Test
    void branchFromAnUnbuiltTemplateOffersABuild() {
        var tui = tui();
        press(tui, DOWN, ENTER);
        assertEquals(Tui.Mode.CONFIRM_BUILD_FOR_BRANCH, tui.mode());
        snapshotInContext("confirm-build-for-branch", tui);
        press(tui, ESC);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
    }

    @Test
    void newChildTemplate() {
        var tui = tui();
        press(tui, KeyEvent.ofChar('n'));
        assertEquals(Tui.Mode.NEW_TEMPLATE, tui.mode());
        // Typed text belongs to the dialog: 'c' must not open the pool cleanup behind it.
        typeText(tui, "tpl-child");
        assertEquals(Tui.Mode.NEW_TEMPLATE, tui.mode());
        press(tui, ESC);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
    }

    @Test
    void deleteATemplate() {
        opensAndCloses("confirm-delete-template", tui(), Tui.Mode.CONFIRM_DELETE,
                open -> assertEquals("tpl-minimal", open.deleteConfirm().name()),
                KeyEvent.ofKey(KeyCode.F8));
    }

    @Test
    void deleteEveryTemplate() {
        opensAndCloses("confirm-delete-all-templates", tui(), Tui.Mode.CONFIRM_DELETE,
                open -> assertEquals("--all", open.deleteConfirm().name()),
                KeyEvent.ofKey(KeyCode.F8, KeyModifiers.SHIFT));
    }

    @Test
    void deleteAnInstance() {
        var tui = tui();
        press(tui, TAB);
        opensAndCloses("confirm-delete-instance", tui, Tui.Mode.CONFIRM_DELETE,
                open -> assertEquals("work-1", open.deleteConfirm().name()),
                KeyEvent.ofKey(KeyCode.F8));
    }

    @Test
    void deleteEveryInstance() {
        var tui = tui();
        press(tui, TAB);
        opensAndCloses("confirm-delete-all-instances", tui, Tui.Mode.CONFIRM_DELETE,
                open -> assertEquals("--all-instances", open.deleteConfirm().name()),
                KeyEvent.ofKey(KeyCode.F8, KeyModifiers.SHIFT));
    }

    @Test
    void renameARunningInstanceAsksToStopIt() {
        var tui = tui();
        press(tui, TAB, KeyEvent.ofKey(KeyCode.F6));
        assertEquals(Tui.Mode.CONFIRM_STOP_FOR_RENAME, tui.mode());
        snapshotInContext("confirm-stop-for-rename", tui);
        press(tui, ESC);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
    }

    @Test
    void renameAStoppedInstance() {
        var tui = tui();
        press(tui, TAB, DOWN);
        opensAndCloses("rename", tui, Tui.Mode.RENAME, KeyEvent.ofKey(KeyCode.F6));
    }

    @Test
    void instanceDetails() {
        var tui = tui();
        press(tui, TAB);
        opensAndCloses("instance-detail", tui, Tui.Mode.INSTANCE_DETAIL, KeyEvent.ofKey(KeyCode.F3));
    }

    @Test
    void templateDetails() {
        opensAndCloses("template-detail", tui(), Tui.Mode.TEMPLATE_DETAIL, KeyEvent.ofKey(KeyCode.F3));
    }

    @Test
    void f9WithNoActionsSaysSo() {
        var tui = tui();
        press(tui, TAB, KeyEvent.ofKey(KeyCode.F9));
        assertEquals(Tui.Mode.BROWSE, tui.mode(), "nothing to offer opens no dialog");
        assertEquals("No actions available for work-1", tui.statusMessage);
    }

    @Test
    void cleaningThePool() {
        var tui = tui(true, new FakeIncusDaemon().pool("default", "btrfs").image("0123456789abcdef"));
        opensAndCloses("clean-confirm", tui, Tui.Mode.CLEAN_CONFIRM, KeyEvent.ofChar('c'));
    }

    @Test
    void warningsDialog() {
        opensAndCloses("warnings", tui(), Tui.Mode.WARNINGS, KeyEvent.ofChar('w'));
    }

    @Test
    void shellQuitsTheRunnerAndLeavesTheShellPending() {
        var tui = tui();
        press(tui, TAB, KeyEvent.ofKey(KeyCode.F2));
        assertEquals(Tui.PendingAction.SHELL, tui.pendingAction());
        assertFalse(runner.isRunning());
    }

    /** {@code n} on an instance is a shell in a new session, independent of any other terminal (#2059). */
    @Test
    void nOnAnInstanceLeavesAShellInANewSessionPending() {
        var tui = tui();
        press(tui, TAB, KeyEvent.ofChar('n'));
        assertEquals(Tui.PendingAction.SHELL_NEW_SESSION, tui.pendingAction());
        assertFalse(runner.isRunning());
    }

    /** A running instance an isx mcp session made, whose guest lists {@code tasks} ({@code TaskScripts.joinable}). */
    private Tui mcpTui(String tasks) {
        var config = branch("tpl-minimal", 30);
        config.put(Metadata.MCP_OWNER, "alice");
        config.put(Metadata.MCP_SESSION, "1-1");
        return tui(false, new FakeIncusDaemon().instance("mcp-box", "container", "Running", config)
                .execOutput("prompt-1.md", tasks));
    }

    private static final KeyEvent SHIFT_F2 = KeyEvent.ofKey(KeyCode.F2, KeyModifiers.SHIFT);

    @Test
    void shiftF2JoinsTheOnlyTaskAndF2IsStillAShell() {
        var tui = mcpTui("task t1-abcd finished 1700000000\nsid t1-abcd sess-1\ncwd t1-abcd /home/agentuser/w\nprompt t1-abcd Fix it\n");
        press(tui, TAB, SHIFT_F2);
        assertEquals(Tui.PendingAction.SHELL_WITH_COMMAND, tui.pendingAction());
        assertTrue(tui.pendingShellCommand().contains("cd '/home/agentuser/w' || isx_refuse"), tui.pendingShellCommand());
        assertTrue(tui.pendingShellCommand().contains("claude --resume sess-1; rc=$?"), tui.pendingShellCommand());
        assertFalse(runner.isRunning());

        tui = mcpTui("");
        press(tui, TAB, KeyEvent.ofKey(KeyCode.F2));
        assertEquals(Tui.PendingAction.SHELL, tui.pendingAction());
    }

    @Test
    void shiftF2OffersSeveralTasksAndRefusesOneMidTurn() {
        var tui = mcpTui("task t1-abcd running 1700000000\nsid t1-abcd sess-1\ncwd t1-abcd /a\n"
                + "task t2-abcd finished 1700000000\nsid t2-abcd sess-2\ncwd t2-abcd /b\n");
        press(tui, TAB, SHIFT_F2);
        assertEquals(Tui.Mode.TASK_PICKER, tui.mode());
        assertTrue(runner.isRunning());
        press(tui, ENTER);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
        assertTrue(tui.statusMessage.startsWith("t1-abcd is mid-turn"), tui.statusMessage);
        assertEquals(Tui.PendingAction.NONE, tui.pendingAction());
        press(tui, SHIFT_F2, DOWN, ENTER);
        assertTrue(tui.pendingShellCommand().contains("claude --resume sess-2; rc=$?"), tui.pendingShellCommand());
        assertFalse(runner.isRunning());
    }

    @Test
    void thePickerJoinsWhatTheTaskIsNowNotWhatItWasWhenItOpened() {
        var daemon = new FakeIncusDaemon();
        var config = branch("tpl-minimal", 30);
        config.put(Metadata.MCP_OWNER, "alice");
        config.put(Metadata.MCP_SESSION, "1-1");
        var tui = tui(false, daemon.instance("mcp-box", "container", "Running", config)
                .execOutput("prompt-1.md", "task t1-abcd finished 1700000000\nsid t1-abcd sess-1\ncwd t1-abcd /a\n"
                        + "task t2-abcd finished 1700000000\nsid t2-abcd sess-2\ncwd t2-abcd /b\n"));
        press(tui, TAB, SHIFT_F2);
        assertEquals(Tui.Mode.TASK_PICKER, tui.mode());
        daemon.execOutput("prompt-1.md", "task t1-abcd running 1700000000\nsid t1-abcd sess-1\ncwd t1-abcd /a\n");
        press(tui, ENTER);
        assertEquals(Tui.PendingAction.NONE, tui.pendingAction(), "a turn started since it opened");
        assertTrue(tui.statusMessage.startsWith("t1-abcd is mid-turn"), tui.statusMessage);
    }

    @Test
    void thePickerJoinsNothingWhileTheInstanceIsBusy() {
        var tui = mcpTui("task t1-abcd finished 1700000000\nsid t1-abcd sess-1\ncwd t1-abcd /a\n"
                + "task t2-abcd finished 1700000000\nsid t2-abcd sess-2\ncwd t2-abcd /b\n");
        press(tui, TAB, SHIFT_F2);
        assertEquals(Tui.Mode.TASK_PICKER, tui.mode());
        assertTrue(tui.backgroundTasks.tryClaim("mcp-box"), "a stop or destroy started meanwhile");
        press(tui, ENTER);
        assertEquals(Tui.PendingAction.NONE, tui.pendingAction());
        assertEquals("Operation in progress for mcp-box", tui.statusMessage);
    }

    @Test
    void thePickerOpenedFromDetailsClosesBackToThem() {
        var tui = mcpTui("task t1-abcd finished 1700000000\nsid t1-abcd sess-1\ncwd t1-abcd /a\n"
                + "task t2-abcd finished 1700000000\nsid t2-abcd sess-2\ncwd t2-abcd /b\n");
        press(tui, TAB, KeyEvent.ofKey(KeyCode.F3), SHIFT_F2);
        assertEquals(Tui.Mode.TASK_PICKER, tui.mode());
        press(tui, ESC);
        assertEquals(Tui.Mode.INSTANCE_DETAIL, tui.mode());
    }

    @Test
    void shiftF2OnAnInstanceWithoutTasksSaysSo() {
        var tui = mcpTui("");
        press(tui, TAB, SHIFT_F2);
        assertEquals("No delegated task to join in mcp-box.", tui.statusMessage);
        var plain = tui();
        press(plain, TAB, SHIFT_F2);
        assertEquals(Tui.PendingAction.NONE, plain.pendingAction());
        assertEquals("work-1 was not made by isx mcp: it has no delegated task to join", plain.statusMessage);
    }

    @Test
    void aShellOnAStoppedHostFirewallLogsTheAdvisoryIsxShellPrints() {
        stoppedFirewall = "firewalld is not running:";
        var tui = tui();
        press(tui, TAB, KeyEvent.ofKey(KeyCode.F2));
        assertEquals(List.of("firewalld is not running:\nFix:\n  sudo systemctl enable --now firewalld"),
                tui.warningMessages());
        assertEquals(Tui.PendingAction.SHELL, tui.pendingAction(), "an advisory, so the shell still opens");
    }

    @Test
    void aBranchRefusedForMissingCredentialsKeepsTheWholeRefusalInTheWarningLog() {
        // No Claude account is configured, so a branch of a template with claude is refused.
        minimalTools = List.of(new ToolDef.ToolRef("claude"));
        var tui = tui();
        press(tui, ENTER);
        typeText(tui, "-x");
        press(tui, ENTER);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
        assertEquals(Tui.PendingAction.NONE, tui.pendingAction(), "the branch is refused");
        // The status bar holds one line: what is missing, and where the rest is
        assertTrue(tui.statusMessage.startsWith("Missing credentials: "), tui.statusMessage);
        assertTrue(tui.statusMessage.endsWith(". Press w for the rest."), tui.statusMessage);
        assertFalse(tui.statusMessage.contains("isx account add"), tui.statusMessage);
        // The remedy and any tool note no longer fall off the end of it (#1759)
        var refusal = tui.warningMessages();
        assertEquals(1, refusal.size(), refusal.toString());
        assertTrue(refusal.getFirst().startsWith("Missing credentials: "), refusal.getFirst());
        assertTrue(refusal.getFirst().contains("Run 'isx account add claude'"), refusal.getFirst());
    }

    @Test
    @ExtendWith(dev.incusspawn.lifecycle.MacOsBranching.class)
    void aVmBranchRefusedOnMacOsKeepsItsRemedyInTheWarningLog() {
        var tui = tui();
        // The VM is the second instance row.
        press(tui, TAB, DOWN, KeyEvent.ofKey(KeyCode.F4));
        assertEquals(Tui.Mode.BROWSE, tui.mode());
        assertTrue(tui.statusMessage.endsWith("'vm-1' is a VM. Press w for the rest."), tui.statusMessage);
        assertEquals(1, tui.warningMessages().size(), tui.warningMessages().toString());
        assertTrue(tui.warningMessages().getFirst().endsWith("Branch a container template instead."),
                tui.warningMessages().getFirst());
    }

    @Test
    @ExtendWith(dev.incusspawn.lifecycle.MacOsBranching.class)
    void aKvmBranchRefusedOnMacOsKeepsItsRemedyInTheWarningLog() {
        var tui = tui();
        // Name, then GUI, then KVM, which Space ticks.
        press(tui, ENTER);
        typeText(tui, "-x");
        press(tui, TAB, TAB, KeyEvent.ofChar(' '), ENTER);
        assertEquals(Tui.PendingAction.NONE, tui.pendingAction(), "the branch is refused");
        assertTrue(tui.statusMessage.startsWith("KVM passthrough is "), tui.statusMessage);
        assertTrue(tui.statusMessage.endsWith(". Press w for the rest."), tui.statusMessage);
        assertTrue(tui.warningMessages().getFirst().endsWith("untick KVM passthrough in the branch dialog."),
                tui.warningMessages().toString());
    }

    @Test
    void helpChatWithNoAccountKeepsItsRemedyInTheWarningLog() {
        var tui = tui();
        press(tui, KeyEvent.ofChar('?'));
        assertEquals(Tui.Mode.BROWSE, tui.mode(), "nothing can answer, so the chat stays closed");
        // The status bar holds the cause, and where the rest is (#1765)
        assertEquals("No AI credentials configured. Press w for the rest.", tui.statusMessage);
        assertEquals(List.of("No AI credentials configured."
                + " Run 'isx init' to set up Anthropic, Vertex AI, or OpenAI credentials."),
                tui.warningMessages());
    }

    @Test
    void helpChatWithOnlyASubscriptionKeepsItsAdviceInTheWarningLog() throws Exception {
        var config = Environment.configDir().resolve("config.yaml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                claude:
                  oauthToken: "sk-ant-oat01-x"
                """);
        var tui = tui();
        press(tui, KeyEvent.ofChar('?'));
        assertEquals(Tui.Mode.BROWSE, tui.mode());
        // Its cause alone is too long for the status bar, so only the first sentence goes there
        assertEquals("Your Claude Pro/Max subscription cannot answer here. Press w for the rest.",
                tui.statusMessage);
        assertTrue(tui.statusMessage.length() < 80, "fits an 80-column status bar after its margin");
        // Its advice spans lines, the credits URL last; none of it fits the status bar (#1765)
        var refusal = tui.warningMessages();
        assertEquals(1, refusal.size(), refusal.toString());
        assertTrue(refusal.getFirst().contains("\nRun 'isx init' to add an Anthropic API key"), refusal.getFirst());
        assertTrue(refusal.getFirst().endsWith(AiHelpClient.API_CREDITS_URL), refusal.getFirst());
    }

    @Test
    void quitting() {
        var tui = tui();
        press(tui, KeyEvent.ofChar('q'));
        assertEquals(Tui.PendingAction.NONE, tui.pendingAction());
        assertFalse(runner.isRunning());
    }

    @Test
    void creatingABranchQuitsTheRunnerAndLeavesTheBranchPending() {
        var tui = tui();
        press(tui, ENTER);
        typeText(tui, "-x");
        press(tui, ENTER);
        assertEquals(Tui.PendingAction.BRANCH, tui.pendingAction());
        assertFalse(runner.isRunning());
    }

    @Test
    void buildingFromTheMenuQuitsTheRunnerAndLeavesTheBuildPending() {
        var tui = tui();
        press(tui, KeyEvent.ofKey(KeyCode.F5), ENTER);
        assertEquals(Tui.PendingAction.BUILD_TEMPLATE, tui.pendingAction());
        assertFalse(runner.isRunning());
    }
}
