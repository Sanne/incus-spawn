package dev.incusspawn.util;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.IntConsumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Runs a batch of independent tasks concurrently while rendering per-task
 * progress. On an ANSI terminal each task gets a line with an animated braille
 * spinner that turns into a green ✓ or red ✗ as it completes, making the
 * parallelism visible; otherwise the caller reports each task as it finishes.
 *
 * <p>The caller owns the task state (typically an {@code AtomicReferenceArray}):
 * {@code task} performs the work for index {@code i} and mutates that state,
 * while {@code animatedLine} renders it and {@code plainDone} reports it. Concurrency is bounded
 * to {@code maxConcurrency}.
 *
 * <p>A task should record how it failed in that state; one that throws anyway, an {@link Error}
 * included, is rethrown by {@link #run} once every task has ended and been reported, so no failure
 * is lost with the thread that met it.
 */
public final class TerminalProgress {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TerminalProgress() {}

    public static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    static int terminalWidth() {
        try {
            var pb = new ProcessBuilder("stty", "size");
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectErrorStream(true);
            var p = pb.start();
            var output = new String(p.getInputStream().readAllBytes()).strip();
            if (p.waitFor(1, TimeUnit.SECONDS)) {
                var parts = WHITESPACE.split(output);
                if (parts.length >= 2) return Integer.parseInt(parts[1]);
            }
        } catch (Exception ignored) {}
        return 120;
    }

    static String truncateToWidth(String s, int maxWidth) {
        int visible = 0;
        boolean inEscape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\033') {
                inEscape = true;
            } else if (inEscape) {
                if (Character.isLetter(c)) inEscape = false;
            } else {
                if (visible >= maxWidth) {
                    return s.substring(0, i) + "\033[0m";
                }
                visible++;
            }
        }
        return s;
    }

    /**
     * Whether output may carry colour and cursor movement: a console, a {@code TERM} that is
     * not {@code dumb}, and no {@code NO_COLOR} (any non-empty value, per no-color.org).
     */
    public static boolean isAnsiTerminal() {
        return isAnsiTerminal(isTerminal(), System::getenv);
    }

    /** Whether a person reads the output on a terminal, with or without colour: its width then bounds a report. */
    static boolean isTerminal() {
        return !Headless.active() && TerminalStreams.hasConsole();
    }

    static boolean isAnsiTerminal(boolean console, UnaryOperator<String> env) {
        if (!console) return false;
        var noColor = env.apply("NO_COLOR");
        if (noColor != null && !noColor.isEmpty()) return false;
        var term = env.apply("TERM");
        return term != null && !term.equals("dumb");
    }

    /**
     * Run {@code taskCount} tasks, at most {@code maxConcurrency} at a time: animated on an ANSI
     * terminal, each line rendered by {@code animatedLine} for task {@code i} at spinner
     * {@code frame}. Off one, each task is reported as it ends, one at a time, through
     * {@code plainDone}, given the task's index: so a caller says how it ended by kind from its
     * state, rather than as a pre-rendered line.
     * What a task threw is rethrown once the batch is over: the first, the others suppressed by it.
     */
    public static void run(int taskCount, int maxConcurrency,
                           IntConsumer task,
                           BiFunction<Integer, Integer, String> animatedLine,
                           IntConsumer plainDone) {
        if (taskCount == 0) return;
        var thrown = isAnsiTerminal()
                ? animate(taskCount, maxConcurrency, task, animatedLine)
                : plainEach(taskCount, maxConcurrency, task, plainDone);
        if (thrown instanceof RuntimeException e) throw e;
        if (thrown instanceof Error e) throw e;
        if (thrown != null) throw new IllegalStateException(thrown);
    }

    /** Plain mode: each task, then {@code done} with its index, one at a time, even if it threw. */
    private static Throwable plainEach(int taskCount, int maxConcurrency, IntConsumer task, IntConsumer done) {
        var lock = new Object();
        return awaitAll(taskCount, maxConcurrency, i -> {
            try {
                task.accept(i);
            } finally {
                // Also for a task that threw: its state says how it ended
                synchronized (lock) { done.accept(i); }
            }
        });
    }

    private static Throwable animate(int taskCount, int maxConcurrency,
                                IntConsumer task,
                                BiFunction<Integer, Integer, String> line) {
        var out = TerminalStreams.out();
        int width = terminalWidth();
        for (int i = 0; i < taskCount; i++) {
            out.println(truncateToWidth(line.apply(i, 0), width));
        }
        out.flush();

        var lock = new Object();
        var frame = new int[]{0};
        Runnable redraw = () -> {
            synchronized (lock) {
                redrawLines(taskCount, line, out, frame[0]++, width);
            }
        };

        var ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "progress-anim");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(redraw, 80, 80, TimeUnit.MILLISECONDS);

        Throwable thrown;
        try {
            thrown = awaitAll(taskCount, maxConcurrency, task);
        } finally {
            ticker.shutdownNow();
            try { ticker.awaitTermination(200, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        synchronized (lock) {
            redrawLines(taskCount, line, out, 0, width);
        }
        return thrown;
    }

    private static void redrawLines(int taskCount, BiFunction<Integer, Integer, String> line,
                                    PrintStream out, int frame, int width) {
        var sb = new StringBuilder();
        sb.append("\033[").append(taskCount).append('A');
        for (int i = 0; i < taskCount; i++) {
            sb.append('\r').append("\033[2K");
            sb.append(truncateToWidth(line.apply(i, frame), width));
            sb.append('\n');
        }
        out.print(sb);
        out.flush();
    }

    /** Run every task and wait for all of them; what the first to throw threw, the others' suppressed, or null. */
    private static Throwable awaitAll(int taskCount, int maxConcurrency, IntConsumer task) {
        var limiter = new Semaphore(Math.max(1, maxConcurrency));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>(taskCount);
            for (int i = 0; i < taskCount; i++) {
                int idx = i;
                futures.add(executor.submit(() -> {
                    limiter.acquireUninterruptibly();
                    try { task.accept(idx); }
                    finally { limiter.release(); }
                }));
            }
            Throwable thrown = null;
            for (var f : futures) {
                while (true) {
                    try {
                        f.get();
                    } catch (ExecutionException e) {
                        if (thrown == null) thrown = e.getCause();
                        else if (e.getCause() != thrown) thrown.addSuppressed(e.getCause());
                    } catch (InterruptedException e) {
                        // Wait again, as the executor's close would, so what this task threw is not lost
                        continue;
                    } catch (java.util.concurrent.CancellationException e) {
                        // Nothing cancels a task; one that was has nothing to report
                    }
                    break;
                }
            }
            return thrown;
        }
    }
}
