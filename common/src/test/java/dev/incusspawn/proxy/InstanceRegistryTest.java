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
                """).byAddress();
        assertEquals(2, parsed.size());

        var work = parsed.get("10.0.0.5");
        assertEquals("work-box", work.instanceName());
        assertEquals("work", work.accountsByNamespace().get("claude"));
        assertEquals("acme-bot", work.accountsByNamespace().get("github"));
        assertFalse(work.usesDefaults());

        assertEquals("personal", parsed.get("10.0.0.6").accountsByNamespace().get("claude"));
    }

    @Test
    void recordsWhenEachAddressOwnerWasCreated() {
        // Tells a box destroyed and branched again under its name from the old one (#1063)
        var parsed = InstanceRegistry.parse("""
                [
                  {"name":"box","created_at":"2026-10-07T10:00:00.123456789Z",
                   "config":{"user.incus-spawn.static-ip":"10.0.0.5"}},
                  {"name":"old","config":{"user.incus-spawn.static-ip":"10.0.0.6"}},
                  {"name":"tpl","created_at":"2026-10-01T09:00:00Z","config":{}}
                ]
                """).incarnationByName();
        assertEquals(java.util.Map.of("box", "2026-10-07T10:00:00.123456789Z", "old", ""), parsed,
                "owners of an address only, an empty value where the listing has none");
    }

    @Test
    void resolvesACallerToItsInstanceAndIncarnationTogether() {
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        var registry = new InstanceRegistry(new dev.incusspawn.incus.IncusClient() {
            @Override
            public String listJsonConfig() {
                if (fail.get()) throw new dev.incusspawn.incus.IncusException("socket gone");
                return """
                        [{"name":"box","created_at":"2026-10-07T10:00:00Z",
                          "config":{"user.incus-spawn.static-ip":"10.0.0.5"}}]
                        """;
            }
        });
        registry.refresh();

        var caller = registry.resolve("::ffff:10.0.0.5");
        assertEquals("box", caller.accounts().instanceName());
        assertEquals("2026-10-07T10:00:00Z", caller.incarnation());
        var first = caller.view();
        assertNull(registry.resolve("10.0.0.6"));

        // Views order listings, which a rename's created_at cannot
        registry.refresh();
        var second = registry.incarnations().view();
        assertTrue(second > first);
        fail.set(true);
        registry.refresh();
        assertEquals(new InstanceRegistry.Incarnations(java.util.Map.of("box", "2026-10-07T10:00:00Z"), second),
                registry.incarnations(), "a failed refresh lists nothing new");
    }

    /** An instance that pins nothing is still known -- it just gets the configured defaults. */
    @Test
    void instanceWithNoPinningUsesDefaults() {
        var parsed = InstanceRegistry.parse("""
                [{"name":"plain","config":{"user.incus-spawn.static-ip":"10.0.0.7"}}]
                """).byAddress();
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
                """).byAddress();
        assertTrue(parsed.isEmpty());
    }

    @Test
    void blankAccountValuesAreNotTreatedAsPinning() {
        var parsed = InstanceRegistry.parse("""
                [{"name":"b","config":{
                    "user.incus-spawn.static-ip":"10.0.0.8",
                    "user.incus-spawn.account.claude":""}}]
                """).byAddress();
        assertTrue(parsed.get("10.0.0.8").usesDefaults());
    }

    @Test
    void malformedJsonYieldsAnEmptyMapRatherThanThrowing() {
        assertTrue(InstanceRegistry.parse("not json").byAddress().isEmpty());
        assertTrue(InstanceRegistry.parse("{}").byAddress().isEmpty());
        assertTrue(InstanceRegistry.parse("").byAddress().isEmpty());
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
                """).byAddress();
        assertNotNull(parsed.get(InstanceRegistry.normalize("::ffff:10.0.0.5")));
    }

    /**
     * A copy made by an older isx, or by {@code incus copy}, carries its source's address
     * (#815). Listing order used to decide which one owned it, and the source's traffic could
     * then be spent on the copy's accounts. Only a running instance can hold the address.
     */
    @Test
    void aSharedAddressBelongsToTheOneRunningClaimant() {
        var parsed = InstanceRegistry.parse("""
                [
                  {"name":"dev-1","status":"Running","config":{
                     "user.incus-spawn.static-ip":"10.0.0.5",
                     "user.incus-spawn.account.claude":"work"}},
                  {"name":"dev-2","status":"Stopped","config":{
                     "user.incus-spawn.static-ip":"10.0.0.5",
                     "user.incus-spawn.account.claude":"personal"}}
                ]
                """).byAddress();
        assertEquals("dev-1", parsed.get("10.0.0.5").instanceName());
        assertEquals("work", parsed.get("10.0.0.5").accountsByNamespace().get("claude"));
    }

    @Test
    void aSharedAddressWithNoSingleRunningClaimantIsNobodys() {
        for (var statuses : new String[][] {{"Stopped", "Stopped"}, {"Running", "Running"}}) {
            var parsed = InstanceRegistry.parse("""
                    [
                      {"name":"dev-1","status":"%s","config":{"user.incus-spawn.static-ip":"10.0.0.5"}},
                      {"name":"dev-2","status":"%s","config":{"user.incus-spawn.static-ip":"10.0.0.5"}},
                      {"name":"dev-3","status":"Running","config":{"user.incus-spawn.static-ip":"10.0.0.6"}}
                    ]
                    """.formatted(statuses[0], statuses[1])).byAddress();
            assertNull(parsed.get("10.0.0.5"), String.join("/", statuses));
            assertEquals("dev-3", parsed.get("10.0.0.6").instanceName(), "others are unaffected");
        }
    }

    /** What the build derived is read too: the proxy refuses an account it cannot match. */
    @Test
    void readsWhatEachInstanceWasBuiltFor() {
        var parsed = InstanceRegistry.parse("""
                [{"name":"box","config":{"user.incus-spawn.static-ip":"10.0.0.8",
                   "user.incus-spawn.account-identity.claude":"oauth",
                   "user.incus-spawn.account-identity.github":"me"}}]
                """).byAddress();
        var box = parsed.get("10.0.0.8");
        assertEquals(java.util.Map.of("claude", "oauth", "github", "me"), box.bakedIdentities());
        assertTrue(box.usesDefaults(), "build stamps are not pins");
    }

    /** Unpinned instances are listed too: they are the ones a change of default moves. */
    @Test
    void accountStatesIncludeUnpinnedInstances() {
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .container("pinned", java.util.Map.of("user.incus-spawn.type", "clone",
                        "user.incus-spawn.account.github", "bot"))
                .container("follower", java.util.Map.of("user.incus-spawn.type", "clone",
                        "user.incus-spawn.account-identity.claude", "oauth"))
                .container("not-isx", java.util.Map.of())
                .container("tpl-dev", java.util.Map.of("user.incus-spawn.type", "base"))
                .container("tpl-dev-failed-build", java.util.Map.of("user.incus-spawn.type", "failed-build"));
        var states = InstanceRegistry.accountStates(daemon.client());
        assertEquals(java.util.Set.of("pinned", "follower"), states.keySet(),
                "branches only: a template's pins would travel to all its future branches");
        assertFalse(states.get("pinned").followsDefault("github"));
        assertTrue(states.get("pinned").followsDefault("claude"));
        assertTrue(states.get("follower").followsDefault("github"));
        assertEquals("oauth", states.get("follower").bakedIdentities().get("claude"));
    }

    private static final String SECRET_KEY = dev.incusspawn.incus.Metadata.INSTANCE_SECRET_SHA256;
    private static final String ADDRESS_KEY = dev.incusspawn.incus.Metadata.STATIC_IP;

    /** A registry over two running boxes, each started with its own secret. */
    private static InstanceRegistry twoBoxes(dev.incusspawn.incus.FakeIncusDaemon daemon,
                                             String secretA, String secretB) {
        daemon.instance("box-a", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.5",
                        SECRET_KEY, InstanceSecret.sha256(secretA)))
                .instance("box-b", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.6",
                        SECRET_KEY, InstanceSecret.sha256(secretB)));
        var registry = new InstanceRegistry(daemon.client());
        assertTrue(registry.refresh());
        return registry;
    }

    /** #934: a caller is an instance only when its address and its secret both say so. */
    @Test
    void identifiesACallerByAddressAndSecretTogether() {
        var a = InstanceSecret.generate();
        var b = InstanceSecret.generate();
        var registry = twoBoxes(new dev.incusspawn.incus.FakeIncusDaemon(), a, b);

        assertEquals("box-a", registry.identify("10.0.0.5", a).instanceName());
        assertEquals("box-a", registry.identify("::ffff:10.0.0.5", a).instanceName(),
                "a dual-stack listener's mapped address is the same caller");
        assertEquals("box-b", registry.identify("10.0.0.6", b).instanceName());

        assertNull(registry.identify("10.0.0.6", a), "another instance's secret");
        assertNull(registry.identify("10.0.0.9", a), "the right secret from an unknown address");
        assertNull(registry.identify("10.0.0.5", null), "the address alone");
        assertNull(registry.identify("10.0.0.5", ""));
        assertNull(registry.identify("10.0.0.5", InstanceSecret.sha256(a)), "the recorded hash");
        assertNull(registry.identify(null, a));
    }

    @Test
    void aRestartRetiresTheSecretOfThePreviousStart() {
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon();
        var before = InstanceSecret.generate();
        var registry = twoBoxes(daemon, before, InstanceSecret.generate());
        var after = InstanceSecret.generate();
        daemon.client().configSet("box-a", SECRET_KEY, InstanceSecret.sha256(after));
        assertTrue(registry.refresh());

        assertNull(registry.identify("10.0.0.5", before));
        assertEquals("box-a", registry.identify("10.0.0.5", after).instanceName());
    }

    @Test
    void anInstanceWithoutASecretIsNeverIdentified() {
        // Started before #934, or only ever by incus itself: still served its accounts by
        // address, but nothing it presents identifies it
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .instance("old", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.7"));
        var registry = new InstanceRegistry(daemon.client());
        registry.refresh();
        assertEquals("old", registry.lookup("10.0.0.7").instanceName());
        assertNull(registry.identify("10.0.0.7", InstanceSecret.generate()));
        assertNull(registry.identify("10.0.0.7", ""));
    }

    @Test
    void aSharedAddressIsTheRunningClaimantsWithItsOwnSecret() {
        // A copy made before #815 carries its source's address; only the running one can call
        var running = InstanceSecret.generate();
        var stopped = InstanceSecret.generate();
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .instance("stopped", "container", "Stopped", java.util.Map.of(ADDRESS_KEY, "10.0.0.5",
                        SECRET_KEY, InstanceSecret.sha256(stopped)))
                .instance("running", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.5",
                        SECRET_KEY, InstanceSecret.sha256(running)));
        var registry = new InstanceRegistry(daemon.client());
        registry.refresh();
        assertEquals("running", registry.identify("10.0.0.5", running).instanceName());
        assertNull(registry.identify("10.0.0.5", stopped));
    }

    @Test
    void onlyAStampedInstanceIdentifiedByItsSecretMayCallIsxMcp() {
        // #915: the stamp says which instance may; address and secret say which instance calls
        var caller = InstanceSecret.generate();
        var plain = InstanceSecret.generate();
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .instance("coord", "container", "Stopped", java.util.Map.of(ADDRESS_KEY, "10.0.0.5",
                        SECRET_KEY, InstanceSecret.sha256(caller),
                        dev.incusspawn.incus.Metadata.MCP_CALLER, dev.incusspawn.incus.Metadata.newMcpCallerGrant()))
                .instance("worker", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.6",
                        SECRET_KEY, InstanceSecret.sha256(plain)));
        var registry = new InstanceRegistry(daemon.client());
        registry.refresh();
        assertEquals("coord", registry.identifyMcpCaller("10.0.0.5", caller));
        assertNull(registry.identifyMcpCaller("10.0.0.5", null), "the address alone");
        assertNull(registry.identifyMcpCaller("10.0.0.6", caller), "its secret from another address");
        assertNull(registry.identifyMcpCaller("10.0.0.6", plain), "an instance that was not granted it");
        assertTrue(registry.isMcpCaller("coord"));
        assertFalse(registry.isMcpCaller("worker"));
    }

    @Test
    void anMcpCallerStampIsAGrantIdOrNothing() {
        // The proxy must refuse what isx mcp --caller-instance refuses, or the session it
        // starts ends unexplained
        var secret = InstanceSecret.generate();
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .instance("odd", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.5",
                        SECRET_KEY, InstanceSecret.sha256(secret),
                        dev.incusspawn.incus.Metadata.MCP_CALLER, "2026-10-05T10:00:00"));
        var registry = new InstanceRegistry(daemon.client());
        registry.refresh();
        assertNull(registry.identifyMcpCaller("10.0.0.5", secret));
        assertFalse(registry.isMcpCaller("odd"));
    }
}
