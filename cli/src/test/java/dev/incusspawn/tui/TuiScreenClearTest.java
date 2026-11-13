package dev.incusspawn.tui;

import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.LinuxBranching;
import dev.incusspawn.tool.ToolDefLoader;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The TUI leaves blank cells unwritten, so whatever the terminal held shows through them unless
 * the screen is cleared (#1681): once before the first frame, which tamboui does since 0.7.0, and again on Ctrl+L.
 */
@ExtendWith({IsolatedHome.class, LinuxBranching.class})
class TuiScreenClearTest {

    private static final KeyEvent CTRL_L = KeyEvent.ofChar('l', KeyModifiers.CTRL);

    private final ScreenBackend screen = new ScreenBackend(120, 30);
    private TuiRunner runner;

    @BeforeEach
    void runner() throws Exception {
        runner = TuiRunner.create(Tui.runnerConfig().backend(screen).shutdownHook(false).noTick().build());
    }

    @AfterEach
    void close() {
        runner.close();
    }

    private static Tui tui() {
        var defs = new LinkedHashMap<String, ImageDef>();
        var def = new ImageDef();
        def.setName("tpl-minimal");
        def.setImage("fedora-44");
        def.setDescription("Minimal Fedora");
        defs.put("tpl-minimal", def);
        var daemon = new FakeIncusDaemon().container("tpl-minimal", Map.of(
                Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-minimal"));
        // The host's proxy and firewall are not this test's to check.
        var tui = new Tui() {
            @Override
            boolean showProxyError() {
                return false;
            }

            @Override
            void warnIfFirewallStopped(BiConsumer<String, String> banner) {}
        };
        tui.useDefinitions(defs, new ToolDefLoader(List.of()), List.of());
        tui.startSession(daemon.client(), new BackgroundTaskManager(), new FlockInstanceLockManager(),
                InstanceListing.collectEntries(daemon.client().listJson()));
        return tui;
    }

    private void assertNoStaleCells(String when) {
        var text = screen.text();
        assertFalse(text.contains(ScreenBackend.STALE), "stale cells " + when + ":\n" + text);
    }

    @Test
    void theFirstFrameLeavesNothingFromBefore() {
        runner.draw(tui()::render);
        assertNoStaleCells("after the first frame");
    }

    @Test
    void ctrlLClearsWhatWasWrittenOverTheScreen() {
        var tui = tui();
        runner.draw(tui::render);
        screen.scribble();
        tui.handleEvent(CTRL_L, runner);
        runner.draw(tui::render);
        assertNoStaleCells("after Ctrl+L");
    }

    @Test
    void ctrlLClearsUnderTheSearchToo() {
        var tui = tui();
        tui.handleEvent(KeyEvent.ofChar('/'), runner);
        runner.draw(tui::render);
        screen.scribble();
        tui.handleEvent(CTRL_L, runner);
        runner.draw(tui::render);
        assertNoStaleCells("after Ctrl+L in the search");
    }
}
