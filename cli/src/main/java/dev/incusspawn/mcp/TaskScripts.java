package dev.incusspawn.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The shell scripts behind background tasks: {@code exec(background)} and {@code delegate}.
 *
 * <p>A task runs as a transient systemd unit inside the instance, so it outlives the exec that
 * started it, the MCP connection and the session's own process -- only destroying the instance
 * ends it. Each run of a task (a delegation plus each {@code send_message}) is its own unit,
 * {@code isx-task-<id>-<n>}, writing into {@code ~/.isx-mcp/tasks/<id>/}. The instance is the
 * source of truth for a task's state; a person can inspect it there with {@code isx shell}.
 *
 * <p>Nothing the agent sends is interpolated into a shell: run scripts travel base64-encoded and
 * prompts arrive on stdin.
 */
final class TaskScripts {

    static final String TASKS_DIR = "$HOME/.isx-mcp/tasks";
    private static final String UNIT_PREFIX = "isx-task-";

    /**
     * Appended to the inner agent's system prompt. It frames the role; it does not forbid
     * anything. What the delegate may reach is bounded by the credentials the template's proxy
     * account carries, and what it does by the coordinating agent's instruction.
     */
    static final String DELEGATE_BRIEF = """
            You are a delegate: a coordinating agent running on the user's host handed you the \
            task below, and will read your final message. Do what the instruction asks -- \
            including committing, pushing branches or opening pull requests when it asks for \
            that -- and do not take outward-facing actions it did not ask for. If you are \
            blocked or unsure, stop and say so rather than guess. End with a concise report: \
            what you changed, how you verified it, and links to anything you published \
            (branches, pull requests).""";

    /**
     * Where a task works, as the physical path: after an adoption, later runs resume there, and
     * a person's Claude Code working in it is found by comparing against {@code /proc/<pid>/cwd}.
     */
    private static final String RECORD_CWD = "pwd -P > \"$D/cwd\"\n";

    /**
     * Set in every run and inherited by everything it starts, so {@link Presence} can tell the
     * task's own Claude Code from a person's. Not the unit's cgroup: {@code su -} goes through
     * PAM, which moves the run into a user session scope.
     */
    static final String TASK_ENV = "ISX_MCP_TASK";

    private TaskScripts() {}

    static String dir(String taskId) {
        ExecScript.requireId(taskId);
        return TASKS_DIR + "/" + taskId;
    }

    /** The unit of one run; {@code run} may be a shell expression such as {@code $n}. */
    static String unit(String taskId, String run) {
        ExecScript.requireId(taskId);
        return UNIT_PREFIX + taskId + "-" + run;
    }

    /** The run script of a background command. */
    static String commandRun(String taskId, String cwd, Map<String, String> env, String command) {
        var d = dir(taskId);
        var sb = new StringBuilder();
        sb.append("D=").append(d).append('\n');
        sb.append("export ").append(TASK_ENV).append('=').append(taskId).append('\n');
        sb.append("cd -- ").append(ExecScript.quote(cwd)).append(" || { ").append(recordExit("1", "125")).append("; exit 0; }\n");
        sb.append(RECORD_CWD);
        ExecScript.appendExports(sb, env, "\n");
        sb.append("bash -c ").append(ExecScript.quote(command))
                .append(" < /dev/null > \"$D/stdout\" 2> \"$D/stderr\"\n");
        sb.append("rc=$?\n").append(recordExit("1", "$rc")).append('\n');
        return sb.toString();
    }

