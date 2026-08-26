package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.proxy.ApiTrafficLog;
import dev.incusspawn.proxy.DumpProxy;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.Platform;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@CommandDefinition(
        name = "proxy",
        description = "Manage the MITM authentication proxy",
        generateHelp = true,
        groupCommands = {
                ProxyStartCommand.class,
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
                gatewayIp = ProxyConfig.resolveGatewayIp(incus);
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
                        var drift = ProxyHealthCheck.checkVersionDrift(proxyInfo);
                        if (drift.isEmpty()) drift = ProxyHealthCheck.checkToolProxyDrift(proxyInfo);
                        if (!drift.isEmpty()) {
                            System.out.println("  \033[1;33m>>> " + drift + "\033[0m");
                        }
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
                ProxyConfig.clearBridgeDns(incus);
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
