package dev.incusspawn.mcp;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.incus.IncusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private McpConfig config = new McpConfig();
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
    private volatile String savedSession;
    /** Without {@link #savedSession}, the session a finished task's status reports, as a Claude run saves it once over; null for none. */
    private volatile String claudeSession = "s1";
    /** When set, the status reports that Claude Code deleted the saved session's transcript. */
    private volatile boolean claudeSessionGone;
    /** The agent a finished task's identity names, with {@link #savedSession}. */
    private volatile String savedAgent = "codex";
    /** What a Pi task's session check answers before send_message launches: ok, or why not. */
    private volatile String piSession = "ok";
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
            if (script.contains("/result-1.txt")) return "Codex completed the task";
            if (script.contains("tr ':.' '--'") && script.endsWith("echo ok")) return piSession + "\n";
            if (script.contains("--numstat")) {
                return "## /home/agentuser\n3\t1\tsrc/A.java\0-\t-\tlogo.png\0\n---\n"
                        + (script.contains("--src-prefix") ? "diff --git a/src/A.java b/src/A.java\n+fixed\n" : "");
            }
            if (failStatus && script.contains("echo run=")) throw new IllegalStateException("exec failed");
            if (!script.startsWith("D=") || !script.contains("echo run=")) return ""; // cancel
            if (taskState.equals("unknown")) return "run=1\nkind=agent\nstate=unknown\n---\n\n---stderr\n";
            var who = attached ? "cwd=/home/agentuser\nsession_id=s1\npresence=claude 77 /elsewhere\n"
                    + "presence=resume 77 s1\n" : savedSession == null ? (claudeSession == null || taskState.equals("running") ? "" : "session_id=" + claudeSession + "\n" + (claudeSessionGone ? "session_gone=1\n" : "")) : "identity=1 " + savedAgent + "\nsession_id=" + savedSession + "\n";
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

    private JqValue call(String tool, String args) throws Exception {
        var id = nextId++;
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + args + "}}");
        server.awaitIdle();
        return results.check(tool, out.byId(id).path("result"));
    }

    private static String text(JqValue r) {
        return r.path("content").getElement(0).path("text").asText();
    }

    private String delegateFresh() throws Exception {
        var r = call("delegate", "{\"instruction\":\"fix the flaky test\",\"template\":\"tpl-agent\"}");
        assertFalse(r.path("isError").asBoolean(false), text(r));
        return r.path("structuredContent").path("task_id").asText();
    }

    private static io.hyperfoil.tools.jjq.value.JqValue structured(io.hyperfoil.tools.jjq.value.JqValue result) {
        assertFalse(result.path("isError").asBoolean(false), text(result));
        return result.path("structuredContent");
    }

    @Test
    void aLeadingTildeInCwdIsTheAgentUsersHomeAndAFailedCdIsSaidInStderr() throws Exception {
        var name = structured(call("create_instance", "{\"template\":\"tpl-agent\"}")).path("instance").asText();
        structured(call("delegate", "{\"instruction\":\"go\",\"template\":\"tpl-agent\",\"cwd\":\"~/incus-spawn\"}"));
        var agent = runScript(1);
        assertTrue(agent.contains("cd -- '/home/agentuser/incus-spawn' 2> \"$D/stderr-1.log\" ||"), agent);

        structured(call("exec", "{\"instance\":\"" + name + "\",\"command\":\"ls\",\"cwd\":\"~\"}"));
        assertTrue(backend.scripts.getLast().contains("cd -- '/home/agentuser' ||"), backend.scripts.getLast());

        structured(call("exec", "{\"instance\":\"" + name + "\",\"command\":\"ls\",\"cwd\":\"~/x\",\"background\":true}"));
        var command = runScript(1);
        assertTrue(command.contains("cd -- '/home/agentuser/x' 2> \"$D/stderr\" ||"), command);
    }

    @Test
    void everyToolReturnsStructuredContentMatchingItsSchema() throws Exception {
        structured(call("list_templates", "{}"));
        var created = structured(call("create_instance", "{\"template\":\"tpl-agent\",\"purpose\":\" #1010 \"}"));
        var name = created.path("instance").asText();
        assertEquals("#1010", created.path("purpose").asText(), "as stored and listed");
        var exec = structured(call("exec", "{\"instance\":\"" + name + "\",\"command\":\"ls\"}"));
        assertEquals(0, exec.path("exit_code").asInt(0));
        var command = structured(call("exec", "{\"instance\":\"" + name + "\",\"command\":\"mvn verify\",\"background\":true}"));
        assertEquals("command", command.path("kind").asText());
        var agent = structured(call("delegate", "{\"instruction\":\"fix it\",\"template\":\"tpl-agent\"}"));
        var task = agent.path("task_id").asText();
        assertEquals(1, agent.path("run").asInt(0));
        var listed = structured(call("list_instances", "{}")).path("instances");
        assertEquals(Map.of(task, "running", command.path("task_id").asText(), "running"), taskStates(listed));
        assertEquals("running", structured(call("task_status", "{\"task_id\":\"" + task + "\"}")).path("state").asText());
        structured(call("cancel_task", "{\"task_id\":\"" + command.path("task_id").asText() + "\"}"));

        taskState = "finished";
        var waited = structured(call("wait_any", "{\"task_ids\":[\"" + task + "\"]}"));
        assertEquals(task, waited.path("finished").getElement(0).asText());
        var status = structured(call("task_status", "{\"task_id\":\"" + task + "\"}"));
        assertEquals(0, status.path("exit_code").asInt(0));
        assertEquals(0.42, status.path("cost_usd").asDouble(0.0));
        var result = structured(call("task_result", "{\"task_id\":\"" + task + "\"}"));
        assertEquals("Fixed the race in A.java; tests pass.", result.path("report").asText());
        assertEquals(7, result.path("turns").asInt(0));
        var stat = structured(call("get_diff", "{\"task_id\":\"" + task + "\",\"stat\":true}"));
        var files = stat.path("repos").getElement(0).path("files");
        assertEquals("src/A.java", files.getElement(0).path("path").asText());
        assertEquals(3, files.getElement(0).path("added").asInt(0));
        assertTrue(files.getElement(1).path("added").isNull(), "a binary file has no line counts");
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
        var orphan = (io.hyperfoil.tools.jjq.value.JqValue) null;
        for (var inst : structured(call("list_instances", "{}")).path("instances").asList()) {
            if (!inst.path("orphaned_since").isMissingNode()) { orphan = inst; break; }
        }
        assertNotNull(orphan);
        assertEquals("orphaned", orphan.path("state").asText());
        structured(call("adopt_instance", "{\"instance\":\"mcp-agent-old-abcde\"}"));
        assertFalse(structured(call("destroy_instance", "{\"instance\":\"mcp-agent-old-abcde\"}")).path("already").asBoolean(false));

        var refused = call("task_status", "{\"task_id\":\"t9-nope\"}");
        assertEquals("not_found", refused.path("_meta").path(ToolResult.ERROR_META).path("code").asText());
        assertEquals(new java.util.TreeSet<>(results.toolNames()), new java.util.TreeSet<>(results.succeeded),
                "every tool returned a structured result checked against its schema");
    }

    /** list_instances' task entries, as task id to state. */
    private static Map<String, String> taskStates(io.hyperfoil.tools.jjq.value.JqValue instances) {
        var states = new java.util.HashMap<String, String>();
        instances.asList().forEach(i -> i.path("tasks").asList().forEach(t -> states.put(t.path("task_id").asText(), t.path("state").asText())));
        return states;
    }

    @Test
    void listInstancesShowsATaskThatFinishedSinceTheLastLookAsFinished() throws Exception {
        var task = delegateFresh();
        assertEquals(Map.of(task, "running"), taskStates(structured(call("list_instances", "{}")).path("instances")));
        // Nobody asked task_status or wait_any: the session's last look still says running.
        taskState = "finished";
        assertEquals(Map.of(task, "finished"), taskStates(structured(call("list_instances", "{}")).path("instances")));
    }

    @Test
    void aFinishedTaskWhoseStatusCannotBeReadIsUnknownNotLost() throws Exception {
        var task = delegateFresh();
        taskState = "finished";
        failStatus = true;
        var waited = structured(call("wait_any", "{\"task_ids\":[\"" + task + "\"]}"));
        assertEquals(task, waited.path("finished").getElement(0).asText(), "it is over");
        var entry = waited.path("tasks").getElement(0);
        assertEquals("unknown", entry.path("state").asText(), "finished or lost is not known: " + entry);
        assertFalse(((io.hyperfoil.tools.jjq.value.JqObject) entry).keys().contains("exit_code"), "absent, not null: " + entry);
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
        assertTrue(r.path("isError").asBoolean(false));
        assertTrue(text(r).contains("supports_delegate"), text(r));
    }

    @Test
    void exactlyOneOfInstanceAndTemplate() throws Exception {
        assertTrue(call("delegate", "{\"instruction\":\"x\"}").path("isError").asBoolean(false));
        assertTrue(call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"instance\":\"i\"}")
                .path("isError").asBoolean(false));
    }

    @Test
    void statusShowsRecentActivityAndResultGivesTheReport() throws Exception {
        var task = delegateFresh();
        var status = text(call("task_status", "{\"task_id\":\"" + task + "\"}"));
        assertTrue(status.contains("state: running"), status);
        assertTrue(status.contains("- said: Found the race"), status);
        assertTrue(status.contains("- ran Edit: src/A.java"), status);
        assertTrue(call("task_result", "{\"task_id\":\"" + task + "\"}").path("isError").asBoolean(false),
                "no result while running");

        taskState = "finished";
        var result = text(call("task_result", "{\"task_id\":\"" + task + "\"}"));
        assertTrue(result.contains("--- report ---\nFixed the race in A.java; tests pass.\n"), result);
        assertTrue(result.contains("cost_usd: 0.42"), result);
    }

    @Test
    void aFreshInstanceTakesItsNameHint() throws Exception {
        var plain = instanceOf(delegateFresh());
        assertTrue(plain.matches("mcp-agent-task-[a-z0-9]{5}"), plain);

        var r = call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"name_hint\":\"870-impl\"}");
        var hinted = instanceOf(structured(r).path("task_id").asText());
        assertTrue(hinted.matches("mcp-agent-870-impl-[a-z0-9]{5}"), hinted);

        var before = instancesMade();
        var bad = call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"name_hint\":\"#870 impl\"}");
        assertTrue(bad.path("isError").asBoolean(false));
        assertTrue(text(bad).contains("name_hint"), text(bad));
        assertEquals(before, instancesMade(), "no instance for a refused hint");
    }

    @Test
    void aSecondAgentInTheSameInstanceIsRefused() throws Exception {
        var task = delegateFresh();
        var instance = backend.instances.keySet().stream().filter(n -> n.startsWith("mcp-")).findFirst().orElseThrow();
        var r = call("delegate", "{\"instruction\":\"more\",\"instance\":\"" + instance + "\"}");
        assertTrue(r.path("isError").asBoolean(false));
        assertTrue(text(r).contains(task), text(r));
    }

    @Test
    void theConcurrentTaskLimitHolds() throws Exception {
        config.setMaxConcurrentTasks(1);
        delegateFresh();
        var r = call("delegate", "{\"instruction\":\"another\",\"template\":\"tpl-agent\"}");
        assertTrue(r.path("isError").asBoolean(false));
        assertTrue(text(r).contains("max-concurrent-tasks in mcp.yaml"), text(r));
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
        assertTrue(r.path("isError").asBoolean(false), text(r));
        assertTrue(text(r).contains("2 in other sessions"), text(r));
        assertEquals(before, backend.instances.size(), "the instance made for it is removed");
    }

    @Test
    void sendMessageWaitsForTheAgentToFinish() throws Exception {
        var task = delegateFresh();
        assertTrue(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"open a PR\"}")
                .path("isError").asBoolean(false));
        taskState = "finished";
        var r = call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"open a PR\"}");
        assertFalse(r.path("isError").asBoolean(false), text(r));
        assertTrue(backend.scripts.stream().anyMatch(s -> s.contains("--unit=isx-task-" + task + "-2")));
    }

    @Test
    void aClaudeTaskThatSavedNoSessionIsNotResumableAndMessagesAreRefused() throws Exception {
        var task = delegateFresh();
        taskState = "finished";
        claudeSession = null; // cancelled before Claude Code wrote its session
        assertFalse(structured(call("task_status", "{\"task_id\":\"" + task + "\"}")).path("resumable").asBoolean(true));
        var launched = launches();
        var r = call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"carry on\"}");
        assertEquals("wrong_state", code(r));
        assertTrue(text(r).contains("start a new delegated task"), text(r));
        assertEquals(launched, launches(), "no run that could only fail");
    }

    @Test
    void aClaudeTaskWhoseTranscriptIsGoneIsNotResumableAndMessagesAreRefused() throws Exception {
        var task = delegateFresh();
        taskState = "finished";
        claudeSessionGone = true; // Claude Code's cleanupPeriodDays deleted it
        var status = structured(call("task_status", "{\"task_id\":\"" + task + "\"}"));
        assertEquals("s1", status.path("session_id").asText());
        assertFalse(status.path("resumable").asBoolean(true));
        assertTrue(status.path("join_command").isMissingNode(), status.toString());
        var launched = launches();
        var r = call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"carry on\"}");
        assertEquals("wrong_state", code(r));
        assertTrue(text(r).contains("its Claude Code conversation is gone"), text(r));
        assertEquals(launched, launches(), "no run that could only fail");
    }

    private String agentInstance() {
        return backend.instances.keySet().stream().filter(n -> n.startsWith("mcp-")).findFirst().orElseThrow();
    }

    @Test
    void aRunningTaskBlocksAStopUnlessForcedWhichCancelsItFirst() throws Exception {
        var task = delegateFresh();
        var instance = agentInstance();
        var refused = call("stop_instance", "{\"instance\":\"" + instance + "\"}");
        assertTrue(refused.path("isError").asBoolean(false));
        assertTrue(text(refused).contains(task), text(refused));
        assertFalse(backend.stopped.contains(instance));

        var forced = call("stop_instance", "{\"instance\":\"" + instance + "\",\"force\":true}");
        assertFalse(forced.path("isError").asBoolean(false), text(forced));
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
        assertFalse(r.path("isError").asBoolean(false), text(r));
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
            assertTrue(r.path("isError").asBoolean(false));
            assertTrue(text(r).contains("start_instance"), text(r));
        }
        var waited = call("wait_any", "{\"task_ids\":[\"" + task + "\"],\"timeout_seconds\":1}");
        assertFalse(waited.path("isError").asBoolean(false), "waiting copes with a stopped instance: " + text(waited));
        assertFalse(call("start_instance", "{\"instance\":\"" + instance + "\"}").path("isError").asBoolean(false));
        assertFalse(backend.stopped.contains(instance));
        assertFalse(call("task_result", "{\"task_id\":\"" + task + "\"}").path("isError").asBoolean(false));
    }

    /** The run script inside a launch, which travels base64-encoded. */
    private String runScript(int run) {
        var launch = backend.scripts.stream().filter(sc -> sc.contains("isx-task-") && sc.contains("-" + run + " --"))
                .reduce((a, b) -> b).orElseThrow();
        var m = java.util.regex.Pattern.compile("echo (\\S+) \\| base64 -d > \"\\$w\" && mv -fT \"\\$w\" \"\\$D/run-" + run + "\\.sh\"")
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
                .path("isError").asBoolean(false));
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
        while (out.sent.stream().noneMatch(m -> m.path("id").asInt(0) == 102) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        var second = out.byId(102).path("result");
        assertTrue(second.path("isError").asBoolean(false), second.toString());
        launchRelease.countDown();
        server.awaitIdle();
        assertFalse(out.byId(101).path("result").path("isError").asBoolean(false));
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
        while (out.sent.stream().noneMatch(m -> m.path("id").asInt(0) == 202) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(out.byId(202).path("result").path("isError").asBoolean(false));
        launchRelease.countDown();
        server.awaitIdle();
        assertEquals(1, backend.scripts.stream().filter(sc -> sc.contains("--unit=isx-task-" + task + "-2")).count());
    }

    @Test
    void aRefusedDelegationToATemplateLeavesNoInstanceBehind() throws Exception {
        config.setMaxConcurrentTasks(1);
        delegateFresh();
        var before = backend.instances.size();
        assertTrue(call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\"}").path("isError").asBoolean(false));
        assertEquals(before, backend.instances.size(), "no instance made only to be turned away");

        config.setMaxConcurrentTasks(5);
        failLaunch = true;
        assertTrue(call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\"}").path("isError").asBoolean(false));
        assertEquals(before, backend.instances.size(), "an instance whose task failed to start is removed");
    }

    private String instanceOf(String task) throws Exception {
        var listed = call("list_instances", "{}").path("structuredContent").path("instances");
        for (var inst : listed.asList()) {
            for (var t : inst.path("tasks").asList()) {
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
        assertFalse(call("destroy_instance", "{\"instance\":\"" + instance + "\"}").path("isError").asBoolean(false));
        var r = call("delegate", "{\"instruction\":\"next\",\"template\":\"tpl-agent\"}");
        assertFalse(r.path("isError").asBoolean(false), text(r));
    }

    @Test
    void anInstanceDeletedBehindTheSessionsBackDoesNotBlockNewTasks() throws Exception {
        // The user deleted it from the TUI: probing it fails, and must not fail everything else.
        var instance = instanceOf(delegateFresh());
        backend.instances.remove(instance);
        var r = call("delegate", "{\"instruction\":\"next\",\"template\":\"tpl-agent\"}");
        assertFalse(r.path("isError").asBoolean(false), text(r));
        assertTrue(text(call("list_instances", "{}")).contains("\"state\" : \"running\""));
    }

    @Test
    void aStateNobodyCouldReadKeepsTheTaskCounted() throws Exception {
        var instance = call("create_instance", "{\"template\":\"tpl-agent\"}").path("structuredContent")
                .path("instance").asText();
        assertFalse(call("delegate", "{\"instruction\":\"x\",\"instance\":\"" + instance + "\"}")
                .path("isError").asBoolean(false));
        taskState = "unknown";
        var status = text(call("task_status", "{\"task_id\":\"" + instanceTask(instance) + "\"}"));
        assertTrue(status.contains("state: unknown"), status);
        var second = call("delegate", "{\"instruction\":\"y\",\"instance\":\"" + instance + "\"}");
        assertTrue(second.path("isError").asBoolean(false), "a second agent in the same working tree");
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
        for (var inst : listed.asList()) {
            if (inst.path("instance").asText().equals(instance)) return inst.path("tasks").getElement(0).path("task_id").asText();
        }
        throw new AssertionError("no task in " + instance);
    }

    @Test
    void anotherSessionsTaskIdIsUnknown() throws Exception {
        assertTrue(call("task_status", "{\"task_id\":\"t1-zz\"}").path("isError").asBoolean(false));
        assertTrue(call("cancel_task", "{\"task_id\":\"t1-zz\"}").path("isError").asBoolean(false));
    }

    @Test
    void theDelegatesPermissionModeIsExplicitAndFull() throws Exception {
        var r = call("delegate", "{\"instruction\":\"review\",\"template\":\"tpl-agent\"}");
        assertTrue(runScript(1).contains("--permission-mode 'bypassPermissions'"), runScript(1));
        assertFalse(runScript(1).contains("--max-turns"), "no turn limit: " + runScript(1));
        assertEquals("bypassPermissions", structured(r).path("permission_mode").asText());
        assertEquals("bypassPermissions", templateNamed("tpl-agent").path("permission_mode").asText());
    }

    /**
     * An mcp.yaml written for the policies removed in #1857 still loads, and none of them
     * narrows a delegate, refuses an agent, or shows in discovery.
     */
    @Test
    void retiredDelegatePoliciesChangeNothing() throws Exception {
        config = dev.incusspawn.config.Jjq.mcpConfig("""
                templates: [tpl-agent, tpl-plain, tpl-codex, tpl-both, tpl-pi, tpl-claude-pi]
                max-concurrent-tasks: 8
                delegate-max-turns: 10
                delegate-permission-mode: plan
                delegate-permission-modes:
                  tpl-codex: plan
                  tpl-pi: plan
                codex:
                  sandbox-mode: read-only
                  sandbox-modes:
                    tpl-codex: read-only
                """);
        backend.template("tpl-codex", true, "codex");
        backend.template("tpl-pi", true, "pi");
        assertNotNull(config.retiredKeysNotice());

        var claude = structured(call("delegate", "{\"template\":\"tpl-agent\",\"instruction\":\"go\"}"));
        assertEquals("bypassPermissions", claude.path("permission_mode").asText(), claude.toString());
        assertTrue(runScript(1).contains("--permission-mode 'bypassPermissions'") && !runScript(1).contains("--max-turns"),
                runScript(1));
        var codex = structured(call("delegate", "{\"template\":\"tpl-codex\",\"instruction\":\"go\"}"));
        assertEquals("danger-full-access", codex.path("permission_mode").asText(), codex.toString());
        var pi = structured(call("delegate", "{\"template\":\"tpl-pi\",\"instruction\":\"go\"}"));
        assertEquals("full-access", pi.path("permission_mode").asText(), pi.toString());

        var listed = structured(call("list_templates", "{}"));
        assertFalse(((io.hyperfoil.tools.jjq.value.JqObject) listed).keys().contains("delegate_max_turns"));
        for (var name : List.of("tpl-agent", "tpl-codex", "tpl-pi")) {
            var agent = (io.hyperfoil.tools.jjq.value.JqObject) templateNamed(name).path("delegate_agents").getElement(0);
            assertFalse(agent.keys().contains("refusal") || agent.keys().contains("supports_max_turns"), agent.toString());
        }
    }

    /** A caller still passing the removed argument learns that its runs have no limit. */
    @Test
    void maxTurnsIsRefusedAsNoLongerSupported() throws Exception {
        var r = call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"max_turns\":5}");
        assertEquals("invalid_argument", code(r));
        assertTrue(text(r).contains("max_turns is no longer supported"), text(r));
        assertEquals(0, instancesMade(), "refused before an instance was made");
        var task = delegateFresh();
        taskState = "finished";
        var sent = call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"m\",\"max_turns\":null}");
        assertEquals("invalid_argument", code(sent), "named at all, even as null");
        assertEquals(1, tasks.all().get(0).runs());
    }

    @Test
    void aSkillIsRunAsItsSlashCommand() throws Exception {
        var r = call("delegate", "{\"skill\":\"fix-issue\",\"args\":\"#870 --careful\",\"template\":\"tpl-agent\"}");
        assertFalse(r.path("isError").asBoolean(false), text(r));
        var launch = backend.scripts.indexOf(backend.scripts.stream().filter(sc -> sc.contains("systemd-run"))
                .findFirst().orElseThrow());
        assertEquals("/fix-issue #870 --careful", backend.stdins.get(launch), "the prompt, on stdin");

        for (var bad : List.of("{\"skill\":\"x; rm -rf ~\",\"template\":\"tpl-agent\"}",
                "{\"skill\":\"/fix\",\"template\":\"tpl-agent\"}",
                "{\"skill\":\"fix\",\"instruction\":\"x\",\"template\":\"tpl-agent\"}",
                "{\"instruction\":\"x\",\"args\":\"y\",\"template\":\"tpl-agent\"}")) {
            assertTrue(call("delegate", bad).path("isError").asBoolean(false), bad);
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
        assertTrue(r.path("isError").asBoolean(false));
        assertTrue(text(r).contains("a person is in"), text(r));
        assertTrue(backend.scripts.stream().noneMatch(sc -> sc.contains("--unit=isx-task-" + task + "-2")));

        attached = false;
        assertFalse(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"push it\"}")
                .path("isError").asBoolean(false));
    }

    @Test
    void taskStatusGivesTheCommandThatJoinsTheConversation() throws Exception {
        var task = delegateFresh();
        taskState = "running";
        var running = structured(call("task_status", "{\"task_id\":\"" + task + "\"}"));
        assertFalse(((io.hyperfoil.tools.jjq.value.JqObject) running).keys().contains("join_command"), "nothing saved to join yet");
        taskState = "finished";
        attached = true;
        var result = call("task_status", "{\"task_id\":\"" + task + "\"}");
        var expected = TaskJoin.hostCommand(structured(result).path("instance").asText(), task);
        assertEquals("isx shell " + structured(result).path("instance").asText() + " --task " + task, expected);
        assertEquals(expected, structured(result).path("join_command").asText());
        assertTrue(text(result).contains("\njoin_command: " + expected + "\n"), text(result));
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
        assertTrue(call("wait_any", "{\"task_ids\":[\"t9-nope\"]}").path("isError").asBoolean(false));
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

    /** A turn count that is not an int is left out, not wrapped (Jackson's canConvertToInt). */
    @Test
    void aTurnCountBeyondTheIntRangeIsLeftOut() throws Exception {
        var task = delegateFresh();
        taskState = "finished";
        finishedEvents = """
                {"type":"result","subtype":"success","is_error":false,"result":"done","num_turns":99999999999,"total_cost_usd":0.1}
                """;
        var result = structured(call("task_result", "{\"task_id\":\"" + task + "\"}"));
        assertFalse(((io.hyperfoil.tools.jjq.value.JqObject) result).keys().contains("turns"), result.toJsonString());
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
        assertTrue(call("get_diff", "{\"task_id\":\"" + task + "\",\"ask\":\"x\",\"stat\":true}").path("isError").asBoolean(false));
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
        assertTrue(call("exec", "{\"instance\":\"mcp-agent-870-impl-abcde\",\"command\":\"ls\"}").path("isError").asBoolean(false),
                "not held until adopted");

        taskListing = "t3-old agent 2 done claude-haiku-4-5 - /home/agentuser/repo dir\nnot a task line\n";
        var adopted = text(call("adopt_instance", "{\"instance\":\"mcp-agent-870-impl-abcde\"}"));
        assertTrue(adopted.contains("\"t3-old\""), adopted);
        taskState = "finished";
        assertTrue(text(call("task_result", "{\"task_id\":\"t3-old\"}")).contains("Fixed the race"));
        assertFalse(call("send_message", "{\"task_id\":\"t3-old\",\"message\":\"rebase\"}").path("isError").asBoolean(false));
        var run = runScript(3);
        assertTrue(run.contains("cd -- '/home/agentuser/repo dir'"), "resumed where it worked: " + run);
        assertTrue(run.contains("--resume"), run);
        assertTrue(run.contains("--model 'claude-haiku-4-5'"), "an adopted task keeps its profile: " + run);
        assertEquals(0, modelChecks(), "a turn naming no model runs on the recorded one");
        assertFalse(call("send_message", "{\"task_id\":\"t3-old\",\"message\":\"m\",\"model\":\"claude-haiku-4-5\"}")
                .path("isError").asBoolean(false));
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
        var listed = structured(call("list_instances", "{}")).path("instances").getElement(0);
        assertEquals("orphaned", listed.path("state").asText(), "still an orphan: a dormant one is one too");
        assertEquals("2026-09-30T10:00:00Z", listed.path("dormant_since").asText());
        assertEquals("2026-10-07T10:00:00Z", listed.path("dormant_until").asText());

        taskListing = "t3-old agent 1 running - - /home/agentuser/repo\n";
        taskState = "lost";
        var adopted = structured(call("adopt_instance", "{\"instance\":\"" + name + "\"}"));
        assertFalse(backend.stopped.contains(name), "started again");
        assertFalse(adopted.has("warning"), "its tasks were read: " + adopted);
        assertEquals("t3-old", adopted.path("tasks").getElement(0).asText());
        assertFalse(backend.instances.get(name).containsKey(dev.incusspawn.incus.Metadata.MCP_DORMANT));

        var sent = call("send_message", "{\"task_id\":\"t3-old\",\"message\":\"carry on\"}");
        assertFalse(sent.path("isError").asBoolean(false), text(sent));
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
        assertTrue(call("exec", bad).path("isError").asBoolean(false));
        assertTrue(call("exec", bad).path("isError").asBoolean(false));
        var r = call("exec", "{\"instance\":\"" + instance + "\",\"command\":\"true\",\"background\":true}");
        assertFalse(r.path("isError").asBoolean(false), text(r));
    }

    @Test
    void tasksOfAnInstanceAnotherSessionAdoptedStopCounting() throws Exception {
        config.setMaxConcurrentTasks(1);
        var task = delegateFresh();
        var instance = instanceOf(task);
        backend.stamp(instance, dev.incusspawn.incus.Metadata.MCP_SESSION, "8-8"); // forced adoption
        var r = call("delegate", "{\"instruction\":\"next\",\"template\":\"tpl-agent\"}");
        assertFalse(r.path("isError").asBoolean(false), text(r));
        assertTrue(call("task_status", "{\"task_id\":\"" + task + "\"}").path("isError").asBoolean(false));
    }

    @Test
    void aMessageToATaskForgottenMeanwhileIsRefusedNotACrash() throws Exception {
        var id = delegateFresh();
        var task = tasks.require(id).task();
        // A concurrent destroy_instance forgets the task between send_message's check and its reservation.
        tasks.forgetInstance(task.instance());
        var e = assertThrows(ToolError.class, () -> tasks.sendMessage(task, "push it", Tasks.Profile.NONE, null));
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
        assertFalse(r.path("isError").asBoolean(false), text(r));
    }

    private long modelChecks() {
        return backend.scripts.stream().filter(s -> s.contains(ModelCheck.MARKER)).count();
    }

    private long instancesMade() {
        return backend.instances.keySet().stream().filter(n -> n.startsWith("mcp-")).count();
    }

    @Test
    void aTaskRunsUnderItsOwnModelAndLaterTurnsKeepIt() throws Exception {
        var r = call("delegate", "{\"instruction\":\"rebase\",\"template\":\"tpl-agent\","
                + "\"model\":\"claude-haiku-4-5\"}");
        assertFalse(r.path("isError").asBoolean(false), text(r));
        var task = r.path("structuredContent").path("task_id").asText();
        assertTrue(runScript(1).contains(" --model 'claude-haiku-4-5'"), runScript(1));
        assertTrue(text(call("task_status", "{\"task_id\":\"" + task + "\"}")).contains("model: claude-haiku-4-5"));

        taskState = "finished";
        assertFalse(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"again\"}").path("isError").asBoolean(false));
        assertTrue(runScript(2).contains(" --model 'claude-haiku-4-5'"), "a later turn keeps the task's profile: " + runScript(2));
        assertEquals(1, modelChecks(), "a model checked once is not checked again");
    }

    @Test
    void withoutAProfileTheTemplatesModelApplies() throws Exception {
        delegateFresh();
        assertFalse(runScript(1).contains("--model"), runScript(1));
        assertEquals(0, modelChecks(), "nothing to check");
    }

    @Test
    void aModelTheAccountCannotUseFailsTheCallAndLeavesNothingBehind() throws Exception {
        modelRefusal = "API Error: 404 model: claude-opus-9 not found";
        var r = call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"model\":\"claude-opus-9\"}");
        assertTrue(r.path("isError").asBoolean(false));
        assertTrue(text(r).contains("claude-opus-9 not found"), text(r));
        assertTrue(backend.scripts.stream().noneMatch(s -> s.contains("systemd-run")), "no task started");
        assertEquals(0, instancesMade(), "the instance made for it is gone");
        assertTrue(tasks.all().isEmpty());

        // A failure is not remembered: the account may be fixed meanwhile.
        modelRefusal = null;
        assertFalse(call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"model\":\"claude-opus-9\"}")
                .path("isError").asBoolean(false));
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
        assertFalse(r.path("isError").asBoolean(false), text(r));
        assertEquals(3, modelChecks());
    }

    @Test
    void aModelThatIsNotAModelNameIsRefusedUnrun() throws Exception {
        var r = call("delegate", "{\"instruction\":\"x\",\"template\":\"tpl-agent\",\"model\":\"haiku --dangerously-skip-permissions\"}");
        assertTrue(r.path("isError").asBoolean(false));
        assertTrue(text(r).contains("model"), text(r));
        assertTrue(backend.scripts.isEmpty(), "nothing ran");
        assertEquals(0, instancesMade());
    }

    @Test
    void listTemplatesSaysWhatAProfileOverrides() throws Exception {
        backend.delegateModel("tpl-agent", "claude-opus-5-5");
        var listed = call("list_templates", "{}").path("structuredContent");
        var agent = listed.path("templates").getElement(0);
        assertEquals("tpl-agent", agent.path("template").asText());
        assertEquals("claude-opus-5-5", agent.path("delegate_model").asText());
        assertTrue(listed.path("templates").getElement(1).path("delegate_model").isMissingNode(), listed.toString());
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
                .path("isError").asBoolean(false));
        assertEquals(1, tasks.all().get(0).runs());
        assertEquals(null, tasks.all().get(0).profile().model());
        modelRefusal = null;
        assertFalse(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"m\"}").path("isError").asBoolean(false));
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
                .path("isError").asBoolean(false));
        assertEquals(1, modelChecks());
    }

    // --- idempotency keys (#1011) ---

    private static String code(io.hyperfoil.tools.jjq.value.JqValue result) {
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
        assertTrue(launch.contains("printf '%s' 't-1' > \"$w\" && mv -fT \"$w\" \"$D/key\""), "recorded with the task: " + launch);
        taskState = "finished";
        var again = structured(call("delegate", args));
        assertEquals(first.path("task_id").asText(), again.path("task_id").asText());
        assertTrue(again.path("replayed").asBoolean(false));
        assertEquals(1, again.path("run").asInt(0));
        assertEquals(first.path("permission_mode").asText(), again.path("permission_mode").asText());
        assertEquals(1, launches());

        var cmd = "{\"instance\":\"" + instance + "\",\"command\":\"make test\",\"background\":true,\"idempotency_key\":\"c-1\"}";
        var started = structured(call("exec", cmd));
        var repeated = structured(call("exec", cmd));
        assertEquals(started.path("task_id").asText(), repeated.path("task_id").asText());
        assertTrue(repeated.path("replayed").asBoolean(false));
        assertTrue(repeated.path("background").asBoolean(false));
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
        while (out.sent.stream().noneMatch(m -> m.path("id").asInt(0) == 302) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        var second = out.byId(302).path("result");
        assertEquals("busy", code(second), second.toString());
        assertTrue(text(second).contains("call again"), text(second));
        launchRelease.countDown();
        server.awaitIdle();
        var first = out.byId(301).path("result");
        assertFalse(first.path("isError").asBoolean(false), first.toString());
        launchEntered = null;
        var third = structured(call("delegate", args));
        assertEquals(first.path("structuredContent").path("task_id").asText(), third.path("task_id").asText());
        assertTrue(third.path("replayed").asBoolean(false));
        assertEquals(1, launches());
    }

    @Test
    void aReplayIsNotRefusedByTheTaskLimitItAlreadyCountsIn() throws Exception {
        config.setMaxConcurrentTasks(1);
        var instance = instanceFor("cap");
        var args = "{\"instruction\":\"x\",\"instance\":\"" + instance + "\",\"idempotency_key\":\"k\"}";
        structured(call("delegate", args));
        assertTrue(structured(call("delegate", args)).path("replayed").asBoolean(false));
    }

    @Test
    void anAdoptedInstancesTaskKeysStillReplayThere() throws Exception {
        backend.instance("mcp-agent-870-impl-abcde", Map.of(
                dev.incusspawn.incus.Metadata.PROFILE, "tpl-agent",
                dev.incusspawn.incus.Metadata.PARENT, "tpl-agent",
                dev.incusspawn.incus.Metadata.MCP_SESSION, "9-9",
                dev.incusspawn.incus.Metadata.MCP_OWNER, "alice"));
        taskListing = "t3-old agent 1 done - =issue-870 /home/agentuser\n"
                + "t4-old command 1 done - =$(reboot) /home/agentuser\n"
                + "t5-old command 1 done - - /home/agentuser\n";
        structured(call("adopt_instance", "{\"instance\":\"mcp-agent-870-impl-abcde\"}"));
        taskState = "finished";
        var r = structured(call("delegate", "{\"instruction\":\"x\",\"instance\":\"mcp-agent-870-impl-abcde\","
                + "\"idempotency_key\":\"issue-870\"}"));
        assertEquals("t3-old", r.path("task_id").asText());
        assertTrue(r.path("replayed").asBoolean(false));
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
        assertTrue(again.path("replayed").asBoolean(false));
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
        assertFalse(r.path("isError").asBoolean(false), "not refused as a mismatch with a task this session let go: " + text(r));
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
            while (out.sent.stream().noneMatch(m -> m.path("id").asInt(0) == 401) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        };
        var first = call("delegate", args);
        server.awaitIdle();
        var repeat = out.byId(401).path("result");
        assertFalse(repeat.path("isError").asBoolean(false), repeat.toString());
        var instance = repeat.path("structuredContent").path("instance").asText();
        assertEquals("busy", code(first), first.toString());
        assertEquals(List.of(), backend.destroyed, "the instance its repeat started the task in stays");
        assertEquals(java.util.Set.of(instance), backend.mcpInstances().keySet());
        assertEquals(1, launches());
        assertTrue(structured(call("delegate", args)).path("replayed").asBoolean(false));
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
        taskListing = "t3-old agent 1 done - =issue-1011 /home/agentuser\n";
        taskState = "finished";
        var again = structured(call("delegate", args));
        assertEquals("t3-old", again.path("task_id").asText());
        assertTrue(again.path("replayed").asBoolean(false));
        assertEquals(0, launches());
    }

    private void addCodexTemplates() {
        backend.template("tpl-codex", true, "codex").template("tpl-both", true, "claude", "codex");
        config.setTemplates(List.of("tpl-agent", "tpl-plain", "tpl-codex", "tpl-both"));
    }

    @Test
    void discoveryAndSelectionIncludeCodexAndPreserveClaudeDefaults() throws Exception {
        addCodexTemplates();
        var templates = structured(call("list_templates", "{}")).path("templates");
        var codex = templates.asList().stream()
                .filter(t -> t.path("template").asText().equals("tpl-codex")).findFirst().orElseThrow();
        assertEquals("codex", codex.path("default_delegate_agent").asText());
        assertFalse(codex.path("supports_ask").asBoolean(false));
        assertEquals(1, codex.path("delegate_agents").size());
        assertEquals("danger-full-access", codex.path("delegate_agents").getElement(0).path("permission_mode").asText());
        assertEquals("claude", structured(call("delegate", "{\"template\":\"tpl-both\",\"instruction\":\"go\"}")).path("agent").asText());
        assertEquals("claude", structured(call("delegate", "{\"template\":\"tpl-both\",\"agent\":\"claude\",\"instruction\":\"go\"}")).path("agent").asText());
        var task = structured(call("delegate", "{\"template\":\"tpl-codex\",\"instruction\":\"go\"}"));
        assertEquals("codex", task.path("agent").asText());
        assertEquals("never", task.path("approval_policy").asText());
        assertFalse(((io.hyperfoil.tools.jjq.value.JqObject) task).keys().contains("max_turns"));
        assertEquals("codex", structured(call("delegate", "{\"template\":\"tpl-both\",\"agent\":\"codex\",\"instruction\":\"go\"}")).path("agent").asText());
        assertEquals("invalid_argument", code(call("delegate", "{\"template\":\"tpl-codex\",\"agent\":\"claude\",\"instruction\":\"go\"}")));

        // A blank skill is absent, as it is for Claude.
        assertEquals("codex", structured(call("delegate", "{\"template\":\"tpl-codex\",\"instruction\":\"go\",\"skill\":\"\"}")).path("agent").asText());
    }

    @Test
    void askFailsBeforeItsProducerWhereOnlyCodexRuns() throws Exception {
        addCodexTemplates();
        var task = structured(call("delegate", "{\"template\":\"tpl-codex\",\"instruction\":\"go\"}"));
        assertEquals("danger-full-access", task.path("permission_mode").asText());
        var calls = backend.scripts.size();
        assertEquals("invalid_argument", code(call("exec", "{\"instance\":\"" + task.path("instance").asText()
                + "\",\"command\":\"touch side-effect\",\"ask\":\"what happened\"}")));
        assertEquals(calls, backend.scripts.size());
    }

    @Test
    void askIsLeftToTheInstanceWhenTheTemplateDefinitionIsGone() throws Exception {
        addCodexTemplates();
        var task = structured(call("delegate", "{\"template\":\"tpl-both\",\"instruction\":\"go\"}"));
        backend.templates.removeIf(t -> t.name().equals("tpl-both"));
        var result = call("exec", "{\"instance\":\"" + task.path("instance").asText()
                + "\",\"command\":\"true\",\"ask\":\"what happened\"}");
        assertNotEquals("invalid_argument", code(result), "a missing definition proves nothing about Claude");
    }

    @Test
    void codexSkillIsCheckedForReadabilityNotOpenedReadWrite() throws Exception {
        addCodexTemplates();
        structured(call("delegate", "{\"template\":\"tpl-codex\",\"instruction\":\"\",\"skill\":\"review\"}"));
        var check = TaskScripts.isReadableFile("$HOME/.agents/skills/review/SKILL.md");
        assertTrue(backend.scripts.contains(check), backend.scripts.toString());
    }

    @Test
    void codexResultsContinuationAndIdempotencyUseItsRecordedAgent() throws Exception {
        addCodexTemplates();
        var args = "{\"template\":\"tpl-both\",\"agent\":\"codex\",\"instruction\":\"go\",\"idempotency_key\":\"codex-key\"}";
        var task = structured(call("delegate", args));
        var id = task.path("task_id").asText();
        assertTrue(structured(call("delegate", args.replace(",\"agent\":\"codex\"", ""))).path("replayed").asBoolean(false));
        assertEquals("invalid_argument", code(call("delegate", args.replace("\"agent\":\"codex\"", "\"agent\":\"claude\""))));
        taskState = "finished";
        savedSession = "codex-thread-123";
        finishedEvents = "{\"type\":\"isx.codex.result\",\"success\":true,\"report_bytes\":24,\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}\n";
        var status = structured(call("task_status", "{\"task_id\":\"" + id + "\"}"));
        assertEquals(savedSession, status.path("session_id").asText());
        assertTrue(status.path("resumable").asBoolean(false));
        var report = structured(call("task_result", "{\"task_id\":\"" + id + "\"}"));
        assertEquals("Codex completed the task", report.path("report").asText());
        assertFalse(report.path("is_error").asBoolean(false));
        assertFalse(((io.hyperfoil.tools.jjq.value.JqObject) report).keys().contains("cost_usd"));
        assertEquals(10, report.path("input_tokens").asInt(0));
        var continued = structured(call("send_message", "{\"task_id\":\"" + id + "\",\"message\":\"continue\"}"));
        assertEquals("codex", continued.path("agent").asText());
        assertEquals(2, continued.path("run").asInt(0));
    }

    @Test
    void codexAdoptionAndInterruptedCreationRetainSelection() throws Exception {
        addCodexTemplates();
        backend.instance("mcp-both-task-abcde", Map.of(
                dev.incusspawn.incus.Metadata.PROFILE, "tpl-both",
                dev.incusspawn.incus.Metadata.PARENT, "tpl-both",
                dev.incusspawn.incus.Metadata.STATIC_IP, "10.0.0.7",
                dev.incusspawn.incus.Metadata.MCP_SESSION, "9-9",
                dev.incusspawn.incus.Metadata.MCP_OWNER, "alice",
                dev.incusspawn.incus.Metadata.MCP_IDEMPOTENCY_KEY, "codex-key",
                dev.incusspawn.incus.Metadata.MCP_DELEGATE_AGENT, "codex"));
        var args = "{\"template\":\"tpl-both\",\"instruction\":\"go\",\"idempotency_key\":\"codex-key\"}";
        assertEquals("codex", structured(call("delegate", args)).path("agent").asText());
        assertEquals(1, backend.mcpInstances().size());
        // Versioned records reconstruct the adapter; old versionless rows remain Claude.
        taskListing = "identity t7-old 1 codex\nt7-old agent 1 done - =old-key /work\nt8-old agent 1 done - - /other\n";
        tasks.adopt("mcp-both-task-abcde");
        assertEquals(DelegateAgent.CODEX, tasks.all().stream().filter(t -> t.id().equals("t7-old")).findFirst().orElseThrow().agent());
        assertEquals(DelegateAgent.CLAUDE, tasks.all().stream().filter(t -> t.id().equals("t8-old")).findFirst().orElseThrow().agent());
    }

    private void addPiTemplates() {
        backend.template("tpl-pi", true, "pi").template("tpl-claude-pi", true, "claude", "pi")
                .template("tpl-codex-pi", true, "codex", "pi");
        config.setTemplates(List.of("tpl-agent", "tpl-plain", "tpl-pi", "tpl-claude-pi", "tpl-codex-pi"));
    }

    private JqValue templateNamed(String name) throws Exception {
        return structured(call("list_templates", "{}")).path("templates").asList().stream()
                .filter(t -> t.path("template").asText().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aPiOnlyTemplateDelegatesToPi() throws Exception {
        addPiTemplates();
        var pi = templateNamed("tpl-pi");
        assertTrue(pi.path("supports_delegate").asBoolean(false), pi.toString());
        assertEquals("pi", pi.path("default_delegate_agent").asText());
        assertFalse(pi.path("supports_ask").asBoolean(true));
        assertEquals(1, pi.path("delegate_agents").size());
        var descriptor = pi.path("delegate_agents").getElement(0);
        assertEquals("pi", descriptor.path("agent").asText());
        assertFalse(((io.hyperfoil.tools.jjq.value.JqObject) descriptor).keys().contains("supports_max_turns"));
        assertFalse(descriptor.path("supports_model_preflight").asBoolean(true));
        assertEquals("full-access", descriptor.path("permission_mode").asText());
        // Claude stays the default where it is installed, Codex keeps its place before Pi.
        assertEquals("claude", templateNamed("tpl-claude-pi").path("default_delegate_agent").asText());
        assertEquals("codex", templateNamed("tpl-codex-pi").path("default_delegate_agent").asText());

        var task = structured(call("delegate", "{\"template\":\"tpl-pi\",\"instruction\":\"go\"}"));
        assertEquals("pi", task.path("agent").asText());
        assertEquals("full-access", task.path("permission_mode").asText());
        assertFalse(((io.hyperfoil.tools.jjq.value.JqObject) task).keys().contains("approval_policy"));
        assertFalse(((io.hyperfoil.tools.jjq.value.JqObject) task).keys().contains("max_turns"));
        assertTrue(backend.scripts.stream().anyMatch(s -> s.contains("command -v pi")), "the CLI is probed before launch");
        assertEquals("pi", structured(call("delegate", "{\"template\":\"tpl-claude-pi\",\"agent\":\"pi\",\"instruction\":\"go\"}")).path("agent").asText());
        assertEquals("invalid_argument", code(call("delegate", "{\"template\":\"tpl-pi\",\"agent\":\"claude\",\"instruction\":\"go\"}")));
    }

    @Test
    void piResultsAndContinuationUseItsSavedSession() throws Exception {
        addPiTemplates();
        var task = structured(call("delegate", "{\"template\":\"tpl-pi\",\"instruction\":\"go\"}"));
        var id = task.path("task_id").asText();
        taskState = "finished";
        savedAgent = "pi";
        savedSession = "0b5c3a52-5e1f-4c8e-9a51-2f4c1e7d9a10";
        finishedEvents = "{\"type\":\"isx.pi.result\",\"success\":true,\"report_bytes\":24,\"turns\":3,\"cost_usd\":0.25,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"cached_input_tokens\":5,\"cache_creation_input_tokens\":9}}\n";
        var status = structured(call("task_status", "{\"task_id\":\"" + id + "\"}"));
        assertEquals(savedSession, status.path("session_id").asText());
        assertTrue(status.path("resumable").asBoolean(false));
        assertTrue(status.path("join_command").asText().contains("--task " + id), status.toString());
        var report = structured(call("task_result", "{\"task_id\":\"" + id + "\"}"));
        assertEquals("success", report.path("outcome").asText());
        assertFalse(report.path("is_error").asBoolean(true));
        assertEquals(3, report.path("turns").asInt(0));
        assertEquals(0.25, report.path("cost_usd").asDouble(0), 1e-9);
        assertEquals(5, report.path("cached_input_tokens").asInt(0));
        assertEquals(9, report.path("cache_creation_input_tokens").asInt(0));
        var continued = structured(call("send_message", "{\"task_id\":\"" + id + "\",\"message\":\"continue\"}"));
        assertEquals("pi", continued.path("agent").asText());
        assertEquals(2, continued.path("run").asInt(0));

        // The session file is gone: refused before a run is reserved, so Pi never writes the message anywhere.
        piSession = "its Pi session file is gone, and Pi would open an empty conversation in its place";
        var launched = launches();
        var gone = call("send_message", "{\"task_id\":\"" + id + "\",\"message\":\"again\"}");
        assertEquals("wrong_state", code(gone));
        assertTrue(text(gone).contains("its Pi session file is gone"), text(gone));
        assertEquals(launched, launches());
        piSession = "ok";

        // A first run that never opened its session saved no ID: nothing to continue, and never a new conversation.
        savedSession = null;
        claudeSession = null;
        assertFalse(structured(call("task_status", "{\"task_id\":\"" + id + "\"}")).path("resumable").asBoolean(true));
        assertEquals("wrong_state", code(call("send_message", "{\"task_id\":\"" + id + "\",\"message\":\"again\"}")));
    }

    @Test
    void piReplayAdoptionAndInterruptedCreationKeepTheirAgent() throws Exception {
        addPiTemplates();
        var args = "{\"template\":\"tpl-claude-pi\",\"agent\":\"pi\",\"instruction\":\"go\",\"idempotency_key\":\"pi-key\"}";
        var task = structured(call("delegate", args));
        assertEquals("pi", task.path("agent").asText());
        var replayed = structured(call("delegate", args.replace(",\"agent\":\"pi\"", "")));
        assertTrue(replayed.path("replayed").asBoolean(false), "an omitted agent means the recorded one");
        assertEquals("pi", replayed.path("agent").asText());
        assertEquals("invalid_argument", code(call("delegate", args.replace("\"agent\":\"pi\"", "\"agent\":\"claude\""))));

        // Branched under a key, never started: the replay finishes Pi, not the template's default Claude.
        backend.instance("mcp-claude-pi-task-abcde", Map.of(
                dev.incusspawn.incus.Metadata.PROFILE, "tpl-claude-pi",
                dev.incusspawn.incus.Metadata.PARENT, "tpl-claude-pi",
                dev.incusspawn.incus.Metadata.STATIC_IP, "10.0.0.8",
                dev.incusspawn.incus.Metadata.MCP_SESSION, "9-9",
                dev.incusspawn.incus.Metadata.MCP_OWNER, "alice",
                dev.incusspawn.incus.Metadata.MCP_IDEMPOTENCY_KEY, "pi-made",
                dev.incusspawn.incus.Metadata.MCP_DELEGATE_AGENT, "pi"));
        assertEquals("pi", structured(call("delegate",
                "{\"template\":\"tpl-claude-pi\",\"instruction\":\"go\",\"idempotency_key\":\"pi-made\"}")).path("agent").asText());

        // An adopting session rebuilds a Pi task from its identity record.
        taskListing = "identity t9-old 1 pi\nt9-old agent 1 done - - /work\n";
        tasks.adopt("mcp-claude-pi-task-abcde");
        assertEquals(DelegateAgent.PI, tasks.all().stream().filter(t -> t.id().equals("t9-old")).findFirst().orElseThrow().agent());

        // One a newer isx recorded is never continued as a guess, now that no policy refuses agents.
        taskListing = "identity t10-new 2 pi\nt10-new agent 1 done - - /work\n";
        tasks.adopt("mcp-claude-pi-task-abcde");
        taskState = "finished";
        var sent = call("send_message", "{\"task_id\":\"t10-new\",\"message\":\"go on\"}");
        assertEquals("refused", code(sent), text(sent));
        assertTrue(text(sent).contains("upgrade isx"), text(sent));
    }
}
