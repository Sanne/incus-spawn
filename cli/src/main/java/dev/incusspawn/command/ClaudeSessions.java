package dev.incusspawn.command;

import dev.incusspawn.command.AgentSessions.Session;
import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Claude Code's sessions in a guest (#2241): the transcripts it keeps under
 * {@code ~/.claude/projects/<directory>/<session>.jsonl}, one JSON object per line. The script
 * reads only the newest {@link #MAX_SESSIONS}, and from each only a few lines: the first lines of
 * the person's among its first {@link #HEAD_LINES}, and the last lines of the agent's that hold
 * text among its last {@link #TAIL_LINES}, read from the end with {@code tac}, so a long
 * transcript is never read whole. Each line is cut to a bound and sent
 * in base64, so no transcript text can frame the output.
 *
 * <p>The script only selects candidate lines by substring; which line is what is decided here, on
 * the parsed JSON's own fields. The closing message is the last top-level {@code assistant} line,
 * not a subagent's ({@code isSidechain}), with a {@code text} block. When the newest candidate
 * does not parse (cut at the bound, or not JSON), the closing message is unreadable: an older one
 * is never shown in its place.
 */
public final class ClaudeSessions implements AgentSessions.GuestReader {

    static final String AGENT = "claude";
    public static final String HEADER = "isx-sessions claude 1";
    /** The newest sessions read; the count of all of them is still given. */
    static final int MAX_SESSIONS = 8;
    /** The bytes kept of one of the person's lines, and of one of the agent's. */
    static final int ASK_BYTES = 8 * 1024;
    static final int CLOSE_BYTES = 24 * 1024;
    /** The candidate lines sent per session, from each end. */
    static final int ASKS = 3;
    static final int CLOSES = 2;
    /**
     * The lines looked through from each end: the person's first message is near the start, and
     * the closing message at the end, so a transcript of any length costs at most these.
     */
    static final int HEAD_LINES = 500;
    static final int TAIL_LINES = 5000;

    /**
     * {@code total <n>} or {@code total x}, then per session {@code session <mtime> <base64 file>}
     * with its {@code ask <base64 line>} and {@code close <base64 line>} candidates, then
     * {@code other <dir>} for another agent's sessions that are not read here, then {@code end}.
     */
    static final String SCRIPT = """
            echo '%s'
            d=${CLAUDE_CONFIG_DIR:-$HOME/.claude}/projects
            if [ -d "$d" ]; then
              if l=$(find "$d" -mindepth 2 -maxdepth 2 -name '*.jsonl' ! -name 'agent-*.jsonl' -type f -printf '%%T@ %%P\\n' 2>/dev/null); then
                echo "total $(printf '%%s' "$l" | grep -c '^')"
                printf '%%s\\n' "$l" | sort -rn | head -n %d | while IFS= read -r e; do
                  [ -n "$e" ] || continue
                  f=$d/${e#* }
                  echo "session ${e%%%% *} $(printf '%%s' "${e#* }" | base64 -w0)"
                  head -n %d "$f" 2>/dev/null | grep -F '"type":"user"' | grep -v -F '"tool_result"' | head -n %d | cut -b1-%d \\
                    | while IFS= read -r x; do echo "ask $(printf '%%s' "$x" | base64 -w0)"; done
                  tac "$f" 2>/dev/null | head -n %d | grep -F '"type":"assistant"' | grep -F '"type":"text"' | head -n %d | cut -b1-%d \\
                    | while IFS= read -r x; do echo "close $(printf '%%s' "$x" | base64 -w0)"; done
                done
              else
                echo 'total x'
              fi
            else
              echo 'total 0'
            fi
            for o in .codex/sessions .pi/agent/sessions .local/share/opencode/opencode.db .local/share/opencode/storage; do
              [ -n "$(ls -A "$HOME/$o" 2>/dev/null)" ] && echo "other $o"
            done
            echo end
            """.formatted(HEADER, MAX_SESSIONS, HEAD_LINES, ASKS, ASK_BYTES, TAIL_LINES, CLOSES, CLOSE_BYTES);

    @Override
    public String header() {
        return HEADER;
    }

    @Override
    public String script() {
        return SCRIPT;
    }

    private static final Pattern TOTAL = Pattern.compile("total (\\d{1,9})");
    private static final Pattern SESSION = Pattern.compile("session (\\d{1,12})(?:\\.\\d+)? ([A-Za-z0-9+/=]{1,8192})");
    private static final Pattern LINE = Pattern.compile("(ask|close) ([A-Za-z0-9+/=]{0,65536})");
    private static final Pattern OTHER = Pattern.compile("other (\\.codex/sessions|\\.pi/agent/sessions|\\.local/share/opencode/(?:opencode\\.db|storage))");

    @Override
    public AgentSessions parse(List<String> section) {
        if (section.size() < 3 || !section.getFirst().equals(HEADER) || !section.getLast().equals("end")) {
            return AgentSessions.unknown("its answer was cut short");
        }
        var total = TOTAL.matcher(section.get(1));
        if (section.get(1).equals("total x")) return AgentSessions.unknown("the sessions could not be listed");
        if (!total.matches()) return AgentSessions.unknown("its answer was not the script's");
        var sessions = new ArrayList<Session>();
        var others = new ArrayList<String>();
        Building current = null;
        for (var line : section.subList(2, section.size() - 1)) {
            var s = SESSION.matcher(line);
            var l = LINE.matcher(line);
            var o = OTHER.matcher(line);
            if (s.matches()) {
                if (current != null) sessions.add(current.session());
                current = new Building(Instant.ofEpochSecond(Long.parseLong(s.group(1))), decode(s.group(2)));
            } else if (l.matches() && current != null) {
                var json = decode(l.group(2));
                if (json == null) return AgentSessions.unknown("its answer was not the script's");
                if (l.group(1).equals("ask")) current.ask(json);
                else current.close(json);
            } else if (o.matches()) {
                var agent = switch (o.group(1)) {
                    case ".codex/sessions" -> "codex";
                    case ".pi/agent/sessions" -> "pi";
                    default -> "opencode";
                };
                if (!others.contains(agent)) others.add(agent);
            } else {
                return AgentSessions.unknown("its answer was not the script's");
            }
        }
        if (current != null) sessions.add(current.session());
        int count = Integer.parseInt(total.group(1));
        if (sessions.size() > Math.min(count, MAX_SESSIONS)) return AgentSessions.unknown("its answer was not the script's");
        return new AgentSessions(AgentSessions.State.READ, count, List.copyOf(sessions), List.copyOf(others), null);
    }

    private static String decode(String base64) {
        try {
            return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** One session as its lines arrive: the first ask that is the person's, the first closing candidate that is the agent's. */
    private static final class Building {
        final Instant modified;
        final String file;
        String cwd;
        String ask;
        boolean askSettled;
        String closing;
        boolean closingSettled;
        boolean closingReadable = true;

        Building(Instant modified, String file) {
            this.modified = modified;
            this.file = file;
        }

        void ask(String line) {
            if (askSettled) return;
            var node = parse(line);
            if (node == null) {
                // The first candidate is cut or not JSON: what was asked first is not known, and a later message is not it
                askSettled = true;
                return;
            }
            if (!"user".equals(node.pathText("type", "")) || node.pathBoolean("isSidechain", false)
                    || node.pathBoolean("isMeta", false) || node.pathBoolean("isCompactSummary", false)) return;
            var text = text(node.path("message").path("content"), false);
            // Slash commands and their output come wrapped in tags; the person's words do not
            if (text == null || text.isBlank() || text.strip().startsWith("<")) return;
            ask = AgentSessions.text(text, AgentSessions.MAX_ASK);
            askSettled = true;
            if (cwd == null) cwd = cwd(node);
        }

        void close(String line) {
            if (closingSettled) return;
            var node = parse(line);
            if (node == null) {
                // The newest candidate is cut or not JSON: what it said is not known, and an older message is not it
                closingReadable = false;
                closingSettled = true;
                return;
            }
            if (!"assistant".equals(node.pathText("type", "")) || node.pathBoolean("isSidechain", false)) return;
            var text = text(node.path("message").path("content"), true);
            if (text == null || text.isBlank()) return;
            closing = AgentSessions.text(text, AgentSessions.MAX_CLOSING);
            closingSettled = true;
            var at = cwd(node);
            if (at != null) cwd = at;
        }

        Session session() {
            return new Session(AGENT, file, modified, cwd, ask, closing, closingReadable);
        }

        private static JqValue parse(String line) {
            try {
                var node = Jjq.parseForeign(line, "a Claude Code session line");
                return node.isObject() ? node : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private static String cwd(JqValue node) {
            var cwd = node.pathText("cwd", "");
            return cwd.isBlank() ? null : AgentSessions.text(cwd, 200);
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
}
