package dev.incusspawn.mcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Whether a person is working in an instance: a process on a terminal (an {@code isx shell},
 * including a tmux or zmx session left detached), or a Claude Code that isx did not start (a
 * person resuming a delegate's conversation). A task's own processes never count: they carry
 * {@code ISX_MCP_TASK} in their environment ({@link TaskScripts#TASK_ENV}).
 *
 * <p>Read from {@code /proc} rather than {@code ps}, which a minimal image may lack; through
 * {@code sudo} when it can, so processes of other users (a {@code sudo -i}) are seen too.
 */
final class Presence {

    /** A Claude Code process isx did not start: where it runs, and which sessions it resumes. */
    record Claude(long pid, String cwd, Set<String> resumes) {}

    /**
     * Prints {@code tty <pid>} for each process on a pseudo-terminal, {@code claude <pid> <cwd>}
     * for each Claude Code, and {@code resume <pid> <session>} for each session one resumes.
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
              [ "$maj" -ge 136 ] && [ "$maj" -le 143 ] && [ "${f[4]}" != "$console" ] && echo "tty $pid"
              mapfile -d '' -t argv 2>/dev/null < "$d/cmdline" || continue
              [ ${#argv[@]} -gt 0 ] || continue
              case "${argv[0]##*/}" in
                claude) i=1;;
                node|bun) case "${argv[1]}" in *claude*) i=2;; *) continue;; esac;;
                *) continue;;
              esac
              grep -qz '^ISX_MCP_TASK=' "$d/environ" 2>/dev/null && continue
              echo "claude $pid $(readlink "$d/cwd" 2>/dev/null)"
              while [ "$i" -lt ${#argv[@]} ]; do
                case "${argv[$i]}" in
                  --resume|-r) i=$((i + 1)); [ -n "${argv[$i]}" ] && echo "resume $pid ${argv[$i]}";;
                  --resume=*) echo "resume $pid ${argv[$i]#--resume=}";;
                esac
                i=$((i + 1))
              done
            done
            exit 0
            """;

    static final Presence NOBODY = new Presence(Set.of(), List.of());

    private final Set<Long> terminals;
    private final List<Claude> claudes;

    private Presence(Set<Long> terminals, List<Claude> claudes) {
        this.terminals = terminals;
        this.claudes = claudes;
    }

    /** The probe, as a shell command whose output {@link #parse} reads; prefixed per line if asked. */
    static String script(String linePrefix) {
        var quoted = ExecScript.quote(PROBE);
        var probe = "{ sudo -n bash -c " + quoted + " 2>/dev/null || bash -c " + quoted + "; }";
        return linePrefix.isEmpty() ? probe : probe + " | sed 's/^/" + linePrefix + "/'";
    }

    static Presence parse(List<String> lines) {
        var terminals = new HashSet<Long>();
        var claudes = new LinkedHashMap<Long, Claude>();
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
                case "claude" -> claudes.put(pid, new Claude(pid, parts.length > 2 ? parts[2] : "", new HashSet<>()));
                case "resume" -> {
                    var c = claudes.get(pid);
                    if (c != null && parts.length > 2) c.resumes().add(parts[2]);
                }
                default -> { }
            }
        }
        return new Presence(Set.copyOf(terminals), List.copyOf(claudes.values()));
    }

    /** Whether anyone is at work in the instance. */
    boolean attended() {
        return !terminals.isEmpty() || !claudes.isEmpty();
    }

    /**
     * The pid of a Claude Code a person runs on this conversation -- resuming {@code sessionId},
     * or working in {@code cwd}, where {@code claude --continue} or the resume picker would pick
     * it up -- or null if there is none.
     */
    Long holderOf(String sessionId, String cwd) {
        for (var c : claudes) {
            if (sessionId != null && !sessionId.isEmpty() && c.resumes().contains(sessionId)) return c.pid();
            if (cwd != null && !cwd.isEmpty() && cwd.equals(c.cwd())) return c.pid();
        }
        return null;
    }
}
