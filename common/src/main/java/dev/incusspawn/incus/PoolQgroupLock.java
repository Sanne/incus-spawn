package dev.incusspawn.incus;

import dev.incusspawn.ClientLog;
import dev.incusspawn.Environment;
import dev.incusspawn.util.HostLock;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

/**
 * The host-wide lock on one btrfs pool's qgroup accounting work (#2435): from the moment a delete
 * sets the pool's {@code drop_subtree_threshold} to 0 until its settle has started the rescan.
 * A drop at threshold 0 cancels a rescan still running, so a second isx process that dropped, or
 * started a rescan, in that window could cancel the first one's rescan, and a series of such
 * cancellations left a pool unable to rescan until it was mounted again.
 *
 * <p>A {@link HostLock} belongs to the thread that took it, and a delete batch is prepared on one
 * thread and settled on another, so a small daemon thread holds the lock and waits to be released.
 * The kernel drops the file lock if isx is killed.
 */
public final class PoolQgroupLock {

    /** Longer than any batch of deletes, which holds it throughout, and shorter than a hung one. */
    static final Duration WAIT = Duration.ofMinutes(5);

    private static final Map<String, PoolQgroupLock> HELD = new ConcurrentHashMap<>();

    private final String pool;
    private final CountDownLatch released;
    private final Thread holder;
    private final boolean waited;

    private PoolQgroupLock(String pool, CountDownLatch released, Thread holder, boolean waited) {
        this.pool = pool;
        this.released = released;
        this.holder = holder;
        this.waited = waited;
    }

    /** Whether another holder had it when this one asked, so what was read before is stale. */
    boolean waited() {
        return waited;
    }

    /**
     * Take the lock for {@code pool}, waiting at most {@code timeout}: {@link Duration#ZERO} takes
     * it only when it is free. Empty when it could not be taken.
     */
    static Optional<PoolQgroupLock> acquire(String pool, Duration timeout, boolean say) {
        var taken = new CompletableFuture<Boolean>();
        var released = new CountDownLatch(1);
        var holder = new Thread(() -> hold(pool, timeout, say, taken, released), "isx-pool-lock-" + pool);
        holder.setDaemon(true);
        holder.start();
        try {
            return Optional.of(new PoolQgroupLock(pool, released, holder, taken.get()));
        } catch (ExecutionException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // The holder may still get the file: let it go at once
            released.countDown();
            return Optional.empty();
        }
    }

    private static void hold(String pool, Duration timeout, boolean say, CompletableFuture<Boolean> taken,
                             CountDownLatch released) {
        try (var lock = HostLock.acquireOrDegrade(Environment.lockDir().resolve("btrfs-pool").resolve(pool + ".lock"),
                "deleting from the btrfs pool", say ? ClientLog::warn : s -> {}, ClientLog::warn, timeout)) {
            taken.complete(lock.waited());
            released.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            taken.completeExceptionally(e);
        } catch (Throwable e) {
            // Never leave the caller waiting for an answer that cannot come
            taken.completeExceptionally(e);
        }
    }

    /** Record this as the pool's current holder, for {@link #releaseHeld}. */
    PoolQgroupLock keep() {
        HELD.put(pool, this);
        return this;
    }

    /** Release what {@link #keep} recorded for {@code pool}, if anything. */
    static void releaseHeld(String pool) {
        var lock = pool == null ? null : HELD.remove(pool);
        if (lock != null) lock.release();
    }

    /** Release it, and wait for the holder to let go of the file, so a waiter finds it free. Idempotent. */
    public void release() {
        HELD.remove(pool, this);
        released.countDown();
        try {
            holder.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
