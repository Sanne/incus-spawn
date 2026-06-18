package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.proxy.MitmProxy;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.vm.VmManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import java.io.Console;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@CommandDefinition(
        name = "init",
        description = "One-time host setup: install Incus, configure auth, test connectivity",
        generateHelp = true
)
public class InitCommand extends BaseCommand {

    private IncusClient incus;

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
     * Check whether init has been run by looking for the config file and CA cert.
     */
    public static boolean hasBeenInitialized() {
        return Files.exists(SpawnConfig.configDir().resolve("config.yaml"))
                && CertificateAuthority.exists();
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
        if (Environment.isLinux()) {
            return true;
        }
        if (Environment.isMacOS()) {
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
        if (Environment.isMacOS()) {
            return doMacOsInit();
        }
        if (!requireLinux()) {
            return CommandResult.valueOf(1);
        }
        totalSteps = 12;
        currentStep = 0;
        printBanner("incus-spawn — First-Time Setup",
                "Configuring your isolated development environment",
                totalSteps + " steps · ~3 minutes · some steps require sudo");

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
        setupClaudeAuth();
        setupGitHubAuth();
        closeHttpClient();
        setupSearchPaths();
        setupHostPaths();

        installGitRemoteShim();

        startStep("DNS Configuration", DNS_HINT);
        MitmProxy.configureBridgeDns(incus);

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
        totalSteps = 8;
        currentStep = 0;
        printBanner("incus-spawn — First-Time Setup (macOS)",
                "Configuring your isolated development environment",
                totalSteps + " steps · ~2 minutes");

        startStep("MITM CA Certificate",
                "Generates a custom Certificate Authority for the MITM",
                "proxy. Containers trust this CA so the proxy can intercept",
                "HTTPS and inject credentials transparently.");
        var gatewayIp = MitmProxy.resolveGatewayIp(incus);
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
        setupClaudeAuth();
        setupGitHubAuth();
        closeHttpClient();
        setupSearchPaths();
        setupHostPaths();

        installGitRemoteShim();

        startStep("DNS Configuration", DNS_HINT);
        var spawnConfig = SpawnConfig.load();
        if (spawnConfig.getClaude().hasAuth() || !spawnConfig.getGithub().getToken().isBlank()) {
            MitmProxy.configureBridgeDns(incus);
        } else {
            System.out.println("  Skipped — no API keys configured yet.");
        }

        startStep("macOS Services",
                "Installs the Incus VM and MITM proxy as macOS launch",
                "agents so they start automatically on login and survive",
                "reboots. Without this you'll need to manually run",
                "'isx vm start' and 'isx proxy start' before launching",
                "containers.");
        offerMacOsServices();

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
        if (!commandExists("openssl"))    missing.add("openssl");
        if (!commandExists("ssh-keygen")) missing.add("openssh-clients");
        if (!commandExists("btrfs"))      missing.add("btrfs-progs");
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
                System.out.print("  Proceed with automatic installation? (Y/n): ");
                var answer = console.readLine().strip();
                if (answer.equalsIgnoreCase("n")) {
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
                "Configures firewalld so containers can reach the internet",
                "and resolve DNS. Adds the Incus bridge to the trusted zone,",
                "enables NAT masquerading, and sets up FORWARD rules for",
                "Docker coexistence.");

        // Check if firewalld is available
        var fwCheck = runHost("which", "firewall-cmd");
        if (fwCheck != 0) {
            System.err.println("  Warning: firewall-cmd not found. Skipping firewall configuration.");
            System.err.println("  Containers may not have network/DNS access.");
            return;
        }

        // Ensure firewalld is actually running — permanent rules are written to disk
        // but not loaded into the kernel unless the daemon is active.
        var activeCheck = runHostQuiet("systemctl", "is-active", "--quiet", "firewalld");
        if (activeCheck != 0) {
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

        // Add incusbr0 to the trusted zone and enable masquerading so container
        // traffic is NAT'd to the internet. Both are --permanent so they survive reboots.
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

        System.out.println("  Enabling masquerading (NAT) for container internet access...");
        runHostQuiet("sudo", "firewall-cmd", "--zone=trusted", "--add-masquerade", "--permanent");

        var reloadResult = runHostQuiet("sudo", "firewall-cmd", "--reload");
        if (reloadResult != 0) {
            System.err.println("  Warning: firewall reload failed. Run: sudo firewall-cmd --reload");
            return;
        }

        // Verify
        try {
            var pb = new ProcessBuilder("sudo", "firewall-cmd", "--zone=trusted", "--list-all");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            boolean hasInterface = output.contains("incusbr0");
            boolean hasMasquerade = output.contains("masquerade: yes");
            if (hasInterface && hasMasquerade) {
                System.out.println("  Firewall configured: incusbr0 in trusted zone with masquerading.");
            } else {
                if (!hasInterface) System.err.println("  Warning: incusbr0 not in trusted zone.");
                if (!hasMasquerade) System.err.println("  Warning: masquerading not enabled.");
                System.err.println("  Containers may not have network/DNS access.");
            }
        } catch (Exception e) {
            System.err.println("  Warning: could not verify firewall config: " + e.getMessage());
        }

        // Ensure FORWARD rules for the Incus bridge are in place. Docker (if installed)
        // sets the FORWARD chain policy to DROP, which blocks Incus container traffic.
        // These direct rules are harmless without Docker and ready if Docker starts later.
        System.out.println("  Adding FORWARD rules for Incus bridge (Docker coexistence)...");
        runHostQuiet("sudo", "firewall-cmd", "--permanent", "--direct",
                "--add-rule", "ipv4", "filter", "FORWARD", "0",
                "-i", "incusbr0", "-j", "ACCEPT");
        runHostQuiet("sudo", "firewall-cmd", "--permanent", "--direct",
                "--add-rule", "ipv4", "filter", "FORWARD", "0",
                "-o", "incusbr0", "-m", "conntrack", "--ctstate", "RELATED,ESTABLISHED", "-j", "ACCEPT");
        runHostQuiet("sudo", "firewall-cmd", "--reload");
        System.out.println("  Firewall rules applied (persistent via firewalld).");
    }

    private void configureMitmProxy() {
        startStep("MITM Authentication Proxy",
                "The MITM proxy intercepts HTTPS from containers and injects",
                "your real API credentials, so containers never hold sensitive",
                "tokens directly. This step sets up iptables port redirection",
                "and generates a custom CA certificate trusted by containers.");

        // Add iptables PREROUTING redirect: traffic arriving on incusbr0 destined
        // for the gateway IP on port 443 is redirected to the proxy's listen port.
        // Only traffic to the gateway IP is redirected (intercepted domains resolve
        // there via dnsmasq); traffic to other IPs (e.g. maven repos) passes through.
        var gatewayIp = MitmProxy.resolveGatewayIp(incus);
        var config = SpawnConfig.load();
        config.setIncusBridgeGateway(gatewayIp);
        config.save();
        System.out.println("  Adding iptables PREROUTING redirect (" + gatewayIp + ":443 -> "
                + MitmProxy.DEFAULT_MITM_PORT + " on incusbr0)...");
        runHostQuiet("sudo", "firewall-cmd", "--permanent", "--direct",
                "--add-rule", "ipv4", "nat", "PREROUTING", "0",
                "-i", "incusbr0", "-d", gatewayIp, "-p", "tcp", "--dport",
                String.valueOf(MitmProxy.CONTAINER_FACING_PORT),
                "-j", "REDIRECT", "--to-port",
                String.valueOf(MitmProxy.DEFAULT_MITM_PORT));
        // Remove overly broad redirect rule from previous installs (missing -d gateway)
        runHostQuiet("sudo", "firewall-cmd", "--permanent", "--direct",
                "--remove-rule", "ipv4", "nat", "PREROUTING", "0",
                "-i", "incusbr0", "-p", "tcp", "--dport",
                String.valueOf(MitmProxy.CONTAINER_FACING_PORT),
                "-j", "REDIRECT", "--to-port",
                String.valueOf(MitmProxy.DEFAULT_MITM_PORT));
        runHostQuiet("sudo", "firewall-cmd", "--reload");

        // Clean up old sysctl config from previous installs (no longer needed)
        runHostQuiet("sudo", "rm", "-f", "/etc/sysctl.d/99-incus-spawn.conf");

        // Generate CA certificate if it doesn't exist
        if (CertificateAuthority.exists()) {
            System.out.println("  MITM CA certificate already exists.");
        } else {
            CertificateAuthority.loadOrCreate();
        }
        System.out.println("  MITM proxy configured.");
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
        try {
            var subuid = java.nio.file.Files.readString(java.nio.file.Path.of("/etc/subuid"));
            if (!subuid.contains("root:1000:1")) {
                runHost("sh", "-c", "echo 'root:1000:1' | sudo tee -a /etc/subuid /etc/subgid");
                changed = true;
            }
            if (!subuid.contains("root:1000000:65536")) {
                runHost("sh", "-c", "echo 'root:1000000:65536' | sudo tee -a /etc/subuid /etc/subgid");
                changed = true;
            }
        } catch (IOException e) {
            System.err.println("  Warning: could not read /etc/subuid: " + e.getMessage());
        }
        if (changed) {
            System.out.println("  Restarting Incus to apply idmap changes...");
            runHost("sudo", "systemctl", "restart", "incus");
        }
        System.out.println("  subuid/subgid configured.");
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

        // Use sudo for admin init since it may need elevated privileges
        var exitCode = runHost("sudo", "incus", "admin", "init", "--minimal");
        if (exitCode == 0) {
            System.out.println("  Incus initialized with default storage pool and network.");
        } else {
            // May already be initialized
            if (incus.probeCowPool().listed()) {
                System.out.println("  Incus already initialized.");
            } else {
                System.err.println("  Warning: Incus initialization may have failed. Check 'incus storage list'.");
            }
        }

        checkStorageDriver();
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
            var createResult = runHost("sudo", "incus", "storage", "create", "cow", "btrfs");
            if (createResult == 0) {
                System.out.println("  Created btrfs storage pool 'cow'.");
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
                System.err.println("    \u001B[1msudo incus storage create cow btrfs\u001B[0m");
                System.err.println("  incus-spawn will automatically use it for all new instances.");
                System.err.println();

                var console = System.console();
                if (console != null) {
                    System.err.print("  \u001B[1;33mContinue without CoW storage? (y/N): \u001B[0m");
                    var answer = console.readLine().strip();
                    if (!answer.equalsIgnoreCase("y")) {
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
        var envApiKey = System.getenv("ANTHROPIC_API_KEY");
        var envOauthToken = System.getenv("CLAUDE_CODE_OAUTH_TOKEN");

        if ("1".equals(envVertex)) {
            var region = System.getenv().getOrDefault("CLOUD_ML_REGION", "");
            var projectId = System.getenv().getOrDefault("ANTHROPIC_VERTEX_PROJECT_ID", "");
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
                    System.out.print("  Use this configuration? (Y/n): ");
                    var accept = console.readLine().strip();
                    if (!accept.equalsIgnoreCase("n")) {
                        saveVertexConfig(config, region, projectId);
                        System.out.println("  Claude auth configuration saved.");
                        return;
                    }
                    System.out.println("  Skipping environment config. Continuing with manual setup...");
                } else {
                    System.out.println("  " + result.message());
                    System.out.print("  Save anyway? (y/N) or press Enter to configure manually: ");
                    var answer = console.readLine().strip();
                    if (answer.equalsIgnoreCase("y")) {
                        saveVertexConfig(config, region, projectId);
                        System.out.println("  Claude auth configuration saved (unverified).");
                        return;
                    }
                }
            }
        } else if (envOauthToken != null && !envOauthToken.isBlank()) {
            System.out.println("  Detected CLAUDE_CODE_OAUTH_TOKEN from environment.");
            System.out.println("  Verifying OAuth token...");
            var oauthResult = verifyOauthToken(envOauthToken);
            if (oauthResult.verified()) {
                System.out.println("  \u001B[1;32m\u2713 " + oauthResult.message() + "\u001B[0m");
                System.out.print("  Use this token? (Y/n): ");
                var accept = console.readLine().strip();
                if (!accept.equalsIgnoreCase("n")) {
                    saveOauthConfig(config, envOauthToken);
                    System.out.println("  Claude auth configuration saved.");
                    return;
                }
                System.out.println("  Skipping environment token. Continuing with manual setup...");
            } else {
                System.out.println("  " + oauthResult.message());
                System.out.println("  Continuing with manual setup...");
            }
        } else if (envApiKey != null && !envApiKey.isBlank()) {
            System.out.println("  Detected ANTHROPIC_API_KEY from environment.");
            System.out.println("  Verifying API key...");
            var result = verifyAnthropicApiKey(envApiKey);
            if (result.verified()) {
                System.out.println("  \u001B[1;32m\u2713 " + result.message() + "\u001B[0m");
                System.out.print("  Use this key? (Y/n): ");
                var accept = console.readLine().strip();
                if (!accept.equalsIgnoreCase("n")) {
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
                    System.out.print("  Try again? (Y/n/s to save anyway): ");
                    var retry = console.readLine().strip();
                    if (retry.equalsIgnoreCase("n")) {
                        System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                        break;
                    } else if (retry.equalsIgnoreCase("s")) {
                        saveVertexConfig(config, region, projectId);
                        System.out.println("  Claude auth configuration saved (unverified).");
                        break;
                    }
                }
            }
        } else if (authChoice.equals("2")) {
            setupClaudeOauth(config, console);
        } else if (authChoice.equals("1")) {
            while (true) {
                System.out.print("  ANTHROPIC_API_KEY (or press Enter to skip): ");
                var key = new String(console.readPassword());
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
                    System.out.print("  Try again? (Y/n/s to save anyway): ");
                    var retry = console.readLine().strip();
                    if (retry.equalsIgnoreCase("n")) {
                        System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                        break;
                    } else if (retry.equalsIgnoreCase("s")) {
                        saveDirectConfig(config, key);
                        System.out.println("  Claude auth configuration saved (unverified).");
                        break;
                    }
                }
            }
        } else {
            System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
        }
    }

    private record AuthResult(boolean verified, String message) {}

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
                case 401 -> new AuthResult(false, label + " rejected (HTTP 401). It may be expired or invalid.");
                case 403 -> new AuthResult(true, label + " accepted (HTTP 403). It may have restricted permissions.");
                default -> new AuthResult(false, "Unexpected response (HTTP " + response.statusCode() + "). The " + label.toLowerCase() + " may be invalid.");
            };
        } catch (Exception e) {
            return new AuthResult(false, "Could not reach api.anthropic.com: " + e.getMessage());
        }
    }

    private void setupClaudeOauth(SpawnConfig config, java.io.Console console) {
        if (commandExists("claude")) {
            System.out.println("  Found 'claude' CLI on this host.");
            System.out.print("  Run 'claude setup-token' to generate an OAuth token? (Y/n): ");
            var proceed = console.readLine().strip();
            if (!proceed.equalsIgnoreCase("n")) {
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
            System.out.println("  'claude' CLI not found on this host.");
            System.out.println("  To generate a token, install Claude Code and run: claude setup-token");
        }

        while (true) {
            System.out.print("  Paste your OAuth token (or press Enter to skip): ");
            var token = new String(console.readPassword());
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
                System.out.print("  Try again? (Y/n/s to save anyway): ");
                var retry = console.readLine().strip();
                if (retry.equalsIgnoreCase("n")) {
                    System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                    break;
                } else if (retry.equalsIgnoreCase("s")) {
                    saveOauthConfig(config, token);
                    System.out.println("  Claude auth configuration saved (unverified).");
                    break;
                }
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
            var host = MitmProxy.vertexHost(region);
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
                "specific repos and permissions. For example, grant Contents,",
                "Issues, and Pull requests (read/write) — avoid admin, org,",
                "and delete permissions unless needed.",
                "Create one at: https://github.com/settings/personal-access-tokens");
        var config = SpawnConfig.load();
        var console = System.console();
        if (console == null) {
            System.err.println("  Error: no console available for interactive setup.");
            return;
        }

        while (true) {
            System.out.print("  GitHub PAT (or press Enter to skip): ");
            var token = new String(console.readPassword());
            if (token.isBlank()) {
                System.out.println("  Skipped GitHub setup. You can configure it later by re-running 'isx init'.");
                break;
            }

            // Test the token using the GitHub API directly, isolated from host credentials
            System.out.println("  Testing GitHub token...");
            boolean verified = false;
            try {
                var client = getHttpClient();
                var request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.github.com/user"))
                        .header("Authorization", "token " + token)
                        .header("Accept", "application/vnd.github+json")
                        .GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    var loginMatch = java.util.regex.Pattern.compile("\"login\"\\s*:\\s*\"([^\"]+)\"")
                            .matcher(response.body());
                    if (loginMatch.find()) {
                        System.out.println("  \u001B[1;32m✓ Token verified. Authenticated as: " + loginMatch.group(1) + "\u001B[0m");
                    } else {
                        System.out.println("  Token verified (could not determine username).");
                    }
                    verified = true;
                } else {
                    System.out.println("  Authentication failed (HTTP " + response.statusCode() + ").");
                }
            } catch (Exception e) {
                System.out.println("  Could not test token: " + e.getMessage());
            }

            if (verified) {
                config.getGithub().setToken(token);
                config.save();
                System.out.println("  GitHub configuration saved.");
                break;
            } else {
                System.out.print("  Try again? (Y/n): ");
                var retry = console.readLine().strip();
                if (retry.equalsIgnoreCase("n")) {
                    System.out.println("  Skipped GitHub setup. You can configure it later by re-running 'isx init'.");
                    break;
                }
            }
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
            for (var path : existing) {
                System.out.println("    - " + path);
            }
        }

        var console = System.console();
        if (console == null) {
            System.err.println("  Error: no console available for interactive setup.");
            return;
        }

        var paths = new java.util.ArrayList<>(existing);
        while (true) {
            System.out.print("  Add a path (or press Enter to " + (paths.isEmpty() ? "skip" : "finish") + "): ");
            var input = console.readLine().strip();
            if (input.isEmpty()) break;

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

    private void setupSearchPaths() {
        startStep("Template Search Paths",
                "Optional directories where isx looks for custom image and",
                "tool definitions. Definitions found here can extend or",
                "override the built-in templates. Each directory should",
                "contain images/ and/or tools/ subdirectories with YAML",
                "files. For examples see (experimental, opinionated):",
                "https://github.com/Sanne/incus-spawn-templates");
        setupPathList(
                SpawnConfig::getSearchPaths,
                SpawnConfig::setSearchPaths,
                "  No search paths configured. You can add them later in ~/.config/incus-spawn/config.yaml");
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
        System.out.print("  Install services? (Y/n): ");
        var answer = console.readLine().strip();
        if (answer.equalsIgnoreCase("n")) {
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
        System.out.print("  Install proxy service? (Y/n): ");
        var answer = console.readLine().strip();
        if (answer.equalsIgnoreCase("n")) {
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
