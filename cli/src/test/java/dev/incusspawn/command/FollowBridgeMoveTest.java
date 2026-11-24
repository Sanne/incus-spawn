package dev.incusspawn.command;

import dev.incusspawn.incus.FirewallDetector.DetectionResult;
import dev.incusspawn.incus.UfwCheck;
import dev.incusspawn.proxy.ProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.incusspawn.command.InitStepOutputTest.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx doctor}'s bridge move (#1203) redoes the PREROUTING redirect for the new gateway on
 * the firewall that is running, and removes the old one, which names an address the bridge left.
 */
@ExtendWith(IsolatedHome.class)
class FollowBridgeMoveTest {

    private static final String OLD_GATEWAY = "10.166.11.1";
    private static final String NEW_GATEWAY = "172.20.0.1";

    private static final DetectionResult FIREWALLD = new DetectionResult.UseFirewalld(false);
    private static final DetectionResult UFW = new DetectionResult.UseUfw();
    private static final DetectionResult NONE = new DetectionResult.NeitherInstalled();

    private static final String STOCK_RULES = """
            *filter
            :ufw-before-input - [0:0]
            :ufw-before-output - [0:0]
            :ufw-before-forward - [0:0]
            -A ufw-before-forward -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
            COMMIT
            """;

    /** before.rules as init left it for the old gateway. */
    private static final String OLD_RULES = UfwCheck.insertNatBlock(STOCK_RULES, UfwCheck.generateNatBlock(
            OLD_GATEWAY, "10.166.11.0/24", ProxyConfig.CONTAINER_FACING_PORT, ProxyConfig.DEFAULT_MITM_PORT));

    /** Records each host command, keeps what a {@code sudo cp} would write, and fails the one named. */
    static final class RecordingInit extends InitCommand {
        final List<List<String>> commands = new ArrayList<>();
        String firewalldRules = "";
        String failing = "";
        String written;

        @Override
        String captureOutput(String... command) {
            return firewalldRules;
        }

        @Override
        int runHostQuiet(String... command) {
            var args = List.of(command);
            commands.add(args);
            if (args.contains(failing)) return 1;
            if (args.size() == 4 && args.get(1).equals("cp")) {
                try {
                    written = Files.readString(Path.of(args.get(2)));
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
            return 0;
        }
    }

    @Test
    void ufwRulesNameOnlyTheNewSubnetAndGateway() throws Exception {
        var init = new RecordingInit();
        var moved = new boolean[1];
        capture(() -> moved[0] = init.host().redirectToMovedGateway(NEW_GATEWAY, UFW, () -> OLD_RULES));
        assertTrue(moved[0]);
        assertTrue(init.written.contains("-A POSTROUTING -s 172.20.0.0/24 ! -d 172.20.0.0/24 -j MASQUERADE"),
                init.written);
        assertTrue(init.written.contains("-d " + NEW_GATEWAY + " -p tcp"), init.written);
        assertFalse(init.written.contains("10.166.11."), init.written);
    }

    @Test
    void ufwWriteFailureIsAFailedMove() throws Exception {
        var init = new RecordingInit();
        init.failing = "cp";
        var moved = new boolean[] {true};
        capture(() -> moved[0] = init.host().redirectToMovedGateway(NEW_GATEWAY, UFW, () -> OLD_RULES));
        assertFalse(moved[0]);
    }

    @Test
    void firewalldRemovesTheOldGatewaysRedirect() throws Exception {
        var init = new RecordingInit();
        init.firewalldRules = "ipv4 nat PREROUTING 0 -i incusbr0 -d " + OLD_GATEWAY
                + " -p tcp --dport 443 -j REDIRECT --to-port " + ProxyConfig.DEFAULT_MITM_PORT;
        var moved = new boolean[1];
        capture(() -> moved[0] = init.host().redirectToMovedGateway(NEW_GATEWAY, FIREWALLD, () -> fail("UFW not running")));
        assertTrue(moved[0]);
        assertTrue(init.commands.stream().anyMatch(c -> c.contains("--remove-rule") && c.contains(OLD_GATEWAY)),
                init.commands.toString());
        assertTrue(init.commands.stream().anyMatch(c -> c.contains("--add-rule") && c.contains(NEW_GATEWAY)),
                init.commands.toString());
    }

    /** The gateway is saved to config.yaml as it is after the move, so an account added meanwhile is kept (#2523). */
    @Test
    void theGatewayIsSavedWithoutUndoingAChangeMadeDuringTheMove() throws Exception {
        IsolatedHome.seed("incus-bridge-gateway: " + OLD_GATEWAY + "\n");
        var init = new RecordingInit();
        // Another terminal's isx account add, while the instances migrated
        IsolatedHome.seed("incus-bridge-gateway: " + OLD_GATEWAY + "\nclaude:\n  accounts:\n    work: {apiKey: x}\n");
        capture(() -> init.host().followBridgeMove(NEW_GATEWAY, NONE));
        assertEquals(NEW_GATEWAY, dev.incusspawn.config.SpawnConfig.load().getIncusBridgeGateway());
        assertEquals("x", IsolatedHome.saved("claude.accounts.work.apiKey"));
    }

    /** A config.yaml that stopped parsing during the move still gets the redirect, then stops the step and is left as it is. */
    @Test
    void aFileBrokenDuringTheMoveStillGetsItsRedirect() throws Exception {
        var broken = "claude: [unclosed\n";
        java.nio.file.Files.createDirectories(IsolatedHome.configFile().getParent());
        java.nio.file.Files.writeString(IsolatedHome.configFile(), broken);
        var init = new RecordingInit();
        var e = assertThrows(IllegalStateException.class, () -> capture(() -> init.host().followBridgeMove(NEW_GATEWAY, FIREWALLD)));
        assertTrue(e.getMessage().contains("run 'isx doctor' again"), e.getMessage());
        assertFalse(init.commands.isEmpty(), "the redirect is written before the save");
        IsolatedHome.assertUnchanged(broken);
    }

    @Test
    void noRunningFirewallIsAFailedMove() throws Exception {
        var init = new RecordingInit();
        var moved = new boolean[] {true};
        var output = capture(() -> moved[0] = init.host().redirectToMovedGateway(NEW_GATEWAY, NONE,
                () -> fail("UFW not running")));
        assertFalse(moved[0]);
        assertTrue(init.commands.isEmpty(), init.commands.toString());
        assertTrue(output.err().contains("No firewall is running"), output.err());
    }

    /** Starting a stopped firewalld is init's; doctor's move does not. */
    @Test
    void aStoppedFirewalldIsAFailedMove() throws Exception {
        var init = new RecordingInit();
        var moved = new boolean[] {true};
        capture(() -> moved[0] = init.host().redirectToMovedGateway(NEW_GATEWAY,
                new DetectionResult.UseFirewalld(true), () -> fail("UFW not running")));
        assertFalse(moved[0]);
        assertTrue(init.commands.isEmpty(), init.commands.toString());
    }
}
