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
 * What a person wrote an instance is for (#2239) in the TUI: a PURPOSE column when some instance has
 * one and the terminal has room, and {@code p} to change it on a running or a stopped instance.
 */
@ExtendWith(IsolatedHome.class)
class TuiPurposeTest {

    private TuiRunner runner;
    private FakeIncusDaemon daemon;

    @AfterEach
    void close() {
        if (runner != null) runner.close();
    }

    private Tui tui(int width, boolean purposed, boolean owned) throws Exception {
        runner = TuiRunner.create(Tui.runnerConfig().backend(new HeadlessBackend(width, 24))
                .shutdownHook(false).noTick().build());
        var ws = new LinkedHashMap<>(Map.of(Metadata.TYPE, "branch", Metadata.PARENT, "tpl-minimal",
                Metadata.PROFILE, "tpl-minimal"));
        if (purposed) ws.put(Metadata.PURPOSE, "waiting on review");
        if (owned) ws.put(Metadata.OWNER, "orca");
        daemon = new FakeIncusDaemon()
                .instance("ws", "container", "Stopped", ws)
                .instance("mine", "container", "Running", Map.of(Metadata.TYPE, "branch", Metadata.PARENT, "tpl-minimal",
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

    private void press(Tui tui, KeyEvent... keys) throws Exception {
        for (var key : keys) tui.handleEvent(key, runner);
    }

    private void type(Tui tui, String text) throws Exception {
        for (var c : text.toCharArray()) tui.handleEvent(KeyEvent.ofChar(c), runner);
    }

    private String purposeOf(String name) {
        return daemon.client().readInstance(name).metadata().path("config").pathText(Metadata.PURPOSE, "");
    }

    @Test
    void aWideTerminalShowsThePurposeColumn() throws Exception {
        var screen = screen(tui(120, true, false), 120);
        assertTrue(screen.lines().anyMatch(l -> l.contains("NAME") && l.contains("PURPOSE")), screen);
        assertTrue(screen.lines().anyMatch(l -> l.contains(" ws ") && l.contains("waiting on review")), screen);
    }

    @Test
    void withAnOwnerColumnBesideItThePurposeNeedsAWiderTerminal() throws Exception {
        var screen = screen(tui(110, true, true), 110);
        assertTrue(screen.contains("OWNER") && !screen.contains("PURPOSE"), screen);
        runner.close();
        var wide = screen(tui(120, true, true), 120);
        assertTrue(wide.lines().anyMatch(l -> l.contains("OWNER") && l.contains("PURPOSE")), wide);
        assertTrue(wide.lines().anyMatch(l -> l.contains(" ws ") && l.contains("orca") && l.contains("waiting on review")), wide);
    }

    @Test
    void aTerminalWithNoRoomOrNoPurposeHasNoColumnAndTheDetailsShowIt() throws Exception {
        var short_ = tui(104, true, false);
        assertFalse(screen(short_, 104).contains("PURPOSE"), "below 110 columns the minimums leave no room for it");
        runner.close();
        var narrow = tui(80, true, false);
        assertFalse(screen(narrow, 80).contains("PURPOSE"));
        press(narrow, KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofKey(KeyCode.DOWN), KeyEvent.ofKey(KeyCode.F3));
        assertTrue(screen(narrow, 80).contains("waiting on review"), screen(narrow, 80));
        runner.close();
        assertFalse(screen(tui(140, false, false), 140).contains("PURPOSE"), "nothing to show, no column");
    }

    @Test
    void pEditsThePurposeOfAStoppedInstanceAndAnEmptyTextClearsIt() throws Exception {
        var tui = tui(120, true, false);
        // The stopped instance is the second row, after the running one
        press(tui, KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofKey(KeyCode.DOWN), KeyEvent.ofChar('p'));
        assertTrue(screen(tui, 120).contains("Purpose of 'ws'"), screen(tui, 120));
        type(tui, " now merged");
        press(tui, KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals("waiting on review now merged", purposeOf("ws"));

        press(tui, KeyEvent.ofChar('p'));
        for (int i = 0; i < 60; i++) press(tui, KeyEvent.ofKey(KeyCode.BACKSPACE));
        press(tui, KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals("", purposeOf("ws"));
    }

    @Test
    void pEditsARunningInstanceToo() throws Exception {
        var tui = tui(120, false, false);
        press(tui, KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofChar('p'));
        type(tui, "spike");
        press(tui, KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals("spike", purposeOf("mine"));
    }

    @Test
    void anOverlongPurposeStopsAtTheLimitWhileTyping() throws Exception {
        var tui = tui(120, false, false);
        press(tui, KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofChar('p'));
        type(tui, "x".repeat(Metadata.PURPOSE_MAX + 1));
        press(tui, KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals("x".repeat(Metadata.PURPOSE_MAX), purposeOf("mine"));
    }

    @Test
    void escapeLeavesThePurposeAsItWas() throws Exception {
        var tui = tui(120, true, false);
        press(tui, KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofKey(KeyCode.DOWN), KeyEvent.ofChar('p'));
        type(tui, "zzz");
        press(tui, KeyEvent.ofKey(KeyCode.ESCAPE));
        assertEquals("waiting on review", purposeOf("ws"));
    }
}
