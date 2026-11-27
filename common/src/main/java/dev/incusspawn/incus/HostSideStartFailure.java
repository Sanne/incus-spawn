package dev.incusspawn.incus;

/**
 * A VM that Incus stopped during its start, by telling a healthy QEMU to quit (#2675).
 *
 * <p>Incus 6.23 has been seen to do so about once in 150 VM starts: it loses its QEMU monitor's
 * event stream, takes that for the VM dying, and sends {@code quit}. What says so is the monitor
 * log of this boot ({@code qemu.qmp.log}, which Incus rotates at every start): its first
 * {@code SHUTDOWN} event has the reason {@code host-qmp-quit}. A guest that died on its own shuts
 * down with another reason ({@code guest-panic}, {@code guest-shutdown}), so it is reported as a
 * plain {@link IncusException}. The console log cannot tell the two apart: Incus writes it only
 * when someone reads it while the VM runs, so a guest that printed and died can read as silent,
 * and one Incus stopped after a read holds what it printed.
 *
 * <p>Thrown by {@link IncusClient#waitForReady}, which does not own the start; the caller that
 * does may start the VM again once ({@code dev.incusspawn.lifecycle.HostSideStart}). A person's
 * {@code incus stop --force} writes the same reason, and is retried too.
 */
public class HostSideStartFailure extends IncusException {
    public HostSideStartFailure(String message) {
        super(message);
    }

    /** The reason QEMU gives when the host tells it to quit over its monitor. */
    static final String HOST_QUIT = "\"host-qmp-quit\"";

    /**
     * Whether the first {@code SHUTDOWN} event in {@code qmpLog}, the monitor log of one boot, is
     * the host telling QEMU to quit. False for a log with no such event, or none at all.
     */
    public static boolean hostQuitFirst(String qmpLog) {
        if (qmpLog == null) return false;
        return qmpLog.lines()
                .filter(l -> l.contains("Event:") && l.contains("\"SHUTDOWN\""))
                .findFirst()
                .map(l -> l.contains(HOST_QUIT))
                .orElse(false);
    }
}
