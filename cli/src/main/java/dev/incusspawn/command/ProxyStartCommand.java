package dev.incusspawn.command;

import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
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
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    @Option(name = "gateway-ip", description = "Incus bridge gateway IP, for when Incus cannot report it (Linux: the bridge address; macOS: the VM bridge)")
    String gatewayIpOption;

    @Option(name = "debug", description = "Log full API request/response details for traffic inspection",
            hasValue = false)
    boolean debug;

    @Override
    protected CommandResult doExecute() throws Exception {
        // Inside the service, this command *is* the proxy: it must launch the binary and nothing
        // else. Managing the service from here restarts the unit/job that is running this very
        // process, which the service manager then starts again (systemd) or, worse, can end this
        // process before it reloads (launchd bootout, #977). A unit/plist from an older build
        // execs `isx proxy start` directly, so this guard is what stops that on an installation
        // that predates it pointing straight at isx-proxy. It is checked last because it can fork
        // `systemctl`/`launchctl`, which the two cheap predicates often make unnecessary.
        var installedAndDefault = ProxyService.isInstalled() && !hasNonDefaultOptions();
        var supervised = installedAndDefault && ProxyService.isSupervisedInvocation();
        var serviceManaged = installedAndDefault && !supervised;

        // Answering "it is already running" needs no binary — keep that answer available to the
        // scripts that call this command idempotently, even where isx-proxy cannot be located
        // next to the isx on PATH.
        var serviceActive = serviceManaged && ProxyService.isActive();
        if (serviceActive && ProxyHealthCheck.awaitHealthy(0)) {
            // A healthy answer may come from another proxy on the ports (#1570), which
            // restarting this service cannot end.
            if (!ProxyService.answeringIsTheService()) return CommandResult.FAILURE;
            BuildOutput.step("Proxy is already running (service-managed).");
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
            // Refused here, not only by the service, so neither the wait for health nor "failed
            // to start" below follows the refusal. Already-healthy is answered above (#1048).
            if (!ProxyService.initComplete()) return CommandResult.FAILURE;
            if (serviceActive) {
                BuildOutput.step("Proxy service is registered but not responding.");
                ProxyService.restart();
                if (ProxyService.awaitStarted(ProxyService.configuredStartWaitSeconds())) {
                    return ProxyService.answeringIsTheService()
                            ? CommandResult.SUCCESS : CommandResult.FAILURE;
                }
                if (ProxyService.failedWithConfigError()) {
                    BuildOutput.error("Proxy failed due to a configuration problem (exit " + ProxyService.EXIT_CONFIG + ").",
                            ProxyService.CHECK_LOGS);
                } else {
                    BuildOutput.error("Proxy is still not responding after restart.", ProxyService.notResponding());
                }
                return CommandResult.FAILURE;
            }
            BuildOutput.stepStart("Starting proxy via service manager");
            if (ProxyService.startService()) {
                if (ProxyService.awaitStarted(ProxyService.configuredStartWaitSeconds())) {
                    if (!ProxyService.answeringIsTheService()) {
                        BuildOutput.stepBreak();
                        return CommandResult.FAILURE;
                    }
                    BuildOutput.stepDone();
                    return CommandResult.SUCCESS;
                }
                BuildOutput.stepBreak();
                BuildOutput.error("Proxy service started but is not responding.", ProxyService.notResponding());
                return CommandResult.FAILURE;
            }
            BuildOutput.stepBreak();
            BuildOutput.error("Proxy service failed to start.", ProxyService.CHECK_LOGS);
            return CommandResult.FAILURE;
        }

        // A legacy plist is never rewritten by restartLocked when this process runs supervised —
        // that would bootout the very job running it (#977) — so this is the only chance to fix it
        // before the next login finds it stale again.
        if (supervised) ProxyService.migrateMacOsPlistIfSupervised();

        var proxy = new ForegroundProxy(foregroundCommand(proxyBin), PROXY_STOP_GRACE);
        int code = proxy.run();
        // A proxy stopped because this process is exiting was asked to stop: nothing to diagnose.
        if (code != 0 && !proxy.stoppedOnShutdown()) {
            BuildOutput.warnBanner(ProxyService.describeExit(code), abnormalExitLines(code, Environment.proxyLogFile()));
        }
        return CommandResult.valueOf(code);
    }

    /**
     * The foreground proxy's command line. {@code --exit-with-pid} makes it stop once this process
     * is gone even when no shutdown hook ran, e.g. on SIGKILL (#923); the service units never pass it.
     */
    List<String> foregroundCommand(String proxyBin) {
        var cmd = new ArrayList<String>();
        cmd.add(proxyBin);
        if (port != ProxyConfig.DEFAULT_MITM_PORT) { cmd.add("--port"); cmd.add(String.valueOf(port)); }
        if (healthPort != ProxyConfig.DEFAULT_HEALTH_PORT) { cmd.add("--health-port"); cmd.add(String.valueOf(healthPort)); }
        if (gatewayIpOption != null && !gatewayIpOption.isBlank()) {
            cmd.add("--gateway-ip"); cmd.add(gatewayIpOption);
        }
        if (debug) cmd.add("--debug");
        cmd.add("--exit-with-pid"); cmd.add(String.valueOf(ProcessHandle.current().pid()));
        return cmd;
    }

    /**
     * The proxy run in the foreground, sharing this terminal. Ctrl+C signals the whole process
     * group, but a signal to this process alone (a {@code kill}, a supervisor, a cancelled CI
     * step) would leave the proxy orphaned and holding its ports (issue #882), so a shutdown hook
     * stops it: SIGTERM, then SIGKILL once {@code grace} is up. A SIGKILL to this process runs no
     * hook; for that the proxy watches this process itself ({@link #foregroundCommand}).
     */
    static final class ForegroundProxy {
        private final List<String> cmd;
        private final Duration grace;
        private Process process;   // guarded by this
        private boolean stopping;  // guarded by this

        ForegroundProxy(List<String> cmd, Duration grace) {
            this.cmd = cmd;
            this.grace = grace;
        }

        /** Start the proxy and wait for it; returns its exit status. */
        int run() throws IOException, InterruptedException {
            var hook = new Thread(this::stop, "stop-proxy");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                Process started;
                // Starting under the lock means a hook that runs meanwhile waits for the process
                // and stops it, rather than finding nothing and letting it outlive this JVM.
                synchronized (this) {
                    if (stopping) return 0;
                    started = process = new ProcessBuilder(cmd).inheritIO().start();
                }
                return started.waitFor();
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) {}
            }
        }

        /** Whether the proxy ended because this process is shutting down. */
        synchronized boolean stoppedOnShutdown() {
            return stopping;
        }

        private void stop() {
            Process started;
            synchronized (this) {
                stopping = true;
                started = process;
            }
            if (started == null) return;
            started.destroy();
            try {
                if (started.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException ignored) {}
            started.destroyForcibly();
        }
    }

    /** Longer than the proxy's own forced exit, so a clean stop always wins. */
    private static final Duration PROXY_STOP_GRACE = Duration.ofSeconds(ProxyService.STOP_FORCE_EXIT_SECONDS + 5);

    private static final int LOG_TAIL_LINES = 10;

    /**
     * The lines of the banner that explains a foreground proxy's non-zero exit, under its cause:
     * the log's last lines, then how to get a proxy that recovers. The exit status is the only
     * signal we get — {@link ProxyService#describeExit} turns it into the cause — and a SIGKILL
     * bypasses the proxy's shutdown hook, so its log just stops mid-stream; that abrupt tail is
     * itself the clue, so we surface it inline.
     */
    static String[] abnormalExitLines(int code, Path logFile) {
        var lines = new ArrayList<String>();
        lines.add("");
        lines.add("Last lines of the proxy log (" + logFile + "):");
        // The banner prints its lines as given, and a log line can quote what a request carried
        logTail(logFile).forEach(line -> lines.add(LOG_INDENT + OutputFormat.oneLine(line)));
        if (code != ProxyService.EXIT_CONFIG) {
            lines.add("");
            lines.add("Foreground proxies don't auto-recover. To survive crashes, suspend/resume,");
            lines.add("and terminal close, install it as a managed service that restarts automatically:");
            lines.add(LOG_INDENT + BuildOutput.styled(BuildOutput.BOLD, "isx proxy install"));
        }
        return lines.toArray(String[]::new);
    }

    /** How far the log's lines, and the command to run, sit under what introduces them. */
    private static final String LOG_INDENT = "  ";

    /** The last {@link #LOG_TAIL_LINES} lines of the log via {@code tail} (O(1) seek), or why there are none. */
    private static List<String> logTail(Path logFile) {
        if (!Files.exists(logFile)) return List.of("(no log file found)");
        var lines = runTail(logFile);
        return lines != null ? lines : readTailFallback(logFile);
    }

    private static List<String> runTail(Path logFile) {
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

    private static List<String> readTailFallback(Path logFile) {
        var tail = new ArrayDeque<String>(LOG_TAIL_LINES);
        try (var reader = Files.newBufferedReader(logFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == LOG_TAIL_LINES) tail.removeFirst();
                tail.addLast(line);
            }
        } catch (IOException e) {
            return List.of("(could not read log file: " + e.getMessage() + ")");
        }
        return List.copyOf(tail);
    }

    private boolean hasNonDefaultOptions() {
        return port != ProxyConfig.DEFAULT_MITM_PORT
                || healthPort != ProxyConfig.DEFAULT_HEALTH_PORT
                || (gatewayIpOption != null && !gatewayIpOption.isBlank())
                || debug;
    }
}
