package dev.incusspawn.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-call context: cancellation and progress. A tool doing long work registers what to do if
 * the client cancels (for an exec, kill the process tree in the guest).
 */
final class ToolContext {

    interface ProgressSink {
        void progress(String message);
    }

    private final ProgressSink progress;
    private final List<Runnable> onCancel = new ArrayList<>();
    private volatile boolean cancelled;

    /** {@code progress} null when the client did not ask for progress notifications. */
    ToolContext(ProgressSink progress) {
        this.progress = progress;
    }

    boolean wantsProgress() {
        return progress != null;
    }

    boolean cancelled() {
        return cancelled;
    }

    void progress(String message) {
        if (progress != null) progress.progress(message);
    }

    /** Run {@code action} if the call is cancelled -- at once, if it already has been. */
    void onCancel(Runnable action) {
        boolean runNow;
        synchronized (onCancel) {
            runNow = cancelled;
            if (!runNow) onCancel.add(action);
        }
        if (runNow) action.run();
    }

    void cancel() {
        List<Runnable> actions;
        synchronized (onCancel) {
            if (cancelled) return;
            cancelled = true;
            actions = List.copyOf(onCancel);
        }
        for (var action : actions) {
            try {
                action.run();
            } catch (RuntimeException e) {
                System.err.println("isx mcp: cancelling: " + e.getMessage());
            }
        }
    }
}
