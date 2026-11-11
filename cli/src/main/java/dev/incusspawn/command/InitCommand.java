package dev.incusspawn.command;

import dev.incusspawn.ClientLog;
import dev.incusspawn.Environment;
import dev.incusspawn.config.CredentialCheck;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.config.McpScope;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.FirewallDetector.DetectionResult;
import dev.incusspawn.incus.FirewallDetector;
import dev.incusspawn.lifecycle.InstanceNetwork;
import dev.incusspawn.incus.BridgeAddress;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.McpClientRegistration;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.TerminalLink;
import dev.incusspawn.vm.VmManager;
import dev.incusspawn.Platform;
import dev.incusspawn.util.HostPath;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;
import dev.incusspawn.util.Privilege;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static dev.incusspawn.util.BuildOutput.BOLD;
import static dev.incusspawn.util.BuildOutput.CYAN;
import static dev.incusspawn.util.BuildOutput.DIM;
import static dev.incusspawn.util.BuildOutput.GREEN;
import static dev.incusspawn.util.BuildOutput.YELLOW;
import static dev.incusspawn.util.BuildOutput.stripAnsi;
import static dev.incusspawn.util.BuildOutput.styled;
import static dev.incusspawn.command.CredentialSetup.readInput;

@CommandDefinition(
        name = "init",
        description = "One-time host setup: install Incus, configure auth, test connectivity",
        generateHelp = true
)
public class InitCommand extends BaseCommand {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // The answers to init's questions that are not secrets, for a run with no terminal (#2075).
    // There is no option, variable or stdin path for a secret: a credential is the person's step.
    @OptionList(name = "search-path",
            description = "Add these directories to the template search paths, without asking (comma-separated)")
    List<String> searchPathAnswers;

    @OptionList(name = "code-dir",
            description = "Add these directories to the code directories, without asking (comma-separated)")
    List<String> codeDirAnswers;

    @Option(name = "fork-templates", hasValue = false,
            description = "Fork Sanne/incus-spawn-templates with gh if you have no copy, clone it and add it, without asking")
    boolean forkTemplates;

    @Option(name = "service", hasValue = false,
            description = "Install the proxy (on macOS, the VM and the proxy) as a service, without asking")
    boolean service;

    @Option(name = "no-service", hasValue = false, description = "Do not install the services, without asking")
    boolean noService;

    @Option(name = "no-sudo", hasValue = false,
            description = "Run nothing as root: write the steps that need root as a script for you to run, then carry on")
    boolean noSudo;

    @Option(name = "plan", hasValue = false,
            description = "Say what init would change on this host and who must do each step, changing nothing")
    boolean plan;

    // Output for scripts (#1036): see OutputFormat for what plain and json promise.
    @Option(name = "format", completer = Completers.Format.class,
            description = "The output format: table (default), plain or json; without --plan, plain and json end the run with a summary")
    String format;

    IncusClient incus;

    static final int BOX_WIDTH = 62;
    private static final String BORDER_H = "─".repeat(BOX_WIDTH);

    // Methods, not constants: whether to style is known only at run time, and a static final
    // would be decided when the native image is built.
    private static String topBorder() { return styled(CYAN, "╭" + BORDER_H + "╮"); }
    private static String bottomBorder() { return styled(CYAN, "╰" + BORDER_H + "╯"); }

    private static final String[] DNS_HINT = {
            "Configures the Incus bridge network so that containers",
            "resolve intercepted domains (GitHub, Anthropic, etc.) to",
            "the proxy gateway. This lets the MITM proxy transparently",
            "inject credentials into HTTPS requests without containers",
            "needing any special network configuration."
    };

    /**
     * How many times the DNS step writes the overrides. Incus applies a bridge's raw.dnsmasq by
     * restarting its dnsmasq, giving the old one 100ms to let go of its sockets and the new one
     * 500ms to stay up; on a busy host that can miss, and Incus then reverts the whole update, so
     * writing again starts from where the first try did. The likely cause of a one-off init
     * failure on CI (#1376); a refusal that persists still fails.
     */
    static final int BRIDGE_DNS_ATTEMPTS = 3;

    private final HostSetup host = new HostSetup(this);
    private int totalSteps;
    /** Whether {@link #totalSteps} is final: until Credential Setup, the credentials chosen may still add to it. */
    private boolean totalKnown = true;
    private int currentStep;
    private final CredentialSetup credentials = new CredentialSetup();

    private static String pad(String s, int width) {
        int vlen = stripAnsi(s).length();
        if (vlen >= width) return s;
        return s + " ".repeat(width - vlen);
    }

    private static String boxLine(String content) {
        return styled(CYAN, "│") + pad(content, BOX_WIDTH) + styled(CYAN, "│");
    }

    private static void printBanner(String title, String subtitle, String info) {
        var out = new ArrayList<String>();
        out.add("");
        out.add(topBorder());
        out.add(boxLine(""));
        out.add(boxLine("   " + styled(BOLD, title)));
        out.add(boxLine("   " + subtitle));
        out.add(boxLine(""));
        out.add(boxLine("   " + styled(DIM, info)));
        out.add(boxLine(""));
        out.add(bottomBorder());
        out.add("");
        // A frame of init's own: laid out here, drawn as given
        BuildOutput.frame(out.toArray(String[]::new));
    }

    /**
     * Init re-runs safely on a configured host, so a re-run must not read as a fresh setup that
     * forgot the existing configuration (#1119). Any marker counts, not only a current one
     * ({@link Environment#hasBeenInitialized()}): re-running init after an upgrade is the common case.
     */
    static void printSetupBanner(String platform, String info) {
        var configured = Files.exists(Environment.initCompleteMarker())
                || Files.exists(Environment.configDir().resolve("config.yaml"));
        printBanner("isx — " + (configured ? "Setup" : "First-Time Setup") + platform,
                configured ? "Reviewing your existing configuration"
                        : "Configuring your isolated development environment",
                info);
    }

    void startStep(String title, String... hintLines) {
        currentStep++;
        String left = "  " + currentStep + "  " + title;
        String right = "[" + currentStep + "/" + totalSteps + (totalKnown ? "" : "+") + "]  ";
        int gap = BOX_WIDTH - left.length() - right.length();

        var out = new ArrayList<String>();
        out.add("");
        out.add(topBorder());
        out.add(styled(CYAN, "│") + styled(BOLD, left)
                + " ".repeat(Math.max(1, gap)) + styled(DIM, right) + styled(CYAN, "│"));
        out.add(bottomBorder());

        if (hintLines.length > 0) {
            for (var line : hintLines) {
                out.add(styled(CYAN, "  ┃ ") + styled(DIM, line));
            }
            out.add("");
        }
        // A frame of init's own: laid out here, drawn as given
        BuildOutput.frame(out.toArray(String[]::new));
    }

    /** Shows {@code step}'s header, then runs it. */
    void begin(InitStep step) throws Exception {
        startStep(step.title(), step.hint());
        step.run().run();
    }

    private static void printCompletionBox(String... lines) {
        var out = new ArrayList<String>();
        out.add("");
        out.add(topBorder());
        out.add(boxLine(""));
        for (var line : lines) {
            out.add(boxLine(line));
        }
        for (var line : credentialGapLines()) {
            out.add(boxLine(line));
        }
        out.add(boxLine(""));
        out.add(bottomBorder());
        out.add("");
        // A frame of init's own: laid out here, drawn as given
        BuildOutput.frame(out.toArray(String[]::new));
    }

    /**
     * What the closing box says about each credential a known template needs that is still not
     * configured: init may have skipped one, or failed to verify it, without a word (#1438).
     * Nothing when everything is configured, or when the definitions cannot be read; the build
     * warns again either way.
     */
    private static List<String> credentialGapLines() {
        try {
            var config = SpawnConfig.load();
            var defs = ImageDef.loadAll(warning -> {});
            // A deprecated template is on its way out; a credential only it needs is not a gap
            var current = defs.values().stream().filter(d -> !d.isDeprecated()).map(ImageDef::getName).toList();
            return credentialGapLines(CredentialCheck.missingByCredential(config, current, defs,
                    new ToolDefLoader(config.getSearchPaths())), defs);
        } catch (RuntimeException e) {
            ClientLog.warn("Missing-credential check skipped: " + e);
            return List.of();
        }
    }

    /**
     * The lines for {@code missing}: each credential, and the templates {@code isx branch} refuses
     * without it. Only the topmost are named, those with no ancestor that needs it too, and the
     * rest are counted, so a credential {@code tpl-dev} needs is not repeated for every template
     * built on it.
     */
    static List<String> credentialGapLines(Map<CredentialCheck.Missing, List<String>> missing, Map<String, ImageDef> defs) {
        if (missing.isEmpty()) return List.of();
        var lines = new ArrayList<String>(List.of("", "   " + styled(BOLD + YELLOW, "Not configured:")));
        missing.forEach((credential, templates) -> {
            var topmost = templates.stream()
                    .filter(name -> ImageDef.ancestors(defs.get(name), defs).stream()
                            .noneMatch(ancestor -> templates.contains(ancestor.getName())))
                    .map(OutputFormat::oneLine)
                    .toList();
            var rest = templates.size() - topmost.size();
            var builtOn = rest == 0 ? "" : " and " + rest + (rest == 1 ? " template" : " templates")
                    + " built on " + (topmost.size() == 1 ? "it" : "them");
            var refused = "'isx branch' refuses " + OutputFormat.andList(topmost) + builtOn + ".";
            var bullet = "• ";
            for (var line : OutputFormat.wrap(OutputFormat.oneLine(credential.credential()), BOX_WIDTH - 6)) {
                lines.add("   " + bullet + line);
                bullet = "  ";
            }
            for (var line : OutputFormat.wrap(refused, BOX_WIDTH - 6)) lines.add("     " + line);
        });
        for (var line : OutputFormat.wrap(CredentialCheck.remedy(missing.keySet()), BOX_WIDTH - 6)) lines.add("   " + line);
        return lines;
    }

    /**
     * Check if init has been run. If not, print a warning and auto-launch init.
     * Call this at the top of any command that requires init (build, proxy, TUI, etc.).
     *
     * @return true if init is complete (either already or just ran), false if user aborted
     */
    public static boolean requireInit() {
        if (!requireIncusHost()) return false;
        if (hasBeenInitialized()) return true;

        // The continuation under sg (#1436) carries on from the header its parent printed
        if (!isContinuation()) {
            BuildOutput.header("First-time setup required.", "Running 'isx init'...");
        }

        try {
            var result = new InitCommand().doExecute();
            return result.getResultValue() == 0 && hasBeenInitialized();
        } catch (Exception e) {
            // A missing or blank message would say nothing, and init would fail silently
            BuildOutput.error(failure(e));
            return false;
        }
    }

    /**
     * Bump this when init gains a new infrastructure step that existing
     * installations need (new dependency, firewall rule, service, etc.).
     * A version mismatch triggers a re-run of init on the next command.
     */
    public static boolean hasBeenInitialized() {
        return Environment.hasBeenInitialized();
    }

    private static void markInitComplete() throws IOException {
        Environment.markInitComplete();
    }

    /**
     * Check that we're running on Linux. Incus is Linux-only, so this tool
     * cannot work on macOS or Windows.
     */
    public static boolean requireLinux() {
        if (!Platform.isLinux()) {
            BuildOutput.error("isx requires Linux.",
                    "Incus system containers require a Linux kernel.",
                    "Detected OS: " + System.getProperty("os.name"),
                    "Run isx on a Linux host or inside a Linux VM.");
            return false;
        }
        return true;
    }

    /**
     * Ensure an Incus daemon is reachable.
     * On Linux: Incus runs natively.
     * On macOS: auto-start the VM that hosts Incus.
     */
    public static boolean requireIncusHost() {
        if (Platform.isLinux()) {
            return true;
        }
        if (Platform.isMacOS()) {
            return VmManager.ensureRunning();
        }
        BuildOutput.error("isx requires Linux or macOS.", "Detected OS: " + System.getProperty("os.name"));
        return false;
    }

