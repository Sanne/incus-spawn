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

    /**
     * The exit {@link #CONVERSATION_LOCK} gives when someone else holds the conversation: a value
     * no agent, shell or common tool gives (not 75, EX_TEMPFAIL), so the agent's own exit is not
     * mistaken for it.
     */
    static final int CONVERSATION_TAKEN = 213;

    /** In a run or a join: flock (util-linux) is there, else {@code then} runs; a guest without it can hold no lock. */
    static String requireFlock(String then) {
        return "command -v flock >/dev/null 2>&1 || { " + then + "; }";
    }

    /** What a run or a join says when the guest has no flock. */
    static final String NO_FLOCK = "isx: flock (util-linux) is missing in this instance, and without it a run and a person "
            + "could both write to one conversation; install util-linux in the template";

    /**
     * Who may write to a task's conversation: whoever holds this lock, on the task directory
     * {@code $D} itself (a directory cannot be swapped for a FIFO that would hold the open). A run
     * and a person's joined agent each run under {@code flock -n}, so the second of two that
     * coincide does not start ({@link #CONVERSATION_TAKEN}) -- the checks before each are a
     * snapshot, this is not. {@code -o}: the lock is held by flock while the agent runs, and is not
     * inherited by what the agent starts, which could outlive it.
     */
    static final String CONVERSATION_LOCK = "flock -n -o -E " + CONVERSATION_TAKEN + " \"$D\" ";


    private TaskScripts() {}

    static String dir(String taskId) {
        ExecScript.requireId(taskId);
        return TASKS_DIR + "/" + taskId;
    }

    /**
     * {@code command} reading {@code file} (a path the shell expands, quoted here) on its stdin if
     * it is a regular file, else nothing (and a false status). In the current shell, so a
     * {@code read} sets its variable. Every task file is read this way: a FIFO in its place
     * (anyone in the instance can make one) would hold an open for reading, and the tool call
     * waiting on it, until something writes to it. Checking the path first is not enough, as a
     * FIFO can be swapped in between the check and the open (#1080); so the file is opened
     * read-write, which never waits on a FIFO, and what was opened is checked. The path is still
     * checked first, as opening read-write creates a file that is not there. So a regular file
     * agentuser may read but not write (read-only mode, another owner, a read-only mount) reads
     * as missing too; isx writes every task file as agentuser, so only something else in the
     * instance makes one.
     */
    static String ifFile(String file, String command) {
        var quoted = "\"" + file + "\"";
        return "{ [ -f " + quoted + " ] && { [ -f /dev/fd/3 ] && " + command + " <&3; } 3<> " + quoted + "; } 2>/dev/null";
    }

    /**
     * True if {@code file} (a path the shell expands, quoted here) is a regular file agentuser may
     * read: for a file only checked for, never opened, so unlike {@link #ifFile} it need not be
     * writable.
     */
    static String isReadableFile(String file) {
        var quoted = "\"" + file + "\"";
        return "[ -f " + quoted + " ] && [ -r " + quoted + " ]";
    }

    /** The contents of {@code file} if it is a regular file ({@link #ifFile}). */
    static String readFile(String file) {
        return ifFile(file, "cat");
    }

    /** {@link #writeAside(String, String, String)} for a file in the task directory {@code $D}. */
    private static String writeAside(String name, String command) {
        return writeAside("$D", name, command);
    }

    /**
     * Write what {@code command} prints to {@code name} in {@code dir} (which the shell expands)
     * without opening it: a FIFO there would hold an open for writing until something reads it
     * (#1074). Written into a file {@code mktemp} makes, then renamed into place, which replaces
     * whatever was there; a directory there fails it ({@code -T}), rather than receiving the file.
     * If any step fails (a full disk, say), nothing is put in place, nothing is left aside, and the
     * status is false: an exit record must never appear empty. A script under {@code set -e}
     * follows it with {@code || exit}, as {@code set -e} ignores a failure inside an
     * {@code &&} list. Sets {@code w}.
     */
    static String writeAside(String dir, String name, String command) {
        return "{ w=$(mktemp \"" + dir + "/.tmp.XXXXXX\") && { " + command + " > \"$w\" && mv -fT \"$w\" \"" + dir + "/" + name
                + "\" || { rm -f \"$w\"; false; }; }; }";
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
        sb.append("cd -- ").append(ExecScript.quote(cwd)).append(" 2> \"$D/stderr\" || { ").append(recordExit("1", "125")).append("; exit 0; }\n");
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
     * back with {@link #list}, and runs on its model in the agent's {@link DelegateAgent#permissionMode},
     * with no turn limit.
     */
    static String agentRun(String taskId, int run, String cwd, Tasks.Profile chosen) {
        return agentRun(taskId, run, cwd, chosen, DelegateAgent.CLAUDE);
    }

    static String agentRun(String taskId, int run, String cwd, Tasks.Profile chosen, DelegateAgent agent) {
        var permissionMode = agent.permissionMode();
        var d = dir(taskId);
        var sb = new StringBuilder();
        sb.append("D=").append(d).append('\n');
        sb.append("export ").append(TASK_ENV).append('=').append(taskId).append('\n');
        if (agent.usesRunner()) {
            // Keep the opened descriptor across rename, so a replaced log path cannot block us.
            sb.append("w=$(mktemp \"$D/.tmp.XXXXXX\") && { exec 4> \"$w\"; } && mv -fT \"$w\" \"$D/runner-")
                    .append(run).append(".log\" || { rm -f -- \"$w\"; ")
                    .append(recordExit(String.valueOf(run), "125")).append("; exit 0; }\n");
        }
        sb.append("cd -- ").append(ExecScript.quote(cwd))
                .append(agent.usesRunner() ? " 2>&4" : " 2> \"$D/stderr-" + run + ".log\"").append(" || { ")
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
        if (agent == DelegateAgent.PI && run > 1) {
            // Checked here too, not only by send_message: an adopting session or a file removed since
            // must not let Pi write the message into a new session.
            sb.append("isx_refuse() { echo \"isx: $1; start a new delegated task.\" >&4; ")
                    .append(recordExit(String.valueOf(run), "125")).append("; exit 0; }\n")
                    .append(PI_SAVED_SESSION).append(piSessionCheck("\"$sid\"")).append('\n');
        }
        if (agent.usesRunner()) {
            var model = ExecScript.quote(chosen.model() == null ? "" : chosen.model());
            sb.append(requireFlock("echo '" + NO_FLOCK + "' >&4; " + recordExit(String.valueOf(run), "127") + "; exit 0")).append('\n');
            sb.append(CONVERSATION_LOCK).append("node -e ").append(ExecScript.quote(WorkerRunners.of(agent)))
                    .append(" \"$D\" ").append(run);
            // Pi has no permission modes: its runs differ only by model.
            if (agent == DelegateAgent.CODEX) sb.append(' ').append(ExecScript.quote(permissionMode));
            sb.append(' ').append(model);
            sb.append(" 2>&4\n");
            sb.append("rc=$?\n").append(TAKEN_NOTE).append(" >&4\n").append(recordExit(String.valueOf(run), "$rc")).append('\n');
            return sb.toString();
        }
        if (run > 1) {
            // Never --resume "": Claude Code fails it at once, with a made-up session ID that would be saved.
            sb.append(SAVED_SESSION).append("case $sid in ").append(NOT_A_SESSION_ID).append(") echo 'isx: ").append(NO_CLAUDE_SESSION)
                    .append("; start a new delegated task.' >> \"$D/stderr-").append(run).append(".log\"; ")
                    .append(recordExit(String.valueOf(run), "125")).append("; exit 0;; esac\n");
            // Claude Code deletes old conversations; resuming one fails at once, and every later message the same way.
            sb.append(claudeTranscriptExists("\"$sid\"")).append(" || { echo 'isx: ").append(CLAUDE_SESSION_GONE)
                    .append("; start a new delegated task.' >> \"$D/stderr-").append(run).append(".log\"; ")
                    .append(recordExit(String.valueOf(run), "125")).append("; exit 0; }\n");
        }
        sb.append(requireFlock("echo '" + NO_FLOCK + "' >> \"$D/stderr-" + run + ".log\"; "
                + recordExit(String.valueOf(run), "127") + "; exit 0")).append('\n');
        sb.append(CONVERSATION_LOCK).append("claude -p --output-format stream-json --verbose")
                .append(" --append-system-prompt \"$(cat \"$D/brief.md\")\"");
        if (chosen.model() != null) sb.append(" --model ").append(ExecScript.quote(chosen.model()));
        // Always explicit: a headless agent that meets a permission prompt has nobody to answer it.
        sb.append(" --permission-mode ").append(ExecScript.quote(permissionMode));
        if (run > 1) sb.append(" --resume \"$sid\"");
        sb.append(" < \"$D/prompt-").append(run).append(".md\" > \"$D/events-").append(run)
                .append(".jsonl\" 2> \"$D/stderr-").append(run).append(".log\"\n");
        sb.append("rc=$?\n");
        sb.append(TAKEN_NOTE).append(" >> \"$D/stderr-").append(run).append(".log\"\n");
        sb.append(saveClaudeSession("$D/events-" + run + ".jsonl")).append('\n');
        sb.append(recordExit(String.valueOf(run), "$rc")).append('\n');
        return sb.toString();
    }

    /** Sets {@code sid} to the session ID the task saved in {@code $D}, if any. */
    private static final String SAVED_SESSION = "sid=$(" + ifFile("$D/session_id", "head -c 128") + "); ";

    /**
     * The {@code case} patterns a {@code sid} that {@link Tasks#SESSION_ID} would refuse matches: empty,
     * not starting with a letter or digit (never an option to the agent), or holding anything else.
     */
    private static final String NOT_A_SESSION_ID = "''|[!A-Za-z0-9]*|*[!A-Za-z0-9_-]*";

    /** Why a Claude task with no saved session cannot be continued. */
    static final String NO_CLAUDE_SESSION = "this task never saved a Claude Code session (it was cancelled or failed "
            + "before Claude Code wrote one), so there is no conversation to continue";

    /** Why a Claude task whose saved session Claude Code no longer has cannot be continued. */
    static final String CLAUDE_SESSION_GONE = "its Claude Code conversation is gone (Claude Code deletes old ones "
            + "after cleanupPeriodDays)";

    /**
     * True when Claude Code still has the transcript of session {@code sessionId} (a shell word: a
     * quoted ID, or {@code "$sid"}), which is what {@code claude --resume} needs: Claude Code keeps
     * one file per session under its config directory's {@code projects/<directory>/}, and deletes
     * those older than {@code cleanupPeriodDays}.
     */
    static String claudeTranscriptExists(String sessionId) {
        return "ls \"${CLAUDE_CONFIG_DIR:-$HOME/.claude}\"/projects/*/" + sessionId + "\".jsonl\" >/dev/null 2>&1";
    }

    /**
     * Save the session a Claude Code run opened, from its stream-json {@code events} file, as the
     * task's {@code session_id}: what the next run resumes. Taken from the {@code init} event alone,
     * since a run Claude Code refused (a resume it could not find) reports a made-up ID in its result;
     * and only once Claude Code has written that session's transcript, which a run cancelled in its
     * first moments has not, so its resume would fail as well. Both leave the saved one as it was.
     * Run after the agent exits, and by {@link #cancel}, which may kill the run script before it can.
     */
    static String saveClaudeSession(String events) {
        return "sid=$(" + ifFile(events, "grep -m 1 '\"subtype\":\"init\"'")
                + " | sed -n 's/.*\"session_id\" *: *\"\\([^\"]*\\)\".*/\\1/p'); "
                + "case $sid in " + NOT_A_SESSION_ID + ") ;; *) ! " + claudeTranscriptExists("\"$sid\"") + " || " + writeAside("session_id", "printf '%s' \"$sid\"") + ";; esac";
    }

    /** What a run that did not start, because a person holds the conversation, says on its stderr. */
    private static final String TAKEN_NOTE = "[ $rc != " + CONVERSATION_TAKEN + " ] || echo 'isx: a person is in this "
            + "conversation (isx shell --task), so this turn did not start; send it again once they leave.'";

    /**
     * Record run {@code run}'s exit code. Whether a run finished is whether its {@code exit-<run>}
     * file exists, so it must never be seen empty: written aside, then renamed into place.
     */
    private static String recordExit(String run, String code) {
        return writeAside("exit-" + run, "echo " + code);
    }

    /**
     * Start run {@code run} of a task: write its files, then hand the run script to systemd.
     * The exec's stdin becomes the prompt file (empty for a command task). An agent's first run
     * also writes the brief its runs append to their system prompt; a first run started under an
     * idempotency key, the key, which {@link #list} reads back.
     */
    static String launch(String taskId, int run, String kind, String runScript) {
        return launch(taskId, run, kind, runScript, null);
    }

    /** {@link #launch}, for a task started under idempotency key {@code key} (null for none). */
    static String launch(String taskId, int run, String kind, String runScript, String key) {
        return launch(taskId, run, kind, runScript, key, DelegateAgent.CLAUDE);
    }

    static String launch(String taskId, int run, String kind, String runScript, String key, DelegateAgent agent) {
        var d = dir(taskId);
        var sb = new StringBuilder();
        sb.append("set -e; D=").append(d).append("; mkdir -p \"$D\"; ");
        var orExit = " || exit 1; ";
        if (key != null && run == 1) sb.append(writeAside("key", "printf '%s' " + ExecScript.quote(McpSession.checkKey(key)))).append(orExit);
        sb.append(writeAside("prompt-" + run + ".md", "cat")).append(orExit);
        sb.append(writeAside("run-" + run + ".sh", "echo " + b64(runScript) + " | base64 -d")).append(orExit);
        if (Tasks.AGENT.equals(kind) && run == 1) sb.append(writeAside("brief.md", "echo " + b64(DELEGATE_BRIEF) + " | base64 -d")).append(orExit);
        sb.append(writeAside("kind", "echo " + kind)).append(orExit);
        if (Tasks.AGENT.equals(kind) && run == 1) {
            sb.append(writeAside("identity", "echo '1 " + agent.id + "'")).append(orExit);
        }
        // A system unit, so the task survives this exec and any session; su - gives the same
        // login environment exec has.
        sb.append("sudo -n systemd-run --quiet --collect --unit=").append(unit(taskId, String.valueOf(run)))
                .append(" --property=KillMode=control-group -- su - agentuser -c \"bash $D/run-")
                .append(run).append(".sh\"; ");
        // Only once the unit started (set -e): a run that never started must not become the
        // current one, hiding the previous run's result behind a run with no exit and no unit.
        sb.append(writeAside("current", "echo " + run));
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
                + "k=$(" + readFile("$D/kind") + "); echo run=$n; echo kind=$k; echo state=$s; "
                // When the run started (it writes current once launched) and last wrote output.
                + "echo started=$(stat -c %Y \"$D/current\" 2>/dev/null); "
                + "[ $s = finished ] && echo exit=$(" + readFile("$D/exit-$n") + "); "
                + "if [ \"$k\" = agent ]; then "
                + "echo cwd=$(" + readFile("$D/cwd") + "); echo session_id=$(" + readFile("$D/session_id") + "); "
                + "ident=$(" + ifFile("$D/identity", "head -c 200") + "); "
                + "if [ -e \"$D/identity\" ] || [ -L \"$D/identity\" ]; then echo identity=$ident; fi; "
                // A Claude session Claude Code has since deleted: task_status says it cannot be resumed.
                + "case $ident in ''|'1 claude') " + SAVED_SESSION + "case $sid in "
                + NOT_A_SESSION_ID + ") ;; *) " + claudeTranscriptExists("\"$sid\"") + " || echo session_gone=1;; esac;; esac; "
                + Presence.script("presence=") + "; "
                // Size and last write of the events in one stat: this runs on every status read.
                + "set -- $(stat -c '%s %Y' \"$D/events-$n.jsonl\" 2>/dev/null); echo events_bytes=${1:-0}; echo activity=$2; echo ---; "
                + ifFile("$D/events-$n.jsonl", "tail -c " + tailBytes) + "; echo; "
                + ifFile("$D/outcome-$n.json", "head -c 16384") + "; echo; echo ---stderr; "
                + ifFile("$D/stderr-$n.log", "tail -c 2000") + "; "
                + ifFile("$D/runner-$n.log", "tail -c 2000") + "; "
                + "else a=; for t in $(stat -c %Y \"$D/stdout\" \"$D/stderr\" 2>/dev/null); do [ \"$t\" -gt \"${a:-0}\" ] && a=$t; done; echo activity=$a; "
                + "echo stdout_bytes=$(stat -c %s \"$D/stdout\" 2>/dev/null || echo 0); "
                + "echo stderr_bytes=$(stat -c %s \"$D/stderr\" 2>/dev/null || echo 0); echo ---; "
                + ifFile("$D/stdout", "tail -c " + tailBytes) + "; echo; echo ---stderr; "
                + ifFile("$D/stderr", "tail -c " + tailBytes) + "; fi; "
                // A run that just started has no output files yet: that is not a failure.
                + "exit 0";
    }

    /**
     * Just whether each task is still running: {@code <id> running|finished|lost|unknown} per line
     * ({@link #RUN_STATE}), where
     * {@code unknown} means systemd could not be asked. Cheap enough to poll, unlike
     * {@link #status}, which also carries the output.
     */
    static String states(Collection<String> taskIds) {
        var sb = new StringBuilder();
        for (var id : taskIds) {
            sb.append("D=").append(dir(id)).append("; id=").append(id).append("; ").append(RUN_STATE)
                    .append("echo $id $s; ");
        }
        return sb.append("exit 0").toString();
    }

    /** Where in {@code $D} {@link #cancel} stamps the time it started cancelling run {@code $n}. */
    private static final String CANCEL_STAMP = "cancelling-$n";

    /**
     * True while run {@code $n} in {@code $D} is being cancelled: {@link #cancel} stamps the time
     * before stopping its unit and records the exit only after its kill sweep, so meanwhile the
     * unit is inactive with no exit file, which would read as lost. Five minutes outlasts the
     * stop itself (systemd waits up to 90 s by default) and the sweep; an older stamp, or one from
     * the future, is a cancel that died midway and no longer counts. The stamp is read as data:
     * anything but digits never reaches the arithmetic, where bash would evaluate it. No fork
     * unless a stamp exists, since this runs on every poll of a running task.
     */
    private static final String CANCELLING = "{ " + ifFile("$D/" + CANCEL_STAMP, "read -r c")
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
    private static final String RUN_STATE = "n=$(" + readFile("$D/current") + "); s=lost; "
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
                    .append("if [ $s = finished ]; then echo \"task $id $n finished $(").append(readFile("$D/exit-$n")).append(")\"; ")
                    .append("k=; ").append(ifFile("$D/kind", "read -r k")).append("; if [ \"$k\" = ").append(Tasks.AGENT).append(" ]; then ")
                    .append("p=1; echo \"sid $id $(").append(readFile("$D/session_id")).append(")\"; ")
                    .append("echo \"cwd $id $(").append(readFile("$D/cwd")).append(")\"; fi; ")
                    .append("else echo \"task $id ${n:-0} $s\"; fi; ");
        }
        return sb.append("[ -n \"$p\" ] && ").append(Presence.script("presence ")).toString();
    }

    /**
     * Every task recorded in the instance, one per line: {@code <id> <kind> <run> <running|done>
     * <model> <key> <cwd>}, with {@code -} for a model never chosen; the key
     * is {@code =<key>}, or {@code -} for a task started without one ({@code -} is a key too). How an
     * adopting session learns the tasks the previous one started; whether a {@code running} one
     * really is, it then asks systemd with {@link #states}.
     */
    static String list() {
        return "for d in " + TASKS_DIR + "/*/; do [ -f \"$d/kind\" ] || continue; "
                + "n=$(" + readFile("$d/current") + "); [ -n \"$n\" ] || continue; "
                + "if [ -f \"$d/exit-$n\" ]; then r=done; else r=running; fi; "
                + "m=$(" + ifFile("$d/model", "head -c 200") + " | tr -d ' \\n'); "
                // '=' marks a key: '-' is one too, so it cannot also mean none.
                + "k=-; [ -f \"$d/key\" ] && k==$(" + ifFile("$d/key", "head -c 64") + " | tr -d ' \\n'); "
                + "{ [ ! -e \"$d/identity\" ] && [ ! -L \"$d/identity\" ]; } || { printf 'identity %s ' \"$(basename \"$d\")\"; "
                + ifFile("$d/identity", "head -c 200") + "; echo; }; "
                + "printf '%s %s %s %s %s %s %s\\n' \"$(basename \"$d\")\" \"$(" + readFile("$d/kind") + ")\" \"$n\" \"$r\" "
                + "\"${m:--}\" \"$k\" \"$(" + readFile("$d/cwd") + ")\"; done; exit 0";
    }

    /**
     * What a person needs to join a delegated agent's conversation ({@link TaskJoin}), for every
     * agent task: {@code task <id> <state> <started>} ({@link #RUN_STATE}, and the current run's
     * start in seconds since the epoch), then {@code identity <id> <identity>} when it recorded
     * one, {@code sid <id> <session>}, {@code cwd <id> <path>} and {@code prompt <id> <first non-blank
     * line of the first instruction>}, each value one line, so none can pass for another task's; then,
     * if there was any agent task, the {@link Presence} probe once, each line prefixed {@code presence }.
     * One exec, read without an {@code isx mcp} session.
     */
    static String joinable() {
        return "p=; " + EACH_TASK + AGENTS_ONLY + RUN_STATE + "p=1; "
                + "echo \"task $id $s $(stat -c %Y \"$D/current\" 2>/dev/null)\"; "
                + "{ [ ! -e \"$D/identity\" ] && [ ! -L \"$D/identity\" ]; } || echo \"identity $id $(" + ifFile("$D/identity", "head -c 200") + " | head -n 1)\"; "
                + "echo \"sid $id $(" + ifFile("$D/session_id", "head -c 200") + " | head -n 1)\"; "
                + "echo \"cwd $id $(" + ifFile("$D/cwd", "head -c 4096") + " | head -n 1)\"; "
                + "echo \"prompt $id $(" + ifFile("$D/prompt-1.md", "head -c 400") + " | grep -m 1 '[^[:space:]]')\"; done; "
                + "[ -n \"$p\" ] && " + Presence.script("presence ") + "; exit 0";
    }

    /**
     * The last check before a person's agent resumes {@code taskId}'s conversation, run by the
     * shell that then execs it ({@link TaskJoin#guestCommand}), so the refusal {@link TaskJoin}
     * decided seconds earlier is decided again a moment before the agent starts. It refuses (a line
     * on stderr, then {@code exit 1}, which leaves no login shell behind) while any unit of the task
     * is active, whatever {@code current} says ({@code current} is written only after
     * {@code systemd-run} has started a run), or systemd cannot be asked; or while a coding agent
     * isx did not start holds the conversation ({@link Presence#holderScript}). A
     * {@code send_message} launched after this check is not seen by it; {@link #CONVERSATION_LOCK},
     * which the agent then starts under, is what keeps that one from running beside the person.
     * With a {@code nonce}, a refusal also leaves its reason for {@link #joinRefusal}.
     */
    static String joinGuard(String taskId, DelegateAgent agent, String sessionId, String cwd, String nonce) {
        if (!Tasks.SESSION_ID.matcher(sessionId).matches()) throw new IllegalArgumentException("not a session id: " + sessionId);
        var mark = nonce == null ? "" : writeAside(REFUSED + requireNonce(nonce), "printf '%s\\n' \"$1\"") + "; ";
        // A refusal waits for Enter on a terminal, so it is read before the session closes (the TUI redraws at once).
        return "D=" + dir(taskId) + "; isx_refuse() { echo \"isx: $1, so not joining " + taskId + ".\" >&2; " + mark
                + "if [ -t 0 ] && [ -t 2 ]; then printf 'Press Enter to go back. ' >&2; read -r _; fi; exit 1; }; "
                // Every unit of the task, whatever current says: one systemctl, and no answer refuses too.
                + "u=$(sudo -n systemctl list-units --plain --no-legend --state=active,activating,deactivating,reloading '"
                + unit(taskId, "*") + "' 2>/dev/null) || isx_refuse 'systemd cannot say whether a run is going'; "
                + "[ -z \"$u\" ] || isx_refuse 'a run of it is going'; "
                + Presence.holderScript(agent, sessionId, cwd)
                + "[ -z \"$h\" ] || isx_refuse \"a person is already in its conversation (agent pid $h)\"; "
                + (agent == DelegateAgent.PI ? piSessionCheck(ExecScript.quote(sessionId)) : "")
                + (agent == DelegateAgent.CLAUDE ? claudeTranscriptExists(ExecScript.quote(sessionId))
                        + " || isx_refuse '" + CLAUDE_SESSION_GONE + "'; " : "");
    }

    /**
     * Calls {@code isx_refuse <why>} (which must not return) unless the file of the Pi session run 1
     * opened in its recorded directory is still there: {@code pi --session-id} opens an empty session in place of
     * a missing one, and writes the new message to it before the runner can stop it. Run before a
     * join ({@link #joinGuard}), a resume ({@link #agentRun}) and {@code send_message}
     * ({@link #piSessionStatus}). {@code sessionId} is a shell word: a quoted ID, or {@code "$sid"}.
     * The file is named by the session header's timestamp ({@code session_started}, with {@code :}
     * and {@code .} as {@code -}) and the ID, so a replacement Pi made since is not taken for it.
     * Looked for only where Pi itself looks for that directory's sessions: {@code PI_CODING_AGENT_SESSION_DIR}
     * when set, else {@link #PI_PROJECT_SESSIONS} under {@code PI_CODING_AGENT_DIR}'s
     * {@code sessions} (default {@code ~/.pi/agent}), either with a leading {@code ~} expanded as
     * Pi 1.4.0's {@code normalizePath} does. A {@code sessionDir} setting or a {@code file://}
     * value is not followed, and refuses. POSIX, as the rest of the guard.
     */
    static String piSessionCheck(String sessionId) {
        return "c=$(" + ifFile("$D/cwd", "head -c 4096") + " | head -n 1); "
                + "[ -n \"$c\" ] || isx_refuse 'its working directory was never recorded'; "
                + "ts=$(" + ifFile("$D/session_started", "head -c 64") + "); "
                + "case $ts in ''|*[!0-9A-Za-z:.-]*) isx_refuse 'its Pi session was never recorded';; esac; "
                // An empty variable counts as unset, as Pi tests them for truth.
                + "if [ -n \"${PI_CODING_AGENT_SESSION_DIR:-}\" ]; then s=$PI_CODING_AGENT_SESSION_DIR; else s=${PI_CODING_AGENT_DIR:-$HOME/.pi/agent}; fi; "
                // Pi's normalizePath on Linux: exactly ~, or a leading ~/, is the home; ~user is not.
                + "case $s in '~') s=$HOME;; '~/'*) s=$HOME/${s#'~/'};; esac; "
                + "[ -n \"${PI_CODING_AGENT_SESSION_DIR:-}\" ] || s=$s/sessions/$(" + PI_PROJECT_SESSIONS + "); "
                + "[ -f \"$s/$(printf '%s' \"$ts\" | tr ':.' '--')_\"" + sessionId + "\".jsonl\" ] "
                + "|| isx_refuse 'its Pi session file is gone, and Pi would open an empty conversation in its place'; ";
    }

    /**
     * Prints the folder Pi keeps the sessions of directory {@code $c} in, under its {@code sessions}:
     * Pi 1.4.0's {@code getDefaultSessionDirPath}, the path without its leading separator, every
     * {@code /}, {@code \\} and {@code :} as {@code -}, between {@code --} and {@code --}. {@code $c} is the
     * task's recorded physical directory, as Pi resolves it from the directory it starts in.
     */
    static final String PI_PROJECT_SESSIONS = "printf '%s' \"--$(printf '%s' \"$c\" | sed 's|^[/\\\\]||' | tr '/\\\\:' '---')--\"";

    /** Sets {@code sid} to the Pi session ID the task saved in {@code $D}, refusing one it never saved. */
    private static final String PI_SAVED_SESSION = SAVED_SESSION
            + "case $sid in ''|*[!A-Za-z0-9_-]*) isx_refuse 'its Pi session was never saved';; esac; ";

    /**
     * {@code ok} when Pi task {@code taskId}'s saved session can be resumed where it ran, else why
     * not, one line: what {@code send_message} asks before it reserves a run, so a lost session is
     * refused with nothing written.
     */
    static String piSessionStatus(String taskId) {
        return "D=" + dir(taskId) + "; isx_refuse() { echo \"$1\"; exit 0; }; " + PI_SAVED_SESSION
                + piSessionCheck("\"$sid\"") + "echo ok";
    }

    /** Where in {@code $D} a refused join leaves its reason for the host ({@link #joinRefusal}). */
    private static final String REFUSED = ".join-refused-";

    private static final java.util.regex.Pattern NONCE = java.util.regex.Pattern.compile("[a-z0-9]{8,32}");

    private static String requireNonce(String nonce) {
        if (!NONCE.matcher(nonce).matches()) throw new IllegalArgumentException("not a join nonce: " + nonce);
        return nonce;
    }

    /**
     * Why the join with {@code nonce} was refused in the guest, then forgotten: empty when it was
     * not. How {@code isx shell --task} learns of a refusal, as the shell's exit status does not
     * reach the host.
     */
    static String joinRefusal(String taskId, String nonce) {
        var f = "$D/" + REFUSED + requireNonce(nonce);
        // Markers of joins nobody asked about (the host gone, interrupted) go after an hour.
        return "D=" + dir(taskId) + "; " + readFile(f) + "; rm -f \"" + f + "\"; "
                + "find \"$D\" -maxdepth 1 -name '" + REFUSED + "*' -mmin +60 -delete 2>/dev/null; exit 0";
    }

    /**
     * {@code task <id> running|unknown} for every delegated agent whose current run has not
     * finished, as systemd sees it; {@code unknown} when systemd could not be asked. What keeps
     * an orphan's working delegate, and its unpushed work, from being destroyed with it. Not
     * background commands: a dev server never finishes, and would keep its orphan forever.
     */
    static String unfinished() {
        return unfinished(AGENTS_ONLY);
    }

    /**
     * Opens a loop over task directories, {@code $D} each, with its id in {@code $id}. Any
     * directory there could be made by anyone in the instance: only one named like a task id.
     */
    private static final String EACH_TASK = "for d in " + TASKS_DIR + "/*/; do D=${d%/}; id=${D##*/}; "
            + "case $id in ''|*[!a-z0-9-]*) continue;; esac; ";

    /** In a loop over task directories {@code $D}: skips all but delegated agents'. */
    private static final String AGENTS_ONLY = ifFile("$D/kind", "read -r k") + " && [ \"$k\" = "
            + Tasks.AGENT + " ] || continue; ";

    /**
     * {@code idle <seconds>}: how long ago, by the guest's own clock, anything in a delegated
     * agent's task directory was last written (its events, its stderr); nothing before a task
     * wrote a file. The orphan sweep's sign of a delegate that still makes progress (#1028).
     * A statement that always succeeds, to go before a script whose exit status counts.
     */
    static String agentIdle() {
        return "a=; " + EACH_TASK + AGENTS_ONLY
                + "for t in $(stat -c %Y \"$D\"/* 2>/dev/null); do [ \"$t\" -gt \"${a:-0}\" ] && a=$t; done; done; "
                + "[ -n \"$a\" ] && echo \"idle $(( $(date +%s) - a ))\"; true";
    }

    /** The seconds {@link #agentIdle} printed, or null if it printed none. */
    static Long agentIdle(java.util.List<String> lines) {
        for (var l : lines) {
            var parts = l.strip().split(" ");
            if (parts.length == 2 && parts[0].equals("idle") && parts[1].matches("-?[0-9]{1,12}")) {
                return Math.max(0, Long.parseLong(parts[1]));
            }
        }
        return null;
    }

    /**
     * {@link #unfinished}, for every task, background commands included: how another session of
     * the same host user counts this instance's tasks against {@code max-concurrent-tasks} in {@code mcp.yaml}.
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
        return EACH_TASK + filter + STATE + "[ $s = done ] || echo \"task $id $s\"; done; exit 0";
    }

    /**
     * Remove every task's records: what a fork must do, since the copy brought its source's
     * along, ids included. Only directories named like a task id, as {@link #unfinished} reads.
     * Fails if one could not be removed (a file a delegate wrote as root, say).
     */
    static String clear() {
        return EACH_TASK + "rm -rf -- \"$D\" || r=1; done; exit ${r:-0}";
    }

    /** All of a command task's output, stdout then stderr, each under a heading. */
    static String output(String taskId) {
        var d = dir(taskId);
        return "D=" + d + "; echo '--- stdout'; " + readFile("$D/stdout") + "; "
                + "echo; echo '--- stderr'; " + readFile("$D/stderr") + "; exit 0";
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
        return "D=" + d + "; n=$(" + readFile("$D/current") + ") || exit 0; "
                // Before the stop: until the exit is recorded below, a state read says running.
                + "[ -f \"$D/exit-$n\" ] || { " + writeAside(CANCEL_STAMP, "date +%s") + "; }; "
                + "sudo -n systemctl stop " + unit(taskId, "$n") + " 2>/dev/null; "
                + "{ sudo -n bash -c " + quoted + " 2>/dev/null || bash -c " + quoted + "; }; "
                // The run script saves its session once Claude Code exits, but the sweep may have
                // killed it first; a Codex or Pi runner publishes its own as soon as it is known.
                + "case $(" + ifFile("$D/identity", "head -c 200") + ") in ''|'1 claude') "
                + saveClaudeSession("$D/events-$n.jsonl") + ";; esac; "
                // The sweep is over either way: the stamp goes, and the cancel keeps its own status.
                + "[ -f \"$D/exit-$n\" ] || { " + recordExit("$n", "143") + "; }; r=$?; rm -f \"$D/" + CANCEL_STAMP + "\"; exit $r";
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
        return diff(taskId, path, maxBytes, statOnly, DIFF_LIMIT_SECONDS);
    }

    /** {@link #diff}, given up on after {@code limitSeconds}. */
    static String diff(String taskId, String path, int maxBytes, boolean statOnly, int limitSeconds) {
        return diff(taskId, path, maxBytes, statOnly, NUMSTAT, limitSeconds);
    }

    /**
     * How long a diff may take. Its files are read without waiting on a FIFO, but git opens the
     * repository's own, which anyone in the instance can replace with one: past this, the diff
     * fails rather than hold the tool call (#1105). A copy of the index makes even a large
     * repository's diff take seconds.
     */
    private static final int DIFF_LIMIT_SECONDS = 300;

    /** {@link #diff}, with the stat records {@code numstat} asks git for. */
    private static String diff(String taskId, String path, int maxBytes, boolean statOnly, String numstat, int limitSeconds) {
        var d = dir(taskId);
        var pathspec = path == null ? "" : " -- " + ExecScript.quote(path);
        var repos = "D=" + d + "; [ -s \"$D/base.txt\" ] || { echo 'no git repository was found where the task started'; exit 0; }; ";
        var each = readFile("$D/base.txt") + " | while read -r base repo; do ( cd \"$repo\" || exit 0; " + DIFF_INDEX;
        String script;
        if (statOnly) {
            script = repos + each
                    + "echo \"## $repo\"; git " + numstat + " \"$base\"" + pathspec + "; echo; "
                    + "rm -f \"$idx\" ); done; echo ---";
        } else {
            script = repos + "out=$(mktemp); stat=$(mktemp); " + each
                    + "{ echo \"## $repo\"; git " + numstat + " \"$base\"" + pathspec + "; echo; } >> \"$stat\"; "
                    + "git diff --cached --src-prefix=a/ --dst-prefix=b/ \"$base\"" + pathspec + " >> \"$out\"; "
                    + "rm -f \"$idx\" ); done; "
                    + "cat \"$stat\"; echo ---; "
                    + "if [ $(stat -c %s \"$out\") -gt " + maxBytes + " ]; then echo \"(too large: $(stat -c %s \"$out\") bytes)\"; "
                    + "else cat \"$out\"; fi; rm -f \"$out\" \"$stat\"";
        }
        // timeout signals its whole process group, git included.
        return "timeout -k 5 " + limitSeconds + " bash -c " + ExecScript.quote(script) + "; r=$?; "
                + "case $r in 124|137) echo \"the diff did not finish within " + limitSeconds
                + " s: something in the instance may be holding it\" >&2;; esac; exit $r";
    }

    /**
     * {@link #diff} for a model to read in the instance ({@code ask}): every change, one stat
     * record per line, git C-quoting a path that holds a control character (as {@link Diff}
     * does), so no file name can forge a line; other names stay as they are.
     */
    static String diffForReading(String taskId, String path) {
        return diff(taskId, path, Integer.MAX_VALUE, false, READABLE_NUMSTAT, DIFF_LIMIT_SECONDS);
    }

    /**
     * One {@code <added>\t<deleted>\t<path>} record per file, each ended by a NUL so that no
     * file name can forge one ({@code -} counts for a binary file). Without renames, as a rename
     * touches two paths and a program comparing what tasks touch needs both.
     */
    private static final String NUMSTAT = "diff --cached --no-renames --numstat -z";

    /** {@link #NUMSTAT} with newline-ended records, for a model to read. */
    private static final String READABLE_NUMSTAT = "-c core.quotePath=false diff --cached --no-renames --numstat";

    /**
     * A throwaway index holding the working tree, so a diff against it covers uncommitted and
     * untracked files without touching the repository's own index: a copy of that index (found
     * by --git-path, as a worktree or submodule has a .git file, not a directory) as a starting
     * point, failing that none -- an empty file would be a corrupt index, a missing one is empty.
     * Read as every task file is ({@link #readFile}): a FIFO there must not hold the copy.
     */
    private static final String DIFF_INDEX = "idx=$(mktemp); ix=$(git rev-parse --git-path index) && " + readFile("$ix")
            + " > \"$idx\" || rm -f \"$idx\"; "
            + "export GIT_INDEX_FILE=\"$idx\"; git add -A >/dev/null 2>&1; ";

    static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
