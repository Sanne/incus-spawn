package dev.incusspawn.tui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FlockInstanceLockManagerTest {

    @TempDir
    Path lockDir;

    @Test
    void acquireAndRelease() {
        var mgr = new FlockInstanceLockManager(lockDir);
        var lock = mgr.tryAcquire("test-instance", "stopping");
        assertTrue(lock.isPresent());
        assertFalse(mgr.isHeldByOther("test-instance"));
        lock.get().close();
        assertFalse(mgr.isHeldByOther("test-instance"));
    }

    @Test
    void doubleAcquireSameInstanceFails() {
        var mgr = new FlockInstanceLockManager(lockDir);
        var lock1 = mgr.tryAcquire("test-instance", "stopping");
        assertTrue(lock1.isPresent());
        var lock2 = mgr.tryAcquire("test-instance", "deleting");
        assertTrue(lock2.isEmpty());
        lock1.get().close();
    }

    @Test
    void differentInstancesIndependent() {
        var mgr = new FlockInstanceLockManager(lockDir);
        var lock1 = mgr.tryAcquire("instance-a", "stopping");
        var lock2 = mgr.tryAcquire("instance-b", "stopping");
        assertTrue(lock1.isPresent());
        assertTrue(lock2.isPresent());
        lock1.get().close();
        lock2.get().close();
    }

    @Test
    void isHeldByOtherWhenNoLockFile() {
        var mgr = new FlockInstanceLockManager(lockDir);
        assertFalse(mgr.isHeldByOther("nonexistent"));
    }

    @Test
    void isHeldByOtherAfterRelease() {
        var mgr = new FlockInstanceLockManager(lockDir);
        var lock = mgr.tryAcquire("test-instance", "stopping");
        assertTrue(lock.isPresent());
        lock.get().close();
        assertFalse(mgr.isHeldByOther("test-instance"));
    }

    @Test
    void closeIsIdempotent() {
        var mgr = new FlockInstanceLockManager(lockDir);
        var lock = mgr.tryAcquire("test-instance", "stopping");
        assertTrue(lock.isPresent());
        lock.get().close();
        assertDoesNotThrow(() -> lock.get().close());
    }

    @Test
    void reacquireAfterRelease() {
        var mgr = new FlockInstanceLockManager(lockDir);
        var lock1 = mgr.tryAcquire("test-instance", "stopping");
        assertTrue(lock1.isPresent());
        lock1.get().close();
        var lock2 = mgr.tryAcquire("test-instance", "deleting");
        assertTrue(lock2.isPresent());
        lock2.get().close();
    }

    @Test
    void crossProcessContention() throws Exception {
        var javaCmd = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var mgr = new FlockInstanceLockManager(lockDir);
        var lock = mgr.tryAcquire("contested", "stopping");
        assertTrue(lock.isPresent());

        // Spawn a child process that tries to acquire the same lock
        var pb = new ProcessBuilder(javaCmd, "-cp",
                System.getProperty("java.class.path"),
                "dev.incusspawn.tui.FlockInstanceLockManagerTest$LockProbe",
                lockDir.toString(), "contested");
        pb.redirectErrorStream(true);
        var proc = pb.start();
        var output = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        int exitCode = proc.waitFor();

        // Child should fail to acquire (exit code 1)
        assertEquals(1, exitCode, "Child should fail to acquire held lock. Output: " + output);

        lock.get().close();

        // Now the child should succeed
        var pb2 = new ProcessBuilder(javaCmd, "-cp",
                System.getProperty("java.class.path"),
                "dev.incusspawn.tui.FlockInstanceLockManagerTest$LockProbe",
                lockDir.toString(), "contested");
        pb2.redirectErrorStream(true);
        var proc2 = pb2.start();
        proc2.getInputStream().readAllBytes();
        assertEquals(0, proc2.waitFor(), "Child should succeed after lock release");
    }

    @Test
    void lockFileDeletedAfterRelease() {
        var mgr = new FlockInstanceLockManager(lockDir);
        var lock = mgr.tryAcquire("cleanup-test", "stopping");
        assertTrue(lock.isPresent());
        assertTrue(Files.exists(lockDir.resolve("cleanup-test.lock")));
        lock.get().close();
        assertFalse(Files.exists(lockDir.resolve("cleanup-test.lock")),
                "Per-instance lock file should be deleted after release");
        assertTrue(Files.exists(lockDir.resolve(".global.lock")),
                "Global lock file should persist");
    }

    /** Helper main class for cross-process lock testing. */
    public static class LockProbe {
        public static void main(String[] args) {
            var dir = Path.of(args[0]);
            var name = args[1];
            var mgr = new FlockInstanceLockManager(dir);
            var lock = mgr.tryAcquire(name, "probe");
            if (lock.isEmpty()) {
                System.exit(1);
            }
            lock.get().close();
            System.exit(0);
        }
    }
}
