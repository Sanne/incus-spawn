package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** How {@code isx init} reports its steps: a counter that adds up, and outcomes, not attempts (#906). */
class InitStepOutputTest {

    private static final Pattern COUNTER = Pattern.compile("\\[(\\d+)/(\\d+)(\\+?)]");

    /**
     * What a test step reported, as a terminal splits it: what it did ({@code out}) and its
     * diagnostics and notes ({@code err}), a line each, recorded rather than read off the streams.
     */
    record Reported(String out, String err) {}

    static Reported capture(InitCommand.Step body) {
        var recorded = RecordingOutput.during(body::run);
        var out = new StringBuilder();
        var err = new StringBuilder();
        for (var event : recorded.events()) {
            if (event.msg() != null) (RecordingOutput.onStderr(event.kind()) ? err : out).append(event.text()).append('\n');
        }
        return new Reported(out.toString(), err.toString());
    }

    /** Init with {@code chosen} credentials picked at Credential Setup, and host commands faked. */
    static final class FakeInit extends InitCommand {
        int chosen;
        final List<String> commands = new ArrayList<>();
        String failing = "";

        @Override
        List<InitStep> credentialSteps() {
            startStep("Credential Setup");
            return Collections.nCopies(chosen, step(() -> {}));
        }

        @Override
        void installGitRemoteShim() {
        }

        @Override
        int runHostQuiet(String... command) {
            commands.add(String.join(" ", command));
            return List.of(command).contains(failing) ? 1 : 0;
        }
    }

    /** A step that does {@code run} under the header "A step", and plans nothing. */
    static InitCommand.InitStep step(InitCommand.Step run) {
        return new InitCommand.InitStep("a-step", "A step", new String[0], run, InitPlan.Assessment::done);
    }

    private static List<InitCommand.InitStep> steps(InitCommand init, int count) {
        return Collections.nCopies(count, step(() -> {}));
    }

    private static List<int[]> counters(int setup, int chosen, int finish) throws Exception {
        var init = new FakeInit();
        init.chosen = chosen;
        // The step boxes are frames init draws as given
        var out = String.join("\n", RecordingOutput.during(() -> init.runSteps(steps(init, setup), steps(init, finish))).frameLines());
        var counters = new ArrayList<int[]>();
        var matcher = COUNTER.matcher(out);
        while (matcher.find()) {
            counters.add(new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    matcher.group(3).isEmpty() ? 0 : 1});
        }
        return counters;
    }

    /** Linux's shape: six steps, Credential Setup, then five, the last of them the service. */
    @Test
    void theCounterEndsAtItsTotalWithNoCredentialsChosen() throws Exception {
        var counters = counters(6, 0, 5);
        assertEquals(12, counters.size());
        for (int i = 0; i < counters.size(); i++) {
            assertArrayEquals(new int[]{i + 1, 12, i < 7 ? 1 : 0}, counters.get(i), "step " + (i + 1));
        }
    }

    /**
     * The credentials chosen add to the total from the next step on, and it still ends at [N/N].
     * Until the choice the total reads {@code 8+}, so the jump to 11 never contradicts it (#1441).
     */
    @Test
    void credentialsChosenGrowTheTotalAndItStillAddsUp() throws Exception {
        var counters = counters(2, 3, 5);
        assertEquals(11, counters.size());
        assertArrayEquals(new int[]{1, 8, 1}, counters.get(0), "a setup step: more may follow");
        assertArrayEquals(new int[]{3, 8, 1}, counters.get(2), "Credential Setup, before the choice");
        assertArrayEquals(new int[]{4, 11, 0}, counters.get(3), "the first credential");
        assertArrayEquals(new int[]{11, 11, 0}, counters.getLast());
        for (var counter : counters) assertTrue(counter[0] <= counter[1], counter[0] + "/" + counter[1]);
    }

    @Test
    void aSysctlFileThatCouldNotBeAppliedIsNotReportedAsConfigured(@TempDir Path dir) throws Exception {
        var init = new FakeInit();
        init.failing = "sysctl";
        var conf = dir.resolve("99-incus-spawn.conf");
        var output = capture(() -> init.host().configureHostSysctls(conf));

        assertEquals("sudo sysctl -p " + conf, init.commands.getLast());
        assertFalse(output.out().contains("Configured host sysctls"), output.out());
        assertTrue(output.err().contains("applies after a reboot"), output.err());
    }

    @Test
    void aNetworkManagerReloadThatFailedIsNotReportedAsConfigured(@TempDir Path dir) throws Exception {
        var init = new FakeInit();
        init.failing = "nmcli";
        var output = capture(() -> init.host().configureNetworkManager(dir));

        assertEquals("sudo nmcli general reload", init.commands.getLast());
        assertFalse(output.out().contains("Configured NetworkManager"), output.out());
        assertTrue(output.err().contains("applies after a reboot"), output.err());
    }

    /** firewall-cmd warns on stderr and exits 0 when it removes a rule that is not there. */
    @Test
    void aHostCommandThatSucceedsPrintsNothing() throws Exception {
        var recorded = RecordingOutput.during(() -> assertEquals(0, new InitCommand().runHostQuiet("sh", "-c",
                "echo \"Warning: NOT_ENABLED: rule is not in 'ipv4:nat:PREROUTING'\" >&2; echo done")));
        assertEquals(List.of(), recorded.events());
    }

    @Test
    void aHostCommandThatFailsShowsItsOutput() throws Exception {
        var recorded = RecordingOutput.during(() -> assertEquals(3, new InitCommand().runHostQuiet("sh", "-c",
                "echo 'trying'; echo 'sysctl: permission denied' >&2; exit 3")));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.NOTE, "trying"),
                new RecordingOutput.Event(RecordingOutput.Kind.NOTE, "sysctl: permission denied")), recorded.events());
    }
}
