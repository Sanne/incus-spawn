package dev.incusspawn.lifecycle;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqObject;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Output;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.ProxyService;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Deleting an instance and everything isx set up for it on the host. The caller holds the
 * instance's lock ({@code InstanceLockManager}). {@code isx destroy}, its bulk variants and
 * {@code isx mcp}'s {@code destroy_instance} delete through {@link #deleteHeld}; the {@code isx mcp}
 * orphan sweep, which must not delete what an adoption took meanwhile, through
 * {@link #deleteHeldIf}. The TUI, which refreshes its view between marking and deleting, does
 * the same steps itself and calls {@link #refreshProxy()} after.
 */
public final class InstanceDestroyer {

    private InstanceDestroyer() {}

    /**
     * Mark the instance as being deleted, delete it and remove its host integration. On failure
     * the pending-operation mark is cleared again and the exception propagates. Does not signal
     * the proxy: a caller deleting several instances calls {@link #refreshProxy()} once after.
     * Returns the host tools it was removed from, for the caller to say after its step
     * ({@link #saidRemoved}).
     */
    public static List<String> deleteHeld(IncusClient incus, String name) {
        incus.setPendingOperation(name, Metadata.OP_DELETING);
        return deleteMarked(incus, name);
    }

    /**
     * Say which host tools {@code name} was removed from, as its first start said it was added:
     * after the caller's live step, which a step printed inside it would break.
     */
    public static void saidRemoved(String name, List<String> removedFrom, Output out) {
        var shown = OutputFormat.oneLine(name);
        for (var tool : removedFrom) out.step("Removed " + shown + " from " + tool + ".");
    }

    /**
     * {@link #deleteHeld}, but only if {@code stillWanted} holds for the instance (its config, status) as
     * read <em>after</em> the {@link Metadata#OP_DELETING} mark is written: a writer that
     * changes the instance before the mark is seen here, and one that reads the mark after
     * writing sees it. Otherwise the mark is taken back and nothing is deleted. The mark is
     * written strictly -- one that did not land could not be seen, so a mark Incus refuses,
     * as it does for an instance already gone, throws. A failed read takes the mark back and
     * fails the delete; so does taking it back, strictly, since a mark left behind would leave
     * the instance busy for good. Returns empty when the instance was not deleted, else the
     * host tools it was removed from, as {@link #deleteHeld} does.
     */
    public static Optional<List<String>> deleteHeldIf(IncusClient incus, String name, Predicate<JqValue> stillWanted) {
        if (!markIf(incus, name, Metadata.OP_DELETING, stillWanted)) return Optional.empty();
        return Optional.of(deleteMarked(incus, name));
    }

    /**
     * Mark the instance with {@code operation}, strictly, then read it and test {@code stillWanted}:
     * true leaves the mark for the caller to act under and clear; false (or an instance gone)
     * takes it back strictly and returns false. A failed read takes the mark back and throws.
     * The guard {@link #deleteHeldIf} and the {@code isx mcp} orphan sweep's stop share.
     */
    public static boolean markIf(IncusClient incus, String name, String operation, Predicate<JqValue> stillWanted) {
        incus.configSet(name, Metadata.PENDING_OP, operation);
        boolean wanted;
        try {
            var instance = incus.instanceMetadataOrThrow(name);
            if (instance == null) return false;
            wanted = stillWanted.test(instance);
        } catch (RuntimeException e) {
            incus.clearPendingOperation(name);
            throw e;
        }
        if (!wanted) incus.configUnset(name, Metadata.PENDING_OP);
        return wanted;
    }

    /**
     * Stop the instance under the {@link Metadata#OP_STOPPING} mark if {@code stillWanted} holds once
     * it is marked ({@link #markIf}), clearing the mark after; the caller holds the instance's lock.
     * {@code beforeStop} runs under the mark first, and {@code onFailure} after a stop that failed,
     * before the mark is cleared and the failure rethrown. Returns whether it stopped: {@code isx
     * stop} and {@code isx mcp}'s stop of a held instance share it.
     */
    public static boolean stopMarkedIf(IncusClient incus, String name, Predicate<JqValue> stillWanted,
                                       Runnable beforeStop, Runnable onFailure) {
        if (!markIf(incus, name, Metadata.OP_STOPPING, stillWanted)) return false;
        try {
            beforeStop.run();
            incus.stop(name);
        } catch (RuntimeException e) {
            onFailure.run();
            incus.clearPendingOperation(name);
            throw e;
        }
        incus.configUnset(name, Metadata.PENDING_OP);
        return true;
    }

    private static List<String> deleteMarked(IncusClient incus, String name) {
        try {
            incus.delete(name, true);
            return InstanceLifecycle.removeHostIntegration(name, BuildOutput.bound());
        } catch (RuntimeException e) {
            incus.clearPendingOperation(name);
            throw e;
        }
    }

    /**
     * The freed static IP is the lowest free address, so the next branch usually reuses it.
     * Until the proxy re-reads the instance list, that address still maps to the deleted
     * instance -- and the new one would inherit its credential account. Tell the proxy now
     * rather than leaving a window.
     */
    public static void refreshProxy() {
        ProxyService.signalAccountRefresh();
    }
}
