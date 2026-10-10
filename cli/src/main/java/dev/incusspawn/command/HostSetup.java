package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.CidrUtils;
import dev.incusspawn.incus.FirewalldCheck;
import dev.incusspawn.incus.FirewallDetector;
import dev.incusspawn.incus.FirewallDetector.DetectionResult;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.UfwCheck;
import dev.incusspawn.lifecycle.InstanceNetwork;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.util.TerminalLink;
import dev.incusspawn.vm.VmManager;
import dev.incusspawn.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.incusspawn.util.BuildOutput.BOLD;
import static dev.incusspawn.util.BuildOutput.YELLOW;
import static dev.incusspawn.util.BuildOutput.styled;
import static dev.incusspawn.command.BaseCommand.askConfirmation;
import static dev.incusspawn.command.InitCommand.commandExists;
import static dev.incusspawn.command.InitCommand.runHost;
import static dev.incusspawn.command.InitCommand.runHostCapturingExit;

/**
 * The Linux host configuration steps of {@code isx init}: packages, Incus itself, user namespace
 * mappings, the firewall, the MITM proxy's redirect, host sysctls and KSM, the btrfs sudoers rule,
 * and the storage pool, default profile and bridge.
 * <p>
 * Host commands and step headers go through the {@link InitCommand} it is given, so a test that
 * overrides {@code runHostQuiet}, {@code captureOutput} or {@code restartProxyService} there
 * drives these steps too.
 */
class HostSetup {

    private final InitCommand init;
    private boolean useUfw;

    HostSetup(InitCommand init) {
        this.init = init;
    }

    private void startStep(String title, String... hintLines) {
        init.startStep(title, hintLines);
    }

    private void restartProxyService() {
        init.restartProxyService();
    }

    private String captureOutput(String... command) {
        return init.captureOutput(command);
    }

    private boolean ran(String... command) {
        return init.ran(command);
    }

    private int runHostQuiet(String... command) {
        return init.runHostQuiet(command);
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

    void installDependencies() {
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

    void checkIncusInstalled() {
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

    void configureFirewall() {
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
                configureUfw(ProxyConfig.resolveGatewayIp(init.incus), UfwCheck.readBeforeRules());
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

    void configureMitmProxy() {
        startStep("MITM Authentication Proxy",
                "The MITM proxy intercepts HTTPS from containers and injects",
                "your real API credentials, so containers never hold sensitive",
                "tokens directly. This step sets up iptables port redirection",
                "and generates a custom CA certificate trusted by containers.");

        var gatewayIp = ProxyConfig.resolveGatewayIp(init.incus);
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

    void configureSubuidSubgid() {
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

    void initializeIncus() {
        startStep("Storage & Network",
                "Initializes the Incus daemon with a network bridge and",
                "storage pool. If no copy-on-write (btrfs) pool exists,",
                "one is created — enabling instant, space-efficient clones",
                "when you branch containers.");

        // Check if we can talk to the Incus daemon
        var connectivity = init.incus.checkConnectivity();
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
        if (init.incus.hasStoragePool()) {
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
        if (init.incus.createBridgeIfMissing("incusbr0", VmManager.gatewayIp())) {
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
        var probe = init.incus.probeCowPool();
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
        var pool = init.incus.findUsablePool();
        if (pool == null) {
            System.err.println("  Warning: no storage pool found — skipping the default profile check.");
            return;
        }
        try {
            var added = init.incus.ensureDefaultProfileDevices(pool, "incusbr0");
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
        var devices = init.incus.profileDevices("default");
        var currentPool = IncusClient.rootDiskPoolFromDevices(devices);
        if (currentPool == null || currentPool.equals(desiredPool)) return;

        var pools = init.incus.listPools();
        var currentDriver = pools.getOrDefault(currentPool, "");
        var desiredDriver = pools.getOrDefault(desiredPool, "");
        if (IncusClient.isCowDriver(currentDriver) || !IncusClient.isCowDriver(desiredDriver)) return;

        if (init.incus.updateProfileRootDiskPool("default", devices, desiredPool)) {
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
        var cowProbe = init.incus.probeCowPool();
        // Guard against transient/permission/daemon errors: if we can't list pools, don't
        // misinterpret that as "no CoW pool" and spuriously try to create one.
        if (!cowProbe.listed()) return;
        var anyCow = cowProbe.poolName() != null;

        if (!anyCow) {
            System.out.println("  No copy-on-write storage pool detected. Creating one...");
            runHostQuiet("sudo", "mkdir", "-p", "/var/lib/incus/disks");
            var create = runHostCapturingStderr(cowPoolCreateCommand(
                    init.incus.hasApiExtension(STORAGE_CREATE_OPTIONS_EXTENSION)));
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
            var result = BridgeSubnetCheck.detectAndFix(init.incus);
            if (result.conflictDetected()) {
                System.out.println("  Detected subnet conflict: bridge " + result.oldSubnet()
                        + " overlaps with route: " + result.conflictingRoute());
                if (result.newSubnet() != null) {
                    System.out.println("  Reconfigured bridge to " + result.newSubnet()
                            + " to avoid conflict.");
                    var migrated = InstanceNetwork.migrateAllInstancesToNewSubnet(init.incus);
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
}
