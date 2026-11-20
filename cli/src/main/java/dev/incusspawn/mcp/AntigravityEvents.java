package dev.incusspawn.mcp;

import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reads the tail of an Antigravity CLI task's {@code --output-format stream-json} events, and the
 * outcome {@code antigravity-runner.cjs} appends to it ({@link TaskScripts#status}). Tolerant as
 * {@link PiEvents} is: the tail starts mid-line, and an event this does not know is skipped. The
 * report is the runner's {@code result-<run>.txt}, since a tool's output may push it out of a tail.
 *
 * <p>agy reports each step of its conversation as {@code step_update} events under one
 * {@code step_index}: a tool as {@code ACTIVE} and then {@code DONE}, and the model's text in
 * {@code text_delta} pieces. So a step is one line here, at the place it was first seen.
 */
final class AntigravityEvents {

    /** Its tools name what they act on in these parameters, in this order of preference. */
    private static final List<String> INPUTS = List.of("CommandLine", "AbsolutePath", "TargetFile", "Path",
            "SearchPath", "Query", "Pattern", "Url");

    private AntigravityEvents() {}

    static StreamJsonEvents.Summary summarize(String tail, int keep) {
        // Per step index: a tool's line, or the model's text so far.
        var steps = new LinkedHashMap<Long, StringBuilder>();
        var tools = new java.util.HashSet<Long>();
        int messages = 0;
        JqValue result = null;
        for (var line : tail.split("\n")) {
            if (line.isBlank() || line.charAt(0) != '{') continue;
            JqValue e;
            try {
                e = Jjq.parseForeign(line, "Antigravity CLI's event");
            } catch (Exception ignored) {
                continue; // cut off by the tail, or still being written
            }
            if (!(e instanceof JqObject event)) continue;
            // The runner's outcome names its kind in type, as the other runners' do; agy's events in event.
            if (JsonRpc.text(event, "type", "").equals("isx.antigravity.result")) {
                result = PiEvents.outcome(event);
                continue;
            }
            switch (JsonRpc.text(event, "event", "")) {
                case "step_update" -> {
                    var step = event.getField("step_update");
                    var index = Jjq.exactInt(step instanceof JqObject s ? s.getField("step_index") : JsonRpc.nullNode());
                    if (index == null) continue;
                    long key = index;
                    switch (JsonRpc.text(step, "step_type", "")) {
                        case "tool" -> {
                            if (tools.add(key)) steps.put(key, new StringBuilder(
                                    "ran " + JsonRpc.text(step, "tool_name", "a tool") + describeInput(step)));
                        }
                        case "agent_response" -> {
                            var text = JsonRpc.text(step, "text_delta", "");
                            var said = steps.get(key);
                            if (said == null) {
                                steps.put(key, said = new StringBuilder());
                                messages++;
                            }
                            said.append(text);
                        }
                        default -> { }
                    }
                }
                default -> { }
            }
        }
        var recent = new ArrayList<String>();
        for (var entry : steps.entrySet()) {
            if (tools.contains(entry.getKey())) {
                recent.add(entry.getValue().toString());
            } else {
                var text = entry.getValue().toString().strip();
                if (!text.isEmpty()) recent.add("said: " + PiEvents.snippet(text));
            }
        }
        var from = Math.max(0, recent.size() - keep);
        return new StreamJsonEvents.Summary(List.copyOf(recent.subList(from, recent.size())), messages, result);
    }

    private static String describeInput(JqValue step) {
        var info = step instanceof JqObject s ? s.getField("tool_info") : JsonRpc.nullNode();
        var input = info instanceof JqObject i ? i.getField("parameters") : JsonRpc.nullNode();
        for (var key : INPUTS) {
            var v = JsonRpc.text(input, key, "");
            if (!v.isEmpty()) return ": " + PiEvents.snippet(v);
        }
        return "";
    }
}
