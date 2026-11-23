package dev.incusspawn.command;

import dev.incusspawn.ClientLog;
import dev.incusspawn.Environment;
import dev.incusspawn.OwnDirectory;
import dev.incusspawn.proxy.HostRouteSecret;
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
import dev.incusspawn.tool.BackgroundDownloads;
import dev.incusspawn.tool.DownloadCache;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Output;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.TerminalLink;
import dev.incusspawn.vm.VmManager;
import dev.incusspawn.Platform;
import dev.incusspawn.util.HostPath;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;
import dev.incusspawn.util.Privilege;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    @Option(name = AgentGitIdentityStep.FLAG, hasValue = false,
            description = "Let agents with no GitHub account commit under this host's Git name and email, without asking")
    boolean gitIdentityFromHost;

    @Option(name = "no-sudo", hasValue = false,
            description = "Run nothing as root: write the steps that need root as a script for you to run, then carry on")
    boolean noSudo;

    @Option(name = "plan", hasValue = false,
            description = "Say what init would change on this host and who must do each step, changing nothing")
    boolean plan;

    @Argument(description = "Run one step alone: credentials, search-paths, code-directories or mcp")
    String only;

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

    /** A top border that carries {@code title}: {@code ╭─ isx setup ───╮}. */
    private static String topBorder(String title) {
        return styled(CYAN, "╭─ ") + styled(BOLD, title) + styled(CYAN, " " + "─".repeat(BOX_WIDTH - title.length() - 3) + "╮");
    }

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
    // Not final: a flow test hands in one whose network and host calls are its own
    CredentialSetup credentials = new CredentialSetup();

    private static String pad(String s, int width) {
        int vlen = stripAnsi(s).length();
        if (vlen >= width) return s;
        return s + " ".repeat(width - vlen);
    }

    /** {@code content} between the frame's borders, padded to what the terminal draws of it. */
    static String boxLine(String content) {
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
        var configured = isConfigured();
        printBanner("isx — " + (configured ? "Setup" : "First-Time Setup") + platform,
                configured ? "Reviewing your existing configuration"
                        : "Configuring your isolated development environment",
                info);
    }

    /** Whether init ran here before, by any marker or a config file: a re-run, not a first one. */
    static boolean isConfigured() {
        return Files.exists(Environment.initCompleteMarker())
                || Files.exists(Environment.configDir().resolve("config.yaml"));
    }

    void startStep(String title, String... hintLines) {
        currentStep++;
        // The number once, in [n/m] (#2528)
        String left = "  " + title;
        String right = "[" + currentStep + "/" + totalSteps + (totalKnown ? "" : "+") + "] ";
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

    /**
     * The steps that ask the person nothing on the usual path, which a run shows as one block (#2406):
     * the lines the step itself prints as it works and completes (each says what it did), with no
     * header and no hint. Incus Installation, User Namespace Mappings and Storage & Network still ask
     * for a confirmation when they find Incus missing, an unexpected subid entry or no CoW pool: the
     * question then shows the step's header and hint first ({@link #prompts}). The two service steps
     * are not here, since each always asks whether to install.
     */
    static final Set<String> PLUMBING = Set.of("incus", "subids", "storage-network", "firewall", "mitm-proxy",
            "ca", "ssh-key", "dns");

    static boolean isPlumbing(InitStep step) {
        return PLUMBING.contains(step.id());
    }

    private static long numbered(List<InitStep> steps) {
        return steps.stream().filter(s -> !isPlumbing(s)).count();
    }

    /** The open block of plumbing steps, if the last step run was one. */
    private Output.Group plumbingBlock;

    private void closePlumbingBlock() {
        if (plumbingBlock == null) return;
        plumbingBlock.close();
        plumbingBlock = null;
    }

    /**
     * Shows {@code step}'s header, then runs it.
     * A plumbing step joins the block instead, and prints its hint only if it fails, with its error.
     */
    void begin(InitStep step) throws Exception {
        if (!isPlumbing(step) && !allPlumbing) {
            closePlumbingBlock();
            startStep(step.title(), step.hint());
            step.run().run();
            return;
        }
        if (plumbingBlock == null) plumbingBlock = BuildOutput.group("Setting up " + dev.incusspawn.Platform.thisComputer(), "");
        unannounced = step;
        try {
            step.run().run();
        } catch (WaitingOnPerson waiting) {
            closePlumbingBlock();
            throw waiting;
        } catch (Exception | Error e) {
            closePlumbingBlock();
            // Its hint, unless the step showed it with a question
            if (unannounced == step) BuildOutput.warn(step.title() + " failed", step.hint());
            else BuildOutput.warn(step.title() + " failed");
            throw e;
        } finally {
            unannounced = null;
        }
    }

    /** The first run's closing box under {@code title}, then {@code later} under it, at the step indent. */
    private static void printClosing(String title, List<String> box, List<String> later) {
        var out = new ArrayList<String>();
        out.add("");
        out.add(topBorder(title));
        out.add(boxLine(""));
        for (var line : box) out.add(boxLine(line));
        out.add(boxLine(""));
        out.add(bottomBorder());
        out.add("");
        for (var line : later) out.add("    " + line);
        out.add("");
        // A frame of init's own: laid out here, drawn as given
        BuildOutput.frame(out.toArray(String[]::new));
    }

    private static void printCompletionBox(String... lines) {
        printCompletionBox(true, lines);
    }

    /**
     * The closing box, with {@link #credentialGapLines} when {@code gaps}: a first run's message
     * names the one account its template lacks itself, and is kept to what a first agent needs.
     */
    private static void printCompletionBox(boolean gaps, String... lines) {
        var out = new ArrayList<String>();
        out.add("");
        out.add(topBorder());
        out.add(boxLine(""));
        for (var line : lines) {
            out.add(boxLine(line));
        }
        for (var line : gaps ? credentialGapLines() : List.<String>of()) {
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
            var init = new InitCommand();
            init.automatic = true;
            var result = init.doExecute();
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
        if (only != null) {
            if (plan) throw new IllegalArgumentException("--plan reads every step; run it without a step name");
            if (service || noService || noSudo || all || outputFormat != OutputFormat.TABLE) {
                throw new IllegalArgumentException("'isx init " + OutputFormat.oneLine(only) + "' takes only the flags"
                        + " that answer it: --search-path, --code-dir, --fork-templates or --" + AgentGitIdentityStep.FLAG);
            }
            // An unknown name is said as one, not as flags its step would not read
            var foreign = DEFERRED.contains(only) ? flagsNotFor(only) : List.<String>of();
            if (!foreign.isEmpty()) {
                throw new IllegalArgumentException(String.join(", ", foreign)
                        + (foreign.size() == 1 ? " answers a question 'isx init " : " answer questions 'isx init ")
                        + OutputFormat.oneLine(only) + "' does not ask");
            }
            checkAnswers();
            return CommandResult.valueOf(runOne(only));
        }
        if (plan) {
            if (all) throw new IllegalArgumentException("--plan already reads every step; run it without --all");
            if (answersGiven()) throw new IllegalArgumentException("--plan only reads; the answers to init's questions go without it");
            return printPlan(outputFormat);
        }
        checkAnswers();
        if (outputFormat == OutputFormat.TABLE) return runInit();
        // A script reads the summary on stdout, so everything on the way there goes to stderr
        var result = withStdoutOnStderr(this::runInit);
        return printSummary(outputFormat, result);
    }

    /**
     * The answer flags given that the step {@code id} does not read, so that none is dropped
     * unsaid: search-paths reads --search-path and --fork-templates, code-directories --code-dir,
     * credentials (followed by the Git identity question) --git-identity-from-host, mcp none.
     */
    List<String> flagsNotFor(String id) {
        var foreign = new ArrayList<String>();
        if (searchPathAnswers != null && !id.equals("search-paths")) foreign.add("--search-path");
        if (forkTemplates && !id.equals("search-paths")) foreign.add("--fork-templates");
        if (codeDirAnswers != null && !id.equals("code-directories")) foreign.add("--code-dir");
        if (gitIdentityFromHost && !id.equals("credentials")) foreign.add("--" + AgentGitIdentityStep.FLAG);
        return foreign;
    }

    private boolean answersGiven() {
        return searchPathAnswers != null || codeDirAnswers != null || forkTemplates || service || noService || gitIdentityFromHost || all;
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
        firstRun = isFirstRun();
        rerun = !firstRun && Files.exists(Environment.initCompleteMarker()) && prompts() != null && OutputFormat.parse(format) == OutputFormat.TABLE && !answersGiven();
        // From here this process elevates nothing: every sudo is the script's (#2074)
        if (noSudo) Privilege.forbid();
        // A first run on macOS asks its questions before the VM starts, which can download its appliance
        if (!(firstRun && Platform.isMacOS()) && !requireIncusHost()) return CommandResult.valueOf(1);
        ownConfigDirectory(Environment.configDir());
        // On macOS a first run reaches Incus only once its VM runs, after the questions, and a
        // client that failed to connect keeps failing: it is made then, and null until it is
        if (!(firstRun && Platform.isMacOS())) this.incus = RuntimeServices.incus();
        if (Platform.isMacOS()) {
            return firstRun ? runFirstRun(true) : doMacOsInit();
        }
        if (!requireLinux()) {
            return CommandResult.valueOf(1);
        }
        var continued = continuedInGroup();
        if (noSudo) {
            var waiting = privilegedStepsLeft();
            if (waiting != null) return waiting;
        }
        if (firstRun) return runFirstRun(false);
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

        offerMenu();
        var next = new ArrayList<>(List.of(nextSteps(linuxServices(proxyServiceInstalled.get()))));
        if (continued) next.addAll(GROUP_PENDING);
        printCompletionBox(next.toArray(String[]::new));
        return CommandResult.SUCCESS;
    }

    /**
     * Makes the config directory before any step writes into it, so no umask makes it
     * group-writable (#2136). One an older isx made so is never repaired, since group write it
     * had may have been used: init says so here rather than leaving it to the first build.
     */
    static void ownConfigDirectory(Path configDir) {
        Path dir;
        Integer uid;
        try {
            dir = OwnDirectory.create(configDir).toRealPath();
            // As the build and the proxy judge it, by the uid this process creates files as, but
            // probed afresh: a cached uid would skip the probe that finds a root-owned directory
            uid = HostRouteSecret.probedUid(dir);
        } catch (AccessDeniedException e) {
            // Denied making it, a parent or the probe in it: name the directory that refused,
            // never the file that could not be made there
            var denied = existingAncestor(e.getFile() == null ? configDir : Path.of(e.getFile()));
            refused(configDir, "isx cannot write to " + denied + ownerOf(denied) + " (chown it to yourself, and chmod u+w it)");
            return;
        } catch (IOException e) {
            // Something else in its place, or a read-only file system: said, as for a refusal,
            // rather than ending init before any step
            refused(configDir, "isx cannot make it: " + e.getMessage());
            return;
        }
        ownConfigDirectory(dir, uid);
    }

    /** ", which belongs to uid N", or nothing where that cannot be read. */
    private static String ownerOf(Path dir) {
        try {
            return ", which belongs to uid " + OwnDirectory.uidOf(dir);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** {@code path}, or the nearest directory above it that exists, resolved where it can be. */
    private static Path existingAncestor(Path path) {
        var at = path.toAbsolutePath();
        while (at.getParent() != null && !Files.isDirectory(at)) at = at.getParent();
        try {
            // Real, as the other warnings name the directory: macOS's /var is a link to /private/var
            return at.toRealPath();
        } catch (IOException e) {
            return at;
        }
    }

    /** That {@code dir}, real, is {@code uid}'s and nobody else's to write, else a warning saying why. */
    static void ownConfigDirectory(Path dir, Integer uid) {
        try {
            OwnDirectory.require(dir, uid, uid, "isx");
        } catch (OwnDirectory.NotOwn e) {
            // Someone else's, as an earlier sudo isx init may leave it, or writable by others
            refused(dir, "The credential proof host key " + e.getMessage());
        } catch (IOException e) {
            // Its owner or mode unreadable: said, rather than ending init before any step
            refused(dir, "isx cannot read who may write to it: " + e.getMessage());
        }
    }

    private static void refused(Path dir, String why) {
        BuildOutput.warn("Builds and host routes will refuse the secrets in " + dir, why + ".",
                "Check that nothing in it is someone else's first.");
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
        // A first run shows a step's header and hint only when the step asks: in its own words (#2528)
        var first = firstRun;
        return List.of(
                new InitStep("incus", first ? "Incus" : "Incus Installation", first ? HostSetup.INCUS_FIRST_RUN : HostSetup.INCUS_HINT,
                        this::installIncus, host::planIncusInstallation),
                new InitStep("subids", first ? "User Mappings" : "User Namespace Mappings", first ? HostSetup.SUBID_FIRST_RUN : HostSetup.SUBID_HINT,
                        host::configureSubuidSubgid, host::planSubids),
                new InitStep("storage-network", "Storage & Network", first ? HostSetup.STORAGE_FIRST_RUN : HostSetup.STORAGE_HINT,
                        host::initializeIncus, host::planStorageAndNetwork),
                new InitStep("firewall", first ? "Firewall" : "Firewall Configuration", first ? HostSetup.FIREWALL_FIRST_RUN : HostSetup.FIREWALL_HINT,
                        host::configureFirewall, host::planFirewall),
                new InitStep("mitm-proxy", first ? "The isx Proxy" : "MITM Authentication Proxy", first ? HostSetup.MITM_FIRST_RUN : HostSetup.MITM_HINT,
                        host::configureMitmProxy, host::planMitmProxy),
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
        return GroupContinuation.sgCommand(ownCommandLine(), captureOutput("id", "-gn"));
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
        return new GroupContinuation().ownCommandLine();
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
            if (answers != null) pb.environment().put(ANSWERS_ENV, answers.encode());
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
        offerMenu();

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
        INSTALLED(null, null),
        START_PROXY("Start the proxy", "isx proxy start"),
        START_VM_AND_PROXY("Start VM, proxy", "isx vm start && isx proxy start"),
        /** Skipped for want of a terminal: the flag that installs them, since 'isx proxy start' holds its terminal. */
        INSTALL_PROXY("Install the proxy", "isx init --service"),
        INSTALL_PROXY_NO_SUDO("Install the proxy", "isx init --no-sudo --service"),
        INSTALL_VM_AND_PROXY("Install services", "isx init --service");

        /** What the person does, in a few words, and the command that does it; both null when nothing is left. */
        final String label;
        final String command;
        /** Both on one line, for the walk's closing box. */
        final String step;

        Services(String label, String command) {
            this.label = label;
            this.command = command;
            this.step = label == null ? null : String.format("%-19s%s", label + ":", command);
        }

        /** Whether the command keeps its terminal until it is stopped, so the next step needs another. */
        boolean holdsTerminal() {
            return this == START_PROXY || this == START_VM_AND_PROXY;
        }
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
        // A re-run at a terminal asks nothing it asked before: those steps are in the menu after it (#2407)
        var asks = !rerun;
        var shown = asks ? finish : finish.stream().filter(s -> !DEFERRED.contains(s.id())).toList();
        currentStep = (int) numbered(setup.subList(0, done));
        totalSteps = (int) (numbered(setup) + (asks ? 1 : 0) + numbered(shown));
        totalKnown = !asks;
        try {
            for (var step : setup.subList(done, setup.size())) begin(step);
            closePlumbingBlock();
            runRest(shown, asks);
        } finally {
            closePlumbingBlock();
        }
    }

    private void runRest(List<InitStep> finish, boolean asks) throws Exception {
        if (asks) {
            var credentialSteps = credentialSteps();
            totalSteps += credentialSteps.size();
            totalKnown = true;
            for (var step : credentialSteps) begin(step);
        }
        credentials.closeHttpClient();
        // A question too: a re-run at a terminal asks it no more than the credentials (#2407)
        if (asks) offerAgentGitIdentity();
        gitRemoteHelper().run().run();
        for (var step : finish) begin(step);
    }

    /**
     * The steps a person runs when they need them, by name ({@code isx init <step>}), and from the
     * menu a re-run shows (#2407): each asks a question only an established user can answer.
     */
    static final List<String> DEFERRED = List.of("credentials", "search-paths", "code-directories", "mcp");

    /** What each of {@link #DEFERRED} is for, in one line of the menu. */
    static final Map<String, String> PURPOSE = Map.of(
            "credentials", "The accounts the proxy injects for your agents and tools",
            "search-paths", "Where isx finds templates of your own, and the templates repository",
            "code-directories", "Your repositories on this host, for fast clones and Git remotes",
            "mcp", "Which templates an agent on this host may create instances from");

    /** Whether this run is init again at a terminal, which shows {@link #DEFERRED} as a menu rather than asking each. */
    boolean rerun;

    /** Whether {@link #requireInit} started this run for another command, which then carries on: no menu. */
    boolean automatic;

    /** The step of {@link #DEFERRED} named {@code id}. */
    InitStep deferredStep(String id) {
        return switch (id) {
            case "credentials" -> credentialSetup();
            case "mcp" -> mcpStep();
            case "search-paths" -> searchPathsStep();
            case "code-directories" -> codeDirectoriesStep();
            default -> throw new IllegalArgumentException(id);
        };
    }

    /**
     * {@code isx init <step>}: runs one of {@link #DEFERRED} alone, and nothing else, as
     * {@code isx mcp setup} runs the approvals.
     *
     * @return the exit code
     */
    int runOne(String id) throws Exception {
        if (!DEFERRED.contains(id)) {
            BuildOutput.error("no init step named '" + OutputFormat.oneLine(id) + "'.",
                    "Steps that run alone: " + String.join(", ", DEFERRED) + ".");
            return 1;
        }
        if (id.equals("credentials")) {
            if (prompts() == null) {
                BuildOutput.error("'isx init credentials' asks for the credential, so it needs a terminal.");
                return 1;
            }
            var code = addCredential(null);
            // Where Credential Setup runs, its Git identity question follows (#2410)
            if (code == 0) offerAgentGitIdentity();
            return code;
        }
        // As 'isx mcp setup': the step says which templates are built, which only Incus knows
        if (id.equals("mcp") && incus == null && prompts() != null) this.incus = RuntimeServices.incus();
        currentStep = 0;
        totalSteps = 1;
        totalKnown = true;
        begin(deferredStep(id));
        return 0;
    }

    /** The menu of {@link #DEFERRED} on a re-run at a terminal, until the person presses Enter. */
    void offerMenu() throws Exception {
        if (!rerun || automatic) return;
        var prompts = prompts();
        if (prompts == null) return;
        while (true) {
            var entries = planEntries(DEFERRED.stream().map(this::deferredStep).toList());
            var width = entries.stream().mapToInt(e -> InitPlan.stateLabel(e.assessment()).length()).max().orElse(0);
            BuildOutput.section("More setup, also run alone as 'isx init <step>'");
            for (int i = 0; i < entries.size(); i++) prompts.say(menuLine(i + 1, entries.get(i), width));
            var answer = readInput(prompts.ask("Run which (number or name, Enter to finish): "));
            if (answer.isEmpty()) return;
            var id = menuChoice(answer);
            if (id == null) {
                prompts.say("No step '" + OutputFormat.oneLine(answer) + "'.");
                continue;
            }
            try {
                runOne(id);
            } catch (Exception e) {
                // One step failing leaves init done and the menu open for the others
                BuildOutput.error(failure(e));
            }
        }
    }

    /** One line of the menu: its number, the step's name, where it stands and what it is for. */
    static String menuLine(int number, InitPlan.Entry entry, int stateWidth) {
        return String.format("%d  %-17s %-" + Math.max(1, stateWidth) + "s %s", number, entry.step(),
                InitPlan.stateLabel(entry.assessment()), PURPOSE.get(entry.step()));
    }

    /** The step of {@link #DEFERRED} that {@code answer}, a number from the menu or a name, chooses; null for none. */
    static String menuChoice(String answer) {
        answer = answer.toLowerCase(java.util.Locale.ROOT);
        if (DEFERRED.contains(answer)) return answer;
        try {
            var n = Integer.parseInt(answer);
            return n >= 1 && n <= DEFERRED.size() ? DEFERRED.get(n - 1) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Once the credentials are set: this host's Git identity for agents no GitHub account names (#2410). */
    void offerAgentGitIdentity() {
        SpawnConfig config;
        try {
            // Strict: load() reads a broken config.yaml as empty, and saving that would drop every account in it.
            // Not loadToChange: the offer is optional and comes last, so a broken file skips it rather than failing init.
            config = SpawnConfig.loadStrict();
        } catch (IOException e) {
            BuildOutput.warn("Did not offer agents a Git identity: " + dev.incusspawn.config.YamlErrors.friendly("config.yaml", e),
                    "Fix config.yaml, then run 'isx init credentials'.");
            return;
        }
        AgentGitIdentityStep.offer(config, prompts(), gitIdentityFromHost, this::hostGitIdentity,
                firstRun ? "isx machines" : "instances", !firstRun || chosenAgent != null);
    }

    /** This host's Git identity, or null; tests answer it instead. */
    dev.incusspawn.config.GitIdentity hostGitIdentity() {
        return AgentGitIdentityStep.fromHost();
    }

    // --- The first run (#2412) ---

    @Option(name = "all", hasValue = false,
            description = "Ask every step in turn, those a first run leaves for later included")
    boolean all;

    /**
     * Set in the continuation's environment beside {@link #CONTINUED_ENV}: the first run's answers
     * that store nothing, so the continuation asks no question again. None is a secret.
     */
    static final String ANSWERS_ENV = "ISX_INIT_ANSWERS";

    /**
     * What a first run's questions were answered that config.yaml does not hold: "none for now",
     * the agent's credential declined, the GitHub account skipped, the Git identity answered No.
     */
    record FirstRunAnswers(boolean noAgent, boolean noCredential, boolean noGitHub, boolean noIdentity) {
        String encode() {
            var words = new ArrayList<>(List.of("asked"));
            if (noAgent) words.add("no-agent");
            if (noCredential) words.add("no-credential");
            if (noGitHub) words.add("no-github");
            if (noIdentity) words.add("no-identity");
            return String.join(",", words);
        }

        /** The answers {@link #encode} wrote, or null when {@code value} holds none. */
        static FirstRunAnswers decode(String value) {
            if (value == null) return null;
            var words = Set.copyOf(List.of(value.split(",")));
            if (!words.contains("asked")) return null;
            return new FirstRunAnswers(words.contains("no-agent"), words.contains("no-credential"),
                    words.contains("no-github"), words.contains("no-identity"));
        }
    }

    /** Whether this run is a first run, {@link #runFirstRun}: decided once, at the start of the run. */
    boolean firstRun;

    /** The agent chosen at the first run's question, or null for "none for now". */
    ToolSetup chosenAgent;

    /** The first run's answers that store nothing, once its questions were asked or handed over. */
    FirstRunAnswers answers;

    /** The command that adds the account the chosen agent still lacks, or null when it has one. */
    String agentAccountCommand;

    private final List<BackgroundDownloads> downloadsAhead = new ArrayList<>();

    /**
     * Whether this run is a first run: at a terminal, in the table format, on a host where init
     * never completed, or with {@code --all}. A host that completed init once gets the menu
     * instead, and a run with no terminal keeps the walk it always had. The continuation under
     * {@code sg} is one when its parent passed it the answers.
     */
    boolean isFirstRun() {
        if (continuedInGroup()) return continuationAnswers() != null;
        return prompts() != null && OutputFormat.parse(format) == OutputFormat.TABLE
                && (all || !Files.exists(Environment.initCompleteMarker()));
    }

    /** The answers the process that started this continuation passed it, or null. */
    FirstRunAnswers continuationAnswers() {
        return FirstRunAnswers.decode(System.getenv(ANSWERS_ENV));
    }

    /**
     * The first run on either platform: the questions first, then the host's plumbing as one
     * block and its services, then the first template's build and the offer to open it. The
     * continuation under {@code sg} asks none of the questions: its parent asked them, wrote what
     * is a setting to config.yaml and passed the rest in {@link #ANSWERS_ENV}.
     */
    CommandResult runFirstRun(boolean macOs) throws Exception {
        var continued = continuedInGroup();
        try {
            if (continued) {
                answers = continuationAnswers();
                chosenAgent = answers.noAgent() ? null : preferredAgentTool();
            } else {
                printFirstRunIntro(macOs);
                startDownloadsAhead(null);
                askFirstRunQuestions();
                if (macOs) {
                    if (!requireIncusHost()) return CommandResult.valueOf(1);
                    this.incus = RuntimeServices.incus();
                }
                if (!macOs && !noSudo) {
                    // The one sudo prompt the intro promised, said before it asks (#2528)
                    BuildOutput.step("isx asks for your sudo password now, to install packages,");
                    BuildOutput.step("set up the firewall and the user namespace mappings.");
                    if (runHost("sudo", "-v") != 0) {
                        BuildOutput.error("sudo authentication failed.", "Please ensure you have sudo access and try again.");
                        return CommandResult.valueOf(1);
                    }
                    dependencies().run().run();
                }
            }
            var servicesInstalled = new AtomicBoolean();
            // The plumbing saves config.yaml after a lenient read (the gateway), and a continuation
            // under sg, or a file edited while sudo and the install ran, was never read strictly here
            firstRunConfig();
            try {
                // A continuation starts after Incus Installation, which its parent ran
                runPlumbing(firstRunPlumbing(macOs, servicesInstalled), !macOs && continued ? 1 : 0);
                // The paths and settings the steps wrote are in the log, not on the screen (#2528)
                var log = Environment.clientLogFile().toString();
                var home = System.getProperty("user.home");
                BuildOutput.note("Details of this setup are in its log:");
                BuildOutput.step(OutputFormat.oneLine(log.startsWith(home + "/") ? "~" + log.substring(home.length()) : log));
            } catch (WaitingOnPerson waiting) {
                return handOver(waiting.changes);
            } catch (Exception e) {
                // The failure is reported by the command; what to do about a run that stopped half way is said here
                BuildOutput.step("Setup stopped before it finished.");
                // Only what this run kept is named: an account counts when one was given or was there (#2528)
                var accounts = answers != null && (!answers.noGitHub() || chosenAgent != null && !answers.noCredential());
                var restart = "Fix the error below, then run 'isx init' again.";
                if (chosenAgent != null && accounts) {
                    Lines.step("Your agent and your accounts are saved. " + restart
                            + " Enter keeps your agent, and it asks only for the accounts you skipped.");
                } else if (chosenAgent != null) {
                    Lines.step("Your agent is saved. " + restart + " Enter keeps your agent.");
                } else if (accounts) {
                    Lines.step("Your accounts are saved. " + restart + " It asks its questions again, and keeps those accounts.");
                } else {
                    Lines.step(restart + " It asks its questions again.");
                }
                throw e;
            }
            return finishFirstRun(macOs, servicesInstalled.get(), continued);
        } finally {
            downloadsAhead.forEach(BackgroundDownloads::close);
        }
    }

    /** What the agent runs in: "isx machines" in the first run, which introduces the word, else "instances" (#2528). */
    String machines() {
        return firstRun ? "isx machines" : "instances";
    }

    /**
     * What a first run shows before its first question: a map of the run, in the box (#2528).
     * What the person answers lists only the questions this run asks. A re-run, on a computer
     * init configured before, keeps its own banner.
     */
    void printFirstRunIntro(boolean macOs) {
        // Only a computer where init completed is a re-run: a config.yaml written by 'isx account
        // add', or by a first run that stopped, still gets the map
        if (Files.exists(Environment.initCompleteMarker())) {
            printSetupBanner(macOs ? " (macOS)" : "", noSudo ? "runs nothing as root: the script did that" : "~5 minutes");
            return;
        }
        var config = SpawnConfig.load();
        var needsGitHub = dev.incusspawn.config.AccountResolver.usableAccountNames(config, dev.incusspawn.tool.GhSetup.NAMESPACE).isEmpty();
        // The questions for the agent the run offers first; with none to offer, those of an agent with an account to set up
        var suggested = suggestedAgent();
        var questions = suggested == null ? new Questions(true, false, needsGitHub) : questionsFor(suggested, needsGitHub);
        var asked = new ArrayList<>(List.of("your coding agent"));
        if (questions.accountIsGitHub()) asked.add("its GitHub account");
        else if (questions.account()) asked.add("its account");
        if (questions.gitHub()) asked.add("GitHub");
        var out = new ArrayList<String>();
        out.add("");
        out.add(topBorder("isx setup"));
        out.add(boxLine(""));
        out.add(boxLine(String.format("   %-13s%s", "You answer", String.join(", ", asked))));
        if (macOs) {
            out.add(boxLine(String.format("   %-13s%s", "isx then", "sets up this Mac: a Linux VM and the proxy")));
        } else if (noSudo) {
            // The root steps ran from the script the --no-sudo run before this one printed
            out.add(boxLine(String.format("   %-13s%s", "isx then", "sets up the rest of this computer")));
            out.add(boxLine(String.format("   %-13s%s", "", "(you ran init-privileged.sh as root)")));
        } else {
            out.add(boxLine(String.format("   %-13s%s", "isx then", "sets up this computer: Incus and the proxy")));
            out.add(boxLine(String.format("   %-13s%s", "", "(asks for your sudo password)")));
        }
        out.add(boxLine(String.format("   %-13s%s", "", "builds your first template")));
        out.add(boxLine(String.format("   %-13s%s", "You get", "an isx machine (an isolated Linux system)")));
        out.add(boxLine(String.format("   %-13s%s", "", "with your agent, ready to open")));
        out.add(boxLine(""));
        out.add(boxLine("   " + styled(DIM, "3 to 5 minutes, most of it downloads.")));
        out.add(boxLine(""));
        out.add(bottomBorder());
        // A frame of init's own: laid out here, drawn as given
        BuildOutput.frame(out.toArray(String[]::new));
    }


    /** The preferred agent in config.yaml, as a coding agent's tool, or null. */
    ToolSetup preferredAgentTool() {
        var preferred = SpawnConfig.load().getPreferredAgent();
        return codingAgentTools().stream().filter(a -> a.name().equals(preferred)).findFirst().orElse(null);
    }

    /** The coding agents init offers, in the order isx lists its tools. */
    List<ToolSetup> codingAgentTools() {
        return AgentCommand.agents(RuntimeServices.toolSetups());
    }

    /** The agents found on this host; tests answer it instead. */
    List<HostAgentProbe.HostAgent> probeAgents() {
        return HostAgentProbe.probe();
    }

    /** The host's {@code gh} login, or null; tests answer it instead. */
    String ghLogin() {
        return getGhLogin();
    }

    /**
     * Starts fetching, in the background and silently, what the first template's build downloads:
     * with no agent, {@code tpl-minimal}'s base image, and with one, the agent's own downloads.
     */
    void startDownloadsAhead(ToolSetup agent) {
        downloadsAhead.add(BackgroundDownloads.start(new DownloadCache(), () -> {
            var planned = firstTemplateDownloads(agent);
            if (agent == null) return planned;
            var started = firstTemplateDownloads(null).stream().map(DownloadCache.Pinned::url).collect(Collectors.toSet());
            return planned.stream().filter(p -> !started.contains(p.url())).toList();
        }));
    }

    /**
     * What the build of the first template for {@code agent} downloads, on a host with nothing
     * built. Only trusted definitions count: a project's {@code .incus-spawn/} in the working
     * directory is a cloned repository's, never the person's first template.
     */
    static List<DownloadCache.Pinned> firstTemplateDownloads(ToolSetup agent) throws IOException {
        var config = SpawnConfig.load();
        var defs = new LinkedHashMap<>(ImageDef.loadTrusted(warning -> {}));
        var template = agent == null ? defs.get("tpl-minimal") : firstTemplate(defs, agent, config);
        if (template == null) return List.of();
        defs.putIfAbsent(template.getName(), template);
        // Trusted tools too: a project's own tool is not fetched for a template init would not build with it
        return BuildDownloads.plan(template, defs, template.machineType(), ImageDef.hostArch(), name -> false,
                BuildCommand::trackedBaseImageQuietly, ToolDefLoader.withoutProjectTools(config.getSearchPaths()),
                RuntimeServices.toolSetups());
    }

    /** The first template for {@code agent} among {@code trusted}: the person's own tpl-agent, kept, else the one init writes. */
    static ImageDef firstTemplate(Map<String, ImageDef> trusted, ToolSetup agent, SpawnConfig config) throws IOException {
        var own = trusted.get(ExampleTemplate.NAME);
        return own != null ? own : ImageDef.parseYaml(ExampleTemplate.text(agent, ExampleTemplate.hasGitHubAccount(config)));
    }

    /** The trusted definitions, which decide the first template; tests answer them instead. */
    Map<String, ImageDef> trustedTemplates() {
        return ImageDef.loadTrusted(warning -> {});
    }

    /** The definitions a build in this directory reads, a project's {@code .incus-spawn/} included; tests answer them instead. */
    Map<String, ImageDef> templatesHere() {
        return ImageDef.loadAll(warning -> {});
    }

    /**
     * The agent the first template installs: {@link #chosenAgent}, or the first coding agent a
     * kept tpl-agent of the person's own names, which may be another (an earlier run's).
     */
    ToolSetup templateAgent() {
        if (chosenAgent == null) return null;
        var trusted = trustedTemplates();
        var own = trusted.get(ExampleTemplate.NAME);
        if (own == null) return chosenAgent;
        var chain = ImageDef.chain(own, trusted);
        // With what they require, as a build installs them; an agent the chain lists itself comes first
        var names = chainTools(chain, trustedToolLoader().allToolSetups());
        if (names.contains(chosenAgent.name())) return chosenAgent;
        var listed = new java.util.HashSet<String>();
        for (var layer : chain) layer.getTools().forEach(t -> listed.add(t.getName()));
        var agents = codingAgentTools();
        return agents.stream().filter(a -> listed.contains(a.name())).findFirst()
                .or(() -> agents.stream().filter(a -> names.contains(a.name())).findFirst())
                .orElse(chosenAgent);
    }

    /**
     * The first run's questions, before any work: the agent, its account, a GitHub account (and,
     * when it is skipped, the Git identity). With {@code --all} the steps a first run leaves for
     * later follow, and a step whose answer flag was given runs with that answer.
     */
    void askFirstRunQuestions() throws Exception {
        currentStep = 0;
        // Its questions use the first run's words, and leave some questions to later (#2528)
        firstRun = true;
        credentials.firstRun = true;
        // A broken config.yaml stops the run before its first question, rather than at a step that saves
        var gitHub = firstRunConfig();
        var needsGitHub = dev.incusspawn.config.AccountResolver.usableAccountNames(gitHub, dev.incusspawn.tool.GhSetup.NAMESPACE).isEmpty();
        var later = new ArrayList<InitStep>();
        if (all || searchPathAnswers != null || forkTemplates) later.add(searchPathsStep());
        if (all || codeDirAnswers != null) later.add(codeDirectoriesStep());
        if (all) later.add(mcpStep());
        // Counted for the agent offered first; with none, the fewest a run can ask, marked "+" (#2528)
        var extra = (all ? 1 : 0) + later.size();
        var suggested = suggestedAgent();
        totalSteps = (suggested == null ? new Questions(false, false, needsGitHub) : questionsFor(suggested, needsGitHub)).count() + extra;
        totalKnown = !all && suggested != null;
        var noCredential = false;
        try {
            begin(agentStep());
            if (chosenAgent != null) startDownloadsAhead(chosenAgent);
            // Counted again for the agent chosen, before the next header shows it
            totalSteps = questionsFor(chosenAgent, needsGitHub).count() + extra;
            totalKnown = !all;
            var gaps = chosenAgent == null ? Map.<CredentialCheck.Missing, List<String>>of() : agentGaps();
            if (gaps.isEmpty()) {
                if (chosenAgent != null) BuildOutput.note(ExampleTemplate.title(templateAgent()) + "'s account is set up already.");
            } else {
                begin(agentCredentialStep(templateAgent()));
                noCredential = !agentGaps().isEmpty();
            }
            var noGitHub = false;
            var noIdentity = false;
            // Read again: the agent's own account may be GitHub's (Copilot), set up just now
            var gitHubNow = dev.incusspawn.config.AccountResolver.usableAccountNames(SpawnConfig.load(),
                    dev.incusspawn.tool.GhSetup.NAMESPACE).isEmpty();
            // Asked already when GitHub's is the account the agent spends (Copilot): never twice
            if (needsGitHub && gitHubNow) {
                if (!gitHubAsked) {
                    var saved = new AtomicBoolean();
                    begin(gitHubStep(saved));
                    noGitHub = !saved.get();
                } else {
                    noGitHub = true;
                }
                if (noGitHub) {
                    offerAgentGitIdentity();
                    noIdentity = SpawnConfig.load().gitIdentity() == null;
                }
            } else if (!all && !gitHubAsked) {
                // Not after the agent's own step, which asked for it and said it was saved
                BuildOutput.note("A GitHub account is set up already.");
            }
            if (all) {
                for (var step : credentialSteps()) {
                    totalSteps++;
                    begin(step);
                }
                totalKnown = true;
            }
            for (var step : later) begin(step);
            answers = new FirstRunAnswers(chosenAgent == null, noCredential, noGitHub, noIdentity);
        } finally {
            credentials.closeHttpClient();
        }
    }

    /** What {@link #probeAgents} found, read once a run: the intro and the agent question both ask. */
    private List<HostAgentProbe.HostAgent> probed;

    private List<HostAgentProbe.HostAgent> probed() {
        if (probed == null) probed = probeAgents();
        return probed;
    }

    /** The agent the question offers first: the one preferred in config.yaml, else one found signed in, else one found. */
    ToolSetup suggestedAgent() {
        var preferred = preferredAgentTool();
        if (preferred != null) return preferred;
        var agents = codingAgentTools();
        return probed().stream()
                .sorted(java.util.Comparator.comparing(a -> !a.signedIn()))
                .map(a -> agents.stream().filter(t -> t.name().equals(a.agent())).findFirst().orElse(null))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * Which questions the run asks for {@code agent} (null for none), after the agent's own:
     * its account, whether that account is GitHub's (Copilot), so the GitHub question is that
     * step, and the GitHub question apart from it.
     */
    record Questions(boolean account, boolean accountIsGitHub, boolean gitHub) {
        int count() {
            return 1 + (account ? 1 : 0) + (gitHub ? 1 : 0);
        }
    }

    Questions questionsFor(ToolSetup agent, boolean needsGitHub) {
        if (agent == null) return new Questions(false, false, needsGitHub);
        var chosen = chosenAgent;
        chosenAgent = agent;
        try {
            var gaps = agentGaps();
            var tools = trustedToolLoader().allToolSetups();
            // The account step asks the GitHub question itself when GitHub's is among the accounts it sets up
            var steps = gaps.keySet().stream().map(g -> CredentialCheck.stepTool(g, tools)).toList();
            var gitHubOnly = !steps.isEmpty() && steps.stream().allMatch("gh"::equals);
            return new Questions(!gaps.isEmpty(), gitHubOnly, needsGitHub && !steps.contains("gh"));
        } catch (RuntimeException e) {
            return new Questions(true, false, needsGitHub);
        } finally {
            chosenAgent = chosen;
        }
    }

    /** The first question: which agent isx sets this host up for. */
    InitStep agentStep() {
        return new InitStep("agent", "Your Coding Agent", new String[]{
                "Which coding agent do you prefer?",
                "You can change it later with 'isx agent'."}, this::askAgent, null);
    }

    /**
     * Asks for the agent. One found on this host is offered first and chosen by Enter; with none
     * found, the list is shown and Enter chooses nothing. "None for now" stores nothing.
     */
    void askAgent() {
        var prompts = prompts();
        var agents = codingAgentTools();
        var found = probed();
        var suggested = suggestedAgent();
        chosenAgent = suggested == null ? chooseAgent(prompts, agents) : confirmAgent(prompts, agents, suggested, found);
        if (chosenAgent == null) {
            BuildOutput.step("No agent for now.");
            BuildOutput.step("Your first isx machine is a plain Linux system.");
            return;
        }
        var config = firstRunConfig();
        config.setPreferredAgent(chosenAgent.name());
        config.save();
        BuildOutput.ok("Preferred agent: " + ExampleTemplate.title(chosenAgent));
    }

    private ToolSetup confirmAgent(Prompts prompts, List<ToolSetup> agents, ToolSetup suggested,
                                   List<HostAgentProbe.HostAgent> found) {
        var title = ExampleTemplate.title(suggested);
        var seen = found.stream().filter(a -> a.agent().equals(suggested.name())).findFirst();
        prompts.say(seen.isPresent() ? "Found " + title + " on " + dev.incusspawn.Platform.thisComputer() + "."
                : "Your preferred agent is " + title + ".");
        BuildOutput.gap();
        // Each answer says what it does, as the account step's do (#2528)
        prompts.say("Use " + title + "?");
        prompts.say("  Y  yes");
        prompts.say("  o  choose another agent");
        prompts.say("  n  no agent for now");
        while (true) {
            var answer = prompts.ask("Choice [Y]: ");
            if (answer == null) return null;
            switch (answer.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "", "y", "yes" -> {
                    return suggested;
                }
                case "n", "no" -> {
                    return null;
                }
                case "o", "other" -> {
                    return chooseAgent(prompts, agents);
                }
                default -> prompts.say("Please answer y, o or n.");
            }
        }
    }

    /** The list of agents and "None for now", until one is chosen: Enter chooses nothing, EOF none. */
    private ToolSetup chooseAgent(Prompts prompts, List<ToolSetup> agents) {
        var options = new ArrayList<>(agents.stream().map(ExampleTemplate::title).toList());
        options.add("None for now\nA plain isx machine, with no agent and no account.");
        while (true) {
            var answer = prompts.choose("isx can set up these agents.", options, null, "see the list again");
            if (answer == null) return null;
            try {
                var n = Integer.parseInt(answer.strip());
                if (n >= 1 && n <= agents.size()) return agents.get(n - 1);
                if (n == agents.size() + 1) return null;
            } catch (NumberFormatException e) {
                // Asked again
            }
        }
    }

    /**
     * The credentials the first template for {@link #chosenAgent} lacks, as a build of it would
     * find them: through the credentials its tools declare, so no agent's namespace is named here.
     */
    Map<CredentialCheck.Missing, List<String>> agentGaps() {
        try {
            var config = SpawnConfig.load();
            // Trusted only, as the first template is: a project's tpl-agent never asks for an account here
            var defs = new LinkedHashMap<>(trustedTemplates());
            defs.putIfAbsent(ExampleTemplate.NAME, firstTemplate(defs, chosenAgent, config));
            return CredentialCheck.missingByCredential(config, List.of(ExampleTemplate.NAME), defs,
                    ToolDefLoader.withoutProjectTools(config.getSearchPaths()));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The step that sets up the account {@code agent} spends, and verifies it; the person may skip it. */
    InitStep agentCredentialStep(ToolSetup agent) {
        var title = ExampleTemplate.title(agent);
        return new InitStep("agent-credential", title + " Account", accountHint(title), this::setUpAgentCredential, null);
    }

    /** The agent account step's hint: whose account it is, and where its credential stays (#2528). */
    static String[] accountHint(String title) {
        var stays = "Its credential stays on " + dev.incusspawn.Platform.thisComputer() + ".";
        var never = "isx machines never get it.";
        var first = "The account " + title + " uses inside isx machines.";
        // One line when it fits the frame under the hint's bar: on neither platform with today's words
        return (stays + " " + never).length() <= BOX_WIDTH - 4 ? new String[]{first, stays + " " + never}
                : new String[]{first, stays, never};
    }

    /**
     * Runs the credential step of each account the first template lacks, as {@code isx account
     * add} runs it. A subscription login of Claude Code found on this host answers which kind of
     * account to set up, and is re-issued with {@code claude setup-token}, never copied.
     */
    void setUpAgentCredential() throws Exception {
        var tools = trustedToolLoader().allToolSetups();
        var agent = templateAgent();
        // An account kind only another agent can use is not offered (#2528); only in this step,
        // so a later step of --all decides it for its own tool
        credentials.agent = agent.name();
        try {
            setUpAgentCredential(agent, tools);
        } finally {
            credentials.agent = null;
        }
    }

    private void setUpAgentCredential(ToolSetup agent, Map<String, ToolSetup> tools) throws Exception {
        var ran = new java.util.HashSet<String>();
        for (var gap : agentGaps().keySet()) {
            var step = CredentialCheck.stepTool(gap, tools);
            if (step != null && !ran.add(step)) continue;
            if (step != null && !step.equals(agent.name())) sayWhoseAccount(agent, tools.get(step), step);
            if (step == null) {
                BuildOutput.note(OutputFormat.oneLine(gap.credential()) + ": " + CredentialCheck.remedy(List.of(gap)));
            } else if (step.equals("gh")) {
                // The first run's GitHub question, which then is not asked a second time
                gitHubAsked = true;
                offerGitHub();
            } else if (step.equals("claude") && claudeSubscriptionFound()) {
                var prompts = consoleOrExplain();
                if (prompts != null) credentials.setupClaudeSubscription(firstRunConfig(), prompts);
            } else {
                // The full step reads config.yaml leniently before it saves: check it strictly first
                firstRunConfig();
                credentialStep(step, tools.get(step)).run().run();
            }
        }
    }

    /** Whether the GitHub question was asked as the agent's own account, so it is not asked again. */
    private boolean gitHubAsked;

    /**
     * Before the step of an account that is not the agent's own: whose it is, since the questions
     * that follow name another product (Pi is asked about Claude), and for an agent with a
     * {@code provider}, how to spend another provider's account instead.
     */
    private static void sayWhoseAccount(ToolSetup agent, ToolSetup owner, String step) {
        var title = ExampleTemplate.title(agent);
        var proxy = owner == null ? null : owner.proxy();
        var namespace = proxy == null || proxy.getConfigNamespace().isBlank() ? step : proxy.getConfigNamespace();
        // Another tool of a kept template (gh beside Claude Code) lacks its own account, not the agent's
        if (!agent.credentialNamespaces().contains(namespace)) return;
        // Only the account the agent spends with no provider set is its default: a kept template may name another
        var byDefault = agent.parameters().containsKey("provider") && agent.credentialNamespaces(Map.of()).contains(namespace);
        var shown = CredentialSetup.accountName(namespace);
        // "an OpenAI account", "a Claude account"
        var article = "AEIOUaeiou".indexOf(shown.charAt(0)) >= 0 ? "an " : "a ";
        if (byDefault) {
            BuildOutput.step(title + " works with several providers, and isx sets it up");
            BuildOutput.step("with " + article + shown + " account first. To use another provider,");
            BuildOutput.step("choose None for now here, then see:");
            var anchor = agent.name().equals("pi") ? "pi-coding-agent" : OutputFormat.oneLine(agent.name());
            BuildOutput.step(TerminalLink.link("https://isx.run/docs/agents#" + anchor, "https://isx.run/docs/agents#" + anchor));
        } else {
            Lines.step(shown.equals("GitHub")
                    ? title + " signs in with a GitHub account, and the agent acts on GitHub as that same account."
                    : title + " uses " + article + shown + " account.");
        }
        // The question that follows opens with a gap of its own
    }


    /**
     * config.yaml for a first-run step that saves it, read strictly: {@code load()} reads a broken
     * file as empty, and saving that would drop every account in it. The run reads it before its
     * first question, and each step that saves reads it again itself, since the file can change
     * while a question waits on the person.
     */
    static SpawnConfig firstRunConfig() {
        return SpawnConfig.loadToChange("run 'isx init' again");
    }

    /** Whether Claude Code on this host is signed in with a subscription. */
    boolean claudeSubscriptionFound() {
        return probeAgents().stream().anyMatch(a -> a.agent().equals("claude") && a.signedIn()
                && a.kind() == HostAgentProbe.Kind.SUBSCRIPTION);
    }

    /** The GitHub question: an account for the agent, which the person may skip. */
    boolean offerGitHub() {
        var prompts = consoleOrExplain();
        credentials.noAgent = firstRun && chosenAgent == null;
        return prompts != null && credentials.offerGitHubForAgent(firstRunConfig(), prompts, ghLogin());
    }

    /**
     * The host's plumbing in a first run, in order, the services last: what today's walk runs
     * that asks nothing. DNS is among them, since no step before it adds a search path.
     */
    List<InitStep> firstRunPlumbing(boolean macOs, AtomicBoolean installed) {
        var steps = new ArrayList<>(macOs ? macOsSetup() : linuxSetup());
        steps.addAll(List.of(gitRemoteHelper(), dnsStep(), macOs ? macOsServicesStep(installed) : proxyServiceStep(installed)));
        return steps;
    }

    /**
     * Every step a first run runs, for the test that holds none is dropped: the questions, the
     * dependencies on Linux or the VM on macOS, then the plumbing. The agent's account is listed
     * for the first coding agent.
     */
    List<InitStep> firstRunSteps(boolean macOs) {
        var steps = new ArrayList<>(List.of(agentStep(), agentCredentialStep(codingAgentTools().getFirst()),
                gitHubStep(new AtomicBoolean())));
        steps.add(macOs ? incusVm() : dependencies());
        steps.addAll(firstRunPlumbing(macOs, new AtomicBoolean()));
        return steps;
    }

    /** The GitHub question, which records in {@code saved} whether an account was saved. */
    InitStep gitHubStep(AtomicBoolean saved) {
        return new InitStep("github", "GitHub Account", chosenAgent == null ? new String[]{
                "Git and gh in isx machines act on GitHub as this account.",
                "What they commit, push or open there carries its name."} : new String[]{
                "The agent acts on GitHub as this account. Its commits,",
                "pull requests, issues and comments carry its name."},
                () -> saved.set(offerGitHub()), null);
    }

    /** Runs {@code steps} from the {@code done}th in the one block of plumbing, since none asks. */
    void runPlumbing(List<InitStep> steps, int done) throws Exception {
        allPlumbing = true;
        try {
            for (var step : steps.subList(done, steps.size())) begin(step);
        } finally {
            closePlumbingBlock();
            allPlumbing = false;
        }
    }

    /** Whether {@link #begin} shows every step in the block of plumbing, as a first run's do. */
    private boolean allPlumbing;

    /**
     * After the plumbing: the first template written and built, the completion message, then the
     * offer to open the agent. Another command's init ({@link #requireInit}) writes the template
     * and builds nothing, and nothing is built while the proxy is not running.
     */
    CommandResult finishFirstRun(boolean macOs, boolean servicesInstalled, boolean continued) throws Exception {
        var template = prepareFirstTemplate();
        var agent = templateAgent();
        var project = projectLayer(template);
        if (chosenAgent != null && answers.noCredential()) {
            var gaps = agentGaps().keySet();
            agentAccountCommand = gaps.isEmpty() ? null : gaps.iterator().next().command();
        }
        var built = false;
        // Whether the box says it is built: so when init holds back one built before, which it does not open here
        var shownBuilt = false;
        var buildFailed = false;
        if (automatic) {
            // The command that started init carries on with its own work
        } else if (project != null) {
            // A build here would read the cloned repository's definition in place of the person's
            var builtAlready = templateBuilt(template);
            // The details at the width of the frame, under the warning's indent (#2528)
            var details = new ArrayList<>(Lines.wrap("The project in " + OutputFormat.oneLine(project.toString())
                    + " defines a template or tool that " + template + " uses, and a build in this directory would use"
                    + " the project's in place of yours.", Lines.WIDTH - 2));
            details.addAll(Lines.wrap((builtAlready ? "Open it with 'isx branch <name> --from " : "Run 'isx build ")
                    + template + "' from a directory with no .incus-spawn/ of its own.", Lines.WIDTH - 2));
            BuildOutput.warn(builtAlready ? template + " is built, and isx does not open it here"
                    : "isx did not build " + template + " here", details.toArray(String[]::new));
            built = false;
            shownBuilt = builtAlready;
        } else if (!servicesInstalled) {
            // One built before is still said to be built, though nothing opens it without the proxy
            shownBuilt = templateBuilt(template);
            BuildOutput.note(shownBuilt ? template + " is built. isx does not open it: the proxy is not running."
                    : "isx did not build " + template + ": the proxy is not running.");
        } else if (templateBuilt(template)) {
            built = true;
        } else {
            buildFailed = buildTemplate(template) != 0;
            built = !buildFailed;
        }
        var services = macOs ? (servicesInstalled ? Services.INSTALLED : Services.START_VM_AND_PROXY)
                : linuxServices(servicesInstalled);
        // The name the offer branches, so the command printed is the one that works
        var instance = firstInstanceName();
        var message = firstRunNextSteps(new Closing(services, template, built || shownBuilt, buildFailed,
                agent == null ? null : ExampleTemplate.title(agent), agentAccountCommand,
                agent == null ? List.of() : agent.firstStartNote(SpawnConfig.load()),
                answers.noIdentity(), instance, !all));
        var lines = new ArrayList<>(message.box());
        if (project != null) {
            // In this directory the commands would read the project's definitions, as the warning says
            // Under each command a build here would read the project for: the build and the branch
            for (var command : List.of("isx build " + template, "isx branch " + instance)) {
                var at = lines.indexOf(lines.stream().filter(l -> l.contains(command)).findFirst().orElse(null));
                if (at < 0) continue;
                // At the command's indent, which is deeper when the steps are numbered
                var line = lines.get(at);
                lines.add(at + 1, line.substring(0, line.indexOf(command)) + "(from a directory with no .incus-spawn/ of its own)");
            }
        }
        if (continued) lines.addAll(GROUP_PENDING);
        printClosing(message.title(), lines, message.later());
        var prompts = prompts();
        if (built && chosenAgent != null && agentAccountCommand == null && !automatic && prompts != null
                && askYesNo(prompts, "Run 'isx branch " + instance + " --from " + template + "' now?", true)) {
            return CommandResult.valueOf(openAgent(template, instance));
        }
        return CommandResult.valueOf(buildFailed ? 1 : 0);
    }

    /**
     * The project directory whose {@code .incus-spawn/} defines a layer of {@code template}'s chain
     * as a build in the working directory reads it, or null when none does. init then builds and
     * opens nothing: that layer is a cloned repository's, not the person's.
     */
    java.nio.file.Path projectLayer(String template) {
        var here = templatesHere();
        var def = here.get(template);
        if (def == null) return null;
        var chain = ImageDef.chain(def, here);
        var layer = chain.stream().map(ImageDef::getProjectRoot)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (layer != null) return layer;
        // A project's .incus-spawn/tools/ overrides a tool of the chain for a build here as well,
        // one a listed tool requires included (Antigravity CLI's agent-cli-tools)
        var projectTools = projectToolsHere();
        for (var tool : chainTools(chain, toolsHere())) {
            if (projectTools.contains(tool)) return Path.of("").toAbsolutePath();
        }
        return null;
    }

    /** Every tool a build of {@code chain} installs: each layer's own, and what they require, resolved through {@code tools}. */
    static Set<String> chainTools(List<ImageDef> chain, Map<String, ToolSetup> tools) {
        var names = new LinkedHashSet<String>();
        for (var layer : chain) {
            for (var tool : layer.getTools()) ToolSetup.addWithRequires(tool.getName(), tools, names);
        }
        return names;
    }

    /** The tools a build in this directory reads, a project's own included; tests answer them instead. */
    Map<String, ToolSetup> toolsHere() {
        return new ToolDefLoader(SpawnConfig.load().getSearchPaths()).allToolSetups();
    }

    /** The tools a build in this directory reads from a project's {@code .incus-spawn/tools/}; tests answer them instead. */
    Set<String> projectToolsHere() {
        return new ToolDefLoader(SpawnConfig.load().getSearchPaths()).projectLocalToolNames();
    }

    /**
     * Writes {@code tpl-agent.yaml} for the chosen agent, unless a trusted definition of that
     * name exists, which is then the one built: a second would make the build refuse both.
     *
     * @return the template to build: {@code tpl-agent}, or {@code tpl-minimal} with no agent
     */
    String prepareFirstTemplate() throws IOException {
        if (chosenAgent == null) return "tpl-minimal";
        if (trustedTemplates().containsKey(ExampleTemplate.NAME)) {
            var agent = templateAgent();
            BuildOutput.note("Kept your own " + ExampleTemplate.NAME + " as it is" + (agent == chosenAgent ? "."
                    : "; it installs " + ExampleTemplate.title(agent) + ", not " + ExampleTemplate.title(chosenAgent) + "."));
        } else {
            // The closing box says what it is and how to change it (#2528); it lists gh with a GitHub account (#2535)
            ExampleTemplate.write(ExampleTemplate.file(), chosenAgent, ExampleTemplate.hasGitHubAccount(SpawnConfig.load()));
        }
        return ExampleTemplate.NAME;
    }

    /** Builds {@code template} as {@code isx build} does, asking for no account: the run just asked. Tests record it instead. */
    int buildTemplate(String template) throws Exception {
        var build = new BuildCommand();
        build.names = List.of(template);
        build.askForAccounts = false;
        return build.doExecute().getResultValue();
    }

    /** Branches the first instance of {@code template}, which opens its agent; tests record it instead. */
    int openAgent(String template, String instance) throws Exception {
        var branch = new BranchCommand();
        branch.name = instance;
        branch.source = template;
        return branch.doExecute().getResultValue();
    }

    /** {@code my-first}, or the first {@code my-first-<n>} no instance has. */
    String firstInstanceName() {
        for (int n = 1; ; n++) {
            var name = n == 1 ? "my-first" : "my-first-" + n;
            try {
                if (!incus.exists(name)) return name;
            } catch (RuntimeException e) {
                // Incus does not answer: the branch says so itself
                return name;
            }
        }
    }

    /** What a first run's closing message is told of the run. */
    record Closing(Services services, String template, boolean built, boolean buildFailed, String agent,
                   String accountCommand, List<String> firstStart, boolean noIdentity, String instance, boolean listLater) {}

    /** The closing message: the box's title and lines, then the lines under it, for later. */
    record ClosingMessage(String title, List<String> box, List<String> later) {}

    /**
     * The first run's closing message (#2528). The box holds the result and the one next
     * command; when more is left, the steps in the order they have to be done, one command to
     * a line: start the services, build the template, add the agent's account, open it.
     * Under the box, what the person may want later, and the one line that bridges "isx
     * machine" to the "instance" of isx's commands and docs.
     */
    static ClosingMessage firstRunNextSteps(Closing c) {
        var plain = c.agent() == null;
        var steps = new ArrayList<List<String>>();
        if (c.services().label != null) {
            // The first run's own words for what the short label of the other boxes says
            var label = c.services() == Services.START_VM_AND_PROXY ? "Start the VM and the proxy" : c.services().label;
            var start = new ArrayList<>(List.of(label + ":", c.services().command));
            if (c.services().holdsTerminal()) start.add("(it keeps its terminal: use another for the rest)");
            steps.add(start);
        }
        if (!c.built()) {
            steps.add(List.of(c.buildFailed() ? "Fix the error above, then build " + c.template() + ":" : "Build " + c.template() + ":",
                    "isx build " + c.template()));
        }
        if (c.accountCommand() != null) steps.add(List.of("Add " + c.agent() + "'s account:", c.accountCommand()));
        var open = new ArrayList<>(List.of(plain ? "Open a new isx machine:" : "Open " + c.agent() + " in a new isx machine:",
                "isx branch " + c.instance() + " --from " + c.template()));
        open.addAll(c.firstStart());
        steps.add(open);

        var ready = steps.size() == 1;
        var box = new ArrayList<String>();
        if (c.buildFailed()) box.add("   " + c.template() + " did not build.");
        if (c.built()) {
            // Where "template" is explained (#2528)
            box.add("   " + c.template() + " is built: a template that makes "
                    + (plain ? "plain" : "isx machines"));
            box.add("   " + (plain ? "isx machines." : "with " + c.agent() + "."));
        }
        // The title counts what is left; the steps follow it
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            if (i > 0 || !box.isEmpty()) box.add("");
            // Numbered only when there is an order to keep
            var number = ready ? "" : (i + 1) + ". ";
            box.add("   " + number + step.getFirst());
            for (var more : step.subList(1, step.size())) box.add("   " + " ".repeat(number.length()) + "  " + more);
        }
        if (c.noIdentity()) {
            box.add("");
            box.add("   Git in isx machines refuses to commit until it has a");
            box.add("   name: 'isx account add github' gives it one.");
        }

        var later = new ArrayList<String>();
        later.add("Later:");
        later.add(String.format("  %-26s%s", "Return to it", "isx shell " + c.instance()));
        later.add(String.format("  %-26s%s", "See your isx machines", "isx"));
        if (!plain) later.add(String.format("  %-26s%s", "Change " + c.template(), "isx templates edit " + c.template()));
        later.add(String.format("  %-26s%s", "Add your own templates",
                TerminalLink.link("https://isx.run/docs/templates")));
        if (c.listLater()) later.add(String.format("  %-26s%s", "Set up more", "isx init"));
        later.add("isx commands and docs call an isx machine an \"instance\".");
        // Titled by what is left, never "set up" above a list of what is not (#2528)
        var title = ready ? "Setup complete" : steps.size() + " steps left";
        return new ClosingMessage(title, box, later);
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
            case "codex" -> new InitStep(toolName, "OpenAI Authentication", new String[]{
                    "Configures how containers authenticate with OpenAI: a",
                    "ChatGPT session, Enterprise plans included, that stays on",
                    "your host — isx signs it in through your browser and the",
                    "proxy serves Codex from it — or an OpenAI API key.",
                    "Containers never see the real credential."}, this::setupCodexAuth, null);
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
        return InitPlan.Assessment.of(changes).asking().noting(HostAgentProbe.note(HostAgentProbe.probe()));
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
     * Asks, one after another, for each account in {@code missing} that a step configures, and
     * runs that step as {@code isx account add} runs it when the person says yes (#2405). Without
     * a terminal nothing is asked. Each step is asked for once, naming every template that needs
     * it, and a credential only {@code isx init} configures is left to the caller's warning.
     *
     * <p>The steps come only from tools a project cannot define, so a cloned repository's tool
     * never turns into a question for a secret: a gap only such a tool closes is not asked for.
     *
     * @param missing each missing credential, mapped to the templates that need it
     * @return whether any step ran, so the caller's check is worth making again
     */
    boolean askForMissing(Map<CredentialCheck.Missing, List<String>> missing) throws Exception {
        if (missing.isEmpty()) return false;
        var prompts = prompts();
        if (prompts == null) return false;
        var tools = trustedToolLoader().allToolSetups();
        var steps = new LinkedHashMap<String, LinkedHashSet<String>>();
        missing.forEach((credential, templates) -> {
            var step = CredentialCheck.stepTool(credential, tools);
            if (step != null) steps.computeIfAbsent(step, s -> new LinkedHashSet<>()).addAll(templates);
        });
        if (steps.isEmpty()) return false;
        currentStep = 0;
        totalSteps = steps.size();
        totalKnown = true;
        var ran = false;
        try {
            for (var entry : steps.entrySet()) {
                var tool = tools.get(entry.getKey());
                var needing = entry.getValue().stream().map(t -> "'" + OutputFormat.oneLine(t) + "'").toList();
                var question = (needing.size() == 1 ? "Template " : "Templates ") + OutputFormat.andList(needing)
                        + (needing.size() == 1 ? " needs" : " need") + " a credential that is not configured: "
                        + CredentialSetup.describeTool(entry.getKey(), tool) + ". Set it up now?";
                if (!askYesNo(prompts, question, true)) {
                    // Counted all the same, so each step's header says where it is among those asked for
                    currentStep++;
                    continue;
                }
                begin(credentialStep(entry.getKey(), tool));
                ran = true;
            }
        } finally {
            credentials.closeHttpClient();
        }
        return ran;
    }

    /**
     * Where {@link #askForMissing} takes its steps from: every tool but a project's own
     * ({@code .incus-spawn/tools/}), which a cloned repository defines.
     */
    ToolDefLoader trustedToolLoader() {
        return ToolDefLoader.withoutProjectTools(SpawnConfig.load().getSearchPaths());
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
                searchPathsStep(),
                codeDirectoriesStep(),
                mcpStep(),
                dnsStep(),
                service);
    }

    InitStep dnsStep() {
        return new InitStep("dns", "DNS Configuration", DNS_HINT, this::setupBridgeDns, this::planBridgeDns);
    }

    InitStep searchPathsStep() {
        return new InitStep("search-paths", "Template Search Paths", new String[]{
                "Local directories where isx looks for custom image and",
                "tool definitions. Definitions found here can extend or",
                "override the built-in templates. Each directory should",
                "contain images/ and/or tools/ subdirectories with YAML",
                "files."}, this::setupSearchPaths, this::planSearchPaths);
    }

    InitStep codeDirectoriesStep() {
        return new InitStep("code-directories", "Code Directories", new String[]{
                "Directories on your host containing git repositories (e.g.",
                "~/code). Enables fast reference clones inside containers",
                "and automatic git remote management — repos cloned from",
                "these paths get a remote named after the instance, to push",
                "and pull directly between container and host without",
                "round-tripping through GitHub. Your host files are never",
                "directly exposed to containers."}, this::setupHostPaths, this::planHostPaths);
    }

    /** Agent Access over MCP, which {@code isx mcp setup} also runs alone (#2077). */
    InitStep mcpStep() {
        return new InitStep("mcp", "Agent Access over MCP", new String[]{
                "Lets an agent on this machine (Claude Code or another MCP",
                "client) use isx itself: create instances from templates",
                "you approve here, run commands in them, and hand tasks",
                "to the Claude Code, Codex, Pi, opencode or Antigravity CLI inside them.",
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
                // For the log the first run names, not for the screen (#2528)
                dev.incusspawn.ClientLog.debug("DNS overrides: " + domains.size() + " domains -> " + gatewayIp + " (via bridge dnsmasq)");
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
        // Checked before the bridge is made, so a config.yaml that does not parse stops the step before it changes anything
        var config = SpawnConfig.loadToChange("run 'isx init' again");
        incus.createBridgeIfMissing("incusbr0", VmManager.gatewayIp());
        var gatewayIp = ProxyConfig.resolveGatewayIp(incus);
        config.setIncusBridgeGateway(gatewayIp);
        config.save();
        if (CertificateAuthority.exists()) {
            BuildOutput.step("The isx proxy's certificate is ready.");
        } else {
            CertificateAuthority.loadOrCreate();
            // What it is for, once, where it is made (#2528)
            for (var line : Lines.wrap("The proxy uses it to open the HTTPS requests that " + machines()
                    + " send to your accounts' services, and adds your credentials."
                    + " This Mac's own traffic does not go through the proxy.", Lines.WIDTH - 2)) {
                BuildOutput.step(styled(DIM, line));
            }
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
        return announcing(Prompts.terminal());
    }

    /**
     * {@code prompts}, which first shows the header and hint of the plumbing step running, if any,
     * when that step asks something: a step that asks keeps its header and hint (#2406).
     * Null stays null.
     */
    Prompts announcing(Prompts prompts) {
        if (prompts == null) return null;
        return new Prompts() {
            @Override
            public String readLine() {
                return prompts.readLine();
            }

            @Override
            public char[] readPassword() {
                return prompts.readPassword();
            }

            @Override
            public void show(String question) {
                announce();
                prompts.show(question);
            }

            @Override
            public void say(String line) {
                announce();
                prompts.say(line);
            }
        };
    }

    /** The plumbing step running whose header is not shown yet; null outside one. */
    private InitStep unannounced;

    /** Shows {@link #unannounced}'s header and hint, unnumbered, outside the block, once. */
    private void announce() {
        var step = unannounced;
        if (step == null) return;
        unannounced = null;
        closePlumbingBlock();
        var out = new ArrayList<String>();
        out.add("");
        out.add(styled(BOLD, "  " + step.title()));
        for (var line : step.hint()) out.add(styled(CYAN, "  ┃ ") + styled(DIM, line));
        out.add("");
        // A frame of init's own: laid out here, drawn as given
        BuildOutput.frame(out.toArray(String[]::new));
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
        // The move ends by saving the new gateway: a config.yaml that does not parse stops it before
        // anything moves, and the one read here is the one saved, so a later edit cannot strand the redirect
        var config = SpawnConfig.loadToChange("run 'isx doctor' again");
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
        if (!followBridgeMove(subnet.substring(0, subnet.indexOf('/')), firewall, config)) {
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

    boolean followBridgeMove(String gatewayIp, DetectionResult firewall, SpawnConfig config) {
        return host.followBridgeMove(gatewayIp, firewall, config);
    }

    void setupSshKeyPair() {
        try {
            if (!SshKeyManager.exists()) SshKeyManager.ensureKeyPairExists();
            if (SshKeyManager.ensureSshConfigInclude()) {
                BuildOutput.ok("SSH access to " + machines() + " ready");
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
        return credentials.selectCredentials(allTools, credentialConfig(), prompts);
    }

    /**
     * config.yaml for a credential step, which saves it: a file that does not parse stops the step
     * before it asks for a secret, rather than losing every account to the save (#2523).
     */
    private static SpawnConfig credentialConfig() {
        // isx init, isx account add and a build's question all run it, so no one command is named
        return SpawnConfig.loadToChange("add the account again");
    }

    private void setupClaudeAuth() {
        var prompts = consoleOrExplain();
        if (prompts != null) credentials.setupClaudeAuth(credentialConfig(), prompts);
    }

    private void setupGitHubAuth() {
        var prompts = consoleOrExplain();
        if (prompts != null) credentials.setupGitHubAuth(credentialConfig(), prompts);
    }

    private void setupCodexAuth() {
        var prompts = consoleOrExplain();
        if (prompts != null) credentials.setupCodexAuth(credentialConfig(), prompts);
    }

    private void setupGenericToolCredentials(String toolName, ToolSetup tool) {
        var prompts = consoleOrExplain();
        if (prompts != null) credentials.setupGenericToolCredentials(toolName, tool, credentialConfig(), prompts);
    }

    private void setupPathList(
            java.util.function.Function<SpawnConfig, java.util.List<String>> getter,
            java.util.function.BiConsumer<SpawnConfig, java.util.List<String>> setter,
            String skipMessage, String step, List<String> answers, String flag) {
        var config = SpawnConfig.loadToChange("run 'isx init " + step + "' again");
        if (answers != null) {
            addPaths(getter, setter, config, answers);
            return;
        }
        var prompts = prompts();
        if (prompts != null) {
            credentials.setupPathList(getter, setter, skipMessage, config, prompts);
        } else {
            BuildOutput.note("Skipped: no terminal. Add directories with " + flag + ", or run 'isx init " + step + "' at a terminal.");
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
     * installs a coding agent whether agents may use it; one with an agent {@code delegate} cannot
     * run (Gemini CLI, Bob Shell, Copilot CLI) is marked create and exec only (#2178). Templates
     * already approved by hand that install none of them are left as they are.
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
        // Never 'claude mcp get', which starts the server it names: isx mcp would then sweep orphans (#2099)
        boolean registered;
        try {
            registered = claude && claudeMcpRegisteredInConfig();
        } catch (IOException | IllegalArgumentException e) {
            BuildOutput.warn("Could not read Claude Code's configuration: " + failure(e),
                    "Fix it, then run '" + MCP_SETUP_COMMAND + "'.");
            return;
        }
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
                BuildOutput.stepNote(McpLaunch.display(claudeMcpAddCommand(mcpLaunch(McpLaunch.PLACEHOLDER))));
            } else if (registerClaudeMcp(isx)) {
                BuildOutput.ok("Registered with Claude Code as '" + McpClientRegistration.SERVER_NAME + "' (all projects)");
            } else {
                BuildOutput.step("Registering failed; register it yourself:");
                BuildOutput.stepNote(McpLaunch.display(claudeMcpAddCommand(mcpLaunch(isx))));
            }
        }
        BuildOutput.stepNote("For another MCP client, add the entry '" + MCP_CONFIG_COMMAND + "' prints to its settings.");

        if (candidates.isEmpty()) {
            BuildOutput.step("No template installs a coding agent yet, so there is none to approve."
                    + " Add one, then run '" + MCP_SETUP_COMMAND + "' or edit " + McpConfig.file() + ".");
            return;
        }
        var chosen = new java.util.ArrayList<String>();
        try (var group = BuildOutput.group("Approve the templates agents may create instances from", null)) {
            for (var name : candidates) {
                var note = templateBuilt(name) ? "" : styled(DIM, " (not built yet: isx build " + name + ")");
                if (!installsAny(defs.get(name), defs, DELEGATE_AGENTS)) {
                    note += styled(DIM, " (create and exec only: delegate runs Claude Code, Codex, Pi, opencode or Antigravity CLI)");
                }
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
     * is not read: the run's own registration is the user scope. Unlike {@code claude mcp get}, which
     * starts the server it names, it starts nothing. A missing file registers nothing.
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

    /** Register {@code isx mcp} with Claude Code for every project; true on success. */
    boolean registerClaudeMcp(String isxPath) {
        return runSilently(claudeMcpAddCommand(mcpLaunch(isxPath)).toArray(String[]::new)) == 0;
    }

    /** The command a client runs to start {@code isx mcp} on this host, {@code sg} included where it helps (#2189). */
    List<String> mcpLaunch(String isxPath) {
        return new McpLaunch().command(isxPath);
    }

    /**
     * The command that registers {@code launch} with Claude Code for every project, which the run,
     * its advice, the plan and {@code isx mcp register} all name.
     */
    static List<String> claudeMcpAddCommand(List<String> launch) {
        return McpCommand.Client.CLAUDE.addCommand(launch);
    }

    /** Run a host command for its exit code alone, showing nothing and reading no input. */
    int runSilently(String... command) {
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
        // Every branch ends by saving config.yaml: one that does not parse stops the step before it asks, forks or clones
        var again = "run 'isx init search-paths' again";
        var paths = SpawnConfig.loadToChange(again).getSearchPaths();
        if (forkTemplates) {
            forkAndCloneTemplates(paths);
        } else if (searchPathAnswers == null && !hasExistingTemplatesSearchPath(paths)) {
            offerTemplatesRepo();
        }

        // --fork-templates answered the step once the templates it cloned are among the paths
        var answers = searchPathAnswers == null && forkTemplates
                && hasExistingTemplatesSearchPath(SpawnConfig.loadToChange(again).getSearchPaths()) ? List.<String>of() : searchPathAnswers;
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
    private void forkAndCloneTemplates(List<String> searchPaths) {
        if (hasExistingTemplatesSearchPath(searchPaths)) {
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
        var config = SpawnConfig.loadToChange("run 'isx init search-paths' again");
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
                "No host paths configured. Add them later with 'isx init code-directories'"
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
        if (present) BuildOutput.step("isx services are installed already.");
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
        // A first run installs them without asking: nearly everyone wants them (#2412)
        if (firstRun && prompts != null) return true;
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
        // A first run installs them without asking: nearly everyone wants them (#2412)
        if (firstRun && prompts != null) return true;
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

    /**
     * The built-in tools that are coding agents, by name: Claude Code, Codex, Pi, opencode, Gemini CLI, Bob Shell, Copilot CLI.
     * Read per call, never held in a static field: that would initialize the run-time-initialized
     * {@code RuntimeConstants} while the native image is built and bake its host paths in.
     */
    private static java.util.Set<String> codingAgents() {
        return RuntimeServices.toolSetups().stream()
                .filter(ToolSetup::codingAgent).map(ToolSetup::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** The tools {@code delegate} can run in an instance ({@code mcp.DelegateAgent}); a template with another agent gets create and exec only. */
    private static final List<String> DELEGATE_AGENTS = List.of("claude", "codex", "pi", "opencode", "antigravity");

    /**
     * Templates that install a coding agent, whichever it is (#2178): an agent on the host uses
     * such a template however it runs, and {@code delegate} hands a task to the ones it can run.
     */
    private static List<String> mcpCandidates(Map<String, ImageDef> defs) {
        var agents = codingAgents();
        return defs.values().stream()
                .filter(def -> installsAny(def, defs, agents))
                .map(ImageDef::getName)
                .sorted()
                .toList();
    }

    private static boolean installsAny(ImageDef def, Map<String, ImageDef> defs, java.util.Collection<String> tools) {
        return ImageDef.chain(def, defs).stream().anyMatch(d -> d.getTools().stream().anyMatch(t -> tools.contains(t.getName())));
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
                    McpLaunch.display(claudeMcpAddCommand(mcpLaunch(isx == null ? McpLaunch.PLACEHOLDER : isx)))));
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
