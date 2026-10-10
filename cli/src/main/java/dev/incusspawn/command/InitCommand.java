package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.McpClientRegistration;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.util.TerminalLink;
import dev.incusspawn.vm.VmManager;
import dev.incusspawn.Platform;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.incusspawn.util.BuildOutput.BOLD;
import static dev.incusspawn.util.BuildOutput.CYAN;
import static dev.incusspawn.util.BuildOutput.DIM;
import static dev.incusspawn.util.BuildOutput.GREEN;
import static dev.incusspawn.util.BuildOutput.RED;
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

    IncusClient incus;

    private static final int BOX_WIDTH = 62;
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

    private final HostSetup host = new HostSetup(this);
    private int totalSteps;
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
        System.out.println();
        System.out.println(topBorder());
        System.out.println(boxLine(""));
        System.out.println(boxLine("   " + styled(BOLD, title)));
        System.out.println(boxLine("   " + subtitle));
        System.out.println(boxLine(""));
        System.out.println(boxLine("   " + styled(DIM, info)));
        System.out.println(boxLine(""));
        System.out.println(bottomBorder());
        System.out.println();
    }

    /**
     * Init re-runs safely on a configured host, so a re-run must not read as a fresh setup that
     * forgot the existing configuration (#1119). Any marker counts, not only a current one
     * ({@link Environment#hasBeenInitialized()}): re-running init after an upgrade is the common case.
     */
    static void printSetupBanner(String platform, String info) {
        var configured = Files.exists(Environment.initCompleteMarker())
                || Files.exists(Environment.configDir().resolve("config.yaml"));
        printBanner("incus-spawn — " + (configured ? "Setup" : "First-Time Setup") + platform,
                configured ? "Reviewing your existing configuration"
                        : "Configuring your isolated development environment",
                info);
    }

    void startStep(String title, String... hintLines) {
        currentStep++;
        String left = "  " + currentStep + "  " + title;
        String right = "[" + currentStep + "/" + totalSteps + "]  ";
        int gap = BOX_WIDTH - left.length() - right.length();

        System.out.println();
        System.out.println(topBorder());
        System.out.println(styled(CYAN, "│") + styled(BOLD, left)
                + " ".repeat(Math.max(1, gap)) + styled(DIM, right) + styled(CYAN, "│"));
        System.out.println(bottomBorder());

        if (hintLines.length > 0) {
            for (var line : hintLines) {
                System.out.println(styled(CYAN, "  ┃ ") + styled(DIM, line));
            }
            System.out.println();
        }
    }

    private static void printCompletionBox(String... lines) {
        System.out.println();
        System.out.println(topBorder());
        System.out.println(boxLine(""));
        for (var line : lines) {
            System.out.println(boxLine(line));
        }
        System.out.println(boxLine(""));
        System.out.println(bottomBorder());
        System.out.println();
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

        System.out.println();
        System.out.println(styled(BOLD + YELLOW, "  First-time setup required."));
        System.out.println("  Running 'isx init'...");
        System.out.println();

        try {
            var result = new InitCommand().doExecute();
            return result.getResultValue() == 0 && hasBeenInitialized();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
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
        var os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("linux")) {
            System.err.println();
            System.err.println(styled(BOLD + RED, "  incus-spawn requires Linux."));
            System.err.println();
            System.err.println("  Incus system containers require a Linux kernel.");
            System.err.println("  macOS and Windows support is planned but not yet available.");
            System.err.println("  Detected OS: " + System.getProperty("os.name"));
            System.err.println();
            System.err.println("  For now, run incus-spawn on a Linux host or inside a Linux VM.");
            System.err.println();
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
        System.err.println();
        System.err.println(styled(BOLD + RED, "  incus-spawn requires Linux or macOS."));
        System.err.println("  Detected OS: " + System.getProperty("os.name"));
        System.err.println();
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
        printSetupBanner("", "~3 minutes · some steps require sudo");

        System.out.println();
        System.out.println("  Several steps need " + styled(BOLD, "sudo") + " to install packages, configure");
        System.out.println("  the firewall, and set up user namespace mappings.");
        System.out.println();
        if (runHost("sudo", "-v") != 0) {
            System.err.println("  sudo authentication failed. Please ensure you have sudo access and try again.");
            return CommandResult.valueOf(1);
        }

        host().installDependencies();
        var proxyServiceInstalled = new AtomicBoolean();
        runSteps(List.of(
                        host()::checkIncusInstalled,
                        host()::configureSubuidSubgid,
                        host()::initializeIncus,
                        host()::configureFirewall,
                        host()::configureMitmProxy,
                        this::setupSshKeyPair),
                finalSteps(() -> {
                    startStep("Proxy Service",
                            "The MITM proxy intercepts HTTPS traffic from containers",
                            "and injects real credentials (API keys, tokens) so that",
                            "containers only ever hold placeholder values. Installing",
                            "it as a systemd service means it starts automatically on",
                            "boot — otherwise you'll need to run 'isx proxy start'",
                            "before launching containers.");
                    proxyServiceInstalled.set(completeWithProxyService(Prompts.console()));
                }));

        var proxyStep = proxyServiceInstalled.get()
                ? "   2. Proxy is running as a systemd service"
                : "   2. Start the auth proxy:  isx proxy start";
        printCompletionBox(
                "   " + styled(BOLD + GREEN, "✓") + styled(BOLD, " Setup complete!"),
                "",
                "   " + styled(BOLD, "Next steps:"),
                "   1. Build a template:      isx build tpl-java",
                proxyStep,
                "   3. Launch the TUI:        isx");
        return CommandResult.SUCCESS;
    }

    private CommandResult doMacOsInit() throws Exception {
        printSetupBanner(" (macOS)", "~2 minutes");

        runSteps(List.of(this::setupMacOsCa, this::setupSshKeyPair),
                finalSteps(() -> {
                    startStep("macOS Services",
                            "Installs the Incus VM and MITM proxy as macOS launch",
                            "agents so they start automatically on login and survive",
                            "reboots. Without this you'll need to manually run",
                            "'isx vm start' and 'isx proxy start' before launching",
                            "containers.");
                    completeWithMacOsServices(Prompts.console());
                }));

        printCompletionBox(
                "   " + styled(BOLD + GREEN, "✓") + styled(BOLD, " Setup complete!"),
                "",
                "   " + styled(BOLD, "Next steps:"),
                "   1. Build a template:  isx build tpl-java",
                "   2. Launch the TUI:    isx");
        return CommandResult.SUCCESS;
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
     * Until the choice is made it assumes no credentials, which is what Enter picks.
     */
    void runSteps(List<Step> setup, List<Step> finish) throws Exception {
        currentStep = 0;
        totalSteps = setup.size() + 1 + finish.size();
        for (var step : setup) step.run();
        var credentialSteps = credentialSteps();
        totalSteps += credentialSteps.size();
        for (var step : credentialSteps) step.run();
        credentials.closeHttpClient();
        installGitRemoteShim();
        for (var step : finish) step.run();
    }

    /** Credential Setup, which returns a step for each credential chosen. */
    List<Step> credentialSteps() {
        var tools = new ToolDefLoader().allToolSetups();
        return selectCredentials(tools).stream().<Step>map(name -> switch (name) {
            case "claude" -> this::setupClaudeAuth;
            case "gh" -> this::setupGitHubAuth;
            default -> () -> setupGenericToolCredentials(name, tools.get(name));
        }).toList();
    }

    /** The steps both platforms end with, the last being {@code service}. */
    private List<Step> finalSteps(Step service) {
        return List.of(
                this::setupSearchPaths,
                this::setupHostPaths,
                this::setupMcp,
                () -> {
                    startStep("DNS Configuration", DNS_HINT);
                    ProxyConfig.configureBridgeDns(incus);
                },
                service);
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
            System.out.println("  MITM CA certificate already exists.");
        } else {
            CertificateAuthority.loadOrCreate();
            System.out.println("  CA certificate generated.");
        }
    }

    static boolean commandExists(String command) {
        try {
            var pb = new ProcessBuilder("which", command);
            pb.redirectErrorStream(true);
            return pb.start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** The terminal, or null after saying why interactive setup cannot run without one. */
    private static Prompts consoleOrExplain() {
        var prompts = Prompts.console();
        if (prompts == null) System.err.println("  Error: no console available for interactive setup.");
        return prompts;
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

    private void setupSshKeyPair() {
        startStep("SSH Key Pair",
                "Generates a dedicated SSH key pair used only by isx",
                "to connect to containers. This is separate from your personal",
                "SSH keys and won't interfere with them. Your ~/.ssh/config is",
                "updated automatically.");
        try {
            if (SshKeyManager.exists()) {
                System.out.println("  SSH key pair already exists.");
            } else {
                SshKeyManager.ensureKeyPairExists();
            }
            if (SshKeyManager.ensureSshConfigInclude()) {
                System.out.println("  SSH configuration ready.");
            } else {
                System.out.println("  SSH key generated but ~/.ssh/config could not be updated.");
                System.out.println("  Add manually: Include ~/.config/incus-spawn/ssh/config");
            }
        } catch (Exception e) {
            System.err.println("  Warning: SSH key setup failed: " + e.getMessage());
            System.err.println("  SSH container access will fall back to your personal keys.");
            System.err.println("  You can retry later with: isx init");
        }
    }

    private List<String> selectCredentials(Map<String, ToolSetup> allTools) {
        startStep("Credential Setup",
                "Choose which API credentials to configure. Each credential",
                "stays on your host — containers only hold placeholders, and",
                "the MITM proxy injects real values transparently.");
        var prompts = Prompts.console();
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
        startStep("Agent Access over MCP (experimental)",
                "Lets Claude Code on this machine use isx itself: create",
                "instances from templates you approve here, run commands",
                "in them, and hand tasks to the Claude Code inside them.",
                "Agents can never approve templates themselves.");
        var prompts = Prompts.console();
        if (prompts == null) {
            System.out.println("  Skipped: no console. Re-run 'isx init' interactively to set this up.");
            return;
        }
        setupMcp(SpawnConfig.load(), prompts, ImageDef.loadTrusted());
    }

    /**
     * Offer to register {@code isx mcp} with Claude Code, then ask about each template that
     * installs Claude Code (the only ones a task can be delegated to) whether agents may use it.
     * Templates already approved by hand that do not install Claude Code are left as they are.
     */
    void setupMcp(SpawnConfig config, Prompts prompts, java.util.Map<String, ImageDef> defs) {
        if (!hostHasCommand("claude")) {
            System.out.println("  Claude Code is not installed on this host; skipping.");
            System.out.println("  Install it and re-run 'isx init' to enable this.");
            return;
        }
        var registered = claudeMcpRegistered();
        var approved = config.mcp().templates();
        var enabled = registered || !approved.isEmpty();
        if (enabled) {
            System.out.println("  Currently " + (registered ? "registered with Claude Code" : "not registered")
                    + "; approved templates: " + (approved.isEmpty() ? "none" : String.join(", ", approved)) + ".");
        }
        if (!askConfirmation(prompts, "  Enable the experimental isx MCP server?", enabled)) {
            if (registered) {
                System.out.println("  Left as it is. To remove it: claude mcp remove --scope user " + McpClientRegistration.SERVER_NAME);
            }
            return;
        }

        if (!registered) {
            var isx = isxPath();
            if (isx == null) {
                System.out.println("  Could not find the installed isx binary; register it yourself:");
                System.out.println("    claude mcp add --scope user " + McpClientRegistration.SERVER_NAME + " -- <path to isx> mcp");
            } else if (registerClaudeMcp(isx)) {
                System.out.println("  " + styled(BOLD + GREEN, "✓") + " Registered with Claude Code as '"
                        + McpClientRegistration.SERVER_NAME + "' (all projects).");
            } else {
                System.out.println("  Registering failed; register it yourself:");
                System.out.println("    claude mcp add --scope user " + McpClientRegistration.SERVER_NAME + " -- " + isx + " mcp");
            }
        }

        var candidates = defs.values().stream()
                .filter(def -> ImageDef.chain(def, defs).stream()
                        .anyMatch(d -> d.getTools().stream().anyMatch(t -> "claude".equals(t.getName()))))
                .map(ImageDef::getName)
                .sorted()
                .toList();
        if (candidates.isEmpty()) {
            System.out.println("  No template installs Claude Code yet (the 'claude' tool), so there is");
            System.out.println("  none to approve. Add one, then re-run 'isx init' or edit the mcp:");
            System.out.println("  section of " + Environment.configDir().resolve("config.yaml") + ".");
            return;
        }
        System.out.println("  Approve the templates agents may create instances from:");
        var chosen = new java.util.ArrayList<String>();
        for (var name : candidates) {
            var note = templateBuilt(name) ? "" : styled(DIM, " (not built yet: isx build " + name + ")");
            if (askConfirmation(prompts, "    " + name + note + "?", approved.contains(name))) chosen.add(name);
        }
        // Keep hand-approved templates this step does not ask about, in their order.
        var updated = new java.util.ArrayList<String>();
        approved.stream().filter(n -> !candidates.contains(n) || chosen.contains(n)).forEach(updated::add);
        chosen.stream().filter(n -> !updated.contains(n)).forEach(updated::add);
        if (updated.equals(approved)) {
            System.out.println("  Approved templates unchanged.");
        } else {
            var mcp = config.mcp();
            mcp.setTemplates(updated);
            config.setMcp(mcp);
            config.save();
            System.out.println("  Approved templates saved: " + (updated.isEmpty() ? "none" : String.join(", ", updated)) + ".");
        }
        System.out.println("  " + styled(DIM, "Keep agents to the MCP tools with Claude Code permissions: see the"));
        System.out.println("  " + styled(DIM, "README section 'Delegating from an agent on your host (MCP)'."));
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
                "  No search paths configured. You can add them later in ~/.config/incus-spawn/config.yaml");
    }

    static boolean hasExistingTemplatesSearchPath(java.util.List<String> searchPaths) {
        return searchPaths.stream()
                .anyMatch(p -> Path.of(p).getFileName().toString().equals(TEMPLATES_REPO));
    }

    private void offerTemplatesRepo() {
        if (!commandExists("gh") || !commandExists("git")) {
            System.out.println("  For community templates, see (clone and add the local path):");
            System.out.println("  " + TerminalLink.link("https://github.com/" + TEMPLATES_UPSTREAM));
            System.out.println();
            return;
        }

        var login = getGhLogin();
        if (login == null) {
            System.out.println("  For community templates, see (clone and add the local path):");
            System.out.println("  " + TerminalLink.link("https://github.com/" + TEMPLATES_UPSTREAM));
            System.out.println();
            return;
        }

        var prompts = Prompts.console();
        if (prompts == null) {
            System.out.println("  For community templates, see (clone and add the local path):");
            System.out.println("  " + TerminalLink.link("https://github.com/" + TEMPLATES_UPSTREAM));
            System.out.println();
            return;
        }

        if (ghRepoExists(login + "/" + TEMPLATES_REPO)) {
            offerCloneTemplates(prompts, login);
        } else {
            offerForkAndCloneTemplates(prompts, login);
        }
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
        System.out.println("  Found " + styled(BOLD, login + "/" + TEMPLATES_REPO) + " on GitHub.");
        var defaultPath = defaultClonePath();
        var clonePath = askClonePath(prompts, defaultPath);
        if (clonePath == null) return;
        cloneAndAddSearchPath(prompts, login + "/" + TEMPLATES_REPO, clonePath, clonePath.equals(defaultPath));
    }

    private void offerForkAndCloneTemplates(Prompts prompts, String login) {
        System.out.println("  You don't have a " + styled(BOLD, TEMPLATES_REPO) + " repo yet.");
        if (!askConfirmation(prompts, "  Fork " + TEMPLATES_UPSTREAM + " to your account?", true)) {
            System.out.println("  Skipped. You can fork it manually at:");
            System.out.println("  " + TerminalLink.link("https://github.com/" + TEMPLATES_UPSTREAM));
            System.out.println();
            return;
        }

        System.out.println("  Forking " + TEMPLATES_UPSTREAM + "...");
        var forkResult = runHostCapturingExit("gh", "repo", "fork", TEMPLATES_UPSTREAM, "--clone=false");
        if (forkResult != 0) {
            System.out.println("  Fork failed. You can fork it manually at:");
            System.out.println("  " + TerminalLink.link("https://github.com/" + TEMPLATES_UPSTREAM + "/fork"));
            System.out.println();
            return;
        }
        System.out.println("  " + styled(BOLD + GREEN, "✓") + " Forked to " + login + "/" + TEMPLATES_REPO);

        var defaultPath = defaultClonePath();
        var clonePath = askClonePath(prompts, defaultPath);
        if (clonePath == null) {
            System.out.println("  You can clone it later with: gh repo clone " + login + "/" + TEMPLATES_REPO);
            System.out.println();
            return;
        }
        cloneAndAddSearchPath(prompts, login + "/" + TEMPLATES_REPO, clonePath, clonePath.equals(defaultPath));
    }

    private static String defaultClonePath() {
        return Environment.configDir().resolve(TEMPLATES_REPO).toString();
    }

    static String askClonePath(Prompts prompts, String defaultPath) {
        System.out.print("  Clone to " + defaultPath + "? (Y/path/n): ");
        var answer = readInput(prompts.readLine());
        if (answer.equalsIgnoreCase("n")) return null;
        if (answer.isEmpty() || answer.equalsIgnoreCase("y")) return defaultPath;

        if (answer.equalsIgnoreCase("path")) {
            System.out.print("  Clone path: ");
            var path = readInput(prompts.readLine());
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
            System.out.println("  Directory already exists: " + target);
            addToSearchPaths(target.toString());
            return;
        }

        if (!alreadyConfirmed || adjusted) {
            if (!askConfirmation(prompts, "  Will clone to " + target + ". Proceed?", true)) {
                System.out.println("  Skipped cloning. You can add the path manually below.");
                return;
            }
        }

        System.out.println("  Cloning...");
        var result = runHostCapturingExit("gh", "repo", "clone", nwo, target.toString());
        if (result != 0) {
            System.out.println("  Clone failed. You can clone it manually and add the path below.");
            return;
        }
        System.out.println("  " + styled(BOLD + GREEN, "✓") + " Cloned to " + target);
        addToSearchPaths(target.toString());
    }

    private void addToSearchPaths(String path) {
        var config = SpawnConfig.load();
        var paths = new java.util.ArrayList<>(config.getSearchPaths());
        if (!paths.contains(path)) {
            paths.add(path);
            config.setSearchPaths(paths);
            config.save();
            System.out.println("  Added to search paths.");
        }
        System.out.println();
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
                "these paths get an 'isx' remote, letting you push and pull",
                "directly between container and host without round-tripping",
                "through GitHub. Your host files are never directly exposed",
                "to containers.");
        setupPathList(
                SpawnConfig::getHostPaths,
                SpawnConfig::setHostPaths,
                "  No host paths configured. Add them later by re-running 'isx init'\n" +
                "  or editing ~/.config/incus-spawn/config.yaml");
    }

    /**
     * The last step of the macOS flow: asks about the launch agents, marks init complete, and
     * only then installs them.
     * <p>
     * The marker comes before the install because {@code isx-proxy} exits
     * {@link ProxyService#EXIT_CONFIG} until init has completed, so a service installed ahead of
     * it fails its first start and init reports a proxy that is not responding (#938; DESIGN.md
     * has the full account). It comes after the question so that an init abandoned at the prompt
     * is still run again. The services are optional: declining them leaves init complete.
     */
    void completeWithMacOsServices(Prompts prompts) throws IOException {
        var install = wantsMacOsServices(prompts);
        markInitComplete();
        if (install) installProxyService();
    }

    private boolean wantsMacOsServices(Prompts prompts) {
        if (macOsServicesInstalled()) {
            System.out.println("  macOS services already installed.");
            return false;
        }
        System.out.println();
        System.out.println("  Optional: install VM and proxy as macOS services so they start");
        System.out.println("  automatically on login and survive reboots.");
        System.out.println();
        if (prompts == null) return false;
        if (!askConfirmation(prompts, "  Install services?", true)) {
            System.out.println("  Skipped. Start manually with: isx vm start && isx proxy start");
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
            System.out.println();
            System.out.println("  Proxy service is already running.");
            return true;
        }
        return install && installProxyService();
    }

    private boolean wantsProxyService(Prompts prompts) {
        System.out.println();
        System.out.println("  Optional: install the proxy as a systemd service so it starts");
        System.out.println("  automatically and survives reboots.");
        System.out.println();
        if (prompts == null) return false;
        if (!askConfirmation(prompts, "  Install proxy service?", true)) {
            System.out.println("  Skipped. You can start the proxy manually with: isx proxy start");
            return false;
        }
        return true;
    }

    void installGitRemoteShim() {
        if (System.getProperty("org.graalvm.version") != null) return;

        try {
            var pb = new ProcessBuilder("which", "isx");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var isxPath = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() != 0 || isxPath.isEmpty()) return;

            var shimPath = java.nio.file.Path.of(isxPath).getParent().resolve("git-remote-isx");
            if (Files.exists(shimPath)) return;

            try (var is = getClass().getClassLoader().getResourceAsStream("git-remote-isx")) {
                if (is == null) return;
                Files.write(shimPath, is.readAllBytes());
                shimPath.toFile().setExecutable(true, false);
                System.out.println("  Installed git remote helper: " + shimPath);
            }
        } catch (Exception e) {
            System.err.println("  Warning: could not install git-remote-isx shim: " + e.getMessage());
        }
    }

    static int runHost(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.inheritIO();
            return pb.start().waitFor();
        } catch (IOException | InterruptedException e) {
            System.err.println("  Failed to run: " + String.join(" ", command) + ": " + e.getMessage());
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
        System.err.println("  Failed: " + String.join(" ", command));
        return false;
    }

    /**
     * Run a host command with its output captured, shown only when it fails, as
     * {@code Container.runQuiet()} does during build: firewall-cmd's {@code ALREADY_ENABLED} and
     * {@code NOT_ENABLED} warnings on a successful call are noise in the step (#906). The caller
     * reports the outcome.
     */
    int runHostQuiet(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes());
            var exitCode = process.waitFor();
            if (exitCode != 0) {
                output.lines().map(String::strip).filter(line -> !line.isEmpty())
                        .forEach(line -> System.err.println("  " + line));
            }
            return exitCode;
        } catch (IOException | InterruptedException e) {
            System.err.println("  Failed to run: " + String.join(" ", command) + ": " + e.getMessage());
            return 1;
        }
    }
}
