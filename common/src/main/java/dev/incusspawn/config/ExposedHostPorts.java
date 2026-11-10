package dev.incusspawn.config;

import dev.incusspawn.proxy.ProxyConfig;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code exposed-host-ports:} in config.yaml (#2117): TCP ports of this host's loopback that every
 * instance with network reaches at {@link #HOST_NAME}{@code :<port>}, relayed byte for byte by the
 * proxy.
 *
 * <pre>
 * exposed-host-ports: [11434]
 * </pre>
 *
 * <p>Opt-in only here, like {@code host-routes:}: the relay reaches host state outside any project
 * and has no caller identity (on macOS every container reaches the proxy from the VM's one address),
 * so no template, tool or project-local definition can add a port (design/proxy.md "Exposed host
 * ports"). Kept as the raw YAML value, so a mistyped key never fails the whole config.yaml. A
 * value that is not a plain list of integers drops the whole key; within one, each port is
 * checked on its own and dropped with a warning naming it, so a typo never exposes another port.
 */
public final class ExposedHostPorts {

    /**
     * The name instances reach the exposed ports at. Under {@code .internal}, which ICANN reserves
     * for private use, so no real host answers it; reserved, so no host route can take it.
     */
    public static final String HOST_NAME = "host.isx.internal";

    /**
     * The ports isx itself serves where the relay listens: the container-facing 443, the MITM and
     * health ports, and DNS. Exposing one would shadow isx's own listener, or be shadowed by it.
     */
    static final List<Integer> RESERVED = List.of(ProxyConfig.CONTAINER_FACING_PORT,
            ProxyConfig.DEFAULT_MITM_PORT, ProxyConfig.DEFAULT_HEALTH_PORT, 53);

    private static final ExposedHostPorts NONE = new ExposedHostPorts(null);

    private final Object raw;

    private ExposedHostPorts(Object raw) {
        this.raw = raw;
    }

    /** The key as written, null when config.yaml has none. */
    static ExposedHostPorts of(Object raw) {
        return raw == null ? NONE : new ExposedHostPorts(raw);
    }

    /**
     * The ports that pass every check, in the order written, each once; empty when there is no
     * key, or when it is not a plain list of integers.
     */
    public List<Integer> ports(Consumer<String> warn) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list) || !list.stream().allMatch(ExposedHostPorts::isInteger)) {
            warn.accept("Ignoring exposed-host-ports: in config.yaml (expected a list of port numbers,"
                    + " such as exposed-host-ports: [11434]; no ranges, maps or host:port)");
            return List.of();
        }
        var ports = new LinkedHashSet<Integer>();
        for (var value : list) {
            var number = new BigInteger(value.toString());
            if (number.signum() <= 0 || number.compareTo(BigInteger.valueOf(65535)) > 0) {
                warn.accept(ignoring(value) + ": a port is a number from 1 to 65535");
                continue;
            }
            var port = number.intValue();
            if (RESERVED.contains(port)) {
                warn.accept(ignoring(value) + ": isx serves it on the address the proxy listens on");
            } else if (!ports.add(port)) {
                warn.accept(ignoring(value) + ": it is listed more than once");
            }
        }
        return List.copyOf(ports);
    }

    /** An integral number as YAML gives it; a fraction or a quoted number is not one. */
    private static boolean isInteger(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger;
    }

    private static String ignoring(Object port) {
        return "Ignoring exposed host port " + port + " in config.yaml";
    }

    /** Whether {@code name} is {@link #HOST_NAME} or lies under it, which no host route may take. */
    public static boolean isReserved(String name) {
        return name.equals(HOST_NAME) || HostRoutes.isUnder(name, HOST_NAME);
    }
}
