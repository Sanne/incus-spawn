package dev.incusspawn.mcp;

import dev.incusspawn.incus.JacksonTrees;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import dev.incusspawn.config.McpConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The output schemas {@code isx mcp} declares are a contract with programs that drive it: each
 * one compiles as JSON Schema 2020-12, and together they equal the golden copy, so changing one is
 * a deliberate diff. To accept a change, run with {@code -Dmcp.output-schemas.update=true} and
 * commit the rewritten file.
 */
class McpOutputSchemaTest {

    private static final Path GOLDEN = Path.of("src/test/resources/mcp/output-schemas.json");

    private static List<McpTool> tools() {
        var backend = new FakeBackend().template("tpl-java", true, "claude");
        var config = new McpConfig();
        var session = new McpSession(new SessionId(1, 1), "alice", 1, "/work", backend, () -> config, s -> false);
        return new McpTools(session, backend, new TemplatePolicy(backend, () -> config),
                new Tasks(session, backend, () -> config)).all();
    }

    @Test
    void everyToolDeclaresAnOutputSchemaThatCompiles() {
        for (var tool : tools()) {
            assertTrue(tool.outputSchema() != null, tool.name() + " declares no outputSchema");
            StructuredResults.compile(tool.name(), tool.outputSchema());
            assertEquals(tool.outputSchema(), tool.descriptor().path("outputSchema"), "listed by tools/list");
        }
    }

    @Test
    void aSchemaAClientCouldNotCompileOrWouldReadInAnotherDialectIsCaught() throws Exception {
        assertThrows(AssertionError.class, () -> StructuredResults.compile("x",
                JqValues.parse("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"text\"}}}")));
        assertThrows(AssertionError.class, () -> StructuredResults.compile("x",
                JqValues.parse("{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\"}")));
    }

    @Test
    void everyTaskStateSpeaksOneVocabulary() {
        var all = Set.of(OutputSchemas.TASK_STATES);
        var byTool = new HashMap<String, Set<String>>();
        for (var tool : tools()) {
            for (var states : taskStates(tool.outputSchema())) {
                assertTrue(all.containsAll(states), tool.name() + ": a task state outside TASK_STATES: " + states);
                byTool.put(tool.name(), states);
            }
        }
        // Every tool that reports a task's state offers every state, and only these report one.
        assertEquals(Map.of("task_status", all, "wait_any", all, "task_result", all, "list_instances", all), byTool);
        // The notification: the same words, never unknown, and released when another session took the task.
        var notified = new HashSet<>(TaskWatcher.ATTACHABLE);
        notified.addAll(List.of(TaskWatcher.RUNNING, TaskWatcher.LOST, "unknown"));
        assertEquals(all, notified);
        assertFalse(all.contains(TaskWatcher.RELEASED), "released is the notification's alone");
    }

    /** The enum of every {@code state} beside a {@code task_id}, anywhere in {@code schema}. */
    private static List<Set<String>> taskStates(JqValue schema) {
        var found = new ArrayList<Set<String>>();
        var properties = schema.path("properties");
        if (properties instanceof JqObject props && props.keys().contains("task_id") && props.keys().contains("state")) {
            var states = new HashSet<String>();
            props.getField("state").path("enum").asList().forEach(v -> states.add(v.asText()));
            assertTrue(!states.isEmpty(), "a task state must be an enum: " + props.getField("state"));
            found.add(states);
        }
        if (properties instanceof JqObject propsObj) {
            for (var p : propsObj.asMap().values()) {
                found.addAll(taskStates(p));
                found.addAll(taskStates(p.path("items")));
            }
        }
        return found;
    }

    @Test
    void theSchemasEqualTheGoldenCopy() throws Exception {
        var actual = JqObject.builder();
        tools().forEach(t -> actual.put(t.name(), t.outputSchema()));
        var text = JqValues.toPrettyJsonString(actual.build()) + "\n";
        if (Boolean.getBoolean("mcp.output-schemas.update")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, text);
        }
        var golden = Files.exists(GOLDEN)
                ? JacksonTrees.toJsonNode(JqValues.parse(Files.readString(GOLDEN)))
                : JacksonTrees.toJsonNode(JqObject.builder().build());
        assertEquals(golden, JacksonTrees.toJsonNode(actual.build()), "an output schema changed: programs driving isx mcp read these. If the change is "
                + "meant, run this test with -Dmcp.output-schemas.update=true and commit " + GOLDEN);
    }
}
