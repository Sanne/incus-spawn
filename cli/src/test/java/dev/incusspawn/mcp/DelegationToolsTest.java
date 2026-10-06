package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.incus.IncusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** delegate and the task tools over the protocol, with the instance side scripted. */
class DelegationToolsTest {

    @TempDir
    Path home;
    private String realHome;

    private final FakeBackend backend = new FakeBackend()
            .template("tpl-agent", true, "claude")
            .template("tpl-plain", true);
    private final McpConfig config = new McpConfig();
    private final McpServerProtocolTest.Captured out = new McpServerProtocolTest.Captured();
    private McpServer server;
    private int nextId = 1;
    /** What the status script reports: running, or finished with this events tail. */
    private volatile String taskState = "running";
    /** When set, a launch waits here, as a slow exec would: counted down on entry, then awaited. */
    private volatile java.util.concurrent.CountDownLatch launchEntered;
    private volatile java.util.concurrent.CountDownLatch launchRelease;
    private volatile boolean failLaunch;
    /** When set, the status script reports a person's Claude Code on the task's conversation. */
    private volatile boolean attached;
    /** When set, the events a finished agent's status reports instead of {@link #EVENTS}. */
    private volatile String finishedEvents;
    /** What the tasks listing of an adopted instance answers. */
    private volatile String taskListing = "";
    /** When set, reading the tasks listing fails, as an exec the guest did not answer would. */
    private volatile boolean failListing;
    /** What the model check answers; null for a model the account can use. */
    private volatile String modelRefusal;
    /** How many busy tasks the per-user count finds in each instance another session holds. */
    private volatile int busyElsewhere;
    /** When set, reading a task's status fails, as an exec Incus cannot run would. */
    private volatile boolean failStatus;

    private static final String EVENTS = """
            {"type":"system","subtype":"init","session_id":"s1"}
            {"type":"assistant","message":{"content":[{"type":"text","text":"Found the race"},{"type":"tool_use","name":"Edit","input":{"file_path":"src/A.java"}}]}}
            {"type":"result","subtype":"success","is_error":false,"result":"Fixed the race in A.java; tests pass.","num_turns":7,"total_cost_usd":0.42}
            """;

    private Tasks tasks;
    private StructuredResults results;

    @BeforeEach
    void setUp() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        config.setTemplates(List.of("tpl-agent", "tpl-plain"));
        // Every other session is dead: another session's instance is an orphan.
        var session = new McpSession(new SessionId(7, 1), "alice", 1, "/work", backend, () -> config, s -> false);
        tasks = new Tasks(session, backend, () -> config);
        var all = new McpTools(session, backend, new TemplatePolicy(backend, () -> config), tasks).all();
        results = new StructuredResults(all);
        server = new McpServer(out, all, "1", null, null);
        backend.responder = script -> {
            if (script.equals(TaskScripts.busy())) {
                return java.util.stream.IntStream.range(0, busyElsewhere).mapToObj(i -> "task t" + i + "-other running\n")
                        .collect(java.util.stream.Collectors.joining());
            }
            if (isStateProbe(script)) { // one line per task asked about
                var ids = java.util.regex.Pattern.compile("; id=(t[0-9a-z-]+); ").matcher(script).results()
                        .map(m -> m.group(1) + switch (taskState) {
                            case "running" -> " running";
                            case "unknown" -> " unknown";
                            case "lost" -> " lost";
                            default -> " finished";
                        }).toList();
                return String.join("\n", ids) + "\n";
            }
            if (script.contains("systemd-run")) {
                if (launchEntered != null) {
                    launchEntered.countDown();
                    try {
                        launchRelease.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (failLaunch) throw new ToolError(ToolError.Code.UNAVAILABLE, "launch failed");
                return "";
            }
            if (script.contains(ModelCheck.MARKER)) {
                return "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":" + (modelRefusal != null) + ",\"result\":\""
                        + (modelRefusal == null ? "OK" : modelRefusal) + "\"}\n";
            }
            if (script.startsWith("for d in")) {
                if (failListing) throw new ToolError(ToolError.Code.UNAVAILABLE, "exec failed");
                return taskListing;
            }
            if (script.startsWith("f=$(mktemp)")) return "exit=0\nsummarised=12 lines, 345 bytes\n---\nIt touches A.java only.\n";
            if (script.contains("--numstat")) {
                return "## /home/agentuser\n3\t1\tsrc/A.java\0-\t-\tlogo.png\0\n---\n"
                        + (script.contains("--src-prefix") ? "diff --git a/src/A.java b/src/A.java\n+fixed\n" : "");
            }
            if (failStatus && script.contains("echo run=")) throw new IllegalStateException("exec failed");
            if (!script.startsWith("D=") || !script.contains("echo run=")) return ""; // cancel
            if (taskState.equals("unknown")) return "run=1\nkind=agent\nstate=unknown\n---\n\n---stderr\n";
            var who = attached ? "cwd=/home/agentuser\nsession_id=s1\npresence=claude 77 /elsewhere\n"
                    + "presence=resume 77 s1\n" : "";
            return taskState.equals("running")
                    ? "run=1\nkind=agent\nstate=running\n" + who + "events_bytes=10\n---\n" + EVENTS.lines().limit(2)
                            .reduce("", (a, b) -> a + b + "\n") + "\n---stderr\n"
                    : "run=1\nkind=agent\nstate=finished\nexit=0\n" + who + "events_bytes=400\n---\n"
                            + (finishedEvents != null ? finishedEvents : EVENTS) + "\n---stderr\n";
        };
    }

    private static boolean isStateProbe(String script) {
        return script.contains("echo $id $s");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", realHome);
    }

    private JsonNode call(String tool, String args) throws Exception {
        var id = nextId++;
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + args + "}}");
        server.awaitIdle();
        return results.check(tool, out.byId(id).path("result"));
    }

    private static String text(JsonNode r) {
        return r.path("content").get(0).path("text").asText();
    }

    private String delegateFresh() throws Exception {
        var r = call("delegate", "{\"instruction\":\"fix the flaky test\",\"template\":\"tpl-agent\"}");
        assertFalse(r.path("isError").asBoolean(), text(r));
        return r.path("structuredContent").path("task_id").asText();
    }

    private static JsonNode structured(JsonNode result) {
        assertFalse(result.path("isError").asBoolean(), text(result));
        return result.path("structuredContent");
    }

    @Test
    void everyToolReturnsStructuredContentMatchingItsSchema() throws Exception {
        structured(call("list_templates", "{}"));
        var created = structured(call("create_instance", "{\"template\":\"tpl-agent\",\"purpose\":\" #1010 \"}"));
        var name = created.path("instance").asText();
        assertEquals("#1010", created.path("purpose").asText(), "as stored and listed");
        var exec = structured(call("exec", "{\"instance\":\"" + name + "\",\"command\":\"ls\"}"));
        assertEquals(0, exec.path("exit_code").asInt());
        var command = structured(call("exec", "{\"instance\":\"" + name + "\",\"command\":\"mvn verify\",\"background\":true}"));
        assertEquals("command", command.path("kind").asText());
        var agent = structured(call("delegate", "{\"instruction\":\"fix it\",\"template\":\"tpl-agent\"}"));
        var task = agent.path("task_id").asText();
        assertEquals(1, agent.path("run").asInt());
        var listed = structured(call("list_instances", "{}")).path("instances");
        assertEquals(Map.of(task, "running", command.path("task_id").asText(), "running"), taskStates(listed));
        assertEquals("running", structured(call("task_status", "{\"task_id\":\"" + task + "\"}")).path("state").asText());
        structured(call("cancel_task", "{\"task_id\":\"" + command.path("task_id").asText() + "\"}"));

        taskState = "finished";
        var waited = structured(call("wait_any", "{\"task_ids\":[\"" + task + "\"]}"));
        assertEquals(task, waited.path("finished").get(0).asText());
        var status = structured(call("task_status", "{\"task_id\":\"" + task + "\"}"));
        assertEquals(0, status.path("exit_code").asInt());
        assertEquals(0.42, status.path("cost_usd").asDouble());
        var result = structured(call("task_result", "{\"task_id\":\"" + task + "\"}"));
        assertEquals("Fixed the race in A.java; tests pass.", result.path("report").asText());
        assertEquals(7, result.path("turns").asInt());
        var stat = structured(call("get_diff", "{\"task_id\":\"" + task + "\",\"stat\":true}"));
        var files = stat.path("repos").get(0).path("files");
        assertEquals("src/A.java", files.get(0).path("path").asText());
        assertEquals(3, files.get(0).path("added").asInt());
        assertTrue(files.get(1).path("added").isNull(), "a binary file has no line counts");
        assertTrue(structured(call("get_diff", "{\"task_id\":\"" + task + "\"}")).path("patch").asText().contains("+fixed"));
        // As last seen: the agent's end was read; the cancel did not read how the command ended.
        assertEquals(Map.of(task, "finished", command.path("task_id").asText(), "unknown"),
                taskStates(structured(call("list_instances", "{}")).path("instances")));
        structured(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"push it\"}"));

