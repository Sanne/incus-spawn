package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An operation {@code /wait} that times out comes back HTTP 200 with the operation still
 * {@code Running} (#1089): that is not completion, so the wait is asked again.
 */
class OperationWaitTest {

    private static long waits(FakeIncusDaemon daemon) {
        return daemon.requests().stream().filter(r -> r.startsWith("GET /1.0/operations/")).count();
    }

    @Test
    void anOperationStillRunningWhenItsWaitTimesOutIsWaitedForAgain() {
        var daemon = new FakeIncusDaemon().container("src", Map.of()).operationsAnswer("Running", "Running");
        daemon.client().copy("src", "dst");
        assertEquals(3, waits(daemon), String.join("\n", daemon.requests()));
    }

    @Test
    void aFastOperationCostsOneWait() {
        var daemon = new FakeIncusDaemon().container("src", Map.of());
        daemon.client().copy("src", "dst");
        assertEquals(1, waits(daemon), String.join("\n", daemon.requests()));
    }

    @Test
    void anOperationStillRunningAtTheCeilingFails() {
        var daemon = new FakeIncusDaemon().container("src", Map.of()).operationsAnswer("Running");
        var api = new IncusApi(daemon);
        api.operationWaitCeiling(Duration.ZERO);
        var e = assertThrows(IncusException.class, () -> new IncusClient(api).copy("src", "dst"));
        assertTrue(e.getMessage().contains("still running"), e.getMessage());
        assertEquals(1, waits(daemon), String.join("\n", daemon.requests()));
    }

    @Test
    void aCancelledOperationFails() {
        var daemon = new FakeIncusDaemon().container("src", Map.of()).operationsAnswer("Cancelling", "Cancelled");
        var e = assertThrows(IncusException.class, () -> daemon.client().copy("src", "dst"));
        assertTrue(e.getMessage().contains("cancelled"), e.getMessage());
        assertEquals(2, waits(daemon), String.join("\n", daemon.requests()));
    }
}
