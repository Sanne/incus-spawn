package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.CidrUtils;
import dev.incusspawn.incus.FirewalldCheck;
import dev.incusspawn.incus.FirewallDetector;
import dev.incusspawn.incus.FirewallDetector.DetectionResult;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.UfwCheck;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.ToolProxyResolver;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.vm.VmManager;
import dev.incusspawn.Platform;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import java.io.Console;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

@CommandDefinition(
        name = "init",
        description = "One-time host setup: install Incus, configure auth, test connectivity",
        generateHelp = true
)
public class InitCommand extends BaseCommand {

    private static final ObjectMapper JSON = new ObjectMapper();

    private IncusClient incus;
    private boolean useUfw;

    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String GREEN_BOLD = "\u001B[1;32m";
    private static final String RESET = "\u001B[0m";
    private static final int BOX_WIDTH = 62;
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;]*m");
    private static final String BORDER_H = "─".repeat(BOX_WIDTH);
    private static final String TOP_BORDER = CYAN + "╭" + BORDER_H + "╮" + RESET;
    private static final String BOT_BORDER = CYAN + "╰" + BORDER_H + "╯" + RESET;

    private static final String[] DNS_HINT = {
            "Configures the Incus bridge network so that containers",
            "resolve intercepted domains (GitHub, Anthropic, etc.) to",
            "the proxy gateway. This lets the MITM proxy transparently",
            "inject credentials into HTTPS requests without containers",
            "needing any special network configuration."
    };

    private int totalSteps;
    private int currentStep;
    private HttpClient httpClient;

    // Not a static final: GraalVM native-image would capture it at build time.
    // HTTP/1.1: Java's default HTTP/2 ALPN negotiation can cause the first TLS
    // request in a native-image JVM to fail, then succeed on retry.
    private HttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        }
        return httpClient;
    }

    private void closeHttpClient() {
        if (httpClient != null) {
            httpClient.close();
            httpClient = null;
        }
    }

    private static String pad(String s, int width) {
        int vlen = ANSI_PATTERN.matcher(s).replaceAll("").length();
        if (vlen >= width) return s;
        return s + " ".repeat(width - vlen);
    }

    private static String boxLine(String content) {
        return CYAN + "│" + RESET + pad(content, BOX_WIDTH) + CYAN + "│" + RESET;
    }

    private static void printBanner(String title, String subtitle, String info) {
        System.out.println();
        System.out.println(TOP_BORDER);
        System.out.println(boxLine(""));
        System.out.println(boxLine("   " + BOLD + title + RESET));
        System.out.println(boxLine("   " + subtitle));
        System.out.println(boxLine(""));
        System.out.println(boxLine("   " + DIM + info + RESET));
        System.out.println(boxLine(""));
        System.out.println(BOT_BORDER);
        System.out.println();
    }

    private void startStep(String title, String... hintLines) {
        currentStep++;
        String left = "  " + currentStep + "  " + title;
        String right = "[" + currentStep + "/" + totalSteps + "]  ";
        int gap = BOX_WIDTH - left.length() - right.length();

        System.out.println();
        System.out.println(TOP_BORDER);
        System.out.println(CYAN + "│" + RESET + BOLD + left + RESET
                + " ".repeat(Math.max(1, gap)) + DIM + right + RESET + CYAN + "│" + RESET);
        System.out.println(BOT_BORDER);

        if (hintLines.length > 0) {
            for (var line : hintLines) {
                System.out.println(CYAN + "  ┃ " + RESET + DIM + line + RESET);
            }
            System.out.println();
        }
    }

    private static void printCompletionBox(String... lines) {
        System.out.println();
        System.out.println(TOP_BORDER);
        System.out.println(boxLine(""));
        for (var line : lines) {
            System.out.println(boxLine(line));
        }
        System.out.println(boxLine(""));
        System.out.println(BOT_BORDER);
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
        System.out.println("\u001B[1;33m  First-time setup required.\u001B[0m");
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

    private static void markInitComplete() {
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
            System.err.println("\u001B[1;31m  incus-spawn requires Linux.\u001B[0m");
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
        System.err.println("\u001B[1;31m  incus-spawn requires Linux or macOS.\u001B[0m");
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
        totalSteps = 13;
        currentStep = 0;
        printBanner("incus-spawn — First-Time Setup",
                "Configuring your isolated development environment",
                "~3 minutes · some steps require sudo");

        System.out.println();
        System.out.println("  Several steps need " + BOLD + "sudo" + RESET + " to install packages, configure");
        System.out.println("  the firewall, and set up user namespace mappings.");
        System.out.println();
        if (runHost("sudo", "-v") != 0) {
            System.err.println("  sudo authentication failed. Please ensure you have sudo access and try again.");
            return CommandResult.valueOf(1);
        }

        installDependencies();
        checkIncusInstalled();
        configureSubuidSubgid();
        initializeIncus();
        checkBridgeSubnet();
        configureFirewall();
        configureMitmProxy();
        setupSshKeyPair();
        var loader = new ToolDefLoader();
        var allTools = loader.allToolSetups();
        var credentials = selectCredentials(allTools);
        totalSteps = 10 + credentials.size();
        for (var toolName : credentials) {
            switch (toolName) {
                case "claude" -> setupClaudeAuth();
                case "gh" -> setupGitHubAuth();
                case "bob" -> setupBobAuth();
                case "codex" -> setupCodexAuth();
                default -> setupGenericToolCredentials(toolName, allTools.get(toolName));
            }
        }
        closeHttpClient();
        setupSearchPaths();
        setupHostPaths();

        installGitRemoteShim();

        startStep("DNS Configuration", DNS_HINT);
        ProxyConfig.configureBridgeDns(incus);

        startStep("Proxy Service",
                "The MITM proxy intercepts HTTPS traffic from containers",
                "and injects real credentials (API keys, tokens) so that",
                "containers only ever hold placeholder values. Installing",
                "it as a systemd service means it starts automatically on",
                "boot — otherwise you'll need to run 'isx proxy start'",
                "before launching containers.");
        boolean proxyServiceInstalled = offerProxyService();

        var proxyStep = proxyServiceInstalled
                ? "   2. Proxy is running as a systemd service"
                : "   2. Start the auth proxy:  isx proxy start";
        markInitComplete();
        printCompletionBox(
                "   " + GREEN_BOLD + "✓" + RESET + BOLD + " Setup complete!" + RESET,
                "",
                "   " + BOLD + "Next steps:" + RESET,
                "   1. Build a template:      isx build tpl-java",
                proxyStep,
                "   3. Launch the TUI:        isx");
        return CommandResult.SUCCESS;
    }

    private CommandResult doMacOsInit() throws Exception {
        totalSteps = 10;
        currentStep = 0;
        printBanner("incus-spawn — First-Time Setup (macOS)",
                "Configuring your isolated development environment",
                "~2 minutes");

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

        setupSshKeyPair();
        var macLoader = new ToolDefLoader();
        var macTools = macLoader.allToolSetups();
        var macCredentials = selectCredentials(macTools);
        totalSteps = 7 + macCredentials.size();
        for (var toolName : macCredentials) {
            switch (toolName) {
                case "claude" -> setupClaudeAuth();
                case "gh" -> setupGitHubAuth();
                case "bob" -> setupBobAuth();
                case "codex" -> setupCodexAuth();
                default -> setupGenericToolCredentials(toolName, macTools.get(toolName));
            }
        }
        closeHttpClient();
        setupSearchPaths();
        setupHostPaths();

        installGitRemoteShim();

        startStep("DNS Configuration", DNS_HINT);
        ProxyConfig.configureBridgeDns(incus);

        startStep("macOS Services",
                "Installs the Incus VM and MITM proxy as macOS launch",
                "agents so they start automatically on login and survive",
                "reboots. Without this you'll need to manually run",
                "'isx vm start' and 'isx proxy start' before launching",
                "containers.");
        offerMacOsServices();

        markInitComplete();
        printCompletionBox(
                "   " + GREEN_BOLD + "✓" + RESET + BOLD + " Setup complete!" + RESET,
                "",
                "   " + BOLD + "Next steps:" + RESET,
                "   1. Build a template:  isx build tpl-java",
                "   2. Launch the TUI:    isx");
        return CommandResult.SUCCESS;
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

    private static boolean commandExists(String command) {
        try {
            var pb = new ProcessBuilder("which", command);
            pb.redirectErrorStream(true);
            return pb.start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
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
                System.out.println("  Install Incus manually (see https://linuxcontainers.org/incus/docs/main/installing/), then run:");
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
                        System.out.println("  Restarting proxy service so it picks up the restored firewall rules...");
                        ProxyService.restart();
                    }
                }
                configureFirewalld();
            }
            case DetectionResult.UseUfw u -> {
                useUfw = true;
                configureUfw();
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

    private void configureFirewalld() {
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
        if (!hasMasquerade) {
            System.out.println("  Enabling masquerading (NAT) for container internet access...");
            runHostQuiet("sudo", "firewall-cmd", "--zone=trusted", "--add-masquerade", "--permanent");
        }
        if (!hasForwardIn) {
            System.out.println("  Adding FORWARD rules for Incus bridge (Docker coexistence)...");
            runHostQuiet("sudo", "firewall-cmd", "--permanent", "--direct",
                    "--add-rule", "ipv4", "filter", "FORWARD", "0",
                    "-i", "incusbr0", "-j", "ACCEPT");
        }
        if (!hasForwardOut) {
            if (hasForwardIn) {
                System.out.println("  Adding FORWARD rules for Incus bridge (Docker coexistence)...");
            }
            runHostQuiet("sudo", "firewall-cmd", "--permanent", "--direct",
                    "--add-rule", "ipv4", "filter", "FORWARD", "0",
                    "-o", "incusbr0", "-m", "conntrack", "--ctstate", "RELATED,ESTABLISHED", "-j", "ACCEPT");
        }

        var reloadResult = runHostQuiet("sudo", "firewall-cmd", "--reload");
        if (reloadResult != 0) {
            System.err.println("  Warning: firewall reload failed. Run: sudo firewall-cmd --reload");
            return;
        }
        System.out.println("  Firewall configured: incusbr0 in trusted zone with masquerading (firewalld).");
    }

    private void configureUfw() {
        var gatewayIp = ProxyConfig.resolveGatewayIp(incus);
        var subnet = CidrUtils.deriveSubnet(gatewayIp);

        var beforeRules = UfwCheck.readBeforeRules();
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
        runHostQuiet("sudo", "ufw", "allow", "in", "on", "incusbr0");

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
            writeBeforeRules(content);
            var reloadResult = runHostQuiet("sudo", "ufw", "reload");
            if (reloadResult != 0) {
                System.err.println("  Warning: UFW reload failed. Run: sudo ufw reload");
                return;
            }
        }
        System.out.println("  Firewall configured: incusbr0 trusted with masquerading (UFW).");
    }

    private void writeBeforeRules(String content) {
        try {
            var tempFile = java.nio.file.Files.createTempFile("isx-before-rules-", ".tmp");
            java.nio.file.Files.writeString(tempFile, content);
            runHostQuiet("sudo", "cp", tempFile.toString(), UfwCheck.BEFORE_RULES.toString());
            java.nio.file.Files.deleteIfExists(tempFile);
        } catch (java.io.IOException e) {
            System.err.println("  Error writing before.rules: " + e.getMessage());
        }
    }

    private void configureNetworkManager() {
        var confDir = Path.of("/etc/NetworkManager/conf.d");
        if (!Files.isDirectory(confDir)) return;
        var confFile = confDir.resolve("99-unmanaged-veth.conf");
        if (Files.exists(confFile)) return;
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("isx-nm-veth-", ".conf");
            Files.writeString(tempFile,
                    "[keyfile]\nunmanaged-devices=interface-name:veth*\n");
            if (runHostQuiet("sudo", "cp", tempFile.toString(), confFile.toString()) != 0) return;
            runHostQuiet("sudo", "nmcli", "general", "reload");
            System.out.println("  Configured NetworkManager to ignore veth devices.");
        } catch (IOException e) {
            System.err.println("  Warning: could not configure NetworkManager: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    private static final String SYSCTL_CONF = "/etc/sysctl.d/99-incus-spawn.conf";

    private void configureInotifyLimits() {
        var sysctlPath = Path.of(SYSCTL_CONF);
        var content = """
                # All containers share one host UID range, so they draw on the same
                # per-UID inotify budget.  The kernel default (128 instances) runs out
                # around the tenth concurrent container and the next one's systemd dies
                # before it can log anything.
                fs.inotify.max_user_instances=8192
                fs.inotify.max_user_watches=524288
                """;
        try {
            if (Files.exists(sysctlPath)) {
                var existing = Files.readString(sysctlPath);
                var matcher = Pattern.compile("max_user_instances\\s*=\\s*(\\d+)").matcher(existing);
                if (matcher.find()) {
                    int current = Integer.parseInt(matcher.group(1));
                    if (current >= 8192) {
                        return;
                    }
                }
            }
            var tempFile = Files.createTempFile("isx-sysctl-", ".conf");
            Files.writeString(tempFile, content);
            if (runHostQuiet("sudo", "cp", tempFile.toString(), SYSCTL_CONF) == 0) {
                runHostQuiet("sudo", "sysctl", "-p", SYSCTL_CONF);
                System.out.println("  Raised inotify limits for concurrent containers.");
            }
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            System.err.println("  Warning: could not configure inotify limits: " + e.getMessage());
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

        if (useUfw) {
            configureMitmProxyUfw(gatewayIp);
        } else {
            configureMitmProxyFirewalld(gatewayIp);
        }

        configureInotifyLimits();

        // Generate CA certificate if it doesn't exist
        if (CertificateAuthority.exists()) {
            System.out.println("  MITM CA certificate already exists.");
        } else {
            CertificateAuthority.loadOrCreate();
        }
        System.out.println("  MITM proxy configured.");
    }

    private void configureMitmProxyFirewalld(String gatewayIp) {
        var rulesOutput = captureOutput("firewall-cmd", "--direct", "--get-all-rules");
        boolean hasRedirect = FirewalldCheck.isPreRoutingRulePresent(rulesOutput, ProxyConfig.DEFAULT_MITM_PORT, gatewayIp);

        if (hasRedirect) {
            System.out.println("  PREROUTING redirect already configured (" + gatewayIp + ":443 -> "
                    + ProxyConfig.DEFAULT_MITM_PORT + ").");
        } else {
            // Remove stale redirect rule pointing to a previous gateway IP
            var staleIp = FirewalldCheck.extractRedirectGatewayIp(rulesOutput, ProxyConfig.DEFAULT_MITM_PORT);
            if (staleIp != null) {
                System.out.println("  Removing stale PREROUTING redirect (old gateway " + staleIp + ")...");
                runHostQuiet("sudo", "firewall-cmd", "--permanent", "--direct",
                        "--remove-rule", "ipv4", "nat", "PREROUTING", "0",
                        "-i", "incusbr0", "-d", staleIp, "-p", "tcp", "--dport",
                        String.valueOf(ProxyConfig.CONTAINER_FACING_PORT),
                        "-j", "REDIRECT", "--to-port",
                        String.valueOf(ProxyConfig.DEFAULT_MITM_PORT));
            }
            System.out.println("  Adding iptables PREROUTING redirect (" + gatewayIp + ":443 -> "
                    + ProxyConfig.DEFAULT_MITM_PORT + " on incusbr0)...");
            runHostQuiet("sudo", "firewall-cmd", "--permanent", "--direct",
                    "--add-rule", "ipv4", "nat", "PREROUTING", "0",
                    "-i", "incusbr0", "-d", gatewayIp, "-p", "tcp", "--dport",
                    String.valueOf(ProxyConfig.CONTAINER_FACING_PORT),
                    "-j", "REDIRECT", "--to-port",
                    String.valueOf(ProxyConfig.DEFAULT_MITM_PORT));
            runHostQuiet("sudo", "firewall-cmd", "--permanent", "--direct",
                    "--remove-rule", "ipv4", "nat", "PREROUTING", "0",
                    "-i", "incusbr0", "-p", "tcp", "--dport",
                    String.valueOf(ProxyConfig.CONTAINER_FACING_PORT),
                    "-j", "REDIRECT", "--to-port",
                    String.valueOf(ProxyConfig.DEFAULT_MITM_PORT));
            runHostQuiet("sudo", "firewall-cmd", "--reload");
        }
    }

    private void configureMitmProxyUfw(String gatewayIp) {
        var beforeRules = UfwCheck.readBeforeRules();
        if (beforeRules.isEmpty()) {
            System.err.println("  Warning: could not read /etc/ufw/before.rules. Skipping PREROUTING redirect.");
            return;
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
            writeBeforeRules(content);
            runHostQuiet("sudo", "ufw", "reload");
        }
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
                    if (askConfirmation(console, System.err, "  \u001B[1;33mReplace it?\u001B[0m", false)) {
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
        ensureDefaultProfile();
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
        } catch (Exception e) {
            System.err.println("  Warning: could not repair the default profile: " + e.getMessage());
            System.err.println("  If instance creation fails with 'No root device could be found', run:");
            System.err.println("    incus profile device add default root disk path=/ pool=" + pool);
            System.err.println("  If containers come up without networking, also run:");
            System.err.println("    incus profile device add default eth0 nic network=incusbr0");
        }
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
            var createResult = runHost("sudo", "incus", "storage", "create", "cow", "btrfs", "size=100GiB");
            if (createResult == 0) {
                System.out.println("  Created btrfs storage pool 'cow' (100 GiB, thin-provisioned).");
                System.out.println("  Resize with: sudo incus storage set cow size=200GiB");
                System.out.println("  All new instances will use it automatically.");
            } else {
                System.out.println();
                System.err.println("\u001B[1;33m  ╔══════════════════════════════════════════════════════════════╗");
                System.err.println("  ║  WARNING: Failed to create btrfs storage pool!             ║");
                System.err.println("  ╚══════════════════════════════════════════════════════════════╝\u001B[0m");
                System.err.println();
                System.err.println("  \u001B[33mThis is expected inside containers or VMs without loop device");
                System.err.println("  support. On bare metal, ensure the 'loop' kernel module is");
                System.err.println("  loaded (sudo modprobe loop) and try again.\u001B[0m");
                System.err.println();
                System.err.println("  \u001B[33mWithout a CoW pool, clones and branches will be FULL COPIES,");
                System.err.println("  using significantly more disk space and taking longer to create.\u001B[0m");
                System.err.println();
                System.err.println("  You can create one manually later:");
                System.err.println("    \u001B[1msudo incus storage create cow btrfs size=100GiB\u001B[0m");
                System.err.println("  incus-spawn will automatically use it for all new instances.");
                System.err.println();

                var console = System.console();
                if (console != null) {
                    if (!askConfirmation(console, System.err,
                            "  \u001B[1;33mContinue without CoW storage?\u001B[0m", false)) {
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
                        System.out.println("  Note: running instances may need a restart"
                                + " for network changes to take effect.");
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

    private static final List<String> KNOWN_TOOL_ORDER = List.of("claude", "gh", "bob", "codex");

    private List<String> selectCredentials(Map<String, ToolSetup> allTools) {
        startStep("Credential Setup",
                "Choose which API credentials to configure. Each credential",
                "stays on your host — containers only hold placeholders, and",
                "the MITM proxy injects real values transparently.");
        var config = SpawnConfig.load();
        var console = System.console();
        if (console == null) {
            return List.of();
        }

        var configTree = JSON.valueToTree(config);

        var toolsWithProxy = new ArrayList<Map.Entry<String, ToolSetup>>();
        for (var entry : allTools.entrySet()) {
            var tool = entry.getValue();
            if (tool.feature() != null && !config.isFeatureEnabled(tool.feature())) continue;
            var proxyDef = tool.proxy();
            if (proxyDef == null || proxyDef.getConfiguration().isEmpty()) continue;
            toolsWithProxy.add(entry);
        }

        if (toolsWithProxy.isEmpty()) {
            System.out.println("  No tools with proxy credentials found.");
            return List.of();
        }

        toolsWithProxy.sort(Comparator.<Map.Entry<String, ToolSetup>, Integer>comparing(
                        e -> {
                            int idx = KNOWN_TOOL_ORDER.indexOf(e.getKey());
                            return idx >= 0 ? idx : Integer.MAX_VALUE;
                        })
                .thenComparing(Map.Entry::getKey));

        System.out.println("  Which credentials do you want to configure?");
        for (int i = 0; i < toolsWithProxy.size(); i++) {
            var entry = toolsWithProxy.get(i);
            var toolName = entry.getKey();
            var tool = entry.getValue();
            var desc = tool.description().isBlank() ? toolName : tool.description();
            var tag = isToolConfigured(toolName, tool, config, configTree) ? " [configured]" : "";
            System.out.println("    " + (i + 1) + ". " + desc + tag);
        }
        System.out.println();
        System.out.print("  Enter numbers separated by commas, 'all', or press Enter to skip: ");
        var input = console.readLine();
        if (input == null || input.isBlank()) {
            System.out.println("  Skipped credential setup.");
            return List.of();
        }

        var selected = new ArrayList<String>();
        if (input.strip().equalsIgnoreCase("all")) {
            for (var entry : toolsWithProxy) selected.add(entry.getKey());
        } else {
            for (var part : input.split(",")) {
                try {
                    int idx = Integer.parseInt(part.strip()) - 1;
                    if (idx >= 0 && idx < toolsWithProxy.size()) {
                        var name = toolsWithProxy.get(idx).getKey();
                        if (!selected.contains(name)) selected.add(name);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return selected;
    }

    private static boolean isToolConfigured(String toolName, ToolSetup tool,
            SpawnConfig config, com.fasterxml.jackson.databind.JsonNode configTree) {
        if ("claude".equals(toolName)) return config.getClaude().hasAuth();
        var proxyDef = tool.proxy();
        if (proxyDef == null) return false;
        for (var entry : proxyDef.getConfiguration().entrySet()) {
            var configDef = entry.getValue();
            if (!configDef.getValue().isBlank()) continue;
            if (!configDef.getConfigPath().isBlank()) {
                var v = ToolProxyResolver.navigateConfigPath(configTree, configDef.getConfigPath());
                if (!v.isBlank()) return true;
            }
        }
        return false;
    }

    private void setupClaudeAuth() {
        startStep("Claude Code Authentication",
                "Configures how containers authenticate with the Claude API.",
                "You can use an Anthropic API key, a Claude Pro/Max OAuth",
                "token, or Google Cloud Vertex AI. The credential stays on",
                "your host and is injected at runtime via the MITM proxy —",
                "containers never see the real key.");
        var config = SpawnConfig.load();
        var console = System.console();
        if (console == null) {
            System.err.println("  Error: no console available for interactive setup.");
            return;
        }

        // Detect existing env vars
        var envVertex = System.getenv("CLAUDE_CODE_USE_VERTEX");
        var envApiKey = Environment.strippedEnv("ANTHROPIC_API_KEY");
        var envOauthToken = Environment.strippedEnv("CLAUDE_CODE_OAUTH_TOKEN");

        if ("1".equals(envVertex)) {
            var region = Environment.strippedEnv("CLOUD_ML_REGION");
            var projectId = Environment.strippedEnv("ANTHROPIC_VERTEX_PROJECT_ID");
            System.out.println("  Detected Vertex AI configuration from environment:");
            System.out.println("    Region:  " + (region.isBlank() ? "(not set)" : region));
            System.out.println("    Project: " + (projectId.isBlank() ? "(not set)" : projectId));

            if (region.isBlank() || projectId.isBlank()) {
                System.out.println("  CLOUD_ML_REGION and ANTHROPIC_VERTEX_PROJECT_ID must both be set for verification.");
                System.out.println("  Continuing with manual setup...");
            } else {
                System.out.println("  Verifying Vertex AI configuration...");
                var result = verifyVertexConfig(region, projectId);
                if (result.verified()) {
                    System.out.println("  \u001B[1;32m\u2713 " + result.message() + "\u001B[0m");
                    if (askConfirmation(console, "  Use this configuration?", true)) {
                        saveVertexConfig(config, region, projectId);
                        System.out.println("  Claude auth configuration saved.");
                        return;
                    }
                    System.out.println("  Skipping environment config. Continuing with manual setup...");
                } else {
                    System.out.println("  " + result.message());
                    if (askConfirmation(console, "  Save anyway? Press Enter to configure manually.", false)) {
                        saveVertexConfig(config, region, projectId);
                        System.out.println("  Claude auth configuration saved (unverified).");
                        return;
                    }
                }
            }
        } else if (!envOauthToken.isBlank()) {
            System.out.println("  Detected CLAUDE_CODE_OAUTH_TOKEN from environment.");
            System.out.println("  Verifying OAuth token...");
            var oauthResult = verifyOauthToken(envOauthToken);
            if (oauthResult.verified()) {
                System.out.println("  \u001B[1;32m\u2713 " + oauthResult.message() + "\u001B[0m");
                if (askConfirmation(console, "  Use this token?", true)) {
                    saveOauthConfig(config, envOauthToken);
                    System.out.println("  Claude auth configuration saved.");
                    return;
                }
                System.out.println("  Skipping environment token. Continuing with manual setup...");
            } else {
                System.out.println("  " + oauthResult.message());
                System.out.println("  Continuing with manual setup...");
            }
        } else if (!envApiKey.isBlank()) {
            System.out.println("  Detected ANTHROPIC_API_KEY from environment.");
            System.out.println("  Verifying API key...");
            var result = verifyAnthropicApiKey(envApiKey);
            if (result.verified()) {
                System.out.println("  \u001B[1;32m\u2713 " + result.message() + "\u001B[0m");
                if (askConfirmation(console, "  Use this key?", true)) {
                    saveDirectConfig(config, envApiKey);
                    System.out.println("  Claude auth configuration saved.");
                    return;
                }
                System.out.println("  Skipping environment key. Continuing with manual setup...");
            } else {
                System.out.println("  " + result.message());
                System.out.println("  Continuing with manual setup...");
            }
        }

        // Offer to keep existing config on re-run
        if (config.getClaude().hasAuth()) {
            String desc;
            if (config.getClaude().isUseVertex()) {
                var region = config.getClaude().getCloudMlRegion();
                var project = config.getClaude().getVertexProjectId();
                desc = "Google Cloud Vertex AI (region: " + (region.isBlank() ? "<not set>" : region)
                        + ", project: " + (project.isBlank() ? "<not set>" : project) + ")";
            } else if (config.getClaude().isOauthMode()) {
                desc = "Claude Pro/Max OAuth token (" + maskSecret(config.getClaude().getOauthToken()) + ")";
            } else {
                desc = "Anthropic API key (" + maskSecret(config.getClaude().getApiKey()) + ")";
            }
            System.out.println("  Claude auth: " + desc);
            if (askConfirmation(console, "  Keep current?", true, true)) {
                return;
            }
        }

        System.out.println("  How do you authenticate with Claude?");
        System.out.println("    1. Anthropic API key");
        System.out.println("    2. Claude Pro/Max subscription (OAuth token)");
        System.out.println("    3. Google Cloud Vertex AI");
        System.out.println();
        System.out.print("  Choice (1/2/3, or Enter to skip): ");
        var authChoice = console.readLine().strip();

        if (authChoice.equals("3")) {
            while (true) {
                System.out.print("  CLOUD_ML_REGION (or press Enter to skip): ");
                var region = console.readLine().strip();
                if (region.isBlank()) {
                    System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                    return;
                }
                System.out.print("  ANTHROPIC_VERTEX_PROJECT_ID: ");
                var projectId = console.readLine().strip();
                if (projectId.isBlank()) {
                    System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                    return;
                }

                System.out.println("  Verifying Vertex AI configuration...");
                var result = verifyVertexConfig(region, projectId);
                if (result.verified()) {
                    System.out.println("  \u001B[1;32m✓ " + result.message() + "\u001B[0m");
                    saveVertexConfig(config, region, projectId);
                    System.out.println("  Claude auth configuration saved.");
                    break;
                } else {
                    System.out.println("  " + result.message());
                    switch (askVerificationFailureAction(console)) {
                        case SKIP -> {
                            System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                            break;
                        }
                        case SAVE_UNVERIFIED -> {
                            saveVertexConfig(config, region, projectId);
                            System.out.println("  Claude auth configuration saved (unverified).");
                            break;
                        }
                        case RETRY -> {
                            continue;
                        }
                    }
                    break;
                }
            }
        } else if (authChoice.equals("2")) {
            setupClaudeOauth(config, console);
        } else if (authChoice.equals("1")) {
            while (true) {
                System.out.print("  ANTHROPIC_API_KEY (or press Enter to skip): ");
                var key = readSecret(console.readPassword());
                if (key.isBlank()) {
                    System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                    break;
                }

                System.out.println("  Verifying API key...");
                var result = verifyAnthropicApiKey(key);
                if (result.verified()) {
                    System.out.println("  \u001B[1;32m✓ " + result.message() + "\u001B[0m");
                    saveDirectConfig(config, key);
                    System.out.println("  Claude auth configuration saved.");
                    break;
                } else {
                    System.out.println("  " + result.message());
                    switch (askVerificationFailureAction(console)) {
                        case SKIP -> {
                            System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                            break;
                        }
                        case SAVE_UNVERIFIED -> {
                            saveDirectConfig(config, key);
                            System.out.println("  Claude auth configuration saved (unverified).");
                            break;
                        }
                        case RETRY -> {
                            continue;
                        }
                    }
                    break;
                }
            }
        } else {
            System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
        }
    }

    private record AuthResult(boolean verified, String message) {}

    enum VerificationFailureAction { RETRY, SKIP, SAVE_UNVERIFIED }

    private static VerificationFailureAction askVerificationFailureAction(Console console) {
        while (true) {
            System.out.print("  Try again? (Y/n/s to save anyway): ");
            var action = parseVerificationFailureAction(console.readLine());
            if (action != null) return action;
            System.out.println("  Please answer y, n, or s.");
        }
    }

    static VerificationFailureAction parseVerificationFailureAction(String answer) {
        if (answer == null) return VerificationFailureAction.SKIP;
        return switch (answer.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "", "y" -> VerificationFailureAction.RETRY;
            case "n" -> VerificationFailureAction.SKIP;
            case "s" -> VerificationFailureAction.SAVE_UNVERIFIED;
            default -> null;
        };
    }

    /**
     * Every {@code readLine()} prompt in this class strips its input; secrets must do the
     * same or a paste that picks up a stray leading/trailing space silently becomes a
     * different credential and is reported as rejected by the remote API.
     */
    static String readSecret(char[] chars) {
        return chars == null ? "" : new String(chars).strip();
    }

    /** Real 'claude setup-token' output runs to ~108 chars; much shorter means a paste cut at a line wrap. */
    private static final int OAUTH_TOKEN_MIN_PLAUSIBLE_LENGTH = 90;

    /** Non-fatal shape check; verification against the API remains the authority. */
    static Optional<String> oauthTokenShapeWarning(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        if (!token.startsWith(SpawnConfig.ClaudeConfig.OAUTH_TOKEN_PREFIX)) {
            return Optional.of("Note: token does not start with '" + SpawnConfig.ClaudeConfig.OAUTH_TOKEN_PREFIX
                    + "' (unexpected format for 'claude setup-token' output).");
        }
        if (token.length() < OAUTH_TOKEN_MIN_PLAUSIBLE_LENGTH) {
            return Optional.of("Note: token is " + token.length() + " characters, shorter than expected."
                    + " If your terminal wrapped the token, the paste may have been cut short.");
        }
        return Optional.empty();
    }

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /**
     * " API said: ..." from an Anthropic error body, or "" when it carries no usable
     * message. Remote input: a malformed or oversized body must yield nothing rather
     * than throwing or flooding the terminal.
     */
    static String apiErrorSuffix(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            var message = JSON.readTree(body).path("error").path("message");
            if (!message.isTextual()) {
                return "";
            }
            var text = WHITESPACE_RUN.matcher(message.asText()).replaceAll(" ").strip();
            if (text.isEmpty()) {
                return "";
            }
            return " API said: " + (text.length() > 200 ? text.substring(0, 200) + "\u2026" : text);
        } catch (Exception e) {
            return "";
        }
    }

    private static final String[] KNOWN_PREFIXES = {"github_pat_", "sk-ant-", "ghp_"};

    static String maskSecret(String secret) {
        if (secret == null || secret.length() < 8) {
            return "****";
        }
        int prefixEnd = 4;
        for (var p : KNOWN_PREFIXES) {
            if (secret.startsWith(p)) { prefixEnd = p.length(); break; }
        }
        if (prefixEnd + 4 >= secret.length()) {
            return "****";
        }
        return secret.substring(0, prefixEnd) + "..." + secret.substring(secret.length() - 4);
    }

    private static void saveVertexConfig(SpawnConfig config, String region, String projectId) {
        config.getClaude().clearAuth();
        config.getClaude().setUseVertex(true);
        config.getClaude().setCloudMlRegion(region);
        config.getClaude().setVertexProjectId(projectId);
        config.save();
    }

    private static void saveDirectConfig(SpawnConfig config, String apiKey) {
        config.getClaude().clearAuth();
        config.getClaude().setApiKey(apiKey);
        config.save();
    }

    private static void saveOauthConfig(SpawnConfig config, String oauthToken) {
        config.getClaude().clearAuth();
        config.getClaude().setOauthToken(oauthToken);
        config.save();
    }

    private AuthResult verifyAnthropicApiKey(String key) {
        if (!key.startsWith("sk-ant-")) {
            System.out.println("  Note: key does not start with 'sk-ant-' (unexpected format).");
        }
        return verifyAnthropicCredential("x-api-key", key, "API key");
    }

    private AuthResult verifyOauthToken(String token) {
        oauthTokenShapeWarning(token).ifPresent(warning -> System.out.println("  " + warning));
        return verifyAnthropicCredential("Authorization", "Bearer " + token, "OAuth token");
    }

    private AuthResult verifyAnthropicCredential(String headerName, String headerValue, String label) {
        try {
            var client = getHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header(headerName, headerValue)
                    .header("Content-Type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return switch (response.statusCode()) {
                case 400 -> new AuthResult(true, label + " verified.");
                case 401 -> new AuthResult(false, label + " rejected (HTTP 401). It may be expired or invalid."
                        + apiErrorSuffix(response.body()));
                case 403 -> new AuthResult(true, label + " accepted (HTTP 403). It may have restricted permissions.");
                default -> new AuthResult(false, "Unexpected response (HTTP " + response.statusCode() + "). The "
                        + label.toLowerCase() + " may be invalid." + apiErrorSuffix(response.body()));
            };
        } catch (Exception e) {
            return new AuthResult(false, "Could not reach api.anthropic.com: " + e.getMessage());
        }
    }

    private void setupClaudeOauth(SpawnConfig config, java.io.Console console) {
        System.out.println("  A Pro/Max subscription does not come with an API key. What it can");
        System.out.println("  produce is a long-lived OAuth token (valid about a year):");
        System.out.println();
        System.out.println("    1. Install Claude Code: https://claude.com/claude-code");
        System.out.println("    2. Sign in with the subscription account: run " + BOLD + "claude" + RESET
                + ", then " + BOLD + "/login" + RESET);
        System.out.println("    3. Run " + BOLD + "claude setup-token" + RESET);
        System.out.println("    4. Copy the token it prints (it starts with '"
                + SpawnConfig.ClaudeConfig.OAUTH_TOKEN_PREFIX + "')");
        System.out.println();
        System.out.println("  Re-run 'isx init' to paste a fresh token once this one expires.");
        System.out.println();

        if (commandExists("claude")) {
            System.out.println("  Found 'claude' CLI on this host — steps 1 and 2 are already done.");
            if (askConfirmation(console, "  Run 'claude setup-token' now?", true)) {
                try {
                    var pb = new ProcessBuilder("claude", "setup-token");
                    pb.inheritIO();
                    var process = pb.start();
                    if (!process.waitFor(120, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        System.err.println("  'claude setup-token' timed out after 2 minutes.");
                    } else if (process.exitValue() != 0) {
                        System.err.println("  'claude setup-token' exited with code " + process.exitValue() + ".");
                    } else {
                        System.out.println("  Token generation complete.");
                    }
                } catch (Exception e) {
                    System.err.println("  Failed to run 'claude setup-token': " + e.getMessage());
                }
            }
        } else {
            System.out.println("  'claude' CLI not found on this host — follow the steps above on any");
            System.out.println("  machine where it is installed, then paste the token here.");
        }

        while (true) {
            System.out.print("  Paste your OAuth token (or press Enter to skip): ");
            var token = readSecret(console.readPassword());
            if (token.isBlank()) {
                System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                break;
            }

            System.out.println("  Verifying OAuth token...");
            var result = verifyOauthToken(token);
            if (result.verified()) {
                System.out.println("  \u001B[1;32m\u2713 " + result.message() + "\u001B[0m");
                saveOauthConfig(config, token);
                System.out.println("  Claude auth configuration saved.");
                break;
            } else {
                System.out.println("  " + result.message());
                switch (askVerificationFailureAction(console)) {
                    case SKIP -> {
                        System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                        break;
                    }
                    case SAVE_UNVERIFIED -> {
                        saveOauthConfig(config, token);
                        System.out.println("  Claude auth configuration saved (unverified).");
                        break;
                    }
                    case RETRY -> {
                        continue;
                    }
                }
                break;
            }
        }
    }

    private AuthResult verifyVertexConfig(String region, String projectId) {
        if (!commandExists("gcloud")) {
            return new AuthResult(false,
                    "gcloud CLI not found. Install it from https://cloud.google.com/sdk/docs/install\n"
                    + "  Then run: gcloud auth application-default login");
        }

        String accessToken;
        try {
            var pb = new ProcessBuilder("gcloud", "auth", "print-access-token");
            var process = pb.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new AuthResult(false, "gcloud timed out. Check your gcloud configuration.");
            }
            var stdout = new String(process.getInputStream().readAllBytes()).strip();
            var stderr = new String(process.getErrorStream().readAllBytes()).strip();
            if (process.exitValue() != 0 || stdout.isBlank()) {
                var detail = !stderr.isBlank() ? stderr : stdout;
                return new AuthResult(false,
                        "gcloud auth failed" + (detail.isBlank() ? "" : ": " + detail)
                        + "\n  Run: gcloud auth application-default login");
            }
            accessToken = stdout;
        } catch (Exception e) {
            return new AuthResult(false, "Failed to run gcloud: " + e.getMessage());
        }

        try {
            var host = ProxyConfig.vertexHost(region);
            var url = "https://" + host + "/v1/projects/" + projectId
                    + "/locations/" + region
                    + "/publishers/anthropic/models/claude-sonnet-4:rawPredict";
            var client = getHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return switch (response.statusCode()) {
                case 400 -> new AuthResult(true,
                        "Vertex AI verified (region: " + region + ", project: " + projectId + ").");
                case 401 -> new AuthResult(false,
                        "Vertex AI authentication failed (HTTP 401). Run: gcloud auth application-default login");
                case 403 -> new AuthResult(false,
                        "Vertex AI access denied (HTTP 403). Check that the Vertex AI API is enabled\n"
                        + "  for project '" + projectId + "' and your account has the required permissions.");
                case 404 -> new AuthResult(false,
                        "Vertex AI endpoint not found (HTTP 404). Check region '" + region
                        + "' and project '" + projectId + "' are correct.");
                default -> new AuthResult(false,
                        "Unexpected Vertex AI response (HTTP " + response.statusCode() + ").");
            };
        } catch (Exception e) {
            return new AuthResult(false, "Could not reach Vertex AI endpoint: " + e.getMessage());
        }
    }

    private void setupGitHubAuth() {
        startStep("GitHub Authentication",
                "Sets up a GitHub PAT so containers can open PRs, push",
                "code, and manage issues on your behalf. For best security,",
                "create a dedicated bot account (e.g. yourname-ai-bot) with",
                "collaborator access, or use a fine-grained PAT scoped to",
                "specific repos and permissions. Grant Contents, Issues, and",
                "Pull requests (read/write), plus Email addresses (read) under",
                "Account permissions (needed for git commit identity).",
                "Avoid admin, org, and delete permissions unless needed.",
                "Create one at: https://github.com/settings/personal-access-tokens");
        var config = SpawnConfig.load();
        var console = System.console();
        if (console == null) {
            System.err.println("  Error: no console available for interactive setup.");
            return;
        }

        // Offer to keep existing token on re-run
        if (!config.getGithub().getToken().isBlank()) {
            System.out.println("  GitHub auth: token configured (" + maskSecret(config.getGithub().getToken()) + ")");
            if (askConfirmation(console, "  Keep current?", true, true)) {
                return;
            }
        }

        while (true) {
            System.out.print("  GitHub PAT (or press Enter to skip): ");
            var token = readSecret(console.readPassword());
            if (token.isBlank()) {
                System.out.println("  Skipped GitHub setup. You can configure it later by re-running 'isx init'.");
                break;
            }

            var result = verifyGitHubToken(token);
            if (result == null) {
                if (!askConfirmation(console, "  Try again?", true)) {
                    System.out.println("  Skipped GitHub setup. You can configure it later by re-running 'isx init'.");
                    break;
                }
                continue;
            }

            if (result.email != null) {
                config.getGithub().setToken(token);
                config.getGithub().setEmail(result.email);
                config.save();
                System.out.println("  GitHub configuration saved.");
                break;
            }

            System.out.println("  \u001B[1;33m⚠ No email accessible — git commits will have no author email.\u001B[0m");
            System.out.println("  To fix this, either:");
            System.out.println("    • Add 'Email addresses' (read) under Account permissions on your PAT");
            System.out.println("      " + patSettingsUrl(token));
            System.out.println("    • Or make your email public at https://github.com/settings/profile");
            System.out.print("  Enter new PAT with email permission, or press Enter to continue without: ");
            var newToken = readSecret(console.readPassword());
            if (newToken.isBlank()) {
                config.getGithub().setToken(token);
                config.save();
                System.out.println("  GitHub configuration saved (without email).");
                break;
            }

            var newResult = verifyGitHubToken(newToken);
            if (newResult == null) {
                System.out.println("  New token failed verification — keeping the original token.");
                config.getGithub().setToken(token);
                config.save();
                System.out.println("  GitHub configuration saved (without email).");
                break;
            }
            config.getGithub().setToken(newToken);
            if (newResult.email != null) {
                config.getGithub().setEmail(newResult.email);
            }
            config.save();
            if (newResult.email != null) {
                System.out.println("  GitHub configuration saved.");
            } else {
                System.out.println("  GitHub configuration saved (still without email).");
            }
            break;
        }
    }

    private record GitHubVerifyResult(String login, String email) {}

    record EmailParseResult(java.util.List<String> verified, String primary) {}

    private GitHubVerifyResult verifyGitHubToken(String token) {
        System.out.println("  Testing GitHub token...");
        try {
            var client = getHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/user"))
                    .header("Authorization", "token " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.out.println("  Authentication failed (HTTP " + response.statusCode() + ").");
                return null;
            }

            var json = JSON.readTree(response.body());
            var login = json.has("login") ? json.get("login").asText(null) : null;
            var email = json.has("email") && !json.get("email").isNull() ? json.get("email").asText(null) : null;
            if (email == null) {
                email = checkGitHubEmail(client, token);
            }

            if (login != null) {
                if (email != null) {
                    System.out.println("  \u001B[1;32m\u2713 Token verified. Authenticated as: " + login + " <" + email + ">\u001B[0m");
                } else {
                    System.out.println("  \u001B[1;32m\u2713 Token verified. Authenticated as: " + login + "\u001B[0m");
                }
            } else {
                System.out.println("  Token verified (could not determine username).");
            }

            return new GitHubVerifyResult(login, email);
        } catch (Exception e) {
            System.out.println("  Could not test token: " + e.getMessage());
            return null;
        }
    }

    private String checkGitHubEmail(java.net.http.HttpClient client, String token) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/user/emails"))
                    .header("Authorization", "token " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }

            var parsed = parseGitHubEmails(response.body());
            if (parsed == null) {
                return null;
            }
            if (parsed.verified.size() == 1) {
                return parsed.verified.get(0);
            }

            var console = System.console();
            if (console == null) {
                return parsed.verified.get(0);
            }
            System.out.println("  Multiple verified emails found:");
            for (int i = 0; i < parsed.verified.size(); i++) {
                var label = parsed.verified.get(i);
                if (label.endsWith("@users.noreply.github.com")) label += " (private, recommended)";
                else if (label.equals(parsed.primary)) label += " (primary)";
                System.out.println("    " + (i + 1) + ". " + label);
            }
            System.out.print("  Select email for git commits [1]: ");
            var choice = console.readLine().strip();
            if (choice.isEmpty()) {
                return parsed.verified.get(0);
            }
            try {
                int idx = Integer.parseInt(choice) - 1;
                if (idx >= 0 && idx < parsed.verified.size()) {
                    return parsed.verified.get(idx);
                }
            } catch (NumberFormatException ignored) {}
            return parsed.verified.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    static EmailParseResult parseGitHubEmails(String json) {
        try {
            var emails = JSON.readTree(json);
            var verifiedEmails = new ArrayList<String>();
            String primaryEmail = null;
            String noreplyEmail = null;
            for (var entry : emails) {
                if (!entry.path("verified").asBoolean(false)) continue;
                var email = entry.path("email").asText(null);
                if (email == null) continue;
                if (email.endsWith("@users.noreply.github.com")) {
                    noreplyEmail = email;
                    continue;
                }
                verifiedEmails.add(email);
                if (entry.path("primary").asBoolean(false)) {
                    primaryEmail = email;
                }
            }
            if (noreplyEmail != null) {
                verifiedEmails.add(0, noreplyEmail);
            }
            if (verifiedEmails.isEmpty()) {
                return null;
            }
            return new EmailParseResult(java.util.List.copyOf(verifiedEmails), primaryEmail);
        } catch (Exception e) {
            return null;
        }
    }

    private static String patSettingsUrl(String token) {
        if (token.startsWith("github_pat_")) {
            return "https://github.com/settings/personal-access-tokens";
        }
        return "https://github.com/settings/tokens";
    }

    private void printNumberedPaths(java.util.List<String> paths) {
        for (int i = 0; i < paths.size(); i++) {
            System.out.println("    " + (i + 1) + ". " + paths.get(i));
        }
    }

    private void setupBobAuth() {
        startStep("Bob Shell Authentication",
                "Sets up an IBM Bob API key so containers can use Bob Shell",
                "for AI-assisted coding. The real key stays on the host —",
                "containers only hold a placeholder value, and the MITM",
                "proxy injects the real credential transparently.");
        var config = SpawnConfig.load();
        var console = System.console();
        if (console == null) {
            System.err.println("  Error: no console available for interactive setup.");
            return;
        }

        if (config.getBob().hasAuth()) {
            System.out.println("  Bob auth: API key configured (" + maskSecret(config.getBob().getApiKey()) + ")");
            if (askConfirmation(console, "  Keep current?", true, true)) {
                return;
            }
        }

        System.out.println("  To create an API key:");
        System.out.println("    1. Go to https://bob.ibm.com/admin/apikeys");
        System.out.println("    2. Click 'Create API key'");
        System.out.println("    3. Set the scope to " + BOLD + "Inference" + RESET);
        System.out.println("    4. Copy the generated key");
        System.out.println();
        System.out.print("  Bob API key (or press Enter to skip): ");
        var apiKey = readSecret(console.readPassword());
        if (apiKey.isBlank()) {
            System.out.println("  Skipped Bob setup. You can configure it later by re-running 'isx init'.");
            return;
        }

        config.getBob().setApiKey(apiKey);

        System.out.println();
        System.out.println("  IBM Bob Shell requires acceptance of the IBM license agreement.");
        System.out.println("  The license is presented on first launch of Bob Shell.");
        System.out.println("  Pre-accepting here skips that prompt inside containers.");
        config.getBob().setLicenseConsent(
                askConfirmation(console, "  Do you accept the IBM license agreement?", false));
        if (!config.getBob().isLicenseConsent()) {
            System.out.println("  License not accepted. Bob Shell will prompt for consent on first use.");
        }

        config.save();
        System.out.println("  Bob configuration saved.");
    }

    private void setupCodexAuth() {
        startStep("Codex CLI Authentication",
                "Sets up an OpenAI API key so containers can use Codex CLI",
                "for AI-assisted coding. The real key stays on the host —",
                "containers only hold a placeholder value, and the MITM",
                "proxy injects the real credential transparently.");
        var config = SpawnConfig.load();
        var console = System.console();
        if (console == null) {
            System.err.println("  Error: no console available for interactive setup.");
            return;
        }

        if (config.getOpenai().hasAuth()) {
            System.out.println("  OpenAI auth: API key configured (" + maskSecret(config.getOpenai().getApiKey()) + ")");
            if (askConfirmation(console, "  Keep current?", true, true)) {
                return;
            }
        }

        System.out.println("  To create an API key:");
        System.out.println("    1. Go to https://platform.openai.com/api-keys");
        System.out.println("    2. Click 'Create new secret key'");
        System.out.println("    3. Copy the generated key (it is only shown once)");
        System.out.println();
        System.out.println("  Note: API usage requires billing credits, even on free accounts.");
        System.out.println("  Add credits at https://platform.openai.com/settings/organization/billing");
        System.out.println();
        System.out.print("  OpenAI API key (or press Enter to skip): ");
        var apiKey = readSecret(console.readPassword());
        if (apiKey.isBlank()) {
            System.out.println("  Skipped Codex CLI setup. You can configure it later by re-running 'isx init'.");
            return;
        }

        config.getOpenai().setApiKey(apiKey);
        config.save();
        System.out.println("  OpenAI configuration saved.");
    }

    private void setupGenericToolCredentials(String toolName, ToolSetup tool) {
        var desc = tool.description().isBlank() ? toolName : tool.description();
        startStep(desc,
                "Configures credentials for " + desc + ".",
                "Real credentials stay on your host — containers only hold",
                "placeholders, and the MITM proxy injects real values.");
        var config = SpawnConfig.load();
        var console = System.console();
        if (console == null) {
            System.err.println("  Error: no console available for interactive setup.");
            return;
        }

        var proxyDef = tool.proxy();
        if (proxyDef == null) return;

        var configTree = JSON.valueToTree(config);
        boolean savedAny = false;
        for (var entry : proxyDef.getConfiguration().entrySet()) {
            var configKey = entry.getKey();
            var configDef = entry.getValue();
            if (!configDef.getValue().isBlank()) continue;
            if (configDef.getConfigPath().isBlank()) continue;

            var existing = ToolProxyResolver.navigateConfigPath(configTree, configDef.getConfigPath());
            if (!existing.isBlank()) {
                System.out.println("  " + configKey + ": " + maskSecret(existing));
                if (askConfirmation(console, "  Keep current?", true, true)) continue;
            }

            if (!configDef.getDescription().isBlank()) {
                System.out.println("  " + configDef.getDescription());
            }

            String value;
            if (configDef.isConfirm()) {
                value = askConfirmation(console, "  " + configKey + "?", false, true) ? "true" : "";
            } else {
                System.out.print("  " + configKey + " (or press Enter to skip): ");
                if (configDef.isSecret()) {
                    value = readSecret(console.readPassword());
                } else {
                    var line = console.readLine();
                    value = line != null ? line.strip() : "";
                }
            }
            if (!value.isBlank()) {
                config.setConfigByPath(configDef.getConfigPath(), value);
                savedAny = true;
            }
        }

        if (savedAny) {
            config.save();
            System.out.println("  " + desc + " credentials saved.");
        } else {
            System.out.println("  Skipped. Configure later with 'isx init'.");
        }
    }

    private void setupPathList(
            java.util.function.Function<SpawnConfig, java.util.List<String>> getter,
            java.util.function.BiConsumer<SpawnConfig, java.util.List<String>> setter,
            String skipMessage) {
        var config = SpawnConfig.load();
        var existing = getter.apply(config);

        if (!existing.isEmpty()) {
            System.out.println("  Current paths:");
            printNumberedPaths(existing);
        }

        var console = System.console();
        if (console == null) {
            System.err.println("  Error: no console available for interactive setup.");
            return;
        }

        var paths = new java.util.ArrayList<>(existing);
        while (true) {
            var hasEntries = !paths.isEmpty();
            System.out.print("  Add a local directory"
                    + (hasEntries ? " or # to remove" : "")
                    + " (or press Enter to " + (hasEntries ? "finish" : "skip") + "): ");
            var input = console.readLine().strip();
            if (input.isEmpty()) break;

            if (input.contains("://")) {
                System.out.println("  That looks like a URL — this needs a local directory path (e.g. ~/my-templates).");
                continue;
            }

            if (input.matches("\\d+")) {
                int index = Integer.parseInt(input) - 1;
                if (index >= 0 && index < paths.size()) {
                    System.out.println("  Removed: " + paths.remove(index));
                    printNumberedPaths(paths);
                } else {
                    System.out.println("  No entry #" + input + ".");
                }
                continue;
            }

            var expanded = HostResourceSetup.expandHostTilde(input);
            var path = java.nio.file.Path.of(expanded);
            if (!java.nio.file.Files.isDirectory(path)) {
                System.out.println("  Warning: '" + input + "' is not an existing directory. Adding anyway.");
            }
            var resolved = path.toAbsolutePath().normalize().toString();
            if (paths.contains(resolved)) {
                System.out.println("  Already in the list.");
            } else {
                paths.add(resolved);
                System.out.println("  Added: " + resolved);
                printNumberedPaths(paths);
            }
        }

        if (!paths.equals(existing)) {
            setter.accept(config, paths);
            config.save();
            System.out.println("  Paths saved.");
        } else if (paths.isEmpty()) {
            System.out.println(skipMessage);
        } else {
            System.out.println("  Paths unchanged.");
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
            System.out.println("  https://github.com/" + TEMPLATES_UPSTREAM);
            System.out.println();
            return;
        }

        var login = getGhLogin();
        if (login == null) {
            System.out.println("  For community templates, see (clone and add the local path):");
            System.out.println("  https://github.com/" + TEMPLATES_UPSTREAM);
            System.out.println();
            return;
        }

        var console = System.console();
        if (console == null) {
            System.out.println("  For community templates, see (clone and add the local path):");
            System.out.println("  https://github.com/" + TEMPLATES_UPSTREAM);
            System.out.println();
            return;
        }

        if (ghRepoExists(login + "/" + TEMPLATES_REPO)) {
            offerCloneTemplates(console, login);
        } else {
            offerForkAndCloneTemplates(console, login);
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

    private void offerCloneTemplates(Console console, String login) {
        System.out.println("  Found " + BOLD + login + "/" + TEMPLATES_REPO + RESET + " on GitHub.");
        var defaultPath = defaultClonePath();
        var clonePath = askClonePath(console, defaultPath);
        if (clonePath == null) return;
        cloneAndAddSearchPath(login + "/" + TEMPLATES_REPO, clonePath, clonePath.equals(defaultPath));
    }

    private void offerForkAndCloneTemplates(Console console, String login) {
        System.out.println("  You don't have a " + BOLD + TEMPLATES_REPO + RESET + " repo yet.");
        if (!askConfirmation(console, "  Fork " + TEMPLATES_UPSTREAM + " to your account?", true)) {
            System.out.println("  Skipped. You can fork it manually at:");
            System.out.println("  https://github.com/" + TEMPLATES_UPSTREAM);
            System.out.println();
            return;
        }

        System.out.println("  Forking " + TEMPLATES_UPSTREAM + "...");
        var forkResult = runHostCapturingExit("gh", "repo", "fork", TEMPLATES_UPSTREAM, "--clone=false");
        if (forkResult != 0) {
            System.out.println("  Fork failed. You can fork it manually at:");
            System.out.println("  https://github.com/" + TEMPLATES_UPSTREAM + "/fork");
            System.out.println();
            return;
        }
        System.out.println("  " + GREEN_BOLD + "✓" + RESET + " Forked to " + login + "/" + TEMPLATES_REPO);

        var defaultPath = defaultClonePath();
        var clonePath = askClonePath(console, defaultPath);
        if (clonePath == null) {
            System.out.println("  You can clone it later with: gh repo clone " + login + "/" + TEMPLATES_REPO);
            System.out.println();
            return;
        }
        cloneAndAddSearchPath(login + "/" + TEMPLATES_REPO, clonePath, clonePath.equals(defaultPath));
    }

    private static String defaultClonePath() {
        return Environment.configDir().resolve(TEMPLATES_REPO).toString();
    }

    private String askClonePath(Console console, String defaultPath) {
        System.out.print("  Clone to " + defaultPath + "? (Y/path/n): ");
        var answer = console.readLine().strip();
        if (answer.equalsIgnoreCase("n")) return null;
        if (answer.isEmpty() || answer.equalsIgnoreCase("y")) return defaultPath;

        if (answer.equalsIgnoreCase("path")) {
            System.out.print("  Clone path: ");
            var path = console.readLine().strip();
            if (path.isEmpty()) return defaultPath;
            return HostResourceSetup.expandHostTilde(path);
        }
        return HostResourceSetup.expandHostTilde(answer);
    }

    private void cloneAndAddSearchPath(String nwo, String targetPath, boolean alreadyConfirmed) {
        var console = System.console();
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

        if (console != null && (!alreadyConfirmed || adjusted)) {
            if (!askConfirmation(console, "  Will clone to " + target + ". Proceed?", true)) {
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
        System.out.println("  " + GREEN_BOLD + "✓" + RESET + " Cloned to " + target);
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

    private int runHostCapturingExit(String... command) {
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

    private void offerMacOsServices() {
        if (ProxyService.isMacOsServiceInstalled()) {
            System.out.println("  macOS services already installed.");
            return;
        }
        System.out.println();
        System.out.println("  Optional: install VM and proxy as macOS services so they start");
        System.out.println("  automatically on login and survive reboots.");
        System.out.println();
        var console = System.console();
        if (console == null) return;
        if (!askConfirmation(console, "  Install services?", true)) {
            System.out.println("  Skipped. Start manually with: isx vm start && isx proxy start");
            return;
        }
        ProxyService.installMacOs();
    }

    private boolean offerProxyService() {
        if (ProxyService.isActive()) {
            ProxyService.upgradeIfNeeded();
            System.out.println();
            System.out.println("  Proxy service is already running.");
            return true;
        }
        System.out.println();
        System.out.println("  Optional: install the proxy as a systemd service so it starts");
        System.out.println("  automatically and survives reboots.");
        System.out.println();
        var console = System.console();
        if (console == null) return false;
        if (!askConfirmation(console, "  Install proxy service?", true)) {
            System.out.println("  Skipped. You can start the proxy manually with: isx proxy start");
            return false;
        }
        return ProxyService.install();
    }

    private void installGitRemoteShim() {
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

    private String captureOutput(String... command) {
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
     * Run a host command, capturing stderr and suppressing benign warnings.
     * Use this for commands like firewall-cmd that emit noisy "ALREADY_ENABLED" warnings.
     */
    private int runHostQuiet(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            var process = pb.start();
            // Drain stdout (show it)
            var stdout = new String(process.getInputStream().readAllBytes());
            if (!stdout.isBlank()) {
                System.out.print(stdout);
            }
            // Capture stderr and filter out benign warnings
            var stderr = new String(process.getErrorStream().readAllBytes());
            var exitCode = process.waitFor();
            if (!stderr.isBlank()) {
                for (var line : stderr.split("\n")) {
                    var trimmed = line.strip();
                    if (trimmed.isEmpty()) continue;
                    // Suppress benign firewalld warnings about already-configured rules
                    if (trimmed.contains("ALREADY_ENABLED")
                            || trimmed.contains("ALREADY_SET")
                            || trimmed.contains("ALREADY_ACTIVE")) {
                        // Silently ignore — the rule is already in place, which is what we want
                        continue;
                    }
                    // Print any other stderr as a non-alarming note
                    System.out.println("  " + trimmed);
                }
            }
            return exitCode;
        } catch (IOException | InterruptedException e) {
            System.err.println("  Failed to run: " + String.join(" ", command) + ": " + e.getMessage());
            return 1;
        }
    }
}
