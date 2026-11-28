package dev.incusspawn.command;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tui.InstanceLockManager;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx start} and {@code isx stop} (#1720): what a script or an orchestrator uses to resume and
 * suspend an instance without a shell. A start is {@code isx shell}'s preparation under the
 * instance's lock; a stop is marked, so another isx process sees it, and repeating one is harmless.
 */
class StartStopCommandTest {

    /** Locks held in memory: a name already held is refused, as another process's lock would be. */
    static final class Locks implements InstanceLockManager {
        final Set<String> held = new HashSet<>();
        final List<String> taken = new ArrayList<>();

        @Override
        public synchronized Optional<LockHandle> tryAcquire(String instanceName, String operation) {
            if (!held.add(instanceName)) return Optional.empty();
            taken.add(instanceName + ":" + operation);
            return Optional.of(() -> { synchronized (this) { held.remove(instanceName); } });
        }

        @Override
        public synchronized boolean isHeldByOther(String instanceName) {
            return held.contains(instanceName);
        }
    }

    @Test
    void startPreparesTheInstanceUnderItsStartingLock() throws Exception {
        var daemon = new FakeIncusDaemon().container("dev", Map.of());
        var locks = new Locks();
        var prepared = new ArrayList<String>();
        var command = new StartCommand();
        command.name = "dev";
        command.preparer = (incus, name, out) -> {
            assertTrue(locks.isHeldByOther(name), "prepared under the lock");
            prepared.add(name);
        };

        var recorded = RecordingOutput.during(() -> assertEquals(0,
                assertDoesNotThrow(() -> command.start(daemon.client(), locks)).getResultValue()));

        assertEquals(List.of("dev"), prepared);
        assertEquals(List.of("dev:starting"), locks.taken);
        assertFalse(locks.isHeldByOther("dev"), "released after");
        assertEquals(List.of("dev ready"), recorded.events(RecordingOutput.Kind.SUCCESS).stream()
                .map(RecordingOutput.Event::msg).toList());
    }

    @Test
    void aRefusedStartFailsWithTheRefusalAsItsError() {
        var daemon = new FakeIncusDaemon();
        var command = new StartCommand();
        command.name = "gone";
        command.preparer = InstanceStart.Preparer.FOR_USE;

        var recorded = RecordingOutput.during(() -> assertEquals(1,
                assertDoesNotThrow(() -> command.start(daemon.client(), new Locks())).getResultValue()));

        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR,
                "no instance named 'gone' found.", "Run 'isx list' to see available instances.")),
                recorded.events(RecordingOutput.Kind.ERROR));
    }

    @Test
    void aStartWaitsForAnotherHolderAndGivesUpOnlyAfterItsWait() {
        var daemon = new FakeIncusDaemon().container("dev", Map.of());
        var locks = new Locks();
        locks.held.add("dev");
        var recorded = RecordingOutput.during(() -> {
            var refused = assertThrows(InstancePrep.Refused.class, () -> InstanceStart.start(daemon.client(), locks, "dev",
                    dev.incusspawn.util.BuildOutput.bound(), (i, n, o) -> fail("never prepared without the lock"),
                    Duration.ofMillis(300)));
            assertEquals("'dev' is still in use by another isx process after 0 seconds.\n" + InstanceStart.BUSY_ADVICE, refused.getMessage());
        });
        assertEquals(List.of("Waiting for another isx process using dev"),
                recorded.events(RecordingOutput.Kind.STEP_START).stream().map(RecordingOutput.Event::msg).toList(),
                "said once, however long it waits");
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path dir;

    @Test
    void stopStopsARunningInstanceUnderItsMarkAndClearsTheMark() {
        var daemon = new FakeIncusDaemon().instance("dev", "container", "Running", Map.of())
                .createdAt("dev", "2026-11-01T00:00:00Z").servesExecs();
        var locks = new Locks();
        var command = new StopCommand();
        command.name = "dev";
        command.kept = () -> new KeptReads(dir);

        RecordingOutput.during(() -> assertEquals(0, assertDoesNotThrow(() -> command.stop(daemon.client(), locks)).getResultValue()));

        assertEquals("Stopped", daemon.instance("dev").path("status").asText());
        assertEquals(List.of("dev:" + Metadata.OP_STOPPING), locks.taken);
        assertFalse(daemon.instance("dev").path("config").has(Metadata.PENDING_OP), "the mark is cleared");
        // Marked, read back, stopped, unmarked: the mark is on while the stop runs.
        var requests = daemon.requests().stream().filter(r -> !r.startsWith("GET /1.0/operations")).toList();
        // The guest is read just before it stops (#2752), with no request of its own besides the exec
        var asked = requests.stream().filter(r -> !r.startsWith("GET /1.0/instances/dev/logs") && !r.contains("/websocket")).toList();
        assertEquals(List.of("GET /1.0/instances/dev", "PATCH /1.0/instances/dev", "GET /1.0/instances/dev",
                "POST /1.0/instances/dev/exec", "PUT /1.0/instances/dev/state", "PATCH /1.0/instances/dev"), asked);
    }

    @Test
    void aStopKeepsTheGuestsLastReadingForTheOverview() {
        var daemon = new FakeIncusDaemon().instance("dev", "container", "Running", Map.of())
                .createdAt("dev", "2026-11-01T00:00:00Z")
                .execOutput(WorkState.HEADER, WorkState.HEADER + "\nrepo 4 0 0 app\nend\n");
        var command = new StopCommand();
        command.name = "dev";
        command.kept = () -> new KeptReads(dir);

        RecordingOutput.during(() -> assertEquals(0, assertDoesNotThrow(() -> command.stop(daemon.client(), new Locks())).getResultValue()));

        assertEquals("Stopped", daemon.instance("dev").path("status").asText());
        var kept = new KeptReads(dir).get("dev", "2026-11-01T00:00:00Z");
        assertNotNull(kept, "read before the stop");
        assertEquals(4, kept.work().uncommitted());
    }

    @Test
    void stoppingAStoppedInstanceSucceedsWithoutAStop() {
        var daemon = new FakeIncusDaemon().container("dev", Map.of());
        var command = new StopCommand();
        command.name = "dev";

        var recorded = RecordingOutput.during(() -> assertEquals(0, assertDoesNotThrow(() -> command.stop(daemon.client(), new Locks())).getResultValue()));

        assertEquals(List.of(), daemon.stateActions());
        assertFalse(daemon.instance("dev").path("config").has(Metadata.PENDING_OP));
        assertEquals(List.of("dev is already stopped"), recorded.events(RecordingOutput.Kind.SUCCESS).stream()
                .map(RecordingOutput.Event::msg).toList());
    }

    @Test
    void aStopRefusesAMissingOrLockedInstance() {
        var daemon = new FakeIncusDaemon().instance("dev", "container", "Running", Map.of());
        var locks = new Locks();
        locks.held.add("dev");
        var busy = new StopCommand();
        busy.name = "dev";
        busy.wait = Duration.ofMillis(100);
        var gone = new StopCommand();
        gone.name = "gone";

        var recorded = RecordingOutput.during(() -> {
            assertEquals(1, assertDoesNotThrow(() -> busy.stop(daemon.client(), locks)).getResultValue());
            assertEquals(1, assertDoesNotThrow(() -> gone.stop(daemon.client(), locks)).getResultValue());
        });

        assertEquals("Running", daemon.instance("dev").path("status").asText());
        assertEquals(List.of("'dev' is still in use by another isx process after 0 seconds.", "no instance named 'gone' found."),
                recorded.events(RecordingOutput.Kind.ERROR).stream().map(RecordingOutput.Event::msg).toList());
    }

    /** A frozen instance cannot shut down: an error that says so, never "already stopped". */
    @Test
    void aFrozenInstanceIsAnErrorNotTakenForStopped() {
        var daemon = new FakeIncusDaemon().instance("dev", "container", "Frozen", Map.of());
        var command = new StopCommand();
        command.name = "dev";

        var recorded = RecordingOutput.during(() -> assertEquals(1,
                assertDoesNotThrow(() -> command.stop(daemon.client(), new Locks())).getResultValue()));

        assertEquals("Frozen", daemon.instance("dev").path("status").asText());
        assertFalse(daemon.instance("dev").path("config").has(Metadata.PENDING_OP));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR,
                "dev is frozen, not running, so it cannot shut down.", "Resume it first, then stop it.")),
                recorded.events(RecordingOutput.Kind.ERROR));
    }
}
