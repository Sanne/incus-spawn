package dev.incusspawn.mcp;

import java.util.regex.Pattern;

/**
 * Taking a delegated task's conversation back from the person's agent that holds it: the
 * {@code reclaim_task} tool and {@code isx shell --task --take}. A task leaves {@code attached}
 * only on an event, never because time passed: its holder exits, or someone reclaims it. The
 * holder is sent SIGHUP, which ends it; its conversation stays on disk, to be resumed. Nothing
 * harder is sent: a holder that does not exit is refused, and the caller decides what to do next.
 *
 * <p>One exec decides and acts, so what the host saw seconds earlier is decided again in the guest:
 * no run of the task may be going, the holder is found as {@link Presence#holderOf} finds it, and
 * the reclaim is recorded in the task's directory ({@link #RECORD}) before the signal.
 */
record Reclaim(Outcome outcome, Long pid) {

    /** What the guest did. */
    enum Outcome {
        /** The holder exited and the conversation's lock is free. */
        RECLAIMED,
        /** Nobody holds the conversation: there was nothing to do. */
        NONE,
        /** A run of the task is going: that is not a person's, and reclaim never signals it. */
        RUNNING,
        /** systemd could not say whether a run is going, so nothing was signalled. */
        UNKNOWN,
        /** Another agent holds it than the one the caller was shown, so nothing was signalled. */
        OTHER,
        /** SIGHUP could not be sent to the holder (another user's, and no sudo), and it is still there. */
        UNSIGNALLED,
        /** The holder was sent SIGHUP and is still there. */
        ALIVE,
        /** The holder exited, but the conversation's lock stayed taken (a run or another person took it). */
        LOCKED,
        /** The reclaim could not be recorded, so nothing was signalled. */
        UNRECORDED,
        /** The guest's answer could not be read: whether anything was signalled is not known. */
        UNREADABLE
    }

    /** Where in the task's directory each reclaim is recorded: when, which pid, by whom. */
    static final String RECORD = "reclaimed";

    /** How long the holder has to exit, and then its lock to come free. */
    static final int WAIT_SECONDS = 10;

    private static final Pattern PID = Pattern.compile("[0-9]{1,10}");

    /**
     * The guest script: prints one line, the outcome in lower case, then the holder's pid when there
     * was one. With {@code expected}, refuses ({@link Outcome#OTHER}) unless that pid is the holder.
     * {@code who} is recorded as given. Always exits 0, so a failed exec is not mistaken for an outcome.
     */
    static String script(String taskId, DelegateAgent agent, String who, Long expected, int waitSeconds) {
        var d = TaskScripts.dir(taskId);
        var record = TaskScripts.writeAside("$D", RECORD, "{ " + TaskScripts.ifFile("$D/" + RECORD, "tail -n 19")
                + "; printf '%s pid %s by %s\\n' \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\" \"$h\" " + ExecScript.quote(who) + "; }");
        return "D=" + d + "; [ -d \"$D\" ] || { echo none; exit 0; }; "
                + TaskScripts.activeUnits(taskId) + " || { echo unknown; exit 0; }; "
                + "[ -z \"$u\" ] || { echo running; exit 0; }; "
                + "sid=$(" + TaskScripts.ifFile("$D/session_id", "head -c 200") + " | head -n 1); "
                + TaskScripts.READ_CWD
                + Presence.holderScriptWords(agent, "\"$sid\"", "\"$c\"")
                + "[ -n \"$h\" ] || { echo none; exit 0; }; "
                + (expected == null ? "" : "[ \"$h\" = " + expected + " ] || { echo \"other $h\"; exit 0; }; ")
                + record + " || { echo \"unrecorded $h\"; exit 0; }; "
                + "sudo -n kill -HUP \"$h\" 2>/dev/null || kill -HUP \"$h\" 2>/dev/null || "
                + "{ [ -e /proc/$h ] && { echo \"unsignalled $h\"; exit 0; }; }; "
                // Gone, or a zombie nobody reaped yet: either way it no longer writes.
                + "i=0; while [ -e /proc/$h ] && ! grep -qs '^State:[[:space:]]*Z' /proc/$h/status; do "
                + "[ $i -ge " + waitSeconds * 5 + " ] && { echo \"alive $h\"; exit 0; }; sleep 0.2; i=$((i + 1)); done; "
                // A holder found by its directory alone held no lock; one that held it gave it up on exit.
                + "if command -v flock >/dev/null 2>&1; then flock -w " + waitSeconds + " \"$D\" true || { echo \"locked $h\"; exit 0; }; fi; "
                + "echo \"reclaimed $h\"; exit 0";
    }

