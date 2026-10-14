package dev.incusspawn.config;

import java.time.Duration;
import java.util.Map;

/**
 * {@code proxy:} in config.yaml: settings for the proxy service.
 * Currently holds only the install timeout -- how long {@code isx proxy install}
 * waits for the proxy to become healthy after starting the service.
 *
 * <pre>{@code
 * proxy:
 *   install-timeout: 15s
 * }</pre>
 *
 * <p>Read from the raw YAML value rather than bound through jjq: a mistyped section
 * must not fail the whole config.yaml.
 */
public record ProxyServiceConfig(String installTimeout) {

    static final int DEFAULT_INSTALL_TIMEOUT_SECONDS = 5;

    static ProxyServiceConfig of(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> map)) return new ProxyServiceConfig(null);
        return new ProxyServiceConfig(scalar(map.get("install-timeout")));
    }

    private static String scalar(Object value) {
        return value instanceof String || value instanceof Number ? String.valueOf(value) : null;
    }

    /**
     * The install timeout in seconds, falling back to {@link #DEFAULT_INSTALL_TIMEOUT_SECONDS}
     * when unset or unreadable.
     */
    public int installTimeoutSeconds() {
        var parsed = ArtifactCacheConfig.parseDuration(installTimeout);
        if (parsed == null) return DEFAULT_INSTALL_TIMEOUT_SECONDS;
        long seconds = parsed.toSeconds();
        if (seconds <= 0) return DEFAULT_INSTALL_TIMEOUT_SECONDS;
        if (seconds > Integer.MAX_VALUE) return DEFAULT_INSTALL_TIMEOUT_SECONDS;
        return (int) seconds;
    }

    /** Load from config.yaml; returns {@link #DEFAULT_INSTALL_TIMEOUT_SECONDS} when absent or unreadable. */
    public static int installTimeoutFromConfig() {
        try {
            var config = SpawnConfig.load().proxyService();
            return config != null ? config.installTimeoutSeconds() : DEFAULT_INSTALL_TIMEOUT_SECONDS;
        } catch (Exception e) {
            return DEFAULT_INSTALL_TIMEOUT_SECONDS;
        }
    }
}
