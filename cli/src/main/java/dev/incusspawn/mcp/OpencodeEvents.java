package dev.incusspawn.mcp;

import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the tail of an opencode task's {@code run --format json} events, and the outcome
 * {@code opencode-runner.cjs} appends to it ({@link TaskScripts#status}). Tolerant as
 * {@link PiEvents} is: the tail starts mid-line, and an event this does not know is skipped. The
 * report is the runner's {@code result-<run>.txt}, since a tool's output may push it out of a tail.
 */
final class OpencodeEvents {

    private OpencodeEvents() {}

    static StreamJsonEvents.Summary summarize(String tail, int keep) {
        var recent = new ArrayList<String>();
        var messageIds = new java.util.HashSet<String>();
        JqValue result = null;
        for (var line : tail.split("\n")) {
            if (line.isBlank() || line.charAt(0) != '{') continue;
            JqValue e;
            try {
                e = Jjq.parseForeign(line, "opencode's event");
            } catch (Exception ignored) {
                continue; // cut off by the tail, or still being written
            }
            if (!(e instanceof JqObject event)) continue;
            var part = event.getField("part");
            switch (JsonRpc.text(event, "type", "")) {
                case "isx.opencode.result" -> result = PiEvents.outcome(event);
                case "tool_use" -> recent.add("ran " + JsonRpc.text(part, "tool", "a tool")
                        + describeInput(part instanceof JqObject p ? p.getField("state") : JsonRpc.nullNode()));
                case "text" -> {
                    var id = JsonRpc.text(part, "messageID", "");
                    if (!id.isEmpty()) messageIds.add(id);
                    var text = JsonRpc.text(part, "text", "").strip();
                    if (!text.isEmpty()) recent.add("said: " + PiEvents.snippet(text));
                }
                default -> { }
            }
        }
        var from = Math.max(0, recent.size() - keep);
        return new StreamJsonEvents.Summary(List.copyOf(recent.subList(from, recent.size())), messageIds.size(), result);
    }

    private static String describeInput(JqValue state) {
        var input = state instanceof JqObject s ? s.getField("input") : JsonRpc.nullNode();
        for (var key : List.of("command", "filePath", "path", "pattern", "url")) {
            var v = JsonRpc.text(input, key, "");
            if (!v.isEmpty()) return ": " + PiEvents.snippet(v);
        }
        return "";
    }
}
