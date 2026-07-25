package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UfwCheckTest {

    private static final String TYPICAL_BEFORE_RULES = """
            #
            # rules.before
            #
            # Rules that should be run before the ufw command line added rules.
            #
            *filter
            :ufw-before-input - [0:0]
            :ufw-before-output - [0:0]
            :ufw-before-forward - [0:0]

            # quickly process packets for which we already have a connection
            -A ufw-before-input -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
            -A ufw-before-output -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
            -A ufw-before-forward -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT

            # don't delete the 'COMMIT' line or these rules won't be processed
            COMMIT
            """;

    // ---- generateNatBlock ----

    @Test
    void generateNatBlockContainsAllRules() {
        var block = UfwCheck.generateNatBlock("10.166.11.1", "10.166.11.0/24", 443, 18443);
        assertTrue(block.contains("*nat"));
        assertTrue(block.contains(":PREROUTING ACCEPT [0:0]"));
        assertTrue(block.contains(":POSTROUTING ACCEPT [0:0]"));
        assertTrue(block.contains("-A POSTROUTING -s 10.166.11.0/24 ! -d 10.166.11.0/24 -j MASQUERADE"));
        assertTrue(block.contains("-A PREROUTING -i incusbr0 -d 10.166.11.1 -p tcp --dport 443 -j REDIRECT --to-port 18443"));
        assertTrue(block.contains("COMMIT"));
        assertTrue(block.startsWith(UfwCheck.MARKER_NAT_BEGIN));
        assertTrue(block.endsWith(UfwCheck.MARKER_NAT_END));
    }

    @Test
    void generateNatBlockWithoutRedirectOmitsPrerouting() {
        var block = UfwCheck.generateNatBlockWithoutRedirect("10.166.11.0/24");
        assertTrue(block.contains("MASQUERADE"));
        assertFalse(block.contains("PREROUTING -i"));
        assertFalse(block.contains("REDIRECT"));
    }

    // ---- generateFilterInsert ----

    @Test
    void generateFilterInsertContainsForwardRules() {
        var insert = UfwCheck.generateFilterInsert();
        assertTrue(insert.contains("-A ufw-before-forward -i incusbr0 -j ACCEPT"));
        assertTrue(insert.contains("-A ufw-before-forward -o incusbr0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT"));
        assertTrue(insert.startsWith(UfwCheck.MARKER_FWD_BEGIN));
        assertTrue(insert.endsWith(UfwCheck.MARKER_FWD_END));
    }

    // ---- insertNatBlock ----

    @Test
    void insertNatBlockBeforeFilter() {
        var natBlock = UfwCheck.generateNatBlock("10.166.11.1", "10.166.11.0/24", 443, 18443);
        var result = UfwCheck.insertNatBlock(TYPICAL_BEFORE_RULES, natBlock);

        int natIdx = result.indexOf(UfwCheck.MARKER_NAT_BEGIN);
        int filterIdx = result.indexOf("*filter");
        assertTrue(natIdx >= 0, "NAT block should be present");
        assertTrue(filterIdx > natIdx, "NAT block should appear before *filter");
    }

    @Test
    void insertNatBlockIsIdempotent() {
        var natBlock = UfwCheck.generateNatBlock("10.166.11.1", "10.166.11.0/24", 443, 18443);
        var first = UfwCheck.insertNatBlock(TYPICAL_BEFORE_RULES, natBlock);
        var second = UfwCheck.insertNatBlock(first, natBlock);
        assertEquals(first, second);
    }

    @Test
    void insertNatBlockReplacesExisting() {
        var oldBlock = UfwCheck.generateNatBlockWithoutRedirect("10.166.11.0/24");
        var withOld = UfwCheck.insertNatBlock(TYPICAL_BEFORE_RULES, oldBlock);
        assertFalse(UfwCheck.hasPreRoutingRedirect(withOld, 18443));

        var newBlock = UfwCheck.generateNatBlock("10.166.11.1", "10.166.11.0/24", 443, 18443);
        var withNew = UfwCheck.insertNatBlock(withOld, newBlock);
        assertTrue(UfwCheck.hasPreRoutingRedirect(withNew, 18443));

        // Only one NAT block should exist
        int firstIdx = withNew.indexOf(UfwCheck.MARKER_NAT_BEGIN);
        int secondIdx = withNew.indexOf(UfwCheck.MARKER_NAT_BEGIN, firstIdx + 1);
        assertEquals(-1, secondIdx, "Should not have duplicate NAT blocks");
    }

    @Test
    void insertNatBlockDoesNotCorruptExistingForwardBlock() {
        // Forward block inserted first, then NAT block added — markers must not collide
        var filterInsert = UfwCheck.generateFilterInsert();
        var withForward = UfwCheck.insertFilterRules(TYPICAL_BEFORE_RULES, filterInsert);
        assertTrue(UfwCheck.hasForwardRules(withForward));
        assertFalse(UfwCheck.hasNatBlock(withForward), "NAT block should not be detected when only forward block exists");

        var natBlock = UfwCheck.generateNatBlock("10.166.11.1", "10.166.11.0/24", 443, 18443);
        var result = UfwCheck.insertNatBlock(withForward, natBlock);

        assertTrue(UfwCheck.hasNatBlock(result), "NAT block should be present");
        assertTrue(UfwCheck.hasForwardRules(result), "Forward block should still be intact");
        assertTrue(UfwCheck.hasPreRoutingRedirect(result, 18443));
        assertFalse(result.contains("-forward\n# END incus-spawn-nat"), "Forward marker should not be partially consumed");
    }

    // ---- insertFilterRules ----

    @Test
    void insertFilterRulesBeforeCommit() {
        var filterInsert = UfwCheck.generateFilterInsert();
        var result = UfwCheck.insertFilterRules(TYPICAL_BEFORE_RULES, filterInsert);

        int fwdIdx = result.indexOf(UfwCheck.MARKER_FWD_BEGIN);
        int commitIdx = result.indexOf("COMMIT");
        assertTrue(fwdIdx >= 0, "Forward block should be present");
        assertTrue(commitIdx > fwdIdx, "Forward block should appear before COMMIT");
    }

    @Test
    void insertFilterRulesIsIdempotent() {
        var filterInsert = UfwCheck.generateFilterInsert();
        var first = UfwCheck.insertFilterRules(TYPICAL_BEFORE_RULES, filterInsert);
        var second = UfwCheck.insertFilterRules(first, filterInsert);
        assertEquals(first, second);
    }

    // ---- hasPreRoutingRedirect ----

    @Test
    void hasPreRoutingRedirectDetectsRule() {
        var natBlock = UfwCheck.generateNatBlock("10.166.11.1", "10.166.11.0/24", 443, 18443);
        var content = UfwCheck.insertNatBlock(TYPICAL_BEFORE_RULES, natBlock);
        assertTrue(UfwCheck.hasPreRoutingRedirect(content, 18443));
    }

    @Test
    void hasPreRoutingRedirectFalseWhenMissing() {
        assertFalse(UfwCheck.hasPreRoutingRedirect(TYPICAL_BEFORE_RULES, 18443));
    }

    @Test
    void hasPreRoutingRedirectFalseForWrongPort() {
        var natBlock = UfwCheck.generateNatBlock("10.166.11.1", "10.166.11.0/24", 443, 18443);
        var content = UfwCheck.insertNatBlock(TYPICAL_BEFORE_RULES, natBlock);
        assertFalse(UfwCheck.hasPreRoutingRedirect(content, 9999));
    }

    @Test
    void hasPreRoutingRedirectFalseWithoutRedirectBlock() {
        var natBlock = UfwCheck.generateNatBlockWithoutRedirect("10.166.11.0/24");
        var content = UfwCheck.insertNatBlock(TYPICAL_BEFORE_RULES, natBlock);
        assertFalse(UfwCheck.hasPreRoutingRedirect(content, 18443));
    }

    @Test
    void hasPreRoutingRedirectFalseOnEmpty() {
        assertFalse(UfwCheck.hasPreRoutingRedirect("", 18443));
    }

    // ---- hasMasquerade ----

    @Test
    void hasMasqueradeDetectsRule() {
        var natBlock = UfwCheck.generateNatBlock("10.166.11.1", "10.166.11.0/24", 443, 18443);
        var content = UfwCheck.insertNatBlock(TYPICAL_BEFORE_RULES, natBlock);
        assertTrue(UfwCheck.hasMasquerade(content, "10.166.11.0/24"));
    }

    @Test
    void hasMasqueradeFalseWhenMissing() {
        assertFalse(UfwCheck.hasMasquerade(TYPICAL_BEFORE_RULES, "10.166.11.0/24"));
    }

    @Test
    void hasMasqueradeFalseForWrongSubnet() {
        var natBlock = UfwCheck.generateNatBlock("10.166.11.1", "10.166.11.0/24", 443, 18443);
        var content = UfwCheck.insertNatBlock(TYPICAL_BEFORE_RULES, natBlock);
        assertFalse(UfwCheck.hasMasquerade(content, "172.20.0.0/24"));
    }

    // ---- hasForwardRules ----

    @Test
    void hasForwardRulesDetectsRules() {
        var filterInsert = UfwCheck.generateFilterInsert();
        var content = UfwCheck.insertFilterRules(TYPICAL_BEFORE_RULES, filterInsert);
        assertTrue(UfwCheck.hasForwardRules(content));
    }

    @Test
    void hasForwardRulesFalseWhenMissing() {
        assertFalse(UfwCheck.hasForwardRules(TYPICAL_BEFORE_RULES));
    }

    // ---- Combined insertion ----

    @Test
    void bothBlocksInsertedCorrectly() {
        var natBlock = UfwCheck.generateNatBlock("10.166.11.1", "10.166.11.0/24", 443, 18443);
        var filterInsert = UfwCheck.generateFilterInsert();
        var result = UfwCheck.insertNatBlock(TYPICAL_BEFORE_RULES, natBlock);
        result = UfwCheck.insertFilterRules(result, filterInsert);

        assertTrue(UfwCheck.hasPreRoutingRedirect(result, 18443));
        assertTrue(UfwCheck.hasMasquerade(result, "10.166.11.0/24"));
        assertTrue(UfwCheck.hasForwardRules(result));

        // Verify ordering: NAT before *filter, forward before COMMIT
        int natIdx = result.indexOf(UfwCheck.MARKER_NAT_BEGIN);
        int filterIdx = result.indexOf("*filter");
        int fwdIdx = result.indexOf(UfwCheck.MARKER_FWD_BEGIN);
        int commitIdx = result.lastIndexOf("COMMIT");
        assertTrue(natIdx < filterIdx);
        assertTrue(fwdIdx < commitIdx);
    }

    @Test
    void combinedInsertionIsIdempotent() {
        var natBlock = UfwCheck.generateNatBlock("10.166.11.1", "10.166.11.0/24", 443, 18443);
        var filterInsert = UfwCheck.generateFilterInsert();

        var first = UfwCheck.insertNatBlock(TYPICAL_BEFORE_RULES, natBlock);
        first = UfwCheck.insertFilterRules(first, filterInsert);

        var second = UfwCheck.insertNatBlock(first, natBlock);
        second = UfwCheck.insertFilterRules(second, filterInsert);

        assertEquals(first, second);
    }
}
