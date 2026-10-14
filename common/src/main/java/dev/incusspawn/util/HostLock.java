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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
 *
 * <p>{@link #acquireShared} takes it shared instead: any number of shared holders, in this process
 * and others, but none while it is held exclusively. A process holds one shared {@code fcntl} lock
 * for all its shared holders, released with the last of them.
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

    private static final Map<Path, InProcess> IN_PROCESS = new ConcurrentHashMap<>();

    /** This process's side of one lock file: its threads, and the shared {@code fcntl} lock they hold. */
    private static final class InProcess {
        final ReentrantReadWriteLock threads = new ReentrantReadWriteLock();
        /** Guards the rest, and is held while the first shared holder waits for the file. */
        final ReentrantLock guard = new ReentrantLock();
        int sharedHolders;
        FileChannel sharedChannel;
        FileLock sharedLock;
    }

    private final Lock inProcess;
    private final FileChannel channel;
    private final FileLock lock;
    /** Non-null for a shared holder, which leaves the file to the last of them. */
    private final InProcess shared;
    private boolean closed;

    private HostLock(Lock inProcess, FileChannel channel, FileLock lock, InProcess shared) {
        this.inProcess = inProcess;
        this.channel = channel;
        this.lock = lock;
        this.shared = shared;
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

    /** As {@link #acquire}, with a wait appropriate to a longer-running operation. */
    public static HostLock acquire(Path lockFile, String activity, Consumer<String> log, Duration timeout) {
        return acquire(lockFile, activity, log, timeout, null);
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

    /** As {@link #acquireOrDegrade}, waiting at most {@code timeout}. */
    public static HostLock acquireOrDegrade(Path lockFile, String activity, Consumer<String> log,
                                            Consumer<String> warn, Duration timeout) {
        return acquire(lockFile, activity, log, timeout, warn);
    }

    /**
     * As {@link #acquireOrDegrade}, but shared: held off only while the lock is held exclusively,
     * and holding off only those who want it exclusively. Closing it twice is harmless.
     *
     * @throws IllegalStateException if this thread holds it exclusively, as {@link #acquire} does
     *                               for any lock this thread already holds
     */
    public static HostLock acquireShared(Path lockFile, String activity, Consumer<String> log,
                                         Consumer<String> warn) {
        var key = key(lockFile);
        var inProcess = IN_PROCESS.computeIfAbsent(key.path(), p -> new InProcess());
        // A downgrade would open a second channel, whose close drops the exclusive fcntl lock
        if (inProcess.threads.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException("Lock " + lockFile + " is already held by this thread");
        }
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        var readLock = inProcess.threads.readLock();
        lockInProcess(readLock, TIMEOUT, activity);
        if (key.unusable() != null) return degraded(readLock, lockFile, activity, warn, key.unusable());
        try {
            // Bounded by the same deadline: another thread may be waiting for the file under it
            lockInProcess(inProcess.guard, Duration.ofNanos(Math.max(0, deadline - System.nanoTime())), activity);
        } catch (RuntimeException e) {
            readLock.unlock();
            throw e;
        }
        try {
            if (inProcess.sharedHolders == 0) {
                try {
                    var locked = open(key.path(), true, activity, log, deadline);
                    inProcess.sharedChannel = locked.channel();
                    inProcess.sharedLock = locked.lock();
                } catch (IOException e) {
                    return degraded(readLock, lockFile, activity, warn, e);
                } catch (RuntimeException e) {
                    readLock.unlock();
                    throw e;
                }
            }
            inProcess.sharedHolders++;
        } finally {
            inProcess.guard.unlock();
        }
        return new HostLock(readLock, null, null, inProcess);
    }

    private record Key(Path path, IOException unusable) {}

    private static Key key(Path lockFile) {
        try {
            // fcntl locks a file, not a path: two spellings of one directory must share a lock
            Files.createDirectories(lockFile.getParent());
            return new Key(lockFile.getParent().toRealPath().resolve(lockFile.getFileName()), null);
        } catch (IOException e) {
            return new Key(lockFile.toAbsolutePath().normalize(), e);
        }
    }

    private static void lockInProcess(Lock lock, Duration timeout, String activity) {
        try {
            if (!lock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw timedOut(activity);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HostLockException("Interrupted waiting for another isx process " + activity, e);
        }
    }

    /** @param degradeWarn non-null to degrade rather than fail on an unusable file */
    static HostLock acquire(Path lockFile, String activity, Consumer<String> log, Duration timeout,
                            Consumer<String> degradeWarn) {
        var degrade = degradeWarn != null;
        var key = key(lockFile);
        var unusable = key.unusable();
        if (unusable != null && !degrade) {
            throw new HostLockException("Failed to lock " + lockFile + ": " + unusable.getMessage(), unusable);
        }
        var threads = IN_PROCESS.computeIfAbsent(key.path(), p -> new InProcess()).threads;
        if (threads.isWriteLockedByCurrentThread() || threads.getReadHoldCount() > 0) {
            throw new IllegalStateException("Lock " + lockFile + " is already held by this thread");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        var inProcess = threads.writeLock();
        lockInProcess(inProcess, timeout, activity);
        if (unusable != null) return degraded(inProcess, lockFile, activity, degradeWarn, unusable);
        try {
            var locked = open(key.path(), false, activity, log, deadline);
            return new HostLock(inProcess, locked.channel(), locked.lock(), null);
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

    private static HostLock degraded(Lock inProcess, Path lockFile, String activity,
                                     Consumer<String> warn, IOException cause) {
        // Once per file and process: a TUI would otherwise repeat it on every action
        if (WARNED_UNUSABLE.add(lockFile.toAbsolutePath().normalize())) {
            warn.accept("Cannot lock " + lockFile + " (" + cause.getMessage()
                    + "); going on without it, so isx processes " + activity
                    + " at the same time are not held off each other.");
        }
        // A degraded shared holder holds no file lock, so it has nothing to leave to the others
        return new HostLock(inProcess, null, null, null);
    }

    private record Locked(FileChannel channel, FileLock lock) {}

    /** Opens the file and waits for its lock; a shared one needs the channel readable. */
    private static Locked open(Path file, boolean shared, String activity, Consumer<String> log,
                               long deadline) throws IOException {
        var channel = shared
                ? FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
                : FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            return new Locked(channel, lockFile(channel, shared, activity, log, deadline));
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static FileLock lockFile(FileChannel channel, boolean shared, String activity,
                                     Consumer<String> log, long deadline) throws IOException {
        var lock = channel.tryLock(0, Long.MAX_VALUE, shared);
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
            lock = channel.tryLock(0, Long.MAX_VALUE, shared);
            if (lock != null) return lock;
            poll = Math.min(poll * 2, MAX_POLL_MILLIS);
        }
        throw timedOut(activity);
    }

    private static HostLockException timedOut(String activity) {
        return new HostLockException("Timed out waiting for another isx process " + activity + ".");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (shared != null) {
                shared.guard.lock();
                try {
                    if (--shared.sharedHolders == 0) {
                        release(shared.sharedLock, shared.sharedChannel);
                        shared.sharedLock = null;
                        shared.sharedChannel = null;
                    }
                } finally {
                    shared.guard.unlock();
                }
            }
            // Both null when degraded to the in-process lock alone, or shared
            release(lock, channel);
        } finally {
            inProcess.unlock();
        }
    }

    private static void release(FileLock lock, FileChannel channel) {
        if (lock != null) try { lock.release(); } catch (IOException ignored) {}
        if (channel != null) try { channel.close(); } catch (IOException ignored) {}
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
