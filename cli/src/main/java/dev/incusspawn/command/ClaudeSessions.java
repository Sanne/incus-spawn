package dev.incusspawn.command;

import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Claude Code's sessions in a guest (#2241): the transcripts it keeps under
 * {@code ~/.claude/projects/<directory>/<session>.jsonl}, one JSON object per line, read as
 * {@link JsonlSessions} reads them. A subagent's {@code agent-*.jsonl} is not a session.
 *
 * <p>The closing message is the last top-level {@code assistant} line, not a subagent's
 * ({@code isSidechain}), with a {@code text} block. The first ask is the first {@code user} line
 * that is neither a subagent's, nor meta, nor a compaction summary, nor a tool result, nor a slash
 * command's tags.
 */
public final class ClaudeSessions extends JsonlSessions {

    static final String AGENT = "claude";
    public static final String HEADER = "isx-sessions claude 1";

    ClaudeSessions() {
        super(new Layout(AGENT, HEADER, "${CLAUDE_CONFIG_DIR:-$HOME/.claude}/projects", 2, "*.jsonl", "agent-*.jsonl",
                List.of("\"type\":\"user\""), List.of("\"tool_result\""),
                List.of("\"type\":\"assistant\"", "\"type\":\"text\""), false, false));
    }

    @Override
    String ask(JqValue node) {
        if (!"user".equals(node.pathText("type", "")) || node.pathBoolean("isSidechain", false)
                || node.pathBoolean("isMeta", false) || node.pathBoolean("isCompactSummary", false)) return null;
        var text = text(node.path("message").path("content"), false);
        // Slash commands and their output come wrapped in tags; the person's words do not
        return text == null || text.strip().startsWith("<") ? null : text;
    }

    @Override
    String close(JqValue node) {
        if (!"assistant".equals(node.pathText("type", "")) || node.pathBoolean("isSidechain", false)) return null;
        return text(node.path("message").path("content"), true);
    }

    @Override
    String cwd(JqValue node) {
        return node.pathText("cwd", "");
    }

    /**
     * A message's text: a plain string, or its {@code text} blocks joined. A person's message
     * that holds a tool result is not something they asked ({@code blocksOnly} false allows a
     * string).
     */
    private static String text(JqValue content, boolean blocksOnly) {
        if (content.isString()) return blocksOnly ? null : content.asText();
        if (!content.isArray()) return null;
        var parts = new ArrayList<String>();
        for (var block : content.asList()) {
            var type = block.pathText("type", "");
            if (type.equals("tool_result")) return null;
            if (type.equals("text")) parts.add(block.pathText("text", ""));
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }
}
