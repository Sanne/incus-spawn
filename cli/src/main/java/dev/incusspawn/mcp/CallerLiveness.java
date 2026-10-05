package dev.incusspawn.mcp;

import dev.incusspawn.incus.Metadata;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Whether the session holding an instance is still there. A process session is alive while its
 * process runs; an instance session (#915) while the instance exists and may still call
 * ({@link Metadata#MCP_CALLER}) -- running or not, since a coordinator box that is stopped or
 * restarting has not let go of its workers. Once it is destroyed, or the user takes the stamp
 * away, what it held is orphaned like a dead process's.
 *
 * <p>An instance answer is one Incus read, and the callers ask once per instance in a listing,
 * so it is remembered for {@link #REMEMBER}: liveness only gates adoption without {@code force}
 * and the orphan clock, neither of which a few seconds' delay can hurt. An instance Incus cannot
 * be asked about counts as alive, so a failed read never orphans anything.
 */
final class CallerLiveness implements Predicate<SessionId> {

    static final Duration REMEMBER = Duration.ofSeconds(5);

    private record Answer(boolean alive, long at) {}

    private final InstanceBackend backend;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, Answer> answers = new ConcurrentHashMap<>();

    CallerLiveness(InstanceBackend backend) {
        this(backend, System::nanoTime);
    }

    CallerLiveness(InstanceBackend backend, LongSupplier nanoTime) {
        this.backend = backend;
        this.nanoTime = nanoTime;
    }

    @Override
    public boolean test(SessionId session) {
        if (!session.isInstance()) return session.isAlive();
        var now = nanoTime.getAsLong();
        var known = answers.get(session.instance());
        if (known != null && now - known.at() < REMEMBER.toNanos()) return known.alive();
        var alive = mayCall(session.instance());
        answers.put(session.instance(), new Answer(alive, now));
        return alive;
    }

    private boolean mayCall(String instance) {
        try {
            var metadata = backend.metadata(instance);
            return Metadata.isMcpCaller(metadata);
        } catch (RuntimeException e) {
            return true;
        }
    }
}
