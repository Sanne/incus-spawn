package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tui.InstanceLockManager;
import dev.incusspawn.util.Output;
import dev.incusspawn.util.OutputFormat;

import java.time.Duration;

/**
 * Starting an existing instance for use without opening a shell: {@code isx start} and
 * {@code isx ssh-proxy --start} (#1720). It is {@code isx shell}'s preparation
 * ({@link InstancePrep#prepare}): the proxy check, the repairs, the start, and the per-start
 * secret and credential proofs, without which an agent in the instance would find its
 * placeholders empty. A bare Incus start would skip all of that.
 *
 * <p>Under the instance's {@code starting} lock, as {@code isx mcp}'s {@code start_instance}. A
 * caller that finds it taken (another connection starting it, a stop under way) waits for it, where
 * {@code start_instance} answers busy, and
 * then prepares the instance itself rather than taking {@code Running} for ready: an instance is
 * running before its start has delivered the proofs. Preparing a ready instance costs little.
 */
public final class InstanceStart {

    /** How long a start waits for another isx process holding the instance. */
    static final Duration WAIT = Duration.ofMinutes(2);
    private static final Duration POLL = Duration.ofMillis(250);

    /** The preparation itself; {@link InstancePrep#prepare} but in tests. */
    @FunctionalInterface
    interface Preparer {
        void prepare(IncusClient incus, String name, Output out);

        /** {@code isx shell}'s: the preparation, then the GUI passthrough's health (#1720). */
        Preparer FOR_USE = (incus, name, out) -> {
            InstancePrep.prepare(incus, name, out);
            dev.incusspawn.lifecycle.GuiPassthrough.checkGuiHealth(incus, name, out);
        };
    }

    /**
     * What to do when another isx process holds an instance's lock: a command, the TUI or an
     * {@code isx mcp} session, while it starts, stops, restarts or destroys the instance, or the
     * TUI clears a mark an interrupted one left.
     */
    public static final String BUSY_ADVICE = "That process (an isx command, the TUI or an agent's isx mcp) is changing its state,"
            + " for example starting or stopping it. Try again when it is done.";

    private InstanceStart() {}

    /** {@code 2 minutes}, {@code 30 seconds}: how long a wait was, in the unit a person reads it in. */
    static String span(Duration wait) {
        var minutes = wait.toSeconds() >= 60 && wait.toSeconds() % 60 == 0;
        var n = minutes ? wait.toMinutes() : wait.toSeconds();
        return n + (minutes ? " minute" : " second") + (n == 1 ? "" : "s");
    }

    /**
     * Prepare {@code name} for use under its lock, waiting up to {@code wait} for another holder,
     * and saying once that it waits. Throws {@link InstancePrep.Refused} when the preparation
     * refuses or the lock stays taken.
     */
    static void start(IncusClient incus, InstanceLockManager locks, String name, Output out,
                      Preparer preparer, Duration wait) throws InterruptedException {
        try (var held = lock(locks, name, Metadata.OP_STARTING, out, wait)) {
            preparer.prepare(incus, name, out);
        }
    }

    /**
     * The instance's lock for {@code operation}, waiting up to {@code wait} for another holder and
     * saying once that it waits; {@code isx stop} takes its lock the same way. Throws
     * {@link InstancePrep.Refused} when it stays taken.
     */
    static InstanceLockManager.LockHandle lock(InstanceLockManager locks, String name, String operation,
                                               Output out, Duration wait) throws InterruptedException {
        var deadline = System.nanoTime() + wait.toNanos();
        var said = false;
        try {
            while (true) {
                var lock = locks.tryAcquire(name, operation);
                if (lock.isPresent()) {
                    if (said) out.stepDone(null);
                    return lock.get();
                }
                if (System.nanoTime() >= deadline) {
                    throw new InstancePrep.Refused("'" + OutputFormat.oneLine(name) + "' is still in use by another isx process after "
                            + span(wait) + ".\n" + BUSY_ADVICE, false);
                }
                if (!said) {
                    out.stepStart("Waiting for another isx process using " + name);
                    said = true;
                }
                Thread.sleep(POLL.toMillis());
            }
        } catch (InterruptedException | RuntimeException e) {
            // The wait ends with its ✗ however it ends, an interrupt included
            if (said) out.abandonStep();
            throw e;
        }
    }
}