    /** The steps whose question this run skipped, for want of a terminal and of a flag answering it (#2075). */
    final Set<String> skipped = new java.util.LinkedHashSet<>();

    /** The steps whose offer was answered no, typed or by a flag such as {@code --no-service}: nothing is left there. */
    final Set<String> declined = new java.util.LinkedHashSet<>();

    /** The exit of a run that finished with steps left for the person, when a summary was asked for (#2075). */
    static final int EXIT_STEPS_LEFT = 76;

    @Override
    protected CommandResult doExecute() throws Exception {
        var outputFormat = OutputFormat.parse(format);
        if (plan) {
            if (answersGiven()) throw new IllegalArgumentException("--plan only reads; the answers to init's questions go without it");
            return printPlan(outputFormat);
        }
        checkAnswers();
        if (outputFormat == OutputFormat.TABLE) return runInit();
        // A script reads the summary on stdout, so everything on the way there goes to stderr
        var result = withStdoutOnStderr(this::runInit);
        return printSummary(outputFormat, result);
    }

    private boolean answersGiven() {
        return searchPathAnswers != null || codeDirAnswers != null || forkTemplates || service || noService;
    }

    /** Refuses answers that contradict each other, or name a directory that is not one, before anything runs. */
    void checkAnswers() {
        if (service && noService) throw new IllegalArgumentException("--service and --no-service contradict each other");
        for (var answers : java.util.Arrays.asList(searchPathAnswers, codeDirAnswers)) {
            if (answers == null) continue;
            for (var dir : answers) {
                // An empty entry would read as the working directory
                if (dir == null || dir.isBlank()) throw new IllegalArgumentException("an empty directory was given");
                if (!Files.isDirectory(Path.of(HostResourceSetup.expandHostTilde(dir)))) {
                    throw new IllegalArgumentException("'" + OutputFormat.oneLine(dir) + "' is not a directory");
                }
            }
        }
    }

    /**
     * The closing summary of a run asked for in a machine format (#2075): each step as
     * {@code isx init --plan} reports it now, in the same fields, then {@code skipped} saying init
     * skipped its question for want of a terminal, and {@code declined} saying the answer was no.
     * A run that finished with steps left for the person (a credential, the MCP approvals, a root
     * step) exits {@link #EXIT_STEPS_LEFT}.
     */
    CommandResult printSummary(OutputFormat outputFormat, CommandResult result) {
        var code = result.getResultValue();
        if (code != 0 && code != EXIT_WAITING_ON_PERSON) return result;
        var entries = planEntries();
        outputFormat.print(BuildOutput.results(), entries.stream().map(e -> summaryRecord(e, skipped.contains(e.step()), declined.contains(e.step()))).toList());
        return code == 0 && leftForThePerson(entries, appliedByScript(), noSudo) ? CommandResult.valueOf(EXIT_STEPS_LEFT) : result;
    }

    /** A step's summary record: the plan's fields, then {@code skipped} and {@code declined}. */
    static Map<String, Object> summaryRecord(InitPlan.Entry entry, boolean skipped, boolean declined) {
        var record = InitPlan.record(entry);
        record.put("skipped", skipped);
        record.put("declined", declined);
        return record;
    }

    /**
     * Whether a step is left that only the person can do: a secret, an approval, or a root change
     * the {@code --no-sudo} script has not recorded as made ({@code applied}). A run with sudo made
     * its root changes itself, so there only one the plan read as still missing is left: one only
     * root can read is not checked, and was written by this run.
     */
    static boolean leftForThePerson(List<InitPlan.Entry> entries, Set<String> applied, boolean noSudo) {
        var root = InitPlan.privileged(entries, applied).stream().filter(p -> noSudo || p.change().checked());
        // A secret or an approval counts while its step is to do; one only offered, as a template
        // the person chose not to approve, is theirs to decline
        return root.findAny().isPresent() || entries.stream().anyMatch(e ->
                e.assessment().state() == InitPlan.State.PENDING && e.assessment().changes().stream()
                        .anyMatch(c -> c.who() == InitPlan.Who.SECRET || c.who() == InitPlan.Who.APPROVAL));
    }

    /** The run itself, in whatever format its summary goes. */
    CommandResult runInit() throws Exception {
        // From here this process elevates nothing: every sudo is the script's (#2074)
        if (noSudo) Privilege.forbid();
        if (!requireIncusHost()) return CommandResult.valueOf(1);
        this.incus = RuntimeServices.incus();
        if (Platform.isMacOS()) {
            return doMacOsInit();
        }
        if (!requireLinux()) {
            return CommandResult.valueOf(1);
        }
        var continued = continuedInGroup();
        if (noSudo) {
            var waiting = privilegedStepsLeft();
            if (waiting != null) return waiting;
        }
        if (!continued && noSudo) {
            printSetupBanner("", "runs nothing as root: the script did that");
        } else if (!continued) {
            printSetupBanner("", "~3 minutes · some steps require sudo");

            BuildOutput.step("Several steps need " + styled(BOLD, "sudo") + " to install packages, configure"
                    + " the firewall, and set up user namespace mappings.");
            if (runHost("sudo", "-v") != 0) {
                BuildOutput.error("sudo authentication failed.", "Please ensure you have sudo access and try again.");
                return CommandResult.valueOf(1);
            }

            dependencies().run().run();
        }
        var proxyServiceInstalled = new AtomicBoolean();
        try {
            runLinuxSteps(finalSteps(proxyServiceStep(proxyServiceInstalled)));
        } catch (WaitingOnPerson waiting) {
            return handOver(waiting.changes);
        }

        var next = new ArrayList<>(List.of(nextSteps(linuxServices(proxyServiceInstalled.get()))));
        if (continued) next.addAll(GROUP_PENDING);
        printCompletionBox(next.toArray(String[]::new));
        return CommandResult.SUCCESS;
    }

    /** Whether this run is {@code --no-sudo}: the privileged steps are the person's script (#2074). */
    boolean noSudo() {
        return noSudo;
    }

    /** The exit of {@code --no-sudo} while the person's script has not run: try again once it has (EX_TEMPFAIL). */
    static final int EXIT_WAITING_ON_PERSON = 75;

    /** The command a person runs again once the script ran. */
    static final String NO_SUDO_COMMAND = "isx init --no-sudo";

    /**
     * Under {@code --no-sudo}, the privileged steps the plan finds left (#2074): none, and init
     * carries on (null), or a script holding them for the person, and the exit that says so.
     * A change the script recorded as made but that is still not in effect is reported, not
     * scripted again: running the same lines again would not change it.
     */
    CommandResult privilegedStepsLeft() throws IOException {
        // Always the Linux steps, never the host platform's: init calls this on Linux alone (macOS
        // init needs no administrator), so a test on any host plans what a Linux run would
        var entries = planEntries(planSteps(false));
        var applied = appliedByScript();
        var privileged = InitPlan.privileged(entries, applied);
        // A step with a change the record lacks is scripted whole: its restart or reload, made by
        // an earlier script, applies this change too
        var unrecorded = privileged.stream().filter(p -> !InitPlan.made(p.change(), applied))
                .map(InitPlan.Privileged::entry).collect(Collectors.toSet());
        var left = new ArrayList<InitPlan.Privileged>();
        for (var p : privileged) {
            if (InitPlan.made(p.change(), applied) && !unrecorded.contains(p.entry())) {
                BuildOutput.warn("The script ran, but this is still not in effect: " + p.change().what() + ".",
                        "isx init carries on; check it, and make it by hand if it is still missing.");
            } else {
                left.add(p);
            }
        }
        // A kept bridge move made by the script is forgotten; one not yet made is in the plan's MITM step
        host().forgetMadeBridgeMove();
        if (left.isEmpty()) {
            var script = Environment.privilegedInitScript();
            // Nothing is left for root: a script still lying there would only be handed over again
            try {
                if (Files.deleteIfExists(script)) {
                    BuildOutput.note("The root script's work is done, so it was removed: " + script);
                }
            } catch (IOException e) {
                // Tidying up only: init carries on, as it would with the script gone
                BuildOutput.warn("Could not remove the root script, whose work is done: " + script,
                        "Remove it yourself: " + e.getMessage());
            }
            return null;
        }
        return handOver(left);
    }

    /**
     * Thrown by a step under {@code --no-sudo} that finds, as it runs, root work only the person
     * can do: the bridge it moved needs its redirect moved too (#2118). The run hands it over as
     * a script, exit {@link #EXIT_WAITING_ON_PERSON}, rather than finishing on a proxy containers cannot reach.
     */
    static final class WaitingOnPerson extends RuntimeException {
        final transient List<InitPlan.Privileged> changes;

        WaitingOnPerson(List<InitPlan.Privileged> changes) {
            super("root work is left for the person");
            this.changes = List.copyOf(changes);
        }
    }

    /**
     * Hands {@code left} to the person: as one script with the exit that waits on them, or, when
     * one of them cannot be written as a script, as an error naming it.
     */
    CommandResult handOver(List<InitPlan.Privileged> left) throws IOException {
        var manual = left.stream().filter(p -> p.change().script().isEmpty()).map(p -> p.change().what()).toList();
        if (!manual.isEmpty()) {
            var details = new ArrayList<>(manual);
            details.add("Do " + (manual.size() == 1 ? "it" : "them") + " yourself, then run '" + NO_SUDO_COMMAND + "' again.");
            BuildOutput.error("isx init --no-sudo cannot write " + (manual.size() == 1 ? "this step" : "these steps")
                    + " as a script:", details.toArray(String[]::new));
            return CommandResult.valueOf(1);
        }
        var script = Environment.privilegedInitScript();
        Files.createDirectories(script.getParent());
        Files.writeString(script, InitPlan.script(left, NO_SUDO_COMMAND));
        try (var group = BuildOutput.group("isx init --no-sudo runs nothing as root. These steps need root", null)) {
            InitPlan.Entry step = null;
            for (var p : left) {
                if (p.entry() != step) {
                    step = p.entry();
                    BuildOutput.step(step.title());
                }
                BuildOutput.stepNote(OutputFormat.oneLine(p.change().what()));
            }
        }
        BuildOutput.step("They are written as a script, with a comment for each step: " + script);
        BuildOutput.step("Read it, then run it as root:");
        BuildOutput.stepNote("sudo sh " + script);
        BuildOutput.step("Then run '" + NO_SUDO_COMMAND + "' again to finish.");
        return CommandResult.valueOf(EXIT_WAITING_ON_PERSON);
    }

    /** The keys the script recorded in {@link InitPlan#APPLIED_RECORD} as it ran; none before it ran. */
    Set<String> appliedByScript() {
        try {
            return Set.copyOf(Files.readAllLines(hostFile(InitPlan.APPLIED_RECORD)).stream()
                    .map(String::strip).filter(l -> !l.isEmpty()).toList());
        } catch (IOException e) {
            return Set.of();
        }
    }

    /**
     * The Linux steps, then {@code finish}. A continuation under {@code sg} starts after Incus
     * Installation, which the process that started it ran.
     */
    void runLinuxSteps(List<InitStep> finish) throws Exception {
        runSteps(linuxSetup(), finish, continuedInGroup() ? 1 : 0);
    }

    /** The Linux steps before Credential Setup, in order. */
    List<InitStep> linuxSetup() {
        var host = host();
        return List.of(
                new InitStep("incus", "Incus Installation", HostSetup.INCUS_HINT, this::installIncus, host::planIncusInstallation),
                new InitStep("subids", "User Namespace Mappings", HostSetup.SUBID_HINT, host::configureSubuidSubgid, host::planSubids),
                new InitStep("storage-network", "Storage & Network", HostSetup.STORAGE_HINT, host::initializeIncus, host::planStorageAndNetwork),
                new InitStep("firewall", "Firewall Configuration", HostSetup.FIREWALL_HINT, host::configureFirewall, host::planFirewall),
                new InitStep("mitm-proxy", "MITM Authentication Proxy", HostSetup.MITM_HINT, host::configureMitmProxy, host::planMitmProxy),
                sshKeyPair());
    }

