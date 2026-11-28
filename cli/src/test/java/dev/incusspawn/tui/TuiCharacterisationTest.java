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
    private boolean subnetConflict;

    /** The tools tpl-minimal declares; none unless a test says. */
    private List<ToolDef.ToolRef> minimalTools = List.of();

    @BeforeEach
    void runner() throws Exception {
        runner = TuiRunner.create(Tui.runnerConfig().backend(new HeadlessBackend(80, 24))
                .shutdownHook(false).noTick().build());
    }

    /**
     * The threads the TUI started through the executors {@link #tui} gives it: the Work tab's reads
     * and merges and the background tasks. They write under the home IsolatedHome deletes after
     * this class's own teardown, so {@link #close()} waits for them first (#2677).
     */
    private final List<Thread> started = new java.util.concurrent.CopyOnWriteArrayList<>();
    private java.util.concurrent.Executor threads(String name) {
        return task -> started.add(Thread.ofVirtual().name(name).start(task));
    }

    @AfterEach
    void close() throws InterruptedException {
        try {
            runner.close();
        } finally {
            // By index: a thread that ends may start another, as a merge that ends asks for the next
            for (int i = 0; i < started.size(); i++) {
                var thread = started.get(i);
                assertTrue(thread.join(java.time.Duration.ofSeconds(30)), "a thread the TUI started is still running: " + thread);
            }
        }
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

            // Nor the host's networks: the test says whether the bridge's subnet overlaps one.
            @Override
            boolean bridgeSubnetConflicts() {
                return subnetConflict;
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
        tui.overviewExecutor = threads("isx-overview");
        tui.overviewMergeExecutor = threads("isx-merge");
        tui.useDefinitions(defs, new ToolDefLoader(List.of()), List.of());
        tui.startSession(daemon.client(), new BackgroundTaskManager(threads("isx-task")), new FlockInstanceLockManager(),
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

    private static final KeyEvent LEFT = KeyEvent.ofKey(KeyCode.LEFT);
    private static final KeyEvent RIGHT = KeyEvent.ofKey(KeyCode.RIGHT);

    @Test
    void templatesTab() {
        var tui = tui();
        press(tui, RIGHT);
        assertEquals(Tui.View.TEMPLATES, tui.view);
        snapshot("tab-templates", tui);
    }

    @Test
    void instancesTab() {
        var tui = tui();
        press(tui, RIGHT, RIGHT);
        assertEquals(Tui.View.INSTANCES, tui.view);
        assertEquals(Tui.Panel.INSTANCES, tui.focusedPanel, "the focus follows to the only panel drawn");
        snapshot("tab-instances", tui);
    }

    @Test
    void leftAndRightStopAtTheEnds() {
        var tui = tui();
        press(tui, LEFT);
        assertEquals(Tui.View.OVERVIEW, tui.view);
        press(tui, RIGHT, RIGHT, RIGHT, RIGHT);
        assertEquals(Tui.View.WORK, tui.view);
        press(tui, KeyEvent.ofChar('h'), KeyEvent.ofChar('h'), KeyEvent.ofChar('h'));
        assertEquals(Tui.View.OVERVIEW, tui.view, "h and l are Left and Right");
        press(tui, KeyEvent.ofChar('l'));
        assertEquals(Tui.View.TEMPLATES, tui.view);
    }

    @Test
    void theOverviewKeepsTheFocusItIsGiven() {
        var tui = tui();
        press(tui, RIGHT, RIGHT, LEFT, LEFT);
        assertEquals(Tui.View.OVERVIEW, tui.view);
        assertEquals(Tui.Panel.TEMPLATES, tui.focusedPanel, "the Templates tab was the last to set it");
        press(tui, TAB);
        assertEquals(Tui.View.OVERVIEW, tui.view, "Tab moves between the overview's panels as before");
        assertEquals(Tui.Panel.INSTANCES, tui.focusedPanel);
    }

    @Test
    void tabInAOnePanelTabShowsTheOtherPanelsTab() {
        var tui = tui();
        press(tui, RIGHT, TAB);
        assertEquals(Tui.View.INSTANCES, tui.view);
        assertEquals(Tui.Panel.INSTANCES, tui.focusedPanel);
        press(tui, TAB);
        assertEquals(Tui.View.TEMPLATES, tui.view);
        assertEquals(Tui.Panel.TEMPLATES, tui.focusedPanel);
    }

    @Test
    void theSelectionIsTheSameRowInEveryTab() {
        var tui = tui();
        press(tui, DOWN, TAB, DOWN, RIGHT);
        assertEquals("tpl-dev", tui.selectedTemplate().name());
        press(tui, RIGHT);
        assertEquals("vm-1", tui.selectedInstance().name());
    }

    @Test
    void leftAndRightMoveTheSearchCursorNotTheTab() {
        var tui = tui();
        press(tui, KeyEvent.ofChar('/'), RIGHT, LEFT);
        assertEquals(Tui.View.OVERVIEW, tui.view);
        typeText(tui, "hl");
        assertEquals(Tui.View.OVERVIEW, tui.view, "h and l are text in the search bar");
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

    /** At the hint level the gauge names the Work tab beside C:clean, where the header has room (#2563). */
    @Test
    void mainScreenWithGaugeAtTheHintLevel() {
        var tui = tui();
        // 87% of 60 GiB, 7.5 GiB free
        tui.poolUsage = new IncusClient.PoolUsage((105L << 30) / 2, 60L << 30);
        snapshot("main-gauge-hint", tui);
    }

    /** A deleted instance's row leaves the cursor on its neighbour, not on the first row (#2622). */
    @Test
    void aDeletedInstanceLeavesTheCursorOnItsNeighbour() {
        var daemon = new FakeIncusDaemon();
        for (var name : List.of("a-1", "b-1", "c-1", "d-1")) {
            daemon.instance(name, "container", "Stopped", branch("tpl-minimal", 3 * 60 + 30));
        }
        var tui = tui(false, daemon);
        press(tui, TAB, DOWN, DOWN);
        assertEquals("c-1", tui.selectedInstance().name());

        daemon.client().delete("c-1", true);
        press(tui, KeyEvent.ofChar('r'));
        assertEquals("d-1", tui.selectedInstance().name(), "the next row moved up under the cursor");

        daemon.client().delete("d-1", true);
        press(tui, KeyEvent.ofChar('r'));
        assertEquals("b-1", tui.selectedInstance().name(), "the last row gone, the cursor goes to the one above");
    }

    /** A tab's count changing is something isx saw change, so its tab is tinted; a search narrowing it is not. */
    @Test
    void aTabWhoseCountChangedIsTintedAndASearchTintsNone() {
        var daemon = new FakeIncusDaemon();
        for (var name : List.of("a-1", "b-1", "c-1")) {
            daemon.instance(name, "container", "Stopped", branch("tpl-minimal", 3 * 60 + 30));
        }
        var tui = tui(false, daemon);
        java.util.function.Supplier<dev.tamboui.style.Style> tab = () -> {
            var drawn = TuiSnapshot.render(120, 40, tui::render);
            return drawn.get(TuiSnapshot.toText(drawn).lines().toList().get(1).indexOf("Instances"), 1).style();
        };
        tui.motion.start();
        try {
            var still = tab.get();
            assertEquals(still, tab.get(), "nothing changed, nothing is tinted");

            typeText(tui, "/a-");
            assertEquals(still.bg(), tab.get().bg(), "a search narrows the count, which is not news");
            press(tui, ESC);
            assertEquals(still.bg(), tab.get().bg(), "nor is the search ending");

            daemon.client().delete("c-1", true);
            press(tui, KeyEvent.ofChar('r'));
            assertNotEquals(still.bg(), tab.get().bg(), "the count went from 3 to 2: the tab is tinted");
            assertTrue(tui.motion.moving());
        } finally {
            tui.motion.stop();
        }
    }

    /** A lasting state's word after the widest name is whole, and so is an operation's, which moves no column. */
    @Test
    void theWordAfterTheWidestNameIsNeverCutToAStub() {
        var daemon = new FakeIncusDaemon()
                .instance("broken-with-the-longest-name", "container", "Error", branch("tpl-minimal", 60));
        var tui = tui(true, daemon);
        press(tui, TAB);
        var shown = TuiSnapshot.toText(TuiSnapshot.render(120, 24, tui::render));
        assertTrue(shown.contains("◐ broken-with-the-longest-name error "), shown);

        var stopping = new FakeIncusDaemon()
                .instance("stopping-with-the-longest-name", "container", "Running",
                        withOp(branch("tpl-minimal", 60), dev.incusspawn.incus.Metadata.OP_RESTARTING));
        var busy = tui(true, stopping);
        press(busy, TAB);
        var rows = TuiSnapshot.toText(TuiSnapshot.render(120, 24, busy::render));
        assertTrue(rows.contains("stopping-with-the-longest-name restarting "), rows);
    }

    private static Map<String, String> withOp(Map<String, String> config, String op) {
        var with = new java.util.HashMap<>(config);
        with.put(dev.incusspawn.incus.Metadata.PENDING_OP, op);
        return with;
    }

    /** The widths come from every row, not the search's: a narrower listing moved the columns on a keystroke. */
    @Test
    void theColumnsHoldStillWhileASearchNarrowsTheLists() {
        var tui = tui();
        var templates = header(tui, "DESCRIPTION");
        var instances = header(tui, "PARENT");
        press(tui, KeyEvent.ofChar('/'));
        // Leaves tpl-dev, "not built", and no instance of its own
        typeText(tui, "dev");
        assertEquals(templates, header(tui, "DESCRIPTION"), "the Templates table");
        press(tui, ESC);
        press(tui, KeyEvent.ofChar('/'));
        // Leaves the one instance with no address and the shorter age
        typeText(tui, "vm-1");
        assertEquals(instances, header(tui, "PARENT"), "the Instances table");
    }

    private static String header(Tui tui, String column) {
        return TuiSnapshot.toText(TuiSnapshot.render(80, 24, tui::render)).lines()
                .filter(line -> line.strip().startsWith("NAME ") && line.contains(column)).findFirst().orElseThrow();
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
        // Name, purpose, GUI, then KVM, which Space toggles.
        press(tui, ENTER, TAB, TAB, TAB, KeyEvent.ofChar(' '));
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
    void renamingARunningInstanceAsksToStopItAndEscChangesNothing() {
        var daemon = new FakeIncusDaemon();
        var tui = tui(true, daemon);
        press(tui, TAB, KeyEvent.ofKey(KeyCode.F6));
        assertEquals(Tui.Mode.EDIT, tui.mode(), "F6 opens the edit dialog, running or not (#2621)");
        typeText(tui, "x");
        press(tui, KeyEvent.ofKey(KeyCode.TAB));
        typeText(tui, "review");
        press(tui, ENTER);
        assertEquals(Tui.Mode.CONFIRM_STOP_FOR_RENAME, tui.mode());
        snapshotInContext("confirm-stop-for-rename", tui);
        press(tui, ESC);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
        assertEquals("", daemon.instance("work-1").path("config").path(Metadata.PURPOSE).asText(""),
                "declining the stop writes nothing, the purpose included");
        assertEquals(List.of(), daemon.stateActions(), "and stops nothing");
    }

    /** #2621: y stops the running instance, then renames it and writes the purpose typed beside the name. */
    @Test
    void stoppingARunningInstanceForItsNewNameAppliesTheWholeEdit() {
        var daemon = new FakeIncusDaemon();
        var tui = tui(true, daemon);
        press(tui, TAB, KeyEvent.ofKey(KeyCode.F6));
        typeText(tui, "x");
        press(tui, KeyEvent.ofKey(KeyCode.TAB));
        typeText(tui, "review");
        press(tui, ENTER, KeyEvent.ofChar('y'));
        assertEquals(Tui.Mode.BROWSE, tui.mode());
        assertEquals("review", daemon.instance("work-1x").path("config").path(Metadata.PURPOSE).asText(""));
        assertEquals("Renamed work-1 to work-1x, and set its purpose", tui.statusMessage);
    }

    @Test
    void editAStoppedInstance() {
        var tui = tui();
        press(tui, TAB, DOWN);
        opensAndCloses("edit", tui, Tui.Mode.EDIT, KeyEvent.ofKey(KeyCode.F6));
    }

    /** #2621: a purpose is set from F6 on a running instance without stopping it. */
    @Test
    void thePurposeOfARunningInstanceIsSetWithoutStoppingIt() {
        var daemon = new FakeIncusDaemon();
        var tui = tui(true, daemon);
        press(tui, TAB, KeyEvent.ofKey(KeyCode.F6), KeyEvent.ofKey(KeyCode.TAB));
        typeText(tui, "review #12");
        press(tui, ENTER);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
        assertEquals("review #12", daemon.instance("work-1").path("config").path(Metadata.PURPOSE).asText());
        assertEquals(List.of(), daemon.stateActions(), "a purpose alone stops nothing");
    }

    /** #2621: one Enter renames a stopped instance and sets its purpose. */
    @Test
    void oneEditRenamesAStoppedInstanceAndSetsItsPurpose() {
        var daemon = new FakeIncusDaemon();
        var tui = tui(true, daemon);
        press(tui, TAB, DOWN, KeyEvent.ofKey(KeyCode.F6));
        typeText(tui, "-b");
        press(tui, KeyEvent.ofKey(KeyCode.TAB));
        typeText(tui, "bisect");
        press(tui, ENTER);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
        assertEquals("bisect", daemon.instance("vm-1-b").path("config").path(Metadata.PURPOSE).asText());
        assertEquals("Renamed vm-1 to vm-1-b, and set its purpose", tui.statusMessage);
    }

    /** A failed purpose write still lets the rename go ahead, so a stop the person agreed to is not wasted. */
    @Test
    void aFailedPurposeWriteStillRenames() {
        var daemon = new FakeIncusDaemon();
        var tui = tui(true, daemon);
        daemon.refuseWritesContaining(Metadata.PURPOSE);
        press(tui, TAB, DOWN, KeyEvent.ofKey(KeyCode.F6));
        typeText(tui, "-b");
        press(tui, KeyEvent.ofKey(KeyCode.TAB));
        typeText(tui, "bisect");
        press(tui, ENTER);
        assertEquals("", daemon.instance("vm-1-b").path("config").path(Metadata.PURPOSE).asText(""));
        assertTrue(tui.statusMessage.startsWith("Failed to set the purpose: ")
                && tui.statusMessage.endsWith("Renamed vm-1 to vm-1-b"), tui.statusMessage);
    }

    /** #2621: p in the details edits the purpose and comes back to the details. */
    @Test
    void pInTheDetailsEditsThePurposeAndReturnsToThem() {
        var daemon = new FakeIncusDaemon();
        var tui = tui(true, daemon);
        press(tui, TAB, KeyEvent.ofKey(KeyCode.F3), KeyEvent.ofChar('p'));
        assertEquals(Tui.Mode.EDIT, tui.mode());
        press(tui, ESC);
        assertEquals(Tui.Mode.INSTANCE_DETAIL, tui.mode(), "Esc goes back to the details");
        press(tui, KeyEvent.ofChar('p'));
        typeText(tui, "docs");
        press(tui, ENTER);
        assertEquals(Tui.Mode.INSTANCE_DETAIL, tui.mode(), "so does Enter");
        assertEquals("docs", daemon.instance("work-1").path("config").path(Metadata.PURPOSE).asText());
    }

    /** #2621: declining the stop for a new name typed from the details goes back to the details. */
    @Test
    void decliningTheStopFromTheDetailsReturnsToThem() {
        var tui = tui();
        press(tui, TAB, KeyEvent.ofKey(KeyCode.F3), KeyEvent.ofChar('p'), KeyEvent.ofKey(KeyCode.TAB));
        typeText(tui, "x");
        press(tui, ENTER);
        assertEquals(Tui.Mode.CONFIRM_STOP_FOR_RENAME, tui.mode());
        press(tui, ESC);
        assertEquals(Tui.Mode.INSTANCE_DETAIL, tui.mode());
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
        assertEquals("work-1 has no tool actions", tui.statusMessage);
    }

    /** A stopped instance whose actions all need it running has actions: F9 says what they need. */
    @Test
    void f9OnAStoppedInstanceSaysItsActionsNeedItRunning() {
        var tui = tui();
        tui.actionsCache.put("vm-1", List.of(new dev.incusspawn.tool.ToolAction() {
            @Override public String toolName() { return "vscode-remote"; }
            @Override public String label() { return "Open in VS Code"; }
            @Override public dev.incusspawn.tool.ActionResult execute(dev.incusspawn.tool.ActionContext context) {
                throw new AssertionError("a stopped instance's action must not run");
            }
        }));
        press(tui, TAB, DOWN, KeyEvent.ofKey(KeyCode.F9));
        assertEquals("vm-1", tui.selectedInstance().name());
        assertEquals(Tui.Mode.BROWSE, tui.mode(), "nothing it can run opens no dialog");
        assertEquals("vm-1's tool actions need it running", tui.statusMessage);
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
    void shiftF2OrAlt2JoinsTheTaskOfTheRowTheSearchFoundAndThePickerKeepsTheFilter() {
        var tui = searchedMcpTui("task t1-abcd finished 1700000000\nsid t1-abcd sess-1\ncwd t1-abcd /a\n");
        press(tui, KeyEvent.ofChar('2', KeyModifiers.ALT));
        assertEquals(Tui.PendingAction.SHELL_WITH_COMMAND, tui.pendingAction(), "Alt+2 is Shift+F2, not typed");
        assertEquals("mcp", tui.searchInput.text());
        assertFalse(runner.isRunning());

        tui = searchedMcpTui("task t1-abcd finished 1700000000\nsid t1-abcd sess-1\ncwd t1-abcd /a\n"
                + "task t2-abcd finished 1700000000\nsid t2-abcd sess-2\ncwd t2-abcd /b\n");
        press(tui, SHIFT_F2);
        assertEquals(Tui.Mode.TASK_PICKER, tui.mode());
        press(tui, DOWN);
        assertEquals("mcp", tui.searchInput.text(), "the picker takes its own keys");
        press(tui, ESC);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
        assertTrue(tui.searchActive, "closing the picker returns to the search");
        assertEquals(List.of("mcp-box"), tui.entries.stream().map(InstanceListing.InstanceInfo::name).toList());
    }

    /** {@link #mcpTui} beside two other instances, the search narrowed to it. */
    private Tui searchedMcpTui(String tasks) {
        var config = branch("tpl-minimal", 30);
        config.put(Metadata.MCP_OWNER, "alice");
        config.put(Metadata.MCP_SESSION, "1-1");
        var tui = tui(true, new FakeIncusDaemon().instance("mcp-box", "container", "Running", config)
                .execOutput("prompt-1.md", tasks));
        press(tui, TAB, KeyEvent.ofChar('/'));
        typeText(tui, "mcp");
        assertEquals(List.of("mcp-box"), tui.entries.stream().map(InstanceListing.InstanceInfo::name).toList());
        var screen = TuiSnapshot.toText(TuiSnapshot.render(120, 40, tui::render));
        assertTrue(screen.lines().anyMatch(l -> l.contains(" / mcp") && l.contains("⇧F2 Join task")),
                "the search bar keeps the hint: " + screen);
        return tui;
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
        assertEquals(Tui.busy("mcp-box"), tui.statusMessage);
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
    void aShellWhoseBridgeSubnetOverlapsTheHostsLogsTheConflict() {
        subnetConflict = true;
        var tui = tui();
        press(tui, TAB, KeyEvent.ofKey(KeyCode.F2));
        assertEquals(1, tui.warningMessages().size());
        assertTrue(tui.warningMessages().getFirst().startsWith("The Incus bridge's subnet overlaps a network of this host"),
                tui.warningMessages().getFirst());
        assertEquals(Tui.PendingAction.SHELL, tui.pendingAction(), "a warning, so the shell still opens");
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
        // Name, purpose, GUI, then KVM, which Space ticks.
        press(tui, ENTER);
        typeText(tui, "-x");
        press(tui, TAB, TAB, TAB, KeyEvent.ofChar(' '), ENTER);
        assertEquals(Tui.PendingAction.NONE, tui.pendingAction(), "the branch is refused");
        assertTrue(tui.statusMessage.startsWith("KVM passthrough is "), tui.statusMessage);
        assertTrue(tui.statusMessage.endsWith(". Press w for the rest."), tui.statusMessage);
        assertTrue(tui.warningMessages().getFirst().endsWith("untick KVM passthrough in the branch dialog."),
                tui.warningMessages().toString());
    }

    /**
     * With no account that can answer, AI Help leaves the TUI to ask for one there, where the
     * person needs it, rather than naming a command to run later (#2541).
     */
    @Test
    void helpChatWithNoAccountLeavesToAskForOne() {
        var tui = tui();
        press(tui, KeyEvent.ofChar('?'));
        assertEquals(Tui.PendingAction.ADD_AI_HELP_ACCOUNT, tui.pendingAction());
        assertFalse(runner.isRunning(), "the terminal is lent to the question");
        assertEquals(Tui.Mode.BROWSE, tui.mode());
    }

    @Test
    void helpChatWithOnlyASubscriptionLeavesToAskForAnotherAccount() throws Exception {
        var config = Environment.configDir().resolve("config.yaml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                claude:
                  oauthToken: "sk-ant-oat01-x"
                """);
        var tui = tui();
        press(tui, KeyEvent.ofChar('?'));
        assertEquals(Tui.PendingAction.ADD_AI_HELP_ACCOUNT, tui.pendingAction());
        // What the status bar says if the person adds nothing: its first sentence fits (#1765)
        var cause = AiHelpClient.noProvider(dev.incusspawn.config.SpawnConfig.load()).cause();
        assertTrue(cause.indexOf(". ") + 1 + " Press w for the rest.".length() < 80, cause);
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
