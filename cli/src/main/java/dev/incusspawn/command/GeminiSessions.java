package dev.incusspawn.command;

import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemini CLI's sessions in a guest (#2363): the chats it keeps under
 * {@code ~/.gemini/tmp/<project>/chats/session-*.jsonl}, read as {@link JsonlSessions} reads them,
 * with no {@code sqlite3}. A subagent's chats are one directory deeper and are not read. What a
 * line holds was read from the files {@code @google/gemini-cli} 0.68.0 wrote, and from its own
 * reader of them, which skips the same lines.
 *
 * <p>The first line is the session's metadata and names no directory. Every message is a line
 * with an {@code id} and a {@code type}: {@code user} with a list of parts as its {@code content},
 * {@code gemini} with the model's text as a string. A message written again, such as a reply
 * whose tool calls ended, is a new line with the same {@code id}, and the newest line counts.
 *
 * <p>The ask is the first {@code user} message with text that is not the context Gemini CLI sends
 * itself ({@code <session_context>}, {@code <hook_context>}) or a command ({@code /} or
 * {@code ?}), the lines its own session list skips. A tool's answer is a {@code user} message
 * with no text. The closing message is the last {@code gemini} message with text.
 *
 * <p>A file the person names with {@code @} has its text in the message's {@code content}, before
 * what they typed in {@code displayContent}, and a reply's {@code thoughts} come after its text:
 * either can make the line longer than the bound, so the script trims a candidate to the fields
 * read before cutting it. A rewind ({@code $rewindTo}) is not followed, so a reply the person
 * rewound away may still be the one shown.
 *
 * <p>Chats saved whole as {@code .json}, before JSON Lines, are only found, by
 * {@link UnreadSessions#GEMINI}, which names the agent as not read.
 */
public final class GeminiSessions extends JsonlSessions {

    static final String AGENT = "gemini-cli";
    /** Not {@code isx-sessions gemini-cli 1}, the header {@link UnreadSessions#GEMINI} prints. */
    public static final String HEADER = "isx-sessions gemini-cli-chats 1";

    GeminiSessions() {
        super(new Layout(AGENT, HEADER, "${GEMINI_CLI_HOME:-$HOME}/.gemini/tmp", 3, "session-*.jsonl", null,
                List.of("\"type\":\"user\""),
                // Gemini CLI's own context, often longer than the bound: never a candidate that would hide the person's ask
                List.of("\"text\":\"<session_context>", "\"text\":\"<hook_context>"),
                List.of("\"type\":\"gemini\"", "\"content\":\""),
                // A reply that only called tools, whose line holds their output and may be cut
                List.of("\"type\":\"gemini\",\"content\":\"\""), false,
                // tmp/ also holds each project's logs and tool output: they are no session
                false,
                "*/chats/session-*.jsonl"),
                // What the person typed, kept apart from the text of the files they named, which comes first
                "s/^(\\{(\"[A-Za-z]+\":" + SED_STRING + ",)*\"type\":\"user\",).*,\"displayContent\":(\\[(\\{\"text\":" + SED_STRING
                        + "\\},?)+\\])(,.*)?\\}$/\\1\"displayContent\":\\4}/",
                // A reply's thoughts and tool calls, often longer than the bound, come after its text
                "s/^(\\{(\"[A-Za-z]+\":" + SED_STRING + ",)*\"type\":\"gemini\",\"content\":" + SED_STRING + ")(,.*)?$/\\1}/");
    }

    @Override
    String ask(JqValue node) {
        if (!"user".equals(node.pathText("type", ""))) return null;
        // What the person typed, when Gemini CLI sent more (a file's text for an @ reference)
        var shown = text(node.path("displayContent"));
        var text = shown != null ? shown : text(node.path("content"));
        if (text == null) return null;
        var trimmed = text.strip();
        return trimmed.isEmpty() || trimmed.startsWith("/") || trimmed.startsWith("?")
                || trimmed.startsWith("<session_context>") || trimmed.startsWith("<hook_context>") ? null : text;
    }

    @Override
    String close(JqValue node) {
        if (!"gemini".equals(node.pathText("type", ""))) return null;
        var content = node.path("content");
        return content.isString() ? content.asText() : null;
    }

    @Override
    String cwd(JqValue node) {
        return null;
    }

    /** A string, or a list of parts' {@code text} joined; null when there is none. */
    private static String text(JqValue content) {
        if (content.isString()) return content.asText();
        if (!content.isArray()) return null;
        var parts = new ArrayList<String>();
        for (var part : content.asList()) {
            var text = part.path("text");
            if (text.isString()) parts.add(text.asText());
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }
}