    /** What Linux init installs before its numbered steps, with no header of its own. */
    InitStep dependencies() {
        var host = host();
        return new InitStep("dependencies", "Dependencies", new String[]{
                "The commands init and isx need on the host: openssl,",
                "ssh-keygen and btrfs, installed with the package manager."},
                host::installDependencies, host::planDependencies);
    }

    /**
     * Set in the environment of the init that {@link #installIncus} continues under {@code sg}:
     * the process that started it ran the banner and Incus Installation.
     */
    static final String CONTINUED_ENV = "ISX_INIT_CONTINUED";

    /** The closing box's last lines in a continued init: the session that started it still lacks the group. */
    static final List<String> GROUP_PENDING = List.of("",
            "   Your incus-admin group is active once you log in again.",
            "   Until then, run 'newgrp incus-admin' in each terminal.");

    boolean continuedInGroup() {
        return isContinuation();
    }

    private static boolean isContinuation() {
        return System.getenv(CONTINUED_ENV) != null;
    }

    /**
     * Incus Installation, which adds the user to {@code incus-admin}. A process only gets its
     * groups at login, so when this one still cannot reach Incus, the rest of init runs under
     * {@code sg incus-admin} and this process exits with it, rather than stopping at Storage &
     * Network to ask for a new login (#1436). Without {@code sg} (Arch-family distros removed it)
     * that step still asks.
     */
    private void installIncus() {
        // Under --no-sudo the script installed it, which the plan confirmed before the steps ran
        if (noSudo()) BuildOutput.note(HostSetup.DONE_BY_SCRIPT);
        else host().checkIncusInstalled();
        continueInGroup();
    }

    /** Runs the rest of this command under {@code sg} if {@link #groupContinuation} says to, and exits with it. */
    void continueInGroup() {
        var continuation = groupContinuation();
        if (continuation == null) return;
        BuildOutput.step("Continuing in the incus-admin group, through sg");
        var exitCode = runContinuation(continuation);
        if (exitCode >= 0) exit(exitCode);
    }

    /**
     * The command that runs this one again in the {@code incus-admin} group, or null when it is
     * not needed or cannot help: this process reaches Incus, or is itself the continuation, or the
     * user is not in the group, or {@code sg} is missing. The command line is this process's own,
     * so {@code isx build}'s first-time init carries on into the build.
     */
    List<String> groupContinuation() {
        if (continuedInGroup()) return null;
        var connectivity = incusConnectivity();
        if (connectivity == null || !connectivity.contains("permission denied")) return null;
        var configured = List.of(WHITESPACE.split(captureOutput("id", "-nG", System.getProperty("user.name"))));
        if (!configured.contains("incus-admin") || !hasSg()) return null;
        var commandLine = ownCommandLine();
        var primary = captureOutput("id", "-gn");
        // captureOutput keeps stderr: a gid with no name reads as an error message, not a group
        if (commandLine.isEmpty() || primary.isEmpty() || primary.chars().anyMatch(Character::isWhitespace)) return null;
        // The inner sg gives back the primary group the outer one took, keeping incus-admin as a
        // supplementary one, so that what init writes stays in the user's group
        var command = "exec " + commandLine.stream().map(Container::shellQuote).collect(Collectors.joining(" "));
        return List.of("sg", "incus-admin", "-c",
                "exec sg " + Container.shellQuote(primary) + " -c " + Container.shellQuote(command));
    }

    /**
     * A fresh probe of the socket, never through {@link IncusClient}'s connection: that one keeps
     * a failed connect for the rest of the process, so a daemon still starting after Incus
     * Installation would decide Storage & Network's own check.
     */
    String incusConnectivity() {
        return IncusClient.diagnoseSocket();
    }

    boolean hasSg() {
        return commandExists("sg");
    }

