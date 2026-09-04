package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.LayeredDefinitions;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FirewalldCheck;
import dev.incusspawn.incus.UfwCheck;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.vm.VmAgentClient;
import dev.incusspawn.vm.VmManager;
import dev.incusspawn.Platform;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

        var findings = Platform.isLinux() ? runLinuxChecks() : runMacChecks();

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
        if (r.action() == null) {
            System.out.println("     " + r.description());
            return;
        }
        var console = System.console();
        if (console == null) {
            System.out.println("     fix: " + r.description() + " (re-run in a terminal to apply)");
            return;
        }
        var warn = r.destructive() ? " This is disruptive." : "";
        if (!askConfirmation(console, "     " + r.description() + "." + warn + " Apply now?", false)) {
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
        findings.addAll(checkStoragePool());
        findings.add(checkInotifyBudget());
        findings.addAll(checkProxy());
        findings.addAll(checkDnsAndBridge());
        findings.add(checkInstanceSubnets());
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
        if (!config.getClaude().hasAuth()) {
            missing.add("claude");
        }
        var unresolved = dev.incusspawn.proxy.ToolProxyResolver.findUnresolved(config);
        for (var u : unresolved) {
            missing.add(u.toolName() + " " + u.configKey());
        }
        if (missing.isEmpty()) return Finding.ok("Credentials", "configured");
        return Finding.warn("Missing credentials", "(" + String.join(", ", missing) + ")",
                new Remediation("Run 'isx init' to configure", false, null));
    }

    // ---- Layer 2: Incus daemon ----

    static final long GIB = 1024L * 1024 * 1024;
    static final long MIN_POOL_SIZE = 100 * GIB;

    private List<Finding> checkStoragePool() {
        try {
            var incus = RuntimeServices.incus();
            var pools = incus.listPools();
            String pool = null;
            for (var e : pools.entrySet()) {
                if (IncusClient.isCowDriver(e.getValue())) { pool = e.getKey(); break; }
            }
            if (pool == null) {
                var poolDesc = pools.isEmpty() ? "no storage pools configured"
                        : String.join(", ", pools.entrySet().stream()
                                .map(e -> "'" + e.getKey() + "' (" + e.getValue() + ")")
                                .toList());
                Remediation remediation = null;
                if (Platform.isLinux()) {
                    remediation = new Remediation(
                            "Create a btrfs pool: sudo incus storage create cow btrfs size=100GiB",
                            false,
                            () -> {
                                var r1 = new ProcessBuilder("sudo", "mkdir", "-p", "/var/lib/incus/disks")
                                        .inheritIO().start().waitFor();
                                if (r1 != 0) throw new RuntimeException(
                                        "Failed to create /var/lib/incus/disks directory");
                                var r2 = new ProcessBuilder("sudo", "incus", "storage", "create",
                                        "cow", "btrfs", "size=100GiB")
                                        .inheritIO().start().waitFor();
                                if (r2 != 0) throw new RuntimeException(
                                        "Failed to create pool — ensure the 'loop' kernel module is loaded (sudo modprobe loop)");
                            });
                }
                return List.of(Finding.fail("No copy-on-write storage pool",
                        poolDesc + " — every branch and derived build is a full rsync copy:"
                                + " minutes instead of seconds, and the template's full size on disk each time",
                        remediation));
            }
            var findings = new ArrayList<Finding>();
            findings.addAll(checkInstancesOffCowPool(incus, pool, pools));
            var usage = incus.getPoolUsageBytes(pool);
            if (usage == null) {
                findings.add(Finding.ok("Storage pool " + pool, "(no usage info)"));
                return findings;
            }
            var usageString = formatPoolUsage(pool, usage);
            findings.add(evaluateStorageUsage(pool, usageString));
            if (usage.percent() > 90) {
                findings.addAll(analyzePoolUsage(incus, pool, usage));
            }
            return findings;
        } catch (Exception e) {
            return List.of(Finding.warn("Storage pool", "(could not check: " + e.getMessage() + ")", null));
        }
    }

    private List<Finding> checkInstancesOffCowPool(IncusClient incus, String cowPool,
                                                    Map<String, String> pools) {
        var instancePools = incus.instanceRootPools();
        var offPool = classifyOffCowPool(cowPool, pools, instancePools);
        if (offPool.isEmpty()) return List.of();
        var findings = new ArrayList<Finding>();
        for (var entry : offPool.entrySet()) {
            var poolName = entry.getKey();
            var driver = pools.getOrDefault(poolName, "unknown");
            var names = entry.getValue();
            var listed = names.size() <= 5 ? String.join(", ", names)
                    : String.join(", ", names.subList(0, 5)) + ", … (" + names.size() + " total)";
            findings.add(Finding.warn(
                    names.size() + " instance(s) not on CoW pool '" + cowPool + "'",
                    "on '" + poolName + "' (" + driver + "): " + listed
                            + " — branching from these is a full copy;"
                            + " rebuild templates with 'isx build <name>' or move with 'incus move <name> --storage " + cowPool + "'",
                    null));
        }
        return findings;
    }

    static Map<String, List<String>> classifyOffCowPool(String cowPool,
                                                         Map<String, String> pools,
                                                         Map<String, String> instancePools) {
        var result = new LinkedHashMap<String, List<String>>();
        for (var entry : instancePools.entrySet()) {
            var pool = entry.getValue();
            if (pool.equals(cowPool)) continue;
            if (IncusClient.isCowDriver(pools.getOrDefault(pool, ""))) continue;
            result.computeIfAbsent(pool, k -> new ArrayList<>()).add(entry.getKey());
        }
        return result;
    }

    static String formatPoolUsage(String poolName, IncusClient.PoolUsage usage) {
        return "%s pool: %dMiB used / %dMiB total (%d%% full)".formatted(
                poolName,
                usage.usedBytes() / (1024 * 1024),
                usage.totalBytes() / (1024 * 1024),
                usage.percent());
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
                return Finding.warn("Storage pool " + poolName + " nearly full",
                        usageString + " — run 'isx clean pool' to reclaim space", null);
            }
        }
        return Finding.ok("Storage pool " + poolName, usageString);
    }

    private List<Finding> analyzePoolUsage(IncusClient incus, String pool,
                                            IncusClient.PoolUsage usage) {
        var findings = new ArrayList<Finding>();

        // 1. Pool too small — suggest resize
        var resizeFinding = evaluatePoolSize(pool, usage, incus);
        if (resizeFinding != null) {
            findings.add(resizeFinding);
        }

        var instances = incus.list();

        // 2. Failed-build instances
        findings.addAll(findFailedBuilds(incus));

        // 3. Unused base images
        findings.addAll(findUnusedImages(incus));

        // 4. DNF cache volume
        var dnfFinding = findDnfCache(incus, pool);
        if (dnfFinding != null) {
            findings.add(dnfFinding);
        }

        // 5. Stopped non-template instances (informational)
        var stoppedFinding = findStoppedInstances(instances);
        if (stoppedFinding != null) {
            findings.add(stoppedFinding);
        }

        return findings;
    }

    static Finding evaluatePoolSize(String pool, IncusClient.PoolUsage usage,
                                     IncusClient incus) {
        long total = usage.totalBytes();
        long maxSize = maxResizeBytes(incus, pool, total);

        if (total >= MIN_POOL_SIZE) {
            if (usage.percent() > 90) {
                long newSize = total * 2;
                if (maxSize > 0 && newSize > maxSize) newSize = maxSize;
                if (newSize <= total) {
                    return Finding.warn("Pool " + pool + " is full",
                            "currently " + (total / GIB) + "GiB — cannot enlarge further,"
                                    + " host filesystem has insufficient free space", null);
                }
                String newSizeStr = (newSize / GIB) + "GiB";
                return Finding.warn("Pool " + pool + " could be enlarged",
                        "currently " + (total / GIB) + "GiB — the pool is thin-provisioned"
                                + " (a sparse file that only uses real disk space as data is written,"
                                + " so enlarging is free)",
                        new Remediation("Resize to " + newSizeStr, false,
                                () -> incus.resizePool(pool, newSizeStr)));
            }
            return null;
        }
        long target = MIN_POOL_SIZE;
        if (maxSize > 0 && target > maxSize) target = maxSize;
        if (target <= total) {
            return Finding.warn("Pool " + pool + " is undersized",
                    (total / GIB) + "GiB — default is " + (MIN_POOL_SIZE / GIB) + "GiB,"
                            + " but host filesystem has insufficient free space to enlarge", null);
        }
        String targetStr = (target / GIB) + "GiB";
        return Finding.warn("Pool " + pool + " is undersized",
                (total / GIB) + "GiB — default is " + (MIN_POOL_SIZE / GIB) + "GiB."
                        + " The pool is thin-provisioned (a sparse file that only uses real disk space"
                        + " as data is written), so resizing does not consume additional host disk",
                new Remediation("Resize pool to " + targetStr, false,
                        () -> incus.resizePool(pool, targetStr)));
    }

    static long maxResizeBytes(IncusClient incus, String pool, long currentPoolSize) {
        try {
            if (incus == null) return 0;
            var source = incus.getPoolSource(pool);
            if (source == null) return 0;
            var path = Path.of(source);
            long freeSpace = Files.getFileStore(path).getUsableSpace();
            return currentPoolSize + freeSpace;
        } catch (Exception e) {
            return 0;
        }
    }

    private List<Finding> findFailedBuilds(IncusClient incus) {
        var findings = new ArrayList<Finding>();
        for (var name : CleanCommand.findFailedBuilds(incus)) {
            findings.add(Finding.warn("Failed build: " + name, "can be deleted to reclaim space",
                    new Remediation("Delete " + name, true,
                            () -> incus.delete(name, true))));
        }
        return findings;
    }

    private List<Finding> findUnusedImages(IncusClient incus) {
        var findings = new ArrayList<Finding>();
        try {
            for (var image : CleanCommand.findUnusedImages(incus)) {
                findings.add(Finding.warn("Unused image: " + image.label(),
                        CleanCommand.formatSize(image.size()),
                        new Remediation("Delete image " + image.label(), true, () -> {
                            for (var alias : image.aliases()) {
                                incus.deleteImageAlias(alias);
                            }
                            incus.deleteImage(image.fingerprint());
                        })));
            }
        } catch (Exception ignored) {}
        return findings;
    }

    private Finding findDnfCache(IncusClient incus, String pool) {
        try {
            if (incus.storageVolumeExists(pool, BuildCommand.DNF_CACHE_VOLUME)) {
                return Finding.warn("DNF build cache volume exists",
                        "can be deleted to reclaim space (will be recreated on next build)",
                        new Remediation("Delete DNF cache volume", false,
                                () -> incus.deleteStorageVolume(pool, BuildCommand.DNF_CACHE_VOLUME)));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Finding findStoppedInstances(List<Map<String, String>> instances) {
        var stopped = new ArrayList<String>();
        for (var inst : instances) {
            var name = inst.get("name");
            if (name.startsWith("tpl-")) continue;
            if (name.endsWith("-failed-build")) continue;
            if ("Stopped".equals(inst.get("status"))) {
                stopped.add(name);
            }
        }
        if (!stopped.isEmpty()) {
            return Finding.warn("Stopped instances",
                    stopped.size() + " stopped: " + String.join(", ", stopped)
                            + " — delete with 'isx destroy <name>'", null);
        }
        return null;
    }

    static final int INOTIFY_RECOMMENDED_MIN = 1024;

    private Finding checkInotifyBudget() {
        try {
            var incus = RuntimeServices.incus();
            int limit = incus.getInotifyMaxInstances();
            if (limit < 0) return Finding.ok("Inotify budget", "(could not read)");

            var instances = incus.list();
            long running = instances.stream()
                    .filter(i -> "Running".equals(i.get("status")))
                    .count();
            long estimated = running * 10;

            if (limit < INOTIFY_RECOMMENDED_MIN) {
                return Finding.warn("Inotify instance limit low",
                        "max_user_instances=" + limit + " (recommended ≥" + INOTIFY_RECOMMENDED_MIN
                                + "); at ~10 per container, builds will fail around "
                                + (limit / 10) + " concurrent containers",
                        new Remediation(
                                "Run 'isx init' to raise fs.inotify.max_user_instances to 8192",
                                false, null));
            }
            if (estimated >= limit * 0.7) {
                return Finding.warn("Inotify budget nearly exhausted",
                        running + " running containers × ~10 ≈ " + estimated
                                + " instances, limit is " + limit,
                        new Remediation(
                                "Run 'isx init' to raise fs.inotify.max_user_instances",
                                false, null));
            }
            return Finding.ok("Inotify budget",
                    "max_user_instances=" + limit + " (" + running + " running containers)");
        } catch (Exception e) {
            return Finding.ok("Inotify budget", "(could not check)");
        }
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
        BuildOutput.header("Restarting VM");
        VmManager.stop();
        if (!VmManager.start()) throw new RuntimeException("VM failed to start");
    }

    // ---- Layer 4: Proxy ----

    private List<Finding> checkProxy() {
        var incus = RuntimeServices.incus();
        var findings = new ArrayList<Finding>();
        findings.add(checkProxyRunning(incus));
        findings.addAll(checkProxyDrift(incus));
        return findings;
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

    private List<Finding> checkProxyDrift(IncusClient incus) {
        try {
            var info = ProxyHealthCheck.fetchProxyInfo(ProxyHealthCheck.healthAddress(incus));
            if (info == null) return List.of(Finding.ok("Proxy version", "(proxy not reachable, skipped)"));
            var drifts = ProxyHealthCheck.checkDrift(info);
            if (drifts.isEmpty()) {
                return List.of(Finding.ok("Proxy version", "matches CLI"));
            }
            Remediation restart = ProxyService.isActive()
                    ? new Remediation("Restart proxy service to update", false,
                            () -> ProxyService.reinstallIfChanged(incus))
                    : new Remediation("Restart proxy: isx proxy stop && isx proxy start", false, null);
            var findings = new ArrayList<Finding>();
            for (var drift : drifts) {
                findings.add(Finding.warn("Proxy drift", drift,
                        findings.isEmpty() ? restart : null));
            }
            return findings;
        } catch (Exception e) {
            return List.of(Finding.ok("Proxy version", "(could not check)"));
        }
    }

    // ---- Layer 5: DNS + bridge plumbing ----

    private List<Finding> checkDnsAndBridge() {
        var findings = new ArrayList<Finding>();
        var incus = RuntimeServices.incus();
        findings.add(checkBridgeDns(incus));
        if (Platform.isLinux()) {
            var iptablesFinding = checkIptablesRedirect();
            if (iptablesFinding != null) findings.add(iptablesFinding);
        }
        return findings;
    }

    private Finding checkBridgeDns(IncusClient incus) {
        try {
            var toolProxyDomains = dev.incusspawn.proxy.ToolProxyResolver.resolvedDomains(
                    SpawnConfig.load());
            var allDomains = ProxyConfig.interceptedDomains(toolProxyDomains);
            if (ProxyConfig.isBridgeDnsComplete(incus, allDomains)) {
                return Finding.ok("Bridge DNS overrides",
                        "all " + allDomains.size() + " domains configured");
            }
            var overrides = ProxyConfig.getDnsOverrides(incus);
            if (overrides.isEmpty()) {
                return Finding.fail("Bridge DNS overrides", "not configured",
                        new Remediation("Configure bridge DNS", false,
                                () -> ProxyConfig.writeBridgeDns(RuntimeServices.incus(),
                                        ProxyConfig.interceptedDomains(
                                                dev.incusspawn.proxy.ToolProxyResolver.resolvedDomains(
                                                        SpawnConfig.load())))));
            }
            var missing = allDomains.stream()
                    .filter(d -> !overrides.contains("address=/" + d + "/"))
                    .sorted()
                    .toList();
            return Finding.warn("Bridge DNS overrides incomplete",
                    "missing: " + String.join(", ", missing),
                    new Remediation("Reconfigure bridge DNS", false,
                            () -> ProxyConfig.writeBridgeDns(RuntimeServices.incus(),
                                    ProxyConfig.interceptedDomains(
                                            dev.incusspawn.proxy.ToolProxyResolver.resolvedDomains(
                                                    SpawnConfig.load())))));
        } catch (Exception e) {
            return Finding.warn("Bridge DNS overrides", "(could not check: " + e.getMessage() + ")", null);
        }
    }

    private Finding checkIptablesRedirect() {
        if (FirewalldCheck.isActive()) {
            return checkFirewalldRedirect();
        }
        if (UfwCheck.isActive()) {
            return checkUfwRedirect();
        }
        if (FirewalldCheck.isInstalled()) {
            return Finding.fail("Firewall not running", "(firewalld installed but inactive)",
                    new Remediation("Enable firewalld and re-run 'isx init'", false, null));
        }
        if (UfwCheck.isInstalled()) {
            return Finding.fail("Firewall not running", "(UFW installed but inactive)",
                    new Remediation("Enable UFW and re-run 'isx init'", false, null));
        }
        return Finding.warn("Firewall redirect", "(no firewall installed, cannot verify)", null);
    }

    private Finding checkFirewalldRedirect() {
        try {
            var pb = new ProcessBuilder("firewall-cmd", "--direct", "--get-all-rules");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return Finding.warn("Firewall PREROUTING redirect", "(could not query firewalld rules)", null);
            }
            var gatewayIp = ProxyConfig.resolveGatewayIp(RuntimeServices.incus());
            if (isPreRoutingRulePresent(output, ProxyConfig.DEFAULT_MITM_PORT, gatewayIp)) {
                return Finding.ok("Firewall PREROUTING redirect (firewalld)", "443 -> " + ProxyConfig.DEFAULT_MITM_PORT);
            }
            var staleIp = FirewalldCheck.extractRedirectGatewayIp(output, ProxyConfig.DEFAULT_MITM_PORT);
            if (staleIp != null) {
                return Finding.fail("Firewall PREROUTING redirect",
                        "rule points to stale gateway " + staleIp + " (current: " + gatewayIp + ")",
                        new Remediation("Re-run 'isx init' to update iptables rules", false, null));
            }
            return Finding.fail("Firewall PREROUTING redirect", "rule not found",
                    new Remediation("Re-run 'isx init' to configure iptables rules", false, null));
        } catch (Exception e) {
            return Finding.warn("Firewall PREROUTING redirect", "(check failed: " + e.getMessage() + ")", null);
        }
    }

    private Finding checkUfwRedirect() {
        try {
            var gatewayIp = ProxyConfig.resolveGatewayIp(RuntimeServices.incus());
            var beforeRules = UfwCheck.readBeforeRules();
            if (beforeRules.isEmpty()) {
                return Finding.warn("Firewall PREROUTING redirect", "(could not read /etc/ufw/before.rules)", null);
            }
            if (UfwCheck.hasPreRoutingRedirect(beforeRules, ProxyConfig.DEFAULT_MITM_PORT, gatewayIp)) {
                return Finding.ok("Firewall PREROUTING redirect (UFW)", "443 -> " + ProxyConfig.DEFAULT_MITM_PORT);
            }
            var staleIp = UfwCheck.extractRedirectGatewayIp(beforeRules, ProxyConfig.DEFAULT_MITM_PORT);
            if (staleIp != null) {
                return Finding.fail("Firewall PREROUTING redirect",
                        "rule points to stale gateway " + staleIp + " (current: " + gatewayIp + ")",
                        new Remediation("Re-run 'isx init' to update firewall rules", false, null));
            }
            return Finding.fail("Firewall PREROUTING redirect", "rule not found in /etc/ufw/before.rules",
                    new Remediation("Re-run 'isx init' to configure firewall rules", false, null));
        } catch (Exception e) {
            return Finding.warn("Firewall PREROUTING redirect", "(check failed: " + e.getMessage() + ")", null);
        }
    }

    static boolean isPreRoutingRulePresent(String firewalldOutput, int mitmPort, String gatewayIp) {
        return FirewalldCheck.isPreRoutingRulePresent(firewalldOutput, mitmPort, gatewayIp);
    }

    // ---- Layer 5b: Instance subnet consistency ----

    private Finding checkInstanceSubnets() {
        try {
            var incus = RuntimeServices.incus();
            var stale = InstanceLifecycle.findStaleSubnetInstances(incus);
            if (stale.isEmpty()) {
                return Finding.ok("Instance network config", "(all on current subnet)");
            }
            var names = stale.size() <= 3
                    ? String.join(", ", stale)
                    : stale.get(0) + ", " + stale.get(1) + " + " + (stale.size() - 2) + " more";
            return Finding.fail("Instance network config",
                    "(" + stale.size() + " on stale subnet: " + names + ")",
                    new Remediation("Migrate all instances to current bridge subnet",
                            false,
                            () -> {
                                var incusClient = RuntimeServices.incus();
                                var migrated = InstanceLifecycle.migrateAllInstancesToNewSubnet(
                                        incusClient);
                                System.out.println("Migrated " + migrated + " instance"
                                        + (migrated == 1 ? "" : "s") + ".");
                                var remaining = InstanceLifecycle.findStaleSubnetInstances(
                                        incusClient);
                                if (!remaining.isEmpty()) {
                                    System.err.println("Warning: " + remaining.size()
                                            + " instance(s) could not be migrated: "
                                            + String.join(", ", remaining));
                                }
                                System.out.println("Note: running instances may need"
                                        + " a restart for network changes to take effect.");
                            }));
        } catch (Exception e) {
            return Finding.warn("Instance network config",
                    "(could not check: " + e.getMessage() + ")", null);
        }
    }

    // ---- Layer 6: Templates ----

    private List<Finding> checkTemplates() {
        var findings = new ArrayList<Finding>();
        try {
            var incus = RuntimeServices.incus();
            var caTrust = CertificateAuthority.CaTrust.snapshot();
            var currentVersion = BuildInfo.instance().version();
            var loaded = ImageDef.loadAllWithConflicts();
            var allDefs = loaded.defs();

            // Same-directory collisions are always a mistake and make builds ambiguous;
            // cross-layer overrides are intentional but surfacing them explains the
            // "built image doesn't match the file I'm editing" confusion.
            addDefinitionFindings(findings, loaded.conflicts(), loaded.overrides());
            var toolLoader = RuntimeServices.toolDefLoader();
            addDefinitionFindings(findings, toolLoader.conflicts(), toolLoader.overrides());

            int builtCount = 0;
            for (var name : allDefs.keySet()) {
                if (!incus.exists(name)) continue;
                builtCount++;

                switch (caTrust.classify(incus.configGet(name, Metadata.CA_FINGERPRINT))) {
                    // Instances self-repair on first use, so a rebuild only refreshes the
                    // template's own baked copy — informational, not something to act on.
                    case REPAIRABLE -> findings.add(Finding.warn(
                            "Template " + name + " has the pre-upgrade CA cert",
                            "instances are fixed automatically on first use", null));
                    case FOREIGN -> findings.add(Finding.warn(
                            "Template " + name + " CA mismatch", "template CA differs from current",
                            new Remediation("Rebuild: isx build " + name, false, null)));
                    default -> { }
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

    /** Turn definition-loading diagnostics (image or tool) into doctor findings. */
    private static void addDefinitionFindings(List<Finding> findings,
            List<LayeredDefinitions.NameConflict> conflicts,
            List<LayeredDefinitions.LayerOverride> overrides) {
        for (var conflict : conflicts) {
            findings.add(Finding.warn(capitalize(conflict.kind()) + " name conflict: " + conflict.name(),
                    conflict.shortMessage(), null));
        }
        for (var override : overrides) {
            findings.add(Finding.ok(capitalize(override.kind()) + " override: " + override.name(),
                    override.overridingSource() + " overrides " + override.overriddenSource()));
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
                gatewayIp = ProxyConfig.resolveGatewayIp(incus);
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
        var probeDomain = ProxyConfig.interceptedDomains().iterator().next();
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
                if (Platform.isMacOS()) {
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
        sb.append("Platform: ").append(Platform.isMacOS() ? "macOS" : "Linux").append("\n");
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
