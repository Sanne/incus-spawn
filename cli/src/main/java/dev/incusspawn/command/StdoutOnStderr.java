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
        if (!active) return pb.start().waitFor();
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        var process = pb.start();
        try (var in = process.getInputStream()) {
            in.transferTo(System.err);
        }
        System.err.flush();
        return process.waitFor();
    }
}
