package dev.incusspawn.proxy;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * One launchd job, started and restarted through {@code launchctl}.
 * <p>
 * A loaded job is restarted in place with {@code kickstart -k}; only a changed plist is unloaded
 * and loaded again, and then not before launchd has finished removing the old job. A restart
 * has worked when {@code launchctl} said so and the job is running, not when {@code print} can
 * still find it. DESIGN.md says why: {@code bootout} returns while the job is still being torn
 * down, and until it is gone {@code bootstrap} fails although {@code print} succeeds (#916).
 * <p>
 * {@code kickstart -k} honours the plist's {@code ThrottleInterval}: asked to restart a job
 * that started less than that long ago, it blocks until the interval is over.
 */
final class LaunchdJob {

    /** Runs {@code launchctl} with the given arguments. The seam tests replace. */
    interface Launchctl {
        Result run(String... args);
    }

    /** What {@code launchctl} exited with and everything it printed. */
    record Result(int exitCode, String output) {
        boolean ok() { return exitCode == 0; }
    }

    /**
     * What {@link #start} or {@link #restart} left behind, named so a caller that needs the
     * reason — {@code installMacOs}, to pick between "the previous job had not finished
     * unloading" and the generic "not responding" — does not have to infer it from {@code
     * isActive()} after the fact. {@code isActive()} alone cannot tell the two apart: both leave
     * the job loaded, one because launchd is still tearing the old one down, the other because
     * {@code kickstart}/{@code bootstrap} succeeded and KeepAlive already relaunched a process
     * that is failing to come up (review on #916).
     */
    enum Outcome {
        RUNNING, STILL_UNLOADING, FAILED;

        boolean up() { return this == RUNNING; }
    }

    /**
     * How long an unloading job is given to be gone. launchd gives a process about five seconds
     * to exit before killing it, so the wait has to outlast that.
     */
    static final int UNLOAD_POLLS = 100;
    static final long POLL_MILLIS = 100;

    /** How long a job is given to show as running once {@code launchctl} has started it. */
    static final int RUNNING_POLLS = 20;

    /**
     * How long a job has to stay up, in the same process, to count as started: a proxy that
     * cannot start (no VM, a port taken, a bad configuration) is gone again well within this.
     */
    static final int SETTLE_POLLS = 5;

    /** What {@code kickstart} exits with for a job that is being unloaded: EALREADY. */
    private static final int BEING_UNLOADED = 37;

    private final Launchctl launchctl;
    private final Runnable pause;
    private final String domain;
    private final String target;
    private final Path plist;

    /**
     * @param domain the launchd domain the job lives in, such as {@code gui/501}
     * @param pause  waits {@link #POLL_MILLIS} between two looks at the job
     */
    LaunchdJob(Launchctl launchctl, Runnable pause, String domain, String label, Path plist) {
        this.launchctl = launchctl;
        this.pause = pause;
        this.domain = domain;
        this.target = domain + "/" + label;
        this.plist = plist;
    }

    /** Whether launchd knows the job, running or not. True also while it is being unloaded. */
    boolean isLoaded() {
        return launchctl.run("print", target).ok();
    }

    /**
     * Loads the job and starts it. Only reached when it is not already loaded: {@code
     * ProxyService.startService()} returns early whenever {@code isActive()} — which on macOS
     * means loaded, including a job that is being unloaded — is already true, and the "loaded but
     * not responding" case goes through {@link #restart} instead (review on #916).
     */
    Outcome start(Consumer<String> log) {
        return load(log);
    }

    /**
     * Restart the job so that it runs what the plist now says.
     *
     * @param plistChanged the plist on disk is not the one the job was loaded from, so launchd
     *                     has to read it again, which only unloading and loading does
     */
    Outcome restart(boolean plistChanged, Consumer<String> log) {
        if (!isLoaded()) return load(log);
        if (!plistChanged) return kick(log, "kickstart", "-k", target);

        // The result of bootout says nothing useful: it is 0 as soon as the job was told to go.
        launchctl.run("bootout", target);
        return awaitUnloaded(log) ? load(log) : Outcome.STILL_UNLOADING;
    }

    /**
     * Starts or restarts a loaded job in place. launchd refuses that for a job it is already
     * unloading, as after {@code isx proxy stop} on a proxy that will not exit; that one is
     * waited out and loaded afresh. Any other refusal leaves the job as it is.
     */
    private Outcome kick(Consumer<String> log, String... kickstart) {
        var kicked = launchctl.run(kickstart);
        if (kicked.ok()) return awaitUp() ? Outcome.RUNNING : Outcome.FAILED;
        if (kicked.exitCode() != BEING_UNLOADED) {
            report(log, kickstart[0], kicked);
            return Outcome.FAILED;
        }
        return awaitUnloaded(log) ? load(log) : Outcome.STILL_UNLOADING;
    }

    /** Loads a job launchd does not have, and starts it. */
    private Outcome load(Consumer<String> log) {
        var up = step(log, "bootstrap", domain, plist.toString()) && step(log, "kickstart", target) && awaitUp();
        return up ? Outcome.RUNNING : Outcome.FAILED;
    }

    private boolean awaitUnloaded(Consumer<String> log) {
        if (await(UNLOAD_POLLS, () -> !isLoaded())) return true;
        log.accept("launchd is still unloading the proxy service; not loading it again.");
        return false;
    }

    /** The job came up and the process it came up as is still there a moment later. */
    private boolean awaitUp() {
        if (!await(RUNNING_POLLS, () -> runningPid() != null)) return false;
        var pid = runningPid();
        for (int i = 0; i < SETTLE_POLLS; i++) pause.run();
        return pid != null && pid.equals(runningPid());
    }

    private boolean await(int polls, java.util.function.BooleanSupplier condition) {
        for (int i = 0; i < polls; i++) {
            // An interrupted launchctl call answers nothing, which must not read as "unloaded".
            if (Thread.currentThread().isInterrupted()) return false;
            if (condition.getAsBoolean()) return true;
            pause.run();
        }
        return !Thread.currentThread().isInterrupted() && condition.getAsBoolean();
    }

    /**
     * What launchd says of the job's runs, such as {@code launchd: state = spawn scheduled, runs =
     * 1, last exit code = 1} for a job waiting out {@code ThrottleInterval} after a failed start,
     * or null when launchd does not know the job.
     */
    String lastRun() {
        var lines = printedLines();
        if (lines == null) return null;
        // The first "state" is the job's own; nested blocks such as endpoints have one too.
        var fields = java.util.stream.Stream.of("state = ", "runs = ", "last exit code = ")
                .flatMap(key -> lines.stream().filter(l -> l.startsWith(key)).limit(1))
                .toList();
        return fields.isEmpty() ? null : "launchd: " + String.join(", ", fields);
    }

    /** The pid of the job's process when launchd reports the job as running, else null. */
    private String runningPid() {
        var lines = printedLines();
        if (lines == null || !lines.contains("state = running")) return null;
        return lines.stream().filter(l -> l.startsWith("pid = ")).findFirst().orElse(null);
    }

    /** What {@code launchctl print} says of the job, line by line and stripped, or null. */
    private java.util.List<String> printedLines() {
        var printed = launchctl.run("print", target);
        return printed.ok() ? printed.output().lines().map(String::strip).toList() : null;
    }

    /** Runs one launchctl verb and, when it fails, says so with launchd's own message. */
    private boolean step(Consumer<String> log, String... args) {
        var result = launchctl.run(args);
        if (!result.ok()) report(log, args[0], result);
        return result.ok();
    }

    private static void report(Consumer<String> log, String verb, Result result) {
        var message = result.output().lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        log.accept("launchctl " + verb + " failed (exit " + result.exitCode() + ")"
                + (message.isEmpty() ? "." : ": " + message));
    }
}
