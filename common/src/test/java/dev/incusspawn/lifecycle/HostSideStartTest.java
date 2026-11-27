package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.HostSideStartFailure;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * A VM Incus stopped while it started, by telling QEMU to quit, is started once more, saying so,
 * and a guest that died on its own is not (#2675).
 */
@ExtendWith(TempHome.class)
class HostSideStartTest {

    private final FakeIncusDaemon daemon = new FakeIncusDaemon();
    private final IncusClient incus = daemon.client();
    private final RecordingOutput out = new RecordingOutput();

    private void vm() {
        daemon.instance("vm1", "virtual-machine", "Stopped", Map.of()).execOutput("ready", "ready\n");
    }

    private String startAndWait() {
        incus.start("vm1");
        return HostSideStart.retryOnce(incus, "vm1", out,
                () -> incus.waitForReady("vm1", MachineType.VM, null, Map.of()));
    }

    @Test
    void aVmIncusStoppedBeforeItsGuestRanIsStartedOnceMore() {
        vm();
        daemon.killedOnStart("vm1", 1);

        assertDoesNotThrow(this::startAndWait);

        assertEquals(List.of("vm1 start", "vm1 start"), daemon.stateActions());
        assertEquals("Running", incus.getInstanceStatus("vm1"));
        var warning = out.events().stream().filter(e -> e.kind() == RecordingOutput.Kind.WARN).toList();
        assertEquals(1, warning.size(), out.events().toString());
        assertTrue(warning.getFirst().msg().contains("while it was starting, so isx is starting it again"),
                warning.getFirst().msg());
    }

    @Test
    void aSecondHostSideFailureIsReportedNotRetried() {
        vm();
        daemon.killedOnStart("vm1", 2);

        var e = assertThrows(HostSideStartFailure.class, this::startAndWait);

        assertTrue(e.getMessage().startsWith("VM vm1 died during startup (status: Stopped)"), e.getMessage());
        assertEquals(List.of("vm1 start", "vm1 start"), daemon.stateActions());
    }

    @Test
    void aGuestThatShutItselfDownIsNotRetried() {
        vm();
        // Its console reads empty all the same: Incus writes it only when it is read while the VM runs
        daemon.stoppedOnStart("vm1", 1,
                "[t] Event: {\"event\": \"SHUTDOWN\", \"data\": {\"guest\": true, \"reason\": \"guest-shutdown\"}}\n");
        var e = assertThrows(IncusException.class, this::startAndWait);

        assertFalse(e instanceof HostSideStartFailure, e.getMessage());
        assertEquals(List.of("vm1 start"), daemon.stateActions());
    }

    @Test
    void aWaitThatSucceedsAsksNothingMore() {
        vm();
        daemon.instance("vm2", "virtual-machine", "Stopped", Map.of());
        incus.start("vm2");
        incus.waitForReady("vm2", MachineType.VM, null, Map.of());
        var direct = daemon.requests().size();
        daemon.clearRequests();

        startAndWait();

        assertEquals(direct, daemon.requests().size(), daemon.requests().toString());
    }

    @Test
    void anIsxStartOfAVmStartsItOnceMore() {
        daemon.instance("vm1", "virtual-machine", "Stopped", Map.of()).ipFiltering("vm1", "true")
                .killedOnStart("vm1", 1);
        var spied = spy(incus);
        // The secret's delivery runs in the wait, which FakeIncusDaemon cannot answer: stand in for it
        doThrow(new HostSideStartFailure("VM vm1 died during startup (status: Stopped), before its guest printed anything."))
                .doReturn("").when(spied).waitForReady(eq("vm1"), eq(MachineType.VM), any(), anyMap());

        InstanceLifecycle.startForUse(spied, "vm1", MachineType.VM, out);

        assertEquals(List.of("vm1 start", "vm1 start"), daemon.stateActions());
        assertTrue(out.events().stream().anyMatch(e -> e.msg().contains("so isx is starting it again")),
                out.events().toString());
    }
}
