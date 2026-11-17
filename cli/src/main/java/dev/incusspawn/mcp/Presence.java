package dev.incusspawn.mcp;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether a person is working in an instance: a process on a terminal (an {@code isx shell},
 * including a tmux or zmx session left detached); and which coding agents isx did not start hold a
 * delegate's conversation (a person resuming it), with or without a terminal. A task's own
 * processes never count: they carry {@code ISX_MCP_TASK} in their environment
 * ({@link TaskScripts#TASK_ENV}).
 *
 * <p>Read from {@code /proc} rather than {@code ps}, which a minimal image may lack; through
 * {@code sudo} when it can, so processes of other users (a {@code sudo -i}) are seen too.
 */
final class Presence {
    private static final Pattern EPOCH_SECONDS = Pattern.compile("[0-9]{1,12}");
    /** A Claude session or a Codex thread: what either resumes by ID. */
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** An opencode session, which opencode names itself. */
    private static final Pattern OPENCODE_SESSION = Pattern.compile("ses_[A-Za-z0-9]+");
    private static final String HEX = "[0-9a-fA-F]";
    private static final String UUID_GLOB = HEX.repeat(8) + "-" + HEX.repeat(4) + "-" + HEX.repeat(4) + "-" + HEX.repeat(4) + "-" + HEX.repeat(12);

    /** A coding agent process isx did not start: where it runs, and which sessions it resumes. */
    record AgentProcess(long pid, DelegateAgent agent, String cwd, Set<String> resumes) {}

    /**
     * Prints {@code tty <pid>} for each process on a pseudo-terminal, {@code claude <pid> <cwd>},
     * {@code codex <pid> <cwd>}, {@code pi <pid> <cwd>} or {@code opencode <pid> <cwd>} for each coding agent,
     * {@code input <pid> <epoch seconds>} for one on a pseudo-terminal, the access time of its
     * controlling pty, which the kernel moves on input and not on output (at about 8 s granularity),
     * and {@code resume <pid> <session>} for each session one resumes. Pi replaces its command line
     * with its title, {@code pi}, so its session is never seen: it is found by its directory,
     * which is enough, as its sessions belong to the directory they started in (a
     * {@code --session-id} from elsewhere opens another, and {@code --session} forks).
     * Only pts devices (majors 136-143) count, and not the one behind {@code /dev/console}:
     * in an Incus container the console is a pty too, with a getty always on it.
     */
    private static final String PROBE = """
            c=$(stat -L -c '%t %T' /dev/console 2>/dev/null) && cmaj=$((16#${c% *})) && cmin=$((16#${c#* })) \
              && console=$(( (cmaj << 8) | (cmin & 255) | ((cmin & ~255) << 12) )) || console=-1
            for d in /proc/[0-9]*; do
              pid=${d#/proc/}
              st=$(cat "$d/stat" 2>/dev/null) || continue
              read -r -a f <<< "${st##*) }"
              maj=$(( (${f[4]:-0} >> 8) & 4095 ))
              pty=0; [ "$maj" -ge 136 ] && [ "$maj" -le 143 ] && [ "${f[4]}" != "$console" ] && pty=1 && echo "tty $pid"
              mapfile -d '' -t argv 2>/dev/null < "$d/cmdline" || continue
              [ ${#argv[@]} -gt 0 ] || continue
              case "${argv[0]##*/}" in
                claude) agent=claude; i=1;;
                codex) agent=codex; i=1;;
                pi) agent=pi; i=1;;
                opencode) agent=opencode; i=1;;
                node|bun) case "${argv[1]}" in *claude*) agent=claude; i=2;; *codex*) agent=codex; i=2;; */pi-coding-agent/*) agent=pi; i=2;; *) continue;; esac;;
                *) continue;;
              esac
              grep -qz '^ISX_MCP_TASK=' "$d/environ" 2>/dev/null && continue
              echo "$agent $pid $(readlink "$d/cwd" 2>/dev/null)"
              if [ "$pty" = 1 ]; then
                pts=$(( (maj - 136) * 256 + ((${f[4]} & 255) | ((${f[4]} >> 12) & 1048320)) ))
                at=$(stat -c %X "/dev/pts/$pts" 2>/dev/null) && echo "input $pid $at"
              fi
              [ "$agent" = pi ] && continue
              if [ "$agent" = opencode ]; then
                # opencode's TUI, run and attach all resume with --session or -s.
                while [ "$i" -lt ${#argv[@]} ]; do
                  case "${argv[$i]}" in
                    --session|-s) i=$((i + 1)); [ -n "${argv[$i]}" ] && echo "resume $pid ${argv[$i]}";;
                    --session=?*) echo "resume $pid ${argv[$i]#--session=}";;
                    --) break;;
                  esac
                  i=$((i + 1))
                done
                continue
              fi
              if [ "$agent" = codex ]; then
                # Parse subcommands and the first resume positional, not words in a prompt.
                # These options take a separate value; --name=value and -mVALUE need no skip.
                phase=command; options=1
                while [ "$i" -lt ${#argv[@]} ]; do
                  arg=${argv[$i]}
                  if [ "$options" = 1 ]; then
                    case "$arg" in
                      --) [ "$phase" = resume ] || break; options=0; i=$((i + 1)); continue;;
                      -c|--config|--enable|--disable|-i|--image|-m|--model|-p|--profile|-s|--sandbox|\
                      -C|--cd|--add-dir|-a|--ask-for-approval|--local-provider|--remote|--remote-auth-token-env|\
                      --thread-source|--cyber-access-program|--output-schema|--color|-o|--output-last-message)
                        i=$((i + 2)); continue;;
                      -*) i=$((i + 1)); continue;;
                    esac
                  fi
                  case "$phase:$arg" in
                    command:exec|command:e) phase=exec;;
                    command:resume|exec:resume) phase=resume;;
                    resume:*) [ -n "$arg" ] && echo "resume $pid $arg"; break;;
                    *) break;;
                  esac
                  i=$((i + 1))
                done
                continue
              fi
              while [ "$i" -lt ${#argv[@]} ]; do
                case "${argv[$i]}" in
                  # --resume takes an optional value: an option after it is not a session.
                  --resume|-r) case "${argv[$((i + 1))]}" in ''|-*) ;; *) i=$((i + 1)); echo "resume $pid ${argv[$i]}";; esac;;
                  --resume=?*) echo "resume $pid ${argv[$i]#--resume=}";;
                esac
                i=$((i + 1))
              done
            done
            exit 0
            """;

    static final Presence NOBODY = new Presence(Set.of(), List.of(), Map.of());

    private final Set<Long> terminals;
    private final List<AgentProcess> agents;
    private final Map<Long, Instant> lastInput;

    private Presence(Set<Long> terminals, List<AgentProcess> agents, Map<Long, Instant> lastInput) {
        this.terminals = terminals;
        this.agents = agents;
        this.lastInput = lastInput;
    }

    /** The probe, as a shell command whose output {@link #parse} reads; prefixed per line if asked. */
    static String script(String linePrefix) {
        var quoted = ExecScript.quote(PROBE);
        var probe = "{ sudo -n bash -c " + quoted + " 2>/dev/null || bash -c " + quoted + "; }";
        return linePrefix.isEmpty() ? probe : probe + " | sed 's/^/" + linePrefix + "/'";
    }

    /**
     * {@link #holderOf} in the guest, for a check that must run where the agent then starts
     * ({@link TaskScripts#joinGuard}): sets {@code h} to the pid of the coding agent that holds the
     * conversation, empty for none, from one run of the probe. Same rule, so change both together.
     */
    static String holderScript(DelegateAgent agent, String sessionId, String cwd) {
        return holderScriptWords(agent, ExecScript.quote(sessionId), ExecScript.quote(cwd));
    }

    /**
     * {@link #holderScript} for a session and directory the guest reads itself: each a shell word,
     * such as {@code "$sid"}. An empty one matches nothing.
     */
    static String holderScriptWords(DelegateAgent agent, String sessionWord, String cwdWord) {
        // POSIX, as the login shell running it need not be bash; the probe brings its own.
        // a: this kind's agents in order; d: those in the directory; r: those resuming a session by its ID;
        // s: those resuming this one. Decided at the end, as an agent's resume lines follow it.
        return "h=$(" + script("") + " | { h=; a=; d=; r=; s=; while read -r k p rest; do "
                + "case $k in " + agent.id + ") a=\"$a $p\"; [ -n " + cwdWord + " ] && [ \"$rest\" = " + cwdWord + " ] && d=\"$d $p \";; "
                + "resume) case \"$a \" in *\" $p \"*) " + shapedSession(agent, "r=\"$r $p \"") + "[ -n " + sessionWord + " ] && [ \"$rest\" = " + sessionWord
                + " ] && s=\"$s $p \";; esac;; esac; done; "
                // The first agent that matches, as holderOf: by this session, or by the directory unless it resumes another one.
                + "for p in $a; do case \"$s\" in *\" $p \"*) h=$p; break;; esac; "
                + "case \"$d\" in *\" $p \"*) case \"$r\" in *\" $p \"*) [ -n " + sessionWord + " ] || { h=$p; break; };; "
                + "*) h=$p; break;; esac;; esac; done; "
                + "echo \"$h\"; }); ";
    }

    static Presence parse(List<String> lines) {
        var terminals = new HashSet<Long>();
        var agents = new LinkedHashMap<Long, AgentProcess>();
        var lastInput = new HashMap<Long, Instant>();
        for (var line : lines) {
            var parts = line.strip().split(" ", 3);
            if (parts.length < 2) continue;
            long pid;
            try {
                pid = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            switch (parts[0]) {
                case "tty" -> terminals.add(pid);
                case "claude", "codex", "pi", "opencode" -> agents.put(pid, new AgentProcess(pid, DelegateAgent.parse(parts[0]), parts.length > 2 ? parts[2] : "", new HashSet<>()));
                case "resume" -> {
                    var c = agents.get(pid);
                    if (c != null && parts.length > 2) c.resumes().add(parts[2]);
                }
                case "input" -> {
                    if (parts.length > 2 && EPOCH_SECONDS.matcher(parts[2]).matches()) {
                        lastInput.put(pid, Instant.ofEpochSecond(Long.parseLong(parts[2])));
                    }
                }
                default -> { }
            }
        }
        return new Presence(Set.copyOf(terminals), List.copyOf(agents.values()), Map.copyOf(lastInput));
    }

    /** Whether {@code pid} has a controlling pseudo-terminal: a person's agent does, a leftover after a hangup does not. */
    boolean onTerminal(long pid) {
        return terminals.contains(pid);
    }

    /** When {@code pid}'s pseudo-terminal last had input, or null when it has none or it could not be read. */
    Instant lastInput(long pid) {
        return lastInput.get(pid);
    }

    /**
     * Whether a person is at work in the instance: something runs on a terminal. A coding agent
     * with no controlling terminal is no one's (a leftover after a hangup, say): it still holds its
     * conversation ({@link #holderOf}), but it does not keep an instance from orphan cleanup.
     */
    boolean attended() {
        return !terminals.isEmpty();
    }

    /**
     * The pid of a coding agent of the task's own kind that a person runs on this conversation --
     * resuming {@code sessionId}, or working in {@code cwd}, where {@code claude --continue} or the
     * resume picker would pick it up -- or null if there is none. Another kind of agent in the
     * directory cannot resume this conversation, so it does not hold it; nor does one there that
     * resumes another session by its ID ({@link #sessionShaped}), which is a person's other conversation.
     */
    Long holderOf(DelegateAgent agent, String sessionId, String cwd) {
        var known = sessionId != null && !sessionId.isEmpty();
        for (var c : agents) {
            if (agent != c.agent()) continue;
            if (known && c.resumes().contains(sessionId)) return c.pid();
            var another = known && c.resumes().stream().anyMatch(r -> sessionShaped(agent, r));
            if (cwd != null && !cwd.isEmpty() && cwd.equals(c.cwd()) && !another) return c.pid();
        }
        return null;
    }

    /**
     * Whether {@code value}, given to a resume option, names one of {@code agent}'s sessions by its
     * ID. Anything else, such as a session's name or the search term Claude Code's picker takes
     * ({@code claude --resume fix}), may still pick this task's conversation, so an agent resuming
     * it in the directory keeps holding it.
     */
    static boolean sessionShaped(DelegateAgent agent, String value) {
        return switch (agent) {
            case CLAUDE, CODEX -> UUID.matcher(value).matches();
            case OPENCODE -> OPENCODE_SESSION.matcher(value).matches();
            case PI, UNKNOWN -> false;
        };
    }

    /** {@link #sessionShaped} in the guest, on {@code $rest}: runs {@code then} when it holds. POSIX. */
    private static String shapedSession(DelegateAgent agent, String then) {
        return switch (agent) {
            case CLAUDE, CODEX -> "case $rest in " + UUID_GLOB + ") " + then + ";; esac; ";
            case OPENCODE -> "case $rest in ses_?*) case ${rest#ses_} in *[!A-Za-z0-9]*) ;; *) " + then + ";; esac;; esac; ";
            case PI, UNKNOWN -> "";
        };
    }
}
