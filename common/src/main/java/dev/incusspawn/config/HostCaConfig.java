package dev.incusspawn.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code host-ca:} in config.yaml: whether to propagate host CA anchors into
 * containers, and which directories to scan.
 *
 * <pre>{@code
 * host-ca:
 *   propagate: true
 *   paths:
 *     - /etc/pki/ca-trust/source/anchors
 * }</pre>
 *
 * <p>Read from the raw YAML value rather than bound by Jackson: a mistyped section
 * must not fail the whole config.yaml.
 */
public record HostCaConfig(boolean propagate, List<Path> paths) {

    private static final Path DEFAULT_ANCHORS_DIR = Path.of("/etc/pki/ca-trust/source/anchors");

    static final HostCaConfig DISABLED = new HostCaConfig(false, List.of(DEFAULT_ANCHORS_DIR));

    @SuppressWarnings("unchecked")
    static HostCaConfig of(Object raw) {
        if (raw == null) return DISABLED;
        if (!(raw instanceof Map<?, ?> map)) return DISABLED;

        var propagate = false;
        var prop = map.get("propagate");
        if (prop instanceof Boolean b) propagate = b;
        else if ("true".equals(String.valueOf(prop))) propagate = true;

        var paths = List.of(DEFAULT_ANCHORS_DIR);
        var rawPaths = map.get("paths");
        if (rawPaths instanceof List<?> list && !list.isEmpty()) {
            paths = list.stream()
                    .filter(e -> e instanceof String)
                    .map(e -> Path.of((String) e))
                    .toList();
        }

        return new HostCaConfig(propagate, paths);
    }
}
