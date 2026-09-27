package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** #815: nothing may be handed an address between another claim's listing and its write. */
class StaticIpClaimTest {

    @TempDir
    Path tmp;

    private Path lock() {
        return tmp.resolve("locks/static-ip.lock");
    }

    private static String claimFor(IncusClient incus, Path lock, String name, Runnable beforeWrite) {
        return StaticIpAllocator.claim(incus, lock, ip -> {
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
    void aClaimWaitsForAnotherProcessHoldingTheLock() throws Exception {
        // What #815 is about: two isx processes, which only the file lock serializes
        var holder = tmp.resolve("Holder.java");
        Files.writeString(holder, """
                import java.nio.channels.FileChannel;
                import java.nio.file.*;
                public class Holder {
                    public static void main(String[] a) throws Exception {
                        var p = Path.of(a[0]);
                        Files.createDirectories(p.getParent());
                        try (var c = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                             var l = c.lock()) {
                            System.out.println("locked");
                            System.out.flush();
                            Thread.sleep(1000);
                            System.out.println("releasing");
                            System.out.flush();
                        }
                    }
                }
                """);
        var java = ProcessHandle.current().info().command().orElse("java");
        var child = new ProcessBuilder(java, holder.toString(), lock().toString())
                .redirectErrorStream(true).start();
        try (var out = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
            assertEquals("locked", out.readLine());
            var incus = new FakeIncusDaemon().container("a", Map.of()).client();
            // The child writes "releasing" before it lets go, so it is waiting in the pipe by
            // the time a claim can run. Not whether the child is alive: it outlives its lock.
            var ranAfterRelease = new AtomicBoolean();
            StaticIpAllocator.claim(incus, lock(), ip -> ranAfterRelease.set(ready(out)));
            assertTrue(ranAfterRelease.get(), "the claim ran while another process held the lock");
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void aFailedWriteReleasesTheLock() throws Exception {
        var incus = new FakeIncusDaemon().container("a", Map.of()).client();
        assertThrows(IncusException.class, () -> StaticIpAllocator.claim(incus, lock(), ip -> {
            throw new IncusException("refused");
        }));
        // On another thread, which the in-process lock would block if it were still held
        try (var pool = Executors.newSingleThreadExecutor()) {
            var ip = pool.submit(() -> claimFor(incus, lock(), "a", () -> {}));
            assertEquals("10.166.11.2", ip.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void aNestedClaimIsRefused() {
        // The outer address is not written yet, so the inner claim would be handed the same one
        var incus = new FakeIncusDaemon().container("a", Map.of()).container("b", Map.of()).client();
        assertThrows(IllegalStateException.class, () -> StaticIpAllocator.claim(incus, lock(),
                outer -> claimFor(incus, lock(), "b", () -> {})));
    }

    @Test
    void aFailedListingFailsTheClaimRatherThanTreatingEveryAddressAsFree() {
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("10.166.11.1/24");
        when(incus.listJsonConfig()).thenThrow(new IncusException("Failed to list instances"));
        assertThrows(IncusException.class,
                () -> StaticIpAllocator.claim(incus, lock(), ip -> {}));
    }

    private static boolean ready(BufferedReader out) {
        try {
            return out.ready();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
