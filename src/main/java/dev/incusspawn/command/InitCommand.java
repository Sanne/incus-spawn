package dev.incusspawn.command;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

import java.io.Console;
import java.io.IOException;

@Command(
        name = "init",
        description = "One-time host setup: install Incus, configure auth, test connectivity",
        mixinStandardHelpOptions = true
)
public class InitCommand implements Runnable {

    @Inject
    IncusClient incus;

    @Override
    public void run() {
        System.out.println("=== incus-spawn init ===\n");

        checkIncusInstalled();
        configureSubuidSubgid();
        initializeIncus();
        configureFirewall();
        setupClaudeAuth();
        setupGitHubAuth();

        System.out.println("\n=== Init complete! ===");
        System.out.println("Next: run 'incus-spawn build golden-base' or 'incus-spawn build golden-java' to create a base image.");
    }

    private void checkIncusInstalled() {
        System.out.println("[1/6] Checking Incus installation...");
        var result = runHost("which", "incus");
        if (result != 0) {
            System.out.println("  Incus is not installed on this system.");
            System.out.println("  The following steps require sudo privileges:");
            System.out.println("    - Install the 'incus' package via dnf");
            System.out.println("    - Enable the incus systemd service");
            System.out.println("    - Add your user to the 'incus-admin' group");
            System.out.println();
            System.out.println("  If you prefer to install manually, abort now (Ctrl+C) and run:");
            System.out.println("    sudo dnf install incus");
            System.out.println("    sudo systemctl enable --now incus");
            System.out.println("    sudo usermod -aG incus-admin " + System.getProperty("user.name"));
            System.out.println("  Then re-run 'incus-spawn init' to continue setup.");
            System.out.println();

            var console = System.console();
            if (console != null) {
                System.out.print("  Proceed with automatic installation? (Y/n): ");
                var answer = console.readLine().strip();
                if (answer.equalsIgnoreCase("n")) {
                    System.out.println("  Aborted. Install Incus manually and re-run 'incus-spawn init'.");
                    System.exit(0);
                }
            }

            System.out.println("  Installing Incus via dnf (sudo required)...");
            runHost("sudo", "dnf", "install", "-y", "incus");
            System.out.println("  Enabling incus service...");
            runHost("sudo", "systemctl", "enable", "--now", "incus");
            System.out.println("  Adding user to incus-admin group...");
            runHost("sudo", "usermod", "-aG", "incus-admin", System.getProperty("user.name"));
            System.out.println("  NOTE: You may need to log out and back in for group membership to take effect.");
            System.out.println("  Alternatively, run: newgrp incus-admin");
        } else {
            System.out.println("  Incus is installed.");
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
                System.out.println();
                System.out.println("  IMPORTANT: Group membership has been updated but is not active in this shell.");
                System.out.println("  Please run: newgrp incus-admin");
                System.out.println("  Then re-run 'incus-spawn init' to continue.");
                System.exit(0);
            }
        } catch (Exception e) {
            System.err.println("  Warning: could not check group membership: " + e.getMessage());
        }
    }

    private void configureFirewall() {
        System.out.println("[4/6] Configuring firewall for Incus bridge...");
        System.out.println("  Adding incusbr0 to the trusted firewall zone (sudo required)...");
        var addResult = runHost("sudo", "firewall-cmd", "--zone=trusted", "--change-interface=incusbr0", "--permanent");
        if (addResult != 0) {
            System.err.println("  Warning: failed to add incusbr0 to trusted zone.");
            System.err.println("  Containers may not have network/DNS access.");
            System.err.println("  You can fix this manually:");
            System.err.println("    sudo firewall-cmd --zone=trusted --change-interface=incusbr0 --permanent");
            System.err.println("    sudo firewall-cmd --reload");
            return;
        }
        var reloadResult = runHost("sudo", "firewall-cmd", "--reload");
        if (reloadResult != 0) {
            System.err.println("  Warning: firewall reload failed. Run: sudo firewall-cmd --reload");
            return;
        }

        // Verify
        try {
            var pb = new ProcessBuilder("sudo", "firewall-cmd", "--zone=trusted", "--list-interfaces");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            if (output.contains("incusbr0")) {
                System.out.println("  Firewall configured: incusbr0 is in the trusted zone.");
            } else {
                System.err.println("  Warning: incusbr0 does not appear in the trusted zone.");
                System.err.println("  Current trusted interfaces: " + output);
                System.err.println("  Containers may not have network/DNS access.");
            }
        } catch (Exception e) {
            System.err.println("  Warning: could not verify firewall config: " + e.getMessage());
        }

        // Docker sets the FORWARD chain policy to DROP, which blocks Incus container traffic.
        // Add explicit FORWARD rules for the Incus bridge to coexist with Docker.
        configureDockerCoexistence();
    }

    private void configureDockerCoexistence() {
        // Check if Docker's FORWARD DROP policy is in effect
        try {
            var pb = new ProcessBuilder("sudo", "iptables", "-L", "FORWARD", "-n");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            if (!output.contains("policy DROP")) {
                return; // No Docker interference, nothing to do
            }
        } catch (Exception e) {
            return; // Can't check, skip
        }

        System.out.println("  Detected Docker FORWARD DROP policy — adding Incus bridge rules...");

        // Allow outbound traffic from the Incus bridge
        runHost("sudo", "iptables", "-I", "FORWARD", "-i", "incusbr0", "-j", "ACCEPT");
        // Allow return traffic back to the Incus bridge
        runHost("sudo", "iptables", "-I", "FORWARD", "-o", "incusbr0",
                "-m", "conntrack", "--ctstate", "RELATED,ESTABLISHED", "-j", "ACCEPT");

        // Make the rules persistent across reboots
        System.out.println("  Making iptables rules persistent...");
        var saved = runHost("sudo", "sh", "-c",
                "iptables-save > /etc/sysconfig/iptables 2>/dev/null || iptables-save > /etc/iptables/rules.v4 2>/dev/null");
        if (saved != 0) {
            System.out.println("  Warning: could not persist iptables rules. They will be lost on reboot.");
            System.out.println("  To fix, install iptables-services and run:");
            System.out.println("    sudo dnf install iptables-services");
            System.out.println("    sudo systemctl enable iptables");
            System.out.println("    sudo iptables-save | sudo tee /etc/sysconfig/iptables");
        }

        // Verify connectivity
        System.out.println("  Docker coexistence rules applied.");
    }

    private void configureSubuidSubgid() {
        System.out.println("[2/6] Configuring subuid/subgid mappings...");
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
        System.out.println("[3/6] Initializing Incus (storage pool, network bridge)...");

        // Check if we can talk to the Incus daemon
        var canConnect = incus.exec("version");
        if (!canConnect.success()) {
            var stderr = canConnect.stderr().strip();
            if (stderr.contains("permissions") || stderr.contains("socket")) {
                System.out.println();
                System.out.println("  Cannot connect to the Incus daemon.");
                System.out.println("  This usually means the 'incus-admin' group membership is not active in this shell.");
                System.out.println();
                System.out.println("  Please do one of the following:");
                System.out.println("    - Run: newgrp incus-admin");
                System.out.println("    - Or log out and log back in");
                System.out.println("  Then re-run 'incus-spawn init' to continue.");
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
        var result = incus.exec("storage", "list", "--format=csv", "--columns=nd");
        if (!result.success()) return;
        var lines = result.stdout().strip().lines().toList();
        if (lines.isEmpty()) return;

        boolean anyCow = false;
        for (var line : lines) {
            var parts = line.split(",", 2);
            if (parts.length >= 2 && COW_DRIVERS.contains(parts[1].strip())) {
                anyCow = true;
                break;
            }
        }

        if (!anyCow) {
            System.out.println();
            System.err.println("  Warning: no copy-on-write storage pool detected.");
            System.err.println("  Clones and branches will be full copies, using more disk space.");
            System.err.println("  For efficient storage, consider creating a btrfs or ZFS pool:");
            System.err.println("    incus storage create default btrfs");
            System.err.println("  See: https://linuxcontainers.org/incus/docs/main/reference/storage_btrfs/");
        }
    }

    private void setupClaudeAuth() {
        System.out.println("[5/6] Configuring Claude Code authentication...");
        var config = SpawnConfig.load();
        var console = System.console();
        if (console == null) {
            System.err.println("  Error: no console available for interactive setup.");
            return;
        }

        // Detect existing Vertex env vars
        var useVertex = System.getenv("CLAUDE_CODE_USE_VERTEX");
        if ("1".equals(useVertex)) {
            System.out.println("  Detected Vertex AI configuration from environment.");
            config.getClaude().setUseVertex(true);
            config.getClaude().setCloudMlRegion(
                    System.getenv().getOrDefault("CLOUD_ML_REGION", ""));
            config.getClaude().setVertexProjectId(
                    System.getenv().getOrDefault("ANTHROPIC_VERTEX_PROJECT_ID", ""));
            System.out.println("  Region: " + config.getClaude().getCloudMlRegion());
            System.out.println("  Project: " + config.getClaude().getVertexProjectId());
        } else {
            System.out.print("  Do you use Vertex AI for Claude? (y/N): ");
            var answer = console.readLine().strip();
            if (answer.equalsIgnoreCase("y")) {
                config.getClaude().setUseVertex(true);
                System.out.print("  CLOUD_ML_REGION: ");
                config.getClaude().setCloudMlRegion(console.readLine().strip());
                System.out.print("  ANTHROPIC_VERTEX_PROJECT_ID: ");
                config.getClaude().setVertexProjectId(console.readLine().strip());
            } else {
                config.getClaude().setUseVertex(false);
                System.out.print("  ANTHROPIC_API_KEY: ");
                var key = new String(console.readPassword());
                config.getClaude().setApiKey(key);
            }
        }

        // Test connectivity
        System.out.println("  Testing Claude Code availability...");
        var testResult = runHost("which", "claude");
        if (testResult == 0) {
            System.out.println("  Claude Code is available on the host.");
        } else {
            System.out.println("  Warning: 'claude' command not found on host. It will need to be installed in golden images.");
        }

        config.save();
        System.out.println("  Claude auth configuration saved.");
    }

    private void setupGitHubAuth() {
        System.out.println("[6/6] Configuring GitHub authentication...");
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
                System.out.println("  Skipped GitHub setup. You can configure it later by re-running 'incus-spawn init'.");
                break;
            }

            // Test the token using the GitHub API directly, isolated from host credentials
            System.out.println("  Testing GitHub token...");
            boolean verified = false;
            try {
                var testPb = new ProcessBuilder("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                        "-H", "Authorization: token " + token,
                        "-H", "Accept: application/vnd.github+json",
                        "https://api.github.com/user");
                testPb.redirectErrorStream(true);
                var process = testPb.start();
                var output = new String(process.getInputStream().readAllBytes()).strip();
                var exitCode = process.waitFor();
                if (exitCode == 0 && "200".equals(output)) {
                    // Fetch the username to confirm which identity the token belongs to
                    var userPb = new ProcessBuilder("curl", "-s",
                            "-H", "Authorization: token " + token,
                            "-H", "Accept: application/vnd.github+json",
                            "https://api.github.com/user");
                    userPb.redirectErrorStream(true);
                    var userProcess = userPb.start();
                    var userOutput = new String(userProcess.getInputStream().readAllBytes()).strip();
                    userProcess.waitFor();
                    // Extract login from JSON (simple extraction to avoid dependency)
                    var loginMatch = java.util.regex.Pattern.compile("\"login\"\\s*:\\s*\"([^\"]+)\"").matcher(userOutput);
                    if (loginMatch.find()) {
                        System.out.println("  Token verified. Authenticated as: " + loginMatch.group(1));
                    } else {
                        System.out.println("  Token verified (could not determine username).");
                    }
                    verified = true;
                } else {
                    System.out.println("  Authentication failed (HTTP " + output + ").");
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
                    System.out.println("  Skipped GitHub setup. You can configure it later by re-running 'incus-spawn init'.");
                    break;
                }
            }
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
}
