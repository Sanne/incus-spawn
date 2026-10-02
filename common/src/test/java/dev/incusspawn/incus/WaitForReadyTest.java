package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IncusClient#waitForReady} against instances that never answer exec ({@link
 * FakeIncusDaemon} serves none), with its waits shortened. A VM's failure must name the VM and
 * its agent, and quote the console lines that say why (#844).
 */
class WaitForReadyTest {

    private static final String AVC = "[    4.105712] audit: type=1400 audit(1758000000.123:4): avc:  denied  { listen } "
            + "for  pid=1288 comm=\"incus-agent\" scontext=system_u:system_r:init_t:s0 "
            + "tcontext=system_u:system_r:init_t:s0 tclass=vsock_socket permissive=0";
    private static final String FAILED = "[FAILED] Failed to start incus-agent.service - Incus - agent.";

    private final FakeIncusDaemon daemon = new FakeIncusDaemon();

    private IncusClient client(long containerMs, long vmMs, long graceMs) {
        return client(containerMs, vmMs, graceMs, 60_000);
    }

    private IncusClient client(long containerMs, long vmMs, long graceMs, long gatedProbeMs) {
        var client = daemon.client();
        client.readyTimeouts(new IncusClient.ReadyTimeouts(Duration.ofMillis(containerMs),
                Duration.ofMillis(vmMs), Duration.ofMillis(graceMs), Duration.ofMillis(20),
                Duration.ofMillis(gatedProbeMs)));
        return client;
    }

    private static long ms(long millis) {
        return Duration.ofMillis(millis).toNanos();
    }

    @Test
    void containerTimeoutKeepsItsMessageAndNeverReadsAConsole() {
        daemon.instance("c1", "container", "Running", Map.of());
        var e = assertThrows(IncusException.class, () -> client(200, 5_000, 100).waitForReady("c1"));

        assertTrue(e.getMessage().startsWith("Container c1 failed to become ready after"), e.getMessage());
        assertTrue(daemon.requests().stream().noneMatch(r -> r.endsWith("/console")),
                "a container's wait must not read a console log: " + daemon.requests());
    }

    @Test
    void vmGetsTheLongerBudgetAndNamesTheAgent() {
        daemon.instance("vm1", "virtual-machine", "Running", Map.of())
                .consoleLog("vm1", "[  OK  ] Reached target multi-user.target\n");
        long start = System.nanoTime();
        var e = assertThrows(IncusException.class, () -> client(50, 400, 100).waitForReady("vm1", MachineType.VM));

        assertTrue(System.nanoTime() - start >= ms(400), "a VM must wait out the VM budget, not a container's");
        var msg = e.getMessage();
        assertTrue(msg.startsWith("VM vm1 is running, but its incus-agent did not come up within"), msg);
        assertTrue(msg.contains("incus console vm1 --show-log"), msg);
        assertFalse(msg.contains("Container"), msg);
    }

    @Test
    void vmFailsFastOnceTheConsoleShowsTheAgentFailing() {
        daemon.instance("vm1", "virtual-machine", "Running", Map.of())
                .consoleLog("vm1", "booting\n" + AVC + "\n" + FAILED + "\n" + FAILED + "\n");
        long start = System.nanoTime();
        var e = assertThrows(IncusException.class, () -> client(50, 60_000, 150).waitForReady("vm1", MachineType.VM));

        long elapsed = System.nanoTime() - start;
        assertTrue(elapsed >= ms(150), "the agent gets its grace period to restart");
        assertTrue(elapsed < ms(30_000), "a known failure must not wait out the whole VM budget");
        var msg = e.getMessage();
        assertTrue(msg.startsWith("The incus-agent in VM vm1 failed to start"), msg);
        assertTrue(msg.contains("tclass=vsock_socket"), msg);
        assertEquals(1, msg.split("Failed to start incus-agent", -1).length - 1,
                "repeated lines are reported once: " + msg);
        assertTrue(msg.contains("incus console vm1 --show-log"), msg);
    }

    @Test
    void vmThatStopsReportsTheAgentFailureItLogged() {
        daemon.instance("vm1", "virtual-machine", "Stopped", Map.of())
                .consoleLog("vm1", FAILED + "\n");
        var e = assertThrows(IncusException.class, () -> client(5_000, 5_000, 1_000).waitForReady("vm1", MachineType.VM));

        var msg = e.getMessage();
        assertTrue(msg.startsWith("VM vm1 died during startup (status: Stopped)"), msg);
        assertTrue(msg.contains(FAILED), msg);
    }

    private List<String> requestsEndingWith(String suffix) {
        return daemon.requests().stream().filter(r -> r.endsWith(suffix)).toList();
    }

    @Test
    void vmWhoseAgentIsNotConnectedIsNeverProbedWithExec() {
        daemon.instance("vm1", "virtual-machine", "Running", Map.of()).agentProcesses("vm1", -1);
        var e = assertThrows(IncusException.class, () -> client(50, 300, 100).waitForReady("vm1", MachineType.VM));

        assertTrue(e.getMessage().startsWith("VM vm1 is running, but its incus-agent did not come up within"),
                e.getMessage());
        assertEquals(List.of(), requestsEndingWith("/exec"),
                "while its state says the agent is not connected, an exec probe can only fail (#954)");
        assertFalse(requestsEndingWith("/state").isEmpty(), daemon.requests().toString());
    }

    @Test
    void vmWhoseStateNeverShowsTheAgentIsStillProbedNowAndThen() {
        // Incus also reports -1 when its state query to a connected agent fails: exec must get a say
        daemon.instance("vm1", "virtual-machine", "Running", Map.of()).agentProcesses("vm1", -1);
        assertThrows(IncusException.class, () -> client(50, 1_500, 100, 500).waitForReady("vm1", MachineType.VM));

        int execs = requestsEndingWith("/exec").size();
        int states = requestsEndingWith("/state").size();
        assertTrue(execs >= 1, "a shut gate still lets exec answer: " + daemon.requests());
        assertTrue(states >= 2 * execs, "one probe per gated interval, not per poll: " + daemon.requests());
    }

    @Test
    void vmIsProbedWithExecOnlyOnceItsAgentConnects() {
        daemon.instance("vm1", "virtual-machine", "Running", Map.of()).agentProcesses("vm1", -1, -1, -1, 16);
        assertThrows(IncusException.class, () -> client(50, 2_000, 100).waitForReady("vm1", MachineType.VM));

        var requests = daemon.requests();
        assertEquals(4, requestsEndingWith("/state").size(),
                "once the agent is connected the state is not read again: " + requests);
        int fourthState = requests.lastIndexOf("GET /1.0/instances/vm1/state");
        int firstExec = requests.indexOf("POST /1.0/instances/vm1/exec");
        assertTrue(firstExec > fourthState, "the first exec follows the state read that saw the agent: " + requests);
    }

    @Test
    void vmWhoseStateDoesNotReportProcessesIsProbedWithExec() {
        daemon.instance("vm1", "virtual-machine", "Running", Map.of());
        assertThrows(IncusException.class, () -> client(50, 300, 100).waitForReady("vm1", MachineType.VM));

        assertEquals(1, requestsEndingWith("/state").size(), daemon.requests().toString());
        assertTrue(requestsEndingWith("/exec").size() > 1, "falls back to probing exec: " + daemon.requests());
    }

    @Test
    void containerNeverReadsItsState() {
        daemon.instance("c1", "container", "Running", Map.of()).agentProcesses("c1", -1);
        assertThrows(IncusException.class, () -> client(200, 5_000, 100).waitForReady("c1", MachineType.CONTAINER));

        assertEquals(List.of(), requestsEndingWith("/state"), "a container's wait costs no state read");
    }

    @Test
    void onlyAProbeThatPrintedReadyCountsAsAnswered() {
        assertTrue(IncusClient.answeredReady(new IncusClient.ExecResult(0, "ready\n", "")));
        assertFalse(IncusClient.answeredReady(new IncusClient.ExecResult(0, "Error: VM agent isn't currently running\n", "")),
                "an exit 0 that did not run the probe must not end the wait");
        assertFalse(IncusClient.answeredReady(new IncusClient.ExecResult(0, "", "")));
        assertFalse(IncusClient.answeredReady(new IncusClient.ExecResult(1, "ready\n", "")));
    }

    @Test
    void containerThatStopsKeepsItsMessage() {
        daemon.instance("c1", "container", "Stopped", Map.of());
        var e = assertThrows(IncusException.class, () -> client(5_000, 5_000, 1_000).waitForReady("c1"));

        assertEquals("Container c1 died during startup (status: Stopped)", e.getMessage());
    }
}
