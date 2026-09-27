package dev.incusspawn.mcp;

import java.util.Optional;

/**
 * Who an MCP session is: the {@code isx mcp} process, by pid and start time. The start time is
 * what makes it safe to check liveness later -- a pid alone may since belong to another process.
 *
 * <p>Over stdio this is the whole of authentication: the MCP client (Claude Code) spawned this
 * process and holds its stdin and stdout, so there is no endpoint anyone else could reach.
 */
record SessionId(long pid, long startMillis) {

    static SessionId current() {
        var self = ProcessHandle.current();
        return new SessionId(self.pid(), startMillisOf(self).orElse(0L));
    }

    /** The value stamped as {@code user.incus-spawn.mcp-session}. */
    @Override
    public String toString() {
        return pid + "-" + startMillis;
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
        return ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive)
                .flatMap(SessionId::startMillisOf)
                .map(start -> start == startMillis)
                .orElse(false);
    }

    private static Optional<Long> startMillisOf(ProcessHandle handle) {
        return handle.info().startInstant().map(java.time.Instant::toEpochMilli);
    }
}
