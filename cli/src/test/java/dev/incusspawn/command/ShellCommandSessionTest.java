package dev.incusspawn.command;

import dev.incusspawn.incus.ShellSession;
import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.util.RecordingOutput.Event;
import dev.incusspawn.util.RecordingOutput.Kind;
import org.aesh.command.CommandResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code isx shell --session} and {@code --no-attach} (#2059): which session they ask for, and what they refuse. */
class ShellCommandSessionTest {

    private static ShellSession session(String session, boolean noAttach, boolean task) {
        return ShellCommand.shellSession(session, noAttach, task, RecordingOutput.FAIL_ON_WARN);
    }

    @Test
    void eachOptionAsksForItsSession() {
        assertEquals(ShellSession.DEFAULT, session(null, false, false));
        assertEquals(ShellSession.named("work"), session("work", false, false));
        assertEquals(ShellSession.NONE, session(null, true, false));
        assertEquals(ShellSession.DEFAULT, session(null, false, true));
    }

    /**
     * Refused in the command itself, before it needs a terminal or reaches Incus: neither is
     * here, so a check made after either would throw instead.
     */
    @Test
    void aBadSessionIsRefusedBeforeAnythingElse() throws Exception {
        for (var refused : List.of(
                new String[] {"a.b", null, null},
                new String[] {"-x", null, null},
                new String[] {"../x", null, null},
                new String[] {"x".repeat(33), null, null},
                new String[] {"work", "no-attach", null},
                new String[] {"work", null, "task"},
                new String[] {null, "no-attach", "task"})) {
            var command = new ShellCommand();
            command.name = "box";
            command.session = refused[0];
            command.noAttach = refused[1] != null;
            command.task = refused[2] != null;
            var result = new CommandResult[1];
            var said = RecordingOutput.during(() -> result[0] = command.doExecute());
            assertEquals(1, result[0].getResultValue(), String.join(" ", java.util.Arrays.asList(refused)));
            assertEquals(1, said.events().size(), said.events().toString());
            assertEquals(Kind.ERROR, said.events().getFirst().kind());
        }
    }

    @Test
    void aBadNameSaysWhatANameIs() {
        var out = new RecordingOutput();
        assertNull(ShellCommand.shellSession("a.b", false, false, out));
        assertEquals(List.of(new Event(Kind.ERROR, "'a.b' is not a session name.",
                "A session name is 1 to 32 letters, digits, '-' or '_', and does not start with '-'.")), out.events());
    }
}
