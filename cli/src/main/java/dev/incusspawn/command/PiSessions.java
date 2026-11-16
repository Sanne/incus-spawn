package dev.incusspawn.command;

import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;

/**
 * Pi's sessions in a guest (#2241): the files it keeps under
 * {@code ~/.pi/agent/sessions/<directory>/<time>_<id>.jsonl}, read as {@link JsonlSessions} reads
 * them (verified with Pi 1.5.2). The first line is the session's header, which names where it
 * ran; every message is a {@code message} line whose {@code message} has a {@code role}.
 *
 * <p>The closing message is the last {@code assistant} one with a {@code text} block, so one that
 * ended in an error or only called tools is passed over. The ask is the first {@code user}
 * message with text.
 */
public final class PiSessions extends JsonlSessions {

    static final String AGENT = "pi";
    public static final String HEADER = "isx-sessions pi 1";

    PiSessions() {
        super(new Layout(AGENT, HEADER, "${PI_CODING_AGENT_DIR:-$HOME/.pi/agent}/sessions", 2, "*.jsonl", null,
                List.of("\"type\":\"message\"", "\"role\":\"user\""), List.of(),
                List.of("\"type\":\"message\"", "\"role\":\"assistant\"", "\"type\":\"text\""), true, true));
    }

    private static JqValue message(JqValue node, String role) {
        if (!"message".equals(node.pathText("type", ""))) return null;
        var message = node.path("message");
        return role.equals(message.pathText("role", "")) ? message : null;
    }

    @Override
    String ask(JqValue node) {
        var message = message(node, "user");
        if (message == null) return null;
        var content = message.path("content");
        return content.isString() ? content.asText() : blocks(content, "text");
    }

    @Override
    String close(JqValue node) {
        var message = message(node, "assistant");
        return message == null ? null : blocks(message.path("content"), "text");
    }

    @Override
    String cwd(JqValue node) {
        return "session".equals(node.pathText("type", "")) ? node.pathText("cwd", "") : null;
    }
}
