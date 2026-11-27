package dev.incusspawn.command;

/**
 * Bob's sessions in a guest (#2363): its tasks, rows of {@code tasks} in {@code ~/.bob/db/bob.db},
 * read as {@link SqliteSessions} reads a database, with the {@code sqlite3} that {@code BobSetup}
 * installs. What the tables hold was read from the database {@code @ibm/bob} 2.1.0 wrote.
 *
 * <p>A session is a task with no {@code parent_id}, archived or not; one with a parent is a
 * subtask's or a subagent's. Where it ran is its {@code directory}, which Bob 2.1.0 leaves empty,
 * else the {@code workspace} of its {@code env}, else its {@code project_id} without the
 * {@code file:} scheme. The ask is its {@code first_message}, else the first {@code user} row of
 * {@code messages}.
 *
 * <p>Each row of {@code messages} is one message as JSON, with a {@code role} and a
 * {@code content}. The closing message is the last {@code assistant} row whose content is text.
 * Bob writes a task's rows at once, all with one {@code created_at}, so their order is the order
 * they were written in, the {@code rowid}.
 */
public final class BobSessions extends SqliteSessions {

    static final String AGENT = "bob";
    /** Not {@code isx-sessions bob 1}, the header the check that found Bob's tasks before #2363 printed. */
    public static final String HEADER = "isx-sessions bob-tasks 1";

    /** The text of the task's first or last message of {@code role} whose content is text, or null. */
    private static String message(String role, String order) {
        // Nested, so a row that is not JSON never reaches json_type, which would fail the whole read
        var text = "CASE WHEN json_valid(m.data) THEN CASE WHEN json_type(m.data, '$.content') = 'text'"
                + " THEN json_extract(m.data, '$.content') END END";
        return ("(SELECT " + text + " FROM messages m WHERE m.task_id = s.id AND m.role = '%s'"
                + " AND length(trim(IFNULL(" + text + ", ''))) > 0 ORDER BY m.created_at %s, m.rowid %s LIMIT 1)")
                .formatted(role, order, order);
    }

    static final String QUERY = "BEGIN; SELECT 'total ' || count(*) FROM tasks WHERE parent_id IS NULL;"
            + " WITH s AS (SELECT id, updated_at, first_message, COALESCE(NULLIF(directory, ''),"
            + " CASE WHEN json_valid(env) THEN json_extract(env, '$.workspace') END,"
            + " CASE WHEN project_id LIKE 'file:%' THEN substr(project_id, 6) END) dir"
            + " FROM tasks WHERE parent_id IS NULL ORDER BY updated_at DESC, id DESC LIMIT " + JsonlSessions.MAX_SESSIONS + ")"
            + " SELECT line FROM ("
            + "SELECT updated_at t, id i, 0 k, 'session ' || (updated_at / 1000) || ' ' || hex(substr(id, 1, 200))"
            + " || ' ' || hex(substr(IFNULL(dir, ''), 1, 1000)) line FROM s"
            + " UNION ALL SELECT updated_at, id, 1, 'ask ' || hex(substr(COALESCE(NULLIF(first_message, ''), "
            + message("user", "ASC") + "), 1, " + (AgentSessions.MAX_ASK + 1) + ")) FROM s"
            + " UNION ALL SELECT updated_at, id, 2, 'close ' || hex(substr(" + message("assistant", "DESC")
            + ", 1, " + (AgentSessions.MAX_CLOSING + 1) + ")) FROM s) ORDER BY t DESC, i DESC, k; COMMIT;";

    BobSessions() {
        super(AGENT, HEADER, "$HOME/.bob/db/bob.db", QUERY, "echo 'total 0'\n");
    }
}
