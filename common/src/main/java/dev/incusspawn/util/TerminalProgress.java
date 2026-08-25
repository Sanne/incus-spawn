package dev.incusspawn.util;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Runs a batch of independent tasks concurrently while rendering per-task
 * progress. On an ANSI terminal each task gets a line with an animated braille
 * spinner that turns into a green ✓ or red ✗ as it completes, making the
 * parallelism visible; otherwise it falls back to plain log lines (one per task,
 * emitted as each finishes).
 *
 * <p>The caller owns the task state (typically an {@code AtomicReferenceArray}):
 * {@code task} performs the work for index {@code i} and mutates that state,
 * while {@code animatedLine}/{@code plainLine} render it. Concurrency is bounded
 * to {@code maxConcurrency}.
 */
public final class TerminalProgress {

    private TerminalProgress() {}

    public static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    public static boolean isAnsiTerminal() {
        if (System.console() == null) return false;
        var term = System.getenv("TERM");
        return term != null && !term.equals("dumb");
    }

    /**
     * Run {@code taskCount} tasks, at most {@code maxConcurrency} at a time.
     *
     * @param task         performs work for a task index and updates shared state
     * @param animatedLine renders task {@code i} at spinner {@code frame} (ANSI mode)
     * @param plainLine    renders the final line for task {@code i}, or {@code null} to
     *                     emit nothing (non-ANSI mode)
     * @param plainSink    where plain-mode lines are written (e.g. {@code System.out::println})
     */
    public static void run(int taskCount, int maxConcurrency,
                           IntConsumer task,
                           BiFunction<Integer, Integer, String> animatedLine,
                           IntFunction<String> plainLine,
                           Consumer<String> plainSink) {
        if (taskCount == 0) return;
        if (isAnsiTerminal()) {
            animate(taskCount, maxConcurrency, task, animatedLine);
        } else {
            plain(taskCount, maxConcurrency, task, plainLine, plainSink);
        }
    }

    private static void animate(int taskCount, int maxConcurrency,
                                IntConsumer task,
                                BiFunction<Integer, Integer, String> line) {
        var out = System.out;
        for (int i = 0; i < taskCount; i++) {
            out.println(line.apply(i, 0));
        }
        out.flush();

        var lock = new Object();
        var frame = new int[]{0};
        Runnable redraw = () -> {
            synchronized (lock) {
                redrawLines(taskCount, line, out, frame[0]++);
            }
        };

        var ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "progress-anim");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(redraw, 80, 80, TimeUnit.MILLISECONDS);

        try {
            awaitAll(taskCount, maxConcurrency, task);
        } finally {
            ticker.shutdownNow();
            try { ticker.awaitTermination(200, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        synchronized (lock) {
            redrawLines(taskCount, line, out, 0);
        }
    }

    private static void redrawLines(int taskCount, BiFunction<Integer, Integer, String> line,
                                    PrintStream out, int frame) {
        var sb = new StringBuilder();
        sb.append("\033[").append(taskCount).append('A');
        for (int i = 0; i < taskCount; i++) {
            sb.append('\r').append("\033[2K");
            sb.append(line.apply(i, frame));
            sb.append('\n');
        }
        out.print(sb);
        out.flush();
    }

    private static void plain(int taskCount, int maxConcurrency, IntConsumer task,
                              IntFunction<String> line, Consumer<String> sink) {
        var printLock = new Object();
        awaitAll(taskCount, maxConcurrency, i -> {
            task.accept(i);
            if (line != null) {
                var text = line.apply(i);
                if (text != null) {
                    synchronized (printLock) { sink.accept(text); }
                }
            }
        });
    }

    private static void awaitAll(int taskCount, int maxConcurrency, IntConsumer task) {
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
            for (var f : futures) {
                try { f.get(); }
                catch (Exception e) { /* per-task failures are recorded in task state */ }
            }
        }
    }
}