    /**
     * The run script of one turn of a delegated agent. Run 1 records, for every git repository
     * at or under {@code cwd}, the commit it started from, which {@link #diff} compares against.
     * Every run records the profile the task {@code chosen}, which an adopting session reads
     * back with {@link #list}, and runs on its model with {@code maxTurns}, the budget the
     * session gives it, as its turn budget.
     */
    static String agentRun(String taskId, int run, String cwd, Tasks.Profile chosen, Integer maxTurns,
                           String permissionMode) {
        var d = dir(taskId);
        var sb = new StringBuilder();
        sb.append("D=").append(d).append('\n');
        sb.append("export ").append(TASK_ENV).append('=').append(taskId).append('\n');
        sb.append("cd -- ").append(ExecScript.quote(cwd)).append(" || { ")
                .append(recordExit(String.valueOf(run), "125")).append("; exit 0; }\n");
        if (run == 1) {
            sb.append(RECORD_CWD);
            sb.append("""
                    { top=$(git rev-parse --show-toplevel 2>/dev/null) && echo "$top"; \
                    find . -maxdepth 3 -name .git -prune -printf '%h\\n' 2>/dev/null; } \
                    | while read -r r; do (cd "$r" 2>/dev/null && h=$(git rev-parse -q --verify HEAD) \
                    && echo "$h $(pwd -P)"); done | sort -u -k2 > "$D/base.txt"
                    """);
        }
        if (chosen.model() != null) {
            sb.append("printf '%s' ").append(ExecScript.quote(chosen.model())).append(" > \"$D/model\"\n");
        }
        if (chosen.maxTurns() != null) {
            sb.append("echo ").append(chosen.maxTurns().intValue()).append(" > \"$D/max-turns\"\n");
        }
        sb.append("claude -p --output-format stream-json --verbose")
                .append(" --append-system-prompt \"$(cat \"$D/brief.md\")\"");
        if (chosen.model() != null) sb.append(" --model ").append(ExecScript.quote(chosen.model()));
        if (maxTurns != null) sb.append(" --max-turns ").append(maxTurns.intValue());
        // Always explicit: a headless agent that meets a permission prompt has nobody to answer it.
        sb.append(" --permission-mode ").append(ExecScript.quote(permissionMode));
        if (run > 1) sb.append(" --resume \"$(cat \"$D/session_id\")\"");
        sb.append(" < \"$D/prompt-").append(run).append(".md\" > \"$D/events-").append(run)
                .append(".jsonl\" 2> \"$D/stderr-").append(run).append(".log\"\n");
        sb.append("rc=$?\n");
        sb.append("sid=$(sed -n 's/.*\"session_id\" *: *\"\\([^\"]*\\)\".*/\\1/p' \"$D/events-").append(run)
                .append(".jsonl\" | head -n 1)\n");
        sb.append("[ -n \"$sid\" ] && printf '%s' \"$sid\" > \"$D/session_id\"\n");
        sb.append(recordExit(String.valueOf(run), "$rc")).append('\n');
        return sb.toString();
    }

    /**
     * Record run {@code run}'s exit code. Whether a run finished is whether its {@code exit-<run>}
     * file exists, so it must never be seen empty: written aside, then renamed into place.
     */
    private static String recordExit(String run, String code) {
        return "echo " + code + " > \"$D/exit-" + run + ".tmp\" && mv -f \"$D/exit-" + run + ".tmp\" \"$D/exit-" + run + "\"";
    }

    /**
     * Start run {@code run} of a task: write its files, then hand the run script to systemd.
     * The exec's stdin becomes the prompt file (empty for a command task). An agent's first run
     * also writes the brief its runs append to their system prompt.
     */
    static String launch(String taskId, int run, String kind, String runScript) {
        var d = dir(taskId);
        var sb = new StringBuilder();
        sb.append("set -e; D=").append(d).append("; mkdir -p \"$D\"; ");
        sb.append("cat > \"$D/prompt-").append(run).append(".md\"; ");
        sb.append("echo ").append(b64(runScript)).append(" | base64 -d > \"$D/run-").append(run).append(".sh\"; ");
        if (Tasks.AGENT.equals(kind) && run == 1) sb.append("echo ").append(b64(DELEGATE_BRIEF)).append(" | base64 -d > \"$D/brief.md\"; ");
        sb.append("echo ").append(kind).append(" > \"$D/kind\"; ");
        // A system unit, so the task survives this exec and any session; su - gives the same
        // login environment exec has.
        sb.append("sudo -n systemd-run --quiet --collect --unit=").append(unit(taskId, String.valueOf(run)))
                .append(" --property=KillMode=control-group -- su - agentuser -c \"bash $D/run-")
                .append(run).append(".sh\"; ");
        // Only once the unit started (set -e): a run that never started must not become the
        // current one, hiding the previous run's result behind a run with no exit and no unit.
        sb.append("echo ").append(run).append(" > \"$D/current\"");
        return sb.toString();
    }

