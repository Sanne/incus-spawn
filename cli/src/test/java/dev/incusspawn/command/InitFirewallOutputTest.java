package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import dev.incusspawn.command.InitStepOutputTest.Reported;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static dev.incusspawn.command.InitStepOutputTest.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The firewall steps of {@code isx init} report outcomes: a success line only once every host
 * command of the step returned 0, and otherwise a warning naming the command that failed (#1102).
 */
class InitFirewallOutputTest {

    private static final String GATEWAY = "10.166.11.1";

    /** Stock before.rules: no isx NAT or FORWARD block yet. */
    private static final String BEFORE_RULES = """
            *filter
            :ufw-before-input - [0:0]
            :ufw-before-output - [0:0]
            :ufw-before-forward - [0:0]
            -A ufw-before-forward -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
            COMMIT
            """;

    /** Init on a host with no rules yet, where any command with the argument {@code failing} fails. */
    static final class FakeInit extends InitCommand {
        String failing = "";
        String alsoFailing = "";

        @Override
        String captureOutput(String... command) {
            return "";
        }

        @Override
        int runHostQuiet(String... command) {
            var args = List.of(command);
            return args.contains(failing) || args.contains(alsoFailing) ? 1 : 0;
        }
    }

    private static Reported run(String failing, Consumer<FakeInit> step) throws Exception {
        var init = new FakeInit();
        init.failing = failing;
        return capture(() -> step.accept(init));
    }

    private static Reported firewalld(String failing) throws Exception {
        return run(failing, init -> init.host().configureFirewalld());
    }

    private static Reported ufw(String failing) throws Exception {
        return run(failing, init -> init.host().configureUfw(GATEWAY, BEFORE_RULES));
    }

    private static void assertFailureReported(Reported output, String command) {
        assertFalse(output.out().contains("configured"), output.out());
        assertTrue(output.err().contains(command), output.err());
    }

    /**
     * The firewall step's outcomes are typed (#1703): what it did is steps, a failure a warning
     * with what to do under it, which the TUI's log and any binding keep.
     */
    @Test
    void aFailedReloadIsAWarningWithItsRemedy() {
        var init = new FakeInit();
        init.failing = "--reload";
        var recorded = RecordingOutput.during(() -> init.host().configureFirewalld());

        assertEquals(List.of("Firewall reload failed.\nRun: sudo firewall-cmd --reload"), recorded.warnings());
        assertTrue(recorded.events(RecordingOutput.Kind.STEP).stream()
                .noneMatch(e -> e.msg().startsWith("Firewall configured")), recorded.events()::toString);
    }

    /**
     * Work a step did is a live step (#1791): it ends in a ✓ once its commands succeeded, and in a
     * ✗ after the warning naming the one that failed, never as a "Doing X..." line left dangling.
     */
    @Test
    void eachFirewallChangeIsALiveStepThatEndsBeforeItsWarning() {
        var masquerade = "Enabling masquerading (NAT) for container internet access";
        var init = new FakeInit();
        var done = RecordingOutput.during(() -> init.host().configureFirewalld()).events();
        int at = done.indexOf(new RecordingOutput.Event(RecordingOutput.Kind.STEP_START, masquerade));
        assertTrue(at >= 0, done::toString);
        assertEquals(RecordingOutput.Kind.STEP_DONE, done.get(at + 1).kind(), done::toString);

        init.failing = "--add-masquerade";
        var failed = RecordingOutput.during(() -> init.host().configureFirewalld()).events();
        int start = failed.indexOf(new RecordingOutput.Event(RecordingOutput.Kind.STEP_START, masquerade));
        assertTrue(start >= 0, failed::toString);
        assertEquals(RecordingOutput.Kind.WARN, failed.get(start + 1).kind(), failed::toString);
        assertTrue(failed.get(start + 1).msg().contains("--add-masquerade"), failed::toString);
        assertEquals(RecordingOutput.Kind.ABANDON_STEP, failed.get(start + 2).kind(), failed::toString);
        assertTrue(failed.stream().noneMatch(e -> e.msg() != null && e.msg().endsWith("...")), failed::toString);
    }

    @Test
    void firewalldReportsSuccessWhenEveryCommandSucceeds() throws Exception {
        var output = firewalld("");
        assertTrue(output.out().contains("Firewall configured"), output.out());
        assertEquals("", output.err());
    }

    @Test
    void firewalldMasqueradeFailureIsNotReportedAsConfigured() throws Exception {
        assertFailureReported(firewalld("--add-masquerade"), "--add-masquerade");
    }

    @Test
    void firewalldForwardRuleFailureIsNotReportedAsConfigured() throws Exception {
        assertFailureReported(firewalld("FORWARD"), "FORWARD");
    }

    @Test
    void firewalldRuleFailureStillSaysToRerunInitWhenTheReloadFailsToo() throws Exception {
        var init = new FakeInit();
        init.failing = "--add-masquerade";
        init.alsoFailing = "--reload";
        var output = capture(init.host()::configureFirewalld);
        assertFailureReported(output, "--add-masquerade");
        assertTrue(output.err().contains("re-run: isx init"), output.err());
    }

    @Test
    void ufwReportsSuccessWhenEveryCommandSucceeds() throws Exception {
        var output = ufw("");
        assertTrue(output.out().contains("Firewall configured"), output.out());
        assertEquals("", output.err());
    }

    @Test
    void ufwAllowFailureIsNotReportedAsConfigured() throws Exception {
        assertFailureReported(ufw("allow"), "ufw allow in on incusbr0");
    }

    @Test
    void ufwBeforeRulesCopyFailureIsNotReportedAsConfigured() throws Exception {
        assertFailureReported(ufw("cp"), "/etc/ufw/before.rules");
    }

    /**
     * What a MITM step printed and returned: {@code configureMitmProxy} prints "MITM proxy
     * configured." only when the step returns true.
     */
    private record Mitm(Reported output, boolean redirected) {}

    private static Mitm mitm(String failing, Predicate<FakeInit> step) throws Exception {
        var redirected = new AtomicBoolean();
        var output = run(failing, init -> redirected.set(step.test(init)));
        return new Mitm(output, redirected.get());
    }

    private static Mitm mitmFirewalld(String failing) throws Exception {
        return mitm(failing, init -> init.host().configureMitmProxyFirewalld(GATEWAY));
    }

    private static Mitm mitmUfw(String failing) throws Exception {
        return mitm(failing, init -> init.host().configureMitmProxyUfw(GATEWAY, BEFORE_RULES));
    }

    private static void assertRedirectFailureReported(Mitm mitm, String command) {
        assertFalse(mitm.redirected(), "MITM proxy would be reported as configured");
        assertTrue(mitm.output().err().contains(command), mitm.output().err());
    }

    /**
     * On server or cloud Fedora only an admin may read firewalld's direct rules, so init reads them
     * through sudo, as its firewall step does; read as the user, the redirect looked missing on every
     * re-run, and a rule left at a previous gateway was never seen or removed.
     */
    @Test
    void mitmFirewalldReadsTheRulesAsRoot() throws Exception {
        var ran = new java.util.ArrayList<String>();
        var init = new InitCommand() {
            @Override
            String captureOutput(String... command) {
                return command[0].equals("sudo")
                        ? "ipv4 nat PREROUTING 0 -i incusbr0 -d 10.0.0.9 -p tcp --dport 443 -j REDIRECT --to-port 18443\n"
                        : "";
            }

            @Override
            int runHostQuiet(String... command) {
                ran.add(String.join(" ", command));
                return 0;
            }
        };
        capture(() -> init.host().configureMitmProxyFirewalld(GATEWAY));
        assertTrue(ran.stream().anyMatch(c -> c.contains("--remove-rule") && c.contains("-d 10.0.0.9")), ran.toString());
    }

    @Test
    void mitmFirewalldReportsSuccessWhenEveryCommandSucceeds() throws Exception {
        var mitm = mitmFirewalld("");
        assertTrue(mitm.redirected());
        assertEquals("", mitm.output().err());
    }

    @Test
    void mitmUfwReportsSuccessWhenEveryCommandSucceeds() throws Exception {
        var mitm = mitmUfw("");
        assertTrue(mitm.redirected());
        assertEquals("", mitm.output().err());
    }

    @Test
    void mitmFirewalldRedirectFailureIsReported() throws Exception {
        assertRedirectFailureReported(mitmFirewalld("--add-rule"), "PREROUTING");
    }

    @Test
    void mitmFirewalldReloadFailureIsReported() throws Exception {
        assertRedirectFailureReported(mitmFirewalld("--reload"), "--reload");
    }

    @Test
    void mitmUfwBeforeRulesCopyFailureIsReported() throws Exception {
        assertRedirectFailureReported(mitmUfw("cp"), "/etc/ufw/before.rules");
    }

    @Test
    void mitmUfwReloadFailureIsReported() throws Exception {
        assertRedirectFailureReported(mitmUfw("reload"), "ufw reload");
    }
}
