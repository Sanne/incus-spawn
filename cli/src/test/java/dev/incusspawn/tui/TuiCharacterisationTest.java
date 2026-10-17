package dev.incusspawn.tui;

import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
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
        defs.put("tpl-minimal", def("tpl-minimal", null, "Minimal Fedora"));
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
