package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Every exec that gives a VM's boot its secret also gives the guest its instance name as its
 * hostname (#2334), since nothing else does: {@code ISX_CONTAINER}, the terminal title and the
 * agents' status lines all read it, and a VM kept the base image's {@code fedora}. A container's
 * hostname is Incus's to set, so its execs leave it alone.
 */
@ExtendWith({TempHome.class, LinuxBranching.class})
class GuestHostnameOnStartTest {

    private static final String NAME = "isx-pi-vm1";
    private static final String SETS_HOSTNAME = "printf '%s' '" + NAME + "' > /proc/sys/kernel/hostname";

    /** The commands of every exec that delivered a secret. */
    private static List<String> deliveries(FakeIncusDaemon daemon) {
        var scripts = daemon.execs().stream().map(e -> String.join(" ", e.command()))
                .filter(c -> c.contains(InstanceSecret.GUEST_SCRIPT)).toList();
        assertFalse(scripts.isEmpty(), "an exec delivered the secret");
        return scripts;
    }

    private static void startStopped(FakeIncusDaemon daemon, MachineType machineType) {
        // FakeIncusDaemon serves no exec, so the wait times out; the probes are what matters
        assertThrows(IncusException.class, () -> InstanceLifecycle.ensureReady(
                daemon.clientWithShortReadyWait(), NAME, daemon.instance(NAME), machineType, RecordingOutput.QUIET));
    }

    @Test
    void aVmsStartSetsItsHostname() {
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Stopped", Map.of()).ipFiltering(NAME, "true");
        startStopped(daemon, MachineType.VM);
        assertTrue(deliveries(daemon).stream().allMatch(c -> c.contains(SETS_HOSTNAME)));
    }

    @Test
    void aContainersStartLeavesItsHostnameToIncus() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true");
        startStopped(daemon, MachineType.CONTAINER);
        assertTrue(deliveries(daemon).stream().noneMatch(c -> c.contains("/proc/sys/kernel/hostname")));
    }

    @Test
    void aVmRebootedInPlaceGetsItsHostnameBackWithItsSecret() {
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Running", Map.of());
        var incus = spy(daemon.client());
        // The agent probe finds the guest without a secret, as after a reboot inside QEMU
        doReturn(new IncusClient.ExecResult(0, InstanceSecret.MISSING + "\n", ""))
                .when(incus).shellExec(eq(NAME), eq("sh"), eq("-c"),
                        eq(InstanceSecret.GUEST_CHECK + "\n" + InstanceSecret.generationCheck("")));

        InstanceLifecycle.ensureReady(incus, NAME, daemon.instance(NAME), MachineType.VM, RecordingOutput.QUIET);

        assertTrue(deliveries(daemon).stream().allMatch(c -> c.contains(SETS_HOSTNAME)));
    }

    @Test
    void aVmRestartedForItsAgentSetsItsHostname() {
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Running", Map.of());
        assertThrows(IncusException.class,
                () -> VmAgentRecovery.restartForAgent(daemon.clientWithShortReadyWait(), NAME, RecordingOutput.QUIET));
        assertTrue(deliveries(daemon).stream().allMatch(c -> c.contains(SETS_HOSTNAME)));
    }

    @Test
    void aVmBranchsSetupSetsItsHostname() {
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Running", Map.of());
        var incus = spy(daemon.client());
        var script = ArgumentCaptor.forClass(String[].class);
        doReturn("").when(incus).pollUntilReadyOutput(eq(NAME), anyInt(), anyMap(), script.capture());
        var prefetched = new RuntimeSetup.RuntimeConfig(null, false, null, null, null, null, "", null,
                daemon.instance(NAME));

        RuntimeSetup.setupRuntime(incus, NAME, NetworkMode.AIRGAP, prefetched, null, false, List.of(),
                RecordingOutput.QUIET);

        assertTrue(String.join(" ", script.getValue()).contains(SETS_HOSTNAME));
    }

    @Test
    void aNameThatIsNoHostnameIsNeverWritten() {
        var vm = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Running", Map.of()).instance(NAME);
        assertEquals("", GuestHostname.script("a'b", vm));
        assertEquals("", GuestHostname.script("-leading", vm));
        assertEquals("", GuestHostname.script("x".repeat(64), vm));
        assertEquals("{ " + SETS_HOSTNAME + "; } 2>/dev/null || true\n", GuestHostname.script(NAME, vm));
    }
}
