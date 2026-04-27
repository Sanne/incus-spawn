package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.proxy.ApiTrafficLog;
import dev.incusspawn.proxy.DumpProxy;
import dev.incusspawn.proxy.MitmProxy;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyService;
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
        description = "Manage the MITM authentication proxy",
        mixinStandardHelpOptions = true,
        subcommands = {
                ProxyCommand.Start.class,
                ProxyCommand.Stop.class,
                ProxyCommand.Status.class,
                ProxyCommand.Install.class,
                ProxyCommand.Uninstall.class,
                ProxyCommand.Logs.class,
                ProxyCommand.Dump.class
        }
)
public class ProxyCommand {

    static Path logFile() { return RuntimeConstants.PROXY_LOG_FILE; }

    @Command(
            name = "start",
            description = "Start the MITM authentication proxy (required for non-airgapped containers)",
            mixinStandardHelpOptions = true
    )
    public static class Start implements Runnable {

        @Option(names = "--port", description = "MITM TLS proxy port (default: ${DEFAULT-VALUE})",
                defaultValue = "18443")
        int port;

        @Option(names = "--health-port", description = "Health check HTTP port (default: ${DEFAULT-VALUE})",
                defaultValue = "18080")
        int healthPort;

        @Option(names = "--debug", description = "Log full API request/response details for traffic inspection")
        boolean debug;

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

            installLogTee();

            MitmProxy.configureBridgeDns(incus);

            var build = BuildInfo.instance();
            System.out.println("Starting MITM authentication proxy...");
            System.out.println("  Version:       " + build.version() + " (" + build.gitSha() + ")");
            System.out.println("  Runtime:       " + build.runtime());
            System.out.println("  Incus:         " + build.incusClient() + " (client) / " + build.incusServer() + " (server)");
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

            if (debug) {
                try {
                    var debugLog = new ApiTrafficLog(RuntimeConstants.API_DEBUG_DIR.resolve("proxy"));
                    proxy.setDebugLog(debugLog);
                    System.out.println("  Debug logs:    " + debugLog.logDir());
                } catch (IOException e) {
                    System.err.println("Warning: could not create debug log directory: " + e.getMessage());
                }
            }

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
    }

    @Command(
            name = "status",
            description = "Check if the MITM TLS proxy is running",
            mixinStandardHelpOptions = true
    )
    public static class Status implements Runnable {

        @Inject
        IncusClient incus;

        @Override
        public void run() {
            String gatewayIp;
            try {
                gatewayIp = MitmProxy.resolveGatewayIp(incus);
            } catch (Exception e) {
                System.err.println("Could not determine Incus bridge gateway IP.");
                System.err.println("Is Incus running? Try 'incus network list'.");
                System.exit(1);
                return;
            }

            var status = ProxyHealthCheck.check(incus);
            var serviceInstalled = ProxyService.isInstalled();
            var serviceActive = serviceInstalled && ProxyService.isActive();
            switch (status) {
                case RUNNING -> {
                    System.out.println("Proxy is running.");
                    var proxyInfo = ProxyHealthCheck.fetchProxyInfo(gatewayIp);
                    if (proxyInfo != null) {
                        if (!proxyInfo.isLegacy()) {
                            System.out.println("  Version:         " + proxyInfo.version() + " (" + proxyInfo.gitSha() + ")");
                        }
                        var drift = ProxyHealthCheck.checkVersionDrift(proxyInfo);
                        if (!drift.isEmpty()) {
                            System.out.println("  \033[1;33m>>> " + drift + "\033[0m");
                        }
                    }
                    System.out.println("  Health endpoint: http://" + gatewayIp + ":" + MitmProxy.DEFAULT_HEALTH_PORT + "/health");
                    System.out.println("  MITM port:       " + MitmProxy.DEFAULT_MITM_PORT);
                    if (serviceActive) {
                        System.out.println("  Managed by:      systemd (incus-spawn-proxy.service)");
                    } else {
                        System.out.println("  Managed by:      manual (foreground process)");
                    }
                }
                case NOT_RUNNING -> {
                    System.err.println("Proxy is not running.");
                    if (serviceInstalled) {
                        System.err.println("Service is installed but not active. Start it with: isx proxy install");
                    } else {
                        System.err.println("Start it with: isx proxy start");
                        System.err.println("Or install as a service: isx proxy install");
                    }
                    System.exit(1);
                }
                case STALE_DNS -> {
                    System.err.println("Proxy is not running, but stale DNS overrides are active.");
                    System.err.println("Clearing stale DNS overrides...");
                    ProxyHealthCheck.clearStaleDns(incus);
                    System.err.println("Start the proxy with: isx proxy start");
                    System.exit(2);
                }
            }
        }
    }

    @Command(
            name = "stop",
            description = "Stop the proxy (handles both systemd service and manual processes)",
            mixinStandardHelpOptions = true
    )
    public static class Stop implements Runnable {

        @Inject
        IncusClient incus;

        @Override
        public void run() {
            ProxyService.stop();
            MitmProxy.clearBridgeDns(incus);
        }
    }

    @Command(
            name = "install",
            description = "Install the proxy as a systemd user service (auto-starts on boot)",
            mixinStandardHelpOptions = true
    )
    public static class Install implements Runnable {

        @Override
        public void run() {
            if (ProxyService.isActive()) {
                ProxyService.upgradeIfNeeded();
                System.out.println("Proxy service is already installed and running.");
                return;
            }
            ProxyService.install();
        }
    }

    @Command(
            name = "uninstall",
            description = "Stop and remove the systemd proxy service",
            mixinStandardHelpOptions = true
    )
    public static class Uninstall implements Runnable {

        @Inject
        IncusClient incus;

        @Override
        public void run() {
            if (ProxyService.uninstall()) {
                MitmProxy.clearBridgeDns(incus);
            }
        }
    }

    @Command(
            name = "logs",
            description = "Follow the proxy log file in real time (like tail -f)",
            mixinStandardHelpOptions = true
    )
    public static class Logs implements Runnable {

        @Override
        public void run() {
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
    }

    @Command(
            name = "dump",
            description = "Run a local pass-through proxy to capture host-side API traffic for debugging",
            mixinStandardHelpOptions = true
    )
    public static class Dump implements Runnable {

        @Option(names = "--port", description = "Local HTTP port (default: ${DEFAULT-VALUE})",
                defaultValue = "19080")
        int port;

        @Override
        public void run() {
            try {
                var debugLog = new ApiTrafficLog(RuntimeConstants.API_DEBUG_DIR.resolve("host"));
                var proxy = new DumpProxy(port, debugLog);
                proxy.start();
            } catch (IOException e) {
                System.err.println("Failed to start dump proxy: " + e.getMessage());
            }
        }
    }

    static class TeeOutputStream extends OutputStream {
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
