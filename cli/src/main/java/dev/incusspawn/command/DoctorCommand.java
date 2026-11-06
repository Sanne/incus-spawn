package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.config.LayeredDefinitions;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.config.McpScope;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.config.UnreadConfigKeys;
import dev.incusspawn.config.YamlErrors;
import dev.incusspawn.incus.BridgeAddress;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.BtrfsUsage;
import dev.incusspawn.incus.FirewalldCheck;
import dev.incusspawn.incus.UfwCheck;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusDiagnostics;
import dev.incusspawn.incus.InstanceSubvolumes;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.InstanceNetwork;
import dev.incusspawn.proxy.BridgeDns;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.InstanceRegistry;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.ProofToken;
import dev.incusspawn.proxy.ProxyActivity;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyHolder;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.proxy.ToolProxyResolver;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.MacKeychainTrust;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.SystemTrust;
import dev.incusspawn.vm.VmAgentClient;
import dev.incusspawn.vm.VmManager;
import dev.incusspawn.Platform;
import io.hyperfoil.tools.jjq.value.JqValue;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TRAILING_PERIOD = Pattern.compile("\\.$");

    @Option(name = "bundle", hasValue = false,
            description = "Collect findings and logs into a redacted support archive (tar.gz); implies --deep")
    boolean bundle;

    @Option(name = "deep", hasValue = false,
            description = "Run per-instance checks (DNS, TLS, resolv.conf)")
    boolean deep;

    // Output for scripts (#1036): see OutputFormat for what plain and json promise.
    @Option(name = "format", completer = Completers.Format.class, description = "Output format: table (default), plain or json")
    String format;

    /**
     * {@code NOTE} states a fact that is neither healthy nor broken -- something the user may
     * want to know about their setup, but which nothing is waiting on. It is deliberately not a
     * quiet WARN: everything that reads a status ({@link #exitFor}, the "issues can be addressed"
     * list, the closing summary) asks {@link #isProblem()}, so a note never turns a clean run
     * into a reported problem.
     */
    enum Status {
        OK("✓"), NOTE("·"), WARN("⚠"), FAIL("✗");
        final String symbol;
        Status(String symbol) { this.symbol = symbol; }
        boolean isProblem() { return this == WARN || this == FAIL; }
    }

    /** A remediation a check can offer. {@code destructive} drives the confirmation wording. */
    interface Action { void run() throws Exception; }
    record Remediation(String description, boolean destructive, Action action) {}

    record Finding(Status status, String label, String detail, Remediation remediation) {
        static Finding ok(String label, String detail) { return new Finding(Status.OK, label, detail, null); }
        static Finding note(String label, String detail) { return new Finding(Status.NOTE, label, detail, null); }
        static Finding warn(String label, String detail, Remediation r) { return new Finding(Status.WARN, label, detail, r); }
        static Finding fail(String label, String detail, Remediation r) { return new Finding(Status.FAIL, label, detail, r); }
    }

    @Override
    protected CommandResult doExecute() throws Exception {
        var outputFormat = OutputFormat.parse(format);
        if (outputFormat != OutputFormat.TABLE) {
            if (bundle) throw new IllegalArgumentException("--bundle writes an archive; it cannot be combined with --format");
            // Only the findings: no remediation is offered or applied, and the exit code is the same.
            var findings = withStdoutOnStderr(() -> Platform.isLinux() ? runLinuxChecks() : runMacChecks());
            outputFormat.print(BuildOutput.results(), findingRecords(findings));
            return exitFor(findings);
        }
        BuildOutput.step("Running incus-spawn doctor");

        var findings = Platform.isLinux() ? runLinuxChecks() : runMacChecks();

        // The report is the command's answer, at the step indent of the lines around it
        BuildOutput.results().println();
        BuildOutput.results().print(formatFindings(findings).indent(BuildOutput.STEP_INDENT.length()));

        if (bundle) {
            return generateBundle(findings) ? exitFor(findings) : CommandResult.valueOf(1);
        }

        var actionable = findings.stream()
                .filter(f -> f.status().isProblem() && f.remediation() != null)
                .toList();

        if (actionable.isEmpty()) {
            boolean anyProblem = findings.stream().anyMatch(f -> f.status().isProblem());
            if (anyProblem) {
                // The report went to the results stream, which the step's spacing does not see
                BuildOutput.results().println();
                BuildOutput.step("Some checks reported issues with no automatic fix.");
            }
            else BuildOutput.success("All checks passed");
            return exitFor(findings);
        }

        var prompts = Prompts.terminal();
        try (var group = BuildOutput.group(actionable.size() + " issue(s) can be addressed", null)) {
            for (var f : actionable) {
                BuildOutput.step(f.status().symbol + " " + f.label());
                applyOrSuggest(f.remediation(), prompts);
            }
        }
        return exitFor(findings);
    }

    /**
     * A bundle is the artifact we ask a user to attach to an issue, and the in-container DNS
     * and TLS probes are its most conclusive evidence — a bundle without them is one we would
     * have to ask them to generate again. So {@code --bundle} runs them too.
     */
    boolean deepChecks() {
        return deep || bundle;
    }

    /** The Incus every check reads; a test answers it from a {@code FakeIncusDaemon}. */
    IncusClient incus() {
        return RuntimeServices.incus();
    }

    private CommandResult exitFor(List<Finding> findings) {
        boolean anyFail = findings.stream().anyMatch(f -> f.status() == Status.FAIL);
        return anyFail ? CommandResult.valueOf(1) : CommandResult.SUCCESS;
    }

    /** Either the loaded definitions or the message from a failed load -- never both null. */
    private record ImageDefsLoad(LayeredDefinitions<ImageDef> loaded, String errorMessage) {}

    private ImageDefsLoad imageDefsLoad;

    /**
     * Loads and caches the full image definition tree for this run. Layer 1 (credentials, via
     * {@code toolInUse}) and Layer 6 ({@code checkTemplates}) both need it; a command instance
     * lives for exactly one invocation, so caching here means the template tree is parsed from
     * disk once per {@code isx doctor} run rather than twice.
     */
    private ImageDefsLoad loadImageDefs() {
        if (imageDefsLoad == null) {
            try {
                imageDefsLoad = new ImageDefsLoad(ImageDef.loadAllWithConflicts(), null);
            } catch (Exception e) {
                imageDefsLoad = new ImageDefsLoad(null, e.getMessage());
            }
        }
        return imageDefsLoad;
    }

    /** Prompt to apply a remediation (TTY), or print the suggestion when non-interactive. */
    static void applyOrSuggest(Remediation r, Prompts prompts) {
        if (r.action() == null) {
            BuildOutput.stepNote(r.description());
            return;
        }
        if (prompts == null) {
            BuildOutput.stepNote("fix: " + r.description() + " (re-run in a terminal to apply)");
            return;
        }
        var warn = r.destructive() ? " This is disruptive." : "";
        if (!askYesNo(prompts, r.description() + "." + warn + " Apply now?", false)) {
            BuildOutput.stepNote("skipped.");
            return;
        }
        try {
            r.action().run();
            BuildOutput.stepNote("done.");
        } catch (Exception e) {
            BuildOutput.warn("Failed: " + failure(e));
        }
    }

    // ---- macOS checks (vfkit VM + vsock tunnel + shared layers) ----

    private List<Finding> runMacChecks() {
        var findings = new ArrayList<Finding>();

        // Layer 1: Host configuration
        findings.addAll(checkHostConfig());
        findings.add(keychainTrustFinding(SystemTrust.keychain(), SystemTrust.findBundle()));
        // Said here so nobody has to find it out from a failed build or branch (#1254)
        findings.add(Finding.note("VM instances and KVM", "(not available on macOS: isx runs containers only)"));

        // Layer 2: Incus daemon (via VM)
        boolean vmRunning = VmManager.isRunning();
        findings.add(checkVmRunning(vmRunning));
        if (!vmRunning) {
            findings.add(checkRootDiskIntegrity());
        }
        boolean incusUp = false;
        if (vmRunning) {
            long t0 = System.nanoTime();
            boolean reachable = IncusClient.isReachable();
            double seconds = (System.nanoTime() - t0) / 1_000_000_000.0;
            // The forwarder is checked before the Incus finding is made: when it locates a leak
            // the no-reboot restart clears, that finding must not offer the VM restart first (#1277).
            var forwarderFinding = checkForwarderLeak();
            var incusFinding = incusReachableFinding(reachable, seconds,
                    () -> incus().checkConnectivity(), forwarderFinding);
            findings.add(incusFinding);
            incusUp = incusFinding.status() == Status.OK;
            findings.add(forwarderFinding);
            findings.add(checkApplianceVersion());
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
        // Layer 1b: when isx runs inside an instance, the credentials it was given
        findings.addAll(checkInstanceCredentials());

        // Layer 2: Incus daemon
        boolean incusUp;
        if (IncusClient.isReachable()) {
            findings.add(Finding.ok("Incus reachable", ""));
            incusUp = true;
        } else {
            var detail = incus().checkConnectivity();
            findings.add(Finding.fail("Incus not reachable", detail == null ? "" : "(" + detail + ")", null));
            incusUp = false;
        }

        if (!incusUp) return findings;
        runSharedChecks(findings);
        return findings;
    }

    /** Layers 2 (storage) through 7 — shared between Linux and macOS once Incus is reachable. */
    void runSharedChecks(List<Finding> findings) {
        findings.addAll(checkStoragePool());
        findings.add(checkInotifyBudget());
        var missingBridge = checkBridgeExists();
        // On macOS the proxy runs on the Mac and answers at 127.0.0.1, bridge or not
        if (missingBridge == null || Platform.isMacOS()) {
            findings.addAll(checkProxy());
        }
        if (missingBridge == null) {
            findings.addAll(checkDnsAndBridge());
            findings.add(checkInstanceSubnets());
        } else {
            findings.add(missingBridge);
        }
        findings.add(checkInstanceAccounts());
        findings.addAll(checkTemplates());
        findings.addAll(checkBaseImages());
        if (deepChecks()) {
            findings.addAll(checkInstances());
        }
    }

    /**
     * The bridge DNS and instance network checks read incusbr0, and so does the proxy's on Linux:
     * without it they would only repeat its absence, or end the run (#1665). Null when it exists
     * or Incus cannot say, in which case those checks report for themselves.
     */
    private Finding checkBridgeExists() {
        try {
            if (incus().networkExists(BridgeAddress.BRIDGE)) return null;
        } catch (Exception e) {
            return null;
        }
        return Finding.fail("Bridge " + BridgeAddress.BRIDGE + " missing",
                Platform.isMacOS() ? "(bridge DNS and instance network checks skipped)"
                        : "(proxy, bridge DNS and instance network checks skipped)",
                new Remediation("Run 'isx init' to create it", false, null));
    }

    // ---- Layer 1: Host configuration ----

    private List<Finding> checkHostConfig() {
        var findings = new ArrayList<Finding>();
        findings.add(checkConfigFile());
        findings.add(checkCaCertificate());
        findings.addAll(checkCredentials());
        return findings;
    }

    /**
     * What macOS's Keychain adds to isx's own TLS trust (#1467), so a user can tell why an inspection CA is or is not used.
     * {@code bundle} is the system CA bundle, or null: whether anything is left to trust is the proxy's own decision.
     */
    static Finding keychainTrustFinding(MacKeychainTrust.Trust trust, Path bundle) {
        // As the proxy decides: with no say from the Keychain it loads the bundle by path, whatever it holds
        if (!trust.isEmpty()) {
            try {
                SystemTrust.upstreamAnchors(bundle, trust);
            } catch (GeneralSecurityException e) {
                // Nothing left: the proxy refuses to start and every download fails, and a user-level process can cause it (#1552)
                return Finding.warn("Keychain CAs trusted for TLS", "(isx trusts no CA, so the proxy will not start: " + e.getMessage()
                        + (trust.problem() == null ? "" : "; " + trust.problem()) + ")", null);
            }
        }
        if (trust.problem() != null) {
            // A deny of the default roots left nothing to trust, which the check above reported
            var trusts = "isx trusts /etc/ssl/cert.pem and the CA list it was built with, no Keychain CA"
                    + (trust.denied().isEmpty() ? "" : ", less the " + trust.denied().size() + " the Keychain denies");
            return Finding.warn("Keychain trust settings unreadable", "(" + trust.problem() + "; " + trusts + ")", null);
        }
        // A deny of the default roots leaves isx trusting the Keychain's CAs alone: say so, and where to look
        var denies = (trust.defaultRootsDenied() ? "the default roots" + (trust.denied().isEmpty() ? "" : " and ") : "")
                + (trust.denied().isEmpty() ? "" : trust.denied().size());
        var denied = denies.isEmpty() ? "" : "; " + denies + " denied (security dump-trust-settings, with -d for an administrator's)";
        if (trust.trusted().isEmpty()) {
            return Finding.note("Keychain CAs trusted for TLS", "(none" + denied + "; a CA trusted only in a login keychain is not used: "
                    + "trust it system-wide with sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain <ca.pem>)");
        }
        var cas = trust.trusted().stream()
                .map(c -> c.getSubjectX500Principal().getName() + " sha256:" + CertificateAuthority.fingerprintOf(c))
                .collect(Collectors.joining("; "));
        return Finding.ok("Keychain CAs trusted for TLS", "(" + cas + denied + ")");
    }

    // ---- Layer 1b: Inside an instance ----

    /** Where {@link #instanceCredentialFindings} sends a user who wants a nested machine to share them. */
    static final String CREDENTIALS_DOCS = "https://isx.run/docs.html#credentials-reach-the-login-environment";

    private List<Finding> checkInstanceCredentials() {
        return instanceCredentialFindings(System.getenv(), Path.of(dev.incusspawn.proxy.CredentialProof.GUEST_PATH),
                Path.of(InstanceSecret.PROOFS_PATH), ProofToken::declared, DoctorCommand::guardHolds);
    }

    /**
     * Inside an instance -- its secret file exists, or the login profile named one -- whether the
     * proof tokens this shell exports are the ones the secret derives ({@link ProofToken}, #1109):
     * a box rebooted behind isx's back has no secret, and a shell opened before a proof reset or
     * upgrade keeps the proofs, or the build's placeholders, it was opened with.
     * Only what this start delivered a proof for is compared, against the token it delivered ({@link
     * ProofToken#delivered}): a variable the host's tools leave alone, or whose guard fails in this
     * image, keeps its static placeholder by design, and a value somebody else set is theirs.
     * Nothing outside the instance: on the host, {@code declared} is never read.
     */
    static List<Finding> instanceCredentialFindings(Map<String, String> env, Path guestSecret, Path proofsFile,
                                                    Supplier<List<ProofToken.Placeholder>> declared,
                                                    Predicate<String> guardHolds) {
        var named = env.get(dev.incusspawn.proxy.CredentialProof.FILE_ENV_VAR);
        boolean profileNamedOne = named != null && !named.isBlank();
        var secretFile = profileNamedOne ? Path.of(named) : guestSecret;
        if (!profileNamedOne && !Files.exists(secretFile) && !env.containsKey(InstanceSecret.FILE_ENV_VAR)) return List.of();

        var findings = new ArrayList<Finding>();
        findings.add(Finding.note("Credentials", "(reach only processes started from this login environment;"
                + " a nested container or sandbox gets them only if you pass them in: " + CREDENTIALS_DOCS + ")"));
        var redeliverText = "run 'isx shell' for this instance on the host, which restores its credential proofs";
        var redeliver = new Remediation(redeliverText, false, null);
        String secret;
        try {
            secret = Files.readString(secretFile).strip();
        } catch (java.nio.file.NoSuchFileException e) {
            secret = ""; // as the guest's own check does (InstanceSecret.GUEST_CHECK): absent and empty are both missing
        } catch (IOException e) {
            findings.add(Finding.warn("Credential secret unreadable", "(" + secretFile + ": " + e.getMessage()
                    + "; it is readable by the instance user's group only)", null));
            return findings;
        }
        if (secret.isEmpty()) {
            findings.add(Finding.warn("Credential secret missing", "(" + secretFile
                    + ": credential proofs cannot be restored in this shell, as after a reboot isx did not do)",
                    redeliver));
            return findings;
        }
        if (!InstanceSecret.isWellFormed(secret)) {
            findings.add(Finding.warn("Credential secret invalid", "(" + secretFile + " holds no secret isx made)", redeliver));
            return findings;
        }
        String proofs;
        try {
            proofs = Files.readString(proofsFile);
        } catch (java.nio.file.NoSuchFileException e) {
            findings.add(Finding.warn("Proof tokens missing", "(" + proofsFile
                    + ": this instance's secret came without them, from an older isx or a failed write)", redeliver));
            return findings;
        } catch (IOException e) {
            findings.add(Finding.warn("Proof tokens unreadable", "(" + proofsFile + ": " + e.getMessage()
                    + "; they are readable by the instance user's group only)", null));
            return findings;
        }

        var delivered = ProofToken.delivered(proofs);
        var fix = new Remediation("Start a new login shell; if it still shows this, " + redeliverText, false, null);
        List<ProofToken.Placeholder> placeholders;
        try {
            placeholders = delivered.isEmpty() ? List.of() : declared.get();
        } catch (RuntimeException e) {
            findings.add(Finding.warn("Proof tokens not checked", "(could not read the tools' placeholder declarations: "
                    + e.getMessage() + ")", null));
            return findings;
        }
        var declaredByEnv = new java.util.HashMap<String, ProofToken.Placeholder>();
        for (var placeholder : placeholders) declaredByEnv.put(placeholder.env(), placeholder);
        int matching = 0;
        boolean mismatch = false;
        var skewed = new LinkedHashSet<String>();
        for (var entry : delivered.entrySet()) {
            var name = entry.getKey();
            var token = entry.getValue();
            var value = env.get(name);
            if (value == null) continue;
            var placeholder = declaredByEnv.get(name);
            // What this start gave the variable is what the file says; this isx's own reading of the
            // declaration is only a cross-check, and where they differ no fix here would change it
            if (placeholder == null || !token.equals(placeholder.tokenFor(secret))) skewed.add(name);
            if (value.equals(token)) {
                matching++;
                continue;
            }
            var marker = token.indexOf(ProofToken.MARKER);
            var prefix = marker < 0 ? token : token.substring(0, marker);
            boolean earlier = value.startsWith(prefix + ProofToken.MARKER);
            boolean build = value.startsWith(prefix + ProofToken.STATIC_MARKER);
            if (build && placeholder != null && !placeholder.guard().isEmpty() && !guardHolds.test(placeholder.guard())) {
                mismatch = true;
                findings.add(Finding.warn(name + " requires a rebuilt image",
                        "(this image cannot refresh its API-key approval; its static placeholder cannot authorize credentials)",
                        new Remediation("Rebuild this instance's template and branch it again; a new login alone cannot fix this image",
                                false, null)));
                continue;
            }
            if (earlier || build) {
                mismatch = true;
                findings.add(Finding.warn(name + " does not match this start's secret",
                        earlier ? "(it holds an earlier start's proof token)" : "(it still holds the build's placeholder)", fix));
            }
        }
        if (!skewed.isEmpty()) {
            findings.add(Finding.note("Proof tokens not cross-checked", "(" + String.join(", ", skewed)
                    + ": this isx does not derive what the host that started the instance delivered, so its tool"
                    + " declarations differ from the host's; compared with what was delivered)"));
        }
        if (!mismatch) {
            findings.add(Finding.ok("Proof tokens", matching == 0 ? "(none to compare in this shell)"
                    : "(" + matching + " match this start's secret)"));
        }
        return findings;
    }

    /**
     * Whether a placeholder's guard holds in this login, as the profile would test it. Code-only
     * shell ({@link ProofToken.Placeholder#onlyWhen}), never read from a file. When it cannot
     * tell, it says the guard holds: a mismatch reported with its fix beats one hidden.
     */
    private static boolean guardHolds(String guard) {
        try {
            var process = new ProcessBuilder("sh", "-c", guard)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) return process.exitValue() == 0;
            process.destroyForcibly();
            return true;
        } catch (IOException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
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
            Jjq.spawnConfig(Files.readString(configFile, StandardCharsets.UTF_8));
            return Finding.ok("config.yaml", "exists and parses");
        } catch (Exception e) {
            return Finding.fail("config.yaml invalid",
                    "(" + YamlErrors.friendly("config.yaml", e) + ")", null);
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

    private List<Finding> checkCredentials() {
        var config = SpawnConfig.load();
        var unresolved = ToolProxyResolver.findUnresolved(config);
        var findings = new ArrayList<>(credentialFindings(config.getClaude().hasAuth(), unresolved, toolInUse()));
        findings.addAll(unreadKeyFindings(config.tree(), UnreadConfigKeys.trustedTools(config)));
        return findings;
    }

    /**
     * Keys in a credential namespace that no tool reads (#1733): a credential written under the
     * wrong name is kept and never used, and would otherwise only show as missing.
     */
    static List<Finding> unreadKeyFindings(JqValue tree, Map<String, ToolSetup> tools) {
        var findings = new ArrayList<Finding>();
        UnreadConfigKeys.all(tree, tools).forEach((namespace, unread) ->
                findings.add(Finding.warn("Unread keys in config.yaml",
                        // In parentheses like every detail, without the sentence's own full stop
                        "(" + TRAILING_PERIOD.matcher(UnreadConfigKeys.note(namespace, unread, tools)).replaceFirst("") + ")",
                        new Remediation("Rename or remove them in config.yaml", false, null))));
        return findings;
    }

    /**
     * Whether a template the user wrote declares this tool, directly or transitively via a
     * `requires:` chain -- the only evidence doctor has that they intend to run it. A wrapper
     * tool that requires codex pulls codex into the build exactly as if the template had listed
     * it directly, so the credential it needs must count as in use the same way. Built-in
     * definitions don't count: they ship with isx and declare tools (`tpl-dev` has `gh`) for
     * everyone, so counting them would warn every user about every credential again. Templates
     * are also not the hard gate -- `isx build` and `isx branch` refuse to run without the
     * credentials a template actually needs.
     *
     * <p>If the definitions can't be read, every tool counts as in use: the conservative
     * direction is to keep reporting, and {@code checkTemplates} surfaces the load failure.
     */
    private Predicate<String> toolInUse() {
        var loaded = loadImageDefs().loaded();
        if (loaded == null) return name -> true;
        var allTools = RuntimeServices.toolDefLoader().allToolSetups();
        var declared = new HashSet<String>();
        for (var def : loaded.defs().values()) {
            if (def.isBuiltIn()) continue;
            for (var toolRef : def.getTools()) {
                ToolSetup.addWithRequires(toolRef.getName(), allTools, declared);
            }
        }
        return declared::contains;
    }

    /**
     * Every tool's credential is offered unconditionally, so "not configured" on its own says
     * nothing about health: an OpenAI key is missing for the user who never touches codex just
     * as it is for the one whose template installs it. Only the second is a warning; the first
     * is a NOTE, which keeps `isx doctor` honest without training people to ignore it.
     *
     * <p>Anthropic credentials are the exception: isx itself uses them (`isx ask`), so there is
     * no template to check against and a missing one is always worth flagging.
     */
    static List<Finding> credentialFindings(boolean claudeConfigured,
            List<ToolProxyResolver.UnresolvedToolProxy> unresolved,
            Predicate<String> toolInUse) {
        var needed = new ArrayList<String>();
        var unused = new ArrayList<String>();
        if (!claudeConfigured) {
            needed.add("claude");
        }
        for (var u : unresolved) {
            var entry = u.toolName() + " " + u.configKey();
            if (toolInUse.test(u.toolName())) needed.add(entry); else unused.add(entry);
        }

        var findings = new ArrayList<Finding>();
        if (!needed.isEmpty()) {
            findings.add(Finding.warn("Missing credentials", "(" + String.join(", ", needed) + ")",
                    new Remediation("Run 'isx account add' to configure", false, null)));
        } else {
            findings.add(Finding.ok("Credentials",
                    unused.isEmpty() ? "configured" : "configured for the tools in use"));
        }
        if (!unused.isEmpty()) {
            findings.add(Finding.note("Credentials not configured",
                    "(" + String.join(", ", unused) + ") — no template of yours uses these tools"));
        }
        return findings;
    }

    // ---- Layer 2: Incus daemon ----

    static final long GIB = 1024L * 1024 * 1024;
    static final long MIN_POOL_SIZE = 100 * GIB;

    private List<Finding> checkStoragePool() {
        try {
            var incus = incus();
            var poolInfo = incus.listPoolInfo();
            var pools = IncusClient.drivers(poolInfo);
            String pool = null;
            for (var e : pools.entrySet()) {
                if (IncusClient.isCowDriver(e.getValue())) { pool = e.getKey(); break; }
            }
            if (pool == null) return List.of(noCowPoolFinding(pools, Platform.isLinux(),
                    Platform.isLinux() ? CowPoolBacking.filesystemOf(CowPoolBacking.POOLS_DIR) : null,
                    () -> incus.hasApiExtension(HostSetup.STORAGE_CREATE_OPTIONS_EXTENSION)));
            var findings = new ArrayList<Finding>();
            findings.addAll(checkProfilePool(incus, pool, pools));
            findings.addAll(checkInstancesOffCowPool(incus, pool, pools));
            findings.addAll(checkDiskAccounting(pool, pools.get(pool)));
            findings.addAll(checkSubvolumes(incus, pool, pools.get(pool)));
            var info = poolInfo.get(pool);
            findings.addAll(CowPoolBacking.doctorFindings(pool, info, CowPoolBacking::filesystemType,
                    incus::setPoolConfig));
            var usage = incus.getPoolUsageBytes(pool);
            if (usage == null) {
                findings.add(Finding.ok("Storage pool " + pool, "(no usage info)"));
                return findings;
            }
            var usageString = formatPoolUsage(pool, usage);
            findings.add(evaluateStorageUsage(pool, usageString));
            if (usage.percent() > 90) {
                findings.addAll(analyzePoolUsage(incus, pool, usage, info.config()));
            }
            return findings;
        } catch (Exception e) {
            return List.of(Finding.warn("Storage pool", "(could not check: " + e.getMessage() + ")", null));
        }
    }

    static Finding noCowPoolFinding(Map<String, String> pools, boolean linux, String hostFs,
                                    java.util.function.BooleanSupplier skipTrim) {
        var poolDesc = pools.isEmpty() ? "no storage pools configured"
                : String.join(", ", pools.entrySet().stream()
                        .map(e -> "'" + e.getKey() + "' (" + e.getValue() + ")")
                        .toList());
        Remediation remediation = null;
        if (linux) {
            remediation = new Remediation(
                    "btrfs".equals(hostFs) ? "Create a btrfs copy-on-write pool 'cow' as a subvolume of the host filesystem"
                            : "Create a 100 GiB btrfs copy-on-write pool 'cow'",
                    false,
                    () -> {
                        var created = CowPoolBacking.create(CowPoolBacking::filesystemOf, skipTrim,
                                CowPoolBacking::runCapturingStderr, DoctorCommand::makeDisksDir,
                                // The explanation can quote what the command printed: shown as text
                                (msg, details) -> BuildOutput.warn(msg,
                                        details.stream().map(BuildOutput::guestText).toArray(String[]::new)));
                        if (created.result().exitCode() != 0) {
                            var why = CowPoolBacking.failureExplanation(created.result().stderr());
                            throw new RuntimeException("Failed to create pool: " + (why.isEmpty()
                                    ? "Incus gave no reason; check that the 'loop' kernel module is loaded (sudo modprobe loop)"
                                    : String.join(" ", why)));
                        }
                    });
        }
        return Finding.fail("No copy-on-write storage pool",
                poolDesc + " — every branch and derived build is a full rsync copy:"
                        + " minutes instead of seconds, and the template's full size on disk each time",
                remediation);
    }

    private static void makeDisksDir() {
        var mkdir = CowPoolBacking.runCapturingStderr("sudo", "mkdir", "-p", CowPoolBacking.DISKS_DIR);
        if (mkdir.exitCode() != 0) {
            throw new RuntimeException("Failed to create " + CowPoolBacking.DISKS_DIR + ": " + mkdir.stderr().strip());
        }
    }

    static List<Finding> checkProfilePool(IncusClient incus, String cowPool,
                                          Map<String, String> pools) {
        try {
            var devices = incus.profileDevices("default");
            var profilePool = IncusClient.rootDiskPoolFromDevices(devices);
            if (profilePool == null || profilePool.equals(cowPool)) return List.of();
            var driver = pools.getOrDefault(profilePool, "unknown");
            if (IncusClient.isCowDriver(driver)) return List.of();
            return List.of(Finding.warn(
                    "Default profile root disk on non-CoW pool '" + profilePool + "'",
                    "the default profile's root disk uses '" + profilePool + "' (" + driver
                            + ") instead of CoW pool '" + cowPool + "', so new instances land on it",
                    new Remediation(
                            "Point the default profile's root disk at pool '" + cowPool + "'",
                            false,
                            () -> {
                                if (!incus.updateProfileRootDiskPool("default", cowPool)) {
                                    throw new RuntimeException("Failed to update profile root disk pool");
                                }
                            })));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Whether the btrfs pool's qgroup accounting — the source of every per-row size the TUI shows —
     * is trustworthy. The kernel flags it {@code inconsistent} after quotas are enabled on a
     * populated pool or a subvolume delete exceeds {@code drop_subtree_threshold}, and from then on
     * every counter is frozen at a plausible-looking stale value until a rescan. The TUI and builds
     * trigger that rescan automatically; this surfaces the state (and offers the rescan) for the
     * case where the automatic repair couldn't run — e.g. a sudoers rule from before it existed.
     */
    private List<Finding> checkDiskAccounting(String pool, String driver) {
        if (!"btrfs".equals(driver)) return List.of();
        var status = dev.incusspawn.incus.BtrfsUsage.status(pool);
        if (!status.available()) return List.of();                  // quota off / old kernel or agent: nothing to assess
        if (!status.enabled()) return List.of(Finding.ok("Disk accounting", "(btrfs quotas not enabled yet)"));
        var detail = "(" + status.mode() + ", drop_subtree_threshold=" + status.dropSubtreeThreshold() + ")";
        if (status.inconsistent()) {
            return List.of(Finding.warn("Disk accounting inconsistent",
                    "btrfs qgroup counters are frozen — every template/instance size is stale " + detail,
                    new Remediation("Start a btrfs quota rescan (rebuilds the counters in the background; data is untouched)",
                            false,
                            () -> {
                                if (!dev.incusspawn.incus.BtrfsUsage.rescan(pool)) {
                                    throw new RuntimeException("could not start the rescan"
                                            + (Platform.isLinux() ? " — re-run 'isx init' to refresh the sudoers rule" : ""));
                                }
                            })));
        }
        return List.of(Finding.ok("Disk accounting", "consistent " + detail));
    }

    /**
     * Orphaned subvolumes and dangling records on the btrfs CoW pool (#717): the pool's instance
     * subvolumes and Incus's records disagree. Silent when the pool cannot be inspected.
     */
    private List<Finding> checkSubvolumes(IncusClient incus, String pool, String driver) {
        if (!"btrfs".equals(driver)) return List.of();
        var scan = incus.scanSubvolumes(pool).orElse(null);
        if (scan == null) return List.of();
        var sizes = scan.orphans().isEmpty() ? Map.<String, Long>of() : BtrfsUsage.probe(pool, false);
        var loaded = loadImageDefs().loaded();
        Set<String> templates = loaded == null ? Set.of() : loaded.defs().keySet();
        var findings = subvolumeFindings(scan, templates, sizes, Platform.isMacOS());
        // Probed before offering the live remediation, not discovered after the user confirms
        // it (review on #874): an appliance too old for the verb keeps today's "wipe the pool
        // with isx vm reset" text instead of a destructive action that only then reports it
        // cannot do what it just offered.
        if (Platform.isMacOS() && !scan.orphans().isEmpty() && VmAgentClient.supportsOrphanDelete(pool)) {
            // subvolumeFindings always adds the orphan finding first when there are any (see its
            // own source, right below): replace its "wipe the whole pool" suggestion with a live
            // per-subvolume delete through the VM agent (#874). subvolumeFindings itself stays
            // pure — no VmAgentClient call — so it is testable without a VM; this impure wrapper
            // is where the action actually gets wired in.
            var orphan = findings.get(0);
            findings = new ArrayList<>(findings);
            findings.set(0, new Finding(orphan.status(), orphan.label(), orphan.detail(),
                    new Remediation("Remove them individually through the VM agent, keeping the rest of the pool",
                            true, () -> deleteOrphans(scan))));
        }
        return findings;
    }

    /**
     * Deletes each orphan through the {@code btrfs-orphan-delete} agent verb (#874). The agent
     * re-checks Incus itself, from inside the VM and in every project, before touching
     * anything — a separate trust boundary from this scan, which can be stale by the time the
     * user confirms. Stops at the first {@code error: unknown verb}: an appliance too old for
     * one of these orphans is too old for all of them, and {@code isx vm reset} is still there.
     * {@code checkSubvolumes} probes for that case before ever offering this remediation, so
     * reaching it here would mean the verb regressed between the probe and the confirm.
     */
    private static void deleteOrphans(InstanceSubvolumes.Scan scan) throws IOException {
        deleteOrphans(scan, ref -> VmAgentClient.btrfsOrphanDelete(scan.pool(), ref.kind().dir, ref.name()));
    }

    /** {@link #deleteOrphans(InstanceSubvolumes.Scan)} against an injected agent call, for tests. */
    static void deleteOrphans(InstanceSubvolumes.Scan scan,
                               java.util.function.Function<InstanceSubvolumes.Ref, Optional<String>> agent)
            throws IOException {
        var failures = new ArrayList<String>();
        for (var ref : scan.orphans()) {
            var replyOpt = agent.apply(ref);
            // Empty means no answer at all — VmAgentClient.send's own 5s timeout, same as an
            // unreachable agent — which is not the same claim as the literal "error: unknown
            // verb" an old appliance's agent actually makes (review on #874): a slow-but-working
            // verb reaching this on a later orphan would otherwise be told it is unsupported
            // while its delete finishes anyway inside the VM.
            if (replyOpt.isEmpty()) {
                throw new IOException(ref.path() + ": the VM agent did not answer in time;"
                        + " re-run 'isx doctor' to see whether the delete went through anyway");
            }
            var reply = replyOpt.get();
            if ("deleted".equals(reply)) {
                BuildOutput.stepNote("deleted " + ref.path());
            } else if ("error: unknown verb".equals(reply)) {
                throw new IOException("this appliance does not support removing a single subvolume yet;"
                        + " wipe the pool instead with 'isx vm reset', or update the appliance");
            } else {
                failures.add(ref.path() + ": " + reply);
            }
        }
        if (!failures.isEmpty()) throw new IOException(String.join("; ", failures));
    }

    static List<Finding> subvolumeFindings(InstanceSubvolumes.Scan scan, Set<String> templates,
                                           Map<String, Long> referencedBytes, boolean macOS) {
        if (scan.isClean()) {
            return List.of(Finding.ok("Pool subvolumes", "match Incus's records"));
        }
        var findings = new ArrayList<Finding>();
        var mount = BtrfsUsage.POOL_MOUNT_PREFIX + scan.pool() + "/";
        if (!scan.orphans().isEmpty()) {
            // A template's build creates <name>-rebuilding and renames it to <name>: either one
            // already on disk makes that build fail at the end with "file exists".
            var blocked = new LinkedHashSet<String>();
            var listed = new ArrayList<String>();
            for (var ref : scan.orphans()) {
                var name = ref.name();
                var template = name.endsWith(BuildCommand.REBUILDING_SUFFIX)
                        ? name.substring(0, name.length() - BuildCommand.REBUILDING_SUFFIX.length()) : name;
                if (templates.contains(template)) blocked.add(template);
                var size = referencedBytes.get(name);
                listed.add(ref.path() + (size == null ? "" : " (" + VmManager.humanSize(size) + " referenced)"));
            }
            var detail = "Incus has no record of " + String.join(", ", listed)
                    + " — the data is unreachable, still takes space, and blocks creating an instance of the same name"
                    + (blocked.isEmpty() ? "" : "; builds of " + String.join(", ", blocked)
                            + " will fail with \"file exists\"");
            var remediation = macOS
                    ? new Remediation("If nothing on the pool is worth keeping, wipe it with 'isx vm reset'"
                            + " (instances are lost; rebuild templates with 'isx build --all')", true, null)
                    : new Remediation("Check nothing there is worth keeping, then remove them: sudo btrfs subvolume delete -R "
                            + String.join(" ", scan.orphans().stream().map(r -> mount + r.path()).toList()),
                            true, null);
            var label = scan.orphans().size() + " orphaned subvolume(s) on pool '" + scan.pool() + "'";
            findings.add(blocked.isEmpty() ? Finding.warn(label, detail, remediation)
                    : Finding.fail(label, detail, remediation));
        }
        if (!scan.dangling().isEmpty()) {
            var names = scan.dangling().stream().map(InstanceSubvolumes.Ref::name).toList();
            findings.add(Finding.warn(
                    scan.dangling().size() + " instance record(s) without storage on pool '" + scan.pool() + "'",
                    String.join(", ", names) + " — Incus lists them, but the pool has no subvolume at their path"
                            + " (if a build or branch is running, re-check once it finishes). isx refuses to delete"
                            + " them: their data may be under another name, and deleting the record would orphan it",
                    null));
        }
        return findings;
    }

    /**
     * Instances whose root disk is on a pool without copy-on-write. A stopped one can be moved to
     * the CoW pool in place (a full copy, once); a running one cannot, so it is only listed.
     */
    static List<Finding> checkInstancesOffCowPool(IncusClient incus, String cowPool,
                                                   Map<String, String> pools) {
        var roots = incus.listInstanceRoots();
        var instancePools = new LinkedHashMap<String, String>();
        var running = new HashSet<String>();
        for (var r : roots) {
            instancePools.put(r.name(), r.pool());
            if (r.running()) running.add(r.name());
        }
        var offPool = classifyOffCowPool(cowPool, pools, instancePools);
        if (offPool.isEmpty()) return List.of();
        var findings = new ArrayList<Finding>();
        for (var entry : offPool.entrySet()) {
            var poolName = entry.getKey();
            var driver = pools.getOrDefault(poolName, "unknown");
            var names = entry.getValue();
            var listed = names.size() <= 5 ? String.join(", ", names)
                    : String.join(", ", names.subList(0, 5)) + ", … (" + names.size() + " total)";
            var movable = names.stream().filter(n -> !running.contains(n)).toList();
            var busy = names.stream().filter(running::contains).toList();
            var detail = "on '" + poolName + "' (" + driver + "): " + listed
                    + " — branching from these is a full copy; rebuild templates with 'isx build <name>' or move them"
                    + (busy.isEmpty() ? "" : "; " + String.join(", ", busy) + (busy.size() == 1
                            ? " is running: stop it and re-run 'isx doctor' to move it"
                            : " are running: stop them and re-run 'isx doctor' to move them"));
            var remediation = movable.isEmpty() ? null : new Remediation(
                    "Move " + movable.size() + " stopped instance(s) to pool '" + cowPool + "' (a full copy, once)",
                    false,
                    () -> {
                        var failures = new ArrayList<String>();
                        for (var name : movable) {
                            try {
                                incus.moveToPool(name, cowPool);
                            } catch (RuntimeException e) {
                                failures.add(name + ": " + e.getMessage());
                            }
                        }
                        if (!failures.isEmpty()) throw new IOException(String.join("; ", failures));
                    });
            findings.add(Finding.warn(names.size() + " instance(s) not on CoW pool '" + cowPool + "'",
                    detail, remediation));
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
                                            IncusClient.PoolUsage usage, Map<String, String> config) {
        var findings = new ArrayList<Finding>();

        // 1. Pool too small — suggest resize. Only a loop file has a size to grow; a pool on a
        // subvolume or a device is as large as what holds it.
        if (CowPoolBacking.isLoopFile(config)) {
            var resizeFinding = evaluatePoolSize(pool, usage, config.get("source"), incus);
            if (resizeFinding != null) {
                findings.add(resizeFinding);
            }
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

    static Finding evaluatePoolSize(String pool, IncusClient.PoolUsage usage, String source,
                                     IncusClient incus) {
        long total = usage.totalBytes();
        long maxSize = maxResizeBytes(source, total);

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

    static long maxResizeBytes(String source, long currentPoolSize) {
        try {
            if (source == null || source.isEmpty()) return 0;
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
                            () -> CleanCommand.deleteFailedBuild(incus, name))));
        }
        return findings;
    }

    private List<Finding> findUnusedImages(IncusClient incus) {
        var findings = new ArrayList<Finding>();
        try {
            for (var image : CleanCommand.findUnusedImages(incus)) {
                findings.add(Finding.warn("Unused image: " + image.label(),
                        CleanCommand.formatSize(image.size()),
                        new Remediation("Delete image " + image.label(), true,
                                () -> CleanCommand.deleteImage(incus, image))));
            }
        } catch (Exception ignored) {}
        return findings;
    }

    private Finding findDnfCache(IncusClient incus, String pool) {
        try {
            if (incus.storageVolumeExists(pool, GuestProvisioning.DNF_CACHE_VOLUME)) {
                return Finding.warn("DNF build cache volume exists",
                        "can be deleted to reclaim space (will be recreated on next build)",
                        new Remediation("Delete DNF cache volume", false,
                                () -> incus.deleteStorageVolume(pool, GuestProvisioning.DNF_CACHE_VOLUME)));
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
            var incus = incus();
            int limit = new IncusDiagnostics(incus).getInotifyMaxInstances();
            if (limit < 0) return Finding.ok("Inotify budget", "(could not read)");

            var instances = incus.list();
            long running = instances.stream()
                    .filter(i -> "Running".equals(i.get("status")))
                    .count();
            int perContainer = IncusDiagnostics.INOTIFY_INSTANCES_PER_CONTAINER;
            long estimated = running * perContainer;

            if (limit < INOTIFY_RECOMMENDED_MIN) {
                return Finding.warn("Inotify instance limit low",
                        "max_user_instances=" + limit + " (recommended ≥" + INOTIFY_RECOMMENDED_MIN
                                + "); at ~" + perContainer + " per container, builds will fail around "
                                + (limit / perContainer) + " concurrent containers",
                        new Remediation(
                                "Run 'isx init' to raise fs.inotify.max_user_instances to 8192",
                                false, null));
            }
            if (estimated >= limit * 0.7) {
                return Finding.warn("Inotify budget nearly exhausted",
                        running + " running containers × ~" + perContainer + " ≈ " + estimated
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

    /**
     * The Incus reachability finding on macOS. When {@code forwarder} offers the no-reboot forwarder
     * restart, leaked forwarder connections are the likely cause, so this finding defers to it: no
     * VM restart of its own, and no detail pointing at the VM (#1277).
     */
    static Finding incusReachableFinding(boolean reachable, double seconds, Supplier<String> connectivity,
                                         Finding forwarder) {
        if (forwarder.remediation() == FORWARDER_RESTART) {
            var cause = "the leaked vsock connections below are the likely cause";
            if (!reachable) return Finding.fail("Incus not reachable", "(the Incus API did not answer — " + cause + ")", null);
            if (seconds > 5.0) return Finding.warn("Incus reachable but slow", String.format("(%.1fs — %s)", seconds, cause), null);
        }
        if (!reachable) {
            var detail = connectivity.get();
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
        return forwarderLeakFinding(VmManager.vsockForwarderConnectionCount(),
                VmAgentClient::socatCount, VmAgentClient::ping);
    }

    /**
     * Turns the host's vfkit fd count and the guest's socat count into the forwarder finding, and
     * picks its fix: the no-reboot forwarder restart when the leak is in the forwarder (or cannot be
     * located) and the agent answers, a VM restart otherwise. Each agent round trip is spent only
     * when it can change the finding: {@code guest} is asked only when the host count reports a
     * leak or cannot be measured, and {@code agentAnswers} only when {@code guest} did not answer,
     * since a guest count already came back over the agent lane (#1214).
     */
    static Finding forwarderLeakFinding(int host, Supplier<OptionalInt> guest, BooleanSupplier agentAnswers) {
        var base = forwarderFinding(host);
        if (host < 0) {
            // No host count: the guest's is the only leak signal left, so it is shown.
            var guestCount = guest.get();
            return guestCount.isEmpty() ? base
                    : Finding.ok(base.label(), base.detail() + " (in-guest socat: " + guestCount.getAsInt() + ")");
        }
        if (base.status() == Status.OK) return base;

        var guestCount = guest.get();
        if (guestCount.isEmpty()) {
            // The leak cannot be located: the forwarder restart is worth trying if the agent answers.
            return agentAnswers.getAsBoolean() ? offerForwarderRestart(base, base.detail()) : base;
        }
        var layer = VmManager.leakLayer(host, guestCount.getAsInt());
        var detail = base.detail() + " (in-guest socat: " + guestCount.getAsInt() + ") — " + layer.description;
        return layer == VmManager.LeakLayer.FORWARDER
                ? offerForwarderRestart(base, detail)
                : new Finding(base.status(), base.label(), detail, base.remediation());
    }

    /** One instance, so {@link #incusReachableFinding} can tell when the forwarder finding offers it. */
    private static final Remediation FORWARDER_RESTART = new Remediation(
            "Restart the forwarder in the VM (no reboot — running containers keep going)",
            false, DoctorCommand::restartForwarderViaAgent);

    private static Finding offerForwarderRestart(Finding base, String detail) {
        return Finding.warn(base.label(), detail, FORWARDER_RESTART);
    }

    private Finding checkApplianceVersion() {
        var running = VmManager.runningApplianceVersion();
        if (running == null) return Finding.ok("Appliance version", "(unknown)");
        var skew = VmManager.applianceSkew(running);
        if (skew == null) return Finding.ok("Appliance version", running);
        return Finding.warn("Appliance outdated",
                "running " + running + ", installed " + skew.installed(),
                new Remediation("Restart the VM to apply appliance " + skew.installed()
                        + " (stops running containers)", true, DoctorCommand::restartVm));
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

    private Finding checkRootDiskIntegrity() {
        var diskImage = Environment.vmDiskImage();
        if (!Files.exists(diskImage)) {
            return Finding.ok("Root disk", "(not yet extracted — will be created on next start)");
        }
        var f = validateBtrfsSuperblock(diskImage);
        if (f.status() == Status.FAIL) {
            return new Finding(f.status(), f.label(), f.detail(),
                    new Remediation("Delete the corrupted image (re-extracted on next start)", true, () -> {
                        Files.deleteIfExists(diskImage);
                        Files.deleteIfExists(Environment.vmDiskVersion());
                    }));
        }
        return f;
    }

    static Finding validateBtrfsSuperblock(Path diskImage) {
        try {
            VmManager.validateBtrfsMagic(diskImage);
            return Finding.ok("Root disk", "btrfs superblock valid");
        } catch (IOException e) {
            return Finding.fail("Root disk corrupted", e.getMessage(), null);
        }
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
                    // Names no layer: it also stands for a VFKIT leak, which the forwarder did not cause.
                    new Remediation("Restart the VM to release the leaked vsock connections "
                            + "(stops running containers)", true, DoctorCommand::restartVm));
        }
        return Finding.ok("vsock forwarder connections: " + conns, "");
    }

    private static void restartVm() {
        BuildOutput.header("Restarting VM");
        if (!VmManager.restart()) throw new RuntimeException("VM failed to start");
    }

    // ---- Layer 4: Proxy ----

    private List<Finding> checkProxy() {
        var incus = incus();
        var findings = new ArrayList<Finding>();
        findings.add(checkProxyRunning(incus));
        ProxyHealthCheck.ProxyInfo info;
        String address = null;
        try {
            address = ProxyHealthCheck.healthAddress(incus);
            info = ProxyHealthCheck.fetchProxyInfo(address);
        } catch (Exception e) {
            info = null;
        }
        var holder = ProxyService.isInstalled() ? ProxyService.foreignHolder(info) : null;
        var holderFinding = holderFinding(holder);
        if (holderFinding != null) findings.add(holderFinding);
        // Another user's proxy: its drift, auth and callers are theirs, and their remedies,
        // restarting this user's service among them, cannot help (#1570).
        if (holder != null && holder.otherUser()) return findings;
        findings.addAll(checkProxyDrift(incus));
        var authFinding = checkProxyAuth(info);
        if (authFinding != null) findings.add(authFinding);
        if (info != null) findings.addAll(checkProxyPlacement(address));
        return findings;
    }

    /**
     * The proxy answering is not the active service's (#1570): the service cannot bind while it
     * holds the ports. Another user's fails, since instances then reach that user's CA and
     * credentials; an orphan of this user's warns.
     */
    static Finding holderFinding(ProxyHolder holder) {
        if (holder == null) return null;
        var detail = "(" + holder.describe() + " holds the ports, not the proxy service)";
        var remediation = new Remediation(holder.remedy(), false, null);
        return holder.otherUser()
                ? Finding.fail("Proxy ownership", detail, remediation)
                : Finding.warn("Proxy ownership", detail, remediation);
    }

    private static List<Finding> checkProxyPlacement(String healthAddress) {
        try {
            return placementFindings(ProxyActivity.fetch(healthAddress), Platform.isMacOS(), java.time.Instant.now());
        } catch (ProxyActivity.Unavailable e) {
            // A proxy that is down or predates /activity is reported by the checks above
            return List.of();
        }
    }

    /** How long an address miss with no traffic placed since keeps {@link #placementFindings} failing. */
    static final java.time.Duration RECENT_ADDRESS_MISS = java.time.Duration.ofMinutes(10);

    /**
     * Whether the proxy can tell its callers apart (#1586), from its host-only {@code /activity}:
     * nothing when it can, as for every other healthy check. #1582 served every macOS instance
     * the defaults for a year while the proxy reported itself healthy.
     */
    static List<Finding> placementFindings(ProxyActivity activity, boolean macOS, java.time.Instant now) {
        var findings = new ArrayList<Finding>();
        var placement = activity.placement();
        var unplaced = placement.unplacedRunning();
        if (placement.registryStale()) {
            findings.add(Finding.warn("Proxy instance listing", "(its last listing of the instances failed or has not answered;"
                            + " until one succeeds, it refuses every credential proof after a failure, and any new or renewed one while it hangs)",
                    new Remediation("Check that Incus is answering, then 'isx proxy logs'", false, null)));
        }
        if (placement.refusedAddressMisses() > 0 && !unplaced.isEmpty()) {
            // The misses never go down and an idle instance is unplaced too. #1582's signature is a
            // recent miss with no traffic placed since; anything else may have been a transient miss
            var lastMiss = placement.lastAddressMissAt();
            var failing = lastMiss != null && lastMiss.isAfter(now.minus(RECENT_ADDRESS_MISS))
                    && activity.instances().values().stream().map(i -> i.traffic().total().lastActivityAt())
                            .noneMatch(at -> at != null && at.isAfter(lastMiss));
            var names = OutputFormat.andList(unplaced.stream().map(OutputFormat::oneLine).toList());
            var one = unplaced.size() == 1;
            var misses = placement.refusedAddressMisses() + " proof(s) came from an address that is no instance since the proxy started"
                    + (lastMiss == null ? "" : ", the last "
                            + Math.max(0, java.time.Duration.between(lastMiss, now).toMinutes()) + " min ago");
            var detail = (failing
                    ? "(the proxy cannot tell " + unplaced.size() + " running instance" + (one ? "" : "s")
                            + " apart; credentials are not injected: " + misses + ", and no instance has had traffic"
                            + " attributed since: " + names + (one ? " is" : " are") + " running"
                    : "(" + misses + ", and no traffic is attributed to " + names
                            + "; if one of them is in use, the proxy cannot tell it apart")
                    + (macOS ? ". On macOS the appliance's NAT hides each instance's address" : "") + ")";
            var remediation = new Remediation("Restart the proxy with 'isx proxy restart'; if it persists, report it with"
                    + " 'isx proxy logs' (isx doctor inside an instance cannot fix this)", false, null);
            findings.add(new Finding(failing ? Status.FAIL : Status.WARN, "Proxy caller placement", detail, remediation));
        }
        if (placement.refusedProofs() > 0) {
            findings.add(Finding.warn("Credential proofs", "(" + placement.refusedProofs()
                            + " refused since the proxy started: an instance presented a proof that matches none of its own)",
                    new Remediation("In the instance, open a new login shell; if needed, run 'isx shell <name>' and"
                            + " 'isx doctor' inside it. Old API-key Claude images may need rebuilding", false, null)));
        }
        return findings;
    }

    private Finding checkProxyRunning(IncusClient incus) {
        ProxyHealthCheck.ProxyStatus status;
        try {
            status = ProxyHealthCheck.check(incus);
        } catch (Exception e) {
            return Finding.warn("Proxy running", "(could not check: " + e.getMessage() + ")", null);
        }
        return switch (status) {
            case RUNNING -> Finding.ok("Proxy running", "");
            case WAITING_FOR_DNS -> Finding.warn("Proxy running", "(waiting for DNS configuration)", null);
            // The missing-binary case is checked first so the `systemctl show` behind
            // failedWithConfigError() is not spent on a diagnosis that ignores it.
            case NOT_RUNNING -> ProxyService.resolveProxyBinaryPath() == null
                    ? missingProxyBinaryFinding()
                    : proxyNotRunningFinding(
                            ProxyService.isInstalled(), ProxyService.failedWithConfigError());
            case STALE_DNS -> Finding.fail("Proxy not running", "(stale DNS overrides still active)",
                    new Remediation("Start proxy to restore connectivity", false, null));
            case STALE_GATEWAY -> staleGatewayFinding(ProxyService.isActive());
        };
    }

    /**
     * The proxy outlived a change of the bridge's address (#919): only a restart rebinds it. A
     * service restart only helps when the service is what runs it, not a foreground proxy.
     */
    static Finding staleGatewayFinding(boolean serviceActive) {
        var detail = "(running, but on an old address of incusbr0 that instances can no longer reach)";
        return Finding.fail("Proxy unreachable", detail, serviceActive
                ? new Remediation("Restart proxy service", false, () -> ProxyService.restart())
                : new Remediation("Restart with 'isx proxy stop && isx proxy start'", false, null));
    }

    /**
     * Restarting cannot help when the binary the service runs does not exist, and this is the one
     * state where that happens in practice: a JBang install of `isx` without `isx-proxy`. The
     * remedy text comes from ProxyService so `isx doctor` and `isx proxy start` cannot disagree.
     */
    static Finding missingProxyBinaryFinding() {
        return Finding.fail("Proxy not running",
                "('isx-proxy' binary not found next to 'isx' — the proxy runs as its own "
                        + "process and the CLI cannot serve it)",
                new Remediation(ProxyService.MISSING_PROXY_REMEDIATION, false, null));
    }

    static Finding proxyNotRunningFinding(boolean installed, boolean configError) {
        if (installed) {
            if (configError) {
                return Finding.fail("Proxy not running",
                        "(service failed with a configuration error — check 'journalctl --user -u "
                                + Environment.PROXY_SERVICE_NAME + "' for details; "
                                + "if the error is about incus-admin group membership, log out and back in)",
                        new Remediation("Restart proxy service after fixing the issue", false,
                                () -> ProxyService.restart()));
            }
            return Finding.fail("Proxy not running", "(service installed but inactive)",
                    new Remediation("Restart proxy service", false, () -> ProxyService.restart()));
        }
        return Finding.fail("Proxy not running", "",
                new Remediation("Start with 'isx proxy start' or install service with 'isx init'", false, null));
    }

    private List<Finding> checkProxyDrift(IncusClient incus) {
        try {
            var info = ProxyHealthCheck.fetchProxyInfo(ProxyHealthCheck.healthAddress(incus));
            if (info == null) return List.of(Finding.ok("Proxy version", "(proxy not reachable, skipped)"));
            var report = ProxyHealthCheck.assessDrift(info);
            if (report.isEmpty()) {
                return List.of(Finding.ok("Proxy version", "matches CLI"));
            }
            Remediation restart;
            if (report.futileReason() != null) {
                restart = new Remediation(report.futileReason(), false, null);
            } else if (ProxyService.isActive()) {
                restart = new Remediation("Restart proxy service to update", false,
                        () -> ProxyService.reinstallIfChanged(incus, info));
            } else {
                restart = new Remediation("Restart proxy: isx proxy stop && isx proxy start", false, null);
            }
            var findings = new ArrayList<Finding>();
            for (var drift : report.drifts()) {
                findings.add(Finding.warn("Proxy drift", drift,
                        findings.isEmpty() ? restart : null));
            }
            return findings;
        } catch (Exception e) {
            return List.of(Finding.ok("Proxy version", "(could not check)"));
        }
    }

    private Finding checkProxyAuth(ProxyHealthCheck.ProxyInfo info) {
        if (info == null) return null;
        if (!info.hasAuthError()) return Finding.ok("Proxy auth", "credentials valid");
        var hint = info.authRemediationHint();
        return Finding.fail("Proxy auth", info.authError(),
                new Remediation("Run '" + hint + "'", false,
                        () -> runInteractive(WHITESPACE.split(hint))));
    }

    private static void runInteractive(String... command) {
        try {
            var p = new ProcessBuilder(command).inheritIO().start();
            int rc = p.waitFor();
            if (rc != 0) throw new RuntimeException("exited with code " + rc);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted");
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    // ---- Layer 5: DNS + bridge plumbing ----

    private List<Finding> checkDnsAndBridge() {
        var findings = new ArrayList<Finding>();
        var incus = incus();
        findings.add(checkBridgeDns(incus));
        if (Platform.isLinux()) {
            // Linux only: on macOS the bridge is in the VM, whose routes the Mac's are not (#1202).
            findings.add(checkBridgeSubnet(incus));
            findings.addAll(checkIptablesRedirect());
        }
        return findings;
    }

    private Finding checkBridgeSubnet(IncusClient incus) {
        try {
            return bridgeSubnetFinding(BridgeSubnetCheck.detect(incus),
                    () -> InitCommand.moveBridgeOffSubnetConflict(incus));
        } catch (Exception e) {
            return Finding.warn("Bridge subnet", "(could not check: " + e.getMessage() + ")", null);
        }
    }

    /**
     * A bridge subnet a host route (typically a VPN's) overlaps sends DNS for the gateway out
     * through that route. The fix {@code move} applies is {@code isx init}'s, narrowed to the
     * bridge and what follows its address (#1203); it detects again when applied, since the
     * routes may have changed while the user read this.
     */
    static Finding bridgeSubnetFinding(BridgeSubnetCheck.ConflictResult result, Action move) {
        if (!result.conflictDetected()) {
            return Finding.ok("Bridge subnet", result.oldSubnet() == null ? "(no IPv4 address)"
                    : "(" + result.oldSubnet() + ", clear of host routes)");
        }
        var detail = "(" + result.oldSubnet() + " overlaps host route: " + result.conflictingRoute() + ")";
        if (result.newSubnet() == null) {
            return Finding.warn("Bridge subnet conflict", detail, new Remediation(
                    "Host routes cover every subnet isx would move the bridge to ("
                            + BridgeSubnetCheck.candidateRange() + "); free one, then re-run 'isx doctor'", false, null));
        }
        return Finding.warn("Bridge subnet conflict", detail, new Remediation(
                "Move the bridge to " + result.newSubnet() + ", migrate instances to it (running ones are"
                        + " stopped and started again), and update the firewall redirect for the new gateway"
                        + " (sudo required)", true, move));
    }

    private Finding checkBridgeDns(IncusClient incus) {
        Set<String> allDomains;
        BridgeDns.Status status;
        ProxyHealthCheck.ProxyStatus proxy;
        try {
            allDomains = ProxyConfig.bridgeDnsDomains(SpawnConfig.load());
            status = ProxyConfig.bridgeDnsStatus(incus, allDomains);
            proxy = ProxyHealthCheck.check(incus);
        } catch (Exception e) {
            return Finding.warn("Bridge DNS overrides", "(could not check: " + e.getMessage() + ")", null);
        }
        return bridgeDnsFinding(proxy, status, allDomains,
                () -> ProxyConfig.writeBridgeDns(incus(), allDomains));
    }

    /**
     * Overrides only help while the proxy is listening: written without it, they send every
     * intercepted domain to a gateway where nothing answers, which is worse than bypassing it.
     * So with the proxy down this reports nothing to fix (the "Proxy running" finding carries
     * the problem), and with it up, missing overrides are a failure the rewrite repairs (#839),
     * and so are overrides that all point at a gateway the bridge no longer has (#840).
     */
    static Finding bridgeDnsFinding(ProxyHealthCheck.ProxyStatus proxy, BridgeDns.Status status,
                                    Set<String> domains, Action rewrite) {
        if (proxy == ProxyHealthCheck.ProxyStatus.NOT_RUNNING
                || proxy == ProxyHealthCheck.ProxyStatus.STALE_DNS) {
            return Finding.note("Bridge DNS overrides", "(not checked: the proxy is not running)");
        }
        if (proxy == ProxyHealthCheck.ProxyStatus.STALE_GATEWAY) {
            // Rewriting them to the current gateway would only move them to where nothing listens.
            return Finding.note("Bridge DNS overrides", "(not checked: the proxy must be restarted first)");
        }
        if (status.complete()) {
            return Finding.ok("Bridge DNS overrides", "all " + domains.size() + " domains configured");
        }
        var repair = new Remediation("Rewrite bridge DNS overrides", false, rewrite);
        if (status.legacy().isEmpty() && status.missing().size() == domains.size()) {
            return Finding.fail("Bridge DNS overrides",
                    "none configured (instances reach intercepted domains directly, bypassing the proxy)",
                    repair);
        }
        if (status.legacy().isEmpty() && status.stale().size() == domains.size()) {
            return Finding.fail("Bridge DNS overrides", status.describe(), repair);
        }
        return Finding.warn("Bridge DNS overrides incomplete", status.describe(), repair);
    }

    private List<Finding> checkIptablesRedirect() {
        var firewalld = FirewalldCheck.state();
        if (firewalld == FirewalldCheck.State.ACTIVE) {
            return List.of(checkFirewalldRedirect());
        }
        if (UfwCheck.isActive()) {
            var beforeRules = UfwCheck.readBeforeRules();
            return ufwFindings(beforeRules, true, checkUfwRedirect(beforeRules));
        }
        if (FirewalldCheck.isInstalled()) {
            var stopped = firewalldNotRunningFinding(firewalld);
            // A ufw beside it may be the firewall that could not load and disabled itself
            return UfwCheck.isInstalled() ? ufwFindings(UfwCheck.readBeforeRules(), false, stopped) : List.of(stopped);
        }
        if (UfwCheck.isInstalled()) {
            return ufwFindings(UfwCheck.readBeforeRules(), false,
                    Finding.fail("Firewall not running", "(UFW installed but inactive)",
                            new Remediation("Enable UFW and re-run 'isx init'", false, null)));
        }
        return List.of(Finding.warn("Firewall redirect", "(no firewall installed, cannot verify)", null));
    }

    /**
     * UFW's own finding, after one for a forward block an older {@code isx init} wrote into the
     * comment naming {@code COMMIT} in {@code before.rules} (#1932). ufw cannot load that file and may
     * disable itself, and then {@code isx init} stops before its repair, so the inactive case points
     * at the manual one (#1944).
     */
    static List<Finding> ufwFindings(String beforeRules, boolean active, Finding firewall) {
        if (!UfwCheck.forwardBlockMisplaced(beforeRules)) return List.of(firewall);
        var repair = active
                ? new Remediation("Re-run 'isx init', which moves the block before the COMMIT line", false, null)
                : new Remediation("Remove the block as described in " + CowPoolBacking.MIGRATIONS
                        + "#ufw-forward-rules-written-into-the-stock-commit-comment"
                        + ", then enable UFW and re-run 'isx init'", false, null);
        return List.of(Finding.fail("UFW forward rules",
                "(written into a comment in /etc/ufw/before.rules, so ufw cannot load its rules)", repair),
                firewall);
    }

    /**
     * An installed firewalld that is not running. One systemd reports {@code failed} is already
     * enabled and may have left its rules in the kernel, so it is restarted rather than enabled (#1654).
     */
    static Finding firewalldNotRunningFinding(FirewalldCheck.State state) {
        if (state == FirewalldCheck.State.FAILED) {
            return Finding.fail("Firewall not running",
                    "(firewalld failed: rules it loaded before failing may remain until the next reboot"
                            + " or reload; see 'systemctl status firewalld')",
                    new Remediation("Restart firewalld ('sudo systemctl restart firewalld'),"
                            + " then re-run 'isx init' if the redirect is missing;"
                            + " if systemd refuses, 'sudo systemctl reset-failed firewalld' first", false, null));
        }
        return Finding.fail("Firewall not running", "(firewalld installed but inactive)",
                new Remediation("Enable firewalld and re-run 'isx init'", false, null));
    }

    /**
     * firewalld lets only root read its direct rules on a server or cloud Fedora, so a user without
     * a sudo that asks for nothing just now cannot check them: a note, as nothing is known to be wrong (#1441).
     */
    static Finding directRulesUnreadable() {
        return Finding.note("Firewall PREROUTING redirect",
                "(not checked: could not read firewalld's direct rules, which a server Fedora shows only to root;"
                        + " 'sudo firewall-cmd --direct --get-all-rules' lists them)");
    }

    private Finding checkFirewalldRedirect() {
        try {
            var output = FirewalldCheck.readDirectRules();
            if (output == null) return directRulesUnreadable();
            var gatewayIp = ProxyConfig.resolveGatewayIp(incus());
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

    private Finding checkUfwRedirect(String beforeRules) {
        try {
            var gatewayIp = ProxyConfig.resolveGatewayIp(incus());
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

    /**
     * Instances pinned to an account that is no longer configured.
     *
     * <p>Worth a check of its own because the failure is silent from the host's side: the
     * proxy fails those requests closed rather than serving another account, so the user sees
     * a container that stopped working with no local symptom. There is no auto-fix -- only the
     * user knows whether the account was renamed or the instance should follow the default.
     */
    private Finding checkInstanceAccounts() {
        try {
            var incus = incus();
            var config = SpawnConfig.load();
            var dangling = new ArrayList<String>();
            var refused = new ArrayList<String>();
            var setups = AccountSelection.namespaceSetups(config);
            // One request for every instance's account state, rather than a full instance GET
            // each: this check runs on every 'isx doctor', not only under --deep.
            for (var entry : InstanceRegistry.accountStates(incus).entrySet()) {
                var state = entry.getValue();
                try {
                    AccountSelection.validate(config, state.pins(), setups);
                } catch (AccountResolver.UnknownAccountException e) {
                    dangling.add(entry.getKey() + " (" + e.namespace() + "=" + e.accountName() + ")");
                    continue;
                }
                // What the proxy refuses: an account the instance was not built for -- most
                // often the global default it follows, since moved to another Claude auth mode.
                var mismatches = AccountSelection.servingMismatches(config, setups, entry.getKey(),
                        state.pins(), state.bakedIdentities());
                if (!mismatches.isEmpty()) {
                    refused.add(entry.getKey() + " (" + String.join(", ", mismatches.keySet()) + ")");
                }
            }
            if (dangling.isEmpty() && refused.isEmpty()) {
                return Finding.ok("Instance credential accounts", "(all resolve)");
            }
            var problems = new ArrayList<String>();
            if (!dangling.isEmpty()) {
                problems.add(dangling.size() + " pinned to a missing account: " + String.join(", ", dangling));
            }
            if (!refused.isEmpty()) {
                problems.add(refused.size() + (refused.size() == 1 ? " has its" : " have their")
                        + " requests refused, being served an account of another auth mode than built for: "
                        + String.join(", ", refused));
            }
            return Finding.fail("Instance credential accounts", "(" + String.join("; ", problems) + ")",
                    new Remediation("'isx account show <instance>' says why and what to pin it to;"
                            + " re-point with 'isx account set <instance> <ns>=<account>'", false, null));
        } catch (Exception e) {
            return Finding.warn("Instance credential accounts",
                    "(could not check: " + e.getMessage() + ")", null);
        }
    }

    private Finding checkInstanceSubnets() {
        try {
            var incus = incus();
            var stale = InstanceNetwork.findStaleSubnetInstances(incus);
            if (stale.isEmpty()) {
                return Finding.ok("Instance network config", "(all on current subnet)");
            }
            var names = stale.size() <= 3
                    ? String.join(", ", stale)
                    : stale.get(0) + ", " + stale.get(1) + " + " + (stale.size() - 2) + " more";
            return Finding.fail("Instance network config",
                    "(" + stale.size() + " on stale subnet: " + names + ")",
                    new Remediation("Migrate all instances to current bridge subnet"
                            + " (running ones are stopped and started again)",
                            false,
                            () -> {
                                var incusClient = incus();
                                var migrated = InstanceNetwork.migrateAllInstancesToNewSubnet(
                                        incusClient);
                                BuildOutput.stepNote("Migrated " + migrated + " instance"
                                        + (migrated == 1 ? "" : "s") + ".");
                                var remaining = InstanceNetwork.findStaleSubnetInstances(
                                        incusClient);
                                if (!remaining.isEmpty()) {
                                    BuildOutput.warn(remaining.size()
                                            + " instance(s) could not be migrated: "
                                            + String.join(", ", remaining));
                                }
                            }));
        } catch (Exception e) {
            return Finding.warn("Instance network config",
                    "(could not check: " + e.getMessage() + ")", null);
        }
    }

    // ---- Layer 6: Templates ----

    /**
     * One warning per way a coordinator template's {@code coordinator.control-over} and {@code mcp.yaml}
     * disagree (#1812), with the same remedy {@code isx branch} and {@code isx mcp} give. Only for a
     * trusted definition whose own {@code coordinator:} says anything, since that layer decides its
     * scope alone, so a chain is reported once. Then one per coordinator instance in
     * {@code listing} (null when Incus cannot be asked) that controls no template, which covers
     * one branched with {@code --mcp-client} from a template that says nothing.
     */
    static List<Finding> mcpClientFindings(Map<String, ImageDef> defs, io.hyperfoil.tools.jjq.value.JqValue listing) {
        var byName = listing == null ? Map.<String, io.hyperfoil.tools.jjq.value.JqValue>of()
                : dev.incusspawn.proxy.InstanceRegistry.configsByName(listing);
        var callers = byName.entrySet().stream()
                .filter(e -> Metadata.isMcpCallerGrant(e.getValue().pathText(Metadata.MCP_CALLER, null)))
                .map(Map.Entry::getKey).sorted().toList();
        var coordinators = defs.values().stream()
                .filter(def -> def.getCoordinator() != null && def.getProjectRoot() == null && declaresScope(def, defs))
                .map(ImageDef::getName).sorted().toList();
        if (coordinators.isEmpty() && callers.isEmpty()) return List.of();
        List<String> approved;
        try {
            approved = McpConfig.load().templates();
        } catch (McpConfig.Invalid e) {
            return List.of(Finding.warn("MCP policy", OutputFormat.oneLine(e.getMessage()), null));
        }
        var findings = new ArrayList<Finding>();
        for (var name : coordinators) {
            for (var mismatch : McpScope.coordinator(name, null, defs, approved).mismatches()) {
                findings.add(Finding.warn("Coordinator template " + name, mismatch, null));
            }
        }
        for (var name : callers) {
            var lineage = dev.incusspawn.proxy.InstanceRegistry.lineageOf(byName.get(name), byName);
            var scope = McpScope.coordinator(lineage, defs, approved);
            if (scope.templates().isEmpty()) {
                findings.add(Finding.warn("Coordinator " + OutputFormat.oneLine(name),
                        "controls no template, and its workers are released when it next connects. "
                                + String.join(" ", scope.mismatches()), null));
            }
        }
        return findings;
    }

    /** Whether {@code def}'s own {@code coordinator:} says anything, so it decides its chain's scope alone. */
    private static boolean declaresScope(ImageDef def, Map<String, ImageDef> defs) {
        return ImageDef.coordinatorLayer(def, defs) == def;
    }

    /** Every instance's config, asked of Incus once per run: the template and base-image checks share it. */
    private io.hyperfoil.tools.jjq.value.JqValue instanceListing() {
        if (instanceListing == null) instanceListing = incus().listJsonConfig();
        return instanceListing;
    }

    private io.hyperfoil.tools.jjq.value.JqValue instanceListing;

    private List<Finding> checkTemplates() {
        var findings = new ArrayList<Finding>();
        var load = loadImageDefs();
        if (load.loaded() == null) {
            findings.add(Finding.warn("Templates", "(could not check: " + load.errorMessage() + ")", null));
            return findings;
        }
        try {
            var incus = incus();
            var caTrust = CertificateAuthority.CaTrust.snapshot();
            var currentVersion = BuildInfo.instance().version();
            var loaded = load.loaded();
            var allDefs = loaded.defs();

            // Same-directory collisions are always a mistake and make builds ambiguous;
            // cross-layer overrides are intentional but surfacing them explains the
            // "built image doesn't match the file I'm editing" confusion.
            addDefinitionFindings(findings, loaded.conflicts(), loaded.overrides());
            var toolLoader = RuntimeServices.toolDefLoader();
            addDefinitionFindings(findings, toolLoader.conflicts(), toolLoader.overrides());
            // The trusted layers isx mcp reads, not the working directory's
            io.hyperfoil.tools.jjq.value.JqValue listing;
            try {
                listing = instanceListing();
            } catch (RuntimeException e) {
                listing = null; // the coordinator templates are still checked
            }
            findings.addAll(mcpClientFindings(ImageDef.loadTrusted(), listing));

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

    /** Layer 6b: templates and instances on an older base image than a rebuild would use (#1657). */
    private List<Finding> checkBaseImages() {
        var load = loadImageDefs();
        if (load.loaded() == null) return List.of(); // checkTemplates reports why
        try {
            return baseImageFindings(instanceListing(), load.loaded().defs());
        } catch (Exception e) {
            return List.of(Finding.warn("Base images", "(could not check: " + e.getMessage() + ")", null));
        }
    }

    /**
     * One finding per release a rebuild would move to, naming the templates on an older one, the
     * projects and instances branched from them, and what to run, parents first. It is a NOTE,
     * or a WARN when a member lacks a known fix that rebuild would bring ({@link
     * BaseImageAge#KNOWN_FIXES}): the network watchdog of releases before fedora-44-20261009
     * restarted networkd every 30 s for weeks, unnoticed. Another NOTE lists those whose release
     * Incus did not record, or that does not compare.
     */
    static List<Finding> baseImageFindings(JqValue listing, Map<String, ImageDef> defs) {
        // By the release a rebuild would use, each in the listing's order. A project template is
        // a copy of its parent, so it moves by being created again, as an instance by a new branch.
        // Templates are keyed by their definition, which is what isx build takes.
        record Template(String name, String definition, String tag) {}
        // Which members lack each known fix a rebuild would bring: what makes the finding a WARN
        record Older(List<Template> templates, List<String> projects, List<String> instances, Set<String> parents,
                     Map<BaseImageAge.KnownFix, List<String>> lacking) {}
        // One finding per release, so a rebuild is planned once; releases in their own order
        var older = new TreeMap<String, Older>((a, b) -> {
            var order = BaseImageAge.compareTags(a, b);
            return order != null && order != 0 ? order : a.compareTo(b);
        });
        // The definitions whose template is on the release a rebuild would use, or a newer one
        var current = new HashSet<String>();
        var unrecorded = new ArrayList<String>();
        var incomparable = new ArrayList<String>();
        int checked = 0;
        for (var i : InstanceListing.collectEntries(listing)) {
            if (Metadata.TYPE_FAILED_BUILD.equals(i.type()) || i.name().endsWith(BuildCommand.REBUILDING_SUFFIX)) continue;
            var age = BaseImageAge.assess(i.metadata(), defs);
            if (age == null) continue;
            checked++;
            var name = OutputFormat.oneLine(i.name());
            // Not null: assess found its definition
            var definition = Metadata.templateOf(i.metadata().getField("config"));
            switch (age.standing()) {
                case OLDER -> {
                    var group = older.computeIfAbsent(age.expectedTag(), k -> new Older(new ArrayList<>(),
                            new ArrayList<>(), new ArrayList<>(), new LinkedHashSet<>(), new LinkedHashMap<>()));
                    for (var known : BaseImageAge.missingFixes(age)) {
                        group.lacking().computeIfAbsent(known, k -> new ArrayList<>()).add(name);
                    }
                    if (Metadata.TYPE_BASE.equals(i.type())) {
                        group.templates().add(new Template(name, definition, OutputFormat.oneLine(age.tag())));
                    } else {
                        (Metadata.TYPE_PROJECT.equals(i.type()) ? group.projects() : group.instances()).add(name);
                        group.parents().add(definition);
                    }
                }
                case CURRENT, NEWER -> {
                    if (Metadata.TYPE_BASE.equals(i.type())) current.add(definition);
                }
                case UNKNOWN -> {
                    if (age.tag() == null) unrecorded.add(name);
                    else incomparable.add(name + " on " + OutputFormat.oneLine(age.tag()) + " (against "
                            + OutputFormat.oneLine(age.expectedTag()) + ")");
                }
            }
        }
        // The detail lists templates parents first, the order the build step names them in
        var depths = new HashMap<String, Integer>();
        Comparator<String> parentsFirst = Comparator.comparingInt(
                d -> depths.computeIfAbsent(d, k -> ImageDef.ancestors(defs.get(k), defs).size()));
        var findings = new ArrayList<Finding>();
        older.forEach((release, group) -> {
            var parts = new ArrayList<String>();
            var steps = new ArrayList<String>();
            // What has to be built: the older templates, and the template behind an instance or
            // project here that is not known to be current (deleted, or its release unrecorded),
            // each with every layer under it that is not known to be current either. Parents
            // first: isx build builds in the order given, and copies a child from its parent as
            // built. Each chain is root first, and a layer under an earlier entry is in that
            // entry's chain, so no later one can come before its parent.
            var needed = new LinkedHashSet<String>();
            group.templates().forEach(t -> needed.add(t.definition()));
            group.parents().stream().filter(d -> !current.contains(d)).forEach(needed::add);
            var builds = new ArrayList<String>();
            for (var definition : needed) {
                for (var layer : ImageDef.chain(defs.get(definition), defs)) {
                    if (!current.contains(layer.getName()) && !builds.contains(layer.getName())) builds.add(layer.getName());
                }
            }
            if (!group.templates().isEmpty()) {
                var templates = new ArrayList<>(group.templates());
                templates.sort(Comparator.comparing(Template::definition, parentsFirst));
                parts.add("templates: " + String.join(", ", templates.stream().map(t -> t.name() + " on " + t.tag()).toList()));
            }
            if (!builds.isEmpty()) steps.add("isx build " + String.join(" ", builds.stream().map(OutputFormat::oneLine).toList()));
            if (!group.projects().isEmpty()) {
                parts.add("projects: " + String.join(", ", group.projects()));
                // One per project: each is created from its own incus-spawn.yaml
                for (var project : group.projects()) {
                    steps.add("run 'isx project create " + project + "' from its project's directory");
                }
            }
            if (!group.instances().isEmpty()) {
                parts.add("instances: " + String.join(", ", group.instances()));
                steps.add("branch " + String.join(", ", group.instances()) + " again");
            }
            var fix = builds.isEmpty()
                    ? capitalize(String.join(", then ", steps)) + " to pick up image fixes"
                    : "Rebuild to pick up image fixes: " + String.join(", then ", steps);
            // A routine newer release is a NOTE, not an issue to address (Sanne, #1664); a WARN
            // names the known fix a rebuild would bring, and what lacks it
            group.lacking().forEach((known, names) -> parts.add("before " + known.release() + " "
                    + known.problem() + ": " + String.join(", ", names)));
            findings.add(new Finding(group.lacking().isEmpty() ? Status.NOTE : Status.WARN,
                    "Base image older than " + OutputFormat.oneLine(release),
                    "(" + String.join("; ", parts) + ")", new Remediation(fix, false, null)));
        });
        var notes = new ArrayList<String>();
        if (!unrecorded.isEmpty()) notes.add("release not recorded: " + String.join(", ", unrecorded));
        if (!incomparable.isEmpty()) notes.add("release not comparable: " + String.join(", ", incomparable));
        if (!notes.isEmpty()) findings.add(Finding.note("Base images", "(" + String.join("; ", notes) + ")"));
        if (findings.isEmpty() && checked > 0) {
            findings.add(Finding.ok("Base images", "(" + checked + " checked, none older than a rebuild would use)"));
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
            var incus = incus();
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
        // A relayed domain, never one the proxy answers itself (mcp.isx.internal refuses a plain GET)
        var probeDomain = ProxyConfig.ANTHROPIC_DOMAINS.iterator().next();
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

    /** Writes the support archive and says what to do with it; false once its failure is reported. */
    private boolean generateBundle(List<Finding> findings) {
        SupportBundle.Result result;
        try {
            result = SupportBundle.generate(formatFindings(findings), findingsToJson(findings));
        } catch (Exception e) {
            BuildOutput.error("could not generate the support archive: " + failure(e));
            return false;
        }

        try (var group = BuildOutput.group("Support archive", result.archive().toString())) {
            BuildOutput.step(result.fileCount() + " files — findings, versions, logs, proxy and"
                    + " instance state, and a redacted config.");
            BuildOutput.step(describeRedactions(result));
        }
        try (var group = BuildOutput.group("What to do with it", null)) {
            // tar does not create the -C target, so the mkdir is part of the instruction, and the
            // archive path is quoted because a home directory may contain spaces.
            BuildOutput.step("1. Review it first:  mkdir -p /tmp/isx-bundle && tar xzf '"
                    + result.archive() + "' -C /tmp/isx-bundle");
            BuildOutput.step("2. Open an issue at " + SupportBundle.ISSUE_URL);
            BuildOutput.step("3. Attach the archive. See REDACTIONS.txt inside for what was withheld.");
        }
        return true;
    }

    static String describeRedactions(SupportBundle.Result result) {
        int keys = result.redactedPaths().size();
        int scrubbed = result.scrubCount();
        if (keys == 0 && scrubbed == 0) {
            return "No credentials were found to redact.";
        }
        var parts = new ArrayList<String>();
        if (keys > 0) {
            parts.add(keys + (keys == 1 ? " config key" : " config keys")
                    + " (" + String.join(", ", result.redactedPaths()) + ")");
        }
        if (scrubbed > 0) {
            parts.add(scrubbed + (scrubbed == 1 ? " value" : " values") + " in the logs");
        }
        return "Redacted " + String.join(" and ", parts) + ".";
    }

    static String formatFindings(List<Finding> findings) {
        var sb = new StringBuilder();
        for (var f : findings) {
            var detail = f.detail() == null || f.detail().isBlank() ? "" : " " + f.detail();
            sb.append(f.status().symbol).append(" ").append(f.label()).append(detail).append("\n");
            // Only an issue's remediation is offered after the list, so a note's is shown here
            if (f.status() == Status.NOTE && f.remediation() != null) {
                sb.append("    ").append(f.remediation().description()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * The fields of {@code isx doctor --format=plain|json}, in order: add to the end, never
     * rename. {@code level} is {@code ok}, {@code note}, {@code warn} or {@code fail}, and
     * {@code remediation} what would fix it, when something can.
     */
    static List<Map<String, Object>> findingRecords(List<Finding> findings) {
        var records = new ArrayList<Map<String, Object>>();
        for (var f : findings) {
            var record = new LinkedHashMap<String, Object>();
            record.put("level", f.status().name().toLowerCase(java.util.Locale.ROOT));
            record.put("label", f.label());
            record.put("detail", recordDetail(f.detail()));
            record.put("remediation", f.remediation() == null ? null : f.remediation().description());
            records.add(record);
        }
        return records;
    }

    /**
     * A finding's detail for {@code --format}: free text for people, so a script keys on
     * {@code level} and {@code label}. The parentheses the table wraps a whole detail in are
     * dropped; anything else is kept as written.
     */
    static String recordDetail(String detail) {
        if (detail == null || detail.isBlank()) return null;
        var d = detail.strip();
        if (d.length() >= 2 && d.charAt(0) == '(' && d.charAt(d.length() - 1) == ')'
                && d.indexOf(')') == d.length() - 1) {
            d = d.substring(1, d.length() - 1).strip();
        }
        return d.isEmpty() ? null : d;
    }

    static String findingsToJson(List<Finding> findings) {
        var root = io.hyperfoil.tools.jjq.value.JqArray.arrayBuilder();
        for (var f : findings) {
            var node = io.hyperfoil.tools.jjq.value.JqObject.builder(f.remediation() != null ? 4 : 3)
                    .put("status", f.status().name())
                    .put("label", f.label())
                    .put("detail", f.detail() != null ? f.detail() : "");
            if (f.remediation() != null) {
                node.put("remediation", f.remediation().description());
            }
            root.add(node.build());
        }
        return io.hyperfoil.tools.jjq.value.JqValues.toPrettyJsonString(root.build());
    }

}
