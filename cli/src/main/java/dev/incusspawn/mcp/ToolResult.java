package dev.incusspawn.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** What a tool call returns: text for the model, and whether it is an error. */
record ToolResult(String text, boolean isError) {

    private static final ObjectWriter PRETTY = JsonRpc.JSON.writerWithDefaultPrettyPrinter();

    static ToolResult text(String text) {
        return new ToolResult(text, false);
    }

    static ToolResult json(JsonNode node) {
        try {
            return new ToolResult(PRETTY.writeValueAsString(node), false);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static ToolResult error(String message) {
        return new ToolResult(message, true);
    }

    ObjectNode toJson() {
        var node = JsonRpc.JSON.createObjectNode();
        var content = node.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", text);
        node.put("isError", isError);
        return node;
    }
}
