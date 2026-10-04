package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code notifications/isx/task_changed} over the protocol, with the instance side scripted:
 * each transition reported once, however it was learned, and only to a client that asked.
 * Polling rounds are run by the test ({@link Tasks#watchRound}), never on a timer.
 */
class TaskChangedNotificationTest {

    @TempDir
    Path home;
    private String realHome;

    private final FakeBackend backend = new FakeBackend().template("tpl-agent", true, "claude");
    private final McpConfig config = new McpConfig();
    private final McpServerProtocolTest.Captured out = new McpServerProtocolTest.Captured();
    private McpServer server;
    private Tasks tasks;
    private McpSession session;
    private int nextId = 1;
    /** How the guest reports the task: running, finished (exit {@link #exit}), lost or unknown. */
    private volatile String guest = "running";
    private volatile int exit = 0;
    private volatile int guestRun = 1;
    /** When set, a person's Claude Code is resuming the task's conversation. */
    private volatile boolean attached;
    /** What the tasks listing of an adopted instance answers. */
    private volatile String listing = "";

    private static final Pattern ID = Pattern.compile("; id=(t[0-9a-z-]+); ");

    @BeforeEach
    void setUp() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        config.setTemplates(List.of("tpl-agent"));
        session = new McpSession(new SessionId(7, 1), "alice", 1, "/work", backend, () -> config, s -> false);
        tasks = new Tasks(session, backend, () -> config);
        var watcher = tasks.watcher();
        watcher.autoPoll = false;
        // Wired as McpMain wires it.
        server = new McpServer(out, new McpTools(session, backend, new TemplatePolicy(backend, () -> config),
                tasks).all(), "1", null, null, Map.of(TaskWatcher.CAPABILITY, watcher::start));
        watcher.sendTo(server::notify);
        backend.responder = script -> {
            // cancel_task and stop_instance(force); a watching client's cancel then asks how they ended.
            if (script.contains("systemctl stop")) guest = "finished";
            if (isWatch(script)) {
                var sb = new StringBuilder();
                ID.matcher(script).results().map(m -> m.group(1)).forEach(id -> {
                    sb.append("task ").append(id).append(' ').append(guestRun).append(' ').append(guest);
                    if (guest.equals("finished")) {
                        sb.append(' ').append(exit).append("\nsid ").append(id).append(" s1\ncwd ")
                                .append(id).append(" /home/agentuser");
                    }
                    sb.append('\n');
                });
                if (attached) sb.append("presence claude 77 /elsewhere\npresence resume 77 s1\n");
                return sb.toString();
            }
            if (script.contains("echo run=")) { // task_status
                return guest.equals("running") ? "run=1\nkind=agent\nstate=running\n---\n\n---stderr\n"
                        : "run=1\nkind=agent\nstate=finished\nexit=" + exit + "\n---\n\n---stderr\n";
            }
            if (script.startsWith("for d in")) return listing; // adopt_instance
            return "";
        };
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", realHome);
    }

    private static boolean isWatch(String script) {
        return script.contains("echo \"task $id");
    }

    private void initialize(boolean optIn) {
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":" + nextId++ + ",\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-11-25\",\"capabilities\":"
                + (optIn ? "{\"experimental\":{\"" + TaskWatcher.CAPABILITY + "\":{}}}" : "{}") + "}}");
    }

    private JsonNode call(String tool, String args) throws Exception {
        var id = nextId++;
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + args + "}}");
        server.awaitIdle();
        var result = out.byId(id).path("result");
        assertFalse(result.path("isError").asBoolean(), result.toString());
        return result;
    }

    private Tasks.Task delegate() throws Exception {
        var r = call("delegate", "{\"instruction\":\"fix the flaky test\",\"template\":\"tpl-agent\"}");
        var id = JsonRpc.JSON.readTree(r.path("content").get(0).path("text").asText()).path("task_id").asText();
        return tasks.require(id).task();
    }

    private List<JsonNode> changes() {
        return out.sent.stream().filter(m -> TaskWatcher.METHOD.equals(m.path("method").asText()))
                .map(m -> m.path("params")).toList();
    }

    private List<String> states() {
        return changes().stream().map(p -> p.path("state").asText()).toList();
    }

    private void rounds(boolean slow, int n) {
        for (int i = 0; i < n; i++) tasks.watchRound(slow);
    }

    @Test
    void theCapabilityIsOfferedAndWithoutItNothingIsSentOrPolled() throws Exception {
        initialize(false);
        assertTrue(out.byId(1).path("result").path("capabilities").path("experimental")
                .has(TaskWatcher.CAPABILITY));
        delegate();
        guest = "finished";
        call("task_status", "{\"task_id\":\"" + tasks.all().getFirst().id() + "\"}");
        assertFalse(tasks.watcher().enabled());
        assertEquals(List.of(), changes());
        assertTrue(backend.scripts.stream().noneMatch(TaskChangedNotificationTest::isWatch), "never polled");
    }

    @Test
    void aTaskThatFinishesIsReportedOnce() throws Exception {
        initialize(true);
        var task = delegate();
        assertEquals(List.of("running"), states(), "the launch");
        rounds(false, 2);
        assertEquals(List.of("running"), states(), "nothing changed");

        guest = "finished";
        exit = 3;
        rounds(false, 2);
        rounds(true, 2);
        assertEquals(List.of("running", "finished"), states());
        var finished = changes().getLast();
        assertEquals(task.id(), finished.path("task_id").asText());
        assertEquals(task.instance(), finished.path("instance").asText());
        assertEquals(1, finished.path("run").asInt());
        assertEquals(3, finished.path("exit_code").asInt());
        assertFalse(changes().getFirst().has("exit_code"), "a running task has none");
    }

    @Test
    void aPersonJoiningAndLeavingIsReportedTwice() throws Exception {
        initialize(true);
        delegate();
        guest = "finished";
        rounds(false, 1);
        var before = changes().size();

        attached = true;
        rounds(false, 1); // a finished agent is looked at only by a slow round
        assertEquals(before, changes().size());
        rounds(true, 2);
        attached = false;
        rounds(true, 2);
        assertEquals(List.of("attached", "finished"), states().subList(before, states().size()));
    }

    @Test
    void anUnknownStateIsNeverReported() throws Exception {
        initialize(true);
        delegate();
        guest = "unknown";
        rounds(true, 2);
        guest = "running";
        rounds(false, 1);
        assertEquals(List.of("running"), states());
    }

    @Test
    void whatATaskStatusCallLearnsIsReportedOnceAndNotAgainByThePoller() throws Exception {
        initialize(true);
        var task = delegate();
        guest = "finished";
        call("task_status", "{\"task_id\":\"" + task.id() + "\"}");
        assertEquals(List.of("running", "finished"), states());
        rounds(true, 1);
        assertEquals(List.of("running", "finished"), states());
    }

    @Test
    void aCancelledTaskIsReportedByTheCancelsOwnExec() throws Exception {
        initialize(true);
        var task = delegate();
        exit = 143;
        var execs = backend.scripts.size();
        call("cancel_task", "{\"task_id\":\"" + task.id() + "\"}");
        assertEquals(List.of("running", "finished"), states());
        assertEquals(143, changes().getLast().path("exit_code").asInt());
        assertEquals(1, backend.scripts.size() - execs, "one exec cancels and says how it ended");
    }

    @Test
    void stoppingWithForceReportsTheCancelledTasksBeforeTheInstanceStops() throws Exception {
        initialize(true);
        var task = delegate();
        exit = 143;
        call("stop_instance", "{\"instance\":\"" + task.instance() + "\",\"force\":true}");
        assertEquals(List.of("running", "finished"), states(), "a stopped instance could not be asked after");
        var execs = backend.scripts.size();
        rounds(false, 4);
        assertEquals(execs, backend.scripts.size(), "nothing left to ask a stopped instance");
    }

    @Test
    void anInstanceThatCannotBeAskedWaitsForTheNextSlowRound() throws Exception {
        initialize(true);
        var task = delegate();
        backend.stop(task.instance()); // stopped behind the session's back: its exec fails
        rounds(false, 1);
        var reads = backend.metadataReads.get();
        rounds(false, 3);
        assertEquals(reads, backend.metadataReads.get(), "neither execs nor asks Incus until the slow round");
        backend.start(task.instance());
        guest = "finished";
        rounds(true, 1);
        assertEquals(List.of("running", "finished"), states(), "asked again once reachable");
    }

    @Test
    void aTaskWhoseInstanceAnotherSessionAdoptedIsReleased() throws Exception {
        initialize(true);
        var task = delegate();
        backend.stamp(task.instance(), Metadata.MCP_SESSION, "99-1");
        rounds(false, 1);
        assertEquals(List.of("running"), states(), "holders are checked by slow rounds");
        rounds(true, 2);
        assertEquals(List.of("running", "released"), states());
    }

    @Test
    void aFinishedTaskIsReleasedToo() throws Exception {
        initialize(true);
        var task = delegate();
        guest = "finished";
        rounds(false, 1);
        backend.stamp(task.instance(), Metadata.MCP_SESSION, "99-1");
        rounds(true, 1);
        assertEquals(List.of("running", "finished", "released"), states(), "its id no longer works here");
    }

    @Test
    void anAdoptedTaskIsReportedByTheNextRound() throws Exception {
        initialize(true);
        backend.instance("mcp-agent-old-abcde", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE,
                Metadata.PARENT, "tpl-agent", Metadata.MCP_SESSION, "9-9", Metadata.MCP_OWNER, "alice",
                Metadata.MCP_ORPHANED, "2026-09-28T10:00:00Z"));
        listing = "t3-old agent 2 done /home/agentuser\n";
        guest = "finished";
        guestRun = 2;
        call("adopt_instance", "{\"instance\":\"mcp-agent-old-abcde\"}");
        rounds(false, 1);
        assertEquals(List.of("finished"), states());
        assertEquals("t3-old", changes().getFirst().path("task_id").asText());
    }

    @Test
    void anInstanceDestroyedWhileASlowRoundRunsIsLostNotReleased() throws Exception {
        initialize(true);
        delegate();
        var second = delegate();
        // destroy_instance on the second, landing while the round asks Incus about the first:
        // the session lets go of it before the round's own check reaches it.
        backend.onRead = () -> {
            backend.onRead = null;
            session.destroy(second.instance());
        };
        rounds(true, 1);
        assertEquals(List.of("running", "running", "lost"), states());
        assertEquals(second.id(), changes().getLast().path("task_id").asText());
    }

    @Test
    void aRunningTaskWhoseInstanceIsDestroyedIsLost() throws Exception {
        initialize(true);
        var task = delegate();
        call("destroy_instance", "{\"instance\":\"" + task.instance() + "\"}");
        rounds(true, 1);
        assertEquals(List.of("running", "lost"), states());
    }

    @Test
    void aFinishedTaskWhoseInstanceIsDestroyedLostNothing() throws Exception {
        initialize(true);
        var task = delegate();
        guest = "finished";
        rounds(false, 1);
        call("destroy_instance", "{\"instance\":\"" + task.instance() + "\"}");
        assertEquals(List.of("running", "finished"), states());
    }
}
