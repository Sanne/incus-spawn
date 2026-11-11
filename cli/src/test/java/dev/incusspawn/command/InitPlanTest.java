package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.FirewallDetector.DetectionResult;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.RecordingOutput;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx init --plan} (#2073): what init would change on this host, read without changing
 * anything, from the same steps the run takes.
 */
@ExtendWith(IsolatedHome.class)
class InitPlanTest {

    private static final String GATEWAY = "10.1.2.1";

    /** Init on a host given by the test: its commands, its files under {@code root}, its firewall and Incus. */
    static class PlanInit extends InitCommand {
        Path root;
        Set<String> commands = Set.of("dnf", "incus", "openssl", "ssh-keygen", "btrfs", "firewall-cmd");
        final Map<String, Query> answers = new HashMap<>();
        final List<String> queried = new ArrayList<>();
        final List<String> ran = new ArrayList<>();
        DetectionResult firewall = new DetectionResult.UseFirewalld(false);
        boolean reachable = true;

        @Override boolean hostHasCommand(String command) { return commands.contains(command); }
        /** What a command run at the terminal exits with, by its line; 0 when not given. */
        final Map<String, Integer> exits = new HashMap<>();
        @Override int runShown(String... command) {
            var line = String.join(" ", command);
            ran.add(line);
            return exits.getOrDefault(line, 0);
        }
        @Override Path hostFile(String path) { return root.resolve(path.substring(1)); }
        @Override DetectionResult runningFirewall() { return firewall; }
        @Override boolean incusReachable() { return reachable; }
        /** The host's routes: none, whatever routes the machine running the test has. */
        List<String> routes = List.of();
        @Override List<String> hostRoutes() { return routes; }
        boolean polkit = true;
        @Override boolean polkitAllows(String action) { return polkit; }
        String filesystem = "ext4";
        @Override String filesystemOf(Path path) { return filesystem; }
        @Override String incusConnectivity() { return "Cannot access the Incus socket (permission denied)."; }
        // Runs 'claude mcp get', which starts the server it names: isx mcp would sweep orphans
        @Override boolean claudeMcpRegistered() { throw new AssertionError("the plan ran claude mcp get"); }
        // The developer's own CLAUDE_CONFIG_DIR would point the plan at their real configuration
        @Override String claudeConfigDirEnv() { return null; }
        boolean proxyActive;
        @Override boolean proxyServiceActive() { return proxyActive; }
        @Override void installGitRemoteShim() { ran.add("git-remote-helper"); }

        @Override
        Query query(String... command) {
            var line = String.join(" ", command);
            // The guard the real one has, which would turn the step unknown rather than fail here
            assertFalse(command[0].equals("sudo"), line);
            queried.add(line);
            return answers.getOrDefault(line, new Query(1, ""));
        }

        // What a run would do, for the drift checks: the commands, never run
        @Override
        int runHostQuiet(String... command) {
            ran.add(String.join(" ", command));
            return 0;
        }

        /** What the run's reads through sudo answer. */
        String captured = "";

        @Override
        String captureOutput(String... command) {
            ran.add(String.join(" ", command));
            return captured;
        }

        /** Runs the step headers alone, recording each step's id. */
        final List<String> begun = new ArrayList<>();

        @Override
        void begin(InitStep step) {
            begun.add(step.id());
        }
    }

    private PlanInit init(Path root) {
        var init = new PlanInit();
        init.root = root;
        init.incus = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24")).client();
        return init;
    }

    private static List<String> ids(List<InitCommand.InitStep> steps) {
        return steps.stream().map(InitCommand.InitStep::id).toList();
    }

    /** The plan lists the steps the run takes, in its order, unnumbered ones included. */
    @Test
    void thePlanListsTheStepsTheLinuxRunTakesInItsOrder(@TempDir Path root) throws Exception {
        var init = init(root);
        RecordingOutput.during(() -> init.runLinuxSteps(init.finalSteps(init.proxyServiceStep(new AtomicBoolean()))));
        var run = new ArrayList<>(List.of("dependencies"));
        // The helper is not a step of its own in the run: it is written between the credentials and the rest
        for (var id : init.begun) {
            if (id.equals("search-paths")) run.add("git-remote-helper");
            run.add(id);
        }
        assertTrue(init.ran.contains("git-remote-helper"));
        assertEquals(run, ids(init.planSteps(false)));
        assertEquals(List.of("dependencies", "incus", "subids", "storage-network", "firewall", "mitm-proxy", "ssh-key",
                "credentials", "git-remote-helper", "search-paths", "code-directories", "mcp", "dns", "proxy-service"),
                ids(init.planSteps(false)));
    }

    @Test
    void thePlanListsTheStepsTheMacOsRunTakesInItsOrder(@TempDir Path root) throws Exception {
        var init = init(root);
        RecordingOutput.during(() -> init.runSteps(init.macOsSetup(), init.finalSteps(init.macOsServicesStep(new AtomicBoolean()))));
        var run = new ArrayList<>(List.of("vm"));
        for (var id : init.begun) {
            if (id.equals("search-paths")) run.add("git-remote-helper");
            run.add(id);
        }
        assertEquals(run, ids(init.planSteps(true)));
    }

    /** Every step plans, and the plan only reads: Incus is asked nothing but GETs, sudo is never run, nothing is written. */
    @Test
    void thePlanChangesNothing(@TempDir Path root) throws Exception {
        var daemon = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"))
                .pool("default", "dir").profile("default", Map.of());
        var init = init(root);
        init.incus = daemon.client();
        var home = Path.of(System.getProperty("user.home"));
        var before = listing(home);

        for (var macOS : List.of(false, true)) {
            var entries = InitCommand.planEntries(init.planSteps(macOS));
            assertEquals(init.planSteps(macOS).size(), entries.size());
            for (var entry : entries) {
                var note = entry.assessment().note();
                assertFalse(note != null && note.startsWith(InitCommand.PLAN_FAILED), entry.step() + ": " + note);
            }
        }

        assertEquals(List.of(), daemon.requests().stream().filter(r -> !r.startsWith("GET ")).toList());
        assertEquals(List.of(), init.ran, "the plan ran a command of the run");
        assertTrue(init.queried.stream().noneMatch(c -> c.startsWith("sudo")), "the plan ran sudo: " + init.queried);
        assertEquals(before, listing(home), "the plan wrote to the home directory");
    }

    private static List<Path> listing(Path dir) throws Exception {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.sorted().toList();
        }
    }

    // ── User Namespace Mappings ─────────────────────────────────────────────────

    private static void subids(Path root, String content) throws Exception {
        Files.createDirectories(root.resolve("etc"));
        Files.writeString(root.resolve("etc/subuid"), content);
        Files.writeString(root.resolve("etc/subgid"), content);
    }

    @Test
    void mappingsInPlaceAreDone(@TempDir Path root) throws Exception {
        subids(root, "root:1000:1\nroot:1000000:1000000000\n");
        assertEquals(InitPlan.State.DONE, init(root).host().planSubids().state());
    }

    @Test
    void missingMappingsAreWrittenWithSudoAndIncusRestarted(@TempDir Path root) throws Exception {
        subids(root, "user:100000:65536\nroot:1000000:65536\n");
        var plan = init(root).host().planSubids();
        assertEquals(InitPlan.State.PENDING, plan.state());
        assertEquals(InitPlan.Who.SUDO, plan.who());
        assertFalse(plan.asks());
        var record = InitPlan.record(new InitPlan.Entry("subids", "User Namespace Mappings", "", plan));
        assertEquals(List.of("/etc/subuid", "/etc/subgid"), record.get("files"));
        assertEquals(List.of("sudo systemctl restart incus"), record.get("commands"));
    }

    @Test
    void aConflictingMappingIsOnlyReplacedIfThePersonAgrees(@TempDir Path root) throws Exception {
        subids(root, "root:1000:1\nroot:1000000:5000\n");
        var plan = init(root).host().planSubids();
        assertTrue(plan.asks());
        assertTrue(plan.changes().getFirst().what().contains("if you agree"), plan.changes().toString());
    }

    @Test
    void unreadableMappingsAreUnknown(@TempDir Path root) {
        assertEquals(InitPlan.State.UNKNOWN, init(root).host().planSubids().state());
    }

    // ── Firewall and the proxy's redirect ───────────────────────────────────────

    private static final String TRUSTED = "trusted (active)\n  interfaces: incusbr0\n  masquerade: yes\n";
    private static final String DIRECT = """
            ipv4 filter FORWARD 0 -i incusbr0 -j ACCEPT
            ipv4 filter FORWARD 0 -o incusbr0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
            """;

    @Test
    void aConfiguredFirewalldIsDone(@TempDir Path root) {
        var init = init(root);
        init.answers.put("firewall-cmd --zone=trusted --list-all", new InitCommand.Query(0, TRUSTED));
        init.answers.put("firewall-cmd --direct --get-all-rules", new InitCommand.Query(0, DIRECT));
        assertEquals(InitPlan.State.DONE, init.host().planFirewall().state());
    }

    /** What firewalld will not show this user is not checked: the note says so and why, and how to check it. */
    @Test
    void aFirewalldThatWillNotAnswerWithoutSudoIsNotChecked(@TempDir Path root) {
        var plan = init(root).host().planFirewall();
        assertEquals(InitPlan.State.UNKNOWN, plan.state());
        assertTrue(plan.note().startsWith("Not checked: firewalld's policy"), plan.note());
        assertEquals(List.of("sudo firewall-cmd --zone=trusted --list-all", "sudo firewall-cmd --direct --get-all-rules"),
                plan.checks());
        assertEquals(InitPlan.Who.SUDO, plan.who());
    }

    /** polkit installed without pkcheck cannot be asked first, so the rules are not read: no dialog either. */
    @Test
    void polkitThatCannotBeAskedIsNotRisked(@TempDir Path root) throws Exception {
        var init = init(root);
        Files.createDirectories(root.resolve("usr/share/polkit-1/actions"));
        init.answers.put("firewall-cmd --zone=trusted --list-all", new InitCommand.Query(0, TRUSTED));
        init.answers.put("firewall-cmd --direct --get-all-rules", new InitCommand.Query(0, DIRECT));
        assertEquals(InitPlan.State.UNKNOWN, init.host().planFirewall().state());
        assertTrue(init.queried.stream().noneMatch(c -> c.startsWith("firewall-cmd")), init.queried.toString());
    }

    /** Without polkit, nothing can raise a dialog: the plan tries the read rather than skipping it. */
    @Test
    void withoutPolkitTheRulesAreReadAnyway(@TempDir Path root) {
        var init = init(root);
        init.polkit = false;
        init.answers.put("firewall-cmd --zone=trusted --list-all", new InitCommand.Query(0, TRUSTED));
        init.answers.put("firewall-cmd --direct --get-all-rules", new InitCommand.Query(0, DIRECT));
        assertEquals(InitPlan.State.DONE, init.host().planFirewall().state());
    }

    /** Where reading the rules would take an administrator, polkit is asked first, and firewalld never: no dialog. */
    @Test
    void aFirewalldWhosePolicyWantsAnAdministratorIsNotAsked(@TempDir Path root) {
        var init = init(root);
        init.commands = Set.of("dnf", "incus", "openssl", "ssh-keygen", "btrfs", "firewall-cmd", "pkcheck");
        init.polkit = false;
        init.answers.put("firewall-cmd --zone=trusted --list-all", new InitCommand.Query(0, TRUSTED));
        init.answers.put("firewall-cmd --direct --get-all-rules", new InitCommand.Query(0, DIRECT));
        var plan = init.host().planFirewall();
        assertEquals(InitPlan.State.UNKNOWN, plan.state());
        assertFalse(plan.checks().isEmpty());
        assertTrue(init.queried.stream().noneMatch(c -> c.startsWith("firewall-cmd")), init.queried.toString());
    }

    /** The rules the plan names are the commands the step runs on a firewalld with none of them. */
    @Test
    void theFirewallRulesPlannedAreTheOnesTheStepRuns(@TempDir Path root) {
        var init = init(root);
        init.answers.put("firewall-cmd --zone=trusted --list-all", new InitCommand.Query(0, ""));
        init.answers.put("firewall-cmd --direct --get-all-rules", new InitCommand.Query(0, ""));
        var plan = init.host().planFirewall();
        RecordingOutput.during(() -> init.host().configureFirewalld());
        var run = init.ran.stream().filter(c -> !c.contains("--list-all") && !c.contains("--get-all-rules")).toList();
        assertEquals(run, InitPlan.record(new InitPlan.Entry("firewall", "", "", plan)).get("commands"));
    }

    @Test
    void theRedirectPlannedIsTheOneTheStepWrites(@TempDir Path root) {
        var init = init(root);
        var stale = "ipv4 nat PREROUTING 0 -i incusbr0 -d 10.9.9.1 -p tcp --dport 443 -j REDIRECT --to-port 18443\n";
        var plan = init.host().planRedirectFirewalld(GATEWAY, stale);
        init.captured = stale;
        RecordingOutput.during(() -> init.host().configureMitmProxyFirewalld(GATEWAY));
        var run = init.ran.stream().filter(c -> !c.contains("--get-all-rules")).toList();
        assertEquals(run, InitPlan.record(new InitPlan.Entry("mitm-proxy", "", "", plan)).get("commands"));
    }

    // ── Host files ──────────────────────────────────────────────────────────────

    @Test
    void sysctlsInPlaceAreDone(@TempDir Path root) throws Exception {
        var init = init(root);
        var conf = root.resolve("99-incus-spawn.conf");
        assertEquals(InitPlan.State.PENDING, init.host().planHostSysctls(conf).state());
        Files.writeString(conf, HostSetup.SYSCTL_CONTENT);
        assertEquals(InitPlan.State.DONE, init.host().planHostSysctls(conf).state());
    }

    @Test
    void networkManagerIsConfiguredOnlyWhereItRuns(@TempDir Path root) throws Exception {
        var host = init(root).host();
        assertEquals(InitPlan.State.DONE, host.planNetworkManager(root.resolve("absent")).state());
        assertEquals(InitPlan.State.PENDING, host.planNetworkManager(root).state());
        Files.writeString(root.resolve("99-unmanaged-veth.conf"), "");
        assertEquals(InitPlan.State.DONE, host.planNetworkManager(root).state());
    }

    // ── Storage & Network ───────────────────────────────────────────────────────

    @Test
    void anIncusThatDoesNotAnswerLeavesStorageUnknown(@TempDir Path root) {
        var init = init(root);
        init.reachable = false;
        var plan = init.host().planStorageAndNetwork();
        assertEquals(InitPlan.State.UNKNOWN, plan.state());
        assertTrue(plan.note().contains("permission denied"), plan.note());
    }

    @Test
    void aFreshHostGetsIncusInitializedAndACowPool(@TempDir Path root) {
        var init = init(root);
        init.commands = Set.of("dnf");
        var record = InitPlan.record(new InitPlan.Entry("storage-network", "", "", init.host().planStorageAndNetwork()));
        assertEquals("pending", record.get("state"));
        assertEquals("sudo incus admin init --minimal", ((List<?>) record.get("commands")).getFirst());
        assertTrue(((List<?>) record.get("commands")).stream().anyMatch(c -> c.toString().contains("storage create cow")));
        // On btrfs, the pool Incus makes is one with copy-on-write already
        init.filesystem = "btrfs";
        var onBtrfs = InitPlan.record(new InitPlan.Entry("storage-network", "", "", init.host().planStorageAndNetwork()));
        assertTrue(((List<?>) onBtrfs.get("commands")).stream().noneMatch(c -> c.toString().contains("storage create")));
    }

    @Test
    void aDefaultProfileWithoutDevicesGetsThem(@TempDir Path root) throws Exception {
        var init = init(root);
        Files.createDirectories(root.resolve("etc/sudoers.d"));
        Files.writeString(root.resolve("etc/sudoers.d/incus-spawn-btrfs"), HostSetup.btrfsSudoersContent("cow"));
        init.incus = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"))
                .pool("cow", "btrfs").profile("default", Map.of()).client();
        var plan = init.host().planStorageAndNetwork();
        assertEquals(List.of("Add root and eth0 to the default profile"), plan.changes().stream().map(InitPlan.Change::what).toList());
        assertEquals(InitPlan.Who.NONE, plan.who());
    }

    /** A bridge on a host route's subnet is moved off it, as the step does. */
    @Test
    void aBridgeOverlappingAHostRouteIsMoved(@TempDir Path root) throws Exception {
        var init = init(root);
        Files.createDirectories(root.resolve("etc/sudoers.d"));
        Files.writeString(root.resolve("etc/sudoers.d/incus-spawn-btrfs"), HostSetup.btrfsSudoersContent("cow"));
        init.incus = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"))
                .pool("cow", "btrfs")
                .profile("default", Map.of("root", Map.of("type", "disk", "path", "/", "pool", "cow"),
                        "eth0", Map.of("type", "nic", "network", "incusbr0")))
                .client();
        init.routes = List.of("10.1.0.0/20 dev eth0");
        var plan = init.host().planStorageAndNetwork();
        assertEquals(List.of("Move the bridge from 10.1.2.1/24 to 172.20.0.1/24, off the route 10.1.0.0/20 dev eth0, and the instances on it"),
                plan.changes().stream().map(InitPlan.Change::what).toList());
        // With every subnet the step would move it to taken, it only warns, and the plan does not call the step done
        var taken = new java.util.ArrayList<>(List.of("10.1.0.0/20 dev eth0"));
        for (int i = 0; i < 256; i++) taken.add("172." + (16 + i / 16) + "." + (i % 16 * 16) + ".0/20 dev vpn0");
        taken.add("172.16.0.0/12 dev vpn0");
        taken.add("192.168.0.0/16 dev vpn0");
        taken.add("10.0.0.0/8 dev vpn0");
        init.routes = taken;
        var stuck = init.host().planStorageAndNetwork();
        assertEquals(InitPlan.State.PENDING, stuck.state());
        assertTrue(stuck.changes().getFirst().what().endsWith("yourself: no free subnet was found"), stuck.changes().toString());
    }

    /** On a host without copy-on-write, the run creates the cow pool and then moves the profile's root disk to it. */
    @Test
    void aDirPoolHostGetsACowPoolAndTheProfileMovedToIt(@TempDir Path root) throws Exception {
        var init = init(root);
        init.incus = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"))
                .pool("default", "dir")
                .profile("default", Map.of("root", Map.of("type", "disk", "path", "/", "pool", "default"),
                        "eth0", Map.of("type", "nic", "network", "incusbr0")))
                .client();
        var whats = init.host().planStorageAndNetwork().changes().stream().map(InitPlan.Change::what).toList();
        assertTrue(whats.contains("Create the copy-on-write storage pool 'cow'"), whats.toString());
        assertTrue(whats.contains("Move the default profile's root disk from pool 'default' to 'cow'"), whats.toString());
    }

    @Test
    void aFreshExt4HostGetsTheProfileMovedToTheCowPool(@TempDir Path root) {
        var init = init(root);
        init.commands = Set.of("dnf");
        var whats = init.host().planStorageAndNetwork().changes().stream().map(InitPlan.Change::what).toList();
        assertTrue(whats.contains("Move the default profile's root disk from pool 'default' to 'cow'"), whats.toString());
    }

    /** Once init has run the step restarts the proxy itself; before, the last step does, and plans it. */
    @Test
    void startingFirewalldRestartsARunningProxy(@TempDir Path root) throws Exception {
        var init = init(root);
        init.firewall = new DetectionResult.UseFirewalld(true);
        init.proxyActive = true;
        var restart = "systemctl --user restart " + Environment.PROXY_SERVICE_NAME;
        var before = InitPlan.record(new InitPlan.Entry("firewall", "", "", init.host().planFirewall())).get("commands");
        assertFalse(((List<?>) before).contains(restart), before.toString());
        Files.createDirectories(Environment.configDir());
        Environment.markInitComplete();
        var commands = InitPlan.record(new InitPlan.Entry("firewall", "", "", init.host().planFirewall())).get("commands");
        assertTrue(((List<?>) commands).contains(restart), commands.toString());
    }

    /** Init checks sudo before any step, so even a host with nothing to do needs it for the run. */
    @Test
    void theDependenciesStepSaysInitNeedsSudo(@TempDir Path root) {
        var plan = init(root).host().planDependencies();
        assertEquals(InitPlan.State.DONE, plan.state());
        assertEquals(HostSetup.SUDO_FIRST, plan.note());
    }

    @Test
    void aSetUpStorageIsDone(@TempDir Path root) throws Exception {
        var init = init(root);
        Files.createDirectories(root.resolve("etc/sudoers.d"));
        Files.writeString(root.resolve("etc/sudoers.d/incus-spawn-btrfs"), HostSetup.btrfsSudoersContent("cow"));
        init.incus = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"))
                .pool("cow", "btrfs")
                .profile("default", Map.of("root", Map.of("type", "disk", "path", "/", "pool", "cow"),
                        "eth0", Map.of("type", "nic", "network", "incusbr0")))
                .client();
        var plan = init.host().planStorageAndNetwork();
        assertEquals(InitPlan.State.DONE, plan.state(), plan.toString());
    }

    /**
     * The btrfs rule is root's to read, and the plan never runs sudo (Sanne on #2091): an unreadable
     * rule is not checked, with what it should hold and the command that checks it.
     */
    @Test
    void anUnreadableSudoersRuleIsNotCheckedAndSudoIsNeverRun(@TempDir Path root) {
        var init = init(root);
        init.incus = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"))
                .pool("cow", "btrfs")
                .profile("default", Map.of("root", Map.of("type", "disk", "path", "/", "pool", "cow"),
                        "eth0", Map.of("type", "nic", "network", "incusbr0")))
                .client();
        assertNull(init.host().btrfsUsageGranted("cow"));
        var plan = init.host().planStorageAndNetwork();
        assertEquals(InitPlan.State.UNKNOWN, plan.state());
        assertTrue(plan.note().startsWith("Not checked: only root can read /etc/sudoers.d/incus-spawn-btrfs"), plan.note());
        assertTrue(plan.note().contains(HostSetup.btrfsSudoersContent("cow").strip()), plan.note());
        assertEquals(List.of("sudo cat /etc/sudoers.d/incus-spawn-btrfs"), plan.checks());
        assertTrue(init.queried.stream().noneMatch(c -> c.startsWith("sudo")), init.queried.toString());
    }

    @Test
    void theTableSaysWhatWasNotCheckedAndHowToCheckIt() {
        var bytes = new ByteArrayOutputStream();
        InitPlan.printTable(new PrintStream(bytes, true, StandardCharsets.UTF_8), List.of(new InitPlan.Entry("firewall",
                "Firewall Configuration", "", InitPlan.Assessment.notChecked("Not checked: only root can read it.",
                        InitPlan.Change.sudo("Add the rules missing", null), List.of("sudo firewall-cmd --direct --get-all-rules")))));
        var table = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(table.contains("Firewall Configuration  not checked: it needs root"), table);
        assertTrue(table.contains("To check it yourself:\n      $ sudo firewall-cmd --direct --get-all-rules"), table);
    }

    @Test
    void thePlanRefusesToRunSudoInAnyForm() {
        var init = new InitCommand();
        for (var command : List.of(new String[]{"sudo", "-n", "-l"}, new String[]{"sudo", "true"})) {
            assertThrows(IllegalArgumentException.class, () -> init.query(command), String.join(" ", command));
        }
    }

    // ── Who must do it ──────────────────────────────────────────────────────────

    @Test
    void aStepNeedsTheMostAnyOfItsChangesNeeds() {
        var plan = InitPlan.Assessment.of(List.of(
                InitPlan.Change.run("a", InitPlan.Who.NONE, null),
                InitPlan.Change.sudo("b", "sudo b"),
                InitPlan.Change.run("c", InitPlan.Who.SECRET, "isx account add c")));
        assertEquals(InitPlan.Who.SECRET, plan.who());
        assertEquals(InitPlan.Who.NONE, InitPlan.Assessment.done().who());
    }

    /** Work a step will certainly do is not hidden by a part it could not check, nor that part by the work. */
    @Test
    void aPendingPartIsNotHiddenByOneNotChecked() {
        var combined = InitPlan.combine(List.of(
                InitPlan.Assessment.of(List.of(InitPlan.Change.sudo("b", "sudo b"))),
                InitPlan.Assessment.notChecked("cannot read", InitPlan.Change.sudo("c", null), List.of("sudo cat c"))));
        assertEquals(InitPlan.State.PENDING, combined.state());
        assertEquals(2, combined.changes().size());
        assertEquals("cannot read", combined.note());
        assertEquals(List.of("sudo cat c"), combined.checks());
        var unknown = InitPlan.combine(List.of(InitPlan.Assessment.done(), InitPlan.Assessment.unknown("cannot read")));
        assertEquals(InitPlan.State.UNKNOWN, unknown.state());
    }

    /** Registration is read from Claude Code's own file: asking Claude Code would start isx mcp. */
    @Test
    void theMcpRegistrationIsReadWithoutStartingClaudeCode(@TempDir Path root) throws Exception {
        var init = init(root);
        init.commands = Set.of("claude");
        var home = Path.of(System.getProperty("user.home"));
        Files.createDirectories(dev.incusspawn.config.McpConfig.file().getParent());
        Files.writeString(dev.incusspawn.config.McpConfig.file(), "templates: [tpl-agent]\n");
        assertEquals(InitPlan.State.OPTIONAL, init.planMcp().state(), "no ~/.claude.json: not registered");
        Files.writeString(home.resolve(".claude.json"), "{\"mcpServers\": {\"isx\": {\"command\": \"isx\", \"args\": [\"mcp\"]}}}");
        assertEquals(InitPlan.State.DONE, init.planMcp().state());
        Files.writeString(home.resolve(".claude.json"), "{\"mcpServers\": {\"other\": {}}}");
        var unregistered = init.planMcp();
        assertTrue(unregistered.changes().getFirst().command().startsWith("claude mcp add"), unregistered.toString());
        // The local scope, keyed by the directory isx runs in
        var cwd = Path.of("").toAbsolutePath().toString().replace("\\", "\\\\");
        Files.writeString(home.resolve(".claude.json"), "{\"projects\": {\"" + cwd + "\": {\"mcpServers\": {\"isx\": {}}}}}");
        assertEquals(InitPlan.State.DONE, init.planMcp().state());
        Files.writeString(home.resolve(".claude.json"), "{not json");
        assertEquals(InitPlan.State.UNKNOWN, init.planMcp().state());
        assertTrue(init.queried.stream().noneMatch(c -> c.startsWith("claude")), init.queried.toString());
    }

    @Test
    void theApprovalsAreThePersonsAndTheCredentialsASecret(@TempDir Path root) throws Exception {
        var init = init(root);
        init.commands = Set.of("claude");
        var mcp = init.planMcp();
        assertEquals(InitPlan.State.OPTIONAL, mcp.state());
        assertTrue(mcp.asks());
        assertTrue(init.planCredentials().asks());
    }

    /** Registered with no template to approve: the step only asks, so it has nothing to change. */
    @Test
    void aRegisteredServerWithNoCandidateHasNothingToChange(@TempDir Path root) throws Exception {
        var init = new PlanInit() {
            @Override
            Map<String, dev.incusspawn.config.ImageDef> trustedDefinitions() {
                return Map.of();
            }
        };
        init.root = root;
        init.commands = Set.of("claude");
        Files.writeString(Path.of(System.getProperty("user.home")).resolve(".claude.json"),
                "{\"mcpServers\": {\"isx\": {}}}");
        var mcp = init.planMcp();
        assertEquals(InitPlan.State.DONE, mcp.state(), mcp.toString());
        assertTrue(mcp.changes().isEmpty(), mcp.toString());
        assertTrue(mcp.asks());
    }

    /** A set-up step still offers the templates it would name as not approved, and is done once all are. */
    @Test
    void templatesNotYetApprovedAreOffered(@TempDir Path root) throws Exception {
        var defs = Map.of(
                "tpl-agent", dev.incusspawn.config.ImageDef.parseYaml("name: tpl-agent\ntools: [claude]\n"),
                "tpl-agent-pi", dev.incusspawn.config.ImageDef.parseYaml("name: tpl-agent-pi\ntools: [pi]\n"));
        var init = new PlanInit() {
            @Override
            Map<String, dev.incusspawn.config.ImageDef> trustedDefinitions() {
                return defs;
            }
        };
        init.root = root;
        init.commands = Set.of("claude");
        var home = Path.of(System.getProperty("user.home"));
        Files.writeString(home.resolve(".claude.json"), "{\"mcpServers\": {\"isx\": {}}}");
        Files.createDirectories(dev.incusspawn.config.McpConfig.file().getParent());
        Files.writeString(dev.incusspawn.config.McpConfig.file(), "templates: [tpl-agent]\n");
        var plan = init.planMcp();
        assertEquals(InitPlan.State.OPTIONAL, plan.state());
        assertEquals("Approve which of tpl-agent-pi agents may create instances from", plan.changes().getFirst().what());
        Files.writeString(dev.incusspawn.config.McpConfig.file(), "templates: [tpl-agent, tpl-agent-pi]\n");
        assertEquals(InitPlan.State.DONE, init.planMcp().state());
    }

    /** Unregistered, the step offers only registration and the templates not yet approved (#2130). */
    @Test
    void anUnregisteredServerOffersOnlyTheTemplatesNotApproved(@TempDir Path root) throws Exception {
        var defs = Map.of(
                "tpl-agent", dev.incusspawn.config.ImageDef.parseYaml("name: tpl-agent\ntools: [claude]\n"),
                "tpl-agent-pi", dev.incusspawn.config.ImageDef.parseYaml("name: tpl-agent-pi\ntools: [pi]\n"));
        var init = new PlanInit() {
            @Override
            Map<String, dev.incusspawn.config.ImageDef> trustedDefinitions() {
                return defs;
            }
        };
        init.root = root;
        init.commands = Set.of("claude");
        Files.createDirectories(dev.incusspawn.config.McpConfig.file().getParent());
        Files.writeString(dev.incusspawn.config.McpConfig.file(), "templates: [tpl-agent, tpl-agent-pi]\n");
        var plan = init.planMcp();
        assertEquals(InitPlan.State.OPTIONAL, plan.state(), plan.toString());
        assertEquals(1, plan.changes().size(), plan.toString());
        assertEquals(InitPlan.Who.NONE, plan.changes().getFirst().who());
        assertTrue(plan.changes().getFirst().command().startsWith("claude mcp add"), plan.toString());
        Files.writeString(dev.incusspawn.config.McpConfig.file(), "templates: [tpl-agent]\n");
        var partial = init.planMcp();
        assertEquals(List.of("claude mcp add", "Approve which of tpl-agent-pi agents may create instances from"),
                partial.changes().stream().map(c -> c.who() == InitPlan.Who.APPROVAL ? c.what()
                        : c.command().substring(0, "claude mcp add".length())).toList(), partial.toString());
    }

    /** Init shows the MCP step's header once, from its InitStep, as 'isx mcp setup' does. */
    @Test
    void initShowsTheMcpHeaderOnce() throws Exception {
        var init = new InitCommand();
        var frames = String.join("\n", RecordingOutput.during(() -> init.begin(init.mcpStep())).frameLines());
        assertEquals(1, frames.split("Agent Access over MCP", -1).length - 1, frames);
    }

    /** The approvals are the person's, and the plan names the command that runs that step alone (#2077). */
    @Test
    void theApprovalStepNamesIsxMcpSetup(@TempDir Path root) throws Exception {
        var agent = dev.incusspawn.config.ImageDef.parseYaml("name: tpl-agent\ntools: [claude]\n");
        var init = new PlanInit() {
            @Override
            Map<String, dev.incusspawn.config.ImageDef> trustedDefinitions() {
                return Map.of("tpl-agent", agent);
            }
        };
        init.root = root;
        init.commands = Set.of("claude");
        var approval = init.planMcp().changes().stream().filter(c -> c.who() == InitPlan.Who.APPROVAL).toList();
        assertEquals(1, approval.size(), init.planMcp().toString());
        assertEquals(InitCommand.MCP_SETUP_COMMAND, approval.getFirst().command());
        assertEquals("isx mcp setup", InitCommand.MCP_SETUP_COMMAND);
        assertEquals(dev.incusspawn.config.McpConfig.file().toString(), approval.getFirst().file());
    }

    @Test
    void theMarkerIsWrittenByTheLastStep(@TempDir Path root) throws Exception {
        var init = init(root);
        Files.createDirectories(Environment.configDir());
        var plan = init.planProxyService();
        assertTrue(plan.changes().stream().anyMatch(c -> Environment.initCompleteMarker().toString().equals(c.file())),
                plan.changes().toString());
        Environment.markInitComplete();
        assertTrue(init.planProxyService().changes().stream().noneMatch(c -> c.file() != null && c.file().endsWith(".init-complete")));
    }

    // ── The output contract ─────────────────────────────────────────────────────

    /** {@code plain} and {@code json} are a contract: fields are added at the end, never renamed, removed or reordered. */
    @Test
    void theJsonFieldsAreAContract() throws Exception {
        var entry = new InitPlan.Entry("firewall", "Firewall Configuration", "Configures the firewall.",
                InitPlan.Assessment.of(List.of(
                        InitPlan.Change.sudo("Reload firewalld", "sudo firewall-cmd --reload"),
                        InitPlan.Change.write("Record the gateway", InitPlan.Who.NONE, "/home/u/.config/incus-spawn/config.yaml"))));
        var bytes = new ByteArrayOutputStream();
        OutputFormat.JSON.print(new PrintStream(bytes, true, StandardCharsets.UTF_8), List.of(InitPlan.record(entry)));
        assertEquals("""
                [ {
                  "step" : "firewall",
                  "title" : "Firewall Configuration",
                  "state" : "pending",
                  "who" : "sudo",
                  "asks" : false,
                  "changes" : [ "Reload firewalld", "Record the gateway" ],
                  "commands" : [ "sudo firewall-cmd --reload" ],
                  "sudo_commands" : [ "sudo firewall-cmd --reload" ],
                  "files" : [ "/home/u/.config/incus-spawn/config.yaml" ],
                  "note" : null,
                  "purpose" : "Configures the firewall.",
                  "check_commands" : [ ],
                  "sudo_script" : [ "firewall-cmd --reload" ]
                } ]
                """, bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        assertTrue(JqValues.parseStrict(bytes.toString(StandardCharsets.UTF_8)).isArray());
    }

    @Test
    void theTableSaysWhoMustDoWhatIsLeft() {
        var bytes = new ByteArrayOutputStream();
        InitPlan.printTable(new PrintStream(bytes, true, StandardCharsets.UTF_8), List.of(
                new InitPlan.Entry("incus", "Incus Installation", "", InitPlan.Assessment.of(List.of(
                        InitPlan.Change.sudo("Enable the incus service", "sudo systemctl enable --now incus")))),
                new InitPlan.Entry("ssh-key", "SSH Key Pair", "", InitPlan.Assessment.done())));
        var table = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(table.contains("Incus Installation  to do, needs sudo"), table);
        assertTrue(table.contains("$ sudo systemctl enable --now incus"), table);
        assertTrue(table.contains("SSH Key Pair        done"), table);
        assertTrue(table.contains("1 step to do; sudo is needed."), table);
        assertTrue(Objects.requireNonNull(table).lines().noneMatch(l -> l.contains("\u001b")), table);
    }
}
