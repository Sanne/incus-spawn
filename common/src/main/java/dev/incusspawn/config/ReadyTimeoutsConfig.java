package dev.incusspawn.config;

import dev.incusspawn.incus.IncusClient.ReadyTimeouts;

import java.time.Duration;
import java.util.Map;

/**
 * {@code ready-timeouts:} in config.yaml: how long {@code waitForReady} waits
 * for a container or VM to answer exec. Durations are written {@code 30s},
 * {@code 2m}, {@code 5m}; a blank or unreadable value means the built-in default.
 *
 * <pre>{@code
 * ready-timeouts:
 *   container: 60s
 *   vm: 3m
 * }</pre>
 *
 * <p>Read from the raw YAML value rather than bound by Jackson: a mistyped section
 * must not fail the whole config.yaml.
 */
public record ReadyTimeoutsConfig(String container, String vm) {

    static ReadyTimeoutsConfig of(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> map)) return new ReadyTimeoutsConfig(null, null);
        return new ReadyTimeoutsConfig(scalar(map.get("container")), scalar(map.get("vm")));
    }

    private static String scalar(Object value) {
        return value instanceof String || value instanceof Number ? String.valueOf(value) : null;
    }

    /** Load from config.yaml; returns {@link ReadyTimeouts#DEFAULT} when absent or unreadable. */
    public static ReadyTimeouts fromConfig() {
        try {
            var config = SpawnConfig.load().readyTimeouts();
            return config != null ? config.resolve() : ReadyTimeouts.DEFAULT;
        } catch (Exception e) {
            return ReadyTimeouts.DEFAULT;
        }
    }

    /** Resolve to a {@link ReadyTimeouts}, filling in defaults for unset values. */
    public ReadyTimeouts resolve() {
        var d = ReadyTimeouts.DEFAULT;
        return new ReadyTimeouts(
                or(ArtifactCacheConfig.parseDuration(container), d.container()),
                or(ArtifactCacheConfig.parseDuration(vm), d.vm()),
                d.agentFailureGrace(),
                d.consoleCheckInterval(),
                d.gatedProbeInterval());
    }

    private static Duration or(Duration parsed, Duration fallback) {
        return parsed != null ? parsed : fallback;
    }
}
