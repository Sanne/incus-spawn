package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.VmAgentFailure;
import dev.incusspawn.proxy.InstanceSecret;

import java.util.HashMap;
import java.util.function.Consumer;

/**
 * What {@code isx shell}, {@code isx run} and the TUI do about a running VM whose incus-agent
 * does not answer.
 *
 * <p>Restarting the VM recovers an agent that merely wedged, but it is a cold boot that kills
 * whatever the guest was doing, and it cannot help an agent that fails to start on this image:
 * the next boot fails identically (#843, found debugging #842). So a VM is restarted only when
 * nothing says the restart is futile, and at most once per boot:
 * <ul>
 *   <li>when this boot's console log shows the agent failing ({@link VmAgentFailure}) and
 *       systemd has not started it since, the agent gets the grace {@code waitForReady} gives
 *       systemd's restart, then the failure is reported instead;</li>
 *   <li>the boot a restart produces is stamped ({@link Metadata#AGENT_RESTART_BOOT}), so an
 *       agent still unresponsive on it is reported rather than cycled again on every attempt.
 *       Any other boot -- one the user started, or a guest reboot -- gets its one restart.</li>
 * </ul>
 * The guest is asked to shut down first, and powered off only if it does not.
 */
public final class VmAgentRecovery {

    /** How long a guest gets to shut down before it is powered off. */
    static final int GRACEFUL_STOP_SECONDS = 15;

    private VmAgentRecovery() {}

    /**
     * Bring VM {@code name}'s unresponsive agent back, restarting the VM if that can help, and
     * wait until it answers; or throw saying why a restart would not help. Progress and warnings
     * go to {@code say}.
     */
    public static void restartForAgent(IncusClient incus, String name, Consumer<String> say) {
        if (!incus.agentFailureLines(name).isEmpty()) {
            // systemd may still be restarting the agent: waitForReady gives it that grace,
            // then reports the failure it logged.
            try {
                incus.waitForReady(name, MachineType.VM);
                return;
            } catch (IncusException failed) {
                throw refusal(name, failed.getMessage()
                        + "\nNot restarting " + name + ": the next boot would most likely fail the same way.");
            }
        }
        // One read serves both the stamp and the device repairs before the start: a stop
        // changes neither config nor devices. A stamp is never "0", so a pid the daemon
        // cannot report never matches it.
        var instance = incus.instanceMetadata(name);
        if (Long.toString(incus.pid(name)).equals(instance.path("config").path(Metadata.AGENT_RESTART_BOOT).asText())) {
            throw refusal(name, "The incus-agent in VM " + name + " is not responding, and isx"
                    + " already restarted " + name + " once to recover it; not restarting it again."
                    + "\nIts boot log may say why: incus console " + name + " --show-log");
        }

        say.accept("VM agent not responding, restarting " + name + "...");
        try {
            incus.stop(name, GRACEFUL_STOP_SECONDS);
        } catch (RuntimeException guestIgnoredShutdown) {
            incus.forceStop(name);
        }
        InstanceLifecycle.prepareHostDevicesForStart(incus, name, instance, say);
        InstanceLifecycle.startInstance(incus, name, say);
        // Stamped before the wait, so a boot whose agent never comes up is still recorded
        // however the wait ends. The boot's new secret (#934) in the same write: nothing in the
        // guest has it before the probe below puts it there.
        var stamps = new HashMap<String, String>();
        var secret = InstanceSecret.stampInto(stamps);
        long restarted = incus.pid(name);
        if (restarted > 0) stamps.put(Metadata.AGENT_RESTART_BOOT, Long.toString(restarted));
        incus.configSetAll(name, stamps);
        incus.waitForReady(name, MachineType.VM, InstanceSecret.GUEST_SCRIPT, InstanceSecret.guestEnv(secret));
    }

    private static IncusException refusal(String name, String why) {
        return new IncusException(why + "\nTo restart it anyway: incus restart --force " + name);
    }
}
