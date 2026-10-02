package dev.incusspawn.proxy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The intercepted-domain overrides isx keeps in the bridge's {@code raw.dnsmasq}, as text.
 * <p>
 * Each intercepted domain gets {@code address=/<domain>/<gateway>} for A and
 * {@code local=/<domain>/}, which makes dnsmasq answer every other record type for it locally
 * with no data. Without the {@code local=} line dnsmasq forwards AAAA (and HTTPS/SVCB, which can
 * carry address hints) upstream, and the domain's real IPv6 addresses bypass the proxy. Released
 * versions closed that hole with {@code address=/<domain>/::} instead, but {@code ::} is the
 * wildcard address, which clients treat as loopback: a nested isx refused every intercepted
 * domain as host-local, and an IPv6-preferring client connected to itself (#814). The pair gives
 * NODATA for AAAA on every dnsmasq from 2.80 to 2.92.
 * <p>
 * The lines live between {@link #BEGIN} and {@link #END}, so a rewrite replaces exactly what isx
 * wrote and nothing a user added. That matters more than it did for {@code ::}: a
 * {@code local=/<domain>/} line without its {@code address=} partner answers NXDOMAIN for the
 * whole domain, so one left behind would make that domain unresolvable. Configurations from
 * before the block are recognised by their {@code address=/<domain>/::} lines (every released
 * version wrote one per domain), and both of that domain's {@code address=} lines are migrated.
 */
public final class BridgeDns {

    static final String BEGIN = "# BEGIN incus-spawn intercepted domains (rewritten by isx)";
    static final String END = "# END incus-spawn intercepted domains";

    private static final String LEGACY_WILDCARD = "/::";

    private BridgeDns() {
    }

    /** {@code existing} with isx's block holding exactly {@code domains}, and nothing else changed. */
    static String render(String existing, Set<String> domains, String gatewayIp) {
        var lines = new ArrayList<>(unmanaged(existing));
        lines.add(BEGIN);
        domains.stream().sorted().forEach(d -> {
            lines.add(addressLine(d, gatewayIp));
            lines.add("local=/" + d + "/");
        });
        lines.add(END);
        return String.join("\n", lines);
    }

    /** {@code existing} without isx's overrides, current or legacy. */
    static String withoutOverrides(String existing) {
        return String.join("\n", unmanaged(existing));
    }

    /**
     * What stands between {@code config} and intercepting exactly {@code domains} at
     * {@code gatewayIp}, the bridge's current address.
     */
    public static Status status(String config, Set<String> domains, String gatewayIp) {
        var managed = managedLines(config);
        var addressed = managed.stream()
                .map(BridgeDns::addressDomain)
                .filter(d -> d != null)
                .collect(Collectors.toSet());
        // An override for an address the bridge no longer has sends the domain where the proxy
        // does not listen (#840): it is there, but it is as wrong as a missing one.
        var misaddressed = managed.stream()
                .filter(l -> addressDomain(l) != null && !l.equals(addressLine(addressDomain(l), gatewayIp)))
                .map(BridgeDns::addressDomain)
                .collect(Collectors.toSet());
        var legacy = legacyDomains(config);
        var missing = domains.stream()
                .filter(d -> !legacy.contains(d))
                .filter(d -> !addressed.contains(d) || !managed.contains("local=/" + d + "/"))
                .sorted()
                .toList();
        var stale = domains.stream()
                .filter(d -> !legacy.contains(d) && !missing.contains(d) && misaddressed.contains(d))
                .sorted()
                .toList();
        return new Status(missing, stale, gatewayIp, legacy.stream().sorted().toList());
    }

    /** The addresses isx's block sends its domains to: the gateway of the proxy that wrote it. */
    public static Set<String> overrideAddresses(String config) {
        return managedLines(config).stream()
                .filter(l -> addressDomain(l) != null)
                .map(BridgeDns::addressTarget)
                .collect(Collectors.toSet());
    }

    /** The stripped lines inside isx's block. */
    private static Set<String> managedLines(String config) {
        var managed = new HashSet<String>();
        var inBlock = false;
        for (var line : config.lines().toList()) {
            var l = line.strip();
            if (l.equals(BEGIN)) inBlock = true;
            else if (l.equals(END)) inBlock = false;
            else if (inBlock) managed.add(l);
        }
        return managed;
    }

    /**
     * @param missing   domains without both of their lines in isx's block, legacy ones aside
     * @param stale     domains whose {@code address=} line points anywhere but {@code gatewayIp}
     * @param gatewayIp the bridge's address, which every override should point at
     * @param legacy    domains still answered with {@code ::} by a pre-#814 {@code address=} line
     */
    public record Status(List<String> missing, List<String> stale, String gatewayIp, List<String> legacy) {
        public boolean complete() {
            return missing.isEmpty() && stale.isEmpty() && legacy.isEmpty();
        }

        public String describe() {
            var parts = new ArrayList<String>();
            if (!missing.isEmpty()) parts.add("missing: " + String.join(", ", missing));
            if (!stale.isEmpty()) {
                parts.add("pointing at another address than the gateway " + gatewayIp + ": "
                        + String.join(", ", stale));
            }
            if (!legacy.isEmpty()) {
                parts.add("AAAA answered with :: (the pre-#814 layout) for: " + String.join(", ", legacy));
            }
            return String.join("; ", parts);
        }
    }

    /**
     * The lines outside isx's block, minus the legacy overrides it wrote before there was one.
     * An unterminated block runs to the end: everything after {@link #BEGIN} was written by isx.
     */
    private static List<String> unmanaged(String existing) {
        var legacy = legacyDomains(existing);
        var kept = new ArrayList<String>();
        var inBlock = false;
        for (var line : existing.lines().toList()) {
            var l = line.strip();
            if (l.equals(BEGIN)) {
                inBlock = true;
            } else if (l.equals(END)) {
                inBlock = false;
            } else if (!inBlock && !legacy.contains(addressDomain(l))) {
                kept.add(line);
            }
        }
        return kept;
    }

    /** Domains with an {@code address=/<domain>/::} line outside isx's block: the old layout. */
    private static Set<String> legacyDomains(String config) {
        var legacy = new HashSet<String>();
        var inBlock = false;
        for (var line : config.lines().toList()) {
            var l = line.strip();
            if (l.equals(BEGIN)) inBlock = true;
            else if (l.equals(END)) inBlock = false;
            else if (!inBlock && l.endsWith(LEGACY_WILDCARD) && addressDomain(l) != null) legacy.add(addressDomain(l));
        }
        return legacy;
    }

    private static String addressLine(String domain, String ip) {
        return "address=/" + domain + "/" + ip;
    }

    /** The {@code <ip>} of an {@code address=/<domain>/<ip>} line. */
    private static String addressTarget(String line) {
        return line.substring(line.lastIndexOf('/') + 1);
    }

    /** The domain of an {@code address=/<domain>/<ip>} line, or null for any other line. */
    private static String addressDomain(String line) {
        if (!line.startsWith("address=/")) return null;
        var end = line.lastIndexOf('/');
        return end > "address=/".length() ? line.substring("address=/".length(), end) : null;
    }
}
