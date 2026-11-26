package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceDestroyerTest {

    /** What isx destroy says after its step, one line per host tool, as the first start said it added it. */
    @Test
    void theRemovalIsSaidOnceForEachHostTool() {
        var recorded = RecordingOutput.during(() ->
                InstanceDestroyer.saidRemoved("ws", List.of("herdr", "Rex"), BuildOutput.bound()));
        assertEquals(List.of(
                new RecordingOutput.Event(RecordingOutput.Kind.STEP, "Removed ws from herdr."),
                new RecordingOutput.Event(RecordingOutput.Kind.STEP, "Removed ws from Rex.")),
                recorded.events());
    }

    @Test
    void aGuardedDeleteReadsUnderItsMarkAndTakesTheMarkBackWhenRefused() {
        var daemon = new FakeIncusDaemon().container("orphan", Map.of(Metadata.MCP_SESSION, "3-300"));
        daemon.clearRequests();

        assertFalse(InstanceDestroyer.deleteHeldIf(daemon.client(), "orphan",
                instance -> "2-200".equals(instance.path("config").path(Metadata.MCP_SESSION).asText())).isPresent());

        var requests = daemon.requests().stream().filter(r -> !r.startsWith("GET /1.0/operations/")).toList();
        assertEquals(List.of("PATCH /1.0/instances/orphan", "GET /1.0/instances/orphan", "PATCH /1.0/instances/orphan"),
                requests, "mark, then read, then take the mark back");
        var config = daemon.instance("orphan").path("config");
        assertTrue(config.path(Metadata.PENDING_OP).asText("").isEmpty(), config.toString());
        assertEquals("3-300", config.path(Metadata.MCP_SESSION).asText());
    }

    @Test
    void aMarkThatCannotBeWrittenDeletesNothing() {
        var daemon = new FakeIncusDaemon();
        assertThrows(IncusException.class, () -> InstanceDestroyer.deleteHeldIf(daemon.client(), "gone", config -> true));
    }

    @Test
    void aFailedReadUnderTheMarkTakesTheMarkBack() {
        var daemon = new FakeIncusDaemon().container("orphan", Map.of(Metadata.MCP_SESSION, "2-200"))
                .failInstanceReads(500);
        assertThrows(IncusException.class, () -> InstanceDestroyer.deleteHeldIf(daemon.client(), "orphan", config -> true));
        daemon.failInstanceReads(0);
        // Left behind, the mark would leave the orphan busy for good: never reaped, never adopted.
        assertTrue(daemon.instance("orphan").path("config").path(Metadata.PENDING_OP).asText("").isEmpty());
    }
}
