package dev.incusspawn.incus;

import dev.incusspawn.util.BuildOutput;

import java.io.IOException;

public final class FirewalldCheck {

    private FirewalldCheck() {}

    public static boolean isInstalled() {
        try {
            var pb = new ProcessBuilder("which", "firewall-cmd");
            pb.redirectErrorStream(true);
            var process = pb.start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static boolean isActive() {
        try {
            // systemctl is-active does not require root, unlike firewall-cmd --state
            // which needs polkit authorization and fails with exit 253 as a normal user
            var pb = new ProcessBuilder("systemctl", "is-active", "firewalld");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            return process.waitFor() == 0 && "active".equals(output);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static String detectDiagnostic() {
        try {
            if (!isInstalled() || isActive()) return null;
            return "Possible cause: firewalld is installed but not running.\n"
                    + "Firewall rules (masquerading, FORWARD, PREROUTING redirect) are not\n"
                    + "loaded into the kernel, so containers cannot reach the internet.\n\n"
                    + "Fix:\n"
                    + "  sudo systemctl enable --now firewalld\n"
                    + "  isx init";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether a line matches the PREROUTING redirect rule pattern (incusbr0, port 443 → mitmPort).
     * Works for both {@code firewall-cmd --direct --get-all-rules} output and persistent
     * {@code /etc/firewalld/direct.xml} element text.
     */
    public static boolean isRedirectRule(String line, int mitmPort) {
        return line.contains("PREROUTING")
                && line.contains("incusbr0")
                && containsToken(line, "--dport", "443")
                && line.contains("REDIRECT")
                && containsToken(line, "--to-port", String.valueOf(mitmPort));
    }

    private static boolean containsToken(String line, String flag, String value) {
        var token = flag + " " + value;
        int idx = line.indexOf(token);
        if (idx < 0) return false;
        int end = idx + token.length();
        return end >= line.length() || !Character.isDigit(line.charAt(end));
    }

    public static boolean isPreRoutingRulePresent(String firewalldOutput, int mitmPort, String gatewayIp) {
        for (var line : firewalldOutput.split("\n")) {
            if (line.contains("nat") && isRedirectRule(line, mitmPort)
                    && line.contains("-d " + gatewayIp)) {
                return true;
            }
        }
        return false;
    }

    /** Extract the gateway IP from an existing PREROUTING redirect rule, or null if none found. */
    public static String extractRedirectGatewayIp(String firewalldOutput, int mitmPort) {
        for (var line : firewalldOutput.split("\n")) {
            if (line.contains("nat") && isRedirectRule(line, mitmPort)
                    && line.contains("-d ")) {
                int idx = line.indexOf("-d ");
                if (idx >= 0) {
                    var rest = line.substring(idx + 3).strip();
                    int end = rest.indexOf(' ');
                    return end > 0 ? rest.substring(0, end) : rest;
                }
            }
        }
        return null;
    }

    public static boolean isForwardRulePresent(String firewalldOutput, String interfaceFlag, String interfaceName) {
        for (var line : firewalldOutput.split("\n")) {
            if (line.contains("FORWARD")
                    && line.contains(interfaceFlag + " " + interfaceName)
                    && line.contains("ACCEPT")) {
                return true;
            }
        }
        return false;
    }

    public static boolean warnIfNotRunning() {
        try {
            var diagnostic = detectDiagnostic();
            if (diagnostic == null) return false;
            BuildOutput.warnBanner("firewalld is not running:", diagnostic);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
