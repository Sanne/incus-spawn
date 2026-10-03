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
}
