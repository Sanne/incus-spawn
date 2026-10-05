package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.Environment;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tools end to end over the protocol, against a {@link FakeBackend}. */
class McpToolsTest {

    @TempDir
    Path home;
    private String realHome;

    private final FakeBackend backend = new FakeBackend()
            .template("tpl-java", true, "maven-3", "claude")
            .template("tpl-secret", true);
    private final McpConfig config = new McpConfig();
    private final McpServerProtocolTest.Captured out = new McpServerProtocolTest.Captured();
    private McpSession session;
    private McpServer server;
    private int nextId = 1;

    @BeforeEach
    void setUp() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString()); // the audit log goes here
        config.setTemplates(List.of("tpl-java"));
        session = new McpSession(new SessionId(4242, 1), "alice", 1, "/work", backend, () -> config, s -> false);
        var tools = new McpTools(session, backend, new TemplatePolicy(backend, () -> config),
                new Tasks(session, backend, () -> config));
        server = new McpServer(out, tools.all(), "1", null, null);
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

    private static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asText();
    }

    private String createJava() throws Exception {
        var result = call("create_instance", "{\"template\":\"tpl-java\"}");
        assertFalse(result.path("isError").asBoolean(), text(result));
        return JsonRpc.JSON.readTree(text(result)).path("instance").asText();
    }

    @Test
    void onlyApprovedTemplatesAreListed() throws Exception {
        var listed = JsonRpc.JSON.readTree(text(call("list_templates", "{}"))).path("templates");
        assertEquals(1, listed.size());
        assertEquals("tpl-java", listed.get(0).path("name").asText());
        assertTrue(listed.get(0).path("supports_delegate").asBoolean());
    }

    @Test
    void theCeilingsAreListedWithTheTemplates() throws Exception {
        config.setMaxInstances(12);
        config.setMaxConcurrentTasks(10);
        var listed = JsonRpc.JSON.readTree(text(call("list_templates", "{}")));
        assertEquals(12, listed.path("instance_limit").asInt());
        assertEquals(10, listed.path("task_limit").asInt());
    }

    @Test
    void withNothingApprovedTheAgentIsToldWhoCanApprove() throws Exception {
        config.setTemplates(List.of());
        assertTrue(text(call("list_templates", "{}")).contains("mcp.templates"));
    }

    @Test
    void creatingStampsTheInstanceAsThisSessions() throws Exception {
        var name = createJava();
        assertTrue(name.startsWith("mcp-java-"));
        assertEquals("4242-1", backend.instances.get(name).get(Metadata.MCP_SESSION));
        assertTrue(Files.readString(Environment.mcpLogFile()).contains("tool=create_instance"));
    }

    @Test
    void anUnapprovedTemplateIsRefused() throws Exception {
        var result = call("create_instance", "{\"template\":\"tpl-secret\"}");
        assertTrue(result.path("isError").asBoolean());
        assertEquals(List.of(), session.instances());
    }

    @Test
    void aFailedCreateLeavesNoReservationBehind() throws Exception {
        config.setMaxInstances(1);
        backend.createFailure = new ToolError("proxy is down");
        assertTrue(call("create_instance", "{\"template\":\"tpl-java\"}").path("isError").asBoolean());
        backend.createFailure = null;
        createJava();
    }

    @Test
    void execRunsInTheInstanceAndReportsTheOutcome() throws Exception {
        var name = createJava();
        backend.execStdout = "BUILD SUCCESS\n";
        backend.execExit = 0;
        var result = text(call("exec", "{\"instance\":\"" + name + "\",\"command\":\"mvn verify\","
                + "\"env\":{\"CI\":\"1\"}}"));
        assertTrue(result.startsWith("exit_code: 0\n"), result);
        assertTrue(result.contains("--- stdout ---\nBUILD SUCCESS\n"), result);
        assertTrue(result.contains("--- stderr: (empty) ---"), result);
        var script = backend.scripts.getLast();
        assertTrue(script.contains("cd -- '/home/agentuser'"), script);
        assertTrue(script.contains("export CI='1'"), script);
        assertTrue(script.contains("bash -c 'mvn verify'"), script);
        assertFalse(script.contains("timeout"), "no limit unless the agent sets one");
    }

    @Test
    void aTimeoutTheAgentSetIsReportedAsSuch() throws Exception {
        var name = createJava();
        backend.execExit = 124;
        var result = text(call("exec", "{\"instance\":\"" + name + "\",\"command\":\"sleep 9\","
                + "\"timeout_seconds\":5}"));
        assertTrue(result.startsWith("exit_code: 124 (killed: timeout of 5s reached)"), result);
    }

    @Test
    void execRefusesAnInstanceItDoesNotOwn() throws Exception {
        backend.instance("users-box", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE));
        var result = call("exec", "{\"instance\":\"users-box\",\"command\":\"rm -rf ~\"}");
        assertTrue(result.path("isError").asBoolean());
        assertEquals(List.of(), backend.scripts);
    }

    @Test
    void longOutputIsTruncatedToTheEnd() throws Exception {
        var name = createJava();
        backend.execStdout = "x".repeat(10_000) + "THE END\n";
        var result = text(call("exec", "{\"instance\":\"" + name + "\",\"command\":\"make\","
                + "\"max_output_bytes\":1000}"));
        assertTrue(result.contains("--- stdout (last 1000 of 10008 bytes) ---"), result);
        assertTrue(result.contains("THE END"), result);
    }

    @Test
    void destroyAndKeepActOnlyOnOwnInstances() throws Exception {
        var a = createJava();
        var b = createJava();
        assertFalse(call("keep_instance", "{\"instance\":\"" + a + "\"}").path("isError").asBoolean());
        assertFalse(call("destroy_instance", "{\"instance\":\"" + b + "\"}").path("isError").asBoolean());
        assertEquals(List.of(b), backend.destroyed);
        assertTrue(call("destroy_instance", "{\"instance\":\"tpl-java\"}").path("isError").asBoolean());
        assertTrue(backend.instances.containsKey("tpl-java"));

        var listed = JsonRpc.JSON.readTree(text(call("list_instances", "{}")));
        assertEquals(1, listed.size());
        assertTrue(listed.get(0).path("kept").asBoolean());
    }

    @Test
    void aPurposeIsStampedAndListed() throws Exception {
        var r = call("create_instance", "{\"template\":\"tpl-java\",\"name_hint\":\"870-impl\",\"purpose\":\"#870 implement\"}");
        var name = JsonRpc.JSON.readTree(text(r)).path("instance").asText();
        assertEquals("#870 implement", backend.instances.get(name).get(Metadata.MCP_PURPOSE));
        assertTrue(text(call("list_instances", "{}")).contains("\"purpose\" : \"#870 implement\""));
    }

    @Test
    void execCanAnswerAQuestionInsteadOfReturningItsOutput() throws Exception {
        var name = createJava();
        backend.execStdout = "exit=3\nsummarised=900 lines, 40000 bytes\n---\nTwo tests fail: FooTest, BarTest.\n";
        var result = text(call("exec", "{\"instance\":\"" + name + "\",\"command\":\"mvn verify\","
                + "\"ask\":\"which tests fail?\"}"));
        assertTrue(result.startsWith("exit_code: 3\n"), "the command's exit code, not the summary's: " + result);
        assertTrue(result.contains("summarised: 900 lines, 40000 bytes"), result);
        assertTrue(result.contains("Two tests fail: FooTest, BarTest."), result);
        var script = backend.scripts.getLast();
        assertTrue(script.contains("--model 'haiku'"), script);
        assertFalse(script.contains("which tests fail"), "the question travels encoded, never as shell text");
        assertTrue(call("exec", "{\"instance\":\"" + name + "\",\"command\":\"x\",\"ask\":\"q\",\"background\":true}")
                .path("isError").asBoolean());
    }

    private JsonNode fork(String source, String extra) throws Exception {
        return call("create_instance", "{\"from_instance\":\"" + source + "\"" + extra + "}");
    }

    @Test
    void aForkIsABranchOfAStoppedInstanceThisSessionHolds() throws Exception {
        var source = createJava();
        call("keep_instance", "{\"instance\":\"" + source + "\"}");
        backend.stamp(source, Metadata.MCP_PURPOSE, "prepared");
        backend.stamp(source, Metadata.ACCOUNT_PREFIX + "claude", "work");
        call("stop_instance", "{\"instance\":\"" + source + "\"}");

        backend.metadataReads.set(0);
        var result = fork(source, ",\"name_hint\":\"review\",\"purpose\":\"correctness review\"");
        assertFalse(result.path("isError").asBoolean(), text(result));
        assertEquals(1, backend.metadataReads.get(), "ownership, status and lineage come from one read");
        var node = JsonRpc.JSON.readTree(text(result));
        var name = node.path("instance").asText();
        assertTrue(name.startsWith("mcp-java-review-"), name);
        assertEquals("tpl-java", node.path("template").asText());
        assertEquals(source, node.path("forked_from").asText());
        assertEquals(List.of(source + " -> " + name), backend.forks);

        var config = backend.instances.get(name);
        assertEquals("4242-1", config.get(Metadata.MCP_SESSION));
        assertEquals("alice", config.get(Metadata.MCP_OWNER));
        assertEquals("correctness review", config.get(Metadata.MCP_PURPOSE));
        assertEquals("work", config.get(Metadata.ACCOUNT_PREFIX + "claude"), "the source's pins come along");
        assertFalse(config.containsKey(Metadata.MCP_KEPT), "a fork of a kept instance is not kept");
        assertTrue(backend.scripts.stream().anyMatch(sc -> sc.equals(TaskScripts.clear())),
                "the fork starts without the source's tasks");

        // A fork is an instance like any other: it can be used, stopped and forked again.
        assertFalse(call("exec", "{\"instance\":\"" + name + "\",\"command\":\"true\"}").path("isError").asBoolean());
        call("stop_instance", "{\"instance\":\"" + name + "\"}");
        var again = fork(name, "");
        assertFalse(again.path("isError").asBoolean(), text(again));
        assertEquals("tpl-java", JsonRpc.JSON.readTree(text(again)).path("template").asText());
    }

    @Test
    void onlyAStoppedInstanceThisSessionHoldsCanBeForked() throws Exception {
        var running = createJava();
        var r = fork(running, "");
        assertTrue(r.path("isError").asBoolean());
        assertTrue(text(r).contains("stop_instance"), text(r));

        backend.instance("users-box", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PROFILE, "tpl-java"));
        backend.stopped.add("users-box");
        assertTrue(fork("users-box", "").path("isError").asBoolean());

        assertTrue(call("create_instance", "{}").path("isError").asBoolean(), "neither");
        assertTrue(call("create_instance", "{\"template\":\"tpl-java\",\"from_instance\":\"" + running + "\"}")
                .path("isError").asBoolean(), "both");
        assertEquals(List.of(), backend.forks);
    }

    @Test
    void aForkIsRefusedOnceItsTemplateIsNoLongerApproved() throws Exception {
        var source = createJava();
        call("stop_instance", "{\"instance\":\"" + source + "\"}");
        config.setTemplates(List.of("tpl-secret"));
        var r = fork(source, "");
        assertTrue(r.path("isError").asBoolean());
        assertTrue(text(r).contains("not approved"), text(r));
        assertEquals(List.of(), backend.forks);
    }

    @Test
    void aForkCountsAgainstTheInstanceLimit() throws Exception {
        config.setMaxInstances(2);
        var source = createJava();
        call("stop_instance", "{\"instance\":\"" + source + "\"}");
        assertFalse(fork(source, "").path("isError").asBoolean());
        var r = fork(source, "");
        assertTrue(r.path("isError").asBoolean());
        assertTrue(text(r).contains("mcp.max-instances"), text(r));
    }

    @Test
    void aForkWhoseTasksCannotBeClearedIsRemoved() throws Exception {
        var source = createJava();
        call("stop_instance", "{\"instance\":\"" + source + "\"}");
        backend.execExit = 1;
        var r = fork(source, "");
        assertTrue(r.path("isError").asBoolean());
        var name = backend.forks.getFirst().split(" -> ")[1];
        assertEquals(List.of(name), backend.destroyed);
        assertEquals(1, session.instances().size(), "only the source is held");
    }

    @Test
    void aBlankTemplateBesideFromInstanceIsNoTemplate() throws Exception {
        var source = createJava();
        call("stop_instance", "{\"instance\":\"" + source + "\"}");
        var r = fork(source, ",\"template\":\"\"");
        assertFalse(r.path("isError").asBoolean(), text(r));
    }

    @Test
    void anInstanceInErrorIsNeitherRunningNorStopped() throws Exception {
        var name = createJava();
        backend.statuses.put(name, "Error");
        var exec = call("exec", "{\"instance\":\"" + name + "\",\"command\":\"ls\"}");
        assertTrue(text(exec).contains("is error, which isx mcp cannot change. Ask the user"), text(exec));
        assertTrue(text(fork(name, "")).contains("is error, not stopped"));
        var start = call("start_instance", "{\"instance\":\"" + name + "\"}");
        assertTrue(start.path("isError").asBoolean(), "never reported started when nothing could start it");
        assertTrue(text(start).contains("only a stopped instance can be started"), text(start));
        backend.statuses.put(name, "Frozen");
        assertTrue(text(call("exec", "{\"instance\":\"" + name + "\",\"command\":\"ls\"}")).contains("is frozen"));
    }

    @Test
    void activityIsWhatTheProxyCountedForTheInstance() throws Exception {
        var name = createJava();
        var since = java.time.Instant.parse("2026-10-05T08:00:00Z");
        var ended = java.time.Instant.now().minusSeconds(90);
        backend.activity = new dev.incusspawn.proxy.ProxyActivity(Map.of(
                name, new dev.incusspawn.proxy.ProxyActivity.Instance(since, 12, 0, ended.minusSeconds(20), ended,
                        1_000, 300, 40_000, 2_000),
                "someone-elses", new dev.incusspawn.proxy.ProxyActivity.Instance(ended, 99, 1, ended, null, 1, 1, 1, 1)));

        var result = call("instance_activity", "{\"instance\":\"" + name + "\"}");

        assertFalse(result.path("isError").asBoolean(), text(result));
        var node = JsonRpc.JSON.readTree(text(result));
        assertEquals(name, node.path("instance").asText());
        assertEquals("2026-10-05T08:00:00Z", node.path("counting_since").asText());
        assertEquals(12, node.path("requests").asLong());
        assertEquals(0, node.path("requests_in_flight").asLong());
        assertEquals(ended.toString(), node.path("last_response_at").asText());
        assertTrue(node.path("idle_seconds").asLong() >= 90, node.toString());
        assertEquals(1_000, node.path("input_tokens").asLong());
        assertEquals(300, node.path("output_tokens").asLong());
        assertEquals(40_000, node.path("cache_read_input_tokens").asLong());
        assertEquals(2_000, node.path("cache_creation_input_tokens").asLong());
    }

    @Test
    void anInstanceThatNeverCalledReadsAsZeroAndACallInFlightIsNotIdle() throws Exception {
        var name = createJava();
        var silent = JsonRpc.JSON.readTree(text(call("instance_activity", "{\"instance\":\"" + name + "\"}")));
        assertEquals(0, silent.path("requests").asLong());
        assertFalse(silent.has("last_request_at"));
        assertFalse(silent.has("counting_since"), "an instance the proxy does not know: nothing to subtract from");
        assertFalse(silent.has("idle_seconds"));

        var now = java.time.Instant.now();
        backend.activity = new dev.incusspawn.proxy.ProxyActivity(Map.of(
                name, new dev.incusspawn.proxy.ProxyActivity.Instance(now, 2, 1, now, now.minusSeconds(600), 0, 0, 0, 0)));
        var busy = JsonRpc.JSON.readTree(text(call("instance_activity", "{\"instance\":\"" + name + "\"}")));
        assertEquals(1, busy.path("requests_in_flight").asLong());
        assertFalse(busy.has("idle_seconds"), "a long call in flight is work, not silence");
    }

    @Test
    void activityIsOnlyForOwnInstancesAndSaysWhenTheProxyCannotAnswer() throws Exception {
        backend.instance("users-box", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE));
        assertTrue(call("instance_activity", "{\"instance\":\"users-box\"}").path("isError").asBoolean());

        var name = createJava();
        backend.activity = null;
        var down = call("instance_activity", "{\"instance\":\"" + name + "\"}");
        assertTrue(down.path("isError").asBoolean());
        assertTrue(text(down).contains("isx proxy status"), text(down));
    }
}
