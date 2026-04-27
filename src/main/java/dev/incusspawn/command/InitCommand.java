package dev.incusspawn.command;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.MitmProxy;
import dev.incusspawn.proxy.ProxyService;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Command;

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

@Command(
        name = "init",
        description = "One-time host setup: install Incus, configure auth, test connectivity",
        mixinStandardHelpOptions = true
)
public class InitCommand implements Runnable {

    @Inject
    IncusClient incus;

    @Inject
    CommandLine.IFactory factory;

    /**
     * Check if init has been run. If not, print a warning and auto-launch init.
     * Call this at the top of any command that requires init (build, proxy, TUI, etc.).
     *
     * @return true if init is complete (either already or just ran), false if user aborted
     */
    public static boolean requireInit(CommandLine.IFactory factory) {
        if (!requireLinux()) return false;
        if (hasBeenInitialized()) return true;

        System.out.println();
        System.out.println("\u001B[1;33m  First-time setup required.\u001B[0m");
        System.out.println("  Running 'isx init' to configure Incus, authentication, and the MITM proxy...");
        System.out.println();

        var exitCode = new CommandLine(InitCommand.class, factory).execute();
        return exitCode == 0 && hasBeenInitialized();
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

    @Override
    public void run() {
        if (!requireLinux()) {
            System.exit(1);
        }
        System.out.println("=== incus-spawn init ===\n");

        installDependencies();
        checkIncusInstalled();
        configureSubuidSubgid();
        initializeIncus();
        configureFirewall();
        configureMitmProxy();
        setupClaudeAuth();
        setupGitHubAuth();
        setupSearchPaths();

        installGitRemoteShim();

        boolean proxyServiceInstalled = offerProxyService();

        System.out.println("\n=== Init complete! ===");
        System.out.println("Next steps:");
        System.out.println("  1. Build a template:      isx build tpl-java");
        if (proxyServiceInstalled) {
            System.out.println("  2. Proxy is running as a service (systemctl --user status incus-spawn-proxy)");
        } else {
            System.out.println("  2. Start the auth proxy:  isx proxy start");
        }
        System.out.println("  3. Launch the TUI:        isx");
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
        if (!commandExists("openssl")) missing.add("openssl");
        if (!commandExists("btrfs"))   missing.add("btrfs-progs");
        if (missing.isEmpty()) return;

        System.out.println("Installing dependencies: " + String.join(", ", missing) + "...");
        // zypper uses "btrfsprogs" instead of "btrfs-progs"
        if (commandExists("zypper")) {
            missing.replaceAll(p -> "btrfs-progs".equals(p) ? "btrfsprogs" : p);
        }
        var cmd = new ArrayList<String>();
        cmd.add("sudo");
        cmd.addAll(java.util.List.of(installCmd));
        cmd.addAll(missing);
        runHost(cmd.toArray(String[]::new));
    }

    private void checkIncusInstalled() {
        System.out.println("[1/8] Checking Incus installation...");
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
        System.out.println("[4/8] Configuring firewall for Incus bridge...");

        // Check if firewalld is available
        var fwCheck = runHost("which", "firewall-cmd");
        if (fwCheck != 0) {
            System.err.println("  Warning: firewall-cmd not found. Skipping firewall configuration.");
            System.err.println("  Containers may not have network/DNS access.");
            return;
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
        System.out.println("[5/8] Configuring MITM authentication proxy...");

        // Add iptables PREROUTING redirect: traffic arriving on incusbr0 destined
        // for the gateway IP on port 443 is redirected to the proxy's listen port.
        // Only traffic to the gateway IP is redirected (intercepted domains resolve
        // there via dnsmasq); traffic to other IPs (e.g. maven repos) passes through.
        var gatewayIp = MitmProxy.resolveGatewayIp(incus);
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

    private void configureSubuidSubgid() {
        System.out.println("[2/8] Configuring subuid/subgid mappings...");
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
        System.out.println("[3/8] Initializing Incus (storage pool, network bridge)...");

        // Check if we can talk to the Incus daemon
        var canConnect = incus.exec("version");
        if (!canConnect.success()) {
            var stderr = canConnect.stderr().strip();
            var daemonNotRunning = stderr.contains("connection refused") || stderr.contains("no such file")
                    || stderr.contains("cannot connect") || stderr.contains("failed to connect");
            var permissionDenied = stderr.contains("permissions") || stderr.contains("socket")
                    || stderr.contains("permission denied");
            if (daemonNotRunning) {
                System.out.println();
                System.out.println("  Cannot connect to the Incus daemon — it does not appear to be running.");
                System.out.println("  Enable and start it with:");
                System.out.println("    sudo systemctl enable --now incus");
                System.out.println("  Then re-run 'isx init' to continue.");
                System.exit(1);
            } else if (permissionDenied) {
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
        }

        // Use sudo for admin init since it may need elevated privileges
        var exitCode = runHost("sudo", "incus", "admin", "init", "--minimal");
        if (exitCode == 0) {
            System.out.println("  Incus initialized with default storage pool and network.");
        } else {
            // May already be initialized
            var check = incus.exec("storage", "list");
            if (check.success() && !check.stdout().isBlank()) {
                System.out.println("  Incus already initialized.");
            } else {
                System.err.println("  Warning: Incus initialization may have failed. Check 'incus storage list'.");
            }
        }

        checkStorageDriver();
    }

    private static final java.util.Set<String> COW_DRIVERS = java.util.Set.of("btrfs", "zfs", "lvm");

    private void checkStorageDriver() {
        var result = incus.exec("storage", "list", "--format=csv", "--columns=nD");
        if (!result.success()) return;
        var lines = result.stdout().strip().lines().toList();
        if (lines.isEmpty()) return;

        boolean anyCow = false;
        var existingPoolNames = new java.util.ArrayList<String>();
        for (var line : lines) {
            var parts = line.split(",", 2);
            if (parts.length >= 1) existingPoolNames.add(parts[0].strip());
            if (parts.length >= 2 && COW_DRIVERS.contains(parts[1].strip())) {
                anyCow = true;
                break;
            }
        }

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

    private void setupClaudeAuth() {
        System.out.println("[6/8] Configuring Claude Code authentication...");
        var config = SpawnConfig.load();
        var console = System.console();
        if (console == null) {
            System.err.println("  Error: no console available for interactive setup.");
            return;
        }

        // Detect existing env vars
        var envVertex = System.getenv("CLAUDE_CODE_USE_VERTEX");
        var envApiKey = System.getenv("ANTHROPIC_API_KEY");

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

        System.out.print("  Do you use Vertex AI for Claude? (y/N): ");
        var vertexAnswer = console.readLine().strip();

        if (vertexAnswer.equalsIgnoreCase("y")) {
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
        } else {
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
        }
    }

    private record AuthResult(boolean verified, String message) {}

    private static void saveVertexConfig(SpawnConfig config, String region, String projectId) {
        config.getClaude().setUseVertex(true);
        config.getClaude().setCloudMlRegion(region);
        config.getClaude().setVertexProjectId(projectId);
        config.save();
    }

    private static void saveDirectConfig(SpawnConfig config, String apiKey) {
        config.getClaude().setUseVertex(false);
        config.getClaude().setApiKey(apiKey);
        config.save();
    }

    private AuthResult verifyAnthropicApiKey(String key) {
        if (!key.startsWith("sk-ant-")) {
            System.out.println("  Note: key does not start with 'sk-ant-' (unexpected format).");
        }

        try {
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("x-api-key", key)
                    .header("Content-Type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return switch (response.statusCode()) {
                case 400 -> new AuthResult(true, "API key verified.");
                case 401 -> new AuthResult(false, "Invalid API key (HTTP 401). Check that the key is correct and not expired.");
                case 403 -> new AuthResult(true, "API key accepted (HTTP 403). It may have restricted permissions.");
                default -> new AuthResult(false, "Unexpected response (HTTP " + response.statusCode() + "). The key may be invalid.");
            };
        } catch (Exception e) {
            return new AuthResult(false, "Could not reach api.anthropic.com: " + e.getMessage());
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
            var client = HttpClient.newHttpClient();
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
        System.out.println("[7/8] Configuring GitHub authentication...");
        var config = SpawnConfig.load();
        var console = System.console();
        if (console == null) {
            System.err.println("  Error: no console available for interactive setup.");
            return;
        }

        System.out.println("  Agents running inside containers will interact with GitHub on your behalf.");
        System.out.println("  For best security, we recommend using a separate GitHub identity:");
        System.out.println();
        System.out.println("  Option A (recommended): Create a dedicated GitHub account for your agent");
        System.out.println("    - Sign up at https://github.com/signup (e.g. 'yourname-bot' or 'yourorg-agent')");
        System.out.println("    - Grant it collaborator access only to the repos it needs");
        System.out.println("    - Then create a PAT at https://github.com/settings/tokens?type=beta");
        System.out.println("    - PRs and issues will be clearly attributed to the agent, not you");
        System.out.println("    - Easy to revoke access without affecting your personal account");
        System.out.println();
        System.out.println("  Option B: Use a fine-grained PAT from your existing account");
        System.out.println("    - Go to https://github.com/settings/tokens?type=beta");
        System.out.println("    - Create a token named 'incus-spawn-agent'");
        System.out.println("    - Scope it to only the repositories you need");
        System.out.println("    - Grant: Contents (read/write), Issues (read/write), Pull requests (read/write)");
        System.out.println("    - Do NOT grant admin, org, or delete permissions");
        System.out.println();
        System.out.println("  In either case, use a dedicated token -- not your personal one.");
        System.out.println();
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
                var client = HttpClient.newHttpClient();
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

    private void setupSearchPaths() {
        System.out.println("[8/8] Configuring template search paths...");
        var config = SpawnConfig.load();
        var existing = config.getSearchPaths();
        if (!existing.isEmpty()) {
            System.out.println("  Current search paths:");
            for (var path : existing) {
                System.out.println("    - " + path);
            }
        }

        var console = System.console();
        if (console == null) {
            System.err.println("  Error: no console available for interactive setup.");
            return;
        }

        System.out.println("  You can add directories containing custom image and tool definitions.");
        System.out.println("  Each directory should have images/ and/or tools/ subdirectories with YAML files.");
        System.out.println();

        var paths = new java.util.ArrayList<>(existing);
        while (true) {
            System.out.print("  Add a search path (or press Enter to " + (paths.isEmpty() ? "skip" : "finish") + "): ");
            var input = console.readLine().strip();
            if (input.isEmpty()) break;

            var expanded = dev.incusspawn.config.HostResourceSetup.expandHostTilde(input);
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
            config.setSearchPaths(paths);
            config.save();
            System.out.println("  Search paths saved.");
        } else if (paths.isEmpty()) {
            System.out.println("  No search paths configured. You can add them later in ~/.config/incus-spawn/config.yaml");
        } else {
            System.out.println("  Search paths unchanged.");
        }
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
