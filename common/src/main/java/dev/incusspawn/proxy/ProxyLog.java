package dev.incusspawn.proxy;

import dev.incusspawn.Environment;

import java.io.IOException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ProxyLog {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static volatile Boolean debugOverride;
    private static volatile boolean stderrSuppressed;

    private ProxyLog() {}

    /**
     * Suppress stderr output while the TUI owns the terminal.  File logging continues
     * unconditionally so the lifecycle log stays complete.
     *
     * @return the previous setting, for a caller that puts it back
     */
    public static boolean setSuppressStderr(boolean suppress) {
        var previous = stderrSuppressed;
        stderrSuppressed = suppress;
        return previous;
    }

    public static boolean isDebugEnabled() {
        var v = debugOverride;
        return v != null ? v : Boolean.getBoolean("isx.proxy.debug");
    }

    public static void setDebugEnabled(boolean enabled) {
        debugOverride = enabled;
    }

    public static void debug(String message) {
        if (!isDebugEnabled()) return;
        logToFile("DEBUG", message);
    }

    /**
     * A line for stderr alone, never the lifecycle log: what a person watching the proxy may want,
     * such as a request's debug line, which a lifecycle log of starts and reloads does not keep.
     */
    public static void trace(String message) {
        if (stderrSuppressed) return;
        // No level of its own: such a line names its kind, as "[debug] ..." does
        System.err.println(now() + " " + message);
    }

    /**
     * {@link #trace}, then {@code failure}'s stack trace, on stderr alone: a request that failed
     * unexpectedly, whose trace a person reading the console wants and the lifecycle log does not.
     */
    public static void trace(String message, Throwable failure) {
        if (stderrSuppressed) return;
        trace(message);
        failure.printStackTrace(System.err);
    }

    /**
     * Lines the daemon shows as they are, on stdout: its help, its version and the banner it
     * starts with, which the console tee ({@link #teeConsoleTo}) keeps in the proxy log. The
     * proxy's one way to stdout (#1703), as {@link #trace} and the levels are to stderr.
     */
    public static void stdout(String... lines) {
        // Silent while the TUI draws, as stderr is
        if (stderrSuppressed) return;
        for (var line : lines) System.out.println(line);
    }

    /** A field of the start banner on {@link #stdout}: {@code "  Version:       0.4.0 (abc)"}, its label laid out here. */
    public static void field(String label, Object value) {
        stdout("  " + String.format("%-14s", label + ":") + " " + value);
    }

    /** An option of the help on {@link #stdout}: {@code "  --port <port>         Port the proxy serves instances on"}. */
    public static void option(String flag, String description) {
        stdout("  " + String.format("%-21s", flag) + " " + description);
    }

    /**
     * From here on, everything the daemon writes to stdout and stderr also goes to {@code
     * logFile}: its whole console, which {@code isx proxy logs} follows. Warns, and goes on,
     * when the file cannot be opened.
     */
    public static void teeConsoleTo(Path logFile) {
        try {
            Files.createDirectories(logFile.getParent());
            var fileOut = new FileOutputStream(logFile.toFile(), true);
            System.setOut(new PrintStream(new TeeOutputStream(System.out, fileOut), true));
            System.setErr(new PrintStream(new TeeOutputStream(System.err, fileOut), true));
        } catch (IOException e) {
            warn("Could not open log file " + logFile + ": " + e.getMessage());
        }
    }

    /** Writes every byte to the console and to a file. */
    public static final class TeeOutputStream extends OutputStream {
        private final OutputStream console;
        private final OutputStream file;

        public TeeOutputStream(OutputStream console, OutputStream file) {
            this.console = console;
            this.file = file;
        }

        @Override
        public void write(int b) throws IOException {
            console.write(b);
            file.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            console.write(b, off, len);
            file.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            console.flush();
            file.flush();
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }

    public static void info(String message) {
        log("INFO", message);
    }

    /** {@link #warn}, with lines of advice under it, each its own log line. */
    public static void warn(String message, String... details) {
        log("WARN", message, details);
    }

    /** {@link #error}, with lines of advice under it, each its own log line. */
    public static void error(String message, String... details) {
        log("ERROR", message, details);
    }

    public static void warn(String message) {
        log("WARN", message);
    }

    public static void error(String message) {
        log("ERROR", message);
    }

    /** {@link #error}, then {@code failure}'s stack trace on stderr, which the lifecycle log does not keep. */
    public static void error(String message, Throwable failure) {
        error(message);
        if (!stderrSuppressed) failure.printStackTrace(System.err);
    }

    private static void log(String level, String message) {
        var line = now() + " [" + level + "] " + message;
        if (!stderrSuppressed) System.err.println(line);
        writeToFile(line);
    }

    /** {@link #log}, then each detail as a line of its own at the same level. */
    private static void log(String level, String message, String... details) {
        log(level, message);
        for (var detail : details) log(level, detail);
    }

    private static void logToFile(String level, String message) {
        writeToFile(now() + " [" + level + "] " + message);
    }

    private static String now() {
        return LocalDateTime.now().format(FMT);
    }

    private static void writeToFile(String line) {
        try {
            var path = Environment.proxyLifecycleLogFile();
            Files.createDirectories(path.getParent());
            Files.writeString(path, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }
}
