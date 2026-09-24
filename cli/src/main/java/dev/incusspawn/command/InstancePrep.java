package dev.incusspawn.command;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.FirewallDetector;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.GuiPassthrough;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;

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
        if (!incus.exists(name)) {
            System.err.println("Error: no instance named '" + name + "' found.");
            System.err.println("Run 'isx list' to see available instances.");
            return null;
        }

        // Validate parent template before any side effects
        var parent = incus.configGet(name, Metadata.PARENT);
        if (parent == null || parent.isEmpty()) {
            System.err.println("Error: instance '" + name + "' has no parent template.");
            System.err.println("This does not appear to be an incus-spawn managed instance.");
            return null;
        }

        // Prefer PROFILE (always the leaf template name) for chain resolution;
        // PARENT may point to a clone when branching from another clone.
        var profile = incus.configGet(name, Metadata.PROFILE);
        var templateName = (profile != null && !profile.isEmpty()) ? profile : parent;

        var networkMode = incus.configGet(name, Metadata.NETWORK_MODE);
        boolean ipFixed = false;
        if (!NetworkMode.AIRGAP.name().equals(networkMode)) {
            if (!ProxyHealthCheck.checkOrWarn(incus)) return null;
            BridgeSubnetCheck.warnIfConflict(incus);
            FirewallDetector.warnIfNotRunning();
            ipFixed = fixStaticIpMismatch(incus, name);
            fixIpFiltering(incus, name, incus.getInstanceStatus(name));
            fixCaMismatch(incus, name);
            fixResolvConfMismatch(incus, name);
        }

        // Start if stopped, or restart VMs with unresponsive agent
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(name))) {
            System.out.println("Starting " + name + "...");
            InstanceLifecycle.prepareHostDevicesForStart(incus, name);
            incus.start(name);
            incus.waitForReady(name);
            if (ipFixed && incus.isVm(name)) {
                InstanceLifecycle.pushDeferredNetworkConfig(incus, name);
            }
        } else if (incus.isVm(name) && !incus.shellExec(name, "echo", "ready").success()) {
            System.out.println("VM agent not responding, restarting " + name + "...");
            incus.forceStop(name);
            InstanceLifecycle.prepareHostDevicesForStart(incus, name);
            incus.start(name);
            incus.waitForReady(name);
            if (ipFixed) {
                InstanceLifecycle.pushDeferredNetworkConfig(incus, name);
            }
        } else if (ipFixed && incus.isVm(name)) {
            // fixCaMismatch started the VM before the block above — still need
            // to push the .network file that couldn't be written while stopped
            InstanceLifecycle.pushDeferredNetworkConfig(incus, name);
        }

        GuiPassthrough.checkGuiHealth(incus, name);

        return templateName;
    }

    private static boolean fixStaticIpMismatch(IncusClient incus, String name) {
        if (!"Stopped".equalsIgnoreCase(incus.getInstanceStatus(name))) return false;
        try {
            if (InstanceLifecycle.fixStaticIpIfNeeded(incus, name)) {
                BuildOutput.warnBanner("Static IP mismatch",
                        "Reassigned to current bridge subnet.");
                return true;
            }
        } catch (Exception e) {
            System.err.println("Warning: could not repair static IP for " + name
                    + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * Turn on IP spoofing protection for an instance branched before it was applied.
     *
     * <p>The proxy trusts the source address to decide which credential account answers, so
     * an instance that predates this check would otherwise be able to impersonate another.
     * Quiet when already set -- this runs on every shell and run.
     */
    private static void fixIpFiltering(IncusClient incus, String name, String status) {
        // Only while stopped, mirroring fixStaticIpMismatch: NIC device changes on a live
        // instance are not reliably applied, and a warning on every shell would be noise.
        // The next stop/start picks it up.
        if (!"Stopped".equalsIgnoreCase(status)) return;
        try {
            // Read the current value from the same request that finds the NIC: this runs on
            // every shell and run, and every instance branched since the check landed already
            // has it, so writing unconditionally would mean an awaited PATCH forever.
            var nic = incus.findNic(name, "incusbr0");
            if (nic == null || "true".equals(nic.config().get("security.ipv4_filtering"))) return;
            InstanceLifecycle.applyIpFiltering(incus, name, nic.name());
        } catch (Exception ignored) {
            // No bridge NIC (airgap, or a hand-made instance) -- nothing to filter.
        }
    }

    private static void fixResolvConfMismatch(IncusClient incus, String name) {
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(name))) return;
        try {
            if (ProxyConfig.fixResolvConfIfNeeded(incus, name)) {
                BuildOutput.warnBanner("DNS configuration mismatch",
                        "Updated /etc/resolv.conf automatically.");
            }
        } catch (Exception ignored) {
        }
    }

    private static void fixCaMismatch(IncusClient incus, String container) {
        // Ensure the container is running so we can push the cert
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(container))) {
            InstanceLifecycle.prepareHostDevicesForStart(incus, container);
            incus.start(container);
            incus.waitForReady(container);
        }

        if (CertificateAuthority.fixContainerCaIfNeeded(incus, container)) {
            BuildOutput.warnBanner("CA certificate mismatch",
                    "Updated automatically.");
        }
    }
}
