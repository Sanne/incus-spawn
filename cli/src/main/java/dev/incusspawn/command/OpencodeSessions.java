package dev.incusspawn.command;

import dev.incusspawn.command.AgentSessions.Session;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * opencode's sessions in a guest (#2241): the rows of {@code ~/.local/share/opencode/opencode.db},
 * read with {@code sqlite3 -readonly}, which {@code OpencodeSetup} installs (verified with opencode
 * 1.19.5). A session is a row of {@code session} with no {@code parent_id}, archived or not; one
 * with a parent is a subagent's. Its messages are rows of {@code message}, their text the
 * {@code text} rows of {@code part}.
 *
 * <p>Unlike a transcript, the database is read by fields, so the query itself selects: the first
 * ask is the text of the first {@code user} message with any, the closing message that of the
 * last {@code assistant} message with any that is not a compaction's summary, and a part opencode
 * made up ({@code synthetic}) or set aside ({@code ignored}) is neither. Each value is cut in the
 * query to one code point beyond what the overview keeps and sent in hex, so no text can frame
 * the output.
 *
 * <p>When the database is there but cannot be read (no {@code sqlite3}, a schema this query does
 * not know, a damaged file), or only the JSON {@code storage} of opencode before its database is,
 * the answer is {@code unread}: there are sessions, and they are named as not read.
 */
public final class OpencodeSessions implements AgentSessions.GuestReader {

    static final String AGENT = "opencode";
    public static final String HEADER = "isx-sessions opencode 1";

    private static final String TEXT_PART = "json_extract(%1$s.data, '$.type') = 'text'"
            + " AND IFNULL(json_extract(%1$s.data, '$.synthetic'), 0) = 0"
            + " AND IFNULL(json_extract(%1$s.data, '$.ignored'), 0) = 0"
            + " AND length(json_extract(%1$s.data, '$.text')) > 0";

    /** The text of the session's first or last message of {@code role} that has some, cut to {@code max}. */
    private static String message(String role, String order, String also, int max) {
        // The parts in order from a sorted subquery, not group_concat's own ORDER BY, which needs SQLite 3.44
        return ("hex(substr((SELECT group_concat(t, char(10)) FROM (SELECT json_extract(p.data, '$.text') t FROM part p"
                + " WHERE " + TEXT_PART.formatted("p") + " AND p.message_id = (SELECT m.id FROM message m"
                + " WHERE m.session_id = s.id AND json_extract(m.data, '$.role') = '%s'%s"
                + " AND EXISTS (SELECT 1 FROM part q WHERE q.message_id = m.id AND " + TEXT_PART.formatted("q") + ")"
                + " ORDER BY m.time_created %s, m.id %s LIMIT 1) ORDER BY p.id)), 1, %d))").formatted(role, also, order, order, max);
    }

    static final String QUERY = "BEGIN; SELECT 'total ' || count(*) FROM session WHERE parent_id IS NULL;"
            + " WITH s AS (SELECT id, directory, time_updated FROM session WHERE parent_id IS NULL"
            + " ORDER BY time_updated DESC, id DESC LIMIT " + JsonlSessions.MAX_SESSIONS + ")"
            + " SELECT line FROM ("
            + "SELECT time_updated t, id i, 0 k, 'session ' || (time_updated / 1000) || ' ' || hex(substr(id, 1, 200))"
            + " || ' ' || hex(substr(directory, 1, 1000)) line FROM s"
            + " UNION ALL SELECT time_updated, id, 1, 'ask ' || " + message("user", "ASC", "", AgentSessions.MAX_ASK + 1) + " FROM s"
            + " UNION ALL SELECT time_updated, id, 2, 'close ' || "
            + message("assistant", "DESC", " AND IFNULL(json_extract(m.data, '$.summary'), 0) != 1", AgentSessions.MAX_CLOSING + 1)
            + " FROM s) ORDER BY t DESC, i DESC, k; COMMIT;";

    static final String SCRIPT = """
            echo '%s'
            o=${XDG_DATA_HOME:-$HOME/.local/share}/opencode
            if [ -f "$o/opencode.db" ]; then
              if command -v sqlite3 >/dev/null 2>&1 && r=$(sqlite3 -readonly -batch -noheader -list -cmd '.timeout 2000' "$o/opencode.db" 2>/dev/null <<'ISX_SQL'
            %s
            ISX_SQL
              ); then
                printf '%%s\\n' "$r"
              else
                echo 'total 0'
                echo unread
              fi
            elif [ -n "$(ls -A "$o/storage" 2>/dev/null)" ]; then
              echo 'total 0'
              echo unread
            else
              echo 'total 0'
            fi
            echo end
            """.formatted(HEADER, QUERY);

    @Override
    public String agent() {
        return AGENT;
    }

    @Override
    public String header() {
        return HEADER;
    }

    @Override
    public String script() {
        return SCRIPT;
    }

    private static final Pattern SESSION = Pattern.compile("session (\\d{1,12}) ([0-9A-F]{2,1600}) ([0-9A-F]{0,8000})");
    private static final Pattern LINE = Pattern.compile("(ask|close) ([0-9A-F]{0,200000})");

    @Override
    public AgentSessions parse(List<String> section) {
        if (section.size() < 3 || !section.getFirst().equals(HEADER) || !section.getLast().equals("end")) {
            return AgentSessions.unknown("its answer was cut short");
        }
        var total = JsonlSessions.TOTAL.matcher(section.get(1));
        if (!total.matches()) return AgentSessions.unknown("its answer was not the script's");
        var body = section.subList(2, section.size() - 1);
        if (body.equals(List.of("unread"))) {
            return new AgentSessions(AgentSessions.State.READ, 0, List.of(), List.of(AGENT), null);
        }
        var sessions = new ArrayList<Session>();
        // Each session is exactly three lines: the session, its ask, its closing message
        if (body.size() % 3 != 0) return AgentSessions.unknown("its answer was not the script's");
        for (int i = 0; i < body.size(); i += 3) {
            var s = SESSION.matcher(body.get(i));
            var ask = LINE.matcher(body.get(i + 1));
            var close = LINE.matcher(body.get(i + 2));
            if (!s.matches() || !ask.matches() || !ask.group(1).equals("ask") || !close.matches() || !close.group(1).equals("close")
                    || odd(s.group(2), s.group(3), ask.group(2), close.group(2))) {
                return AgentSessions.unknown("its answer was not the script's");
            }
            var closing = AgentSessions.text(decode(close.group(2)), AgentSessions.MAX_CLOSING);
            sessions.add(new Session(AGENT, decode(s.group(2)), Instant.ofEpochSecond(Long.parseLong(s.group(1))),
                    JsonlSessions.bounded(decode(s.group(3))), AgentSessions.text(decode(ask.group(2)), AgentSessions.MAX_ASK),
                    closing, true));
        }
        int count = Integer.parseInt(total.group(1));
        if (sessions.size() > Math.min(count, JsonlSessions.MAX_SESSIONS)) return AgentSessions.unknown("its answer was not the script's");
        return new AgentSessions(AgentSessions.State.READ, count, List.copyOf(sessions), List.of(), null);
    }

    /** Hex that is not whole bytes is not what the query writes. */
    private static boolean odd(String... hex) {
        for (var h : hex) if (h.length() % 2 != 0) return true;
        return false;
    }

    private static String decode(String hex) {
        if (hex.isEmpty()) return null;
        return new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
    }
}
