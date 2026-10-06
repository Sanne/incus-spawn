package dev.incusspawn.mcp;

import dev.incusspawn.incus.Container;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The shell scripts {@code exec} runs inside an instance, as agentuser in a login shell.
 *
 * <p>The command runs in its own session ({@code setsid}), whose id is written to a pid file, so
 * the whole process tree can be killed from a second exec when the client cancels -- a command
 * the agent stopped waiting for must not keep running unowned. Every value the agent supplies
 * is single-quoted; nothing it sends is ever interpreted by a shell other than its own command's.
 */
final class ExecScript {

    static final String RUN_DIR = "$HOME/.isx-mcp/run";
    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");

    private ExecScript() {}

    /**
     * @param cwd            directory to run in; relative to agentuser's home if not absolute
     * @param timeoutSeconds null for no limit: how long a command may run is the agent's call
     */
    static String build(String runId, String cwd, Map<String, String> env, String command,
                        Integer timeoutSeconds) {
        requireId(runId);
        var sb = new StringBuilder();
        sb.append("mkdir -p ").append(RUN_DIR).append(" && cd -- ").append(quote(cwd)).append(" || exit 125; ");
        appendExports(sb, env, "; ");
        // $$ of the inner bash is the new session's id (setsid made it the leader); exec keeps
        // it for the command, so the pid file names the session every descendant belongs to.
        sb.append("exec setsid --wait bash -c ")
                .append(quote("echo $$ > " + RUN_DIR + "/" + runId + ".pid; exec \"$@\""))
                .append(" isx-exec ");
        if (timeoutSeconds != null) {
            if (timeoutSeconds <= 0) throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "timeout_seconds must be positive");
            sb.append("timeout --kill-after=10s ").append(timeoutSeconds).append("s ");
        }
        sb.append("bash -c ").append(quote(command));
        return sb.toString();
    }

    /** Kill the process tree of a running {@link #build} script, TERM first, then KILL. */
    static String kill(String runId) {
        requireId(runId);
        var pidFile = RUN_DIR + "/" + runId + ".pid";
        return "p=$(" + TaskScripts.readFile(pidFile) + ") || exit 0; "
                + "kill -TERM -- -\"$p\" 2>/dev/null; "
                + "command -v pkill >/dev/null && pkill -TERM -s \"$p\"; "
                + "sleep 3; kill -KILL -- -\"$p\" 2>/dev/null; "
                + "command -v pkill >/dev/null && pkill -KILL -s \"$p\"; "
                + "rm -f " + pidFile;
    }

    /** Single-quote {@code value} for a POSIX shell. */
    static String quote(String value) {
        return Container.shellQuote(value);
    }

    /**
     * Append {@code export K='v'} for each entry, each followed by {@code separator}, refusing a
     * name that is not a shell identifier (it would otherwise be interpreted by the shell).
     */
    static void appendExports(StringBuilder sb, Map<String, String> env, String separator) {
        for (var e : env.entrySet()) {
            if (!ENV_KEY.matcher(e.getKey()).matches()) {
                throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "invalid environment variable name: " + e.getKey());
            }
            sb.append("export ").append(e.getKey()).append('=').append(quote(e.getValue())).append(separator);
        }
    }

    /** Ids of runs and tasks are embedded in paths and unit names: lowercase, digits, dashes. */
    static void requireId(String id) {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("bad id: " + id);
    }
}
