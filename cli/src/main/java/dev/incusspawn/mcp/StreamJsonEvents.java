package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;

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
    record Summary(List<String> recent, int assistantMessages, JsonNode result) {

        boolean finished() {
            return result != null;
        }

        String resultText() {
            return result == null ? null : result.path("result").asText("");
        }

        boolean isError() {
            return result != null && result.path("is_error").asBoolean(false);
        }
    }

    private StreamJsonEvents() {}

    static Summary summarize(String tail, int keep) {
        var recent = new ArrayList<String>();
        int assistant = 0;
        JsonNode result = null;
        for (var line : tail.split("\n")) {
            if (line.isBlank() || line.charAt(0) != '{') continue;
            JsonNode event;
            try {
                event = JsonRpc.JSON.readTree(line);
            } catch (Exception e) {
                continue; // cut off by the tail, or still being written
            }
            switch (event.path("type").asText("")) {
                case "assistant" -> {
                    assistant++;
                    for (var block : event.path("message").path("content")) {
                        switch (block.path("type").asText("")) {
                            case "text" -> {
                                var text = block.path("text").asText("").strip();
                                if (!text.isEmpty()) recent.add("said: " + snippet(text));
                            }
                            case "tool_use" -> recent.add("ran " + block.path("name").asText("a tool")
                                    + describeInput(block.path("input")));
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

    private static String describeInput(JsonNode input) {
        for (var key : List.of("command", "file_path", "pattern", "url", "description")) {
            var v = input.path(key).asText("");
            if (!v.isEmpty()) return ": " + snippet(v);
        }
        return "";
    }

    private static String snippet(String text) {
        var oneLine = text.replace('\n', ' ');
        return oneLine.length() <= SNIPPET ? oneLine : oneLine.substring(0, SNIPPET) + "...";
    }
}
