package dev.incusspawn.incus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class UfwCheck {

    public static final Path BEFORE_RULES = Path.of("/etc/ufw/before.rules");
    static final String MARKER_NAT_BEGIN = "# BEGIN incus-spawn-nat";
    static final String MARKER_NAT_END = "# END incus-spawn-nat";
    static final String MARKER_FWD_BEGIN = "# BEGIN incus-spawn-forward";
    static final String MARKER_FWD_END = "# END incus-spawn-forward";

    private UfwCheck() {}

    // ---- Subprocess methods ----

    public static boolean isInstalled() {
        try {
            var pb = new ProcessBuilder("which", "ufw");
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
        // "ufw status" requires root, so non-root callers (branch, build, doctor)
        // would always get false. "systemctl is-active ufw" is wrong too — the
        // oneshot service is "active" on Ubuntu even when UFW is disabled.
        // /etc/ufw/ufw.conf is world-readable and reflects the real state.
        try {
            var content = java.nio.file.Files.readString(Path.of("/etc/ufw/ufw.conf"));
            return content.lines().anyMatch(l -> l.strip().equals("ENABLED=yes"));
        } catch (IOException e) {
            return false;
        }
    }

    public static String detectDiagnostic() {
        try {
            if (!isInstalled() || isActive()) return null;
            return "Possible cause: UFW is installed but not active.\n"
                    + "Firewall rules (masquerading, FORWARD, PREROUTING redirect) are not\n"
                    + "loaded into the kernel, so containers cannot reach the internet.\n\n"
                    + "Fix:\n"
                    + "  sudo ufw enable\n"
                    + "  isx init";
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean warnIfNotRunning() {
        try {
            var diagnostic = detectDiagnostic();
            if (diagnostic == null) return false;
            System.err.println("\033[33m" + "─".repeat(60) + "\033[0m");
            System.err.println("\033[1;33mUFW is not active:\033[0m");
            System.err.println(diagnostic);
            System.err.println("\033[33m" + "─".repeat(60) + "\033[0m");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Pure parsing/checking methods ----

    public static boolean hasNatBlock(String beforeRulesContent) {
        return beforeRulesContent.contains(MARKER_NAT_BEGIN)
                && beforeRulesContent.contains(MARKER_NAT_END);
    }

    public static boolean hasForwardBlock(String beforeRulesContent) {
        return beforeRulesContent.contains(MARKER_FWD_BEGIN)
                && beforeRulesContent.contains(MARKER_FWD_END);
    }

    public static boolean hasPreRoutingRedirect(String beforeRulesContent, int mitmPort) {
        if (!hasNatBlock(beforeRulesContent)) return false;
        var block = extractBlock(beforeRulesContent, MARKER_NAT_BEGIN, MARKER_NAT_END);
        return block.contains("PREROUTING")
                && block.contains("incusbr0")
                && block.contains("-d ")
                && block.contains("--dport 443")
                && block.contains("REDIRECT")
                && block.contains("--to-port " + mitmPort);
    }

    public static boolean hasMasquerade(String beforeRulesContent, String subnet) {
        if (!hasNatBlock(beforeRulesContent)) return false;
        var block = extractBlock(beforeRulesContent, MARKER_NAT_BEGIN, MARKER_NAT_END);
        return block.contains("MASQUERADE") && block.contains(subnet);
    }

    public static boolean hasForwardRules(String beforeRulesContent) {
        if (!hasForwardBlock(beforeRulesContent)) return false;
        var block = extractBlock(beforeRulesContent, MARKER_FWD_BEGIN, MARKER_FWD_END);
        return block.contains("-i incusbr0") && block.contains("-o incusbr0");
    }

    // ---- Generation methods ----

    public static String generateNatBlock(String gatewayIp, String subnet,
                                          int containerPort, int mitmPort) {
        var sb = new StringBuilder();
        sb.append(MARKER_NAT_BEGIN).append('\n');
        sb.append("*nat\n");
        sb.append(":PREROUTING ACCEPT [0:0]\n");
        sb.append(":POSTROUTING ACCEPT [0:0]\n");
        sb.append("-A POSTROUTING -s ").append(subnet)
                .append(" ! -d ").append(subnet).append(" -j MASQUERADE\n");
        sb.append("-A PREROUTING -i incusbr0 -d ").append(gatewayIp)
                .append(" -p tcp --dport ").append(containerPort)
                .append(" -j REDIRECT --to-port ").append(mitmPort).append('\n');
        sb.append("COMMIT\n");
        sb.append(MARKER_NAT_END);
        return sb.toString();
    }

    public static String generateNatBlockWithoutRedirect(String subnet) {
        var sb = new StringBuilder();
        sb.append(MARKER_NAT_BEGIN).append('\n');
        sb.append("*nat\n");
        sb.append(":PREROUTING ACCEPT [0:0]\n");
        sb.append(":POSTROUTING ACCEPT [0:0]\n");
        sb.append("-A POSTROUTING -s ").append(subnet)
                .append(" ! -d ").append(subnet).append(" -j MASQUERADE\n");
        sb.append("COMMIT\n");
        sb.append(MARKER_NAT_END);
        return sb.toString();
    }

    public static String generateFilterInsert() {
        return MARKER_FWD_BEGIN + "\n"
                + "-A ufw-before-forward -i incusbr0 -j ACCEPT\n"
                + "-A ufw-before-forward -o incusbr0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT\n"
                + MARKER_FWD_END;
    }

    // ---- Insertion methods ----

    public static String insertNatBlock(String existingContent, String natBlock) {
        if (hasNatBlock(existingContent)) {
            return replaceBlock(existingContent, MARKER_NAT_BEGIN, MARKER_NAT_END, natBlock);
        }
        // Insert before the first *filter line
        int filterIdx = existingContent.indexOf("*filter");
        if (filterIdx < 0) {
            return existingContent + "\n" + natBlock + "\n";
        }
        return existingContent.substring(0, filterIdx)
                + natBlock + "\n\n"
                + existingContent.substring(filterIdx);
    }

    public static String insertFilterRules(String existingContent, String filterInsert) {
        if (hasForwardBlock(existingContent)) {
            return replaceBlock(existingContent, MARKER_FWD_BEGIN, MARKER_FWD_END, filterInsert);
        }
        // Insert before the first COMMIT in the *filter section
        int filterIdx = existingContent.indexOf("*filter");
        if (filterIdx < 0) {
            return existingContent + "\n" + filterInsert + "\n";
        }
        int commitIdx = existingContent.indexOf("COMMIT", filterIdx);
        if (commitIdx < 0) {
            return existingContent + "\n" + filterInsert + "\n";
        }
        return existingContent.substring(0, commitIdx)
                + filterInsert + "\n"
                + existingContent.substring(commitIdx);
    }

    // ---- Helpers ----

    static String extractBlock(String content, String beginMarker, String endMarker) {
        int start = content.indexOf(beginMarker);
        int end = content.indexOf(endMarker);
        if (start < 0 || end < 0 || end <= start) return "";
        return content.substring(start, end + endMarker.length());
    }

    private static String replaceBlock(String content, String beginMarker,
                                       String endMarker, String replacement) {
        int start = content.indexOf(beginMarker);
        int end = content.indexOf(endMarker);
        if (start < 0 || end < 0) return content;
        end += endMarker.length();
        // Consume trailing newline if present
        if (end < content.length() && content.charAt(end) == '\n') {
            end++;
        }
        return content.substring(0, start) + replacement + "\n"
                + content.substring(end);
    }

    public static String readBeforeRules() {
        try {
            var pb = new ProcessBuilder("sudo", "cat", BEFORE_RULES.toString());
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) return "";
            return output;
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
