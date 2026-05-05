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
        NOT_RUNNING,
        STALE_DNS
    }

    public record ProxyInfo(String version, String gitSha, String caFingerprint) {
        public boolean isLegacy() { return version == null || version.isEmpty(); }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProxyHealthCheck() {}

    public static ProxyStatus check(IncusClient incus) {
        var gatewayIp = MitmProxy.resolveGatewayIp(incus);
        if (isHealthy(gatewayIp)) {
            return ProxyStatus.RUNNING;
        }
        var dnsOverrides = MitmProxy.getDnsOverrides(incus);
        if (!dnsOverrides.isEmpty() && dnsOverrides.contains("address=/")) {
            return ProxyStatus.STALE_DNS;
        }
        return ProxyStatus.NOT_RUNNING;
    }

    static boolean isHealthy(String gatewayIp) {
        return isHealthy(gatewayIp, MitmProxy.DEFAULT_HEALTH_PORT);
    }

    static boolean isHealthy(String gatewayIp, int port) {
        try {
            var url = URI.create("http://" + gatewayIp + ":" + port + "/health").toURL();
            var conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
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
            return new ProxyInfo(
                    textOrEmpty(node, "version"),
                    textOrEmpty(node, "gitSha"),
                    textOrEmpty(node, "caFingerprint"));
        } catch (Exception e) {
            return new ProxyInfo("", "", "");
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
            case RUNNING -> "";
        };
    }

    public static void requireProxy(IncusClient incus) {
        var status = check(incus);
        if (status == ProxyStatus.RUNNING) {
            warnIfDrifted(incus);
            return;
        }
        System.err.println(formatError(status));
        System.exit(1);
    }

    public static boolean checkOrWarn(IncusClient incus) {
        var status = check(incus);
        if (status == ProxyStatus.RUNNING) {
            warnIfDrifted(incus);
            return true;
        }
        System.err.println(formatError(status));
        return false;
    }

    static void warnIfDrifted(IncusClient incus) {
        try {
            var gatewayIp = MitmProxy.resolveGatewayIp(incus);
            var info = fetchProxyInfo(gatewayIp);
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
