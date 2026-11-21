package dev.incusspawn.command;

import java.util.List;

/**
 * The sessions of a coding agent that the overview does not read, only finds (#2290): Gemini CLI,
 * GitHub Copilot CLI and Bob, and Antigravity CLI's conversations that have no transcript. When
 * an agent's session files are there, it is named among {@link AgentSessions#otherAgents} as not
 * read, so an instance it worked in never reads as {@code untouched}. Its closing messages are not
 * shown: a reader is written from session files the agent itself wrote, as the others were, and
 * none were at hand for Gemini CLI, Copilot and Bob.
 *
 * <p>Each check looks for session files alone, never for the agent's directory, which isx fills at
 * build time with settings, extensions and policies. Where each agent keeps them was read from the
 * code it ships: {@code @google/gemini-cli-core} 0.68.0, {@code @github/copilot} 1.0.114 and
 * {@code @ibm/bob} 2.1.0.
 */
public final class UnreadSessions implements AgentSessions.GuestReader {

    /**
     * Gemini CLI's chats, {@code ~/.gemini/tmp/<project>/chats/session-*.jsonl} ({@code .json} before),
     * a subagent's one directory deeper.
     */
    static final UnreadSessions GEMINI = new UnreadSessions("gemini-cli", """
            g=${GEMINI_CLI_HOME:-$HOME}/.gemini/tmp
            [ -n "$(find "$g"/*/chats -mindepth 1 -maxdepth 2 -name 'session-*.json*' -type f -print -quit 2>/dev/null)" ] && echo unread
            """);

    /**
     * Antigravity CLI's conversations, {@code ~/.gemini/antigravity-cli/conversations/<id>.db}, one
     * per conversation, beside the build's settings in that directory but never Gemini CLI's (#2366).
     * {@link AntigravitySessions} reads the transcript of each in {@code brain/<id>}, so only a
     * conversation with none is found here (#2478). Where it keeps them was read from the files
     * {@code agy} 1.5.13 wrote.
     */
    static final UnreadSessions ANTIGRAVITY = new UnreadSessions("antigravity", """
            a=$HOME/.gemini/antigravity-cli
            for f in "$a"/conversations/*.db; do
              [ -f "$f" ] || continue
              i=${f##*/}
              [ -f "$a/brain/${i%.db}/.system_generated/logs/transcript.jsonl" ] || { echo unread; break; }
            done
            """);

    /** Copilot's {@code ~/.copilot/session-state}, one entry per session; an XDG location is migrated back there. */
    static final UnreadSessions COPILOT = new UnreadSessions("copilot", """
            [ -n "$(ls -A "${COPILOT_HOME:-$HOME/.copilot}/session-state" 2>/dev/null)" ] && echo unread
            """);

    /**
     * Bob's tasks, rows of {@code tasks} in {@code ~/.bob/db/bob.db}. A database {@code sqlite3}
     * cannot count, or none installed, names Bob as not read too.
     */
    static final UnreadSessions BOB = new UnreadSessions("bob", """
            b=$HOME/.bob/db/bob.db
            if [ -f "$b" ]; then
              n=$(sqlite3 -readonly -batch -noheader -cmd '.timeout 2000' "$b" 'SELECT count(*) FROM tasks' 2>/dev/null) || n=x
              [ "$n" = 0 ] || echo unread
            fi
            """);

    static final List<UnreadSessions> ALL = List.of(GEMINI, ANTIGRAVITY, COPILOT, BOB);

    private final String agent;
    private final String header;
    private final String script;

    private UnreadSessions(String agent, String check) {
        this.agent = agent;
        this.header = "isx-sessions " + agent + " 1";
        this.script = "echo '" + header + "'\n" + check + "echo end\n";
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

    /** It only finds sessions, so it never counts as a reader that answered. */
    @Override
    public boolean readsSessions() {
        return false;
    }

    /** Its section is a few short lines. */
    @Override
    public int maxOutput() {
        return 256;
    }

    @Override
    public AgentSessions parse(List<String> section) {
        if (section.size() < 2 || !section.getFirst().equals(header) || !section.getLast().equals("end")) {
            return AgentSessions.unknown("its answer was cut short");
        }
        var body = section.subList(1, section.size() - 1);
        if (body.isEmpty()) return new AgentSessions(AgentSessions.State.READ, 0, List.of(), List.of(), null);
        if (body.equals(List.of("unread"))) return new AgentSessions(AgentSessions.State.READ, 0, List.of(), List.of(agent), null);
        return AgentSessions.unknown("its answer was not the script's");
    }
}
