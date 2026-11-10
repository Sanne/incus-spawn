package dev.incusspawn.config;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@code host-routes:} in config.yaml (#936): names an instance reaches on 443 that the proxy
 * answers by forwarding to a controller on this host, over plain HTTP, with the caller's
 * identity stamped as headers the instance cannot forge.
 *
 * <pre>
 * host-routes:
 *   work.isx.internal:
 *     target: 127.0.0.1:7070       # localhost, 127.0.0.0/8 or [::1], with a port
 *     templates: [tpl-isx-agent]   # required: only instances of these leaf templates
 * </pre>
 *
 * <p>Opt-in only here: a route reaches host state outside any project, so a template, least of
 * all a project-local one, can never declare it (design/proxy.md "Host routes"). Kept as the raw YAML
 * value, like {@code artifact-cache:}: a mistyped section must not fail the whole config.yaml.
 * Each entry is checked on its own and dropped with a warning naming it when it is wrong, so one
 * typo never widens what a route admits -- an unknown key drops the entry rather than ignoring a
 * misspelt {@code templates:}.
 *
 * <p>Keyed by name, with the target and the filter as fields, so that a per-instance grant
 * (#935) can be added beside {@code templates:} rather than as a second route table.
 */
public final class HostRoutes {
    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");

    /**
     * One route.
     *
     * @param templates the leaf templates whose instances may call it, never empty: there is no
     *                  route for every instance
     */
    public record Route(String name, String host, int port, List<String> templates) {
        public Route {
            templates = List.copyOf(templates);
            if (templates.isEmpty()) throw new IllegalArgumentException("host route " + name + " lists no templates");
        }

        /**
         * Whether an instance of {@code template} may call this route: one of the listed
         * templates, and never an untrusted one (project-local, or a failed, unfinished or project
         * build, or a copy of one), whatever the list says.
         */
        public boolean admits(String template, boolean untrusted) {
            return !untrusted && templates.contains(template);
        }
    }

    /** Every route name lies under it: ICANN reserves it for private use, so no real host answers. */
    public static final String SUFFIX = ".internal";

    private static final Set<String> KEYS = Set.of("target", "templates");

    private final Object raw;

    private HostRoutes(Object raw) {
        this.raw = raw;
    }

    /** The section as written, null when config.yaml has none. */
    static HostRoutes of(Object raw) {
        return new HostRoutes(raw);
    }

    /**
     * The routes that pass every check, by name, in the order written. A route whose name
     * equals, lies under, or lies above one of {@code interceptedDomains} is dropped: that name
     * already answers as something else. So is one under another route, which answers its
     * subdomains with a 404.
     */
    public Map<String, Route> routes(Set<String> interceptedDomains, Consumer<String> warn) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map)) {
            warn.accept("Ignoring host-routes: in config.yaml (expected a name per route, each with a target:)");
            return Map.of();
        }
        var valid = new ArrayList<Route>();
        map.forEach((key, value) -> {
            var route = route(String.valueOf(key), value, interceptedDomains, warn);
            if (route != null) valid.add(route);
        });
        var routes = new LinkedHashMap<String, Route>();
        for (var route : valid) {
            var above = valid.stream().filter(r -> isUnder(route.name(), r.name())).findFirst();
            if (valid.stream().filter(r -> r.name().equals(route.name())).count() > 1) {
                // Work.isx.internal and work.isx.internal: neither is dropped in favour of the other
                warn.accept(ignoring(route.name()) + ": it is written more than once");
            } else if (above.isPresent()) {
                warn.accept(ignoring(route.name()) + ": it lies under route " + above.get().name());
            } else {
                routes.put(route.name(), route);
            }
        }
        return java.util.Collections.unmodifiableMap(routes);
    }

    private static Route route(String key, Object value, Set<String> interceptedDomains, Consumer<String> warn) {
        var name = key.strip().toLowerCase(Locale.ROOT);
        if (!isRouteName(name)) {
            warn.accept(ignoring(key) + ": a route name is a hostname under " + SUFFIX);
            return null;
        }
        if (ExposedHostPorts.isReserved(name)) {
            warn.accept(ignoring(key) + ": " + ExposedHostPorts.HOST_NAME
                    + " is reserved for exposed-host-ports");
            return null;
        }
        for (var domain : interceptedDomains) {
            if (name.equals(domain) || isUnder(name, domain) || isUnder(domain, name)) {
                warn.accept(ignoring(key) + ": it overlaps " + domain + ", which the proxy already intercepts");
                return null;
            }
        }
        if (!(value instanceof Map<?, ?> fields)) {
            warn.accept(ignoring(key) + ": expected target: under it");
            return null;
        }
        for (var field : fields.keySet()) {
            if (!KEYS.contains(String.valueOf(field))) {
                warn.accept(ignoring(key) + ": unknown key '" + field + "' (expected target: and templates:)");
                return null;
            }
        }
        var target = fields.get("target");
        var hostPort = target instanceof String s ? hostPort(s) : null;
        if (hostPort == null) {
            warn.accept(ignoring(key) + ": target must be host:port on this host's loopback"
                    + " (localhost, 127.0.0.1 or [::1]), with a port from 1 to 65535");
            return null;
        }
        // Required and non-empty: a route admits only the templates its owner named (#936)
        var listed = fields.get("templates");
        if (!(listed instanceof List<?> list) || list.isEmpty()
                || !list.stream().allMatch(t -> t instanceof String s && !s.isBlank())) {
            warn.accept(ignoring(key) + ": templates: is required, listing the templates whose instances may call it"
                    + " (for example templates: [tpl-isx-agent])");
            return null;
        }
        var templates = list.stream().map(t -> ((String) t).strip()).toList();
        return new Route(name, hostPort.host(), hostPort.port(), templates);
    }

    private static String ignoring(String name) {
        return "Ignoring host route '" + name + "' in config.yaml";
    }

    /** A lower-case hostname under {@code .internal}, which the proxy can mint a certificate for. */
    static boolean isRouteName(String name) {
        return name.endsWith(SUFFIX) && dev.incusspawn.proxy.CertStore.isHostname(name);
    }

    /** Whether {@code name} is a subdomain of {@code parent}, at any depth. */
    public static boolean isUnder(String name, String parent) {
        return name.length() > parent.length() + 1 && name.endsWith(parent)
                && name.charAt(name.length() - parent.length() - 1) == '.';
    }

    /**
     * The host and port of a loopback target, or null: {@code localhost} (127.0.0.1), an IPv4 address in
     * 127.0.0.0/8, or {@code [::1]}, never a name to look up, so the route cannot leave the host.
     */
    record Target(String host, int port) {}

    static Target hostPort(String target) {
        var t = target.strip();
        String host;
        String port;
        if (t.startsWith("[")) {
            var close = t.indexOf("]:");
            if (close < 0) return null;
            host = t.substring(1, close);
            port = t.substring(close + 2);
        } else {
            var colon = t.indexOf(':');
            if (colon < 0 || colon != t.lastIndexOf(':')) return null;
            host = t.substring(0, colon);
            port = t.substring(colon + 1);
        }
        if (!isLoopback(host) || !PORT.matcher(port).matches()) return null;
        var number = Integer.parseInt(port);
        if (number < 1 || number > 65535) return null;
        // Stored canonical: a literal the connecting side does not read as one (127.1, 2130706433)
        // would be looked up as a name. Nor is localhost: a hosts file could point it anywhere
        if (host.equalsIgnoreCase("localhost")) return new Target("127.0.0.1", number);
        // ::1 is the one IPv6 loopback address; a mapped IPv4 one is an Inet4Address
        var address = InetAddress.ofLiteral(host);
        return new Target(address instanceof java.net.Inet6Address ? "::1" : address.getHostAddress(), number);
    }

    private static boolean isLoopback(String host) {
        if (host.equalsIgnoreCase("localhost")) return true;
        // A scope (::1%lo) names an interface, which the connecting side may read as a name
        if (host.contains("%")) return false;
        try {
            // A literal only: ofLiteral never looks a name up
            return InetAddress.ofLiteral(host).isLoopbackAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
