package dev.incusspawn.mcp;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.tui.InstanceLockManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IncusInstanceBackendTest {

    private final IncusClient incus = mock(IncusClient.class);
    private final IncusInstanceBackend backend = new IncusInstanceBackend(incus, mock(InstanceLockManager.class));

    @Test
    void onlyAnInstanceIncusSaysIsMissingReadsAsGone() {
        when(incus.instanceMetadataOrThrow("gone")).thenReturn(null);
        assertNull(backend.metadata("gone"));
    }

    @Test
    void aFailedReadIsNotMistakenForAGoneInstance() {
        // A 403 or 500 said nothing about the instance: reading it as gone abandoned live ones (#858).
        when(incus.instanceMetadataOrThrow("dev")).thenThrow(new IncusException("Failed to read instance 'dev' (HTTP 500)"));
        assertThrows(ToolError.class, () -> backend.metadata("dev"));
    }
}
