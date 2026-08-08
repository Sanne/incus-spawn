package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FirewalldCheckTest {

    private static final String TYPICAL_OUTPUT = """
            ipv4 filter FORWARD 0 -i incusbr0 -j ACCEPT
            ipv4 filter FORWARD 0 -o incusbr0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
            ipv4 nat PREROUTING 0 -i incusbr0 -d 10.166.11.1 -p tcp --dport 443 -j REDIRECT --to-port 18443
            """;

    // ---- isPreRoutingRulePresent ----

    @Test
    void preRoutingRuleDetected() {
        assertTrue(FirewalldCheck.isPreRoutingRulePresent(TYPICAL_OUTPUT, 18443, "10.166.11.1"));
    }

    @Test
    void preRoutingRuleNotMatchedWithWrongGateway() {
        assertFalse(FirewalldCheck.isPreRoutingRulePresent(TYPICAL_OUTPUT, 18443, "10.99.99.1"));
    }

    @Test
    void preRoutingRuleMissing() {
        var output = """
                ipv4 filter FORWARD 0 -i incusbr0 -j ACCEPT
                """;
        assertFalse(FirewalldCheck.isPreRoutingRulePresent(output, 18443, "10.166.11.1"));
    }

    @Test
    void preRoutingLegacyBroadRuleNotMatched() {
        var output = """
                ipv4 nat PREROUTING 0 -i incusbr0 -p tcp --dport 443 -j REDIRECT --to-port 18443
                """;
        assertFalse(FirewalldCheck.isPreRoutingRulePresent(output, 18443, "10.166.11.1"));
    }

    @Test
    void preRoutingWrongPortNotMatched() {
        var output = """
                ipv4 nat PREROUTING 0 -i incusbr0 -d 10.166.11.1 -p tcp --dport 443 -j REDIRECT --to-port 9999
                """;
        assertFalse(FirewalldCheck.isPreRoutingRulePresent(output, 18443, "10.166.11.1"));
    }

    @Test
    void preRoutingEmptyOutput() {
        assertFalse(FirewalldCheck.isPreRoutingRulePresent("", 18443, "10.166.11.1"));
    }

    // ---- extractRedirectGatewayIp ----

    @Test
    void extractGatewayIpFromTypicalOutput() {
        assertEquals("10.166.11.1", FirewalldCheck.extractRedirectGatewayIp(TYPICAL_OUTPUT, 18443));
    }

    @Test
    void extractGatewayIpReturnsNullWhenNoRule() {
        var output = """
                ipv4 filter FORWARD 0 -i incusbr0 -j ACCEPT
                """;
        assertNull(FirewalldCheck.extractRedirectGatewayIp(output, 18443));
    }

    @Test
    void extractGatewayIpReturnsNullForEmptyOutput() {
        assertNull(FirewalldCheck.extractRedirectGatewayIp("", 18443));
    }

    @Test
    void extractGatewayIpReturnsNullForWrongPort() {
        var output = """
                ipv4 nat PREROUTING 0 -i incusbr0 -d 10.166.11.1 -p tcp --dport 443 -j REDIRECT --to-port 9999
                """;
        assertNull(FirewalldCheck.extractRedirectGatewayIp(output, 18443));
    }

    // ---- isForwardRulePresent ----

    @Test
    void forwardInRuleDetected() {
        assertTrue(FirewalldCheck.isForwardRulePresent(TYPICAL_OUTPUT, "-i", "incusbr0"));
    }

    @Test
    void forwardOutRuleDetected() {
        assertTrue(FirewalldCheck.isForwardRulePresent(TYPICAL_OUTPUT, "-o", "incusbr0"));
    }

    @Test
    void forwardRuleMissing() {
        var output = """
                ipv4 nat PREROUTING 0 -i incusbr0 -d 10.166.11.1 -p tcp --dport 443 -j REDIRECT --to-port 18443
                """;
        assertFalse(FirewalldCheck.isForwardRulePresent(output, "-i", "incusbr0"));
    }

    @Test
    void forwardRuleWrongInterface() {
        assertFalse(FirewalldCheck.isForwardRulePresent(TYPICAL_OUTPUT, "-i", "docker0"));
    }

    @Test
    void forwardInNotMatchedByOutRule() {
        var output = """
                ipv4 filter FORWARD 0 -o incusbr0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
                """;
        assertFalse(FirewalldCheck.isForwardRulePresent(output, "-i", "incusbr0"));
    }

    @Test
    void forwardOutNotMatchedByInRule() {
        var output = """
                ipv4 filter FORWARD 0 -i incusbr0 -j ACCEPT
                """;
        assertFalse(FirewalldCheck.isForwardRulePresent(output, "-o", "incusbr0"));
    }

    @Test
    void forwardEmptyOutput() {
        assertFalse(FirewalldCheck.isForwardRulePresent("", "-i", "incusbr0"));
    }
}
