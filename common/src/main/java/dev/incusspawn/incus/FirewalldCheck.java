package dev.incusspawn.incus;

import dev.incusspawn.util.HostPath;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public final class FirewalldCheck {

    private FirewalldCheck() {}

    public static boolean isInstalled() {
        return HostPath.isOnPath("firewall-cmd");
    }

    /**
     * What {@code systemctl is-active firewalld} says, in the three states a user is told apart:
     * a daemon that crashed left its rules in the kernel, one that never ran or stopped cleanly did not.
     */
    public enum State {
        ACTIVE,
        /** Never started, cleanly stopped, or on its way up or down. */
        INACTIVE,
        /** systemd's {@code failed}: the daemon crashed, was killed or exited with an error. */
        FAILED
    }

    public static boolean isActive() {
        return state() == State.ACTIVE;
    }

    /** The daemon's state; a probe that could not run reads as {@link State#INACTIVE}. */
    public static State state() {
        try {
            return probeState();
        } catch (IOException e) {
            return State.INACTIVE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return State.INACTIVE;
        }
    }

    /**
     * Like {@link #state()}, but a probe that was cut short throws rather than reading as
     * stopped. A host without {@code systemctl} (macOS, Linux without systemd) is an answer: inactive.
     */
    static State probeState() throws IOException, InterruptedException {
        // systemctl is-active does not require root, unlike firewall-cmd --state
        // which needs polkit authorization and fails with exit 253 as a normal user
        return probeState(List.of("systemctl", "is-active", "firewalld"));
    }

    /**
     * The direct rules, as {@code firewall-cmd --direct --get-all-rules} prints them, or null when
     * they could not be read. The policy a server or cloud Fedora installs lets only an admin read
     * them (polkit's {@code direct.info}), so a plain user's query fails there; this then asks
     * through {@code sudo -n}, which works when sudo needs no password now and never prompts (#1441).
     */
    public static String readDirectRules() {
        return readDirectRules(DIRECT_RULE_QUERIES);
    }

    /** The queries {@link #readDirectRules()} tries in order: as the user, then through {@code sudo -n}. */
    public static final List<List<String>> DIRECT_RULE_QUERIES = List.of(
            List.of("firewall-cmd", "--direct", "--get-all-rules"),
            List.of("sudo", "-n", "firewall-cmd", "--direct", "--get-all-rules"));

    /** The first of {@code attempts} that succeeds, its output; null when none does. */
    public static String readDirectRules(List<List<String>> attempts) {
        for (var command : attempts) {
            if (!HostPath.isOnPath(command.getFirst())) continue;
            // A process that may not elevate asks as the user only (#2074)
            if (dev.incusspawn.util.Privilege.forbidden() && dev.incusspawn.util.Privilege.elevates(command)) continue;
            try {
                var pb = new ProcessBuilder(command);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
                var process = pb.start();
                var output = new String(process.getInputStream().readAllBytes());
                if (process.waitFor() == 0) return output;
            } catch (IOException e) {
                // the next attempt, or none
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** The states systemctl is-active counts as active: a reload keeps the rules loaded. */
    private static final Set<String> RUNNING = Set.of("active", "reloading", "refreshing");

    static State probeState(List<String> command) throws IOException, InterruptedException {
        if (!HostPath.isOnPath(command.getFirst())) return State.INACTIVE;
        var pb = new ProcessBuilder(command);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes()).strip();
        // it prints the state either way, and exits non-zero for every state but active
        if (process.waitFor() == 0 && RUNNING.contains(output)) return State.ACTIVE;
        return "failed".equals(output) ? State.FAILED : State.INACTIVE;
    }

    static final FirewallDetector.Stopped STOPPED = new FirewallDetector.Stopped(
            "firewalld is not running:",
            "Possible cause: firewalld is installed but not running.\n"
            + "Firewall rules (masquerading, FORWARD, PREROUTING redirect) are not\n"
            + "loaded into the kernel, so containers cannot reach the internet.\n\n"
            + "Fix:\n"
            + "  sudo systemctl enable --now firewalld\n"
            + "  isx init");

    /**
     * systemd's {@code failed} covers a daemon that died after installing its rules, which stay in
     * the kernel, and one that never got that far. An unprivileged check cannot read the ruleset to
     * tell them apart, so the banner says "may" and covers both.
     */
    static final FirewallDetector.Stopped CRASHED = new FirewallDetector.Stopped(
            "firewalld has failed:",
            "Possible cause: firewalld crashed or exited with an error (systemd reports it failed).\n"
            + "If it ran before failing, the rules it installed (masquerading, FORWARD,\n"
            + "PREROUTING redirect) may still be in the kernel, so containers may keep working\n"
            + "until the next reboot or firewall reload. Otherwise they cannot reach the internet.\n\n"
            + "Fix:\n"
            + "  sudo systemctl restart firewalld\n"
            + "  isx init    (if the redirect rules are missing)\n"
            + "If systemd refuses the restart, run 'sudo systemctl reset-failed firewalld' first.\n\n"
            + "Why it stopped: systemctl status firewalld, or coredumpctl info firewalld");

    /**
     * Whether a line matches the PREROUTING redirect rule pattern (incusbr0, port 443 → mitmPort).
     * Works for both {@code firewall-cmd --direct --get-all-rules} output and persistent
     * {@code /etc/firewalld/direct.xml} element text.
     */
    public static boolean isRedirectRule(String line, int mitmPort) {
        return line.contains("PREROUTING")
                && line.contains("incusbr0")
                && containsToken(line, "--dport", "443")
                && line.contains("REDIRECT")
                && containsToken(line, "--to-port", String.valueOf(mitmPort));
    }

    private static boolean containsToken(String line, String flag, String value) {
        var token = flag + " " + value;
        int idx = line.indexOf(token);
        if (idx < 0) return false;
        int end = idx + token.length();
        return end >= line.length() || !Character.isDigit(line.charAt(end));
    }

    public static boolean isPreRoutingRulePresent(String firewalldOutput, int mitmPort, String gatewayIp) {
        for (var line : firewalldOutput.split("\n")) {
            if (line.contains("nat") && isRedirectRule(line, mitmPort)
                    && line.contains("-d " + gatewayIp)) {
                return true;
            }
        }
        return false;
    }

    /** Extract the gateway IP from an existing PREROUTING redirect rule, or null if none found. */
    public static String extractRedirectGatewayIp(String firewalldOutput, int mitmPort) {
        for (var line : firewalldOutput.split("\n")) {
            if (line.contains("nat") && isRedirectRule(line, mitmPort)
                    && line.contains("-d ")) {
                int idx = line.indexOf("-d ");
                if (idx >= 0) {
                    var rest = line.substring(idx + 3).strip();
                    int end = rest.indexOf(' ');
                    return end > 0 ? rest.substring(0, end) : rest;
                }
            }
        }
        return null;
    }

    public static boolean isForwardRulePresent(String firewalldOutput, String interfaceFlag, String interfaceName) {
        for (var line : firewalldOutput.split("\n")) {
            if (line.contains("FORWARD")
                    && line.contains(interfaceFlag + " " + interfaceName)
                    && line.contains("ACCEPT")) {
                return true;
            }
        }
        return false;
    }

}
