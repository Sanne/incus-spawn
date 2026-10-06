package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.proxy.ApiTrafficLog;
import dev.incusspawn.proxy.DumpProxy;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.Platform;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

@CommandDefinition(
        name = "proxy",
        description = "Manage the MITM authentication proxy",
        generateHelp = true,
        groupCommands = {
                ProxyStartCommand.class,
                ProxyCommand.Stop.class,
                ProxyCommand.Restart.class,
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
            name = "status",
            description = "Check if the MITM TLS proxy is running",
            generateHelp = true
    )
    public static class Status extends BaseCommand {

        @Override
        protected CommandResult doExecute() throws Exception {
            var incus = RuntimeServices.incus();
            // On macOS a healthy proxy answers on loopback without asking Incus, so the gateway
            // is only looked up, and only fails, when the check actually needs it.
            ProxyHealthCheck.ProxyStatus status;
            String healthIp;
            try {
                status = ProxyHealthCheck.check(incus);
                healthIp = ProxyHealthCheck.healthAddress(incus);
            } catch (Exception e) {
                System.err.println("Could not determine Incus bridge gateway IP: " + e.getMessage());
                System.err.println(ProxyConfig.gatewayUnavailableHint(Platform.isMacOS()));
                return CommandResult.valueOf(1);
            }
            var serviceInstalled = ProxyService.isInstalled();
            var serviceActive = serviceInstalled && ProxyService.isActive();
            switch (status) {
                case RUNNING, WAITING_FOR_DNS -> {
                    System.out.println(status == ProxyHealthCheck.ProxyStatus.RUNNING
                            ? "Proxy is running." : "Proxy is running (waiting for DNS configuration).");
                    var proxyInfo = ProxyHealthCheck.fetchProxyInfo(healthIp);
                    if (proxyInfo != null) {
                        if (!proxyInfo.isLegacy()) {
                            System.out.println("  Version:         " + proxyInfo.version() + " (" + proxyInfo.gitSha() + ")");
                            if (proxyInfo.runtime() != null && !proxyInfo.runtime().isEmpty()) {
                                System.out.println("  Runtime:         " + proxyInfo.runtime());
                            }
                        }
                        System.out.println("  DNS overrides:   " + (proxyInfo.dnsConfigured() ? "active" : "pending"));
                        var drift = ProxyHealthCheck.assessDrift(proxyInfo);
                        for (var d : drift.drifts()) {
                            System.out.println("  " + BuildOutput.styled(BuildOutput.BOLD + BuildOutput.YELLOW, ">>> " + d));
                        }
                        if (drift.futileReason() != null) System.out.println("      " + drift.futileReason());
                    }
                    if (proxyInfo != null && proxyInfo.hasAuthError()) {
                        System.out.println("  " + BuildOutput.styled(BuildOutput.BOLD + BuildOutput.RED, ">>> Auth error: " + proxyInfo.authError()));
                    }
                    System.out.println("  Health endpoint: http://" + healthIp + ":" + ProxyConfig.DEFAULT_HEALTH_PORT + "/health");
                    System.out.println("  MITM port:       " + ProxyConfig.DEFAULT_MITM_PORT);
                    if (serviceActive) {
                        var manager = Platform.isMacOS() ? "launchd (dev.incusspawn.proxy)" : "systemd (incus-spawn-proxy.service)";
                        System.out.println("  Managed by:      " + manager);
                    } else {
                        System.out.println("  Managed by:      manual (foreground process)");
                    }
                }
                case NOT_RUNNING, STALE_DNS, STALE_GATEWAY -> {
                    return CommandResult.valueOf(reportDown(status, serviceInstalled, serviceActive, System.out));
                }
            }
            return CommandResult.SUCCESS;
        }

        /**
         * Report a proxy that instances cannot use, on {@code out} like a healthy one's: the
         * report is the command's result, and the exit code tells the states apart.
         */
        static int reportDown(ProxyHealthCheck.ProxyStatus status, boolean serviceInstalled,
                              boolean serviceActive, PrintStream out) {
            switch (status) {
                case NOT_RUNNING -> {
                    out.println("Proxy is not running.");
                    if (serviceInstalled) {
                        out.println("Service is installed but not active. Start it with: isx proxy install");
                    } else {
                        out.println("Start it with: isx proxy start");
                        out.println("Or install as a service: isx proxy install");
                    }
                    return 1;
                }
                case STALE_DNS -> {
                    out.println("Proxy is not running, but DNS overrides are still active.");
                    out.println("Start the proxy to restore connectivity: isx proxy start");
                    return 2;
                }
                case STALE_GATEWAY -> {
                    out.println("Proxy is running, but on an old address of incusbr0 that instances cannot reach.");
                    out.println("Restart it to bind the current address: "
                            + (serviceActive ? "isx proxy restart" : "isx proxy stop && isx proxy start"));
                    return 3;
                }
                default -> throw new IllegalArgumentException("not a down state: " + status);
            }
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
            name = "restart",
            description = "Restart the proxy service",
            generateHelp = true
    )
    public static class Restart extends BaseCommand {
        @Override
        protected CommandResult doExecute() throws Exception {
            if (!ProxyService.isInstalled() && !ProxyService.isActive()) {
                System.out.println("Proxy is not installed or running. Use 'isx proxy start' or 'isx proxy install'.");
                return CommandResult.SUCCESS;
            }
            return ProxyService.restart() ? CommandResult.SUCCESS : CommandResult.valueOf(1);
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
            // Before the running-service path too: after an upgrade that bumped INIT_VERSION,
            // restarting the proxy here would stop a working one for one that refuses to start.
            if (!ProxyService.initComplete()) return CommandResult.FAILURE;
            var incus = RuntimeServices.incus();
            if (ProxyService.isActive()) {
                ProxyService.upgradeIfNeeded();
                var info = ProxyHealthCheck.fetchProxyInfo(ProxyHealthCheck.healthAddress(incus));
                if (ProxyService.reinstallIfChanged(incus, info)) {
                    BuildOutput.success("Proxy service restarted with updated binary.");
                } else {
                    BuildOutput.step("Proxy service is already installed and running.");
                    // Drift it declined to restart for would otherwise go unexplained here.
                    var futile = ProxyHealthCheck.assessDrift(info).futileReason();
                    if (futile != null) BuildOutput.note(futile);
                }
                if (!ProxyHealthCheck.awaitHealthy(5)) {
                    System.err.println("Warning: proxy service is registered but not responding.");
                    System.err.println("Check logs with: isx proxy logs");
                    return CommandResult.FAILURE;
                }
                return CommandResult.SUCCESS;
            }
            if (!ProxyService.install()) {
                return CommandResult.FAILURE;
            }
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
            ProxyService.uninstall(RuntimeServices.incus());
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
                if (showFallbackLogs()) {
                    return CommandResult.SUCCESS;
                }
                System.err.println("No proxy log file found at " + logFile());
                System.err.println("The proxy has not been started yet, or logs have been cleared.");
                return CommandResult.valueOf(1);
            }

            // Show version and runtime at the beginning
            var build = BuildInfo.instance();
            String gatewayIp = "(unknown)";
            try {
                gatewayIp = ProxyConfig.resolveGatewayIp(incus);
            } catch (Exception ignored) {}

            System.out.println("Gateway IP:    " + gatewayIp);
            System.out.println("MITM port:     " + ProxyConfig.DEFAULT_MITM_PORT);
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

        private boolean showFallbackLogs() {
            String header;
            java.util.List<String> command;
            if (Platform.isMacOS()) {
                var serviceLog = Environment.proxyServiceLogFile();
                if (!Files.exists(serviceLog)) return false;
                header = "Showing launchd service log instead (" + serviceLog + "):";
                command = java.util.List.of("tail", "-f", serviceLog.toString());
            } else if (ProxyService.isInstalled()) {
                header = "Showing systemd journal instead:";
                command = java.util.List.of("journalctl", "--user", "-u",
                        Environment.PROXY_SERVICE_NAME, "--no-pager", "-n", "50", "-f");
            } else {
                return false;
            }
            System.err.println("No proxy log file at " + logFile());
            System.err.println(header);
            System.err.println();
            try {
                var pb = new ProcessBuilder(command);
                pb.inheritIO();
                pb.start().waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException e) {
                System.err.println("Failed to read service logs: " + e.getMessage());
                return false;
            }
            return true;
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

}
