package dev.incusspawn.incus;

import dev.incusspawn.Platform;
import dev.incusspawn.util.BuildOutput;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public final class BridgeSubnetCheck {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public record ConflictResult(boolean conflictDetected, String conflictingRoute,
                                  String oldSubnet, String newSubnet) {}

    private static final List<String> CANDIDATE_SUBNETS = List.of(
            "172.20.0.1/24", "172.21.0.1/24", "172.22.0.1/24", "172.23.0.1/24",
            "172.24.0.1/24", "172.25.0.1/24", "172.26.0.1/24", "172.27.0.1/24",
            "172.28.0.1/24", "172.29.0.1/24", "172.30.0.1/24");

    private BridgeSubnetCheck() {}

    /** The subnets {@link #findNonConflictingSubnet} chooses from, first to last, for a message. */
    public static String candidateRange() {
        return CANDIDATE_SUBNETS.getFirst() + " to " + CANDIDATE_SUBNETS.getLast();
    }

    public static String resolveBridgeCidr(IncusClient incus) {
        return incus.networkConfigGet(BridgeAddress.BRIDGE, "ipv4.address");
    }

    public static List<String> getHostRoutes() {
        try {
            var pb = new ProcessBuilder("ip", "route", "show", "table", "all");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            if (process.exitValue() != 0 || output.isEmpty()) {
                return List.of();
            }
            return Arrays.asList(output.split("\n"));
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    public static String findConflictingRoute(String bridgeCidr, List<String> routeLines) {
        var bridgeNet = CidrUtils.parseCidr(bridgeCidr);
        for (var line : routeLines) {
            if (line.contains("incusbr0")) continue;
            var tokens = WHITESPACE.split(line.strip());
            if (tokens.length == 0) continue;
            var first = tokens[0];
            if ("default".equals(first)) continue;
            if (!first.contains("/") && !first.contains(".")) continue;
            try {
                var routeNet = CidrUtils.parseCidr(first);
                if (CidrUtils.overlaps(bridgeNet, routeNet)) {
                    return line.strip();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public static String findNonConflictingSubnet(String bridgeCidr, List<String> routeLines) {
        var bridgeNet = CidrUtils.parseCidr(bridgeCidr);
        for (var candidate : CANDIDATE_SUBNETS) {
            var candidateNet = CidrUtils.parseCidr(candidate);
            if (candidateNet.network() == bridgeNet.network()
                    && candidateNet.prefixLen() == bridgeNet.prefixLen()) {
                continue;
            }
            boolean conflicts = false;
            for (var line : routeLines) {
                var tokens = WHITESPACE.split(line.strip());
                if (tokens.length == 0) continue;
                var first = tokens[0];
                if ("default".equals(first)) continue;
                if (!first.contains("/") && !first.contains(".")) continue;
                try {
                    var routeNet = CidrUtils.parseCidr(first);
                    if (CidrUtils.overlaps(candidateNet, routeNet)) {
                        conflicts = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!conflicts) return candidate;
        }
        return null;
    }

    /** Whether the bridge overlaps a host route, and the subnet {@link #detectAndFix} would move it to; changes nothing. */
    public static ConflictResult detect(IncusClient incus) {
        return detect(incus, null);
    }

    /** {@link #detect(IncusClient)} against the given route lines, or the host's when {@code null}. */
    public static ConflictResult detect(IncusClient incus, List<String> hostRoutes) {
        var bridgeCidr = resolveBridgeCidr(incus);
        if (bridgeCidr == null || BridgeAddress.parse(bridgeCidr).isEmpty()) {
            return new ConflictResult(false, null, null, null); // no IPv4 address: nothing to overlap
        }
        // An address without a prefix length is a /24, as BridgeAddress reads it, not a single host
        if (!bridgeCidr.contains("/")) bridgeCidr += "/24";
        var routes = hostRoutes != null ? hostRoutes : getHostRoutes();
        var conflict = findConflictingRoute(bridgeCidr, routes);
        if (conflict == null) {
            return new ConflictResult(false, null, bridgeCidr, null);
        }
        return new ConflictResult(true, conflict, bridgeCidr, findNonConflictingSubnet(bridgeCidr, routes));
    }

    public static ConflictResult detectAndFix(IncusClient incus) {
        return detectAndFix(incus, null);
    }

    /** {@link #detectAndFix(IncusClient)} against the given route lines, or the host's when {@code null}. */
    public static ConflictResult detectAndFix(IncusClient incus, List<String> hostRoutes) {
        var result = detect(incus, hostRoutes);
        if (result.newSubnet() != null) {
            incus.networkConfigSet(BridgeAddress.BRIDGE, "ipv4.address", result.newSubnet());
        }
        return result;
    }

    public static boolean warnIfConflict(IncusClient incus) {
        try {
            var diagnostic = detectConflictDiagnostic(incus);
            if (diagnostic == null) return false;
            BuildOutput.warnBanner("Bridge subnet conflict detected:", diagnostic);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Linux only: elsewhere the host's routes are not the VM's and nothing offers to move the
     * bridge, so a warning there would be a dead end (#1202).
     */
    public static String detectConflictDiagnostic(IncusClient incus) {
        if (!Platform.isLinux()) return null;
        try {
            var result = detect(incus);
            if (!result.conflictDetected()) return null;
            return "Possible cause: the Incus bridge subnet (" + result.oldSubnet()
                    + ") overlaps with a host route:\n  " + result.conflictingRoute()
                    + "\n\nDNS queries to the bridge gateway are likely being routed"
                    + " through the VPN instead of reaching the bridge."
                    + "\n\nFix: run 'isx doctor', which offers to move the bridge to a free subnet.";
        } catch (Exception e) {
            return null;
        }
    }
}
