package dev.incusspawn.command;

import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;

/**
 * GitHub Copilot CLI's sessions in a guest (#2363): the event log it keeps of each, in
 * {@code ~/.copilot/session-state/<id>/events.jsonl}, read as {@link JsonlSessions} reads them,
 * with no {@code sqlite3}. What a line holds was read from the files {@code @github/copilot}
 * 1.0.114 wrote and from the schema it ships, {@code schemas/session-events.schema.json}.
 *
 * <p>Each line is one event, its {@code type} first and its fields in {@code data}. The first is
 * {@code session.start}, whose {@code context.cwd} is where the session ran. An event of a
 * subagent carries an {@code agentId}; the main agent's never does.
 *
 * <p>The ask is the first {@code user.message} the person sent: its {@code content}, the text as
 * shown, not the {@code transformedContent} sent to the model. A message whose {@code source} is
 * another (a skill's, another agent's) or that autopilot added is not the person's. The closing
 * message is the last {@code assistant.message} with text in its {@code content}.
 *
 * <p>A message's {@code transformedContent}, with the files attached, and a report's reasoning,
 * which follows its text, can make a line longer than the bound, so the script drops the first and
 * keeps a report only through its text before cutting the line.
 *
 * <p>A session that ended before its first message has a directory with no {@code events.jsonl}
 * and is no session. A file directly in {@code session-state} is a layout this reader does not
 * know, found by {@link UnreadSessions#COPILOT}, which names the agent as not read.
 */
public final class CopilotSessions extends JsonlSessions {

    static final String AGENT = "copilot";
    /** Not {@code isx-sessions copilot 1}, the header {@link UnreadSessions#COPILOT} prints. */
    public static final String HEADER = "isx-sessions copilot-events 1";

    private static final String SUBAGENT = "\"agentId\":\"";

    CopilotSessions() {
        super(new Layout(AGENT, HEADER, "${COPILOT_HOME:-$HOME/.copilot}/session-state", 2, "events.jsonl", null,
                List.of("\"type\":\"user.message\""), List.of(SUBAGENT),
                List.of("\"type\":\"assistant.message\"", "\"content\":\""),
                // A message with no text, anchored on the field after it so a tool's empty argument is not taken for it
                List.of("\"content\":\"\",\"toolRequests\"", SUBAGENT),
                true, false, "*/events.jsonl"),
                // The text sent to the model, with the files attached, is not what the person typed
                "s/,\"transformedContent\":" + SED_STRING + "//; s/\"transformedContent\":" + SED_STRING + ",//",
                // A message's reasoning, often longer than the bound, comes after its text: keep what precedes it
                "s/^(\\{\"type\":\"assistant\\.message\",\"data\":\\{(\"[A-Za-z]+\":(" + SED_STRING
                        + "|[0-9A-Za-z.+-]+),)*\"content\":" + SED_STRING + ")(,.*)?$/\\1}}/");
    }

    private static JqValue data(JqValue node, String type) {
        if (!type.equals(node.pathText("type", "")) || node.has("agentId")) return null;
        var data = node.path("data");
        return data.isObject() ? data : null;
    }

    @Override
    String ask(JqValue node) {
        var data = data(node, "user.message");
        if (data == null) return null;
        if (data.has("source") && !"user".equals(data.pathText("source", ""))) return null;
        if (data.path("isAutopilotContinuation").asBoolean(false)) return null;
        var content = data.path("content");
        return content.isString() ? content.asText() : null;
    }

    @Override
    String close(JqValue node) {
        var data = data(node, "assistant.message");
        if (data == null) return null;
        var content = data.path("content");
        return content.isString() ? content.asText() : null;
    }

    @Override
    String cwd(JqValue node) {
        var data = data(node, "session.start");
        return data == null ? null : data.path("context").pathText("cwd", "");
    }
}
