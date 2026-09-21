package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Turning {@code /1.0/instances?recursion=1} into the source-address → account map the proxy
 * uses to tell callers apart.
 */
class InstanceRegistryTest {

    @Test
    void mapsStaticIpToInstanceAndAccounts() {
        var parsed = InstanceRegistry.parse("""
                [
                  {"name":"work-box","config":{
                     "user.incus-spawn.static-ip":"10.0.0.5",
                     "user.incus-spawn.account.claude":"work",
                     "user.incus-spawn.account.github":"acme-bot"}},
                  {"name":"personal-box","config":{
                     "user.incus-spawn.static-ip":"10.0.0.6",
                     "user.incus-spawn.account.claude":"personal"}}
                ]
                """);
        assertEquals(2, parsed.size());

        var work = parsed.get("10.0.0.5");
        assertEquals("work-box", work.instanceName());
        assertEquals("work", work.accountsByNamespace().get("claude"));
        assertEquals("acme-bot", work.accountsByNamespace().get("github"));
        assertFalse(work.usesDefaults());

        assertEquals("personal", parsed.get("10.0.0.6").accountsByNamespace().get("claude"));
    }

    /** An instance that pins nothing is still known -- it just gets the configured defaults. */
    @Test
    void instanceWithNoPinningUsesDefaults() {
        var parsed = InstanceRegistry.parse("""
                [{"name":"plain","config":{"user.incus-spawn.static-ip":"10.0.0.7"}}]
                """);
        var entry = parsed.get("10.0.0.7");
        assertNotNull(entry);
        assertTrue(entry.usesDefaults());
    }

    /**
     * Airgapped instances and anything else without a static IP simply are not in the map;
     * the proxy serves such a caller the defaults rather than failing.
     */
    @Test
    void instancesWithoutAStaticIpAreSkipped() {
        var parsed = InstanceRegistry.parse("""
                [
                  {"name":"airgap","config":{"user.incus-spawn.network-mode":"AIRGAP"}},
                  {"name":"blank","config":{"user.incus-spawn.static-ip":"  "}}
                ]
                """);
        assertTrue(parsed.isEmpty());
    }

    @Test
    void blankAccountValuesAreNotTreatedAsPinning() {
        var parsed = InstanceRegistry.parse("""
                [{"name":"b","config":{
                    "user.incus-spawn.static-ip":"10.0.0.8",
                    "user.incus-spawn.account.claude":""}}]
                """);
        assertTrue(parsed.get("10.0.0.8").usesDefaults());
    }

    @Test
    void malformedJsonYieldsAnEmptyMapRatherThanThrowing() {
        assertTrue(InstanceRegistry.parse("not json").isEmpty());
        assertTrue(InstanceRegistry.parse("{}").isEmpty());
        assertTrue(InstanceRegistry.parse("").isEmpty());
    }

    /**
     * Vert.x reports an IPv4 peer on a dual-stack listener in mapped form. Without
     * normalization every such lookup would miss and the instance would silently fall back to
     * the default account -- the exact failure per-instance selection exists to prevent.
     */
    @Test
    void ipv4MappedAddressesNormalizeToThePlainForm() {
        assertEquals("10.0.0.5", InstanceRegistry.normalize("::ffff:10.0.0.5"));
        assertEquals("10.0.0.5", InstanceRegistry.normalize("::FFFF:10.0.0.5"));
        assertEquals("10.0.0.5", InstanceRegistry.normalize("10.0.0.5"));
        assertEquals("10.0.0.5", InstanceRegistry.normalize("  10.0.0.5  "));
    }

    @Test
    void lookupHandlesTheMappedFormEndToEnd() {
        var parsed = InstanceRegistry.parse("""
                [{"name":"work","config":{
                    "user.incus-spawn.static-ip":"10.0.0.5",
                    "user.incus-spawn.account.claude":"work"}}]
                """);
        assertNotNull(parsed.get(InstanceRegistry.normalize("::ffff:10.0.0.5")));
    }
}
