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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What a TUI rename leaves in the host's SSH config (#1612). */
@ExtendWith(IsolatedHome.class)
class TuiRenameTest {

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

    @Test
    void theRenamedHostBlockKeepsTheAddressGatewayDials() throws Exception {
        // Rename needs a stopped instance, which has no live address: the block's comes from its stamp.
        var daemon = new FakeIncusDaemon().instance("alpha", "container", "Stopped", Map.of(
                Metadata.TYPE, "branch", Metadata.PROFILE, "tpl-minimal", Metadata.PARENT, "tpl-minimal",
                Metadata.NETWORK_MODE, "proxy", Metadata.STATIC_IP, "10.166.11.42",
                Metadata.SSH_SETUP, "true"));
        Files.createDirectories(Environment.sshKeyFile().getParent());
        Files.writeString(Environment.sshKeyFile(), "stand-in");
        Files.writeString(Environment.sshPubKeyFile(), "stand-in");
        SshKeyManager.addHostEntry("alpha", "10.166.11.42");
        var tui = new Tui() {
            @Override
            boolean showProxyError() {
                return false;
            }
        };
        tui.useDefinitions(new LinkedHashMap<>(), new ToolDefLoader(List.of()), List.of());
        tui.startSession(daemon.client(), new BackgroundTaskManager(Runnable::run), new FlockInstanceLockManager(),
                InstanceListing.collectEntries(daemon.client().listJson()));

        daemon.clearRequests();
        for (var key : List.of(KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofKey(KeyCode.F6), KeyEvent.ofChar('2'),
                KeyEvent.ofKey(KeyCode.ENTER))) {
            tui.handleEvent(key, runner);
        }

        // Only rename's own check reads the new name: host integration reuses that read.
        assertEquals(2, daemon.requests().stream().filter("GET /1.0/instances/alpha2"::equals).count(),
                daemon.requests().toString());
        assertTrue(daemon.client().exists("alpha2"), daemon.requests().toString());
        var sshConfig = Files.readString(Environment.sshConfigFile());
        assertFalse(sshConfig.contains("Host alpha\n"), sshConfig);
        assertTrue(sshConfig.contains("Host alpha2\n    Hostname 10.166.11.42\n"), sshConfig);
    }
}
