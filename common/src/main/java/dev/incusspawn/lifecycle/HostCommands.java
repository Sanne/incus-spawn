package dev.incusspawn.lifecycle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The host programs a {@link HostIntegration} runs, such as {@code herdr}: a seam, so tests record
 * what would run rather than run it.
 */
public interface HostCommands {

    /** How a command ended: its exit status (-1 when it was killed for its time), and its output, stderr included. */
    record Result(int exit, String output) {
        public boolean ok() {
            return exit == 0;
        }
    }

    /** Whether {@code command} is an executable on this process's {@code PATH}; looked up, never run. */
    boolean onPath(String command);

    /**
     * Run {@code command} with no stdin, killed after {@code timeout}, and with
     * {@link dev.incusspawn.ssh.SshKeyManager#NO_START_ENV} set, so no ssh it runs starts an instance.
     */
    Result run(Duration timeout, List<String> command);

    /** The real host. */
    HostCommands SYSTEM = new HostCommands() {
        @Override
        public boolean onPath(String command) {
            return dev.incusspawn.util.HostPath.isOnPath(command);
        }

        @Override
        public Result run(Duration timeout, List<String> command) {
            try {
                var builder = new ProcessBuilder(command).redirectErrorStream(true)
                        .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
                builder.environment().put(dev.incusspawn.ssh.SshKeyManager.NO_START_ENV, "1");
                var process = builder.start();
                // Read while it runs, so a chatty command cannot fill the pipe and stall
                var output = new StringBuilder();
                var reader = Thread.ofVirtual().start(() -> {
                    // Appended as it is read, so what came before a daemon kept the pipe open is kept
                    try (var in = new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                        var chunk = new char[4096];
                        for (int n; (n = in.read(chunk)) >= 0; ) {
                            synchronized (output) {
                                output.append(chunk, 0, n);
                            }
                        }
                    } catch (IOException ignored) {
                        // The exit status says what happened
                    }
                });
                var deadline = System.nanoTime() + timeout.toNanos();
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    reader.join(1000);
                    // What it said before it hung says why, often
                    String said;
                    synchronized (output) {
                        var lines = output.toString().strip().lines().toList();
                        said = lines.isEmpty() ? "" : lines.getLast();
                    }
                    return new Result(-1, said + (said.isEmpty() ? "" : "\n") + "timed out after " + timeout.toSeconds() + " seconds");
                }
                // A daemon it left behind may hold the pipe open: the output is read within the same time
                if (!reader.join(Duration.ofNanos(Math.max(deadline - System.nanoTime(), 0)).plusSeconds(1))) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                }
                synchronized (output) {
                    return new Result(process.exitValue(), output.toString());
                }
            } catch (IOException e) {
                return new Result(-1, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(-1, "interrupted");
            }
        }
    };
}
