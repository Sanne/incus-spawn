package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;

/** Bounded JSONL tails, plus the durable terminal record appended by TaskScripts.status. */
final class CodexEvents {
    static StreamJsonEvents.Summary summarize(String tail, int keep) {
        var recent = new ArrayList<String>();
        int messages = 0;
        JsonNode result = null;
        for (var line : tail.split("\n")) {
            try {
                var e = JsonRpc.JSON.readTree(line);
                if (e == null) continue;
                if (e.path("type").asText().equals("isx.codex.result")) {
                    var r = JsonRpc.JSON.createObjectNode();
                    r.put("subtype", e.path("success").asBoolean() ? "success" : "error");
                    r.put("is_error", !e.path("success").asBoolean());
                    r.put("result", e.path("error").asText());
                    r.set("usage", e.path("usage"));
                    r.set("report_bytes", e.path("report_bytes"));
                    r.set("report_truncated", e.path("report_truncated"));
                    result = r;
                } else if (java.util.List.of("item.started", "item.updated", "item.completed").contains(e.path("type").asText())) {
                    var item = e.path("item");
                    var type = item.path("type").asText();
                    if (type.equals("agent_message") && e.path("type").asText().equals("item.completed")) messages++;
                    var text = type + ": " + item.path("text").asText(item.path("command").asText(item.path("status").asText()));
                    recent.add(text.substring(0, Math.min(300, text.length())));
                    while (recent.size() > keep) recent.removeFirst();
                }
            } catch (Exception ignored) { /* partial lines and future events are harmless */ }
        }
        return new StreamJsonEvents.Summary(recent, messages, result);
    }

    static String runner() {
        try (var in = CodexEvents.class.getResourceAsStream("/mcp/codex-runner.cjs")) {
            if (in == null) throw new IllegalStateException("missing Codex runner resource");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read Codex runner", e);
        }
    }
}
