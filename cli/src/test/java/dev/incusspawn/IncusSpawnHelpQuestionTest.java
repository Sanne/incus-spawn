package dev.incusspawn;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What {@code isx --help <words>} says: a question for {@code isx ask}, not a command (#1703). */
class IncusSpawnHelpQuestionTest {

    /** One error with what to run instead under it, which a recorder sees, never raw stderr lines. */
    @Test
    void aHelpQuestionPointsToIsxAsk() {
        var exit = new int[1];
        var recorded = RecordingOutput.during(() -> exit[0] = new IncusSpawn().run("--help", "how", "do", "I", "branch"));
        assertEquals(1, exit[0]);
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR,
                "--help takes no question.",
                "Did you mean: isx ask how do I branch",
                "Use 'isx ask <question>' to ask an AI model (uses AI tokens from your account).",
                "Use 'isx --help' for command usage.")), recorded.events(RecordingOutput.Kind.ERROR));
    }
}
