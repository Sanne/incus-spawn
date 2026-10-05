package dev.incusspawn;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The process-wide destination for warnings from code that has no caller to hand it a sink.
 * <p>
 * Taking a {@code Consumer<String>} from the caller stays the first choice (see CLAUDE.md).
 * This is for the rest: code several layers below any command, such as the
 * {@code ToolDefLoader} that {@code ToolProxyResolver} builds for account validation,
 * where threading a sink through every caller would mean changing all of them. Such code calls
 * {@link #warn} and whoever owns the terminal decides where warnings go: stderr by default, the
 * TUI's warning log while the TUI draws ({@link #redirect}).
 * <p>
 * Each {@link Channel} reports a distinct message once. The same definition is often loaded many
 * times in one command (every fresh loader re-reads it), and printing the same line each time
 * helps nobody. The memory belongs to the channel, not the process: a build the TUI runs on the
 * released terminal must print a broken file's warning even though the TUI already logged it.
 */
public final class Warnings {

    /** A destination for warnings, which passes each distinct message on once. */
    public static final class Channel {
        private final Consumer<String> out;
        private final Set<String> reported = ConcurrentHashMap.newKeySet();

        public Channel(Consumer<String> out) {
            this.out = out;
        }

        void report(String message) {
            if (reported.add(message)) {
                out.accept(message);
            }
        }

        /** Let every message be reported again, e.g. when the user explicitly reloads definitions. */
        public void forgetReported() {
            reported.clear();
        }
    }

    private static final Channel STDERR = new Channel(msg -> System.err.println("Warning: " + msg));

    private static volatile Channel current = STDERR;

    private Warnings() {}

    /** Report a warning, unless the current channel already reported the same message. */
    public static void warn(String message) {
        if (message == null || message.isBlank()) return;
        current.report(message);
    }

    /**
     * Let the current channel report every message again: for a long-running process that
     * re-reads definitions, as the proxy does on each config reload, so a problem still there
     * is reported again rather than once per process.
     */
    public static void forgetReported() {
        current.forgetReported();
    }

    /**
     * Send warnings to {@code to} until the returned handle is closed, which restores the
     * previous channel. Redirecting to the same channel again keeps what it already reported.
     */
    public static Redirect redirect(Channel to) {
        var previous = current;
        current = to;
        return () -> current = previous;
    }

    /** Restores the channel that was in place before {@link #redirect}. */
    @FunctionalInterface
    public interface Redirect extends AutoCloseable {
        @Override
        void close();
    }
}
