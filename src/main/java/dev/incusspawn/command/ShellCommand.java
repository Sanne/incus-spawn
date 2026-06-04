package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.FirewalldCheck;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.GuiPassthrough;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.config.HostResourceSetup;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;

@CommandDefinition(
        name = "shell",
        description = "Open a shell in an existing clone",
        generateHelp = true
)
public class ShellCommand extends BaseCommand {

    @Argument(description = "Name of the clone to connect to", required = true)
    String name;

    @Override
    protected CommandResult doExecute() throws Exception {
        var incus = RuntimeServices.incus();

        if (!incus.exists(name)) {
            System.err.println("Error: no instance named '" + name + "' found.");
            System.err.println("Run 'incus-spawn list' to see available environments.");
            return CommandResult.valueOf(1);
        }

        var networkMode = incus.configGet(name, Metadata.NETWORK_MODE);
        if (!NetworkMode.AIRGAP.name().equals(networkMode)) {
            if (!ProxyHealthCheck.checkOrWarn(incus)) return CommandResult.valueOf(1);
            BridgeSubnetCheck.warnIfConflict(incus);
            FirewalldCheck.warnIfNotRunning();
            fixCaMismatch(incus, name);
        }

        // Start if stopped
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(name))) {
            System.out.println("Starting " + name + "...");
            HostResourceSetup.removeStaleDevices(incus, name);
            incus.start(name);
            incus.waitForReady(name);
        }

        GuiPassthrough.checkGuiHealth(incus, name);

        System.out.println("Connecting to " + name + "...\n");
        incus.interactiveShell(name, "agentuser");
        return CommandResult.SUCCESS;
    }

    private void fixCaMismatch(dev.incusspawn.incus.IncusClient incus, String container) {
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
