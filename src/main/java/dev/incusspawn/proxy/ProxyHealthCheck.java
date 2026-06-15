package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.incus.IncusClient;

import java.net.HttpURLConnection;
import java.net.URI;

public final class ProxyHealthCheck {

    public enum ProxyStatus {
        RUNNING,
        WAITING_FOR_DNS,
        NOT_RUNNING,
        STALE_DNS
    }

    public record ProxyInfo(String version, String gitSha, String runtime, String caFingerprint,
                            boolean dnsConfigured) {
        public boolean isLegacy() { return version == null || version.isEmpty(); }
    }

    private record HealthResult(boolean healthy, boolean dnsConfigured) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProxyHealthCheck() {}

    /** The IP to query for health checks: localhost on macOS, bridge gateway on Linux. */
    public static String healthAddress(IncusClient incus) {
        return dev.incusspawn.Environment.isMacOS()
                ? "127.0.0.1" : MitmProxy.resolveGatewayIp(incus);
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

    private static ProxyStatus checkUncached(IncusClient incus) {
        if (dev.incusspawn.Environment.isMacOS()) {
            var result = checkHealth("127.0.0.1");
            if (result.healthy()) {
                return result.dnsConfigured() ? ProxyStatus.RUNNING : ProxyStatus.WAITING_FOR_DNS;
            }
        }
        var gatewayIp = MitmProxy.resolveGatewayIp(incus);
        var result = checkHealth(gatewayIp);
        if (result.healthy()) {
            return result.dnsConfigured() ? ProxyStatus.RUNNING : ProxyStatus.WAITING_FOR_DNS;
        }
        var dnsOverrides = MitmProxy.getDnsOverrides(incus);
        if (!dnsOverrides.isEmpty() && dnsOverrides.contains("address=/")) {
            return ProxyStatus.STALE_DNS;
        }
        return ProxyStatus.NOT_RUNNING;
    }

    private static HealthResult checkHealth(String addr) {
        try {
            var url = URI.create("http://" + addr + ":" + MitmProxy.DEFAULT_HEALTH_PORT + "/health").toURL();
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
        return isHealthy(gatewayIp, MitmProxy.DEFAULT_HEALTH_PORT);
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
        try {
            var url = URI.create("http://" + gatewayIp + ":" + MitmProxy.DEFAULT_HEALTH_PORT + "/health").toURL();
            var conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return null;
            var body = new String(conn.getInputStream().readAllBytes());
            return parseProxyInfo(body);
        } catch (Exception e) {
            return null;
        }
    }

    static ProxyInfo parseProxyInfo(String json) {
        try {
            var node = JSON.readTree(json);
            var dnsNode = node.get("dnsConfigured");
            // Default to true only if field is missing (backwards compat with old proxies).
            // Reject malformed values (non-boolean) to catch serialization bugs.
            var dnsConfigured = dnsNode == null ? true : (dnsNode.isBoolean() && dnsNode.asBoolean());
            return new ProxyInfo(
                    textOrEmpty(node, "version"),
                    textOrEmpty(node, "gitSha"),
                    textOrEmpty(node, "runtime"),
                    textOrEmpty(node, "caFingerprint"),
                    dnsConfigured);
        } catch (Exception e) {
            return new ProxyInfo("", "", "", "", true);
        }
    }

    public static String checkVersionDrift(ProxyInfo proxyInfo) {
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

    public static String formatError(ProxyStatus status) {
        var separator = "\033[33m" + "─".repeat(60) + "\033[0m";
        return switch (status) {
            case STALE_DNS -> separator + "\n"
                    + "\033[1mThe MITM proxy is not running, but DNS overrides are\n"
                    + "still active from a previous session.\033[0m\n\n"
                    + "Intercepted domains (Maven repos, GitHub, Docker registries)\n"
                    + "are resolving to the gateway where nothing is listening.\n\n"
                    + "Start the proxy to restore connectivity:\n"
                    + "  \033[1misx proxy start\033[0m\n\n"
                    + "Then re-run this command.\n"
                    + separator;
            case NOT_RUNNING -> separator + "\n"
                    + "\033[1mThe MITM proxy is not running.\033[0m\n\n"
                    + "The proxy provides authentication for Claude, GitHub,\n"
                    + "and caches Maven/Docker artifacts during builds.\n\n"
                    + "Start it in a separate terminal:\n"
                    + "  \033[1misx proxy start\033[0m\n\n"
                    + "Or install it as a service (auto-starts on boot):\n"
                    + "  \033[1misx init\033[0m\n\n"
                    + "Then re-run this command.\n"
                    + separator;
            case WAITING_FOR_DNS -> separator + "\n"
                    + "\033[1mThe MITM proxy is running but DNS overrides are not\n"
                    + "yet configured.\033[0m\n\n"
                    + "The proxy is waiting for the VM to become reachable so it\n"
                    + "can configure bridge DNS. Containers cannot reach intercepted\n"
                    + "domains until this completes.\n\n"
                    + "Check VM status:  \033[1misx vm status\033[0m\n"
                    + "Proxy status:     \033[1misx proxy status\033[0m\n"
                    + separator;
            case RUNNING -> "";
        };
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
        if (tryAutoRestart(incus)) {
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
        if (tryAutoRestart(incus)) {
            if (waitForDns(incus)) { warnIfDrifted(incus); return true; }
        }
        System.err.println(formatError(check(incus)));
        return false;
    }

    public static boolean tryAutoRestart(IncusClient incus) {
        if (!ProxyService.isInstalled()) return false;
        System.err.println("Proxy is not running, restarting service...");
        ProxyService.restart();
        var addr = healthAddress(incus);
        for (int i = 0; i < 30; i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (isHealthy(addr)) {
                invalidateCache();
                System.err.println("Proxy service restarted successfully.");
                return true;
            }
        }
        System.err.println("Proxy service did not become healthy after restart.");
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
            var drift = checkVersionDrift(info);
            if (drift.isEmpty()) return;
            var sep = "\033[33m" + "─".repeat(60) + "\033[0m";
            if (ProxyService.isActive()) {
                System.err.println(sep);
                System.err.println("\033[1;33mProxy version drift detected:\033[0m " + drift);
                ProxyService.restart();
                System.err.println(sep);
            } else {
                System.err.println(sep);
                System.err.println("\033[1;33mProxy version drift detected:\033[0m " + drift);
                System.err.println("Restart the proxy to use the current version:");
                System.err.println("  \033[1misx proxy stop && isx proxy start\033[0m");
                System.err.println(sep);
            }
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
