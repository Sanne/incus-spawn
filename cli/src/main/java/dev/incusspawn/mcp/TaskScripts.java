package dev.incusspawn.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

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
    private static final Pattern TASK_ID = Pattern.compile("[a-z0-9-]+");

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

    private TaskScripts() {}

    static String dir(String taskId) {
        requireId(taskId);
        return TASKS_DIR + "/" + taskId;
    }

    static String unit(String taskId, int run) {
        requireId(taskId);
        return "isx-task-" + taskId + "-" + run;
    }

    /** The run script of a background command. */
    static String commandRun(String taskId, String cwd, Map<String, String> env, String command) {
        var d = dir(taskId);
        var sb = new StringBuilder();
        sb.append("D=").append(d).append('\n');
        sb.append("cd -- ").append(ExecScript.quote(cwd)).append(" || { echo 125 > \"$D/exit-1\"; exit 0; }\n");
        appendEnv(sb, env);
        sb.append("bash -c ").append(ExecScript.quote(command))
                .append(" < /dev/null > \"$D/stdout\" 2> \"$D/stderr\"\n");
        sb.append("echo $? > \"$D/exit-1\"\n");
        return sb.toString();
    }

    /**
     * The run script of one turn of a delegated agent. Run 1 records, for every git repository
     * at or under {@code cwd}, the commit it started from, which {@link #diff} compares against.
     */
    static String agentRun(String taskId, int run, String cwd, Integer maxTurns) {
        var d = dir(taskId);
        var sb = new StringBuilder();
        sb.append("D=").append(d).append('\n');
        sb.append("cd -- ").append(ExecScript.quote(cwd)).append(" || { echo 125 > \"$D/exit-").append(run)
                .append("\"; exit 0; }\n");
        if (run == 1) {
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
     * The exec's stdin becomes the prompt file (empty for a command task).
     */
    static String launch(String taskId, int run, String kind, String runScript, boolean writeBrief) {
        var d = dir(taskId);
        var sb = new StringBuilder();
        sb.append("set -e; D=").append(d).append("; mkdir -p \"$D\"; ");
        sb.append("cat > \"$D/prompt-").append(run).append(".md\"; ");
        sb.append("echo ").append(b64(runScript)).append(" | base64 -d > \"$D/run-").append(run).append(".sh\"; ");
        if (writeBrief) sb.append("echo ").append(b64(DELEGATE_BRIEF)).append(" | base64 -d > \"$D/brief.md\"; ");
        sb.append("echo ").append(kind).append(" > \"$D/kind\"; ");
        sb.append("echo ").append(run).append(" > \"$D/current\"; ");
        // A system unit, so the task survives this exec and any session; su - gives the same
        // login environment exec has.
        sb.append("sudo -n systemd-run --quiet --collect --unit=").append(unit(taskId, run))
                .append(" --property=KillMode=control-group -- su - agentuser -c \"bash $D/run-")
                .append(run).append(".sh\"");
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
                + "echo unit=$(sudo -n systemctl is-active isx-task-" + taskId + "-$n 2>/dev/null); "
                + "[ -f \"$D/exit-$n\" ] && echo exit=$(cat \"$D/exit-$n\"); "
                + "if [ \"$k\" = agent ]; then "
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

    /** Stop the task's current run, and everything it started. */
    static String cancel(String taskId) {
        var d = dir(taskId);
        return "D=" + d + "; n=$(cat \"$D/current\" 2>/dev/null) || exit 0; "
                + "sudo -n systemctl stop isx-task-" + taskId + "-$n 2>/dev/null; "
                + "[ -f \"$D/exit-$n\" ] || echo 143 > \"$D/exit-$n\"";
    }

    /**
     * The changes since the task started, committed or not, in every repository it recorded a
     * base for: {@code git diff} against a throwaway index built from the working tree, so the
     * instance's own index is untouched and untracked files are included. Prints a
     * {@code --stat} summary, then {@code ---}, then the patch (or {@code (too large)}).
     */
    static String diff(String taskId, String path, int maxBytes) {
        var d = dir(taskId);
        var pathspec = path == null ? "" : " -- " + ExecScript.quote(path);
        return "D=" + d + "; [ -s \"$D/base.txt\" ] || { echo 'no git repository was found where the task started'; exit 0; }; "
                + "out=$(mktemp); stat=$(mktemp); "
                + "while read -r base repo; do ( cd \"$repo\" || exit 0; "
                + "idx=$(mktemp); cp .git/index \"$idx\" 2>/dev/null; export GIT_INDEX_FILE=\"$idx\"; "
                + "git add -A >/dev/null 2>&1; "
                + "echo \"## $repo\" >> \"$stat\"; git diff --cached --stat \"$base\"" + pathspec + " >> \"$stat\"; "
                + "git diff --cached --src-prefix=a/ --dst-prefix=b/ \"$base\"" + pathspec + " >> \"$out\"; "
                + "rm -f \"$idx\" ); done < \"$D/base.txt\"; "
                + "cat \"$stat\"; echo ---; "
                + "if [ $(stat -c %s \"$out\") -gt " + maxBytes + " ]; then echo \"(too large: $(stat -c %s \"$out\") bytes)\"; "
                + "else cat \"$out\"; fi; rm -f \"$out\" \"$stat\"";
    }

    private static void appendEnv(StringBuilder sb, Map<String, String> env) {
        for (var e : env.entrySet()) {
            if (!Pattern.matches("[A-Za-z_][A-Za-z0-9_]*", e.getKey())) {
                throw new ToolError("invalid environment variable name: " + e.getKey());
            }
            sb.append("export ").append(e.getKey()).append('=').append(ExecScript.quote(e.getValue())).append('\n');
        }
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireId(String id) {
        if (!TASK_ID.matcher(id).matches()) throw new IllegalArgumentException("bad task id: " + id);
    }
}
