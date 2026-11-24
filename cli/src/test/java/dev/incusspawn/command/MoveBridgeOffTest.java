package dev.incusspawn.command;

import dev.incusspawn.incus.BridgeSubnetCheck.ConflictResult;
import dev.incusspawn.incus.FirewallDetector.DetectionResult;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

import static dev.incusspawn.command.InitStepOutputTest.capture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The order and failures of {@code isx doctor}'s bridge move (#1203): nothing changes before sudo
 * is granted, the move acts on the conflict the user agreed to, and a step that did not complete
 * fails the remedy rather than letting doctor report it done.
 */
@ExtendWith(IsolatedHome.class)
class MoveBridgeOffTest {

    private static final ConflictResult CONFLICT = new ConflictResult(true,
            "10.0.0.0/8 via 10.64.0.1 dev tun0", "10.166.11.1/24", "172.20.0.1/24");

    static final class Steps extends InitCommand {
        final List<String> done = new ArrayList<>();
        boolean sudo = true;
        DetectionResult firewall = new DetectionResult.UseFirewalld(false);
        boolean redirected = true;
        List<String> stale = List.of();
        /** Runs the real redirect and gateway save, on host commands that only succeed. */
        boolean realRedirect;
        Runnable duringMigration = () -> {};

        Steps() {
            incus = mock(IncusClient.class);
            doAnswer(i -> done.add("move " + i.getArgument(2))).when(incus)
                    .networkConfigSet(anyString(), anyString(), anyString());
        }

        @Override boolean sudoValidated() { done.add("sudo"); return sudo; }
        @Override int migrateInstances() { done.add("migrate"); duringMigration.run(); return 1; }
        @Override String captureOutput(String... command) { return ""; }
        @Override int runHostQuiet(String... command) { return 0; }
        @Override DetectionResult runningFirewall() { return firewall; }
        @Override boolean followBridgeMove(String gatewayIp, DetectionResult fw) {
            done.add("redirect " + gatewayIp);
            return realRedirect ? super.followBridgeMove(gatewayIp, fw) : redirected;
        }
        @Override List<String> staleInstances() { return stale; }
    }

    /** The move ends by saving the gateway, so a config.yaml that does not parse stops it before anything moves (#2523). */
    @Test
    void aConfigThatDoesNotParseStopsTheMoveBeforeAnythingChanges() throws Exception {
        var broken = "claude:\n  accounts:\n    work: {apiKey: x\n";
        java.nio.file.Files.createDirectories(IsolatedHome.configFile().getParent());
        java.nio.file.Files.writeString(IsolatedHome.configFile(), broken);
        var steps = new Steps();
        var e = assertThrows(IllegalStateException.class, () -> capture(() -> steps.moveBridgeOff(CONFLICT)));
        assertTrue(e.getMessage().contains("run 'isx doctor' again"), e.getMessage());
        assertEquals(List.of(), steps.done);
        IsolatedHome.assertUnchanged(broken);
    }

    /** The gateway is saved to config.yaml as it is after the migration, so an account added during it is kept (#2523). */
    @Test
    void anAccountAddedDuringTheMigrationIsKept() throws Exception {
        IsolatedHome.seed("incus-bridge-gateway: 10.166.11.1\n");
        var steps = new Steps();
        steps.realRedirect = true;
        // Another terminal's isx account add, while the instances migrate
        steps.duringMigration = () -> {
            try {
                IsolatedHome.seed("incus-bridge-gateway: 10.166.11.1\nclaude:\n  accounts:\n    work: {apiKey: x}\n");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        capture(() -> steps.moveBridgeOff(CONFLICT));
        assertEquals("172.20.0.1", dev.incusspawn.config.SpawnConfig.load().getIncusBridgeGateway());
        assertEquals("x", IsolatedHome.saved("claude.accounts.work.apiKey"));
    }

    @Test
    void movesMigratesThenRedirectsToTheNewGateway() throws Exception {
        var steps = new Steps();
        capture(() -> steps.moveBridgeOff(CONFLICT));
        assertEquals(List.of("sudo", "move 172.20.0.1/24", "migrate", "redirect 172.20.0.1"), steps.done);
    }

    @Test
    void refusedSudoChangesNothing() {
        var steps = new Steps();
        steps.sudo = false;
        var e = assertThrows(IllegalStateException.class, () -> capture(() -> steps.moveBridgeOff(CONFLICT)));
        assertTrue(e.getMessage().contains("nothing was changed"), e.getMessage());
        assertEquals(List.of("sudo"), steps.done);
    }

    @Test
    void aConflictGoneSinceTheFindingAsksForNothing() throws Exception {
        var steps = new Steps();
        capture(() -> steps.moveBridgeOff(new ConflictResult(false, null, "10.166.11.1/24", null)));
        assertEquals(List.of(), steps.done);
    }

    @Test
    void noFreeSubnetAnyMoreFailsBeforeSudo() {
        var steps = new Steps();
        assertThrows(IllegalStateException.class, () -> capture(() -> steps.moveBridgeOff(
                new ConflictResult(true, "172.16.0.0/12 via 10.0.0.1 dev tun0", "10.166.11.1/24", null))));
        assertEquals(List.of(), steps.done);
    }

    @Test
    void aRedirectNotWrittenFailsTheRemedyAndPointsAtInit() {
        var steps = new Steps();
        steps.redirected = false;
        var e = assertThrows(IllegalStateException.class, () -> capture(() -> steps.moveBridgeOff(CONFLICT)));
        assertTrue(e.getMessage().contains("isx init"), e.getMessage());
    }

    @Test
    void instancesLeftOnTheOldSubnetFailTheRemedy() {
        var steps = new Steps();
        steps.stale = List.of("dev1");
        var e = assertThrows(IllegalStateException.class, () -> capture(() -> steps.moveBridgeOff(CONFLICT)));
        assertTrue(e.getMessage().contains("dev1"), e.getMessage());
        assertTrue(steps.done.contains("redirect 172.20.0.1"), "the redirect follows the bridge even so");
    }

    /** The redirect could not follow the bridge, so the bridge and the instances stay put. */
    @Test
    void noRunningFirewallRefusesBeforeAnythingMoves() {
        for (var firewall : List.of(new DetectionResult.UseFirewalld(true), new DetectionResult.NeitherInstalled())) {
            var steps = new Steps();
            steps.firewall = firewall;
            var e = assertThrows(IllegalStateException.class, () -> capture(() -> steps.moveBridgeOff(CONFLICT)));
            assertTrue(e.getMessage().contains("nothing was changed"), e.getMessage());
            assertEquals(List.of(), steps.done, firewall.toString());
        }
    }
}