        taskState = "finished";
        structured(call("stop_instance", "{\"instance\":\"" + name + "\"}"));
        structured(call("start_instance", "{\"instance\":\"" + name + "\"}"));
        structured(call("instance_activity", "{\"instance\":\"" + name + "\"}"));
        structured(call("keep_instance", "{\"instance\":\"" + name + "\"}"));
        backend.instance("mcp-agent-old-abcde", Map.of(
                dev.incusspawn.incus.Metadata.PROFILE, "tpl-agent",
                dev.incusspawn.incus.Metadata.MCP_SESSION, "9-9",
                dev.incusspawn.incus.Metadata.MCP_OWNER, "alice",
                dev.incusspawn.incus.Metadata.MCP_ORPHANED, "2026-09-28T10:00:00Z"));
        var orphan = structured(call("list_instances", "{}")).path("instances").findParent("orphaned_since");
        assertEquals("orphaned", orphan.path("state").asText());
        structured(call("adopt_instance", "{\"instance\":\"mcp-agent-old-abcde\"}"));
        assertFalse(structured(call("destroy_instance", "{\"instance\":\"mcp-agent-old-abcde\"}")).path("already").asBoolean());

        var refused = call("task_status", "{\"task_id\":\"t9-nope\"}");
        assertEquals("not_found", refused.path("_meta").path(ToolResult.ERROR_META).path("code").asText());
        assertEquals(new java.util.TreeSet<>(results.toolNames()), new java.util.TreeSet<>(results.succeeded),
                "every tool returned a structured result checked against its schema");
    }

    /** list_instances' task entries, as task id to state. */
    private static Map<String, String> taskStates(JsonNode instances) {
        var states = new java.util.HashMap<String, String>();
        instances.forEach(i -> i.path("tasks").forEach(t -> states.put(t.path("task_id").asText(), t.path("state").asText())));
        return states;
    }

    @Test
    void aFinishedTaskWhoseStatusCannotBeReadIsUnknownNotLost() throws Exception {
        var task = delegateFresh();
        taskState = "finished";
        failStatus = true;
        var waited = structured(call("wait_any", "{\"task_ids\":[\"" + task + "\"]}"));
        assertEquals(task, waited.path("finished").get(0).asText(), "it is over");
        var entry = waited.path("tasks").get(0);
        assertEquals("unknown", entry.path("state").asText(), "finished or lost is not known: " + entry);
        assertFalse(entry.has("exit_code"));
    }

    @Test
    void delegatingToATemplateMakesAFreshInstanceAndStartsTheAgent() throws Exception {
        var task = delegateFresh();
        assertTrue(task.matches("t1-[0-9a-z]+"), task);
        var launch = backend.scripts.stream().filter(s -> s.contains("systemd-run")).findFirst().orElseThrow();
        assertTrue(launch.contains("--unit=isx-task-" + task + "-1"), launch);
        assertFalse(launch.contains("fix the flaky test"), "the instruction travels on stdin, never in a script");
    }

    @Test
    void aTemplateWithoutClaudeCannotBeDelegatedTo() throws Exception {
        var r = call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-plain\"}");
        assertTrue(r.path("isError").asBoolean());
        assertTrue(text(r).contains("supports_delegate"), text(r));
    }

    @Test
    void exactlyOneOfInstanceAndTemplate() throws Exception {
        assertTrue(call("delegate", "{\"instruction\":\"x\"}").path("isError").asBoolean());
        assertTrue(call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"instance\":\"i\"}")
                .path("isError").asBoolean());
    }

    @Test
    void statusShowsRecentActivityAndResultGivesTheReport() throws Exception {
        var task = delegateFresh();
        var status = text(call("task_status", "{\"task_id\":\"" + task + "\"}"));
        assertTrue(status.contains("state: running"), status);
        assertTrue(status.contains("- said: Found the race"), status);
        assertTrue(status.contains("- ran Edit: src/A.java"), status);
        assertTrue(call("task_result", "{\"task_id\":\"" + task + "\"}").path("isError").asBoolean(),
                "no result while running");

        taskState = "finished";
        var result = text(call("task_result", "{\"task_id\":\"" + task + "\"}"));
        assertTrue(result.contains("--- report ---\nFixed the race in A.java; tests pass.\n"), result);
        assertTrue(result.contains("cost_usd: 0.42"), result);
    }

    @Test
    void aSecondAgentInTheSameInstanceIsRefused() throws Exception {
        var task = delegateFresh();
        var instance = backend.instances.keySet().stream().filter(n -> n.startsWith("mcp-")).findFirst().orElseThrow();
        var r = call("delegate", "{\"instruction\":\"more\",\"instance\":\"" + instance + "\"}");
        assertTrue(r.path("isError").asBoolean());
        assertTrue(text(r).contains(task), text(r));
    }

    @Test
    void theConcurrentTaskLimitHolds() throws Exception {
        config.setMaxConcurrentTasks(1);
        delegateFresh();
        var r = call("delegate", "{\"instruction\":\"another\",\"template\":\"tpl-agent\"}");
        assertTrue(r.path("isError").asBoolean());
        assertTrue(text(r).contains("mcp.max-concurrent-tasks"), text(r));
    }

    @Test
    void tasksOtherSessionsStartWhileTheInstanceIsMadeStillCount() throws Exception {
        config.setMaxConcurrentTasks(2);
        // An orphan of alice's, from a session that ended: its tasks count against her cap.
        backend.instance("mcp-agent-orphan-aaaaa", Map.of(
                dev.incusspawn.incus.Metadata.MCP_SESSION, "9-9",
                dev.incusspawn.incus.Metadata.MCP_OWNER, "alice"));
        backend.onCreate = () -> busyElsewhere = 2; // started while the branch was being made
        var before = backend.instances.size();
        var r = call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\"}");
        assertTrue(r.path("isError").asBoolean(), text(r));
        assertTrue(text(r).contains("2 in other sessions"), text(r));
        assertEquals(before, backend.instances.size(), "the instance made for it is removed");
    }

    @Test
    void sendMessageWaitsForTheAgentToFinish() throws Exception {
        var task = delegateFresh();
        assertTrue(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"open a PR\"}")
                .path("isError").asBoolean());
        taskState = "finished";
        var r = call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"open a PR\"}");
        assertFalse(r.path("isError").asBoolean(), text(r));
        assertTrue(backend.scripts.stream().anyMatch(s -> s.contains("--unit=isx-task-" + task + "-2")));
    }

    private String agentInstance() {
        return backend.instances.keySet().stream().filter(n -> n.startsWith("mcp-")).findFirst().orElseThrow();
    }

    @Test
    void aRunningTaskBlocksAStopUnlessForcedWhichCancelsItFirst() throws Exception {
        var task = delegateFresh();
        var instance = agentInstance();
        var refused = call("stop_instance", "{\"instance\":\"" + instance + "\"}");
        assertTrue(refused.path("isError").asBoolean());
        assertTrue(text(refused).contains(task), text(refused));
        assertFalse(backend.stopped.contains(instance));

        var forced = call("stop_instance", "{\"instance\":\"" + instance + "\",\"force\":true}");
        assertFalse(forced.path("isError").asBoolean(), text(forced));
        assertTrue(backend.stopped.contains(instance));
        assertTrue(backend.scripts.stream().anyMatch(sc -> sc.contains("systemctl stop isx-task-" + task)),
                "the task was cancelled before the stop");
        assertTrue(tasks.all().stream().noneMatch(Tasks.Task::busy), "no task is left recorded as running");
    }

    @Test
    void aFinishedTaskDoesNotBlockAStop() throws Exception {
        delegateFresh();
        taskState = "finished";
        var r = call("stop_instance", "{\"instance\":\"" + agentInstance() + "\"}");
        assertFalse(r.path("isError").asBoolean(), text(r));
    }

    @Test
    void aStoppedInstanceIsReachedOnlyAfterStartInstance() throws Exception {
        var task = delegateFresh();
        taskState = "finished";
        var instance = agentInstance();
        call("stop_instance", "{\"instance\":\"" + instance + "\"}");
        for (var r : List.of(call("exec", "{\"instance\":\"" + instance + "\",\"command\":\"ls\"}"),
                call("delegate", "{\"instruction\":\"x\",\"instance\":\"" + instance + "\"}"),
                call("task_result", "{\"task_id\":\"" + task + "\"}"))) {
            assertTrue(r.path("isError").asBoolean());
            assertTrue(text(r).contains("start_instance"), text(r));
        }
        var waited = call("wait_any", "{\"task_ids\":[\"" + task + "\"],\"timeout_seconds\":1}");
        assertFalse(waited.path("isError").asBoolean(), "waiting copes with a stopped instance: " + text(waited));
        assertFalse(call("start_instance", "{\"instance\":\"" + instance + "\"}").path("isError").asBoolean());
        assertFalse(backend.stopped.contains(instance));
        assertFalse(call("task_result", "{\"task_id\":\"" + task + "\"}").path("isError").asBoolean());
    }

    /** The run script inside a launch, which travels base64-encoded. */
    private String runScript(int run) {
        var launch = backend.scripts.stream().filter(sc -> sc.contains("isx-task-") && sc.contains("-" + run + " --"))
                .reduce((a, b) -> b).orElseThrow();
        var m = java.util.regex.Pattern.compile("echo (\\S+) \\| base64 -d > \"\\$D/run-" + run + "\\.sh\"")
                .matcher(launch);
        assertTrue(m.find(), launch);
        return new String(java.util.Base64.getDecoder().decode(m.group(1)), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void aResumedAgentWorksWhereTheFirstRunDid() throws Exception {
        // Claude Code keeps its sessions per directory: --resume from elsewhere finds nothing.
        var instance = call("create_instance", "{\"template\":\"tpl-agent\"}").path("structuredContent")
                .path("instance").asText();
        var r = call("delegate", "{\"instruction\":\"x\",\"instance\":\"" + instance
                + "\",\"cwd\":\"/home/agentuser/other-repo\"}");
        var task = r.path("structuredContent").path("task_id").asText();
        taskState = "finished";
        assertFalse(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"more\"}")
                .path("isError").asBoolean());
        assertTrue(runScript(2).contains("cd -- '/home/agentuser/other-repo'"), runScript(2));
        assertTrue(runScript(2).contains("--resume"), runScript(2));
    }

    @Test
    void concurrentDelegationsToOneInstanceCannotBothStart() throws Exception {
        var instance = call("create_instance", "{\"template\":\"tpl-agent\"}").path("structuredContent")
                .path("instance").asText();
        launchEntered = new java.util.concurrent.CountDownLatch(1);
        launchRelease = new java.util.concurrent.CountDownLatch(1);
        var args = "{\"instruction\":\"x\",\"instance\":\"" + instance + "\"}";
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":101,\"method\":\"tools/call\",\"params\":{\"name\":\"delegate\",\"arguments\":" + args + "}}");
        assertTrue(launchEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "first launch under way");
        // The first launch has not returned yet; its slot must already count.
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":102,\"method\":\"tools/call\",\"params\":{\"name\":\"delegate\",\"arguments\":" + args + "}}");
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (out.sent.stream().noneMatch(m -> m.path("id").asInt() == 102) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        var second = out.byId(102).path("result");
        assertTrue(second.path("isError").asBoolean(), second.toString());
        launchRelease.countDown();
        server.awaitIdle();
        assertFalse(out.byId(101).path("result").path("isError").asBoolean());
    }

    @Test
    void concurrentMessagesToOneTaskCannotBothStart() throws Exception {
        var task = delegateFresh();
        taskState = "finished";
        launchEntered = new java.util.concurrent.CountDownLatch(1);
        launchRelease = new java.util.concurrent.CountDownLatch(1);
        var args = "{\"task_id\":\"" + task + "\",\"message\":\"m\"}";
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":201,\"method\":\"tools/call\",\"params\":{\"name\":\"send_message\",\"arguments\":" + args + "}}");
        assertTrue(launchEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":202,\"method\":\"tools/call\",\"params\":{\"name\":\"send_message\",\"arguments\":" + args + "}}");
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (out.sent.stream().noneMatch(m -> m.path("id").asInt() == 202) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(out.byId(202).path("result").path("isError").asBoolean());
        launchRelease.countDown();
        server.awaitIdle();
        assertEquals(1, backend.scripts.stream().filter(sc -> sc.contains("--unit=isx-task-" + task + "-2")).count());
    }

    @Test
    void aRefusedDelegationToATemplateLeavesNoInstanceBehind() throws Exception {
        config.setMaxConcurrentTasks(1);
        delegateFresh();
        var before = backend.instances.size();
        assertTrue(call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\"}").path("isError").asBoolean());
        assertEquals(before, backend.instances.size(), "no instance made only to be turned away");

        config.setMaxConcurrentTasks(5);
        failLaunch = true;
        assertTrue(call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\"}").path("isError").asBoolean());
        assertEquals(before, backend.instances.size(), "an instance whose task failed to start is removed");
    }

    private String instanceOf(String task) throws Exception {
        var listed = call("list_instances", "{}").path("structuredContent").path("instances");
        for (var inst : listed) {
            for (var t : inst.path("tasks")) {
                if (t.path("task_id").asText().equals(task)) return inst.path("instance").asText();
            }
        }
        throw new AssertionError("no instance for " + task);
    }

    @Test
    void destroyingAnInstanceFreesItsRunningTasks() throws Exception {
        config.setMaxConcurrentTasks(1);
        var task = delegateFresh();
        var instance = instanceOf(task);
        assertFalse(call("destroy_instance", "{\"instance\":\"" + instance + "\"}").path("isError").asBoolean());
        var r = call("delegate", "{\"instruction\":\"next\",\"template\":\"tpl-agent\"}");
        assertFalse(r.path("isError").asBoolean(), text(r));
    }

    @Test
    void anInstanceDeletedBehindTheSessionsBackDoesNotBlockNewTasks() throws Exception {
        // The user deleted it from the TUI: probing it fails, and must not fail everything else.
        var instance = instanceOf(delegateFresh());
        backend.instances.remove(instance);
        var r = call("delegate", "{\"instruction\":\"next\",\"template\":\"tpl-agent\"}");
        assertFalse(r.path("isError").asBoolean(), text(r));
        assertTrue(text(call("list_instances", "{}")).contains("\"state\" : \"running\""));
    }

    @Test
    void aStateNobodyCouldReadKeepsTheTaskCounted() throws Exception {
        var instance = call("create_instance", "{\"template\":\"tpl-agent\"}").path("structuredContent")
                .path("instance").asText();
        assertFalse(call("delegate", "{\"instruction\":\"x\",\"instance\":\"" + instance + "\"}")
                .path("isError").asBoolean());
        taskState = "unknown";
        var status = text(call("task_status", "{\"task_id\":\"" + instanceTask(instance) + "\"}"));
        assertTrue(status.contains("state: unknown"), status);
        var second = call("delegate", "{\"instruction\":\"y\",\"instance\":\"" + instance + "\"}");
        assertTrue(second.path("isError").asBoolean(), "a second agent in the same working tree");
        assertTrue(text(second).contains("already an agent"), text(second));
    }

    @Test
    void anInstanceIncusCannotAnswerForKeepsItsTasks() throws Exception {
        config.setMaxConcurrentTasks(1);
        var task = delegateFresh();
        var instance = instanceOf(task);
        // The daemon fails every read: neither the probe nor the existence check can answer.
        backend.metadataFailure = new ToolError(ToolError.Code.UNAVAILABLE, "cannot read it from Incus right now");
        var probe = backend.responder;
        backend.responder = script -> {
            throw new IncusException("exec failed (HTTP 500)");
        };
        var second = call("delegate", "{\"instruction\":\"next\",\"template\":\"tpl-agent\"}");
        assertTrue(text(second).contains("max-concurrent-tasks"), "the running task still holds the only slot: "
                + text(second));
        backend.metadataFailure = null;
        backend.responder = probe;
        assertEquals(task, instanceTask(instance));
    }

    private String instanceTask(String instance) throws Exception {
        var listed = call("list_instances", "{}").path("structuredContent").path("instances");
        for (var inst : listed) {
            if (inst.path("instance").asText().equals(instance)) return inst.path("tasks").get(0).path("task_id").asText();
        }
        throw new AssertionError("no task in " + instance);
    }

    @Test
    void anotherSessionsTaskIdIsUnknown() throws Exception {
        assertTrue(call("task_status", "{\"task_id\":\"t1-zz\"}").path("isError").asBoolean());
        assertTrue(call("cancel_task", "{\"task_id\":\"t1-zz\"}").path("isError").asBoolean());
    }

    @Test
    void theDelegatesPermissionModeIsExplicitAndPerTemplate() throws Exception {
        delegateFresh();
        assertTrue(runScript(1).contains("--permission-mode 'bypassPermissions'"), runScript(1));
        config.setDelegatePermissionModes(java.util.Map.of("tpl-agent", "plan"));
        var listed = text(call("list_templates", "{}"));
        assertTrue(listed.contains("\"permission_mode\" : \"plan\""), listed);
        config.setMaxConcurrentTasks(5);
        var r = call("delegate", "{\"instruction\":\"review\",\"template\":\"tpl-agent\"}");
        assertTrue(text(r).contains("\"permission_mode\" : \"plan\""), text(r));
        assertTrue(runScript(1).contains("--permission-mode 'plan'"), runScript(1));
    }

    @Test
    void aSkillIsRunAsItsSlashCommand() throws Exception {
        var r = call("delegate", "{\"skill\":\"fix-issue\",\"args\":\"#870 --careful\",\"template\":\"tpl-agent\"}");
        assertFalse(r.path("isError").asBoolean(), text(r));
        var launch = backend.scripts.indexOf(backend.scripts.stream().filter(sc -> sc.contains("systemd-run"))
                .findFirst().orElseThrow());
        assertEquals("/fix-issue #870 --careful", backend.stdins.get(launch), "the prompt, on stdin");

        for (var bad : List.of("{\"skill\":\"x; rm -rf ~\",\"template\":\"tpl-agent\"}",
                "{\"skill\":\"/fix\",\"template\":\"tpl-agent\"}",
                "{\"skill\":\"fix\",\"instruction\":\"x\",\"template\":\"tpl-agent\"}",
                "{\"instruction\":\"x\",\"args\":\"y\",\"template\":\"tpl-agent\"}")) {
            assertTrue(call("delegate", bad).path("isError").asBoolean(), bad);
        }
    }

    @Test
    void aPersonInTheConversationIsAStateAndHoldsOffMessages() throws Exception {
        var task = delegateFresh();
        taskState = "finished";
        attached = true;
        var status = text(call("task_status", "{\"task_id\":\"" + task + "\"}"));
        assertTrue(status.contains("state: attached"), status);
        assertTrue(status.contains("pid 77"), status);
        var r = call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"push it\"}");
        assertTrue(r.path("isError").asBoolean());
        assertTrue(text(r).contains("a person is in"), text(r));
        assertTrue(backend.scripts.stream().noneMatch(sc -> sc.contains("--unit=isx-task-" + task + "-2")));

        attached = false;
        assertFalse(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"push it\"}")
                .path("isError").asBoolean());
    }

    @Test
    void waitAnyReturnsWhenATaskFinishes() throws Exception {
        assertTrue(text(call("wait_any", "{}")).contains("No task"));
        var task = delegateFresh();
        var none = text(call("wait_any", "{\"timeout_seconds\":0}"));
        assertTrue(none.contains(task + " (agent in ") && none.contains("): running"), none);
        assertTrue(none.contains("None finished"), none);
        taskState = "finished";
        var done = text(call("wait_any", "{\"task_ids\":[\"" + task + "\"],\"timeout_seconds\":30}"));
        assertTrue(done.contains("): finished, exit 0"), done);
        assertTrue(done.contains("Finished: " + task), done);
        assertTrue(call("wait_any", "{\"task_ids\":[\"t9-nope\"]}").path("isError").asBoolean());
    }

    @Test
    void resultsStaySmallAndSayWhatTheAgentWasRefused() throws Exception {
        var task = delegateFresh();
        taskState = "finished";
        finishedEvents = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"Trying to push"}]}}
                {"type":"result","subtype":"success","is_error":false,"result":"%s","num_turns":3,"total_cost_usd":0.1,"permission_denials":[{"tool_name":"Bash","tool_use_id":"x","tool_input":{}}]}
                """.formatted("r".repeat(2000));
        var result = text(call("task_result", "{\"task_id\":\"" + task + "\",\"max_bytes\":300}"));
        assertTrue(result.contains("(first 300 of 2000 bytes"), result);
        assertTrue(result.contains("permission_denials: 1 (Bash)"), result);
        assertFalse(result.contains("Trying to push"), "the event tail is behind a flag");
        var withEvents = text(call("task_result", "{\"task_id\":\"" + task + "\",\"events\":true}"));
        assertTrue(withEvents.contains("- said: Trying to push"), withEvents);
        assertTrue(text(call("task_status", "{\"task_id\":\"" + task + "\"}")).contains("permission_denials: 1"));
    }

    @Test
    void askAnswersInsteadOfReturningTheText() throws Exception {
        var task = delegateFresh();
        taskState = "finished";
        var result = text(call("task_result", "{\"task_id\":\"" + task + "\",\"ask\":\"what broke?\"}"));
        assertTrue(result.contains("summarised: 12 lines, 345 bytes"), result);
        assertTrue(result.contains("It touches A.java only."), result);
        assertFalse(result.contains("--- report ---"), result);
        var ask = backend.scripts.indexOf(backend.scripts.stream().filter(sc -> sc.startsWith("f=$(mktemp)"))
                .findFirst().orElseThrow());
        assertEquals("Fixed the race in A.java; tests pass.", backend.stdins.get(ask), "the report is read inside the instance");

        var diff = text(call("get_diff", "{\"task_id\":\"" + task + "\",\"ask\":\"which areas?\"}"));
        assertTrue(diff.contains("It touches A.java only."), diff);
        assertTrue(backend.scripts.getLast().contains(TaskScripts.diffForReading(task, null)),
                "the model reads the stat one file per line, not NUL-separated");
        assertTrue(call("get_diff", "{\"task_id\":\"" + task + "\",\"ask\":\"x\",\"stat\":true}").path("isError").asBoolean());
    }

    @Test
    void aStatDiffIsJustTheFilesTouched() throws Exception {
        var task = delegateFresh();
        var stat = text(call("get_diff", "{\"task_id\":\"" + task + "\",\"stat\":true}"));
        assertTrue(stat.contains("3\t1\tsrc/A.java"), stat);
    }

    @Test
    void anOrphanAndItsTasksAreAdopted() throws Exception {
        backend.instance("mcp-agent-870-impl-abcde", java.util.Map.of(
                dev.incusspawn.incus.Metadata.TYPE, dev.incusspawn.incus.Metadata.TYPE_CLONE,
                dev.incusspawn.incus.Metadata.PARENT, "tpl-agent",
                dev.incusspawn.incus.Metadata.MCP_SESSION, "9-9",
                dev.incusspawn.incus.Metadata.MCP_OWNER, "alice",
                dev.incusspawn.incus.Metadata.MCP_PURPOSE, "#870 implement",
                dev.incusspawn.incus.Metadata.MCP_ORPHANED, "2026-09-28T10:00:00Z"));
        var listed = text(call("list_instances", "{}"));
        assertTrue(listed.contains("\"state\" : \"orphaned\""), listed);
        assertTrue(listed.contains("#870 implement"), listed);
        assertTrue(listed.contains("orphan_until"), listed);
        assertTrue(call("exec", "{\"instance\":\"mcp-agent-870-impl-abcde\",\"command\":\"ls\"}").path("isError").asBoolean(),
                "not held until adopted");

        taskListing = "t3-old agent 2 done claude-haiku-4-5 9 - /home/agentuser/repo dir\nnot a task line\n";
        var adopted = text(call("adopt_instance", "{\"instance\":\"mcp-agent-870-impl-abcde\"}"));
        assertTrue(adopted.contains("\"t3-old\""), adopted);
        taskState = "finished";
        assertTrue(text(call("task_result", "{\"task_id\":\"t3-old\"}")).contains("Fixed the race"));
        assertFalse(call("send_message", "{\"task_id\":\"t3-old\",\"message\":\"rebase\"}").path("isError").asBoolean());
        var run = runScript(3);
        assertTrue(run.contains("cd -- '/home/agentuser/repo dir'"), "resumed where it worked: " + run);
        assertTrue(run.contains("--resume"), run);
        assertTrue(run.contains("--model 'claude-haiku-4-5'") && run.contains("--max-turns 9 "),
                "an adopted task keeps its profile: " + run);
        assertEquals(0, modelChecks(), "a turn naming no model runs on the recorded one");
        assertFalse(call("send_message", "{\"task_id\":\"t3-old\",\"message\":\"m\",\"model\":\"claude-haiku-4-5\"}")
                .path("isError").asBoolean());
        assertEquals(1, modelChecks(), "naming the recorded model checks it: the instance wrote that record");
    }

    @Test
    void anInstanceAdoptedWhileStoppedGetsItsTasksBackWhenStarted() throws Exception {
        // Its coordinator stopped it to fork it, then ended; its tasks cannot be read until it runs.
        backend.instance("mcp-agent-src-abcde", Map.of(
                dev.incusspawn.incus.Metadata.PROFILE, "tpl-agent",
                dev.incusspawn.incus.Metadata.MCP_SESSION, "9-9",
                dev.incusspawn.incus.Metadata.MCP_OWNER, "alice"));
        backend.stopped.add("mcp-agent-src-abcde");
        taskListing = "t3-old agent 1 done /home/agentuser\n";
        var adopted = text(call("adopt_instance", "{\"instance\":\"mcp-agent-src-abcde\"}"));
        assertTrue(adopted.contains("warning"), adopted);
        var started = text(call("start_instance", "{\"instance\":\"mcp-agent-src-abcde\"}"));
        assertTrue(started.contains("t3-old"), started);
        taskState = "finished";
        assertTrue(text(call("task_result", "{\"task_id\":\"t3-old\"}")).contains("Fixed the race"));
    }

    @Test
    void aDormantOrphanIsStartedWhenAdoptedAndItsDelegateResumes() throws Exception {
        // The sweep stopped it: its delegate's run was cut off, its conversation is on the disk (#1028).
        var name = "mcp-agent-hung-abcde";
        backend.instance(name, Map.of(
                dev.incusspawn.incus.Metadata.PROFILE, "tpl-agent",
                dev.incusspawn.incus.Metadata.MCP_SESSION, "9-9",
                dev.incusspawn.incus.Metadata.MCP_OWNER, "alice",
                dev.incusspawn.incus.Metadata.MCP_ORPHANED, "2026-09-28T10:00:00Z 9-9",
                dev.incusspawn.incus.Metadata.MCP_DORMANT, "2026-09-30T10:00:00Z 9-9"));
        backend.stopped.add(name);
        var listed = structured(call("list_instances", "{}")).path("instances").get(0);
        assertEquals("orphaned", listed.path("state").asText(), "still an orphan: a dormant one is one too");
        assertEquals("2026-09-30T10:00:00Z", listed.path("dormant_since").asText());
        assertEquals("2026-10-07T10:00:00Z", listed.path("dormant_until").asText());

        taskListing = "t3-old agent 1 running - - - /home/agentuser/repo\n";
        taskState = "lost";
        var adopted = structured(call("adopt_instance", "{\"instance\":\"" + name + "\"}"));
        assertFalse(backend.stopped.contains(name), "started again");
        assertFalse(adopted.has("warning"), "its tasks were read: " + adopted);
        assertEquals("t3-old", adopted.path("tasks").get(0).asText());
        assertFalse(backend.instances.get(name).containsKey(dev.incusspawn.incus.Metadata.MCP_DORMANT));

        var sent = call("send_message", "{\"task_id\":\"t3-old\",\"message\":\"carry on\"}");
        assertFalse(sent.path("isError").asBoolean(), text(sent));
        var run = runScript(2);
        assertTrue(run.contains("--resume") && run.contains("cd -- '/home/agentuser/repo'"), run);
    }

    @Test
    void anInstanceStartedOrStoppedByItsHolderIsNoLongerDormant() throws Exception {
        // The dormant start failed, the agent started it with start_instance, then stopped it to fork it.
        var name = "mcp-agent-hung-abcde";
        backend.instance(name, Map.of(
                dev.incusspawn.incus.Metadata.PROFILE, "tpl-agent",
                dev.incusspawn.incus.Metadata.MCP_SESSION, "9-9",
                dev.incusspawn.incus.Metadata.MCP_OWNER, "alice",
                dev.incusspawn.incus.Metadata.MCP_ORPHANED, "2026-09-28T10:00:00Z 9-9",
                dev.incusspawn.incus.Metadata.MCP_DORMANT, "2026-09-30T10:00:00Z 9-9"));
        backend.stopped.add(name);
        backend.startFailure = new IllegalStateException("proxy down");
        var refused = call("adopt_instance", "{\"instance\":\"" + name + "\"}");
        assertEquals("unavailable", refused.path("_meta").path(ToolResult.ERROR_META).path("code").asText());
        backend.startFailure = null;
        structured(call("start_instance", "{\"instance\":\"" + name + "\"}"));
        assertFalse(backend.instances.get(name).containsKey(dev.incusspawn.incus.Metadata.MCP_DORMANT));
        structured(call("stop_instance", "{\"instance\":\"" + name + "\"}"));
        structured(call("adopt_instance", "{\"instance\":\"" + name + "\"}"));
        assertTrue(backend.stopped.contains(name), "stopped on purpose: a repeated adoption leaves it stopped");
    }

    @Test
    void aRefusedEnvironmentNameGivesItsTaskSlotBack() throws Exception {
        config.setMaxConcurrentTasks(1);
        var instance = call("create_instance", "{\"template\":\"tpl-plain\"}").path("structuredContent")
                .path("instance").asText();
        var bad = "{\"instance\":\"" + instance + "\",\"command\":\"true\",\"background\":true,\"env\":{\"FOO-BAR\":\"x\"}}";
        assertTrue(call("exec", bad).path("isError").asBoolean());
        assertTrue(call("exec", bad).path("isError").asBoolean());
        var r = call("exec", "{\"instance\":\"" + instance + "\",\"command\":\"true\",\"background\":true}");
        assertFalse(r.path("isError").asBoolean(), text(r));
    }

    @Test
    void tasksOfAnInstanceAnotherSessionAdoptedStopCounting() throws Exception {
        config.setMaxConcurrentTasks(1);
        var task = delegateFresh();
        var instance = instanceOf(task);
        backend.stamp(instance, dev.incusspawn.incus.Metadata.MCP_SESSION, "8-8"); // forced adoption
        var r = call("delegate", "{\"instruction\":\"next\",\"template\":\"tpl-agent\"}");
        assertFalse(r.path("isError").asBoolean(), text(r));
        assertTrue(call("task_status", "{\"task_id\":\"" + task + "\"}").path("isError").asBoolean());
    }

    @Test
    void aMessageToATaskForgottenMeanwhileIsRefusedNotACrash() throws Exception {
        var id = delegateFresh();
        var task = tasks.require(id).task();
        // A concurrent destroy_instance forgets the task between send_message's check and its reservation.
        tasks.forgetInstance(task.instance());
        var e = assertThrows(ToolError.class, () -> tasks.sendMessage(task, "push it", Tasks.Profile.NONE, "bypassPermissions", null));
        assertTrue(e.getMessage().contains("no longer this session's"), e.getMessage());
    }

    @Test
    void delegatingToATemplateAsksTheBusyInstancesOnce() throws Exception {
        config.setMaxConcurrentTasks(2);
        delegateFresh(); // still running: every new task must ask its instance whether it is
        var probes = backend.scripts.size();
        var reads = backend.metadataReads.get();
        delegateFresh();
        var stateProbes = backend.scripts.subList(probes, backend.scripts.size()).stream()
                .filter(DelegationToolsTest::isStateProbe).count();
        assertEquals(1, stateProbes, "one state probe of the running task's instance, not one per check");
        assertEquals(1, backend.metadataReads.get() - reads, "one check that the session still holds it");
    }

    @Test
    void aSlotTakenDuringTheCreateIsCountedButOneFreedIsToo() throws Exception {
        config.setMaxConcurrentTasks(2);
        var first = tasks.require(delegateFresh()).task();
        backend.onCreate = () -> {
            // While the new instance is copied, a background command takes the last slot, and
            // the first task finishes: only asking the instances again shows the slot free.
            tasks.startCommand(first.instance(), "/home/agentuser", Map.of(), "make", null);
            taskState = "done";
        };
        var r = call("delegate", "{\"instruction\":\"next\",\"template\":\"tpl-agent\"}");
        assertFalse(r.path("isError").asBoolean(), text(r));
    }

    private long modelChecks() {
        return backend.scripts.stream().filter(s -> s.contains(ModelCheck.MARKER)).count();
    }

    private long instancesMade() {
        return backend.instances.keySet().stream().filter(n -> n.startsWith("mcp-")).count();
    }

    @Test
    void aTaskRunsUnderItsOwnModelAndBudgetAndLaterTurnsKeepThem() throws Exception {
        config.setDelegateMaxTurns(200);
        var r = call("delegate", "{\"instruction\":\"rebase\",\"template\":\"tpl-agent\","
                + "\"model\":\"claude-haiku-4-5\",\"max_turns\":7}");
        assertFalse(r.path("isError").asBoolean(), text(r));
        var task = r.path("structuredContent").path("task_id").asText();
        assertTrue(runScript(1).contains(" --model 'claude-haiku-4-5'"), runScript(1));
        assertTrue(runScript(1).contains(" --max-turns 7 "), runScript(1));
        assertTrue(text(call("task_status", "{\"task_id\":\"" + task + "\"}")).contains("model: claude-haiku-4-5"));

        taskState = "finished";
        assertFalse(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"again\"}").path("isError").asBoolean());
        assertTrue(runScript(2).contains(" --model 'claude-haiku-4-5'") && runScript(2).contains(" --max-turns 7 "),
                "a later turn keeps the task's profile: " + runScript(2));
        assertFalse(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"think\",\"max_turns\":3}")
                .path("isError").asBoolean());
        assertTrue(runScript(3).contains(" --model 'claude-haiku-4-5'") && runScript(3).contains(" --max-turns 3 "),
                runScript(3));
        assertEquals(1, modelChecks(), "a model checked once is not checked again");
    }

    @Test
    void withoutAProfileTheTemplatesModelAndTheConfiguredBudgetApply() throws Exception {
        config.setDelegateMaxTurns(200);
        delegateFresh();
        assertFalse(runScript(1).contains("--model"), runScript(1));
        assertTrue(runScript(1).contains(" --max-turns 200 "), runScript(1));
        assertEquals(0, modelChecks(), "nothing to check");
    }

    @Test
    void theTurnBudgetCanNarrowTheUsersCeilingButNeverWidenIt() throws Exception {
        config.setDelegateMaxTurns(50);
        var r = call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"max_turns\":51}");
        assertTrue(r.path("isError").asBoolean());
        assertTrue(text(r).contains("mcp.delegate-max-turns"), text(r));
        assertTrue(call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"max_turns\":0}")
                .path("isError").asBoolean());
        assertEquals(0, instancesMade(), "refused before an instance was made");
        assertFalse(call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"max_turns\":50}")
                .path("isError").asBoolean());

        // A budget recorded under a higher ceiling is held to the one the user set since.
        var task = tasks.all().get(0).id();
        taskState = "finished";
        config.setDelegateMaxTurns(20);
        assertFalse(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"m\"}").path("isError").asBoolean());
        assertTrue(runScript(2).contains(" --max-turns 20 "), runScript(2));
    }

    @Test
    void aModelTheAccountCannotUseFailsTheCallAndLeavesNothingBehind() throws Exception {
        modelRefusal = "API Error: 404 model: claude-opus-9 not found";
        var r = call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"model\":\"claude-opus-9\"}");
        assertTrue(r.path("isError").asBoolean());
        assertTrue(text(r).contains("claude-opus-9 not found"), text(r));
        assertTrue(backend.scripts.stream().noneMatch(s -> s.contains("systemd-run")), "no task started");
        assertEquals(0, instancesMade(), "the instance made for it is gone");
        assertTrue(tasks.all().isEmpty());

        // A failure is not remembered: the account may be fixed meanwhile.
        modelRefusal = null;
        assertFalse(call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"model\":\"claude-opus-9\"}")
                .path("isError").asBoolean());
        assertEquals(2, modelChecks());
    }

    @Test
    void aModelIsCheckedPerTemplateAccountAndModel() throws Exception {
        config.setMaxConcurrentTasks(10);
        config.setMaxInstances(10);
        var instance = call("create_instance", "{\"template\":\"tpl-agent\"}").path("structuredContent")
                .path("instance").asText();
        call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"model\":\"haiku\"}");
        call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"model\":\"haiku\"}");
        assertEquals(1, modelChecks());
        call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"model\":\"opus\"}");
        assertEquals(2, modelChecks());
        // Another account can reach other models.
        backend.stamp(instance, dev.incusspawn.incus.Metadata.accountKey("claude"), "work");
        var r = call("delegate", "{\"instruction\":\"x\",\"instance\":\"" + instance + "\",\"model\":\"haiku\"}");
        assertFalse(r.path("isError").asBoolean(), text(r));
        assertEquals(3, modelChecks());
    }

    @Test
    void aModelThatIsNotAModelNameIsRefusedUnrun() throws Exception {
        var r = call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"model\":\"haiku --dangerously-skip-permissions\"}");
        assertTrue(r.path("isError").asBoolean());
        assertTrue(text(r).contains("model"), text(r));
        assertTrue(backend.scripts.isEmpty(), "nothing ran");
        assertEquals(0, instancesMade());
    }

    @Test
    void listTemplatesSaysWhatAProfileOverrides() throws Exception {
        config.setDelegateMaxTurns(200);
        backend.delegateModel("tpl-agent", "claude-opus-5-5");
        var listed = call("list_templates", "{}").path("structuredContent");
        var agent = listed.path("templates").get(0);
        assertEquals("tpl-agent", agent.path("template").asText());
        assertEquals("claude-opus-5-5", agent.path("delegate_model").asText());
        assertEquals(200, listed.path("delegate_max_turns").asInt());
        assertTrue(listed.path("templates").get(1).path("delegate_model").isMissingNode(), listed.toString());
    }

    @Test
    void aCallRefusedAnywaySpendsNoModelCheck() throws Exception {
        var task = delegateFresh();
        var r = call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"m\",\"model\":\"opus\"}");
        assertTrue(text(r).contains("still running"), text(r));
        var instance = tasks.all().get(0).instance();
        r = call("delegate", "{\"instruction\":\"x\",\"instance\":\"" + instance + "\",\"model\":\"opus\"}");
        assertTrue(text(r).contains("already an agent"), text(r));
        assertEquals(0, modelChecks());

        // A run refused by its check gives its slot back, and the task keeps its profile.
        taskState = "finished";
        modelRefusal = "API Error: 403";
        assertTrue(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"m\",\"model\":\"opus\"}")
                .path("isError").asBoolean());
        assertEquals(1, tasks.all().get(0).runs());
        assertEquals(null, tasks.all().get(0).profile().model());
        modelRefusal = null;
        assertFalse(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"m\"}").path("isError").asBoolean());
        assertTrue(runScript(2).contains("--resume") && !runScript(2).contains("--model"), runScript(2));
    }

    @Test
    void aCheckedModelIsCheckedAgainWhenTheAccountItResolvesToChanges() throws Exception {
        config.setMaxConcurrentTasks(10);
        call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"model\":\"haiku\"}");
        call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"model\":\"haiku\"}");
        assertEquals(1, modelChecks());
        backend.defaultAccount = "vertex"; // the user changed claude.default meanwhile
        call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"model\":\"haiku\"}");
        assertEquals(2, modelChecks());
    }

    @Test
    void aFreshInstancesAccountIsTheOneItsBranchPinnedWithoutReadingItBack() throws Exception {
        config.setMaxConcurrentTasks(10);
        backend.createdAccounts.put("claude", "work");
        var reads = backend.metadataReads.get();
        call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"model\":\"haiku\"}");
        assertEquals(reads, backend.metadataReads.get(), "no Incus read to learn what the branch wrote");
        // An instance pinned to the same account shares the check.
        var instance = call("create_instance", "{\"template\":\"tpl-agent\"}").path("structuredContent")
                .path("instance").asText();
        assertFalse(call("delegate", "{\"instruction\":\"x\",\"instance\":\"" + instance + "\",\"model\":\"haiku\"}")
                .path("isError").asBoolean());
        assertEquals(1, modelChecks());
    }

    @Test
    void taskStatusShowsTheBudgetTheRunGets() throws Exception {
        config.setDelegateMaxTurns(200);
        var r = call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"max_turns\":150}");
        var task = r.path("structuredContent").path("task_id").asText();
        config.setDelegateMaxTurns(50);
        assertTrue(text(call("task_status", "{\"task_id\":\"" + task + "\"}")).contains("max_turns: 50"));
    }

    @Test
    void everyToolReportsTheBudgetTheRunGets() throws Exception {
        // None chosen: the run gets the user's ceiling, and both tools say so.
        config.setDelegateMaxTurns(30);
        var started = call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\"}").path("structuredContent");
        var task = started.path("task_id").asText();
        assertEquals(30, started.path("max_turns").asInt());
        assertEquals(30, call("task_status", "{\"task_id\":\"" + task + "\"}").path("structuredContent").path("max_turns").asInt());

        // Chosen under a higher ceiling that was lowered since: the lower one, everywhere.
        config.setDelegateMaxTurns(200);
        var second = call("delegate", "{\"instruction\":\"y\",\"template\":\"tpl-agent\",\"max_turns\":150}")
                .path("structuredContent");
        assertEquals(150, second.path("max_turns").asInt());
        config.setDelegateMaxTurns(50);
        taskState = "finished";
        var id = second.path("task_id").asText();
        var sent = call("send_message", "{\"task_id\":\"" + id + "\",\"message\":\"more\"}").path("structuredContent");
        assertEquals(50, sent.path("max_turns").asInt(), sent.toString());
        assertEquals(50, call("task_status", "{\"task_id\":\"" + id + "\"}").path("structuredContent").path("max_turns").asInt());
    }

    // --- idempotency keys (#1011) ---

    private static String code(JsonNode result) {
        return result.path("_meta").path(ToolResult.ERROR_META).path("code").asText();
    }

    private long launches() {
        return backend.scripts.stream().filter(sc -> sc.contains("systemd-run")).count();
    }

    private String instanceFor(String purpose) throws Exception {
        return structured(call("create_instance", "{\"template\":\"tpl-agent\",\"purpose\":\"" + purpose + "\"}"))
                .path("instance").asText();
    }

    @Test
    void theSameKeyStartsOneTaskOfEachKind() throws Exception {
        var instance = instanceFor("keys");
        var args = "{\"instruction\":\"fix it\",\"instance\":\"" + instance + "\",\"idempotency_key\":\"t-1\"}";
        var first = structured(call("delegate", args));
        assertFalse(first.has("replayed"));
        var launch = backend.scripts.stream().filter(sc -> sc.contains("systemd-run")).findFirst().orElseThrow();
        assertTrue(launch.contains("printf '%s' 't-1' > \"$D/key\""), "recorded with the task: " + launch);
        taskState = "finished";
        var again = structured(call("delegate", args));
        assertEquals(first.path("task_id").asText(), again.path("task_id").asText());
        assertTrue(again.path("replayed").asBoolean());
        assertEquals(1, again.path("run").asInt());
        assertEquals(first.path("permission_mode").asText(), again.path("permission_mode").asText());
        assertEquals(1, launches());

        var cmd = "{\"instance\":\"" + instance + "\",\"command\":\"make test\",\"background\":true,\"idempotency_key\":\"c-1\"}";
        var started = structured(call("exec", cmd));
        var repeated = structured(call("exec", cmd));
        assertEquals(started.path("task_id").asText(), repeated.path("task_id").asText());
        assertTrue(repeated.path("replayed").asBoolean());
        assertTrue(repeated.path("background").asBoolean());
        assertEquals(2, launches());
        structured(call("exec", cmd.replace("c-1", "c-2")));
        assertEquals(3, launches(), "a different key is a different task");
    }

    @Test
    void aTaskKeyRepeatedForAnotherInstanceOrKindIsRefused() throws Exception {
        var a = instanceFor("a");
        var b = instanceFor("b");
        structured(call("delegate", "{\"instruction\":\"x\",\"instance\":\"" + a + "\",\"idempotency_key\":\"k\"}"));
        taskState = "finished";
        var elsewhere = call("delegate", "{\"instruction\":\"x\",\"instance\":\"" + b + "\",\"idempotency_key\":\"k\"}");
        assertEquals("invalid_argument", code(elsewhere), text(elsewhere));
        assertTrue(text(elsewhere).contains(a), "names where the key was used: " + text(elsewhere));
        var asCommand = call("exec", "{\"instance\":\"" + a + "\",\"command\":\"x\",\"background\":true,\"idempotency_key\":\"k\"}");
        assertEquals("invalid_argument", code(asCommand), text(asCommand));
        var waited = call("exec", "{\"instance\":\"" + a + "\",\"command\":\"x\",\"idempotency_key\":\"w\"}");
        assertEquals("invalid_argument", code(waited), "a command waited for has nothing to repeat: " + text(waited));
        assertEquals(1, launches());
    }

    @Test
    void aKeyedDelegationStillLaunchingIsBusyNotDoubled() throws Exception {
        var instance = instanceFor("busy");
        launchEntered = new java.util.concurrent.CountDownLatch(1);
        launchRelease = new java.util.concurrent.CountDownLatch(1);
        var args = "{\"instruction\":\"x\",\"instance\":\"" + instance + "\",\"idempotency_key\":\"k\"}";
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":301,\"method\":\"tools/call\",\"params\":{\"name\":\"delegate\",\"arguments\":" + args + "}}");
        assertTrue(launchEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "first launch under way");
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":302,\"method\":\"tools/call\",\"params\":{\"name\":\"delegate\",\"arguments\":" + args + "}}");
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (out.sent.stream().noneMatch(m -> m.path("id").asInt() == 302) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        var second = out.byId(302).path("result");
        assertEquals("busy", code(second), second.toString());
        assertTrue(text(second).contains("call again"), text(second));
        launchRelease.countDown();
        server.awaitIdle();
        var first = out.byId(301).path("result");
        assertFalse(first.path("isError").asBoolean(), first.toString());
        launchEntered = null;
        var third = structured(call("delegate", args));
        assertEquals(first.path("structuredContent").path("task_id").asText(), third.path("task_id").asText());
        assertTrue(third.path("replayed").asBoolean());
        assertEquals(1, launches());
    }

    @Test
    void aReplayIsNotRefusedByTheTaskLimitItAlreadyCountsIn() throws Exception {
        config.setMaxConcurrentTasks(1);
        var instance = instanceFor("cap");
        var args = "{\"instruction\":\"x\",\"instance\":\"" + instance + "\",\"idempotency_key\":\"k\"}";
        structured(call("delegate", args));
        assertTrue(structured(call("delegate", args)).path("replayed").asBoolean());
    }

    @Test
    void anAdoptedInstancesTaskKeysStillReplayThere() throws Exception {
        backend.instance("mcp-agent-870-impl-abcde", Map.of(
                dev.incusspawn.incus.Metadata.PROFILE, "tpl-agent",
                dev.incusspawn.incus.Metadata.PARENT, "tpl-agent",
                dev.incusspawn.incus.Metadata.MCP_SESSION, "9-9",
                dev.incusspawn.incus.Metadata.MCP_OWNER, "alice"));
        taskListing = "t3-old agent 1 done - - =issue-870 /home/agentuser\n"
                + "t4-old command 1 done - - =$(reboot) /home/agentuser\n"
                + "t5-old command 1 done - - - /home/agentuser\n";
        structured(call("adopt_instance", "{\"instance\":\"mcp-agent-870-impl-abcde\"}"));
        taskState = "finished";
        var r = structured(call("delegate", "{\"instruction\":\"x\",\"instance\":\"mcp-agent-870-impl-abcde\","
                + "\"idempotency_key\":\"issue-870\"}"));
        assertEquals("t3-old", r.path("task_id").asText());
        assertTrue(r.path("replayed").asBoolean());
        assertEquals(0, launches());
        // What the guest wrote is a key only if it reads as one.
        structured(call("exec", "{\"instance\":\"mcp-agent-870-impl-abcde\",\"command\":\"x\",\"background\":true,"
                + "\"idempotency_key\":\"reboot\"}"));
        assertEquals(1, launches());
        // A task without a key lists '-', which is a key too: it is no task's.
        var dash = structured(call("exec", "{\"instance\":\"mcp-agent-870-impl-abcde\",\"command\":\"x\",\"background\":true,"
                + "\"idempotency_key\":\"-\"}"));
        assertFalse(dash.has("replayed"), dash.toString());
        assertEquals(2, launches());
    }

    @Test
    void delegatingToATemplateUnderAKeyReplaysTheInstanceAndItsTask() throws Exception {
        var args = "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"idempotency_key\":\"issue-1011\"}";
        var first = structured(call("delegate", args));
        var instance = first.path("instance").asText();
        assertEquals("issue-1011", backend.instances.get(instance).get(dev.incusspawn.incus.Metadata.MCP_IDEMPOTENCY_KEY),
                "the instance carries the key");
        taskState = "finished";
        var again = structured(call("delegate", args));
        assertEquals(first.path("task_id").asText(), again.path("task_id").asText());
        assertEquals(instance, again.path("instance").asText());
        assertTrue(again.path("replayed").asBoolean());
        assertEquals(1, launches());
        assertEquals(1, backend.mcpInstances().size());

        var asTemplate = call("delegate", args.replace("tpl-agent", "tpl-plain"));
        assertEquals("invalid_argument", code(asTemplate), text(asTemplate));
    }

    @Test
    void aKeyedInstanceWithoutItsTaskGetsTheTaskNotASecondInstance() throws Exception {
        // The first call made the instance and was cut off before the task started; its session ended.
        backend.instance("mcp-agent-task-abcde", Map.of(
                dev.incusspawn.incus.Metadata.PROFILE, "tpl-agent",
                dev.incusspawn.incus.Metadata.PARENT, "tpl-agent",
                dev.incusspawn.incus.Metadata.STATIC_IP, "10.0.0.7",
                dev.incusspawn.incus.Metadata.MCP_SESSION, "9-9",
                dev.incusspawn.incus.Metadata.MCP_OWNER, "alice",
                dev.incusspawn.incus.Metadata.MCP_IDEMPOTENCY_KEY, "issue-1011"));
        var r = structured(call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\","
                + "\"idempotency_key\":\"issue-1011\"}"));
        assertEquals("mcp-agent-task-abcde", r.path("instance").asText());
        assertFalse(r.has("replayed"), "the task is new: " + r);
        assertEquals(1, launches());
        assertEquals(1, backend.mcpInstances().size());
        assertEquals("7-1", backend.instances.get("mcp-agent-task-abcde").get(dev.incusspawn.incus.Metadata.MCP_SESSION),
                "adopted first");
    }

    @Test
    void aKeyOfATaskAnotherSessionTookIsNoLongerThisSessions() throws Exception {
        var a = instanceFor("a");
        var b = instanceFor("b");
        structured(call("delegate", "{\"instruction\":\"x\",\"instance\":\"" + a + "\",\"idempotency_key\":\"k\"}"));
        backend.stamp(a, dev.incusspawn.incus.Metadata.MCP_SESSION, "8-8"); // forced adoption
        assertEquals("not_held", code(call("exec", "{\"instance\":\"" + a + "\",\"command\":\"ls\"}")));
        var r = call("delegate", "{\"instruction\":\"x\",\"instance\":\"" + b + "\",\"idempotency_key\":\"k\"}");
        assertFalse(r.path("isError").asBoolean(), "not refused as a mismatch with a task this session let go: " + text(r));
    }

    @Test
    void aTaskKeyUsedElsewhereIsRefusedBeforeAnInstanceIsMadeForIt() throws Exception {
        var a = instanceFor("a");
        structured(call("exec", "{\"instance\":\"" + a + "\",\"command\":\"make serve\",\"background\":true,"
                + "\"idempotency_key\":\"k\"}"));
        var instances = backend.mcpInstances().keySet();
        var r = call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"idempotency_key\":\"k\"}");
        assertEquals("invalid_argument", code(r), "not busy, which a retry would loop on: " + text(r));
        assertEquals(instances, backend.mcpInstances().keySet());
        assertEquals(List.of(), backend.destroyed);
    }

    @Test
    void aRepeatThatStartedTheTaskInTheInstanceKeepsItFromBeingRemoved() throws Exception {
        // The repeat arrives once the first call's instance is ready, before its task is reserved:
        // it finds the instance and starts the task there. The first call must not remove it.
        var args = "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"idempotency_key\":\"k\"}";
        backend.onListing = () -> {
            if (backend.instances.values().stream().noneMatch(c -> "k".equals(c.get(dev.incusspawn.incus.Metadata.MCP_IDEMPOTENCY_KEY)))) return;
            backend.onListing = null;
            server.handle("{\"jsonrpc\":\"2.0\",\"id\":401,\"method\":\"tools/call\",\"params\":{\"name\":\"delegate\",\"arguments\":" + args + "}}");
            var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (out.sent.stream().noneMatch(m -> m.path("id").asInt() == 401) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        };
        var first = call("delegate", args);
        server.awaitIdle();
        var repeat = out.byId(401).path("result");
        assertFalse(repeat.path("isError").asBoolean(), repeat.toString());
        var instance = repeat.path("structuredContent").path("instance").asText();
        assertEquals("busy", code(first), first.toString());
        assertEquals(List.of(), backend.destroyed, "the instance its repeat started the task in stays");
        assertEquals(java.util.Set.of(instance), backend.mcpInstances().keySet());
        assertEquals(1, launches());
        assertTrue(structured(call("delegate", args)).path("replayed").asBoolean());
    }

    @Test
    void aReplayedInstanceWhoseTasksCannotBeReadStartsNoSecondAgent() throws Exception {
        backend.instance("mcp-agent-task-abcde", Map.of(
                dev.incusspawn.incus.Metadata.PROFILE, "tpl-agent",
                dev.incusspawn.incus.Metadata.PARENT, "tpl-agent",
                dev.incusspawn.incus.Metadata.STATIC_IP, "10.0.0.7",
                dev.incusspawn.incus.Metadata.MCP_SESSION, "9-9",
                dev.incusspawn.incus.Metadata.MCP_OWNER, "alice",
                dev.incusspawn.incus.Metadata.MCP_IDEMPOTENCY_KEY, "issue-1011"));
        var args = "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"idempotency_key\":\"issue-1011\"}";
        failListing = true;
        var r = call("delegate", args);
        assertEquals("unavailable", code(r), text(r));
        assertEquals(0, launches());
        failListing = false;
        taskListing = "t3-old agent 1 done - - =issue-1011 /home/agentuser\n";
        taskState = "finished";
        var again = structured(call("delegate", args));
        assertEquals("t3-old", again.path("task_id").asText());
        assertTrue(again.path("replayed").asBoolean());
        assertEquals(0, launches());
    }
}
