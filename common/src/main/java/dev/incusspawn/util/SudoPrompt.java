package dev.incusspawn.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs a host command whose output isx captures, freezing a live step's line only when its sudo
 * asks for a password (#2595).
 * <p>
 * A live step's line is redrawn in place, so before a sudo that asks, the line is frozen
 * ({@link BuildOutput#releaseTerminal}) and the prompt is not drawn over. A frozen line stays as it
 * was and the step's mark comes on a new line, so freezing for a sudo that asks nothing leaves a
 * stale {@code … label} above it. A sudo is therefore first run so that it cannot ask
 * ({@code sudo -n}); only when sudo itself says a password is required is the line frozen and the
 * command run again, free to ask on the terminal. Asking the command itself, not
 * {@code sudo -n true}, leaves no case where the two disagree, such as a sudoers rule for one
 * command or a credential that expires in between.
 * <p>
 * The {@code sudo -n} run is in the C locale, since sudo translates "a password is required"; a
 * command that runs then prints its own messages in it too.
 * <p>
 * It is only for a command whose output isx captures: a child that writes to the terminal itself
 * needs the line frozen whatever sudo does.
 */
public final class SudoPrompt {

    private SudoPrompt() {}

    /**
     * How a command ended: its exit code, what it printed on stdout (and stderr, when merged), and
     * what it printed on stderr when that was kept apart.
     */
    public record Ran(int exit, String output, String errors) {
        public Ran(int exit, String output) {
            this(exit, output, "");
        }
    }

    /** Starts one command and waits for it, its stderr merged into stdout or kept apart, and captured. */
    @FunctionalInterface
    public interface Runner {
        Ran run(List<String> command, Consumer<ProcessBuilder> setup, boolean cLocale, boolean merged)
                throws IOException, InterruptedException;
    }

    private static volatile Runner runner = SudoPrompt::start;

    /**
     * Runs {@code command}, set up by {@code setup} (its input, for one), with its output merged
     * and captured. A sudo runs first under {@code sudo -n}, and again with the terminal released
     * only when that one could not ask for the password.
     */
    public static Ran run(Consumer<ProcessBuilder> setup, String... command) throws IOException, InterruptedException {
        return run(setup, true, command);
    }

    /**
     * {@link #run}, with stderr kept apart in {@link Ran#errors}, for a command whose stdout is
     * content isx reads, such as a file it writes back: a sudo warning must not become part of it.
     */
    public static Ran read(Consumer<ProcessBuilder> setup, String... command) throws IOException, InterruptedException {
        return run(setup, false, command);
    }

    private static Ran run(Consumer<ProcessBuilder> setup, boolean merged, String... command)
            throws IOException, InterruptedException {
        Privilege.check(command);
        var asGiven = List.of(command);
        if (asGiven.isEmpty() || !asGiven.getFirst().equals("sudo")) return runner.run(asGiven, setup, false, merged);
        var unasked = new ArrayList<>(asGiven);
        unasked.add(1, "-n");
        var first = runner.run(unasked, setup, true, merged);
        // Succeeded, or ran and failed: running it again would only fail again
        if (first.exit() == 0 || !needsPassword(first.output() + "\n" + first.errors())) return first;
        BuildOutput.releaseTerminal();
        return runner.run(asGiven, setup, false, merged);
    }

    /**
     * Whether what {@code sudo -n} printed, in the C locale, says that it would have had to ask:
     * a line of sudo's own, in sudo's words or sudo-rs's (Ubuntu's sudo since 25.10), so a command
     * that ran and printed the phrase itself is not run a second time.
     */
    public static boolean needsPassword(String said) {
        return said.lines().map(String::strip).anyMatch(line -> line.startsWith("sudo")
                && (line.contains("a password is required") || line.contains("interactive authentication is required")));
    }

    /** Swaps in a test double for starting the commands, returning the one it replaced. */
    public static Runner replaceRunner(Runner replacement) {
        var previous = runner;
        runner = replacement;
        return previous;
    }

    private static Ran start(List<String> command, Consumer<ProcessBuilder> setup, boolean cLocale, boolean merged)
            throws IOException, InterruptedException {
        var pb = new ProcessBuilder(command);
        setup.accept(pb);
        if (cLocale) pb.environment().put("LC_ALL", "C");
        if (merged) {
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Ran(process.waitFor(), output);
        }
        // Kept apart in a file, so a full stderr pipe cannot stall the read of stdout
        var errorsFile = Files.createTempFile("isx-sudo-", ".err");
        try {
            pb.redirectErrorStream(false);
            pb.redirectError(errorsFile.toFile());
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var exit = process.waitFor();
            return new Ran(exit, output, new String(Files.readAllBytes(errorsFile), StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(errorsFile);
        }
    }
}
