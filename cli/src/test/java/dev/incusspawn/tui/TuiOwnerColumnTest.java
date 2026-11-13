package dev.incusspawn.tui;

import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDefLoader;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The instance table shows who owns each instance, an orchestrator or an isx mcp session (#1720),
 * in an OWNER column when the terminal has room for it, and leaves the column out when it is too
 * narrow. The instance details show the owner either way (Sanne's decision on #1820).
 */
@ExtendWith(IsolatedHome.class)
class TuiOwnerColumnTest {

    private TuiRunner runner;

    @AfterEach
    void close() {
        if (runner != null) runner.close();
    }

    private Tui tui(int width) throws Exception {
        return tui(width, true);
    }

    private Tui tui(int width, boolean owned) throws Exception {
        runner = TuiRunner.create(Tui.runnerConfig().backend(new HeadlessBackend(width, 24))
                .shutdownHook(false).noTick().build());
        var daemon = new FakeIncusDaemon()
                .instance("ws", "container", "Stopped", Map.of(Metadata.TYPE, "branch", Metadata.PARENT, "tpl-minimal",
                        Metadata.PROFILE, "tpl-minimal", Metadata.OWNER, owned ? "orca" : "", Metadata.OWNER_ID, owned ? "rt-1" : ""))
                .instance("mine", "container", "Stopped", Map.of(Metadata.TYPE, "branch", Metadata.PARENT, "tpl-minimal",
                        Metadata.PROFILE, "tpl-minimal"));
        var tui = new Tui() {
            @Override
            boolean showProxyError() {
                return false;
            }
        };
        tui.useDefinitions(new LinkedHashMap<>(), new ToolDefLoader(List.of()), List.of());
        tui.startSession(daemon.client(), new BackgroundTaskManager(Runnable::run), new FlockInstanceLockManager(),
                InstanceListing.collectEntries(daemon.client().listJson()));
        return tui;
    }

    private static String screen(Tui tui, int width) {
        return TuiSnapshot.toText(TuiSnapshot.render(width, 24, tui::render));
    }

    private static String rowOf(String screen, String name) {
        return screen.lines().filter(l -> l.contains(" " + name + " ")).findFirst().orElseThrow(() -> new AssertionError(screen));
    }

    @Test
    void aWideTerminalShowsTheOwnerColumn() throws Exception {
        var tui = tui(140);
        var screen = screen(tui, 140);
        assertTrue(screen.lines().anyMatch(l -> l.contains("NAME") && l.contains("OWNER")), screen);
        assertTrue(rowOf(screen, "ws").contains("orca"), screen);
        assertFalse(rowOf(screen, "mine").contains("orca"), screen);
    }

    @Test
    void aNarrowTerminalLeavesTheColumnOutAndTheDetailsStillShowTheOwner() throws Exception {
        var tui = tui(80);
        var screen = screen(tui, 80);
        assertTrue(screen.lines().anyMatch(l -> l.contains("NAME") && l.contains("DISK")), screen);
        assertFalse(screen.contains("OWNER"), screen);
        assertFalse(rowOf(screen, "ws").contains("orca"), screen);

        tui.handleEvent(KeyEvent.ofKey(KeyCode.TAB), runner);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), runner);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.F3), runner);
        var details = screen(tui, 80);
        assertTrue(details.contains("Owner:") && details.contains("orca (rt-1)"), details);
    }


    /**
     * The column starts at 100 terminal columns: below, it would take the other columns' slack
     * (PARENT showed {@code tpl-minima} at 84 to 90), and at 100 nothing is clipped (review of #1820).
     */
    @Test
    void theColumnStartsAtAHundredColumnsWithNothingClipped() throws Exception {
        var narrow = screen(tui(99), 99);
        assertFalse(narrow.contains("OWNER"), narrow);
        runner.close();
        runner = null;
        var wide = screen(tui(100), 100);
        assertTrue(wide.lines().anyMatch(l -> l.contains("NAME") && l.contains("OWNER")), wide);
        assertTrue(rowOf(wide, "ws").contains("tpl-minimal ") && rowOf(wide, "ws").contains("orca"), wide);
    }

    /** Nothing owned, nothing to show: the column stays hidden on a wide terminal too. */
    @Test
    void aWideTerminalWithNothingOwnedHasNoOwnerColumn() throws Exception {
        var screen = screen(tui(140, false), 140);
        assertTrue(screen.lines().anyMatch(l -> l.contains("NAME") && l.contains("DISK")), screen);
        assertFalse(screen.contains("OWNER"), screen);
    }

    /** A search that hides the owned instance keeps the column: the layout must not jump as one types. */
    @Test
    void aSearchHidingTheOwnedInstanceKeepsTheColumn() throws Exception {
        var tui = tui(140);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.TAB), runner);
        // "mine": the search also matches the parent, which holds "mi" for ws too
        for (var c : "/mine".toCharArray()) tui.handleEvent(KeyEvent.ofChar(c), runner);
        var screen = screen(tui, 140);
        assertFalse(screen.lines().anyMatch(l -> l.contains(" ws ")), "the search hides ws: " + screen);
        assertTrue(screen.lines().anyMatch(l -> l.contains("NAME") && l.contains("OWNER")), screen);
    }
}
