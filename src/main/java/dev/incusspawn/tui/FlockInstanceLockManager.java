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
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-lock-based implementation of {@link InstanceLockManager}.
 * Uses {@code fcntl} (via {@link FileChannel#tryLock()}) for kernel-level
 * cross-process mutual exclusion. Locks are automatically released
 * when the process exits, even on SIGKILL.
 * <p>
 * <b>fcntl gotcha:</b> on Linux, closing <em>any</em> file descriptor
 * for a file releases <em>all</em> fcntl locks held by the process on
 * that file. All methods that open or close channels for a given instance
 * synchronize on a per-instance lock object to prevent accidental
 * self-release.
 */
@ApplicationScoped
public class FlockInstanceLockManager implements InstanceLockManager {

    private record HeldLock(FileChannel channel, FileLock lock) {}

    private final Map<String, HeldLock> heldLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> lockStripes = new ConcurrentHashMap<>();
    private final Path lockDir;

    public FlockInstanceLockManager() {
        this(Environment.lockDir());
    }

    FlockInstanceLockManager(Path lockDir) {
        this.lockDir = lockDir;
    }

    private Object lockFor(String instanceName) {
        return lockStripes.computeIfAbsent(instanceName, k -> new Object());
    }

    @Override
    public Optional<LockHandle> tryAcquire(String instanceName, String operation) {
        synchronized (lockFor(instanceName)) {
            if (heldLocks.containsKey(instanceName)) {
                return Optional.empty();
            }

            FileChannel channel = null;
            try {
                Files.createDirectories(lockDir);
                var lockFile = lockDir.resolve(instanceName + ".lock");
                channel = FileChannel.open(lockFile,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
                FileLock fileLock;
                try {
                    fileLock = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    channel.close();
                    return Optional.empty();
                }
                if (fileLock == null) {
                    channel.close();
                    return Optional.empty();
                }

                channel.truncate(0);
                channel.position(0);
                channel.write(ByteBuffer.wrap(
                        (operation + "\n").getBytes(StandardCharsets.UTF_8)));
                channel.force(false);
                heldLocks.put(instanceName, new HeldLock(channel, fileLock));
                return Optional.of(new FlockLockHandle(instanceName));
            } catch (IOException e) {
                if (channel != null) {
                    try { channel.close(); } catch (IOException ignored) {}
                }
                throw new UncheckedIOException("Failed to acquire lock for " + instanceName, e);
            }
        }
    }

    @Override
    public boolean isHeldByOther(String instanceName) {
        synchronized (lockFor(instanceName)) {
            if (heldLocks.containsKey(instanceName)) {
                return false;
            }

            var lockFile = lockDir.resolve(instanceName + ".lock");
            if (!Files.exists(lockFile)) {
                return false;
            }

            try (var channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE,
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
            } catch (IOException e) {
                return false;
            }
        }
    }

    private void release(String instanceName) {
        synchronized (lockFor(instanceName)) {
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
