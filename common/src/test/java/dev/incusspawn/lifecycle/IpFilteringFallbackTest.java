package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Starting an instance whose IP spoofing protection the host cannot enforce.
 *
 * <p>Found by running against a real Incus: setting {@code security.ipv4_filtering} succeeds
 * even where it cannot be enforced, and the failure only appears when the instance starts and
 * Incus installs the per-NIC rules. Without the fallback, such a host would accept every branch
 * and then be unable to start any of them.
 */
class IpFilteringFallbackTest {

    private static final String NAME = "box";

    /** The message a host without the ebtables filter table actually produces. */
    private static final String EBTABLES_FAILURE =
            "Operation failed: Failed to start device \"eth0\": Failed to run: "
                    + "ebtables -t filter -A INPUT -s ! 10:66:6a:f9:3d:b3 -i veth0 -j DROP: "
                    + "exit status 255 (The kernel doesn't support the ebtables 'filter' table.)";

    private static IncusClient withFilteringOn() {
        var incus = mock(IncusClient.class);
        when(incus.findNic(eq(NAME), anyString()))
                .thenReturn(new IncusClient.NicDevice("eth0",
                        Map.of("security.ipv4_filtering", "true")));
        return incus;
    }

    @Test
    void startsOnceFilteringIsDroppedOnAHostThatCannotEnforceIt() {
        var incus = withFilteringOn();
        doThrow(new IncusException(EBTABLES_FAILURE)).doNothing().when(incus).start(NAME);

        InstanceLifecycle.startInstance(incus, NAME);

        verify(incus).deviceConfigSet(NAME, "eth0", "security.ipv4_filtering", "false");
        verify(incus, times(2)).start(NAME);
    }

    /**
     * The fallback strips a security setting, so it must not trigger on anything else -- a
     * disk, a GPU, an out-of-memory start failure. Those have to surface as themselves.
     */
    @Test
    void anUnrelatedStartFailureIsNotSilentlyTurnedIntoALossOfProtection() {
        var incus = withFilteringOn();
        doThrow(new IncusException("Failed to start device \"gpu\": no such device"))
                .when(incus).start(NAME);

        assertThrows(IncusException.class, () -> InstanceLifecycle.startInstance(incus, NAME));

        verify(incus, never()).deviceConfigSet(any(), any(), eq("security.ipv4_filtering"), any());
        verify(incus, times(1)).start(NAME);
    }

    @Test
    void aStartFailureWithFilteringAlreadyOffIsNotRetried() {
        var incus = mock(IncusClient.class);
        when(incus.findNic(eq(NAME), anyString()))
                .thenReturn(new IncusClient.NicDevice("eth0", Map.of()));
        doThrow(new IncusException(EBTABLES_FAILURE)).when(incus).start(NAME);

        assertThrows(IncusException.class, () -> InstanceLifecycle.startInstance(incus, NAME));
        verify(incus, times(1)).start(NAME);
    }

    @Test
    void aNormalStartDoesNotTouchTheNic() {
        var incus = mock(IncusClient.class);
        InstanceLifecycle.startInstance(incus, NAME);

        verify(incus).start(NAME);
        verify(incus, never()).findNic(any(), any());
    }

    @Test
    void recognisesTheFilteringFailureAndOnlyThat() {
        assertTrue(InstanceLifecycle.looksLikeIpFilteringFailure(
                new IncusException(EBTABLES_FAILURE)));
        // Nested, as Incus exceptions often arrive.
        assertTrue(InstanceLifecycle.looksLikeIpFilteringFailure(
                new RuntimeException("wrapped", new IncusException(EBTABLES_FAILURE))));
        assertFalse(InstanceLifecycle.looksLikeIpFilteringFailure(
                new IncusException("Failed to start device \"root\": disk full")));
        assertFalse(InstanceLifecycle.looksLikeIpFilteringFailure(
                new IncusException("ebtables is unhappy but no device is named")));
        assertFalse(InstanceLifecycle.looksLikeIpFilteringFailure(new IncusException(null)));
    }
}
