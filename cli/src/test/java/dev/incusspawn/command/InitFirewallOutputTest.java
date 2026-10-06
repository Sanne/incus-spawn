package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import dev.incusspawn.command.InitStepOutputTest.Output;

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

    private static Output run(String failing, Consumer<FakeInit> step) throws Exception {
        var init = new FakeInit();
        init.failing = failing;
        return capture(() -> step.accept(init));
    }

    private static Output firewalld(String failing) throws Exception {
        return run(failing, FakeInit::configureFirewalld);
    }

    private static Output ufw(String failing) throws Exception {
        return run(failing, init -> init.configureUfw(GATEWAY, BEFORE_RULES));
    }

    private static void assertFailureReported(Output output, String command) {
        assertFalse(output.out().contains("configured"), output.out());
        assertTrue(output.err().contains(command), output.err());
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
        var output = capture(init::configureFirewalld);
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
    private record Mitm(Output output, boolean redirected) {}

    private static Mitm mitm(String failing, Predicate<FakeInit> step) throws Exception {
        var redirected = new AtomicBoolean();
        var output = run(failing, init -> redirected.set(step.test(init)));
        return new Mitm(output, redirected.get());
    }

    private static Mitm mitmFirewalld(String failing) throws Exception {
        return mitm(failing, init -> init.configureMitmProxyFirewalld(GATEWAY));
    }

    private static Mitm mitmUfw(String failing) throws Exception {
        return mitm(failing, init -> init.configureMitmProxyUfw(GATEWAY, BEFORE_RULES));
    }

    private static void assertRedirectFailureReported(Mitm mitm, String command) {
        assertFalse(mitm.redirected(), "MITM proxy would be reported as configured");
        assertTrue(mitm.output().err().contains(command), mitm.output().err());
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
