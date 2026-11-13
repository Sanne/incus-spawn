package dev.incusspawn.mcp;

import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the tail of a Pi task's {@code --mode json} events, and the outcome {@code pi-runner.cjs}
 * appends to it ({@link TaskScripts#status}). Tolerant as {@link StreamJsonEvents} is: the tail
 * starts mid-line, and an event this does not know is skipped. The report is not read from here:
 * Pi's events are too large for a tail to be sure to hold it, so the runner keeps it in
 * {@code result-<run>.txt}.
 */
final class PiEvents {

    private static final int SNIPPET = 300;

    private PiEvents() {}

    static StreamJsonEvents.Summary summarize(String tail, int keep) {
        var recent = new ArrayList<String>();
        int messages = 0;
        JqValue result = null;
        for (var line : tail.split("\n")) {
            if (line.isBlank() || line.charAt(0) != '{') continue;
            JqValue e;
            try {
                e = Jjq.parseForeign(line, "Pi's event");
            } catch (Exception ignored) {
                continue; // cut off by the tail, or still being written
            }
            if (!(e instanceof JqObject event)) continue;
            switch (JsonRpc.text(event, "type", "")) {
                case "isx.pi.result" -> result = outcome(event);
                case "tool_execution_start" -> recent.add("ran " + JsonRpc.text(event, "toolName", "a tool")
                        + describeArgs(event.getField("args")));
                case "message_end" -> {
                    var message = event.getField("message");
                    if (!"assistant".equals(JsonRpc.text(message, "role", ""))) continue;
                    messages++;
                    var content = message instanceof JqObject m ? m.getField("content") : JsonRpc.nullNode();
                    for (var block : content.asList()) {
                        if (!"text".equals(JsonRpc.text(block, "type", ""))) continue;
                        var text = JsonRpc.text(block, "text", "").strip();
                        if (!text.isEmpty()) recent.add("said: " + snippet(text));
                    }
                }
                default -> { }
            }
        }
        var from = Math.max(0, recent.size() - keep);
        return new StreamJsonEvents.Summary(List.copyOf(recent.subList(from, recent.size())), messages, result);
    }

    /**
     * The result a runner's outcome record ({@code isx.pi.result}, {@code isx.opencode.result})
     * stands for, in the shape {@link StreamJsonEvents.Summary#result} holds.
     */
    static JqValue outcome(JqObject event) {
        var success = JsonRpc.bool(event, "success", false);
        return JqObject.builder(8)
                .put("subtype", success ? "success" : "error")
                .put("is_error", !success)
                .put("result", JsonRpc.text(event, "error", ""))
                .put("usage", event.getField("usage"))
                .put("turns", event.getField("turns"))
                .put("cost_usd", event.getField("cost_usd"))
                .put("report_bytes", event.getField("report_bytes"))
                .put("report_truncated", event.getField("report_truncated"))
                .build();
    }

    private static String describeArgs(JqValue args) {
        for (var key : List.of("command", "path", "file_path", "pattern", "url")) {
            var v = JsonRpc.text(args, key, "");
            if (!v.isEmpty()) return ": " + snippet(v);
        }
        return "";
    }

    static String snippet(String text) {
        var oneLine = text.replace('\n', ' ');
        return oneLine.length() <= SNIPPET ? oneLine : oneLine.substring(0, SNIPPET) + "...";
    }
}
