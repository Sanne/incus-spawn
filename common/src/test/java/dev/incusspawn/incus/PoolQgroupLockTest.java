package dev.incusspawn.incus;

import dev.incusspawn.lifecycle.TempHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** One delete at a time prepares and settles a pool, across threads and processes (#2435). */
@ExtendWith(TempHome.class)
class PoolQgroupLockTest {

    @Test
    void aHeldPoolIsNotTakenAgainUntilItIsReleasedFromAnotherThread() throws Exception {
        PoolQgroupLock.acquire("p1", Duration.ofSeconds(5), false).orElseThrow().keep();

        // A rescan trigger finds it busy and schedules nothing
        assertTrue(PoolQgroupLock.acquire("p1", Duration.ZERO, false).isEmpty());
        // Another pool is its own lock
        PoolQgroupLock.acquire("p2", Duration.ZERO, false).orElseThrow().release();

        // The settle runs on the thread that closes a batch, which is not the one that prepared it
        var settle = new Thread(() -> PoolQgroupLock.releaseHeld("p1"));
        settle.start();
        settle.join();

        PoolQgroupLock.acquire("p1", Duration.ZERO, false).orElseThrow().release();
    }

    @Test
    void aSecondDeleteWaitsForTheFirstSettleAndReadsAgain() throws Exception {
        var first = PoolQgroupLock.acquire("p3", Duration.ofSeconds(5), false).orElseThrow();
        var second = new java.util.concurrent.CompletableFuture<PoolQgroupLock>();
        var waiter = new Thread(() -> second.complete(PoolQgroupLock.acquire("p3", Duration.ofSeconds(20), false).orElseThrow()));
        waiter.setDaemon(true);
        waiter.start();

        assertThrows(java.util.concurrent.TimeoutException.class, () -> second.get(300, java.util.concurrent.TimeUnit.MILLISECONDS));
        first.release();
        var got = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(got.waited(), "what it read before the wait is stale");
        got.release();
    }

    @Test
    void aRescanWhileTheLockIsHeldRunsNothing() {
        var held = PoolQgroupLock.acquire("p4", Duration.ofSeconds(5), false).orElseThrow();
        try {
            // Reports a rescan in progress, which the holder's settle starts, and runs no btrfs
            assertTrue(BtrfsUsage.rescan("p4"));
        } finally {
            held.release();
        }
    }
}
