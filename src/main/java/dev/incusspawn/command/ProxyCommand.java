package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.proxy.MitmProxy;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "proxy",
        description = "Start the MITM authentication proxy (required for non-airgapped containers)",
        mixinStandardHelpOptions = true
)
public class ProxyCommand implements Runnable {

    @Option(names = "--port", description = "MITM TLS proxy port (default: ${DEFAULT-VALUE})",
            defaultValue = "443")
    int port;

    @Option(names = "--health-port", description = "Health check HTTP port (default: ${DEFAULT-VALUE})",
            defaultValue = "18080")
    int healthPort;

    @Inject
    IncusClient incus;

    @Inject
    picocli.CommandLine.IFactory factory;

    @Override
    public void run() {
        if (!InitCommand.requireInit(factory)) return;
        var config = dev.incusspawn.config.SpawnConfig.load();
        var claude = config.getClaude();
        var apiKey = claude.getApiKey();
        var ghToken = config.getGithub().getToken();

        if (apiKey.isBlank() && !claude.isUseVertex()) {
            System.err.println("Error: no Claude API key configured. Run 'isx init' first.");
            return;
        }

        if (claude.isUseVertex()) {
            if (claude.getCloudMlRegion().isBlank() || claude.getVertexProjectId().isBlank()) {
                System.err.println("Error: Vertex AI enabled but region or project ID not configured. Run 'isx init' first.");
                return;
            }
        }

        String gatewayIp;
        try {
            gatewayIp = MitmProxy.resolveGatewayIp(incus);
        } catch (Exception e) {
            System.err.println("Error: could not determine Incus bridge gateway IP.");
            System.err.println("Is Incus running? Try 'incus network list'.");
            return;
        }

        MitmProxy.configureBridgeDns(incus);

        System.out.println("Starting MITM authentication proxy...");
        System.out.println("  Gateway IP:    " + gatewayIp);
        System.out.println("  MITM port:     " + port);
        System.out.println("  Health port:   " + healthPort);
        if (claude.isUseVertex()) {
            System.out.println("  Vertex AI:     " + claude.getCloudMlRegion() +
                    " (project: " + claude.getVertexProjectId() + ")");
        } else {
            System.out.println("  API key:       " + (apiKey.isBlank() ? "(not configured)" : "configured"));
        }
        System.out.println("  GitHub token:  " + (ghToken.isBlank() ? "(not configured)" : "configured"));
        System.out.println();

        var proxy = new MitmProxy(gatewayIp, port, healthPort, apiKey, ghToken,
                claude.isUseVertex(), claude.getCloudMlRegion(), claude.getVertexProjectId());

        // Handle Ctrl+C gracefully
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nStopping proxy...");
            MitmProxy.clearBridgeDns(incus);
            proxy.stop();
        }));

        try {
            proxy.start();
        } catch (Exception e) {
            System.err.println("Failed to start proxy: " + e.getMessage());
            System.err.println("Is another proxy already running? Check port " + port + ".");
            System.err.println("If port 443 requires elevated privileges, run 'isx init' to configure sysctl.");
        }
    }
}
