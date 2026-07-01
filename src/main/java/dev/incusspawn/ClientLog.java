package dev.incusspawn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * File-only client-side diagnostic log.
 *
 * Unlike {@code ProxyLog} (which also writes to stderr — fine for the standalone proxy
 * daemon), this NEVER touches stdout/stderr, so it is safe to call from inside the TUI,
 * which owns the terminal. Best-effort: any logging failure is swallowed.
 *
 * Use it for expected-but-noisy events that should stay diagnoseable without surfacing to
 * the user — e.g. recycling a stale pooled connection. Genuine, actionable errors should
 * propagate as exceptions rather than be logged-and-swallowed here.
 */
public final class ClientLog {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private ClientLog() {}

    public static void debug(String message) { log("DEBUG", message); }
    public static void warn(String message)  { log("WARN", message); }

    private static void log(String level, String message) {
        var line = LocalDateTime.now().format(FMT) + " [" + level + "] " + message;
        try {
            var path = Environment.clientLogFile();
            Files.createDirectories(path.getParent());
            Files.writeString(path, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Diagnostics are best-effort; never let logging failures affect the operation
            // or the TUI.
        }
    }
}
