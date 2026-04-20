package dev.incusspawn.command;

import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.proxy.MitmProxy;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Command(
        name = "proxy",
        description = "Start the MITM authentication proxy (required for non-airgapped containers)",
        mixinStandardHelpOptions = true
)
public class ProxyCommand implements Runnable {

    static Path logFile() { return RuntimeConstants.PROXY_LOG_FILE; }

    @Option(names = "--port", description = "MITM TLS proxy port (default: ${DEFAULT-VALUE})",
            defaultValue = "18443")
    int port;

    @Option(names = "--health-port", description = "Health check HTTP port (default: ${DEFAULT-VALUE})",
            defaultValue = "18080")
    int healthPort;

    @Option(names = "--logs",
            description = "Show proxy logs instead of starting the proxy. " +
                    "Follows the log file in real time (like tail -f). " +
                    "Does NOT start the proxy.",
            defaultValue = "false")
    boolean showLogs;

    @Inject
    IncusClient incus;

    @Inject
    picocli.CommandLine.IFactory factory;

    @Override
    public void run() {
        if (showLogs) {
            showProxyLogs();
            return;
        }

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

        installLogTee();

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
        System.out.println("  Log file:      " + logFile());
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
            System.err.println("If the iptables redirect rule is missing, re-run 'isx init'.");
        }
    }

    private void installLogTee() {
        try {
            Files.createDirectories(logFile().getParent());
            var fileOut = new FileOutputStream(logFile().toFile(), true);
            System.setOut(new PrintStream(new TeeOutputStream(System.out, fileOut), true));
            System.setErr(new PrintStream(new TeeOutputStream(System.err, fileOut), true));
        } catch (IOException e) {
            System.err.println("Warning: could not open log file " + logFile() + ": " + e.getMessage());
        }
    }

    private void showProxyLogs() {
        if (!Files.exists(logFile())) {
            System.err.println("No proxy log file found at " + logFile());
            System.err.println("The proxy has not been started yet, or logs have been cleared.");
            return;
        }
        try {
            var pb = new ProcessBuilder("tail", "-f", logFile().toString());
            pb.inheritIO();
            var process = pb.start();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to tail log file: " + e.getMessage());
        }
    }

    private static class TeeOutputStream extends OutputStream {
        private final OutputStream console;
        private final OutputStream file;

        TeeOutputStream(OutputStream console, OutputStream file) {
            this.console = console;
            this.file = file;
        }

        @Override
        public void write(int b) throws IOException {
            console.write(b);
            file.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            console.write(b, off, len);
            file.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            console.flush();
            file.flush();
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }
}
