package dev.incusspawn.command;

import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;

/**
 * Antigravity CLI's sessions in a guest (#2478): the transcript {@code agy} keeps of each
 * conversation, {@code ~/.gemini/antigravity-cli/brain/<id>/.system_generated/logs/transcript.jsonl},
 * read as {@link JsonlSessions} reads them, with no {@code sqlite3}. Where it keeps them and what
 * a line holds was read from the files {@code agy} 1.5.13 wrote, and from the reader of its own
 * swarm sidecar, which reads the same file. {@code transcript_full.jsonl} and the chunks beside it
 * are not read.
 *
 * <p>Each line is one step: {@code step_index}, {@code source}, {@code type}, {@code status},
 * {@code created_at} and {@code content}, compact JSON. The ask is the first {@code USER_INPUT}
 * step the person wrote ({@code USER_EXPLICIT}), whose content wraps their words in
 * {@code <USER_REQUEST>} before context agy adds itself (the time, a changed setting). The closing
 * message is the last {@code PLANNER_RESPONSE} step with text in its content, the reply the model
 * showed; its thinking and tool calls are fields of their own. A step names no directory.
 *
 * <p>A conversation in {@code conversations/<id>.db} with no transcript is still found, by
 * {@link UnreadSessions#ANTIGRAVITY}, which names the agent as not read.
 */
public final class AntigravitySessions extends JsonlSessions {

    static final String AGENT = "antigravity";
    /** Not {@code isx-sessions antigravity 1}, the header {@link UnreadSessions#ANTIGRAVITY} prints. */
    public static final String HEADER = "isx-sessions antigravity-transcripts 1";

    private static final String OPEN = "<USER_REQUEST>";
    private static final String CLOSE = "</USER_REQUEST>";

    AntigravitySessions() {
        super(new Layout(AGENT, HEADER, "$HOME/.gemini/antigravity-cli/brain", 4, "transcript.jsonl", null,
                List.of("\"type\":\"USER_INPUT\""), List.of(), List.of("\"type\":\"PLANNER_RESPONSE\"", "\"content\":\""), false,
                // brain/ also holds a conversation's artifacts and swarm files: they are no session
                false));
    }

    @Override
    String ask(JqValue node) {
        if (!"USER_INPUT".equals(node.pathText("type", "")) || !"USER_EXPLICIT".equals(node.pathText("source", ""))) return null;
        var content = node.path("content");
        if (!content.isString()) return null;
        var text = content.asText();
        int open = text.indexOf(OPEN);
        if (open < 0) return text;
        int close = text.indexOf(CLOSE, open);
        // Cut at the bound before its end: what was asked is the rest of the line
        return close < 0 ? text.substring(open + OPEN.length()) : text.substring(open + OPEN.length(), close);
    }

    @Override
    String close(JqValue node) {
        if (!"PLANNER_RESPONSE".equals(node.pathText("type", ""))) return null;
        var content = node.path("content");
        return content.isString() ? content.asText() : null;
    }

    @Override
    String cwd(JqValue node) {
        return null;
    }
}
