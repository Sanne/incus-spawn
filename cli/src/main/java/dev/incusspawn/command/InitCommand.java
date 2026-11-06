package dev.incusspawn.command;

import dev.incusspawn.ClientLog;
import dev.incusspawn.Environment;
import dev.incusspawn.config.CredentialCheck;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.McpConfig;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @Override
    protected CommandResult doExecute() throws Exception {
        if (!requireIncusHost()) return CommandResult.valueOf(1);
        this.incus = RuntimeServices.incus();
        if (Platform.isMacOS()) {
            return doMacOsInit();
        }
        if (!requireLinux()) {
            return CommandResult.valueOf(1);
        }
        var continued = continuedInGroup();
        if (!continued) {
            printSetupBanner("", "~3 minutes · some steps require sudo");

            BuildOutput.step("Several steps need " + styled(BOLD, "sudo") + " to install packages, configure"
                    + " the firewall, and set up user namespace mappings.");
            if (runHost("sudo", "-v") != 0) {
                BuildOutput.error("sudo authentication failed.", "Please ensure you have sudo access and try again.");
                return CommandResult.valueOf(1);
            }

            host().installDependencies();
        }
        var proxyServiceInstalled = new AtomicBoolean();
        runLinuxSteps(finalSteps(() -> {
                    startStep("Proxy Service",
                            "The MITM proxy intercepts HTTPS traffic from containers",
                            "and injects real credentials (API keys, tokens) so that",
                            "containers only ever hold placeholder values. Installing",
                            "it as a systemd service means it starts automatically on",
                            "boot — otherwise you'll need to run 'isx proxy start'",
                            "before launching containers.");
                    proxyServiceInstalled.set(completeWithProxyService(Prompts.console()));
                }));

        var next = new ArrayList<>(List.of(nextSteps(proxyServiceInstalled.get() ? Services.INSTALLED : Services.START_PROXY)));
        if (continued) next.addAll(GROUP_PENDING);
        printCompletionBox(next.toArray(String[]::new));
        return CommandResult.SUCCESS;
    }

    /**
     * The Linux steps, then {@code finish}. A continuation under {@code sg} starts after Incus
     * Installation, which the process that started it ran.
     */
    void runLinuxSteps(List<Step> finish) throws Exception {
        runSteps(List.of(
                        this::installIncus,
                        host()::configureSubuidSubgid,
                        host()::initializeIncus,
                        host()::configureFirewall,
                        host()::configureMitmProxy,
                        this::setupSshKeyPair),
                finish, continuedInGroup() ? 1 : 0);
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
        host().checkIncusInstalled();
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

        runSteps(List.of(this::setupMacOsCa, this::setupSshKeyPair),
                finalSteps(() -> {
                    startStep("macOS Services",
                            "Installs the Incus VM and MITM proxy as macOS launch",
                            "agents so they start automatically on login and survive",
                            "reboots. Without this you'll need to manually run",
                            "'isx vm start' and 'isx proxy start' before launching",
                            "containers.");
                    servicesInstalled.set(completeWithMacOsServices(Prompts.console()));
                }));

        printCompletionBox(nextSteps(servicesInstalled.get() ? Services.INSTALLED : Services.START_VM_AND_PROXY));
        return CommandResult.SUCCESS;
    }

    /**
     * What the closing box says about the services a branch needs: that they are installed, or
     * the step that starts them.
     */
    enum Services {
        INSTALLED(null),
        START_PROXY("Start the proxy:   isx proxy start"),
        START_VM_AND_PROXY("Start VM, proxy:   isx vm start && isx proxy start");

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

    /** One numbered step of init: it shows exactly one header, through {@link #startStep}. */
    @FunctionalInterface
    interface Step {
        void run() throws Exception;
    }

    /**
     * Runs init's numbered steps: {@code setup}, Credential Setup, a step for each credential
     * chosen there, then {@code finish}. The total is counted from these lists rather than written
     * down, so {@code [n/N]} adds up on every path and the last step reads {@code [N/N]} (#906).
     * Until the choice is made it counts no credentials, which is what Enter picks, and reads
     * {@code [n/N+]}, so a total that grows at the choice was never shown as final (#1441).
     */
    void runSteps(List<Step> setup, List<Step> finish) throws Exception {
        runSteps(setup, finish, 0);
    }

    /** As {@link #runSteps(List, List)}, with the first {@code done} steps of {@code setup} run by the process that started this one. */
    void runSteps(List<Step> setup, List<Step> finish, int done) throws Exception {
        currentStep = done;
        totalSteps = setup.size() + 1 + finish.size();
        totalKnown = false;
        for (var step : setup.subList(done, setup.size())) step.run();
        var credentialSteps = credentialSteps();
        totalSteps += credentialSteps.size();
        totalKnown = true;
        for (var step : credentialSteps) step.run();
        credentials.closeHttpClient();
        installGitRemoteShim();
        for (var step : finish) step.run();
    }

    /** Credential Setup, which returns a step for each credential chosen. */
    List<Step> credentialSteps() {
        var tools = new ToolDefLoader().allToolSetups();
        return selectCredentials(tools).stream().map(name -> credentialStep(name, tools.get(name))).toList();
    }

    /** The step that configures {@code toolName}'s credential. */
    private Step credentialStep(String toolName, ToolSetup tool) {
        return switch (toolName) {
            case "claude" -> this::setupClaudeAuth;
            case "gh" -> this::setupGitHubAuth;
            default -> () -> setupGenericToolCredentials(toolName, tool);
        };
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
        List<Step> steps;
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
            for (var step : steps) step.run();
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
    private List<Step> finalSteps(Step service) {
        return List.of(
                this::setupSearchPaths,
                this::setupHostPaths,
                this::setupMcp,
                this::setupBridgeDns,
                service);
    }

    /**
     * DNS Configuration, which writes the whole block from the configuration the earlier steps
     * saved. Never the built-in domains alone: the proxy's reload has just written the tool
     * domains too, and nothing would write them back until it restarts (#1354).
     */
    void setupBridgeDns() {
        startStep("DNS Configuration", DNS_HINT);
        var domains = ProxyConfig.bridgeDnsDomains(SpawnConfig.load());
        var gatewayIp = ProxyConfig.resolveGatewayIp(incus);
        for (int attempt = 1; ; attempt++) {
            try {
                ProxyConfig.configureBridgeDns(incus, domains, gatewayIp);
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
        startStep("MITM CA Certificate",
                "Generates a custom Certificate Authority for the MITM",
                "proxy. Containers trust this CA so the proxy can intercept",
                "HTTPS and inject credentials transparently.");
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
        return Prompts.console();
    }

    boolean hostHasCommand(String command) {
        return commandExists(command);
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
        startStep("SSH Key Pair",
                "Generates a dedicated SSH key pair used only by isx",
                "to connect to containers. This is separate from your personal",
                "SSH keys and won't interfere with them. Your ~/.ssh/config is",
                "updated automatically.");
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
        startStep("Credential Setup",
                "Choose which API credentials to configure. Each credential",
                "stays on your host — containers only hold placeholders, and",
                "the MITM proxy injects real values transparently.");
        var prompts = prompts();
        if (prompts == null) {
            return List.of();
        }
        return credentials.selectCredentials(allTools, SpawnConfig.load(), prompts);
    }

    private void setupClaudeAuth() {
        startStep("Claude Code Authentication",
                "Configures how containers authenticate with the Claude API.",
                "You can use an Anthropic API key, a Claude Pro/Max OAuth",
                "token, or Google Cloud Vertex AI. The credential stays on",
                "your host and is injected at runtime via the MITM proxy —",
                "containers never see the real key.");
        var prompts = consoleOrExplain();
        if (prompts != null) credentials.setupClaudeAuth(SpawnConfig.load(), prompts);
    }

    private void setupGitHubAuth() {
        startStep("GitHub Authentication",
                "Sets up a GitHub PAT so containers can open PRs, push",
                "code, and manage issues. These containers run autonomous",
                "agents, so give them their own identity rather than",
                "your personal one — create a dedicated agent account (e.g.",
                "yourname-ai-bot) and mint a fine-grained PAT for it, scoped to",
                "just the repos and permissions the agent needs. That keeps the",
                "agent's actions attributable to it and its blast radius small.");
        var prompts = consoleOrExplain();
        if (prompts != null) credentials.setupGitHubAuth(SpawnConfig.load(), prompts);
    }

    private void setupGenericToolCredentials(String toolName, ToolSetup tool) {
        var desc = CredentialSetup.describeTool(toolName, tool);
        startStep(desc,
                "Configures credentials for " + desc + ".",
                "Real credentials stay on your host — containers only hold",
                "placeholders, and the MITM proxy injects real values.");
        var prompts = consoleOrExplain();
        if (prompts != null) credentials.setupGenericToolCredentials(toolName, tool, SpawnConfig.load(), prompts);
    }

    private void setupPathList(
            java.util.function.Function<SpawnConfig, java.util.List<String>> getter,
            java.util.function.BiConsumer<SpawnConfig, java.util.List<String>> setter,
            String skipMessage) {
        var config = SpawnConfig.load();
        var prompts = consoleOrExplain();
        if (prompts != null) {
            credentials.setupPathList(getter, setter, skipMessage, config, prompts);
        } else {
            credentials.printCurrentPaths(getter.apply(config));
        }
    }

    private void setupMcp() {
        startStep("Agent Access over MCP",
                "Lets Claude Code on this machine use isx itself: create",
                "instances from templates you approve here, run commands",
                "in them, and hand tasks to the Claude Code inside them.",
                "Agents can never approve templates themselves.");
        var prompts = Prompts.console();
        if (prompts == null) {
            BuildOutput.step("Skipped: no console. Re-run 'isx init' interactively to set this up.");
            return;
        }
        setupMcp(prompts, ImageDef.loadTrusted());
    }

    /**
     * Offer to register {@code isx mcp} with Claude Code, then ask about each template that
     * installs Claude Code, Codex or Pi (the only ones a task can be delegated to) whether agents may
     * use it. Templates already approved by hand that install none of them are left as they are.
     * The approvals live in {@code mcp.yaml}; one that cannot be read is reported and left alone.
     */
    void setupMcp(Prompts prompts, java.util.Map<String, ImageDef> defs) {
        if (!hostHasCommand("claude")) {
            BuildOutput.step("Claude Code is not installed on this host; skipping."
                    + " Install it and re-run 'isx init' to enable this.");
            return;
        }
        McpConfig mcp;
        try {
            mcp = McpConfig.load();
        } catch (McpConfig.Invalid e) {
            BuildOutput.warn(e.getMessage() + ".", "Fix it, then re-run 'isx init'.");
            return;
        }
        var legacy = McpConfig.legacySectionNotice(SpawnConfig.load());
        if (legacy != null) BuildOutput.step(Character.toUpperCase(legacy.charAt(0)) + legacy.substring(1) + ".");
        var retired = mcp.retiredKeysNotice();
        if (retired != null) BuildOutput.warn(retired + ".", "Saving the approvals here removes them too.");
        var registered = claudeMcpRegistered();
        var approved = mcp.templates();
        var candidates = defs.values().stream()
                .filter(def -> ImageDef.chain(def, defs).stream()
                        .anyMatch(d -> d.getTools().stream().anyMatch(t -> List.of("claude", "codex", "pi").contains(t.getName()))))
                .map(ImageDef::getName)
                .sorted()
                .toList();
        var enabled = registered || !approved.isEmpty();
        if (enabled) {
            BuildOutput.step("Currently " + (registered ? "registered with Claude Code" : "not registered")
                    + "; approved templates: " + (approved.isEmpty() ? "none" : String.join(", ", approved)) + ".");
        }
        // Fully set up: a re-run of init for something else costs one Enter, as credentials do (#1292).
        boolean proceed;
        if (registered && !approved.isEmpty()) {
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
            }
            return;
        }

        if (!registered) {
            var isx = isxPath();
            if (isx == null) {
                BuildOutput.step("Could not find the installed isx binary; register it yourself:");
                BuildOutput.stepNote("claude mcp add --scope user " + McpClientRegistration.SERVER_NAME + " -- <path to isx> mcp");
            } else if (registerClaudeMcp(isx)) {
                BuildOutput.ok("Registered with Claude Code as '" + McpClientRegistration.SERVER_NAME + "' (all projects)");
            } else {
                BuildOutput.step("Registering failed; register it yourself:");
                BuildOutput.stepNote("claude mcp add --scope user " + McpClientRegistration.SERVER_NAME + " -- " + isx + " mcp");
            }
        }

        if (candidates.isEmpty()) {
            BuildOutput.step("No template installs Claude Code, Codex or Pi yet, so there is none to approve."
                    + " Add one, then re-run 'isx init' or edit " + McpConfig.file() + ".");
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
        BuildOutput.stepNote("Keep agents to the MCP tools with Claude Code permissions: see https://isx.run/docs/mcp (docs/MCP.md).");
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

    /** Whether Claude Code already knows an MCP server by this name. */
    boolean claudeMcpRegistered() {
        return runSilently("claude", "mcp", "get", McpClientRegistration.SERVER_NAME) == 0;
    }

    /** Register {@code isx mcp} with Claude Code for every project; true on success. */
    boolean registerClaudeMcp(String isxPath) {
        return runSilently("claude", "mcp", "add", "--scope", "user", McpClientRegistration.SERVER_NAME, "--", isxPath, "mcp") == 0;
    }

    /** Run a host command for its exit code alone, showing nothing and reading no input. */
    private static int runSilently(String... command) {
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
        startStep("Template Search Paths",
                "Local directories where isx looks for custom image and",
                "tool definitions. Definitions found here can extend or",
                "override the built-in templates. Each directory should",
                "contain images/ and/or tools/ subdirectories with YAML",
                "files.");

        if (!hasExistingTemplatesSearchPath(SpawnConfig.load().getSearchPaths())) {
            offerTemplatesRepo();
        }

        setupPathList(
                SpawnConfig::getSearchPaths,
                SpawnConfig::setSearchPaths,
                "No search paths configured. You can add them later in ~/.config/incus-spawn/config.yaml");
    }

    static boolean hasExistingTemplatesSearchPath(java.util.List<String> searchPaths) {
        return searchPaths.stream()
                .anyMatch(p -> Path.of(p).getFileName().toString().equals(TEMPLATES_REPO));
    }

    private void offerTemplatesRepo() {
        var login = commandExists("gh") && commandExists("git") ? getGhLogin() : null;
        var prompts = login == null ? null : Prompts.console();
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
        startStep("Code Directories",
                "Directories on your host containing git repositories (e.g.",
                "~/code). Enables fast reference clones inside containers",
                "and automatic git remote management — repos cloned from",
                "these paths get a remote named after the instance, to push",
                "and pull directly between container and host without",
                "round-tripping through GitHub. Your host files are never",
                "directly exposed to containers.");
        setupPathList(
                SpawnConfig::getHostPaths,
                SpawnConfig::setHostPaths,
                "No host paths configured. Add them later by re-running 'isx init'"
                        + " or editing ~/.config/incus-spawn/config.yaml");
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
        if (prompts == null) {
            BuildOutput.step("Skipped: no console. Start manually with: isx vm start && isx proxy start");
            return false;
        }
        prompts.say("Optional: install VM and proxy as macOS services so they start automatically on login"
                + " and survive reboots.");
        if (!askYesNo(prompts, "Install services?", true)) {
            BuildOutput.step("Skipped. Start manually with: isx vm start && isx proxy start");
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
        if (prompts == null) {
            BuildOutput.step("Skipped: no console. You can start the proxy manually with: isx proxy start");
            return false;
        }
        prompts.say("Optional: install the proxy as a systemd service so it starts automatically and survives reboots.");
        if (!askYesNo(prompts, "Install proxy service?", true)) {
            BuildOutput.step("Skipped. You can start the proxy manually with: isx proxy start");
            return false;
        }
        return true;
    }

    void installGitRemoteShim() {
        if (System.getProperty("org.graalvm.version") != null) return;
        var isxPath = HostPath.find("isx");
        if (isxPath != null) installGitRemoteShim(java.nio.file.Path.of(isxPath).getParent());
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

    static int runHost(String... command) {
        // The command writes to the terminal itself, and sudo may ask for a password there
        BuildOutput.releaseTerminal();
        try {
            var pb = new ProcessBuilder(command);
            pb.inheritIO();
            return pb.start().waitFor();
        } catch (IOException | InterruptedException e) {
            BuildOutput.warn("Failed to run: " + String.join(" ", command) + ": " + failure(e));
            return 1;
        }
    }

    String captureOutput(String... command) {
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
