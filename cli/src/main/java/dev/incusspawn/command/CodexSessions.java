package dev.incusspawn.command;

import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;

/**
 * Codex's sessions in a guest (#2241): the rollouts it keeps under
 * {@code ~/.codex/sessions/<year>/<month>/<day>/rollout-*.jsonl}, read as {@link JsonlSessions}
 * reads them (verified with Codex 0.169.0). Sessions moved to {@code archived_sessions} are not
 * read.
 *
 * <p>Each line is {@code {"type": ..., "payload": ...}}; the conversation is in its
 * {@code response_item} lines whose payload is a {@code message}. The closing message is the last
 * {@code assistant} one with {@code output_text}, never {@code task_complete}'s
 * {@code last_agent_message}, which an interrupted turn leaves at an older message. Codex puts its
 * own context in {@code user} messages too (the environment, a repository's {@code AGENTS.md}):
 * where the message's {@code content_item_kinds} say, the ask is one of the person's kinds alone, with
 * {@code user.text} among them; in a rollout that does not say, one not opening with that context's markers.
 */
public final class CodexSessions extends JsonlSessions {

    static final String AGENT = "codex";
    public static final String HEADER = "isx-sessions codex 1";

    CodexSessions() {
        super(new Layout(AGENT, HEADER, "${CODEX_HOME:-$HOME/.codex}/sessions", 4, "rollout-*.jsonl", null,
                List.of("\"type\":\"response_item\"", "\"role\":\"user\""),
                // Codex's own context, often longer than the bound: never a candidate that would hide the person's ask
                List.of("\"agents_md.instructions\"", "\"environments.environment_context\"",
                        "\"text\":\"# AGENTS.md instructions", "\"text\":\"<environment_context>", "\"text\":\"<user_instructions>"),
                List.of("\"type\":\"response_item\"", "\"role\":\"assistant\"", "\"output_text\""), false, true));
    }

    private static JqValue message(JqValue node, String role) {
        if (!"response_item".equals(node.pathText("type", ""))) return null;
        var payload = node.path("payload");
        return "message".equals(payload.pathText("type", "")) && role.equals(payload.pathText("role", "")) ? payload : null;
    }

    @Override
    String ask(JqValue node) {
        var message = message(node, "user");
        if (message == null) return null;
        var kinds = message.path("internal_chat_message_metadata_passthrough").path("content_item_kinds");
        if (kinds.isArray()) {
            // The person's text, perhaps beside what they attached (user.image), never Codex's own context
            boolean text = false;
            for (var kind : kinds.asList()) {
                var k = kind.isString() ? kind.asText() : "";
                if (!k.startsWith("user.")) return null;
                text |= k.equals("user.text");
            }
            if (!text) return null;
        }
        var text = blocks(message.path("content"), "input_text");
        if (text == null) return null;
        var lead = text.strip();
        return lead.startsWith("<") || lead.startsWith("# AGENTS.md instructions") ? null : text;
    }

    @Override
    String close(JqValue node) {
        var message = message(node, "assistant");
        return message == null ? null : blocks(message.path("content"), "output_text");
    }

    @Override
    String cwd(JqValue node) {
        // Only the session's first line and its turn contexts say, and neither is read
        return null;
    }
}
