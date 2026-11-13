package dev.incusspawn.tui;

import dev.incusspawn.Environment;
import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.tool.ToolDefLoader;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

/** The TUI destroys the instance whose deletion was confirmed, whatever dialog opens after it (#1248). */
@ExtendWith(IsolatedHome.class)
class TuiDeleteTargetTest {

    private TuiRunner runner;

    @BeforeEach
    void runner() throws Exception {
        runner = TuiRunner.create(Tui.runnerConfig().backend(new HeadlessBackend(80, 24))
                .shutdownHook(false).noTick().build());
    }

    @AfterEach
    void close() {
        runner.close();
    }

    private void press(Tui tui, KeyEvent... keys) {
        for (var key : keys) tui.handleEvent(key, runner);
    }

    private static String screen(Tui tui) {
        return TuiSnapshot.toText(TuiSnapshot.render(80, 24, tui::render));
    }

    private static Map<String, String> branch() {
        return Map.of(Metadata.TYPE, "branch", Metadata.PROFILE, "tpl-minimal",
                Metadata.PARENT, "tpl-minimal", Metadata.NETWORK_MODE, "proxy");
    }

    @Test
    void openingAnotherDeleteDialogDoesNotRetargetTheConfirmedDestroy() throws Exception {
        var daemon = new FakeIncusDaemon()
                .instance("alpha", "container", "Stopped", branch())
                .instance("beta", "container", "Stopped", branch());
        SshKeyManager.addHostEntry("alpha");
        SshKeyManager.addHostEntry("beta");
        // Submitted tasks wait here until the test runs them.
        Queue<Runnable> queued = new ArrayDeque<>();
        var tasks = new BackgroundTaskManager(queued::add);
        var tui = new Tui() {
            @Override
            boolean showProxyError() {
                return false;
            }
        };
        tui.useDefinitions(new LinkedHashMap<>(), new ToolDefLoader(List.of()), List.of());
        tui.startSession(daemon.client(), tasks, new FlockInstanceLockManager(),
                InstanceListing.collectEntries(daemon.client().listJson()));

        press(tui, KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofKey(KeyCode.F8));
        assertTrue(screen(tui).contains("Destroy 'alpha'"), screen(tui));
        press(tui, KeyEvent.ofChar('y'));
        assertEquals(1, queued.size());

        // A's destroy has not run yet; B's dialog opens and stays open, unconfirmed.
        press(tui, KeyEvent.ofKey(KeyCode.DOWN), KeyEvent.ofKey(KeyCode.F8));
        assertTrue(screen(tui).contains("Destroy 'beta'"), screen(tui));
        daemon.clearRequests();
        while (!queued.isEmpty()) queued.remove().run();

        assertTrue(daemon.requests().contains("DELETE /1.0/instances/alpha"), daemon.requests().toString());
        assertFalse(daemon.requests().contains("DELETE /1.0/instances/beta"), daemon.requests().toString());
        var sshConfig = Files.readString(Environment.sshConfigFile());
        assertFalse(sshConfig.contains("Host alpha"), sshConfig);
        assertTrue(sshConfig.contains("Host beta"), sshConfig);
    }
}
