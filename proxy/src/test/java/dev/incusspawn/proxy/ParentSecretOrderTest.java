package dev.incusspawn.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** #2327: a read of the parent's secret that enters while another is in flight ends holding the newest content. */
class ParentSecretOrderTest {

    @Test
    void aReadEnteringDuringAnotherEndsHoldingTheNewestContent() throws Exception {
        var proxy = new MitmProxy(null, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        var file = new AtomicReference<>("old");
        var firstHasRead = new CountDownLatch(1);
        var firstMayStore = new CountDownLatch(1);
        var secondStarted = new AtomicBoolean();
        var first = new Thread(() -> proxy.readParentSecret(() -> {
            var content = file.get();
            firstHasRead.countDown();
            await(firstMayStore);
            return content;
        }));
        first.start();
        firstHasRead.await();
        // The file is rotated, and a second read enters while the first has read "old" but not stored it.
        file.set("new");
        var second = new Thread(() -> proxy.readParentSecret(() -> {
            secondStarted.set(true);
            return file.get();
        }));
        second.start();
        second.join(200);
        assertFalse(secondStarted.get(), "the second read waits for the first, it does not read beside it");
        firstMayStore.countDown();
        first.join();
        second.join();
        assertEquals("new", proxy.heldParentSecret());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
