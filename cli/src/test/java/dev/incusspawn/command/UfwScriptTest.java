package dev.incusspawn.command;

import dev.incusspawn.incus.UfwCheck;
import dev.incusspawn.proxy.ProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The root script edits UFW's {@code before.rules} in shell, since isx cannot read it ahead (#2074):
 * these run its lines over sample files and hold their result to the Java's, {@link UfwCheck}'s,
 * so the script writes exactly what {@code isx init} with sudo would.
 */
class UfwScriptTest {

    private static final String GATEWAY = "10.1.2.1";
    private static final String SUBNET = "10.1.2.0/24";

    private static String stock() throws Exception {
        try (var in = UfwScriptTest.class.getResourceAsStream("/ufw/before.rules.stock")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * What isx init with sudo writes for the firewall step, as {@code configureUfw} does: the NAT
     * block without the redirect unless one already masquerades the subnet, then the FORWARD rules.
     */
    private static String javaFirewall(String content) {
        var nat = UfwCheck.hasMasquerade(content, SUBNET) ? content
                : UfwCheck.insertNatBlock(content, UfwCheck.generateNatBlockWithoutRedirect(SUBNET));
        return UfwCheck.insertFilterRules(nat, UfwCheck.generateFilterInsert());
    }

    /** What it writes for the redirect: the NAT block with it. */
    private static String javaRedirect(String content) {
        return UfwCheck.insertNatBlock(content, UfwCheck.generateNatBlock(GATEWAY, SUBNET,
                ProxyConfig.CONTAINER_FACING_PORT, ProxyConfig.DEFAULT_MITM_PORT));
    }

    record Ran(int exit, String output, String rules) {}

    /**
     * Ubuntu's awk is mawk, Fedora's gawk, macOS's its own: every scenario runs with each one this host has,
     * as the script's own lines would on that host.
     */
    private static final List<String> AWKS = java.util.stream.Stream.of("awk", "gawk", "mawk", "busybox")
            .filter(dev.incusspawn.util.HostPath::isOnPath).toList();

    /** Runs {@code lines} on a copy of {@code content}, with the gateway set and a stand-in ufw whose first {@code reload} reloads fail. */
    private static Ran run(Path dir, List<String> lines, String content, int reload) throws Exception {
        Ran first = null;
        for (var awk : AWKS) {
            var ran = run(dir.resolve(awk), lines, content, reload, awk);
            if (first == null) first = ran;
            else assertEquals(first, ran, "with " + awk);
        }
        assertNotNull(first, "no awk on this host");
        return first;
    }

    private static Ran run(Path dir, List<String> lines, String content, int reload, String awk) throws Exception {
        Files.createDirectories(dir);
        var rules = dir.resolve("before.rules");
        Files.writeString(rules, content);
        var bin = Files.createDirectories(dir.resolve("bin"));
        var ufw = bin.resolve("ufw");
        var count = bin.resolve("reloads");
        Files.writeString(ufw, "#!/bin/sh\necho \"ufw $*\"\n[ \"$1\" = reload ] || exit 0\n"
                + "n=$(( $(cat " + count + " 2>/dev/null || echo 0) + 1 )); echo $n > " + count + "\n"
                + "[ $n -le " + reload + " ] && exit 1\nexit 0\n");
        ufw.toFile().setExecutable(true);
        var awkStandIn = bin.resolve("awk");
        // By its absolute path: a bare "awk" would find this stand-in again and exec itself forever
        var binary = dev.incusspawn.util.HostPath.find(awk);
        Files.writeString(awkStandIn, "#!/bin/sh\nexec '" + binary + "'" + (awk.equals("busybox") ? " awk" : "") + " \"$@\"\n");
        awkStandIn.toFile().setExecutable(true);
        var script = String.join("\n", lines.stream()
                .filter(l -> !l.startsWith("incus network") && !l.startsWith("gw=$(incus"))
                .map(l -> l.equals("rules=" + UfwScript.RULES) ? "rules=" + rules : l).toList());
        var pb = new ProcessBuilder("sh", "-euc", "gw=" + GATEWAY + "\n" + script).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Ran(process.waitFor(), output.replace(rules.toString(), "<rules>"), Files.readString(rules));
    }

    @Test
    void onAStockFileTheFirewallLinesWriteWhatTheRunWould(@TempDir Path dir) throws Exception {
        var ran = run(dir, UfwScript.firewallLines(), stock(), 0);
        assertEquals(0, ran.exit(), ran.output());
        assertEquals(javaFirewall(stock()), ran.rules());
        assertTrue(ran.output().contains("ufw allow in on incusbr0"), ran.output());
    }

    @Test
    void theRedirectLinesThenWriteWhatTheRunWould(@TempDir Path dir) throws Exception {
        var after = javaFirewall(stock());
        var ran = run(dir, UfwScript.redirectLines(), after, 0);
        assertEquals(0, ran.exit(), ran.output());
        assertEquals(javaRedirect(after), ran.rules());
    }

    /** A block isx wrote before, for an old gateway, is replaced where it stands, and running the lines again changes nothing. */
    @Test
    void existingBlocksAreReplacedWhereTheyStand(@TempDir Path dir) throws Exception {
        var old = UfwCheck.insertFilterRules(UfwCheck.insertNatBlock(stock(), UfwCheck.generateNatBlock("10.9.9.1", "10.9.9.0/24",
                ProxyConfig.CONTAINER_FACING_PORT, ProxyConfig.DEFAULT_MITM_PORT)), UfwCheck.generateFilterInsert());
        var ran = run(dir, UfwScript.redirectLines(), old, 0);
        assertEquals(javaRedirect(old), ran.rules());
        var again = run(dir, UfwScript.redirectLines(), ran.rules(), 0);
        assertEquals(ran.rules(), again.rules());
        var firewall = run(dir, UfwScript.firewallLines(), old, 0);
        assertEquals(javaFirewall(old), firewall.rules());
    }

    /** The firewall lines leave a NAT block that masquerades the subnet as it stands: its redirect is not dropped. */
    @Test
    void theFirewallLinesKeepARedirectAlreadyInPlace(@TempDir Path dir) throws Exception {
        var redirected = javaRedirect(stock());
        assertFalse(UfwCheck.hasForwardRules(redirected));
        var ran = run(dir, UfwScript.firewallLines(), redirected, 0);
        assertEquals(0, ran.exit(), ran.output());
        assertEquals(javaFirewall(redirected), ran.rules());
        assertTrue(UfwCheck.hasPreRoutingRedirect(ran.rules(), ProxyConfig.DEFAULT_MITM_PORT, GATEWAY), ran.rules());
    }

    @Test
    void aFileWithoutAFilterSectionGetsTheBlocksAtItsEnd(@TempDir Path dir) throws Exception {
        var content = "# a file of someone's own\n-A something\n";
        var ran = run(dir, UfwScript.firewallLines(), content, 0);
        assertEquals(javaFirewall(content), ran.rules());
    }

    /** UFW refusing the new file gets the old one back, and the script stops before its record. */
    @Test
    void aReloadThatFailsPutsTheOldFileBack(@TempDir Path dir) throws Exception {
        var ran = run(dir, UfwScript.firewallLines(), stock(), 1);
        assertNotEquals(0, ran.exit(), ran.output());
        assertEquals(stock(), ran.rules());
        assertTrue(ran.output().contains("the old one is back"), ran.output());
    }

    /** A host whose UFW refuses the old file too is told its rules may not be loaded, never that all is back. */
    @Test
    void aReloadOfTheOldFileThatFailsTooSaysTheRulesMayNotBeLoaded(@TempDir Path dir) throws Exception {
        var ran = run(dir, UfwScript.firewallLines(), stock(), 2);
        assertNotEquals(0, ran.exit(), ran.output());
        assertEquals(stock(), ran.rules());
        assertTrue(ran.output().contains("may not be loaded"), ran.output());
        assertFalse(ran.output().contains("the old one is back"), ran.output());
    }

    /** Marked blocks that are not whole, as an edit by hand can leave them, stop the script with the file untouched. */
    @Test
    void anEndMarkerBeforeItsBeginStopsTheScript(@TempDir Path dir) throws Exception {
        var content = stock().replace("*filter", UfwCheck.MARKER_NAT_END + "\n" + UfwCheck.MARKER_NAT_BEGIN + "\n*filter");
        var ran = run(dir, UfwScript.firewallLines(), content, 0);
        assertNotEquals(0, ran.exit(), ran.output());
        assertEquals(content, ran.rules());
        assertTrue(ran.output().contains("not whole"), ran.output());
        var unclosed = stock().replace("*filter", UfwCheck.MARKER_NAT_BEGIN + "\n*filter");
        assertNotEquals(0, run(dir.resolve("unclosed"), UfwScript.redirectLines(), unclosed, 0).exit());
        var endFirst = javaRedirect(stock()).replace(UfwCheck.MARKER_NAT_BEGIN, UfwCheck.MARKER_NAT_END + "\n" + UfwCheck.MARKER_NAT_BEGIN);
        var stray = run(dir.resolve("endFirst"), UfwScript.redirectLines(), endFirst, 0);
        assertNotEquals(0, stray.exit(), stray.output());
        assertEquals(endFirst, stray.rules());
    }

    /** The redirect's lines leave the FORWARD block alone, so a broken one does not stop them. */
    @Test
    void aBrokenForwardBlockDoesNotStopTheRedirect(@TempDir Path dir) throws Exception {
        var content = stock().replace("*filter", UfwCheck.MARKER_FWD_BEGIN + "\n*filter");
        var ran = run(dir, UfwScript.redirectLines(), content, 0);
        assertEquals(0, ran.exit(), ran.output());
        assertEquals(javaRedirect(content), ran.rules());
    }

    /** A second NAT block is left as it stands, as the Java's {@code indexOf} leaves it: a stray BEGIN never swallows the rest of the file. */
    @Test
    void onlyTheFirstNatBlockIsReplaced(@TempDir Path dir) throws Exception {
        var content = javaRedirect(stock()) + UfwCheck.MARKER_NAT_BEGIN + "\n# kept\n";
        var ran = run(dir, UfwScript.redirectLines(), content, 0);
        assertEquals(0, ran.exit(), ran.output());
        assertEquals(javaRedirect(content), ran.rules());
        assertTrue(ran.rules().endsWith(UfwCheck.MARKER_NAT_BEGIN + "\n# kept\n"), ran.rules());
    }

    /** A FORWARD block an older isx wrote into another line is the run's to move: the script stops and says so. */
    @Test
    void aMisplacedForwardBlockStopsTheScript(@TempDir Path dir) throws Exception {
        var content = stock().replace("# End required lines", "# End required lines" + UfwCheck.generateFilterInsert());
        assertTrue(UfwCheck.forwardBlockMisplaced(content));
        var ran = run(dir, UfwScript.firewallLines(), content, 0);
        assertNotEquals(0, ran.exit());
        assertEquals(content, ran.rules());
        assertTrue(ran.output().contains("sudo isx init"), ran.output());
    }
}
