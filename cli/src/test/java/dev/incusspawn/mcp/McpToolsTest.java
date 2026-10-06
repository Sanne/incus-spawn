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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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
    private StructuredResults results;
    private int nextId = 1;

    @BeforeEach
    void setUp() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString()); // the audit log goes here
        config.setTemplates(List.of("tpl-java"));
        wire(s -> false);
    }

    /** A session whose view of other sessions is {@code alive}, and the server over its tools. */
    private void wire(Predicate<SessionId> alive) {
        wire(new SessionId(4242, 1), alive);
    }

    /** {@link #wire(Predicate)}, as session {@code id}: a later session of the same user. */
    private void wire(SessionId id, Predicate<SessionId> alive) {
        session = new McpSession(id, "alice", 1, "/work", backend, () -> config, alive);
        var all = new McpTools(session, backend, new TemplatePolicy(backend, () -> config),
                new Tasks(session, backend, () -> config)).all();
        results = new StructuredResults(all);
        server = new McpServer(out, all, "1", null, null);
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

    private static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asText();
    }

    private String createJava() throws Exception {
        var result = call("create_instance", "{\"template\":\"tpl-java\"}");
        assertFalse(result.path("isError").asBoolean(), text(result));
        return result.path("structuredContent").path("instance").asText();
    }

    @Test
    void onlyApprovedTemplatesAreListed() throws Exception {
        var listed = call("list_templates", "{}").path("structuredContent").path("templates");
        assertEquals(1, listed.size());
        assertEquals("tpl-java", listed.get(0).path("template").asText());
        assertTrue(listed.get(0).path("supports_delegate").asBoolean());
    }

    @Test
    void theCeilingsAreListedWithTheTemplates() throws Exception {
        config.setMaxInstances(12);
        config.setMaxConcurrentTasks(10);
        var listed = call("list_templates", "{}").path("structuredContent");
        assertEquals(12, listed.path("max_instances").asInt());
        assertEquals(10, listed.path("max_concurrent_tasks").asInt());
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
        backend.createFailure = new ToolError(ToolError.Code.UNAVAILABLE, "proxy is down");
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

        var listed = call("list_instances", "{}").path("structuredContent").path("instances");
        assertEquals(1, listed.size());
        assertTrue(listed.get(0).path("kept").asBoolean());

        // Deleted behind the session's back: destroying it says it was already gone, as stop and start do.
        var c = createJava();
        backend.instances.remove(c);
        var gone = call("destroy_instance", "{\"instance\":\"" + c + "\"}");
        assertTrue(gone.path("structuredContent").path("already").asBoolean(), gone.toString());
    }

    @Test
    void anotherLiveSessionsInstanceIsListedWithWhatIsStampedAndNothingMade() throws Exception {
        wire(s -> true);
        backend.instance("mcp-java-a-abcde", Map.of(Metadata.PROFILE, "tpl-java", Metadata.MCP_OWNER, "alice",
                Metadata.MCP_SESSION, "9-9", Metadata.MCP_CLIENT, "claude-code"));
        backend.instance("mcp-java-b-abcde", Map.of(Metadata.PROFILE, "tpl-java", Metadata.MCP_OWNER, "alice",
                Metadata.MCP_SESSION, "not-a-session"));
        backend.instance("mcp-java-kept-abcde", Map.of(Metadata.PROFILE, "tpl-java", Metadata.MCP_OWNER, "alice",
                Metadata.MCP_SESSION, "9-9", Metadata.MCP_KEPT, "true"));
        var listed = call("list_instances", "{}").path("structuredContent").path("instances");
        var byName = new HashMap<String, JsonNode>();
        listed.forEach(i -> byName.put(i.path("instance").asText(), i));
        assertEquals(Set.of("mcp-java-a-abcde", "mcp-java-b-abcde"), byName.keySet(),
                "a kept instance is the user's, even while a live session still holds it");
        var a = byName.get("mcp-java-a-abcde");
        assertEquals("held", a.path("state").asText());
        assertEquals("9-9", a.path("held_by").asText());
        assertEquals("claude-code", a.path("held_by_client").asText());
        assertFalse(a.path("kept").asBoolean());
        var b = byName.get("mcp-java-b-abcde");
        assertEquals("held", b.path("state").asText(), "an unreadable session stamp counts as held");
        assertFalse(b.has("held_by"), "no session id is made up: " + b);
        assertFalse(b.has("held_by_client"));
    }

    @Test
    void aPurposeIsStampedAndListed() throws Exception {
        var r = call("create_instance", "{\"template\":\"tpl-java\",\"name_hint\":\"870-impl\",\"purpose\":\"#870 implement\"}");
        var name = r.path("structuredContent").path("instance").asText();
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
        var node = result.path("structuredContent");
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
        assertEquals("tpl-java", again.path("structuredContent").path("template").asText());
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
        assertEquals("unavailable", down.path("_meta").path(ToolResult.ERROR_META).path("code").asText());
    }

    // --- idempotency keys (#1011) ---

    private JsonNode createKeyed(String key) throws Exception {
        return call("create_instance", "{\"template\":\"tpl-java\",\"idempotency_key\":\"" + key + "\"}");
    }

    private static String code(JsonNode result) {
        return result.path("_meta").path(ToolResult.ERROR_META).path("code").asText();
    }

    /** The instances made through isx mcp, by name. */
    private Set<String> made() {
        return backend.mcpInstances().keySet();
    }

    @Test
    void theSameKeyTwiceMakesOneInstanceAndSaysTheSecondCallMadeNothing() throws Exception {
        var first = createKeyed("issue-1011:impl");
        assertFalse(first.path("isError").asBoolean(), text(first));
        var name = first.path("structuredContent").path("instance").asText();
        assertFalse(first.path("structuredContent").has("replayed"), "a create that made it says nothing of replays");
        assertEquals("issue-1011:impl", backend.instances.get(name).get(Metadata.MCP_IDEMPOTENCY_KEY));

        var again = createKeyed("issue-1011:impl");
        assertFalse(again.path("isError").asBoolean(), text(again));
        var replayed = again.path("structuredContent");
        assertEquals(name, replayed.path("instance").asText());
        assertTrue(replayed.path("replayed").asBoolean());
        assertEquals("tpl-java", replayed.path("template").asText());
        assertEquals("10.0.0.2", replayed.path("ip").asText(), "rebuilt from what Incus holds: " + replayed);
        assertEquals("/home/agentuser", replayed.path("workdir").asText());
        assertTrue(replayed.path("supports_delegate").asBoolean());
        assertEquals(Set.of(name), made());

        var other = createKeyed("issue-1011:review");
        assertFalse(other.path("isError").asBoolean(), text(other));
        assertEquals(2, made().size(), "a different key is a different instance");
    }

    @Test
    void aKeyedCreateLooksForItsKeyInTheListingItReservesWith() throws Exception {
        backend.listings.set(0);
        createJava();
        assertEquals(1, backend.listings.get(), "a create without a key reads what it always did");
        backend.listings.set(0);
        var reads = backend.metadataReads.get();
        createKeyed("k1");
        assertEquals(2, backend.listings.get(), "one listing before the copy, one after it");
        assertEquals(reads, backend.metadataReads.get(), "and nothing else");
        backend.listings.set(0);
        createKeyed("k1");
        assertEquals(1, backend.listings.get(), "a replay held here is one listing");
        assertEquals(reads, backend.metadataReads.get());
    }

    @Test
    void aKeyThatIsNotOneIsRefused() throws Exception {
        for (var bad : List.of("", "has space", "semi;colon", "x".repeat(65), "\u00e9t\u00e9")) {
            var r = createKeyed(bad);
            assertTrue(r.path("isError").asBoolean(), bad);
            assertEquals("invalid_argument", code(r), bad);
        }
        assertEquals(Set.of(), made());
        assertFalse(createKeyed("A-z.0_9:" + "x".repeat(56)).path("isError").asBoolean(), "64 of the allowed characters");
    }

    @Test
    void aKeyRepeatedForAnotherInstanceIsRefusedNotRedirected() throws Exception {
        config.setTemplates(List.of("tpl-java", "tpl-secret"));
        var name = createKeyed("k1").path("structuredContent").path("instance").asText();
        var otherTemplate = call("create_instance", "{\"template\":\"tpl-secret\",\"idempotency_key\":\"k1\"}");
        assertTrue(otherTemplate.path("isError").asBoolean());
        assertEquals("invalid_argument", code(otherTemplate));
        assertTrue(text(otherTemplate).contains(name) && text(otherTemplate).contains("tpl-java"), text(otherTemplate));

        var source = createJava();
        call("stop_instance", "{\"instance\":\"" + source + "\"}");
        var asFork = fork(source, ",\"idempotency_key\":\"k1\"");
        assertTrue(asFork.path("isError").asBoolean());
        assertEquals("invalid_argument", code(asFork));
        assertEquals(List.of(), backend.forks);

        var forked = fork(source, ",\"idempotency_key\":\"k2\"");
        assertFalse(forked.path("isError").asBoolean(), text(forked));
        var fromTemplate = createKeyed("k2");
        assertEquals("invalid_argument", code(fromTemplate), "a fork's key is not its template's: " + text(fromTemplate));
        var forkAgain = fork(source, ",\"idempotency_key\":\"k2\"");
        assertTrue(forkAgain.path("structuredContent").path("replayed").asBoolean(), text(forkAgain));
        assertEquals(source, forkAgain.path("structuredContent").path("forked_from").asText());
    }

    @Test
    void aForkCarriesItsOwnKeyOrNone() throws Exception {
        var source = createKeyed("k1").path("structuredContent").path("instance").asText();
        call("stop_instance", "{\"instance\":\"" + source + "\"}");

        var plain = fork(source, "").path("structuredContent").path("instance").asText();
        assertFalse(backend.forkStamps.getLast().containsKey(Metadata.MCP_IDEMPOTENCY_KEY));
        assertFalse(backend.instances.get(plain).containsKey(Metadata.MCP_IDEMPOTENCY_KEY),
                "the copy drops its source's (InterruptedBranchTest has it on a real copy request)");

        var keyed = fork(source, ",\"idempotency_key\":\"k2\"").path("structuredContent").path("instance").asText();
        assertEquals("k2", backend.forkStamps.getLast().get(Metadata.MCP_IDEMPOTENCY_KEY));
        assertEquals("k2", backend.instances.get(keyed).get(Metadata.MCP_IDEMPOTENCY_KEY));

        var replay = createKeyed("k1").path("structuredContent");
        assertEquals(source, replay.path("instance").asText(), "the source's key names the source, never a fork of it");
    }

    @Test
    void aKeptInstancesKeyIsRefused() throws Exception {
        var name = createKeyed("k1").path("structuredContent").path("instance").asText();
        call("keep_instance", "{\"instance\":\"" + name + "\"}");
        var r = createKeyed("k1");
        assertTrue(r.path("isError").asBoolean());
        assertEquals("refused", code(r));
        assertTrue(text(r).contains("list_instances"), text(r));
        assertEquals(Set.of(name), made(), "refused, not made again");
    }

    /** An instance another session made under {@code key}: held by {@code session}, from tpl-java. */
    private void madeElsewhere(String name, String key, String session, boolean finished) {
        var config = new HashMap<>(Map.of(Metadata.PROFILE, "tpl-java", Metadata.PARENT, "tpl-java",
                Metadata.MCP_OWNER, "alice", Metadata.MCP_SESSION, session, Metadata.MCP_IDEMPOTENCY_KEY, key));
        if (finished) config.put(Metadata.STATIC_IP, "10.0.0.9");
        backend.instance(name, config);
    }

    @Test
    void aKeyAnotherLiveSessionHoldsIsRefusedPointingAtAdoption() throws Exception {
        wire(s -> true);
        madeElsewhere("mcp-java-theirs", "k1", "9-9", true);
        var r = createKeyed("k1");
        assertTrue(r.path("isError").asBoolean());
        assertEquals("not_held", code(r));
        assertTrue(text(r).contains("adopt_instance"), text(r));
        assertEquals(Set.of("mcp-java-theirs"), made());
        assertEquals("9-9", backend.instances.get("mcp-java-theirs").get(Metadata.MCP_SESSION), "and not taken");
    }

    @Test
    void anotherUsersKeyIsNotMine() throws Exception {
        madeElsewhere("mcp-java-bobs", "k1", "9-9", true);
        backend.stamp("mcp-java-bobs", Metadata.MCP_OWNER, "bob");
        var r = createKeyed("k1");
        assertFalse(r.path("structuredContent").path("replayed").asBoolean(), text(r));
        assertEquals("bob", backend.instances.get("mcp-java-bobs").get(Metadata.MCP_OWNER));
    }

    @Test
    void afterTheSessionIsReplacedTheKeyAdoptsTheOrphan() throws Exception {
        var name = createKeyed("k1").path("structuredContent").path("instance").asText();
        session.release();
        wire(new SessionId(5151, 2), s -> false);
        var r = createKeyed("k1");
        assertFalse(r.path("isError").asBoolean(), text(r));
        assertEquals(name, r.path("structuredContent").path("instance").asText());
        assertTrue(r.path("structuredContent").path("replayed").asBoolean());
        assertEquals("5151-2", backend.instances.get(name).get(Metadata.MCP_SESSION), "adopted, with adopt's checks");
        assertFalse(backend.instances.get(name).containsKey(Metadata.MCP_ORPHANED));
        assertTrue(session.holds(name));
        assertEquals(Set.of(name), made());
    }

    @Test
    void anOrphanWhoseTemplateIsNoLongerApprovedIsNotReplayed() throws Exception {
        madeElsewhere("mcp-java-old", "k1", "9-9", true);
        config.setTemplates(List.of("tpl-secret"));
        var r = createKeyed("k1");
        assertEquals("not_approved", code(r), text(r));
        assertEquals("9-9", backend.instances.get("mcp-java-old").get(Metadata.MCP_SESSION));
    }

    @Test
    void aKeyedCreateThatRacedAnEarlierCopyGivesWayToIt() throws Exception {
        // A session that died while Incus went on copying: its copy is listed after this create looked.
        backend.onCreate = () -> {
            backend.onCreate = null;
            madeElsewhere("mcp-java-earlier", "k1", "9-9", true);
            backend.createdAt.put("mcp-java-earlier", "2026-10-06T07:00:00Z");
        };
        var r = createKeyed("k1");
        assertFalse(r.path("isError").asBoolean(), text(r));
        assertEquals("mcp-java-earlier", r.path("structuredContent").path("instance").asText());
        assertTrue(r.path("structuredContent").path("replayed").asBoolean());
        assertEquals(1, backend.destroyed.size(), "its own copy is gone: " + backend.destroyed);
        assertEquals(Set.of("mcp-java-earlier"), made());
        assertEquals("4242-1", backend.instances.get("mcp-java-earlier").get(Metadata.MCP_SESSION));
        assertEquals(List.of("mcp-java-earlier"), session.instances().stream().map(McpSession.Owned::name).toList());
    }

    @Test
    void aKeyedCreateThatRacedALaterCopyKeepsItsOwn() throws Exception {
        // The other create began after this one: it is the one to give way, when it looks again.
        backend.onCreate = () -> {
            backend.onCreate = null;
            madeElsewhere("mcp-java-later", "k1", "9-9", true);
            backend.createdAt.put("mcp-java-later", "2026-10-06T09:00:00Z");
        };
        var r = createKeyed("k1");
        var name = r.path("structuredContent").path("instance").asText();
        assertTrue(name.startsWith("mcp-java-") && !name.equals("mcp-java-later"), text(r));
        assertFalse(r.path("structuredContent").has("replayed"));
        assertEquals(List.of(), backend.destroyed);
    }

    @Test
    void aCopyADeadSessionNeverFinishedIsNoInstanceTheKeyMade() throws Exception {
        // Cut off between the copy and the branch's configuration: no address, never started.
        madeElsewhere("mcp-java-halfmade", "k1", "9-9", false);
        var r = createKeyed("k1");
        var name = r.path("structuredContent").path("instance").asText();
        assertFalse(r.path("structuredContent").has("replayed"), text(r));
        assertTrue(!name.equals("mcp-java-halfmade"), name);
        // From now on the key answers with the one made properly, however the two compare.
        assertEquals(name, createKeyed("k1").path("structuredContent").path("instance").asText());
    }

    @Test
    void aKeyedCreateStillUnderWayIsBusyNotDoubled() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        backend.onCreate = () -> {
            entered.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var args = "{\"template\":\"tpl-java\",\"idempotency_key\":\"k1\"}";
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":901,\"method\":\"tools/call\",\"params\":{\"name\":\"create_instance\",\"arguments\":" + args + "}}");
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "first copy under way");
        server.handle("{\"jsonrpc\":\"2.0\",\"id\":902,\"method\":\"tools/call\",\"params\":{\"name\":\"create_instance\",\"arguments\":" + args + "}}");
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (out.sent.stream().noneMatch(m -> m.path("id").asInt() == 902) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("busy", code(out.byId(902).path("result")), out.byId(902).toString());
        release.countDown();
        server.awaitIdle();
        backend.onCreate = null;
        var name = out.byId(901).path("result").path("structuredContent").path("instance").asText();
        assertEquals(Set.of(name), made());
        assertEquals(name, createKeyed("k1").path("structuredContent").path("instance").asText());
    }

    /** A copy a live session is still making from tpl-java under {@code key}: as Incus lists it before configureBranch. */
    private void inFlightElsewhere(String name, String key) {
        var config = new HashMap<>(backend.instances.get("tpl-java"));
        config.put(Metadata.TYPE, Metadata.TYPE_CLONE);
        config.putAll(Map.of(Metadata.MCP_OWNER, "alice", Metadata.MCP_SESSION, "9-9", Metadata.MCP_IDEMPOTENCY_KEY, key));
        backend.instance(name, config);
    }

    @Test
    void aRepeatWhileAnotherSessionIsStillMakingTheInstanceIsNotHeldNotAMismatch() throws Exception {
        wire(s -> true);
        // Still the template's copy: its parent is the template's own, which would read as a fork of it.
        inFlightElsewhere("mcp-java-theirs", "k1");
        var r = createKeyed("k1");
        assertEquals("not_held", code(r), text(r));
        assertFalse(text(r).contains("new key"), "never advice that makes a second instance: " + text(r));
    }

    @Test
    void aCreateThatGivesWayToACopyStillBeingMadeIsNotHeldNotAMismatch() throws Exception {
        wire(s -> true);
        backend.onCreate = () -> {
            backend.onCreate = null;
            inFlightElsewhere("mcp-java-theirs", "k1");
            backend.createdAt.put("mcp-java-theirs", "2026-10-06T07:00:00Z");
        };
        var r = createKeyed("k1");
        assertEquals("not_held", code(r), text(r));
        assertEquals(Set.of("mcp-java-theirs"), made(), "its own copy gave way");
    }

    @Test
    void aForkADeadSessionNeverFinishedIsNoInstanceTheKeyMade() throws Exception {
        var source = createJava();
        call("stop_instance", "{\"instance\":\"" + source + "\"}");
        // The session dies between the fork's copy and its configuration.
        backend.onCreate = () -> {
            backend.onCreate = null;
            throw new IllegalStateException("killed");
        };
        assertTrue(fork(source, ",\"idempotency_key\":\"k1\"").path("isError").asBoolean());
        session.release();
        wire(new SessionId(5151, 2), s -> false);
        call("adopt_instance", "{\"instance\":\"" + source + "\"}");
        var r = fork(source, ",\"idempotency_key\":\"k1\"");
        assertFalse(r.path("isError").asBoolean(), text(r));
        assertFalse(r.path("structuredContent").has("replayed"), "the half-made fork is not what the key made: " + text(r));
        assertEquals("10.0.0.3", r.path("structuredContent").path("ip").asText(), "its own address, never its source's");
    }

    @Test
    void anInstanceItsHolderReleasedIsAdoptedForTheKey() throws Exception {
        // An instance session (#915) lives on after releasing what it held.
        wire(s -> true);
        madeElsewhere("mcp-java-released", "k1", "9-9", true);
        backend.stamp("mcp-java-released", Metadata.MCP_ORPHANED, Orphans.orphanedStamp(java.time.Instant.now(), "9-9"));
        var r = createKeyed("k1");
        assertTrue(r.path("structuredContent").path("replayed").asBoolean(), text(r));
        assertEquals("4242-1", backend.instances.get("mcp-java-released").get(Metadata.MCP_SESSION));
    }
}