    /**
     * Report a task's state as {@code key=value} lines, then {@code ---} and the tail of its
     * output: the events of the current run for an agent, stdout then stderr for a command.
     * The state is {@link #RUN_STATE}'s, so every reader decides it by the same rules.
     */
    static String status(String taskId, int tailBytes) {
        var d = dir(taskId);
        return "D=" + d + "; [ -d \"$D\" ] || { echo state=missing; exit 0; }; id=" + taskId + "; " + RUN_STATE
                + "k=$([ -f \"$D/kind\" ] && cat \"$D/kind\"); echo run=$n; echo kind=$k; echo state=$s; "
                // When the run started (it writes current once launched) and last wrote output.
                + "echo started=$(stat -c %Y \"$D/current\" 2>/dev/null); "
                + "[ $s = finished ] && echo exit=$(cat \"$D/exit-$n\"); "
                + "if [ \"$k\" = agent ]; then "
                + "echo cwd=$(cat \"$D/cwd\" 2>/dev/null); echo session_id=$(cat \"$D/session_id\" 2>/dev/null); "
                + Presence.script("presence=") + "; "
                // Size and last write of the events in one stat: this runs on every status read.
                + "set -- $(stat -c '%s %Y' \"$D/events-$n.jsonl\" 2>/dev/null); echo events_bytes=${1:-0}; echo activity=$2; echo ---; "
                + "tail -c " + tailBytes + " \"$D/events-$n.jsonl\" 2>/dev/null; echo; echo ---stderr; "
                + "tail -c 2000 \"$D/stderr-$n.log\" 2>/dev/null; "
                + "else a=; for t in $(stat -c %Y \"$D/stdout\" \"$D/stderr\" 2>/dev/null); do [ \"$t\" -gt \"${a:-0}\" ] && a=$t; done; echo activity=$a; "
                + "echo stdout_bytes=$(stat -c %s \"$D/stdout\" 2>/dev/null || echo 0); "
                + "echo stderr_bytes=$(stat -c %s \"$D/stderr\" 2>/dev/null || echo 0); echo ---; "
                + "tail -c " + tailBytes + " \"$D/stdout\" 2>/dev/null; echo; echo ---stderr; "
                + "tail -c " + tailBytes + " \"$D/stderr\" 2>/dev/null; fi; "
                // A run that just started has no output files yet: that is not a failure.
                + "exit 0";
    }

    /**
     * Just whether each task is still running: {@code <id> running|done|unknown} per line, where
     * {@code unknown} means systemd could not be asked. Cheap enough to poll, unlike
     * {@link #status}, which also carries the output.
     */
    static String states(Collection<String> taskIds) {
        var sb = new StringBuilder();
        for (var id : taskIds) {
            sb.append("D=").append(dir(id)).append("; id=").append(id).append("; ").append(STATE)
                    .append("echo $id $s; ");
        }
        return sb.append("exit 0").toString();
    }

    /** Where {@link #cancel} stamps the time it started cancelling run {@code $n} in {@code $D}. */
    private static final String CANCEL_STAMP = "\"$D/cancelling-$n\"";

    /**
     * True while run {@code $n} in {@code $D} is being cancelled: {@link #cancel} stamps the time
     * before stopping its unit and records the exit only after its kill sweep, so meanwhile the
     * unit is inactive with no exit file, which would read as lost. Five minutes outlasts the
     * stop itself (systemd waits up to 90 s by default) and the sweep; an older stamp, or one from
     * the future, is a cancel that died midway and no longer counts. The stamp is read as data:
     * anything but digits never reaches the arithmetic, where bash would evaluate it. No fork
     * unless a stamp exists, since this runs on every poll of a running task.
     */
    private static final String CANCELLING = "{ [ -f " + CANCEL_STAMP + " ] && read -r c 2>/dev/null < " + CANCEL_STAMP
            + " && case $c in ''|*[!0-9]*) false;; esac && t=$(date +%s) "
            + "&& [ $(( t - c )) -ge 0 ] && [ $(( t - c )) -lt 300 ]; }";

