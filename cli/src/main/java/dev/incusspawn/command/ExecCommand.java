package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.ExecScript;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.TerminalStreams;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * {@code isx exec <instance> -- <command> [args...]}: run one command in a running instance and wait
 * for it, for a script. A missing or stopped instance is refused, never started: starting and
 * stopping are {@code isx start}'s and {@code isx stop}'s. A running box whose boot lacks its secret
 * is given it, as a plain {@code ssh} does. The command runs as agentuser in a login shell, in the
 * template's workdir, with no terminal; its stdout and stderr are isx's, and its exit code is isx's.
 * isx's own failures exit {@link #ISX_FAILED}, as {@code docker run}'s do: before the command runs, an
 * exec that fails under it, and one Incus gives no exit code for.
 *
 * <p>It runs through {@link ExecScript}, in a session of its own, so the whole process tree is
 * killed whenever isx stops waiting for it: when isx is interrupted, when Incus gives no exit code
 * (the wait's ceiling, a lost operation), when the exec fails under it, and when what reads isx's
 * stdout has gone. A command nobody waits for must not keep running.
 */
@CommandDefinition(
        name = "exec",
        description = "Run a command in a running instance and wait for it",
        generateHelp = true
)
public class ExecCommand extends BaseCommand {

    /** isx's exit code when it fails instead of the command; the command's own otherwise. */
    static final int ISX_FAILED = 125;

    /** The exit code once what reads isx's stdout has gone: a shell's for a command SIGPIPE killed. */
    static final int OUTPUT_CLOSED = 128 + 13;

    @Arguments(completer = Completers.Instance.class,
            description = "The instance, then -- and the command with its arguments")
    List<String> args;

    @Option(name = "cwd", description = "Directory to run in, relative to agentuser's home unless absolute"
            + " (default: the template's workdir)")
    String cwd;

    @OptionList(name = "env", description = "Environment variable for the command, as NAME=value; repeatable")
    List<String> env;

    @Option(shortName = 'i', name = "stdin", hasValue = false,
            description = "Pass standard input on to the command, so it reads what you pipe into isx exec; without -i it does not read any input")
    boolean stdin;

    @Override
    protected CommandResult doExecute() throws Exception {
        return exec(RuntimeServices.incus(), stdin ? TerminalStreams.in() : null, TerminalStreams.out(), TerminalStreams.err());
    }

    CommandResult exec(IncusClient incus, InputStream in, OutputStream out, OutputStream err) throws Exception {
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
        String runId = runId();
        try {
            var environment = environment(env);
            var instance = incus.instanceMetadataOrThrow(name);
            if (instance == null) {
                BuildOutput.error("no instance named '" + shown + "' found.", "Run 'isx list' to see available instances.");
                return CommandResult.valueOf(ISX_FAILED);
            }
            if (!"Running".equals(instance.path("status").asText(""))) {
                BuildOutput.error(shown + " is not running.", "Start it with 'isx start " + shown + "'.");
                return CommandResult.valueOf(ISX_FAILED);
            }
            // A box Incus restarted by itself lacks its secret, without which the proxy refuses its proofs.
            // One isx did not make (no parent stamp, which isx shell refuses) is never written isx's
            // secret, and a delivery that fails is warned, never refusing the command, as a plain ssh does.
            if (!instance.path("config").pathText(Metadata.PARENT, "").isEmpty()) {
                try {
                    withStdoutOnStderr(() -> InstanceLifecycle.giveSecretIfMissing(incus, name, instance, BuildOutput.bound()));
                } catch (RuntimeException e) {
                    BuildOutput.warn("could not give " + shown + " a new instance secret: " + OutputFormat.oneLine(failure(e)));
                }
            }
            var workdir = instance.path("config").path(Metadata.WORKDIR).asText("");
            script = ExecScript.build(runId, cwd != null ? GuestProvisioning.expandHome(cwd)
                            : workdir.isEmpty() ? "." : workdir,
                    environment, command, null);
        } catch (RuntimeException e) {
            BuildOutput.abandonStep();
            BuildOutput.error("could not run the command in " + shown + ": " + OutputFormat.oneLine(failure(e)));
            return CommandResult.valueOf(ISX_FAILED);
        }

        Runnable killRun = () -> kill(incus, name, runId);
        // Nobody reads the output any more (`isx exec box -- yes | head -1`): end the command now,
        // rather than when it ends by itself or the exec's wait gives up.
        var closed = new AtomicReference<Thread>();
        Runnable onClosed = () -> {
            // On a thread of its own: this is the exec's output reader, which must keep draining
            var killing = Thread.ofVirtual().unstarted(killRun);
            if (closed.compareAndSet(null, killing)) killing.start();
        };
        var guardedOut = new ClosedOutputGuard(out, onClosed);
        // A closed stderr drops stderr alone (`make 2>&- > build.log`): stdout is still read, and
        // a shell's SIGPIPE would end only a process writing to the closed stream, never make.
        var guardedErr = new ClosedOutputGuard(err, () -> {});
        // Interrupted (Ctrl+C, SIGTERM): end the command's process tree in the guest before isx exits.
        var hook = new Thread(killRun, "isx-exec-kill");
        Runtime.getRuntime().addShutdownHook(hook);
        int exit;
        try {
            exit = incus.execScriptAsUser(name, "agentuser", script, in, guardedOut, guardedErr);
        } catch (RuntimeException e) {
            // The exec failed (refused, its sockets did not open, the instance stopped under it):
            // whether the command started is unknown, so it is ended in case it did.
            killRun.run();
            BuildOutput.abandonStep();
            BuildOutput.error("could not run the command in " + shown + ": " + OutputFormat.oneLine(failure(e)));
            return CommandResult.valueOf(ISX_FAILED);
        } finally {
            removeHook(hook);
            guardedOut.flush();
            guardedErr.flush();
        }
        var killing = closed.get();
        if (killing != null) {
            killing.join(); // a virtual thread would not hold the JVM up for it
            return CommandResult.valueOf(OUTPUT_CLOSED);
        }
        if (exit < 0) {
            // Incus lost the operation or gave up waiting for it, or the instance stopped under it:
            // no exit code to pass on, and the command may still be running.
            killRun.run();
            BuildOutput.error("the command in " + shown + " ended without an exit code.");
            return CommandResult.valueOf(ISX_FAILED);
        }
        return CommandResult.valueOf(exit);
    }

    /**
     * A run id no other run in the guest has had: this process's pid, which the host reuses, and
     * a random part, so a later run never takes an earlier one's pid file for its own.
     */
    static String runId() {
        return "cli-" + ProcessHandle.current().pid() + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 1);
    }

    /** End the run's process tree in the guest; harmless once it has ended, and never throwing. */
    private static void kill(IncusClient incus, String name, String runId) {
        try {
            incus.execScriptAsUser(name, "agentuser", ExecScript.kill(runId), null, null, null);
        } catch (RuntimeException e) {
            // The instance stopped or Incus is gone: there is nothing left running to end
        }
    }

    /**
     * The command's output, noticing when what reads it has gone. A {@link PrintStream} keeps a
     * failed write to itself, and the JVM ignores SIGPIPE, so without this isx would write on into
     * a closed pipe. Once a write fails it calls {@code onClosed} and discards the rest, still
     * accepting it, so the exec's sockets keep draining and the guest never blocks on its output.
     */
    static final class ClosedOutputGuard extends OutputStream {
        private final OutputStream target;
        private final Runnable onClosed;
        private volatile boolean closed;

        ClosedOutputGuard(OutputStream target, Runnable onClosed) {
            this.target = target;
            this.onClosed = onClosed;
        }

        @Override
        public void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (closed) return;
            try {
                target.write(b, off, len);
                if (target instanceof PrintStream ps && ps.checkError()) failed();
            } catch (IOException e) {
                failed();
            }
        }

        @Override
        public void flush() {
            if (closed) return;
            try {
                target.flush();
                if (target instanceof PrintStream ps && ps.checkError()) failed();
            } catch (IOException e) {
                failed();
            }
        }

        private void failed() {
            closed = true;
            onClosed.run();
        }
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
