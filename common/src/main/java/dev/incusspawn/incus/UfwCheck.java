package dev.incusspawn.incus;

import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.HostPath;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class UfwCheck {

    public static final Path BEFORE_RULES = Path.of("/etc/ufw/before.rules");
    public static final String MARKER_NAT_BEGIN = "# BEGIN incus-spawn-nat";
    public static final String MARKER_NAT_END = "# END incus-spawn-nat";
    static final String MARKER_FWD_BEGIN = "# BEGIN incus-spawn-forward";
    static final String MARKER_FWD_END = "# END incus-spawn-forward";
    /** A line that is exactly {@code *filter} or {@code COMMIT}, trailing blanks aside: never a comment naming it. */
    static final Pattern FILTER_LINE = Pattern.compile("^\\*filter[ \\t\\r]*$", Pattern.MULTILINE);
    static final Pattern COMMIT_LINE = Pattern.compile("^COMMIT[ \\t\\r]*$", Pattern.MULTILINE);

    private UfwCheck() {}

    // ---- Subprocess methods ----

    public static boolean isInstalled() {
        return HostPath.isOnPath("ufw");
    }

    public static boolean isActive() {
        try {
            return probeActive();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Like {@link #isActive()}, but a read that was cut short throws rather than reading as
     * inactive. A config that is missing or that this user may not open is an answer: not active.
     */
    static boolean probeActive() throws IOException {
        return probeActive(Path.of("/etc/ufw/ufw.conf"));
    }

    static boolean probeActive(Path ufwConf) throws IOException {
        // "ufw status" requires root, so non-root callers (branch, build, doctor)
        // would always get false. "systemctl is-active ufw" is wrong too — the
        // oneshot service is "active" on Ubuntu even when UFW is disabled.
        // /etc/ufw/ufw.conf is world-readable and reflects the real state.
        String content;
        try {
            content = Files.readString(ufwConf);
        } catch (NoSuchFileException | AccessDeniedException e) {
            return false;
        }
        return content.lines().anyMatch(l -> l.strip().equals("ENABLED=yes"));
    }

    static final FirewallDetector.Stopped STOPPED = new FirewallDetector.Stopped(
            "UFW is not active:",
            "Possible cause: UFW is installed but not active.\n"
            + "Firewall rules (masquerading, FORWARD, PREROUTING redirect) are not\n"
            + "loaded into the kernel, so containers cannot reach the internet.\n\n"
            + "Fix:\n"
            + "  sudo ufw enable\n"
            + "  isx init");

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

    public static boolean hasPreRoutingRedirect(String beforeRulesContent, int mitmPort, String gatewayIp) {
        if (!hasNatBlock(beforeRulesContent)) return false;
        var block = extractBlock(beforeRulesContent, MARKER_NAT_BEGIN, MARKER_NAT_END);
        return block.contains("PREROUTING")
                && block.contains("incusbr0")
                && block.contains("-d " + gatewayIp)
                && block.contains("--dport 443")
                && block.contains("REDIRECT")
                && block.contains("--to-port " + mitmPort);
    }

    public static String extractRedirectGatewayIp(String beforeRulesContent, int mitmPort) {
        if (!hasNatBlock(beforeRulesContent)) return null;
        var block = extractBlock(beforeRulesContent, MARKER_NAT_BEGIN, MARKER_NAT_END);
        for (var line : block.split("\n")) {
            if (line.contains("PREROUTING") && line.contains("incusbr0")
                    && line.contains("-d ") && line.contains("--dport 443")
                    && line.contains("REDIRECT") && line.contains("--to-port " + mitmPort)) {
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

    public static boolean hasMasquerade(String beforeRulesContent, String subnet) {
        if (!hasNatBlock(beforeRulesContent)) return false;
        var block = extractBlock(beforeRulesContent, MARKER_NAT_BEGIN, MARKER_NAT_END);
        return block.contains("MASQUERADE") && block.contains(subnet);
    }

    public static boolean hasForwardRules(String beforeRulesContent) {
        if (!hasForwardBlock(beforeRulesContent) || forwardBlockMisplaced(beforeRulesContent)) return false;
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
        int filterIdx = lineIndex(existingContent, FILTER_LINE, 0);
        if (filterIdx < 0) {
            return existingContent + "\n" + natBlock + "\n";
        }
        return existingContent.substring(0, filterIdx)
                + natBlock + "\n\n"
                + existingContent.substring(filterIdx);
    }

    public static String insertFilterRules(String existingContent, String filterInsert) {
        if (hasForwardBlock(existingContent)) {
            if (!forwardBlockMisplaced(existingContent)) {
                return replaceBlock(existingContent, MARKER_FWD_BEGIN, MARKER_FWD_END, filterInsert);
            }
            // Written into a line by an older isx (#1932): take it out, which also rejoins that line
            existingContent = removeBlock(existingContent, MARKER_FWD_BEGIN, MARKER_FWD_END);
        }
        // Insert before the COMMIT line of the *filter section, never a comment naming it
        int filterIdx = lineIndex(existingContent, FILTER_LINE, 0);
        if (filterIdx < 0) {
            return existingContent + "\n" + filterInsert + "\n";
        }
        int commitIdx = lineIndex(existingContent, COMMIT_LINE, filterIdx);
        if (commitIdx < 0) {
            return existingContent + "\n" + filterInsert + "\n";
        }
        return existingContent.substring(0, commitIdx)
                + filterInsert + "\n"
                + existingContent.substring(commitIdx);
    }

    // ---- Helpers ----

    /** Where a line {@code line} matches starts, at or after {@code from}. */
    static int lineIndex(String content, Pattern line, int from) {
        var matcher = line.matcher(content);
        return matcher.find(from) ? matcher.start() : -1;
    }

    /** A forward block that does not start its own line, as {@code isx init} once wrote into ufw's stock comment. */
    public static boolean forwardBlockMisplaced(String content) {
        int start = content.indexOf(MARKER_FWD_BEGIN);
        return start > 0 && content.charAt(start - 1) != '\n';
    }

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
        if (end < content.length() && content.charAt(end) == '\n') end++;
        return content.substring(0, start) + replacement + "\n"
                + content.substring(end);
    }

    public static String removeBlock(String content, String beginMarker, String endMarker) {
        int start = content.indexOf(beginMarker);
        int end = content.indexOf(endMarker);
        if (start < 0 || end < 0) return content;
        end += endMarker.length();
        if (end < content.length() && content.charAt(end) == '\n') end++;
        return content.substring(0, start) + content.substring(end);
    }

    public static String deriveSubnetFromNatBlock(String beforeRules) {
        var block = extractBlock(beforeRules, MARKER_NAT_BEGIN, MARKER_NAT_END);
        if (block.isEmpty()) return null;
        for (var line : block.split("\n")) {
            if (line.contains("MASQUERADE") && line.contains("-s ")) {
                int idx = line.indexOf("-s ") + 3;
                int end = line.indexOf(' ', idx);
                if (end > idx) return line.substring(idx, end);
            }
        }
        return null;
    }

    public static String readBeforeRules() {
        try {
            dev.incusspawn.util.Privilege.check("sudo", "cat", BEFORE_RULES.toString());
            BuildOutput.releaseTerminal(); // sudo may prompt on the tty
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
