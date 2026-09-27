package dev.incusspawn.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * A host-wide mutex on a lock file, held until {@link #close()}: an {@code fcntl} lock for other
 * processes, which the kernel releases even if this one is killed, behind an in-process lock
 * for other threads of this one.
 *
 * <p>The in-process lock is not optional. A second {@link FileChannel#tryLock} on a file this
 * process already locks throws rather than waits, and closing any channel on the file drops
 * every {@code fcntl} lock the process holds on it, so only one thread may have it open.
 *
 * <p>Not reentrant: acquiring a lock this thread already holds throws. Callers that nest share
 * the work through a variant that assumes the lock is held (see {@code VmManager}).
 */
public final class HostLock implements AutoCloseable {

    /** Bounds the wait for a holder that is stuck, not one that is slow. */
    public static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final long FIRST_POLL_MILLIS = 10;
    private static final long MAX_POLL_MILLIS = 500;

    private static final Map<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();

    private final ReentrantLock inProcess;
    private final FileChannel channel;
    private final FileLock lock;

    private HostLock(ReentrantLock inProcess, FileChannel channel, FileLock lock) {
        this.inProcess = inProcess;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Wait for the lock, at most {@link #TIMEOUT}.
     *
     * @param activity what the holder is doing, for messages: "managing the VM"
     * @param log      where to say that another process holds the lock and this one waits
     * @throws HostLockException if the lock cannot be taken in time or at all
     */
    public static HostLock acquire(Path lockFile, String activity, Consumer<String> log) {
        return acquire(lockFile, activity, log, TIMEOUT);
    }

    static HostLock acquire(Path lockFile, String activity, Consumer<String> log, Duration timeout) {
        var inProcess = IN_PROCESS.computeIfAbsent(lockFile.toAbsolutePath().normalize(),
                p -> new ReentrantLock());
        if (inProcess.isHeldByCurrentThread()) {
            throw new IllegalStateException("Lock " + lockFile + " is already held by this thread");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            if (!inProcess.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw timedOut(activity);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HostLockException("Interrupted waiting for another isx process " + activity, e);
        }
        try {
            Files.createDirectories(lockFile.getParent());
            var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                return new HostLock(inProcess, channel, lockFile(channel, activity, log, deadline));
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
        } catch (IOException e) {
            inProcess.unlock();
            throw new HostLockException("Failed to lock " + lockFile + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            inProcess.unlock();
            throw e;
        }
    }

    private static FileLock lockFile(FileChannel channel, String activity, Consumer<String> log,
                                     long deadline) throws IOException {
        var lock = channel.tryLock();
        if (lock != null) return lock;
        log.accept("Another isx process is " + activity + " — waiting...");
        // Starts short, as a lock held for a few requests frees within milliseconds
        long poll = FIRST_POLL_MILLIS;
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(poll);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new HostLockException("Interrupted waiting for another isx process " + activity, e);
            }
            lock = channel.tryLock();
            if (lock != null) return lock;
            poll = Math.min(poll * 2, MAX_POLL_MILLIS);
        }
        throw timedOut(activity);
    }

    private static HostLockException timedOut(String activity) {
        return new HostLockException("Timed out waiting for another isx process " + activity + ".");
    }

    @Override
    public void close() {
        try {
            try { lock.release(); } catch (IOException ignored) {}
            try { channel.close(); } catch (IOException ignored) {}
        } finally {
            inProcess.unlock();
        }
    }

    public static final class HostLockException extends RuntimeException {
        HostLockException(String message) {
            super(message);
        }

        HostLockException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
