package dev.incusspawn;

import dev.incusspawn.util.HostPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Copies text to the clipboard of the person's own screen, beside {@link Platform#openUrl}: the
 * one place isx does, for the token guide of {@code isx init} and for a tool's copy-to-clipboard
 * action (#2594).
 */
public final class Clipboard {

    private Clipboard() {}

    /**
     * The command that takes text on its stdin for the clipboard of the person's own screen, or
     * null: none over SSH, where the clipboard a command here reaches is not the one the person
     * pastes from.
     *
     * @param env a variable's value, blank when unset
     * @param hasCommand whether a command is on this computer's path
     */
    public static List<String> command(UnaryOperator<String> env, Predicate<String> hasCommand) {
        if (!env.apply("SSH_CONNECTION").isBlank() || !env.apply("SSH_TTY").isBlank()) return null;
        if (Platform.isMacOS()) return hasCommand.test("pbcopy") ? List.of("pbcopy") : null;
        if (!env.apply("WAYLAND_DISPLAY").isBlank() && hasCommand.test("wl-copy")) return List.of("wl-copy");
        if (env.apply("DISPLAY").isBlank()) return null;
        if (hasCommand.test("xclip")) return List.of("xclip", "-selection", "clipboard");
        if (hasCommand.test("xsel")) return List.of("xsel", "--clipboard", "--input");
        return null;
    }

    /** {@link #command(UnaryOperator, Predicate)} for this process's environment and path. */
    public static List<String> command() {
        return command(Environment::strippedEnv, HostPath::isOnPath);
    }

    /** Copies {@code text} to the clipboard: false when there is no command for it or it failed. */
    public static boolean copy(String text) {
        return copy(command(), text);
    }

    /** Copies {@code text} with {@code command}: false when it is null, failed or took over 5 seconds. */
    public static boolean copy(List<String> command, String text) {
        if (command == null) return false;
        try {
            // Nothing of the command's is read: wl-copy and xclip leave a child holding their output open
            var process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try (var stdin = process.getOutputStream()) {
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
