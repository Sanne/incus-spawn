package dev.incusspawn.lifecycle;

import java.time.Duration;
import java.util.List;

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
    HostCommands SYSTEM = new SystemHostCommands();
}
