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
                var log = new CopyOnWriteArrayList<String>();
                var waiter = pool.submit(() -> {
                    try (var lock = acquire(log)) {
                        return lock.waited();
                    }
                });
                Thread.sleep(200);
                assertFalse(waiter.isDone(), "a second thread took the lock while the first held it");
                assertEquals(List.of(), log, "a brief wait between threads is not said");
                Eventually.holdsWithin(Duration.ofSeconds(10), () -> !(log.isEmpty()));
                assertEquals(List.of("Another isx operation is testing — waiting..."), log,
                        "a longer one is, as another process's is");
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
                assertTrue(waiter.get(10, TimeUnit.SECONDS), "says it waited, so a caller re-checks");
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
                    return lock.waited();
                }
            });
            Eventually.holdsWithin(Duration.ofSeconds(10), () -> !(log.isEmpty()));
            assertEquals(List.of("Another isx process is testing — waiting..."), log);
            assertFalse(acquired.isDone(), "acquired while another process held the lock");
            in.println("release");
            assertTrue(acquired.get(10, TimeUnit.SECONDS), "says it waited, so a caller re-checks");
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void anUncontendedLockSaysItDidNotWait() {
        try (var lock = acquire(new ArrayList<>())) {
            assertFalse(lock.waited());
        }
        try (var lock = acquire(new ArrayList<>())) {
            assertFalse(lock.waited(), "a holder that has let go is not waited for");
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
                        lockFile(), "testing", s -> {}, Duration.ofMillis(100), null));
                assertEquals("Timed out waiting for another isx operation testing.", e.getMessage(),
                        "another thread, not another process, holds it");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void anUnusableLockFileFailsAcquireButDegradesWithOneWarning() throws Exception {
        // A regular file where the lock's directory should be: nothing there can be locked,
        // as on a home without working fcntl locks
        var blocker = tmp.resolve("not-a-dir");
        Files.writeString(blocker, "");
        var unusable = blocker.resolve("test.lock");

        assertThrows(HostLock.HostLockException.class,
                () -> HostLock.acquire(unusable, "testing", s -> {}));

        var warnings = new CopyOnWriteArrayList<String>();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                pool.submit(() -> {
                    try (var lock = HostLock.acquireOrDegrade(unusable, "testing", s -> {}, warnings::add)) {
                        held.countDown();
                        release.await();
                    }
                    return null;
                });
                assertTrue(held.await(10, TimeUnit.SECONDS));
                // Degraded to the in-process lock, which still holds off this process's threads
                var second = pool.submit(() -> {
                    try (var lock = HostLock.acquireOrDegrade(unusable, "testing", s -> {}, warnings::add)) {
                        return true;
                    }
                });
                Thread.sleep(200);
                assertFalse(second.isDone(), "the degraded lock let a second thread in");
                release.countDown();
                assertTrue(second.get(10, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
        assertEquals(1, warnings.size(), "warned once per file, not per acquisition: " + warnings);
        assertTrue(warnings.getFirst().startsWith("Cannot lock " + unusable), warnings.getFirst());
    }

    private HostLock acquireShared(List<String> log) {
        return HostLock.acquireShared(lockFile(), "testing", log::add, s -> {});
    }

    /**
     * Another process on the lock file: {@code hold shared|exclusive} takes it and prints
     * "locked", letting go on a line on stdin; {@code probe} prints whether it could have taken
     * it exclusively, "free" or "busy".
     */
    private Process otherProcess(String... args) throws Exception {
        var source = tmp.resolve("Other.java");
        Files.writeString(source, """
                import java.io.*;
                import java.nio.channels.FileChannel;
                import java.nio.file.*;
                public class Other {
                    public static void main(String[] a) throws Exception {
                        var p = Path.of(a[0]);
                        Files.createDirectories(p.getParent());
                        try (var c = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.READ,
                                StandardOpenOption.WRITE)) {
                            if (a[1].equals("probe")) {
                                var l = c.tryLock();
                                System.out.println(l == null ? "busy" : "free");
                                return;
                            }
                            try (var l = c.lock(0, Long.MAX_VALUE, a[2].equals("shared"))) {
                                System.out.println("locked");
                                System.out.flush();
                                new BufferedReader(new InputStreamReader(System.in)).readLine();
                            }
                        }
                    }
                }
                """);
        var command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"),
                source.toString(), lockFile().toString()));
        command.addAll(List.of(args));
        // stderr apart: the JVM reports JAVA_TOOL_OPTIONS and the like there
        return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private String probe() throws Exception {
        var probe = otherProcess("probe");
        try (var out = new BufferedReader(new InputStreamReader(probe.getInputStream()))) {
            return out.readLine();
        } finally {
            probe.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void sharedHoldersLetEachOtherInAndHoldTheExclusiveOff() throws Exception {
        var release = new CountDownLatch(1);
        var held = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(3)) {
            try {
                var holders = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 2; i++) {
                    holders.add(pool.submit(() -> {
                        try (var lock = acquireShared(new ArrayList<>())) {
                            held.countDown();
                            release.await();
                        }
                        return null;
                    }));
                }
                assertTrue(held.await(10, TimeUnit.SECONDS), "a shared holder waited for another");
                var exclusive = pool.submit(() -> {
                    try (var lock = acquire(new ArrayList<>())) {
                        return true;
                    }
                });
                Thread.sleep(200);
                assertFalse(exclusive.isDone(), "taken exclusively while shared");
                release.countDown();
                for (var holder : holders) holder.get(10, TimeUnit.SECONDS);
                assertTrue(exclusive.get(10, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void theFileStaysLockedUntilTheLastSharedHolderOfTheProcessLetsGo() throws Exception {
        // Closing one holder's channel would drop the process's fcntl lock for both
        var releaseFirst = new CountDownLatch(1);
        var held = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            try {
                var first = pool.submit(() -> {
                    try (var lock = acquireShared(new ArrayList<>())) {
                        held.countDown();
                        releaseFirst.await();
                    }
                    return null;
                });
                assertTrue(held.await(10, TimeUnit.SECONDS));
                try (var second = acquireShared(new ArrayList<>())) {
                    assertEquals("busy", probe());
                    releaseFirst.countDown();
                    first.get(10, TimeUnit.SECONDS);
                    assertEquals("busy", probe(), "the first holder letting go released the second's lock");
                }
                assertEquals("free", probe());
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    @Test
    void anotherProcessesSharedLockLetsSharedInButNotExclusive() throws Exception {
        var child = otherProcess("hold", "shared");
        try (var out = new BufferedReader(new InputStreamReader(child.getInputStream()));
             var in = new PrintWriter(child.getOutputStream(), true);
             var pool = Executors.newSingleThreadExecutor()) {
            assertEquals("locked", out.readLine());
            var log = new CopyOnWriteArrayList<String>();
            acquireShared(log).close();
            assertEquals(List.of(), log, "a shared holder waited for another process's shared lock");

            var exclusive = pool.submit(() -> {
                try (var lock = acquire(log)) {
                    return child.isAlive();
                }
            });
            Eventually.holdsWithin(Duration.ofSeconds(10), () -> !(log.isEmpty()));
            assertEquals(List.of("Another isx process is testing — waiting..."), log);
            assertFalse(exclusive.isDone(), "taken exclusively while another process held it shared");
            in.println("release");
            exclusive.get(10, TimeUnit.SECONDS);
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void anotherProcessesExclusiveLockHoldsSharedOff() throws Exception {
        var child = otherProcess("hold", "exclusive");
        try (var out = new BufferedReader(new InputStreamReader(child.getInputStream()));
             var in = new PrintWriter(child.getOutputStream(), true);
             var pool = Executors.newSingleThreadExecutor()) {
            assertEquals("locked", out.readLine());
            var log = new CopyOnWriteArrayList<String>();
            var shared = pool.submit(() -> {
                try (var lock = acquireShared(log)) {
                    return child.isAlive();
                }
            });
            Eventually.holdsWithin(Duration.ofSeconds(10), () -> !(log.isEmpty()));
            assertEquals(List.of("Another isx process is testing — waiting..."), log);
            assertFalse(shared.isDone(), "taken shared while another process held it exclusively");
            in.println("release");
            shared.get(10, TimeUnit.SECONDS);
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void takingItSharedWhileHoldingItExclusivelyIsRefusedAndKeepsTheLock() throws Exception {
        try (var lock = acquire(new ArrayList<>())) {
            assertThrows(IllegalStateException.class, () -> acquireShared(new ArrayList<>()));
            // A downgrade would open a second channel, whose close drops the exclusive fcntl lock
            assertEquals("busy", probe(), "the refused attempt released the exclusive lock");
        }
    }
}