    /**
     * Sets {@code n} to task {@code $id}'s current run in {@code $D} and {@code s} to
     * {@code finished} once that run recorded an exit, else as systemd sees its unit:
     * {@code running}, {@code lost} (ended without an exit, or no run at all) or {@code unknown}
     * (systemd could not be asked). Through sudo, like {@link #status}. The exit file is looked
     * at again after asking systemd, since a run writes it before its unit ends, and once more
     * after the {@link #CANCELLING} check, since a cancel records it before removing its stamp:
     * only a unit seen inactive with no exit file after both, and no fresh stamp, died without one.
     */
    // Only regular files are read: a FIFO in their place (anyone in the instance can make one)
    // would hold the read until something writes to it.
    private static final String RUN_STATE = "n=$([ -f \"$D/current\" ] && cat \"$D/current\" 2>/dev/null); s=lost; "
            + "if [ -n \"$n\" ]; then "
            + "[ -f \"$D/exit-$n\" ] || s=$(sudo -n systemctl is-active \"" + UNIT_PREFIX + "$id-$n\" 2>/dev/null); "
            + "if [ -f \"$D/exit-$n\" ]; then s=finished; else "
            + "case \"$s\" in active|activating) s=running;; '') s=unknown;; "
            // A cancel records its exit before it removes its stamp: looked at once more, a cancel
            // that finished between the two checks reads finished, never lost.
            + "*) if " + CANCELLING + "; then s=running; elif [ -f \"$D/exit-$n\" ]; then s=finished; "
            + "else s=lost; fi;; esac; fi; fi; ";

    /** {@link #RUN_STATE}, with {@code finished} and {@code lost} both {@code done}. */
    private static final String STATE = RUN_STATE + "case $s in finished|lost) s=done;; esac; ";

    /**
     * What a client watching for task changes is told ({@link TaskWatcher}): {@code task <id>
     * <run> running|finished <exit>|lost|unknown} per task ({@link #RUN_STATE}), then for each
     * finished agent its {@code sid <id> <session>} and {@code cwd <id> <path>}, and if there was
     * one, the {@link Presence} probe once, each line prefixed {@code presence }. One exec per
     * instance, one {@code /proc} scan at most.
     */
    static String watch(Collection<String> taskIds) {
        return watchBody(taskIds) + "; exit 0";
    }

    /** {@link #watch} without its {@code exit}, to follow another script in the same exec. */
    static String watchBody(Collection<String> taskIds) {
        var sb = new StringBuilder("p=; ");
        for (var id : taskIds) {
            sb.append("D=").append(dir(id)).append("; id=").append(id).append("; ").append(RUN_STATE)
                    .append("if [ $s = finished ]; then echo \"task $id $n finished $(cat \"$D/exit-$n\")\"; ")
                    // Regular files only, as in RUN_STATE: a FIFO would hold the poller for good.
                    .append("k=; [ -f \"$D/kind\" ] && read -r k < \"$D/kind\"; if [ \"$k\" = ").append(Tasks.AGENT).append(" ]; then ")
                    .append("p=1; echo \"sid $id $([ -f \"$D/session_id\" ] && cat \"$D/session_id\")\"; ")
                    .append("echo \"cwd $id $([ -f \"$D/cwd\" ] && cat \"$D/cwd\")\"; fi; ")
                    .append("else echo \"task $id ${n:-0} $s\"; fi; ");
        }
        return sb.append("[ -n \"$p\" ] && ").append(Presence.script("presence ")).toString();
    }

    /**
     * Every task recorded in the instance, one per line: {@code <id> <kind> <run> <running|done>
     * <model> <max-turns> <cwd>}, with {@code -} for a profile value never chosen. How an
     * adopting session learns the tasks the previous one started; whether a {@code running} one
     * really is, it then asks systemd with {@link #states}.
     */
    static String list() {
        return "for d in " + TASKS_DIR + "/*/; do [ -f \"$d/kind\" ] || continue; "
                + "n=$(cat \"$d/current\" 2>/dev/null); [ -n \"$n\" ] || continue; "
                + "if [ -f \"$d/exit-$n\" ]; then r=done; else r=running; fi; "
                + "m=$(head -c 200 \"$d/model\" 2>/dev/null | tr -d ' \\n'); t=$(head -c 20 \"$d/max-turns\" 2>/dev/null | tr -d ' \\n'); "
                + "printf '%s %s %s %s %s %s %s\\n' \"$(basename \"$d\")\" \"$(cat \"$d/kind\")\" \"$n\" \"$r\" "
                + "\"${m:--}\" \"${t:--}\" \"$(cat \"$d/cwd\" 2>/dev/null)\"; done; exit 0";
    }

