package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.FirewallDetector.DetectionResult;
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

    /**
     * The script finds btrfs as the run does, on the user's PATH, and only as it runs: before
     * it, btrfs-progs may not be installed yet, and a rule naming the fallback would be scripted
     * again on the next run (found on a stock Fedora VM, #2081).
     */
    @Test
    void theBtrfsRuleNamesBtrfsWhereTheRunWillFindIt(@TempDir Path dir) throws Exception {
        var out = dir.resolve("rule");
        var lines = HostSetup.btrfsAccess("cow").script().stream()
                .filter(l -> !l.startsWith("visudo ") && !l.startsWith("rm -f "))
                .map(l -> l.startsWith("install -m 0440") ? "cat \"$tmp\" > " + out : l).toList();
        var process = new ProcessBuilder("sh", "-euc", String.join("\n", lines)).redirectErrorStream(true).start();
        var said = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), said);
        assertEquals(HostSetup.btrfsSudoersContent("cow"), Files.readString(out));
        assertFalse(String.join("\n", HostSetup.btrfsAccess("cow").script()).contains(HostSetup.btrfsSudoersContent("cow").strip()),
                "the path is read as the script runs, not written into it");
    }

    /**
     * Once the script recorded what only root can check, the plan and the summary report it done,
     * as --no-sudo reads it, not as root work left (#2081); the check commands stay for a person.
     */
    @Test
    void thePlanReadsTheScriptsRecord() {
        var change = HostSetup.btrfsAccess("cow");
        var unchecked = new InitPlan.Entry("storage-network", "Storage & Network", "",
                InitPlan.Assessment.notChecked("Not checked", change, List.of("sudo cat /etc/sudoers.d/incus-spawn-btrfs")));
        assertEquals(InitPlan.State.UNKNOWN, InitPlan.withRecord(List.of(unchecked), Set.of()).getFirst().assessment().state());
        var recorded = InitPlan.withRecord(List.of(unchecked), Set.of(InitPlan.key(change))).getFirst().assessment();
        assertEquals(InitPlan.State.DONE, recorded.state());
        assertEquals(InitPlan.Who.NONE, recorded.who());
        assertTrue(recorded.note().contains(InitPlan.APPLIED_RECORD), recorded.note());
        assertEquals(List.of("sudo cat /etc/sudoers.d/incus-spawn-btrfs"), recorded.checks());
    }

    /** With nothing left for root, the script is removed rather than left to be handed over again. */
    @Test
    void theScriptIsRemovedOnceNothingIsLeft(@TempDir Path root) throws Exception {
        var init = new InitPlanTest.PlanInit();
        init.root = root;
        init.noSudo = true;
        Files.createDirectories(root.resolve("etc"));
        for (var f : List.of("etc/subuid", "etc/subgid")) Files.writeString(root.resolve(f), "root:1000:1\nroot:1000000:1000000000\n");
        Files.createDirectories(root.resolve("etc/sysctl.d"));
        Files.writeString(root.resolve("etc/sysctl.d/99-incus-spawn.conf"), HostSetup.SYSCTL_CONTENT);
        Files.createDirectories(root.resolve("etc/sudoers.d"));
        Files.writeString(root.resolve("etc/sudoers.d/incus-spawn-btrfs"), HostSetup.btrfsSudoersContent("cow"));
        init.answers.put("id -nG " + System.getProperty("user.name"), new InitCommand.Query(0, "u incus-admin"));
        init.answers.put("systemctl is-active --quiet incus", new InitCommand.Query(0, ""));
        init.answers.put("firewall-cmd --zone=trusted --list-all", new InitCommand.Query(0, "interfaces: incusbr0\n  masquerade: yes"));
        init.answers.put("firewall-cmd --direct --get-all-rules", new InitCommand.Query(0, """
                ipv4 filter FORWARD 0 -i incusbr0 -j ACCEPT
                ipv4 filter FORWARD 0 -o incusbr0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
                ipv4 nat PREROUTING 0 -i incusbr0 -d 10.1.2.1 -p tcp --dport 443 -j REDIRECT --to-port 18443
                """));
        init.proxyActive = true;
        init.incus = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"))
                .pool("cow", "btrfs")
                .profile("default", Map.of("root", Map.of("type", "disk", "path", "/", "pool", "cow"),
                        "eth0", Map.of("type", "nic", "network", "incusbr0")))
                .client();
        Files.createDirectories(Environment.configDir());
        Environment.markInitComplete();
        Files.createDirectories(Environment.privilegedInitScript().getParent());
        Files.writeString(Environment.privilegedInitScript(), "#!/bin/sh\n");
        RecordingOutput.during(() -> assertNull(init.privilegedStepsLeft()));
        assertFalse(Files.exists(Environment.privilegedInitScript()));
    }

    @Test
    void underNoSudoTheDependenciesStepDoesNotPromiseSudoV(@TempDir Path root) {
        var init = new InitPlanTest.PlanInit();
        init.root = root;
        assertEquals(HostSetup.SUDO_FIRST, init.host().planDependencies().note());
        init.noSudo = true;
        assertEquals(HostSetup.NO_SUDO_FIRST, init.host().planDependencies().note());
    }

    // ── What the run itself finds is root's ────────────────────────────────────

    private static InitPlanTest.PlanInit setUp(Path root) throws Exception {
        var init = new InitPlanTest.PlanInit();
        init.root = root;
        init.noSudo = true;
        init.incus = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"))
                .pool("default", "dir").pool("cow", "btrfs")
                .profile("default", Map.of("root", Map.of("type", "disk", "path", "/", "pool", "cow"),
                        "eth0", Map.of("type", "nic", "network", "incusbr0")))
                .client();
        Files.createDirectories(Environment.configDir());
        return init;
    }

    /**
     * A bridge this run moves off a host route takes its redirect with it, which is root's: the run
     * hands that over rather than finishing on a proxy no container reaches (#2118 review).
     */
    @Test
    void aBridgeTheRunMovesHandsItsRedirectToThePerson(@TempDir Path root) throws Exception {
        var init = setUp(root);
        init.routes = List.of("10.1.0.0/20 dev vpn0");
        Privilege.forbid();
        RecordingOutput.during(() -> init.host().checkBridgeSubnet());
        var waiting = assertThrows(InitCommand.WaitingOnPerson.class,
                () -> RecordingOutput.during(() -> init.host().configureMitmProxy()));
        var lines = waiting.changes.stream().flatMap(p -> p.change().script().stream()).toList();
        assertTrue(lines.getFirst().contains("--remove-rule") && lines.getFirst().contains("-d " + GATEWAY), lines.toString());
        assertTrue(lines.contains("gw=$(incus network get incusbr0 ipv4.address)"), lines.toString());
    }

    /** On UFW the move rewrites isx's NAT block, under the old gateway's name, so the first script's record does not hold it. */
    @Test
    void aBridgeTheRunMovesOnUfwRewritesTheNatBlock(@TempDir Path root) throws Exception {
        var init = setUp(root);
        init.routes = List.of("10.1.0.0/20 dev vpn0");
        init.firewall = new DetectionResult.UseUfw();
        Privilege.forbid();
        RecordingOutput.during(() -> init.host().checkBridgeSubnet());
        var waiting = assertThrows(InitCommand.WaitingOnPerson.class,
                () -> RecordingOutput.during(() -> init.host().configureMitmProxy()));
        // isx's NAT block, replaced whole on the new gateway, takes the old redirect with it (#2074)
        var scripts = waiting.changes.stream().map(p -> p.change().script()).toList();
        assertEquals(1, scripts.size(), scripts.toString());
        assertEquals(UfwScript.redirectLines(), scripts.get(0).subList(1, scripts.get(0).size()));
        assertFalse(InitPlan.made(waiting.changes.get(0).change(), Set.of(InitPlan.key(UfwScript.redirectLines()))),
                "the first script's redirect is not the move");
    }

    /**
     * A move of the bridge is kept on disk until the script's record shows the redirect moved
     * after it: a script written over it, or not run, cannot lose it, and a host whose root work
     * was made another way (plain isx init) is not held at exit 75 by an old script (#2118 review).
     */
    @Test
    void aBridgeMoveIsKeptUntilTheScriptMovedTheRedirect(@TempDir Path root) throws Exception {
        var init = setUp(root);
        Files.createDirectories(Environment.initBridgeMovedFile().getParent());
        Files.writeString(Environment.initBridgeMovedFile(), GATEWAY + "\n");
        var move = init.host().movedRedirect();
        assertEquals(1, move.size());
        // The plan names it, so the script is still the plan's sudo_script
        assertTrue(init.host().planMitmProxy().changes().contains(move.getFirst().change()));
        assertTrue(move.getFirst().change().script().getFirst().contains("-d " + GATEWAY), move.toString());
        Files.createDirectories(root.resolve("var/lib/isx"));
        Files.write(root.resolve("var/lib/isx/init-applied"), List.of(InitPlan.key(move.getFirst().change())));
        assertEquals(List.of(), init.host().movedRedirect());
        assertTrue(Files.exists(Environment.initBridgeMovedFile()), "the plan reads it and changes nothing");
        init.host().forgetMadeBridgeMove();
        assertFalse(Files.exists(Environment.initBridgeMovedFile()), "forgotten once a run sees it made");
    }

    /** An old, unrun script does not hold a host at exit 75 when nothing is left: it is removed. */
    @Test
    void anUnrunScriptWithNothingLeftIsRemoved(@TempDir Path root) throws Exception {
        var script = Environment.privilegedInitScript();
        Files.createDirectories(script.getParent());
        Files.writeString(script, "#!/bin/sh\ncat >> /var/lib/isx/init-applied <<'ISX_EOF'\n0123456789abcdef01234567\nISX_EOF\n");
        var init = new InitPlanTest.PlanInit() {
            @Override
            List<InitStep> planSteps(boolean macOS) {
                return List.of();
            }
        };
        init.root = root;
        init.noSudo = true;
        RecordingOutput.during(() -> assertNull(init.privilegedStepsLeft()));
        assertFalse(Files.exists(script));
    }

    /** firewalld shows the redirect missing for the gateway: scripted again, unless the script already ran it. */
    @Test
    void aRedirectFirewalldShowsMissingIsScriptedOnceThenWarned(@TempDir Path root) throws Exception {
        var init = setUp(root);
        init.answers.put("firewall-cmd --direct --get-all-rules", new InitCommand.Query(0, ""));
        Privilege.forbid();
        var waiting = assertThrows(InitCommand.WaitingOnPerson.class,
                () -> RecordingOutput.during(() -> init.host().configureMitmProxy()));
        var keys = waiting.changes.stream().map(p -> InitPlan.key(p.change())).toList();
        Files.createDirectories(root.resolve("var/lib/isx"));
        Files.write(root.resolve("var/lib/isx/init-applied"), keys);
        var recorded = RecordingOutput.during(() -> init.host().configureMitmProxy());
        assertTrue(recorded.lines().stream().anyMatch(l -> l.contains("still not in effect")), recorded.lines().toString());
    }

    /**
     * The script tolerates a failed CoW pool create and records it; the run then has no root to
     * create it with, so it says how, never reaching for sudo (#2118 review).
     */
    @Test
    void aRecordedButAbsentCowPoolIsNamedNotCreated(@TempDir Path root) throws Exception {
        var init = setUp(root);
        init.incus = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"))
                .pool("default", "dir")
                .profile("default", Map.of("root", Map.of("type", "disk", "path", "/", "pool", "default"),
                        "eth0", Map.of("type", "nic", "network", "incusbr0")))
                .client();
        Privilege.forbid();
        var recorded = RecordingOutput.during(() -> init.host().initializeIncus());
        assertTrue(recorded.events().stream().anyMatch(e -> e.text() != null && e.text().contains("could not create one")),
                recorded.lines().toString());
    }

    @Test
    void anIncusWithNoPoolStopsTheRunNamingTheCommand(@TempDir Path root) throws Exception {
        var init = setUp(root);
        init.incus = new FakeIncusDaemon().failPoolListing().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24")).client();
        Privilege.forbid();
        var failure = assertThrows(IllegalStateException.class, () -> RecordingOutput.during(() -> init.host().initializeIncus()));
        // The step's own stop, never the guard refusing the sudo it would otherwise have run
        assertFalse(failure instanceof Privilege.Refused, failure.toString());
        assertTrue(failure.getMessage().contains("sudo incus admin init --minimal"), failure.getMessage());
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
        for (var command : List.of(List.of("env", "sudo", "id"), List.of("env", "LC_ALL=C", "sudo", "id"),
                List.of("timeout", "5", "sudo", "id"), List.of("nice", "-n", "5", "sudo", "id"),
                List.of("dash", "-c", "sudo id"), List.of("timeout", "-s", "KILL", "5", "sudo", "id"),
                List.of("env", "-u", "HOME", "sudo", "id"), List.of("env", "-S", "sudo id"))) {
            assertThrows(Privilege.Refused.class, () -> Privilege.check(command), command.toString());
        }
        assertDoesNotThrow(() -> Privilege.check("env", "LC_ALL=C", "ls"));
        // init's own continuation under sg passes; an sg, xargs or systemd-run that elevates does not
        assertDoesNotThrow(() -> Privilege.check("sg", "incus-admin", "-c", "exec sg u -c 'exec /usr/bin/isx init --no-sudo'"));
        for (var command : List.of(List.of("sg", "wheel", "-c", "sudo id"), List.of("xargs", "sudo"),
                List.of("systemd-run", "--user", "sudo", "id"), List.of("systemd-run", "id"))) {
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
