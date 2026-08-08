package dev.incusspawn.incus;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Process-wide keep-alive connection cache for the request path.
 *
 * Usually holds a single warm connection that sequential calls reuse; grows on demand under
 * momentary concurrency (up to {@link #MAX_IDLE}) and evicts connections idle longer than
 * {@link #IDLE_TTL_NANOS} — well under the forwarder's -T 180 so a parked connection is never
 * reaped out from under us.
 *
 * It is a CACHE, never a bottleneck: {@link #borrow} returns null on a miss and the caller
 * opens a fresh (overflow) connection; {@link #release} parks a live connection if there is
 * room, otherwise closes it. The real concurrency ceiling stays the transport's fail-open
 * connection semaphore. Long-poll GET /wait requests use the pool too; because a borrowed
 * connection leaves the idle set, a long-poll can't monopolize it — a concurrent call just
 * overflows to a fresh connection.
 */
final class ConnectionPool {

    static final int MAX_IDLE = 4;
    static final long IDLE_TTL_NANOS = 5_000_000_000L; // 5s

    private static final ConnectionPool INSTANCE = new ConnectionPool();

    static ConnectionPool global() { return INSTANCE; }

    private final Map<String, Deque<KeepAliveConnection>> idle = new HashMap<>();

    /** A warm, non-expired connection for the path, or null if none is cached. */
    synchronized KeepAliveConnection borrow(String socketPath) {
        var q = idle.get(socketPath);
        if (q == null) return null;
        long now = System.nanoTime();
        while (!q.isEmpty()) {
            var c = q.pollFirst();
            if (c.healthy() && (now - c.idleSinceNanos()) < IDLE_TTL_NANOS) {
                return c; // optimistic — server-closed-while-idle is caught at execute()
            }
            c.close(); // dead or expired
        }
        return null;
    }

    /** Park a live connection for reuse if there is room, otherwise close it. */
    synchronized void release(KeepAliveConnection c) {
        if (!c.healthy()) {
            c.close();
            return;
        }
        var q = idle.computeIfAbsent(c.socketPath(), k -> new ArrayDeque<>());
        // Reclaim slots held by dead/expired parked connections first, so a fresh healthy
        // connection is never dropped in favour of stale ones sitting in a full deque.
        pruneExpired(q);
        if (q.size() >= MAX_IDLE) {
            c.close(); // overflow beyond the retention cap — don't hoard
            return;
        }
        c.markIdle();
        q.addLast(c);
    }

    private static void pruneExpired(Deque<KeepAliveConnection> q) {
        long now = System.nanoTime();
        q.removeIf(k -> {
            boolean expired = !k.healthy() || (now - k.idleSinceNanos()) >= IDLE_TTL_NANOS;
            if (expired) k.close();
            return expired;
        });
    }

    /** Number of parked (idle) connections for a path — for tests/diagnostics. */
    synchronized int idleCount(String socketPath) {
        var q = idle.get(socketPath);
        return q == null ? 0 : q.size();
    }
}
