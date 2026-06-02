package dev.incusspawn.incus;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public final class BridgeSubnetCheck {

    public record ConflictResult(boolean conflictDetected, String conflictingRoute,
                                  String oldSubnet, String newSubnet) {}

    private static final List<String> CANDIDATE_SUBNETS = List.of(
            "172.20.0.1/24", "172.21.0.1/24", "172.22.0.1/24", "172.23.0.1/24",
            "172.24.0.1/24", "172.25.0.1/24", "172.26.0.1/24", "172.27.0.1/24",
            "172.28.0.1/24", "172.29.0.1/24", "172.30.0.1/24");

    private BridgeSubnetCheck() {}

    public static String resolveBridgeCidr(IncusClient incus) {
        return incus.networkConfigGet("incusbr0", "ipv4.address");
    }

    public static List<String> getHostRoutes() {
        try {
            var pb = new ProcessBuilder("ip", "route", "show");
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
            var tokens = line.strip().split("\\s+");
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
                var tokens = line.strip().split("\\s+");
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

    public static ConflictResult detectAndFix(IncusClient incus) {
        var bridgeCidr = resolveBridgeCidr(incus);
        var routes = getHostRoutes();
        var conflict = findConflictingRoute(bridgeCidr, routes);
        if (conflict == null) {
            return new ConflictResult(false, null, bridgeCidr, null);
        }
        var newSubnet = findNonConflictingSubnet(bridgeCidr, routes);
        if (newSubnet != null) {
            incus.networkConfigSet("incusbr0", "ipv4.address", newSubnet);
        }
        return new ConflictResult(true, conflict, bridgeCidr, newSubnet);
    }

    public static boolean warnIfConflict(IncusClient incus) {
        try {
            var diagnostic = detectConflictDiagnostic(incus);
            if (diagnostic == null) return false;
            System.err.println("\033[33m" + "─".repeat(60) + "\033[0m");
            System.err.println("\033[1;33mBridge subnet conflict detected:\033[0m");
            System.err.println(diagnostic);
            System.err.println("\033[33m" + "─".repeat(60) + "\033[0m");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String detectConflictDiagnostic(IncusClient incus) {
        try {
            var bridgeCidr = resolveBridgeCidr(incus);
            var routes = getHostRoutes();
            var conflict = findConflictingRoute(bridgeCidr, routes);
            if (conflict == null) return null;
            return "Possible cause: the Incus bridge subnet (" + bridgeCidr
                    + ") overlaps with a host route:\n  " + conflict
                    + "\n\nDNS queries to the bridge gateway are likely being routed"
                    + " through the VPN instead of reaching the bridge."
                    + "\n\nFix: run 'isx init' to auto-detect and reconfigure the bridge subnet,"
                    + "\nor manually run:"
                    + "\n  incus network set incusbr0 ipv4.address 172.20.0.1/24";
        } catch (Exception e) {
            return null;
        }
    }
}
