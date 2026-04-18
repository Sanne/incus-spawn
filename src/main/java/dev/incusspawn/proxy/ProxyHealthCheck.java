package dev.incusspawn.proxy;

import dev.incusspawn.incus.IncusClient;

import java.net.HttpURLConnection;
import java.net.URI;

public final class ProxyHealthCheck {

    public enum ProxyStatus {
        RUNNING,
        NOT_RUNNING,
        STALE_DNS
    }

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

    public static void clearStaleDns(IncusClient incus) {
        MitmProxy.clearBridgeDns(incus);
    }

    public static String formatError(ProxyStatus status) {
        var separator = "\033[33m" + "─".repeat(60) + "\033[0m";
        return switch (status) {
            case STALE_DNS -> separator + "\n"
                    + "\033[1mThe MITM proxy is not running, but DNS overrides are\n"
                    + "still active from a previous session.\033[0m\n\n"
                    + "Intercepted domains (Maven repos, GitHub, Docker registries)\n"
                    + "are resolving to the gateway where nothing is listening.\n"
                    + "Stale DNS overrides have been cleared.\n\n"
                    + "Start the proxy in a separate terminal:\n"
                    + "  \033[1misx proxy\033[0m\n\n"
                    + "Then re-run this command.\n"
                    + separator;
            case NOT_RUNNING -> separator + "\n"
                    + "\033[1mThe MITM proxy is not running.\033[0m\n\n"
                    + "The proxy provides authentication for Claude, GitHub,\n"
                    + "and caches Maven/Docker artifacts during builds.\n\n"
                    + "Start it in a separate terminal:\n"
                    + "  \033[1misx proxy\033[0m\n\n"
                    + "Then re-run this command.\n"
                    + separator;
            case RUNNING -> "";
        };
    }

    public static void requireProxy(IncusClient incus) {
        var status = check(incus);
        if (status == ProxyStatus.RUNNING) return;
        if (status == ProxyStatus.STALE_DNS) {
            clearStaleDns(incus);
        }
        System.err.println(formatError(status));
        System.exit(1);
    }

    public static boolean checkOrWarn(IncusClient incus) {
        var status = check(incus);
        if (status == ProxyStatus.RUNNING) return true;
        if (status == ProxyStatus.STALE_DNS) {
            clearStaleDns(incus);
        }
        System.err.println(formatError(status));
        return false;
    }
}
