package dev.incusspawn.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;

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
        sb.append("cd -- ").append(ExecScript.quote(cwd)).append(" || { echo 125 > \"$D/exit-1\"; exit 0; }\n");
        sb.append(RECORD_CWD);
        ExecScript.appendExports(sb, env, "\n");
        sb.append("bash -c ").append(ExecScript.quote(command))
                .append(" < /dev/null > \"$D/stdout\" 2> \"$D/stderr\"\n");
        sb.append("echo $? > \"$D/exit-1\"\n");
        return sb.toString();
    }

    /**
     * The run script of one turn of a delegated agent. Run 1 records, for every git repository
     * at or under {@code cwd}, the commit it started from, which {@link #diff} compares against.
     */
    static String agentRun(String taskId, int run, String cwd, Integer maxTurns, String permissionMode) {
        var d = dir(taskId);
        var sb = new StringBuilder();
        sb.append("D=").append(d).append('\n');
        sb.append("export ").append(TASK_ENV).append('=').append(taskId).append('\n');
        sb.append("cd -- ").append(ExecScript.quote(cwd)).append(" || { echo 125 > \"$D/exit-").append(run)
                .append("\"; exit 0; }\n");
        if (run == 1) {
            sb.append(RECORD_CWD);
            sb.append("""
                    { top=$(git rev-parse --show-toplevel 2>/dev/null) && echo "$top"; \
                    find . -maxdepth 3 -name .git -prune -printf '%h\\n' 2>/dev/null; } \
                    | while read -r r; do (cd "$r" 2>/dev/null && h=$(git rev-parse -q --verify HEAD) \
                    && echo "$h $(pwd -P)"); done | sort -u -k2 > "$D/base.txt"
                    """);
        }
        sb.append("claude -p --output-format stream-json --verbose")
                .append(" --append-system-prompt \"$(cat \"$D/brief.md\")\"");
        if (maxTurns != null) sb.append(" --max-turns ").append(maxTurns);
        // Always explicit: a headless agent that meets a permission prompt has nobody to answer it.
        sb.append(" --permission-mode ").append(ExecScript.quote(permissionMode));
        if (run > 1) sb.append(" --resume \"$(cat \"$D/session_id\")\"");
        sb.append(" < \"$D/prompt-").append(run).append(".md\" > \"$D/events-").append(run)
                .append(".jsonl\" 2> \"$D/stderr-").append(run).append(".log\"\n");
        sb.append("rc=$?\n");
        sb.append("sid=$(sed -n 's/.*\"session_id\" *: *\"\\([^\"]*\\)\".*/\\1/p' \"$D/events-").append(run)
                .append(".jsonl\" | head -n 1)\n");
        sb.append("[ -n \"$sid\" ] && printf '%s' \"$sid\" > \"$D/session_id\"\n");
        sb.append("echo $rc > \"$D/exit-").append(run).append("\"\n");
        return sb.toString();
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
     */
    static String status(String taskId, int tailBytes) {
        var d = dir(taskId);
        return "D=" + d + "; [ -d \"$D\" ] || { echo state=missing; exit 0; }; "
                + "n=$(cat \"$D/current\"); k=$(cat \"$D/kind\"); echo run=$n; echo kind=$k; "
                // Through sudo: an unprivileged login session in a container may not reach
                // systemd's system bus, and would report every running task as gone.
                + "echo unit=$(sudo -n systemctl is-active " + unit(taskId, "$n") + " 2>/dev/null); "
                + "[ -f \"$D/exit-$n\" ] && echo exit=$(cat \"$D/exit-$n\"); "
                + "if [ \"$k\" = agent ]; then "
                + "echo cwd=$(cat \"$D/cwd\" 2>/dev/null); echo session_id=$(cat \"$D/session_id\" 2>/dev/null); "
                + Presence.script("presence=") + "; "
                + "echo events_bytes=$(stat -c %s \"$D/events-$n.jsonl\" 2>/dev/null || echo 0); echo ---; "
                + "tail -c " + tailBytes + " \"$D/events-$n.jsonl\" 2>/dev/null; echo; echo ---stderr; "
                + "tail -c 2000 \"$D/stderr-$n.log\" 2>/dev/null; "
                + "else echo stdout_bytes=$(stat -c %s \"$D/stdout\" 2>/dev/null || echo 0); "
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
            var d = dir(id);
            sb.append("n=$(cat \"").append(d).append("/current\" 2>/dev/null); ")
                    .append("if [ -z \"$n\" ] || [ -f \"").append(d).append("/exit-$n\" ]; then s=done; else ")
                    .append("s=$(sudo -n systemctl is-active ").append(unit(id, "$n")).append(" 2>/dev/null); ")
                    .append("case \"$s\" in active|activating) s=running;; '') s=unknown;; *) s=done;; esac; fi; ")
                    .append("echo ").append(id).append(" $s; ");
        }
        return sb.append("exit 0").toString();
    }

    /**
     * Every task recorded in the instance, one per line: {@code <id> <kind> <run> <running|done>
     * <cwd>}. How an adopting session learns the tasks the previous one started; whether a
     * {@code running} one really is, it then asks systemd with {@link #states}.
     */
    static String list() {
        return "for d in " + TASKS_DIR + "/*/; do [ -f \"$d/kind\" ] || continue; "
                + "n=$(cat \"$d/current\" 2>/dev/null); [ -n \"$n\" ] || continue; "
                + "if [ -f \"$d/exit-$n\" ]; then r=done; else r=running; fi; "
                + "printf '%s %s %s %s %s\\n' \"$(basename \"$d\")\" \"$(cat \"$d/kind\")\" \"$n\" \"$r\" "
                + "\"$(cat \"$d/cwd\" 2>/dev/null)\"; done; exit 0";
    }

    /** All of a command task's output, stdout then stderr, each under a heading. */
    static String output(String taskId) {
        var d = dir(taskId);
        return "D=" + d + "; echo '--- stdout'; cat \"$D/stdout\" 2>/dev/null; "
                + "echo; echo '--- stderr'; cat \"$D/stderr\" 2>/dev/null; exit 0";
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
                + "sudo -n systemctl stop " + unit(taskId, "$n") + " 2>/dev/null; "
                + "{ sudo -n bash -c " + quoted + " 2>/dev/null || bash -c " + quoted + "; }; "
                + "[ -f \"$D/exit-$n\" ] || echo 143 > \"$D/exit-$n\"";
    }

    /**
     * The changes since the task started, committed or not, in every repository it recorded a
     * base for: {@code git diff} against a throwaway index built from the working tree, so the
     * instance's own index is untouched and untracked files are included. Prints a
     * {@code --stat} summary, then {@code ---}, then the patch (or {@code (too large)}).
     */
    static String diff(String taskId, String path, int maxBytes) {
        return diff(taskId, path, maxBytes, false);
    }

    /**
     * {@link #diff}, or with {@code statOnly} just what it touched: per repository, a
     * {@code --numstat} line per file (lines added, removed, path) and the {@code --shortstat}
     * total, then {@code ---}. Deterministic and small, whatever the size of the patch.
     */
    static String diff(String taskId, String path, int maxBytes, boolean statOnly) {
        var d = dir(taskId);
        var pathspec = path == null ? "" : " -- " + ExecScript.quote(path);
        if (statOnly) {
            return "D=" + d + "; [ -s \"$D/base.txt\" ] || { echo 'no git repository was found where the task started'; exit 0; }; "
                    + "while read -r base repo; do ( cd \"$repo\" || exit 0; "
                    + DIFF_INDEX
                    + "echo \"## $repo\"; git diff --cached --numstat \"$base\"" + pathspec + "; "
                    + "git diff --cached --shortstat \"$base\"" + pathspec + "; "
                    + "rm -f \"$idx\" ); done < \"$D/base.txt\"; echo ---";
        }
        return "D=" + d + "; [ -s \"$D/base.txt\" ] || { echo 'no git repository was found where the task started'; exit 0; }; "
                + "out=$(mktemp); stat=$(mktemp); "
                + "while read -r base repo; do ( cd \"$repo\" || exit 0; "
                + DIFF_INDEX
                + "echo \"## $repo\" >> \"$stat\"; git diff --cached --stat \"$base\"" + pathspec + " >> \"$stat\"; "
                + "git diff --cached --src-prefix=a/ --dst-prefix=b/ \"$base\"" + pathspec + " >> \"$out\"; "
                + "rm -f \"$idx\" ); done < \"$D/base.txt\"; "
                + "cat \"$stat\"; echo ---; "
                + "if [ $(stat -c %s \"$out\") -gt " + maxBytes + " ]; then echo \"(too large: $(stat -c %s \"$out\") bytes)\"; "
                + "else cat \"$out\"; fi; rm -f \"$out\" \"$stat\"";
    }

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
