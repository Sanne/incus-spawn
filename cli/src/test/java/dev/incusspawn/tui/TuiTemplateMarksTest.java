package dev.incusspawn.tui;

import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDefLoader;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
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
 * Marking templates with Space for one build from F5 (#1131): the marks, what clears them, and
 * the build the menu leaves pending for them.
 */
@ExtendWith(IsolatedHome.class)
class TuiTemplateMarksTest {

    private static final KeyEvent SPACE = KeyEvent.ofChar(' ');
    private static final KeyEvent DOWN = KeyEvent.ofKey(KeyCode.DOWN);
    private static final KeyEvent UP = KeyEvent.ofKey(KeyCode.UP);
    private static final String VERSION = "1.2.3";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private TuiRunner runner;
    private final FakeIncusDaemon daemon = new FakeIncusDaemon();
    /** Submitted background tasks wait here until the test runs them. */
    private final java.util.Queue<Runnable> queued = new java.util.ArrayDeque<>();

    @BeforeEach
    void runner() throws Exception {
        runner = TuiRunner.create(TuiConfig.builder().backend(new HeadlessBackend(80, 24)).keyboardProtocol(dev.tamboui.terminal.KeyboardProtocol.LEGACY)
                .shutdownHook(false).noTick().build());
    }

    @AfterEach
    void close() {
        runner.close();
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
        config.put(Metadata.CREATED, LocalDateTime.now().minusMinutes(3 * 24 * 60 + 30).format(STAMP));
        config.put(Metadata.BUILD_VERSION, VERSION);
        if (parent != null) config.put(Metadata.PARENT, parent);
        return config;
    }

    /** A built root and two leaves on it, the second not built, in that order on the templates table. */
    private static Map<String, ImageDef> defs() {
        var defs = new LinkedHashMap<String, ImageDef>();
        defs.put("tpl-minimal", def("tpl-minimal", null, "Minimal Fedora"));
        defs.put("tpl-a", def("tpl-a", "tpl-minimal", "Leaf A"));
        defs.put("tpl-b", def("tpl-b", "tpl-minimal", "Leaf B"));
        return defs;
    }

    private Tui tui() {
        daemon.container("tpl-minimal", template("tpl-minimal", null))
                .container("tpl-a", template("tpl-a", "tpl-minimal"));
        var tui = new Tui() {
            @Override
            boolean showProxyError() {
                return false;
            }

            @Override
            String currentVersion() {
                return VERSION;
            }

            @Override
            String displayVersion() {
                return VERSION;
            }
        };
        startSession(tui, defs());
        return tui;
    }

    private void startSession(Tui tui, Map<String, ImageDef> defs) {
        tui.useDefinitions(defs, new ToolDefLoader(List.of()), List.of());
        tui.startSession(daemon.client(), new BackgroundTaskManager(queued::add), new FlockInstanceLockManager(),
                InstanceListing.collectEntries(daemon.client().listJson()));
    }

    private void press(Tui tui, KeyEvent... keys) {
        for (var key : keys) tui.handleEvent(key, runner);
    }

    /** Both leaves marked, the cursor left on the second. */
    private Tui withBothLeavesMarked() {
        var tui = tui();
        press(tui, DOWN, SPACE, DOWN, SPACE);
        assertEquals(List.of("tpl-a", "tpl-b"), List.copyOf(tui.markedTemplates()));
        return tui;
    }

    @Test
    void spaceTogglesTheMarkOnTheSelectedTemplate() {
        var tui = tui();
        press(tui, SPACE);
        assertEquals(List.of("tpl-minimal"), List.copyOf(tui.markedTemplates()));
        press(tui, SPACE);
        assertTrue(tui.markedTemplates().isEmpty());
    }

    @Test
    void rebuildingTheMarkedLeavesWithParentsBuildsBothInOneRun() {
        var tui = withBothLeavesMarked();
        press(tui, KeyEvent.ofKey(KeyCode.F5));
        assertEquals(Tui.Mode.BUILD_MENU, tui.mode());
        press(tui, KeyEvent.ofChar('1'));
        assertEquals(Tui.PendingAction.BUILD_TEMPLATE, tui.pendingAction());
        assertArrayEquals(new String[]{"tpl-a", "tpl-b", "--with-parents"}, tui.pendingBuildArgs());
        assertFalse(runner.isRunning());
        assertTrue(tui.markedTemplates().isEmpty(), "a build launched from the marks clears them");
    }

    @Test
    void buildingTheRowUnderTheCursorKeepsTheMarks() {
        var tui = withBothLeavesMarked();
        // The marks' options come first: with parents, then alone; then the row under the cursor.
        press(tui, KeyEvent.ofKey(KeyCode.F5), KeyEvent.ofChar('3'));
        assertArrayEquals(new String[]{"tpl-b"}, tui.pendingBuildArgs());
        assertEquals(List.of("tpl-a", "tpl-b"), List.copyOf(tui.markedTemplates()));
    }

    @Test
    void aBusySharedParentStopsTheBatchThatRebuildsIt() {
        var tui = withBothLeavesMarked();
        // The cursor is on tpl-b; their parent tpl-minimal, marked by neither, is being deleted.
        daemon.container("tpl-minimal", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-minimal",
                Metadata.PENDING_OP, Metadata.OP_DELETING));
        startSession(tui, defs());
        press(tui, DOWN, DOWN, KeyEvent.ofKey(KeyCode.F5), KeyEvent.ofChar('1'));
        assertEquals("Operation in progress for tpl-minimal", tui.statusMessage, "with parents rebuilds it");
        assertEquals(Tui.PendingAction.NONE, tui.pendingAction());
        // Alone, the marked leaves leave their parent be.
        press(tui, KeyEvent.ofKey(KeyCode.F5), KeyEvent.ofChar('2'));
        assertArrayEquals(new String[]{"tpl-a", "tpl-b"}, tui.pendingBuildArgs());
    }

    @Test
    void aBusyParentStopsOneTemplatesRebuildWithParents() {
        var tui = tui();
        daemon.container("tpl-minimal", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-minimal",
                Metadata.PENDING_OP, Metadata.OP_DELETING));
        startSession(tui, defs());
        // On tpl-a, nothing marked: rebuild it, then with parents.
        press(tui, DOWN, KeyEvent.ofKey(KeyCode.F5), KeyEvent.ofChar('2'));
        assertEquals("Operation in progress for tpl-minimal", tui.statusMessage);
        assertEquals(Tui.PendingAction.NONE, tui.pendingAction());
    }

    @Test
    void aBusyRowUnderTheCursorDoesNotStopTheMarkedBatch() {
        var tui = withBothLeavesMarked();
        daemon.container("tpl-minimal", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-minimal",
                Metadata.PENDING_OP, Metadata.OP_DELETING));
        startSession(tui, defs());
        // The cursor is on the busy tpl-minimal: the menu opens, and the leaves alone can be built.
        press(tui, KeyEvent.ofKey(KeyCode.F5));
        assertEquals(Tui.Mode.BUILD_MENU, tui.mode());
        press(tui, KeyEvent.ofChar('2'));
        assertArrayEquals(new String[]{"tpl-a", "tpl-b"}, tui.pendingBuildArgs());
    }

    @Test
    void aBusyRowUnderTheCursorIsNotBuiltFromTheMenu() {
        var tui = withBothLeavesMarked();
        daemon.container("tpl-minimal", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-minimal",
                Metadata.PENDING_OP, Metadata.OP_DELETING));
        startSession(tui, defs());
        // After the marks' two options: "Rebuild tpl-minimal".
        press(tui, KeyEvent.ofKey(KeyCode.F5), KeyEvent.ofChar('3'));
        assertEquals("Operation in progress for tpl-minimal", tui.statusMessage);
        assertEquals(Tui.PendingAction.NONE, tui.pendingAction());
    }

    @Test
    void aBatchLeavesTheCursorWhereItWas() {
        assertNull(Tui.singleBuildTarget(new String[]{"tpl-a", "tpl-b", "--with-parents"}));
        assertNull(Tui.singleBuildTarget(new String[]{"--out-of-sync"}));
        assertEquals("tpl-a", Tui.singleBuildTarget(new String[]{"tpl-a", "--with-parents"}));
    }

    @Test
    void aBusyMarkedTemplateIsNotBuilt() {
        var tui = withBothLeavesMarked();
        // The cursor is on tpl-b; tpl-a is the one being deleted.
        daemon.container("tpl-a", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-a",
                Metadata.PENDING_OP, Metadata.OP_DELETING));
        startSession(tui, defs());
        press(tui, DOWN, DOWN, KeyEvent.ofKey(KeyCode.F5), KeyEvent.ofChar('1'));
        assertEquals("Operation in progress for tpl-a", tui.statusMessage);
        assertEquals(Tui.PendingAction.NONE, tui.pendingAction());
        assertEquals(List.of("tpl-a", "tpl-b"), List.copyOf(tui.markedTemplates()));
    }

    @Test
    void destroyingWithMarksDestroysTheMarkedBuiltTemplatesDerivedFirst() {
        var tui = tui();
        // All three marked, the cursor left on tpl-b, which is not built.
        press(tui, SPACE, DOWN, SPACE, DOWN, SPACE);
        press(tui, KeyEvent.ofKey(KeyCode.F8));
        assertEquals(Tui.Mode.CONFIRM_DELETE, tui.mode());
        var dialog = TuiSnapshot.toText(TuiSnapshot.render(80, 24, tui::render));
        assertTrue(dialog.contains("Destroy 2 templates"), dialog);
        assertTrue(dialog.contains("This will destroy:"), dialog);
        assertTrue(dialog.indexOf("  tpl-a ") > 0 && dialog.indexOf("  tpl-a ") < dialog.indexOf("  tpl-minimal "), dialog);
        press(tui, KeyEvent.ofChar('y'));
        assertTrue(tui.markedTemplates().isEmpty(), "a destroy launched from the marks clears them");
        daemon.clearRequests();
        while (!queued.isEmpty()) queued.remove().run();

        var deletes = daemon.requests().stream().filter(r -> r.startsWith("DELETE /1.0/instances/")).toList();
        assertEquals(List.of("DELETE /1.0/instances/tpl-a", "DELETE /1.0/instances/tpl-minimal"), deletes);
    }

    @Test
    void destroyingMarksNoneOfWhichIsBuiltSaysSo() {
        var tui = tui();
        press(tui, DOWN, DOWN, SPACE, UP);
        press(tui, KeyEvent.ofKey(KeyCode.F8));
        assertEquals("No marked template is built", tui.statusMessage);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
    }

    @Test
    void escapeClearsTheMarks() {
        var tui = withBothLeavesMarked();
        press(tui, KeyEvent.ofKey(KeyCode.ESCAPE));
        assertTrue(tui.markedTemplates().isEmpty());
        assertEquals(Tui.Mode.BROWSE, tui.mode());
        assertEquals("Cleared 2 marks", tui.statusMessage);
    }

    @Test
    void aRefreshDropsTheMarkOfATemplateThatIsGone() {
        var tui = withBothLeavesMarked();
        var defs = defs();
        defs.remove("tpl-b");
        startSession(tui, defs);
        assertEquals(List.of("tpl-a"), List.copyOf(tui.markedTemplates()));
    }

    @Test
    void marksShowOnTheirRowsAndTheContextLine() {
        var tui = withBothLeavesMarked();
        TuiSnapshot.assertMatches("tui-templates-marked-80x24", TuiSnapshot.render(80, 24, tui::render));
    }
}
