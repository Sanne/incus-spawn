package dev.incusspawn.proxy;

import dev.incusspawn.util.BuildOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.incus.IncusClient;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ProxyHealthCheck {

    public enum ProxyStatus {
        RUNNING,
        WAITING_FOR_DNS,
        NOT_RUNNING,
        STALE_DNS,
        STALE_GATEWAY
    }

    /**
     * What {@code /health} reports. {@code pid} is -1 when absent: older proxies don't send it,
     * and a proxy only sends it to callers on the host.
     */
    public record ProxyInfo(String version, String gitSha, String runtime, String caFingerprint,
                            boolean configDrifted, boolean dnsConfigured, String authError,
                            long pid) {
        public ProxyInfo(String version, String gitSha, String runtime, String caFingerprint,
                         boolean configDrifted, boolean dnsConfigured, String authError) {
            this(version, gitSha, runtime, caFingerprint, configDrifted, dnsConfigured, authError, -1);
        }

        public boolean isLegacy() { return version == null || version.isEmpty(); }
        public boolean hasAuthError() { return authError != null && !authError.isEmpty(); }
        public String authRemediationHint() {
            return authError != null && authError.contains("gcloud") ? "gcloud auth login" : "isx init";
        }
    }

    private record HealthResult(boolean healthy, boolean dnsConfigured) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProxyHealthCheck() {}

    /**
     * The running proxy's PID as it reports it in {@code /health}, or -1 if it doesn't (an
     * older proxy) or can't be reached. One local HTTP request, where finding the PID by its
     * listening port means {@code fuser} scanning every process on the host.
     */
    public static long reportedProxyPid() {
        var addr = resolveHealthAddress();
        if (addr == null) return -1;
        var info = fetchProxyInfo(addr, 500);
        return info == null ? -1 : info.pid();
    }

    /** The IP to query for health checks: localhost on macOS, bridge gateway on Linux. */
    public static String healthAddress(IncusClient incus) {
        return dev.incusspawn.Platform.isMacOS()
                ? "127.0.0.1" : ProxyConfig.resolveGatewayIp(incus);
    }

    /** {@link #healthAddress(IncusClient)} for a proxy that already knows its gateway. */
    public static String healthAddress(String gatewayIp) {
        return dev.incusspawn.Platform.isMacOS() ? "127.0.0.1" : gatewayIp;
    }

    private record CacheEntry(IncusClient client, ProxyStatus status, long timestamp) {}
    private static volatile CacheEntry cache;
    private static final long CACHE_TTL_MS = 2000;

    public static ProxyStatus check(IncusClient incus) {
        var entry = cache;
        if (entry != null && entry.client == incus
                && (System.currentTimeMillis() - entry.timestamp) < CACHE_TTL_MS) {
            return entry.status;
        }
        var result = checkUncached(incus);
        cache = new CacheEntry(incus, result, System.currentTimeMillis());
        return result;
    }

    public static void invalidateCache() {
        cache = null;
    }

    private static String resolveHealthAddress() {
        try {
            return healthAddress(new IncusClient());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Poll the health endpoint until the proxy responds or the timeout expires.
     * Returns true immediately if the health address cannot be determined.
     *
     * @param maxWaitSeconds maximum time to poll; 0 for a single immediate check
     */
    public static boolean awaitHealthy(int maxWaitSeconds) {
        var addr = resolveHealthAddress();
        if (addr == null) {
            System.err.println("Could not determine proxy health address; skipping health check.");
            return true;
        }
        if (isHealthy(addr)) return true;
        if (maxWaitSeconds <= 0) return false;
        for (int i = 0; i < maxWaitSeconds * 2; i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (isHealthy(addr)) return true;
        }
        return false;
    }

    private static ProxyStatus checkUncached(IncusClient incus) {
        if (dev.incusspawn.Platform.isMacOS()) {
            var result = checkHealth("127.0.0.1");
            if (result.healthy()) {
                return result.dnsConfigured() ? ProxyStatus.RUNNING : ProxyStatus.WAITING_FOR_DNS;
            }
        }
        var gatewayIp = ProxyConfig.resolveGatewayIp(incus);
        var result = checkHealth(gatewayIp);
        if (result.healthy()) {
            return result.dnsConfigured() ? ProxyStatus.RUNNING : ProxyStatus.WAITING_FOR_DNS;
        }
        var dnsOverrides = ProxyConfig.getDnsOverrides(incus);
        if (!dnsOverrides.isEmpty() && dnsOverrides.contains("address=/")) {
            return listensOnFormerGateway(dnsOverrides, gatewayIp)
                    ? ProxyStatus.STALE_GATEWAY : ProxyStatus.STALE_DNS;
        }
        return ProxyStatus.NOT_RUNNING;
    }

    /**
     * Whether a proxy still holds the health port on the address the overrides point at, when
     * the host no longer has that address (#919). Probing it cannot tell: once the bridge drops
     * the address nothing answers there, though the socket bound to it lives on. An address the
     * host still has is a proxy started on it on purpose ({@code --gateway-ip}, #933), which a
     * restart would not move.
     */
    private static boolean listensOnFormerGateway(String dnsOverrides, String gatewayIp) {
        var procNet = Path.of("/proc/net");
        return BridgeDns.overrideAddresses(dnsOverrides).stream()
                .filter(ip -> !ip.equals(gatewayIp))
                .anyMatch(ip -> listensOn(ip, ProxyConfig.DEFAULT_HEALTH_PORT, procNet) && !isHostAddress(ip));
    }

    private static boolean isHostAddress(String ip) {
        try {
            return java.net.NetworkInterface.getByInetAddress(InetAddress.ofLiteral(ip)) != null;
        } catch (Exception e) {
            // Unknown: keep the weaker STALE_DNS diagnosis rather than advise a restart.
            return true;
        }
    }

    /**
     * Whether a socket on this host listens on IPv4 {@code ip}:{@code port}, read from
     * {@code procNet}'s {@code tcp} and {@code tcp6} (where Java's dual-stack sockets bind
     * {@code ::ffff:<ip>}). The kernel prints each address word in host byte order.
     */
    static boolean listensOn(String ip, int port, Path procNet) {
        byte[] bytes;
        try {
            bytes = InetAddress.ofLiteral(ip).getAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (bytes.length != 4) return false;
        var v4 = hexWord(bytes);
        var portHex = String.format(":%04X", port);
        return hasListener(procNet.resolve("tcp"), v4 + portHex)
                || hasListener(procNet.resolve("tcp6"), V4_MAPPED_PREFIX + v4 + portHex);
    }

    private static final String TCP_LISTEN = "0A";
    /** The first three words of {@code ::ffff:a.b.c.d} as {@code tcp6} prints them. */
    private static final String V4_MAPPED_PREFIX =
            "0000000000000000" + hexWord(new byte[] {0, 0, (byte) 0xff, (byte) 0xff});

    private static String hexWord(byte[] bytes) {
        return String.format("%08X", ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).getInt());
    }

    private static boolean hasListener(Path table, String localAddress) {
        try (var lines = Files.lines(table)) {
            return lines.map(l -> l.strip().split("\\s+"))
                    .anyMatch(f -> f.length > 3 && f[1].equals(localAddress) && f[3].equals(TCP_LISTEN));
        } catch (Exception e) {
            return false;
        }
    }

    private static HealthResult checkHealth(String addr) {
        try {
            var url = URI.create("http://" + addr + ":" + ProxyConfig.DEFAULT_HEALTH_PORT + "/health").toURL();
            var conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return new HealthResult(false, false);
            var body = new String(conn.getInputStream().readAllBytes());
            var info = parseProxyInfo(body);
            return new HealthResult(true, info.dnsConfigured());
        } catch (Exception e) {
            return new HealthResult(false, false);
        }
    }

    static boolean isHealthy(String gatewayIp) {
        return isHealthy(gatewayIp, ProxyConfig.DEFAULT_HEALTH_PORT);
    }

    static boolean isHealthy(String gatewayIp, int port) {
        try {
            var url = URI.create("http://" + gatewayIp + ":" + port + "/health").toURL();
            var conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            conn.setRequestMethod("GET");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public static ProxyInfo fetchProxyInfo(String gatewayIp) {
        return fetchProxyInfo(gatewayIp, 2000);
    }

    public static ProxyInfo fetchProxyInfo(String gatewayIp, int timeoutMs) {
        try {
            var answer = get(gatewayIp, ProxyConfig.DEFAULT_HEALTH_PORT, "/health", timeoutMs);
            return answer.status() == 200 ? parseProxyInfo(answer.body()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** What the health port answered a GET; the body only for a 200. */
    record Answer(int status, String body) {}

    static Answer get(String address, int port, String path, int timeoutMs) throws java.io.IOException {
        var conn = (HttpURLConnection) URI.create("http://" + address + ":" + port + path).toURL().openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestMethod("GET");
        try {
            var status = conn.getResponseCode();
            return new Answer(status, status == 200
                    ? new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) : "");
        } finally {
            conn.disconnect();
        }
    }

    static ProxyInfo parseProxyInfo(String json) {
        try {
            var node = JSON.readTree(json);
            var dnsNode = node.get("dnsConfigured");
            var dnsConfigured = dnsNode == null ? true : (dnsNode.isBoolean() && dnsNode.asBoolean());
            var driftNode = node.get("configDrifted");
            var configDrifted = driftNode != null && driftNode.isBoolean() && driftNode.asBoolean();
            return new ProxyInfo(
                    textOrEmpty(node, "version"),
                    textOrEmpty(node, "gitSha"),
                    textOrEmpty(node, "runtime"),
                    textOrEmpty(node, "caFingerprint"),
                    configDrifted,
                    dnsConfigured,
                    textOrEmpty(node, "authError"),
                    node.path("pid").canConvertToLong() ? node.path("pid").asLong() : -1);
        } catch (Exception e) {
            return new ProxyInfo("", "", "", "", false, true, "");
        }
    }

    public static java.util.List<String> checkDrift(ProxyInfo proxyInfo) {
        var drifts = new java.util.ArrayList<String>();
        var version = checkVersionDrift(proxyInfo);
        if (!version.isEmpty()) drifts.add(version);
        var tool = checkToolProxyDrift(proxyInfo);
        if (!tool.isEmpty()) drifts.add(tool);
        return drifts;
    }

    static String checkVersionDrift(ProxyInfo proxyInfo) {
        if (proxyInfo == null) return "";
        if (proxyInfo.isLegacy()) {
            return "The proxy is running a pre-versioning build. Restart recommended.";
        }
        var cliInfo = BuildInfo.instance();
        if (!cliInfo.version().equals(proxyInfo.version())
                || !cliInfo.gitSha().equals(proxyInfo.gitSha())) {
            return "Proxy is " + proxyInfo.version() + " (" + shortSha(proxyInfo.gitSha()) + ")"
                    + ", CLI is " + cliInfo.version() + " (" + shortSha(cliInfo.gitSha()) + ").";
        }
        return "";
    }

    /**
     * The drift {@code proxyInfo} shows, and why restarting the proxy service cannot clear it
     * ({@code futileReason}), or null when it can. Every consumer reads this one report, so
     * none can forget to ask whether a restart would help.
     */
    public record DriftReport(java.util.List<String> drifts, String futileReason) {
        public boolean isEmpty() { return drifts.isEmpty(); }
        public boolean restartHelps() { return !drifts.isEmpty() && futileReason == null; }
    }

    /**
     * Assess {@code proxyInfo}'s drift, including whether a restart would clear it.
     * <p>
     * The service runs the <em>installed</em> {@code isx-proxy}, not a build matching the CLI.
     * Once the service has been restarted onto that binary for this CLI and the version still
     * differs, restarting again changes nothing but cuts every instance's connection; without
     * this check every command did exactly that (#798). See {@link DriftRestartRecord}.
     * Config drift is always cleared by a restart, so it is never futile, and only pure version
     * drift reads the record.
     */
    public static DriftReport assessDrift(ProxyInfo proxyInfo) {
        return assessDrift(proxyInfo, DriftRestartRecord::restartAlreadyTried);
    }

    static DriftReport assessDrift(ProxyInfo proxyInfo,
                                   java.util.function.Supplier<DriftRestartRecord.Stamp> restartAlreadyTried) {
        var drifts = checkDrift(proxyInfo);
        if (drifts.isEmpty() || proxyInfo.isLegacy() || proxyInfo.configDrifted()) {
            return new DriftReport(drifts, null);
        }
        var tried = restartAlreadyTried.get();
        if (tried == null) return new DriftReport(drifts, null);
        return new DriftReport(drifts, "The proxy was already restarted onto the installed "
                + tried.proxyBin() + ", which is still a different build, so restarting again"
                + " cannot help. Install isx and isx-proxy from the same build; the next command"
                + " then restarts the proxy onto it.");
    }

    static String checkToolProxyDrift(ProxyInfo proxyInfo) {
        if (proxyInfo == null || proxyInfo.isLegacy()) return "";
        if (!proxyInfo.configDrifted()) return "";
        return "(config has changed since the proxy started)";
    }

    public static String formatError(ProxyStatus status) {
        var separator = BuildOutput.styled(BuildOutput.YELLOW, "─".repeat(60));
        return switch (status) {
            case STALE_DNS -> separator + "\n"
                    + bold("The MITM proxy is not running, but DNS overrides are\n"
                    + "still active from a previous session.") + "\n\n"
                    + "Intercepted domains (Maven repos, GitHub, Docker registries)\n"
                    + "are resolving to the gateway where nothing is listening.\n\n"
                    + "Start the proxy to restore connectivity:\n"
                    + "  " + bold("isx proxy start") + "\n\n"
                    + "Then re-run this command.\n"
                    + separator;
            case STALE_GATEWAY -> separator + "\n"
                    + bold("The MITM proxy is running, but on an old address of\n"
                    + "the Incus bridge.") + "\n\n"
                    + "The address of incusbr0 changed after the proxy started, so\n"
                    + "intercepted domains resolve to an address nothing answers on.\n\n"
                    + "Restart the proxy so it binds the bridge's current address:\n"
                    + "  " + bold("isx proxy stop && isx proxy start") + "\n\n"
                    + "Then re-run this command.\n"
                    + separator;
            case NOT_RUNNING -> separator + "\n"
                    + bold("The MITM proxy is not running.") + "\n\n"
                    + "The proxy provides authentication for Claude, GitHub,\n"
                    + "and caches Maven/Docker artifacts during builds.\n\n"
                    + "Start it in a separate terminal:\n"
                    + "  " + bold("isx proxy start") + "\n\n"
                    + "Or install it as a service (auto-starts on boot):\n"
                    + "  " + bold("isx init") + "\n\n"
                    + "Then re-run this command.\n"
                    + separator;
            case WAITING_FOR_DNS -> separator + "\n"
                    + bold("The MITM proxy is running but DNS overrides are not\n"
                    + "yet configured.") + "\n\n"
                    + "The proxy is waiting for the VM to become reachable so it\n"
                    + "can configure bridge DNS. Containers cannot reach intercepted\n"
                    + "domains until this completes.\n\n"
                    + "Check VM status:  " + bold("isx vm status") + "\n"
                    + "Proxy status:     " + bold("isx proxy status") + "\n"
                    + separator;
            case RUNNING -> "";
        };
    }

    private static String bold(String text) {
        return BuildOutput.styled(BuildOutput.BOLD, text);
    }

    public static void requireProxy(IncusClient incus) {
        var status = check(incus);
        if (status == ProxyStatus.RUNNING) {
            warnIfDrifted(incus);
            return;
        }
        if (status == ProxyStatus.WAITING_FOR_DNS) {
            if (waitForDns(incus)) { warnIfDrifted(incus); return; }
            System.err.println(formatError(ProxyStatus.WAITING_FOR_DNS));
            System.exit(1);
        }
        if (serviceRestartCanHelp(status) && tryAutoRestart(incus)) {
            if (waitForDns(incus)) { warnIfDrifted(incus); return; }
        }
        System.err.println(formatError(check(incus)));
        System.exit(1);
    }

    public static boolean checkOrWarn(IncusClient incus) {
        var status = check(incus);
        if (status == ProxyStatus.RUNNING) {
            warnIfDrifted(incus);
            return true;
        }
        if (status == ProxyStatus.WAITING_FOR_DNS) {
            if (waitForDns(incus)) { warnIfDrifted(incus); return true; }
            System.err.println(formatError(ProxyStatus.WAITING_FOR_DNS));
            return false;
        }
        if (serviceRestartCanHelp(status) && tryAutoRestart(incus)) {
            if (waitForDns(incus)) { warnIfDrifted(incus); return true; }
        }
        System.err.println(formatError(check(incus)));
        return false;
    }

    /**
     * Whether restarting the proxy service can clear {@code status}. A proxy on an old bridge
     * address that the service does not run is a foreground one: starting the service would put
     * a second proxy beside it, and the stale one would write its old address back into the
     * bridge DNS on its next reload.
     */
    public static boolean serviceRestartCanHelp(ProxyStatus status) {
        return serviceRestartCanHelp(status, ProxyService.isInstalled(), ProxyService::isActive);
    }

    static boolean serviceRestartCanHelp(ProxyStatus status, boolean installed,
                                         java.util.function.BooleanSupplier active) {
        return installed && (status != ProxyStatus.STALE_GATEWAY || active.getAsBoolean());
    }

    public static boolean tryAutoRestart(IncusClient incus) {
        return tryAutoRestart(incus, System.err::println);
    }

    public static boolean tryAutoRestart(IncusClient incus, java.util.function.Consumer<String> log) {
        if (!ProxyService.isInstalled()) return false;
        var addr = healthAddress(incus);
        log.accept("Proxy is not reachable, restarting service...");
        // A restart refused until init runs has said so through log, and has nothing to wait for.
        if (!ProxyService.restartIfUnhealthy(addr, log) && !Environment.hasBeenInitialized()) return false;
        for (int i = 0; i < 30; i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (isHealthy(addr)) {
                invalidateCache();
                log.accept("Proxy service restarted successfully.");
                return true;
            }
        }
        if (ProxyService.failedWithConfigError()) {
            log.accept("Proxy service failed to start due to a configuration problem "
                    + "(exit " + ProxyService.EXIT_CONFIG + ").");
            log.accept("  systemctl --user status " + Environment.PROXY_SERVICE_NAME);
        } else {
            log.accept("Proxy service did not become healthy after restart.");
        }
        return false;
    }

    public static boolean waitForDns(IncusClient incus) {
        return waitForDns(incus, System.err::println);
    }

    public static boolean waitForDns(IncusClient incus, java.util.function.Consumer<String> log) {
        var addr = healthAddress(incus);
        var result = checkHealth(addr);
        if (result.healthy() && result.dnsConfigured()) return true;
        if (!result.healthy()) return false;
        log.accept("Waiting for proxy DNS configuration...");
        for (int i = 0; i < 120; i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            result = checkHealth(addr);
            if (!result.healthy()) return false;
            if (result.dnsConfigured()) {
                invalidateCache();
                log.accept("Proxy DNS overrides configured.");
                return true;
            }
        }
        log.accept("Proxy DNS overrides were not configured within 60 seconds.");
        return false;
    }

    static void warnIfDrifted(IncusClient incus) {
        try {
            var info = fetchProxyInfo(healthAddress(incus));
            var report = assessDrift(info);
            if (report.isEmpty()) return;
            var sep = BuildOutput.styled(BuildOutput.YELLOW, "─".repeat(60));
            System.err.println(sep);
            System.err.println(BuildOutput.styled(BuildOutput.BOLD + BuildOutput.YELLOW, "Proxy drift detected:")
                    + " " + String.join(" ", report.drifts()));
            if (ProxyService.isActive()) {
                if (report.futileReason() != null) {
                    System.err.println(report.futileReason());
                } else {
                    // Not a bare restart: the service files may still exec a binary from a
                    // previous installation, and restarting that would leave the drift in place.
                    ProxyService.reinstallIfChanged(incus, info);
                }
            } else {
                System.err.println("Restart the proxy to pick up changes:");
                System.err.println("  " + bold("isx proxy stop && isx proxy start"));
                if (!info.configDrifted()) {
                    // A foreground proxy leaves no record of what it runs, so this cannot tell
                    // whether a restart would help; say what to do if it does not.
                    System.err.println("If the drift remains afterwards, install isx and isx-proxy"
                            + " from the same build.");
                }
            }
            System.err.println(sep);
        } catch (Exception ignored) {}
    }

    private static String textOrEmpty(JsonNode node, String field) {
        var child = node.get(field);
        return child != null && child.isTextual() ? child.asText() : "";
    }

    private static String shortSha(String sha) {
        return sha != null && sha.length() > 7 ? sha.substring(0, 7) : (sha != null ? sha : "");
    }
}
