package dev.incusspawn.mcp;

import dev.incusspawn.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Who an MCP session is: the {@code isx mcp} process, by pid and start mark. The start mark is
 * what makes it safe to check liveness later -- a pid alone may since belong to another process.
 *
 * <p>The mark must read the same from every later process, so on Linux it is the kernel's own
 * {@code starttime} (clock ticks since boot, {@code /proc/<pid>/stat}), never a wall-clock
 * instant: the JDK derives those from {@code /proc/stat}'s {@code btime}, which moves when NTP
 * steps the clock, and a live holder would then read as dead -- its instances adoptable without
 * {@code force}, and destroyed as orphans. Elsewhere it is the JDK's start instant; macOS records
 * that once, when the process starts.
 *
 * <p>Over stdio this is the whole of authentication: the MCP client (Claude Code) spawned this
 * process and holds its stdin and stdout, so there is no endpoint anyone else could reach.
 */
record SessionId(long pid, long start) {

    static SessionId current() {
        var pid = ProcessHandle.current().pid();
        return new SessionId(pid, startOf(pid).orElse(0L));
    }

    /** The value stamped as {@code user.incus-spawn.mcp-session}. */
    @Override
    public String toString() {
        return pid + "-" + start;
    }

    static Optional<SessionId> parse(String value) {
        if (value == null) return Optional.empty();
        var dash = value.indexOf('-');
        if (dash <= 0) return Optional.empty();
        try {
            return Optional.of(new SessionId(Long.parseLong(value.substring(0, dash)),
                    Long.parseLong(value.substring(dash + 1))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Whether the process that owns this session is still running. */
    boolean isAlive() {
        return startOf(pid).map(s -> s == start).orElse(false);
    }

    /** The start mark of a running process, or empty if there is none by that pid. */
    static Optional<Long> startOf(long pid) {
        if (Platform.isLinux()) {
            try {
                return procStartTicks(Files.readString(Path.of("/proc", Long.toString(pid), "stat")));
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                .flatMap(h -> h.info().startInstant()).map(java.time.Instant::toEpochMilli);
    }

    /**
     * Field 22 of a {@code /proc/<pid>/stat} line. The command name (field 2) is in parentheses
     * and may itself hold spaces and parentheses, so fields are counted from after the last ')'.
     */
    static Optional<Long> procStartTicks(String stat) {
        var close = stat.lastIndexOf(')');
        if (close < 0) return Optional.empty();
        var fields = stat.substring(close + 1).trim().split(" +");
        try {
            return fields.length > 19 ? Optional.of(Long.parseLong(fields[19])) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
