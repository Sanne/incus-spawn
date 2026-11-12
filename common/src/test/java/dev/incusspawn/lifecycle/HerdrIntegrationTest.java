package dev.incusspawn.lifecycle;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The host half of the herdr tool (#1720): an instance whose template installs herdr is added to
 * the host's herdr once, on its first start, and only the machine isx added is removed when it is
 * destroyed. Later starts, and destroys of anything else, run nothing.
 */
class HerdrIntegrationTest {

    private static final String ID = "ae376ca7bf702d2ab8ae75ad49cd411b";
    private static final String USERS = "0123456789abcdef0123456789abcdef";
    /** The machine's target: the alias that starts a stopped instance (#2038). */
    private static final String TARGET = "ws.start.isx";

    @TempDir
    Path records;

    /** A host with or without herdr, answering each command from a script and recording it. */
    static final class FakeHost implements HostCommands {
        final boolean hasHerdr;
        final List<String> ran = new ArrayList<>();
        final Map<String, Result> answers = new HashMap<>();

        FakeHost(boolean hasHerdr) {
            this.hasHerdr = hasHerdr;
            answers.put("herdr machine add", new Result(0, "Saved SSH machine " + ID + ". Remote server is ready."));
            answers.put("herdr machine list --json", new Result(0, "[{\"id\":\"" + ID + "\",\"target\":\"ws\"}]"));
            answers.put("herdr machine remove", new Result(0, "Removed SSH machine " + ID + "."));
        }

        @Override
        public boolean onPath(String command) {
            return hasHerdr && command.equals("herdr");
        }

        @Override
        public Result run(Duration timeout, List<String> command) {
            var line = String.join(" ", command);
            ran.add(line);
            return answers.entrySet().stream().filter(e -> line.startsWith(e.getKey())).map(Map.Entry::getValue)
                    .findFirst().orElse(new Result(127, "not scripted: " + line));
        }
    }

    private HerdrIntegration herdr(FakeHost host) {
        return new HerdrIntegration(host, () -> records, (name, ip) -> true);
    }

    private static String buildWith(String tool, boolean projectLocal) {
        var tools = new java.util.LinkedHashMap<String, ToolDef>();
        var def = new ToolDef();
        def.setName(tool);
        tools.put(tool, def);
        var source = new BuildSource(Map.of(), tools, null, null);
        if (projectLocal) source.setProjectRoots(Map.of("tpl-herdr", "/home/u/project"));
        return source.toJson();
    }

    private static FakeIncusDaemon daemon(String name, Map<String, String> config) {
        return new FakeIncusDaemon().instance(name, "container", "Running", config);
    }

    private static RecordingOutput started(HerdrIntegration herdr, FakeIncusDaemon daemon, String name) {
        return RecordingOutput.during(() ->
                herdr.started(daemon.client(), name, daemon.client().instanceMetadataOrThrow(name), BuildOutput.bound()));
    }

    private String recorded(String name) throws Exception {
        return Files.readString(records.resolve(name)).strip();
    }

    @Test
    void theFirstStartAddsTheInstanceAndRecordsItsMachineSoLaterStartsRunNothing() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = new FakeHost(true);
        var herdr = herdr(host);

        var first = started(herdr, daemon, "ws");
        assertEquals(List.of("herdr machine add ws.start.isx --label ws"), host.ran);
        assertEquals(ID + "\n" + TARGET, recorded("ws"));
        assertEquals(List.of("Added ws to herdr."), first.events(RecordingOutput.Kind.STEP).stream()
                .map(RecordingOutput.Event::msg).toList());
        assertTrue(herdr.done("ws"));
        assertFalse(herdr.done("ws-2"), "a copy, another name, adds itself");

        host.ran.clear();
        RecordingOutput.during(() -> HostIntegration.startedAll(daemon.client(), "ws",
                daemon.client().instanceMetadataOrThrow("ws"), BuildOutput.bound()));
        assertEquals(List.of(), host.ran, "a recorded instance runs nothing");
        assertEquals(List.of("GET /1.0/instances/ws"), daemon.requests().subList(daemon.requests().size() - 1,
                daemon.requests().size()), "and writes nothing to Incus");
    }

    @Test
    void nothingRunsWithoutHerdrOnTheHost() {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = new FakeHost(false);
        var recorded = started(herdr(host), daemon, "ws");
        assertEquals(List.of(), host.ran);
        assertEquals(List.of(), recorded.events());
        assertFalse(Files.exists(records.resolve("ws")));
    }

    /** A project-local tool may be called herdr too: a host command is never run for one, and it is said once. */
    @Test
    void aProjectLocalOrUntrustedBuildIsNeverAddedAndSaidOnce() {
        var cases = List.of(Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", true)),
                Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false), Metadata.UNTRUSTED_LINEAGE, "failed-build"));
        for (var i = 0; i < cases.size(); i++) {
            var name = "ws" + i;
            var daemon = daemon(name, cases.get(i));
            var host = new FakeHost(true);
            var herdr = herdr(host);
            var recorded = started(herdr, daemon, name);
            assertEquals(List.of(), host.ran, cases.get(i).toString());
            assertEquals(List.of("Not adding " + name + " to herdr: its template is project-local or untrusted."),
                    recorded.events(RecordingOutput.Kind.STEP).stream().map(RecordingOutput.Event::msg).toList());
            assertTrue(herdr.done(name), "not said again on the next start");
        }
    }

    /**
     * Until ssh &lt;name&gt; reaches the instance through isx's SSH config (its Host block for its
     * address, and the Include in ~/.ssh/config), herdr is not asked: it would resolve the name as
     * any host, a real machine of that name included, and install and start its server there.
     * Nothing is recorded, so a later start adds it (review of #1823).
     */
    @Test
    void anInstanceSshDoesNotReachThroughIsxIsNotAddedYet() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false), Metadata.STATIC_IP, "10.166.11.20"));
        var host = new FakeHost(true);
        var asked = new ArrayList<String>();
        var notYet = new HerdrIntegration(host, () -> records, (name, ip) -> { asked.add(name + "@" + ip); return false; });

        var recorded = started(notYet, daemon, "ws");

        assertEquals(List.of(), host.ran, "herdr is never asked");
        assertEquals(List.of("ws@10.166.11.20"), asked);
        assertFalse(Files.exists(records.resolve("ws")), "nothing recorded, so a later start retries");
        assertEquals(List.of("Not adding ws to herdr yet: ssh ws does not reach it through isx's SSH config."),
                recorded.events(RecordingOutput.Kind.STEP).stream().map(RecordingOutput.Event::msg).toList());

        started(herdr(host), daemon, "ws");
        assertEquals(List.of("herdr machine add ws.start.isx --label ws"), host.ran, "added once the alias resolves");
    }

    /**
     * A failed add (a timeout, a refused connection) is tried once more, on the next start; a second
     * failure is one warning with the command, recorded as not added, and never tried again (Sanne's
     * decision on #1823).
     */
    @Test
    void aFailedAddIsRetriedOnceThenWarnedAndRecordedAsNotAdded() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = new FakeHost(true);
        host.answers.put("herdr machine add", new HostCommands.Result(1, "ssh: connect\nerror: could not reach ws"));
        var herdr = herdr(host);

        var first = started(herdr, daemon, "ws");
        assertEquals(List.of(), first.events(RecordingOutput.Kind.WARN), "no warning yet");
        assertEquals(List.of("Could not add ws to herdr yet (error: could not reach ws); isx tries again on its next start."),
                first.events(RecordingOutput.Kind.STEP).stream().map(RecordingOutput.Event::msg).toList());
        assertEquals(HerdrIntegration.RETRY, recorded("ws"));
        assertFalse(herdr.done("ws"));

        var second = started(herdr, daemon, "ws");
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.WARN,
                "could not add ws to herdr: error: could not reach ws", "Add it yourself: herdr machine add ws.start.isx --label ws")),
                second.events(RecordingOutput.Kind.WARN));
        assertEquals(HerdrIntegration.NOT_ADDED, recorded("ws"));
        assertTrue(herdr.done("ws"));

        started(herdr, daemon, "ws");
        assertEquals(2, host.ran.stream().filter(l -> l.startsWith("herdr machine add")).count(), "no third try");
    }

    /** The retry that works records the machine, with no warning. */
    @Test
    void aRetryThatWorksRecordsTheMachine() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = new FakeHost(true);
        var works = host.answers.get("herdr machine add");
        host.answers.put("herdr machine add", new HostCommands.Result(-1, "timed out after 30 seconds"));
        var herdr = herdr(host);
        started(herdr, daemon, "ws");
        assertEquals(HerdrIntegration.RETRY, recorded("ws"));

        host.answers.put("herdr machine add", works);
        var retry = started(herdr, daemon, "ws");
        assertEquals(ID + "\n" + TARGET, recorded("ws"));
        assertEquals(List.of(), retry.events(RecordingOutput.Kind.WARN));
        assertEquals(List.of("Added ws to herdr."), retry.events(RecordingOutput.Kind.STEP).stream()
                .map(RecordingOutput.Event::msg).toList());
    }

    /**
     * herdr keeps its machines across a stop and reconnects to them by itself, so the machine's
     * target is the alias whose connection starts a stopped instance, never the instance's name,
     * whose Host block a plain ssh reads and which never starts one (#2038).
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(TempHome.class)
    void theMachinesTargetStartsAStoppedInstance() throws Exception {
        assertTrue(dev.incusspawn.ssh.SshKeyManager.addHostEntry("ws", "10.0.0.5"));
        assertEquals(TARGET, HerdrIntegration.SshAlias.MANAGED.startTarget("ws"));
        var config = Files.readString(dev.incusspawn.Environment.sshConfigFile());
        assertTrue(config.contains("Host ws.start.isx\n"), config);
        assertTrue(config.contains(" ssh-proxy --start ws\n"), config);
        assertTrue(config.contains(" ssh-proxy ws\n"), "ssh ws still never starts it: " + config);

        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = new FakeHost(true);
        started(herdr(host), daemon, "ws");
        assertEquals(List.of("herdr machine add " + TARGET + " --label ws"), host.ran,
                "labelled with the name, so herdr --machine ws still finds it");
    }

    /**
     * A machine an older isx added for the instance's name never starts it, and herdr cannot change
     * a machine's target: the next start removes it and adds the instance again, once (#2038).
     */
    @Test
    void aMachineAnOlderIsxAddedIsReplacedOnceSoItStartsTheInstance() throws Exception {
        Files.writeString(records.resolve("ws"), ID + "\n");
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = new FakeHost(true);
        var next = "fedcba9876543210fedcba9876543210";
        host.answers.put("herdr machine add", new HostCommands.Result(0, "Saved SSH machine " + next + "."));
        var herdr = herdr(host);
        assertFalse(herdr.done("ws"), "asked again on its next start");
        assertTrue(herdr.registered("ws"), "still listed until then");

        var recorded = started(herdr, daemon, "ws");

        assertEquals(List.of("herdr machine list --json", "herdr machine remove " + ID,
                "herdr machine add " + TARGET + " --label ws"), host.ran);
        assertEquals(next + "\n" + TARGET, recorded("ws"));
        assertEquals(List.of(), recorded.events(RecordingOutput.Kind.WARN));
        assertTrue(herdr.done("ws"));
        host.ran.clear();
        RecordingOutput.during(() -> HostIntegration.startedAll(daemon.client(), "ws",
                daemon.client().instanceMetadataOrThrow("ws"), BuildOutput.bound()));
        assertEquals(List.of(), host.ran, "once");
    }

    /** herdr failing to list its machines is asked again on the next start, never taken for a refusal. */
    @Test
    void aMachineAnOlderIsxAddedIsReplacedOnTheNextStartWhenHerdrCannotListIt() throws Exception {
        Files.writeString(records.resolve("ws"), ID + "\n");
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = new FakeHost(true);
        var lists = host.answers.get("herdr machine list --json");
        host.answers.put("herdr machine list --json", new HostCommands.Result(-1, "timed out after 10 seconds"));
        var herdr = herdr(host);

        var first = started(herdr, daemon, "ws");
        assertEquals(List.of(), first.events(RecordingOutput.Kind.WARN));
        assertEquals(ID, recorded("ws"), "left as it was");
        assertFalse(herdr.done("ws"));

        host.answers.put("herdr machine list --json", lists);
        started(herdr, daemon, "ws");
        assertEquals(ID + "\n" + TARGET, recorded("ws"));
        assertTrue(herdr.usesStartAlias("ws"));
    }

    /** One that cannot be removed is kept, and recorded as reaching the name: warned once, never tried again. */
    @Test
    void aMachineAnOlderIsxAddedThatCannotBeRemovedIsKeptAndWarnedOnce() throws Exception {
        Files.writeString(records.resolve("ws"), ID + "\n");
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = new FakeHost(true);
        host.answers.put("herdr machine remove", new HostCommands.Result(1, "error: catalog is read-only"));
        var herdr = herdr(host);

        var recorded = started(herdr, daemon, "ws");

        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.WARN,
                "could not replace ws in herdr: error: catalog is read-only",
                "While it is stopped, herdr cannot reach it: start it with isx start ws")),
                recorded.events(RecordingOutput.Kind.WARN));
        assertEquals(ID + "\nws", recorded("ws"));
        assertTrue(herdr.done("ws"));
        assertFalse(herdr.usesStartAlias("ws"), "its start alias is not written again for it");
        assertTrue(herdr.registered("ws"), "a destroy still removes it");
    }

    private FakeHost hostThatTogglesMachines() {
        var host = new FakeHost(true);
        host.answers.put("herdr machine disable", new HostCommands.Result(0, "Disabled SSH machine " + ID + "."));
        host.answers.put("herdr machine enable", new HostCommands.Result(0, "Enabled SSH machine " + ID + "."));
        return host;
    }

    @Test
    void aStopDisablesTheMachineSoHerdrDoesNotStartItAgainAndTheNextStartEnablesIt() throws Exception {
        Files.writeString(records.resolve("ws"), ID + "\n" + TARGET + "\n");
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = hostThatTogglesMachines();
        var herdr = herdr(host);

        var stopping = RecordingOutput.during(() -> herdr.stopping("ws", BuildOutput.bound()));
        assertEquals(List.of("herdr machine disable " + ID), host.ran);
        assertEquals(ID + "\n" + TARGET + "\n" + HerdrIntegration.DISABLED, recorded("ws"));
        assertEquals(List.of(), stopping.events(), "nothing to say on a stop that worked");
        assertFalse(herdr.done("ws"), "so the next start enables it");
        assertTrue(herdr.registered("ws"));
        assertTrue(herdr.usesStartAlias("ws"));

        host.ran.clear();
        RecordingOutput.during(() -> herdr.stopping("ws", BuildOutput.bound()));
        assertEquals(List.of(), host.ran, "a machine already disabled is not asked for again");

        started(herdr, daemon, "ws");
        assertEquals(List.of("herdr machine enable " + ID), host.ran, "enabled, never added again");
        assertEquals(ID + "\n" + TARGET, recorded("ws"));
        assertTrue(herdr.done("ws"), "a later start runs nothing");
    }

    @Test
    void aStopRunsNothingForAnInstanceWithoutAMachineThatStartsIt() throws Exception {
        Files.writeString(records.resolve("old"), ID + "\n");
        Files.writeString(records.resolve("n"), HerdrIntegration.NOT_ADDED + "\n");
        Files.writeString(records.resolve("r"), HerdrIntegration.RETRY + "\n");
        var host = hostThatTogglesMachines();
        var herdr = herdr(host);
        var recorded = RecordingOutput.during(() -> {
            for (var name : List.of("none", "old", "n", "r")) herdr.stopping(name, BuildOutput.bound());
        });
        assertEquals(List.of(), host.ran, "an older isx's machine never starts the instance, so it is left enabled");
        assertEquals(List.of(), recorded.events());
        assertFalse(Files.exists(records.resolve("none")));

        Files.writeString(records.resolve("ws"), ID + "\n" + TARGET + "\n");
        RecordingOutput.during(() -> herdr(new FakeHost(false)).stopping("ws", BuildOutput.bound()));
        assertEquals(ID + "\n" + TARGET, recorded("ws"), "nothing without herdr on the host");
    }

    @Test
    void aStopThatFailsEnablesTheMachineAgain() throws Exception {
        Files.writeString(records.resolve("ws"), ID + "\n" + TARGET + "\n");
        var host = hostThatTogglesMachines();
        var herdr = herdr(host);
        RecordingOutput.during(() -> herdr.stopping("ws", BuildOutput.bound()));
        RecordingOutput.during(() -> herdr.stopFailed("ws", BuildOutput.bound()));
        assertEquals(List.of("herdr machine disable " + ID, "herdr machine enable " + ID), host.ran);
        assertEquals(ID + "\n" + TARGET, recorded("ws"));

        host.ran.clear();
        RecordingOutput.during(() -> herdr.stopFailed("ws", BuildOutput.bound()));
        assertEquals(List.of(), host.ran, "nothing to undo when isx disabled nothing");
    }

    @Test
    void aFailedDisableIsWarnedAndAFailedEnableIsWarnedOnce() throws Exception {
        Files.writeString(records.resolve("ws"), ID + "\n" + TARGET + "\n");
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = new FakeHost(true);
        host.answers.put("herdr machine disable", new HostCommands.Result(1, "machine profile " + ID + " was not found"));
        var herdr = herdr(host);

        var stopping = RecordingOutput.during(() -> herdr.stopping("ws", BuildOutput.bound()));
        assertEquals(1, stopping.events(RecordingOutput.Kind.WARN).size());
        assertTrue(stopping.events(RecordingOutput.Kind.WARN).getFirst().msg().contains("was not found"));
        assertEquals(ID + "\n" + TARGET, recorded("ws"), "not recorded as disabled");
        assertTrue(herdr.done("ws"));

        Files.writeString(records.resolve("ws"), ID + "\n" + TARGET + "\n" + HerdrIntegration.DISABLED + "\n");
        host.answers.put("herdr machine enable", new HostCommands.Result(1, "machine profile " + ID + " was not found"));
        host.ran.clear();
        var first = started(herdr, daemon, "ws");
        assertEquals(List.of("herdr machine enable " + ID), host.ran);
        assertEquals(1, first.events(RecordingOutput.Kind.WARN).size());
        assertTrue(herdr.done("ws"), "forgotten, so a machine the user removed is not asked for on every start");
    }

    /**
     * The instance details say herdr lists the instance only while isx's record holds the machine it
     * added: not before the first add, not for a retry or a decision not to add, not after a destroy.
     */
    @Test
    void registeredIsOnlyAnAddedMachineThatIsStillThere() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = new FakeHost(true);
        var herdr = herdr(host);
        assertFalse(herdr.registered("ws"), "nothing recorded");
        started(herdr, daemon, "ws");
        assertTrue(herdr.registered("ws"));
        assertFalse(herdr.registered("ws-2"), "a copy is not");

        Files.writeString(records.resolve("r"), HerdrIntegration.RETRY + "\n");
        Files.writeString(records.resolve("n"), HerdrIntegration.NOT_ADDED + "\n");
        assertFalse(herdr.registered("r"));
        assertFalse(herdr.registered("n"));

        herdr.destroyed("ws", BuildOutput.bound());
        assertFalse(herdr.registered("ws"));
    }

    @Test
    void aDestroyRemovesOnlyTheMachineIsxAdded() throws Exception {
        var host = new FakeHost(true);
        Files.writeString(records.resolve("ws"), ID + "\n");
        var recorded = RecordingOutput.during(() -> herdr(host).destroyed("ws", BuildOutput.bound()));
        assertEquals(List.of("herdr machine list --json", "herdr machine remove " + ID), host.ran);
        assertEquals(List.of(), recorded.events(RecordingOutput.Kind.WARN));
        assertFalse(Files.exists(records.resolve("ws")));
    }

    /**
     * The user's own herdr machine for a real host called dev survives destroying an isx instance
     * called dev, which isx never added: no record, no command.
     */
    @Test
    void aDestroyOfAnInstanceIsxNeverAddedRunsNothing() {
        var host = new FakeHost(true);
        host.answers.put("herdr machine list --json", new HostCommands.Result(0, "[{\"id\":\"" + USERS + "\",\"target\":\"dev\"}]"));
        herdr(host).destroyed("dev", BuildOutput.bound());
        assertEquals(List.of(), host.ran);
    }

    /** Without herdr on the PATH, the machine cannot be removed: the record stays, and the warning says how (review of #1823). */
    @Test
    void aDestroyWithoutHerdrKeepsTheMachineIdAndSaysHowToRemoveIt() throws Exception {
        var host = new FakeHost(false);
        Files.writeString(records.resolve("ws"), ID + "\n");
        var recorded = RecordingOutput.during(() -> herdr(host).destroyed("ws", BuildOutput.bound()));
        assertEquals(List.of(), host.ran);
        assertTrue(Files.exists(records.resolve(".orphaned").resolve(ID)), "kept, by its machine id");
        assertFalse(herdr(host).done("ws"), "not under the name: a new ws is added (review of #1823)");
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.WARN,
                "could not remove ws from herdr: herdr is not on the PATH.", "Remove it yourself: herdr machine remove " + ID)),
                recorded.events(RecordingOutput.Kind.WARN));
    }

    /** A machine the user already removed by hand, or one isx never added, is only forgotten. */
    @Test
    void aRecordWithoutAMachineIsOnlyForgotten() throws Exception {
        var host = new FakeHost(true);
        host.answers.put("herdr machine list --json", new HostCommands.Result(0, "[]"));
        Files.writeString(records.resolve("ws"), ID + "\n");
        Files.writeString(records.resolve("ws2"), HerdrIntegration.NOT_ADDED + "\n");
        herdr(host).destroyed("ws", BuildOutput.bound());
        herdr(host).destroyed("ws2", BuildOutput.bound());
        assertEquals(List.of("herdr machine list --json"), host.ran);
        assertFalse(Files.exists(records.resolve("ws")));
        assertFalse(Files.exists(records.resolve("ws2")));
    }

    @Test
    void theAddedIdIsTheOneHerdrSaved() {
        assertEquals(ID, HerdrIntegration.addedId("Saved SSH machine " + ID + ". Remote server is ready.\n"));
        assertNull(HerdrIntegration.addedId("Saved SSH machine. Remote server is ready."));
    }

    @Test
    void onlyAnInstanceWhoseBuildInstallsHerdrIsConsidered() {
        assertTrue(BuildSource.installs(buildWith("herdr", false), "herdr"));
        assertFalse(BuildSource.installs(buildWith("zmx", false), "herdr"));
        assertFalse(BuildSource.installs("", "herdr"));
    }

    /**
     * A new instance of a destroyed one's name is added to herdr, and once herdr is back on the PATH
     * the machine the destroy could not remove is removed and forgotten (review of #1823).
     */
    @Test
    void aMachineLeftByADestroyWithoutHerdrIsRemovedLaterAndANewInstanceOfTheNameIsAdded() throws Exception {
        Files.writeString(records.resolve("ws"), ID + "\n");
        RecordingOutput.during(() -> herdr(new FakeHost(false)).destroyed("ws", BuildOutput.bound()));

        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = new FakeHost(true);
        var next = "fedcba9876543210fedcba9876543210";
        host.answers.put("herdr machine add", new HostCommands.Result(0, "Saved SSH machine " + next + "."));
        started(herdr(host), daemon, "ws");

        assertEquals(List.of("herdr machine list --json", "herdr machine remove " + ID, "herdr machine add ws.start.isx --label ws"), host.ran);
        assertFalse(Files.exists(records.resolve(".orphaned").resolve(ID)), "forgotten once removed");
        assertEquals(next + "\n" + TARGET, recorded("ws"), "the new ws has its own machine");
    }

    /**
     * herdr connects with {@code StrictHostKeyChecking=yes}, so the instance's alias reaches it only
     * once its host key is pinned (#1863): before, herdr saved machines it could never reach again.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(TempHome.class)
    void theManagedAliasWantsTheHostKeyPinned() {
        assertTrue(dev.incusspawn.ssh.SshKeyManager.ensureSshConfigInclude());
        assertTrue(dev.incusspawn.ssh.SshKeyManager.addHostEntry("ws", "10.0.0.5"));
        assertFalse(HerdrIntegration.SshAlias.MANAGED.resolves("ws", "10.0.0.5"), "no key a strict client finds");

        dev.incusspawn.ssh.SshKeyManager.pinHostKeys("ws", "10.0.0.5",
                List.of(dev.incusspawn.ssh.TestHostKeys.key("ssh-ed25519", 5)));

        assertTrue(HerdrIntegration.SshAlias.MANAGED.resolves("ws", "10.0.0.5"));
    }
}
