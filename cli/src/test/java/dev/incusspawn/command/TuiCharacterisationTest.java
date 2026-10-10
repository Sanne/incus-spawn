package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tui.BackgroundTaskManager;
import dev.incusspawn.tui.FlockInstanceLockManager;
import dev.incusspawn.tui.TuiSnapshot;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The TUI as it is, pinned before it is taken apart (#959): the main screen and every dialog that
 * still lives in the TUI class, rendered at two sizes, and the mode each key leads to. A move that
 * changes any of these is not a move.
 */
@ExtendWith(IsolatedHome.class)
class TuiCharacterisationTest {

    private static final KeyEvent ESC = KeyEvent.ofKey(KeyCode.ESCAPE);
    private static final KeyEvent TAB = KeyEvent.ofKey(KeyCode.TAB);
    private static final KeyEvent ENTER = KeyEvent.ofKey(KeyCode.ENTER);
    private static final KeyEvent DOWN = KeyEvent.ofKey(KeyCode.DOWN);

    private TuiRunner runner;

    @BeforeEach
    void runner() throws Exception {
        runner = TuiRunner.create(TuiConfig.builder().backend(new HeadlessBackend(80, 24))
                .shutdownHook(false).noTick().build());
    }

    @AfterEach
    void close() {
        runner.close();
    }

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
        config.put(Metadata.BUILD_VERSION, BuildInfo.instance().version());
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

    private static void typeText(Tui tui, TuiRunner runner, String text) {
        for (char c : text.toCharArray()) tui.handleEvent(KeyEvent.ofChar(c), runner);
    }

    private static final java.util.regex.Pattern VM_LIMITS =
            java.util.regex.Pattern.compile("CPU \\d+ +RAM \\S+ +Disk (\\S+) *(?=║)");

    /**
     * What varies between hosts and builds: the version in the header, creation stamps in the
     * details, and the CPU and RAM a VM branch defaults to on this host. The dialog lays those
     * fields out at their values' width, so the whole run up to its border is replaced.
     */
    private static String withoutVersion(String text) {
        var stable = text.replace(BuildInfo.instance().version(), "<version>")
                .replaceAll("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d", "YYYY-MM-DDThh:mm:ss");
        return VM_LIMITS.matcher(stable).replaceAll(m -> "CPU <n>  RAM <mem>  Disk " + m.group(1) + " ");
    }

    private static void snapshot(String name, Tui tui) {
        TuiSnapshot.assertMatches("tui-" + name + "-80x24", TuiSnapshot.render(80, 24, tui::render),
                TuiCharacterisationTest::withoutVersion);
        TuiSnapshot.assertMatches("tui-" + name + "-120x40", TuiSnapshot.render(120, 40, tui::render),
                TuiCharacterisationTest::withoutVersion);
    }

    private void opensAndCloses(String name, Tui tui, Tui.Mode expected, KeyEvent... keys) {
        press(tui, keys);
        assertEquals(expected, tui.mode(), name);
        snapshot(name, tui);
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
    void searching() {
        var tui = tui();
        press(tui, KeyEvent.ofChar('/'));
        typeText(tui, runner, "work");
        snapshot("search", tui);
        press(tui, ESC);
        snapshot("search-closed", tui);
    }

    @Test
    void shortcutsHelp() {
        // Not rendered: it names the commit, the JVM and the Incus daemon this test JVM last saw.
        var tui = tui();
        press(tui, KeyEvent.ofKey(KeyCode.F1));
        assertEquals(Tui.Mode.INFO, tui.mode());
        press(tui, ESC);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
    }

    @Test
    void buildMenu() {
        var tui = tui();
        press(tui, KeyEvent.ofKey(KeyCode.F5));
        snapshot("build-menu", tui);
        press(tui, ESC);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
    }

    @Test
    void branchFromATemplate() {
        opensAndCloses("branch-template", tui(), Tui.Mode.BRANCH, ENTER);
    }

    @Test
    void branchFromAVm() {
        var tui = tui();
        press(tui, TAB);
        // The VM is the second instance row.
        press(tui, DOWN);
        opensAndCloses("branch-vm", tui, Tui.Mode.BRANCH, KeyEvent.ofKey(KeyCode.F4));
    }

    @Test
    void branchFieldsAreWalkedWithTab() {
        var tui = tui();
        press(tui, ENTER, TAB, TAB, KeyEvent.ofChar(' '));
        snapshot("branch-template-fields", tui);
    }

    @Test
    void branchFromAnUnbuiltTemplateOffersABuild() {
        var tui = tui();
        press(tui, DOWN);
        opensAndCloses("confirm-build-for-branch", tui, Tui.Mode.CONFIRM_BUILD_FOR_BRANCH, ENTER);
    }

    @Test
    void newChildTemplate() {
        var tui = tui();
        press(tui, KeyEvent.ofChar('n'));
        assertEquals(Tui.Mode.NEW_TEMPLATE, tui.mode());
        typeText(tui, runner, "tpl-child");
        snapshot("new-template", tui);
        press(tui, ESC);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
    }

    @Test
    void deleteATemplate() {
        opensAndCloses("confirm-delete-template", tui(), Tui.Mode.CONFIRM_DELETE,
                KeyEvent.ofKey(KeyCode.F8));
    }

    @Test
    void deleteEveryTemplate() {
        opensAndCloses("confirm-delete-all-templates", tui(), Tui.Mode.CONFIRM_DELETE,
                KeyEvent.ofKey(KeyCode.F8, KeyModifiers.SHIFT));
    }

    @Test
    void deleteAnInstance() {
        var tui = tui();
        press(tui, TAB);
        opensAndCloses("confirm-delete-instance", tui, Tui.Mode.CONFIRM_DELETE,
                KeyEvent.ofKey(KeyCode.F8));
    }

    @Test
    void deleteEveryInstance() {
        var tui = tui();
        press(tui, TAB);
        opensAndCloses("confirm-delete-all-instances", tui, Tui.Mode.CONFIRM_DELETE,
                KeyEvent.ofKey(KeyCode.F8, KeyModifiers.SHIFT));
    }

    @Test
    void renameARunningInstanceAsksToStopIt() {
        var tui = tui();
        press(tui, TAB);
        opensAndCloses("confirm-stop-for-rename", tui, Tui.Mode.CONFIRM_STOP_FOR_RENAME,
                KeyEvent.ofKey(KeyCode.F6));
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
        var tui = tui();
        press(tui, KeyEvent.ofKey(KeyCode.F3));
        assertEquals(Tui.Mode.TEMPLATE_DETAIL, tui.mode());
        press(tui, ESC);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
    }

    @Test
    void actionsMenu() {
        var tui = tui();
        press(tui, TAB, KeyEvent.ofKey(KeyCode.F9));
        snapshot("actions", tui);
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
        typeText(tui, runner, "-x");
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
