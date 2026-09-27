package dev.incusspawn.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostLockTest {

    @TempDir
    Path tmp;

    private Path lockFile() {
        return tmp.resolve("locks/test.lock");
    }

    private HostLock acquire(List<String> log) {
        return HostLock.acquire(lockFile(), "testing", log::add);
    }

    @Test
    void anotherThreadWaitsForTheHolder() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> {
                try (var lock = acquire(new ArrayList<>())) {
                    held.countDown();
                    release.await();
                }
                return null;
            });
            // Released however the assertions go, or closing the pool waits on the holder forever
            try {
                assertTrue(held.await(10, TimeUnit.SECONDS));
                var waiter = pool.submit(() -> {
                    try (var lock = acquire(new ArrayList<>())) {
                        return true;
                    }
                });
                Thread.sleep(200);
                assertFalse(waiter.isDone(), "a second thread took the lock while the first held it");
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
                assertTrue(waiter.get(10, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void anotherProcessHoldingTheLockIsWaitedFor() throws Exception {
        // The fcntl half: what two isx processes contend on. The child holds the lock until
        // told to let go, so the test depends on no timing.
        var holder = tmp.resolve("Holder.java");
        Files.writeString(holder, """
                import java.io.*;
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
                            new BufferedReader(new InputStreamReader(System.in)).readLine();
                        }
                    }
                }
                """);
        var java = ProcessHandle.current().info().command().orElse("java");
        // stderr apart: the JVM reports JAVA_TOOL_OPTIONS and the like there
        var child = new ProcessBuilder(java, holder.toString(), lockFile().toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var out = new BufferedReader(new InputStreamReader(child.getInputStream()));
             var in = new PrintWriter(child.getOutputStream(), true);
             var pool = Executors.newSingleThreadExecutor()) {
            assertEquals("locked", out.readLine());
            var log = new CopyOnWriteArrayList<String>();
            var acquired = pool.submit(() -> {
                try (var lock = HostLock.acquire(lockFile(), "testing", log::add)) {
                    return child.isAlive();
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (log.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(List.of("Another isx process is testing — waiting..."), log);
            assertFalse(acquired.isDone(), "acquired while another process held the lock");
            in.println("release");
            acquired.get(10, TimeUnit.SECONDS);
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void closingReleasesTheLockForOtherThreads() throws Exception {
        acquire(new ArrayList<>()).close();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reacquired = pool.submit(() -> {
                try (var lock = acquire(new ArrayList<>())) {
                    return true;
                }
            });
            assertTrue(reacquired.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void reacquiringOnTheSameThreadIsRefused() {
        try (var lock = acquire(new ArrayList<>())) {
            assertThrows(IllegalStateException.class, () -> acquire(new ArrayList<>()));
        }
    }

    @Test
    void aStuckHolderTimesOutTheWaiter() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            pool.submit(() -> {
                try (var lock = acquire(new ArrayList<>())) {
                    held.countDown();
                    release.await();
                }
                return null;
            });
            try {
                assertTrue(held.await(10, TimeUnit.SECONDS));
                var e = assertThrows(HostLock.HostLockException.class, () -> HostLock.acquire(
                        lockFile(), "testing", s -> {}, Duration.ofMillis(100)));
                assertEquals("Timed out waiting for another isx process testing.", e.getMessage());
            } finally {
                release.countDown();
            }
        }
    }
}
