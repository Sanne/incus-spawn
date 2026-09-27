package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.config.McpConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static final String EVENTS = """
            {"type":"system","subtype":"init","session_id":"s1"}
            {"type":"assistant","message":{"content":[{"type":"text","text":"Found the race"},{"type":"tool_use","name":"Edit","input":{"file_path":"src/A.java"}}]}}
            {"type":"result","subtype":"success","is_error":false,"result":"Fixed the race in A.java; tests pass.","num_turns":7,"total_cost_usd":0.42}
            """;

    @BeforeEach
    void setUp() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        config.setTemplates(List.of("tpl-agent", "tpl-plain"));
        var session = new McpSession(new SessionId(7, 1), "alice", 1, "/work", backend, () -> config);
        var tasks = new Tasks(session, backend, () -> config);
        server = new McpServer(out, new McpTools(session, backend, new TemplatePolicy(backend, () -> config),
                tasks).all(), "1", null, null);
        backend.responder = script -> {
            if (script.startsWith("n=$(cat")) { // the state probe: one line per task asked about
                var ids = java.util.regex.Pattern.compile("echo (t[0-9a-z-]+) \\$s").matcher(script).results()
                        .map(m -> m.group(1) + switch (taskState) {
                            case "running" -> " running";
                            case "unknown" -> " unknown";
                            default -> " done";
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
                if (failLaunch) throw new ToolError("launch failed");
                return "";
            }
            if (!script.startsWith("D=") || !script.contains("echo run=")) return ""; // cancel
            if (taskState.equals("unknown")) return "run=1\nkind=agent\nunit=\n---\n\n---stderr\n";
            return taskState.equals("running")
                    ? "run=1\nkind=agent\nunit=active\nevents_bytes=10\n---\n" + EVENTS.lines().limit(2)
                            .reduce("", (a, b) -> a + b + "\n") + "\n---stderr\n"
                    : "run=1\nkind=agent\nunit=inactive\nexit=0\nevents_bytes=400\n---\n" + EVENTS + "\n---stderr\n";
        };
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
        return out.byId(id).path("result");
    }

    private static String text(JsonNode r) {
        return r.path("content").get(0).path("text").asText();
    }

    private String delegateFresh() throws Exception {
        var r = call("delegate", "{\"instruction\":\"fix the flaky test\",\"template\":\"tpl-agent\"}");
        assertFalse(r.path("isError").asBoolean(), text(r));
        return JsonRpc.JSON.readTree(text(r)).path("task_id").asText();
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
    void sendMessageWaitsForTheAgentToFinish() throws Exception {
        var task = delegateFresh();
        assertTrue(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"open a PR\"}")
                .path("isError").asBoolean());
        taskState = "finished";
        var r = call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"open a PR\"}");
        assertFalse(r.path("isError").asBoolean(), text(r));
        assertTrue(backend.scripts.stream().anyMatch(s -> s.contains("--unit=isx-task-" + task + "-2")));
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
        var instance = JsonRpc.JSON.readTree(text(call("create_instance", "{\"template\":\"tpl-agent\"}")))
                .path("instance").asText();
        var r = call("delegate", "{\"instruction\":\"x\",\"instance\":\"" + instance
                + "\",\"cwd\":\"/home/agentuser/other-repo\"}");
        var task = JsonRpc.JSON.readTree(text(r)).path("task_id").asText();
        taskState = "finished";
        assertFalse(call("send_message", "{\"task_id\":\"" + task + "\",\"message\":\"more\"}")
                .path("isError").asBoolean());
        assertTrue(runScript(2).contains("cd -- '/home/agentuser/other-repo'"), runScript(2));
        assertTrue(runScript(2).contains("--resume"), runScript(2));
    }

    @Test
    void concurrentDelegationsToOneInstanceCannotBothStart() throws Exception {
        var instance = JsonRpc.JSON.readTree(text(call("create_instance", "{\"template\":\"tpl-agent\"}")))
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
        var listed = JsonRpc.JSON.readTree(text(call("list_instances", "{}")));
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
        assertTrue(text(call("list_instances", "{}")).contains("\"running\" : true"));
    }

    @Test
    void aStateNobodyCouldReadKeepsTheTaskCounted() throws Exception {
        var instance = JsonRpc.JSON.readTree(text(call("create_instance", "{\"template\":\"tpl-agent\"}")))
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

    private String instanceTask(String instance) throws Exception {
        var listed = JsonRpc.JSON.readTree(text(call("list_instances", "{}")));
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
}
