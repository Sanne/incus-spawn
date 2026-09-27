package dev.incusspawn.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
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
    /**
     * Low, because {@code fcntl} has no queue: whoever polls first after a release wins, and a
     * waiter backed off to half a second would keep losing to newcomers polling every 10 ms
     * until it timed out, with the lock changing hands all along.
     */
    private static final long MAX_POLL_MILLIS = 50;

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
        return acquire(lockFile, activity, log, TIMEOUT, null);
    }

    /**
     * As {@link #acquire}, but where the file cannot be locked at all -- a home on NFS without
     * lockd, a read-only or odd filesystem -- warn once and go on holding only the in-process
     * lock, rather than fail. For a caller with a backstop of its own when processes do collide.
     * A holder that never lets go still times the wait out: only an unusable file degrades.
     *
     * @param warn where to say, once per file and process, that the file lock is not held
     */
    public static HostLock acquireOrDegrade(Path lockFile, String activity, Consumer<String> log,
                                            Consumer<String> warn) {
        return acquire(lockFile, activity, log, TIMEOUT, warn);
    }

    /** @param degradeWarn non-null to degrade rather than fail on an unusable file */
    static HostLock acquire(Path lockFile, String activity, Consumer<String> log, Duration timeout,
                            Consumer<String> degradeWarn) {
        var degrade = degradeWarn != null;
        Path key;
        IOException unusable = null;
        try {
            // fcntl locks a file, not a path: two spellings of one directory must share a lock
            Files.createDirectories(lockFile.getParent());
            key = lockFile.getParent().toRealPath().resolve(lockFile.getFileName());
        } catch (IOException e) {
            if (!degrade) throw new HostLockException("Failed to lock " + lockFile + ": " + e.getMessage(), e);
            key = lockFile.toAbsolutePath().normalize();
            unusable = e;
        }
        var inProcess = IN_PROCESS.computeIfAbsent(key, p -> new ReentrantLock());
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
        if (unusable != null) return degraded(inProcess, lockFile, activity, degradeWarn, unusable);
        try {
            var channel = FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                return new HostLock(inProcess, channel, lockFile(channel, activity, log, deadline));
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
        } catch (IOException e) {
            if (degrade) return degraded(inProcess, lockFile, activity, degradeWarn, e);
            inProcess.unlock();
            throw new HostLockException("Failed to lock " + lockFile + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            inProcess.unlock();
            throw e;
        }
    }

    private static final Set<Path> WARNED_UNUSABLE = ConcurrentHashMap.newKeySet();

    private static HostLock degraded(ReentrantLock inProcess, Path lockFile, String activity,
                                     Consumer<String> warn, IOException cause) {
        // Once per file and process: a TUI would otherwise repeat it on every action
        if (WARNED_UNUSABLE.add(lockFile.toAbsolutePath().normalize())) {
            warn.accept("Cannot lock " + lockFile + " (" + cause.getMessage()
                    + "); going on without it, so isx processes " + activity
                    + " at the same time are not held off each other.");
        }
        return new HostLock(inProcess, null, null);
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
            // Both null when degraded to the in-process lock alone
            if (lock != null) try { lock.release(); } catch (IOException ignored) {}
            if (channel != null) try { channel.close(); } catch (IOException ignored) {}
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
