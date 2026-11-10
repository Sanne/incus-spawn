package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * While a command prints a summary for a script, a child it runs must not write the results'
 * stdout: {@code incus storage create} printed 'Storage pool cow created' ahead of
 * {@code isx init --format=json}'s summary on a fresh host (#2120 review).
 */
class StdoutOnStderrTest {

    /** Runs {@code work} under {@link StdoutOnStderr#during}, returning what reached its stand-in for stderr. */
    private static String stderrDuring(Runnable work) throws Exception {
        var capture = new ByteArrayOutputStream();
        StdoutOnStderr.during(() -> {
            // during points System.out at stderr; the stand-in catches what a child's copied stdout writes there
            var swapped = System.out;
            System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
            try {
                work.run();
            } finally {
                System.setOut(swapped);
            }
            return null;
        });
        return capture.toString(StandardCharsets.UTF_8);
    }

    @Test
    void thePoolCreatesOutputGoesToStderrUnderASummary() throws Exception {
        var attempt = new CowPoolBacking.Attempt[1];
        var copied = stderrDuring(() -> attempt[0] = CowPoolBacking.runCapturingStderr("sh", "-c", "echo created; echo why >&2"));
        assertEquals("created\n", copied);
        assertEquals(0, attempt[0].exitCode());
        assertEquals("why\n", attempt[0].stderr(), "its stderr is still the caller's, to word a failure");
    }

    @Test
    void runHostsChildGoesToStderrUnderASummary() throws Exception {
        var copied = stderrDuring(() -> assertEquals(0, InitCommand.runHost("sh", "-c", "echo progress")));
        assertEquals("progress\n", copied);
    }
}
