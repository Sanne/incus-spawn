package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.util.SudoPrompt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A live step around one of init's captured sudo commands keeps its line when sudo does not ask,
 * so the step shows one line for its outcome, not a stale {@code … label} above its mark (#2595).
 * No host command starts: the runner is replaced.
 */
@ExtendWith(IsolatedHome.class)
class InitSudoPromptTest {

    private SudoPrompt.Runner previous;
    private final List<String> ran = new ArrayList<>();

    @BeforeEach
    void outsideTheFirstRunsBlock() {
        InitCommand.hostOutputToLog = false;
    }

    @AfterEach
    void restore() {
        InitCommand.hostOutputToLog = false;
        if (previous != null) SudoPrompt.replaceRunner(previous);
    }

    private void sudoAsks(boolean asks) {
        previous = SudoPrompt.replaceRunner((command, setup, cLocale, merged) -> {
            ran.add(String.join(" ", command));
            return asks && command.contains("-n")
                    ? new SudoPrompt.Ran(1, "sudo: a password is required\n") : new SudoPrompt.Ran(0, "");
        });
    }

    @Test
    void aQuietSudoThatDoesNotAskLeavesTheLineLive() {
        sudoAsks(false);
        var init = new InitCommand();
        var out = RecordingOutput.during(() -> assertEquals(0, init.runHostQuiet("sudo", "true")));
        assertTrue(out.events(RecordingOutput.Kind.RELEASE_TERMINAL).isEmpty(), out.events().toString());
        assertEquals(List.of("sudo -n true"), ran);
    }

    @Test
    void aQuietSudoThatAsksFreezesTheLineFirst() {
        sudoAsks(true);
        var init = new InitCommand();
        var out = RecordingOutput.during(() -> assertEquals(0, init.runHostQuiet("sudo", "true")));
        assertEquals(List.of("sudo -n true", "sudo true"), ran);
        assertEquals(1, out.events(RecordingOutput.Kind.RELEASE_TERMINAL).size());
    }

    /** In the first run's block, a sudo whose credentials are cached runs once and no line freezes. */
    @Test
    void theFirstRunsBlockLeavesTheLineLiveToo() {
        sudoAsks(false);
        InitCommand.hostOutputToLog = true;
        var out = RecordingOutput.during(() -> assertEquals(0, InitCommand.runHost("sudo", "true")));
        assertTrue(out.events(RecordingOutput.Kind.RELEASE_TERMINAL).isEmpty(), out.events().toString());
        assertEquals(List.of("sudo -n true"), ran);
    }

    /**
     * Outside the first run too, a host command's output is captured, so a sudo whose credentials
     * are cached runs once and the live step around it keeps its line, as {@code Starting Incus} does.
     */
    @Test
    void runHostOutsideTheFirstRunLeavesTheLineLive() {
        sudoAsks(false);
        var out = RecordingOutput.during(() -> assertEquals(0, InitCommand.runHost("sudo", "true")));
        assertTrue(out.events(RecordingOutput.Kind.RELEASE_TERMINAL).isEmpty(), out.events().toString());
        assertEquals(List.of("sudo -n true"), ran);
    }

    @Test
    void runHostOutsideTheFirstRunFreezesTheLineWhenSudoAsks() {
        sudoAsks(true);
        var out = RecordingOutput.during(() -> assertEquals(0, InitCommand.runHost("sudo", "true")));
        assertEquals(List.of("sudo -n true", "sudo true"), ran);
        assertEquals(1, out.events(RecordingOutput.Kind.RELEASE_TERMINAL).size());
    }

    /** The CoW pool's create keeps the line live when its sudo does not ask, and still returns stderr to word a failure. */
    @Test
    void thePoolCreateLeavesTheLineLive() {
        previous = SudoPrompt.replaceRunner((command, setup, cLocale, merged) -> {
            ran.add(String.join(" ", command));
            return new SudoPrompt.Ran(1, "", "Error: loop device missing\n");
        });
        var attempt = new CowPoolBacking.Attempt[1];
        var out = RecordingOutput.during(() -> attempt[0] = CowPoolBacking.runCapturingStderr(CowPoolBacking.subvolumeCommand()));
        assertTrue(out.events(RecordingOutput.Kind.RELEASE_TERMINAL).isEmpty(), out.events().toString());
        assertEquals(1, ran.size(), ran.toString());
        assertTrue(ran.getFirst().startsWith("sudo -n incus storage create"), ran.toString());
        assertEquals(1, attempt[0].exitCode());
        assertEquals("Error: loop device missing\n", attempt[0].stderr());
    }
}
