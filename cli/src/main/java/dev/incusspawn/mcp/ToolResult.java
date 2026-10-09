package dev.incusspawn.mcp;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

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
record ToolResult(String text, JqObject structured, JqObject error) {

    static final String ERROR_META = "dev.incusspawn/error";

    /** Prose for the model, and the same facts for a program. */
    static ToolResult text(String text, JqObject structured) {
        return new ToolResult(text, structured, null);
    }

    /** The structure itself, pretty-printed, is the text. */
    static ToolResult json(JqObject structured) {
        return new ToolResult(dev.incusspawn.util.OutputFormat.toPrettyJson(structured), structured, null);
    }

    /** The structure, followed by guidance for the model that is not part of it. */
    static ToolResult json(JqObject structured, String note) {
        return new ToolResult(dev.incusspawn.util.OutputFormat.toPrettyJson(structured) + "\n\n" + note, structured, null);
    }

    static ToolResult error(ToolError.Code code, String message, JqValue args) {
        var b = JqObject.builder(4);
        b.put("code", code.wire());
        b.put("message", message);
        // What the call named, so a program running many need not keep its own map from call to subject.
        for (var key : new String[] {"instance", "task_id"}) {
            var value = args == null ? null : args.getField(key);
            if (value != null && value.isString()) b.put(key, value.asText());
        }
        return new ToolResult(message, null, b.build());
    }

    boolean isError() {
        return error != null;
    }

    JqObject toJson() {
        var content = JqObject.builder(2).put("type", "text").put("text", text).build();
        var items = JqArray.arrayBuilder().add(content).build();
        var b = JqObject.builder(4).put("content", items).put("isError", isError());
        if (structured != null) b.put("structuredContent", structured);
        if (error != null) b.put("_meta", JqObject.builder(1).put(ERROR_META, error).build());
        return b.build();
    }
}