    /**
     * {@code task <id> running|unknown} for every delegated agent whose current run has not
     * finished, as systemd sees it; {@code unknown} when systemd could not be asked. What keeps
     * an orphan's working delegate, and its unpushed work, from being destroyed with it. Not
     * background commands: a dev server never finishes, and would keep its orphan forever.
     */
    static String unfinished() {
        return unfinished("[ -f \"$D/kind\" ] && read -r k < \"$D/kind\" 2>/dev/null && [ \"$k\" = "
                + Tasks.AGENT + " ] || continue; ");
    }

    /**
     * {@link #unfinished}, for every task, background commands included: how another session of
     * the same host user counts this instance's tasks against {@code mcp.max-concurrent-tasks}.
     */
    static String busy() {
        return unfinished("[ -f \"$D/kind\" ] || continue; ");
    }

    /**
     * The task ids in what {@link #unfinished} or {@link #busy} printed, each once. Anything else
     * on the output -- it is the guest's -- is ignored, never an error.
     */
    static Set<String> taskIds(Stream<String> lines) {
        var ids = new LinkedHashSet<String>();
        lines.forEach(l -> {
            var parts = l.split(" ");
            if (parts.length >= 3 && parts[0].equals("task") && !parts[1].isEmpty()) ids.add(parts[1]);
        });
        return ids;
    }

    private static String unfinished(String filter) {
        return "for d in " + TASKS_DIR + "/*/; do D=${d%/}; id=${D##*/}; "
                // Any directory there could be made by anyone in the instance: only a task id.
                + "case $id in ''|*[!a-z0-9-]*) continue;; esac; "
                + filter + STATE + "[ $s = done ] || echo \"task $id $s\"; done; exit 0";
    }

    /**
     * Remove every task's records: what a fork must do, since the copy brought its source's
     * along, ids included. Only directories named like a task id, as {@link #unfinished} reads.
     * Fails if one could not be removed (a file a delegate wrote as root, say).
     */
    static String clear() {
        return "for d in " + TASKS_DIR + "/*/; do D=${d%/}; id=${D##*/}; "
                + "case $id in ''|*[!a-z0-9-]*) continue;; esac; rm -rf -- \"$D\" || r=1; done; exit ${r:-0}";
    }

    /** All of a command task's output, stdout then stderr, each under a heading. */
    static String output(String taskId) {
        var d = dir(taskId);
        return "D=" + d + "; echo '--- stdout'; cat \"$D/stdout\" 2>/dev/null; "
                + "echo; echo '--- stderr'; cat \"$D/stderr\" 2>/dev/null; exit 0";
    }

    /**
     * {@link #cancel} for several tasks at once, side by side: each waits between TERM and KILL.
     * Fails if any of them did, as {@link #cancel} alone would.
     */
    static String cancelAll(List<String> taskIds) {
        return cancelAll(taskIds, "");
    }

    /** {@link #cancelAll}, then {@code then} (a script with no {@code exit}) once all are cancelled. */
    static String cancelAll(List<String> taskIds, String then) {
        return taskIds.stream().map(id -> "( " + cancel(id) + " ) & p=\"$p $!\"; ")
                .collect(Collectors.joining("", "p=; ", "r=0; for c in $p; do wait $c || r=1; done; "
                        + (then.isEmpty() ? "" : then + "; ") + "exit $r"));
    }

    /**
     * Stop the task's current run, and everything it started. Stopping the unit is not enough:
     * {@code su -} goes through PAM, which moves the run out of the unit's cgroup into a user
     * session scope, so the unit's KillMode never reaches it. Every process carrying the task's
     * {@link #TASK_ENV} is signalled too, TERM first, then KILL.
     */
    static String cancel(String taskId) {
        var d = dir(taskId);
        var kill = "for s in TERM KILL; do for p in /proc/[0-9]*; do "
                + "grep -qzx '" + TASK_ENV + "=" + taskId + "' \"$p/environ\" 2>/dev/null && kill -$s \"${p#/proc/}\" 2>/dev/null; "
                + "done; [ $s = TERM ] && sleep 2; done; exit 0";
        var quoted = ExecScript.quote(kill);
        return "D=" + d + "; n=$(cat \"$D/current\" 2>/dev/null) || exit 0; "
                // Before the stop: until the exit is recorded below, a state read says running.
                + "[ -f \"$D/exit-$n\" ] || date +%s > " + CANCEL_STAMP + "; "
                + "sudo -n systemctl stop " + unit(taskId, "$n") + " 2>/dev/null; "
                + "{ sudo -n bash -c " + quoted + " 2>/dev/null || bash -c " + quoted + "; }; "
                // The sweep is over either way: the stamp goes, and the cancel keeps its own status.
                + "[ -f \"$D/exit-$n\" ] || { " + recordExit("$n", "143") + "; }; r=$?; rm -f " + CANCEL_STAMP + "; exit $r";
    }

