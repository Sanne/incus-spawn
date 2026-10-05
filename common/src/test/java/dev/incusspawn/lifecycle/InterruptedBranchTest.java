package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A branch is an instance from its copy on (#1036). {@code isx list -q}, completion and the bench
 * cleanups tell templates apart by the {@code base} type, and a copy carries its template's: a
 * branch interrupted before it is configured (Ctrl-C, a failed write) must not pass for a
 * template, or nothing lists it and it leaks.
 */
@ExtendWith(TempHome.class)
class InterruptedBranchTest {

    @Test
    void aBranchInterruptedRightAfterItsCopyIsAlreadyAnInstance() {
        var daemon = new FakeIncusDaemon()
                .container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE))
                // The branch's configuration write fails, as an interruption right after the copy would
                .refuseWritesContaining("limits.memory");
        var request = new BranchFlow.Request("tpl-dev", "dev-1", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());

        assertThrows(RuntimeException.class,
                () -> BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of())));

        assertNotNull(daemon.instance("dev-1"), "precondition: the copy was made");
        assertEquals(Metadata.TYPE_CLONE, daemon.instance("dev-1").path("config").path(Metadata.TYPE).asText(),
                "the copy itself carries the instance type");
        assertEquals(Metadata.TYPE_BASE, daemon.instance("tpl-dev").path("config").path(Metadata.TYPE).asText(),
                "the template keeps its own");
    }
}
