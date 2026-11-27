package dev.incusspawn.command;

import dev.incusspawn.command.AgentSessions.Session;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * An agent that keeps its sessions in a SQLite database: opencode ({@link OpencodeSessions}) and
 * Bob ({@link BobSessions}). The database is read with {@code sqlite3 -readonly}, which each
 * agent's setup installs, by one query that selects what is shown: {@code total <n>}, then per
 * session, newest first, {@code session <seconds> <hex id> <hex directory>}, {@code ask <hex>} and
 * {@code close <hex>}. Each value is cut in the query to one code point beyond what the overview
 * keeps and sent in hex, so no text can frame the output.
 *
 * <p>When the database is there but cannot be read (no {@code sqlite3}, a schema the query does not
 * know, a damaged file), the answer is {@code unread}: there are sessions, and they are named as
 * not read.
 */
abstract class SqliteSessions implements AgentSessions.GuestReader {

    private final String agent;
    private final String header;
    private final String script;

    /**
     * {@code db} is a shell expression for the database's path, {@code query} the SQL that writes
     * the lines above, and {@code otherwise} the shell that answers when there is no database:
     * {@code echo 'total 0'}, then {@code echo unread} for sessions kept another way.
     */
    SqliteSessions(String agent, String header, String db, String query, String otherwise) {
        this.agent = agent;
        this.header = header;
        this.script = """
                echo '%s'
                db=%s
                if [ -f "$db" ]; then
                  if command -v sqlite3 >/dev/null 2>&1 && r=$(sqlite3 -readonly -batch -noheader -list -cmd '.timeout 2000' "$db" 2>/dev/null <<'ISX_SQL'
                %s
                ISX_SQL
                  ); then
                    printf '%%s\\n' "$r"
                  else
                    echo 'total 0'
                    echo unread
                  fi
                else
                %sfi
                echo end
                """.formatted(header, db, query, otherwise.indent(2));
    }

    @Override
    public String agent() {
        return agent;
    }

    @Override
    public String header() {
        return header;
    }

    @Override
    public String script() {
        return script;
    }

    private static final Pattern SESSION = Pattern.compile("session (\\d{1,12}) ([0-9A-F]{2,1600}) ([0-9A-F]{0,8000})");
    private static final Pattern LINE = Pattern.compile("(ask|close) ([0-9A-F]{0,200000})");

    @Override
    public AgentSessions parse(List<String> section) {
        if (section.size() < 3 || !section.getFirst().equals(header) || !section.getLast().equals("end")) {
            return AgentSessions.unknown("its answer was cut short");
        }
        var total = JsonlSessions.TOTAL.matcher(section.get(1));
        if (!total.matches()) return AgentSessions.unknown("its answer was not the script's");
        var body = section.subList(2, section.size() - 1);
        if (body.equals(List.of("unread"))) {
            return new AgentSessions(AgentSessions.State.READ, 0, List.of(), List.of(agent), null);
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
            sessions.add(new Session(agent, decode(s.group(2)), Instant.ofEpochSecond(Long.parseLong(s.group(1))),
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
