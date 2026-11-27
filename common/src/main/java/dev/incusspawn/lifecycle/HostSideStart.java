package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.HostSideStartFailure;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.Output;
import dev.incusspawn.util.OutputFormat;

import java.util.function.Supplier;

/**
 * One more start for a VM that Incus stopped by telling a healthy QEMU to quit (#2675).
 *
 * <p>Incus 6.23 has been seen to do so milliseconds into a start, about once in 150 starts, which
 * cost a whole build. Its monitor log says so ({@link HostSideStartFailure}), and the VM is
 * stopped, so starting it again costs about a second. Its devices are still attached, and the
 * failed start already consumed the first start's template application. A guest that died on its
 * own shuts down for another reason and is never retried, and neither is a second host-side stop:
 * one that persists costs one extra start and is then reported as before.
 *
 * <p>Used by the code that owns a start, around the wait for its guest; the wait itself only
 * classifies, since it does not own the start. A wait that succeeds costs no request here.
 */
public final class HostSideStart {

    private HostSideStart() {}

    /**
     * {@code ready}, the wait for the started VM {@code name}; when it reports a host-side stop,
     * say so to {@code out}, start the VM again with {@code start}, the caller's own start with
     * whatever handling of its failures that has, and wait once more.
     */
    public static <T> T retryOnce(String name, Output out, Runnable start, Supplier<T> ready) {
        try {
            return ready.get();
        } catch (HostSideStartFailure e) {
            out.warn("Incus stopped VM " + OutputFormat.oneLine(name) + " while it was starting, so isx is starting it again.",
                    "Incus sometimes does this to a healthy VM on a busy host.");
            try {
                start.run();
            } catch (RuntimeException startFailed) {
                // Why it stopped stays with the start that could not follow it
                startFailed.addSuppressed(e);
                throw startFailed;
            }
            return ready.get();
        }
    }

    /** {@link #retryOnce(String, Output, Runnable, Supplier)}, starting it again as a build or branch starts it. */
    public static <T> T retryOnce(IncusClient incus, String name, Output out, Supplier<T> ready) {
        return retryOnce(name, out, () -> InstanceLifecycle.startInstance(incus, name, out), ready);
    }
}
