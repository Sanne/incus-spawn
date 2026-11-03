package dev.incusspawn.tui;

import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
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

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Marking instances with Space (#1482): F8 destroys the marked ones, F7 stops and Shift+F7 restarts
 * the marked running ones, and the cursor row is left alone while anything is marked.
 */
@ExtendWith(IsolatedHome.class)
class TuiInstanceMarksTest {

    private static final KeyEvent SPACE = KeyEvent.ofChar(' ');
    private static final KeyEvent DOWN = KeyEvent.ofKey(KeyCode.DOWN);
    private static final KeyEvent UP = KeyEvent.ofKey(KeyCode.UP);
    private static final KeyEvent TAB = KeyEvent.ofKey(KeyCode.TAB);

    private TuiRunner runner;
    private final FakeIncusDaemon daemon = new FakeIncusDaemon();
    /** Submitted background tasks wait here until the test runs them. */
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final BackgroundTaskManager tasks = new BackgroundTaskManager(queued::add);

    @BeforeEach
    void runner() throws Exception {
        runner = TuiRunner.create(TuiConfig.builder().backend(new HeadlessBackend(80, 24))
                .shutdownHook(false).noTick().build());
    }

    @AfterEach
    void close() {
        runner.close();
    }

    private static Map<String, String> branch() {
        return branch(null);
    }

    private static Map<String, String> branch(String pendingOp) {
        var config = new HashMap<String, String>();
        config.put(Metadata.TYPE, "branch");
        config.put(Metadata.PROFILE, "tpl-minimal");
        config.put(Metadata.PARENT, "tpl-minimal");
        config.put(Metadata.NETWORK_MODE, "proxy");
        if (pendingOp != null) config.put(Metadata.PENDING_OP, pendingOp);
        return config;
    }

    private static Map<String, ImageDef> defs() {
        var def = new ImageDef();
        def.setName("tpl-minimal");
        def.setImage("fedora-44");
        def.setDescription("Minimal Fedora");
        var defs = new LinkedHashMap<String, ImageDef>();
        defs.put("tpl-minimal", def);
        return defs;
    }

    /** alpha and beta running, gamma stopped: the instance rows in that order. */
    private Tui tui() {
        daemon.container("tpl-minimal", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-minimal"))
                .instance("alpha", "container", "Running", branch())
                .instance("beta", "container", "Running", branch())
                .instance("gamma", "container", "Stopped", branch());
        var tui = new Tui() {
            @Override
            boolean showProxyError() {
                return false;
            }

            @Override
            String currentVersion() {
                return "1.2.3";
            }

            @Override
            String displayVersion() {
                return "1.2.3";
            }
        };
        startSession(tui);
        return tui;
    }

    private void startSession(Tui tui) {
        startSession(tui, daemon);
    }

    private void startSession(Tui tui, FakeIncusDaemon daemon) {
        tui.useDefinitions(defs(), new ToolDefLoader(List.of()), List.of());
        tui.startSession(daemon.client(), tasks, new FlockInstanceLockManager(),
                InstanceListing.collectEntries(daemon.client().listJson()));
    }

    private void press(Tui tui, KeyEvent... keys) {
        for (var key : keys) tui.handleEvent(key, runner);
    }

    /** Reloads the listing as a live refresh does, leaving the open dialog as it is. */
    private void reload(Tui tui) {
        tui.loader.mergeInstances(InstanceListing.collectEntries(daemon.client().listJson()));
        tui.publishRows();
    }

    private void runQueued() {
        while (!queued.isEmpty()) queued.remove().run();
    }

    private static String screen(Tui tui) {
        return TuiSnapshot.toText(TuiSnapshot.render(80, 24, tui::render));
    }

    /** alpha and gamma marked, the cursor left on beta between them. */
    private Tui withAlphaAndGammaMarked() {
        var tui = tui();
        press(tui, TAB, SPACE, DOWN, DOWN, SPACE, UP);
        assertEquals(List.of("alpha", "gamma"), List.copyOf(tui.markedInstances()));
        return tui;
    }

    @Test
    void spaceTogglesTheMarkOnTheSelectedInstance() {
        var tui = tui();
        press(tui, TAB, SPACE);
        assertEquals(List.of("alpha"), List.copyOf(tui.markedInstances()));
        press(tui, SPACE);
        assertTrue(tui.markedInstances().isEmpty());
    }

    @Test
    void destroyingWithMarksDestroysTheMarkedOnesNotTheCursorRow() {
        var tui = withAlphaAndGammaMarked();
        press(tui, KeyEvent.ofKey(KeyCode.F8));
        assertEquals(Tui.Mode.CONFIRM_DELETE, tui.mode());
        var dialog = screen(tui);
        assertTrue(dialog.contains("Destroy 2 instances"), dialog);
        assertTrue(dialog.contains("This will destroy:"), dialog);
        assertTrue(dialog.indexOf("  alpha ") > 0 && dialog.indexOf("  alpha ") < dialog.indexOf("  gamma "), dialog);
        press(tui, KeyEvent.ofChar('y'));
        daemon.clearRequests();
        runQueued();

        var requests = daemon.requests();
        assertTrue(requests.contains("DELETE /1.0/instances/alpha"), requests.toString());
        assertTrue(requests.contains("DELETE /1.0/instances/gamma"), requests.toString());
        assertFalse(requests.contains("DELETE /1.0/instances/beta"), requests.toString());
        assertTrue(tui.markedInstances().isEmpty(), "a destroy launched from the marks clears them");
    }

    @Test
    void anInstanceIsDestroyedAfterTheMarkedBranchesOfIt() {
        var tui = tui();
        // gamma was branched from alpha, which the table lists first
        var fromAlpha = branch();
        fromAlpha.put(Metadata.PARENT, "alpha");
        daemon.instance("gamma", "container", "Stopped", fromAlpha);
        startSession(tui);
        press(tui, TAB, SPACE, DOWN, DOWN, SPACE, KeyEvent.ofKey(KeyCode.F8), KeyEvent.ofChar('y'));
        daemon.clearRequests();
        runQueued();

        var deletes = daemon.requests().stream().filter(r -> r.startsWith("DELETE /1.0/instances/")).toList();
        assertEquals(List.of("DELETE /1.0/instances/gamma", "DELETE /1.0/instances/alpha"), deletes);
    }

    @Test
    void stoppingWithMarksAsksFirstNamingTheOnesItStops() {
        var tui = withAlphaAndGammaMarked();
        press(tui, KeyEvent.ofKey(KeyCode.F7));
        assertEquals(Tui.Mode.CONFIRM_MARKED_STOP, tui.mode());
        assertTrue(queued.isEmpty(), "nothing stops before the confirmation");
        var dialog = screen(tui);
        assertTrue(dialog.contains("Stop 1 instance"), dialog);
        assertTrue(dialog.contains("This will stop:") && dialog.contains("  alpha "), dialog);
        assertTrue(dialog.contains("Not running, left as is: gamma."), dialog);
        press(tui, KeyEvent.ofChar('y'));
        runQueued();
        assertEquals(List.of("alpha stop"), daemon.stateActions(), "gamma is already stopped, beta not marked");
        assertTrue(tui.markedInstances().isEmpty());
    }

    @Test
    void restartingWithMarksRestartsOnlyTheMarkedRunningOnes() {
        var tui = withAlphaAndGammaMarked();
        press(tui, KeyEvent.ofKey(KeyCode.F7, KeyModifiers.SHIFT));
        assertTrue(tasks.getActiveTasks().isEmpty(), "nothing restarts before the confirmation");
        var dialog = screen(tui);
        assertTrue(dialog.contains("Restart 1 instance"), dialog);
        assertTrue(dialog.contains("This will restart:") && dialog.contains("  alpha "), dialog);
        press(tui, KeyEvent.ofChar('y'));
        // Not run: a restart waits for the guest, which this daemon does not bring up
        assertEquals(List.of("Restarting alpha"),
                tasks.getActiveTasks().stream().map(BackgroundTask::displayName).toList());
        assertTrue(tui.markedInstances().isEmpty());
    }

    /** Whether the key bar shows F7 enabled: its key is drawn in the disabled colour when it is not. */
    private static boolean f7Enabled(Tui tui) {
        var buffer = TuiSnapshot.render(80, 24, tui::render);
        var bar = TuiSnapshot.toText(buffer).split("\n")[23];
        // The labels before F7 are one cell per character
        assertTrue(bar.contains("F7Stop"), bar);
        var cell = buffer.get(bar.indexOf("F7"), 23);
        return !cell.style().fg().equals(java.util.Optional.of(tui.theme.barDisabledFg()));
    }

    @Test
    void withMarksF7IsEnabledOnlyWhenAMarkedInstanceIsRunning() {
        var tui = tui();
        press(tui, TAB, DOWN, DOWN, SPACE);
        // Only the stopped gamma is marked: F7 would stop nothing, even on a running cursor row
        press(tui, UP);
        assertFalse(f7Enabled(tui));
        press(tui, SPACE);
        assertTrue(f7Enabled(tui), "beta, marked now, is running");
    }

    @Test
    void withMarksF7IsDisabledWhenTheSearchHidesEveryInstance() {
        var tui = tui();
        press(tui, TAB, SPACE);
        assertTrue(f7Enabled(tui));
        press(tui, KeyEvent.ofChar('/'));
        for (var c : "zzz".toCharArray()) press(tui, KeyEvent.ofChar(c));
        // While the search is open it can hide every row, the marked one too
        assertFalse(f7Enabled(tui), "with no row shown, F7 is shown disabled, as F8 is");
    }

    @Test
    void confirmingStopsNothingThatStoppedWhileTheDialogWasOpen() {
        var tui = withAlphaAndGammaMarked();
        press(tui, KeyEvent.ofKey(KeyCode.F7));
        assertEquals(Tui.Mode.CONFIRM_MARKED_STOP, tui.mode());
        // alpha stops elsewhere; the listing the dialog acts on is reloaded
        daemon.instance("alpha", "container", "Stopped", branch());
        reload(tui);
        press(tui, KeyEvent.ofChar('y'));
        runQueued();
        assertTrue(daemon.stateActions().isEmpty(), daemon.stateActions().toString());
        assertTrue(screen(tui).contains("Not stopped, 1 no longer running or gone: alpha"), screen(tui));
        assertEquals(List.of("alpha", "gamma"), List.copyOf(tui.markedInstances()), "the marks are kept");
    }

    @Test
    void confirmingNamesTheListedInstancesItNoLongerStops() {
        var tui = tui();
        press(tui, TAB, SPACE, DOWN, SPACE, KeyEvent.ofKey(KeyCode.F7));
        assertTrue(screen(tui).contains("  beta "), screen(tui));
        // beta stops elsewhere while the dialog lists it
        daemon.instance("beta", "container", "Stopped", branch());
        reload(tui);
        press(tui, KeyEvent.ofChar('y'));
        // The warning log announces it on the status line, and keeps it after the stops' messages
        assertTrue(screen(tui).contains("Not stopped, 1 no longer running or gone: beta"), screen(tui));
        assertTrue(tui.warningLog.entries().stream()
                .anyMatch(e -> e.message().equals("Not stopped, 1 no longer running or gone: beta")));
        runQueued();
        assertEquals(List.of("alpha stop"), daemon.stateActions());
    }

    /**
     * A stop that fails before the next frame does not take the line from the "Not stopped …"
     * notice (#1497): the notice holds it until the next key, and the failure shows after that.
     */
    @Test
    void aStopThatFailsAtOnceWaitsBehindTheNotStoppedNotice() {
        var tui = tui();
        press(tui, TAB, SPACE, DOWN, SPACE, KeyEvent.ofKey(KeyCode.F7));
        daemon.instance("beta", "container", "Stopped", branch());
        daemon.ignoreShutdown("alpha");
        reload(tui);
        press(tui, KeyEvent.ofChar('y'));
        // alpha's stop fails and posts its message before any frame is drawn
        runQueued();
        assertTrue(screen(tui).contains("Not stopped, 1 no longer running or gone: beta"), screen(tui));
        assertTrue(screen(tui).contains("Not stopped, 1 no longer running or gone: beta"), "still, a frame later");
        press(tui, DOWN);
        var after = screen(tui);
        assertFalse(after.contains("no longer running or gone"), after);
        assertTrue(after.contains("Failed: Failed to stop alpha"), "the failure shows after the next key:\n" + after);
    }

    @Test
    void anyOtherKeyCancelsTheStopAndKeepsTheMarks() {
        var tui = withAlphaAndGammaMarked();
        press(tui, KeyEvent.ofKey(KeyCode.F7), KeyEvent.ofChar('n'));
        assertEquals(Tui.Mode.BROWSE, tui.mode());
        runQueued();
        assertTrue(daemon.stateActions().isEmpty(), daemon.stateActions().toString());
        assertEquals(List.of("alpha", "gamma"), List.copyOf(tui.markedInstances()));
    }

    @Test
    void theStopConfirmationListsEveryMarkedRunningInstance() {
        var names = new java.util.ArrayList<String>();
        for (int i = 1; i <= 12; i++) {
            var name = String.format("mcp-tpl-isx-agent-1482-design-%02d", i);
            names.add(name);
            daemon.instance(name, "container", "Running", branch());
        }
        var tui = tui();
        press(tui, TAB);
        // alpha and beta come first in the table; mark everything
        for (int i = 0; i < names.size() + 3; i++) press(tui, SPACE, DOWN);
        press(tui, KeyEvent.ofKey(KeyCode.F7));
        var dialog = screen(tui);
        assertTrue(dialog.contains("Stop 14 instances"), dialog);
        for (var name : names) assertTrue(dialog.contains(name), name + " is not listed:\n" + dialog);
        assertTrue(dialog.contains("alpha") && dialog.contains("beta"), dialog);
        assertFalse(dialog.contains("more"), "every name is listed, none counted:\n" + dialog);
    }

    @Test
    void stoppingMarksThatAreAllStoppedSaysSo() {
        var tui = tui();
        press(tui, TAB, DOWN, DOWN, SPACE);
        press(tui, KeyEvent.ofKey(KeyCode.F7));
        assertEquals("No marked instance is running", tui.statusMessage);
        assertTrue(queued.isEmpty());
        assertEquals(List.of("gamma"), List.copyOf(tui.markedInstances()));
    }

    @Test
    void aBusyMarkedInstanceStopsTheBatch() {
        var tui = withAlphaAndGammaMarked();
        daemon.instance("gamma", "container", "Stopped", branch(Metadata.OP_RESTARTING));
        startSession(tui);
        // A new session starts on the templates table.
        press(tui, TAB, KeyEvent.ofKey(KeyCode.F8));
        assertEquals("Operation in progress for gamma", tui.statusMessage);
        assertEquals(Tui.Mode.BROWSE, tui.mode());
        assertEquals(List.of("alpha", "gamma"), List.copyOf(tui.markedInstances()));
    }

    @Test
    void aRefreshDropsTheMarkOfAnInstanceThatIsGone() {
        var tui = withAlphaAndGammaMarked();
        startSession(tui, new FakeIncusDaemon()
                .instance("alpha", "container", "Running", branch())
                .instance("beta", "container", "Running", branch()));
        assertEquals(List.of("alpha"), List.copyOf(tui.markedInstances()));
    }

    @Test
    void escapeClearsTheMarksOnBothTables() {
        var tui = withAlphaAndGammaMarked();
        press(tui, TAB, SPACE, TAB);
        assertEquals(List.of("tpl-minimal"), List.copyOf(tui.markedTemplates()));
        press(tui, KeyEvent.ofKey(KeyCode.ESCAPE));
        assertTrue(tui.markedInstances().isEmpty());
        assertTrue(tui.markedTemplates().isEmpty());
        assertEquals("Cleared 3 marks", tui.statusMessage);
    }

    /**
     * Marking a row changes its mark cell and the end of the context line, and nothing else: no
     * name, label or column moves (#1482), on either table, with the cursor on the row or not.
     */
    @Test
    void markingMovesNothingElseOnTheScreen() {
        var tui = tui();
        press(tui, TAB);
        assertOnlyTheMarkAppears(tui, () -> press(tui, SPACE), "alpha");
        press(tui, DOWN, DOWN);
        assertOnlyTheMarkAppears(tui, () -> press(tui, SPACE), "gamma");
        press(tui, TAB);
        assertOnlyTheMarkAppears(tui, () -> press(tui, SPACE), "tpl-minimal");
    }

    private static void assertOnlyTheMarkAppears(Tui tui, Runnable mark, String row) {
        var before = screen(tui).split("\n", -1);
        mark.run();
        var after = screen(tui).split("\n", -1);
        assertEquals(before.length, after.length);
        // The line above the key bar
        int contextLine = java.util.Arrays.asList(after).indexOf(
                java.util.Arrays.stream(after).filter(l -> l.startsWith("│F1")).findFirst().orElseThrow()) - 1;
        for (int y = 0; y < after.length; y++) {
            if (y == contextLine) {
                // Only the count after the Space hint, at the end of the line, changes
                var upToHint = before[y].substring(0, before[y].indexOf("Space Mark") + "Space Mark".length());
                assertTrue(after[y].startsWith(upToHint), "the context line changes at its end only:\n"
                        + before[y] + "\n" + after[y]);
                continue;
            }
            int markAt = after[y].indexOf("●" + row + " ");
            if (markAt >= 0) {
                assertEquals(before[y].charAt(markAt), ' ', "the mark takes a blank cell:\n" + before[y]);
                var unmarked = after[y].substring(0, markAt) + " " + after[y].substring(markAt + 1);
                assertEquals(before[y], unmarked, "only the mark cell changes");
            } else {
                assertEquals(before[y], after[y], "line " + y + " moved");
            }
        }
        assertTrue(String.join("\n", after).contains("●" + row + " "), String.join("\n", after));
    }

    @Test
    void marksShowOnTheirRowsAndTheContextLine() {
        var tui = withAlphaAndGammaMarked();
        TuiSnapshot.assertMatches("tui-instances-marked-80x24", TuiSnapshot.render(80, 24, tui::render));
    }
}
