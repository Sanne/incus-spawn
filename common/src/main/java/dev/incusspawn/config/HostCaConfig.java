package dev.incusspawn.config;

import java.util.Map;

/**
 * {@code host-ca:} in config.yaml: whether to propagate host CA anchors into
 * containers. Scans the platform's default anchor directory.
 *
 * <pre>{@code
 * host-ca:
 *   propagate: true
 * }</pre>
 *
 * <p>Read from the raw YAML value rather than bound by Jackson: a mistyped section
 * must not fail the whole config.yaml.
 */
public record HostCaConfig(boolean propagate) {

    static final HostCaConfig DISABLED = new HostCaConfig(false);

    static HostCaConfig of(Object raw) {
        if (raw == null) return DISABLED;
        if (raw instanceof Boolean b) return new HostCaConfig(b);
        if (!(raw instanceof Map<?, ?> map)) return DISABLED;

        var propagate = false;
        var prop = map.get("propagate");
        if (prop instanceof Boolean b) propagate = b;
        else if ("true".equals(String.valueOf(prop))) propagate = true;

        return new HostCaConfig(propagate);
    }
}
