package dev.incusspawn.tui;

import dev.incusspawn.Environment;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-lock-based implementation of {@link InstanceLockManager}.
 * Uses {@code fcntl} (via {@link FileChannel#tryLock()}) for kernel-level
 * cross-process mutual exclusion. Locks are automatically released
 * when the process exits, even on SIGKILL.
 * <p>
 * <b>fcntl gotcha:</b> on Linux, closing <em>any</em> file descriptor
 * for a file releases <em>all</em> fcntl locks held by the process on
 * that file. All methods that open or close channels for a given instance
 * synchronize on a per-instance stripe lock to prevent accidental
 * self-release.
 * <p>
 * <b>Global lock:</b> a process-lifetime {@code .global.lock} file
 * serializes per-instance lock file creation and deletion across
 * processes. This prevents the inode-swap race where deleting a lock
 * file after release allows a third process to lock a different inode.
 * The global lock is held only for the brief file syscalls; per-instance
 * fcntl locks provide the long-running mutual exclusion.
 */
@ApplicationScoped
public class FlockInstanceLockManager implements InstanceLockManager {

    private record HeldLock(FileChannel channel, FileLock lock) {}

    private static final int STRIPE_COUNT = 64;

    private final Map<String, HeldLock> heldLocks = new ConcurrentHashMap<>();
    private final Object[] lockStripes;
    private final Path lockDir;

    // Global lock: serializes per-instance lock file creation/deletion.
    // The channel is kept open for the process lifetime to avoid the
    // fcntl close-any-fd gotcha. The ReentrantLock ensures only one
    // thread at a time uses the channel.
    private final ReentrantLock globalMutex = new ReentrantLock();
    private volatile FileChannel globalChannel;

    public FlockInstanceLockManager() {
        this(Environment.lockDir());
    }

    FlockInstanceLockManager(Path lockDir) {
        this.lockDir = lockDir;
        this.lockStripes = new Object[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            lockStripes[i] = new Object();
        }
    }

    private Object lockFor(String instanceName) {
        return lockStripes[Math.floorMod(instanceName.hashCode(), STRIPE_COUNT)];
    }

    private static void validateName(String instanceName) {
        if (instanceName.contains("/") || instanceName.contains("\\")
                || instanceName.contains("..") || instanceName.isEmpty()) {
            throw new IllegalArgumentException("Unsafe instance name for lock file: " + instanceName);
        }
    }

    private FileChannel getGlobalChannel() throws IOException {
        if (globalChannel == null) {
            Files.createDirectories(lockDir);
            globalChannel = FileChannel.open(
                    lockDir.resolve(".global.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
        }
        return globalChannel;
    }

    /**
     * Execute an action while holding the cross-process global lock.
     * Locking order: caller holds stripe lock → globalMutex → global file lock.
     */
    private <T> T withGlobalLock(Callable<T> action) throws IOException {
        globalMutex.lock();
        try {
            FileLock globalLock = getGlobalChannel().lock();
            try {
                return action.call();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            } finally {
                globalLock.release();
            }
        } finally {
            globalMutex.unlock();
        }
    }

    @Override
    public Optional<LockHandle> tryAcquire(String instanceName, String operation) {
        validateName(instanceName);
        synchronized (lockFor(instanceName)) {
            if (heldLocks.containsKey(instanceName)) {
                return Optional.empty();
            }

            try {
                return withGlobalLock(() -> {
                    var lockFile = lockDir.resolve(instanceName + ".lock");
                    var channel = FileChannel.open(lockFile,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE);
                    FileLock fileLock;
                    try {
                        fileLock = channel.tryLock();
                    } catch (OverlappingFileLockException e) {
                        channel.close();
                        return Optional.<LockHandle>empty();
                    }
                    if (fileLock == null) {
                        channel.close();
                        return Optional.<LockHandle>empty();
                    }

                    try {
                        channel.truncate(0);
                        channel.position(0);
                        channel.write(ByteBuffer.wrap(
                                (operation + "\n").getBytes(StandardCharsets.UTF_8)));
                        channel.force(false);
                    } catch (IOException e) {
                        fileLock.release();
                        channel.close();
                        throw e;
                    }
                    heldLocks.put(instanceName, new HeldLock(channel, fileLock));
                    return Optional.<LockHandle>of(new FlockLockHandle(instanceName));
                });
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to acquire lock for " + instanceName, e);
            }
        }
    }

    @Override
    public boolean isHeldByOther(String instanceName) {
        validateName(instanceName);
        synchronized (lockFor(instanceName)) {
            if (heldLocks.containsKey(instanceName)) {
                return false;
            }

            try {
                return withGlobalLock(() -> {
                    var lockFile = lockDir.resolve(instanceName + ".lock");
                    if (!Files.exists(lockFile)) {
                        return false;
                    }

                    try (var channel = FileChannel.open(lockFile,
                            StandardOpenOption.WRITE)) {
                        FileLock fileLock;
                        try {
                            fileLock = channel.tryLock();
                        } catch (OverlappingFileLockException e) {
                            return false;
                        }
                        if (fileLock == null) {
                            return true;
                        }
                        fileLock.release();
                        return false;
                    }
                });
            } catch (IOException e) {
                return true;
            }
        }
    }

    private void release(String instanceName) {
        validateName(instanceName);
        synchronized (lockFor(instanceName)) {
            var held = heldLocks.remove(instanceName);
            if (held != null) {
                try {
                    withGlobalLock(() -> {
                        try {
                            held.lock.release();
                        } catch (IOException ignored) {}
                        try {
                            held.channel.close();
                        } catch (IOException ignored) {}
                        Files.deleteIfExists(lockDir.resolve(instanceName + ".lock"));
                        return null;
                    });
                } catch (IOException ignored) {}
            }
        }
    }

    private class FlockLockHandle implements LockHandle {
        private final String instanceName;

        FlockLockHandle(String instanceName) {
            this.instanceName = instanceName;
        }

        @Override
        public void close() {
            release(instanceName);
        }
    }
}
