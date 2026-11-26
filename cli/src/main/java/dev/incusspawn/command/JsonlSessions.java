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
 * An agent that keeps each session as a JSON Lines transcript (#2241): Claude Code
 * ({@link ClaudeSessions}), Codex ({@link CodexSessions}), Pi ({@link PiSessions}) and Antigravity CLI
 * ({@link AntigravitySessions}). The script
 * reads only the newest {@link #MAX_SESSIONS}, and from each only a few lines: the first lines of
 * the person's among its first {@link #HEAD_LINES}, and the last lines of the agent's that hold
 * text among its last {@link #TAIL_LINES}, read from the end with {@code tac}, so a long
 * transcript is never read whole. Each line is cut to a bound and sent in base64, so no
 * transcript text can frame the output.
 *
 * <p>The script only selects candidate lines by substring; which line is what is decided here, on
 * the parsed JSON's own fields, by each agent's {@link #ask} and {@link #close}. When the newest
 * candidate does not parse (cut at the bound, or not JSON), the closing message is unreadable: an
 * older one is never shown in its place. The first ask is the same: when the first candidate does
 * not parse, none is shown, never a later message.
 */
abstract class JsonlSessions implements AgentSessions.GuestReader {

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
     * Where one agent keeps its transcripts and which lines may be what: {@code root} is a shell
     * expression for the directory, searched exactly {@code depth} levels down for {@code name}
     * and not {@code notName} (null for none). A candidate ask holds every {@code askHas} and no
     * {@code askLacks}; a closing candidate holds every {@code closeHas}. With {@code meta}, the
     * first line is sent too, for where the session ran ({@link #cwd}). With {@code strangeIsUnread},
     * a directory that holds files but no session this layout knows answers {@code unread}: the
     * agent may keep its sessions another way, so they are named as not read, never as none.
     * With a {@code glob}, a shell pattern under {@code root} that ends in {@code name}, only the
     * files it matches are looked at, not every directory {@code depth} levels down (#2627).
     */
    record Layout(String agent, String header, String root, int depth, String name, String notName,
                  List<String> askHas, List<String> askLacks, List<String> closeHas, boolean meta,
                  boolean strangeIsUnread, String glob) {
        Layout {
            for (var s : concat(List.of(root, name), notName == null ? List.of() : List.of(notName),
                    glob == null ? List.of() : List.of(glob), askHas, askLacks, closeHas)) {
                if (s.indexOf('\'') >= 0 || s.indexOf('\n') >= 0) throw new IllegalArgumentException("not quotable: " + s);
            }
            if (glob != null && !glob.endsWith("/" + name)) throw new IllegalArgumentException("a glob ends in its name: " + glob);
        }

        Layout(String agent, String header, String root, int depth, String name, String notName,
               List<String> askHas, List<String> askLacks, List<String> closeHas, boolean meta,
               boolean strangeIsUnread) {
            this(agent, header, root, depth, name, notName, askHas, askLacks, closeHas, meta, strangeIsUnread, null);
        }

        @SafeVarargs
        private static List<String> concat(List<String>... lists) {
            var all = new ArrayList<String>();
            for (var l : lists) all.addAll(l);
            return all;
        }
    }

    private final Layout layout;
    private final String script;

    JsonlSessions(Layout layout) {
        this.layout = layout;
        this.script = script(layout);
    }

    /**
     * {@code total <n>} or {@code total x}, then per session {@code session <mtime> <base64 file>}
     * with its {@code meta}, {@code ask} and {@code close} candidates, each {@code <base64 line>},
     * then {@code end}.
     */
    private static String script(Layout l) {
        var asks = new StringBuilder();
        for (var has : l.askHas()) asks.append(" | grep -F '").append(has).append('\'');
        for (var lacks : l.askLacks()) asks.append(" | grep -v -F '").append(lacks).append('\'');
        var closes = new StringBuilder();
        for (var has : l.closeHas()) closes.append(" | grep -F '").append(has).append('\'');
        var notName = l.notName() == null ? "" : " ! -name '" + l.notName() + "'";
        // A glob's paths start with ./, so find never takes one for an option; the name drops it
        var list = l.glob() == null
                ? "find \"$d\" -mindepth %d -maxdepth %d -name '%s'%s -type f -printf '%%T@ %%P\\n'"
                        .formatted(l.depth(), l.depth(), l.name(), notName)
                : "cd \"$d\" && set -- ./%s && if [ -e \"$1\" ] || [ -L \"$1\" ]; then find \"$@\" -maxdepth 0 -name '%s'%s -type f -printf '%%T@ %%p\\n'; fi"
                        .formatted(l.glob(), l.name(), notName);
        // No session found where the directory holds files: the agent may keep them another way
        var unread = !l.strangeIsUnread() ? "" : """
                    [ -n "$l" ] || [ -z "$(find "$d" -type f -print -quit 2>/dev/null)" ] || echo unread
                """;
        var meta = !l.meta() ? "" : """
                      head -n 1 "$f" 2>/dev/null | cut -b1-%d | while IFS= read -r x; do echo "meta $(printf '%%s' "$x" | base64 -w0)"; done
                """.formatted(ASK_BYTES);
        return """
                echo '%s'
                d=%s
                if [ -d "$d" ]; then
                  if l=$({ %s; } 2>/dev/null); then
                    echo "total $(printf '%%s' "$l" | grep -c '^')"
                %s    printf '%%s\\n' "$l" | sort -rn | head -n %d | while IFS= read -r e; do
                      [ -n "$e" ] || continue
                      n=${e#* }; n=${n#./}; f=$d/$n
                      echo "session ${e%%%% *} $(printf '%%s' "$n" | base64 -w0)"
                %s      head -n %d "$f" 2>/dev/null%s | head -n %d | cut -b1-%d \\
                        | while IFS= read -r x; do echo "ask $(printf '%%s' "$x" | base64 -w0)"; done
                      tac "$f" 2>/dev/null | head -n %d%s | head -n %d | cut -b1-%d \\
                        | while IFS= read -r x; do echo "close $(printf '%%s' "$x" | base64 -w0)"; done
                    done
                  else
                    echo 'total x'
                  fi
                else
                  echo 'total 0'
                fi
                echo end
                """.formatted(l.header(), l.root(), list, unread, MAX_SESSIONS,
                meta, HEAD_LINES, asks, ASKS, ASK_BYTES, TAIL_LINES, closes, CLOSES, CLOSE_BYTES);
    }

    @Override
    public String agent() {
        return layout.agent();
    }

    @Override
    public String header() {
        return layout.header();
    }

    @Override
    public String script() {
        return script;
    }

    /** The person's words when {@code node} is a message they wrote, else null: not this line, try the next. */
    abstract String ask(JqValue node);

    /** The agent's report when {@code node} is a top-level message of its own with text, else null. */
    abstract String close(JqValue node);

    /** Where the session ran, from a line that says, else null. */
    abstract String cwd(JqValue node);

    static final Pattern TOTAL = Pattern.compile("total (\\d{1,9})");
    private static final Pattern SESSION = Pattern.compile("session (\\d{1,12})(?:\\.\\d+)? ([A-Za-z0-9+/=]{1,8192})");
    private static final Pattern LINE = Pattern.compile("(meta|ask|close) ([A-Za-z0-9+/=]{0,65536})");

    @Override
    public AgentSessions parse(List<String> section) {
        if (section.size() < 3 || !section.getFirst().equals(header()) || !section.getLast().equals("end")) {
            return AgentSessions.unknown("its answer was cut short");
        }
        var total = TOTAL.matcher(section.get(1));
        if (section.get(1).equals("total x")) return AgentSessions.unknown("the sessions could not be listed");
        if (!total.matches()) return AgentSessions.unknown("its answer was not the script's");
        var body = section.subList(2, section.size() - 1);
        if (layout.strangeIsUnread() && total.group(1).equals("0") && body.equals(List.of("unread"))) {
            return new AgentSessions(AgentSessions.State.READ, 0, List.of(), List.of(agent()), null);
        }
        var sessions = new ArrayList<Session>();
        Building current = null;
        for (var line : body) {
            var s = SESSION.matcher(line);
            var l = LINE.matcher(line);
            if (s.matches()) {
                if (current != null) sessions.add(current.session());
                current = new Building(Instant.ofEpochSecond(Long.parseLong(s.group(1))), decode(s.group(2)));
            } else if (l.matches() && current != null && (layout.meta() || !l.group(1).equals("meta"))) {
                var json = decode(l.group(2));
                if (json == null) return AgentSessions.unknown("its answer was not the script's");
                switch (l.group(1)) {
                    case "meta" -> current.meta(json);
                    case "ask" -> current.ask(json);
                    default -> current.close(json);
                }
            } else {
                return AgentSessions.unknown("its answer was not the script's");
            }
        }
        if (current != null) sessions.add(current.session());
        int count = Integer.parseInt(total.group(1));
        if (sessions.size() > Math.min(count, MAX_SESSIONS)) return AgentSessions.unknown("its answer was not the script's");
        return new AgentSessions(AgentSessions.State.READ, count, List.copyOf(sessions), List.of(), null);
    }

    private static String decode(String base64) {
        try {
            return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static JqValue parseLine(String line, String agent) {
        try {
            var node = Jjq.parseForeign(line, "a " + agent + " session line");
            return node.isObject() ? node : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String bounded(String cwd) {
        return cwd == null || cwd.isBlank() ? null : AgentSessions.text(cwd, 200);
    }

    /** One session as its lines arrive: the first ask that is the person's, the first closing candidate that is the agent's. */
    private final class Building {
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

        void meta(String line) {
            var node = parseLine(line, agent());
            var at = node == null ? null : bounded(cwd(node));
            if (at != null) cwd = at;
        }

        void ask(String line) {
            if (askSettled) return;
            var node = parseLine(line, agent());
            if (node == null) {
                // The first candidate is cut or not JSON: what was asked first is not known, and a later message is not it
                askSettled = true;
                return;
            }
            var text = JsonlSessions.this.ask(node);
            if (text == null || text.isBlank()) return;
            ask = AgentSessions.text(text, AgentSessions.MAX_ASK);
            askSettled = true;
            if (cwd == null) cwd = bounded(cwd(node));
        }

        void close(String line) {
            if (closingSettled) return;
            var node = parseLine(line, agent());
            if (node == null) {
                // The newest candidate is cut or not JSON: what it said is not known, and an older message is not it
                closingReadable = false;
                closingSettled = true;
                return;
            }
            var text = JsonlSessions.this.close(node);
            if (text == null || text.isBlank()) return;
            closing = AgentSessions.text(text, AgentSessions.MAX_CLOSING);
            closingSettled = true;
            var at = bounded(cwd(node));
            if (at != null) cwd = at;
        }

        Session session() {
            return new Session(agent(), file, modified, cwd, ask, closing, closingReadable);
        }
    }

    /** A message's {@code type} blocks' text joined, or null when it has none. */
    static String blocks(JqValue content, String type) {
        if (!content.isArray()) return null;
        var parts = new ArrayList<String>();
        for (var block : content.asList()) {
            if (block.pathText("type", "").equals(type)) parts.add(block.pathText("text", ""));
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }
}
