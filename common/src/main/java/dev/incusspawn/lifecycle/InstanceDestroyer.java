package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.ProxyService;

/**
 * Deleting an instance and everything isx set up for it on the host. The caller holds the
 * instance's lock ({@code InstanceLockManager}). {@code isx destroy}, its bulk variants and
 * {@code isx mcp} delete through {@link #deleteHeld}; the TUI, which refreshes its view between
 * marking and deleting, does the same steps itself and calls {@link #refreshProxy()} after.
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
