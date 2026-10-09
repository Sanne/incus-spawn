package dev.incusspawn.mcp;

import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One tool the server offers. Plain data plus a handler, so the tool list is ordinary code
 * with no annotation processing or reflection.
 *
 * @param outputSchema what its {@link ToolResult#structured()} holds: every tool declares one, and
 *                     every result it returns matches it
 * @param annotations MCP tool annotations ({@code readOnlyHint}, {@code destructiveHint}, ...)
 */
record McpTool(String name, String description, JqObject inputSchema, JqObject outputSchema,
               JqObject annotations, Handler handler) {

    @FunctionalInterface
    interface Handler {
        /**
         * Run the tool. Throw {@link ToolError} for a failure the agent should read and act on;
         * any other exception is reported as an internal error.
         */
        ToolResult call(Args args, ToolContext ctx) throws Exception;
    }

    JqObject descriptor() {
        return JqObject.builder(5)
                .put("name", name)
                .put("description", description)
                .put("inputSchema", inputSchema)
                .put("outputSchema", outputSchema)
                .put("annotations", annotations)
                .build();
    }

    static JqObject annotations(boolean readOnly, boolean destructive, boolean idempotent) {
        var builder = JqObject.builder(4).put("readOnlyHint", readOnly);
        if (!readOnly) builder.put("destructiveHint", destructive);
        builder.put("idempotentHint", idempotent);
        // Everything acts on isx instances, never on the open world directly
        builder.put("openWorldHint", false);
        return builder.build();
    }

    /** Typed access to a call's arguments, turning a missing or mistyped one into a {@link ToolError}. */
    record Args(JqValue node) {

        String requireString(String name) {
            var value = string(name);
            if (value == null || value.isBlank()) throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "missing required argument '" + name + "'");
            return value;
        }

        String string(String name) {
            var v = field(name);
            if (v.isNull()) return null;
            if (!v.isString()) throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "argument '" + name + "' must be a string");
            return v.asText();
        }

        Integer integer(String name) {
            var v = field(name);
            if (v.isNull()) return null;
            if (!v.isIntegralNumber()) {
                throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "argument '" + name + "' must be an integer");
            }
            var l = v.longValue();
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "argument '" + name + "' must be an integer");
            }
            return (int) l;
        }

        boolean bool(String name) {
            var v = field(name);
            if (v.isNull()) return false;
            if (!v.isBoolean()) throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "argument '" + name + "' must be a boolean");
            return v.booleanValue();
        }

        List<String> stringList(String name) {
            var v = field(name);
            var list = new java.util.ArrayList<String>();
            if (v.isNull()) return list;
            if (!v.isArray()) throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "argument '" + name + "' must be an array of strings");
            for (var e : v.asList()) {
                if (!e.isString()) throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "argument '" + name + "' must be an array of strings");
                list.add(e.asText());
            }
            return list;
        }

        Map<String, String> stringMap(String name) {
            var v = field(name);
            var map = new LinkedHashMap<String, String>();
            if (v.isNull()) return map;
            if (!(v instanceof JqObject object)) throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "argument '" + name + "' must be an object of strings");
            for (var e : object.entries()) {
                if (!e.getValue().isString()) {
                    throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "argument '" + name + "." + e.getKey() + "' must be a string");
                }
                map.put(e.getKey(), e.getValue().asText());
            }
            return map;
        }

        private JqValue field(String name) {
            if (!(node instanceof JqObject object)) return JqNull.NULL;
            return object.keys().contains(name)
                    ? object.getField(name)
                    : JqNull.NULL;
        }
    }
}
