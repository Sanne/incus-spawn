package dev.incusspawn.command;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.HostPath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Runs an isx command again under {@code sg incus-admin} when this process lacks the group the
 * group database gives the user (#2167).
 * A process gets its groups at login, so a session that started before {@code isx init}'s root
 * script added the user to {@code incus-admin} is refused by the socket until it logs in again,
 * which an agent cannot do.
 * Init has its own continuation ({@link InitCommand#groupContinuation}), which starts its steps
 * after Incus Installation; this one is for every other command, {@code isx mcp} included.
 *
 * <p>Only the socket's permission is checked on every command, a {@code stat} and an
 * {@code access(2)} per socket path; {@code id} and {@code sg} run only when the socket refuses this process.
 */
public class GroupContinuation {

    /** Set in the environment of the command this runs under {@code sg}, which never continues again. */
    static final String CONTINUED_ENV = "ISX_GROUP_CONTINUED";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Runs {@code args}' command under {@code sg} if {@link #command} says to, and returns its exit
     * code, or -1 to run it in this process.
     */
    public static int runIfNeeded(String[] args) {
        return new GroupContinuation().continueIfNeeded(args);
    }

    int continueIfNeeded(String[] args) {
        var continuation = command(args);
        if (continuation == null) return -1;
        return run(continuation);
    }

    /**
     * The command that runs this one again in the {@code incus-admin} group, or null when it is
     * not needed or cannot help: the command is init, which continues by itself, or this process
     * is already a continuation, or the socket lets it in, or the user is not in the group, or
     * {@code sg} is missing.
     */
    List<String> command(String[] args) {
        if (args.length > 0 && args[0].equals("init")) return null;
        if (continued()) return null;
        if (!socketDenied()) return null;
        if (!configuredGroups().contains("incus-admin") || !hasSg()) return null;
        return sgCommand(ownCommandLine(), primaryGroup());
    }

    /**
     * {@code commandLine} run by {@code sg incus-admin}, or null when it is empty or the primary
     * group has no usable name.
     * The inner {@code sg} gives back the primary group the outer one took, keeping
     * {@code incus-admin} as a supplementary one, so what the command writes stays in the user's
     * group.
     */
    static List<String> sgCommand(List<String> commandLine, String primaryGroup) {
        // captureOutput keeps stderr: a gid with no name reads as an error message, not a group
        if (commandLine.isEmpty() || primaryGroup.isEmpty()
                || primaryGroup.chars().anyMatch(Character::isWhitespace)) return null;
        var command = "exec " + commandLine.stream().map(Container::shellQuote).collect(Collectors.joining(" "));
        return List.of("sg", "incus-admin", "-c",
                "exec sg " + Container.shellQuote(primaryGroup) + " -c " + Container.shellQuote(command));
    }

    boolean continued() {
        return System.getenv(CONTINUED_ENV) != null || System.getenv(InitCommand.CONTINUED_ENV) != null;
    }

    boolean socketDenied() {
        return IncusClient.socketDenied();
    }

    /** The groups the group database gives the user, which a new login would get. */
    List<String> configuredGroups() {
        return List.of(WHITESPACE.split(capture("id", "-nG", System.getProperty("user.name"))));
    }

    String primaryGroup() {
        return capture("id", "-gn");
    }

    boolean hasSg() {
        return HostPath.isOnPath("sg");
    }

    /** This process's argv, from {@code /proc}: the native binary, or {@code java} and its arguments. */
    List<String> ownCommandLine() {
        try {
            var cmdline = new String(Files.readAllBytes(Path.of("/proc/self/cmdline")));
            // Each argument ends in a NUL; an empty last argument is still one
            if (cmdline.endsWith("\0")) cmdline = cmdline.substring(0, cmdline.length() - 1);
            return cmdline.isEmpty() ? List.of() : List.of(cmdline.split("\0", -1));
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Runs {@code command} on this process's streams, marked as the continuation, and returns its
     * exit code, or -1 when it could not start, which leaves the command to this process.
     * Stdin and stdout pass straight through, so {@code isx mcp}'s protocol and a script reading
     * {@code --format=json} see only the continuation's.
     */
    int run(List<String> command) {
        BuildOutput.releaseTerminal();
        try {
            var pb = new ProcessBuilder(command).inheritIO();
            pb.environment().put(CONTINUED_ENV, "1");
            var child = pb.start();
            // A signal that ends this process (an MCP client's SIGTERM, or Ctrl-C, which the child
            // gets too) must not leave the child running alone or return the prompt before it ends
            var hook = new Thread(() -> stopAndWait(child), "isx-group-continuation");
            Runtime.getRuntime().addShutdownHook(hook);
            var exit = child.waitFor();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // the hook is already running and waits for the same child
            }
            return exit;
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    /**
     * Stops the continuation and waits for it, up to 30 seconds.
     * {@code sg} execs only while {@code SYSLOG_SG_ENAB} is off: Ubuntu turns it on, and then each
     * {@code sg} forks and waits to log the session's end, so the continued isx is a descendant of
     * {@code child}, not {@code child} itself, and a signal to {@code child} alone would leave it
     * running under PID 1.
     * Only the chain from {@code child} down to the continued isx is signalled, the isx first:
     * what that isx started is left to its own shutdown, which stops a foreground proxy or a Git
     * process in order.
     * A JVM already shutting down ignores the SIGTERM.
     */
    static void stopAndWait(Process child) {
        var isx = ProcessHandle.current().info().command();
        stopAndWait(child.toHandle(), p -> isx.isPresent() && p.info().command().equals(isx),
                java.time.Duration.ofSeconds(30));
    }

    static void stopAndWait(ProcessHandle child, java.util.function.Predicate<ProcessHandle> continued,
                            java.time.Duration timeout) {
        if (!child.isAlive()) return;
        var chain = chain(child, continued);
        chain.forEach(ProcessHandle::destroy);
        var deadline = System.nanoTime() + timeout.toNanos();
        for (var process : chain) {
            try {
                process.onExit().get(Math.max(0, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                return;
            }
        }
    }

    /**
     * {@code child} and its only-child descendants down to the first {@code continued} one, or to
     * the first with no single child, deepest first: the {@code sg} processes and the isx they run.
     */
    static List<ProcessHandle> chain(ProcessHandle child, java.util.function.Predicate<ProcessHandle> continued) {
        var chain = new java.util.ArrayList<ProcessHandle>();
        var process = child;
        while (true) {
            chain.addFirst(process);
            if (continued.test(process)) return chain;
            var children = process.children().toList();
            if (children.size() != 1) return chain;
            process = children.getFirst();
        }
    }

    private static String capture(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            return output;
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
