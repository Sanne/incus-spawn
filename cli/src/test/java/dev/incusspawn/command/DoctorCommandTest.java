package dev.incusspawn.command;

import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.FirewalldCheck;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.InstanceSubvolumes;
import dev.incusspawn.incus.UfwCheck;
import dev.incusspawn.proxy.BridgeDns;
import dev.incusspawn.proxy.ProxyActivity;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ToolProxyResolver;
import dev.incusspawn.util.MacKeychainTrust;
import dev.incusspawn.vm.VmManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class DoctorCommandTest {

    @Test
    void keychainTrustSaysWhichCasAreTrustedAndHowToTrustOneSystemWide(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        var savedHome = System.getProperty("user.home");
        java.security.cert.X509Certificate ca;
        try {
            System.setProperty("user.home", temp.toString());
            ca = dev.incusspawn.proxy.CertificateAuthority.loadOrCreate().caCert();
        } finally {
            System.setProperty("user.home", savedHome);
        }
        var trusted = DoctorCommand.keychainTrustFinding(new MacKeychainTrust.Trust(List.of(ca), Set.of("AB"), null), null);
        assertEquals(DoctorCommand.Status.OK, trusted.status());
        assertTrue(trusted.detail().contains("sha256:" + dev.incusspawn.proxy.CertificateAuthority.fingerprintOf(ca)), trusted.detail());
        assertTrue(trusted.detail().contains("1 denied"), trusted.detail());

        // No say from the Keychain: never the "trusts no CA" warning, even with a bundle that holds no certificate
        var emptyBundle = Files.writeString(temp.resolve("empty.pem"), "");
        var none = DoctorCommand.keychainTrustFinding(new MacKeychainTrust.Trust(List.of(), Set.of(), null), emptyBundle);
        assertEquals(DoctorCommand.Status.NOTE, none.status());
        assertTrue(none.detail().contains("sudo security add-trusted-cert -d"), none.detail());

        // A user-level process can deny the default roots in the user's own domain (#1552)
        var rootsDenied = DoctorCommand.keychainTrustFinding(new MacKeychainTrust.Trust(List.of(ca), Set.of(), true, null), null);
        assertEquals(DoctorCommand.Status.OK, rootsDenied.status());
        assertTrue(rootsDenied.detail().contains("the default roots denied (security dump-trust-settings"), rootsDenied.detail());
        // With no Keychain CA either, isx trusts nothing: the proxy will not start
        var nothingTrusted = DoctorCommand.keychainTrustFinding(new MacKeychainTrust.Trust(List.of(), Set.of(), true, null), null);
        assertEquals(DoctorCommand.Status.WARN, nothingTrusted.status());
        assertTrue(nothingTrusted.detail().contains("isx trusts no CA"), nothingTrusted.detail());
        assertTrue(nothingTrusted.detail().contains("security dump-trust-settings"), nothingTrusted.detail());
        // The same when each CA of the bundle is denied on its own: doctor asks the proxy's own rule
        var bundle = Files.writeString(temp.resolve("cert.pem"), dev.incusspawn.DerEncoder.toPem("CERTIFICATE", ca.getEncoded()));
        var eachDenied = DoctorCommand.keychainTrustFinding(new MacKeychainTrust.Trust(List.of(), Set.of(MacKeychainTrust.sha1(ca)), null), bundle);
        assertEquals(DoctorCommand.Status.WARN, eachDenied.status());
        assertEquals("Keychain CAs trusted for TLS", eachDenied.label());
        // Also when part of the Keychain could not be read: its denies still leave the proxy nothing
        var partlyRead = DoctorCommand.keychainTrustFinding(new MacKeychainTrust.Trust(List.of(), Set.of(MacKeychainTrust.sha1(ca)), "timed out"), bundle);
        assertEquals(DoctorCommand.Status.WARN, partlyRead.status());
        assertTrue(partlyRead.detail().contains("isx trusts no CA") && partlyRead.detail().contains("timed out"), partlyRead.detail());

        var failed = DoctorCommand.keychainTrustFinding(new MacKeychainTrust.Trust(List.of(), Set.of(), "timed out"), null);
        assertEquals(DoctorCommand.Status.WARN, failed.status());
        assertTrue(failed.detail().contains("timed out"), failed.detail());
    }

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    /** What {@code /activity} says: {@code placed} last had traffic {@code placedAt}, {@code idle} none and is running. */
    private static ProxyActivity activity(ProxyActivity.Placement placement, Instant placedAt) {
        var placed = new ProxyActivity.Instance(Instant.EPOCH, 0, 0, null, null, 0, 0, 0, 0,
                new ProxyActivity.Traffic(new ProxyActivity.Counts(4, 0, placedAt, 0, 0), Map.of()));
        var idle = new ProxyActivity.Instance(Instant.EPOCH, 0, 0, null, null, 0, 0, 0, 0);
        return new ProxyActivity(Map.of("placed", placed, "idle", idle), null, ProxyActivity.Traffic.NONE, placement);
    }

    private static ProxyActivity.Placement misses(long count, Instant last, List<String> unplaced) {
        return new ProxyActivity.Placement(0, count, false, unplaced, last);
    }

    @Test
    void aProxyThatPlacesEveryCallerIsNoFinding() {
        assertEquals(List.of(), DoctorCommand.placementFindings(activity(ProxyActivity.Placement.NONE, NOW), false, NOW));
    }

    @Test
    void aRecentAddressMissWithNoTrafficPlacedSinceFails() {
        // #1582: the proxy places nobody, so every instance's traffic predates the misses
        var findings = DoctorCommand.placementFindings(activity(misses(5, NOW.minusSeconds(30), List.of("idle", "other")),
                NOW.minusSeconds(3600)), true, NOW);
        assertEquals(1, findings.size(), findings.toString());
        var f = findings.getFirst();
        assertEquals(DoctorCommand.Status.FAIL, f.status());
        assertTrue(f.detail().contains("cannot tell 2 running instances apart"), f.detail());
        assertTrue(f.detail().contains("idle and other are running"), f.detail());
        assertTrue(f.detail().contains("the last 0 min ago"), f.detail());
        assertTrue(f.detail().contains("appliance's NAT"), f.detail());
        assertFalse(DoctorCommand.placementFindings(activity(misses(5, NOW.minusSeconds(30), List.of("idle")), null), false, NOW)
                .getFirst().detail().contains("NAT"), "the NAT pointer is macOS's");
    }

    @Test
    void aStrayAddressMissOnlyWarns() {
        // The misses never go down and an idle instance is unplaced: one stray miss must not fail doctor for good
        var placedSince = DoctorCommand.placementFindings(activity(misses(1, NOW.minusSeconds(60), List.of("idle")),
                NOW.minusSeconds(10)), false, NOW);
        assertEquals(1, placedSince.size(), placedSince.toString());
        assertEquals(DoctorCommand.Status.WARN, placedSince.getFirst().status());
        assertTrue(placedSince.getFirst().detail().contains("no traffic is attributed to idle"), placedSince.getFirst().detail());
        assertFalse(placedSince.getFirst().detail().contains("placed"), placedSince.getFirst().detail());

        var old = DoctorCommand.placementFindings(activity(misses(1, NOW.minus(DoctorCommand.RECENT_ADDRESS_MISS).minusSeconds(1),
                List.of("idle")), null), false, NOW);
        assertEquals(DoctorCommand.Status.WARN, old.getFirst().status(), "nothing placed since, but long ago: " + old);

        assertEquals(List.of(), DoctorCommand.placementFindings(activity(misses(5, NOW, List.of()), null), false, NOW),
                "a miss while every running instance is placed was transient");
    }

    @Test
    void refusedProofsOfAKnownInstanceWarnWithTheInstancesRemedy() {
        var findings = DoctorCommand.placementFindings(activity(new ProxyActivity.Placement(2, 0, false, List.of()), NOW), false, NOW);
        assertEquals(1, findings.size(), findings.toString());
        assertEquals(DoctorCommand.Status.WARN, findings.getFirst().status());
        assertTrue(findings.getFirst().remediation().description().contains("new login shell"),
                findings.getFirst().remediation().description());
    }

    @Test
    void aFailedListingWarns() {
        var findings = DoctorCommand.placementFindings(activity(new ProxyActivity.Placement(0, 0, true, List.of()), NOW), false, NOW);
        assertEquals(1, findings.size(), findings.toString());
        assertEquals(DoctorCommand.Status.WARN, findings.getFirst().status());
    }

    @Test
    void unmeasurableCountIsOkAndOffersNoFix() {
        var f = DoctorCommand.forwarderFinding(-1);
        assertEquals(DoctorCommand.Status.OK, f.status());
        assertNull(f.remediation());
    }

    @Test
    void countAtOrBelowThresholdIsOk() {
        assertEquals(DoctorCommand.Status.OK, DoctorCommand.forwarderFinding(1).status());
        assertEquals(DoctorCommand.Status.OK,
                DoctorCommand.forwarderFinding(VmManager.VSOCK_CONN_WARN_THRESHOLD).status(),
                "exactly at threshold must not warn");
    }

    @Test
    void countAboveThresholdWarnsWithDestructiveRemediation() {
        var f = DoctorCommand.forwarderFinding(VmManager.VSOCK_CONN_WARN_THRESHOLD + 1);
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertNotNull(f.remediation(), "a leak must offer a remediation");
        assertTrue(f.remediation().destructive(), "VM restart is disruptive and must be flagged");
        assertTrue(f.label().contains(String.valueOf(VmManager.VSOCK_CONN_WARN_THRESHOLD + 1)),
                "label should report the actual count");
    }

    // leakLayer tests are canonical in VmManagerTest (the method now lives in VmManager).

    // ---- Forwarder leak finding: which layer, and which fix it offers ----

    private static final BooleanSupplier AGENT_ANSWERS = () -> true;
    private static final BooleanSupplier AGENT_SILENT = () -> false;
    private static final BooleanSupplier AGENT_NOT_ASKED = () -> fail("the agent must not be asked");
    private static final Supplier<OptionalInt> GUEST_NOT_ASKED = () -> fail("the guest count must not be asked");

    private static void assertOffersVmRestart(DoctorCommand.Finding f) {
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertTrue(f.remediation().destructive(), "a VM restart stops running containers");
        assertTrue(f.remediation().description().startsWith("Restart the VM"), f.remediation().description());
    }

    private static void assertOffersForwarderRestart(DoctorCommand.Finding f) {
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertFalse(f.remediation().destructive(), "the forwarder restart needs no reboot");
        assertTrue(f.remediation().description().startsWith("Restart the forwarder in the VM"),
                f.remediation().description());
    }

    @Test
    void hostLeakOffersVmRestartWithoutAskingTheAgent() {
        var f = DoctorCommand.forwarderLeakFinding(300, () -> OptionalInt.of(5), AGENT_NOT_ASKED);
        assertOffersVmRestart(f);
        assertEquals("vsock forwarder connections: 300", f.label());
        assertTrue(f.detail().contains("(in-guest socat: 5)"), f.detail());
        assertTrue(f.detail().endsWith("— " + VmManager.LeakLayer.VFKIT.description), f.detail());
    }

    // A guest count came from the agent, so the agent lane is proven: no ping, and the fix offered
    // is the one the FORWARDER detail names (#1214).
    @Test
    void forwarderLeakWithAGuestCountOffersTheNoRebootRestartWithoutAPing() {
        var f = DoctorCommand.forwarderLeakFinding(300, () -> OptionalInt.of(280), AGENT_NOT_ASKED);
        assertOffersForwarderRestart(f);
        assertTrue(f.detail().contains("(in-guest socat: 280)"), f.detail());
        assertTrue(f.detail().endsWith("— " + VmManager.LeakLayer.FORWARDER.description), f.detail());
    }

    // The detail blames vfkit, so the fix must not blame the forwarder (#1214).
    @Test
    void hostLeakFixDoesNotBlameTheForwarder() {
        var fix = DoctorCommand.forwarderLeakFinding(300, () -> OptionalInt.of(5), AGENT_NOT_ASKED).remediation();
        assertFalse(fix.description().contains("forwarder"), fix.description());
    }

    @Test
    void layerBoundaryPicksTheFix() {
        assertOffersVmRestart(DoctorCommand.forwarderLeakFinding(100, () -> OptionalInt.of(50), AGENT_NOT_ASKED));
        assertOffersForwarderRestart(DoctorCommand.forwarderLeakFinding(100, () -> OptionalInt.of(51), AGENT_NOT_ASKED));
    }

    @Test
    void stoppedGuestForwarderGetsTheNoRebootRestart() {
        assertOffersForwarderRestart(DoctorCommand.forwarderLeakFinding(300, () -> OptionalInt.of(0), AGENT_NOT_ASKED));
    }

    @Test
    void countAtThresholdIsOkWithoutAskingTheAgent() {
        var f = DoctorCommand.forwarderLeakFinding(VmManager.VSOCK_CONN_WARN_THRESHOLD, GUEST_NOT_ASKED, AGENT_NOT_ASKED);
        assertEquals(DoctorCommand.Status.OK, f.status());
        assertNull(f.remediation());
        assertEquals("", f.detail());
    }

    @Test
    void unmeasurableHostCountIsOkWithoutAskingTheAgent() {
        var f = DoctorCommand.forwarderLeakFinding(-1, () -> OptionalInt.of(3), AGENT_NOT_ASKED);
        assertEquals(DoctorCommand.Status.OK, f.status());
        assertNull(f.remediation());
        assertEquals("(not measurable) (in-guest socat: 3)", f.detail(), "the guest count is the only signal left");
        assertEquals("(not measurable)",
                DoctorCommand.forwarderLeakFinding(-1, OptionalInt::empty, AGENT_NOT_ASKED).detail());
    }

    // No guest count: socat-count timed out or its reply did not parse (an agent without the verb),
    // while ping, a separate request, may still answer.
    @Test
    void leakWithoutAGuestCountOffersTheFixTheAgentAllows() {
        var answering = DoctorCommand.forwarderLeakFinding(300, OptionalInt::empty, AGENT_ANSWERS);
        assertOffersForwarderRestart(answering);
        assertFalse(answering.detail().contains("in-guest socat"), answering.detail());
        assertOffersVmRestart(DoctorCommand.forwarderLeakFinding(300, OptionalInt::empty, AGENT_SILENT));
    }

    // ---- Incus reachability on macOS: defers to the forwarder finding that holds the fix ----

    private static final Supplier<String> CONNECTIVITY_NOT_ASKED =
            () -> fail("the VM-pointing connectivity diagnosis must not be asked");

    // A saturated forwarder stops the Incus API answering: the unreachable finding must not offer
    // the VM restart ahead of the forwarder restart that clears it, nor point at the VM (#1277).
    @Test
    void unreachableIncusDefersToTheForwarderRestart() {
        var forwarder = DoctorCommand.forwarderLeakFinding(108, () -> OptionalInt.of(109), AGENT_NOT_ASKED);
        assertOffersForwarderRestart(forwarder);
        var f = DoctorCommand.incusReachableFinding(false, 3.0, CONNECTIVITY_NOT_ASKED, forwarder);
        assertEquals(DoctorCommand.Status.FAIL, f.status());
        assertNull(f.remediation(), "the forwarder finding holds the fix");
        assertFalse(f.detail().contains("VM"), f.detail());
        assertTrue(f.detail().contains("leaked vsock connections"), f.detail());
    }

    @Test
    void unreachableIncusDefersWhenTheLeakCannotBeLocatedButTheAgentAnswers() {
        var forwarder = DoctorCommand.forwarderLeakFinding(300, OptionalInt::empty, AGENT_ANSWERS);
        var f = DoctorCommand.incusReachableFinding(false, 3.0, CONNECTIVITY_NOT_ASKED, forwarder);
        assertNull(f.remediation());
    }

    @Test
    void slowIncusDefersToTheForwarderRestart() {
        var forwarder = DoctorCommand.forwarderLeakFinding(300, () -> OptionalInt.of(280), AGENT_NOT_ASKED);
        var f = DoctorCommand.incusReachableFinding(true, 6.0, CONNECTIVITY_NOT_ASKED, forwarder);
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertNull(f.remediation());
    }

    @Test
    void unreachableIncusOffersTheVmRestartWhenTheForwarderDoesNot() {
        // A vfkit leak, a healthy forwarder, or one that cannot be measured: the VM restart is the fix.
        for (var forwarder : List.of(
                DoctorCommand.forwarderLeakFinding(300, () -> OptionalInt.of(5), AGENT_NOT_ASKED),
                DoctorCommand.forwarderLeakFinding(1, GUEST_NOT_ASKED, AGENT_NOT_ASKED),
                DoctorCommand.forwarderLeakFinding(-1, OptionalInt::empty, AGENT_NOT_ASKED))) {
            var f = DoctorCommand.incusReachableFinding(false, 3.0, () -> "timed out", forwarder);
            assertEquals(DoctorCommand.Status.FAIL, f.status());
            assertEquals("(timed out)", f.detail());
            assertTrue(f.remediation().destructive());
            assertTrue(f.remediation().description().startsWith("Restart the VM"), f.remediation().description());
        }
        var slow = DoctorCommand.incusReachableFinding(true, 6.0, CONNECTIVITY_NOT_ASKED,
                DoctorCommand.forwarderLeakFinding(1, GUEST_NOT_ASKED, AGENT_NOT_ASKED));
        assertTrue(slow.remediation().description().startsWith("Restart the VM"));
    }

    @Test
    void reachableIncusIsOk() {
        var f = DoctorCommand.incusReachableFinding(true, 0.2, CONNECTIVITY_NOT_ASKED,
                DoctorCommand.forwarderLeakFinding(300, () -> OptionalInt.of(280), AGENT_NOT_ASKED));
        assertEquals(DoctorCommand.Status.OK, f.status());
        assertNull(f.remediation());
    }

    // ---- Storage pool usage evaluation ----

    private static final long MIB = 1024L * 1024;

    private static DoctorCommand.Finding storage(long usedMib, long totalMib) {
        return DoctorCommand.evaluateStorageUsage("default", new IncusClient.PoolUsage(usedMib * MIB, totalMib * MIB));
    }

    @Test
    void storagePoolOkWhenBelowThreshold() {
        assertEquals(DoctorCommand.Status.OK, storage(5000, 50000).status());
    }

    @Test
    void storagePoolWarnsWhenAbove90Percent() {
        var f = storage(46000, 50000);
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertTrue(f.label().contains("nearly full"));
        assertNull(f.remediation(), "overall finding should not have an action — specific findings do");
        assertTrue(f.detail().contains("isx clean pool"), "detail should suggest isx clean pool");
    }

    @Test
    void storagePoolWarnsAtExactly90Percent() {
        // The TUI and build diagnostics already said critical at 90%; doctor now agrees (#2204)
        assertEquals(DoctorCommand.Status.WARN, storage(45000, 50000).status());
    }

    @Test
    void storagePoolOkWhenLargePoolStillHasPlentyFree() {
        // 1 TiB at 92% leaves about 80 GiB: a percentage alone called that nearly full (#2204)
        assertEquals(DoctorCommand.Status.OK, storage(964690, 1048576).status());
    }

    @Test
    void storagePoolHandlesNoSpaceInfo() {
        var f = storage(0, 0);
        assertEquals(DoctorCommand.Status.OK, f.status());
        assertEquals("(no space info)", f.detail());
    }

    // ---- Pool size evaluation ----

    @Test
    void undersizedPoolWarnsWithResizeRemediation() {
        long thirtyGiB = 30L * 1024 * 1024 * 1024;
        var usage = new IncusClient.PoolUsage(thirtyGiB * 96 / 100, thirtyGiB);
        var f = DoctorCommand.evaluatePoolSize("cow", usage, null, null);
        assertNotNull(f);
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertTrue(f.label().contains("undersized"), "label: " + f.label());
        assertTrue(f.detail().contains("thin-provisioned"), "should explain thin provisioning");
        assertNotNull(f.remediation());
        assertTrue(f.remediation().description().contains("100GiB"));
    }

    @Test
    void adequatePoolSuggestsDoublingWhenFull() {
        long hundredGiB = 100L * 1024 * 1024 * 1024;
        var usage = new IncusClient.PoolUsage(hundredGiB * 95 / 100, hundredGiB);
        var f = DoctorCommand.evaluatePoolSize("cow", usage, null, null);
        assertNotNull(f);
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertTrue(f.label().contains("enlarged"), "label: " + f.label());
        assertTrue(f.detail().contains("thin-provisioned"), "should explain thin provisioning");
        assertNotNull(f.remediation());
        assertTrue(f.remediation().description().contains("200GiB"));
    }

    @Test
    void adequatePoolNotFullReturnsNull() {
        long hundredGiB = 100L * 1024 * 1024 * 1024;
        var usage = new IncusClient.PoolUsage(hundredGiB * 50 / 100, hundredGiB);
        assertNull(DoctorCommand.evaluatePoolSize("cow", usage, null, null));
    }

    // ---- iptables PREROUTING rule detection ----

    @Test
    void iptablesRuleDetectedInFirewalldOutput() {
        var output = """
                ipv4 filter FORWARD 0 -o incusbr0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
                ipv4 nat PREROUTING 0 -i incusbr0 -d 10.166.11.1 -p tcp --dport 443 -j REDIRECT --to-port 18443
                """;
        assertTrue(DoctorCommand.isPreRoutingRulePresent(output, 18443, "10.166.11.1"));
    }

    @Test
    void iptablesRuleMissingInFirewalldOutput() {
        var output = """
                ipv4 filter FORWARD 0 -o incusbr0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
                """;
        assertFalse(DoctorCommand.isPreRoutingRulePresent(output, 18443, "10.166.11.1"));
    }

    @Test
    void iptablesRuleNotMatchedWithDifferentPort() {
        var output = """
                ipv4 nat PREROUTING 0 -i incusbr0 -d 10.166.11.1 -p tcp --dport 443 -j REDIRECT --to-port 9999
                """;
        assertFalse(DoctorCommand.isPreRoutingRulePresent(output, 18443, "10.166.11.1"));
    }

    @Test
    void iptablesRuleNotMatchedWithoutIncusbr0() {
        var output = """
                ipv4 nat PREROUTING 0 -i docker0 -d 10.166.11.1 -p tcp --dport 443 -j REDIRECT --to-port 18443
                """;
        assertFalse(DoctorCommand.isPreRoutingRulePresent(output, 18443, "10.166.11.1"));
    }

    @Test
    void iptablesRuleNotMatchedWithDifferentDport() {
        var output = """
                ipv4 nat PREROUTING 0 -i incusbr0 -d 10.166.11.1 -p tcp --dport 8080 -j REDIRECT --to-port 18443
                """;
        assertFalse(DoctorCommand.isPreRoutingRulePresent(output, 18443, "10.166.11.1"));
    }

    @Test
    void iptablesRuleNotMatchedWithStaleGateway() {
        var output = """
                ipv4 nat PREROUTING 0 -i incusbr0 -d 10.73.232.1 -p tcp --dport 443 -j REDIRECT --to-port 18443
                """;
        assertFalse(DoctorCommand.isPreRoutingRulePresent(output, 18443, "10.166.11.1"));
    }

    @Test
    void iptablesEmptyOutputReturnsFalse() {
        assertFalse(DoctorCommand.isPreRoutingRulePresent("", 18443, "10.166.11.1"));
    }

    // ---- Config permissions evaluation ----

    // ---- Credential findings ----

    private static final DoctorCommand.Status NOTE = DoctorCommand.Status.NOTE;

    /** Triples of tool, key and namespace. */
    private static List<ToolProxyResolver.UnresolvedToolProxy> unresolved(String... triples) {
        var list = new ArrayList<ToolProxyResolver.UnresolvedToolProxy>();
        for (int i = 0; i < triples.length; i += 3) {
            list.add(new ToolProxyResolver.UnresolvedToolProxy(triples[i], triples[i + 1], triples[i + 2]));
        }
        return list;
    }

    private static List<DoctorCommand.Finding> credentialFindings(boolean claudeConfigured,
            List<ToolProxyResolver.UnresolvedToolProxy> unresolved, java.util.function.Predicate<String> toolInUse) {
        return DoctorCommand.credentialFindings(claudeConfigured, unresolved, toolInUse, ns -> false,
                DoctorCommand.Inheritance.AVAILABLE);
    }

    // ---- Transitive `requires:` expansion (toolInUse's building block) ----

    /** Bare-minimum ToolSetup: a name and the tools it requires, nothing else wired up. */
    private static class FakeTool implements dev.incusspawn.tool.ToolSetup {
        private final String name;
        private final List<String> requires;
        FakeTool(String name, String... requires) { this.name = name; this.requires = List.of(requires); }
        @Override public String name() { return name; }
        @Override public List<String> requires() { return requires; }
        @Override public void install(dev.incusspawn.incus.Container c, Map<String, String> params) { }
    }

    private static Map<String, dev.incusspawn.tool.ToolSetup> toolMap(dev.incusspawn.tool.ToolSetup... tools) {
        var map = new java.util.LinkedHashMap<String, dev.incusspawn.tool.ToolSetup>();
        for (var t : tools) map.put(t.name(), t);
        return map;
    }

    @Test
    void addWithRequiresIncludesTheToolItself() {
        var into = new java.util.HashSet<String>();
        dev.incusspawn.tool.ToolSetup.addWithRequires("codex", toolMap(new FakeTool("codex")), into);
        assertEquals(java.util.Set.of("codex"), into);
    }

    @Test
    void addWithRequiresExpandsTransitively() {
        // A wrapper tool that requires codex pulls it into the build exactly as if the
        // template had listed it directly -- this is the case that motivated the fix.
        var tools = toolMap(
                new FakeTool("my-wrapper", "codex"),
                new FakeTool("codex", "nodejs"),
                new FakeTool("nodejs"));
        var into = new java.util.HashSet<String>();
        dev.incusspawn.tool.ToolSetup.addWithRequires("my-wrapper", tools, into);
        assertEquals(java.util.Set.of("my-wrapper", "codex", "nodejs"), into);
    }

    @Test
    void addWithRequiresToleratesACycleWithoutLooping() {
        var tools = toolMap(new FakeTool("a", "b"), new FakeTool("b", "a"));
        var into = new java.util.HashSet<String>();
        dev.incusspawn.tool.ToolSetup.addWithRequires("a", tools, into);
        assertEquals(java.util.Set.of("a", "b"), into);
    }

    @Test
    void addWithRequiresToleratesAnUnknownTool() {
        var into = new java.util.HashSet<String>();
        dev.incusspawn.tool.ToolSetup.addWithRequires("ghost", Map.of(), into);
        assertEquals(java.util.Set.of("ghost"), into);
    }

    private static dev.incusspawn.config.ImageDef template(String yaml, boolean builtIn) throws Exception {
        var def = dev.incusspawn.config.ImageDef.parseYaml(yaml);
        if (builtIn) def.setSource("built-in");
        return def;
    }

    /**
     * A tool a user template inherits from a built-in parent is in use (#2159): {@code isx branch}
     * refuses the template without its credential, so doctor must warn about it rather than call
     * it unused. A built-in template alone still counts nothing.
     */
    @Test
    void aToolInheritedFromABuiltInParentIsInUse() throws Exception {
        var defs = new java.util.LinkedHashMap<String, dev.incusspawn.config.ImageDef>();
        defs.put("tpl-minimal", template("name: tpl-minimal\nimage: fedora\n", true));
        defs.put("tpl-dev", template("name: tpl-dev\nparent: tpl-minimal\ntools: [gh]\n", true));
        defs.put("tpl-java", template("name: tpl-java\nparent: tpl-minimal\ntools: [codex]\n", true));
        defs.put("tpl-claude", template("name: tpl-claude\nparent: tpl-dev\ntools: [claude]\n", false));
        var tools = toolMap(new FakeTool("gh"), new FakeTool("claude"), new FakeTool("codex"));

        var inUse = DoctorCommand.toolsInUse(defs, tools);

        assertEquals(java.util.Set.of("gh", "claude"), inUse);
        var findings = credentialFindings(true, unresolved("gh", "token", "github"), inUse::contains);
        assertEquals(DoctorCommand.Status.WARN, findings.getFirst().status(), findings.toString());
        assertEquals("Missing credentials", findings.getFirst().label());
    }

    /** A credential under a key no tool reads is named with the key it belongs in (#1733). */
    @Test
    void anUnreadCredentialKeyIsAWarning() throws Exception {
        var tools = builtInTools();
        var tree = dev.incusspawn.config.Jjq.spawnConfig("openai:\n  token: sk-x\ngithub:\n  token: ghp_x\n").tree();

        var findings = DoctorCommand.unreadKeyFindings(tree, tools);

        assertEquals(1, findings.size(), findings.toString());
        assertEquals(DoctorCommand.Status.WARN, findings.getFirst().status());
        assertTrue(findings.getFirst().detail().contains("openai.token, which isx does not read"),
                findings.getFirst().detail());
        assertTrue(findings.getFirst().detail().endsWith(".accounts.<name>)"), findings.getFirst().detail());
        assertEquals("Rename or remove them in config.yaml", findings.getFirst().remediation().description());
    }

    @Test
    void credentialsOkWhenNothingIsMissing() {
        var findings = credentialFindings(true, List.of(), name -> true);

        assertEquals(1, findings.size());
        assertEquals(DoctorCommand.Status.OK, findings.getFirst().status());
        assertEquals("configured", findings.getFirst().detail());
    }

    @Test
    void unconfiguredCredentialForAnUnusedToolIsANeutralNote() {
        var findings = credentialFindings(true, unresolved("codex", "api-key", "openai"), name -> false);

        assertEquals(2, findings.size());
        assertEquals(DoctorCommand.Status.OK, findings.get(0).status());
        assertEquals("configured for the tools in use", findings.get(0).detail());

        var note = findings.get(1);
        assertEquals(NOTE, note.status());
        assertTrue(note.detail().contains("codex api-key"), note.detail());
        assertNull(note.remediation(), "a note is not something to fix");
    }

    @Test
    void aNoteIsNotAProblemAndCannotFailTheRun() {
        assertFalse(NOTE.isProblem());
        assertFalse(DoctorCommand.Status.OK.isProblem());
        assertTrue(DoctorCommand.Status.WARN.isProblem());
        assertTrue(DoctorCommand.Status.FAIL.isProblem());
    }

    @Test
    void unconfiguredCredentialForATemplatesToolWarns() {
        var findings = credentialFindings(true, unresolved("codex", "api-key", "openai"),
                name -> name.equals("codex"));

        assertEquals(1, findings.size());
        assertEquals(DoctorCommand.Status.WARN, findings.getFirst().status());
        assertTrue(findings.getFirst().detail().contains("codex api-key"));
        assertNotNull(findings.getFirst().remediation());
    }

    @Test
    void usedAndUnusedToolsAreReportedSeparately() {
        var findings = credentialFindings(true,
                unresolved("codex", "api-key", "openai", "bob", "api-key", "bob"), name -> name.equals("codex"));

        assertEquals(2, findings.size());
        assertEquals(DoctorCommand.Status.WARN, findings.get(0).status());
        assertTrue(findings.get(0).detail().contains("codex api-key"));
        assertFalse(findings.get(0).detail().contains("bob"));
        assertEquals(NOTE, findings.get(1).status());
        assertTrue(findings.get(1).detail().contains("bob api-key"));
    }

    @Test
    void missingClaudeCredentialWarnsEvenWithNoTemplate() {
        // isx itself uses the Anthropic credential (isx ask), so there is no template to check.
        var findings = credentialFindings(false, List.of(), name -> false);

        assertEquals(1, findings.size());
        assertEquals(DoctorCommand.Status.WARN, findings.getFirst().status());
        assertTrue(findings.getFirst().detail().contains("claude"));
    }

    private static final String INHERITING = "claude:\n  default: inherit\ngithub:\n  default: inherit\n";

    private static Map<String, dev.incusspawn.tool.ToolSetup> builtInTools() {
        var tools = new java.util.LinkedHashMap<String, dev.incusspawn.tool.ToolSetup>();
        dev.incusspawn.RuntimeConstants.CDI_TOOLS.forEach(t -> tools.put(t.name(), t));
        return tools;
    }

    /**
     * Inside an isx instance, a namespace whose default is {@code inherit} is served by the outer
     * instance's account, so it is configured, not missing (#2055).
     */
    @Test
    void anInheritedDefaultInsideAnInstanceCountsAsConfigured() throws Exception {
        var config = dev.incusspawn.config.Jjq.spawnConfig(INHERITING);

        var findings = DoctorCommand.credentialFindings(config, builtInTools(), "gh"::equals,
                DoctorCommand.Inheritance.AVAILABLE);

        assertTrue(findings.stream().noneMatch(f -> f.status() == DoctorCommand.Status.WARN), findings.toString());
        assertEquals(DoctorCommand.Status.OK, findings.getFirst().status(), findings.toString());
        assertTrue(findings.getFirst().detail().contains("claude, github inherited from the outer instance"),
                findings.getFirst().detail());
        assertTrue(findings.stream().noneMatch(f -> f.detail().contains("gh token")), findings.toString());
    }

    /** Outside an instance there is nothing to inherit from, and doctor says that, not "missing". */
    @Test
    void anInheritedDefaultOutsideAnInstanceIsNamedAsSuch() throws Exception {
        var config = dev.incusspawn.config.Jjq.spawnConfig(INHERITING);

        var findings = DoctorCommand.credentialFindings(config, builtInTools(), "gh"::equals,
                DoctorCommand.Inheritance.NOT_NESTED);

        assertTrue(findings.stream().noneMatch(f -> f.label().equals("Missing credentials")), findings.toString());
        assertTrue(findings.stream().noneMatch(f -> f.label().equals("Credentials")),
                "a credential that cannot be served is not configured: " + findings);
        var finding = findings.stream()
                .filter(f -> f.label().equals("Credentials set to inherit outside an isx instance"))
                .findFirst().orElseThrow(() -> new AssertionError(findings.toString()));
        assertEquals(DoctorCommand.Status.WARN, finding.status());
        assertEquals("(claude, github)", finding.detail());
        assertTrue(finding.remediation().description().contains("default"), finding.remediation().description());
    }

    /** Inside an instance whose secret cannot be read, the cause is the secret, not the place. */
    @Test
    void anInheritedDefaultWithAnUnusableSecretNamesTheSecret() throws Exception {
        var config = dev.incusspawn.config.Jjq.spawnConfig(INHERITING);

        var findings = DoctorCommand.credentialFindings(config, builtInTools(), "gh"::equals,
                DoctorCommand.Inheritance.SECRET_UNUSABLE);

        assertTrue(findings.stream().noneMatch(f -> f.label().contains("Missing") || f.label().contains("outside")),
                findings.toString());
        assertTrue(findings.stream().noneMatch(f -> f.label().equals("Credentials")),
                "a credential that cannot be served is not configured: " + findings);
        var finding = findings.stream()
                .filter(f -> f.label().equals("Credentials set to inherit, but this instance's secret cannot be read"))
                .findFirst().orElseThrow(() -> new AssertionError(findings.toString()));
        assertEquals(DoctorCommand.Status.WARN, finding.status());
        assertEquals("(claude, github)", finding.detail());
    }

    @Test
    void configPermissionsOkWhenOwnerOnly() {
        var f = DoctorCommand.evaluateConfigPermissions("rw-------");
        assertEquals(DoctorCommand.Status.OK, f.status());
    }

    @Test
    void configPermissionsWarnsWhenGroupReadable() {
        var f = DoctorCommand.evaluateConfigPermissions("rw-r-----");
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertTrue(f.label().contains("too open"));
    }

    @Test
    void configPermissionsWarnsWhenWorldReadable() {
        var f = DoctorCommand.evaluateConfigPermissions("rw-r--r--");
        assertEquals(DoctorCommand.Status.WARN, f.status());
    }

    @Test
    void configPermissionsWarnsWhenOnlyOtherWritable() {
        var f = DoctorCommand.evaluateConfigPermissions("rw-----w-");
        assertEquals(DoctorCommand.Status.WARN, f.status());
    }

    @Test
    void configPermissionsWarnsWhenOnlyOtherExecutable() {
        var f = DoctorCommand.evaluateConfigPermissions("rw------x");
        assertEquals(DoctorCommand.Status.WARN, f.status());
    }

    // ---- Findings JSON serialization ----

    @Test
    void findingsToJsonProducesValidJson() throws Exception {
        var findings = List.of(
                DoctorCommand.Finding.ok("Test label", "some detail"),
                DoctorCommand.Finding.warn("Warn label", "warn detail",
                        new DoctorCommand.Remediation("fix it", false, null)),
                DoctorCommand.Finding.fail("Fail label", "", null)
        );
        var json = DoctorCommand.findingsToJson(findings);
        var root = io.hyperfoil.tools.jjq.value.JqValues.parse(json);
        assertTrue(root.isArray());
        assertEquals(3, root.arrayValue().size());

        var first = (io.hyperfoil.tools.jjq.value.JqObject) root.arrayValue().get(0);
        assertEquals("OK", first.path("status").asText());
        assertEquals("Test label", first.path("label").asText());
        assertEquals("some detail", first.path("detail").asText());
        assertTrue(first.path("remediation").isMissingNode());

        var second = (io.hyperfoil.tools.jjq.value.JqObject) root.arrayValue().get(1);
        assertEquals("WARN", second.path("status").asText());
        assertEquals("fix it", second.path("remediation").asText());

        var third = (io.hyperfoil.tools.jjq.value.JqObject) root.arrayValue().get(2);
        assertEquals("FAIL", third.path("status").asText());
        assertEquals("", third.path("detail").asText());
    }

    @Test
    void findingsToJsonEmptyListProducesEmptyArray() throws Exception {
        var json = DoctorCommand.findingsToJson(List.of());
        assertEquals("[]", json);
    }

    // ---- Off-CoW-pool instance classification ----

    @Test
    void allInstancesOnCowPoolProducesNoFinding() {
        var pools = Map.of("cow", "btrfs", "default", "dir");
        var instancePools = Map.of("tpl-minimal", "cow", "my-branch", "cow");
        var result = DoctorCommand.classifyOffCowPool("cow", pools, instancePools);
        assertTrue(result.isEmpty());
    }

    @Test
    void instanceOnAnotherCowPoolProducesNoFinding() {
        var pools = Map.of("cow", "btrfs", "fast", "zfs");
        var instancePools = Map.of("tpl-minimal", "cow", "my-branch", "fast");
        var result = DoctorCommand.classifyOffCowPool("cow", pools, instancePools);
        assertTrue(result.isEmpty(), "instance on another CoW pool should not be flagged");
    }

    @Test
    void instanceOnDirPoolIsGroupedAndFlagged() {
        var pools = Map.of("cow", "btrfs", "default", "dir");
        var instancePools = Map.of("tpl-minimal", "cow", "stray-1", "default", "stray-2", "default");
        var result = DoctorCommand.classifyOffCowPool("cow", pools, instancePools);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("default"));
        assertEquals(2, result.get("default").size());
    }

    @Test
    void instancesOnMultipleNonCowPoolsGroupedSeparately() {
        var pools = Map.of("cow", "btrfs", "old", "dir", "tmp", "dir");
        var instancePools = Map.of("a", "old", "b", "tmp", "c", "cow");
        var result = DoctorCommand.classifyOffCowPool("cow", pools, instancePools);
        assertEquals(2, result.size());
        assertEquals(List.of("a"), result.get("old"));
        assertEquals(List.of("b"), result.get("tmp"));
    }

    @Test
    void emptyInstancePoolsProducesNoFinding() {
        var pools = Map.of("cow", "btrfs", "default", "dir");
        var result = DoctorCommand.classifyOffCowPool("cow", pools, Map.of());
        assertTrue(result.isEmpty(), "no instances means no findings");
    }

    // ---- Storage remediations act through isx, never name an incus command (#986) ----

    /** The Incus CLI is not on a macOS host, and on Linux it is an implementation detail. */
    private static void assertNoIncusCommand(DoctorCommand.Finding f) {
        assertFalse(f.detail().contains("incus "), f.detail());
        if (f.remediation() != null) {
            assertFalse(f.remediation().description().contains("incus "), f.remediation().description());
        }
    }

    private static final Map<String, String> DIR_AND_COW = Map.of("default", "dir", "cow", "btrfs");

    private static FakeIncusDaemon dirDefaultWithCowPool() {
        return new FakeIncusDaemon().pool("default", "dir").pool("cow", "btrfs");
    }

    @Test
    void noCowPoolFindingNamesNoIncusCommand() {
        var linux = DoctorCommand.noCowPoolFinding(Map.of("default", "dir"), true, "ext4", () -> false);
        assertNoIncusCommand(linux);
        assertNotNull(linux.remediation().action());
        assertTrue(linux.remediation().description().contains("100 GiB"), linux.remediation().description());
        var btrfs = DoctorCommand.noCowPoolFinding(Map.of("default", "dir"), true, "btrfs", () -> false);
        assertNoIncusCommand(btrfs);
        assertTrue(btrfs.remediation().description().contains("subvolume"), btrfs.remediation().description());
        // The macOS remediation is still to be decided (#1085).
        var mac = DoctorCommand.noCowPoolFinding(Map.of("default", "dir"), false, null, () -> false);
        assertNoIncusCommand(mac);
        assertNull(mac.remediation());
    }

    @Test
    void profileOnANonCowPoolIsFixedThroughIsx() throws Exception {
        var daemon = dirDefaultWithCowPool();
        var incus = daemon.client();

        var findings = DoctorCommand.checkProfilePool(incus, "cow", DIR_AND_COW);

        assertEquals(1, findings.size());
        findings.forEach(DoctorCommandTest::assertNoIncusCommand);
        findings.getFirst().remediation().action().run();
        assertEquals("cow", IncusClient.rootDiskPoolFromDevices(incus.profileDevices("default")));
    }

    @Test
    void stoppedInstancesOffTheCowPoolAreMovedRunningOnesOnlyListed() throws Exception {
        var daemon = dirDefaultWithCowPool()
                .container("tpl-old", Map.of())
                .instance("dev-1", "container", "Running", Map.of())
                .containerSubvolumes("tpl-old");
        var incus = daemon.client();

        var findings = DoctorCommand.checkInstancesOffCowPool(incus, "cow", DIR_AND_COW);

        assertEquals(1, findings.size());
        var f = findings.getFirst();
        assertNoIncusCommand(f);
        assertTrue(f.detail().contains("dev-1 is running: stop it and re-run 'isx doctor'"), f.detail());
        assertEquals("Move 1 stopped instance(s) to pool 'cow' (a full copy, once)", f.remediation().description());

        daemon.clearRequests();
        f.remediation().action().run();
        assertTrue(daemon.requests().contains("POST /1.0/instances/tpl-old"), daemon.requests().toString());
        assertFalse(daemon.requests().contains("POST /1.0/instances/dev-1"), daemon.requests().toString());
        var pools = new java.util.HashMap<String, String>();
        incus.listInstanceRoots().forEach(r -> pools.put(r.name(), r.pool()));
        assertEquals(Map.of("tpl-old", "cow", "dev-1", "default"), pools);
    }

    @Test
    void templatesAreToldApartByTheirTypeNeverByATplPrefix() {
        // 'tpl-' is a convention: a template may be named otherwise, and a branch may be called tpl-anything
        var type = dev.incusspawn.incus.Metadata.TYPE;
        var daemon = dirDefaultWithCowPool()
                .container("isx-presentation", Map.of(type, dev.incusspawn.incus.Metadata.TYPE_BASE))
                .container("my-project", Map.of(type, dev.incusspawn.incus.Metadata.TYPE_PROJECT))
                .container("tpl-looks-like-one", Map.of(type, dev.incusspawn.incus.Metadata.TYPE_CLONE))
                .container("isx-presentation" + BuildCommand.REBUILDING_SUFFIX, Map.of())
                .container("dev-1", Map.of());

        var templates = new java.util.TreeSet<String>();
        for (var inst : daemon.client().list()) {
            if (DoctorCommand.isTemplate(inst)) templates.add(inst.get("name"));
        }

        assertEquals(Set.of("isx-presentation", "isx-presentation-rebuilding", "my-project"), templates);
    }

    @Test
    void anInstanceLeftInErrorIsMovedAFrozenOneIsNot() {
        var daemon = dirDefaultWithCowPool()
                .instance("failed-start", "container", "Error", Map.of())
                .instance("paused", "container", "Frozen", Map.of());

        var f = DoctorCommand.checkInstancesOffCowPool(daemon.client(), "cow", DIR_AND_COW).getFirst();

        assertEquals("Move 1 stopped instance(s) to pool 'cow' (a full copy, once)", f.remediation().description());
        assertTrue(f.detail().contains("paused is running"), f.detail());
    }

    @Test
    void onlyRunningInstancesOffTheCowPoolOfferNoMove() {
        var daemon = dirDefaultWithCowPool().instance("dev-1", "container", "Running", Map.of());

        var f = DoctorCommand.checkInstancesOffCowPool(daemon.client(), "cow", DIR_AND_COW).getFirst();

        assertNull(f.remediation());
    }

    // ---- Root disk btrfs superblock validation ----

    @Test
    void validBtrfsSuperblockIsOk() throws Exception {
        var tmp = Files.createTempFile("disk", ".img");
        try {
            try (var raf = new RandomAccessFile(tmp.toFile(), "rw")) {
                raf.setLength(0x10048);
                raf.seek(0x10040);
                raf.write("_BHRfS_M".getBytes(StandardCharsets.US_ASCII));
            }
            var f = DoctorCommand.validateBtrfsSuperblock(tmp);
            assertEquals(DoctorCommand.Status.OK, f.status());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void corruptedDiskImageFailsWithBadMagic() throws Exception {
        var tmp = Files.createTempFile("disk", ".img");
        try {
            try (var raf = new RandomAccessFile(tmp.toFile(), "rw")) {
                raf.setLength(0x10048);
                // leave zeros — no valid magic
            }
            var f = DoctorCommand.validateBtrfsSuperblock(tmp);
            assertEquals(DoctorCommand.Status.FAIL, f.status());
            assertTrue(f.label().contains("corrupted"));
            assertTrue(f.detail().contains("superblock"));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void truncatedDiskImageFailsAsTooSmall() throws Exception {
        var tmp = Files.createTempFile("disk", ".img");
        try {
            try (var raf = new RandomAccessFile(tmp.toFile(), "rw")) {
                raf.setLength(1024); // way too small
            }
            var f = DoctorCommand.validateBtrfsSuperblock(tmp);
            assertEquals(DoctorCommand.Status.FAIL, f.status());
            assertTrue(f.detail().contains("too small"));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---- Firewall not-running finding selection ----

    @Test
    void aFailedFirewalldIsRestartedNotEnabled() {
        var f = DoctorCommand.firewalldNotRunningFinding(FirewalldCheck.State.FAILED);
        assertEquals(DoctorCommand.Status.FAIL, f.status());
        assertTrue(f.detail().contains("firewalld failed"), f.detail());
        assertFalse(f.detail().contains("inactive"), f.detail());
        assertTrue(f.remediation().description().contains("systemctl restart firewalld"));
    }

    @Test
    void anInactiveFirewalldIsEnabled() {
        var f = DoctorCommand.firewalldNotRunningFinding(FirewalldCheck.State.INACTIVE);
        assertEquals(DoctorCommand.Status.FAIL, f.status());
        assertTrue(f.detail().contains("installed but inactive"));
        assertTrue(f.remediation().description().startsWith("Enable firewalld"));
    }

    /** Direct rules only root may read are not checked, which is a note, not a warning right after init (#1441). */
    @Test
    void unreadableDirectRulesAreANoteNotAWarning() {
        var f = DoctorCommand.directRulesUnreadable();
        assertEquals(DoctorCommand.Status.NOTE, f.status());
        assertTrue(f.detail().contains("sudo firewall-cmd --direct --get-all-rules"), f.detail());
    }

    // ---- UFW forward block written into a comment (#1944) ----

    private static final String STOCK_FILTER = """
            *filter
            :ufw-before-input - [0:0]
            :ufw-before-forward - [0:0]
            -A ufw-before-forward -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
            # don't delete the 'COMMIT' line or these rules won't be processed
            COMMIT
            """;

    private static final DoctorCommand.Finding UFW_INACTIVE = DoctorCommand.Finding.fail(
            "Firewall not running", "(UFW installed but inactive)", null);

    private static String ufwBlockInComment() {
        return STOCK_FILTER.replace("the 'COMMIT' line",
                "the '" + UfwCheck.generateFilterInsert() + "\nCOMMIT' line");
    }

    @Test
    @Tag("docs")
    void anInactiveUfwWithItsForwardBlockInACommentPointsAtTheManualRepair() throws Exception {
        var findings = DoctorCommand.ufwFindings(ufwBlockInComment(), false, UFW_INACTIVE);
        assertEquals(2, findings.size(), findings.toString());
        var f = findings.getFirst();
        assertEquals(DoctorCommand.Status.FAIL, f.status());
        assertTrue(f.detail().contains("/etc/ufw/before.rules"), f.detail());
        assertTrue(f.remediation().description().contains(
                "MIGRATIONS.md#ufw-forward-rules-written-into-the-stock-commit-comment"),
                f.remediation().description());
        assertSame(UFW_INACTIVE, findings.get(1));
        assertTrue(Files.readString(Path.of("../docs/dev/MIGRATIONS.md"))
                .contains("\n## UFW forward rules written into the stock COMMIT comment\n"),
                "the anchor names a section of the migration notes");
    }

    @Test
    void anActiveUfwWithItsForwardBlockInACommentIsRepairedByInit() {
        var redirect = DoctorCommand.Finding.ok("Firewall PREROUTING redirect (UFW)", "443 -> 8443");
        var findings = DoctorCommand.ufwFindings(ufwBlockInComment(), true, redirect);
        assertEquals(2, findings.size(), findings.toString());
        assertEquals(DoctorCommand.Status.FAIL, findings.getFirst().status());
        assertTrue(findings.getFirst().remediation().description().contains("isx init"));
        assertSame(redirect, findings.get(1));
    }

    @Test
    void aStoppedFirewalldKeepsItsFindingAfterTheUfwOne() {
        var stopped = DoctorCommand.firewalldNotRunningFinding(FirewalldCheck.State.INACTIVE);
        var findings = DoctorCommand.ufwFindings(ufwBlockInComment(), false, stopped);
        assertEquals("UFW forward rules", findings.getFirst().label());
        assertSame(stopped, findings.get(1));
    }

    @Test
    void aForwardBlockOnItsOwnLinesAddsNoFinding() {
        var placed = UfwCheck.insertFilterRules(STOCK_FILTER, UfwCheck.generateFilterInsert());
        assertEquals(List.of(UFW_INACTIVE), DoctorCommand.ufwFindings(placed, false, UFW_INACTIVE));
        assertEquals(List.of(UFW_INACTIVE), DoctorCommand.ufwFindings(STOCK_FILTER, false, UFW_INACTIVE));
        assertEquals(List.of(UFW_INACTIVE), DoctorCommand.ufwFindings("", false, UFW_INACTIVE));
    }

    // ---- Proxy not-running finding selection ----

    @Test
    void proxyNotRunningConfigErrorShowsJournalHint() {
        var f = DoctorCommand.proxyNotRunningFinding(true, true);
        assertEquals(DoctorCommand.Status.FAIL, f.status());
        assertTrue(f.detail().contains("journalctl"), "should point to journal for details");
        assertTrue(f.detail().contains("incus-admin"), "should mention group membership");
        assertNotNull(f.remediation());
        assertTrue(f.remediation().description().contains("after fixing"));
    }

    @Test
    void proxyNotRunningInstalledButInactiveIsGeneric() {
        var f = DoctorCommand.proxyNotRunningFinding(true, false);
        assertEquals(DoctorCommand.Status.FAIL, f.status());
        assertTrue(f.detail().contains("installed but inactive"));
        assertNotNull(f.remediation());
        assertFalse(f.detail().contains("journalctl"),
                "generic inactive should not suggest journal inspection");
    }

    @Test
    void proxyNotRunningNotInstalledSuggestsInit() {
        var f = DoctorCommand.proxyNotRunningFinding(false, false);
        assertEquals(DoctorCommand.Status.FAIL, f.status());
        assertNotNull(f.remediation());
        assertTrue(f.remediation().description().contains("isx init"));
    }

    @Test
    void proxyNotRunningWithoutBinarySaysSoInsteadOfSuggestingRestart() {
        // A JBang install of `isx` alone has no isx-proxy: restarting a service whose binary does
        // not exist can never work, so the finding must name the missing binary (issue #701).
        var f = DoctorCommand.missingProxyBinaryFinding();
        assertEquals(DoctorCommand.Status.FAIL, f.status());
        assertTrue(f.detail().contains("isx-proxy"));
        assertNotNull(f.remediation());
        assertEquals(dev.incusspawn.proxy.ProxyService.MISSING_PROXY_REMEDIATION,
                f.remediation().description(),
                "doctor and 'isx proxy start' must offer the same command");
        assertFalse(f.remediation().description().contains("Restart"),
                "restarting cannot fix a missing binary");
    }

    @Test
    void anotherUsersProxyOnThePortsFailsAndAnOrphanWarns() {
        // #1570: the service cannot bind while another proxy holds the ports.
        assertNull(DoctorCommand.holderFinding(null));
        var other = DoctorCommand.holderFinding(new dev.incusspawn.proxy.ProxyHolder(80914, "0.3.12", "runner", true));
        assertEquals(DoctorCommand.Status.FAIL, other.status());
        assertTrue(other.detail().contains("isx-proxy 0.3.12, pid 80914, owned by user runner"), other.detail());
        assertTrue(other.remediation().description().contains("runner"));
        var orphan = DoctorCommand.holderFinding(new dev.incusspawn.proxy.ProxyHolder(4242, "0.3.12", "me", false));
        assertEquals(DoctorCommand.Status.WARN, orphan.status());
        assertTrue(orphan.remediation().description().contains("kill 4242"));
    }

    @Test
    void exposedHostPortsAreReportedAndOneHeldBySomethingElseWarns() {
        assertNull(DoctorCommand.exposedHostPortsFinding(List.of(), List.of(), null));
        var info = new dev.incusspawn.proxy.ProxyHealthCheck.ProxyInfo("1", "a", "r", "f", false, true, "", 1, "1",
                List.of());
        var listed = DoctorCommand.exposedHostPortsFinding(List.of(11434), List.of(), info);
        assertEquals(DoctorCommand.Status.NOTE, listed.status());
        assertTrue(listed.detail().contains("11434 at host.isx.internal"), listed.detail());

        var held = DoctorCommand.exposedHostPortsFinding(List.of(22, 11434), List.of(),
                new dev.incusspawn.proxy.ProxyHealthCheck.ProxyInfo("1", "a", "r", "f", false, true, "", 1, "1",
                        List.of(22, 8080)));
        assertEquals(DoctorCommand.Status.WARN, held.status());
        assertTrue(held.detail().startsWith("22 could not be bound"), "8080 is no longer listed: " + held.detail());

        var down = DoctorCommand.exposedHostPortsFinding(List.of(11434), List.of(), null);
        assertEquals(DoctorCommand.Status.NOTE, down.status());
        assertTrue(down.detail().endsWith("relayed once the proxy runs"), "nothing relays while the proxy is down: " + down.detail());

        var refused = DoctorCommand.exposedHostPortsFinding(List.of(),
                List.of("Ignoring exposed host port 443 in config.yaml: isx serves it on the address the proxy listens on"), info);
        assertEquals(DoctorCommand.Status.WARN, refused.status(), "a port the config lists but isx refuses is said");
        assertTrue(refused.detail().contains("443"), refused.detail());

        var heldAndRefused = DoctorCommand.exposedHostPortsFinding(List.of(22),
                List.of("Ignoring exposed host port 443 in config.yaml: isx serves it on the address the proxy listens on"),
                new dev.incusspawn.proxy.ProxyHealthCheck.ProxyInfo("1", "a", "r", "f", false, true, "", 1, "1",
                        List.of(22)));
        assertTrue(heldAndRefused.detail().startsWith("22 could not be bound"),
                "a refused port does not hide a held one: " + heldAndRefused.detail());
        assertTrue(heldAndRefused.detail().contains("443"), heldAndRefused.detail());
    }

    // ---- Option implications ----

    @Test
    void bundleRunsTheDeepChecks() {
        var command = new DoctorCommand();
        command.bundle = true;
        assertTrue(command.deepChecks(), "--bundle must collect the per-instance probes");
    }

    @Test
    void deepRunsThemWithoutABundle() {
        var command = new DoctorCommand();
        command.deep = true;
        assertTrue(command.deepChecks());
    }

    @Test
    void plainDoctorStaysCheap() {
        assertFalse(new DoctorCommand().deepChecks());
    }

    // ---- Redaction summary wording ----

    @Test
    void redactionSummaryNamesTheKeysItRemoved() {
        var result = new SupportBundle.Result(Path.of("/tmp/b.tar.gz"),
                List.of("claude.oauthToken", "github.token"), 3, 12);
        var summary = DoctorCommand.describeRedactions(result);
        assertTrue(summary.contains("2 config keys"), summary);
        assertTrue(summary.contains("claude.oauthToken"), summary);
        assertTrue(summary.contains("3 values in the logs"), summary);
    }

    @Test
    void redactionSummarySaysSoWhenThereWasNothingToRedact() {
        var result = new SupportBundle.Result(Path.of("/tmp/b.tar.gz"), List.of(), 0, 12);
        assertEquals("No credentials were found to redact.", DoctorCommand.describeRedactions(result));
    }

    @Test
    void redactionSummaryHandlesASingleKey() {
        var result = new SupportBundle.Result(Path.of("/tmp/b.tar.gz"),
                List.of("github.token"), 0, 12);
        var summary = DoctorCommand.describeRedactions(result);
        assertTrue(summary.contains("1 config key ("), summary);
        assertFalse(summary.contains("in the logs"), "nothing was scrubbed: " + summary);
    }

    // ---- Bridge DNS finding by proxy state (#839) ----

    private static final java.util.Set<String> DNS_DOMAINS = java.util.Set.of("github.com", "api.anthropic.com");

    private static String overridesFor(String... domains) {
        var sb = new StringBuilder("# BEGIN incus-spawn intercepted domains (rewritten by isx)\n");
        for (var d : domains) sb.append("address=/").append(d).append("/10.1.2.1\nlocal=/").append(d).append("/\n");
        return sb.append("# END incus-spawn intercepted domains").toString();
    }

    private static DoctorCommand.Finding dnsFinding(ProxyHealthCheck.ProxyStatus proxy, String overrides) {
        return DoctorCommand.bridgeDnsFinding(proxy,
                BridgeDns.status(overrides, DNS_DOMAINS, "10.1.2.1"), DNS_DOMAINS, () -> {});
    }

    @Test
    void bridgeDnsWithNoOverridesFailsWhileTheProxyRuns() {
        var f = dnsFinding(ProxyHealthCheck.ProxyStatus.RUNNING, "");
        assertEquals(DoctorCommand.Status.FAIL, f.status());
        assertTrue(f.detail().contains("none configured"), f.detail());
        assertNotNull(f.remediation(), "the proxy is listening, so rewriting the overrides is safe");
    }

    @Test
    void bridgeDnsWithOnlyUserLinesCountsAsNone() {
        var f = dnsFinding(ProxyHealthCheck.ProxyStatus.RUNNING, "server=/corp.example/10.0.0.53");
        assertEquals(DoctorCommand.Status.FAIL, f.status());
    }

    @Test
    void bridgeDnsWithNoOverridesStillRepairsWhileTheProxyWaitsForDns() {
        var f = dnsFinding(ProxyHealthCheck.ProxyStatus.WAITING_FOR_DNS, "");
        assertEquals(DoctorCommand.Status.FAIL, f.status());
        assertNotNull(f.remediation());
    }

    @Test
    void bridgeDnsIsNotRepairedWhileTheProxyIsOnAnOldGateway() {
        // #919: the overrides point where the proxy listens; the bridge no longer has that address.
        var f = dnsFinding(ProxyHealthCheck.ProxyStatus.STALE_GATEWAY, overridesFor("github.com"));
        assertEquals(DoctorCommand.Status.NOTE, f.status());
        assertNull(f.remediation());
    }

    @Test
    void aProxyOnAnOldGatewayIsToldToRestartNotToStart() {
        // Only a running service is restarted for the user: an installed service beside a
        // foreground proxy would start next to it and leave the stale one in place.
        var service = DoctorCommand.staleGatewayFinding(true);
        assertEquals(DoctorCommand.Status.FAIL, service.status());
        assertNotNull(service.remediation().action());
        var foreground = DoctorCommand.staleGatewayFinding(false);
        assertTrue(foreground.remediation().description().contains("isx proxy stop && isx proxy start"));
    }

    @Test
    void bridgeDnsIsNotRepairedWhileTheProxyIsDown() {
        // Writing overrides now would point every intercepted domain at a gateway where nothing
        // listens, which is worse than bypassing the proxy.
        for (var proxy : java.util.List.of(ProxyHealthCheck.ProxyStatus.NOT_RUNNING,
                ProxyHealthCheck.ProxyStatus.STALE_DNS)) {
            for (var overrides : java.util.List.of("", overridesFor("github.com"))) {
                var f = dnsFinding(proxy, overrides);
                assertEquals(DoctorCommand.Status.NOTE, f.status(), proxy + " / " + overrides);
                assertTrue(f.detail().contains("proxy is not running"), f.detail());
                assertNull(f.remediation(), proxy + " must not offer to write DNS");
            }
        }
    }

    @Test
    void bridgeDnsWithEveryDomainIsOk() {
        var f = dnsFinding(ProxyHealthCheck.ProxyStatus.RUNNING, overridesFor("github.com", "api.anthropic.com"));
        assertEquals(DoctorCommand.Status.OK, f.status());
        assertNull(f.remediation());
    }

    @Test
    void bridgeDnsMissingSomeDomainsWarnsAndNamesThem() {
        var f = dnsFinding(ProxyHealthCheck.ProxyStatus.RUNNING, overridesFor("github.com"));
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertTrue(f.detail().contains("missing: api.anthropic.com"), f.detail());
        assertNotNull(f.remediation());
    }

    @Test
    void bridgeDnsForAnOldGatewayFailsAndOffersTheRewrite() {
        // The bridge moved to 10.1.5.1 since the overrides were written for 10.1.2.1 (#840):
        // every intercepted domain resolves to where the proxy no longer listens.
        var status = BridgeDns.status(overridesFor("github.com", "api.anthropic.com"), DNS_DOMAINS, "10.1.5.1");
        var f = DoctorCommand.bridgeDnsFinding(ProxyHealthCheck.ProxyStatus.RUNNING, status, DNS_DOMAINS, () -> {});
        assertEquals(DoctorCommand.Status.FAIL, f.status());
        assertTrue(f.detail().contains("the gateway 10.1.5.1: api.anthropic.com, github.com"), f.detail());
        assertNotNull(f.remediation());
    }

    // ---- Pool subvolumes vs Incus records (#717) ----

    private static InstanceSubvolumes.Scan subvolScan(List<String> orphans, List<String> dangling) {
        var kind = InstanceSubvolumes.Kind.CONTAINER;
        return new InstanceSubvolumes.Scan("cow",
                new java.util.TreeSet<>(orphans.stream().map(n -> new InstanceSubvolumes.Ref(kind, n)).toList()),
                new java.util.TreeSet<>(dangling.stream().map(n -> new InstanceSubvolumes.Ref(kind, n)).toList()));
    }

    @Test
    void matchingSubvolumesAreOk() {
        var findings = DoctorCommand.subvolumeFindings(subvolScan(List.of(), List.of()), Set.of(), Map.of(), false);
        assertEquals(1, findings.size());
        assertEquals(DoctorCommand.Status.OK, findings.getFirst().status());
    }

    @Test
    void anOrphanNamedAfterATemplateFailsBecauseItBlocksTheBuild() {
        for (var orphan : List.of("tpl-minimal", "tpl-minimal-rebuilding")) {
            var f = DoctorCommand.subvolumeFindings(subvolScan(List.of(orphan), List.of()),
                    Set.of("tpl-minimal"), Map.of(orphan, 5L * DoctorCommand.GIB), false).getFirst();
            assertEquals(DoctorCommand.Status.FAIL, f.status(), orphan);
            assertTrue(f.detail().contains("containers/" + orphan + " (5.0G referenced)"), f.detail());
            assertTrue(f.detail().contains("builds of tpl-minimal will fail"), f.detail());
        }
    }

    @Test
    void anOrphanNoTemplateNeedsOnlyWarns() {
        var f = DoctorCommand.subvolumeFindings(subvolScan(List.of("isx-old-branch"), List.of()),
                Set.of("tpl-minimal"), Map.of(), false).getFirst();
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertFalse(f.detail().contains("will fail"));
    }

    @Test
    void orphanRemediationIsASuggestionNeverAnAction() {
        var linux = DoctorCommand.subvolumeFindings(subvolScan(List.of("a", "b"), List.of()),
                Set.of(), Map.of(), false).getFirst().remediation();
        assertNull(linux.action(), "doctor must not delete data on its own");
        assertTrue(linux.destructive());
        assertTrue(linux.description().contains("sudo btrfs subvolume delete -R "
                + "/var/lib/incus/storage-pools/cow/containers/a /var/lib/incus/storage-pools/cow/containers/b"),
                linux.description());

        var mac = DoctorCommand.subvolumeFindings(subvolScan(List.of("a"), List.of()),
                Set.of(), Map.of(), true).getFirst().remediation();
        assertNull(mac.action());
        assertTrue(mac.description().contains("isx vm reset"), mac.description());
    }

    @Test
    void aDanglingRecordWarnsWithoutOfferingToDeleteIt() {
        var findings = DoctorCommand.subvolumeFindings(subvolScan(List.of(), List.of("tpl-isx-rebuilding")),
                Set.of("tpl-isx"), Map.of(), false);
        assertEquals(1, findings.size());
        var f = findings.getFirst();
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertTrue(f.detail().contains("tpl-isx-rebuilding"));
        assertNull(f.remediation());
    }

    // ---- deleteOrphans, the live macOS agent action (#874) ----

    @Test
    void deleteOrphansDeletesEveryOneThroughTheAgent() throws Exception {
        var scan = subvolScan(List.of("a", "b"), List.of());
        var asked = new ArrayList<String>();
        DoctorCommand.deleteOrphans(scan, ref -> {
            asked.add(ref.path());
            return java.util.Optional.of("deleted");
        });
        assertEquals(List.of("containers/a", "containers/b"), asked);
    }

    @Test
    void deleteOrphansStopsAtTheFirstUnknownVerbWithoutTryingTheRest() {
        var scan = subvolScan(List.of("a", "b"), List.of());
        var asked = new ArrayList<String>();
        var failure = assertThrows(java.io.IOException.class, () -> DoctorCommand.deleteOrphans(scan, ref -> {
            asked.add(ref.path());
            return java.util.Optional.of("error: unknown verb");
        }));
        assertEquals(List.of("containers/a"), asked, "an appliance too old for one orphan is too old for all of them");
        assertTrue(failure.getMessage().contains("isx vm reset"), failure.getMessage());
    }

    @Test
    void deleteOrphansCollectsEveryFailureInsteadOfStoppingAtTheFirst() {
        var scan = subvolScan(List.of("a", "b"), List.of());
        var failure = assertThrows(java.io.IOException.class, () -> DoctorCommand.deleteOrphans(scan,
                ref -> java.util.Optional.of("error: still referenced by Incus")));
        assertTrue(failure.getMessage().contains("containers/a"), failure.getMessage());
        assertTrue(failure.getMessage().contains("containers/b"), failure.getMessage());
    }

    /**
     * No answer at all (VmAgentClient.send's own timeout, or an unreachable agent) is not the
     * same claim as the appliance's own literal "error: unknown verb": a slow-but-working verb
     * on a later orphan would otherwise be told it is unsupported while its delete finishes
     * inside the VM anyway (review on #874).
     */
    @Test
    void deleteOrphansDoesNotTreatATimeoutAsUnsupported() {
        var scan = subvolScan(List.of("a"), List.of());
        var failure = assertThrows(java.io.IOException.class,
                () -> DoctorCommand.deleteOrphans(scan, ref -> java.util.Optional.empty()));
        assertFalse(failure.getMessage().contains("isx vm reset"), failure.getMessage());
        assertTrue(failure.getMessage().contains("did not answer"), failure.getMessage());
    }


    // ---- Bridge subnet conflict (#1203) ----

    private static final DoctorCommand.Action MOVE_NOT_RUN = () -> fail("the finding must not run its remedy");

    @Test
    void bridgeClearOfHostRoutesIsOk() {
        var f = DoctorCommand.bridgeSubnetFinding(
                new BridgeSubnetCheck.ConflictResult(false, null, "172.20.0.1/24", null), MOVE_NOT_RUN);
        assertEquals(DoctorCommand.Status.OK, f.status());
        assertNull(f.remediation());
    }

    @Test
    void bridgeConflictOffersToMoveTheBridgeWithoutInit() throws Exception {
        var moved = new boolean[1];
        var f = DoctorCommand.bridgeSubnetFinding(new BridgeSubnetCheck.ConflictResult(true,
                "10.0.0.0/8 via 10.64.0.1 dev tun0", "10.166.11.1/24", "172.20.0.1/24"), () -> moved[0] = true);
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertTrue(f.detail().contains("10.0.0.0/8"), f.detail());
        var r = f.remediation();
        assertNotNull(r.action(), "a free subnet must be offered as a fix doctor applies");
        assertTrue(r.destructive(), "it stops and starts running instances and changes the firewall");
        assertTrue(r.description().contains("172.20.0.1/24"), r.description());
        assertFalse(r.description().contains("isx init"), r.description());
        r.action().run();
        assertTrue(moved[0]);
    }

    @Test
    void bridgeConflictWithNoFreeSubnetOffersNoFixAndNoIncusCommand() {
        var f = DoctorCommand.bridgeSubnetFinding(new BridgeSubnetCheck.ConflictResult(true,
                "172.16.0.0/12 via 10.0.0.1 dev tun0", "172.20.0.1/24", null), MOVE_NOT_RUN);
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertNull(f.remediation().action());
        assertFalse(f.remediation().description().contains("incus "), f.remediation().description());
    }

    @Test
    void bridgeWithoutIpv4IsOk() {
        var f = DoctorCommand.bridgeSubnetFinding(
                new BridgeSubnetCheck.ConflictResult(false, null, null, null), MOVE_NOT_RUN);
        assertEquals(DoctorCommand.Status.OK, f.status());
        assertEquals("(no IPv4 address)", f.detail());
    }
}
