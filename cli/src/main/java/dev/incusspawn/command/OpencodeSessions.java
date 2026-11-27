package dev.incusspawn.command;

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
 * made up ({@code synthetic}) or set aside ({@code ignored}) is neither.
 *
 * <p>When only the JSON {@code storage} of opencode before its database is there, the answer is
 * {@code unread}, as for a database that cannot be read ({@link SqliteSessions}).
 */
public final class OpencodeSessions extends SqliteSessions {

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

    OpencodeSessions() {
        super(AGENT, HEADER, "${XDG_DATA_HOME:-$HOME/.local/share}/opencode/opencode.db", QUERY, """
                echo 'total 0'
                [ -z "$(ls -A "${db%/*}/storage" 2>/dev/null)" ] || echo unread
                """);
    }
}
