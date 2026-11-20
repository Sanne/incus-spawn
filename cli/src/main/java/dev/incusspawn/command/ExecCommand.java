package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.mcp.ExecScript;
import dev.incusspawn.tui.InstanceLockManager;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.TerminalStreams;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code isx exec <instance> -- <command> [args...]}: run one command in an instance and wait for
 * it, for a script. The instance is started and prepared as {@code isx start} does it
 * ({@link InstanceStart}), so the command finds its credential proofs, and then left running:
 * stopping it is {@code isx stop}'s job. The command runs as agentuser in a login shell, in the
 * template's workdir, with no terminal; its stdout and stderr are isx's, and its exit code is isx's.
 * isx's own failures, before the command runs, exit {@link #ISX_FAILED}, as {@code docker run}'s do.
 *
 * <p>It runs through {@code isx mcp}'s {@link ExecScript}, in a session of its own, so the whole
 * process tree is killed when isx is interrupted: a command nobody waits for must not keep running.
 */
@CommandDefinition(
        name = "exec",
        description = "Run a command in an instance, starting it if it is stopped, and wait for it",
        generateHelp = true
)
public class ExecCommand extends BaseCommand {

    /** isx's exit code when it fails before the command runs; the command's own otherwise. */
    static final int ISX_FAILED = 125;

    @Arguments(completer = Completers.Instance.class,
            description = "The instance, then -- and the command with its arguments")
    List<String> args;

    @Option(name = "cwd", description = "Directory to run in, relative to agentuser's home unless absolute"
            + " (default: the template's workdir)")
    String cwd;

    @OptionList(name = "env", description = "Environment variable for the command, as NAME=value; repeatable")
    List<String> env;

    @Option(shortName = 'i', name = "stdin", hasValue = false,
            description = "Pass isx's standard input to the command (otherwise it reads none)")
    boolean stdin;

    /** The preparation; {@link InstanceStart.Preparer#FOR_USE} but in tests. */
    InstanceStart.Preparer preparer = InstanceStart.Preparer.FOR_USE;

    @Override
    protected CommandResult doExecute() throws Exception {
        return exec(RuntimeServices.incus(), RuntimeServices.lockManager(),
                stdin ? TerminalStreams.in() : null, TerminalStreams.out(), TerminalStreams.err());
    }

    CommandResult exec(IncusClient incus, InstanceLockManager locks, InputStream in, OutputStream out,
                       OutputStream err) throws Exception {
        if (args == null || args.size() < 2) {
            BuildOutput.error("name an instance and the command to run in it.",
                    "Usage: isx exec <instance> [options] -- <command> [args...]",
                    "Example: isx exec my-box -- ./ci.sh");
            return CommandResult.valueOf(ISX_FAILED);
        }
        var name = args.getFirst();
        var command = args.subList(1, args.size()).stream().map(Container::shellQuote).collect(Collectors.joining(" "));
        var shown = OutputFormat.oneLine(name);
        String script;
        String runId = "cli-" + ProcessHandle.current().pid();
        try {
            var environment = environment(env);
            // stdout holds only the command's output: what the start says goes to stderr
            var workdir = withStdoutOnStderr(() -> {
                InstanceStart.start(incus, locks, name, BuildOutput.bound(), preparer, InstanceStart.WAIT);
                return incus.configGet(name, Metadata.WORKDIR);
            });
            script = ExecScript.build(runId, cwd != null ? cwd : workdir.isEmpty() ? "." : workdir,
                    environment, command, null);
        } catch (InstancePrep.Refused e) {
            if (!e.reported()) InstancePrep.reportRefusal(e.getMessage());
            return CommandResult.valueOf(ISX_FAILED);
        } catch (RuntimeException e) {
            BuildOutput.abandonStep();
            BuildOutput.error("could not run the command in " + shown + ": " + failure(e));
            return CommandResult.valueOf(ISX_FAILED);
        }

        // Interrupted (Ctrl+C, SIGTERM): end the command's process tree in the guest before isx exits.
        var kill = new Thread(() -> incus.execScriptAsUser(name, "agentuser", ExecScript.kill(runId), null, null, null),
                "isx-exec-kill");
        Runtime.getRuntime().addShutdownHook(kill);
        int exit;
        try {
            exit = incus.execScriptAsUser(name, "agentuser", script, in, out, err);
        } finally {
            removeHook(kill);
            out.flush();
            err.flush();
        }
        if (exit < 0) {
            // Incus lost the operation, or the instance stopped under it: no exit code to pass on
            BuildOutput.error("the command in " + shown + " ended without an exit code.");
            return CommandResult.valueOf(ISX_FAILED);
        }
        return CommandResult.valueOf(exit);
    }

    /** {@code --env} as names and values, refusing an entry without {@code =}. */
    static Map<String, String> environment(List<String> entries) {
        var result = new LinkedHashMap<String, String>();
        if (entries == null) return result;
        for (var entry : entries) {
            var eq = entry.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("--env takes NAME=value, not '" + OutputFormat.oneLine(entry) + "'");
            result.put(entry.substring(0, eq), entry.substring(eq + 1));
        }
        return result;
    }

    private static void removeHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // The JVM is already shutting down, and the hook is running: let it end the command
        }
    }
}
