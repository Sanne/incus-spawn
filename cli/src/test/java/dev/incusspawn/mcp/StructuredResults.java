package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaId;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks tool results the way a client would: a successful result's {@code structuredContent}
 * against the tool's {@code outputSchema} with a real JSON Schema 2020-12 validator, and an error
 * result for no {@code structuredContent} and a code in {@code _meta}. Remembers which tools
 * returned a checked success, so a test can tell that it covered them all.
 */
final class StructuredResults {

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final JsonSchema METASCHEMA = FACTORY.getSchema(SchemaLocation.of(SchemaId.V202012));
    static final Set<String> CODES = java.util.Arrays.stream(ToolError.Code.values())
            .map(ToolError.Code::wire).collect(Collectors.toUnmodifiableSet());

    private final Map<String, McpTool> tools;
    private final Map<String, JsonSchema> compiled = new ConcurrentHashMap<>();
    final Set<String> succeeded = ConcurrentHashMap.newKeySet();

    StructuredResults(List<McpTool> tools) {
        this.tools = tools.stream().collect(Collectors.toMap(McpTool::name, Function.identity()));
    }

    Set<String> toolNames() {
        return tools.keySet();
    }

    /** {@code result} is the {@code result} of a {@code tools/call} of {@code tool}. */
    JsonNode check(String tool, JsonNode result) {
        if (result.path("isError").asBoolean()) {
            assertFalse(result.has("structuredContent"),
                    tool + ": an error result must carry no structuredContent (1.x SDK clients validate it): " + result);
            var error = result.path("_meta").path(ToolResult.ERROR_META);
            assertTrue(CODES.contains(error.path("code").asText()), tool + ": an error needs a known code: " + result);
            assertEquals(result.path("content").get(0).path("text").asText(), error.path("message").asText());
            return result;
        }
        var structured = result.get("structuredContent");
        assertNotNull(structured, tool + " returned no structuredContent: " + result);
        assertTrue(structured.isObject(), tool + ": structuredContent must be an object");
        var errors = compiled.computeIfAbsent(tool, t -> {
            var schema = tools.get(t);
            assertNotNull(schema, "no tool " + t);
            return compile(t, schema.outputSchema());
        }).validate(structured);
        assertTrue(errors.isEmpty(), tool + ": structuredContent does not match its outputSchema: " + errors
                + "\n" + structured.toPrettyString());
        succeeded.add(tool);
        return result;
    }

    /**
     * A schema every client can compile: no {@code $schema}, or 2020-12 (clients dispatch on it,
     * and default to 2020-12), and valid against the 2020-12 metaschema -- a schema a client cannot
     * compile fails every call of its tool, not just a bad one.
     */
    static JsonSchema compile(String tool, JsonNode schema) {
        var dialect = schema.path("$schema");
        assertTrue(dialect.isMissingNode() || dialect.asText().equals(SchemaId.V202012),
                tool + ": declares the dialect " + dialect);
        var invalid = METASCHEMA.validate(schema);
        assertTrue(invalid.isEmpty(), tool + ": not a valid 2020-12 schema: " + invalid);
        assertEquals("object", schema.path("type").asText(), tool + ": an outputSchema must describe an object");
        return FACTORY.getSchema(schema);
    }
}
