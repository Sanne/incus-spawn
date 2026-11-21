package dev.incusspawn.incus;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The shell scripts that run a command inside an instance, as agentuser in a login shell, for
 * {@code isx exec} and {@code isx mcp}'s {@code exec} and tasks.
 *
 * <p>The command runs in its own session ({@code setsid}), whose id is written to a pid file, so
 * the whole process tree can be killed from a second exec when the caller gives up: a command
 * nobody waits for must not keep running unowned. The pid file is removed once the run ends. Every value the caller supplies is
 * single-quoted; nothing it sends is ever interpreted by a shell other than its own command's.
 * What it refuses is an {@link IllegalArgumentException}.
 */
public final class ExecScript {

    static final String RUN_DIR = "$HOME/.isx-mcp/run";
    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");

    private ExecScript() {}

    /**
     * @param cwd            directory to run in; relative to agentuser's home if not absolute
     * @param timeoutSeconds null for no limit: how long a command may run is the agent's call
     */
    public static String build(String runId, String cwd, Map<String, String> env, String command,
                        Integer timeoutSeconds) {
        requireId(runId);
        var sb = new StringBuilder();
        sb.append("mkdir -p ").append(RUN_DIR).append(" && cd -- ").append(quote(cwd)).append(" || exit 125; ");
        appendExports(sb, env, "; ");
        // The pid file goes once the session ends, however it ends: a stale one would name a
        // session id the guest may since have given to something else, for a later kill to signal.
        sb.append("trap ").append(quote("rm -f " + pidFile(runId))).append(" EXIT; ");
        // $$ of the inner bash is the new session's id (setsid made it the leader), so the pid
        // file names the session every descendant belongs to. Not exec'd itself, so this shell
        // outlives the session to run the trap; its exit code is setsid's, which is the command's.
        // The inner bash waits for the command rather than exec'ing it, and exits with its status:
        // a command a signal ended is then 128+n, as a shell reports it. Exec'd, a setsid that
        // forked (it does when it starts as a process group leader) would pass on the bare
        // signal number instead, so an OOM kill would exit 9, like the command's own exit 9.
        sb.append("setsid --wait bash -c ")
                // In a subshell: its variable must not replace one the command was given.
                .append(quote("( " + writeAside(RUN_DIR, runId + ".pid", "echo $$") + " ); \"$@\"; exit $?"))
                .append(" isx-exec ");
        if (timeoutSeconds != null) {
            if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeout_seconds must be positive");
            sb.append("timeout --kill-after=10s ").append(timeoutSeconds).append("s ");
        }
        sb.append("bash -c ").append(quote(command));
        return sb.toString();
    }

    /** Kill the process tree of a running {@link #build} script, TERM first, then KILL. */
    public static String kill(String runId) {
        requireId(runId);
        var pidFile = pidFile(runId);
        return "p=$(" + ifFile(pidFile, "cat") + ") || exit 0; "
                + "kill -TERM -- -\"$p\" 2>/dev/null; "
                + "command -v pkill >/dev/null && pkill -TERM -s \"$p\"; "
                + "sleep 3; kill -KILL -- -\"$p\" 2>/dev/null; "
                + "command -v pkill >/dev/null && pkill -KILL -s \"$p\"; "
                + "rm -f " + pidFile;
    }

    /** Where a run's session id is kept while it runs (a path the shell expands, unquoted). */
    private static String pidFile(String runId) {
        return RUN_DIR + "/" + runId + ".pid";
    }

    /** Single-quote {@code value} for a POSIX shell. */
    public static String quote(String value) {
        return Container.shellQuote(value);
    }

    /**
     * Append {@code export K='v'} for each entry, each followed by {@code separator}, refusing a
     * name that is not a shell identifier (it would otherwise be interpreted by the shell).
     */
    public static void appendExports(StringBuilder sb, Map<String, String> env, String separator) {
        for (var e : env.entrySet()) {
            if (!ENV_KEY.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("invalid environment variable name: " + e.getKey());
            }
            sb.append("export ").append(e.getKey()).append('=').append(quote(e.getValue())).append(separator);
        }
    }

    /** Ids of runs and tasks are embedded in paths and unit names: lowercase, digits, dashes. */
    public static void requireId(String id) {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("bad id: " + id);
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
    public static String ifFile(String file, String command) {
        var quoted = "\"" + file + "\"";
        return "{ [ -f " + quoted + " ] && { [ -f /dev/fd/3 ] && " + command + " <&3; } 3<> " + quoted + "; } 2>/dev/null";
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
    public static String writeAside(String dir, String name, String command) {
        return "{ w=$(mktemp \"" + dir + "/.tmp.XXXXXX\") && { " + command + " > \"$w\" && mv -fT \"$w\" \"" + dir + "/" + name
                + "\" || { rm -f \"$w\"; false; }; }; }";
    }
}
