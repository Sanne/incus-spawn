package dev.incusspawn.command;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx destroy --owner} (#1720): an orchestrator deletes only its own instances, and only
 * once the user granted it in {@code drivers:}. A plain destroy of such an instance asks.
 */
class DestroyOwnedTest {

    private static final String GRANTED = "drivers:\n  orca:\n    destroy: true\n";

    private static FakeIncusDaemon daemon() {
        return new FakeIncusDaemon()
                .container("ws", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.OWNER, "orca", Metadata.OWNER_ID, "rt-1"))
                .container("mine", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE));
    }

    private record Owned(DestroyCommand command, String config) {}

    private static Owned owned(String config, String owner, String ownerId) {
        var command = new DestroyCommand();
        command.owner = owner;
        command.ownerId = ownerId;
        return new Owned(command, config);
    }

    private static int run(Owned owned, FakeIncusDaemon daemon, String target, RecordingOutput[] recorded) {
        var result = new int[1];
        recorded[0] = RecordingOutput.during(() -> {
            try {
                result[0] = owned.command().destroyOwned(daemon.client(), new StartStopCommandTest.Locks(),
                        Jjq.spawnConfig(owned.config()).drivers(), target).getResultValue();
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
        return result[0];
    }

    private static List<String> errors(RecordingOutput recorded) {
        return recorded.events(RecordingOutput.Kind.ERROR).stream().map(RecordingOutput.Event::msg).toList();
    }

    @Test
    void aGrantedOwnerDestroysItsOwnInstance() {
        var daemon = daemon();
        var recorded = new RecordingOutput[1];
        assertEquals(0, run(owned(GRANTED, "orca", "rt-1"), daemon, "ws", recorded));
        assertNull(daemon.instance("ws"));
        assertEquals(List.of("Destroyed ws"), recorded[0].events(RecordingOutput.Kind.SUCCESS).stream()
                .map(RecordingOutput.Event::msg).toList());
    }

    @Test
    void anInstanceAlreadyGoneIsSuccessSoADestroyCanBeRepeated() {
        var recorded = new RecordingOutput[1];
        assertEquals(0, run(owned(GRANTED, "orca", null), daemon(), "gone", recorded));
        assertEquals(List.of("gone is already gone"), recorded[0].events(RecordingOutput.Kind.SUCCESS).stream()
                .map(RecordingOutput.Event::msg).toList());
    }

    @Test
    void withoutTheGrantNothingIsDeleted() {
        for (var config : List.of("{}", "drivers:\n  orca:\n    destroy: false\n", "drivers:\n  orca:\n    destory: true\n",
                "drivers:\n  herdr:\n    destroy: true\n")) {
            var daemon = daemon();
            var recorded = new RecordingOutput[1];
            assertEquals(1, run(owned(config, "orca", null), daemon, "ws", recorded), config);
            assertNotNull(daemon.instance("ws"), config);
            assertEquals(List.of("config.yaml does not let orca destroy its instances."), errors(recorded[0]), config);
        }
    }

    @Test
    void anInstanceTheToolDoesNotOwnIsLeft() {
        var daemon = daemon();
        var recorded = new RecordingOutput[1];
        assertEquals(1, run(owned(GRANTED, "orca", null), daemon, "mine", recorded));
        assertEquals(1, run(owned(GRANTED, "orca", "rt-2"), daemon, "ws", recorded));
        assertNotNull(daemon.instance("mine"));
        assertNotNull(daemon.instance("ws"));
        assertEquals(List.of("did not destroy 'ws': orca does not own it under the id rt-2."), errors(recorded[0]));
        assertFalse(daemon.instance("ws").path("config").has(Metadata.PENDING_OP), "the deleting mark is taken back");
    }

    /** Deleted by another process between the first read and the mark: the retry it was is "already gone". */
    @Test
    void anInstanceDeletedMeanwhileIsAlreadyGone() {
        var daemon = daemon();
        daemon.writeAfterRead("ws", () -> daemon.client().delete("ws", true));
        var recorded = new RecordingOutput[1];
        assertEquals(0, run(owned(GRANTED, "orca", null), daemon, "ws", recorded));
        assertNull(daemon.instance("ws"));
        assertEquals(List.of("ws is already gone"), recorded[0].events(RecordingOutput.Kind.SUCCESS).stream()
                .map(RecordingOutput.Event::msg).toList());
        assertEquals(List.of(), errors(recorded[0]));
    }

    /**
     * Another process changes the owner's handle between the first read and the deleting mark (a
     * user's adoption of the instance, say): the check under the mark sees it, nothing is deleted,
     * the mark is taken back, and the tool is told no (review of #1820).
     */
    @Test
    void anOwnerChangedBeforeTheMarkIsSeenUnderItAndNothingIsDeleted() {
        var daemon = daemon();
        daemon.writeAfterRead("ws", () -> daemon.client().configSet("ws", Metadata.OWNER_ID, "rt-other"));
        var recorded = new RecordingOutput[1];
        assertEquals(1, run(owned(GRANTED, "orca", "rt-1"), daemon, "ws", recorded));
        assertNotNull(daemon.instance("ws"), "nothing is deleted");
        var config = daemon.instance("ws").path("config");
        assertFalse(config.has(Metadata.PENDING_OP), "the deleting mark is taken back: " + config);
        assertEquals("rt-other", config.pathText(Metadata.OWNER_ID, ""));
        assertEquals(List.of("did not destroy 'ws': orca does not own it under the id rt-1."), errors(recorded[0]));
    }

    /** Refusing an instance that is not the tool's costs one read, and marks nothing. */
    @Test
    void aRefusalCostsOneReadAndWritesNothing() {
        var daemon = daemon();
        daemon.clearRequests();
        var recorded = new RecordingOutput[1];
        assertEquals(1, run(owned(GRANTED, "orca", null), daemon, "mine", recorded));
        assertEquals(List.of("GET /1.0/instances/mine"), daemon.requests());
    }

    @Test
    void anOwnerOrIdThatCannotBeAStampIsRefusedBeforeAnything() {
        var daemon = daemon();
        var recorded = new RecordingOutput[1];
        assertEquals(1, run(owned(GRANTED, "mcp", null), daemon, "ws", recorded));
        assertEquals(1, run(owned(GRANTED, "orca", "$(id)"), daemon, "ws", recorded));
        assertEquals(1, run(owned(GRANTED, null, "rt-1"), daemon, "ws", recorded));
        assertNotNull(daemon.instance("ws"));
        assertEquals(List.of("--owner-id needs --owner."), errors(recorded[0]));
    }

    /** A person destroying a tool's instance is asked; a script without a terminal must say --skip-confirmation. */
    @Test
    void aPlainDestroyOfAnOwnedInstanceAsksAndFailsClosedWithoutATerminal() {
        var daemon = daemon();
        var command = new DestroyCommand();
        var refused = assertThrows(BaseCommand.NoTerminalException.class,
                () -> RecordingOutput.during(() -> command.destroySingle(daemon.client(), new StartStopCommandTest.Locks(), "ws")));
        assertTrue(refused.getMessage().contains("--skip-confirmation"), refused.getMessage());
        assertNotNull(daemon.instance("ws"));

        command.skipConfirmation = true;
        RecordingOutput.during(() -> assertEquals(0,
                command.destroySingle(daemon.client(), new StartStopCommandTest.Locks(), "ws").getResultValue()));
        assertNull(daemon.instance("ws"));
        // An instance nobody owns is destroyed without asking, as before
        RecordingOutput.during(() -> assertEquals(0,
                new DestroyCommand().destroySingle(daemon.client(), new StartStopCommandTest.Locks(), "mine").getResultValue()));
        assertNull(daemon.instance("mine"));
    }
}
