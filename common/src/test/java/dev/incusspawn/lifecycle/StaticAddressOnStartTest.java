package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * A stopped container started by {@code isx shell} or {@code isx run} has its static address
 * before the shell is handed over, as a branch does (#801). Its readiness probe waited for exec
 * alone, so a shell could come up before networkd had configured eth0, with no way out.
 */
@ExtendWith(TempHome.class)
class StaticAddressOnStartTest {

    private static final String NAME = "t-addr";

    @Test
    void aContainerStartAppliesItsAddressFirstInTheReadinessProbe() {
        var script = probe(new FakeIncusDaemon().container(NAME, Map.of()), MachineType.CONTAINER);
        // First, so a secret delivery waiting for /run or an MCP registration does not delay it
        assertTrue(script.startsWith(InstanceNetwork.APPLY_STATIC_ADDRESS + "\n"), script);
    }

    @Test
    void aVmOrAnAirgappedContainerIsLeftAlone() {
        // A VM's NIC is not eth0 (#997), and DHCP gives it the address before its agent answers
        var vm = probe(new FakeIncusDaemon().instance(NAME, "virtual-machine", "Stopped", Map.of()), MachineType.VM);
        assertFalse(vm.contains(InstanceNetwork.APPLY_STATIC_ADDRESS), vm);
        var airgapped = probe(new FakeIncusDaemon().container(NAME,
                Map.of(Metadata.NETWORK_MODE, NetworkMode.AIRGAP.name())), MachineType.CONTAINER);
        assertFalse(airgapped.contains(InstanceNetwork.APPLY_STATIC_ADDRESS), airgapped);
    }

    /** Start {@code daemon}'s instance for use, and return the script its readiness probe ran. */
    private static String probe(FakeIncusDaemon daemon, MachineType machineType) {
        var incus = spy(daemon.ipFiltering(NAME, "true").client());
        var script = ArgumentCaptor.forClass(String.class);
        doReturn("").when(incus).waitForReady(eq(NAME), eq(machineType), script.capture(), anyMap());
        InstanceLifecycle.startForUse(incus, NAME, machineType, RecordingOutput.QUIET);
        return script.getValue();
    }
}
