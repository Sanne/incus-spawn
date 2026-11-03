package dev.incusspawn.proxy;

import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.Environment;
import dev.incusspawn.incus.Container;
import dev.incusspawn.config.ReadyTimeoutsConfig;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.Platform;
import dev.incusspawn.util.HostLock;
import dev.incusspawn.util.HostPath;
import dev.incusspawn.util.Output;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

public final class ProxyService {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern JAVA_WRAPPER = Pattern.compile("exec\\s+\"?([^\"\\s]+)\"?\\s+.*-jar");
    private static final Pattern JAVA_VERSION = Pattern.compile("\"(\\d+)(?:\\.(\\d+))?");
    private static final Pattern JBANG_WRAPPER = Pattern.compile("exec\\s+\"?([^\"\\s]*jbang)\"?\\s+run\\b");
    private static final Pattern EXEC_START = Pattern.compile("ExecStart=(.*)");

    private static final String SERVICE_NAME = Environment.PROXY_SERVICE_NAME;

    /**
     * Exit code for a fatal misconfiguration — a condition no amount of retrying can fix,
     * because it needs a human to change something (run init, fill in a config field).
     * {@code isx proxy start} returns it; the systemd unit below names it in
     * {@code RestartPreventExitStatus} so such a failure stops immediately instead of
     * crash-looping with the reason buried in the journal. Transient failures — Incus or the VM
     * not up yet — must keep returning 1 so the restart loop can do its job.
     * <p>
     * Value is {@code EX_CONFIG} from sysexits.h; it does not collide with the 1 and 2 returned
     * by other {@code isx proxy} subcommands.
     */
    public static final int EXIT_CONFIG = 78;

    /** The unit directive that makes {@link #EXIT_CONFIG} non-restartable. */
    static final String RESTART_PREVENT_LINE = "RestartPreventExitStatus=" + EXIT_CONFIG;

    /**
     * Human-readable interpretation of a proxy process exit status: what stopped it and, where the
     * cause is actionable, what to do about it. Lives here so the meaning of an exit code is defined
     * once — beside {@link #EXIT_CONFIG} and {@link #failedWithConfigError()} — rather than
     * re-derived by every caller. On Unix a signal-terminated child is reported as
     * {@code 128 + signum}.
     */
    public static String describeExit(int code) {
        if (code > 128) {
            int sig = code - 128;
            return switch (sig) {
                case 9 -> "Killed by SIGKILL — likely " + (Platform.isMacOS()
                        ? "memory pressure (jetsam) or an external 'kill -9'.\n"
                        + "This bypasses the proxy's clean-shutdown path, so its log ends abruptly.\n"
                        + "Check Console.app or the system log for memory kills around then:\n"
                        + "  log show --predicate 'eventMessage contains \"jetsam\"' --style compact --last 1h"
                        : "the OOM killer or an external 'kill -9'.\n"
                        + "This bypasses the proxy's clean-shutdown path, so its log ends abruptly.\n"
                        + "Check the kernel log for an OOM kill around then:\n"
                        + "  journalctl -k -e | grep -i 'killed process\\|oom'");
                case 15 -> "Terminated by SIGTERM — asked to stop by the system or another process.";
                case 2 -> "Interrupted by SIGINT (Ctrl-C).";
                case 1 -> "Killed by SIGHUP — the controlling terminal/session went away.";
                default -> "Killed by signal " + sig + ".";
            };
        }
        if (code == EXIT_CONFIG) {
            return "Exited due to a configuration problem (exit " + EXIT_CONFIG + ").\n"
                    + "Run 'isx init' to fix the configuration, then start it again.";
        }
        return "Exited unexpectedly (exit " + code + ").";
    }

    private ProxyService() {}

    // --- Lifecycle lock ---

    private static HostLock acquireProxyLock() {
        return HostLock.acquire(Environment.configDir().resolve("proxy.lock"),
                "managing the proxy", ProxyLog::info);
    }

    /** The systemd unit written by {@link #install()}. Package-private so tests can assert on it. */
    static String serviceUnitContent() {
        return """
                [Unit]
                Description=incus-spawn MITM authentication proxy
                After=incus.service

                [Service]
                Type=simple
                %s
                Restart=on-failure
                %s
                RestartSec=5

                [Install]
                WantedBy=default.target
                """.formatted(execStartLine(), RESTART_PREVENT_LINE);
    }

    public static boolean isInstalled() {
        if (Platform.isMacOS()) return isMacOsServiceInstalled();
        return Files.exists(Environment.proxyServiceFile());
    }

    /** launchd's pause between two starts of the proxy job: the plist's {@code ThrottleInterval}. */
    static final int LAUNCHD_THROTTLE_SECONDS = 10;
    /** How long a stopping proxy runs before the shutdown hook in {@code ProxyMain} halts it. */
    public static final int STOP_FORCE_EXIT_SECONDS = 10;
    /**
     * How long launchd lets the proxy run on after SIGTERM before it kills it (#1905). Its default
     * is system-defined, about 5s when measured for #916, below the stop's worst case of the
     * force-exit plus the process's teardown, so a slow close would be killed before the proxy
     * could warn about it. {@link LaunchdJob#UNLOAD_POLLS} has to outlast this.
     */
    static final int LAUNCHD_EXIT_TIMEOUT_SECONDS = STOP_FORCE_EXIT_SECONDS + 5;

    /**
     * How long to wait for a proxy that was just started or installed to become healthy.
     * {@link ProxyHealthCheck#awaitHealthy} returns as soon as the proxy answers, so a
     * generous default costs a healthy host nothing. Configurable via
     * {@code ready-timeouts.proxy} in config.yaml.
     */
    static final int DEFAULT_START_WAIT_SECONDS = 30;

    /**
     * The start/install wait from config.yaml ({@code ready-timeouts.proxy}), or
     * {@link #DEFAULT_START_WAIT_SECONDS} when absent. Replaceable for tests.
     */
    static java.util.function.IntSupplier startWaitSecondsSupplier =
            () -> ReadyTimeoutsConfig.proxySecondsFromConfig(DEFAULT_START_WAIT_SECONDS);

    /** The configured proxy start/install wait, reading config once per call. */
    public static int configuredStartWaitSeconds() {
        return startWaitSecondsSupplier.getAsInt();
    }

    /**
     * How long to wait for a service that was just started or restarted to answer. On macOS a
     * first start that fails (the VM-facing bridge not discoverable yet, say) is retried by
     * launchd only after {@code ThrottleInterval}, so a shorter wait reports "not responding"
     * for a proxy that comes up a moment later (#969). A healthy proxy ends the wait at once.
     */
    static int startWaitSeconds(int seconds) {
        return startWaitSeconds(Platform.isMacOS(), seconds);
    }

    static int startWaitSeconds(boolean macOs, int seconds) {
        return macOs ? Math.max(seconds, LAUNCHD_THROTTLE_SECONDS + 5) : seconds;
    }

    /**
     * Waits for a service that was just started or restarted to answer: {@code seconds}, or on
     * macOS long enough for launchd's retry ({@link #startWaitSeconds}).
     */
    public static boolean awaitStarted(int seconds) {
        return ProxyHealthCheck.awaitHealthy(startWaitSeconds(seconds));
    }

    /**
     * True when the service gave up because of a misconfiguration rather than a transient
     * failure — it exited {@link #EXIT_CONFIG} and systemd declined to restart it. Callers use
     * this to report the actual cause instead of a generic "did not become healthy", which
     * points at the network and hides the real problem.
     * <p>
     * Always false on macOS: launchd's {@code KeepAlive} cannot express a per-exit-code restart
     * policy, so the condition this detects does not arise there.
     */
    public static boolean failedWithConfigError() {
        if (Platform.isMacOS()) return false;
        // Both properties in one invocation. Without --value the output is self-describing
        // ("ActiveState=failed\nExecMainStatus=78"), so neither value is read positionally.
        var shown = showProperties("ActiveState", "ExecMainStatus");
        return shown != null
                && shown.contains("ActiveState=failed")
                && shown.contains("ExecMainStatus=" + EXIT_CONFIG);
    }

