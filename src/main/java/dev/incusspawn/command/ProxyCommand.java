package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.proxy.ApiTrafficLog;
import dev.incusspawn.proxy.DumpProxy;
import dev.incusspawn.proxy.MitmProxy;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.vm.VmNetwork;
import io.quarkus.arc.Arc;
import io.vertx.core.Vertx;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

@CommandDefinition(
        name = "proxy",
        description = "Manage the MITM authentication proxy",
        generateHelp = true,
        groupCommands = {
                ProxyCommand.Start.class,
                ProxyCommand.Stop.class,
                ProxyCommand.Status.class,
                ProxyCommand.Install.class,
                ProxyCommand.Uninstall.class,
                ProxyCommand.Logs.class,
                ProxyCommand.Dump.class
        }
)
public class ProxyCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() throws Exception {
        System.out.println(commandInvocation.getHelpInfo());
        return CommandResult.SUCCESS;
    }

    static Path logFile() { return Environment.proxyLogFile(); }

    @CommandDefinition(
            name = "start",
            description = "Start the MITM authentication proxy (required for non-airgapped containers)",
            generateHelp = true
    )
    public static class Start extends BaseCommand {

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
            var incus = RuntimeServices.incus();
            if (!InitCommand.requireInit()) return CommandResult.valueOf(1);
            var config = dev.incusspawn.config.SpawnConfig.load();
            var claude = config.getClaude();
            var apiKey = claude.getApiKey();
            var ghToken = config.getGithub().getToken();

            if (apiKey.isBlank() && !claude.isUseVertex()) {
                System.err.println("Error: no Claude API key configured. Run 'isx init' first.");
                return CommandResult.valueOf(1);
            }

            if (claude.isUseVertex()) {
                if (claude.getCloudMlRegion().isBlank() || claude.getVertexProjectId().isBlank()) {
                    System.err.println("Error: Vertex AI enabled but region or project ID not configured. Run 'isx init' first.");
                    return CommandResult.valueOf(1);
                }
            }

            String gatewayIp;
            if (gatewayIpOption != null && !gatewayIpOption.isBlank()) {
                gatewayIp = gatewayIpOption;
            } else if (Environment.isMacOS()) {
                gatewayIp = VmNetwork.discoverHostBridgeIp();
                if (gatewayIp == null) {
                    System.err.println("Error: could not discover VM-facing bridge interface.");
                    System.err.println("Is the VM running? Try 'isx vm status'.");
                    return CommandResult.valueOf(1);
                }
            } else {
                try {
                    gatewayIp = MitmProxy.resolveGatewayIp(incus);
                } catch (Exception e) {
                    System.err.println("Error: could not determine Incus bridge gateway IP.");
                    System.err.println("Is Incus running? Try 'incus network list'.");
                    return CommandResult.valueOf(1);
                }
            }

            installLogTee();

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

            var healthBindAddress = ProxyHealthCheck.healthAddress(incus);
            var vertx = Arc.container().instance(Vertx.class).get();
            var proxy = new MitmProxy(vertx, gatewayIp, port, healthPort, healthBindAddress, apiKey, ghToken,
                    claude.isUseVertex(), claude.getCloudMlRegion(), claude.getVertexProjectId());

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
                proxy.stop();
            }));

            try {
                proxy.start(() -> MitmProxy.configureBridgeDnsWithRetry(incus));
            } catch (Exception e) {
                System.err.println("Failed to start proxy: " + e.getMessage());
                System.err.println("Is another proxy already running? Check port " + port + ".");
                System.err.println("If the iptables redirect rule is missing, re-run 'isx init'.");
            }
            return CommandResult.SUCCESS;
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

    @CommandDefinition(
            name = "status",
            description = "Check if the MITM TLS proxy is running",
            generateHelp = true
    )
    public static class Status extends BaseCommand {

        @Override
        protected CommandResult doExecute() throws Exception {
            var incus = RuntimeServices.incus();
            String gatewayIp;
            try {
                gatewayIp = MitmProxy.resolveGatewayIp(incus);
            } catch (Exception e) {
                System.err.println("Could not determine Incus bridge gateway IP.");
                System.err.println("Is Incus running? Try 'incus network list'.");
                return CommandResult.valueOf(1);
            }

            var status = ProxyHealthCheck.check(incus);
            var serviceInstalled = ProxyService.isInstalled();
            var serviceActive = serviceInstalled && ProxyService.isActive();
            var healthIp = ProxyHealthCheck.healthAddress(incus);
            switch (status) {
                case RUNNING -> {
                    System.out.println("Proxy is running.");
                    var proxyInfo = ProxyHealthCheck.fetchProxyInfo(healthIp);
                    if (proxyInfo != null) {
                        if (!proxyInfo.isLegacy()) {
                            System.out.println("  Version:         " + proxyInfo.version() + " (" + proxyInfo.gitSha() + ")");
                            if (proxyInfo.runtime() != null && !proxyInfo.runtime().isEmpty()) {
                                System.out.println("  Runtime:         " + proxyInfo.runtime());
                            }
                        }
                        var drift = ProxyHealthCheck.checkVersionDrift(proxyInfo);
                        if (!drift.isEmpty()) {
                            System.out.println("  \033[1;33m>>> " + drift + "\033[0m");
                        }
                    }
                    System.out.println("  Health endpoint: http://" + healthIp + ":" + MitmProxy.DEFAULT_HEALTH_PORT + "/health");
                    System.out.println("  MITM port:       " + MitmProxy.DEFAULT_MITM_PORT);
                    if (serviceActive) {
                        var manager = Environment.isMacOS() ? "launchd (dev.incusspawn.proxy)" : "systemd (incus-spawn-proxy.service)";
                        System.out.println("  Managed by:      " + manager);
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
                    return CommandResult.valueOf(1);
                }
                case STALE_DNS -> {
                    System.err.println("Proxy is not running, but DNS overrides are still active.");
                    System.err.println("Start the proxy to restore connectivity: isx proxy start");
                    return CommandResult.valueOf(2);
                }
            }
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "stop",
            description = "Stop the proxy (handles both systemd service and manual processes)",
            generateHelp = true
    )
    public static class Stop extends BaseCommand {

        @Override
        protected CommandResult doExecute() throws Exception {
            ProxyService.stop();
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "install",
            description = "Install the proxy as a systemd user service (auto-starts on boot)",
            generateHelp = true
    )
    public static class Install extends BaseCommand {

        @Override
        protected CommandResult doExecute() throws Exception {
            var incus = RuntimeServices.incus();
            if (ProxyService.isActive()) {
                ProxyService.upgradeIfNeeded();
                if (ProxyService.reinstallIfChanged(incus)) {
                    System.out.println("Proxy service restarted with updated binary.");
                } else {
                    System.out.println("Proxy service is already installed and running.");
                }
                return CommandResult.SUCCESS;
            }
            ProxyService.install();
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "uninstall",
            description = "Stop and remove the systemd proxy service",
            generateHelp = true
    )
    public static class Uninstall extends BaseCommand {

        @Override
        protected CommandResult doExecute() throws Exception {
            var incus = RuntimeServices.incus();
            if (ProxyService.uninstall()) {
                MitmProxy.clearBridgeDns(incus);
            }
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "logs",
            description = "Follow the proxy log file in real time (like tail -f)",
            generateHelp = true
    )
    public static class Logs extends BaseCommand {

        @Override
        protected CommandResult doExecute() throws Exception {
            var incus = RuntimeServices.incus();
            if (!Files.exists(logFile())) {
                System.err.println("No proxy log file found at " + logFile());
                System.err.println("The proxy has not been started yet, or logs have been cleared.");
                return CommandResult.valueOf(1);
            }

            // Show version and runtime at the beginning
            var build = BuildInfo.instance();
            String gatewayIp = "(unknown)";
            try {
                gatewayIp = MitmProxy.resolveGatewayIp(incus);
            } catch (Exception ignored) {}

            System.out.println("Gateway IP:    " + gatewayIp);
            System.out.println("MITM port:     " + MitmProxy.DEFAULT_MITM_PORT);
            System.out.println("Version:       " + build.version() + " (" + build.gitSha() + ")");
            System.out.println("Runtime:       " + build.runtime());
            System.out.println();

            try {
                var pb = new ProcessBuilder("tail", "-f", logFile().toString());
                pb.inheritIO();
                var process = pb.start();
                process.waitFor();
            } catch (IOException | InterruptedException e) {
                System.err.println("Failed to tail log file: " + e.getMessage());
            }
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "dump",
            description = "Run a local pass-through proxy to capture host-side API traffic for debugging",
            generateHelp = true
    )
    public static class Dump extends BaseCommand {

        @Option(name = "port", description = "Local HTTP port (default: 19080)",
                defaultValue = {"19080"})
        int port;

        @Override
        protected CommandResult doExecute() throws Exception {
            try {
                var debugLog = new ApiTrafficLog(Environment.apiDebugDir().resolve("host"));
                var proxy = new DumpProxy(port, debugLog);
                proxy.start();
            } catch (IOException e) {
                System.err.println("Failed to start dump proxy: " + e.getMessage());
                return CommandResult.valueOf(1);
            }
            return CommandResult.SUCCESS;
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
