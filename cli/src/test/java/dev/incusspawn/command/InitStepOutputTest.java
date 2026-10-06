package dev.incusspawn.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** How {@code isx init} reports its steps: a counter that adds up, and outcomes, not attempts (#906). */
class InitStepOutputTest {

    private static final Pattern COUNTER = Pattern.compile("\\[(\\d+)/(\\d+)]");

    /** What a test step would print to stdout and stderr. */
    private record Output(String out, String err) {}

    private static Output capture(InitCommand.Step body) throws Exception {
        var out = System.out;
        var err = System.err;
        var outBuffer = new ByteArrayOutputStream();
        var errBuffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuffer, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(errBuffer, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return new Output(outBuffer.toString(StandardCharsets.UTF_8), errBuffer.toString(StandardCharsets.UTF_8));
    }

    /** Init with {@code chosen} credentials picked at Credential Setup, and host commands faked. */
    static final class FakeInit extends InitCommand {
        int chosen;
        final List<String> commands = new ArrayList<>();
        String failing = "";

        @Override
        List<Step> credentialSteps() {
            startStep("Credential Setup");
            return Collections.nCopies(chosen, () -> startStep("A credential"));
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

    private static List<InitCommand.Step> steps(InitCommand init, int count) {
        return Collections.nCopies(count, () -> init.startStep("A step"));
    }

    private static List<int[]> counters(int setup, int chosen, int finish) throws Exception {
        var init = new FakeInit();
        init.chosen = chosen;
        var out = capture(() -> init.runSteps(steps(init, setup), steps(init, finish))).out();
        var counters = new ArrayList<int[]>();
        var matcher = COUNTER.matcher(out);
        while (matcher.find()) {
            counters.add(new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))});
        }
        return counters;
    }

    /** Linux's shape: six steps, Credential Setup, then five, the last of them the service. */
    @Test
    void theCounterEndsAtItsTotalWithNoCredentialsChosen() throws Exception {
        var counters = counters(6, 0, 5);
        assertEquals(12, counters.size());
        for (int i = 0; i < counters.size(); i++) {
            assertArrayEquals(new int[]{i + 1, 12}, counters.get(i), "step " + (i + 1));
        }
    }

    /** The credentials chosen add to the total from the next step on, and it still ends at [N/N]. */
    @Test
    void credentialsChosenGrowTheTotalAndItStillAddsUp() throws Exception {
        var counters = counters(2, 3, 5);
        assertEquals(11, counters.size());
        assertArrayEquals(new int[]{3, 8}, counters.get(2), "Credential Setup, before the choice");
        assertArrayEquals(new int[]{4, 11}, counters.get(3), "the first credential");
        assertArrayEquals(new int[]{11, 11}, counters.getLast());
        for (var counter : counters) assertTrue(counter[0] <= counter[1], counter[0] + "/" + counter[1]);
    }

    @Test
    void aSysctlFileThatCouldNotBeAppliedIsNotReportedAsConfigured(@TempDir Path dir) throws Exception {
        var init = new FakeInit();
        init.failing = "sysctl";
        var conf = dir.resolve("99-incus-spawn.conf");
        var output = capture(() -> init.configureHostSysctls(conf));

        assertEquals("sudo sysctl -p " + conf, init.commands.getLast());
        assertFalse(output.out().contains("Configured host sysctls"), output.out());
        assertTrue(output.err().contains("applies after a reboot"), output.err());
    }

    @Test
    void aNetworkManagerReloadThatFailedIsNotReportedAsConfigured(@TempDir Path dir) throws Exception {
        var init = new FakeInit();
        init.failing = "nmcli";
        var output = capture(() -> init.configureNetworkManager(dir));

        assertEquals("sudo nmcli general reload", init.commands.getLast());
        assertFalse(output.out().contains("Configured NetworkManager"), output.out());
        assertTrue(output.err().contains("applies after a reboot"), output.err());
    }

    /** firewall-cmd warns on stderr and exits 0 when it removes a rule that is not there. */
    @Test
    void aHostCommandThatSucceedsPrintsNothing() throws Exception {
        var output = capture(() -> assertEquals(0, new InitCommand().runHostQuiet("sh", "-c",
                "echo \"Warning: NOT_ENABLED: rule is not in 'ipv4:nat:PREROUTING'\" >&2; echo done")));
        assertEquals(new Output("", ""), output);
    }

    @Test
    void aHostCommandThatFailsShowsItsOutput() throws Exception {
        var output = capture(() -> assertEquals(3, new InitCommand().runHostQuiet("sh", "-c",
                "echo 'trying'; echo 'sysctl: permission denied' >&2; exit 3")));
        assertEquals("", output.out());
        assertEquals("  trying\n  sysctl: permission denied\n", output.err());
    }
}
