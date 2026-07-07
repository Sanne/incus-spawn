package dev.incusspawn.command;

import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.FirewalldCheck;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.GuiPassthrough;
import dev.incusspawn.proxy.CertificateAuthority;
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
        if (!NetworkMode.AIRGAP.name().equals(networkMode)) {
            if (!ProxyHealthCheck.checkOrWarn(incus)) return null;
            BridgeSubnetCheck.warnIfConflict(incus);
            FirewalldCheck.warnIfNotRunning();
            fixCaMismatch(incus, name);
        }

        // Start if stopped, or restart VMs with unresponsive agent
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(name))) {
            System.out.println("Starting " + name + "...");
            HostResourceSetup.removeStaleDevices(incus, name);
            incus.start(name);
            incus.waitForReady(name);
        } else if (incus.isVm(name) && !incus.shellExec(name, "echo", "ready").success()) {
            System.out.println("VM agent not responding, restarting " + name + "...");
            incus.forceStop(name);
            incus.start(name);
            incus.waitForReady(name);
        }

        GuiPassthrough.checkGuiHealth(incus, name);

        return templateName;
    }

    private static void fixCaMismatch(IncusClient incus, String container) {
        // Ensure the container is running so we can push the cert
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(container))) {
            HostResourceSetup.removeStaleDevices(incus, container);
            incus.start(container);
            incus.waitForReady(container);
        }

        if (CertificateAuthority.fixContainerCaIfNeeded(incus, container)) {
            var sep = "\033[33m" + "─".repeat(60) + "\033[0m";
            System.err.println(sep);
            System.err.println("\033[1;33mCA certificate mismatch\033[0m — updated automatically.");
            System.err.println(sep);
        }
    }
}
