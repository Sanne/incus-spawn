package dev.incusspawn.lifecycle;

import dev.incusspawn.util.Output;
import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.Platform;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.VmAgentFailure;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.ProofToken;

import java.util.HashMap;

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
     * go to {@code out}.
     *
     * <p>Under the instance's secret lock (#1065), as its restart gives it a new secret. After
     * waiting for another isx, which may have just restarted it, it asks the agent again first.
     */
    public static void restartForAgent(IncusClient incus, String name, Output out) {
        try (var lock = InstanceLifecycle.secretLock(incus, name, MachineType.VM, "recovering " + name, out)) {
            JqValue instance = null;
            if (lock.waited()) {
                instance = incus.instanceMetadataOrThrow(name);
                var probe = InstanceLifecycle.probeAgent(incus, name, instance);
                if (probe != null) {
                    if (InstanceSecret.missingIn(probe)) {
                        InstanceLifecycle.giveSecretHeld(incus, name, instance, null, Metadata.isMcpCaller(instance), out);
                    }
                    return;
                }
            }
            restartHeld(incus, name, instance, out);
        }
    }

    /** @param read the instance as read under the lock, or null to read it before the stop */
    private static void restartHeld(IncusClient incus, String name, JqValue read, Output out) {
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
        var instance = read != null ? read : incus.instanceMetadata(name);
        if (Long.toString(incus.pid(name)).equals(instance.path("config").path(Metadata.AGENT_RESTART_BOOT).asText())) {
            throw refusal(name, "The incus-agent in VM " + name + " is not responding, and isx"
                    + " already restarted " + name + " once to recover it; not restarting it again."
                    + VmAgentFailure.pointer("Its boot log may say why: ", VmAgentFailure.save(name, incus.consoleLog(name))));
        }

        out.header("Restarting " + name, "VM agent not responding");
        var declared = ProofToken.declaredInBackground();
        try {
            incus.stop(name, GRACEFUL_STOP_SECONDS);
        } catch (RuntimeException guestIgnoredShutdown) {
            incus.forceStop(name);
        }
        ProxyOnlyAcl.explaining(incus, name, instance, () -> {
            InstanceLifecycle.prepareForStart(incus, name, instance, out);
            InstanceLifecycle.startInstance(incus, name, out);
            return null;
        });
        // Stamped before the wait, so a boot whose agent never comes up is still recorded
        // however the wait ends. The boot's new secret (#934) in the same write: nothing in the
        // guest has it before the probe below puts it there.
        var stamps = new HashMap<String, String>();
        long restarted = incus.pid(name);
        if (restarted > 0) stamps.put(Metadata.AGENT_RESTART_BOOT, Long.toString(restarted));
        var secret = InstanceLifecycle.rotateInstanceSecret(incus, name, instance, stamps, declared.join());
        InstanceLifecycle.withCurrentProofs(incus, name, secret, current -> {
            incus.waitForReady(name, MachineType.VM, GuestHostname.afterSecret(name, instance),
                    InstanceSecret.guestEnv(current, Metadata.isMcpCaller(instance), declared.join().placeholders()));
            return null;
        });
    }

    private static IncusException refusal(String name, String why) {
        return new IncusException(why + "\n" + restartAnywayHint(name, Platform.isMacOS()));
    }

    /** The macOS host has no {@code incus} CLI (#986), so it is pointed at the TUI's restart instead. */
    public static String restartAnywayHint(String name, boolean macOS) {
        return macOS
                ? "To restart it anyway: select " + name + " in the isx TUI and press Shift+F7."
                : "To restart it anyway: incus restart --force " + name;
    }
}
