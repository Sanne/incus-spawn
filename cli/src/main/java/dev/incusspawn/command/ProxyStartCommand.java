package dev.incusspawn.command;

import dev.incusspawn.proxy.ProxyConfig;
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
            defaultValue = {"" + ProxyConfig.DEFAULT_MITM_PORT})
    int port;

    @Option(name = "health-port", description = "Health check HTTP port (default: 18080)",
            defaultValue = {"" + ProxyConfig.DEFAULT_HEALTH_PORT})
    int healthPort;

    @Option(name = "gateway-ip", description = "Incus bridge gateway IP (skips Incus API lookup)")
    String gatewayIpOption;

    @Option(name = "debug", description = "Log full API request/response details for traffic inspection",
            hasValue = false)
    boolean debug;

    @Override
    protected CommandResult doExecute() throws Exception {
        if (ProxyService.isInstalled() && !hasNonDefaultOptions()) {
            if (ProxyService.isActive()) {
                System.out.println("Proxy is already running (service-managed).");
                return CommandResult.SUCCESS;
            }
            System.out.println("Starting proxy via service manager...");
            if (ProxyService.startService()) {
                System.out.println("Proxy service started.");
                return CommandResult.SUCCESS;
            }
            System.err.println("Proxy service failed to start. Check logs with: isx proxy logs");
            return CommandResult.FAILURE;
        }

        var proxyBin = ProxyService.resolveProxyBinaryPath();
        if (proxyBin == null) {
            System.err.println("Error: could not find 'isx-proxy' binary.");
            System.err.println("Reinstall isx using your package manager, or: curl -fsSL https://isx.run | sh");
            System.err.println("Then run: isx init");
            return CommandResult.valueOf(ProxyService.EXIT_CONFIG);
        }

        var cmd = new ArrayList<String>();
        cmd.add(proxyBin);
        if (port != ProxyConfig.DEFAULT_MITM_PORT) { cmd.add("--port"); cmd.add(String.valueOf(port)); }
        if (healthPort != ProxyConfig.DEFAULT_HEALTH_PORT) { cmd.add("--health-port"); cmd.add(String.valueOf(healthPort)); }
        if (gatewayIpOption != null && !gatewayIpOption.isBlank()) {
            cmd.add("--gateway-ip"); cmd.add(gatewayIpOption);
        }
        if (debug) cmd.add("--debug");

        var pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        var process = pb.start();
        return CommandResult.valueOf(process.waitFor());
    }

    private boolean hasNonDefaultOptions() {
        return port != ProxyConfig.DEFAULT_MITM_PORT
                || healthPort != ProxyConfig.DEFAULT_HEALTH_PORT
                || (gatewayIpOption != null && !gatewayIpOption.isBlank())
                || debug;
    }
}
