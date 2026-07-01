package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.incusspawn.vm.VmManager;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void leakLayerLocatesVfkitWhenGuestCountStaysLow() {
        assertEquals(DoctorCommand.LeakLayer.VFKIT, DoctorCommand.leakLayer(300, 5));
        assertEquals(DoctorCommand.LeakLayer.VFKIT, DoctorCommand.leakLayer(100, 50), "boundary: guest*2 == host");
    }

    @Test
    void leakLayerLocatesForwarderWhenBothCountsClimb() {
        assertEquals(DoctorCommand.LeakLayer.FORWARDER, DoctorCommand.leakLayer(300, 280));
        assertEquals(DoctorCommand.LeakLayer.FORWARDER, DoctorCommand.leakLayer(100, 51));
    }

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

    // ---- iptables PREROUTING rule detection ----

    @Test
    void iptablesRuleDetectedInFirewalldOutput() {
        var output = """
                ipv4 filter FORWARD 0 -o incusbr0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
                ipv4 nat PREROUTING 0 -i incusbr0 -d 10.166.11.1 -p tcp --dport 443 -j REDIRECT --to-port 18443
                """;
        assertTrue(DoctorCommand.isPreRoutingRulePresent(output, 18443));
    }

    @Test
    void iptablesRuleMissingInFirewalldOutput() {
        var output = """
                ipv4 filter FORWARD 0 -o incusbr0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
                """;
        assertFalse(DoctorCommand.isPreRoutingRulePresent(output, 18443));
    }

    @Test
    void iptablesRuleNotMatchedWithDifferentPort() {
        var output = """
                ipv4 nat PREROUTING 0 -i incusbr0 -d 10.166.11.1 -p tcp --dport 443 -j REDIRECT --to-port 9999
                """;
        assertFalse(DoctorCommand.isPreRoutingRulePresent(output, 18443));
    }

    @Test
    void iptablesRuleNotMatchedWithoutIncusbr0() {
        var output = """
                ipv4 nat PREROUTING 0 -i docker0 -d 10.166.11.1 -p tcp --dport 443 -j REDIRECT --to-port 18443
                """;
        assertFalse(DoctorCommand.isPreRoutingRulePresent(output, 18443));
    }

    @Test
    void iptablesRuleNotMatchedWithDifferentDport() {
        var output = """
                ipv4 nat PREROUTING 0 -i incusbr0 -d 10.166.11.1 -p tcp --dport 8080 -j REDIRECT --to-port 18443
                """;
        assertFalse(DoctorCommand.isPreRoutingRulePresent(output, 18443));
    }

    @Test
    void iptablesEmptyOutputReturnsFalse() {
        assertFalse(DoctorCommand.isPreRoutingRulePresent("", 18443));
    }

    // ---- Config permissions evaluation ----

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

    // ---- Sanitized config structural redaction ----

    @Test
    void sanitizedConfigRemovesSecrets() {
        var yaml = DoctorCommand.sanitizedConfig();
        assertFalse(yaml.contains("sk-ant-"), "API keys must not appear in sanitized config");
        assertFalse(yaml.contains("ghp_"), "GitHub tokens must not appear in sanitized config");
        assertFalse(yaml.contains("sk-ant-oat"), "OAuth tokens must not appear in sanitized config");
        // Structure should still be present
        assertTrue(yaml.contains("claude") || yaml.contains("github"),
                "Config structure should be preserved");
    }
}
