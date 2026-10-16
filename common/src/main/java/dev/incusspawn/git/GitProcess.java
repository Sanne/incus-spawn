package dev.incusspawn.git;

import dev.incusspawn.config.GitTransferConfig;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.OwnSession;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * One host-side Git process under a {@link GitTransferConfig}: non-interactive, its progress read
 * into a bounded tail, stopped when it stalls or overruns, and its whole process tree killed on the
 * way out, so a stalled helper cannot outlive the wait or hold the caller (#1343). Unless it may
 * prompt, it leads a session of its own, out of reach of the terminal's Ctrl+C, and isx stops it
 * when it exits (#1425).
 */
final class GitProcess {
    private GitProcess() {}

    enum Status { OK, FAILED, STALLED, TIMED_OUT, INTERRUPTED }

    /**
     * @param diagnostics the last lines Git wrote, bounded by {@link #TAIL_LINES} and {@link #MAX_LINE}
     * @param stopReason  why it was stopped, null when Git ran to completion
     * @param ssh         what isx did to keep SSH from prompting; null when nothing
     */
    record Result(Status status, String diagnostics, String stopReason, PreparationGit.SshSetup ssh) {
        boolean success() { return status == Status.OK; }
    }

    /** {@code line} is Git's latest progress or message line, null before the first. */
    record Progress(Duration elapsed, String line) {
        String summary() {
            var time = BuildOutput.formatElapsed(elapsed.toMillis());
            return line == null ? time : time + "  " + line;
        }
    }

    static final int TAIL_LINES = 20;
    static final int MAX_LINE = 200;
    private static final long POLL_MILLIS = 250;
    private static final long CPU_SAMPLE_NANOS = TimeUnit.SECONDS.toNanos(1);
    /**
     * The share of a quiet spell Git and its helpers must spend on the CPU for it to count as work.
     * An idle helper is not quite idle: {@code git-remote-http} polls curl and gains a clock tick a
     * minute or so while its server says nothing (0.02%), so any change at all would hide every
     * stall. Quiet work such as resolving deltas runs near 100%.
     */
    static final double BUSY_CPU_SHARE = 0.05;
    private static final long READER_MILLIS = 2_000;
    private static final Pattern CSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");

    /**
     * Runs {@code builder} on {@code repo} (null when none), with SSH as
     * {@link PreparationGit#nonInteractiveSsh} sets it for {@code forceNoPrompt}, reading its stderr (or its merged output) as progress. Its stdin becomes
     * {@code /dev/null} and a separate stdout is discarded, unless redirected. {@code progress} is called a few times a second while Git runs,
     * on the calling thread. Interruption stops Git, returns {@link Status#INTERRUPTED} and keeps the
     * interrupt flag set.
     * <p>
     * Git leads a session of its own ({@link OwnSession}), so the terminal's Ctrl+C reaches isx alone,
     * and isx decides: a build waits for a second one (#1415, #1425). Only {@code update-all}'s, run
     * with the user's SSH command as it is ({@code repo} given, not {@code forceNoPrompt}), keeps the
     * terminal that command may ask on.
     */
    static Result run(ProcessBuilder builder, Path repo, boolean forceNoPrompt, GitTransferConfig limits,
                      Consumer<Progress> progress) throws IOException {
        var ssh = nonInteractive(builder, repo, forceNoPrompt);
        var tail = new Tail();
        long start = System.nanoTime();
        // Only update-all's Git runs the user's SSH command as it is, which may ask on the terminal.
        boolean mayPrompt = !forceNoPrompt && repo != null;
        var process = mayPrompt ? builder.start() : OwnSession.start(builder);
        var stream = builder.redirectErrorStream() ? process.getInputStream() : process.getErrorStream();
        var reader = Thread.ofPlatform().daemon().name("git-output").start(() -> tail.read(stream));
        Status stopped = null;
        String reason = null;
        try {
            long lastActivity = start, lastCpuSample = start, lastChars = 0;
            // The tree's CPU time and I/O at the previous sample; null when the platform cannot tell.
            var previous = limits.stall() == null ? null : activity(process, start);
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                long now = System.nanoTime();
                if (tail.chars() != lastChars) {
                    lastChars = tail.chars();
                    lastActivity = now;
                }
                // Sampling scans the process table: only when a stall could be declared and output is quiet.
                if (limits.stall() != null && now - lastActivity >= CPU_SAMPLE_NANOS
                        && now - lastCpuSample >= CPU_SAMPLE_NANOS) {
                    lastCpuSample = now;
                    var sample = activity(process, now);
                    // Against the previous sample, a second ago (or from before output that just stopped,
                    // which only starts the stall clock a second later): a burst of work late in a long
                    // quiet spell is not diluted by the spell.
                    if (sample != null && previous != null && sample.busierThan(previous)) lastActivity = now;
                    previous = sample;
                }
                // Compared as Durations: a bound of centuries must not overflow nanoseconds.
                if (limits.timeout() != null && Duration.ofNanos(now - start).compareTo(limits.timeout()) >= 0) {
                    stopped = Status.TIMED_OUT;
                    reason = "timed out after " + describe(limits.timeout());
                    break;
                }
                // Without CPU times, silence alone proves nothing: only the total limit applies.
                if (limits.stall() != null && previous != null
                        && Duration.ofNanos(now - lastActivity).compareTo(limits.stall()) >= 0) {
                    stopped = Status.STALLED;
                    reason = "stalled: no output, I/O or CPU work for " + describe(limits.stall());
                    break;
                }
                progress.accept(new Progress(Duration.ofNanos(now - start), tail.latest()));
            }
        } catch (InterruptedException e) {
            stopped = Status.INTERRUPTED;
            reason = "interrupted";
        } finally {
            // Waits below must not be cut short by the interrupt being handled; it is restored after.
            boolean interrupted = Thread.interrupted() || stopped == Status.INTERRUPTED;
            if (process.isAlive()) interrupted |= OwnSession.stop(List.of(process));
            interrupted |= joinUninterruptibly(reader, READER_MILLIS);
            // An orphaned helper could still hold the pipe open: never let it hold the caller.
            if (reader.isAlive()) closeQuietly(stream);
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (stopped != null) return new Result(stopped, tail.text(), reason, ssh);
        return new Result(process.exitValue() == 0 ? Status.OK : Status.FAILED, tail.text(), null, ssh);
    }

    /** Says what isx did to SSH, or null when nothing. */
    private static PreparationGit.SshSetup nonInteractive(ProcessBuilder builder, Path repo, boolean forceNoPrompt) {
        var env = builder.environment();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GCM_INTERACTIVE", "never");
        var ssh = PreparationGit.nonInteractiveSsh(env, repo, forceNoPrompt);
        if (builder.redirectInput() == ProcessBuilder.Redirect.PIPE) {
            builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        }
        // Nothing reads a separate stdout pipe: once full, it would block Git.
        if (!builder.redirectErrorStream() && builder.redirectOutput() == ProcessBuilder.Redirect.PIPE) {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        return ssh;
    }

    /** One process's CPU time and I/O (reads and writes, sockets included; Linux only, else 0). */
    record Usage(long cpuNanos, long ioBytes) {}

    /**
     * The usage of Git and its live helpers at {@code atNanos}, by process, so a helper that exits
     * neither looks like work nor takes the others' with it.
     */
    record Activity(long atNanos, Map<Long, Usage> byPid) {
        private static final Usage NONE = new Usage(0, 0);

        /**
         * Any I/O since {@code before} (a busy server's keepalives cost the client almost no CPU), or
         * CPU for at least {@link #BUSY_CPU_SHARE} of the time since.
         */
        boolean busierThan(Activity before) {
            long cpu = 0;
            for (var entry : byPid.entrySet()) {
                var was = before.byPid.getOrDefault(entry.getKey(), NONE);
                if (entry.getValue().ioBytes() > was.ioBytes()) return true;
                cpu += Math.max(0, entry.getValue().cpuNanos() - was.cpuNanos());
            }
            return cpu >= BUSY_CPU_SHARE * (atNanos - before.atNanos);
        }
    }

    /** Null when the platform reports no CPU times. */
    private static Activity activity(Process process, long atNanos) {
        var tree = new ArrayList<ProcessHandle>(List.of(process.toHandle()));
        tree.addAll(process.descendants().toList());
        if (process.info().totalCpuDuration().isEmpty()) {
            // On macOS the JDK reports CPU time only for its own process: ask ps for the tree's instead.
            var cpu = psCpuNanos(tree);
            if (cpu == null) return null;
            var byPid = new HashMap<Long, Usage>();
            cpu.forEach((pid, nanos) -> byPid.put(pid, new Usage(nanos, 0)));
            return new Activity(atNanos, byPid);
        }
        var byPid = new HashMap<Long, Usage>();
        for (var handle : tree) {
            byPid.put(handle.pid(), new Usage(handle.info().totalCpuDuration().map(Duration::toNanos).orElse(0L),
                    io(handle.pid())));
        }
        return new Activity(atNanos, byPid);
    }

    /** The CPU time {@code ps} reports for each process of {@code tree}; null when it cannot say. */
    static Map<Long, Long> psCpuNanos(List<ProcessHandle> tree) {
        var pids = String.join(",", tree.stream().map(h -> Long.toString(h.pid())).toList());
        try {
            var ps = new ProcessBuilder("ps", "-o", "pid=,time=", "-p", pids)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            // Wait before reading: a line per process fits the pipe, and a stuck ps must not hold the poll.
            if (!ps.waitFor(5, TimeUnit.SECONDS)) {
                ps.destroyForcibly();
                return null;
            }
            // A helper that exited meanwhile makes ps fail; the others' times are still a sample.
            var byPid = new HashMap<Long, Long>();
            for (var line : new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                var fields = line.strip().split("\\s+");
                if (fields.length == 2) byPid.put(Long.parseLong(fields[0]), parsePsTime(fields[1]));
            }
            return byPid.isEmpty() ? null : byPid;
        } catch (IOException | NumberFormatException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** {@code ps}'s {@code time}: {@code [[dd-]hh:]mm:ss[.cc]} (macOS shows minutes and centiseconds). */
    static long parsePsTime(String time) {
        var value = time.strip();
        long days = 0;
        int dash = value.indexOf('-');
        if (dash >= 0) {
            days = Long.parseLong(value.substring(0, dash));
            value = value.substring(dash + 1);
        }
        double seconds = 0;
        for (var field : value.split(":")) seconds = seconds * 60 + Double.parseDouble(field);
        return TimeUnit.DAYS.toNanos(days) + Math.round(seconds * 1e9);
    }

    private static long io(long pid) {
        long bytes = 0;
        try {
            for (var line : Files.readAllLines(Path.of("/proc", Long.toString(pid), "io"))) {
                if (line.startsWith("rchar:") || line.startsWith("wchar:")) bytes += Long.parseLong(line.substring(6).strip());
            }
        } catch (IOException | NumberFormatException | SecurityException unavailable) {
            // Not Linux, or the process is gone: CPU time alone decides.
        }
        return bytes;
    }

    /** Joins {@code thread} for up to {@code millis}, through interrupts, and says whether one came. */
    private static boolean joinUninterruptibly(Thread thread, long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        boolean interrupted = false;
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try {
                thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private static void closeQuietly(InputStream stream) {
        try { stream.close(); } catch (IOException ignored) {}
    }

    static String describe(Duration duration) {
        long s = duration.toSeconds();
        if (s > 0 && s % 3600 == 0) return s / 3600 + "h";
        if (s > 0 && s % 60 == 0) return s / 60 + "m";
        return s + "s";
    }

    /**
     * Git's output split at {@code \r} (progress updates) and {@code \n}, without terminal escapes or
     * {@link OutputFormat#oneLine} controls (a remote's sideband text reaches the terminal). Keeps the latest segment and
     * the last {@link #TAIL_LINES} completed lines, each cut at {@link #MAX_LINE} characters.
     */
    static final class Tail {
        private final ArrayDeque<String> lines = new ArrayDeque<>();
        private final StringBuilder current = new StringBuilder();
        // A \r just ended the current line: a progress update, unless a \n follows (ssh ends its lines so).
        private boolean carriageReturn;
        private volatile String latest;
        private volatile long chars;

        void read(InputStream stream) {
            try (var in = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                var buffer = new char[4096];
                for (int n; (n = in.read(buffer)) != -1;) {
                    for (int i = 0; i < n; i++) accept(buffer[i]);
                    chars += n;
                }
            } catch (IOException closed) {
                // Closed by run() or by Git's death; what was read is kept.
            }
            // A last line without its \n still counts; a last progress update is already the latest.
            if (!carriageReturn) end(true);
        }

        private void accept(char c) {
            if (carriageReturn) {
                carriageReturn = false;
                if (c == '\n') {
                    end(true);
                    return;
                }
                current.setLength(0); // it was a progress update, already shown
            }
            if (c == '\r') {
                carriageReturn = true;
                show();
            } else if (c == '\n') {
                show();
                end(true);
            } else if (current.length() <= 4 * MAX_LINE) {
                current.append(c);
            }
        }

        /** {@code current} as the latest line, at once: a progress update is shown before what follows it is known. */
        private void show() {
            var line = clean(current);
            if (!line.isEmpty()) latest = line;
        }

        /** {@code current} becomes a completed line when {@code complete}. */
        private synchronized void end(boolean complete) {
            var line = clean(current);
            current.setLength(0);
            if (line.isEmpty() || !complete) return;
            latest = line;
            lines.addLast(line);
            if (lines.size() > TAIL_LINES) lines.removeFirst();
        }

        static String clean(CharSequence raw) {
            // Scrubbed before the cut: a credential cut short of its '@' no longer looks like one.
            var line = GitCommandException.scrub(OutputFormat.oneLine(CSI.matcher(raw).replaceAll(""))).strip();
            return line.length() > MAX_LINE ? line.substring(0, MAX_LINE - 1) + "…" : line;
        }

        String latest() { return latest; }
        long chars() { return chars; }

        /** The completed lines, then the progress line Git was showing when it stopped, if any. */
        synchronized String text() {
            var out = new ArrayList<>(lines);
            if (latest != null && (out.isEmpty() || !out.getLast().equals(latest))) out.add(latest);
            return String.join("\n", out);
        }
    }
}
