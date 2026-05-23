package dev.incusspawn.tui;

/**
 * Represents the state of a background operation in the TUI.
 * Tasks can be running (Pending) or finished (Completed with success/failure status).
 */
public sealed interface BackgroundTask permits BackgroundTask.Pending, BackgroundTask.Completed {

    /**
     * Unique identifier for this task.
     */
    String id();

    /**
     * Human-readable display name shown in the UI.
     */
    String displayName();

    /**
     * Current status of the task.
     */
    TaskStatus status();

    /**
     * Optional instance/template name this task operates on (for duplicate prevention).
     */
    String targetName();

    /**
     * A task that is currently running.
     */
    record Pending(String id, String displayName, String targetName) implements BackgroundTask {
        @Override
        public TaskStatus status() {
            return TaskStatus.RUNNING;
        }
    }

    /**
     * A task that has completed (successfully or with failure).
     * @param completedDisplayName Past-tense display name for completed task (e.g., "Stopped instance-name")
     */
    record Completed(String id, String displayName, String completedDisplayName, String targetName,
                     boolean success, String message, long completionTime) implements BackgroundTask {
        @Override
        public TaskStatus status() {
            return success ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        }

        /**
         * Get the appropriate display name based on completion state.
         * For completed tasks, returns the past-tense version.
         */
        public String getDisplayText() {
            if (!success) return displayName;
            return completedDisplayName != null ? completedDisplayName : displayName;
        }
    }

    enum TaskStatus {
        RUNNING, SUCCESS, FAILED
    }
}
