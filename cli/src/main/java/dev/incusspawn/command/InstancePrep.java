package dev.incusspawn.command;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.FirewallDetector;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.StaticIpAllocator;
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
        var machineType = incus.machineType(name);
        boolean ipFixed = false;
        if (!NetworkMode.AIRGAP.name().equals(networkMode)) {
            if (!ProxyHealthCheck.checkOrWarn(incus)) return null;
            BridgeSubnetCheck.warnIfConflict(incus);
            FirewallDetector.warnIfNotRunning();
            ipFixed = fixStaticIpMismatch(incus, name, machineType);
            fixCaMismatch(incus, name, machineType);
            fixResolvConfMismatch(incus, name);
        }

        InstanceLifecycle.ensureReady(incus, name, incus.getInstanceStatus(name),
                ipFixed && machineType == MachineType.VM, machineType, System.out::println);

        GuiPassthrough.checkGuiHealth(incus, name);
        InstanceLifecycle.reconcileAccountIdentities(incus, name);

        return templateName;
    }

    private static boolean fixStaticIpMismatch(IncusClient incus, String name, MachineType machineType) {
        if (!"Stopped".equalsIgnoreCase(incus.getInstanceStatus(name))) return false;
        try {
            if (InstanceLifecycle.fixStaticIpIfNeeded(incus, name, StaticIpAllocator.Output.TERMINAL, machineType)) {
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

    private static void fixCaMismatch(IncusClient incus, String container, MachineType machineType) {
        // Ensure the container is running so we can push the cert
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(container))) {
            InstanceLifecycle.prepareHostDevicesForStart(incus, container);
            InstanceLifecycle.startInstance(incus, container);
            incus.waitForReady(container, machineType);
        }

        if (CertificateAuthority.fixContainerCaIfNeeded(incus, container)) {
            BuildOutput.warnBanner("CA certificate mismatch",
                    "Updated automatically.");
        }
    }
}
