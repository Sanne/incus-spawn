package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.util.Privilege;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx init --no-sudo} (#2074): isx runs nothing as root; the steps that need root are
 * written as one script for the person, the same lines {@code isx init --plan} reports, and init
 * carries on once the script ran.
 */
@ExtendWith(IsolatedHome.class)
class InitNoSudoTest {

    private static final String GATEWAY = "10.1.2.1";

    @AfterEach
    void allowAgain() {
        Privilege.allow();
    }

    private static InitPlanTest.PlanInit fresh(Path root) {
        var init = new InitPlanTest.PlanInit();
        init.root = root;
        init.noSudo = true;
        // A fresh Fedora host: dnf and nothing else isx needs
        init.commands = Set.of("dnf");
        init.incus = new FakeIncusDaemon().client();
        init.firewall = new dev.incusspawn.incus.FirewallDetector.DetectionResult.NeitherInstalled();
        return init;
    }

    private static void subids(Path root) throws Exception {
        Files.createDirectories(root.resolve("etc"));
        Files.writeString(root.resolve("etc/subuid"), "user:100000:65536\n");
        Files.writeString(root.resolve("etc/subgid"), "user:100000:65536\n");
    }

    /** A fresh host is one script: everything from the packages to the redirect, in the run's order. */
    @Test
    void aFreshHostGetsOneScriptAndTheExitThatWaitsOnThePerson(@TempDir Path root) throws Exception {
        subids(root);
        var init = fresh(root);
        var recorded = RecordingOutput.during(() -> assertEquals(InitCommand.EXIT_WAITING_ON_PERSON,
                init.privilegedStepsLeft().getResultValue()));
        var script = Files.readString(Environment.privilegedInitScript());
        var order = List.of("dnf install -y openssl openssh-clients btrfs-progs", "dnf install -y incus",
                "systemctl enable --now incus", "usermod -aG incus-admin", "mv /etc/subuid.isx-tmp /etc/subuid",
                "systemctl restart incus", "incus admin init --minimal", "incus storage create cow btrfs",
                "visudo -cf", "dnf install -y firewalld", "firewall-cmd --zone=trusted --change-interface=incusbr0",
                "gw=$(incus network get incusbr0 ipv4.address)", "-d \"$gw\" -p tcp --dport 443",
                "install -m 0644 -o root -g root /dev/stdin /etc/sysctl.d/99-incus-spawn.conf", "cat >> /var/lib/isx/init-applied");
        var at = -1;
        for (var line : order) {
            var next = script.indexOf(line, at + 1);
            assertTrue(next > at, "'" + line + "' after the line before it, in:\n" + script);
            at = next;
        }
        assertTrue(script.startsWith("#!/bin/sh\n"), script);
        // incus storage create and network create read a YAML config from a stdin that is not a
        // terminal: without this, the script run from an agent's shell waits forever on a real host
        var detached = script.indexOf("\n" + InitPlan.DETACH_STDIN + "\n");
        assertTrue(detached > 0 && detached < script.indexOf("dnf install"), "stdin detached before any command:\n" + script);
        assertFalse(script.lines().anyMatch(l -> l.startsWith("sudo ")), "the script runs as root, so names no sudo:\n" + script);
        assertTrue(recorded.lines().stream().anyMatch(l -> l.contains("sudo sh " + Environment.privilegedInitScript())),
                recorded.lines().toString());
        assertTrue(init.queried.stream().noneMatch(c -> c.startsWith("sudo")), init.queried.toString());
    }

    /** The script is the plan's: every step's sudo_script, in the plan's order, and nothing else of isx's. */
    @Test
    void theScriptHoldsExactlyWhatThePlanReports(@TempDir Path root) throws Exception {
        subids(root);
        var init = fresh(root);
        RecordingOutput.during(init::privilegedStepsLeft);
        var script = Files.readString(Environment.privilegedInitScript());
        var planned = InitCommand.planEntries(init.planSteps(false)).stream()
                .flatMap(e -> ((List<?>) InitPlan.record(e).get("sudo_script")).stream()).map(String::valueOf).toList();
        assertFalse(planned.isEmpty());
        planned = planned.stream().filter(l -> !l.isEmpty()).toList();
        // Between the root check and the record, the script's own lines are comments alone
        var start = script.indexOf("{ echo \"Run this as root");
        var end = script.indexOf("\n# Record what ran");
        var titles = init.planSteps(false).stream().map(step -> "# " + step.title() + ": ").toList();
        var body = script.substring(script.indexOf('\n', start) + 1, end).lines()
                .filter(l -> !l.isEmpty() && !l.startsWith("# - ") && titles.stream().noneMatch(l::startsWith))
                // The script's own line, before every step's: no command reads the person's input
                .filter(l -> !l.equals(InitPlan.DETACH_STDIN)).toList();
        assertEquals(planned, body);
    }

    /** What only root can check counts as made once the script recorded it; nothing is left to script. */
    @Test
    void aSetUpHostWhoseScriptRanCarriesOn(@TempDir Path root) throws Exception {
        var init = new InitPlanTest.PlanInit();
        init.root = root;
        init.noSudo = true;
        Files.createDirectories(root.resolve("etc"));
        Files.writeString(root.resolve("etc/subuid"), "root:1000:1\nroot:1000000:1000000000\n");
        Files.writeString(root.resolve("etc/subgid"), "root:1000:1\nroot:1000000:1000000000\n");
        Files.createDirectories(root.resolve("etc/sysctl.d"));
        Files.writeString(root.resolve("etc/sysctl.d/99-incus-spawn.conf"), HostSetup.SYSCTL_CONTENT);
        init.answers.put("id -nG " + System.getProperty("user.name"), new InitCommand.Query(0, "u incus-admin"));
        init.answers.put("systemctl is-active --quiet incus", new InitCommand.Query(0, ""));
        init.polkit = false;
        init.commands = Set.of("dnf", "incus", "openssl", "ssh-keygen", "btrfs", "firewall-cmd", "pkcheck");
        init.proxyActive = true;
        init.incus = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"))
                .pool("cow", "btrfs")
                .profile("default", Map.of("root", Map.of("type", "disk", "path", "/", "pool", "cow"),
                        "eth0", Map.of("type", "nic", "network", "incusbr0")))
                .client();
        Files.createDirectories(Environment.configDir());
        Environment.markInitComplete();
        // Before the script: the btrfs rule and the firewall's rules are root's to read, so they are scripted
        RecordingOutput.during(() -> assertEquals(InitCommand.EXIT_WAITING_ON_PERSON, init.privilegedStepsLeft().getResultValue()));
        var script = Files.readString(Environment.privilegedInitScript());
        var keys = script.lines().filter(l -> l.matches("[0-9a-f]{24}")).toList();
        assertEquals(3, keys.size(), script);
        // The script ran and left its record
        Files.createDirectories(root.resolve("var/lib/isx"));
        Files.write(root.resolve("var/lib/isx/init-applied"), keys);
        RecordingOutput.during(() -> assertNull(init.privilegedStepsLeft()));
    }

    /** A restart an earlier script made is scripted again with the new change of its step, which it applies. */
    @Test
    void aStepWithANewChangeIsScriptedWhole(@TempDir Path root) throws Exception {
        subids(root);
        var init = fresh(root);
        Files.createDirectories(root.resolve("var/lib/isx"));
        Files.writeString(root.resolve("var/lib/isx/init-applied"), InitPlan.key(List.of("systemctl restart incus")) + "\n");
        RecordingOutput.during(init::privilegedStepsLeft);
        var script = Files.readString(Environment.privilegedInitScript());
        assertTrue(script.indexOf("systemctl restart incus") > script.indexOf("mv /etc/subgid.isx-tmp /etc/subgid"), script);
    }

    /** Rules a fresh host's script made one by one count as made when a later plan lists them as one change. */
    @Test
    void singleLineChangesTheScriptMadeCoverOneChangeOfTheirLines() {
        var lines = List.of("firewall-cmd --reload", "firewall-cmd --zone=trusted --add-masquerade --permanent");
        var together = InitPlan.Change.sudo("Add the rules", null).scripted(lines).unchecked();
        var applied = Set.of(InitPlan.key(List.of(lines.get(0))), InitPlan.key(List.of(lines.get(1))));
        assertTrue(InitPlan.made(together, applied));
        assertFalse(InitPlan.made(together, Set.of(InitPlan.key(List.of(lines.get(0))))));
        assertTrue(InitPlan.made(together, Set.of(InitPlan.key(together))));
        assertFalse(InitPlan.made(InitPlan.Change.sudo("By hand", null), Set.of(InitPlan.key(List.of()))));
    }

    /** A step isx cannot write as a script is named for the person, and nothing is written. */
    @Test
    void aStepWithoutAScriptIsThePersonsAndStopsInit(@TempDir Path root) throws Exception {
        subids(root);
        var init = fresh(root);
        init.commands = Set.of();
        var recorded = RecordingOutput.during(() -> assertEquals(1, init.privilegedStepsLeft().getResultValue()));
        assertFalse(Files.exists(Environment.privilegedInitScript()));
        assertTrue(recorded.events().stream().anyMatch(e -> e.text() != null && e.text().contains("cannot write")),
                recorded.lines().toString());
    }

    /** Under --no-sudo the steps that need root only say the script did them, and run nothing as root. */
    @Test
    void theRootStepsRunNothingAsRoot(@TempDir Path root) {
        var init = new InitPlanTest.PlanInit();
        init.root = root;
        init.noSudo = true;
        Privilege.forbid();
        var recorded = RecordingOutput.during(() -> {
            init.host().configureSubuidSubgid();
            init.host().configureFirewall();
        });
        assertEquals(List.of(), init.ran);
        assertEquals(2, recorded.events().stream().filter(e -> HostSetup.DONE_BY_SCRIPT.equals(e.text())).count());
    }

    // ── The guard ───────────────────────────────────────────────────────────────

    @Test
    void aForbiddenProcessRefusesEveryWayToElevate() {
        Privilege.forbid();
        for (var command : List.of(List.of("sudo", "-v"), List.of("/usr/bin/sudo", "true"), List.of("pkexec", "id"),
                List.of("doas", "id"), List.of("run0", "id"),
                List.of("sh", "-c", "printf x | sudo tee /etc/subuid > /dev/null"),
                List.of("sh", "-c", "true;sudo id"), List.of("sh", "-c", "x=$(sudo id)"),
                List.of("bash", "-c", "true && /usr/bin/sudo\tid"))) {
            assertThrows(Privilege.Refused.class, () -> Privilege.check(command), command.toString());
        }
        assertDoesNotThrow(() -> Privilege.check("firewall-cmd", "--list-all"));
        assertDoesNotThrow(() -> Privilege.check("sh", "-c", "echo sudoku"));
        Privilege.allow();
        assertDoesNotThrow(() -> Privilege.check("sudo", "-v"));
    }

    /** InitCommand's host runners ask the guard before they start anything. */
    @Test
    void initsRunnersAskTheGuard() {
        Privilege.forbid();
        var init = new InitCommand();
        assertThrows(Privilege.Refused.class, () -> InitCommand.runHost("sudo", "-v"));
        assertThrows(Privilege.Refused.class, () -> init.runHostQuiet("sudo", "true"));
        assertThrows(Privilege.Refused.class, () -> init.captureOutput("sudo", "true"));
        assertThrows(Privilege.Refused.class, () -> InitCommand.runHostCapturingExit("sudo", "true"));
        assertThrows(Privilege.Refused.class, () -> CowPoolBacking.runCapturingStderr("sudo", "true"));
    }
}
