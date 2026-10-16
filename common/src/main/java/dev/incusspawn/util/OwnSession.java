package dev.incusspawn.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts a command as the leader of a new session, under the pid the caller gets: out of the
 * starting command's process group and off its terminal, so a hangup or a Ctrl+C there does not
 * reach it (DESIGN.md "The macOS VM outlives whatever started it", #971, #1425).
 * <p>
 * Java cannot call {@code setsid}. Linux has the {@code setsid} command, which execs the command in
 * its own place when it is not a process group leader, as a child of the JVM never is. macOS ships
 * none, so perl calls {@code setsid} and then execs the command. Without either the command is left
 * as it is, and runs in the caller's session as it used to.
 */
public final class OwnSession {

    private static final List<Path> SETSID = List.of(Path.of("/usr/bin/setsid"), Path.of("/bin/setsid"));
    private static final Path PERL = Path.of("/usr/bin/perl");
    private static final long TERM_MILLIS = 2_000;
    private static final long REAP_MILLIS = 5_000;

    /** What {@link #start} started and is still running: stopped when isx exits. */
    private static final Set<Process> RUNNING = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean STOP_ON_EXIT = new AtomicBoolean();
    private static final AtomicBoolean EXITING = new AtomicBoolean();

    private OwnSession() {}

    /** Whether {@link #command} can start a session here. */
    public static boolean available() {
        return !wrapper().isEmpty();
    }

    /** The command that runs {@code cmd} as the leader of a new session, or {@code cmd} when nothing can. */
    public static List<String> command(List<String> cmd) {
        return prefixed(wrapper(), cmd);
    }

    /** macOS's way, apart so that Linux tests run it too. */
    static List<String> viaPerl(List<String> cmd) {
        return prefixed(perl(), cmd);
    }

    /**
     * Makes {@code builder} start its command through {@link #command}. The user's perl settings are
     * for their scripts, and can stop the one-liner from loading, so the perl wrapper runs without them.
     */
    public static ProcessBuilder apply(ProcessBuilder builder) {
        var wrapper = wrapper();
        builder.command(prefixed(wrapper, builder.command()));
        if (wrapper.equals(perl())) {
            builder.environment().remove("PERL5OPT");
            builder.environment().remove("PERL5LIB");
        }
        return builder;
    }

    private static List<String> prefixed(List<String> wrapper, List<String> cmd) {
        var wrapped = new ArrayList<>(wrapper);
        wrapped.addAll(cmd);
        return wrapped;
    }

    /**
     * Starts {@code builder}'s command through {@link #apply}, for a host process isx waits on: out of
     * the terminal's process group, the Ctrl+C that ends isx no longer stops it, so isx stops it, and
     * whatever it started, when it exits first (#1425).
     */
    public static Process start(ProcessBuilder builder) throws IOException {
        var process = apply(builder).start();
        if (STOP_ON_EXIT.compareAndSet(false, true)) {
            try {
                Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(OwnSession::stopOnExit));
            } catch (IllegalStateException exiting) {
                EXITING.set(true); // the hooks are already running and take no more
            }
        }
        RUNNING.add(process);
        process.onExit().thenRun(() -> RUNNING.remove(process));
        // Started while the hook stops the others, by a thread that outlived their failure: it would outlive isx.
        if (EXITING.get()) {
            stop(List.of(process));
            throw new IOException("isx is exiting");
        }
        return process;
    }

    private static void stopOnExit() {
        EXITING.set(true);
        stopRunning();
    }

    /** Stops everything {@link #start} started that is still running. */
    static void stopRunning() {
        while (!RUNNING.isEmpty()) stop(List.copyOf(RUNNING));
    }

    /**
     * Stops {@code processes} and every process they started, then waits (bounded) until they are
     * gone: a SIGTERM first, so each can clean up (Git removes its temporary packs and locks), then a
     * SIGKILL for whatever is left. Waits through interrupts and says whether one came; the caller
     * restores the flag once its own cleanup is done.
     */
    public static boolean stop(List<Process> processes) {
        var tree = new ArrayList<ProcessHandle>();
        for (var process : processes) {
            tree.addAll(process.descendants().toList());
            tree.add(process.toHandle());
        }
        tree.forEach(ProcessHandle::destroy);
        boolean interrupted = awaitExit(tree, TERM_MILLIS);
        // Even after an interrupt: a helper that ignores SIGTERM must not survive.
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        return awaitExit(tree, REAP_MILLIS) | interrupted;
    }

    private static boolean awaitExit(List<ProcessHandle> tree, long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        boolean interrupted = false;
        for (var handle : tree) {
            while (true) {
                try {
                    handle.onExit().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (Exception gone) {
                    break;
                }
            }
        }
        return interrupted;
    }

    /** setsid where there is one, else perl, else nothing; looked up per call, never at image build. */
    private static List<String> wrapper() {
        for (var setsid : SETSID) {
            // No "--": an old busybox setsid would exec it. No command here starts with a dash.
            if (Files.isExecutable(setsid)) return List.of(setsid.toString());
        }
        return Files.isExecutable(PERL) ? perl() : List.of();
    }

    private static List<String> perl() {
        return List.of(PERL.toString(), "-MPOSIX=setsid", "-e",
                "setsid(); exec { $ARGV[0] } @ARGV or die qq(exec $ARGV[0]: $!\\n)", "--");
    }
}
