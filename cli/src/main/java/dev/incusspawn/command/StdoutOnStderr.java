package dev.incusspawn.command;

import java.util.concurrent.Callable;

/**
 * Points {@code System.out} at stderr while a command in a machine format gets its answer
 * (#1036): stdout holds only its results, so whatever prints progress or notes on the way there
 * goes to stderr. A gate of {@code RawOutputGateTest}, as {@code StdioGuard} is for {@code isx mcp}.
 */
final class StdoutOnStderr {

    private StdoutOnStderr() {}

    /** Whether {@link #during} is running: a child handed the terminal must not write to stdout either. */
    private static volatile boolean active;

    static <T> T during(Callable<T> work) throws Exception {
        var out = System.out;
        var wasActive = active;
        System.setOut(System.err);
        active = true;
        try {
            return work.call();
        } finally {
            active = wasActive;
            System.setOut(out);
        }
    }

    /**
     * Runs {@code pb} and waits for it, its stdout copied to stderr while {@link #during} runs: a
     * child given this process's streams ({@code sudo dnf install}, say) would otherwise write its
     * output into the results a script reads on stdout (#2075).
     */
    static int run(ProcessBuilder pb) throws java.io.IOException, InterruptedException {
        var copied = pipesStdout(pb);
        var process = pb.start();
        if (copied) copyStdout(process).join();
        return process.waitFor();
    }

    /**
     * Whether a child started from {@code pb} must have its stdout copied: while {@link #during}
     * runs, its stdout becomes a pipe, which {@link #copyStdout} drains to this process's stdout,
     * pointed at stderr. A child that writes its own stdout would otherwise reach the results.
     */
    static boolean pipesStdout(ProcessBuilder pb) {
        if (!active) return false;
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        return true;
    }

    /** Drains {@code process}'s stdout to {@code System.out}, which {@link #during} points at stderr, in a thread of its own. */
    static Thread copyStdout(Process process) {
        var sink = System.out;
        return Thread.startVirtualThread(() -> {
            try (var in = process.getInputStream()) {
                in.transferTo(sink);
            } catch (java.io.IOException e) {
                // The child's output is progress for a person; losing its end loses nothing a script reads
            }
            sink.flush();
        });
    }
}
