package dev.incusspawn.mcp;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the tail of a delegated agent's {@code --output-format stream-json} log: what it has
 * been doing lately, and its final result once there is one.
 *
 * <p>Tolerant by design: the tail usually starts mid-line, a run still in progress ends mid-line,
 * and event shapes an older or newer Claude Code emits that are not understood are skipped.
 */
final class StreamJsonEvents {

    private static final int SNIPPET = 300;

    /**
     * @param recent  the last few things the agent said or did, oldest first
     * @param result  the {@code result} event, or null while the run is still going
     */
    record Summary(List<String> recent, int assistantMessages, JqValue result) {

        boolean finished() {
            return result != null;
        }

        String resultText() {
            return result == null ? null : JsonRpc.text(result, "result", "");
        }

        boolean isError() {
            return result != null && JsonRpc.bool(result, "is_error", false);
        }

        /**
         * The tools the agent's permission mode refused, by name, one per refusal. A headless
         * agent cannot ask for permission, so this is the only place a refusal shows.
         */
        List<String> permissionDenials() {
            if (result == null) return List.of();
            var names = new ArrayList<String>();
            var denials = result instanceof JqObject obj ? obj.getField("permission_denials") : JsonRpc.nullNode();
            for (var denial : denials.asList()) {
                names.add(JsonRpc.text(denial, "tool_name", "?"));
            }
            return names;
        }
    }

    private StreamJsonEvents() {}

    static Summary summarize(String tail, int keep) {
        var recent = new ArrayList<String>();
        int assistant = 0;
        JqValue result = null;
        for (var line : tail.split("\n")) {
            if (line.isBlank() || line.charAt(0) != '{') continue;
            JqValue event;
            try {
                event = JsonRpc.parse(line);
            } catch (Exception e) {
                continue; // cut off by the tail, or still being written
            }
            switch (JsonRpc.text(event, "type", "")) {
                case "assistant" -> {
                    assistant++;
                    var content = event instanceof JqObject msg ? msg.getField("message") : JsonRpc.nullNode();
                    var blocks = content instanceof JqObject contentObj ? contentObj.getField("content") : JsonRpc.nullNode();
                    for (var block : blocks.asList()) {
                        switch (JsonRpc.text(block, "type", "")) {
                            case "text" -> {
                                var text = JsonRpc.text(block, "text", "").strip();
                                if (!text.isEmpty()) recent.add("said: " + snippet(text));
                            }
                            case "tool_use" -> recent.add("ran " + JsonRpc.text(block, "name", "a tool")
                                    + describeInput(block instanceof JqObject b ? b.getField("input") : JsonRpc.nullNode()));
                            default -> { }
                        }
                    }
                }
                case "result" -> result = event;
                default -> { }
            }
        }
        var from = Math.max(0, recent.size() - keep);
        return new Summary(List.copyOf(recent.subList(from, recent.size())), assistant, result);
    }

    private static String describeInput(JqValue input) {
        for (var key : List.of("command", "file_path", "pattern", "url", "description")) {
            var v = JsonRpc.text(input, key, "");
            if (!v.isEmpty()) return ": " + snippet(v);
        }
        return "";
    }

    private static String snippet(String text) {
        var oneLine = text.replace('\n', ' ');
        return oneLine.length() <= SNIPPET ? oneLine : oneLine.substring(0, SNIPPET) + "...";
    }
}
