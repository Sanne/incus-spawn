package dev.incusspawn.command;

import java.util.List;

/**
 * The sessions of a coding agent that the overview finds but does not read (#2290): Gemini CLI's
 * chats from before JSON Lines, files directly in Copilot's {@code session-state}, and Antigravity
 * CLI's conversations that have no transcript. Their readers ({@link GeminiSessions},
 * {@link CopilotSessions}, {@link AntigravitySessions}) do not know them, so when they are there
 * the agent is named among {@link AgentSessions#otherAgents} as not read, and an instance it worked
 * in never reads as {@code untouched}.
 *
 * <p>Each check looks for session files alone, never for the agent's directory, which isx fills at
 * build time with settings, extensions and policies.
 */
public final class UnreadSessions implements AgentSessions.GuestReader {

    /**
     * Gemini CLI's chats saved whole, {@code ~/.gemini/tmp/<project>/chats/session-*.json}, as it
     * kept them before {@code .jsonl} ({@code @google/gemini-cli-core} 0.68.0 still lists both).
     */
    static final UnreadSessions GEMINI = new UnreadSessions("gemini-cli", """
            g=${GEMINI_CLI_HOME:-$HOME}/.gemini/tmp
            [ -n "$(find "$g"/*/chats -mindepth 1 -maxdepth 1 -name 'session-*.json' -type f -print -quit 2>/dev/null)" ] && echo unread
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

    /**
     * A file directly in Copilot's {@code ~/.copilot/session-state}, where {@code @github/copilot}
     * 1.0.114 keeps a directory per session and nothing else: a layout {@link CopilotSessions}
     * does not know.
     */
    static final UnreadSessions COPILOT = new UnreadSessions("copilot", """
            [ -n "$(find "${COPILOT_HOME:-$HOME/.copilot}/session-state" -mindepth 1 -maxdepth 1 -type f -print -quit 2>/dev/null)" ] && echo unread
            """);

    static final List<UnreadSessions> ALL = List.of(GEMINI, ANTIGRAVITY, COPILOT);

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
