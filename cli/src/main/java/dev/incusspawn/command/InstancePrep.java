package dev.incusspawn.command;

import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.Platform;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Output;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.FirewallDetector;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.GuiPassthrough;
import dev.incusspawn.lifecycle.HostIntegration;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.lifecycle.InstanceNetwork;
import dev.incusspawn.lifecycle.ZmxSocketForward;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;

import java.util.function.BiConsumer;

/**
 * Shared instance preparation logic for shell and run commands.
 * Handles validation, network checks, instance startup, and GUI passthrough.
 */
public class InstancePrep {

    /**
     * Prepare an instance for use: validate it exists, check proxy/network,
     * start if stopped, and verify GUI health.
     *
     * @param incus the Incus client
     * @param name the instance name
     * @return the parent template name, or null if validation fails
     */
    public static String prepareInstance(IncusClient incus, String name) {
        String templateName;
        try {
            templateName = prepare(incus, name, BuildOutput.bound());
        } catch (Refused e) {
            if (!e.reported()) reportRefusal(e.getMessage());
            return null;
        }
        GuiPassthrough.checkGuiHealth(incus, name, BuildOutput.bound());
        return templateName;
    }

    /** A refusal's first line as the error, and the lines under it as its advice. */
    static void reportRefusal(String message) {
        var lines = message.lines().toList();
        if (lines.isEmpty()) {
            BuildOutput.error("the instance could not be prepared.");
            return;
        }
        BuildOutput.error(lines.getFirst(), lines.subList(1, lines.size()).toArray(String[]::new));
    }

    /** Why {@link #prepare} refused, as lines for the user; {@code reported} when already printed. */
    public static final class Refused extends RuntimeException {
        private final boolean reported;

        Refused(String message, boolean reported) {
            super(message);
            this.reported = reported;
        }

        public boolean reported() {
            return reported;
        }
    }

    /**
     * {@link #prepareInstance} without the GUI check, for a caller with no terminal ({@code isx
     * mcp}'s {@code start_instance}): refuses by throwing {@link Refused} rather than printing and
     * returning null. {@code out} gets the repairs' (which may start the instance to fix its CA),
     * {@link InstanceLifecycle#ensureReady}'s and the reconcile's progress and warnings; a repair
     * made is a banner, as {@code isx shell} prints it.
     *
     * @return the leaf template the instance descends from
     */
    public static String prepare(IncusClient incus, String name, Output out) {
        if (!incus.exists(name)) {
            throw new Refused("no instance named '" + name + "' found.\n"
                    + "Run 'isx list' to see available instances.", false);
        }

        // Validate parent template before any side effects
        var parent = incus.configGet(name, Metadata.PARENT);
        if (parent == null || parent.isEmpty()) {
            // macOS has no incus client on the host: its Incus runs in isx's VM
            throw new Refused("'" + name + "' does not record a parent template, so isx cannot prepare it."
                    + (Platform.isMacOS() ? "" : "\n'incus shell " + name + "' opens a shell in it without isx's preparation."), false);
        }

        // Prefer PROFILE (always the leaf template name) for chain resolution;
        // PARENT may point to a clone when branching from another clone.
        var profile = incus.configGet(name, Metadata.PROFILE);
        var templateName = (profile != null && !profile.isEmpty()) ? profile : parent;

        var networkMode = incus.configGet(name, Metadata.NETWORK_MODE);
        var machineType = incus.machineType(name);
        if (!NetworkMode.AIRGAP.name().equals(networkMode)) {
            if (!ProxyHealthCheck.checkOrWarn(incus)) {
                // checkOrWarn printed why; the reason may be DNS or a stale gateway as well as a stop.
                throw new Refused("the isx proxy is not ready to serve this instance; 'isx doctor' says why.", true);
            }
            BridgeSubnetCheck.warnIfConflict(incus);
            FirewallDetector.warnIfNotRunning();
            repairBeforeUse(incus, name, machineType, new Notices(out, out::warnBanner));
        }

        // Read after the repair above, so a VM it just reassigned is seen to owe its file
        var instance = incus.instanceMetadata(name);
        readyForUse(incus, name, instance, machineType, out);
        return templateName;
    }

