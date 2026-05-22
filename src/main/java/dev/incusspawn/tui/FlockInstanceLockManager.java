package dev.incusspawn.tui;

import dev.incusspawn.Environment;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-lock-based implementation of {@link InstanceLockManager}.
 * Uses {@code fcntl} (via {@link FileChannel#tryLock()}) for kernel-level
 * cross-process mutual exclusion. Locks are automatically released
 * when the process exits, even on SIGKILL.
 * <p>
 * <b>fcntl gotcha:</b> on Linux, closing <em>any</em> file descriptor
 * for a file releases <em>all</em> fcntl locks held by the process on
 * that file. To prevent accidental self-release, {@link #tryAcquire}
 * synchronizes on the instance name so only one thread at a time can
 * open a channel for a given lock file, and {@link #isHeldByOther}
 * skips the file probe when this manager already holds the lock.
 */
@ApplicationScoped
public class FlockInstanceLockManager implements InstanceLockManager {

    private record HeldLock(FileChannel channel, FileLock lock) {}

    private final Map<String, HeldLock> heldLocks = new ConcurrentHashMap<>();
    private final Path lockDir;

    public FlockInstanceLockManager() {
        this(Environment.lockDir());
    }

    FlockInstanceLockManager(Path lockDir) {
        this.lockDir = lockDir;
    }

    @Override
    public Optional<LockHandle> tryAcquire(String instanceName, String operation) {
        // Synchronize per instance name to prevent two threads from opening
        // channels to the same lock file concurrently (fcntl self-release gotcha).
        synchronized (instanceName.intern()) {
            if (heldLocks.containsKey(instanceName)) {
                return Optional.empty();
            }

            try {
                Files.createDirectories(lockDir);
                var lockFile = lockDir.resolve(instanceName + ".lock");
                var channel = FileChannel.open(lockFile,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
                var fileLock = channel.tryLock();
                if (fileLock == null) {
                    channel.close();
                    return Optional.empty();
                }

                heldLocks.put(instanceName, new HeldLock(channel, fileLock));
                return Optional.of(new FlockLockHandle(instanceName));
            } catch (IOException e) {
                return Optional.empty();
            }
        }
    }

    @Override
    public boolean isHeldByOther(String instanceName) {
        // If WE hold it, it's not held by "other" — and we must not
        // open a second channel (fcntl would see our own lock).
        if (heldLocks.containsKey(instanceName)) {
            return false;
        }

        var lockFile = lockDir.resolve(instanceName + ".lock");
        if (!Files.exists(lockFile)) {
            return false;
        }

        // Safe to probe: we definitely don't hold a lock on this file,
        // so closing this channel won't release anything.
        try (var channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            var fileLock = channel.tryLock();
            if (fileLock == null) {
                return true;
            }
            fileLock.release();
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private void release(String instanceName) {
        var held = heldLocks.remove(instanceName);
        if (held != null) {
            try {
                held.lock.release();
            } catch (IOException ignored) {}
            try {
                held.channel.close();
            } catch (IOException ignored) {}
            try {
                Files.deleteIfExists(lockDir.resolve(instanceName + ".lock"));
            } catch (IOException ignored) {}
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
