package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests read the fake's state from stubbed execs while other threads' requests rewrite it, as
 * {@code RepoClonerReferenceTest} does while parallel detaches replace the device map (#1478). An
 * unguarded read then copies a map mid-write and throws {@code ConcurrentModificationException}.
 */
class FakeIncusDaemonConcurrencyTest {

    private static final String NAME = "vm";

    @Test
    @Timeout(10)
    void anInstanceReadWaitsForTheRequestInProgress() throws Exception {
        var inRequest = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Running", Map.of());
        daemon.writeAfterRead(NAME, () -> {
            inRequest.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var request = new Thread(() -> {
            try {
                daemon.request("GET", "/1.0/instances/" + NAME, null, Map.of(), (byte[]) null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        request.start();
        assertTrue(inRequest.await(5, TimeUnit.SECONDS), "the instance GET never ran its after-read write");

        var reader = new Thread(() -> daemon.instance(NAME));
        reader.start();
        // The reader either blocks on the daemon's lock or, unguarded, reads mid-request and ends.
        dev.incusspawn.util.Eventually.await("the reader to block or end",
                () -> reader.getState() == Thread.State.BLOCKED || reader.getState() == Thread.State.TERMINATED);
        var state = reader.getState();
        release.countDown();
        request.join();
        reader.join();

        assertEquals(Thread.State.BLOCKED, state, "the instance was read while a request was writing it");
    }
}
