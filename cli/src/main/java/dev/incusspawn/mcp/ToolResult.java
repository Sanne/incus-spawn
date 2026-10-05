package dev.incusspawn.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What a tool call returns: text for the model, and for a program either {@code structured},
 * the tool's facts matching its {@link McpTool#outputSchema()}, or {@code error}, a refusal's
 * {@code {code, message, instance?, task_id?}}.
 *
 * <p>An error result carries no {@code structuredContent} at all: its code goes in {@code _meta}
 * under {@link #ERROR_META}. The 1.x TypeScript SDKs validate {@code structuredContent} against
 * the tool's output schema even on an error result (2.x and the Python SDK do not), and Claude
 * Code ships both generations, so an error object there would turn every refusal into a client
 * failure.
 */
record ToolResult(String text, ObjectNode structured, ObjectNode error) {

    static final String ERROR_META = "dev.incusspawn/error";

    private static final ObjectWriter PRETTY = JsonRpc.JSON.writerWithDefaultPrettyPrinter();

    /** Prose for the model, and the same facts for a program. */
    static ToolResult text(String text, ObjectNode structured) {
        return new ToolResult(text, structured, null);
    }

    /** The structure itself, pretty-printed, is the text. */
    static ToolResult json(ObjectNode structured) {
        return new ToolResult(pretty(structured), structured, null);
    }

    /** The structure, followed by guidance for the model that is not part of it. */
    static ToolResult json(ObjectNode structured, String note) {
        return new ToolResult(pretty(structured) + "\n\n" + note, structured, null);
    }

    static ToolResult error(ToolError.Code code, String message, JsonNode args) {
        var error = JsonRpc.JSON.createObjectNode();
        error.put("code", code.wire());
        error.put("message", message);
        // What the call named, so a program running many need not keep its own map from call to subject.
        for (var key : new String[] {"instance", "task_id"}) {
            var value = args == null ? null : args.get(key);
            if (value != null && value.isTextual()) error.put(key, value.asText());
        }
        return new ToolResult(message, null, error);
    }

    boolean isError() {
        return error != null;
    }

    private static String pretty(JsonNode node) {
        try {
            return PRETTY.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    ObjectNode toJson() {
        var node = JsonRpc.JSON.createObjectNode();
        var content = node.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", text);
        node.put("isError", isError());
        if (structured != null) node.set("structuredContent", structured);
        if (error != null) node.putObject("_meta").set(ERROR_META, error);
        return node;
    }
}
