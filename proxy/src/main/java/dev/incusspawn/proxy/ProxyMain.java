package dev.incusspawn.proxy;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.Platform;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.vm.VmNetwork;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.vertx.core.Vertx;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

@QuarkusMain
public class ProxyMain implements QuarkusApplication {

    @Override
    public int run(String... args) {
        int port = ProxyConfig.DEFAULT_MITM_PORT;
        int healthPort = ProxyConfig.DEFAULT_HEALTH_PORT;
        String gatewayIpOption = null;
        boolean debug = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help", "-h" -> {
                    System.out.println("Usage: isx-proxy [OPTIONS]");
                    System.out.println();
                    System.out.println("MITM authentication proxy for incus-spawn containers.");
                    System.out.println();
                    System.out.println("Options:");
                    System.out.println("  --port <port>         MITM listen port (default: " + ProxyConfig.DEFAULT_MITM_PORT + ")");
                    System.out.println("  --health-port <port>  Health check port (default: " + ProxyConfig.DEFAULT_HEALTH_PORT + ")");
                    System.out.println("  --gateway-ip <ip>     Override gateway IP detection");
                    System.out.println("  --debug               Enable API traffic debug logging");
                    System.out.println("  --version, -V         Display version info");
                    System.out.println("  --help, -h            Show this help");
                    return 0;
                }
                case "--version", "-V" -> {
                    var info = BuildInfo.instance();
                    System.out.println("isx-proxy " + info.version() + " (" + info.gitSha() + ")");
                    System.out.println(info.runtime());
                    return 0;
                }
                case "--port" -> { if (i + 1 < args.length) port = Integer.parseInt(args[++i]); }
                case "--health-port" -> { if (i + 1 < args.length) healthPort = Integer.parseInt(args[++i]); }
                case "--gateway-ip" -> { if (i + 1 < args.length) gatewayIpOption = args[++i]; }
                case "--debug" -> debug = true;
            }
        }

        var incus = new IncusClient();
        if (!Environment.hasBeenInitialized()) {
            System.err.println("Error: incus-spawn has not been initialized. Run 'isx init' first.");
            return ProxyService.EXIT_CONFIG;
        }

        var config = SpawnConfig.load();
        var claude = config.getClaude();
        var creds = ProxyCredentials.fromConfig(config);

        if (claude.isUseVertex()) {
            if (claude.getCloudMlRegion().isBlank() || claude.getVertexProjectId().isBlank()) {
                System.err.println("Error: Vertex AI enabled but region or project ID not configured. Run 'isx init' first.");
                return ProxyService.EXIT_CONFIG;
            }
        }

        String gatewayIp;
        if (gatewayIpOption != null && !gatewayIpOption.isBlank()) {
            gatewayIp = gatewayIpOption;
        } else if (Platform.isMacOS()) {
            gatewayIp = VmNetwork.discoverHostBridgeIp();
            if (gatewayIp == null) {
                System.err.println("Error: could not discover VM-facing bridge interface.");
                System.err.println("Is the VM running? Try 'isx vm status'.");
                return 1;
            }
        } else {
            try {
                gatewayIp = ProxyConfig.resolveGatewayIp(incus);
            } catch (Exception e) {
                System.err.println("Error: could not determine Incus bridge gateway IP.");
                System.err.println("Is Incus running? Try 'incus network list'.");
                return 1;
            }
        }

        installLogTee();

        var build = BuildInfo.instance();
        ProxyLog.info("Starting proxy " + build.version() + " (" + build.gitSha() + ") " + build.runtime());
        System.out.println("Starting MITM authentication proxy...");
        System.out.println("  Version:       " + build.version() + " (" + build.gitSha() + ")");
        System.out.println("  Runtime:       " + build.runtime());
        if (!Platform.isMacOS()) {
            System.out.println("  Incus:         " + build.incusClient() + " (client) / " + build.incusServer() + " (server)");
        }
        System.out.println("  Gateway IP:    " + gatewayIp);
        System.out.println("  MITM port:     " + port);
        System.out.println("  Health port:   " + healthPort);
        if (creds.useVertex()) {
            System.out.println("  Vertex AI:     " + creds.vertexRegion() +
                    " (project: " + creds.vertexProjectId() + ")");
        } else if (!creds.oauthToken().isBlank()) {
            System.out.println("  OAuth token:   configured");
        } else if (!creds.anthropicApiKey().isBlank()) {
            System.out.println("  API key:       configured");
        } else {
            System.out.println("  Claude:        (not configured)");
        }
        System.out.println("  GitHub token:  " + (creds.ghToken().isBlank() ? "(not configured)" : "configured"));
        System.out.println("  Bob API key:   " + (creds.bobApiKey().isBlank() ? "(not configured)" : "configured"));
        System.out.println("  OpenAI key:    " + (creds.openaiApiKey().isBlank() ? "(not configured)" : "configured"));
        System.out.println("  Log file:      " + Environment.proxyLogFile());
        System.out.println();

        var healthBindAddress = ProxyHealthCheck.healthAddress(incus);
        var vertx = Arc.container().instance(Vertx.class).get();
        var proxy = new MitmProxy(vertx, gatewayIp, port, healthPort, healthBindAddress, creds);

        if (debug) {
            try {
                var debugLog = new ApiTrafficLog(Environment.apiDebugDir().resolve("proxy"));
                proxy.setDebugLog(debugLog);
                System.out.println("  Debug logs:    " + debugLog.logDir());
            } catch (IOException e) {
                System.err.println("Warning: could not create debug log directory: " + e.getMessage());
            }
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nStopping proxy...");
            var forceExit = new Thread(() -> {
                try { Thread.sleep(10000); } catch (InterruptedException e) { return; }
                System.err.println("Proxy shutdown exceeded 10 seconds, forcing exit.");
                Runtime.getRuntime().halt(0);
            }, "force-exit");
            forceExit.setDaemon(true);
            forceExit.start();
            proxy.stop();
        }));

        Runnable dnsCallback;
        if (Platform.isMacOS()) {
            dnsCallback = () -> {
                try {
                    ProxyConfig.configureBridgeDns(incus);
                    ProxyLog.info("DNS overrides configured");
                } catch (Exception e) {
                    ProxyLog.info("Using install-time DNS configuration (VM API not reachable from launchd)");
                }
                proxy.setDnsConfigured(true);
            };
        } else {
            dnsCallback = () -> ProxyConfig.configureBridgeDnsWithRetry(incus, () -> proxy.setDnsConfigured(true));
        }
        try {
            proxy.start(dnsCallback);
        } catch (Exception e) {
            ProxyLog.error("Failed to start: " + e.getMessage());
            System.err.println("Is another proxy already running? Check port " + port + ".");
            System.err.println("If the iptables redirect rule is missing, re-run 'isx init'.");
            return 1;
        }
        return 0;
    }

    private static void installLogTee() {
        var logFile = Environment.proxyLogFile();
        try {
            Files.createDirectories(logFile.getParent());
            var fileOut = new FileOutputStream(logFile.toFile(), true);
            System.setOut(new PrintStream(new TeeOutputStream(System.out, fileOut), true));
            System.setErr(new PrintStream(new TeeOutputStream(System.err, fileOut), true));
        } catch (IOException e) {
            System.err.println("Warning: could not open log file " + logFile + ": " + e.getMessage());
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
