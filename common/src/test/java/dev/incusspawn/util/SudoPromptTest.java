package dev.incusspawn.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A live step's line is frozen before a captured sudo only when that sudo asks for a password,
 * told by running the command itself under {@code sudo -n} (#2595).
 */
class SudoPromptTest {

    private SudoPrompt.Runner previous;
    private final List<String> ran = new ArrayList<>();

    @AfterEach
    void restore() {
        if (previous != null) SudoPrompt.replaceRunner(previous);
    }

    /** Records each command, with {@code C} for one run in the C locale, and answers with {@code answer}. */
    private void answering(Function<List<String>, SudoPrompt.Ran> answer) {
        previous = SudoPrompt.replaceRunner((command, setup, cLocale, merged) -> {
            ran.add((cLocale ? "C " : "") + String.join(" ", command));
            return answer.apply(command);
        });
    }

    @Test
    void aSudoThatDoesNotAskRunsOnceAndLeavesTheLineLive() throws Exception {
        answering(command -> new SudoPrompt.Ran(0, "ok"));
        var out = RecordingOutput.during(() -> assertEquals(new SudoPrompt.Ran(0, "ok"),
                SudoPrompt.run(pb -> {}, "sudo", "systemctl", "restart", "x")));
        assertEquals(List.of("C sudo -n systemctl restart x"), ran);
        assertTrue(out.events(RecordingOutput.Kind.RELEASE_TERMINAL).isEmpty());
    }

    @Test
    void aSudoThatWouldAskFreezesTheLineAndRunsAgain() throws Exception {
        answering(command -> command.contains("-n")
                ? new SudoPrompt.Ran(1, "sudo: a password is required\n") : new SudoPrompt.Ran(0, "done"));
        var out = RecordingOutput.during(() -> assertEquals(new SudoPrompt.Ran(0, "done"),
                SudoPrompt.run(pb -> {}, "sudo", "cat", "/etc/x")));
        assertEquals(List.of("C sudo -n cat /etc/x", "sudo cat /etc/x"), ran);
        assertEquals(1, out.events(RecordingOutput.Kind.RELEASE_TERMINAL).size());
    }

    @Test
    void aSudoCommandThatFailsIsNotRunAgain() throws Exception {
        answering(command -> new SudoPrompt.Ran(1, "Failed to enable linger: Access denied\n"));
        var out = RecordingOutput.during(() -> assertEquals(1, SudoPrompt.run(pb -> {}, "sudo", "loginctl").exit()));
        assertEquals(List.of("C sudo -n loginctl"), ran);
        assertTrue(out.events(RecordingOutput.Kind.RELEASE_TERMINAL).isEmpty());
    }

    @Test
    void aCommandWithoutSudoRunsAsGiven() throws Exception {
        answering(command -> new SudoPrompt.Ran(0, ""));
        var out = RecordingOutput.during(() -> SudoPrompt.run(pb -> {}, "systemctl", "is-active", "x"));
        assertEquals(List.of("systemctl is-active x"), ran);
        assertTrue(out.events(RecordingOutput.Kind.RELEASE_TERMINAL).isEmpty());
    }

    /**
     * A sudo that could not ask says so in these words, which isx reads in the C locale: the
     * same message translated would be taken for a command that failed, and the person never
     * asked for the password.
     */
    @Test
    void aSudoThatWouldAskIsToldFromACommandThatFailed() {
        assertTrue(SudoPrompt.needsPassword("sudo: a password is required\n"));
        assertFalse(SudoPrompt.needsPassword("Failed to enable linger: Access denied\n"));
        assertFalse(SudoPrompt.needsPassword(""));
        // What a German sudo prints, which is why the probe runs with LC_ALL=C
        assertFalse(SudoPrompt.needsPassword("sudo: Ein Passwort ist notwendig\n"));
        // sudo-rs, Ubuntu's sudo since 25.10, says it in other words
        assertTrue(SudoPrompt.needsPassword("sudo: interactive authentication is required\n"));
        // A command that ran and printed the phrase itself is not sudo asking
        assertFalse(SudoPrompt.needsPassword("ssh: a password is required\n"));
    }

    /** A read keeps sudo's stderr apart from the content, and still tells a sudo that would ask. */
    @Test
    void aReadKeepsStderrApartAndStillAsks() throws Exception {
        var merged = new ArrayList<Boolean>();
        previous = SudoPrompt.replaceRunner((command, setup, cLocale, isMerged) -> {
            merged.add(isMerged);
            ran.add(String.join(" ", command));
            return command.contains("-n")
                    ? new SudoPrompt.Ran(1, "", "sudo: a password is required\n") : new SudoPrompt.Ran(0, "<rules/>", "");
        });
        var out = RecordingOutput.during(() -> assertEquals("<rules/>",
                SudoPrompt.read(pb -> {}, "sudo", "cat", "/etc/x").output()));
        assertEquals(List.of("sudo -n cat /etc/x", "sudo cat /etc/x"), ran);
        assertEquals(List.of(false, false), merged);
        assertEquals(1, out.events(RecordingOutput.Kind.RELEASE_TERMINAL).size());
    }

    /** The real runner keeps stderr apart for a read and merges it for a run; no sudo is started. */
    @Test
    void theRealRunnerKeepsStderrApartOnlyForARead() throws Exception {
        var read = SudoPrompt.read(pb -> {}, "sh", "-c", "echo out; echo err >&2");
        assertEquals(new SudoPrompt.Ran(0, "out\n", "err\n"), read);
        var run = SudoPrompt.run(pb -> {}, "sh", "-c", "echo out; echo err >&2; exit 3");
        assertEquals(3, run.exit());
        assertTrue(run.output().contains("out") && run.output().contains("err"), run.output());
    }
}
