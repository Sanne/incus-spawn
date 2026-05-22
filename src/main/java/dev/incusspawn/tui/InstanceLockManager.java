package dev.incusspawn.tui;

import java.util.Optional;

/**
 * Cross-process lock coordinator for instance operations.
 * Implementations provide mutual exclusion so that at most one process
 * can perform a lifecycle operation (stop/delete/restart) on a given
 * instance at a time.
 */
public interface InstanceLockManager {

    /**
     * Attempt to acquire an exclusive lock for the named instance.
     *
     * @param instanceName the Incus instance or template name
     * @param operation description of the operation (for diagnostics)
     * @return a lock handle if acquired, empty if another holder exists
     */
    Optional<LockHandle> tryAcquire(String instanceName, String operation);

    /**
     * Check whether the lock is held by another process. Used for
     * stale metadata cleanup: if metadata says "deleting" but no
     * lock is held, the metadata is from a crashed process.
     */
    boolean isHeldByOther(String instanceName);

    interface LockHandle extends AutoCloseable {
        @Override
        void close();
    }
}