    /**
     * The changes since the task started, committed or not, in every repository it recorded a
     * base for: {@code git diff} against a throwaway index built from the working tree, so the
     * instance's own index is untouched and untracked files are included. Prints, per
     * repository, {@code ## <repo>}, its {@link #NUMSTAT} records and an empty line; then
     * {@code ---}, then the patch (or {@code (too large: <n> bytes)}). {@link Diff} reads it.
     */
    static String diff(String taskId, String path, int maxBytes) {
        return diff(taskId, path, maxBytes, false);
    }

    /**
     * {@link #diff}, or with {@code statOnly} just what it touched: the same per repository, then
     * {@code ---} and no patch. Deterministic and small, whatever the size of the patch.
     */
    static String diff(String taskId, String path, int maxBytes, boolean statOnly) {
        return diff(taskId, path, maxBytes, statOnly, "cat \"$stat\"; ");
    }

    /** {@link #diff}, printing the full diff's stat records with {@code printStat}. */
    private static String diff(String taskId, String path, int maxBytes, boolean statOnly, String printStat) {
        var d = dir(taskId);
        var pathspec = path == null ? "" : " -- " + ExecScript.quote(path);
        if (statOnly) {
            return "D=" + d + "; [ -s \"$D/base.txt\" ] || { echo 'no git repository was found where the task started'; exit 0; }; "
                    + "while read -r base repo; do ( cd \"$repo\" || exit 0; "
                    + DIFF_INDEX
                    + "echo \"## $repo\"; git diff --cached " + NUMSTAT + " \"$base\"" + pathspec + "; echo; "
                    + "rm -f \"$idx\" ); done < \"$D/base.txt\"; echo ---";
        }
        return "D=" + d + "; [ -s \"$D/base.txt\" ] || { echo 'no git repository was found where the task started'; exit 0; }; "
                + "out=$(mktemp); stat=$(mktemp); "
                + "while read -r base repo; do ( cd \"$repo\" || exit 0; "
                + DIFF_INDEX
                + "{ echo \"## $repo\"; git diff --cached " + NUMSTAT + " \"$base\"" + pathspec + "; echo; } >> \"$stat\"; "
                + "git diff --cached --src-prefix=a/ --dst-prefix=b/ \"$base\"" + pathspec + " >> \"$out\"; "
                + "rm -f \"$idx\" ); done < \"$D/base.txt\"; "
                + printStat + "echo ---; "
                + "if [ $(stat -c %s \"$out\") -gt " + maxBytes + " ]; then echo \"(too large: $(stat -c %s \"$out\") bytes)\"; "
                + "else cat \"$out\"; fi; rm -f \"$out\" \"$stat\"";
    }

    /**
     * {@link #diff} for a model to read in the instance ({@code ask}): every change, with each
     * NUL-ended stat record on a line of its own. Only the stat is translated, never the patch.
     */
    static String diffForReading(String taskId, String path) {
        return diff(taskId, path, Integer.MAX_VALUE, false, "tr '\\0' '\\n' < \"$stat\"; ");
    }

    /**
     * One {@code <added>\t<deleted>\t<path>} record per file, each ended by a NUL so that no
     * file name can forge one ({@code -} counts for a binary file). Without renames, as a rename
     * touches two paths and a program comparing what tasks touch needs both.
     */
    private static final String NUMSTAT = "--no-renames --numstat -z";

    /**
     * A throwaway index holding the working tree, so a diff against it covers uncommitted and
     * untracked files without touching the repository's own index: a copy of that index (found
     * by --git-path, as a worktree or submodule has a .git file, not a directory) as a starting
     * point, failing that none -- an empty file would be a corrupt index, a missing one is empty.
     */
    private static final String DIFF_INDEX = "idx=$(mktemp); cp \"$(git rev-parse --git-path index)\" \"$idx\" 2>/dev/null || rm -f \"$idx\"; "
            + "export GIT_INDEX_FILE=\"$idx\"; git add -A >/dev/null 2>&1; ";

    static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
