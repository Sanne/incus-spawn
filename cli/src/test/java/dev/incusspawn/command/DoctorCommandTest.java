package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.InstanceSubvolumes;
import dev.incusspawn.proxy.BridgeDns;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ToolProxyResolver;
import dev.incusspawn.vm.VmManager;
import org.junit.jupiter.api.Test;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DoctorCommandTest {

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

    // ---- Storage pool usage evaluation ----

    @Test
    void storagePoolOkWhenBelowThreshold() {
        var f = DoctorCommand.evaluateStorageUsage("default", "default pool: 5000MiB used / 50000MiB total (10% full)");
        assertEquals(DoctorCommand.Status.OK, f.status());
    }

    @Test
    void storagePoolWarnsWhenAbove90Percent() {
        var f = DoctorCommand.evaluateStorageUsage("default", "default pool: 46000MiB used / 50000MiB total (92% full)");
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertTrue(f.label().contains("nearly full"));
        assertNull(f.remediation(), "overall finding should not have an action — specific findings do");
        assertTrue(f.detail().contains("isx clean pool"), "detail should suggest isx clean pool");
    }

    @Test
    void storagePoolOkAtExactly90Percent() {
        var f = DoctorCommand.evaluateStorageUsage("default", "default pool: 45000MiB used / 50000MiB total (90% full)");
        assertEquals(DoctorCommand.Status.OK, f.status());
    }

    @Test
    void storagePoolHandlesMalformedUsage() {
        var f = DoctorCommand.evaluateStorageUsage("default", "(no space info)");
        assertEquals(DoctorCommand.Status.OK, f.status());
    }

    @Test
    void storagePoolHandlesEmptyUsage() {
        var f = DoctorCommand.evaluateStorageUsage("default", "");
        assertEquals(DoctorCommand.Status.OK, f.status());
    }

    // ---- Pool size evaluation ----

    @Test
    void undersizedPoolWarnsWithResizeRemediation() {
        long thirtyGiB = 30L * 1024 * 1024 * 1024;
        var usage = new IncusClient.PoolUsage(thirtyGiB * 96 / 100, thirtyGiB);
        var f = DoctorCommand.evaluatePoolSize("cow", usage, null);
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
        var f = DoctorCommand.evaluatePoolSize("cow", usage, null);
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
        assertNull(DoctorCommand.evaluatePoolSize("cow", usage, null));
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

    private static List<ToolProxyResolver.UnresolvedToolProxy> unresolved(String... pairs) {
        var list = new ArrayList<ToolProxyResolver.UnresolvedToolProxy>();
        for (int i = 0; i < pairs.length; i += 2) {
            list.add(new ToolProxyResolver.UnresolvedToolProxy(pairs[i], pairs[i + 1]));
        }
        return list;
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

    @Test
    void credentialsOkWhenNothingIsMissing() {
        var findings = DoctorCommand.credentialFindings(true, List.of(), name -> true);

        assertEquals(1, findings.size());
        assertEquals(DoctorCommand.Status.OK, findings.getFirst().status());
        assertEquals("configured", findings.getFirst().detail());
    }

    @Test
    void unconfiguredCredentialForAnUnusedToolIsANeutralNote() {
        var findings = DoctorCommand.credentialFindings(true, unresolved("codex", "api-key"), name -> false);

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
        var findings = DoctorCommand.credentialFindings(true, unresolved("codex", "api-key"),
                name -> name.equals("codex"));

        assertEquals(1, findings.size());
        assertEquals(DoctorCommand.Status.WARN, findings.getFirst().status());
        assertTrue(findings.getFirst().detail().contains("codex api-key"));
        assertNotNull(findings.getFirst().remediation());
    }

    @Test
    void usedAndUnusedToolsAreReportedSeparately() {
        var findings = DoctorCommand.credentialFindings(true,
                unresolved("codex", "api-key", "bob", "api-key"), name -> name.equals("codex"));

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
        var findings = DoctorCommand.credentialFindings(false, List.of(), name -> false);

        assertEquals(1, findings.size());
        assertEquals(DoctorCommand.Status.WARN, findings.getFirst().status());
        assertTrue(findings.getFirst().detail().contains("claude"));
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
        var mapper = new ObjectMapper();
        var root = mapper.readTree(json);
        assertTrue(root.isArray());
        assertEquals(3, root.size());

        assertEquals("OK", root.get(0).get("status").asText());
        assertEquals("Test label", root.get(0).get("label").asText());
        assertEquals("some detail", root.get(0).get("detail").asText());
        assertFalse(root.get(0).has("remediation"));

        assertEquals("WARN", root.get(1).get("status").asText());
        assertEquals("fix it", root.get(1).get("remediation").asText());

        assertEquals("FAIL", root.get(2).get("status").asText());
        assertEquals("", root.get(2).get("detail").asText());
    }

    @Test
    void findingsToJsonEmptyListProducesEmptyArray() throws Exception {
        var json = DoctorCommand.findingsToJson(List.of());
        assertEquals("[ ]", json);
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

}
