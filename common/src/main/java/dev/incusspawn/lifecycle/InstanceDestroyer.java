package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.ProxyService;

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
     */
    public static void deleteHeld(IncusClient incus, String name) {
        incus.setPendingOperation(name, Metadata.OP_DELETING);
        deleteMarked(incus, name);
    }

    /**
     * {@link #deleteHeld}, but only if {@code stillWanted} holds for the instance (its config, status) as
     * read <em>after</em> the {@link Metadata#OP_DELETING} mark is written: a writer that
     * changes the instance before the mark is seen here, and one that reads the mark after
     * writing sees it. Otherwise the mark is taken back and nothing is deleted. The mark is
     * written strictly -- one that did not land could not be seen, so a mark Incus refuses,
     * as it does for an instance already gone, throws. A failed read takes the mark back and
     * fails the delete; so does taking it back, strictly, since a mark left behind would leave
     * the instance busy for good. Returns whether the instance was deleted.
     */
    public static boolean deleteHeldIf(IncusClient incus, String name, Predicate<JsonNode> stillWanted) {
        incus.configSet(name, Metadata.PENDING_OP, Metadata.OP_DELETING);
        boolean wanted;
        try {
            var instance = incus.instanceMetadataOrThrow(name);
            if (instance == null) return false;
            wanted = stillWanted.test(instance);
        } catch (RuntimeException e) {
            incus.clearPendingOperation(name);
            throw e;
        }
        if (!wanted) {
            incus.configUnset(name, Metadata.PENDING_OP);
            return false;
        }
        deleteMarked(incus, name);
        return true;
    }

    private static void deleteMarked(IncusClient incus, String name) {
        try {
            incus.delete(name, true);
            InstanceLifecycle.removeHostIntegration(name);
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