    /**
     * What {@link #script} printed, its last line, as a login shell may print before it;
     * {@link Outcome#UNREADABLE} for anything else.
     */
    static Reclaim parse(String output) {
        var lines = output.strip().lines().toList();
        var parts = lines.isEmpty() ? new String[] {""} : lines.getLast().strip().split(" ");
        Outcome outcome;
        try {
            outcome = Outcome.valueOf(parts[0].toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return new Reclaim(Outcome.UNREADABLE, null);
        }
        var pid = parts.length > 1 && PID.matcher(parts[1]).matches() ? Long.valueOf(parts[1]) : null;
        return new Reclaim(outcome, pid);
    }

    /** Whether the conversation is free now: reclaimed, or nobody held it. */
    boolean free() {
        return outcome == Outcome.RECLAIMED || outcome == Outcome.NONE;
    }

    /** Why the conversation was not taken back, as one line; null when it is free. */
    String refusal(String taskId) {
        return switch (outcome) {
            case RECLAIMED, NONE -> null;
            case RUNNING -> "a run of task " + taskId + " is going, which is not a person's agent; reclaim does not stop it (cancel_task does).";
            case UNKNOWN -> "systemd in the instance could not say whether a run of task " + taskId + " is going, so nothing was signalled.";
            case OTHER -> "another agent (pid " + pid + ") holds task " + taskId + "'s conversation now, so nothing was signalled.";
            case UNSIGNALLED -> "the agent holding task " + taskId + "'s conversation (pid " + pid + ") could not be sent SIGHUP: "
                    + "it is another user's, and sudo is not available in the instance.";
            case ALIVE -> "the agent holding task " + taskId + "'s conversation (pid " + pid + ") was sent SIGHUP and did not exit within "
                    + WAIT_SECONDS + " s; nothing harder was sent.";
            case LOCKED -> "the agent holding task " + taskId + "'s conversation (pid " + pid + ") exited, but a run or another person "
                    + "took the conversation within " + WAIT_SECONDS + " s.";
            case UNRECORDED -> "the reclaim could not be recorded in task " + taskId + "'s directory, so nothing was signalled.";
            case UNREADABLE -> "isx could not read what the instance did with task " + taskId
                    + "'s conversation, so its holder may or may not have been sent SIGHUP; task_status says whether it is still attached.";
        };
    }

    /**
     * {@link #refusal} for a person at {@code isx shell --take}, whose next step is a command of
     * theirs rather than an MCP tool; null when the conversation is free.
     */
    String personRefusal(String taskId, String instance) {
        var join = TaskJoin.hostCommand(instance, taskId);
        return switch (outcome) {
            case RUNNING -> "a run of task " + taskId + " is going, which is not a person's agent, so nothing was signalled; "
                    + "join it once the turn ends: " + join;
            case OTHER -> "another agent (pid " + pid + ") holds task " + taskId + "'s conversation now, so nothing was signalled; "
                    + "to see who it is and take it: " + join + " --take";
            case UNREADABLE -> "isx could not read what the instance did with task " + taskId + "'s conversation, "
                    + "so the other agent may or may not have been ended; to see whether it is still held: " + join;
            default -> refusal(taskId);
        };
    }

    /** Throws why the conversation was not taken back, unless it is free. */
    void requireFree(String taskId) {
        var why = refusal(taskId);
        if (why == null) return;
        throw new ToolError(switch (outcome) {
            case UNKNOWN, UNRECORDED, UNREADABLE -> ToolError.Code.UNAVAILABLE;
            case UNSIGNALLED -> ToolError.Code.REFUSED;
            default -> ToolError.Code.WRONG_STATE;
        }, why);
    }
}
