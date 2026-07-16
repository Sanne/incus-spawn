package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FirewalldCheck;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.MitmProxy;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.vm.VmAgentClient;
import dev.incusspawn.vm.VmManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

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

    @Option(name = "bundle", hasValue = false,
            description = "Collect findings and logs into a support archive (tar.gz)")
    boolean bundle;

    @Option(name = "deep", hasValue = false,
            description = "Run per-instance checks (DNS, TLS, resolv.conf)")
    boolean deep;

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

        System.out.print(formatFindings(findings).indent(2));

        if (bundle) {
            generateBundle(findings);
            return exitFor(findings);
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

    // ---- macOS checks (vfkit VM + vsock tunnel + shared layers) ----

    private List<Finding> runMacChecks() {
        var findings = new ArrayList<Finding>();

        // Layer 1: Host configuration
        findings.addAll(checkHostConfig());

        // Layer 2: Incus daemon (via VM)
        boolean vmRunning = VmManager.isRunning();
        findings.add(checkVmRunning(vmRunning));
        boolean incusUp = false;
        if (vmRunning) {
            var incusFinding = checkIncusReachable();
            findings.add(incusFinding);
            incusUp = incusFinding.status() == Status.OK;
            findings.add(checkForwarderLeak());
            findings.add(checkVmDiskHeadroom());
        }

        if (!incusUp) return findings;
        runSharedChecks(findings);
        return findings;
    }

    // ---- Linux checks (native Incus + shared layers) ----

    private List<Finding> runLinuxChecks() {
        var findings = new ArrayList<Finding>();

        // Layer 1: Host configuration
        findings.addAll(checkHostConfig());

        // Layer 2: Incus daemon
        boolean incusUp;
        if (IncusClient.isReachable()) {
            findings.add(Finding.ok("Incus reachable", ""));
            incusUp = true;
        } else {
            var detail = RuntimeServices.incus().checkConnectivity();
            findings.add(Finding.fail("Incus not reachable", detail == null ? "" : "(" + detail + ")", null));
            incusUp = false;
        }

        if (!incusUp) return findings;
        runSharedChecks(findings);
        return findings;
    }

    /** Layers 2 (storage) through 7 — shared between Linux and macOS once Incus is reachable. */
    private void runSharedChecks(List<Finding> findings) {
        findings.add(checkStoragePool());
        findings.addAll(checkProxy());
        findings.addAll(checkDnsAndBridge());
        findings.addAll(checkTemplates());
        if (deep) {
            findings.addAll(checkInstances());
        }
    }

    // ---- Layer 1: Host configuration ----

    private List<Finding> checkHostConfig() {
        return List.of(checkConfigFile(), checkCaCertificate(), checkCredentials());
    }

    private Finding checkConfigFile() {
        var configFile = SpawnConfig.configDir().resolve("config.yaml");
        if (!Files.exists(configFile)) {
            return Finding.warn("config.yaml missing", "",
                    new Remediation("Run 'isx init' to create configuration", false, null));
        }
        try {
            var perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(configFile));
            var permFinding = evaluateConfigPermissions(perms);
            if (permFinding.status() != Status.OK) {
                return new Finding(permFinding.status(), permFinding.label(), permFinding.detail(),
                        new Remediation("Restrict to owner-only (chmod 600)", false, () -> {
                            Files.setPosixFilePermissions(configFile,
                                    PosixFilePermissions.fromString("rw-------"));
                        }));
            }
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        } catch (IOException e) {
            return Finding.warn("config.yaml", "(could not check permissions: " + e.getMessage() + ")", null);
        }
        try {
            var yaml = new ObjectMapper(new YAMLFactory());
            yaml.readValue(configFile.toFile(), SpawnConfig.class);
            return Finding.ok("config.yaml", "exists and parses");
        } catch (Exception e) {
            var msg = e.getClass().getSimpleName();
            var loc = e.getMessage() == null ? "" : e.getMessage().replaceAll("(?s)\\n.*", "");
            if (loc.contains("line:") || loc.contains("column:")) {
                loc = loc.replaceAll("(?s)(line: \\d+, column: \\d+).*", "$1");
                msg += " at " + loc;
            }
            return Finding.fail("config.yaml invalid", "(" + msg + ")", null);
        }
    }

    static Finding evaluateConfigPermissions(String perms) {
        if (perms.length() >= 9) {
            for (int i = 3; i < 9; i++) {
                if (perms.charAt(i) != '-') {
                    return Finding.warn("config.yaml permissions too open", "(" + perms + ")", null);
                }
            }
        }
        return Finding.ok("config.yaml permissions", "(" + perms + ")");
    }

    private Finding checkCaCertificate() {
        if (!CertificateAuthority.exists()) {
            return Finding.warn("CA certificate missing", "",
                    new Remediation("Run 'isx init' to generate CA", false, null));
        }
        try {
            var ca = CertificateAuthority.loadOrCreate();
            var cert = ca.caCert();
            cert.checkValidity();
            var daysLeft = ChronoUnit.DAYS.between(Instant.now(), cert.getNotAfter().toInstant());
            if (daysLeft < 30) {
                return Finding.warn("CA certificate expires soon",
                        "(" + daysLeft + " days left)", null);
            }
            return Finding.ok("CA certificate", "valid (" + daysLeft + " days remaining)");
        } catch (CertificateExpiredException e) {
            return Finding.fail("CA certificate expired", "",
                    new Remediation("Delete old CA and run 'isx init' to regenerate", true, null));
        } catch (CertificateNotYetValidException e) {
            return Finding.warn("CA certificate not yet valid", "(" + e.getMessage() + ")", null);
        } catch (Exception e) {
            return Finding.fail("CA certificate unreadable", "(" + e.getMessage() + ")", null);
        }
    }

    private Finding checkCredentials() {
        var config = SpawnConfig.load();
        var missing = new ArrayList<String>();
        if (!config.getClaude().hasAuth()) missing.add("Claude API key/OAuth/Vertex");
        if (config.getGithub().getToken().isBlank()) missing.add("GitHub token");
        if (missing.isEmpty()) return Finding.ok("Credentials", "configured");
        return Finding.warn("Missing credentials", "(" + String.join(", ", missing) + ")",
                new Remediation("Run 'isx init' to configure", false, null));
    }

    // ---- Layer 2: Incus daemon ----

    private Finding checkStoragePool() {
        try {
            var incus = RuntimeServices.incus();
            var pool = incus.findCowPool();
            if (pool == null) {
                return Finding.warn("No CoW storage pool", "(btrfs/zfs/lvm recommended)", null);
            }
            var usage = incus.getStoragePoolUsage(pool);
            return evaluateStorageUsage(pool, usage);
        } catch (Exception e) {
            return Finding.warn("Storage pool", "(could not check: " + e.getMessage() + ")", null);
        }
    }

    private static final Pattern STORAGE_PCT = Pattern.compile("(\\d+)% full");

    static Finding evaluateStorageUsage(String poolName, String usageString) {
        if (usageString == null || usageString.isEmpty()) {
            return Finding.ok("Storage pool " + poolName, "(no usage info)");
        }
        var matcher = STORAGE_PCT.matcher(usageString);
        if (matcher.find()) {
            int pct = Integer.parseInt(matcher.group(1));
            if (pct > 90) {
                return Finding.warn("Storage pool " + poolName + " nearly full", usageString, null);
            }
        }
        return Finding.ok("Storage pool " + poolName, usageString);
    }

    // ---- Layer 3: VM/tunnel (macOS) ----

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
        int host = VmManager.vsockForwarderConnectionCount();
        var base = forwarderFinding(host);

        var guest = VmAgentClient.socatCount();
        var detail = base.detail();
        if (guest.isPresent()) {
            detail = append(detail, "(in-guest socat: " + guest.getAsInt() + ")");
        }

        if (base.status() == Status.OK) {
            return new Finding(Status.OK, base.label(), detail, null);
        }
        if (guest.isPresent()) {
            var layer = leakLayer(host, guest.getAsInt());
            detail = append(detail, "— " + layer.description);
            if (layer == LeakLayer.FORWARDER && VmAgentClient.ping()) {
                return Finding.warn(base.label(), detail,
                        new Remediation("Restart the forwarder in the VM (no reboot — running containers keep going)",
                                false, DoctorCommand::restartForwarderViaAgent));
            }
            return new Finding(base.status(), base.label(), detail, base.remediation());
        }
        if (VmAgentClient.ping()) {
            return Finding.warn(base.label(), detail,
                    new Remediation("Restart the forwarder in the VM (no reboot — running containers keep going)",
                            false, DoctorCommand::restartForwarderViaAgent));
        }
        return new Finding(base.status(), base.label(), detail, base.remediation());
    }

    private Finding checkVmDiskHeadroom() {
        try {
            var diskImage = Environment.vmDiskImage();
            if (!Files.exists(diskImage)) return Finding.ok("VM disk", "(no disk image found)");
            var store = Files.getFileStore(diskImage.getParent());
            long usable = store.getUsableSpace();
            long total = store.getTotalSpace();
            if (total == 0) return Finding.ok("VM disk", "(no space info)");
            long usedPct = (total - usable) * 100 / total;
            var detail = String.format("%dMiB free / %dMiB total (%d%% used)",
                    usable / (1024 * 1024), total / (1024 * 1024), usedPct);
            if (usable < 2L * 1024 * 1024 * 1024) {
                return Finding.warn("VM host disk low", detail, null);
            }
            return Finding.ok("VM host disk", detail);
        } catch (Exception e) {
            return Finding.ok("VM disk", "(could not check)");
        }
    }

    /** Where forwarder streams are leaking, inferred from host vs in-guest connection counts. */
    enum LeakLayer {
        FORWARDER("forwarder is lingering children (link 3) — the in-VM forwarder-restart clears it"),
        VFKIT("vfkit is not reaping host fds (link 2) — a VM restart is required");
        final String description;
        LeakLayer(String description) { this.description = description; }
    }

    static LeakLayer leakLayer(int hostCount, int guestCount) {
        return guestCount * 2 <= hostCount ? LeakLayer.VFKIT : LeakLayer.FORWARDER;
    }

    private static String append(String detail, String extra) {
        if (detail == null || detail.isBlank()) return extra;
        return detail + " " + extra;
    }

    private static void restartForwarderViaAgent() {
        if (!VmAgentClient.restartForwarder()) {
            throw new RuntimeException("control agent did not confirm forwarder restart");
        }
    }

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

    // ---- Layer 4: Proxy ----

    private List<Finding> checkProxy() {
        var incus = RuntimeServices.incus();
        return List.of(checkProxyRunning(incus), checkProxyVersionDrift(incus));
    }

    private Finding checkProxyRunning(IncusClient incus) {
        var status = ProxyHealthCheck.check(incus);
        return switch (status) {
            case RUNNING -> Finding.ok("Proxy running", "");
            case WAITING_FOR_DNS -> Finding.warn("Proxy running", "(waiting for DNS configuration)", null);
            case NOT_RUNNING -> {
                if (ProxyService.isInstalled()) {
                    yield Finding.fail("Proxy not running", "(service installed but inactive)",
                            new Remediation("Restart proxy service", false, () -> ProxyService.restart()));
                }
                yield Finding.fail("Proxy not running", "",
                        new Remediation("Start with 'isx proxy start' or install service with 'isx init'", false, null));
            }
            case STALE_DNS -> Finding.fail("Proxy not running", "(stale DNS overrides still active)",
                    new Remediation("Start proxy to restore connectivity", false, null));
        };
    }

    private Finding checkProxyVersionDrift(IncusClient incus) {
        try {
            var info = ProxyHealthCheck.fetchProxyInfo(ProxyHealthCheck.healthAddress(incus));
            if (info == null) return Finding.ok("Proxy version", "(proxy not reachable, skipped)");
            var drift = ProxyHealthCheck.checkVersionDrift(info);
            if (drift.isEmpty()) return Finding.ok("Proxy version", "matches CLI");
            if (ProxyService.isActive()) {
                return Finding.warn("Proxy version drift", drift,
                        new Remediation("Restart proxy service to update", false, () -> ProxyService.restart()));
            }
            return Finding.warn("Proxy version drift", drift,
                    new Remediation("Restart proxy: isx proxy stop && isx proxy start", false, null));
        } catch (Exception e) {
            return Finding.ok("Proxy version", "(could not check)");
        }
    }

    // ---- Layer 5: DNS + bridge plumbing ----

    private List<Finding> checkDnsAndBridge() {
        var findings = new ArrayList<Finding>();
        var incus = RuntimeServices.incus();
        findings.add(checkBridgeDns(incus));
        if (Environment.isLinux()) {
            var iptablesFinding = checkIptablesRedirect();
            if (iptablesFinding != null) findings.add(iptablesFinding);
        }
        return findings;
    }

    private Finding checkBridgeDns(IncusClient incus) {
        try {
            if (MitmProxy.isBridgeDnsComplete(incus)) {
                return Finding.ok("Bridge DNS overrides",
                        "all " + MitmProxy.interceptedDomains().size() + " domains configured");
            }
            var overrides = MitmProxy.getDnsOverrides(incus);
            if (overrides.isEmpty()) {
                return Finding.fail("Bridge DNS overrides", "not configured",
                        new Remediation("Configure bridge DNS", false,
                                () -> MitmProxy.writeBridgeDns(RuntimeServices.incus())));
            }
            var missing = MitmProxy.interceptedDomains().stream()
                    .filter(d -> !overrides.contains("address=/" + d + "/"))
                    .sorted()
                    .toList();
            return Finding.warn("Bridge DNS overrides incomplete",
                    "missing: " + String.join(", ", missing),
                    new Remediation("Reconfigure bridge DNS", false,
                            () -> MitmProxy.writeBridgeDns(RuntimeServices.incus())));
        } catch (Exception e) {
            return Finding.warn("Bridge DNS overrides", "(could not check: " + e.getMessage() + ")", null);
        }
    }

    private Finding checkIptablesRedirect() {
        if (!FirewalldCheck.isInstalled()) {
            return Finding.warn("Firewall redirect", "(firewalld not installed, cannot verify)", null);
        }
        if (!FirewalldCheck.isActive()) {
            return Finding.fail("Firewall not running", "(iptables redirect requires firewalld)",
                    new Remediation("Enable firewalld and re-run 'isx init'", false, null));
        }
        try {
            var pb = new ProcessBuilder("firewall-cmd", "--direct", "--get-all-rules");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return Finding.warn("Firewall PREROUTING redirect", "(could not query firewalld rules)", null);
            }
            if (isPreRoutingRulePresent(output, MitmProxy.DEFAULT_MITM_PORT)) {
                return Finding.ok("Firewall PREROUTING redirect", "443 -> " + MitmProxy.DEFAULT_MITM_PORT);
            }
            return Finding.fail("Firewall PREROUTING redirect", "rule not found",
                    new Remediation("Re-run 'isx init' to configure iptables rules", false, null));
        } catch (Exception e) {
            return Finding.warn("Firewall PREROUTING redirect", "(check failed: " + e.getMessage() + ")", null);
        }
    }

    static boolean isPreRoutingRulePresent(String firewalldOutput, int mitmPort) {
        return FirewalldCheck.isPreRoutingRulePresent(firewalldOutput, mitmPort);
    }

    // ---- Layer 6: Templates ----

    private List<Finding> checkTemplates() {
        var findings = new ArrayList<Finding>();
        try {
            var incus = RuntimeServices.incus();
            var currentCaFp = CertificateAuthority.exists() ? CertificateAuthority.currentCaFingerprint() : "";
            var currentVersion = BuildInfo.instance().version();
            var allDefs = ImageDef.loadAll();

            int builtCount = 0;
            for (var name : allDefs.keySet()) {
                if (!incus.exists(name)) continue;
                builtCount++;

                var storedCaFp = incus.configGet(name, Metadata.CA_FINGERPRINT);
                if (!storedCaFp.isEmpty() && !currentCaFp.isEmpty() && !storedCaFp.equals(currentCaFp)) {
                    findings.add(Finding.warn("Template " + name + " CA mismatch",
                            "template CA differs from current",
                            new Remediation("Rebuild: isx build " + name, false, null)));
                }

                var buildVersion = incus.configGet(name, Metadata.BUILD_VERSION);
                if (!buildVersion.isEmpty() && !buildVersion.equals(currentVersion)) {
                    findings.add(Finding.warn("Template " + name + " built with " + buildVersion,
                            "(current: " + currentVersion + ")", null));
                }
            }

            if (findings.isEmpty() && builtCount > 0) {
                findings.add(Finding.ok("Templates", builtCount + " checked, all current"));
            }
        } catch (Exception e) {
            findings.add(Finding.warn("Templates", "(could not check: " + e.getMessage() + ")", null));
        }
        return findings;
    }

    // ---- Layer 7: Per-instance (--deep) ----

    private List<Finding> checkInstances() {
        var findings = new ArrayList<Finding>();
        try {
            var incus = RuntimeServices.incus();
            var instances = incus.list();
            var running = instances.stream()
                    .filter(i -> "Running".equals(i.get("status")))
                    .toList();

            if (running.isEmpty()) {
                findings.add(Finding.ok("Instances", "no running instances to check"));
                return findings;
            }

            var gatewayIp = "";
            try {
                gatewayIp = MitmProxy.resolveGatewayIp(incus);
            } catch (Exception ignored) {}

            for (var inst : running) {
                var name = inst.get("name");
                if (name.startsWith("tpl-")) continue;
                findings.addAll(checkSingleInstance(incus, name, gatewayIp));
            }
        } catch (Exception e) {
            findings.add(Finding.warn("Instances", "(could not check: " + e.getMessage() + ")", null));
        }
        return findings;
    }

    private List<Finding> checkSingleInstance(IncusClient incus, String name, String gatewayIp) {
        var findings = new ArrayList<Finding>();
        var prefix = name + ": ";

        // Check /etc/resolv.conf
        try {
            var result = incus.shellExec(name, "cat", "/etc/resolv.conf");
            if (result.success()) {
                var content = result.stdout();
                if (!gatewayIp.isEmpty() && content.contains("nameserver " + gatewayIp)) {
                    findings.add(Finding.ok(prefix + "resolv.conf", "points to gateway"));
                } else if (gatewayIp.isEmpty()) {
                    findings.add(Finding.ok(prefix + "resolv.conf", "(gateway unknown, skipped)"));
                } else {
                    findings.add(Finding.warn(prefix + "resolv.conf",
                            "does not point to gateway " + gatewayIp, null));
                }
            } else {
                findings.add(Finding.warn(prefix + "resolv.conf", "(could not read)", null));
            }
        } catch (Exception e) {
            findings.add(Finding.warn(prefix + "resolv.conf", "(exec failed)", null));
        }

        // Check DNS resolution of an intercepted domain
        var probeDomain = MitmProxy.interceptedDomains().iterator().next();
        if (!gatewayIp.isEmpty()) {
            try {
                var result = incus.shellExec(name, "sh", "-c",
                        "getent hosts " + probeDomain + " 2>/dev/null | awk '{print $1}'");
                if (result.success() && result.stdout().strip().equals(gatewayIp)) {
                    findings.add(Finding.ok(prefix + "DNS interception", probeDomain + " -> gateway"));
                } else if (result.success()) {
                    findings.add(Finding.warn(prefix + "DNS interception",
                            probeDomain + " resolves to " + result.stdout().strip()
                                    + " (expected " + gatewayIp + ")", null));
                } else {
                    findings.add(Finding.warn(prefix + "DNS interception", "(getent failed)", null));
                }
            } catch (Exception e) {
                findings.add(Finding.warn(prefix + "DNS interception", "(exec failed)", null));
            }
        }

        // Check TLS handshake through proxy (end-to-end probe)
        try {
            var result = incus.shellExec(name,
                    "curl", "-sf", "--max-time", "5", "-o", "/dev/null",
                    "https://" + probeDomain);
            if (result.success()) {
                findings.add(Finding.ok(prefix + "TLS proxy handshake", "successful"));
            } else {
                findings.add(Finding.warn(prefix + "TLS proxy handshake",
                        "(failed, exit " + result.exitCode() + " — CA trust or proxy issue)", null));
            }
        } catch (Exception e) {
            findings.add(Finding.warn(prefix + "TLS proxy handshake", "(exec failed)", null));
        }

        return findings;
    }

    // ---- Bundle generation ----

    private static final ObjectMapper JSON = new ObjectMapper();

    private void generateBundle(List<Finding> findings) {
        try {
            var timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            var bundleDir = Files.createTempDirectory("isx-doctor-");
            try {
                Files.writeString(bundleDir.resolve("findings.txt"), formatFindings(findings));
                Files.writeString(bundleDir.resolve("findings.json"), findingsToJson(findings));
                Files.writeString(bundleDir.resolve("versions.txt"), collectVersions());
                copyLogTail(Environment.proxyLogFile(), bundleDir.resolve("proxy.log"), 1000);
                copyLogTail(Environment.clientLogFile(), bundleDir.resolve("client.log"), 1000);
                if (Environment.isMacOS()) {
                    copyLogTail(Environment.vmLogFile(), bundleDir.resolve("vm.log"), 1000);
                    copyLogTail(Environment.vmStateDir().resolve("proxy-service.log"),
                            bundleDir.resolve("proxy-service.log"), 1000);
                }
                Files.writeString(bundleDir.resolve("proxy-status.txt"), collectProxyStatus());
                Files.writeString(bundleDir.resolve("config-sanitized.yaml"), sanitizedConfig());
                writeInstanceList(bundleDir.resolve("instances.json"));
                Files.writeString(bundleDir.resolve("service-status.txt"), collectServiceStatus());

                var outputDir = Environment.vmStateDir();
                Files.createDirectories(outputDir);
                var archivePath = outputDir.resolve("isx-doctor-" + timestamp + ".tar.gz");
                var pb = new ProcessBuilder("tar", "czf", archivePath.toString(),
                        "-C", bundleDir.toString(), ".");
                pb.redirectErrorStream(true);
                var process = pb.start();
                process.getInputStream().readAllBytes();
                if (process.waitFor() != 0) {
                    System.err.println("Failed to create support archive.");
                    return;
                }
                System.out.println("\nSupport archive: " + archivePath);
            } finally {
                try (var walk = Files.walk(bundleDir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to generate bundle: " + e.getMessage());
        }
    }

    private String formatFindings(List<Finding> findings) {
        var sb = new StringBuilder();
        for (var f : findings) {
            var detail = f.detail() == null || f.detail().isBlank() ? "" : " " + f.detail();
            sb.append(f.status().symbol).append(" ").append(f.label()).append(detail).append("\n");
        }
        return sb.toString();
    }

    static String findingsToJson(List<Finding> findings) {
        var root = JSON.createArrayNode();
        for (var f : findings) {
            var node = JSON.createObjectNode();
            node.put("status", f.status().name());
            node.put("label", f.label());
            node.put("detail", f.detail() != null ? f.detail() : "");
            if (f.remediation() != null) {
                node.put("remediation", f.remediation().description());
            }
            root.add(node);
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String collectVersions() {
        var info = BuildInfo.instance();
        var sb = new StringBuilder();
        sb.append("isx version: ").append(info.version()).append("\n");
        sb.append("isx git SHA: ").append(info.gitSha()).append("\n");
        sb.append("isx runtime: ").append(info.runtime()).append("\n");
        try {
            sb.append("Incus server: ").append(IncusClient.daemonVersion()).append("\n");
        } catch (Exception e) {
            sb.append("Incus server: unknown\n");
        }
        sb.append("OS: ").append(System.getProperty("os.name")).append(" ")
                .append(System.getProperty("os.version")).append("\n");
        sb.append("Arch: ").append(System.getProperty("os.arch")).append("\n");
        sb.append("Java: ").append(System.getProperty("java.version", "n/a")).append("\n");
        return sb.toString();
    }

    private String collectProxyStatus() {
        var sb = new StringBuilder();
        try {
            var incus = RuntimeServices.incus();
            var status = ProxyHealthCheck.check(incus);
            sb.append("Status: ").append(status.name()).append("\n");
            var info = ProxyHealthCheck.fetchProxyInfo(ProxyHealthCheck.healthAddress(incus));
            if (info != null) {
                sb.append("Version: ").append(info.version()).append("\n");
                sb.append("Git SHA: ").append(info.gitSha()).append("\n");
                sb.append("Runtime: ").append(info.runtime()).append("\n");
                sb.append("CA fingerprint: ").append(info.caFingerprint()).append("\n");
                sb.append("DNS configured: ").append(info.dnsConfigured()).append("\n");
            }
        } catch (Exception e) {
            sb.append("Error: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    static String sanitizedConfig() {
        var config = SpawnConfig.load();
        config.getClaude().clearAuth();
        config.getGithub().setToken("");
        try {
            var yaml = new ObjectMapper(new YAMLFactory());
            return yaml.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        } catch (Exception e) {
            return "# could not serialize config: " + e.getMessage();
        }
    }

    private void writeInstanceList(Path dest) {
        try {
            var incus = RuntimeServices.incus();
            Files.writeString(dest, incus.listJsonConfig());
        } catch (Exception e) {
            try {
                Files.writeString(dest, "[]");
            } catch (IOException ignored) {}
        }
    }

    private String collectServiceStatus() {
        var sb = new StringBuilder();
        sb.append("Platform: ").append(Environment.isMacOS() ? "macOS" : "Linux").append("\n");
        sb.append("Service installed: ").append(ProxyService.isInstalled()).append("\n");
        sb.append("Service active: ").append(ProxyService.isActive()).append("\n");
        return sb.toString();
    }

    private void copyLogTail(Path src, Path dest, int maxLines) {
        try {
            if (!Files.exists(src)) {
                Files.writeString(dest, "(file not found: " + src + ")");
                return;
            }
            // Use a bounded deque to avoid loading the entire file into memory
            var tail = new java.util.ArrayDeque<String>(maxLines);
            try (var reader = Files.newBufferedReader(src)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (tail.size() == maxLines) tail.removeFirst();
                    tail.addLast(line);
                }
            }
            Files.write(dest, tail);
        } catch (Exception e) {
            try {
                Files.writeString(dest, "(could not read: " + e.getMessage() + ")");
            } catch (IOException ignored) {}
        }
    }
}
