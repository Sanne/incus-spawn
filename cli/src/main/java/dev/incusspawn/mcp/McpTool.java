package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One tool the server offers. Plain data plus a handler, so the tool list is ordinary code
 * with no annotation processing or reflection.
 *
 * @param annotations MCP tool annotations ({@code readOnlyHint}, {@code destructiveHint}, ...)
 */
record McpTool(String name, String description, ObjectNode inputSchema, ObjectNode annotations,
               Handler handler) {

    @FunctionalInterface
    interface Handler {
        /**
         * Run the tool. Throw {@link ToolError} for a failure the agent should read and act on;
         * any other exception is reported as an internal error.
         */
        ToolResult call(Args args, ToolContext ctx) throws Exception;
    }

    ObjectNode descriptor() {
        var node = JsonRpc.JSON.createObjectNode();
        node.put("name", name);
        node.put("description", description);
        node.set("inputSchema", inputSchema);
        node.set("annotations", annotations);
        return node;
    }

    static ObjectNode annotations(boolean readOnly, boolean destructive, boolean idempotent) {
        var node = JsonRpc.JSON.createObjectNode();
        node.put("readOnlyHint", readOnly);
        if (!readOnly) node.put("destructiveHint", destructive);
        node.put("idempotentHint", idempotent);
        // Everything acts on isx instances, never on the open world directly
        node.put("openWorldHint", false);
        return node;
    }

    /** Typed access to a call's arguments, turning a missing or mistyped one into a {@link ToolError}. */
    record Args(JsonNode node) {

        String requireString(String name) {
            var value = string(name);
            if (value == null || value.isBlank()) throw new ToolError("missing required argument '" + name + "'");
            return value;
        }

        String string(String name) {
            var v = node.get(name);
            if (v == null || v.isNull()) return null;
            if (!v.isTextual()) throw new ToolError("argument '" + name + "' must be a string");
            return v.asText();
        }

        Integer integer(String name) {
            var v = node.get(name);
            if (v == null || v.isNull()) return null;
            if (!v.canConvertToInt() || !v.isIntegralNumber()) {
                throw new ToolError("argument '" + name + "' must be an integer");
            }
            return v.asInt();
        }

        boolean bool(String name) {
            var v = node.get(name);
            if (v == null || v.isNull()) return false;
            if (!v.isBoolean()) throw new ToolError("argument '" + name + "' must be a boolean");
            return v.asBoolean();
        }

        Map<String, String> stringMap(String name) {
            var v = node.get(name);
            var map = new LinkedHashMap<String, String>();
            if (v == null || v.isNull()) return map;
            if (!v.isObject()) throw new ToolError("argument '" + name + "' must be an object of strings");
            for (var e : v.properties()) {
                if (!e.getValue().isTextual()) {
                    throw new ToolError("argument '" + name + "." + e.getKey() + "' must be a string");
                }
                map.put(e.getKey(), e.getValue().asText());
            }
            return map;
        }
    }
}
