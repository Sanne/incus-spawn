package dev.incusspawn.proxy;

import dev.incusspawn.Environment;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ProxyLog {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static volatile boolean debugEnabled =
            Boolean.getBoolean("isx.proxy.debug");

    private ProxyLog() {}

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static void debug(String message) {
        if (!debugEnabled) return;
        logToFile("DEBUG", message);
    }

    public static void info(String message) {
        log("INFO", message);
    }

    public static void warn(String message) {
        log("WARN", message);
    }

    public static void error(String message) {
        log("ERROR", message);
    }

    private static void log(String level, String message) {
        var line = LocalDateTime.now().format(FMT) + " [" + level + "] " + message;
        System.err.println(line);
        writeToFile(line);
    }

    private static void logToFile(String level, String message) {
        var line = LocalDateTime.now().format(FMT) + " [" + level + "] " + message;
        writeToFile(line);
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
