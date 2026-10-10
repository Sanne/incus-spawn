package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.CidrUtils;
import dev.incusspawn.incus.FirewalldCheck;
import dev.incusspawn.incus.FirewallDetector;
import dev.incusspawn.incus.FirewallDetector.DetectionResult;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.UfwCheck;
import dev.incusspawn.lifecycle.InstanceLifecycle;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private IncusClient incus;
    private boolean useUfw;

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

        installDependencies();
        var proxyServiceInstalled = new AtomicBoolean();
        runSteps(List.of(
                        this::checkIncusInstalled,
                        this::configureSubuidSubgid,
                        this::initializeIncus,
                        this::configureFirewall,
                        this::configureMitmProxy,
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

    /**
     * Detect the host package manager. Returns the install command prefix
     * (e.g. {"dnf", "install", "-y"}) or null if none is found.
     */
    private static String[] detectInstallCommand() {
        if (commandExists("dnf"))    return new String[]{"dnf", "install", "-y"};
        if (commandExists("apt"))    return new String[]{"apt", "install", "-y"};
        if (commandExists("zypper")) return new String[]{"zypper", "install", "-y"};
        if (commandExists("pacman")) return new String[]{"pacman", "-S", "--noconfirm"};
        return null;
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

    private void installDependencies() {
        var installCmd = detectInstallCommand();
        if (installCmd == null) return;

        var missing = new ArrayList<String>();
        if (!commandExists("openssl"))      missing.add("openssl");
        if (!commandExists("ssh-keygen"))  missing.add("openssh-clients");
        if (!commandExists("btrfs"))       missing.add("btrfs-progs");
        if (missing.isEmpty()) return;

        System.out.println("Installing dependencies: " + String.join(", ", missing) + "...");
        // zypper uses "btrfsprogs" instead of "btrfs-progs"
        if (commandExists("zypper")) {
            missing.replaceAll(p -> "btrfs-progs".equals(p) ? "btrfsprogs" : p);
        }
        // Debian/Ubuntu uses "openssh-client" (singular)
        if (commandExists("apt")) {
            missing.replaceAll(p -> "openssh-clients".equals(p) ? "openssh-client" : p);
        }
        // Arch/pacman uses "openssh"
        if (commandExists("pacman")) {
            missing.replaceAll(p -> "openssh-clients".equals(p) ? "openssh" : p);
        }
        var cmd = new ArrayList<String>();
        cmd.add("sudo");
        cmd.addAll(java.util.List.of(installCmd));
        cmd.addAll(missing);
        runHost(cmd.toArray(String[]::new));
    }

    private void checkIncusInstalled() {
        startStep("Incus Installation",
                "Incus provides full Linux system containers — lightweight",
                "VMs with near-native performance. This step installs the",
                "Incus package, enables its systemd service, and adds your",
                "user to the incus-admin group for unprivileged access.");
        var result = runHost("which", "incus");
        if (result != 0) {
            var installCmd = detectInstallCommand();
            System.out.println("  Incus is not installed on this system.");
            System.out.println("  The following steps require sudo privileges:");
            System.out.println("    - Install the 'incus' package");
            System.out.println("    - Enable the incus systemd service");
            System.out.println("    - Add your user to the 'incus-admin' group");
            System.out.println();
            if (installCmd != null) {
                System.out.println("  If you prefer to install manually, abort now (Ctrl+C) and run:");
                System.out.println("    sudo " + String.join(" ", installCmd) + " incus");
            } else {
                System.out.println("  No supported package manager found (dnf, apt, zypper, pacman).");
                System.out.println("  Install Incus manually (see " + TerminalLink.link("https://linuxcontainers.org/incus/docs/main/installing/") + "), then run:");
            }
            System.out.println("    sudo systemctl enable --now incus");
            System.out.println("    sudo usermod -aG incus-admin " + System.getProperty("user.name"));
            System.out.println("  Then re-run 'isx init' to continue setup.");
            System.out.println();

            if (installCmd == null) {
                System.out.println("  Cannot auto-install without a supported package manager.");
                System.exit(1);
            }

            var console = System.console();
            if (console != null) {
                if (!askConfirmation(console, "  Proceed with automatic installation?", true)) {
                    System.out.println("  Aborted. Install Incus manually and re-run 'isx init'.");
                    System.exit(0);
                }
            }

            System.out.println("  Installing Incus via " + installCmd[0] + " (sudo required)...");
            var fullCmd = new String[installCmd.length + 2];
            fullCmd[0] = "sudo";
            System.arraycopy(installCmd, 0, fullCmd, 1, installCmd.length);
            fullCmd[fullCmd.length - 1] = "incus";
            runHost(fullCmd);
            System.out.println("  Enabling incus service...");
            runHost("sudo", "systemctl", "enable", "--now", "incus");
            System.out.println("  Adding user to incus-admin group...");
            runHost("sudo", "usermod", "-aG", "incus-admin", System.getProperty("user.name"));
            System.out.println("  NOTE: You may need to log out and back in for group membership to take effect.");
            System.out.println("  Alternatively, run: newgrp incus-admin");
        } else {
            System.out.println("  Incus is installed.");
            var serviceActive = runHost("systemctl", "is-active", "--quiet", "incus");
            if (serviceActive != 0) {
                System.out.println("  Incus service is not running. Enabling and starting it (sudo required)...");
                var enableResult = runHost("sudo", "systemctl", "enable", "--now", "incus");
                if (enableResult != 0) {
                    System.err.println("  Failed to start the Incus service. Run 'sudo systemctl enable --now incus' manually, then re-run 'isx init'.");
                    System.exit(1);
                }
            }
        }

        // Always ensure current user is in incus-admin group
        try {
            var pb = new ProcessBuilder("id", "-nG");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var groups = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            if (!groups.contains("incus-admin")) {
                System.out.println("  Adding user to incus-admin group...");
                runHost("sudo", "usermod", "-aG", "incus-admin", System.getProperty("user.name"));
                System.out.println("  Group membership updated (active after next login).");
            }
        } catch (Exception e) {
            System.err.println("  Warning: could not check group membership: " + e.getMessage());
        }
    }

    private void configureFirewall() {
        startStep("Firewall Configuration",
                "Configures the host firewall so containers can reach the",
                "internet and resolve DNS. Detects whether firewalld or UFW",
                "is active, adds the Incus bridge to a trusted zone, enables",
                "NAT masquerading, and sets up FORWARD rules.");

        var detection = FirewallDetector.detect();
        switch (detection) {
            case DetectionResult.UseFirewalld fwd -> {
                if (fwd.needsStart()) {
                    System.out.println("  firewalld is installed but not running. Starting and enabling it...");
                    var startResult = runHost("sudo", "systemctl", "enable", "--now", "firewalld");
                    if (startResult != 0) {
                        System.err.println("  Error: failed to start firewalld.");
                        System.err.println("  Run manually: sudo systemctl enable --now firewalld");
                        System.err.println("  Then re-run: isx init");
                        return;
                    }
                    System.out.println("  firewalld started and enabled.");
                    if (ProxyService.isActive()) {
                        if (Environment.hasBeenInitialized()) {
                            System.out.println("  Restarting proxy service so it picks up the restored firewall rules...");
                            restartProxyService();
                        } else {
                            // Restarted before the marker, it would refuse to start (#1048);
                            // completeWithProxyService restarts it once the marker is written.
                            System.out.println("  The proxy service will be restarted once init completes, to pick up the restored firewall rules.");
                        }
                    }
                }
                configureFirewalld();
            }
            case DetectionResult.UseUfw u -> {
                useUfw = true;
                configureUfw(ProxyConfig.resolveGatewayIp(incus), UfwCheck.readBeforeRules());
            }
            case DetectionResult.NeitherInstalled n -> {
                System.out.println("  No firewall detected. Installing firewalld...");
                var installCmd = detectInstallCommand();
                if (installCmd == null) {
                    System.err.println("  Error: could not detect package manager.");
                    return;
                }
                var cmd = new java.util.ArrayList<String>();
                cmd.add("sudo");
                cmd.addAll(java.util.List.of(installCmd));
                cmd.add("firewalld");
                var installResult = runHost(cmd.toArray(String[]::new));
                if (installResult != 0) {
                    System.err.println("  Error: failed to install firewalld.");
                    return;
                }
                var startResult = runHost("sudo", "systemctl", "enable", "--now", "firewalld");
                if (startResult != 0) {
                    System.err.println("  Error: failed to start firewalld.");
                    return;
                }
                configureFirewalld();
            }
        }
        configureNetworkManager();
    }

    void configureFirewalld() {
        var trustedZoneOutput = captureOutput("sudo", "firewall-cmd", "--zone=trusted", "--list-all");
        boolean hasInterface = trustedZoneOutput.contains("incusbr0");
        boolean hasMasquerade = trustedZoneOutput.contains("masquerade: yes");

        var directRulesOutput = captureOutput("sudo", "firewall-cmd", "--direct", "--get-all-rules");
        boolean hasForwardIn = FirewalldCheck.isForwardRulePresent(directRulesOutput, "-i", "incusbr0");
        boolean hasForwardOut = FirewalldCheck.isForwardRulePresent(directRulesOutput, "-o", "incusbr0");

        if (hasInterface && hasMasquerade && hasForwardIn && hasForwardOut) {
            System.out.println("  Firewall already configured (firewalld).");
            return;
        }

        if (!hasInterface) {
            System.out.println("  Adding incusbr0 to the trusted firewall zone (sudo required)...");
            var addResult = runHostQuiet("sudo", "firewall-cmd", "--zone=trusted", "--change-interface=incusbr0", "--permanent");
            if (addResult != 0) {
                System.err.println("  Warning: failed to add incusbr0 to trusted zone.");
                System.err.println("  Containers may not have network/DNS access.");
                System.err.println("  You can fix this manually:");
                System.err.println("    sudo firewall-cmd --zone=trusted --change-interface=incusbr0 --permanent");
                System.err.println("    sudo firewall-cmd --zone=trusted --add-masquerade --permanent");
                System.err.println("    sudo firewall-cmd --reload");
                return;
            }
        }
        boolean ok = true;
        if (!hasMasquerade) {
            System.out.println("  Enabling masquerading (NAT) for container internet access...");
            ok &= ran("sudo", "firewall-cmd", "--zone=trusted", "--add-masquerade", "--permanent");
        }
        if (!hasForwardIn) {
            System.out.println("  Adding FORWARD rules for Incus bridge (Docker coexistence)...");
            ok &= ran("sudo", "firewall-cmd", "--permanent", "--direct",
                    "--add-rule", "ipv4", "filter", "FORWARD", "0",
                    "-i", "incusbr0", "-j", "ACCEPT");
        }
        if (!hasForwardOut) {
            if (hasForwardIn) {
                System.out.println("  Adding FORWARD rules for Incus bridge (Docker coexistence)...");
            }
            ok &= ran("sudo", "firewall-cmd", "--permanent", "--direct",
                    "--add-rule", "ipv4", "filter", "FORWARD", "0",
                    "-o", "incusbr0", "-m", "conntrack", "--ctstate", "RELATED,ESTABLISHED", "-j", "ACCEPT");
        }

        var reloadResult = runHostQuiet("sudo", "firewall-cmd", "--reload");
        if (reloadResult != 0) {
            System.err.println("  Warning: firewall reload failed. Run: sudo firewall-cmd --reload");
            if (!ok) warnFirewallIncomplete();
            return;
        }
        if (!ok) {
            warnFirewallIncomplete();
            return;
        }
        System.out.println("  Firewall configured: incusbr0 in trusted zone with masquerading (firewalld).");
    }

    void configureUfw(String gatewayIp, String beforeRules) {
        var subnet = CidrUtils.deriveSubnet(gatewayIp);

        if (beforeRules.isEmpty()) {
            System.err.println("  Error: could not read /etc/ufw/before.rules.");
            System.err.println("  Skipping UFW configuration to avoid overwriting existing rules.");
            return;
        }
        boolean hasForward = UfwCheck.hasForwardRules(beforeRules);
        boolean hasMasquerade = UfwCheck.hasMasquerade(beforeRules, subnet);

        if (hasForward && hasMasquerade) {
            System.out.println("  Firewall already configured (UFW).");
            return;
        }

        System.out.println("  Allowing traffic on incusbr0...");
        boolean ok = ran("sudo", "ufw", "allow", "in", "on", "incusbr0");

        var content = beforeRules;
        if (!hasMasquerade) {
            System.out.println("  Adding NAT masquerading for container internet access...");
            var natBlock = UfwCheck.generateNatBlockWithoutRedirect(subnet);
            content = UfwCheck.insertNatBlock(content, natBlock);
        }
        if (!hasForward) {
            System.out.println("  Adding FORWARD rules for Incus bridge...");
            var filterInsert = UfwCheck.generateFilterInsert();
            content = UfwCheck.insertFilterRules(content, filterInsert);
        }

        if (!content.equals(beforeRules)) {
            ok &= writeBeforeRules(content);
            var reloadResult = runHostQuiet("sudo", "ufw", "reload");
            if (reloadResult != 0) {
                System.err.println("  Warning: UFW reload failed. Run: sudo ufw reload");
                if (!ok) warnFirewallIncomplete();
                return;
            }
        }
        if (!ok) {
            warnFirewallIncomplete();
            return;
        }
        System.out.println("  Firewall configured: incusbr0 trusted with masquerading (UFW).");
    }

    private static void warnFirewallIncomplete() {
        warnIncomplete("firewall only partly configured; containers may lack network/DNS access.");
    }

    private static void warnIncomplete(String consequence) {
        System.err.println("  Warning: " + consequence);
        System.err.println("  Fix the problem reported above, then re-run: isx init");
    }

    /** Whether the new rules reached {@code before.rules}. */
    private boolean writeBeforeRules(String content) {
        try {
            var tempFile = java.nio.file.Files.createTempFile("isx-before-rules-", ".tmp");
            java.nio.file.Files.writeString(tempFile, content);
            var copied = ran("sudo", "cp", tempFile.toString(), UfwCheck.BEFORE_RULES.toString());
            java.nio.file.Files.deleteIfExists(tempFile);
            return copied;
        } catch (java.io.IOException e) {
            System.err.println("  Error writing before.rules: " + e.getMessage());
            return false;
        }
    }

    private void configureNetworkManager() {
        configureNetworkManager(Path.of("/etc/NetworkManager/conf.d"));
    }

    void configureNetworkManager(Path confDir) {
        if (!Files.isDirectory(confDir)) return;
        var confFile = confDir.resolve("99-unmanaged-veth.conf");
        if (Files.exists(confFile)) return;
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("isx-nm-veth-", ".conf");
            Files.writeString(tempFile,
                    "[keyfile]\nunmanaged-devices=interface-name:veth*\n");
            if (installHostFile(tempFile, confFile.toString()) != 0) return;
            if (runHostQuiet("sudo", "nmcli", "general", "reload") != 0) {
                System.err.println("  Warning: wrote " + confFile
                        + " but could not reload NetworkManager; it applies after a reboot.");
            } else {
                System.out.println("  Configured NetworkManager to ignore veth devices.");
            }
        } catch (IOException e) {
            System.err.println("  Warning: could not configure NetworkManager: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Put a config file in place as root, world-readable like the rest of {@code /etc}. Not
     * {@code cp}: the source is a {@code 0600} temp file, and {@code cp} gives a new file the
     * source's mode, so the next run could not read it back to see whether it changed (#821).
     */
    private int installHostFile(Path source, String destination) {
        return runHostQuiet("sudo", "install", "-m", "0644", source.toString(), destination);
    }

    /**
     * The file's content, or {@code null} when it is missing or unreadable -- either way it needs
     * (re)writing. Files earlier releases wrote {@code 0600 root} fall in the second case, so
     * re-running {@code isx init} repairs them.
     */
    static String readIfReadable(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static final String SYSCTL_CONF = "/etc/sysctl.d/99-incus-spawn.conf";

    private void configureHostSysctls() {
        configureHostSysctls(Path.of(SYSCTL_CONF));
    }

    void configureHostSysctls(Path sysctlPath) {
        var sysctlConf = sysctlPath.toString();
        var content = """
                # All containers share one host UID range, so they draw on the same
                # per-UID inotify budget.  The kernel default (128 instances) runs out
                # around the tenth concurrent container and the next one's systemd dies
                # before it can log anything.
                fs.inotify.max_user_instances=8192
                fs.inotify.max_user_watches=524288

                # Allow kernel-inclusive profiling from unprivileged containers. The
                # default (2) restricts perf_event_open() to userspace-only, which blocks
                # perf record/stat with kernel samples and narrows what async-profiler can
                # see. -1 removes all restrictions -- the container is the security
                # boundary, not this sysctl.
                kernel.perf_event_paranoid=-1
                """;
        try {
            if (content.equals(readIfReadable(sysctlPath))) {
                return;
            }
            var tempFile = Files.createTempFile("isx-sysctl-", ".conf");
            Files.writeString(tempFile, content);
            if (installHostFile(tempFile, sysctlConf) != 0) {
                System.err.println("  Warning: could not write " + sysctlConf + ".");
            } else if (runHostQuiet("sudo", "sysctl", "-p", sysctlConf) != 0) {
                System.err.println("  Warning: wrote " + sysctlConf
                        + " but could not apply it now; it applies after a reboot.");
            } else {
                System.out.println("  Configured host sysctls (inotify, perf_event_paranoid).");
            }
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            System.err.println("  Warning: could not configure host sysctls: " + e.getMessage());
        }
    }

    private static final String KSM_RUN = "/sys/kernel/mm/ksm/run";
    private static final String KSM_TMPFILES = "/etc/tmpfiles.d/incus-spawn-ksm.conf";

    /**
     * Turn on kernel same-page merging. QEMU marks guest RAM mergeable, and branches of one
     * template hold many identical pages (kernel, JDK, libraries), so VMs share them instead of
     * each holding a copy. Containers are unaffected: their processes do not opt in. Persisted as
     * a tmpfiles.d {@code w} line, which only writes when the file exists.
     */
    private void configureKsm() {
        if (!Files.exists(Path.of(KSM_RUN))) {
            return; // kernel built without CONFIG_KSM
        }
        if ("active".equals(captureOutput("systemctl", "is-active", "ksmtuned"))) {
            System.out.println("  KSM is managed by ksmtuned; leaving it alone.");
            return;
        }
        var content = """
                # Written by isx init: share identical memory pages between VMs.
                w %s - - - - 1
                """.formatted(KSM_RUN);
        var confPath = Path.of(KSM_TMPFILES);
        Path tempFile = null;
        try {
            // Also check the live value: a written config whose apply failed must be retried
            if (content.equals(readIfReadable(confPath))
                    && "1".equals(Files.readString(Path.of(KSM_RUN)).strip())) {
                return;
            }
            tempFile = Files.createTempFile("isx-ksm-", ".conf");
            Files.writeString(tempFile, content);
            if (installHostFile(tempFile, KSM_TMPFILES) != 0) {
                System.err.println("  Warning: could not write " + KSM_TMPFILES + "; KSM stays off.");
            } else if (runHostQuiet("sudo", "systemd-tmpfiles", "--create", KSM_TMPFILES) != 0) {
                System.err.println("  Warning: could not enable KSM now; it will be on after a reboot.");
            } else {
                System.out.println("  Enabled kernel same-page merging (KSM) for VMs.");
            }
        } catch (IOException e) {
            System.err.println("  Warning: could not enable KSM: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    private void configureMitmProxy() {
        startStep("MITM Authentication Proxy",
                "The MITM proxy intercepts HTTPS from containers and injects",
                "your real API credentials, so containers never hold sensitive",
                "tokens directly. This step sets up iptables port redirection",
                "and generates a custom CA certificate trusted by containers.");

        var gatewayIp = ProxyConfig.resolveGatewayIp(incus);
        var config = SpawnConfig.load();
        if (!gatewayIp.equals(config.getIncusBridgeGateway())) {
            config.setIncusBridgeGateway(gatewayIp);
            config.save();
        }

        var redirected = useUfw
                ? configureMitmProxyUfw(gatewayIp, UfwCheck.readBeforeRules())
                : configureMitmProxyFirewalld(gatewayIp);

        configureHostSysctls();
        configureKsm();

        // Generate CA certificate if it doesn't exist
        if (CertificateAuthority.exists()) {
            System.out.println("  MITM CA certificate already exists.");
        } else {
            CertificateAuthority.loadOrCreate();
        }
        if (!redirected) {
            warnIncomplete("the PREROUTING redirect was not fully configured; containers' HTTPS may not reach the proxy.");
            return;
        }
        System.out.println("  MITM proxy configured.");
    }

    /** Whether every command updating the PREROUTING redirect, stale-rule removals included, succeeded. */
    boolean configureMitmProxyFirewalld(String gatewayIp) {
        var rulesOutput = captureOutput("firewall-cmd", "--direct", "--get-all-rules");
        boolean hasRedirect = FirewalldCheck.isPreRoutingRulePresent(rulesOutput, ProxyConfig.DEFAULT_MITM_PORT, gatewayIp);

        boolean ok = true;
        if (hasRedirect) {
            System.out.println("  PREROUTING redirect already configured (" + gatewayIp + ":443 -> "
                    + ProxyConfig.DEFAULT_MITM_PORT + ").");
        } else {
            // Remove stale redirect rule pointing to a previous gateway IP
            var staleIp = FirewalldCheck.extractRedirectGatewayIp(rulesOutput, ProxyConfig.DEFAULT_MITM_PORT);
            if (staleIp != null) {
                System.out.println("  Removing stale PREROUTING redirect (old gateway " + staleIp + ")...");
                ok &= ran("sudo", "firewall-cmd", "--permanent", "--direct",
                        "--remove-rule", "ipv4", "nat", "PREROUTING", "0",
                        "-i", "incusbr0", "-d", staleIp, "-p", "tcp", "--dport",
                        String.valueOf(ProxyConfig.CONTAINER_FACING_PORT),
                        "-j", "REDIRECT", "--to-port",
                        String.valueOf(ProxyConfig.DEFAULT_MITM_PORT));
            }
            System.out.println("  Adding iptables PREROUTING redirect (" + gatewayIp + ":443 -> "
                    + ProxyConfig.DEFAULT_MITM_PORT + " on incusbr0)...");
            ok &= ran("sudo", "firewall-cmd", "--permanent", "--direct",
                    "--add-rule", "ipv4", "nat", "PREROUTING", "0",
                    "-i", "incusbr0", "-d", gatewayIp, "-p", "tcp", "--dport",
                    String.valueOf(ProxyConfig.CONTAINER_FACING_PORT),
                    "-j", "REDIRECT", "--to-port",
                    String.valueOf(ProxyConfig.DEFAULT_MITM_PORT));
            ok &= ran("sudo", "firewall-cmd", "--permanent", "--direct",
                    "--remove-rule", "ipv4", "nat", "PREROUTING", "0",
                    "-i", "incusbr0", "-p", "tcp", "--dport",
                    String.valueOf(ProxyConfig.CONTAINER_FACING_PORT),
                    "-j", "REDIRECT", "--to-port",
                    String.valueOf(ProxyConfig.DEFAULT_MITM_PORT));
            ok &= ran("sudo", "firewall-cmd", "--reload");
        }
        return ok;
    }

    /** Whether the PREROUTING redirect was written and applied. */
    boolean configureMitmProxyUfw(String gatewayIp, String beforeRules) {
        if (beforeRules.isEmpty()) {
            System.err.println("  Warning: could not read /etc/ufw/before.rules. Skipping PREROUTING redirect.");
            return false;
        }
        boolean hasRedirect = UfwCheck.hasPreRoutingRedirect(beforeRules, ProxyConfig.DEFAULT_MITM_PORT, gatewayIp);

        if (hasRedirect) {
            System.out.println("  PREROUTING redirect already configured (" + gatewayIp + ":443 -> "
                    + ProxyConfig.DEFAULT_MITM_PORT + ").");
        } else {
            var staleIp = UfwCheck.extractRedirectGatewayIp(beforeRules, ProxyConfig.DEFAULT_MITM_PORT);
            if (staleIp != null) {
                System.out.println("  Removing stale PREROUTING redirect (old gateway " + staleIp + ")...");
            }
            System.out.println("  Adding PREROUTING redirect (" + gatewayIp + ":443 -> "
                    + ProxyConfig.DEFAULT_MITM_PORT + " on incusbr0) to UFW before.rules...");
            var subnet = CidrUtils.deriveSubnet(gatewayIp);
            var natBlock = UfwCheck.generateNatBlock(gatewayIp, subnet,
                    ProxyConfig.CONTAINER_FACING_PORT, ProxyConfig.DEFAULT_MITM_PORT);
            var content = UfwCheck.insertNatBlock(beforeRules, natBlock);
            var written = writeBeforeRules(content);
            return ran("sudo", "ufw", "reload") && written;
        }
        return true;
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

    private void configureSubuidSubgid() {
        startStep("User Namespace Mappings",
                "Containers use Linux user namespaces to isolate processes.",
                "This configures /etc/subuid and /etc/subgid so the",
                "container's root user maps to an unprivileged UID range",
                "on the host, preventing privilege escalation.");
        boolean changed = false;
        for (var path : java.util.List.of("/etc/subuid", "/etc/subgid")) {
            changed |= ensureSubidEntry(path, "root:1000:1", null);
            // Align with Zabbly Incus packages which set root:1000000:1000000000.
            changed |= ensureSubidEntry(path, "root:1000000:1000000000", "root:1000000:65536");
        }
        if (changed) {
            System.out.println("  Restarting Incus to apply idmap changes...");
            runHost("sudo", "systemctl", "restart", "incus");
        }
        System.out.println("  subuid/subgid configured.");
    }

    enum SubidAction { UNCHANGED, UPDATED, NEEDS_CONFIRMATION }

    record SubidUpdateResult(SubidAction action, String newContent, String conflictingEntry) {
        static SubidUpdateResult unchanged() {
            return new SubidUpdateResult(SubidAction.UNCHANGED, null, null);
        }
        static SubidUpdateResult updated(String newContent) {
            return new SubidUpdateResult(SubidAction.UPDATED, newContent, null);
        }
        static SubidUpdateResult needsConfirmation(String conflictingEntry) {
            return new SubidUpdateResult(SubidAction.NEEDS_CONFIRMATION, null, conflictingEntry);
        }
    }

    static SubidUpdateResult computeSubidUpdate(String content, String entry, String oldEntry) {
        if (content.lines().anyMatch(l -> l.equals(entry))) {
            return SubidUpdateResult.unchanged();
        }

        if (oldEntry != null && content.lines().anyMatch(l -> l.equals(oldEntry))) {
            return SubidUpdateResult.updated(replaceSubidLine(content, oldEntry, entry));
        }

        var prefix = entry.substring(0, entry.lastIndexOf(':') + 1);
        var existing = content.lines().filter(l -> l.startsWith(prefix)).findFirst();

        if (existing.isEmpty()) {
            String appended = content.endsWith("\n") ? content + entry + "\n" : content + "\n" + entry + "\n";
            return SubidUpdateResult.updated(appended);
        }

        String[] entryParts = entry.split(":");
        long neededBase = Long.parseLong(entryParts[1]);
        long neededCount = Long.parseLong(entryParts[2]);
        if (subidRangeCovers(existing.get(), entryParts[0], neededBase, neededCount)) {
            return SubidUpdateResult.unchanged();
        }

        return SubidUpdateResult.needsConfirmation(existing.get());
    }

    static String replaceSubidLine(String content, String oldLine, String newLine) {
        return content.replaceAll("(?m)^" + Pattern.quote(oldLine) + "$", Matcher.quoteReplacement(newLine));
    }

    private boolean ensureSubidEntry(String path, String entry, String oldEntry) {
        String content;
        try {
            content = Files.readString(java.nio.file.Path.of(path));
        } catch (IOException e) {
            System.err.println("  Warning: could not read " + path + ": " + e.getMessage());
            return false;
        }

        var result = computeSubidUpdate(content, entry, oldEntry);

        return switch (result.action()) {
            case UNCHANGED -> false;
            case UPDATED -> writeSubidFile(path, result.newContent());
            case NEEDS_CONFIRMATION -> {
                System.err.println();
                System.err.println("  " + path + " contains an unexpected entry: " + result.conflictingEntry());
                System.err.println("  incus-spawn expects: " + entry);
                var console = System.console();
                if (console != null) {
                    if (askConfirmation(console, System.err, "  " + styled(BOLD + YELLOW, "Replace it?"), false)) {
                        yield writeSubidFile(path,
                                replaceSubidLine(content, result.conflictingEntry(), entry));
                    }
                }
                System.err.println("  Skipped \u2014 containers may not start correctly.");
                yield false;
            }
        };
    }

    private boolean writeSubidFile(String path, String content) {
        var tmpPath = path + ".isx-tmp";
        int exitCode = runHost("sh", "-c",
                "printf '%s' '" + content.replace("'", "'\\''") + "' | sudo tee " + tmpPath + " > /dev/null"
                        + " && sudo chmod --reference=" + path + " " + tmpPath
                        + " && sudo mv " + tmpPath + " " + path);
        if (exitCode != 0) {
            System.err.println("  Warning: failed to write " + path);
            runHostCapturingExit("sudo", "rm", "-f", tmpPath);
            return false;
        }
        return true;
    }

    static boolean subidRangeCovers(String line, String user, long base, long count) {
        String[] parts = line.split(":");
        if (parts.length < 3 || !parts[0].equals(user)) return false;
        try {
            long existingBase = Long.parseLong(parts[1].trim());
            long existingCount = Long.parseLong(parts[2].trim());
            return existingBase <= base && existingBase + existingCount >= base + count;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void initializeIncus() {
        startStep("Storage & Network",
                "Initializes the Incus daemon with a network bridge and",
                "storage pool. If no copy-on-write (btrfs) pool exists,",
                "one is created — enabling instant, space-efficient clones",
                "when you branch containers.");

        // Check if we can talk to the Incus daemon
        var connectivity = incus.checkConnectivity();
        if (connectivity != null) {
            if (connectivity.contains("not running") || connectivity.contains("Connection refused")
                    || connectivity.contains("not accepting connections")) {
                System.out.println();
                System.out.println("  Cannot connect to the Incus daemon — it does not appear to be running.");
                System.out.println("  Enable and start it with:");
                System.out.println("    sudo systemctl enable --now incus");
                System.out.println("  Then re-run 'isx init' to continue.");
                System.exit(1);
            } else if (connectivity.contains("permission denied") || connectivity.contains("newgrp")) {
                System.out.println();
                System.out.println("  Cannot connect to the Incus daemon.");
                System.out.println("  This usually means the 'incus-admin' group membership is not active in this shell.");
                System.out.println();
                System.out.println("  Please do one of the following:");
                System.out.println("    - Run: newgrp incus-admin");
                System.out.println("    - Or log out and log back in");
                System.out.println("  Then re-run 'isx init' to continue.");
                System.exit(1);
            }
            // Unknown error — continue anyway (daemon may start during init)
        }

        // Skip admin init only if Incus genuinely already has a storage pool. Note: probeCowPool()
        // .listed() means "the list call succeeded", NOT "a pool exists" — using it here skipped
        // admin init on every fresh daemon, leaving no default profile / no incusbr0 bridge.
        if (incus.hasStoragePool()) {
            System.out.println("  Incus already initialized.");
        } else {
            var exitCode = runHost("sudo", "incus", "admin", "init", "--minimal");
            if (exitCode == 0) {
                System.out.println("  Incus initialized with default storage pool and network.");
            } else {
                System.err.println("  Warning: Incus initialization may have failed. Check 'incus storage list'.");
            }
        }

        // 'incus admin init --minimal' normally creates the incusbr0 bridge, but on some
        // distros/versions it doesn't — and when a storage pool already exists we skip the
        // admin init entirely, so the bridge may never be created. Later steps (bridge subnet
        // check, MITM proxy) hard-fail without it. Ensure it exists here; this is a no-op when
        // the bridge is already present, and checkBridgeSubnet() fixes any VPN subnet conflict.
        if (incus.createBridgeIfMissing("incusbr0", VmManager.gatewayIp())) {
            System.out.println("  Bridge 'incusbr0' was missing — created it ("
                    + VmManager.gatewayIp() + "/24).");
        }

        checkStorageDriver();
        configureBtrfsUsageAccess();
        ensureDefaultProfile();
        checkBridgeSubnet();
    }

    private static final String BTRFS_SUDOERS = "/etc/sudoers.d/incus-spawn-btrfs";

    /**
     * Install a tightly-scoped NOPASSWD sudoers rule so the (non-root) TUI/build can read btrfs
     * <em>referenced</em> sizes for the CoW pool — the data behind per-template disk deltas (see
     * {@link dev.incusspawn.incus.BtrfsUsage}). Reading qgroups needs CAP_SYS_ADMIN and the pool dir
     * is root-only, and Incus exposes no API for rfer, so a privileged read is unavoidable; a
     * password prompt on TUI refresh isn't acceptable, hence NOPASSWD. The rule permits ONLY the
     * read-only qgroup/subvolume listings plus {@code quota rescan} (rebuilds accounting counters,
     * never touches data) against this pool's mount — no wildcards, no other btrfs subcommands.
     *
     * <p>Linux only (on macOS the pool lives in the appliance VM and the in-VM agent reads it as
     * root). Best-effort: if it can't be installed, rfer stamping simply fails and the TUI falls
     * back to exclusive-usage display.
     */
    private void configureBtrfsUsageAccess() {
        if (Platform.isMacOS()) return;
        var probe = incus.probeCowPool();
        if (probe.poolName() == null || !probe.isBtrfs()) return;
        var pool = probe.poolName();

        var btrfsPath = captureOutput("which", "btrfs").strip();
        if (btrfsPath.isEmpty()) btrfsPath = "/usr/sbin/btrfs";     // fall back to the usual location

        var user = System.getProperty("user.name");
        var mount = "/var/lib/incus/storage-pools/" + pool;
        // Permit both flavours of the qgroup read: the plain form (cheap, for periodic sampling) and
        // the --sync form (forces a commit, for the accuracy-critical read right after a build). sudo
        // matches the argument vector exactly, so each form needs its own entry. `quota rescan` is
        // the one non-read: it only rebuilds the accounting counters (never touches data) and is how
        // isx repairs the `inconsistent` state that would otherwise freeze every size it shows.
        var content = user + " ALL=(root) NOPASSWD: "
                + btrfsPath + " qgroup show -re --raw " + mount + ", "
                + btrfsPath + " qgroup show -re --raw --sync " + mount + ", "
                + btrfsPath + " subvolume list " + mount + ", "
                + btrfsPath + " quota rescan " + mount + "\n";

        try {
            if (Files.exists(Path.of(BTRFS_SUDOERS))
                    && content.equals(Files.readString(Path.of(BTRFS_SUDOERS)))) {
                return;                                             // already installed, up to date
            }
        } catch (IOException ignored) {
            // unreadable (root-owned 0440) — fall through and (re)install
        }

        try {
            var tempFile = Files.createTempFile("isx-btrfs-sudoers-", ".tmp");
            Files.writeString(tempFile, content);
            // Validate before installing: a malformed sudoers file can lock the user out of sudo.
            if (runHostQuiet("visudo", "-cf", tempFile.toString()) != 0) {
                System.err.println("  Warning: generated btrfs sudoers rule failed validation; skipping.");
                Files.deleteIfExists(tempFile);
                return;
            }
            if (runHostQuiet("sudo", "install", "-m", "0440", "-o", "root", "-g", "root",
                    tempFile.toString(), BTRFS_SUDOERS) == 0) {
                System.out.println("  Enabled per-template disk accounting (scoped btrfs read).");
            }
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            System.err.println("  Warning: could not configure btrfs disk accounting: " + e.getMessage());
        }
    }

    /**
     * The default profile gets its root disk and NIC from 'incus admin init --minimal', which is
     * skipped whenever a storage pool already exists — and if it failed, checkStorageDriver() and
     * createBridgeIfMissing() still produce a pool and a bridge, so init looked like it succeeded
     * while every instance creation failed with "No root device could be found". Repair it here,
     * after the pool and bridge are known to exist.
     */
    private void ensureDefaultProfile() {
        // Never guess a pool name here. A root disk pointing at a pool that does not exist leaves
        // the profile worse than an empty one, and "default" is not guaranteed to be present.
        var pool = incus.findUsablePool();
        if (pool == null) {
            System.err.println("  Warning: no storage pool found — skipping the default profile check.");
            return;
        }
        try {
            var added = incus.ensureDefaultProfileDevices(pool, "incusbr0");
            if (!added.isEmpty()) {
                System.out.println("  Default profile was incomplete — added " + added
                        + " (root disk on pool '" + pool + "').");
            }
            upgradeProfilePoolIfNeeded(pool);
        } catch (Exception e) {
            System.err.println("  Warning: could not repair the default profile: " + e.getMessage());
            System.err.println("  If instance creation fails with 'No root device could be found', run:");
            System.err.println("    incus profile device add default root disk path=/ pool=" + pool);
            System.err.println("  If containers come up without networking, also run:");
            System.err.println("    incus profile device add default eth0 nic network=incusbr0");
        }
    }

    private void upgradeProfilePoolIfNeeded(String desiredPool) {
        var devices = incus.profileDevices("default");
        var currentPool = IncusClient.rootDiskPoolFromDevices(devices);
        if (currentPool == null || currentPool.equals(desiredPool)) return;

        var pools = incus.listPools();
        var currentDriver = pools.getOrDefault(currentPool, "");
        var desiredDriver = pools.getOrDefault(desiredPool, "");
        if (IncusClient.isCowDriver(currentDriver) || !IncusClient.isCowDriver(desiredDriver)) return;

        if (incus.updateProfileRootDiskPool("default", devices, desiredPool)) {
            System.out.println("  Default profile root disk upgraded from pool '" + currentPool
                    + "' (" + currentDriver + ") to '" + desiredPool + "' (" + desiredDriver + ").");
        }
    }

    /**
     * The Incus API extension that added {@code btrfs.create_options} (Incus 7.1). Older daemons,
     * such as the 6.x in Fedora's and Ubuntu's repositories, reject the whole pool creation over
     * the unknown key (#820).
     */
    static final String STORAGE_CREATE_OPTIONS_EXTENSION = "storage_create_options";

    /**
     * @param skipTrim pass {@code -K} to mkfs.btrfs, skipping the whole-device TRIM that takes
     *                 minutes on a loop device backed by a sparse file on ext4. Only when the
     *                 daemon supports {@code btrfs.create_options}.
     */
    static String[] cowPoolCreateCommand(boolean skipTrim) {
        var cmd = new ArrayList<>(List.of("sudo", "incus", "storage", "create", "cow", "btrfs",
                "size=100GiB"));
        if (skipTrim) cmd.add("btrfs.create_options=-K");
        return cmd.toArray(String[]::new);
    }

    /**
     * Why creating the CoW pool failed, worded after Incus's own error rather than a guess:
     * missing loop device support is only one cause, and naming it for any other sends the user
     * after the wrong fix.
     */
    static List<String> cowPoolFailureExplanation(String incusError) {
        var lines = new ArrayList<String>();
        var error = incusError == null ? "" : incusError.strip();
        error.lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(lines::add);
        var lower = error.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("loop")) {
            lines.add("This is expected inside containers or VMs without loop device");
            lines.add("support. On bare metal, ensure the 'loop' kernel module is");
            lines.add("loaded (sudo modprobe loop) and try again.");
        } else if (lower.contains("mkfs.btrfs") || lower.contains("btrfs-progs")) {
            lines.add("Install btrfs-progs on the host and try again.");
        }
        return lines;
    }

    private void checkStorageDriver() {
        var cowProbe = incus.probeCowPool();
        // Guard against transient/permission/daemon errors: if we can't list pools, don't
        // misinterpret that as "no CoW pool" and spuriously try to create one.
        if (!cowProbe.listed()) return;
        var anyCow = cowProbe.poolName() != null;

        if (!anyCow) {
            System.out.println("  No copy-on-write storage pool detected. Creating one...");
            runHostQuiet("sudo", "mkdir", "-p", "/var/lib/incus/disks");
            var create = runHostCapturingStderr(cowPoolCreateCommand(
                    incus.hasApiExtension(STORAGE_CREATE_OPTIONS_EXTENSION)));
            if (create.exitCode() == 0) {
                System.out.println("  Created btrfs storage pool 'cow' (100 GiB, thin-provisioned).");
                System.out.println("  Resize with: sudo incus storage set cow size=200GiB");
                System.out.println("  All new instances will use it automatically.");
            } else {
                System.out.println();
                var warning = BOLD + YELLOW;
                System.err.println(styled(warning, "  ╔══════════════════════════════════════════════════════════════╗"));
                System.err.println(styled(warning, "  ║  WARNING: Failed to create btrfs storage pool!             ║"));
                System.err.println(styled(warning, "  ╚══════════════════════════════════════════════════════════════╝"));
                System.err.println();
                for (var line : cowPoolFailureExplanation(create.stderr())) {
                    System.err.println("  " + styled(YELLOW, line));
                }
                System.err.println();
                System.err.println("  " + styled(YELLOW, "Without a CoW pool, clones and branches will be FULL COPIES,"));
                System.err.println("  " + styled(YELLOW, "using significantly more disk space and taking longer to create."));
                System.err.println();
                System.err.println("  You can create one manually later:");
                System.err.println("    " + styled(BOLD, "sudo incus storage create cow btrfs size=100GiB"));
                System.err.println("  incus-spawn will automatically use it for all new instances.");
                System.err.println();

                var console = System.console();
                if (console != null) {
                    if (!askConfirmation(console, System.err,
                            "  " + styled(BOLD + YELLOW, "Continue without CoW storage?"), false)) {
                        System.out.println("  Aborted. Re-run 'isx init' after creating a CoW storage pool.");
                        System.exit(0);
                    }
                }
            }
        }
    }

    private void checkBridgeSubnet() {
        System.out.println("  Checking bridge subnet for VPN conflicts...");
        try {
            var result = BridgeSubnetCheck.detectAndFix(incus);
            if (result.conflictDetected()) {
                System.out.println("  Detected subnet conflict: bridge " + result.oldSubnet()
                        + " overlaps with route: " + result.conflictingRoute());
                if (result.newSubnet() != null) {
                    System.out.println("  Reconfigured bridge to " + result.newSubnet()
                            + " to avoid conflict.");
                    var migrated = InstanceLifecycle.migrateAllInstancesToNewSubnet(incus);
                    if (migrated > 0) {
                        System.out.println("  Migrated network config for " + migrated
                                + " instance" + (migrated == 1 ? "" : "s") + ".");
                    }
                } else {
                    System.err.println("  Warning: could not find a non-conflicting subnet.");
                    System.err.println("  You may need to manually set the bridge address:");
                    System.err.println("    incus network set incusbr0 ipv4.address 172.20.0.1/24");
                }
            } else {
                System.out.println("  Bridge subnet is clear of VPN route conflicts.");
            }
        } catch (Exception e) {
            System.err.println("  Warning: could not check bridge subnet: " + e.getMessage());
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

    private int runHost(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.inheritIO();
            return pb.start().waitFor();
        } catch (IOException | InterruptedException e) {
            System.err.println("  Failed to run: " + String.join(" ", command) + ": " + e.getMessage());
            return 1;
        }
    }

    private record HostResult(int exitCode, String stderr) {}

    /** Run a host command with stdout shown, returning stderr so the caller can word the failure. */
    private HostResult runHostCapturingStderr(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            var process = pb.start();
            var stderr = new String(process.getErrorStream().readAllBytes());
            return new HostResult(process.waitFor(), stderr);
        } catch (IOException | InterruptedException e) {
            return new HostResult(1, "Failed to run: " + String.join(" ", command) + ": " + e.getMessage());
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
