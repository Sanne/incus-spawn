package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyService;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
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
        // Inside the service, this command *is* the proxy: it must launch the binary and nothing
        // else. Managing the service from here restarts the unit that is running this very
        // process, which systemd then starts again. Units written by older builds exec
        // `isx proxy start`, so this guard is what stops the loop on an installation that
        // predates the unit pointing straight at isx-proxy. It is checked last because it can
        // fork `systemctl`, which the two cheap predicates often make unnecessary.
        var serviceManaged = ProxyService.isInstalled() && !hasNonDefaultOptions()
                && !ProxyService.isSupervisedInvocation();

        // Answering "it is already running" needs no binary — keep that answer available to the
        // scripts that call this command idempotently, even where isx-proxy cannot be located
        // next to the isx on PATH.
        var serviceActive = serviceManaged && ProxyService.isActive();
        if (serviceActive && ProxyHealthCheck.awaitHealthy(0)) {
            System.out.println("Proxy is already running (service-managed).");
            return CommandResult.SUCCESS;
        }

        // Before anything that starts or restarts the service: restarting a service whose binary
        // does not exist cannot help, and on macOS — where launchd's KeepAlive has no
        // per-exit-code brake — that restart is itself the crash loop from issue #701.
        var proxyBin = ProxyService.resolveProxyBinaryPath();
        if (proxyBin == null) {
            ProxyService.printMissingProxyBinary();
            // On Linux the exit code is enough: RestartPreventExitStatus halts the unit. launchd
            // has no equivalent and would respawn this every ThrottleInterval, so on macOS the
            // job has to be taken out of service explicitly.
            ProxyService.haltUnusableMacOsService();
            return CommandResult.valueOf(ProxyService.EXIT_CONFIG);
        }

        if (serviceManaged) {
            if (serviceActive) {
                System.err.println("Proxy service is registered but not responding. Restarting...");
                ProxyService.restart(System.err::println);
                if (ProxyHealthCheck.awaitHealthy(10)) {
                    return CommandResult.SUCCESS;
                }
                if (ProxyService.failedWithConfigError()) {
                    System.err.println("Proxy failed due to a configuration problem (exit " + ProxyService.EXIT_CONFIG + ").");
                } else {
                    System.err.println("Proxy is still not responding after restart.");
                }
                System.err.println("Check logs with: isx proxy logs");
                return CommandResult.FAILURE;
            }
            System.out.println("Starting proxy via service manager...");
            if (ProxyService.startService()) {
                if (ProxyHealthCheck.awaitHealthy(5)) {
                    System.out.println("Proxy service started.");
                    return CommandResult.SUCCESS;
                }
                System.err.println("Proxy service started but is not responding.");
                System.err.println("Check logs with: isx proxy logs");
                return CommandResult.FAILURE;
            }
            System.err.println("Proxy service failed to start. Check logs with: isx proxy logs");
            return CommandResult.FAILURE;
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
        int code = process.waitFor();
        if (code != 0) {
            explainAbnormalExit(code);
        }
        return CommandResult.valueOf(code);
    }

    private static final int LOG_TAIL_LINES = 10;

    /**
     * When the foreground proxy exits non-zero, print a diagnosis. The exit status is the only
     * signal we get — {@link ProxyService#describeExit} turns it into a cause — and a SIGKILL
     * bypasses the proxy's shutdown hook, so its log just stops mid-stream; that abrupt tail is
     * itself the clue, so we surface it inline.
     */
    private void explainAbnormalExit(int code) {
        var sep = "\033[33m" + "─".repeat(60) + "\033[0m";
        var logFile = Environment.proxyLogFile();
        System.err.println();
        System.err.println(sep);
        System.err.println("\033[1m" + ProxyService.describeExit(code) + "\033[0m");
        System.err.println();
        System.err.println("Last lines of the proxy log (" + logFile + "):");
        printLogTail(logFile);
        if (code != ProxyService.EXIT_CONFIG) {
            System.err.println();
            System.err.println("Foreground proxies don't auto-recover. To survive crashes, suspend/resume,");
            System.err.println("and terminal close, install it as a managed service that restarts automatically:");
            System.err.println("  \033[1misx proxy install\033[0m");
        }
        System.err.println(sep);
    }

    /** Print the last {@link #LOG_TAIL_LINES} lines of the log via {@code tail} (O(1) seek). */
    private void printLogTail(Path logFile) {
        if (!Files.exists(logFile)) {
            System.err.println("  (no log file found)");
            return;
        }
        var lines = runTail(logFile);
        if (lines == null) {
            lines = readTailFallback(logFile);
        }
        if (lines == null) return;
        for (String line : lines) {
            System.err.println("  " + line);
        }
    }

    private static java.util.List<String> runTail(Path logFile) {
        try {
            var pb = new ProcessBuilder("tail", "-n", String.valueOf(LOG_TAIL_LINES), logFile.toString());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() == 0 && !output.isBlank()) {
                return output.lines().toList();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static java.util.List<String> readTailFallback(Path logFile) {
        var tail = new ArrayDeque<String>(LOG_TAIL_LINES);
        try (var reader = Files.newBufferedReader(logFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == LOG_TAIL_LINES) tail.removeFirst();
                tail.addLast(line);
            }
        } catch (IOException e) {
            System.err.println("  (could not read log file: " + e.getMessage() + ")");
            return null;
        }
        return java.util.List.copyOf(tail);
    }

    private boolean hasNonDefaultOptions() {
        return port != ProxyConfig.DEFAULT_MITM_PORT
                || healthPort != ProxyConfig.DEFAULT_HEALTH_PORT
                || (gatewayIpOption != null && !gatewayIpOption.isBlank())
                || debug;
    }
}
