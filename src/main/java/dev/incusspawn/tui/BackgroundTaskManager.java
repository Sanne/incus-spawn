package dev.incusspawn.tui;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages background tasks for TUI operations (delete, stop, etc.).
 * Tasks run on virtual threads and update their state asynchronously.
 */
@ApplicationScoped
public class BackgroundTaskManager {

    private final Map<String, BackgroundTask> tasks = new ConcurrentHashMap<>();
    private final Set<String> instancesWithPendingOps = ConcurrentHashMap.newKeySet();

    /**
     * Submit a background task for execution.
     *
     * @param displayName Human-readable name for UI display (present tense, e.g., "Stopping instance")
     * @param completedDisplayName Past-tense display name for completed state (e.g., "Stopped instance")
     * @param task The task to execute
     * @return Unique task ID
     */
    public String submit(String displayName, String completedDisplayName, Runnable task) {
        return submit(displayName, completedDisplayName, null, task);
    }

    /**
     * Submit a background task that operates on a specific instance/template.
     *
     * @param displayName Human-readable name for UI display (present tense, e.g., "Stopping instance")
     * @param completedDisplayName Past-tense display name for completed state (e.g., "Stopped instance")
     * @param targetName Instance/template name (for display), or null
     * @param task The task to execute
     * @return Unique task ID
     */
    public String submit(String displayName, String completedDisplayName, String targetName, Runnable task) {
        String id = UUID.randomUUID().toString();
        tasks.put(id, new BackgroundTask.Pending(id, displayName, targetName));

        Thread.startVirtualThread(() -> {
            try {
                task.run();
                tasks.put(id, new BackgroundTask.Completed(id, displayName, completedDisplayName,
                        targetName, true, null, System.currentTimeMillis()));
            } catch (Throwable t) {
                tasks.put(id, new BackgroundTask.Completed(id, displayName, completedDisplayName,
                        targetName, false, t.getMessage(), System.currentTimeMillis()));
            }
        });

        return id;
    }

    /**
     * Atomically claim an instance for an operation within this JVM.
     * @return true if the claim succeeded (no other in-process task is operating on this instance)
     */
    public boolean tryClaim(String instanceName) {
        return instancesWithPendingOps.add(instanceName);
    }

    /**
     * Release the in-process claim for an instance.
     */
    public void releaseClaim(String instanceName) {
        instancesWithPendingOps.remove(instanceName);
    }

    /**
     * Get all active tasks (running first, then completed by time), in stable order.
     */
    public List<BackgroundTask> getActiveTasks() {
        var list = new ArrayList<>(tasks.values());
        list.sort((a, b) -> {
            boolean aRunning = a.status() == BackgroundTask.TaskStatus.RUNNING;
            boolean bRunning = b.status() == BackgroundTask.TaskStatus.RUNNING;
            if (aRunning != bRunning) return aRunning ? -1 : 1;
            if (a instanceof BackgroundTask.Completed ca && b instanceof BackgroundTask.Completed cb) {
                return Long.compare(ca.completionTime(), cb.completionTime());
            }
            return a.id().compareTo(b.id());
        });
        return list;
    }

    /**
     * Get a specific task by ID.
     */
    public Optional<BackgroundTask> getTask(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    /**
     * Check if there's a running task for a specific instance/template.
     */
    public boolean hasRunningTask(String instanceName) {
        return instancesWithPendingOps.contains(instanceName);
    }

    /**
     * Remove completed tasks older than the specified duration.
     * Called periodically by the render loop to prevent memory leaks.
     */
    public void cleanupCompleted(Duration maxAge) {
        long now = System.currentTimeMillis();
        long cutoff = now - maxAge.toMillis();

        tasks.entrySet().removeIf(entry -> {
            var task = entry.getValue();
            return task instanceof BackgroundTask.Completed completed
                    && completed.completionTime() < cutoff;
        });
    }

    /**
     * Remove all completed tasks immediately.
     */
    public void clearCompleted() {
        tasks.entrySet().removeIf(entry -> entry.getValue() instanceof BackgroundTask.Completed);
    }

    /**
     * Get count of running tasks.
     */
    public long getRunningCount() {
        return tasks.values().stream()
                .filter(t -> t.status() == BackgroundTask.TaskStatus.RUNNING)
                .count();
    }

    /**
     * Get count of completed tasks.
     */
    public long getCompletedCount() {
        return tasks.values().stream()
                .filter(t -> t.status() != BackgroundTask.TaskStatus.RUNNING)
                .count();
    }
}
