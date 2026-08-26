package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.proxy.ProxyConfig;
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
        System.err.println();
        System.err.println("Foreground proxies don't auto-recover. To survive crashes, suspend/resume,");
        System.err.println("and terminal close, install it as a managed service (Restart=on-failure):");
        System.err.println("  \033[1misx proxy install\033[0m");
        System.err.println(sep);
    }

    /** Print the last {@link #LOG_TAIL_LINES} lines of the log, streaming to avoid loading it all. */
    private void printLogTail(Path logFile) {
        if (!Files.exists(logFile)) {
            System.err.println("  (no log file found)");
            return;
        }
        var tail = new ArrayDeque<String>(LOG_TAIL_LINES);
        try (var reader = Files.newBufferedReader(logFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == LOG_TAIL_LINES) tail.removeFirst();
                tail.addLast(line);
            }
        } catch (IOException e) {
            System.err.println("  (could not read log file: " + e.getMessage() + ")");
            return;
        }
        for (String line : tail) {
            System.err.println("  " + line);
        }
    }
}
