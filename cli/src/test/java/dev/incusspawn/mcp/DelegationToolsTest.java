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
            if (!script.startsWith("D=") || !script.contains("echo run=")) return ""; // launch, cancel
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

    @Test
    void anotherSessionsTaskIdIsUnknown() throws Exception {
        assertTrue(call("task_status", "{\"task_id\":\"t1-zz\"}").path("isError").asBoolean());
        assertTrue(call("cancel_task", "{\"task_id\":\"t1-zz\"}").path("isError").asBoolean());
    }
}
