package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.vm.VmAgentClient;
import dev.incusspawn.vm.VmManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a battery of health checks across the host, VM, and vsock tunnel and reports a
 * grouped pass/warn/fail summary. For problems that have a remediation, it offers to apply
 * the fix interactively (when attached to a terminal) or prints the suggested action otherwise.
 *
 * Fine-grained diagnostics/recovery are intentionally not separate top-level commands — they
 * live here as checks so there is a single, discoverable entry point.
 */
@CommandDefinition(
        name = "doctor",
        description = "Run health checks and offer to fix problems",
        generateHelp = true
)
public class DoctorCommand extends BaseCommand {

    enum Status {
        OK("✓"), WARN("⚠"), FAIL("✗");
        final String symbol;
        Status(String symbol) { this.symbol = symbol; }
    }

    /** A remediation a check can offer. {@code destructive} drives the confirmation wording. */
    interface Action { void run() throws Exception; }
    record Remediation(String description, boolean destructive, Action action) {}

    record Finding(Status status, String label, String detail, Remediation remediation) {
        static Finding ok(String label, String detail) { return new Finding(Status.OK, label, detail, null); }
        static Finding warn(String label, String detail, Remediation r) { return new Finding(Status.WARN, label, detail, r); }
        static Finding fail(String label, String detail, Remediation r) { return new Finding(Status.FAIL, label, detail, r); }
    }

    @Override
    protected CommandResult doExecute() throws Exception {
        System.out.println("Running incus-spawn doctor...\n");

        var findings = Environment.isLinux() ? runLinuxChecks() : runMacChecks();

        for (var f : findings) {
            var detail = f.detail() == null || f.detail().isBlank() ? "" : " " + f.detail();
            System.out.println("  " + f.status().symbol + " " + f.label() + detail);
        }

        var actionable = findings.stream()
                .filter(f -> f.status() != Status.OK && f.remediation() != null)
                .toList();

        if (actionable.isEmpty()) {
            boolean anyProblem = findings.stream().anyMatch(f -> f.status() != Status.OK);
            System.out.println("\n" + (anyProblem ? "Some checks reported issues with no automatic fix."
                    : "All checks passed."));
            return exitFor(findings);
        }

        System.out.println("\n" + actionable.size() + " issue(s) can be addressed:\n");
        for (var f : actionable) {
            System.out.println("  " + f.status().symbol + " " + f.label());
            applyOrSuggest(f.remediation());
        }
        return exitFor(findings);
    }

    private CommandResult exitFor(List<Finding> findings) {
        boolean anyFail = findings.stream().anyMatch(f -> f.status() == Status.FAIL);
        return anyFail ? CommandResult.valueOf(1) : CommandResult.SUCCESS;
    }

    /** Prompt to apply a remediation (TTY), or print the suggestion when non-interactive. */
    private void applyOrSuggest(Remediation r) {
        var console = System.console();
        if (console == null) {
            System.out.println("     fix: " + r.description() + " (re-run in a terminal to apply)");
            return;
        }
        var warn = r.destructive() ? " This is disruptive." : "";
        System.out.print("     " + r.description() + "." + warn + " Apply now? (y/N): ");
        var answer = console.readLine();
        if (answer == null || !answer.strip().equalsIgnoreCase("y")) {
            System.out.println("     skipped.");
            return;
        }
        try {
            r.action().run();
            System.out.println("     done.");
        } catch (Exception e) {
            System.out.println("     failed: " + e.getMessage());
        }
    }

    // ---- macOS checks (vfkit VM + vsock tunnel) ----

    private List<Finding> runMacChecks() {
        var findings = new ArrayList<Finding>();
        boolean vmRunning = VmManager.isRunning();
        findings.add(checkVmRunning(vmRunning));
        if (vmRunning) {
            findings.add(checkIncusReachable());
            findings.add(checkForwarderLeak());
        }
        return findings;
    }

    private Finding checkVmRunning(boolean running) {
        if (running) return Finding.ok("VM running", "");
        return Finding.fail("VM not running", "",
                new Remediation("Start the VM", false, VmManager::start));
    }

    private Finding checkIncusReachable() {
        long t0 = System.nanoTime();
        boolean reachable = IncusClient.isReachable();
        double seconds = (System.nanoTime() - t0) / 1_000_000_000.0;
        if (!reachable) {
            var detail = RuntimeServices.incus().checkConnectivity();
            return Finding.fail("Incus not reachable", detail == null ? "" : "(" + detail + ")",
                    new Remediation("Restart the VM to restore the tunnel", true, DoctorCommand::restartVm));
        }
        if (seconds > 5.0) {
            return Finding.warn("Incus reachable but slow",
                    String.format("(%.1fs — possible forwarder pressure)", seconds),
                    new Remediation("Restart the VM to clear the tunnel", true, DoctorCommand::restartVm));
        }
        return Finding.ok("Incus reachable", String.format("(%.1fs)", seconds));
    }

    private Finding checkForwarderLeak() {
        var base = forwarderFinding(VmManager.vsockForwarderConnectionCount());

        // If the in-VM control agent is present, enrich with the in-guest socat child count.
        // Comparing it to the host-side count locates the leak: low in-guest + high host = vfkit
        // not reaping (link 2); both high = forwarder lingering children (link 3).
        var guest = VmAgentClient.socatCount();
        var detail = base.detail();
        if (guest.isPresent()) {
            var sep = detail == null || detail.isBlank() ? "" : " ";
            detail = (detail == null ? "" : detail) + sep + "(in-guest socat: " + guest.getAsInt() + ")";
        }

        if (base.status() == Status.OK) {
            return new Finding(Status.OK, base.label(), detail, null);
        }
        // Leak: prefer no-reboot recovery via the agent; fall back to the VM restart otherwise.
        if (VmAgentClient.ping()) {
            return Finding.warn(base.label(), detail,
                    new Remediation("Restart the forwarder in the VM (no reboot — running containers keep going)",
                            false, DoctorCommand::restartForwarderViaAgent));
        }
        return new Finding(base.status(), base.label(), detail, base.remediation());
    }

    private static void restartForwarderViaAgent() {
        if (!VmAgentClient.restartForwarder()) {
            throw new RuntimeException("control agent did not confirm forwarder restart");
        }
    }

    /** Pure decision from a forwarder connection count (-1 = unmeasurable). Package-private for testing. */
    static Finding forwarderFinding(int conns) {
        if (conns < 0) return Finding.ok("vsock forwarder", "(not measurable)");
        if (conns > VmManager.VSOCK_CONN_WARN_THRESHOLD) {
            return Finding.warn("vsock forwarder connections: " + conns,
                    "(high — leaked streams degrade new-connection latency)",
                    new Remediation("Restart the VM to clear leaked forwarder streams "
                            + "(stops running containers)", true, DoctorCommand::restartVm));
        }
        return Finding.ok("vsock forwarder connections: " + conns, "");
    }

    private static void restartVm() {
        VmManager.stop();
        if (!VmManager.start()) throw new RuntimeException("VM failed to start");
    }

    // ---- Linux checks (native Incus) ----

    private List<Finding> runLinuxChecks() {
        var findings = new ArrayList<Finding>();
        if (IncusClient.isReachable()) {
            findings.add(Finding.ok("Incus reachable", ""));
        } else {
            var detail = RuntimeServices.incus().checkConnectivity();
            findings.add(Finding.fail("Incus not reachable", detail == null ? "" : "(" + detail + ")", null));
        }
        return findings;
    }
}
