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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The host half of the rex tool (#2647): an instance whose template installs Rex is added to the
 * host's Rex hosts once, on its first start, at {@code ssh://<name>}, and only the host isx added is
 * removed when it is destroyed.
 */
class RexIntegrationTest {

    @TempDir
    Path records;

    /** A host whose rex keeps a list of hosts, as {@code rex hosts} does, recording each command. */
    static final class FakeRex implements HostCommands {
        boolean onPath = true;
        HostCommands.Result failAdd;
        HostCommands.Result failList;
        /** An add that saves the host and still reports this, as one killed for its time after saving does. */
        HostCommands.Result savedThenFailed;
        final List<String> ran = new ArrayList<>();
        /** Label to {id, endpoint}. */
        final Map<String, String[]> hosts = new LinkedHashMap<>();
        private int next = 1;

        String add(String label, String endpoint) {
            var id = "host:01a218e9-f7cd-7ec7-b9e3-5dd08f6481%02x".formatted(next++);
            hosts.put(label, new String[] {id, endpoint});
            return id;
        }

        @Override
        public boolean onPath(String command) {
            return onPath && command.equals("rex");
        }

        /** What {@code rex version} prints: Superlogical's Rex unless a test swaps in another. */
        HostCommands.Result version = new HostCommands.Result(0,
                "rex 0.1.0-preview.25\ncommit: b070aae645f5cbfac65d65d031a8b712b2ebca36\napi: revision 24 (supports 0 to 24)\n");
        final List<Path> directories = new ArrayList<>();

        @Override
        public Result run(Duration timeout, List<String> command, Path directory) {
            directories.add(directory);
            var args = command.subList(1, command.size()).stream().filter(a -> !a.equals("--")).toList();
            if (args.equals(List.of("version"))) return version;
            ran.add(String.join(" ", command));
            if (args.equals(List.of("hosts", "list", "--json"))) {
                if (failList != null) return failList;
                var json = new StringBuilder("{\"home\":{\"this_machine\":true},\"hosts\":[");
                var first = true;
                for (var e : hosts.entrySet()) {
                    if (!first) json.append(',');
                    first = false;
                    json.append("{\"host\":{\"id\":\"").append(e.getValue()[0]).append("\",\"label\":\"").append(e.getKey())
                            .append("\",\"endpoints\":[\"").append(e.getValue()[1]).append("\"]},\"from\":\"home\",\"held\":false}");
                }
                return new Result(0, json.append("],\"warnings\":[]}").toString());
            }
            if (args.size() == 4 && args.subList(0, 2).equals(List.of("hosts", "add"))) {
                if (failAdd != null) return failAdd;
                if (savedThenFailed != null) {
                    var failed = savedThenFailed;
                    savedThenFailed = null;
                    add(args.get(2), args.get(3));
                    return failed;
                }
                if (hosts.containsKey(args.get(2))) {
                    return new Result(1, "Error: a host named \"" + args.get(2) + "\" already exists (" + hosts.get(args.get(2))[1] + ").");
                }
                add(args.get(2), args.get(3));
                return new Result(0, "Added " + args.get(2) + ".");
            }
            if (args.size() == 3 && args.subList(0, 2).equals(List.of("hosts", "remove"))) {
                return hosts.remove(args.get(2)) != null ? new Result(0, "Removed " + args.get(2) + ".")
                        : new Result(1, "Error: no host has that label: \"" + args.get(2) + "\"");
            }
            return new Result(127, "not scripted: " + command);
        }
    }

    private RexIntegration rex(FakeRex host) {
        return new RexIntegration(host, () -> records, (name, ip) -> true);
    }

    private static String buildWith(String tool, boolean projectLocal) {
        var tools = new LinkedHashMap<String, ToolDef>();
        var def = new ToolDef();
        def.setName(tool);
        tools.put(tool, def);
        var source = new BuildSource(Map.of(), tools, null, null);
        if (projectLocal) source.setProjectRoots(Map.of("tpl-rex", "/home/u/project"));
        return source.toJson();
    }

    private static FakeIncusDaemon daemon(String name, Map<String, String> config) {
        return new FakeIncusDaemon().instance(name, "container", "Running", config);
    }

    private static RecordingOutput started(RexIntegration rex, FakeIncusDaemon daemon, String name) {
        return RecordingOutput.during(() ->
                rex.started(daemon.client(), name, daemon.client().instanceMetadataOrThrow(name), BuildOutput.bound()));
    }

    private static List<String> steps(RecordingOutput recorded) {
        return recorded.events(RecordingOutput.Kind.STEP).stream().map(RecordingOutput.Event::msg).toList();
    }

    private String recorded(String name) throws Exception {
        return Files.readString(records.resolve(name)).strip();
    }

    @Test
    void theFirstStartAddsTheInstanceAtItsOwnHostBlockAndLaterStartsRunNothing() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("rex", false)));
        var host = new FakeRex();
        var rex = rex(host);

        var first = started(rex, daemon, "ws");
        assertEquals(List.of("rex hosts add -- ws ssh://ws", "rex hosts list --json"), host.ran);
        assertEquals("ssh://ws", host.hosts.get("ws")[1], "never the start alias: Rex must not start a stopped instance");
        assertEquals(host.hosts.get("ws")[0], recorded("ws"));
        assertEquals(List.of("Added ws to Rex."), steps(first));
        assertTrue(rex.done("ws"));
        assertTrue(rex.registered("ws"));
        assertFalse(rex.done("ws-2"), "a copy, another name, adds itself");

        host.ran.clear();
        started(rex, daemon, "ws");
        assertEquals(List.of(), host.ran, "a recorded instance runs nothing");
    }

    /** An add Rex made is never later taken for the user's own host of that label. */
    @Test
    void aListFailingAfterTheAddIsNotTakenForTheUsersHostOnTheNextStart() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("rex", false)));
        var host = new FakeRex();
        host.failList = new HostCommands.Result(1, "Error: home server busy");
        var rex = rex(host);

        var first = started(rex, daemon, "ws");
        assertEquals(RexIntegration.NOT_ADDED, recorded("ws"), "recorded, so the next start does not add it again");
        assertEquals(1, first.events(RecordingOutput.Kind.WARN).size());
        assertTrue(first.events(RecordingOutput.Kind.WARN).getFirst().msg().startsWith("added ws to Rex"));

        host.ran.clear();
        started(rex, daemon, "ws");
        assertEquals(List.of(), host.ran);
    }

    @Test
    void nothingRunsWithoutRexOnTheHost() {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("rex", false)));
        var host = new FakeRex();
        host.onPath = false;
        var recorded = started(rex(host), daemon, "ws");
        assertEquals(List.of(), host.ran);
        assertEquals(List.of(), recorded.events());
        assertFalse(Files.exists(records.resolve("ws")), "a later start with Rex adds it");
    }

    @Test
    void aProjectLocalOrUntrustedBuildIsNeverAdded() {
        var cases = List.of(Map.of(Metadata.BUILD_SOURCE, buildWith("rex", true)),
                Map.of(Metadata.BUILD_SOURCE, buildWith("rex", false), Metadata.UNTRUSTED_LINEAGE, "failed-build"));
        for (var i = 0; i < cases.size(); i++) {
            var name = "ws" + i;
            var host = new FakeRex();
            var rex = rex(host);
            var recorded = started(rex, daemon(name, cases.get(i)), name);
            assertEquals(List.of(), host.ran, cases.get(i).toString());
            assertEquals(List.of("Not adding " + name + " to Rex: its template is project-local or untrusted."), steps(recorded));
            assertTrue(rex.done(name), "not said again on the next start");
            assertFalse(rex.registered(name));
        }
    }

    /** Rex's ssh runs in BatchMode: until ssh &lt;name&gt; reaches the instance through isx's config, nothing is added. */
    @Test
    void anInstanceSshDoesNotReachYetIsAddedOnALaterStart() {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("rex", false), Metadata.STATIC_IP, "10.166.11.20"));
        var host = new FakeRex();
        var asked = new ArrayList<String>();
        var notYet = new RexIntegration(host, () -> records, (name, ip) -> { asked.add(name + "@" + ip); return false; });

        var recorded = started(notYet, daemon, "ws");
        assertEquals(List.of(), host.ran);
        assertEquals(List.of("ws@10.166.11.20"), asked);
        assertEquals(List.of("Not adding ws to Rex yet: ssh ws does not reach it through isx's SSH config."), steps(recorded));
        assertFalse(Files.exists(records.resolve("ws")));

        started(rex(host), daemon, "ws");
        assertTrue(host.hosts.containsKey("ws"));
    }

    /** A host the user added under the instance's name is theirs: never replaced, never removed. */
    @Test
    void aHostOfTheUsersOwnWithTheNameIsLeftAlone() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("rex", false)));
        var host = new FakeRex();
        host.add("ws", "ssh://me@ws.example.com");
        var rex = rex(host);

        var recorded = started(rex, daemon, "ws");
        assertEquals(List.of("Not adding ws to Rex: it already has a host of that name."), steps(recorded));
        assertEquals(RexIntegration.NOT_ADDED, recorded("ws"));
        assertFalse(rex.registered("ws"));

        host.ran.clear();
        RecordingOutput.during(() -> rex.destroyed("ws", BuildOutput.bound()));
        assertEquals(List.of(), host.ran, "the user's host is never asked about");
        assertEquals("ssh://me@ws.example.com", host.hosts.get("ws")[1]);
        assertFalse(Files.exists(records.resolve("ws")));
    }

    @Test
    void aDestroyRemovesTheRecordedHostOnly() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("rex", false)));
        var host = new FakeRex();
        var rex = rex(host);
        started(rex, daemon, "ws");

        var recorded = RecordingOutput.during(() -> rex.destroyed("ws", BuildOutput.bound()));
        assertEquals(List.of(), recorded.events(RecordingOutput.Kind.WARN));
        assertFalse(host.hosts.containsKey("ws"));
        assertFalse(Files.exists(records.resolve("ws")));

        // The user removed isx's host and added one of their own under the name: it stays
        started(rex, daemon, "ws");
        host.hosts.remove("ws");
        host.add("ws", "ssh://me@elsewhere");
        RecordingOutput.during(() -> rex.destroyed("ws", BuildOutput.bound()));
        assertEquals("ssh://me@elsewhere", host.hosts.get("ws")[1]);
        assertFalse(Files.exists(records.resolve("ws")));

        host.ran.clear();
        RecordingOutput.during(() -> rex.destroyed("other", BuildOutput.bound()));
        assertEquals(List.of(), host.ran, "a destroy with no record runs nothing");
    }

    /** Destroyed while rex is not on the PATH: kept apart from the name, and removed by a later isx with Rex. */
    @Test
    void aHostLeftByADestroyWithoutRexIsRemovedLater() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("rex", false)));
        var host = new FakeRex();
        var rex = rex(host);
        started(rex, daemon, "ws");

        host.onPath = false;
        var recorded = RecordingOutput.during(() -> rex.destroyed("ws", BuildOutput.bound()));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.WARN,
                "could not remove ws from Rex: Superlogical's rex is not on the PATH.", "Remove it yourself: rex hosts remove ws")),
                recorded.events(RecordingOutput.Kind.WARN));
        assertFalse(Files.exists(records.resolve("ws")), "a new instance of the name is not taken as added");
        assertTrue(host.hosts.containsKey("ws"));

        host.onPath = true;
        var other = daemon("ws2", Map.of(Metadata.BUILD_SOURCE, buildWith("rex", false)));
        started(rex, other, "ws2");
        assertFalse(host.hosts.containsKey("ws"), "the destroyed instance's host is gone");
        assertTrue(host.hosts.containsKey("ws2"));
        try (var left = Files.list(records.resolve(".orphaned"))) {
            assertEquals(0, left.count());
        }
    }

    @Test
    void aFailedAddIsRetriedOnceThenWarnedAndNotTriedAgain() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("rex", false)));
        var host = new FakeRex();
        host.failAdd = new HostCommands.Result(1, "Error: connecting to home: timed out");
        var rex = rex(host);

        var first = started(rex, daemon, "ws");
        assertEquals(List.of(), first.events(RecordingOutput.Kind.WARN));
        assertEquals(List.of("Could not add ws to Rex yet (Error: connecting to home: timed out); isx tries again on its next start."),
                steps(first));
        assertFalse(rex.done("ws"));

        var second = started(rex, daemon, "ws");
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.WARN,
                "could not add ws to Rex: Error: connecting to home: timed out", "Add it yourself: rex hosts add ws ssh://ws")),
                second.events(RecordingOutput.Kind.WARN));
        assertEquals(RexIntegration.NOT_ADDED, recorded("ws"));

        started(rex, daemon, "ws");
        assertEquals(2, host.ran.stream().filter(l -> l.startsWith("rex hosts add")).count(), "no third try");
    }

    /**
     * Debian's, Ubuntu's and CPAN's rex is a Perl deployment tool that loads ./Rexfile as code on
     * every command: isx never runs a hosts command with it, and says so once.
     */
    @Test
    void anotherProgramNamedRexIsNeverAskedToAddOrRemove() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("rex", false)));
        var host = new FakeRex();
        host.version = new HostCommands.Result(1, "No Rexfile found.\nUsage: rex [<options>] [-H <host>] [-G <group>] <task> [<task-options>]");
        var rex = rex(host);

        var recorded = started(rex, daemon, "ws");
        assertEquals(List.of(), host.ran, "only rex version ran");
        assertEquals(List.of("Not adding ws to Rex: the rex on the PATH is not Superlogical's Rex."), steps(recorded));
        assertEquals(RexIntegration.NOT_ADDED, recorded("ws"));
        assertTrue(rex.done("ws"), "not asked again on the next start");

        // An earlier record of a host isx added, destroyed while another rex is first on the PATH
        Files.writeString(records.resolve("ws2"), "host:01a218e9-f7cd-7ec7-b9e3-5dd08f6481aa\n");
        RecordingOutput.during(() -> rex.destroyed("ws2", BuildOutput.bound()));
        assertEquals(List.of(), host.ran);
        assertTrue(Files.exists(records.resolve(".orphaned/01a218e9-f7cd-7ec7-b9e3-5dd08f6481aa")), "kept for a later isx with Rex");
    }

    /**
     * A Rexfile in the directory isx was started in is never read: every rex runs in an empty
     * directory under isx's records, where not even the record of an instance named Rexfile is.
     */
    @Test
    void everyRexCommandRunsInTheRecordsDirectory() {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("rex", false)));
        var host = new FakeRex();
        var rex = rex(host);
        started(rex, daemon, "ws");
        RecordingOutput.during(() -> rex.destroyed("ws", BuildOutput.bound()));
        assertFalse(host.directories.isEmpty());
        var working = records.resolve(RexIntegration.WORKING_DIRECTORY);
        assertTrue(host.directories.stream().allMatch(working::equals), host.directories.toString());
    }

    /** An add killed for its time after Rex saved the host: the retry takes it as isx's own, so a destroy removes it. */
    @Test
    void anAddThatTimedOutAfterSavingIsRecordedOnTheRetry() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("rex", false)));
        var host = new FakeRex();
        host.savedThenFailed = new HostCommands.Result(-1, "timed out after 20 seconds");
        var rex = rex(host);

        started(rex, daemon, "ws");
        assertEquals(RexIntegration.RETRY, recorded("ws"));

        var second = started(rex, daemon, "ws");
        assertEquals(List.of("Added ws to Rex."), steps(second));
        assertEquals(host.hosts.get("ws")[0], recorded("ws"));

        RecordingOutput.during(() -> rex.destroyed("ws", BuildOutput.bound()));
        assertFalse(host.hosts.containsKey("ws"), "isx's own host is removed with the instance");
    }

    /** A rex version that did not answer decides nothing: the next start asks again and adds it. */
    @Test
    void aRexVersionThatTimedOutIsAskedAgain() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("rex", false)));
        var host = new FakeRex();
        var answer = host.version;
        host.version = new HostCommands.Result(-1, "timed out after 20 seconds");
        var rex = rex(host);

        started(rex, daemon, "ws");
        assertFalse(Files.exists(records.resolve("ws")), "not recorded as another rex");
        assertEquals(List.of(), host.ran);

        host.version = answer;
        started(rex, daemon, "ws");
        assertTrue(host.hosts.containsKey("ws"));
        assertTrue(rex.registered("ws"));
    }

    /** Only a template that installs rex is added, through the built-in list every start goes through. */
    @Test
    void theBuiltInListHasRex() {
        assertTrue(HostIntegration.BUILT_IN.stream().anyMatch(i -> i.tool().equals("rex")));
    }
}