    /** This process's argv, from {@code /proc}: the native binary, or {@code java} and its arguments. */
    List<String> ownCommandLine() {
        try {
            var cmdline = new String(Files.readAllBytes(Path.of("/proc/self/cmdline")));
            // Each argument ends in a NUL; an empty last argument is still one
            if (cmdline.endsWith("\0")) cmdline = cmdline.substring(0, cmdline.length() - 1);
            return cmdline.isEmpty() ? List.of() : List.of(cmdline.split("\0", -1));
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Runs {@code command} on this terminal, marked as the continuation, and returns its exit
     * code, or -1 when it could not start, which leaves the rest of init to this process.
     */
    int runContinuation(List<String> command) {
        BuildOutput.releaseTerminal();
        try {
            var pb = new ProcessBuilder(command).inheritIO();
            pb.environment().put(CONTINUED_ENV, "1");
            return pb.start().waitFor();
        } catch (IOException e) {
            BuildOutput.warn("Could not continue through sg: " + failure(e));
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    void exit(int exitCode) {
        System.exit(exitCode);
    }

    private CommandResult doMacOsInit() throws Exception {
        printSetupBanner(" (macOS)", "~2 minutes");
        var servicesInstalled = new AtomicBoolean();

        runSteps(macOsSetup(), finalSteps(macOsServicesStep(servicesInstalled)));

        printCompletionBox(nextSteps(servicesInstalled.get() ? Services.INSTALLED
                : skipped.contains("macos-services") ? Services.INSTALL_VM_AND_PROXY : Services.START_VM_AND_PROXY));
        return CommandResult.SUCCESS;
    }

    /**
     * What Linux's closing box says of the proxy service: a skipped question names the run that
     * installs it, with {@code --no-sudo} when this run had it, since a re-run without it asks sudo
     * for a password no terminal can type.
     */
    Services linuxServices(boolean installed) {
        if (installed) return Services.INSTALLED;
        if (!skipped.contains("proxy-service")) return Services.START_PROXY;
        return noSudo ? Services.INSTALL_PROXY_NO_SUDO : Services.INSTALL_PROXY;
    }

    /**
     * What the closing box says about the services a branch needs: that they are installed, or
     * the step that starts them.
     */
    enum Services {
        INSTALLED(null),
        START_PROXY("Start the proxy:   isx proxy start"),
        START_VM_AND_PROXY("Start VM, proxy:   isx vm start && isx proxy start"),
        /** Skipped for want of a terminal: the flag that installs them, since 'isx proxy start' holds its terminal. */
        INSTALL_PROXY("Install the proxy: isx init --service"),
        INSTALL_PROXY_NO_SUDO("Install the proxy: isx init --no-sudo --service"),
        INSTALL_VM_AND_PROXY("Install services:  isx init --service");

        final String step;

        Services(String step) { this.step = step; }
    }

    /**
     * The closing box: the Quick Start's way to a first agent (#1435), after saying the services
     * are installed, or starting them when nothing does. The template itself is YAML, so the box
     * points at the page that shows it rather than printing it.
     */
    static String[] nextSteps(Services services) {
        var lines = new ArrayList<String>(List.of(
                "   " + styled(BOLD + GREEN, "✓") + styled(BOLD, " Setup complete!"),
                ""));
        if (services.step == null) lines.addAll(List.of("   isx's services start on their own.", ""));
        lines.add("   " + styled(BOLD, "Next steps, to a first agent:"));
        var steps = new ArrayList<String>();
        if (services.step != null) steps.add(services.step);
        steps.add("Define tpl-agent:  https://isx.run/docs#quick-start");
        steps.add("Build it:          isx build tpl-agent");
        steps.add("Branch it:         isx branch my-first --from tpl-agent");
        for (int i = 0; i < steps.size(); i++) lines.add("   " + (i + 1) + ". " + steps.get(i));
        lines.add("   Or launch the TUI:    isx");
        return lines.toArray(String[]::new);
    }

    /** The work of one step of init. */
    @FunctionalInterface
    interface Step {
        void run() throws Exception;
    }

    /** How a step of init reads, without changing anything, what it would change on this host (#2073). */
    @FunctionalInterface
    interface Planner {
        InitPlan.Assessment assess() throws Exception;
    }

    /**
     * One step of init, described once: its header, its work, and what {@code isx init --plan}
     * says it would change. The run shows the header itself ({@link #begin}), so a step shows
     * exactly one, and the plan lists the same steps from the same lists, so the two cannot drift.
     * A credential's own step has no planner: Credential Setup plans the credentials together.
     *
     * @param id    the step's name in {@code --plan}'s formats, a contract
     */
    record InitStep(String id, String title, String[] hint, Step run, Planner plan) {}

    /**
     * Runs init's numbered steps: {@code setup}, Credential Setup, a step for each credential
     * chosen there, then {@code finish}. The total is counted from these lists rather than written
     * down, so {@code [n/N]} adds up on every path and the last step reads {@code [N/N]} (#906).
     * Until the choice is made it counts no credentials, which is what Enter picks, and reads
     * {@code [n/N+]}, so a total that grows at the choice was never shown as final (#1441).
     */
    void runSteps(List<InitStep> setup, List<InitStep> finish) throws Exception {
        runSteps(setup, finish, 0);
    }

    /** As {@link #runSteps(List, List)}, with the first {@code done} steps of {@code setup} run by the process that started this one. */
    void runSteps(List<InitStep> setup, List<InitStep> finish, int done) throws Exception {
        currentStep = done;
        totalSteps = setup.size() + 1 + finish.size();
        totalKnown = false;
        for (var step : setup.subList(done, setup.size())) begin(step);
        var credentialSteps = credentialSteps();
        totalSteps += credentialSteps.size();
        totalKnown = true;
        for (var step : credentialSteps) begin(step);
        credentials.closeHttpClient();
        gitRemoteHelper().run().run();
        for (var step : finish) begin(step);
    }

    /** The steps chosen at Credential Setup, which {@link #credentialSteps} runs. */
    private List<InitStep> chosenCredentials = List.of();

    /** Credential Setup, which returns a step for each credential chosen. */
    List<InitStep> credentialSteps() throws Exception {
        begin(credentialSetup());
        return chosenCredentials;
    }

    /** Credential Setup itself: the choice of credentials, whose steps follow it. */
    InitStep credentialSetup() {
        return new InitStep("credentials", "Credential Setup", new String[]{
                "Choose which API credentials to configure. Each credential",
                "stays on your host — containers only hold placeholders, and",
                "the MITM proxy injects real values transparently."},
                () -> {
                    var tools = new ToolDefLoader().allToolSetups();
                    chosenCredentials = selectCredentials(tools).stream().map(name -> credentialStep(name, tools.get(name))).toList();
                },
                this::planCredentials);
    }

    /** The step that configures {@code toolName}'s credential. */
    private InitStep credentialStep(String toolName, ToolSetup tool) {
        return switch (toolName) {
            case "claude" -> new InitStep(toolName, "Claude Code Authentication", new String[]{
                    "Configures how containers authenticate with the Claude API.",
                    "You can use an Anthropic API key, a Claude Pro/Max OAuth",
                    "token, or Google Cloud Vertex AI. The credential stays on",
                    "your host and is injected at runtime via the MITM proxy —",
                    "containers never see the real key."}, this::setupClaudeAuth, null);
            case "gh" -> new InitStep(toolName, "GitHub Authentication", new String[]{
                    "Sets up a GitHub PAT so containers can open PRs, push",
                    "code, and manage issues. These containers run autonomous",
                    "agents, so give them their own identity rather than",
                    "your personal one — create a dedicated agent account (e.g.",
                    "yourname-ai-bot) and mint a fine-grained PAT for it, scoped to",
                    "just the repos and permissions the agent needs. That keeps the",
                    "agent's actions attributable to it and its blast radius small."}, this::setupGitHubAuth, null);
            default -> {
                var desc = CredentialSetup.describeTool(toolName, tool);
                yield new InitStep(toolName, desc, new String[]{
                        "Configures credentials for " + desc + ".",
                        "Real credentials stay on your host — containers only hold",
                        "placeholders, and the MITM proxy injects real values."},
                        () -> setupGenericToolCredentials(toolName, tool), null);
            }
        };
    }

    /**
     * What Credential Setup leaves for a person: each credential a known template needs that is
     * not configured, as the closing box names them. Only a person types one, at a terminal.
     */
    InitPlan.Assessment planCredentials() {
        var config = SpawnConfig.load();
        var defs = ImageDef.loadAll(warning -> {});
        var current = defs.values().stream().filter(d -> !d.isDeprecated()).map(ImageDef::getName).toList();
        var missing = CredentialCheck.missingByCredential(config, current, defs, new ToolDefLoader(config.getSearchPaths()));
        var changes = missing.entrySet().stream().map(e -> InitPlan.Change.run(
                e.getKey().credential() + ", which " + OutputFormat.andList(e.getValue()) + " need",
                InitPlan.Who.SECRET, e.getKey().command())).toList();
        return InitPlan.Assessment.of(changes).asking();
    }

    /**
     * {@code isx account add}: the step that configures one credential, exactly as init runs it,
     * and nothing else (#1469); with no {@code name}, Credential Setup and the steps it picks.
     * Nothing has to be restarted: the proxy reloads when {@code config.yaml} changes.
     *
     * @param name a credential namespace ({@code github}) or the tool that owns it ({@code gh})
     * @return the exit code
     */
    int addCredential(String name) throws Exception {
        if (prompts() == null) {
            BuildOutput.error("'isx account add' asks for the credential, so it needs a terminal.");
            return 1;
        }
        currentStep = 0;
        List<InitStep> steps;
        if (name == null) {
            totalSteps = 1;
            totalKnown = false;
            steps = credentialSteps();
            totalKnown = true;
        } else {
            var tools = new ToolDefLoader().allToolSetups();
            var toolName = CredentialCheck.credentialTool(name, tools);
            if (toolName == null) {
                BuildOutput.error("no credential named '" + OutputFormat.oneLine(name) + "'.",
                        "Credentials: " + String.join(", ", credentialNames(tools)) + ".");
                return 1;
            }
            steps = List.of(credentialStep(toolName, tools.get(toolName)));
        }
        totalSteps = currentStep + steps.size();
        try {
            for (var step : steps) begin(step);
        } finally {
            credentials.closeHttpClient();
        }
        return 0;
    }

    /**
     * What {@code isx account add} takes: for each credential step, the name that reaches it, and
     * its tool when that differs -- its namespace when it owns that, else the tool's own name.
     */
    static List<String> credentialNames(Map<String, ToolSetup> tools) {
        var prefix = "isx account add ";
        var names = new ArrayList<String>();
        tools.forEach((toolName, tool) -> {
            if (!CredentialCheck.hasCredentialStep(tool)) return;
            var command = CredentialCheck.stepCommand(toolName, tools);
            if (!command.startsWith(prefix)) return;
            var name = command.substring(prefix.length());
            names.add(name.equals(toolName) ? toolName : name + " (" + toolName + ")");
        });
        names.sort(null);
        return names;
    }

    /** The steps both platforms end with, the last being {@code service}. */
    List<InitStep> finalSteps(InitStep service) {
        return List.of(
                new InitStep("search-paths", "Template Search Paths", new String[]{
                        "Local directories where isx looks for custom image and",
                        "tool definitions. Definitions found here can extend or",
                        "override the built-in templates. Each directory should",
                        "contain images/ and/or tools/ subdirectories with YAML",
                        "files."}, this::setupSearchPaths, this::planSearchPaths),
                new InitStep("code-directories", "Code Directories", new String[]{
                        "Directories on your host containing git repositories (e.g.",
                        "~/code). Enables fast reference clones inside containers",
                        "and automatic git remote management — repos cloned from",
                        "these paths get a remote named after the instance, to push",
                        "and pull directly between container and host without",
                        "round-tripping through GitHub. Your host files are never",
                        "directly exposed to containers."}, this::setupHostPaths, this::planHostPaths),
                mcpStep(),
                new InitStep("dns", "DNS Configuration", DNS_HINT, this::setupBridgeDns, this::planBridgeDns),
                service);
    }

    /** Agent Access over MCP, which {@code isx mcp setup} also runs alone (#2077). */
    InitStep mcpStep() {
        return new InitStep("mcp", "Agent Access over MCP", new String[]{
                "Lets an agent on this machine (Claude Code or another MCP",
                "client) use isx itself: create instances from templates",
                "you approve here, run commands in them, and hand tasks",
                "to the Claude Code, Codex or Pi inside them.",
                "Agents can never approve templates themselves."}, this::setupMcp, this::planMcp);
    }

    /** Linux's last step, which records in {@code installed} whether the service is installed once it ends. */
    InitStep proxyServiceStep(AtomicBoolean installed) {
        return new InitStep("proxy-service", "Proxy Service", new String[]{
                "The MITM proxy intercepts HTTPS traffic from containers",
                "and injects real credentials (API keys, tokens) so that",
                "containers only ever hold placeholder values. Installing",
                "it as a systemd service means it starts automatically on",
                "boot — otherwise you'll need to run 'isx proxy start'",
                "before launching containers."},
                () -> installed.set(completeWithProxyService(prompts())), this::planProxyService);
    }

    /** macOS's last step, as {@link #proxyServiceStep} is Linux's. */
    InitStep macOsServicesStep(AtomicBoolean installed) {
        return new InitStep("macos-services", "macOS Services", new String[]{
                "Installs the Incus VM and MITM proxy as macOS launch",
                "agents so they start automatically on login and survive",
                "reboots. Without this you'll need to manually run",
                "'isx vm start' and 'isx proxy start' before launching",
                "containers."},
                () -> installed.set(completeWithMacOsServices(prompts())), this::planMacOsServices);
    }

    /** The macOS steps before Credential Setup, in order. */
    List<InitStep> macOsSetup() {
        return List.of(
                new InitStep("ca", "MITM CA Certificate", new String[]{
                        "Generates a custom Certificate Authority for the MITM",
                        "proxy. Containers trust this CA so the proxy can intercept",
                        "HTTPS and inject credentials transparently."}, this::setupMacOsCa, this::planMacOsCa),
                sshKeyPair());
    }

    InitStep sshKeyPair() {
        return new InitStep("ssh-key", "SSH Key Pair", new String[]{
                "Generates a dedicated SSH key pair used only by isx",
                "to connect to containers. This is separate from your personal",
                "SSH keys and won't interfere with them. Your ~/.ssh/config is",
                "updated automatically."}, this::setupSshKeyPair, this::planSshKeyPair);
    }

    /** Writes the git remote helper beside a JVM isx, between the credentials and the last steps; no header. */
    InitStep gitRemoteHelper() {
        return new InitStep("git-remote-helper", "Git Remote Helper", new String[]{
                "Puts the git-remote-isx helper beside a JVM install of isx,",
                "so git can reach instances as remotes."},
                this::installGitRemoteShim, this::planGitRemoteShim);
    }

    /** On macOS, the VM init needs before anything else, which {@link #requireIncusHost} starts; no header. */
    InitStep incusVm() {
        return new InitStep("vm", "Incus VM", new String[]{
                "Incus runs in a small Linux VM on macOS: init downloads its",
                "appliance when it is missing and starts it."},
                () -> {}, this::planIncusVm);
    }

    /**
     * DNS Configuration, which writes the whole block from the configuration the earlier steps
     * saved. Never the built-in domains alone: the proxy's reload has just written the tool
     * domains too, and nothing would write them back until it restarts (#1354).
     */
    void setupBridgeDns() {
        var config = SpawnConfig.load();
        var domains = ProxyConfig.bridgeDnsDomains(config);
        var gatewayIp = ProxyConfig.resolveGatewayIp(incus);
        var hostAddress = ProxyConfig.bridgeDnsHostAddress(config, gatewayIp);
        for (int attempt = 1; ; attempt++) {
            try {
                ProxyConfig.configureBridgeDns(incus, domains, gatewayIp, hostAddress);
                return;
            } catch (IncusException e) {
                if (attempt == BRIDGE_DNS_ATTEMPTS) throw e;
                // Progress, not a problem yet: the next write usually takes
                BuildOutput.note(e.getMessage() + "; trying again");
                try {
                    pauseBeforeBridgeDnsRetry();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    e.addSuppressed(ie);
                    throw e;
                }
            }
        }
    }

    void pauseBeforeBridgeDnsRetry() throws InterruptedException {
        Thread.sleep(2000);
    }

    private void setupMacOsCa() {
        incus.createBridgeIfMissing("incusbr0", VmManager.gatewayIp());
        var gatewayIp = ProxyConfig.resolveGatewayIp(incus);
        var config = SpawnConfig.load();
        config.setIncusBridgeGateway(gatewayIp);
        config.save();
        if (CertificateAuthority.exists()) {
            BuildOutput.step("MITM CA certificate already exists.");
        } else {
            CertificateAuthority.loadOrCreate();
            BuildOutput.ok("CA certificate generated");
        }
    }

    static boolean commandExists(String command) {
        return HostPath.isOnPath(command);
    }

    /** The terminal, or null after saying why interactive setup cannot run without one. */
    private Prompts consoleOrExplain() {
        var prompts = prompts();
        if (prompts == null) BuildOutput.error("No console available for interactive setup.");
        return prompts;
    }

    /** Where init's questions read their answers, its credential and host steps' alike: the terminal, or null without one. */
    Prompts prompts() {
        // A terminal, never a console whose stdin is piped: that would answer a credential or an
        // approval from whatever an agent pipes in (#2075; a JVM under -Djdk.console=jdk.internal.le)
        return Prompts.terminal();
    }

    boolean hostHasCommand(String command) {
        return commandExists(command);
    }

    /** Runs a host command at the terminal, as {@link #runHost} does; tests record it instead. */
    int runShown(String... command) {
        return runHost(command);
    }

    boolean proxyServiceActive() {
        return ProxyService.isActive();
    }

    boolean macOsServicesInstalled() {
        return ProxyService.isMacOsServiceInstalled();
    }

    /** Brings a running service's files up to date, restarting it when they changed. */
    boolean upgradeProxyService() {
        return ProxyService.upgradeIfNeeded();
    }

    void restartProxyService() {
        ProxyService.restart();
    }

    /** Installs and starts the proxy service; on macOS, the VM launch agent with it. */
    boolean installProxyService() {
        return ProxyService.install();
    }

    /** The Linux host configuration steps; they read this init's Incus client when they run. */
    HostSetup host() {
        return host;
    }

    /** {@code isx doctor}'s remedy for a bridge subnet conflict (#1203); see {@link #moveBridgeOff}. */
    static void moveBridgeOffSubnetConflict(IncusClient incus) {
        var init = new InitCommand();
        init.incus = incus;
        init.moveBridgeOff(BridgeSubnetCheck.detect(incus));
    }

    /**
     * Init's move and instance migration, then only what names the bridge's address
     * ({@link HostSetup#followBridgeMove}), without the rest of {@code isx init}. {@code conflict}
     * is read when the user agreed, since the routes may have changed while they read the finding,
     * and it is the one read the move acts on. With no running firewall, or without sudo, nothing
     * moves; a long
     * migration can outlast sudo's timestamp, so the firewall step may ask again. Throws when a
     * step did not complete, so doctor does not report it done.
     */
    void moveBridgeOff(BridgeSubnetCheck.ConflictResult conflict) {
        if (!conflict.conflictDetected()) {
            BuildOutput.step("The bridge no longer overlaps a host route; nothing to move.");
            return;
        }
        var subnet = conflict.newSubnet();
        if (subnet == null) {
            throw new IllegalStateException("host routes now cover every subnet isx would move the bridge to");
        }
        var firewall = runningFirewall();
        if (!(firewall instanceof DetectionResult.UseUfw)
                && !(firewall instanceof DetectionResult.UseFirewalld fwd && !fwd.needsStart())) {
            throw new IllegalStateException("no firewall is running for the proxy's redirect to follow the"
                    + " bridge; nothing was changed. Run 'isx init', which sets one up and moves the bridge");
        }
        if (!sudoValidated()) {
            throw new IllegalStateException("sudo authentication failed; nothing was changed");
        }
        incus.networkConfigSet(BridgeAddress.BRIDGE, "ipv4.address", subnet);
        BuildOutput.step("Moved the bridge from " + conflict.oldSubnet() + " to " + subnet + ".");
        try {
            var migrated = migrateInstances();
            if (migrated > 0) {
                BuildOutput.step("Migrated " + migrated + " instance" + (migrated == 1 ? "" : "s") + ".");
            }
        } catch (RuntimeException e) {
            // The redirect still follows the bridge; the stale check below fails the remedy
            BuildOutput.warn("Could not migrate instances: " + failure(e));
        }
        if (!followBridgeMove(subnet.substring(0, subnet.indexOf('/')), firewall)) {
            throw new IllegalStateException("the bridge moved, but its firewall redirect was not updated"
                    + " (see above); run 'isx init' to finish it");
        }
        var stale = staleInstances();
        if (!stale.isEmpty()) {
            throw new IllegalStateException(stale.size() + " instance(s) still on the old subnet ("
                    + String.join(", ", stale) + "); re-run 'isx doctor' to migrate them");
        }
        // The proxy follows the move itself (#966): it exits on a moved bridge within a minute, and
        // the service's Restart=on-failure binds it to the new gateway. A restart here would be a second one.
        BuildOutput.step("The proxy moves to the new gateway by itself within a minute;"
                + " one started in a terminal stops instead, and needs 'isx proxy start' again.");
        BuildOutput.step("Re-run 'isx doctor' to check the bridge's firewall and DNS findings again.");
    }

    boolean sudoValidated() {
        return runHost("sudo", "-v") == 0;
    }

    int migrateInstances() {
        return InstanceNetwork.migrateAllInstancesToNewSubnet(incus);
    }

    List<String> staleInstances() {
        return InstanceNetwork.findStaleSubnetInstances(incus);
    }

    DetectionResult runningFirewall() {
        return FirewallDetector.detect();
    }

    boolean followBridgeMove(String gatewayIp, DetectionResult firewall) {
        return host.followBridgeMove(gatewayIp, firewall);
    }

    void setupSshKeyPair() {
        try {
            if (SshKeyManager.exists()) {
                BuildOutput.step("SSH key pair already exists.");
            } else {
                SshKeyManager.ensureKeyPairExists();
            }
            if (SshKeyManager.ensureSshConfigInclude()) {
                BuildOutput.ok("SSH configuration ready");
            } else {
                BuildOutput.warn("SSH key generated but ~/.ssh/config could not be updated.",
                        "Add manually: Include ~/.config/incus-spawn/ssh/config");
            }
        } catch (Exception e) {
            BuildOutput.warn("SSH key setup failed: " + failure(e),
                    "SSH container access will fall back to your personal keys.",
                    "You can retry later with: isx init");
        }
    }

    private List<String> selectCredentials(Map<String, ToolSetup> allTools) {
        var prompts = prompts();
        if (prompts == null) {
            // No answer to a credential comes from anywhere but a person at a terminal (#2075)
            skipped.add("credentials");
            return List.of();
        }
        return credentials.selectCredentials(allTools, SpawnConfig.load(), prompts);
    }

    private void setupClaudeAuth() {
        var prompts = consoleOrExplain();
        if (prompts != null) credentials.setupClaudeAuth(SpawnConfig.load(), prompts);
    }

    private void setupGitHubAuth() {
        var prompts = consoleOrExplain();
        if (prompts != null) credentials.setupGitHubAuth(SpawnConfig.load(), prompts);
    }

    private void setupGenericToolCredentials(String toolName, ToolSetup tool) {
        var prompts = consoleOrExplain();
        if (prompts != null) credentials.setupGenericToolCredentials(toolName, tool, SpawnConfig.load(), prompts);
    }

    private void setupPathList(
            java.util.function.Function<SpawnConfig, java.util.List<String>> getter,
            java.util.function.BiConsumer<SpawnConfig, java.util.List<String>> setter,
            String skipMessage, String step, List<String> answers, String flag) {
        var config = SpawnConfig.load();
        if (answers != null) {
            addPaths(getter, setter, config, answers);
            return;
        }
        var prompts = prompts();
        if (prompts != null) {
            credentials.setupPathList(getter, setter, skipMessage, config, prompts);
        } else {
            BuildOutput.note("Skipped: no terminal. Add directories with " + flag + ", or run 'isx init' at a terminal.");
            skipped.add(step);
            credentials.printCurrentPaths(getter.apply(config));
        }
    }

    /** Adds {@code answers} to a path list, as the question would have: each a directory, resolved, once. */
    static void addPaths(java.util.function.Function<SpawnConfig, java.util.List<String>> getter,
                         java.util.function.BiConsumer<SpawnConfig, java.util.List<String>> setter,
                         SpawnConfig config, List<String> answers) {
        var paths = new ArrayList<>(getter.apply(config));
        for (var answer : answers) {
            var resolved = Path.of(HostResourceSetup.expandHostTilde(answer)).toAbsolutePath().normalize().toString();
            if (paths.contains(resolved)) {
                BuildOutput.step("Already in the list: " + resolved);
            } else {
                paths.add(resolved);
                BuildOutput.step("Added: " + resolved);
            }
        }
        if (!paths.equals(getter.apply(config))) {
            setter.accept(config, paths);
            config.save();
            BuildOutput.step("Paths saved.");
        }
    }

    private void setupMcp() {
        var prompts = prompts();
        if (prompts == null) {
            skipped.add("mcp");
            BuildOutput.step("Skipped: no console. Run '" + MCP_SETUP_COMMAND + "' at a terminal to set this up.");
            return;
        }
        setupMcp(prompts, trustedDefinitions());
    }

    /** The step's header, from its {@link InitStep}: {@code isx mcp setup} shows it as init does. */
    private void startMcpStep() {
        var step = mcpStep();
        startStep(step.title(), step.hint());
    }

    /** The command that runs the step above alone, for a person at a terminal (#2077). */
    static final String MCP_SETUP_COMMAND = McpScope.SETUP_COMMAND;

    /** The command that prints the client entry for {@code isx mcp}, for any MCP client (#2078). */
    static final String MCP_CONFIG_COMMAND = "isx mcp config";

    /**
     * {@code isx mcp setup}: init's "Agent Access over MCP" step and nothing else. It asks which
     * templates agents may use, so it runs only when stdin and stdout are a terminal: an agent
     * driving the install hands this step to the person rather than answering it (#2072).
     */
    int setupMcpAlone() {
        var prompts = Prompts.terminal();
        if (prompts != null) this.incus = RuntimeServices.incus();
        return setupMcpAlone(prompts, this::trustedDefinitions);
    }

    /** {@link #setupMcpAlone()} with its terminal given: null when there is none, which refuses. */
    int setupMcpAlone(Prompts prompts, java.util.function.Supplier<java.util.Map<String, ImageDef>> defs) {
        if (prompts == null) {
            BuildOutput.error("'" + MCP_SETUP_COMMAND + "' asks which templates agents may use, so a person must run it at a terminal.",
                    "Nothing was changed.");
            return 1;
        }
        currentStep = 0;
        totalSteps = 1;
        startMcpStep();
        setupMcp(prompts, defs.get());
        // init runs this step before it finishes; alone, the server it registered still waits for init.
        if (!hasBeenInitialized()) BuildOutput.note("isx mcp serves agents once 'isx init' has run.");
        return 0;
    }

    /**
     * Offer to register {@code isx mcp} with Claude Code when it is installed, then ask about each template that
     * installs Claude Code, Codex or Pi (the only ones a task can be delegated to) whether agents may
     * use it. Templates already approved by hand that install none of them are left as they are.
     * The approvals live in {@code mcp.yaml}; one that cannot be read is reported and left alone.
     * Without Claude Code the approvals are the whole step: any other client is registered by the
     * person, with the entry {@code isx mcp config} prints (#2078).
     */
    void setupMcp(Prompts prompts, java.util.Map<String, ImageDef> defs) {
        var claude = hostHasCommand("claude");
        McpConfig mcp;
        try {
            mcp = McpConfig.load();
        } catch (McpConfig.Invalid e) {
            BuildOutput.warn(e.getMessage() + ".", "Fix it, then run '" + MCP_SETUP_COMMAND + "'.");
            return;
        }
        var legacy = McpConfig.legacySectionNotice(SpawnConfig.load());
        if (legacy != null) BuildOutput.step(Character.toUpperCase(legacy.charAt(0)) + legacy.substring(1) + ".");
        var retired = mcp.retiredKeysNotice();
        if (retired != null) BuildOutput.warn(retired + ".", "Saving the approvals here removes them too.");
        var registered = claude && claudeMcpRegistered();
        var approved = mcp.templates();
        var candidates = mcpCandidates(defs);
        var enabled = registered || !approved.isEmpty();
        if (enabled) {
            BuildOutput.step("Currently " + (!claude ? "" : registered ? "registered with Claude Code; " : "not registered; ")
                    + "approved templates: " + (approved.isEmpty() ? "none" : String.join(", ", approved)) + ".");
        }
        // Fully set up: a re-run of init for something else costs one Enter, as credentials do (#1292).
        boolean proceed;
        if ((registered || !claude) && !approved.isEmpty()) {
            var unasked = candidates.stream().filter(n -> !approved.contains(n)).toList();
            if (!unasked.isEmpty()) {
                BuildOutput.step("Not approved: " + String.join(", ", unasked) + ".");
            }
            var choice = mcpSetupChoice(prompts);
            if (choice.isEmpty()) {
                BuildOutput.step("Left as it is.");
                return;
            }
            proceed = choice.equals("t");
        } else {
            proceed = askYesNo(prompts, "Enable the isx MCP server?", enabled);
        }
        if (!proceed) {
            if (registered) {
                BuildOutput.step("Left as it is. To remove it: claude mcp remove --scope user " + McpClientRegistration.SERVER_NAME);
            } else if (!claude && !approved.isEmpty()) {
                // Without Claude Code the approvals are the setup, so turning it off is approving none.
                BuildOutput.step("Left as it is. To turn it off, choose 't' and approve no templates,"
                        + " and remove '" + McpClientRegistration.SERVER_NAME + "' from your MCP client's settings.");
            }
            return;
        }

        if (!claude) {
            BuildOutput.note("Claude Code is not installed on this host, so nothing was registered.");
        } else if (!registered) {
            var isx = isxPath();
            if (isx == null) {
                BuildOutput.step("Could not find the installed isx binary; register it yourself:");
                BuildOutput.stepNote(String.join(" ", claudeMcpAddCommand("<path to isx>")));
            } else if (registerClaudeMcp(isx)) {
                BuildOutput.ok("Registered with Claude Code as '" + McpClientRegistration.SERVER_NAME + "' (all projects)");
            } else {
                BuildOutput.step("Registering failed; register it yourself:");
                BuildOutput.stepNote(String.join(" ", claudeMcpAddCommand(isx)));
            }
        }
        BuildOutput.stepNote("For another MCP client, add the entry '" + MCP_CONFIG_COMMAND + "' prints to its settings.");

        if (candidates.isEmpty()) {
            BuildOutput.step("No template installs Claude Code, Codex or Pi yet, so there is none to approve."
                    + " Add one, then run '" + MCP_SETUP_COMMAND + "' or edit " + McpConfig.file() + ".");
            return;
        }
        var chosen = new java.util.ArrayList<String>();
        try (var group = BuildOutput.group("Approve the templates agents may create instances from", null)) {
            for (var name : candidates) {
                var note = templateBuilt(name) ? "" : styled(DIM, " (not built yet: isx build " + name + ")");
                // End of input keeps the existing answer: a closed terminal must not drop an approval.
                var current = approved.contains(name);
                if (askYesNo(prompts, name + note + "?", current, current)) chosen.add(name);
            }
        }
        // Keep hand-approved templates this step does not ask about, in their order.
        var updated = new java.util.ArrayList<String>();
        approved.stream().filter(n -> !candidates.contains(n) || chosen.contains(n)).forEach(updated::add);
        chosen.stream().filter(n -> !updated.contains(n)).forEach(updated::add);
        if (updated.equals(approved)) {
            BuildOutput.step("Approved templates unchanged.");
        } else {
            mcp.setTemplates(updated);
            mcp.save();
            BuildOutput.ok("Approved templates saved: " + (updated.isEmpty() ? "none" : String.join(", ", updated)));
        }
        if (claude) BuildOutput.stepNote("Keep agents to the MCP tools with Claude Code permissions: see https://isx.run/docs/mcp (docs/MCP.md).");
    }

    /** Enter (or end of input) to keep the MCP setup, {@code t} to change templates, {@code n} for how to turn it off. */
    private static String mcpSetupChoice(Prompts prompts) {
        while (true) {
            prompts.say("t. Change the approved templates");
            prompts.say("n. How to turn it off");
            var choice = readInput(prompts.ask("Choice (Enter to keep as-is): ")).toLowerCase(java.util.Locale.ROOT);
            if (choice.isEmpty() || choice.equals("t") || choice.equals("n")) return choice;
        }
    }

    /** The installed isx, for Claude Code to start; null if none is found. */
    String isxPath() {
        return ProxyService.resolveIsxPath();
    }

    /**
     * Whether Claude Code's user configuration registers isx's MCP server, read from its file:
     * {@code ~/.claude.json}, or {@code .claude.json} in {@code CLAUDE_CONFIG_DIR} when that is set,
     * whose top-level {@code mcpServers} holds the servers of the user scope, and
     * {@code projects.<directory>.mcpServers} those of the local scope. A project's {@code .mcp.json}
     * is not read: the run's own registration is the user scope. Unlike
     * {@link #claudeMcpRegistered}, it starts nothing. A missing file registers nothing.
     *
     * @throws IllegalArgumentException when the file is not JSON
     */
    boolean claudeMcpRegisteredInConfig() throws IOException {
        var dir = claudeConfigDirEnv();
        var file = (dir == null || dir.isBlank() ? Environment.home() : Path.of(dir)).resolve(".claude.json");
        if (!Files.exists(file)) return false;
        var config = dev.incusspawn.config.Jjq.parseForeign(Files.readAllBytes(file), file.toString());
        if (!config.isObject()) return false;
        // The user scope, then the local one, which Claude Code keys by the directory it runs in
        if (namesIsx(config.path("mcpServers"))) return true;
        var projects = config.path("projects");
        var cwd = Path.of("").toAbsolutePath().toString();
        return projects.isObject() && projects.asMap().containsKey(cwd) && namesIsx(projects.path(cwd).path("mcpServers"));
    }

    private static boolean namesIsx(io.hyperfoil.tools.jjq.value.JqValue servers) {
        return servers != null && servers.isObject() && servers.asMap().containsKey(McpClientRegistration.SERVER_NAME);
    }

    /** {@code CLAUDE_CONFIG_DIR}, where Claude Code keeps its configuration when set. */
    String claudeConfigDirEnv() {
        return System.getenv("CLAUDE_CONFIG_DIR");
    }

    /** Whether Claude Code already knows an MCP server by this name; runs {@code claude mcp get}, which starts it. */
    boolean claudeMcpRegistered() {
        return runSilently("claude", "mcp", "get", McpClientRegistration.SERVER_NAME) == 0;
    }

    /** Register {@code isx mcp} with Claude Code for every project; true on success. */
    boolean registerClaudeMcp(String isxPath) {
        return runSilently(claudeMcpAddCommand(isxPath).toArray(String[]::new)) == 0;
    }

    /** The command that registers {@code isx mcp} with Claude Code for every project, which the run, its advice and the plan all name. */
    static List<String> claudeMcpAddCommand(String isxPath) {
        return List.of("claude", "mcp", "add", "--scope", "user", McpClientRegistration.SERVER_NAME, "--", isxPath, "mcp");
    }

    /** Run a host command for its exit code alone, showing nothing and reading no input. */
    private static int runSilently(String... command) {
        dev.incusspawn.util.Privilege.check(command);
        try {
            return new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** Whether a template has been built (exists in Incus as a base image). */
    boolean templateBuilt(String name) {
        try {
            return incus != null && Metadata.TYPE_BASE.equals(Metadata.getType(incus, name));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final String TEMPLATES_REPO = "incus-spawn-templates";
    private static final String TEMPLATES_UPSTREAM = "Sanne/" + TEMPLATES_REPO;
    private static final String TEMPLATES_URL = "https://github.com/" + TEMPLATES_UPSTREAM;

    private void setupSearchPaths() {
        if (forkTemplates) {
            forkAndCloneTemplates();
        } else if (searchPathAnswers == null && !hasExistingTemplatesSearchPath(SpawnConfig.load().getSearchPaths())) {
            offerTemplatesRepo();
        }

        // --fork-templates answered the step once the templates it cloned are among the paths
        var answers = searchPathAnswers == null && forkTemplates
                && hasExistingTemplatesSearchPath(SpawnConfig.load().getSearchPaths()) ? List.<String>of() : searchPathAnswers;
        setupPathList(
                SpawnConfig::getSearchPaths,
                SpawnConfig::setSearchPaths,
                "No search paths configured. You can add them later in ~/.config/incus-spawn/config.yaml",
                "search-paths", answers, "--search-path");
    }

    static boolean hasExistingTemplatesSearchPath(java.util.List<String> searchPaths) {
        return searchPaths.stream()
                .anyMatch(p -> Path.of(p).getFileName().toString().equals(TEMPLATES_REPO));
    }

    /**
     * {@code --fork-templates}: what the offer does on a yes, asking nothing. The clone goes to the
     * default path, which the offer only confirms; it needs a logged-in {@code gh}, or it says why not.
     */
    private void forkAndCloneTemplates() {
        if (hasExistingTemplatesSearchPath(SpawnConfig.load().getSearchPaths())) {
            BuildOutput.step("A templates repository is already among the search paths.");
            return;
        }
        var login = commandExists("gh") && commandExists("git") ? getGhLogin() : null;
        if (login == null) {
            BuildOutput.warn("--fork-templates needs gh and git, with gh logged in ('gh auth login').",
                    "Nothing was forked or cloned.");
            return;
        }
        var nwo = login + "/" + TEMPLATES_REPO;
        if (!ghRepoExists(nwo)) {
            BuildOutput.stepStart("Forking " + TEMPLATES_UPSTREAM);
            if (runHostCapturingExit("gh", "repo", "fork", TEMPLATES_UPSTREAM, "--clone=false") != 0) {
                BuildOutput.stepBreak();
                BuildOutput.warn("Fork failed.", "You can fork it manually at: " + TEMPLATES_URL + "/fork");
                return;
            }
            BuildOutput.stepDone(nwo);
        }
        cloneAndAddSearchPath(null, nwo, defaultClonePath(), true);
    }

    private void offerTemplatesRepo() {
        var login = commandExists("gh") && commandExists("git") ? getGhLogin() : null;
        var prompts = login == null ? null : prompts();
        if (prompts == null) {
            pointAt("For community templates, see (clone and add the local path):");
            return;
        }

        if (ghRepoExists(login + "/" + TEMPLATES_REPO)) {
            offerCloneTemplates(prompts, login);
        } else {
            offerForkAndCloneTemplates(prompts, login);
        }
    }

    /** {@code lead}, then the link to the upstream templates repository. */
    private static void pointAt(String lead) {
        BuildOutput.step(lead);
        BuildOutput.step(TerminalLink.link(TEMPLATES_URL));
    }

    private String getGhLogin() {
        if (runHostCapturingExit("gh", "auth", "status") != 0) return null;
        var login = captureOutput("gh", "api", "user", "--jq", ".login");
        return login.isEmpty() ? null : login;
    }

    private boolean ghRepoExists(String nwo) {
        try {
            var pb = new ProcessBuilder("gh", "api", "repos/" + nwo, "--silent");
            pb.redirectErrorStream(true);
            var process = pb.start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void offerCloneTemplates(Prompts prompts, String login) {
        BuildOutput.step("Found " + styled(BOLD, login + "/" + TEMPLATES_REPO) + " on GitHub.");
        var defaultPath = defaultClonePath();
        var clonePath = askClonePath(prompts, defaultPath);
        if (clonePath == null) return;
        cloneAndAddSearchPath(prompts, login + "/" + TEMPLATES_REPO, clonePath, clonePath.equals(defaultPath));
    }

    private void offerForkAndCloneTemplates(Prompts prompts, String login) {
        BuildOutput.step("You don't have a " + styled(BOLD, TEMPLATES_REPO) + " repo yet.");
        if (!askYesNo(prompts, "Fork " + TEMPLATES_UPSTREAM + " to your account?", true)) {
            pointAt("Skipped. You can fork it manually at:");
            return;
        }

        BuildOutput.stepStart("Forking " + TEMPLATES_UPSTREAM);
        var forkResult = runHostCapturingExit("gh", "repo", "fork", TEMPLATES_UPSTREAM, "--clone=false");
        if (forkResult != 0) {
            BuildOutput.stepBreak();
            // The URL as text: a warning's details are made safe, which a hyperlink's escapes are not
            BuildOutput.warn("Fork failed.", "You can fork it manually at: " + TEMPLATES_URL + "/fork");
            return;
        }
        BuildOutput.stepDone(login + "/" + TEMPLATES_REPO);

        var defaultPath = defaultClonePath();
        var clonePath = askClonePath(prompts, defaultPath);
        if (clonePath == null) {
            BuildOutput.step("You can clone it later with: gh repo clone " + login + "/" + TEMPLATES_REPO);
            return;
        }
        cloneAndAddSearchPath(prompts, login + "/" + TEMPLATES_REPO, clonePath, clonePath.equals(defaultPath));
    }

    private static String defaultClonePath() {
        return Environment.configDir().resolve(TEMPLATES_REPO).toString();
    }

    static String askClonePath(Prompts prompts, String defaultPath) {
        var answer = readInput(prompts.ask("Clone to " + defaultPath + "? (Y/path/n): "));
        if (answer.equalsIgnoreCase("n")) return null;
        if (answer.isEmpty() || answer.equalsIgnoreCase("y")) return defaultPath;

        if (answer.equalsIgnoreCase("path")) {
            var path = readInput(prompts.ask("Clone path: "));
            if (path.isEmpty()) return defaultPath;
            return HostResourceSetup.expandHostTilde(path);
        }
        return HostResourceSetup.expandHostTilde(answer);
    }

    private void cloneAndAddSearchPath(Prompts prompts, String nwo, String targetPath, boolean alreadyConfirmed) {
        var target = Path.of(targetPath).toAbsolutePath().normalize();
        var adjusted = false;
        if (Files.isDirectory(target) && !target.getFileName().toString().equals(TEMPLATES_REPO)) {
            target = target.resolve(TEMPLATES_REPO);
            adjusted = true;
        }
        if (Files.isDirectory(target)) {
            BuildOutput.step("Directory already exists: " + target);
            addToSearchPaths(target.toString());
            return;
        }

        if (!alreadyConfirmed || adjusted) {
            if (!askYesNo(prompts, "Will clone to " + target + ". Proceed?", true)) {
                BuildOutput.step("Skipped cloning. You can add the path manually below.");
                return;
            }
        }

        BuildOutput.stepStart("Cloning " + nwo);
        var result = runHostCapturingExit("gh", "repo", "clone", nwo, target.toString());
        if (result != 0) {
            BuildOutput.stepBreak();
            BuildOutput.warn("Clone failed.", "You can clone it manually and add the path below.");
            return;
        }
        BuildOutput.stepDone(target.toString());
        addToSearchPaths(target.toString());
    }

    private void addToSearchPaths(String path) {
        var config = SpawnConfig.load();
        var paths = new java.util.ArrayList<>(config.getSearchPaths());
        if (!paths.contains(path)) {
            paths.add(path);
            config.setSearchPaths(paths);
            config.save();
            BuildOutput.step("Added to search paths.");
        }
    }

    static int runHostCapturingExit(String... command) {
        dev.incusspawn.util.Privilege.check(command);
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            var process = pb.start();
            process.getInputStream().readAllBytes();
            return process.waitFor();
        } catch (Exception e) {
            return 1;
        }
    }

    private void setupHostPaths() {
        setupPathList(
                SpawnConfig::getHostPaths,
                SpawnConfig::setHostPaths,
                "No host paths configured. Add them later by re-running 'isx init'"
                        + " or editing ~/.config/incus-spawn/config.yaml",
                "code-directories", codeDirAnswers, "--code-dir");
    }

    /**
     * The last step of the macOS flow: asks about the launch agents, marks init complete, and
     * only then installs them.
     * <p>
     * The marker comes before the install because {@code isx-proxy} exits
     * {@link ProxyService#EXIT_CONFIG} until init has completed, so a service installed ahead of
     * it fails its first start and init reports a proxy that is not responding (#938; design/native-image.md
     * has the full account). It comes after the question so that an init abandoned at the prompt
     * is still run again. The services are optional: declining them leaves init complete.
     *
     * @return whether the services are installed once this returns: found so, or installed now
     */
    boolean completeWithMacOsServices(Prompts prompts) throws IOException {
        var present = macOsServicesInstalled();
        if (present) BuildOutput.step("macOS services already installed.");
        var install = !present && wantsMacOsServices(prompts);
        markInitComplete();
        return present || install && installProxyService();
    }

    private boolean wantsMacOsServices(Prompts prompts) {
        if (service) return true;
        if (noService) {
            declined.add("macos-services");
            BuildOutput.step("Not installed, as --no-service asks. Start manually with: isx vm start && isx proxy start; the proxy runs in the foreground.");
            return false;
        }
        if (prompts == null) {
            skipped.add("macos-services");
            BuildOutput.step("Skipped: no terminal. Install the services with 'isx init --service', or decline them with"
                    + " --no-service; 'isx proxy start' runs the proxy in the foreground instead.");
            return false;
        }
        prompts.say("Optional: install VM and proxy as macOS services so they start automatically on login"
                + " and survive reboots.");
        if (!askYesNo(prompts, "Install services?", true)) {
            declined.add("macos-services");
            BuildOutput.step("Skipped. Start manually with: isx vm start && isx proxy start; the proxy runs in the foreground.");
            return false;
        }
        return true;
    }

    /**
     * The last step of the Linux flow, in the order {@link #completeWithMacOsServices} explains.
     * A service that is already running is covered too: on a re-run after an
     * {@code INIT_VERSION} bump, a restart by the upgrade would otherwise meet an outdated marker.
     * <p>
     * A running service found with an outdated marker is restarted once the marker is written,
     * unless the upgrade did it: the firewall step leaves its restart here, and nothing else could
     * make one while the marker was stale (#1048). Read from the marker rather than remembered, so
     * a restart owed by an earlier run that stopped before this step is still made.
     */
    boolean completeWithProxyService(Prompts prompts) throws IOException {
        var active = proxyServiceActive();
        var install = !active && wantsProxyService(prompts);
        var restartOwed = active && !Environment.hasBeenInitialized();
        markInitComplete();
        if (active) {
            if (!upgradeProxyService() && restartOwed) restartProxyService();
            BuildOutput.step("Proxy service is already running.");
            return true;
        }
        return install && installProxyService();
    }

    private boolean wantsProxyService(Prompts prompts) {
        if (service) return true;
        if (noService) {
            declined.add("proxy-service");
            BuildOutput.step("Not installed, as --no-service asks. You can start the proxy manually with: isx proxy start, which runs in the foreground.");
            return false;
        }
        if (prompts == null) {
            skipped.add("proxy-service");
            BuildOutput.step("Skipped: no terminal. Install the proxy service with 'isx init" + (noSudo ? " --no-sudo" : "")
                    + " --service', or decline it with --no-service; 'isx proxy start' runs the proxy in the foreground instead.");
            return false;
        }
        prompts.say("Optional: install the proxy as a systemd service so it starts automatically and survives reboots.");
        if (!askYesNo(prompts, "Install proxy service?", true)) {
            declined.add("proxy-service");
            BuildOutput.step("Skipped. You can start the proxy manually with: isx proxy start, which runs in the foreground.");
            return false;
        }
        return true;
    }

    void installGitRemoteShim() {
        var dir = gitRemoteShimDir();
        if (dir != null) installGitRemoteShim(dir);
    }

    /** Where a JVM isx gets its git remote helper: beside the isx on the PATH; null for a native one. */
    private static java.nio.file.Path gitRemoteShimDir() {
        if (System.getProperty("org.graalvm.version") != null) return null;
        var isxPath = HostPath.find("isx");
        return isxPath == null ? null : java.nio.file.Path.of(isxPath).getParent();
    }

    /** What {@link #installGitRemoteShim(java.nio.file.Path)} would write. */
    InitPlan.Assessment planGitRemoteShim() {
        var dir = gitRemoteShimDir();
        if (dir == null) return InitPlan.Assessment.done();
        var shimPath = dir.resolve("git-remote-isx");
        if (Files.exists(shimPath, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(dir)) {
            return InitPlan.Assessment.done();
        }
        return InitPlan.Assessment.of(List.of(InitPlan.Change.write("Install the git remote helper",
                InitPlan.Who.NONE, shimPath.toString())));
    }

    /**
     * Writes the helper beside {@code isx} in {@code dir}, unless one is there. A directory the user
     * cannot write, such as a package's {@code /usr/bin}, is skipped quietly: warning there would
     * repeat on every init of such an install, and nothing the user does in init changes it.
     */
    void installGitRemoteShim(java.nio.file.Path dir) {
        try {
            var shimPath = dir.resolve("git-remote-isx");
            if (Files.exists(shimPath, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(dir)) return;

            try (var is = getClass().getClassLoader().getResourceAsStream("git-remote-isx")) {
                if (is == null) return;
                Files.write(shimPath, is.readAllBytes());
                shimPath.toFile().setExecutable(true, false);
                BuildOutput.step("Installed git remote helper: " + shimPath);
            }
        } catch (Exception e) {
            BuildOutput.warn("Could not install git-remote-isx shim: " + failure(e));
        }
    }

    // --- isx init --plan (#2073) ---

    /**
     * {@code isx init --plan}: every step init would take on this host, from the same step lists
     * the run takes, each read without changing anything. No sudo, no prompt, no Incus write, and
     * on macOS no VM start: a VM that is not running leaves what Incus holds unknown.
     */
    CommandResult printPlan(OutputFormat outputFormat) {
        if (!Platform.isLinux() && !Platform.isMacOS()) {
            BuildOutput.error("isx requires Linux or macOS.", "Detected OS: " + System.getProperty("os.name"));
            return CommandResult.valueOf(1);
        }
        if (incus == null && (Platform.isLinux() || VmManager.isRunning() && IncusClient.isReachable())) {
            incus = RuntimeServices.incus();
        }
        var entries = planEntries();
        if (outputFormat == OutputFormat.TABLE) {
            InitPlan.printTable(BuildOutput.results(), entries);
        } else {
            outputFormat.print(BuildOutput.results(), entries.stream().map(InitPlan::record).toList());
        }
        return CommandResult.SUCCESS;
    }

    /**
     * The steps in the order the run takes them, unnumbered ones included: Linux installs its
     * dependencies and macOS starts its VM first, and the git remote helper comes between the
     * credentials and the last steps.
     */
    List<InitStep> planSteps() {
        return planSteps(Platform.isMacOS());
    }

    List<InitStep> planSteps(boolean macOS) {
        var steps = new ArrayList<InitStep>();
        if (macOS) {
            steps.add(incusVm());
            steps.addAll(macOsSetup());
        } else {
            steps.add(dependencies());
            steps.addAll(linuxSetup());
        }
        steps.add(credentialSetup());
        steps.add(gitRemoteHelper());
        steps.addAll(finalSteps(macOS ? macOsServicesStep(new AtomicBoolean())
                : proxyServiceStep(new AtomicBoolean())));
        return steps;
    }

    /**
     * {@link #planSteps}, each with its standing on this host (a read that failed leaves its step
     * unknown), what only root can check counted
     * as done when the {@code --no-sudo} script recorded making it: the plan and the summary read
     * the record as {@code --no-sudo} does, so they never report its work as left (#2081).
     */
    List<InitPlan.Entry> planEntries() {
        return InitPlan.withRecord(planEntries(planSteps()), appliedByScript());
    }

    /** How the note of a step whose reading failed starts. */
    static final String PLAN_FAILED = "Could not read this step: ";

    static List<InitPlan.Entry> planEntries(List<InitStep> steps) {
        return steps.stream().map(step -> {
            InitPlan.Assessment assessment;
            try {
                assessment = step.plan().assess();
            } catch (Exception e) {
                assessment = InitPlan.Assessment.unknown(PLAN_FAILED + failure(e));
            }
            return new InitPlan.Entry(step.id(), step.title(), String.join(" ", step.hint()), assessment);
        }).toList();
    }

    /** What a read-only host command answered: its exit code and output, stderr included. */
    record Query(int exitCode, String output) {}

    /**
     * Runs a host command the plan reads from, with no input, so it can never ask anything. Never
     * through sudo, in any form (Sanne on #2091): what only root can read is reported as not checked.
     */
    Query query(String... command) {
        if (command[0].equals("sudo")) {
            throw new IllegalArgumentException("the plan never runs sudo: " + String.join(" ", command));
        }
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            return new Query(process.waitFor(), output);
        } catch (IOException e) {
            return new Query(-1, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Query(-1, "");
        }
    }

    /**
     * Whether polkit grants this user {@code action} as it stands, asked without interaction so
     * that no agent shows a password dialog. Asked only where {@code pkcheck} is installed.
     */
    boolean polkitAllows(String action) {
        return query("pkcheck", "--action-id", action, "--process", String.valueOf(ProcessHandle.current().pid()))
                .exitCode() == 0;
    }

    /** The filesystem holding {@code path}, as {@link CowPoolBacking#filesystemOf} reads it; tests choose it. */
    String filesystemOf(Path path) {
        return CowPoolBacking.filesystemOf(path);
    }

    /** The host's routes, which the plan checks the bridge against as doctor does; tests give their own. */
    List<String> hostRoutes() {
        return BridgeSubnetCheck.getHostRoutes();
    }

    /** A file of the host's, for the plan to read; tests point it elsewhere. */
    Path hostFile(String path) {
        return Path.of(path);
    }

    /** Whether Incus answers: on macOS, only when the VM is up and the plan holds a client. */
    private boolean incusReadable() {
        return incus != null && incusReachable();
    }

    /** Whether the Incus socket accepts this process, asked afresh as {@link #incusConnectivity} is. */
    boolean incusReachable() {
        return IncusClient.isReachable();
    }

    InitPlan.Assessment planSshKeyPair() {
        var changes = new ArrayList<InitPlan.Change>();
        if (!SshKeyManager.exists()) {
            changes.add(InitPlan.Change.write("Generate isx's own SSH key pair", InitPlan.Who.NONE,
                    Environment.sshKeyFile().toString()));
        }
        if (!SshKeyManager.hasSshConfigInclude()) {
            changes.add(InitPlan.Change.write("Include isx's SSH configuration in yours", InitPlan.Who.NONE,
                    Environment.home().resolve(".ssh/config").toString()));
        }
        return InitPlan.Assessment.of(changes);
    }

    /**
     * What a step that asks for directories would offer: adding some when there are none, and
     * {@code offers} besides. Nothing needs it, so a step with something to offer is optional.
     */
    private static InitPlan.Assessment planPathList(List<String> paths, List<InitPlan.Change> offers, String what) {
        var changes = new ArrayList<>(offers);
        if (paths.isEmpty()) changes.add(InitPlan.Change.write("Add the " + what + " you name", InitPlan.Who.NONE, HostSetup.configFile()));
        var state = changes.isEmpty() ? InitPlan.State.DONE : InitPlan.State.OPTIONAL;
        return new InitPlan.Assessment(state, true, changes, null);
    }

    /** What {@link #setupSearchPaths} would offer. */
    InitPlan.Assessment planSearchPaths() {
        var paths = SpawnConfig.load().getSearchPaths();
        var offers = new ArrayList<InitPlan.Change>();
        // As offerTemplatesRepo decides, but for the gh login it asks GitHub for
        if (!hasExistingTemplatesSearchPath(paths) && hostHasCommand("gh") && hostHasCommand("git")) {
            offers.add(InitPlan.Change.write("Offer to fork and clone " + TEMPLATES_UPSTREAM + " with gh",
                    InitPlan.Who.NONE, defaultClonePath()));
        }
        return planPathList(paths, offers, "template directories");
    }

    /** What {@link #setupHostPaths} would offer. */
    InitPlan.Assessment planHostPaths() {
        return planPathList(SpawnConfig.load().getHostPaths(), List.of(), "code directories");
    }

    /** The trusted definitions, which the MCP step asks about; tests give their own. */
    Map<String, ImageDef> trustedDefinitions() {
        return ImageDef.loadTrusted();
    }

    /** Templates that install Claude Code, Codex or Pi: the only ones a task can be delegated to. */
    private static List<String> mcpCandidates(Map<String, ImageDef> defs) {
        return defs.values().stream()
                .filter(def -> ImageDef.chain(def, defs).stream()
                        .anyMatch(d -> d.getTools().stream().anyMatch(t -> List.of("claude", "codex", "pi").contains(t.getName()))))
                .map(ImageDef::getName)
                .sorted()
                .toList();
    }

    /** What {@link #setupMcp(Prompts, Map)} would offer: registering isx with Claude Code, and the approvals that are the person's. */
    InitPlan.Assessment planMcp() {
        // Without Claude Code the step still asks for the approvals, which another MCP client uses (#2078)
        var claude = hostHasCommand("claude");
        McpConfig mcp;
        try {
            mcp = McpConfig.load();
        } catch (McpConfig.Invalid e) {
            return InitPlan.Assessment.unknown(e.getMessage() + ".");
        }
        // Never 'claude mcp get', which starts the server it names: isx mcp would then sweep orphans
        boolean registered;
        try {
            registered = claude && claudeMcpRegisteredInConfig();
        } catch (IOException | IllegalArgumentException e) {
            return InitPlan.Assessment.unknown("Could not read Claude Code's configuration: " + failure(e));
        }
        var approved = mcp.templates();
        var candidates = mcpCandidates(trustedDefinitions());
        // The run asks about every candidate, but one already approved keeps its answer: only the rest is a change (#2130)
        var toApprove = candidates.stream().filter(n -> !approved.contains(n)).toList();
        var offers = new ArrayList<InitPlan.Change>();
        if (claude && !registered) {
            var isx = isxPath();
            offers.add(InitPlan.Change.run("Register isx mcp with Claude Code for every project", InitPlan.Who.NONE,
                    String.join(" ", claudeMcpAddCommand(isx == null ? "<path to isx>" : isx))));
        }
        if (!toApprove.isEmpty()) {
            // The person's step, which they can run alone, at a terminal (#2077)
            offers.add(new InitPlan.Change("Approve which of " + OutputFormat.andList(toApprove)
                    + " agents may create instances from", InitPlan.Who.APPROVAL, MCP_SETUP_COMMAND,
                    McpConfig.file().toString()));
        }
        // Registered, with no template to approve: the step asks, and has nothing to change
        if (offers.isEmpty()) return InitPlan.Assessment.done().asking();
        // With no template approved yet, no agent can use isx until the person approves one: to do
        var state = approved.isEmpty() && !candidates.isEmpty() ? InitPlan.State.PENDING : InitPlan.State.OPTIONAL;
        return new InitPlan.Assessment(state, true, offers, null);
    }

    InitPlan.Assessment planBridgeDns() {
        var write = List.of(InitPlan.Change.run("Point the intercepted domains at the proxy in incusbr0's DNS",
                InitPlan.Who.NONE, null));
        if (!incusReadable()) {
            if (Platform.isLinux() && !hostHasCommand("incus") || Platform.isMacOS() && incus == null) {
                return InitPlan.Assessment.of(write).noting("Read once Incus is set up.");
            }
            return InitPlan.Assessment.unknown("Incus does not answer this process.");
        }
        if (!incus.networkExists(dev.incusspawn.incus.BridgeAddress.BRIDGE)) return InitPlan.Assessment.of(write);
        // The config, not its domains alone: the block's host.isx.internal line is what
        // setupBridgeDns writes too while exposed-host-ports: lists a port
        return ProxyConfig.isBridgeDnsComplete(incus, SpawnConfig.load())
                ? InitPlan.Assessment.done() : InitPlan.Assessment.of(write);
    }

    /** Init's mark of completion, which its last step writes before it starts the service (#938). */
    private static List<InitPlan.Change> initMarker() {
        if (Environment.hasBeenInitialized()) return List.of();
        return List.of(InitPlan.Change.write("Mark init complete for this version of isx", InitPlan.Who.NONE,
                Environment.initCompleteMarker().toString()));
    }

    /** What {@link #completeWithProxyService} would change. */
    InitPlan.Assessment planProxyService() {
        var marker = InitPlan.Assessment.of(initMarker());
        if (proxyServiceActive()) {
            var changes = new ArrayList<InitPlan.Change>();
            var stale = ProxyService.serviceFilesStale();
            if (stale || !Environment.hasBeenInitialized()) {
                changes.add(InitPlan.Change.run("Restart the proxy service" + (stale
                        ? ", with its files brought up to date" : ""), InitPlan.Who.NONE,
                        "systemctl --user restart " + Environment.PROXY_SERVICE_NAME));
            }
            return InitPlan.combine(List.of(marker, InitPlan.Assessment.of(changes)));
        }
        var user = System.getProperty("user.name");
        var offers = new ArrayList<InitPlan.Change>(List.of(
                InitPlan.Change.write("Install the proxy as a systemd user service", InitPlan.Who.NONE,
                        Environment.proxyServiceFile().toString()),
                InitPlan.Change.run("Start it", InitPlan.Who.NONE,
                        "systemctl --user enable --now " + Environment.PROXY_SERVICE_NAME)));
        if (!Files.exists(hostFile("/var/lib/systemd/linger/" + user))) {
            offers.add(InitPlan.Change.sudo("Keep it running after you log out", "sudo loginctl enable-linger " + user));
        }
        return InitPlan.combine(List.of(marker, new InitPlan.Assessment(InitPlan.State.OPTIONAL, true, offers, SERVICE_NOTE)));
    }

    /**
     * What the plan and the summary say of a service not installed: the flag that installs it
     * without a terminal. 'isx proxy start' runs the proxy in the foreground, holding its caller,
     * which an agent took for the way to start it (#2081).
     */
    static final String SERVICE_NOTE = "Install the service with 'isx init --service', or decline it with --no-service;"
            + " 'isx proxy start' runs the proxy in the foreground instead, holding its terminal.";

    /** What {@link #completeWithMacOsServices} would change. */
    InitPlan.Assessment planMacOsServices() {
        var marker = InitPlan.Assessment.of(initMarker());
        if (macOsServicesInstalled()) return marker;
        return InitPlan.combine(List.of(marker, new InitPlan.Assessment(InitPlan.State.OPTIONAL, true, List.of(
                InitPlan.Change.write("Install the VM and the proxy as launch agents", InitPlan.Who.NONE,
                        Environment.home().resolve("Library/LaunchAgents").toString())), SERVICE_NOTE)));
    }

    /** What {@link #requireIncusHost} does on macOS before init: download the appliance if missing, start the VM. */
    InitPlan.Assessment planIncusVm() {
        if (incus != null) return InitPlan.Assessment.done();
        var changes = new ArrayList<InitPlan.Change>();
        if (!Files.exists(Environment.applianceKernel())
                || !Files.exists(Environment.applianceDiskImage()) && !Files.exists(Environment.vmDiskImage())) {
            changes.add(InitPlan.Change.write("Download the VM appliance", InitPlan.Who.NONE,
                    Environment.applianceDir().toString()));
        }
        changes.add(InitPlan.Change.run("Start the VM", InitPlan.Who.NONE, "isx vm start"));
        return InitPlan.Assessment.of(changes);
    }

    /** What {@link #setupMacOsCa} would change. */
    InitPlan.Assessment planMacOsCa() {
        var changes = new ArrayList<InitPlan.Change>();
        String note = null;
        if (incus == null) {
            changes.add(InitPlan.Change.write("Record the bridge's gateway", InitPlan.Who.NONE, HostSetup.configFile()));
            note = "The VM is not running, so what Incus holds is read once it is.";
        } else {
            if (!incus.networkExists(dev.incusspawn.incus.BridgeAddress.BRIDGE)) {
                changes.add(InitPlan.Change.run("Create the incusbr0 bridge", InitPlan.Who.NONE, null));
            }
            var gatewayIp = ProxyConfig.resolveGatewayIp(incus);
            if (!gatewayIp.equals(SpawnConfig.load().getIncusBridgeGateway())) {
                changes.add(InitPlan.Change.write("Record the bridge's gateway " + gatewayIp, InitPlan.Who.NONE, HostSetup.configFile()));
            }
        }
        if (!CertificateAuthority.exists()) {
            changes.add(InitPlan.Change.write("Generate the MITM CA certificate", InitPlan.Who.NONE,
                    Environment.configDir().resolve("ca.crt").toString()));
        }
        return InitPlan.Assessment.of(changes).noting(note);
    }

    static int runHost(String... command) {
        dev.incusspawn.util.Privilege.check(command);
        // The command writes to the terminal itself, and sudo may ask for a password there
        BuildOutput.releaseTerminal();
        try {
            var pb = new ProcessBuilder(command);
            pb.inheritIO();
            return StdoutOnStderr.run(pb);
        } catch (IOException | InterruptedException e) {
            BuildOutput.warn("Failed to run: " + String.join(" ", command) + ": " + failure(e));
            return 1;
        }
    }

    String captureOutput(String... command) {
        dev.incusspawn.util.Privilege.check(command);
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            return output;
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    /**
     * Run one host command of a step and say which one failed, so the step can withhold its
     * success line: a step reports what it did, not what it attempted (#1102).
     */
    boolean ran(String... command) {
        if (runHostQuiet(command) == 0) return true;
        BuildOutput.warn("Failed: " + String.join(" ", command));
        return false;
    }

    /**
     * Run a host command with its output captured, shown only when it fails, as
     * {@code Container.runQuiet()} does during build: firewall-cmd's {@code ALREADY_ENABLED} and
     * {@code NOT_ENABLED} warnings on a successful call are noise in the step (#906). The caller
     * reports the outcome.
     */
    int runHostQuiet(String... command) {
        dev.incusspawn.util.Privilege.check(command);
        // sudo may ask for a password on the tty, which a live step line would be drawn over
        if (command.length > 0 && command[0].equals("sudo")) BuildOutput.releaseTerminal();
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes());
            var exitCode = process.waitFor();
            if (exitCode != 0) {
                // The host's own text, shown as text: notes, which the caller's report of the failure follows
                output.lines().map(String::strip).filter(line -> !line.isEmpty())
                        .forEach(line -> BuildOutput.note(BuildOutput.guestText(line)));
            }
            return exitCode;
        } catch (IOException | InterruptedException e) {
            BuildOutput.warn("Failed to run: " + String.join(" ", command) + ": " + failure(e));
            return 1;
        }
    }
}