    /**
     * The last step before a shell or a command, once the terminal is the caller's to print on:
     * start the instance if it is still stopped, bring its credential identities in line with its
     * account pins, and recreate the host's zmx symlink to its session socket. The TUI calls this
     * after releasing the terminal, as {@code isx shell} does at the end of {@link #prepare}.
     *
     * <p>The reconcile is what makes an account changed on a stopped instance take effect on its
     * next use ({@link InstanceLifecycle#reconcileAccountIdentities}); the TUI's shell used to
     * skip it, and then pushed as the new account and committed as the old one (#907).
     *
     * @param instance the instance as read after the repairs before it
     * @param out      the start's and the reconcile's progress and warnings
     */
    public static void readyForUse(IncusClient incus, String name, JqValue instance, MachineType machineType,
                                   Output out) {
        InstanceLifecycle.ensureReady(incus, name, instance, machineType, out);
        // From the same read: what ensureReady writes is none of what the reconcile looks at
        InstanceLifecycle.reconcileAccountIdentities(incus, name, instance, out);
        ZmxSocketForward.ensureSymlink(name, out);
        // The host halves of its tools (#1720): nothing to run once done, read from this instance
        HostIntegration.startedAll(incus, name, instance, out);
    }

    /**
     * Where {@link #repairBeforeUse} reports, so each front end can keep its own output: the
     * repairs' and the start's progress and warnings, and each repair made.
     */
    public record Notices(Output output, BiConsumer<String, String> repaired) {}

    /**
     * The repairs an instance may owe before it is used, as {@code isx shell} and the TUI's shell
     * both make them (#907): a static IP from an old bridge, a stale CA (which starts a stopped
     * instance to push the new one, through {@link InstanceLifecycle#startForUse}, so the start
     * re-arms IP spoofing protection and falls back where the host cannot enforce it), then
     * resolv.conf. The TUI runs this while it still owns the terminal, so it passes
     * {@link Notices} that print nothing.
     */
    public static void repairBeforeUse(IncusClient incus, String name, MachineType machineType, Notices out) {
        fixStaticIpMismatch(incus, name, machineType, out);
        fixCaMismatch(incus, name, machineType, out);
        fixResolvConfMismatch(incus, name, out);
    }

    private static void fixStaticIpMismatch(IncusClient incus, String name, MachineType machineType, Notices out) {
        if (!"Stopped".equalsIgnoreCase(incus.getInstanceStatus(name))) return;
        try {
            if (InstanceNetwork.fixStaticIpIfNeeded(incus, name, out.output(), machineType)) {
                out.repaired().accept("Static IP mismatch", "Reassigned to current bridge subnet.");
            }
        } catch (Exception e) {
            out.output().warn("could not repair static IP for " + name + ": " + e.getMessage());
        }
    }

    private static void fixResolvConfMismatch(IncusClient incus, String name, Notices out) {
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(name))) return;
        try {
            if (ProxyConfig.fixResolvConfIfNeeded(incus, name)) {
                out.repaired().accept("DNS configuration mismatch", "Updated /etc/resolv.conf automatically.");
            }
        } catch (Exception ignored) {
        }
    }

    private static void fixCaMismatch(IncusClient incus, String container, MachineType machineType, Notices out) {
        // Ensure the container is running so we can push the cert
        JqValue started = null;
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(container))) {
            // A header, as ensureReady's start has: the start's own steps go under it
            out.output().header("Starting " + container, null);
            started = InstanceLifecycle.startForUse(incus, container, machineType, out.output());
        }

        // The start's own read of the instance, when there was one: no need to read it again
        if (CertificateAuthority.fixContainerCaIfNeeded(incus, container, started, out.output()::warn)) {
            out.repaired().accept("CA certificate mismatch", "Updated automatically.");
        }
    }
}
