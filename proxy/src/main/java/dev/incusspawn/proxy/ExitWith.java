package dev.incusspawn.proxy;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * {@code --exit-with-pid}: a proxy run in the foreground by {@code isx proxy start} stops when that
 * CLI is gone (#923). The CLI's shutdown hook stops the proxy on SIGTERM, SIGINT or SIGHUP (#882),
 * but nothing runs on SIGKILL, the OOM killer or a JVM crash, and the proxy would live on as an
 * orphan holding its ports. An internal argument: only {@code isx proxy start} sets it, it is not
 * in {@code --help}, and the service units never pass it.
 */
final class ExitWith {

    static final String ARG = "--exit-with-pid";

    private static final Duration CHECK_EVERY = Duration.ofSeconds(1);

    /** Deeper than any real process tree; only there so a cycle cannot loop forever. */
    private static final int MAX_DEPTH = 256;

    private ExitWith() {}

    /**
     * Watch the process {@code args} names after {@link #ARG}, if any, and stop this one through
     * its shutdown hooks (as on SIGTERM) once it is gone.
     */
    static void watchIfAsked(String... args) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (!args[i].equals(ARG)) continue;
            long pid = Long.parseLong(args[i + 1]);
            watch(() -> isAncestor(pid), CHECK_EVERY, () -> {
                ProxyLog.warn("isx proxy start (pid " + pid + ") is gone; stopping");
                System.exit(0);
            });
            return;
        }
    }

    /**
     * Run {@code exit} once {@code cliAlive} is false: on this thread if it already is, since the
     * CLI may die before the proxy gets here, else from a daemon thread checking {@code every}.
     */
    static void watch(BooleanSupplier cliAlive, Duration every, Runnable exit) {
        if (!cliAlive.getAsBoolean()) {
            exit.run();
            return;
        }
        Thread.ofPlatform().daemon().name("exit-with-pid").start(() -> {
            try {
                while (cliAlive.getAsBoolean()) Thread.sleep(every);
            } catch (InterruptedException e) {
                return;
            }
            exit.run();
        });
    }

    /**
     * Whether {@code pid} is this process's parent, or further up, as the CLI is when a launcher
     * that does not {@code exec} sits between them. Not whether it is alive: a SIGKILLed CLI that
     * nobody reaps stays a zombie, which {@link ProcessHandle#onExit()} counts as alive, but its
     * children are reparented the moment it dies, so it stops being an ancestor at once.
     */
    static boolean isAncestor(long pid) {
        Optional<ProcessHandle> next = ProcessHandle.current().parent();
        for (int depth = 0; next.isPresent() && depth < MAX_DEPTH; depth++) {
            if (next.get().pid() == pid) return true;
            next = next.get().parent();
        }
        return false;
    }
}
