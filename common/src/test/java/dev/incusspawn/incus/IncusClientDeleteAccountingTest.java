package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A delete prepares and settles the btrfs accounting of the pool holding the instance's root disk
 * (#2061): before it, isx has btrfs skip tracing what the instance shared, which made the branches
 * after a bulk delete wait for minutes; after it, isx has btrfs free it and rebuild the accounting,
 * without which no instance's root {@code size} is enforced. A batch settles once, at its end.
 */
class IncusClientDeleteAccountingTest {

    private static final String POOLS = "GET /1.0/storage-pools?recursion=1";

    /** The restore hooks the client registered, instead of the JVM's: no test leaves a real one behind. */
    private final List<Thread> hooks = new java.util.concurrent.CopyOnWriteArrayList<>();

    @org.junit.jupiter.api.BeforeEach
    void recordHooks() {
        IncusClient.resetReplacedThresholdsForTest();
        IncusClient.shutdownHooks = hooks::add;
    }

    /** The held thresholds are process-wide: one test's must not reach the next one's assertions. */
    @org.junit.jupiter.api.AfterEach
    void forgetHeldThresholds() {
        IncusClient.resetReplacedThresholdsForTest();
        IncusClient.shutdownHooks = hook -> Runtime.getRuntime().addShutdownHook(hook);
    }

    @Test
    void aDeletePreparesAndSettlesThePoolItsRootDiskIsOn() {
        var daemon = new FakeIncusDaemon().subvolumeListing(FakeIncusDaemon.listing("dev-1")).container("dev-1", Map.of());
        daemon.client().delete("dev-1", true);
        assertEquals(List.of("before default", "after default"), daemon.poolAccountingCalls());
    }

    @Test
    void aBatchPreparesOnceAndSettlesWhenItCloses() {
        var daemon = new FakeIncusDaemon().subvolumeListing(FakeIncusDaemon.listing("dev-1", "dev-2", "dev-3"))
                .container("dev-1", Map.of()).container("dev-2", Map.of()).container("dev-3", Map.of());
        var client = daemon.client();
        try (var batch = client.deleteBatch()) {
            client.delete("dev-1", true);
            client.delete("dev-2", true);
            assertEquals(List.of("before default"), daemon.poolAccountingCalls(),
                    "a rescan started before the last delete is freed would be undone by it");
        }
        assertEquals(List.of("before default", "after default"), daemon.poolAccountingCalls());

        client.delete("dev-3", true);
        assertEquals(List.of("before default", "after default", "before default", "after default"),
                daemon.poolAccountingCalls(), "a delete after the batch settles on its own");
    }

    /** Each pool a batch deleted from is settled when it closes, not only the first one's. */
    @Test
    void aBatchSettlesEveryPoolItDeletedFrom() {
        var daemon = new FakeIncusDaemon().pool("default", "btrfs").pool("fast", "btrfs")
                .subvolumeListing(FakeIncusDaemon.listing("dev-1", "dev-2")).container("dev-1", Map.of());
        daemon.profile("default", Map.of("root", Map.of("type", "disk", "path", "/", "pool", "fast")))
                .container("dev-2", Map.of());
        var client = daemon.client();
        try (var batch = client.deleteBatch()) {
            client.delete("dev-1", true);
            client.delete("dev-2", true);
        }
        assertEquals(List.of("before default", "before fast", "after default", "after fast"),
                daemon.poolAccountingCalls());
    }

    /** A refused DELETE still settles: the threshold is already 0, and nothing else would put it back. */
    @Test
    void aDeleteThatFailsStillSettles() {
        var daemon = new FakeIncusDaemon().subvolumeListing(FakeIncusDaemon.listing("dev-1"))
                .container("dev-1", Map.of()).refuseDeletes();
        assertThrows(IncusException.class, () -> daemon.client().delete("dev-1", true));
        assertEquals(List.of("before default", "after default"), daemon.poolAccountingCalls());
    }

    @Test
    void aBatchWithNoDeleteSettlesNothing() {
        var daemon = new FakeIncusDaemon();
        try (var batch = daemon.client().deleteBatch()) {
            // nothing deleted
        }
        assertEquals(List.of(), daemon.poolAccountingCalls());
    }

    @Test
    void aPoolOfAnotherDriverIsLeftAlone() {
        var daemon = new FakeIncusDaemon().pool("default", "dir").container("dev-1", Map.of());
        daemon.client().delete("dev-1", true);
        assertEquals(List.of(), daemon.poolAccountingCalls());
    }

    /** One listing serves the storage check, the accounting and the stale-volume cleanup. */
    @Test
    void aDeleteReadsThePoolListingOnce() {
        var daemon = new FakeIncusDaemon().subvolumeListing(FakeIncusDaemon.listing("dev-1")).container("dev-1", Map.of());
        daemon.client().delete("dev-1", true);
        assertEquals(1, daemon.requests().stream().filter(POOLS::equals).count(), String.join("\n", daemon.requests()));
    }

    /** A pool isx gave threshold 0, which it found at 3. */
    private static final BtrfsUsage.DropAccounting REPLACED_3 = new BtrfsUsage.DropAccounting(
            new BtrfsUsage.QgroupStatus(true, true, false, "qgroup", 0), 3);

    /** Records the threshold writes a client's deletes and the exit hook make on {@code pool}. */
    private static List<String> replacingThreshold(IncusClient client) {
        var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        client.poolAccounting = new IncusClient.PoolAccounting() {
            @Override
            public BtrfsUsage.DropAccounting beforeDeletes(String pool) {
                calls.add("before " + pool);
                return REPLACED_3;
            }

            @Override
            public void afterDeletes(String pool, BtrfsUsage.DropAccounting before) {
                calls.add("after " + pool);
            }

            @Override
            public void restoreThreshold(String pool, int threshold) {
                calls.add("restore " + pool + " " + threshold);
            }
        };
        return calls;
    }

    /**
     * Ctrl-C during {@code isx destroy --all-instances} never closes the batch: isx's exit hook puts
     * back the threshold it replaced, or the pool would stay at 0 until the next mount, with every
     * delete isx does not make left untraced (review, #2061).
     */
    @Test
    void aBatchCutShortPutsTheThresholdBackAtExit() {
        var daemon = new FakeIncusDaemon().subvolumeListing(FakeIncusDaemon.listing("dev-1", "dev-2"))
                .container("dev-1", Map.of()).container("dev-2", Map.of());
        var client = daemon.client();
        var calls = replacingThreshold(client);
        var batch = client.deleteBatch();
        client.delete("dev-1", true);
        assertEquals(java.util.Set.of("default"), IncusClient.replacedThresholdPoolsForTest());

        IncusClient.restoreReplacedThresholds();   // what the shutdown hook runs, the batch still open
        assertEquals(List.of("before default", "restore default 3"), calls);
        assertEquals(java.util.Set.of(), IncusClient.replacedThresholdPoolsForTest());
        batch.close();
    }

    /** Once the settle has put the threshold back, the exit hook writes nothing over a later delete's. */
    @Test
    void aSettledDeleteLeavesNothingForTheExitHook() {
        var daemon = new FakeIncusDaemon().subvolumeListing(FakeIncusDaemon.listing("dev-1"))
                .container("dev-1", Map.of());
        var client = daemon.client();
        var calls = replacingThreshold(client);
        client.delete("dev-1", true);
        IncusClient.restoreReplacedThresholds();
        assertEquals(List.of("before default", "after default"), calls);
    }

    /**
     * A settle forgets only the threshold its own delete replaced: another delete on the pool that
     * replaced it again once this settle put it back keeps its own, for the exit hook.
     */
    @Test
    void aSettleLeavesALaterReplacementHeld() {
        var daemon = new FakeIncusDaemon().subvolumeListing(FakeIncusDaemon.listing("dev-1", "dev-2"))
                .container("dev-1", Map.of()).container("dev-2", Map.of());
        var later = daemon.client();
        replacingThreshold(later);
        var laterBatch = later.deleteBatch();
        var first = daemon.client();
        first.poolAccounting = new IncusClient.PoolAccounting() {
            @Override
            public BtrfsUsage.DropAccounting beforeDeletes(String pool) {
                return REPLACED_3;
            }

            @Override
            public void afterDeletes(String pool, BtrfsUsage.DropAccounting before) {
                // The threshold is back at 3 here, and the other client's delete replaces it again
                later.delete("dev-2", true);
            }
        };
        first.delete("dev-1", true);
        assertEquals(java.util.Set.of("default"), IncusClient.replacedThresholdPoolsForTest());
        laterBatch.close();
        assertEquals(java.util.Set.of(), IncusClient.replacedThresholdPoolsForTest());
    }

    /**
     * The restore hook is registered before the first threshold write, not after it: a signal
     * between the two would leave the pool at 0 with no hook to put it back (review, #2061).
     */
    @Test
    void theRestoreHookIsRegisteredBeforeTheThresholdIsWritten() {
        var daemon = new FakeIncusDaemon().subvolumeListing(FakeIncusDaemon.listing("dev-1", "dev-2"))
                .container("dev-1", Map.of()).container("dev-2", Map.of());
        var client = daemon.client();
        var calls = replacingThreshold(client);
        IncusClient.shutdownHooks = hook -> {
            calls.add("hook");
            hooks.add(hook);
        };
        client.delete("dev-1", true);
        client.delete("dev-2", true);
        assertEquals(List.of("hook", "before default", "after default", "before default", "after default"), calls,
                "once, and before the first write");
    }

    /**
     * A delete while isx is already exiting cannot register the hook (addShutdownHook throws): it
     * keeps the threshold, traced as before, and still deletes and settles.
     */
    @Test
    void aDeleteDuringShutdownKeepsTheThresholdAndStillSettles() {
        var daemon = new FakeIncusDaemon().subvolumeListing(FakeIncusDaemon.listing("dev-1"))
                .container("dev-1", Map.of());
        var client = daemon.client();
        var calls = replacingThreshold(client);
        IncusClient.shutdownHooks = hook -> {
            throw new IllegalStateException("Shutdown in progress");
        };
        client.delete("dev-1", true);
        assertEquals(List.of("after default"), calls, "no threshold written that no hook could put back");
        assertEquals(java.util.Set.of(), IncusClient.replacedThresholdPoolsForTest());
        org.junit.jupiter.api.Assertions.assertFalse(client.exists("dev-1"), "the delete still happened");
    }

    /**
     * The hook can run while a delete's write is in flight and read the map before that delete
     * holds its threshold: the delete then puts it back itself, and a delete after the hook writes none.
     */
    @Test
    void aWriteInFlightWhenTheHookRunsIsPutBackAndLaterDeletesWriteNone() {
        var daemon = new FakeIncusDaemon().subvolumeListing(FakeIncusDaemon.listing("dev-1", "dev-2"))
                .container("dev-1", Map.of()).container("dev-2", Map.of());
        var client = daemon.client();
        var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        client.poolAccounting = new IncusClient.PoolAccounting() {
            @Override
            public BtrfsUsage.DropAccounting beforeDeletes(String pool) {
                calls.add("before " + pool);
                IncusClient.restoreAtExit();   // the signal, between the write and the hold
                return REPLACED_3;
            }

            @Override
            public void afterDeletes(String pool, BtrfsUsage.DropAccounting before) {
                calls.add("after " + pool);
            }

            @Override
            public void restoreThreshold(String pool, int threshold) {
                calls.add("restore " + pool + " " + threshold);
            }
        };
        client.delete("dev-1", true);
        client.delete("dev-2", true);
        assertEquals(List.of("before default", "restore default 3", "after default", "after default"), calls);
        assertEquals(java.util.Set.of(), IncusClient.replacedThresholdPoolsForTest());
    }
}
