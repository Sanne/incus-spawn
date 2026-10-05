package dev.incusspawn.mcp;

import dev.incusspawn.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Who an MCP session is: over stdio, the {@code isx mcp} process, by pid and start mark; over
 * the network (#915), the isx instance that calls, by name ({@link #ofInstance}).
 *
 * <p>A process session's start mark is what makes it safe to check liveness later -- a pid
 * alone may since belong to another process.
 *
 * <p>The mark must read the same from every later process, so on Linux it is the kernel's own
 * {@code starttime} (clock ticks since boot, {@code /proc/<pid>/stat}), never a wall-clock
 * instant: the JDK derives those from {@code /proc/stat}'s {@code btime}, which moves when NTP
 * steps the clock, and a live holder would then read as dead -- its instances adoptable without
 * {@code force}, and destroyed as orphans. Elsewhere it is the JDK's start instant; macOS records
 * that once, when the process starts.
 *
 * <p>Over stdio this is the whole of authentication: the MCP client (Claude Code) spawned this
 * process and holds its stdin and stdout, so there is no endpoint anyone else could reach. Over
 * the network the proxy authenticates the instance (address, per-start secret, {@code
 * mcp-caller} stamp) and runs {@code isx mcp --caller-instance <name>} for it, which checks the
 * stamp again before serving.
 */
record SessionId(long pid, long start, String instance) {

    /** How an instance session's stamp starts; never a digit, so never read as a process. */
    static final String INSTANCE_PREFIX = "instance:";
    private static final java.util.regex.Pattern INSTANCE_NAME =
            java.util.regex.Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9-]{0,62}");

    SessionId(long pid, long start) {
        this(pid, start, null);
    }

    static SessionId current() {
        var pid = ProcessHandle.current().pid();
        return new SessionId(pid, startOf(pid).orElse(0L));
    }

    /**
     * The session of the isx instance {@code name}, calling through the proxy. It is the same
     * session across every connection and every restart of the instance: what it holds stays
     * held for as long as the instance exists and may call ({@link #isInstance}).
     */
    static SessionId ofInstance(String name) {
        if (name == null || !INSTANCE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not an instance name: " + name);
        }
        return new SessionId(0, 0, name);
    }

    boolean isInstance() {
        return instance != null;
    }

    /** The value stamped as {@code user.incus-spawn.mcp-session}. */
    @Override
    public String toString() {
        return isInstance() ? INSTANCE_PREFIX + instance : pid + "-" + start;
    }

    /** The holder as a person reads it: {@code isx mcp pid 123}, {@code isx instance coord}. */
    String describe() {
        return isInstance() ? "isx instance " + instance : "isx mcp pid " + pid;
    }

    static Optional<SessionId> parse(String value) {
        if (value == null) return Optional.empty();
        if (value.startsWith(INSTANCE_PREFIX)) {
            var name = value.substring(INSTANCE_PREFIX.length());
            return INSTANCE_NAME.matcher(name).matches() ? Optional.of(new SessionId(0, 0, name)) : Optional.empty();
        }
        var dash = value.indexOf('-');
        if (dash <= 0) return Optional.empty();
        try {
            return Optional.of(new SessionId(Long.parseLong(value.substring(0, dash)),
                    Long.parseLong(value.substring(dash + 1))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether the process that owns this session is still running. Not for an instance session,
     * whose liveness is a question for Incus ({@link CallerLiveness}).
     */
    boolean isAlive() {
        if (isInstance()) throw new IllegalStateException("an instance session is not a process: " + this);
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
