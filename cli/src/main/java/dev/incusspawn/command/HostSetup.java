package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.BridgeAddress;
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
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.TerminalLink;
import dev.incusspawn.vm.VmManager;
import dev.incusspawn.Platform;
import dev.incusspawn.util.HostPath;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Predicate;

import static dev.incusspawn.util.BuildOutput.BOLD;
import static dev.incusspawn.util.BuildOutput.YELLOW;
import static dev.incusspawn.util.BuildOutput.styled;
import static dev.incusspawn.command.BaseCommand.askYesNo;
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
        return detectInstallCommand(InitCommand::commandExists);
    }

    static String[] detectInstallCommand(Predicate<String> has) {
        if (has.test("dnf"))    return new String[]{"dnf", "install", "-y"};
        if (has.test("apt"))    return new String[]{"apt", "install", "-y"};
        if (has.test("zypper")) return new String[]{"zypper", "install", "-y"};
        if (has.test("pacman")) return new String[]{"pacman", "-S", "--noconfirm"};
        return null;
    }

    static final String NO_PACKAGE_MANAGER = "No supported package manager found (dnf, apt, zypper, pacman).";

    /** The command that installs {@code packages} through the host's package manager, or null without one. */
    static List<String> installCommand(Predicate<String> has, String... packages) {
        var installCmd = detectInstallCommand(has);
        if (installCmd == null) return null;
        var cmd = new ArrayList<String>();
        cmd.add("sudo");
        cmd.addAll(List.of(installCmd));
        cmd.addAll(List.of(packages));
        return cmd;
    }

    /** The packages init installs before its steps, as Fedora names them: those whose command is missing. */
    static List<String> missingDependencies(Predicate<String> has) {
        var missing = new ArrayList<String>();
        if (!has.test("openssl"))      missing.add("openssl");
        if (!has.test("ssh-keygen"))  missing.add("openssh-clients");
        if (!has.test("btrfs"))       missing.add("btrfs-progs");
        return missing;
    }

    /** The command installing {@code missing}, as this host's package manager names them; null without one. */
    static List<String> dependencyCommand(Predicate<String> has, List<String> missing) {
        var packages = new ArrayList<>(missing);
        // zypper uses "btrfsprogs" instead of "btrfs-progs"
        if (has.test("zypper")) {
            packages.replaceAll(p -> "btrfs-progs".equals(p) ? "btrfsprogs" : p);
        }
        // Debian/Ubuntu uses "openssh-client" (singular)
        if (has.test("apt")) {
            packages.replaceAll(p -> "openssh-clients".equals(p) ? "openssh-client" : p);
        }
        // Arch/pacman uses "openssh"
        if (has.test("pacman")) {
            packages.replaceAll(p -> "openssh-clients".equals(p) ? "openssh" : p);
        }
        return installCommand(has, packages.toArray(String[]::new));
    }

    void installDependencies() {
        Predicate<String> has = InitCommand::commandExists;
        var missing = missingDependencies(has);
        var cmd = dependencyCommand(has, missing);
        if (missing.isEmpty() || cmd == null) return;
        var label = "Installing dependencies: " + String.join(", ", missing);
        BuildOutput.liveStep(label, () -> runHost(cmd.toArray(String[]::new)) == 0);
    }

    /** What {@link #installDependencies} would install. */
    InitPlan.Assessment planDependencies() {
        var plan = planMissingDependencies();
        return plan.note() != null ? plan : plan.noting(SUDO_FIRST);
    }

    /**
     * What every Linux run needs before its first step, done or not: {@code isx init} checks sudo
     * with {@code sudo -v}, which asks for a password unless sudo has one cached.
     */
    static final String SUDO_FIRST = "isx init starts with sudo -v, so running it needs sudo even when every step is done.";

    private InitPlan.Assessment planMissingDependencies() {
        Predicate<String> has = init::hostHasCommand;
        var missing = missingDependencies(has);
        if (missing.isEmpty()) return InitPlan.Assessment.done();
        var cmd = dependencyCommand(has, missing);
        if (cmd == null) {
            // Init skips them, and the steps that need them fail: a person installs them
            return InitPlan.Assessment.of(List.of(InitPlan.Change.sudo(
                    "Install " + String.join(", ", missing) + " yourself", null)))
                    .noting(NO_PACKAGE_MANAGER + " " + SUDO_FIRST);
        }
        return InitPlan.Assessment.of(List.of(InitPlan.Change.sudo(
                "Install " + String.join(", ", missing), String.join(" ", cmd))));
    }

    static final String[] INCUS_HINT = {
            "Incus provides full Linux system containers — lightweight",
            "VMs with near-native performance. This step installs the",
            "Incus package, enables its systemd service, and adds your",
            "user to the incus-admin group for unprivileged access."};

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** What {@link #checkIncusInstalled} would change. */
    InitPlan.Assessment planIncusInstallation() {
        var user = System.getProperty("user.name");
        var changes = new ArrayList<InitPlan.Change>();
        var asks = false;
        String note = null;
        if (!init.hostHasCommand("incus")) {
            var cmd = installCommand(init::hostHasCommand, "incus");
            changes.add(InitPlan.Change.sudo("Install the incus package", cmd == null ? null : String.join(" ", cmd)));
            changes.add(InitPlan.Change.sudo("Enable the incus service", "sudo systemctl enable --now incus"));
            // It asks before installing, and stops where it cannot
            if (cmd == null) note = NO_PACKAGE_MANAGER + " Install Incus yourself, then run 'isx init'.";
            else asks = true;
        } else if (init.query("systemctl", "is-active", "--quiet", "incus").exitCode() != 0) {
            changes.add(InitPlan.Change.sudo("Enable and start the incus service", "sudo systemctl enable --now incus"));
        }
        // The group database, as the step reads it: a membership added since login counts, as sg uses it
        if (!List.of(WHITESPACE.split(init.query("id", "-nG", user).output())).contains("incus-admin")) {
            changes.add(InitPlan.Change.sudo("Add " + user + " to the incus-admin group",
                    "sudo usermod -aG incus-admin " + user));
        }
        var plan = InitPlan.Assessment.of(changes).noting(note);
        return asks ? plan.asking() : plan;
    }

    void checkIncusInstalled() {
        if (!commandExists("incus")) {
            var installCmd = detectInstallCommand();
            BuildOutput.step("Incus is not installed on this system.");
            BuildOutput.step("The following steps require sudo privileges:");
            BuildOutput.stepNote("Install the 'incus' package");
            BuildOutput.stepNote("Enable the incus systemd service");
            BuildOutput.stepNote("Add your user to the 'incus-admin' group");
            if (installCmd != null) {
                BuildOutput.step("If you prefer to install manually, abort now (Ctrl+C) and run:");
                BuildOutput.stepNote("sudo " + String.join(" ", installCmd) + " incus");
            } else {
                BuildOutput.step("No supported package manager found (dnf, apt, zypper, pacman).");
                BuildOutput.step("Install Incus manually (see " + TerminalLink.link("https://linuxcontainers.org/incus/docs/main/installing/") + "), then run:");
            }
            BuildOutput.stepNote("sudo systemctl enable --now incus");
            BuildOutput.stepNote("sudo usermod -aG incus-admin " + System.getProperty("user.name"));
            BuildOutput.step("Then re-run 'isx init' to continue setup.");

            if (installCmd == null) {
                BuildOutput.error("Cannot auto-install without a supported package manager.");
                System.exit(1);
            }

            var prompts = init.prompts();
            if (prompts != null) {
                if (!askYesNo(prompts, "Proceed with automatic installation?", true)) {
                    BuildOutput.step("Aborted. Install Incus manually and re-run 'isx init'.");
                    System.exit(0);
                }
            }

            var fullCmd = new String[installCmd.length + 2];
            fullCmd[0] = "sudo";
            System.arraycopy(installCmd, 0, fullCmd, 1, installCmd.length);
            fullCmd[fullCmd.length - 1] = "incus";
            BuildOutput.liveStep("Installing Incus via " + installCmd[0] + " (sudo required)", () -> runHost(fullCmd) == 0);
            BuildOutput.liveStep("Enabling the incus service", () -> runHost("sudo", "systemctl", "enable", "--now", "incus") == 0);
            BuildOutput.liveStep("Adding user to the incus-admin group",
                    () -> runHost("sudo", "usermod", "-aG", "incus-admin", System.getProperty("user.name")) == 0);
            BuildOutput.step("You may need to log out and back in for group membership to take effect, or run:");
            BuildOutput.stepNote("newgrp incus-admin");
        } else {
            BuildOutput.step("Incus is installed.");
            var serviceActive = runHost("systemctl", "is-active", "--quiet", "incus");
            if (serviceActive != 0) {
                BuildOutput.step("Incus service is not running.");
                if (!BuildOutput.liveStep("Enabling and starting the Incus service (sudo required)",
                        () -> runHost("sudo", "systemctl", "enable", "--now", "incus") == 0)) {
                    BuildOutput.error("Failed to start the Incus service.",
                            "Run 'sudo systemctl enable --now incus' manually, then re-run 'isx init'.");
                    System.exit(1);
                }
            }
        }

        // Always ensure current user is in incus-admin group, as the group database has it: a
        // membership added since this login counts, and the continuation under sg takes it up
        try {
            var pb = new ProcessBuilder("id", "-nG", System.getProperty("user.name"));
            pb.redirectErrorStream(true);
            var process = pb.start();
            var groups = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            if (!groups.contains("incus-admin")) {
                if (BuildOutput.liveStep("Adding user to the incus-admin group",
                        () -> runHost("sudo", "usermod", "-aG", "incus-admin", System.getProperty("user.name")) == 0)) {
                    BuildOutput.stepNote("Active after the next login.");
                }
            }
        } catch (Exception e) {
            BuildOutput.warn("Could not check group membership: " + BaseCommand.failure(e));
        }
    }

    static final String[] FIREWALL_HINT = {
            "Configures the host firewall so containers can reach the",
            "internet and resolve DNS. Detects whether firewalld or UFW",
            "is active (and installs firewalld when neither is), adds the",
            "Incus bridge to a trusted zone, enables NAT masquerading, and",
            "sets up FORWARD rules."};

    // The firewalld commands the firewall step runs and the plan names, written once
    private static final String[] FWD_LIST_TRUSTED = {"firewall-cmd", "--zone=trusted", "--list-all"};
    private static final String[] FWD_LIST_DIRECT = {"firewall-cmd", "--direct", "--get-all-rules"};
    private static final String[] FWD_TRUST_BRIDGE = {"sudo", "firewall-cmd", "--zone=trusted", "--change-interface=incusbr0", "--permanent"};
    private static final String[] FWD_MASQUERADE = {"sudo", "firewall-cmd", "--zone=trusted", "--add-masquerade", "--permanent"};
    private static final String[] FWD_FORWARD_IN = {"sudo", "firewall-cmd", "--permanent", "--direct",
            "--add-rule", "ipv4", "filter", "FORWARD", "0", "-i", "incusbr0", "-j", "ACCEPT"};
    private static final String[] FWD_FORWARD_OUT = {"sudo", "firewall-cmd", "--permanent", "--direct",
            "--add-rule", "ipv4", "filter", "FORWARD", "0",
            "-o", "incusbr0", "-m", "conntrack", "--ctstate", "RELATED,ESTABLISHED", "-j", "ACCEPT"};
    private static final String[] FWD_RELOAD = {"sudo", "firewall-cmd", "--reload"};
    private static final String[] ENABLE_FIREWALLD = {"sudo", "systemctl", "enable", "--now", "firewalld"};
    private static final String[] UFW_ALLOW_BRIDGE = {"sudo", "ufw", "allow", "in", "on", "incusbr0"};
    private static final String[] UFW_RELOAD = {"sudo", "ufw", "reload"};

    private static String[] sudo(String[] command) {
        var withSudo = new String[command.length + 1];
        withSudo[0] = "sudo";
        System.arraycopy(command, 0, withSudo, 1, command.length);
        return withSudo;
    }

    private static InitPlan.Change sudoChange(String what, String[] command) {
        return InitPlan.Change.sudo(what, String.join(" ", command));
    }

    /**
     * What firewalld says to a read without sudo, or null when it would not answer this user: the
     * run asks it through sudo, which the plan never uses.
     */
    private String firewalldRead(String[] command) {
        // Where polkit is installed it is asked first, without interaction; without it no agent can ask.
        // Installed without pkcheck it cannot be asked, so the rules are not read.
        if (init.hostHasCommand("pkcheck")) {
            if (!FIREWALLD_READS.stream().allMatch(init::polkitAllows)) return null;
        } else if (Files.isDirectory(init.hostFile(POLKIT_ACTIONS))) {
            return null;
        }
        var result = init.query(command);
        return result.exitCode() == 0 ? result.output() : null;
    }

    /**
     * The polkit actions firewalld checks for the plan's two listings. Fedora's desktop policy
     * grants them to every user; its server policy wants an administrator, which a polkit agent
     * would ask for in a dialog, so the plan asks polkit first, without interaction.
     */
    /** Where polkit keeps its actions: there whenever polkit is installed. */
    private static final String POLKIT_ACTIONS = "/usr/share/polkit-1/actions";

    private static final List<String> FIREWALLD_READS = List.of(
            "org.fedoraproject.FirewallD1.config.info", "org.fedoraproject.FirewallD1.direct.info");

    /**
     * Rules only root can read here: not checked, with what the run may change there, which needs
     * sudo, and the reads a person runs to check them (Sanne on #2091: say what is skipped and why).
     */
    private static InitPlan.Assessment unreadable(String why, String what, List<String> checks, List<String> lines) {
        return InitPlan.Assessment.notChecked(why, InitPlan.Change.sudo(what, null).scripted(lines), checks);
    }

    /** As root, without sudo: a line of the script. */
    private static String asRoot(String[] command) {
        return String.join(" ", command).substring("sudo ".length());
    }

    /**
     * Every rule the firewall step adds, for a firewalld whose rules the plan could not read:
     * firewalld leaves a rule that is already there as it is, so adding all of them is safe.
     */
    private static final List<String> FIREWALLD_ALL_RULES = List.of(asRoot(FWD_TRUST_BRIDGE), asRoot(FWD_MASQUERADE),
            asRoot(FWD_FORWARD_IN), asRoot(FWD_FORWARD_OUT), asRoot(FWD_RELOAD));

    /** The redirect to {@code gateway} (a literal, or a shell variable the script set), as the step adds it. */
    private static List<String> redirectLines(String gateway) {
        return List.of(asRoot(fwdRedirect("--add-rule", gateway)), asRoot(fwdRedirect("--remove-rule", null)),
                asRoot(FWD_RELOAD));
    }

    private static final String FIREWALLD_UNREADABLE = "Not checked: firewalld's policy on this host lets only an"
            + " administrator read its rules, and the plan never runs sudo or raises a password dialog."
            + " isx init reads them with sudo and adds those missing.";
    private static final String UFW_UNREADABLE = "Not checked: only root can read /etc/ufw/before.rules, and the plan"
            + " never runs sudo. isx init reads it with sudo and adds the rules missing.";
    /** The reads the run makes with sudo, for a person to make the same check. */
    private static final List<String> FIREWALLD_CHECKS = List.of(
            String.join(" ", sudo(FWD_LIST_TRUSTED)), String.join(" ", sudo(FWD_LIST_DIRECT)));
    private static final List<String> FIREWALLD_REDIRECT_CHECKS = List.of(String.join(" ", sudo(FWD_LIST_DIRECT)));
    private static final List<String> UFW_CHECKS = List.of("sudo cat " + UfwCheck.BEFORE_RULES);

    /**
     * What {@link #configureFirewall} would change, as {@link FirewallDetector} chooses the
     * firewall. Rules are read without sudo where the firewall allows it, and are otherwise unknown.
     */
    InitPlan.Assessment planFirewall() {
        var parts = new ArrayList<InitPlan.Assessment>();
        switch (init.runningFirewall()) {
            case DetectionResult.UseFirewalld fwd when fwd.needsStart() -> {
                parts.add(InitPlan.Assessment.of(List.of(sudoChange("Start and enable firewalld", ENABLE_FIREWALLD))));
                // As the step does when init has run; else its last step restarts it, and plans that restart
                if (init.proxyServiceActive() && Environment.hasBeenInitialized()) {
                    parts.add(InitPlan.Assessment.of(List.of(InitPlan.Change.run(
                            "Restart the proxy service, to pick up the restored firewall rules", InitPlan.Who.NONE,
                            "systemctl --user restart " + Environment.PROXY_SERVICE_NAME))));
                }
                // Its rules cannot be read until it runs: all of them, of which init adds those missing
                parts.add(firewalldRuleChanges("", "")
                        .noting("firewalld is not running, so its rules cannot be read; init adds those missing once it is."));
            }
            case DetectionResult.UseFirewalld fwd -> {
                // One listing it will not show is enough to leave the rules unchecked: the other is not asked
                var trusted = firewalldRead(FWD_LIST_TRUSTED);
                parts.add(firewalldRuleChanges(trusted, trusted == null ? null : firewalldRead(FWD_LIST_DIRECT)));
            }
            case DetectionResult.UseUfw u -> parts.add(planUfw());
            case DetectionResult.NeitherInstalled n -> {
                var install = installCommand(init::hostHasCommand, "firewalld");
                if (install == null) {
                    // The step stops at once
                    parts.add(InitPlan.Assessment.of(List.of(InitPlan.Change.sudo("Install a firewall yourself", null)))
                            .noting(NO_PACKAGE_MANAGER));
                } else {
                    parts.add(InitPlan.Assessment.of(List.of(
                            InitPlan.Change.sudo("Install firewalld", String.join(" ", install)),
                            sudoChange("Start firewalld", ENABLE_FIREWALLD))));
                    parts.add(firewalldRuleChanges("", ""));
                }
            }
        }
        parts.add(planNetworkManager(init.hostFile(NM_CONF_DIR)));
        return InitPlan.combine(parts);
    }

    /**
     * The trust, masquerade and FORWARD rules {@link #configureFirewalld} would add, given what
     * firewalld listed: null when it would not answer, and empty for one with none of them yet.
     */
    private InitPlan.Assessment firewalldRuleChanges(String trustedZone, String directRules) {
        if (trustedZone == null || directRules == null) return unreadable(FIREWALLD_UNREADABLE, "Add the firewall rules that are missing, if any", FIREWALLD_CHECKS,
                FIREWALLD_ALL_RULES);
        var changes = new ArrayList<InitPlan.Change>();
        if (!trustedZone.contains("incusbr0")) changes.add(sudoChange("Add incusbr0 to the trusted zone", FWD_TRUST_BRIDGE));
        if (!trustedZone.contains("masquerade: yes")) changes.add(sudoChange("Enable masquerading (NAT) in the trusted zone", FWD_MASQUERADE));
        if (!FirewalldCheck.isForwardRulePresent(directRules, "-i", "incusbr0")) {
            changes.add(sudoChange("Accept forwarded traffic from incusbr0", FWD_FORWARD_IN));
        }
        if (!FirewalldCheck.isForwardRulePresent(directRules, "-o", "incusbr0")) {
            changes.add(sudoChange("Accept replies forwarded to incusbr0", FWD_FORWARD_OUT));
        }
        if (!changes.isEmpty()) changes.add(sudoChange("Reload firewalld", FWD_RELOAD));
        return InitPlan.Assessment.of(changes);
    }

    /** What {@link #configureUfw} would change, when {@code before.rules} can be read without sudo. */
    private InitPlan.Assessment planUfw() {
        var beforeRules = readIfReadable(init.hostFile(UfwCheck.BEFORE_RULES.toString()));
        if (beforeRules == null) return unreadable(UFW_UNREADABLE, "Add the firewall rules that are missing, if any", UFW_CHECKS, List.of());
        String subnet;
        try {
            subnet = CidrUtils.deriveSubnet(ProxyConfig.resolveGatewayIp(init.incus));
        } catch (RuntimeException e) {
            return InitPlan.Assessment.unknown("The bridge's subnet is not known yet: " + BaseCommand.failure(e));
        }
        var adding = new ArrayList<String>();
        if (!UfwCheck.hasMasquerade(beforeRules, subnet)) adding.add("NAT masquerading");
        if (!UfwCheck.hasForwardRules(beforeRules)) adding.add("FORWARD rules for incusbr0");
        if (adding.isEmpty()) return InitPlan.Assessment.done();
        return InitPlan.Assessment.of(List.of(
                sudoChange("Allow traffic in on incusbr0", UFW_ALLOW_BRIDGE),
                new InitPlan.Change("Add " + String.join(" and ", adding) + " to before.rules", InitPlan.Who.SUDO,
                        null, UfwCheck.BEFORE_RULES.toString()),
                sudoChange("Reload UFW", UFW_RELOAD)));
    }

    /** What a step's root part says under {@code --no-sudo}, where the script made it (#2074). */
    static final String DONE_BY_SCRIPT = "Its root part is the script's, which you ran; isx runs nothing as root here.";

    void configureFirewall() {
        if (init.noSudo()) {
            BuildOutput.note(DONE_BY_SCRIPT);
            return;
        }

        var detection = FirewallDetector.detect();
        switch (detection) {
            case DetectionResult.UseFirewalld fwd -> {
                if (fwd.needsStart()) {
                    BuildOutput.step("firewalld is installed but not running.");
                    if (!BuildOutput.liveStep("Starting and enabling firewalld",
                            () -> runHost(ENABLE_FIREWALLD) == 0)) {
                        BuildOutput.warn("Failed to start firewalld.",
                                "Run manually: sudo systemctl enable --now firewalld", "Then re-run: isx init");
                        return;
                    }
                    if (ProxyService.isActive()) {
                        if (Environment.hasBeenInitialized()) {
                            // Not a live step: the restart reports its own
                            BuildOutput.step("Restarting the proxy service so it picks up the restored firewall rules");
                            restartProxyService();
                        } else {
                            // Restarted before the marker, it would refuse to start (#1048);
                            // completeWithProxyService restarts it once the marker is written.
                            BuildOutput.step("The proxy service will be restarted once init completes, to pick up the restored firewall rules.");
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
                BuildOutput.step("No firewall detected.");
                var installCmd = detectInstallCommand();
                if (installCmd == null) {
                    BuildOutput.warn("Could not detect package manager.");
                    return;
                }
                var cmd = new java.util.ArrayList<String>();
                cmd.add("sudo");
                cmd.addAll(java.util.List.of(installCmd));
                cmd.add("firewalld");
                if (!BuildOutput.liveStep("Installing firewalld", () -> runHost(cmd.toArray(String[]::new)) == 0)) {
                    BuildOutput.warn("Failed to install firewalld.");
                    return;
                }
                if (!BuildOutput.liveStep("Starting firewalld", () -> runHost(ENABLE_FIREWALLD) == 0)) {
                    BuildOutput.warn("Failed to start firewalld.");
                    return;
                }
                configureFirewalld();
            }
        }
        configureNetworkManager();
    }

    void configureFirewalld() {
        var trustedZoneOutput = captureOutput(sudo(FWD_LIST_TRUSTED));
        boolean hasInterface = trustedZoneOutput.contains("incusbr0");
        boolean hasMasquerade = trustedZoneOutput.contains("masquerade: yes");

        var directRulesOutput = captureOutput(sudo(FWD_LIST_DIRECT));
        boolean hasForwardIn = FirewalldCheck.isForwardRulePresent(directRulesOutput, "-i", "incusbr0");
        boolean hasForwardOut = FirewalldCheck.isForwardRulePresent(directRulesOutput, "-o", "incusbr0");

        if (hasInterface && hasMasquerade && hasForwardIn && hasForwardOut) {
            BuildOutput.step("Firewall already configured (firewalld).");
            return;
        }

        if (!hasInterface) {
            if (!BuildOutput.liveStep("Adding incusbr0 to the trusted firewall zone (sudo required)",
                    () -> runHostQuiet(FWD_TRUST_BRIDGE) == 0)) {
                BuildOutput.warn("Failed to add incusbr0 to trusted zone.",
                        "Containers may not have network/DNS access. You can fix this manually:",
                        "sudo firewall-cmd --zone=trusted --change-interface=incusbr0 --permanent",
                        "sudo firewall-cmd --zone=trusted --add-masquerade --permanent",
                        "sudo firewall-cmd --reload");
                return;
            }
        }
        boolean ok = true;
        if (!hasMasquerade) {
            ok &= BuildOutput.liveStep("Enabling masquerading (NAT) for container internet access",
                    () -> ran(FWD_MASQUERADE));
        }
        if (!hasForwardIn || !hasForwardOut) {
            ok &= BuildOutput.liveStep("Adding FORWARD rules for Incus bridge (Docker coexistence)", () -> {
                // Both rules, even when the first fails: the step reports what it did
                boolean added = true;
                if (!hasForwardIn) {
                    added &= ran(FWD_FORWARD_IN);
                }
                if (!hasForwardOut) {
                    added &= ran(FWD_FORWARD_OUT);
                }
                return added;
            });
        }

        var reloadResult = runHostQuiet(FWD_RELOAD);
        if (reloadResult != 0) {
            BuildOutput.warn("Firewall reload failed.", "Run: sudo firewall-cmd --reload");
            if (!ok) warnFirewallIncomplete();
            return;
        }
        if (!ok) {
            warnFirewallIncomplete();
            return;
        }
        BuildOutput.step("Firewall configured: incusbr0 in trusted zone with masquerading (firewalld).");
    }

    void configureUfw(String gatewayIp, String beforeRules) {
        var subnet = CidrUtils.deriveSubnet(gatewayIp);

        if (beforeRules.isEmpty()) {
            BuildOutput.warn("Could not read /etc/ufw/before.rules.",
                    "Skipping UFW configuration to avoid overwriting existing rules.");
            return;
        }
        boolean hasForward = UfwCheck.hasForwardRules(beforeRules);
        boolean hasMasquerade = UfwCheck.hasMasquerade(beforeRules, subnet);

        if (hasForward && hasMasquerade) {
            BuildOutput.step("Firewall already configured (UFW).");
            return;
        }

        boolean ok = BuildOutput.liveStep("Allowing traffic on incusbr0", () -> ran(UFW_ALLOW_BRIDGE));

        var content = beforeRules;
        var adding = new ArrayList<String>();
        if (!hasMasquerade) {
            adding.add("NAT masquerading");
            var natBlock = UfwCheck.generateNatBlockWithoutRedirect(subnet);
            content = UfwCheck.insertNatBlock(content, natBlock);
        }
        if (!hasForward) {
            adding.add("FORWARD rules for Incus bridge");
            var filterInsert = UfwCheck.generateFilterInsert();
            content = UfwCheck.insertFilterRules(content, filterInsert);
        }

        if (!content.equals(beforeRules)) {
            var written = content;
            ok &= BuildOutput.liveStep("Adding " + String.join(" and ", adding) + " to UFW before.rules", () -> writeBeforeRules(written));
            var reloadResult = runHostQuiet(UFW_RELOAD);
            if (reloadResult != 0) {
                BuildOutput.warn("UFW reload failed.", "Run: sudo ufw reload");
                if (!ok) warnFirewallIncomplete();
                return;
            }
        }
        if (!ok) {
            warnFirewallIncomplete();
            return;
        }
        BuildOutput.step("Firewall configured: incusbr0 trusted with masquerading (UFW).");
    }

    private static void warnFirewallIncomplete() {
        warnIncomplete("Firewall only partly configured; containers may lack network/DNS access.");
    }

    private static void warnIncomplete(String consequence) {
        BuildOutput.warn(consequence, "Fix the problem reported above, then re-run: isx init");
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
            BuildOutput.warn("Could not write before.rules: " + BaseCommand.failure(e));
            return false;
        }
    }

    private void configureNetworkManager() {
        configureNetworkManager(Path.of(NM_CONF_DIR));
    }

    /**
     * A change applying a file the step just wrote, which the step only warns about when it fails:
     * it applies after a reboot. The script carries on past it, as the step does.
     */
    private static InitPlan.Change tolerated(InitPlan.Change change) {
        return change.scripted(change.script().stream()
                .map(line -> line + " || echo 'Could not apply this now; it applies after a reboot.' >&2").toList());
    }

    static final String NM_CONF_DIR = "/etc/NetworkManager/conf.d";
    private static final String NM_CONF_FILE = "99-unmanaged-veth.conf";
    private static final String NM_CONF = "[keyfile]\nunmanaged-devices=interface-name:veth*\n";

    /** What {@link #configureNetworkManager(Path)} would write in {@code confDir}. */
    InitPlan.Assessment planNetworkManager(Path confDir) {
        if (!Files.isDirectory(confDir) || Files.exists(confDir.resolve(NM_CONF_FILE))) return InitPlan.Assessment.done();
        var file = Path.of(NM_CONF_DIR, NM_CONF_FILE).toString();
        return InitPlan.Assessment.of(List.of(
                InitPlan.Change.rootFile("Tell NetworkManager to leave veth devices alone", file, "0644", NM_CONF),
                tolerated(InitPlan.Change.sudo("Reload NetworkManager", "sudo nmcli general reload"))));
    }

    void configureNetworkManager(Path confDir) {
        if (!Files.isDirectory(confDir)) return;
        var confFile = confDir.resolve(NM_CONF_FILE);
        if (Files.exists(confFile)) return;
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("isx-nm-veth-", ".conf");
            Files.writeString(tempFile, NM_CONF);
            if (installHostFile(tempFile, confFile.toString()) != 0) return;
            if (runHostQuiet("sudo", "nmcli", "general", "reload") != 0) {
                BuildOutput.warn("Wrote " + confFile + " but could not reload NetworkManager; it applies after a reboot.");
            } else {
                BuildOutput.step("Configured NetworkManager to ignore veth devices.");
            }
        } catch (IOException e) {
            BuildOutput.warn("Could not configure NetworkManager: " + BaseCommand.failure(e));
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

    /** What {@link #configureHostSysctls(Path)} would write and apply. */
    InitPlan.Assessment planHostSysctls(Path sysctlPath) {
        if (SYSCTL_CONTENT.equals(readIfReadable(sysctlPath))) return InitPlan.Assessment.done();
        return InitPlan.Assessment.of(List.of(
                InitPlan.Change.rootFile("Raise the inotify limits and allow perf in containers", SYSCTL_CONF, "0644", SYSCTL_CONTENT),
                tolerated(InitPlan.Change.sudo("Apply the sysctls", "sudo sysctl -p " + SYSCTL_CONF))));
    }

    void configureHostSysctls(Path sysctlPath) {
        var sysctlConf = sysctlPath.toString();
        var content = SYSCTL_CONTENT;
        try {
            if (content.equals(readIfReadable(sysctlPath))) {
                return;
            }
            var tempFile = Files.createTempFile("isx-sysctl-", ".conf");
            Files.writeString(tempFile, content);
            if (installHostFile(tempFile, sysctlConf) != 0) {
                BuildOutput.warn("Could not write " + sysctlConf + ".");
            } else if (runHostQuiet("sudo", "sysctl", "-p", sysctlConf) != 0) {
                BuildOutput.warn("Wrote " + sysctlConf + " but could not apply it now; it applies after a reboot.");
            } else {
                BuildOutput.step("Configured host sysctls (inotify, perf_event_paranoid).");
            }
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            BuildOutput.warn("Could not configure host sysctls: " + BaseCommand.failure(e));
        }
    }

    static final String SYSCTL_CONTENT = """
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

    private static final String KSM_RUN = "/sys/kernel/mm/ksm/run";
    private static final String KSM_TMPFILES = "/etc/tmpfiles.d/incus-spawn-ksm.conf";

    private static final String KSM_CONTENT = """
            # Written by isx init: share identical memory pages between VMs.
            w %s - - - - 1
            """.formatted(KSM_RUN);

    /** What {@link #configureKsm} would write and apply. */
    InitPlan.Assessment planKsm() {
        var run = init.hostFile(KSM_RUN);
        if (!Files.exists(run) || "active".equals(init.query("systemctl", "is-active", "ksmtuned").output())) {
            return InitPlan.Assessment.done();
        }
        var live = readIfReadable(run);
        if (KSM_CONTENT.equals(readIfReadable(init.hostFile(KSM_TMPFILES))) && live != null && "1".equals(live.strip())) {
            return InitPlan.Assessment.done();
        }
        return InitPlan.Assessment.of(List.of(
                InitPlan.Change.rootFile("Turn on kernel same-page merging (KSM) for VMs", KSM_TMPFILES, "0644", KSM_CONTENT),
                tolerated(InitPlan.Change.sudo("Apply it", "sudo systemd-tmpfiles --create " + KSM_TMPFILES))));
    }

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
            BuildOutput.step("KSM is managed by ksmtuned; leaving it alone.");
            return;
        }
        var content = KSM_CONTENT;
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
                BuildOutput.warn("Could not write " + KSM_TMPFILES + "; KSM stays off.");
            } else if (runHostQuiet("sudo", "systemd-tmpfiles", "--create", KSM_TMPFILES) != 0) {
                BuildOutput.warn("Could not enable KSM now; it will be on after a reboot.");
            } else {
                BuildOutput.step("Enabled kernel same-page merging (KSM) for VMs.");
            }
        } catch (IOException e) {
            BuildOutput.warn("Could not enable KSM: " + BaseCommand.failure(e));
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    void configureMitmProxy() {

        var gatewayIp = ProxyConfig.resolveGatewayIp(init.incus);
        var config = SpawnConfig.load();
        if (!gatewayIp.equals(config.getIncusBridgeGateway())) {
            config.setIncusBridgeGateway(gatewayIp);
            config.save();
        }

        // Under --no-sudo the script made the redirect, the sysctls and KSM (#2074)
        var redirected = true;
        if (init.noSudo()) {
            BuildOutput.note(DONE_BY_SCRIPT);
        } else {
            redirected = useUfw
                    ? configureMitmProxyUfw(gatewayIp, UfwCheck.readBeforeRules())
                    : configureMitmProxyFirewalld(gatewayIp);
            configureHostSysctls();
            configureKsm();
        }

        // Generate CA certificate if it doesn't exist
        if (CertificateAuthority.exists()) {
            BuildOutput.step("MITM CA certificate already exists.");
        } else {
            CertificateAuthority.loadOrCreate();
        }
        if (!redirected) {
            warnIncomplete("The PREROUTING redirect was not fully configured; containers' HTTPS may not reach the proxy.");
            return;
        }
        BuildOutput.step("MITM proxy configured.");
    }

    /** Whether every command updating the PREROUTING redirect, stale-rule removals included, succeeded. */
    boolean configureMitmProxyFirewalld(String gatewayIp) {
        var rulesOutput = captureOutput(sudo(FWD_LIST_DIRECT));
        boolean hasRedirect = FirewalldCheck.isPreRoutingRulePresent(rulesOutput, ProxyConfig.DEFAULT_MITM_PORT, gatewayIp);

        boolean ok = true;
        if (hasRedirect) {
            BuildOutput.step("PREROUTING redirect already configured (" + gatewayIp + ":443 -> "
                    + ProxyConfig.DEFAULT_MITM_PORT + ").");
        } else {
            // Remove stale redirect rule pointing to a previous gateway IP
            var staleIp = FirewalldCheck.extractRedirectGatewayIp(rulesOutput, ProxyConfig.DEFAULT_MITM_PORT);
            if (staleIp != null) {
                ok &= BuildOutput.liveStep("Removing stale PREROUTING redirect (old gateway " + staleIp + ")",
                        () -> ran(fwdRedirect("--remove-rule", staleIp)));
            }
            // Every command runs, even after one failed (&, not &&): the step reports what it did
            ok &= BuildOutput.liveStep("Adding iptables PREROUTING redirect (" + gatewayIp + ":443 -> "
                    + ProxyConfig.DEFAULT_MITM_PORT + " on incusbr0)",
                    () -> ran(fwdRedirect("--add-rule", gatewayIp))
                            & ran(fwdRedirect("--remove-rule", null))
                            & ran(FWD_RELOAD));
        }
        return ok;
    }

    /**
     * The firewalld command adding or removing ({@code op}) the redirect of the containers' HTTPS
     * to the proxy, for {@code gatewayIp}, or for any address when it is null: the rule releases
     * before the gateway was part of it wrote.
     */
    private static String[] fwdRedirect(String op, String gatewayIp) {
        var cmd = new ArrayList<>(List.of("sudo", "firewall-cmd", "--permanent", "--direct",
                op, "ipv4", "nat", "PREROUTING", "0", "-i", "incusbr0"));
        if (gatewayIp != null) cmd.addAll(List.of("-d", gatewayIp));
        cmd.addAll(List.of("-p", "tcp", "--dport", String.valueOf(ProxyConfig.CONTAINER_FACING_PORT),
                "-j", "REDIRECT", "--to-port", String.valueOf(ProxyConfig.DEFAULT_MITM_PORT)));
        return cmd.toArray(String[]::new);
    }

    /** What {@link #configureMitmProxyFirewalld} would change, from rules read without sudo; null when unreadable. */
    InitPlan.Assessment planRedirectFirewalld(String gatewayIp, String rulesOutput) {
        if (rulesOutput == null) return unreadable(FIREWALLD_UNREADABLE, "Add the redirect to the proxy, if it is missing", FIREWALLD_REDIRECT_CHECKS,
                bridgeRedirectLines());
        if (FirewalldCheck.isPreRoutingRulePresent(rulesOutput, ProxyConfig.DEFAULT_MITM_PORT, gatewayIp)) {
            return InitPlan.Assessment.done();
        }
        var changes = new ArrayList<InitPlan.Change>();
        var staleIp = FirewalldCheck.extractRedirectGatewayIp(rulesOutput, ProxyConfig.DEFAULT_MITM_PORT);
        if (staleIp != null) {
            changes.add(sudoChange("Remove the redirect to the old gateway " + staleIp, fwdRedirect("--remove-rule", staleIp)));
        }
        changes.add(sudoChange("Redirect the containers' HTTPS on " + gatewayIp + " to the proxy", fwdRedirect("--add-rule", gatewayIp)));
        changes.add(sudoChange("Remove a redirect for any address, as older releases wrote", fwdRedirect("--remove-rule", null)));
        changes.add(sudoChange("Reload firewalld", FWD_RELOAD));
        return InitPlan.Assessment.of(changes);
    }

    /** What {@link #configureMitmProxyUfw} would change, when {@code before.rules} can be read without sudo. */
    private InitPlan.Assessment planRedirectUfw(String gatewayIp) {
        var beforeRules = readIfReadable(init.hostFile(UfwCheck.BEFORE_RULES.toString()));
        if (beforeRules == null) return unreadable(UFW_UNREADABLE, "Add the redirect to the proxy, if it is missing", UFW_CHECKS, List.of());
        if (UfwCheck.hasPreRoutingRedirect(beforeRules, ProxyConfig.DEFAULT_MITM_PORT, gatewayIp)) {
            return InitPlan.Assessment.done();
        }
        return InitPlan.Assessment.of(List.of(
                new InitPlan.Change("Redirect the containers' HTTPS on " + gatewayIp + " to the proxy",
                        InitPlan.Who.SUDO, null, UfwCheck.BEFORE_RULES.toString()),
                sudoChange("Reload UFW", UFW_RELOAD)));
    }

    static final String[] MITM_HINT = {
            "The MITM proxy intercepts HTTPS from containers and injects",
            "your real API credentials, so containers never hold sensitive",
            "tokens directly. This step sets up iptables port redirection",
            "and generates a custom CA certificate trusted by containers."};

    /**
     * The redirect for the script, which reads the gateway from the bridge as it runs: the bridge
     * {@code incus admin init} made, or one made as {@link IncusClient#createBridgeIfMissing}
     * makes it when that did not. The same lines whether or not the gateway is known yet, so the
     * script's record of them still holds once it is.
     */
    private static List<String> bridgeRedirectLines() {
        var lines = new ArrayList<>(List.of(
                "incus network show " + BridgeAddress.BRIDGE + " >/dev/null 2>&1 || incus network create " + BridgeAddress.BRIDGE
                        + " ipv4.address=" + VmManager.gatewayIp() + "/24 ipv4.nat=true ipv6.address=none",
                "gw=$(incus network get " + BridgeAddress.BRIDGE + " ipv4.address)",
                "gw=${gw%/*}"));
        lines.addAll(redirectLines("\"$gw\""));
        return lines;
    }

    /** What {@link #configureMitmProxy} would change. */
    InitPlan.Assessment planMitmProxy() {
        var parts = new ArrayList<InitPlan.Assessment>();
        String gatewayIp = null;
        String unreadable = null;
        try {
            gatewayIp = ProxyConfig.resolveGatewayIp(init.incus);
        } catch (RuntimeException e) {
            unreadable = BaseCommand.failure(e);
        }
        var firewall = init.runningFirewall();
        // Before Incus is installed the gateway read is only the recorded or default one: admin init chooses its own
        if (gatewayIp == null || !init.hostHasCommand("incus")) {
            if (init.hostHasCommand("incus")) {
                parts.add(InitPlan.Assessment.unknown("The bridge's gateway cannot be read: " + unreadable));
            } else {
                parts.add(InitPlan.Assessment.of(List.of(
                        InitPlan.Change.write("Record the bridge's gateway", InitPlan.Who.NONE, configFile()),
                        InitPlan.Change.sudo("Redirect the containers' HTTPS on the bridge's gateway to the proxy", null)
                                .scripted(firewall instanceof DetectionResult.UseUfw ? List.of() : bridgeRedirectLines())))
                        .noting("The gateway is known once Incus is set up."));
            }
        } else {
            if (!gatewayIp.equals(SpawnConfig.load().getIncusBridgeGateway())) {
                parts.add(InitPlan.Assessment.of(List.of(
                        InitPlan.Change.write("Record the bridge's gateway " + gatewayIp, InitPlan.Who.NONE, configFile()))));
            }
            parts.add(switch (firewall) {
                case DetectionResult.UseUfw u -> planRedirectUfw(gatewayIp);
                // Read once it runs, which the firewall step sees to: until then it has no rules to read
                case DetectionResult.UseFirewalld fwd when !fwd.needsStart() -> planRedirectFirewalld(gatewayIp, firewalldRead(FWD_LIST_DIRECT));
                default -> planRedirectFirewalld(gatewayIp, "");
            });
        }
        parts.add(planHostSysctls(init.hostFile(SYSCTL_CONF)));
        parts.add(planKsm());
        if (!CertificateAuthority.exists()) {
            parts.add(InitPlan.Assessment.of(List.of(InitPlan.Change.write("Generate the MITM CA certificate",
                    InitPlan.Who.NONE, Environment.configDir().resolve("ca.crt").toString()))));
        }
        return InitPlan.combine(parts);
    }

    static String configFile() {
        return Environment.configDir().resolve("config.yaml").toString();
    }

    /** Whether the PREROUTING redirect was written and applied. */
    boolean configureMitmProxyUfw(String gatewayIp, String beforeRules) {
        if (beforeRules.isEmpty()) {
            BuildOutput.warn("Could not read /etc/ufw/before.rules.", "Skipping the PREROUTING redirect.");
            return false;
        }
        boolean hasRedirect = UfwCheck.hasPreRoutingRedirect(beforeRules, ProxyConfig.DEFAULT_MITM_PORT, gatewayIp);

        if (hasRedirect) {
            BuildOutput.step("PREROUTING redirect already configured (" + gatewayIp + ":443 -> "
                    + ProxyConfig.DEFAULT_MITM_PORT + ").");
        } else {
            var staleIp = UfwCheck.extractRedirectGatewayIp(beforeRules, ProxyConfig.DEFAULT_MITM_PORT);
            if (staleIp != null) {
                BuildOutput.note("Replacing the stale PREROUTING redirect (old gateway " + staleIp + ")");
            }
            var subnet = CidrUtils.deriveSubnet(gatewayIp);
            var natBlock = UfwCheck.generateNatBlock(gatewayIp, subnet,
                    ProxyConfig.CONTAINER_FACING_PORT, ProxyConfig.DEFAULT_MITM_PORT);
            var content = UfwCheck.insertNatBlock(beforeRules, natBlock);
            // The reload runs even when the write failed (&, not &&)
            return BuildOutput.liveStep("Adding PREROUTING redirect (" + gatewayIp + ":443 -> "
                    + ProxyConfig.DEFAULT_MITM_PORT + " on incusbr0) to UFW before.rules",
                    () -> writeBeforeRules(content) & ran("sudo", "ufw", "reload"));
        }
        return true;
    }

    static final String[] SUBID_HINT = {
            "Containers use Linux user namespaces to isolate processes.",
            "This configures /etc/subuid and /etc/subgid so the",
            "container's root user maps to an unprivileged UID range",
            "on the host, preventing privilege escalation."};

    static final List<String> SUBID_FILES = List.of("/etc/subuid", "/etc/subgid");

    /**
     * Each entry the mappings need, and the older one it replaces: the second aligns with Zabbly
     * Incus packages, which set root:1000000:1000000000.
     */
    static final List<String[]> SUBID_ENTRIES = List.of(
            new String[]{"root:1000:1", null},
            new String[]{"root:1000000:1000000000", "root:1000000:65536"});

    /** What {@link #configureSubuidSubgid} would change, from the same {@link #computeSubidUpdate}. */
    InitPlan.Assessment planSubids() {
        var changes = new ArrayList<InitPlan.Change>();
        var asks = false;
        for (var path : SUBID_FILES) {
            var original = readIfReadable(init.hostFile(path));
            if (original == null) return InitPlan.Assessment.unknown("Could not read " + path + ".");
            var content = original;
            var whats = new ArrayList<String>();
            for (var entry : SUBID_ENTRIES) {
                var result = computeSubidUpdate(content, entry[0], entry[1]);
                switch (result.action()) {
                    case UNCHANGED -> {}
                    case UPDATED -> {
                        content = result.newContent();
                        whats.add("Add " + entry[0] + " to " + path);
                    }
                    case NEEDS_CONFIRMATION -> {
                        asks = true;
                        content = replaceSubidLine(content, result.conflictingEntry(), entry[0]);
                        whats.add("Replace " + result.conflictingEntry() + " with " + entry[0] + " in " + path + ", if you agree");
                    }
                }
            }
            if (!whats.isEmpty()) {
                changes.add(new InitPlan.Change(String.join("; ", whats), InitPlan.Who.SUDO, null, path)
                        .scripted(subidWrite(path, content)));
            }
        }
        if (!changes.isEmpty()) {
            changes.add(InitPlan.Change.sudo("Restart Incus to apply the mappings", "sudo systemctl restart incus"));
        }
        var plan = InitPlan.Assessment.of(changes);
        return asks ? plan.asking() : plan;
    }

    /** The root lines writing {@code content} to {@code path} as {@link #writeSubidFile} does: its mode kept, swapped in whole. */
    private static List<String> subidWrite(String path, String content) {
        var delimiter = InitPlan.Change.delimiter(content);
        var lines = new ArrayList<String>();
        lines.add("cat > " + path + ".isx-tmp <<'" + delimiter + "'");
        lines.addAll(content.lines().toList());
        lines.add(delimiter);
        lines.add("chmod --reference=" + path + " " + path + ".isx-tmp");
        lines.add("mv " + path + ".isx-tmp " + path);
        return lines;
    }

    void configureSubuidSubgid() {
        if (init.noSudo()) {
            BuildOutput.note(DONE_BY_SCRIPT);
            return;
        }
        boolean changed = false;
        for (var path : SUBID_FILES) {
            for (var entry : SUBID_ENTRIES) changed |= ensureSubidEntry(path, entry[0], entry[1]);
        }
        if (changed) {
            BuildOutput.liveStep("Restarting Incus to apply idmap changes", () -> runHost("sudo", "systemctl", "restart", "incus") == 0);
        }
        BuildOutput.step("subuid/subgid configured.");
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
        return content.replaceAll("(?m)^" + Pattern.quote(oldLine) + "$", Matcher.quoteReplacement(newLine)); // regex at run time: the line to replace
    }

    private boolean ensureSubidEntry(String path, String entry, String oldEntry) {
        String content;
        try {
            content = Files.readString(java.nio.file.Path.of(path));
        } catch (IOException e) {
            BuildOutput.warn("Could not read " + path + ": " + BaseCommand.failure(e));
            return false;
        }

        var result = computeSubidUpdate(content, entry, oldEntry);

        return switch (result.action()) {
            case UNCHANGED -> false;
            case UPDATED -> writeSubidFile(path, result.newContent());
            case NEEDS_CONFIRMATION -> {
                // The entry is the host file's own text: shown as text
                var conflicting = BuildOutput.guestText(result.conflictingEntry());
                var prompts = init.prompts();
                if (prompts != null) {
                    prompts.say(path + " contains an unexpected entry: " + conflicting);
                    prompts.say("incus-spawn expects: " + entry);
                    if (askYesNo(prompts, styled(BOLD + YELLOW, "Replace it?"), false)) {
                        yield writeSubidFile(path, replaceSubidLine(content, result.conflictingEntry(), entry));
                    }
                    // The entries were just shown with the question
                    BuildOutput.warn("Left " + path + " as it is: containers may not start correctly.");
                } else {
                    BuildOutput.warn("Left " + path + " as it is: containers may not start correctly.",
                            "It holds " + result.conflictingEntry() + "; incus-spawn expects: " + entry);
                }
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
            BuildOutput.warn("Failed to write " + path);
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

        // Check if we can talk to the Incus daemon
        var connectivity = init.incus.checkConnectivity();
        if (connectivity != null) {
            if (connectivity.contains("not running") || connectivity.contains("Connection refused")
                    || connectivity.contains("not accepting connections")) {
                BuildOutput.error("Cannot connect to the Incus daemon — it does not appear to be running.",
                        "Enable and start it with: sudo systemctl enable --now incus",
                        "Then re-run 'isx init' to continue.");
                System.exit(1);
            } else if (connectivity.contains("permission denied") || connectivity.contains("newgrp")) {
                BuildOutput.error("Cannot connect to the Incus daemon.",
                        "This usually means the 'incus-admin' group membership is not active in this shell.",
                        "Run 'newgrp incus-admin', or log out and log back in.",
                        "Then re-run 'isx init' to continue.");
                System.exit(1);
            }
            // Unknown error — continue anyway (daemon may start during init)
        }

        // Skip admin init only if Incus genuinely already has a storage pool. Note: probeCowPool()
        // .listed() means "the list call succeeded", NOT "a pool exists" — using it here skipped
        // admin init on every fresh daemon, leaving no default profile / no incusbr0 bridge.
        if (init.incus.hasStoragePool()) {
            BuildOutput.step("Incus already initialized.");
        } else {
            var exitCode = runHost("sudo", "incus", "admin", "init", "--minimal");
            if (exitCode == 0) {
                BuildOutput.step("Incus initialized with default storage pool and network.");
            } else {
                BuildOutput.warn("Incus initialization may have failed.", "Check 'incus storage list'.");
            }
        }

        // 'incus admin init --minimal' normally creates the incusbr0 bridge, but on some
        // distros/versions it doesn't — and when a storage pool already exists we skip the
        // admin init entirely, so the bridge may never be created. Later steps (bridge subnet
        // check, MITM proxy) hard-fail without it. Ensure it exists here; this is a no-op when
        // the bridge is already present, and checkBridgeSubnet() fixes any VPN subnet conflict.
        if (init.incus.createBridgeIfMissing("incusbr0", VmManager.gatewayIp())) {
            BuildOutput.step("Bridge 'incusbr0' was missing — created it ("
                    + VmManager.gatewayIp() + "/24).");
        }

        checkStorageDriver();
        configureBtrfsUsageAccess();
        ensureDefaultProfile();
        checkBridgeSubnet();
    }

    private static final String BTRFS_SUDOERS = "/etc/sudoers.d/incus-spawn-btrfs";

    /** The commands the btrfs sudoers rule permits on {@code pool}'s mount, one entry each. */
    static List<String> btrfsUsageCommands(String pool) {
        var btrfsPath = HostPath.find("btrfs");
        if (btrfsPath == null) btrfsPath = "/usr/sbin/btrfs";     // fall back to the usual location
        var mount = "/var/lib/incus/storage-pools/" + pool;
        // Permit both flavours of the qgroup read: the plain form (cheap, for periodic sampling) and
        // the --sync form (forces a commit, for the accuracy-critical read right after a build). sudo
        // matches the argument vector exactly, so each form needs its own entry. `quota rescan` is
        // the one non-read: it only rebuilds the accounting counters (never touches data) and is how
        // isx repairs the `inconsistent` state that would otherwise freeze every size it shows.
        return List.of(
                btrfsPath + " qgroup show -re --raw " + mount,
                btrfsPath + " qgroup show -re --raw --sync " + mount,
                btrfsPath + " subvolume list " + mount,
                btrfsPath + " quota rescan " + mount);
    }

    static String btrfsSudoersContent(String pool) {
        return System.getProperty("user.name") + " ALL=(root) NOPASSWD: "
                + String.join(", ", btrfsUsageCommands(pool)) + "\n";
    }

    /**
     * Whether the btrfs rule is in place for {@code pool}, or null when it was not checked: the file
     * is {@code 0440 root}, and the plan never runs sudo to read it (Sanne on #2091), so it says so
     * instead ({@link #btrfsRuleNotChecked}). The run rewrites a rule it cannot read, to the same text.
     */
    Boolean btrfsUsageGranted(String pool) {
        var content = readIfReadable(init.hostFile(BTRFS_SUDOERS));
        return content == null ? null : content.equals(btrfsSudoersContent(pool));
    }

    /** The rule the plan could not read: what was skipped and why, what it should hold, and how to check it. */
    static InitPlan.Assessment btrfsRuleNotChecked(String pool) {
        return InitPlan.Assessment.notChecked("Not checked: only root can read " + BTRFS_SUDOERS
                        + ", and the plan never runs sudo. It should hold '" + btrfsSudoersContent(pool).strip()
                        + "'. isx init writes it with sudo whenever it cannot read it.",
                btrfsAccess(pool), List.of("sudo cat " + BTRFS_SUDOERS));
    }

    static final String[] STORAGE_HINT = {
            "Initializes the Incus daemon with a network bridge and",
            "storage pool. If no copy-on-write (btrfs) pool exists,",
            "one is created — enabling instant, space-efficient clones",
            "when you branch containers."};

    private static final String ADMIN_INIT = "sudo incus admin init --minimal";
    /** The pool {@link #ADMIN_INIT} makes, and gives the default profile's root disk. */
    private static final String ADMIN_INIT_POOL = "default";

    /** The move {@link #upgradeProfilePoolIfNeeded} makes, onto a CoW pool that exists or the run creates. */
    private static InitPlan.Change moveRootDisk(String from, String to) {
        return InitPlan.Change.run("Move the default profile's root disk from pool '" + from + "' to '" + to + "'",
                InitPlan.Who.NONE, null);
    }

    private InitPlan.Change createCowPool(boolean skipTrim) {
        var hostFs = init.filesystemOf(CowPoolBacking.POOLS_DIR);
        var command = CowPoolBacking.createCommand(hostFs, skipTrim);
        var change = InitPlan.Change.sudo("Create the copy-on-write storage pool '" + CowPoolBacking.POOL_NAME + "'",
                String.join(" ", command));
        // A loop file goes in the disks directory, which the run makes first. The run carries on
        // without a CoW pool when the create fails, warning, and so does the script
        var create = asRoot(command) + " || echo 'Could not create the copy-on-write pool: run isx doctor for why.' >&2";
        return change.scripted("btrfs".equals(hostFs) ? List.of(create)
                : List.of("mkdir -p " + CowPoolBacking.DISKS_DIR, create));
    }

    /** The pool a host gets once the run made one: the one admin init makes on btrfs, else the CoW pool beside it. */
    private String poolToCome() {
        return "btrfs".equals(init.filesystemOf(CowPoolBacking.POOLS_DIR)) ? ADMIN_INIT_POOL : CowPoolBacking.POOL_NAME;
    }

    /**
     * The btrfs sudoers rule for {@code pool}, as {@link #configureBtrfsUsageAccess} installs it: checked by
     * {@code visudo} before it is put in place, since a malformed rule can lock the user out of sudo.
     */
    static InitPlan.Change btrfsAccess(String pool) {
        var content = btrfsSudoersContent(pool);
        var delimiter = InitPlan.Change.delimiter(content);
        var lines = new ArrayList<String>();
        lines.add("tmp=$(mktemp)");
        lines.add("cat > \"$tmp\" <<'" + delimiter + "'");
        lines.addAll(content.lines().toList());
        lines.add(delimiter);
        lines.add("visudo -cf \"$tmp\"");
        lines.add("install -m 0440 -o root -g root \"$tmp\" " + BTRFS_SUDOERS);
        lines.add("rm -f \"$tmp\"");
        return new InitPlan.Change("Let isx read the pool's btrfs disk usage without a password",
                InitPlan.Who.SUDO, null, BTRFS_SUDOERS).scripted(lines);
    }

    /** What {@link #initializeIncus} would change, read through the Incus API alone. */
    InitPlan.Assessment planStorageAndNetwork() {
        if (!init.hostHasCommand("incus")) {
            var changes = new ArrayList<>(List.of(
                    InitPlan.Change.sudo("Initialize Incus with a storage pool and the incusbr0 bridge", ADMIN_INIT)));
            // On btrfs the pool that makes is btrfs already; elsewhere it is a directory, beside which init makes one
            if (!"btrfs".equals(init.filesystemOf(CowPoolBacking.POOLS_DIR))) {
                changes.add(createCowPool(false));
                changes.add(moveRootDisk(ADMIN_INIT_POOL, CowPoolBacking.POOL_NAME));
            }
            changes.add(btrfsAccess(poolToCome()));
            return InitPlan.Assessment.of(changes)
                    .noting("Incus is not installed yet; the rest of its setup is read once it is.");
        }
        if (!init.incusReachable()) {
            return InitPlan.Assessment.unknown("Incus does not answer this process: " + init.incusConnectivity().strip());
        }
        try {
            var incus = init.incus;
            InitPlan.Assessment btrfsUnchecked = null;
            var changes = new ArrayList<InitPlan.Change>();
            var hasPool = incus.hasStoragePool();
            if (!hasPool) {
                changes.add(InitPlan.Change.sudo("Initialize Incus with a storage pool and the incusbr0 bridge", ADMIN_INIT));
            } else if (!incus.networkExists(BridgeAddress.BRIDGE)) {
                changes.add(InitPlan.Change.run("Create the incusbr0 bridge (" + VmManager.gatewayIp() + "/24)",
                        InitPlan.Who.NONE, null));
            }
            // One read of the default profile, for the root-disk checks below
            var devices = hasPool ? incus.profileDevices("default") : null;
            var cow = incus.probeCowPool();
            if (cow.listed() && cow.poolName() == null) {
                // With no pool yet, admin init makes one, btrfs already on a btrfs host, as above
                if (hasPool || !"btrfs".equals(init.filesystemOf(CowPoolBacking.POOLS_DIR))) {
                    changes.add(createCowPool(incus.hasApiExtension(STORAGE_CREATE_OPTIONS_EXTENSION)));
                    // The default profile then follows it: findUsablePool picks it, and the profile check moves the disk
                    var rootPool = hasPool ? IncusClient.rootDiskPoolFromDevices(devices) : ADMIN_INIT_POOL;
                    if (rootPool != null) changes.add(moveRootDisk(rootPool, CowPoolBacking.POOL_NAME));
                }
                changes.add(btrfsAccess(hasPool ? CowPoolBacking.POOL_NAME : poolToCome()));
            } else if (cow.poolName() != null && cow.isBtrfs()) {
                var granted = btrfsUsageGranted(cow.poolName());
                if (granted == null) btrfsUnchecked = btrfsRuleNotChecked(cow.poolName());
                else if (!granted) changes.add(btrfsAccess(cow.poolName()));
            }
            var pool = incus.findUsablePool();
            if (pool != null && hasPool) {
                var missing = incus.missingProfileDevices("default", pool, BridgeAddress.BRIDGE);
                if (!missing.isEmpty()) {
                    changes.add(InitPlan.Change.run("Add " + String.join(" and ", missing.keySet())
                            + " to the default profile", InitPlan.Who.NONE, null));
                }
                var upgrade = profilePoolUpgrade(pool, devices);
                if (upgrade != null) changes.add(moveRootDisk(upgrade.from(), upgrade.to()));
            }
            if (incus.networkExists(BridgeAddress.BRIDGE)) {
                var conflict = BridgeSubnetCheck.detect(incus, init.hostRoutes());
                if (conflict.conflictDetected() && conflict.newSubnet() != null) {
                    changes.add(InitPlan.Change.run("Move the bridge from " + conflict.oldSubnet() + " to "
                            + conflict.newSubnet() + ", off the route " + conflict.conflictingRoute()
                            + ", and the instances on it", InitPlan.Who.NONE, null));
                } else if (conflict.conflictDetected()) {
                    // The step finds no free subnet and only warns: the overlap stays for a person to resolve
                    changes.add(InitPlan.Change.run("Move the bridge from " + conflict.oldSubnet() + " off the route "
                            + conflict.conflictingRoute() + " yourself: no free subnet was found", InitPlan.Who.NONE, null));
                }
            }
            var plan = InitPlan.Assessment.of(changes);
            return btrfsUnchecked == null ? plan : InitPlan.combine(List.of(plan, btrfsUnchecked));
        } catch (RuntimeException e) {
            return InitPlan.Assessment.unknown("Could not read Incus: " + BaseCommand.failure(e));
        }
    }

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
        // Root's file, which the script wrote under --no-sudo (#2074)
        if (Platform.isMacOS() || init.noSudo()) return;
        var probe = init.incus.probeCowPool();
        if (probe.poolName() == null || !probe.isBtrfs()) return;
        var pool = probe.poolName();

        var content = btrfsSudoersContent(pool);

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
                BuildOutput.warn("Generated btrfs sudoers rule failed validation; skipping.");
                Files.deleteIfExists(tempFile);
                return;
            }
            if (runHostQuiet("sudo", "install", "-m", "0440", "-o", "root", "-g", "root",
                    tempFile.toString(), BTRFS_SUDOERS) == 0) {
                BuildOutput.step("Enabled per-template disk accounting (scoped btrfs read).");
            }
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            BuildOutput.warn("Could not configure btrfs disk accounting: " + BaseCommand.failure(e));
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
            BuildOutput.warn("No storage pool found — skipping the default profile check.");
            return;
        }
        try {
            var added = init.incus.ensureDefaultProfileDevices(pool, "incusbr0");
            if (!added.isEmpty()) {
                BuildOutput.step("Default profile was incomplete — added " + added
                        + " (root disk on pool '" + pool + "').");
            }
            upgradeProfilePoolIfNeeded(pool);
        } catch (Exception e) {
            BuildOutput.warn("Could not repair the default profile: " + BaseCommand.failure(e),
                    "If instance creation fails with 'No root device could be found', run:",
                    "incus profile device add default root disk path=/ pool=" + pool,
                    "If containers come up without networking, also run:",
                    "incus profile device add default eth0 nic network=incusbr0");
        }
    }

    private void upgradeProfilePoolIfNeeded(String desiredPool) {
        var devices = init.incus.profileDevices("default");
        var upgrade = profilePoolUpgrade(desiredPool, devices);
        if (upgrade != null && init.incus.updateProfileRootDiskPool("default", devices, desiredPool)) {
            BuildOutput.step("Default profile root disk upgraded from pool '" + upgrade.from()
                    + "' (" + upgrade.fromDriver() + ") to '" + upgrade.to() + "' (" + upgrade.toDriver() + ").");
        }
    }

    /** A move of the default profile's root disk to a pool with copy-on-write. */
    record PoolUpgrade(String from, String fromDriver, String to, String toDriver) {}

    /**
     * The move of the default profile's root disk to {@code desiredPool}, or null when it stays:
     * only a disk on a pool without copy-on-write moves, and only to one with it.
     */
    private PoolUpgrade profilePoolUpgrade(String desiredPool, JqValue devices) {
        var currentPool = IncusClient.rootDiskPoolFromDevices(devices);
        if (currentPool == null || currentPool.equals(desiredPool)) return null;

        var pools = init.incus.listPools();
        var currentDriver = pools.getOrDefault(currentPool, "");
        var desiredDriver = pools.getOrDefault(desiredPool, "");
        if (IncusClient.isCowDriver(currentDriver) || !IncusClient.isCowDriver(desiredDriver)) return null;
        return new PoolUpgrade(currentPool, currentDriver, desiredPool, desiredDriver);
    }

    /**
     * The Incus API extension that added {@code btrfs.create_options} (Incus 7.1). Older daemons,
     * such as the 6.x in Fedora's and Ubuntu's repositories, reject the whole pool creation over
     * the unknown key (#820).
     */
    static final String STORAGE_CREATE_OPTIONS_EXTENSION = "storage_create_options";

    private void checkStorageDriver() {
        var cowProbe = init.incus.probeCowPool();
        // Guard against transient/permission/daemon errors: if we can't list pools, don't
        // misinterpret that as "no CoW pool" and spuriously try to create one.
        if (!cowProbe.listed()) return;
        var anyCow = cowProbe.poolName() != null;

        if (!anyCow) {
            BuildOutput.step("No copy-on-write storage pool detected.");
            var hostFs = CowPoolBacking.filesystemOf(CowPoolBacking.POOLS_DIR);
            BuildOutput.stepStart("Creating a copy-on-write storage pool");
            // Its sudo inherits the terminal, and may ask for a password there
            BuildOutput.releaseTerminal();
            var created = CowPoolBacking.create(CowPoolBacking::filesystemOf,
                    () -> init.incus.hasApiExtension(STORAGE_CREATE_OPTIONS_EXTENSION),
                    CowPoolBacking::runCapturingStderr,
                    () -> runHostQuiet("sudo", "mkdir", "-p", CowPoolBacking.DISKS_DIR),
                    // The explanation can quote what the command printed: shown as text
                    (msg, details) -> BuildOutput.warn(msg, details.stream().map(BuildOutput::guestText).toArray(String[]::new)));
            var create = created.result();
            if (create.exitCode() == 0) {
                BuildOutput.stepDone();
                if (created.subvolume()) {
                    BuildOutput.step("Created btrfs storage pool 'cow' as a subvolume of the host filesystem.");
                    BuildOutput.step("It has no size cap of its own: it shares that filesystem's free space.");
                } else {
                    BuildOutput.step("Created btrfs storage pool 'cow' (100 GiB, thin-provisioned).");
                    BuildOutput.step("Resize with: sudo incus storage set cow size=200GiB");
                }
                BuildOutput.step("All new instances will use it automatically.");
            } else {
                BuildOutput.abandonStep();
                // The banner prints its lines as given, and the explanation can quote what the command printed
                var lines = new ArrayList<String>(CowPoolBacking.failureExplanation(create.stderr()).stream()
                        .map(BuildOutput::guestText).toList());
                lines.add("");
                lines.add("Without a CoW pool, clones and branches will be FULL COPIES,");
                lines.add("using significantly more disk space and taking longer to create.");
                lines.add("");
                lines.add("You can create one manually later:");
                lines.add(styled(BOLD, String.join(" ", CowPoolBacking.createCommand(hostFs, false))));
                lines.add("incus-spawn will automatically use it for all new instances.");
                BuildOutput.warnBanner("Failed to create btrfs storage pool!", lines.toArray(String[]::new));

                var prompts = init.prompts();
                if (prompts != null) {
                    if (!askYesNo(prompts, styled(BOLD + YELLOW, "Continue without CoW storage?"), false)) {
                        BuildOutput.step("Aborted. Re-run 'isx init' after creating a CoW storage pool.");
                        System.exit(0);
                    }
                }
            }
        }
    }

    private void checkBridgeSubnet() {
        BuildOutput.stepStart("Checking bridge subnet for VPN conflicts");
        try {
            var result = BridgeSubnetCheck.detectAndFix(init.incus);
            BuildOutput.stepDone();
            if (result.conflictDetected()) {
                BuildOutput.step("Detected subnet conflict: bridge " + result.oldSubnet()
                        + " overlaps with route: " + result.conflictingRoute());
                if (result.newSubnet() != null) {
                    BuildOutput.step("Reconfigured bridge to " + result.newSubnet()
                            + " to avoid conflict.");
                    var migrated = InstanceNetwork.migrateAllInstancesToNewSubnet(init.incus);
                    if (migrated > 0) {
                        BuildOutput.step("Migrated network config for " + migrated
                                + " instance" + (migrated == 1 ? "" : "s") + ".");
                    }
                } else {
                    BuildOutput.warn("Could not find a non-conflicting subnet.",
                            "You may need to manually set the bridge address:",
                            "incus network set incusbr0 ipv4.address 172.20.0.1/24");
                }
            } else {
                BuildOutput.step("Bridge subnet is clear of VPN route conflicts.");
            }
        } catch (Exception e) {
            BuildOutput.abandonStep();
            BuildOutput.warn("Could not check bridge subnet: " + BaseCommand.failure(e));
        }
    }

    /**
     * After {@code isx doctor} moved the bridge so its gateway is {@code gatewayIp} (#1203): redo
     * only what names the bridge's address -- the gateway {@code config.yaml} caches and the
     * PREROUTING redirect -- on {@code firewall}, the one already running, chosen as init chooses it.
     * Installing or enabling a firewall stays init's. Whether the redirect was written.
     */
    boolean followBridgeMove(String gatewayIp, DetectionResult firewall) {
        var config = SpawnConfig.load();
        config.setIncusBridgeGateway(gatewayIp);
        config.save();
        return redirectToMovedGateway(gatewayIp, firewall, UfwCheck::readBeforeRules);
    }

    /**
     * The redirect half of {@link #followBridgeMove}. On UFW the isx NAT block is replaced whole,
     * so the old gateway's redirect and the old subnet's masquerade go with it; on firewalld the
     * stale redirect is removed. The FORWARD and trust rules name the interface, not the address.
     */
    boolean redirectToMovedGateway(String gatewayIp, DetectionResult firewall,
                                   java.util.function.Supplier<String> beforeRules) {
        return switch (firewall) {
            case DetectionResult.UseFirewalld fwd when !fwd.needsStart() -> configureMitmProxyFirewalld(gatewayIp);
            case DetectionResult.UseUfw u -> configureMitmProxyUfw(gatewayIp, beforeRules.get());
            default -> {
                BuildOutput.warn("No firewall is running to redirect the proxy's port on the new gateway.");
                yield false;
            }
        };
    }
}
