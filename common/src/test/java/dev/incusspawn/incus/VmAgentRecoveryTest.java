package dev.incusspawn.incus;

import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.lifecycle.VmAgentRecovery;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VmAgentRecovery} against a VM whose agent never answers ({@link FakeIncusDaemon} serves
 * no exec). A restart is a cold boot that kills the guest's work, so it must happen at most once
 * per boot, and not at all when the console log already shows it cannot help (#843).
 *
 * <p>In this package rather than {@code lifecycle} so it can shorten {@code waitForReady}.
 */
class VmAgentRecoveryTest {

    private static final String FAILED = "[FAILED] Failed to start incus-agent.service - Incus - agent.";
    private static final String STARTED = "[  OK  ] Started incus-agent.service - Incus - agent.";

    private final FakeIncusDaemon daemon = new FakeIncusDaemon();
    /** What recovery reported through its sink; it must never print directly (the TUI may own the terminal). */
    private final List<String> said = new java.util.ArrayList<>();

    private IncusClient client() {
        var client = daemon.client();
        client.readyTimeouts(new IncusClient.ReadyTimeouts(Duration.ofMillis(50),
                Duration.ofMillis(100), Duration.ofMillis(50), Duration.ofMillis(20), Duration.ofMillis(20)));
        return client;
    }

    @Test
    void consoleShowingTheAgentFailingIsReportedWithoutARestart() {
        daemon.instance("vm1", "virtual-machine", "Running", Map.of())
                .consoleLog("vm1", "booting\n" + FAILED + "\n");
        var e = assertThrows(IncusException.class, () -> VmAgentRecovery.restartForAgent(client(), "vm1", said::add));

        var msg = e.getMessage();
        assertTrue(msg.startsWith("The incus-agent in VM vm1 failed to start"), msg);
        assertTrue(msg.contains(FAILED), msg);
        assertTrue(msg.contains("Not restarting vm1"), msg);
        assertTrue(msg.contains("incus restart --force vm1"), msg);
        assertEquals(List.of(), daemon.stateActions(), "nothing may stop or start the VM");
    }

    @Test
    void ensureReadyRecoversAVmWhoseAgentRefusesExec() {
        // FakeIncusDaemon refuses the exec POST, as Incus does when a VM's agent is down: the
        // probe throws rather than failing, and must still count as "not responding".
        daemon.instance("vm1", "virtual-machine", "Running", Map.of());

        assertThrows(IncusException.class, () -> InstanceLifecycle.ensureReady(client(), "vm1", daemon.instance("vm1"), MachineType.VM, said::add));
        assertEquals(List.of("vm1 stop", "vm1 start"), daemon.stateActions());
        assertEquals(List.of("VM agent not responding, restarting vm1..."), said);
    }

    @Test
    void aFailureTheAgentRecoveredFromDoesNotPreventARestart() {
        daemon.instance("vm1", "virtual-machine", "Running", Map.of())
                .consoleLog("vm1", FAILED + "\n" + STARTED + "\n");

        assertThrows(IncusException.class, () -> VmAgentRecovery.restartForAgent(client(), "vm1", said::add));
        assertEquals(List.of("vm1 stop", "vm1 start"), daemon.stateActions());
    }

    @Test
    void restartsOnceThenReportsOnTheBootItRestartedInto() {
        daemon.instance("vm1", "virtual-machine", "Running", Map.of())
                .consoleLog("vm1", "[  OK  ] Reached target multi-user.target\n");
        var incus = client();

        var first = assertThrows(IncusException.class, () -> VmAgentRecovery.restartForAgent(incus, "vm1", said::add));
        assertTrue(first.getMessage().startsWith("VM vm1 is running, but its incus-agent did not come up"),
                first.getMessage());
        assertEquals(List.of("vm1 stop", "vm1 start"), daemon.stateActions(),
                "a guest that shuts down when asked is not powered off");
        assertEquals(Long.toString(incus.pid("vm1")),
                daemon.instance("vm1").path("config").path(Metadata.AGENT_RESTART_BOOT).asText());

        daemon.stateActions().clear();
        var second = assertThrows(IncusException.class, () -> VmAgentRecovery.restartForAgent(incus, "vm1", said::add));
        var msg = second.getMessage();
        assertTrue(msg.contains("already restarted vm1 once"), msg);
        assertTrue(msg.contains("incus console vm1 --show-log"), msg);
        assertEquals(List.of(), daemon.stateActions(), "the restarted boot must not be cycled again");
    }

    @Test
    void aBootIsxDidNotRestartIntoGetsItsRestart() {
        // Stamped by an earlier recovery; the VM has since been stopped and started again.
        daemon.instance("vm1", "virtual-machine", "Running", Map.of(Metadata.AGENT_RESTART_BOOT, "1"));
        var incus = client();
        assertNotEquals(1L, incus.pid("vm1"));

        assertThrows(IncusException.class, () -> VmAgentRecovery.restartForAgent(incus, "vm1", said::add));
        assertEquals(List.of("vm1 stop", "vm1 start"), daemon.stateActions());
    }

    @Test
    void guestIgnoringTheShutdownIsPoweredOff() {
        daemon.instance("vm1", "virtual-machine", "Running", Map.of()).ignoreShutdown("vm1");

        assertThrows(IncusException.class, () -> VmAgentRecovery.restartForAgent(client(), "vm1", said::add));
        assertEquals(List.of("vm1 stop", "vm1 stop force", "vm1 start"), daemon.stateActions());
    }
}