    /** Read systemd unit properties as {@code key=value} lines, or null if unavailable. */
    private static String showProperties(String... properties) {
        var command = new ArrayList<>(List.of("systemctl", "--user", "show", SERVICE_NAME));
        for (var property : properties) {
            command.add("-p");
            command.add(property);
        }
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            return process.waitFor() == 0 ? output : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The pid of the service's running process ({@code MainPID}, or the {@code pid} of {@code
     * launchctl print}), 0 when the service means to run but has no process, or -1 when it does
     * not mean to run. A unit waiting to be restarted is {@code activating} (auto-restart), and
     * a launchd job between two retries is loaded with no pid: both are 0, since a service that
     * cannot bind spends most of its time there.
     */
    static long servicePid() {
        String pid;
        if (Platform.isMacOS()) {
            // Loaded is what isActive() means on macOS.
            var output = proxyJobPrintOutput();
            if (output == null) return -1;
            pid = parseLaunchctlPrintPid(output);
        } else {
            var shown = showProperties("ActiveState", "MainPID");
            var state = shown == null ? null : shown.lines().filter(l -> l.startsWith("ActiveState="))
                    .findFirst().map(l -> l.substring("ActiveState=".length())).orElse(null);
            if (!"active".equals(state) && !"activating".equals(state) && !"reloading".equals(state)) return -1;
            pid = shown.lines().filter(l -> l.startsWith("MainPID=")).findFirst()
                    .map(l -> l.substring("MainPID=".length())).orElse(null);
        }
        try {
            return pid == null ? 0 : Long.parseLong(pid.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The proxy answering {@code info} when it is not the service's process (#1570), else null.
     * With the service active, any proxy that is not its own; with it stopped or failed, only
     * another user's, whose CA and credentials instances then reach, while one of this user's
     * stays unreported, as before. Asks the service manager once, so only commands that report
     * on the service call it, never the shell path.
     */
    public static ProxyHolder foreignHolder(ProxyHealthCheck.ProxyInfo info) {
        if (info == null || info.pid() <= 0) return null;
        return ProxyHolder.forService(info, servicePid());
    }

    /**
     * Whether the healthy proxy that answers is the active service's own, reporting the holder
     * as an error when it is not (#1570). A health check alone passes for any proxy on the ports,
     * so every path that reports the service as running asks this after it.
     * A holder of this user's is asked about again for up to {@link #LAUNCHD_THROTTLE_SECONDS}:
     * a restarted service's previous process frees the health port last (#1219), and launchd
     * kills one that ignores SIGTERM only seconds later. Another user's is never the service's.
     */
    public static boolean answeringIsTheService() {
        var address = ProxyHealthCheck.resolveHealthAddress();
        if (address == null) return true;
        for (int attempt = 1; ; attempt++) {
            var holder = foreignHolder(ProxyHealthCheck.fetchProxyInfo(address, 500));
            if (holder == null) return true;
            if (!holder.otherUser() && attempt < LAUNCHD_THROTTLE_SECONDS * 2) {
                try {
                    Thread.sleep(500);
                    continue;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            holder.report(BuildOutput.bound());
            return false;
        }
    }

    public static boolean isActive() {
        if (Platform.isMacOS()) return isMacOsServiceActive();
        try {
            var pb = new ProcessBuilder("systemctl", "--user", "is-active", SERVICE_NAME);
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            return process.waitFor() == 0 && "active".equals(output);
        } catch (Exception e) {
            return false;
        }
    }

    private static final int REQUIRED_JAVA_MAJOR = 25;

    public static boolean install() {
        if (!initComplete()) return false;
        try (var ignored = acquireProxyLock()) {
            // Starting a service that is already running is a no-op, so only a service that was
            // down is known to be running the binary just written into its files.
            var wasActive = isActive();
            var installed = installLocked();
            if (installed && !wasActive) DriftRestartRecord.write(resolveProxyBinaryPath());
            return installed;
        }
    }

    /**
     * {@code isx-proxy} exits {@link #EXIT_CONFIG} until init has completed for this build, so a
     * service installed or restarted before then only ever reports a proxy that is not responding
     * (#968). Says so instead, and names the command that fixes it. Every path that starts or
     * restarts the service asks this first: after an upgrade that raised {@code INIT_VERSION}, a
     * restart would stop a working proxy for one that refuses to start (#1048).
     */
    public static boolean initComplete() {
        return initComplete(BuildOutput.bound());
    }

    /** {@link #initComplete()}, reporting the refusal to {@code out} as an error: the TUI's paths reach this. */
    public static boolean initComplete(Output out) {
        if (Environment.hasBeenInitialized()) return true;
        out.error("incus-spawn is not initialized for this version of isx. Run 'isx init' first.");
        return false;
    }

    private static boolean installLocked() {
        if (Platform.isMacOS()) {
            return installMacOs();
        }

        var proxyBin = resolveProxyBinaryPath();
        if (!proxyBinaryIsUsable(proxyBin)) return false;

        var serviceContent = serviceUnitContent();

        try {
            writeProxyStartScript(proxyStartScript(), proxyBin);
            Files.createDirectories(Environment.proxyServiceFile().getParent());
            Files.writeString(Environment.proxyServiceFile(), serviceContent);
        } catch (IOException e) {
            BuildOutput.error("Failed to write service file: " + e.getMessage());
            return false;
        }

        BuildOutput.step("Service file written to " + Environment.proxyServiceFile());
        // Whether it took is asked below, of the service itself
        BuildOutput.stepStart("Enabling and starting proxy service");
        if (runQuiet("systemctl", "--user", "daemon-reload")
                & runQuiet("systemctl", "--user", "enable", "--now", SERVICE_NAME)) BuildOutput.stepDone();
        else BuildOutput.stepBreak();

        BuildOutput.stepStart("Enabling lingering for user (sudo required)");
        if (runQuiet("sudo", "loginctl", "enable-linger", System.getProperty("user.name"))) BuildOutput.stepDone();
        else BuildOutput.stepFail("The proxy stops when you log out. Run: sudo loginctl enable-linger "
                + System.getProperty("user.name"));

        if (isActive()) {
            if (ProxyHealthCheck.awaitHealthy(configuredStartWaitSeconds())) {
                if (!answeringIsTheService()) return false;
                BuildOutput.step("Proxy service is running.");
                return true;
            }
            BuildOutput.error("Service started but proxy is not responding.", serviceLogs());
            return false;
        } else {
            BuildOutput.error("Service did not start.", serviceLogs());
            return false;
        }
    }

    /**
     * Removes the service alone, not what routes instances to it; false when it is installed and
     * stays. For {@code isx reset}, which clears the routing whatever became of the service.
     */
    public static boolean uninstallService() {
        return uninstall(ProxyService::isActive);
    }

    /** {@link #uninstallService()} with the unit's state after its stop replaceable, for tests. */
    static boolean uninstall(BooleanSupplier active) {
        try (var ignored = acquireProxyLock()) {
            return uninstallLocked(active);
        }
    }

    /**
     * Removes the service, then what routes instances to it; false when either stays. A service
     * still running keeps its DNS overrides and redirect rules, which it needs.
     */
    public static boolean uninstall(IncusClient incus) {
        return uninstall(ProxyService::uninstallService, () -> ProxyConfig.clearBridgeDns(incus),
                ProxyConfig::clearRedirectRules);
    }

    /** {@link #uninstall(IncusClient)} with its three removals replaceable, for tests. */
    static boolean uninstall(BooleanSupplier service, BooleanSupplier bridgeDns, BooleanSupplier redirectRules) {
        if (!service.getAsBoolean()) return false;
        // Both are tried: one that stays does not keep the other
        var dnsCleared = bridgeDns.getAsBoolean();
        return redirectRules.getAsBoolean() && dnsCleared;
    }

    private static boolean uninstallLocked(BooleanSupplier active) {
        if (Platform.isMacOS()) return uninstallMacOs();
        if (!isInstalled()) {
            // Nothing to remove is what uninstalling asks for
            BuildOutput.step("Proxy service is not installed.");
            return true;
        }

        BuildOutput.stepStart("Stopping and disabling proxy service");
        // A stop systemctl refuses for a unit that is not running (no user manager, a unit never
        // loaded) still lets its files go; a unit still running keeps them.
        runQuiet("systemctl", "--user", "stop", SERVICE_NAME);
        if (active.getAsBoolean()) {
            BuildOutput.stepBreak();
            BuildOutput.error("Could not stop the proxy service; its files stay.",
                    "Check it with: systemctl --user status " + SERVICE_NAME);
            return false;
        }
        runQuiet("systemctl", "--user", "disable", SERVICE_NAME);
        BuildOutput.stepDone();

        try {
            Files.deleteIfExists(Environment.proxyServiceFile());
            Files.deleteIfExists(proxyStartScript());
        } catch (IOException e) {
            BuildOutput.error("Failed to remove service files: " + e.getMessage());
            return false;
        }

        runQuiet("systemctl", "--user", "daemon-reload");
        BuildOutput.step("Proxy service uninstalled.");
        return true;
    }

    public static boolean restart() {
        return restart(BuildOutput.bound());
    }

    /**
     * Restart the service: its progress as notes to {@code out}, the refusal before init as an
     * error, and a restart that did not take, or launchd's refusal of one, as a warning.
     */
    public static boolean restart(Output out) {
        try (var ignored = acquireProxyLock()) {
            return restartLocked(out);
        }
    }

    /**
     * Restarts the proxy unless it is already healthy by the time the lock is held. Several
     * {@code isx} commands finding the proxy down at once (parallel {@code isx branch}, the
     * usual agent pattern) all reach this; without the recheck, the second one through the lock
     * would {@code kickstart -k} a proxy the first one just brought up — on macOS blocking for
     * the rest of {@code ThrottleInterval} and then cutting every connection of a proxy that was
     * fine (review on #916). Re-checks under the lock, the way {@link #reinstallIfChanged}
     * re-checks drift, rather than before it: another process could otherwise finish its own
     * restart in the gap between this check and acquiring the lock.
     */
    public static boolean restartIfUnhealthy(String healthAddr, Output out) {
        return restartIfUnhealthy(healthAddr, ProxyConfig.DEFAULT_HEALTH_PORT, out);
    }

    /** {@link #restartIfUnhealthy(String, Output)} against a given port, for tests. */
    static boolean restartIfUnhealthy(String healthAddr, int healthPort, Output out) {
        return restartIfUnhealthy(healthAddr, healthPort, () -> restartLocked(out));
    }

    /**
     * As above, with the restart itself replaceable. A test proving the health recheck skips a
     * restart it never reaches only needs a stub here, not the real {@code launchctl}/{@code
     * systemctl} — which a regression in that recheck would otherwise run against whatever
     * service is actually installed on the machine running the test (review on #916).
     */
    static boolean restartIfUnhealthy(String healthAddr, int healthPort, BooleanSupplier restart) {
        try (var ignored = acquireProxyLock()) {
            if (ProxyHealthCheck.isHealthy(healthAddr, healthPort)) return true;
            return restart.getAsBoolean();
        }
    }

    private static boolean restartLocked(Output out) {
        return restartLocked(out, false);
    }

    /**
     * @param forceReload the caller already knows the loaded job may not match disk — a drift
     *                     repair ({@link #reinstallIfChanged}) or an upgrade ({@link
     *                     #upgradeIfNeeded}) — so launchd must always re-read the plist. Left
     *                     false, only a plist this restart itself finds stale forces that: {@code
     *                     kickstart -k} never re-reads the plist, so any other cause of a
     *                     loaded/disk mismatch (a Ctrl-C between a write and the {@code bootout}
     *                     below, a failed {@code bootout}, an install interrupted after writing
     *                     the plist but before this restart) would otherwise last until the next
     *                     login (review on #916).
     */
    private static boolean restartLocked(Output out, boolean forceReload) {
        // Under the lock, after restartIfUnhealthy's recheck: a healthy proxy is never refused.
        if (!initComplete(out)) return false;
        ProxyLog.info("Service restarting");
        out.note("Restarting proxy service");
        boolean restarted;
        if (Platform.isMacOS()) {
            // Refresh a stale plist before loading it: a plist from an older build — one whose
            // ProgramArguments are `isx proxy start` rather than isx-proxy directly — would
            // otherwise relaunch into the same binary this restart is meant to replace. This
            // method is never reached from inside the job it would unload: that case is
            // isSupervisedInvocation(), which ProxyStartCommand routes to the foreground instead
            // of here (#977) — see migrateMacOsPlistIfSupervised() for how that case still gets
            // its plist rewritten.
            // forceReload callers have already decided to restart for their own reason — a
            // stale plist (upgradeIfNeeded), or health-assessed drift needsMacOsPlistUpdate()
            // itself said nothing about (reinstallIfChanged, review on #916). Writing directly
            // here, instead of asking refreshStaleMacOsPlist's own comparison again, means a
            // restart they already committed to is never second-guessed by a check that would
            // say "nothing to do" in the second case.
            var rewrote = forceReload ? writeMacOsPlist(out) : refreshStaleMacOsPlist(out);
            restarted = proxyJob().restart(forceReload || rewrote, out).up();
        } else {
            // A unit halted by RestartPreventExitStatus sits in 'failed' state, and repeated
            // restart attempts can trip systemd's start rate limit — reset makes recovery
            // unconditional once the user has fixed the config. No-op when the unit is healthy.
            runQuiet("systemctl", "--user", "reset-failed", SERVICE_NAME);
            runQuiet("systemctl", "--user", "restart", SERVICE_NAME);
            restarted = isActive();
        }
        if (restarted) {
            out.note("Proxy service restarted.");
            return true;
        }
        out.warn("Proxy service did not restart.");
        return false;
    }

    /**
     * Start the proxy through the service manager when it is installed but not
     * currently active. Returns true if the service is active afterward, which the caller follows
     * with {@link #awaitStarted}.
     * <p>
     * On macOS that includes a job whose first run exited before {@code LaunchdJob} saw it settle:
     * it is loaded and launchd starts it again after {@code ThrottleInterval}, which is what
     * {@code awaitStarted} waits out. Answering false there reported "failed to start" about a
     * proxy that came up ten seconds later (#1098). False is left for a load launchd refused.
     */
    public static boolean startService() {
        if (!isInstalled()) return false;
        if (isActive()) return true;
        if (!initComplete()) return false;
        try (var ignored = acquireProxyLock()) {
            if (isActive()) return true;
            if (Platform.isMacOS()) return proxyJob().start(BuildOutput.bound()).launched();
            runQuiet("systemctl", "--user", "reset-failed", SERVICE_NAME);
            runQuiet("systemctl", "--user", "start", SERVICE_NAME);
            return isActive();
        }
    }

    /** Stops the proxy, the service's or one run by hand; false when one is running and could not be stopped. */
    public static boolean stop() {
        try (var ignored = acquireProxyLock()) {
            return stopLocked();
        }
    }

    private static boolean stopLocked() {
        if (isActive()) {
            if (!Platform.isMacOS()) return stopSystemdUnit(ProxyService::isActive);
            BuildOutput.stepStart("Stopping proxy service");
            // launchd's bootout answers as soon as the job is told to go (see LaunchdJob), so its
            // result proves nothing and is not asked
            runQuiet("launchctl", "bootout", "gui/" + getUid() + "/" + PROXY_LABEL);
            BuildOutput.stepDone();
            BuildOutput.stepNote("Re-enable it with: isx proxy install");
            return true;
        }

        return stopManualProxy(ProxyHealthCheck::reportedProxyPid, ProxyService::findProxyPid);
    }

    /** Stops the systemd unit, false with an error when {@code systemctl} refused or the unit is still {@code active} after it. */
    static boolean stopSystemdUnit(BooleanSupplier active) {
        BuildOutput.stepStart("Stopping proxy service");
        if (runQuiet("systemctl", "--user", "stop", SERVICE_NAME) && !active.getAsBoolean()) {
            BuildOutput.stepDone();
            return true;
        }
        BuildOutput.stepBreak();
        BuildOutput.error("Could not stop the proxy service.",
                "Check it with: systemctl --user status " + SERVICE_NAME);
        return false;
    }

    /**
     * Stops a proxy run by hand rather than by the service (#155). Its PID comes from what
     * {@code /health} reports, as {@link #sendAccountRefreshSignal()} finds it: {@code fuser}
     * alone finds nothing on macOS, whose {@code fuser} has no {@code port/tcp} form, nor where
     * psmisc is not installed, and the proxy was then left running behind "Proxy is not running."
     */
    static boolean stopManualProxy(LongSupplier reportedPid, LongSupplier pidOnPort) {
        var pid = reportedPid.getAsLong();
        if (pid <= 0) pid = pidOnPort.getAsLong();
        // The PID came over HTTP: 0 or a negative one would make `kill` signal a process group.
        if (pid > 0) {
            BuildOutput.stepStart("Stopping proxy (PID " + pid + ")");
            if (runQuiet("kill", String.valueOf(pid))) {
                BuildOutput.stepDone();
                return true;
            }
            BuildOutput.stepBreak();
            BuildOutput.error("Could not stop the proxy (PID " + pid + "); is it another user's?");
            return false;
        }

        BuildOutput.step("Proxy is not running.");
        return true;
    }

    /**
     * Check whether the installed service needs updating (binary path or version)
     * and restart if so. Returns true if a restart was performed.
     * <p>
     * Drift is judged from {@code info}, which the caller has just fetched, rather than by
     * fetching {@code /health} again: a second fetch that failed would read as "no drift", and a
     * caller that had already announced drift would then do nothing. Null means "unknown".
     */
    public static boolean reinstallIfChanged(IncusClient incus, ProxyHealthCheck.ProxyInfo info) {
        try (var ignored = acquireProxyLock()) {
            var proxyBin = resolveProxyBinaryPath();
            // A plist whose binary has gone cannot be brought into line, and restarting it
            // below would just hand it back to KeepAlive. Take it out of service instead.
            if (Platform.isMacOS() && proxyBin == null && haltUnusableMacOsServiceLocked()) {
                return false;
            }
            // Before the service files too: rewritten now and not restarted onto, they would
            // compare equal on the run of `isx init` that could restart onto them.
            if (!initComplete()) return false;
            var needsReinstall = Platform.isMacOS() ? needsMacOsPlistUpdate() : regenerateServiceFiles(proxyBin);

            // Assessed again under the lock: another isx process may have made this very restart
            // while we waited for it, and a second one would only cut every instance's
            // connection again.
            if (!needsReinstall) needsReinstall = ProxyHealthCheck.assessDrift(info).restartHelps();

            if (needsReinstall) {
                // Always reload on macOS: the plist on disk may already match what this build
                // would write even though the loaded job does not (review on #916) — e.g. drift
                // was assessed from a health signal, not from needsMacOsPlistUpdate() above, so
                // there is nothing here to tell restartLocked the loaded job is stale except
                // saying so directly.
                var restarted = restartLocked(BuildOutput.bound(), true);
                // The service files now exec proxyBin, so this records the binary the service
                // really restarted onto. A bare restart() may still exec a binary from a previous
                // installation, which says nothing about this one, so it records nothing.
                if (restarted) DriftRestartRecord.write(proxyBin);
                return restarted;
            }
            return false;
        }
    }

    /**
     * Bring both on-disk service files — the systemd unit and the start script it execs — into
     * line with what this build would write, rewriting whichever differs. Returns true if either
     * was rewritten, meaning the caller must restart the service.
     * <p>
     * The two files are checked independently because <b>they do not carry the same information</b>.
     * {@link #execStartLine()} names the start script, at a fixed path, so the unit text is
     * identical for every installation on every machine; the path to the {@code isx} binary
     * appears only inside the start script. Comparing the unit alone therefore cannot detect a
     * binary that moved, which is exactly what happens on an upgrade that changes where {@code isx}
     * lives (a distro package landing in {@code /usr/bin} over a previous {@code ~/.local/bin}
     * install, or the reverse). Before this checked the script too, the unit always compared equal,
     * the script was never refreshed, and the service went on exec'ing a binary from the previous
     * installation indefinitely — while version-drift detection dutifully restarted that same stale
     * binary and reported success.
     * <p>
     * Regenerating rather than patching means each file picks up every future change to its
     * template for free. Patching individual directives instead meant a new helper per directive,
     * in each of the places that knew the unit's shape, which is how {@code RestartPreventExitStatus}
     * came to be missing from this path.
     * <p>
     * Safe to overwrite: {@link #install()} already writes both files wholesale, and systemd's
     * supported customization mechanism is a drop-in ({@code <unit>.d/override.conf}), a separate
     * file this never touches.
     */
    private static boolean regenerateServiceFiles(String proxyBin) {
        if (Platform.isMacOS() || !Files.exists(Environment.proxyServiceFile())) return false;
        try {
            var changed = false;

            // Only the *script* needs a proxy binary. The unit is refreshed either way: its text
            // holds no binary path, and an installation with no isx-proxy is exactly the one that
            // needs `RestartPreventExitStatus` — without it a unit from an older build retries the
            // EXIT_CONFIG failure every five seconds forever, which is the loop this all exists to
            // end.
            var script = proxyStartScript();
            if (proxyBin != null && startScriptIsStale(script, proxyBin)) {
                writeProxyStartScript(script, proxyBin);
                changed = true;
            }

            var expectedUnit = serviceUnitContent();
            if (!expectedUnit.equals(Files.readString(Environment.proxyServiceFile()))) {
                Files.writeString(Environment.proxyServiceFile(), expectedUnit);
                // Only the unit is systemd's to parse; a start script change needs a restart
                // (handled by the caller) but not a reload.
                runQuiet("systemctl", "--user", "daemon-reload");
                changed = true;
            }

            return changed;
        } catch (IOException e) {
            BuildOutput.warn("Could not update proxy service files: " + e.getMessage());
            return false;
        }
    }

    /**
     * True when the start script is missing or does not exec {@code proxyBin} — i.e. when the
     * service would otherwise keep running a binary from a previous installation.
     * <p>
     * Only the exec'd binary matters for staleness. PATH changes across sessions must not trigger
     * restarts — users with conda/nvm/sdkman get different PATHs per session, and each would
     * cause a spurious proxy restart that interrupts in-flight proxied requests. PATH is refreshed
     * whenever the script is rewritten for binary-path reasons or on explicit reinstall.
     */
    static boolean startScriptIsStale(Path script, String proxyBin) throws IOException {
        if (!Files.exists(script)) return true;
        var content = Files.readString(script);
        if (!content.contains("export PATH=")) return true;
        // Also catches scripts from before the proxy split: they exec `isx proxy start`, which is
        // not the exec line for any proxy binary, so the comparison below rewrites them.
        if (!content.contains(execCommand(proxyBin))) return true;
        if (Platform.isLinux() && !content.contains("command -v sg")) return true;
        return false;
    }

    /** Returns true if it restarted the service. */
    public static boolean upgradeIfNeeded() {
        try (var ignored = acquireProxyLock()) {
            var proxyBin = resolveProxyBinaryPath();
            if (Platform.isMacOS() && proxyBin == null && haltUnusableMacOsServiceLocked()) {
                return false;
            }
            // As in reinstallIfChanged: refused before the service files are rewritten.
            if (!initComplete()) return false;
            if (Platform.isMacOS()) {
                // Forcing the reload here is also what lets restartLocked skip asking
                // needsMacOsPlistUpdate() a second time: it already answered yes, right above.
                if (!needsMacOsPlistUpdate()) return false;
                var restarted = restartLocked(BuildOutput.bound(), true);
                if (restarted) DriftRestartRecord.write(proxyBin);
                return restarted;
            }
            if (!regenerateServiceFiles(proxyBin)) return false;
            BuildOutput.step("Updated proxy service configuration.");
            runQuiet("systemctl", "--user", "restart", SERVICE_NAME);
            if (isActive()) DriftRestartRecord.write(proxyBin);
            return true;
        }
    }

    /**
     * Waits for launchd to finish removing a job after {@code bootout} (#977), by asking {@code
     * launchctl print} whether the target is still known at all. The proxy job has {@link
     * LaunchdJob} for this; the VM login agent is a one-shot that is loaded but never kickstarted
     * or expected to stay running, so it only needs the wait. Up to 20s: how long launchd lets a
     * job ignore SIGTERM before killing it ({@code ExitTimeOut}) is "system-defined" in current
     * {@code launchd.plist(5)}, about 5s when measured for #916 and 20s in older man pages, so
     * this takes the longest of those.
     */
    private static void waitForJobUnloaded(String target) {
        for (int i = 0; i < 200; i++) {
            if (!runQuiet("launchctl", "print", target)) return;
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Ask a running proxy to re-read which accounts each instance uses, without restarting it
     * and without the full config reload SIGHUP triggers.
     *
     * <p>Account pinning is instance state, not config state, and it changes on every branch --
     * the most common operation there is. A full reload would re-mint certificates and re-push
     * SSL options every time, so this uses SIGUSR1, which only re-reads the instance list.
     *
     * <p>Best-effort and deliberately silent: the proxy also refreshes on a short timer, so
     * this only removes the delay. In-flight requests are unaffected.
     *
     * <p>Not lock-guarded, unlike the mutating lifecycle operations: it does not change service
     * state, and taking the lock would serialise every branch behind it.
     */
    public static void signalAccountRefresh() {
        accountRefreshSignal.run();
    }

    /**
     * What {@link #signalAccountRefresh()} does. The real one signals whatever proxy runs on the
     * developer's machine, so a test reaching any caller -- branch, destroy, clean, an account
     * change -- runs under a test home ({@code TempHome}, {@code IsolatedHome}), which replaces it.
     */
    private static volatile Runnable accountRefreshSignal = ProxyService::sendAccountRefreshSignal;

    /** Replace what {@link #signalAccountRefresh()} does, returning what it did before. For tests. */
    public static Runnable replaceAccountRefreshSignal(Runnable signal) {
        var previous = accountRefreshSignal;
        accountRefreshSignal = signal;
        return previous;
    }

    private static void sendAccountRefreshSignal() {
        // Asked at signalling time rather than remembered from an earlier health check, so a
        // proxy restarted in between is never signalled by a stale PID. fuser is the fallback
        // for proxies that predate reporting it: 45-90 ms scanning every process on a desktop.
        var pid = ProxyHealthCheck.reportedProxyPid();
        if (pid == -1) pid = findProxyPid();
        if (pid != -1) runQuiet("kill", "-USR1", String.valueOf(pid));
    }

    private static long findProxyPid() {
        try {
            var pb = new ProcessBuilder("fuser", ProxyConfig.DEFAULT_HEALTH_PORT + "/tcp");
            pb.redirectErrorStream(false);
            var process = pb.start();
            // fuser sends port label to stderr, PIDs to stdout
            var stdout = new String(process.getInputStream().readAllBytes()).strip();
            process.getErrorStream().readAllBytes();
            if (process.waitFor() == 0 && !stdout.isBlank()) {
                return Long.parseLong(WHITESPACE.split(stdout)[0]);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * If the given binary is really a launcher script, validate whatever it needs at run time.
     * Returns a diagnostic message on failure, null if OK (including for a native binary).
     * <p>
     * Two launcher shapes exist. {@code install.sh} writes a bash wrapper that execs a fixed
     * {@code java -jar}, so the check is that the Java it names is present and new enough. JBang
     * writes {@code exec jbang run <alias>} and provisions its own JDK, so what has to be on PATH
     * is {@code jbang} itself — and a service manager's PATH is not the shell's, which is exactly
     * where that goes wrong.
     */
    static String checkJvmWrapper(String binaryPath) {
        try {
            var content = Files.readString(Path.of(binaryPath));
            if (!content.startsWith("#!")) return null; // native binary
            var jbangIssue = checkJbangWrapper(binaryPath, content);
            if (jbangIssue != null) return jbangIssue;
            var matcher = JAVA_WRAPPER.matcher(content);
            if (!matcher.find()) return null; // not a java wrapper
            var javaBin = matcher.group(1);

            if (!Files.isExecutable(Path.of(javaBin))) {
                return "Java binary not found: " + javaBin + "\n"
                        + "'" + binaryPath + "' is a JVM wrapper that requires Java "
                        + REQUIRED_JAVA_MAJOR + "+.";
            }

            var pb = new ProcessBuilder(javaBin, "-version");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() != 0) {
                return "Could not determine Java version for " + javaBin;
            }
            var vmatch = output.lines()
                    .map(JAVA_VERSION::matcher)
                    .filter(java.util.regex.Matcher::find)
                    .findFirst();
            if (vmatch.isEmpty()) {
                return "Could not determine Java version for " + javaBin;
            }
            var vmatcher = vmatch.get();
            int major = Integer.parseInt(vmatcher.group(1));
            if (major == 1 && vmatcher.group(2) != null) {
                major = Integer.parseInt(vmatcher.group(2));
            }
            if (major < REQUIRED_JAVA_MAJOR) {
                return "Java " + REQUIRED_JAVA_MAJOR + "+ is required, but " + javaBin
                        + " is version " + major + ".";
            }
            return null;
        } catch (Exception e) {
            return null; // if we can't check, let it proceed and fail naturally
        }
    }

    /**
     * Diagnose a JBang launcher script, or return null when the script is not one (or is one that
     * will work). The wrapper re-resolves its alias through {@code jbang} on every invocation, so
     * a service that cannot find {@code jbang} on its PATH fails at every start.
     */
    private static String checkJbangWrapper(String binaryPath, String content) {
        var matcher = JBANG_WRAPPER.matcher(content);
        if (!matcher.find()) return null;
        var jbang = matcher.group(1);

        // A path the shell expands at run time ($JBANG_DIR/bin/jbang) is captured literally and
        // cannot be probed. Fall back to the bare name rather than declaring a working install
        // broken — this diagnostic blocks `proxy install`, so a false positive is expensive.
        if (jbang.contains("$") || jbang.contains("{")) jbang = "jbang";

        var found = jbang.contains("/")
                ? Files.isExecutable(Path.of(jbang))
                : HostPath.isOnPath(jbang);
        if (found) return null;

        return "'" + binaryPath + "' is a JBang launcher, but '" + jbang
                + "' is not on PATH.\n"
                + "The proxy service inherits the PATH it was installed with, so jbang must be "
                + "resolvable from the shell you run 'isx init' in.";
    }

    /** What the journal says of the service, with advice for what it shows: the lines under a failed start. */
    private static String[] serviceLogs() {
        var lines = new ArrayList<String>();
        try {
            var pb = new ProcessBuilder(
                    "journalctl", "--user", "-u", SERVICE_NAME, "--no-pager", "-n", "10");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            if (!output.isBlank()) {
                lines.add("Recent logs:");
                output.lines().forEach(lines::add);
                if (output.contains("status=127")) {
                    lines.add("");
                    lines.add("Exit code 127 usually means a binary was not found.");
                    try {
                        var svc = Files.readString(Environment.proxyServiceFile());
                        var m = EXEC_START.matcher(svc);
                        if (m.find()) lines.add("ExecStart: " + m.group(1));
                    } catch (Exception ignored) {}
                    lines.add("If isx-proxy was installed as a JVM wrapper, ensure Java "
                            + REQUIRED_JAVA_MAJOR + "+ is available at the path embedded in the wrapper."
                            + " If it was installed with JBang, ensure 'jbang' is on the PATH the"
                            + " service was installed with.");
                    lines.add("Alternatively, reinstall isx using your package manager, or: curl -fsSL https://isx.run | sh");
                }
            } else {
                lines.add("Check logs with: journalctl --user -u " + SERVICE_NAME);
            }
        } catch (Exception ignored) {
            lines.add("Check logs with: journalctl --user -u " + SERVICE_NAME);
        }
        return lines.toArray(String[]::new);
    }

    /** The installed {@code isx} to point services and clients at, or null if none is found. */
    public static String resolveIsxPath() {
        var found = HostPath.find("isx");
        if (found != null) return found;
        var fallback = Environment.localBinIsx();
        if (java.nio.file.Files.isExecutable(fallback)) {
            return fallback.toString();
        }
        return null;
    }

    public static String resolveProxyBinaryPath() {
        return proxyBinaryNextTo(resolveIsxPath());
    }

    /**
     * The {@code isx} an {@code isx-proxy} at {@code proxyPath} runs: the one beside it (a native
     * install), else {@link #resolveIsxPath()}. The mirror of {@link #proxyBinaryNextTo}.
     */
    public static String isxNextTo(Path proxyPath) {
        if (proxyPath != null && proxyPath.getFileName().toString().equals("isx-proxy")) {
            var sibling = proxyPath.resolveSibling("isx");
            if (Files.isExecutable(sibling)) return sibling.toString();
        }
        return resolveIsxPath();
    }

    /**
     * The sibling-probe half of {@link #resolveProxyBinaryPath()}, split out so callers that have
     * already resolved {@code isx} do not fork a second {@code which}.
     */
    private static String proxyBinaryNextTo(String isxPath) {
        if (isxPath != null) {
            var proxyPath = Path.of(isxPath).getParent().resolve("isx-proxy");
            if (Files.isExecutable(proxyPath)) return proxyPath.toString();
        }
        var fallback = Environment.localBinIsx().getParent().resolve("isx-proxy");
        if (Files.isExecutable(fallback)) return fallback.toString();
        return null;
    }

    /**
     * Whether this is a binary the service can be pointed at, printing why not when it is not.
     * Both install paths gate on it, so the launchd install gets the same JBang/JVM-launcher
     * diagnostics as the systemd one.
     */
    private static boolean proxyBinaryIsUsable(String proxyBin) {
        if (proxyBin == null) {
            printMissingProxyBinary();
            return false;
        }
        var launcherIssue = checkJvmWrapper(proxyBin);
        if (launcherIssue != null) {
            var issue = launcherIssue.lines().toList();
            var details = new ArrayList<>(issue.subList(1, issue.size()));
            details.add("Reinstall isx using your package manager, or: curl -fsSL https://isx.run | sh");
            details.add("Then run: isx init");
            BuildOutput.error(issue.getFirst(), details.toArray(String[]::new));
            return false;
        }
        return true;
    }

    /**
     * How a user fixes a missing {@code isx-proxy}. Single source: {@code isx doctor} and
     * {@code isx proxy start} must not offer differently-worded commands, and the JBang alias is
     * pinned by the uber-jar smoke test in {@code test-integration.yml}.
     */
    public static final String MISSING_PROXY_REMEDIATION =
            "Install it with 'jbang app install isx-proxy@Sanne/incus-spawn' if you installed with"
                    + " JBang, or reinstall isx: curl -fsSL https://isx.run | sh";

    /**
     * The proxy has lived in its own binary since the CLI dropped its Vert.x dependency, so there
     * is nothing for {@code isx} to fall back to when {@code isx-proxy} is missing — every install
     * channel must ship both. JBang is called out by name because its catalog installs each alias
     * separately, which is how an {@code isx}-only installation arises in practice.
     */
    public static void printMissingProxyBinary() {
        BuildOutput.error("Could not find the 'isx-proxy' binary next to 'isx'.",
                "The proxy runs as its own binary; isx cannot serve it in-process.",
                MISSING_PROXY_REMEDIATION,
                "Then run: isx init");
    }

    /**
     * Take the launchd job out of service when its program cannot run, and report it.
     * <p>
     * launchd has no {@code RestartPreventExitStatus}: a {@code KeepAlive} job that exits
     * {@link #EXIT_CONFIG} is simply started again, once per {@code ThrottleInterval}, forever. So
     * on macOS "there is no isx-proxy" cannot be answered by exiting with a good error — the error
     * is reprinted every ten seconds and nothing changes. Booting the job out alone would not
     * settle it either, because {@code RunAtLoad} brings the same plist back at the next login;
     * the plist has to go, and `isx init` writes a working one once the binary is in place.
     * <p>
     * Only reached once the proxy is known unhealthy (a responding proxy answers earlier), so this
     * never takes down a working service.
     */
    public static boolean haltUnusableMacOsService() {
        if (!Platform.isMacOS() || !isMacOsServiceInstalled()) return false;
        try (var ignored = acquireProxyLock()) {
            return haltUnusableMacOsServiceLocked();
        }
    }

    private static boolean haltUnusableMacOsServiceLocked() {
        if (!Platform.isMacOS() || !isMacOsServiceInstalled()) return false;
        // A responding proxy outranks our inability to find its binary on PATH: something is
        // serving, so removing its agent would break a working install over a resolution quirk.
        if (ProxyHealthCheck.isHealthy("127.0.0.1")) return false;
        runQuiet("launchctl", "bootout", "gui/" + getUid() + "/" + PROXY_LABEL);
        try {
            Files.deleteIfExists(proxyPlistFile());
        } catch (IOException e) {
            BuildOutput.warn("Could not remove the proxy launch agent: " + e.getMessage(),
                    "Remove " + proxyPlistFile() + " by hand to stop launchd retrying it.");
            return false;
        }
        BuildOutput.warn("Removed the proxy launch agent: launchd would otherwise keep restarting"
                        + " a service that cannot start.",
                "'isx init' reinstalls it once isx-proxy is present.");
        return true;
    }

    /**
     * True when this process <em>is</em> the proxy service rather than a user shell asking about
     * it. Managing the service from inside it is a self-restart loop: the unit/job execs
     * {@code isx proxy start}, that command finds the service installed and unhealthy, restarts
     * it, and the service manager starts the cycle again — on Linux with {@code reset-failed}
     * clearing the start rate limiter each time round, so the loop never terminates on its own.
     * <p>
     * Only a unit or plist written by older builds execs the CLI at all — this build's points
     * straight at {@code isx-proxy} — so the check is aimed squarely at them. On Linux, systemd
     * sets {@code INVOCATION_ID} for every unit it starts, and comparing it against the proxy
     * unit's own invocation confirms it is <em>this</em> unit rather than some other unit that
     * happens to run isx. On macOS there is no environment variable to read, but {@code
     * launchctl print}'s {@code pid} for the job is this process's own pid in exactly the same
     * case, which is just as conclusive. Either way, a positive answer here matters most on the
     * restart path: this process attempting {@code bootout} on the very job it is running under
     * can be ended by it before {@code bootstrap} runs again, leaving the job unloaded until the
     * next isx command or login (#977) — the restart that matters is this process simply running
     * the proxy in the foreground instead, which {@code ProxyStartCommand} already does whenever
     * {@code isSupervisedInvocation()} is true.
     */
    public static boolean isSupervisedInvocation() {
        if (Platform.isMacOS()) {
            var output = proxyJobPrintOutput();
            return output != null && isOwnJob(output, ProcessHandle.current().pid());
        }
        var invocationId = System.getenv("INVOCATION_ID");
        if (invocationId == null || invocationId.isBlank()) return false;
        var shown = showProperties("InvocationID");
        return shown != null && shown.contains("InvocationID=" + invocationId);
    }

    /** Raw {@code launchctl print} output for the proxy job, or null if not running or not found. */
    private static String proxyJobPrintOutput() {
        try {
            var pb = new ProcessBuilder("launchctl", "print", "gui/" + getUid() + "/" + PROXY_LABEL);
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return process.waitFor() == 0 ? output : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether {@code launchctlPrintOutput}'s {@code pid = N} line names {@code ownPid} — the
     * decision {@link #isSupervisedInvocation()} pins on macOS, separated from the process launch
     * above so the three cases (own pid, another pid, job not running) are testable without
     * shelling out. Package-private for tests.
     */
    static boolean isOwnJob(String launchctlPrintOutput, long ownPid) {
        var pid = parseLaunchctlPrintPid(launchctlPrintOutput);
        return pid != null && pid.equals(String.valueOf(ownPid));
    }

    /** {@code launchctl print}'s {@code pid = N} line, for {@link #isOwnJob}; package-private for tests. */
    static String parseLaunchctlPrintPid(String launchctlPrintOutput) {
        return launchctlPrintOutput.lines().map(String::strip)
                .filter(l -> l.startsWith("pid = "))
                .findFirst().map(l -> l.substring("pid = ".length())).orElse(null);
    }

    private static Path proxyStartScript() {
        return Environment.configDir().resolve("proxy-start.sh");
    }

    /**
     * The exec command the start script should contain for this proxy binary. There is
     * deliberately no {@code isx proxy start} fallback — see {@link #isSupervisedInvocation()}.
     */
    private static String execCommand(String proxyBin) {
        return "exec " + Container.shellQuote(proxyBin);
    }

    /** Single source of truth for the start script, so writing and staleness-checking cannot drift. */
    static String proxyStartScriptContent(String proxyBin) {
        return proxyStartScriptContent(proxyBin, System.getenv("PATH"));
    }

    static String proxyStartScriptContent(String proxyBin, String path) {
        var sb = new StringBuilder("#!/bin/bash\n");
        sb.append("export PATH=").append(Container.shellQuote(HostPath.effective(path))).append('\n');
        var cmd = execCommand(proxyBin);
        if (Platform.isLinux()) {
            sb.append(sgFallbackBlock(cmd));
        } else {
            sb.append(cmd).append('\n');
        }
        return sb.toString();
    }

    /**
     * Shell block that ensures incus-admin group membership before exec'ing the proxy.
     * Three cases:
     * <ol>
     *   <li>Group already active in this process → exec directly</li>
     *   <li>Group configured in /etc/group but not active (e.g. systemd user manager
     *       started before the user was added) → use {@code sg} to activate it, or
     *       exit 78 if {@code sg} is unavailable (Arch-family distros removed it)</li>
     *   <li>User not in the group at all → exit 78 with actionable guidance</li>
     * </ol>
     * {@code id -nG} (no argument) reports the current process's active groups;
     * {@code id -nG "$user"} reads configured membership from /etc/group.
     */
    static String sgFallbackBlock(String execCmd) {
        return """
                if id -nG | tr ' ' '\\n' | grep -qx incus-admin; then
                    %1$s
                fi
                if id -nG "$(id -un)" | tr ' ' '\\n' | grep -qx incus-admin; then
                    if command -v sg >/dev/null 2>&1; then
                        exec sg incus-admin -c %2$s
                    fi
                    echo "Cannot start proxy: your 'incus-admin' group membership is not active" >&2
                    echo "in this session, and 'sg' is not available to activate it." >&2
                    echo "" >&2
                    echo "Fix: log out and log back in to activate the group for all sessions." >&2
                    exit 78
                fi
                echo "Cannot start proxy: your user is not in the 'incus-admin' group." >&2
                echo "" >&2
                echo "Fix: run 'isx init' to set up Incus and add your user to the group," >&2
                echo "     then log out and log back in." >&2
                exit 78
                """.formatted(execCmd, Container.shellQuote(execCmd));
    }

    static void writeProxyStartScript(Path script, String proxyBin) throws IOException {
        Files.createDirectories(script.getParent());
        Files.writeString(script, proxyStartScriptContent(proxyBin));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static String execStartLine() {
        return "ExecStart=" + Container.shellQuote(proxyStartScript().toString());
    }

    // --- macOS launchd support ---

    private static final String VM_LABEL = "dev.incusspawn.vm";
    private static final String PROXY_LABEL = "dev.incusspawn.proxy";

    private static Path launchAgentsDir() {
        return Path.of(System.getProperty("user.home"), "Library", "LaunchAgents");
    }

    private static Path vmPlistFile() {
        return launchAgentsDir().resolve(VM_LABEL + ".plist");
    }

    private static Path proxyPlistFile() {
        return launchAgentsDir().resolve(PROXY_LABEL + ".plist");
    }

    public static boolean isMacOsServiceInstalled() {
        return Files.exists(proxyPlistFile());
    }

    /** Where to look when the service does not do what it was asked. */
    public static final String CHECK_LOGS = "Check logs with: isx proxy logs";

    /**
     * The lines under a service that is not responding: what launchd says of the proxy job's
     * last runs on macOS, if anything, and where its logs are.
     */
    public static String[] notResponding() {
        var details = new ArrayList<String>();
        var lastRun = macOsJobLastRun();
        if (lastRun != null) details.add(lastRun);
        details.add(CHECK_LOGS);
        return details.toArray(String[]::new);
    }

    /** What launchd says of the proxy job's last runs on macOS, or null when it says nothing. */
    private static String macOsJobLastRun() {
        if (!Platform.isMacOS()) return null;
        try {
            return proxyJob().lastRun();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean isMacOsServiceActive() {
        try {
            return proxyJob().isLoaded();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String generateProxyPlist(String proxyBin) {
        var path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            path = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin";
        }
        var serviceLog = Environment.proxyServiceLogFile();
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key><string>%s</string>
                    <key>ProgramArguments</key>
                    <array>
                        <string>%s</string>
                    </array>
                    <key>RunAtLoad</key><true/>
                    <key>KeepAlive</key><true/>
                    <key>ThrottleInterval</key><integer>%d</integer>
                    <key>ExitTimeOut</key><integer>%d</integer>
                    <key>EnvironmentVariables</key>
                    <dict>
                        <key>PATH</key><string>%s</string>
                    </dict>
                    <key>StandardOutPath</key><string>%s</string>
                    <key>StandardErrorPath</key><string>%s</string>
                </dict>
                </plist>
                """.formatted(PROXY_LABEL, proxyBin, LAUNCHD_THROTTLE_SECONDS, LAUNCHD_EXIT_TIMEOUT_SECONDS, path, serviceLog, serviceLog);
    }

    private static boolean needsMacOsPlistUpdate() {
        if (!Files.exists(proxyPlistFile())) return true;
        // With no binary there is no correct plist to compare against, let alone write. That is
        // not "up to date": callers handle it first, by taking the job out of service
        // (haltUnusableMacOsServiceLocked) rather than leaving KeepAlive to respawn it.
        var proxyBin = resolveProxyBinaryPath();
        if (proxyBin == null) return false;
        try {
            var content = Files.readString(proxyPlistFile());
            return !content.equals(generateProxyPlist(proxyBin));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Rewrites the plist when it is not what this build would write, and reports whether the
     * job now needs to reload it. False on a write failure too — a caller that read true back
     * as "reload" would have launchd re-read a plist that, in fact, did not change (review on
     * #916). The one caller that writes the plist without asking this is {@link #restartLocked}'s
     * {@code forceReload} branch, which already knows to reload for a reason this comparison
     * cannot see (health-assessed drift, in {@link #reinstallIfChanged}) and would otherwise be
     * told "nothing changed" by it.
     */
    private static boolean refreshStaleMacOsPlist(Output out) {
        return needsMacOsPlistUpdate() && writeMacOsPlist(out);
    }

    /** Writes the plist this build would generate. Returns whether the write succeeded. */
    private static boolean writeMacOsPlist(Output out) {
        var proxyBin = resolveProxyBinaryPath();
        if (proxyBin == null) return false;
        try {
            Files.createDirectories(proxyPlistFile().getParent());
            Files.writeString(proxyPlistFile(), generateProxyPlist(proxyBin));
            return true;
        } catch (IOException e) {
            out.warn("Could not update proxy plist: " + e.getMessage());
            return false;
        }
    }

    /**
     * Rewrite a stale legacy plist in place. Named for its one caller, {@code ProxyStartCommand},
     * which calls it only on the branch that runs the proxy in the foreground because this process
     * is itself {@code isSupervisedInvocation()} — the one case {@link #restartLocked} can never
     * reach, because bootout/bootstrap there would end the very job running this process (#977);
     * this method itself does not check that. No bootout or bootstrap here either, for the same
     * reason: launchd only re-reads the file on its next load, so this only takes effect at the
     * next login or explicit restart, not this one.
     * <p>
     * The write also closes {@link #needsMacOsPlistUpdate}'s only window onto this plist: once it
     * matches what this build generates, {@link #upgradeIfNeeded} and {@link #reinstallIfChanged}
     * see nothing stale and never reload the job from outside either. The legacy parent process
     * (a resident JVM on JVM installs, running `isx proxy start` under the old plist) therefore
     * stays up — this invocation runs the proxy in its own foreground meanwhile — until the next
     * login, or a drift/health check restarts the service for an unrelated reason.
     */
    public static void migrateMacOsPlistIfSupervised() {
        if (!Platform.isMacOS()) return;
        try (var ignored = acquireProxyLock()) {
            refreshStaleMacOsPlist(BuildOutput.bound());
        }
    }

    public static boolean installMacOs() {
        var isxPath = resolveIsxPath();
        if (isxPath == null) {
            BuildOutput.error("Could not find 'isx' in PATH.");
            return false;
        }

        var logDir = Environment.vmStateDir();
        try {
            Files.createDirectories(launchAgentsDir());
            Files.createDirectories(logDir);

            var path = System.getenv("PATH");
            if (path == null || path.isBlank()) {
                path = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin";
            }

            // VM agent — starts the VM on login
            var vmPlist = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0">
                    <dict>
                        <key>Label</key><string>%s</string>
                        <key>ProgramArguments</key>
                        <array>
                            <string>%s</string>
                            <string>vm</string>
                            <string>start</string>
                        </array>
                        <key>RunAtLoad</key><true/>
                        <key>EnvironmentVariables</key>
                        <dict>
                            <key>PATH</key><string>%s</string>
                        </dict>
                        <key>StandardOutPath</key><string>%s/vm-service.log</string>
                        <key>StandardErrorPath</key><string>%s/vm-service.log</string>
                    </dict>
                    </plist>
                    """.formatted(VM_LABEL, isxPath, path, logDir, logDir);
            Files.writeString(vmPlistFile(), vmPlist);
        } catch (IOException e) {
            BuildOutput.error("Failed to write launchd plist: " + e.getMessage());
            return false;
        }

        var uid = getUid();
        BuildOutput.stepStart("Installing VM service");
        runQuiet("launchctl", "bootout", "gui/" + uid, vmPlistFile().toString());
        // bootout returns before launchd has actually removed the job — the same race #916 found
        // for the proxy — and bootstrapping it again in that window fails with "Input/output
        // error", leaving no VM login agent loaded at all until this runs a second time (#977).
        // The window exists because the job is still running (isx vm start, under a previous
        // install, or just logged in) when a reinstall reaches it; a finished one-shot job is
        // already gone by the time bootout is asked to remove it, so this returns at once then.
        waitForJobUnloaded("gui/" + uid + "/" + VM_LABEL);
        if (!runQuiet("launchctl", "bootstrap", "gui/" + uid, vmPlistFile().toString())) {
            // waitForJobUnloaded gave up before launchd finished removing the old job: bootstrap
            // fails the same "Input/output error" this all exists to avoid. Warn rather than fail
            // outright — the proxy agent below is unaffected and still worth installing — but say
            // so, since nothing else here would otherwise reveal that no VM login agent is loaded.
            BuildOutput.stepBreak();
            BuildOutput.warn("VM service did not reinstall cleanly.", "Run 'isx doctor' or retry 'isx init'.");
        } else {
            BuildOutput.stepDone();
        }

        // Only the proxy agent needs the separate binary — the VM agent runs `isx vm start`, so it
        // is installed above regardless. Bailing before this point would leave a user with no
        // isx-proxy also without a VM that starts on login, a failure unrelated to the proxy.
        var proxyBin = proxyBinaryNextTo(isxPath);
        if (!proxyBinaryIsUsable(proxyBin)) {
            // A plist from an earlier install may still be loaded and respawning. Leaving it would
            // mean `isx init` reported the problem while launchd kept re-running it.
            haltUnusableMacOsServiceLocked();
            return false;
        }
        // Configure bridge DNS now (from Terminal) so the launchd proxy service
        // doesn't need to reach the Incus VM API at startup — macOS Sequoia blocks
        // local network access from ad-hoc-signed binaries under launchd.
        BuildOutput.stepStart("Configuring bridge DNS");
        try {
            ProxyConfig.configureBridgeDns(new IncusClient(), SpawnConfig.load());
            BuildOutput.stepDone();
        } catch (Exception e) {
            BuildOutput.stepBreak();
            BuildOutput.warn("Could not configure bridge DNS: " + e.getMessage(),
                    "Is the VM running? The proxy will retry DNS at startup.");
        }

        BuildOutput.stepStart("Installing proxy service");
        // Written only now, right before it is loaded: restartLocked takes a plist that is
        // current on disk for the one the job runs, so the two must not be left apart by an
        // install that stops in between.
        try {
            Files.writeString(proxyPlistFile(), generateProxyPlist(proxyBin));
        } catch (IOException e) {
            BuildOutput.stepBreak();
            BuildOutput.error("Failed to write proxy plist: " + e.getMessage());
            return false;
        }
        // A reinstall over a loaded job is a restart with a new plist, with the same teardown
        // to wait for (see LaunchdJob).
        var outcome = proxyJob().restart(true, BuildOutput.bound());
        if (outcome.launched()) BuildOutput.stepDone();
        else BuildOutput.stepBreak();

        if (outcome == LaunchdJob.Outcome.STILL_UNLOADING) {
            // Named by LaunchdJob itself, not guessed from isActive(): that alone cannot tell
            // this apart from a restart that loaded fine and is merely unhealthy, which isActive()
            // would read the same way and report as generic "not responding" below (review on
            // #916) — wrong, since nothing here was actually loaded when the install started.
            ProxyLog.info("Service installed, restart did not complete");
            BuildOutput.error("Services installed, but the previous job had not finished unloading.",
                    "It may not come back on its own; check: isx proxy status");
            return false;
        }
        if (isActive()) {
            if (awaitStarted(configuredStartWaitSeconds())) {
                if (!answeringIsTheService()) {
                    ProxyLog.info("Service installed, but another proxy holds its ports");
                    return false;
                }
                ProxyLog.info("Service installed and running");
                BuildOutput.step("Services installed and running.");
                return true;
            }
            ProxyLog.info("Service installed but not healthy");
            BuildOutput.error("Services installed but proxy is not responding.", notResponding());
            return false;
        } else {
            ProxyLog.info("Service installed (waiting for VM)");
            BuildOutput.step("Services installed (proxy will start when VM is ready).");
            return true;
        }
    }

    /** Removes both launch agents; false when a plist stays, which launchd loads again at the next login. */
    private static boolean uninstallMacOs() {
        var uid = getUid();
        runQuiet("launchctl", "bootout", "gui/" + uid, proxyPlistFile().toString());
        runQuiet("launchctl", "bootout", "gui/" + uid, vmPlistFile().toString());
        var removed = true;
        for (var plist : List.of(proxyPlistFile(), vmPlistFile())) {
            try {
                Files.deleteIfExists(plist);
            } catch (IOException e) {
                BuildOutput.error("Failed to remove the launch agent " + plist + ": " + e.getMessage());
                removed = false;
            }
        }
        if (removed) BuildOutput.step("macOS services uninstalled.");
        return removed;
    }

    private static LaunchdJob proxyJob() {
        return new LaunchdJob(ProxyService::launchctl, ProxyService::pauseBetweenPolls,
                "gui/" + getUid(), PROXY_LABEL, proxyPlistFile());
    }

    private static LaunchdJob.Result launchctl(String... args) {
        var command = new ArrayList<String>();
        command.add("launchctl");
        command.addAll(List.of(args));
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes());
            return new LaunchdJob.Result(process.waitFor(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LaunchdJob.Result(-1, "interrupted");
        } catch (IOException e) {
            return new LaunchdJob.Result(-1, e.getMessage());
        }
    }

    private static void pauseBetweenPolls() {
        try {
            Thread.sleep(LaunchdJob.POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String getUid() {
        try {
            var pb = new ProcessBuilder("id", "-u");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var uid = new String(process.getInputStream().readAllBytes()).strip();
            if (uid.isEmpty() || !uid.chars().allMatch(Character::isDigit)) {
                throw new RuntimeException("unexpected id -u output: " + uid);
            }
            return uid;
        } catch (Exception e) {
            throw new RuntimeException("Cannot determine current UID — launchd service install/uninstall requires a valid UID", e);
        }
    }

    static boolean runQuiet(String... command) {
        return commandRunner.test(command);
    }

    /**
     * What {@link #runQuiet} does. Replaceable so a test of a path that must not touch the service
     * fails by recording a {@code systemctl} call rather than making it against whatever service
     * is installed on the machine running the test.
     */
    private static volatile java.util.function.Predicate<String[]> commandRunner = ProxyService::runProcess;

    /** Swaps in a test double for {@link #runQuiet}, returning the one it replaced. */
    static java.util.function.Predicate<String[]> replaceCommandRunner(java.util.function.Predicate<String[]> runner) {
        var previous = commandRunner;
        commandRunner = runner;
        return previous;
    }

    private static boolean runProcess(String... command) {
        // sudo may ask for a password on the tty, which a live step line would be drawn over.
        if (command.length > 0 && command[0].equals("sudo")) BuildOutput.releaseTerminal();
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            var process = pb.start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
