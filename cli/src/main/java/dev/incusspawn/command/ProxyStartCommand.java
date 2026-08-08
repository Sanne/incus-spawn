package dev.incusspawn.command;

import dev.incusspawn.proxy.ProxyService;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.util.ArrayList;

@CommandDefinition(
        name = "start",
        description = "Start the MITM authentication proxy (required for non-airgapped containers)",
        generateHelp = true
)
public class ProxyStartCommand extends BaseCommand {

    @Option(name = "port", description = "MITM TLS proxy port (default: 18443)",
            defaultValue = {"18443"})
    int port;

    @Option(name = "health-port", description = "Health check HTTP port (default: 18080)",
            defaultValue = {"18080"})
    int healthPort;

    @Option(name = "gateway-ip", description = "Incus bridge gateway IP (skips Incus API lookup)")
    String gatewayIpOption;

    @Option(name = "debug", description = "Log full API request/response details for traffic inspection",
            hasValue = false)
    boolean debug;

    @Override
    protected CommandResult doExecute() throws Exception {
        var proxyBin = ProxyService.resolveProxyBinaryPath();
        if (proxyBin == null) {
            System.err.println("Error: could not find 'isx-proxy' binary.");
            System.err.println("Install it alongside 'isx' or rebuild with: mvn package -DskipTests");
            return CommandResult.valueOf(1);
        }

        var cmd = new ArrayList<String>();
        cmd.add(proxyBin);
        if (port != 18443) { cmd.add("--port"); cmd.add(String.valueOf(port)); }
        if (healthPort != 18080) { cmd.add("--health-port"); cmd.add(String.valueOf(healthPort)); }
        if (gatewayIpOption != null && !gatewayIpOption.isBlank()) {
            cmd.add("--gateway-ip"); cmd.add(gatewayIpOption);
        }
        if (debug) cmd.add("--debug");

        var pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        var process = pb.start();
        return CommandResult.valueOf(process.waitFor());
    }
}
