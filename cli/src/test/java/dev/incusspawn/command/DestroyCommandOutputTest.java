package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What {@code isx destroy} reports when its arguments name nothing it can act on (#1703). */
class DestroyCommandOutputTest {

    private static List<String> destroy(String name, boolean allTemplates, boolean allInstances) throws Exception {
        var command = new DestroyCommand();
        command.name = name;
        command.allTemplates = allTemplates;
        command.allInstances = allInstances;
        var recorded = RecordingOutput.during(() -> assertEquals(1, command.execute(null).getResultValue()));
        return recorded.events(RecordingOutput.Kind.ERROR).stream().map(RecordingOutput.Event::msg).toList();
    }

    /** Each refusal is one error, which the TUI's log and a recorder keep, not a raw "Error:" line on stderr. */
    @Test
    void conflictingArgumentsAreAnError() throws Exception {
        assertEquals(List.of("give --all-templates or --all-instances, not both."),
                destroy(null, true, true));
        assertEquals(List.of("give a name or --all-templates / --all-instances, not both."),
                destroy("box", true, false));
        assertEquals(List.of("give the name of what to destroy, or --all-templates / --all-instances."),
                destroy(null, false, false));
    }

    /** A bulk destroy that left something behind, which fails the command, ends on an error. */
    @Test
    void aBulkDestroyThatLeftSomethingBehindEndsOnAnError() {
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.SUCCESS, "Destroyed 2 instances")),
                summarized(new DestroyCommand.DestroyResult(2, 0, 0)));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.SUCCESS, "Destroyed 1 instance")),
                summarized(new DestroyCommand.DestroyResult(1, 0, 0)));
        var busy = summarized(new DestroyCommand.DestroyResult(1, 1, 0));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR,
                "could not destroy 1 of 2 instances: 1 in use by another isx process.", InstanceStart.BUSY_ADVICE)), busy);
        var both = summarized(new DestroyCommand.DestroyResult(1, 1, 1));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR,
                "could not destroy 2 of 3 instances: 1 in use by another isx process, 1 failed, as shown above.",
                InstanceStart.BUSY_ADVICE)), both);
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR,
                "could not destroy 1 of 1 instance: 1 failed, as shown above.")),
                summarized(new DestroyCommand.DestroyResult(0, 0, 1)), "no advice about a busy one when none was");
    }

    private static List<RecordingOutput.Event> summarized(DestroyCommand.DestroyResult result) {
        return RecordingOutput.during(() -> DestroyCommand.summarize(result, "instance")).events();
    }
}
