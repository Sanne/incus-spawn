package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** #815: nothing may be handed an address between another claim's listing and its write. */
class StaticIpClaimTest {

    private static final StaticIpAllocator.Output QUIET =
            new StaticIpAllocator.Output(s -> {}, s -> {});
    private static final BridgeAddress BRIDGE = BridgeAddress.parse("10.166.11.1/24").orElseThrow();

    @TempDir
    Path tmp;

    private Path lock() {
        return tmp.resolve("locks/static-ip.lock");
    }

    private static String claimFor(IncusClient incus, Path lock, String name, Runnable beforeWrite) {
        return StaticIpAllocator.claim(incus, BRIDGE, lock, QUIET, ip -> {
            beforeWrite.run();
            incus.deviceConfigSet(name, "eth0", "ipv4.address", ip);
        });
    }

    @Test
    void concurrentClaimsGetDifferentAddresses() throws Exception {
        var daemon = new FakeIncusDaemon().container("a", Map.of()).container("b", Map.of());
        var incus = daemon.client();
        var aAllocated = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            // A holds its address unwritten long enough for B to list the instances, as a slow
            // .network push would: without the lock B finds the same address free.
            var a = pool.submit(() -> claimFor(incus, lock(), "a", () -> {
                aAllocated.countDown();
                sleep(300);
            }));
            assertTrue(aAllocated.await(10, TimeUnit.SECONDS));
            var b = pool.submit(() -> claimFor(incus, lock(), "b", () -> {}));
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void aNestedClaimIsRefused() {
        // The outer address is not written yet, so the inner claim would be handed the same one
        var incus = new FakeIncusDaemon().container("a", Map.of()).container("b", Map.of()).client();
        assertThrows(IllegalStateException.class, () -> StaticIpAllocator.claim(incus, BRIDGE, lock(), QUIET,
                outer -> claimFor(incus, lock(), "b", () -> {})));
    }

    @Test
    void aFailedListingFailsTheClaimRatherThanTreatingEveryAddressAsFree() {
        var incus = mock(IncusClient.class);
        when(incus.listJsonConfig()).thenThrow(new IncusException("Failed to list instances"));
        assertThrows(IncusException.class,
                () -> StaticIpAllocator.claim(incus, BRIDGE, lock(), QUIET, ip -> {}));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
