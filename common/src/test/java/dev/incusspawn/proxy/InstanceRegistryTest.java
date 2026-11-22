package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Turning {@code /1.0/instances?recursion=1} into the source-address → account map the proxy
 * uses to tell callers apart.
 */
class InstanceRegistryTest {

    /**
     * A registry over a listing that counts down {@code started}, then waits for {@code listed},
     * on a clock the test moves (#1596).
     */
    private static InstanceRegistry hangingRegistry(java.util.concurrent.CountDownLatch started,
                                                    java.util.concurrent.CountDownLatch listed,
                                                    java.util.function.LongSupplier clock) {
        return new InstanceRegistry(new dev.incusspawn.incus.IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                started.countDown();
                try {
                    listed.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return io.hyperfoil.tools.jjq.value.JqValues.parse("[]");
            }
        }, clock);
    }

    @Test
    void aListingThatHasJustStartedIsNotHung() throws Exception {
        // Held where the listing reads its start time: no read may see it under way with an older one
        var now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        var reading = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var lister = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var registry = hangingRegistry(new java.util.concurrent.CountDownLatch(1), new java.util.concurrent.CountDownLatch(0), () -> {
            if (Thread.currentThread() == lister.get()) {
                reading.countDown();
                try {
                    resume.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return now.get();
        });
        var thread = new Thread(registry::refresh);
        lister.set(thread);
        thread.start();
        try {
            assertTrue(reading.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(registry.listingUnavailable(), "a listing starting now has not hung");
        } finally {
            resume.countDown();
            thread.join(10_000);
        }
        assertFalse(thread.isAlive(), "the listing ended");
    }

    @Test
    void aListingClaimsBeforeItReadsItsStartTime() throws Exception {
        // A start time read before the claim may be long gone when the claim wins, after other
        // listings ran: then a listing would read as hung the moment it begins
        var reading = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var lister = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var firstRead = new java.util.concurrent.atomic.AtomicBoolean(true);
        var registry = hangingRegistry(new java.util.concurrent.CountDownLatch(1), new java.util.concurrent.CountDownLatch(0), () -> {
            if (Thread.currentThread() == lister.get() && firstRead.getAndSet(false)) {
                reading.countDown();
                try {
                    resume.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return 1_000_000;
        });
        var thread = new Thread(registry::refresh);
        lister.set(thread);
        thread.start();
        try {
            assertTrue(reading.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(registry.refresh(), "the one reading its start time already holds the listing");
        } finally {
            resume.countDown();
            thread.join(10_000);
        }
        assertFalse(thread.isAlive(), "the listing ended");
    }

    @Test
    void aClockThatThrowsAfterTheClaimStillReleasesIt() {
        var once = new java.util.concurrent.atomic.AtomicBoolean(true);
        var registry = hangingRegistry(new java.util.concurrent.CountDownLatch(1), new java.util.concurrent.CountDownLatch(0), () -> {
            if (once.getAndSet(false)) throw new IllegalStateException("no clock");
            return 1_000_000;
        });
        assertFalse(registry.refresh(), "that listing failed");
        assertTrue(registry.refresh(), "and released its claim");
    }

    @Test
    void aListingUnansweredPastTheRefreshWaitIsUnavailableUntilItLands() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        var listed = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var registry = hangingRegistry(started, listed, now::get);
        var thread = new Thread(registry::refresh);
        thread.start();
        try {
            assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(registry.listingUnavailable(), "within the wait");
            now.addAndGet(InstanceRegistry.LISTING_HUNG_AFTER_MS + 1);
            assertTrue(registry.listingUnavailable(), "past it");
        } finally {
            listed.countDown();
            thread.join(10_000);
        }
        assertFalse(thread.isAlive(), "the listing ended");
        assertFalse(registry.listingUnavailable(), "once it lands");
    }

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

    /**
     * A refused caller takes the next listing's number and is answered by a listing that started
     * after it, never by one under way when it was refused, which may predate its secret (#1800).
     */
    @Test
    void aListingTakenBeforeTheMarkDoesNotAnswerIt() {
        var registry = new InstanceRegistry(new dev.incusspawn.incus.IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                return io.hyperfoil.tools.jjq.value.JqValues.parse("[]");
            }
        });
        registry.refresh();
        var wanted = registry.nextListing();
        assertFalse(registry.listedFrom(wanted), "the listing before the mark is not the answer");
        registry.refreshFrom(wanted, 5_000);
        assertTrue(registry.listedFrom(wanted));
        assertEquals(wanted + 1, registry.nextListing());
    }

    @Test
    void resolvesACallerToItsInstanceAndIncarnationTogether() {
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        var registry = new InstanceRegistry(new dev.incusspawn.incus.IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                if (fail.get()) throw new dev.incusspawn.incus.IncusException("socket gone");
                return io.hyperfoil.tools.jjq.value.JqValues.parse("""
                        [{"name":"box","created_at":"2026-10-07T10:00:00Z",
                          "config":{"user.incus-spawn.static-ip":"10.0.0.5"}}]
                        """);
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
        assertEquals(new InstanceRegistry.Incarnations(java.util.Map.of("box", "2026-10-07T10:00:00Z"), second, java.util.Set.of()),
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
    void aFailedBuildOrACopyOfOneIsNobodysTemplate() {
        // #936: a failed build keeps its parent's PROFILE and BUILD_SOURCE (they are only replaced
        // when a build succeeds), so on those alone it would pass for the parent's template
        var secret = InstanceSecret.generate();
        var hash = InstanceSecret.sha256(secret);
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .instance("foo-failed-build", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.5",
                        SECRET_KEY, hash, dev.incusspawn.incus.Metadata.PROFILE, "tpl-isx-agent",
                        dev.incusspawn.incus.Metadata.TYPE, dev.incusspawn.incus.Metadata.TYPE_FAILED_BUILD))
                .instance("branch-of-it", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.6",
                        SECRET_KEY, hash, dev.incusspawn.incus.Metadata.PROFILE, "tpl-isx-agent",
                        dev.incusspawn.incus.Metadata.TYPE, dev.incusspawn.incus.Metadata.TYPE_CLONE,
                        dev.incusspawn.incus.Metadata.UNTRUSTED_LINEAGE, "failed-build"))
                .instance("old-branch", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.8",
                        SECRET_KEY, hash, dev.incusspawn.incus.Metadata.PROFILE, "tpl-isx-agent",
                        dev.incusspawn.incus.Metadata.TYPE, dev.incusspawn.incus.Metadata.TYPE_CLONE,
                        // branched before #936 from foo-failed-build: no taint, only its parent's name
                        dev.incusspawn.incus.Metadata.PARENT, "foo-failed-build"))
                .instance("agent", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.7",
                        SECRET_KEY, hash, dev.incusspawn.incus.Metadata.PROFILE, "tpl-isx-agent",
                        dev.incusspawn.incus.Metadata.TYPE, dev.incusspawn.incus.Metadata.TYPE_CLONE));
        var registry = new InstanceRegistry(daemon.client());
        registry.refresh();

        var route = new dev.incusspawn.config.HostRoutes.Route("work.isx.internal", "127.0.0.1", 1, java.util.List.of("tpl-isx-agent"));
        for (var address : java.util.List.of("10.0.0.5", "10.0.0.6", "10.0.0.8")) {
            var lineage = registry.identifyWithLineage(address, secret, false).lineage();
            assertFalse(route.admits(lineage.template(), lineage.untrusted()), address + " is admitted: " + lineage);
        }
        var agent = registry.identifyWithLineage("10.0.0.7", secret, false).lineage();
        assertTrue(route.admits(agent.template(), agent.untrusted()), "an ordinary branch still is");
    }

    /**
     * isx update-all gives a template a pinned address and a secret while it runs (#1743), as a
     * build container has: like one, it is refused host routes, while its branches are not.
     */
    @Test
    void aTemplateBeingUpdatedIsRefusedHostRoutesButItsBranchesAreNot() {
        var secret = InstanceSecret.generate();
        var hash = InstanceSecret.sha256(secret);
        var tpl = dev.incusspawn.incus.Metadata.PROFILE;
        var type = dev.incusspawn.incus.Metadata.TYPE;
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .instance("tpl-isx-agent", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.5", SECRET_KEY, hash,
                        tpl, "tpl-isx-agent", type, dev.incusspawn.incus.Metadata.TYPE_BASE))
                .instance("dev-1", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.6", SECRET_KEY, hash,
                        tpl, "tpl-isx-agent", type, dev.incusspawn.incus.Metadata.TYPE_CLONE,
                        dev.incusspawn.incus.Metadata.PARENT, "tpl-isx-agent"));
        var registry = new InstanceRegistry(daemon.client());
        registry.refresh();

        var route = new dev.incusspawn.config.HostRoutes.Route("work.isx.internal", "127.0.0.1", 1, java.util.List.of("tpl-isx-agent"));
        var template = registry.identifyWithLineage("10.0.0.5", secret, false).lineage();
        assertFalse(route.admits(template.template(), template.untrusted()), "the template is admitted: " + template);
        var branch = registry.identifyWithLineage("10.0.0.6", secret, false).lineage();
        assertTrue(route.admits(branch.template(), branch.untrusted()), "its branch is refused: " + branch);
    }

    @Test
    void anInstanceDescendedFromAFailedBuildIsNobodysTemplateAtAnyDepth() {
        // Before #936 nothing was stamped: a branch of a branch of a failed build, and a branch of a
        // failed build renamed in the TUI, name no -failed-build parent, but their ancestry does
        var secret = InstanceSecret.generate();
        var hash = InstanceSecret.sha256(secret);
        var tpl = dev.incusspawn.incus.Metadata.PROFILE;
        var type = dev.incusspawn.incus.Metadata.TYPE;
        var parent = dev.incusspawn.incus.Metadata.PARENT;
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .instance("foo-failed-build", "container", "Stopped", java.util.Map.of(tpl, "tpl-isx-agent",
                        type, dev.incusspawn.incus.Metadata.TYPE_FAILED_BUILD, parent, "foo"))
                .instance("dev-1", "container", "Stopped", java.util.Map.of(tpl, "tpl-isx-agent",
                        type, dev.incusspawn.incus.Metadata.TYPE_CLONE, parent, "foo-failed-build"))
                .instance("dev-2", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.5", SECRET_KEY, hash,
                        tpl, "tpl-isx-agent", type, dev.incusspawn.incus.Metadata.TYPE_CLONE, parent, "dev-1"))
                .instance("foo-debug", "container", "Stopped", java.util.Map.of(tpl, "tpl-isx-agent",
                        type, dev.incusspawn.incus.Metadata.TYPE_FAILED_BUILD, parent, "foo"))
                .instance("dev-3", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.6", SECRET_KEY, hash,
                        tpl, "tpl-isx-agent", type, dev.incusspawn.incus.Metadata.TYPE_CLONE, parent, "foo-debug"))
                .instance("tpl-isx-agent", "container", "Stopped", java.util.Map.of(tpl, "tpl-isx-agent",
                        type, dev.incusspawn.incus.Metadata.TYPE_BASE, parent, "tpl-isx"))
                .instance("dev-4", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.7", SECRET_KEY, hash,
                        tpl, "tpl-isx-agent", type, dev.incusspawn.incus.Metadata.TYPE_CLONE, parent, "tpl-isx-agent"));
        var registry = new InstanceRegistry(daemon.client());
        registry.refresh();

        assertTrue(registry.identifyWithLineage("10.0.0.5", secret, false).lineage().untrusted(), "a branch of a branch");
        assertTrue(registry.identifyWithLineage("10.0.0.6", secret, false).lineage().untrusted(), "of a renamed failed build");
        var trusted = registry.identifyWithLineage("10.0.0.7", secret, false).lineage();
        assertFalse(trusted.untrusted(), "an ordinary branch, whose ancestors are templates: " + trusted);
        assertEquals("tpl-isx-agent", trusted.template());
    }

    @Test
    void aBurstOfRefreshesAfterNowSharesOneListing() throws Exception {
        // Refused callers are what a guest with a wrong secret makes at will: each must not list on its own
        var listings = new java.util.concurrent.atomic.AtomicInteger();
        var listingStarted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var registry = new InstanceRegistry(new dev.incusspawn.incus.IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                listings.incrementAndGet();
                listingStarted.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return io.hyperfoil.tools.jjq.value.JqValues.parse("[]");
            }
        });
        var inFlight = new Thread(registry::refresh);
        inFlight.start();
        assertTrue(listingStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var waiters = new java.util.ArrayList<Thread>();
        for (int i = 0; i < 16; i++) {
            var waiter = new Thread(() -> registry.refreshAfterNow(5_000));
            waiters.add(waiter);
            waiter.start();
        }
        // Every waiter has asked, and is waiting for the listing under way, before it ends
        for (var waiter : waiters) {
            while (waiter.getState() != Thread.State.TIMED_WAITING) Thread.onSpinWait();
        }
        release.countDown();
        for (var waiter : waiters) waiter.join(5_000);
        inFlight.join(5_000);

        assertEquals(2, listings.get(), "the one under way, then one listing for all sixteen");
        waiters.forEach(w -> assertFalse(w.isAlive()));
    }

    @Test
    void aRefreshAfterNowWaitsOutOneAlreadyListingAndListsAgain() throws Exception {
        // A refused caller's retry must see a listing from after its request, not the one the
        // request path started a moment earlier, from before the start that rotated the secret
        var secret = InstanceSecret.generate();
        var before = "[]";
        var after = """
                [{"name":"box","status":"Running","config":{"%s":"10.0.0.5","%s":"%s"}}]
                """.formatted(ADDRESS_KEY, SECRET_KEY, InstanceSecret.sha256(secret));
        var listing = new java.util.concurrent.atomic.AtomicReference<>(before);
        var listingStarted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var registry = new InstanceRegistry(new dev.incusspawn.incus.IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                var answer = listing.get();
                listingStarted.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return io.hyperfoil.tools.jjq.value.JqValues.parse(answer);
            }
        });
        var inFlight = new Thread(registry::refresh);
        inFlight.start();
        assertTrue(listingStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        listing.set(after);
        var waiter = new Thread(() -> registry.refreshAfterNow(5_000));
        waiter.start();
        Thread.sleep(50);
        release.countDown();
        waiter.join(5_000);
        inFlight.join(5_000);

        assertEquals("box", registry.identify("10.0.0.5", secret).instanceName());
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

    /** #1583: on macOS every container arrives from the VM's address, which is no instance. */
    @Test
    void whereTheAddressNamesNoInstanceTheSecretAloneIdentifiesTheCaller() {
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon();
        var a = InstanceSecret.generate();
        var b = InstanceSecret.generate();
        var registry = twoBoxes(daemon, a, b);
        var vm = "10.166.11.2";

        assertEquals("box-a", registry.identify(vm, a, true).instanceName());
        assertEquals("box-b", registry.identify(vm, " " + b + "\n", true).instanceName(),
                "trimmed, as against the address");
        assertNull(registry.identify(vm, a), "only where the caller allows it");
        assertNull(registry.identify(vm, InstanceSecret.generate(), true), "a wrong secret");
        assertNull(registry.identify(vm, InstanceSecret.sha256(a), true), "the recorded hash");
        assertNull(registry.identify(vm, null, true));
        assertNull(registry.identify(vm, "", true));
        assertNull(registry.identify("10.0.0.6", a, true), "a known address is held to its own secret");

        var after = InstanceSecret.generate();
        daemon.client().configSet("box-a", SECRET_KEY, InstanceSecret.sha256(after));
        assertTrue(registry.refresh());
        assertNull(registry.identify(vm, a, true), "the previous start's secret");
        assertEquals("box-a", registry.identify(vm, after, true).instanceName());
    }

    @Test
    void theSecretAloneIdentifiesForIsxMcpAndHostRoutesToo() {
        var secret = InstanceSecret.generate();
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .instance("coord", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.5",
                        SECRET_KEY, InstanceSecret.sha256(secret), dev.incusspawn.incus.Metadata.PROFILE, "tpl-agent",
                        dev.incusspawn.incus.Metadata.MCP_CALLER, dev.incusspawn.incus.Metadata.newMcpCallerGrant()));
        var registry = new InstanceRegistry(daemon.client());
        assertTrue(registry.refresh());

        assertEquals("coord", mcpCaller(registry, "10.166.11.2", secret, true));
        assertNull(mcpCaller(registry, "10.166.11.2", secret, false));
        var identified = registry.identifyWithLineage("10.166.11.2", secret, true);
        assertEquals("coord", identified.instanceName());
        assertEquals("tpl-agent", identified.lineage().template());
        assertNull(registry.identifyWithLineage("10.166.11.2", secret, false));
    }

    @Test
    void anIdentifiedCallerCarriesTheIncarnationOfTheListingThatNamedIt() {
        // Its traffic is charged to that incarnation, never to one a later listing pairs with the name
        var secret = InstanceSecret.generate();
        var listing = new java.util.concurrent.atomic.AtomicReference<>("2026-10-01T09:00:00Z");
        var registry = new InstanceRegistry(new dev.incusspawn.incus.IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                return io.hyperfoil.tools.jjq.value.JqValues.parse("""
                        [{"name":"box","status":"Running","created_at":"%s","config":{"%s":"10.0.0.5","%s":"%s"}}]
                        """.formatted(listing.get(), ADDRESS_KEY, SECRET_KEY, InstanceSecret.sha256(secret)));
            }
        });
        assertTrue(registry.refresh());
        var identified = registry.identifyWithLineage("10.166.11.2", secret, true);
        var view = registry.incarnations().view();
        assertFalse(identified.mcpCaller(), "not stamped");

        listing.set("2026-10-02T09:00:00Z");
        assertTrue(registry.refresh());
        assertEquals("2026-10-01T09:00:00Z", identified.caller().incarnation());
        assertEquals(view, identified.caller().view());
        assertEquals("2026-10-02T09:00:00Z",
                registry.identifyWithLineage("10.166.11.2", secret, true).caller().incarnation());
    }

    @Test
    void aSecretSharedByACopyIdentifiesOnlyTheOneRunning() {
        // An incus copy made outside isx carries its source's hash until isx starts it
        var secret = InstanceSecret.generate();
        var hash = InstanceSecret.sha256(secret);
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .instance("source", "container", "Stopped", java.util.Map.of(ADDRESS_KEY, "10.0.0.5", SECRET_KEY, hash))
                .instance("copy", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.6", SECRET_KEY, hash));
        var registry = new InstanceRegistry(daemon.client());
        assertTrue(registry.refresh());
        assertEquals("copy", registry.identify("10.166.11.2", secret, true).instanceName());

        daemon.instance("source", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.5", SECRET_KEY, hash));
        assertTrue(registry.refresh());
        assertNull(registry.identify("10.166.11.2", secret, true), "two running: nobody");
        assertEquals("source", registry.identify("10.0.0.5", secret).instanceName(), "an address still decides");
    }

    @Test
    void aStoppedInstancesSecretIdentifiesNobodyBySecretAlone() {
        var secret = InstanceSecret.generate();
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .instance("box", "container", "Stopped", java.util.Map.of(ADDRESS_KEY, "10.0.0.5",
                        SECRET_KEY, InstanceSecret.sha256(secret)));
        var registry = new InstanceRegistry(daemon.client());
        assertTrue(registry.refresh());
        assertNull(registry.identify("10.166.11.2", secret, true));
    }

    @Test
    void aFailedListingStopsIdentifyingBySecretAlone() {
        // As for proofs: without a current listing, nothing says the instance is still running
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        var secret = InstanceSecret.generate();
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .instance("box", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.5",
                        SECRET_KEY, InstanceSecret.sha256(secret)));
        var registry = new InstanceRegistry(new dev.incusspawn.incus.IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                if (fail.get()) throw new dev.incusspawn.incus.IncusException("socket gone");
                return daemon.client().listJsonConfig();
            }
        });
        assertTrue(registry.refresh());
        assertEquals("box", registry.identify("10.166.11.2", secret, true).instanceName());

        fail.set(true);
        assertFalse(registry.refresh());
        assertNull(registry.identify("10.166.11.2", secret, true));
    }

    @Test
    void anIdentifiedCallerCarriesWhatItWasBuiltFrom() {
        // A host route's templates: filter is decided on these (#936)
        var secret = InstanceSecret.generate();
        var hash = InstanceSecret.sha256(secret);
        var projectSource = "{\"definitions\":{\"tpl-repo\":{\"name\":\"tpl-repo\"}},"
                + "\"projectRoots\":{\"tpl-repo\":\"/home/me/repo\"}}";
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .instance("agent", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.5",
                        SECRET_KEY, hash, dev.incusspawn.incus.Metadata.PROFILE, "tpl-agent"))
                .instance("repo-box", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.6",
                        SECRET_KEY, hash, dev.incusspawn.incus.Metadata.PROFILE, "tpl-agent",
                        dev.incusspawn.incus.Metadata.BUILD_SOURCE, projectSource))
                .instance("project-box", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.7",
                        SECRET_KEY, hash, dev.incusspawn.incus.Metadata.PROJECT, "my-repo"))
                .instance("broken", "container", "Running", java.util.Map.of(ADDRESS_KEY, "10.0.0.8",
                        SECRET_KEY, hash, dev.incusspawn.incus.Metadata.BUILD_SOURCE, "{not json"));
        var registry = new InstanceRegistry(daemon.client());
        registry.refresh();

        var agent = registry.identifyWithLineage("10.0.0.5", secret, false);
        assertEquals("agent", agent.instanceName());
        assertEquals(new InstanceRegistry.Lineage("tpl-agent", false), agent.lineage());
        assertTrue(registry.identifyWithLineage("10.0.0.6", secret, false).lineage().untrusted());
        assertTrue(registry.identifyWithLineage("10.0.0.7", secret, false).lineage().untrusted());
        assertTrue(registry.identifyWithLineage("10.0.0.8", secret, false).lineage().untrusted(),
                "a build stamp that cannot be read cannot show it was not");
        assertNull(registry.identifyWithLineage("10.0.0.5", InstanceSecret.generate(), false));
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
        assertEquals("coord", mcpCaller(registry, "10.0.0.5", caller, false));
        assertNull(mcpCaller(registry, "10.0.0.5", null, false), "the address alone");
        assertNull(mcpCaller(registry, "10.0.0.6", caller, false), "its secret from another address");
        assertNull(mcpCaller(registry, "10.0.0.6", plain, false), "an instance that was not granted it");
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
        assertNull(mcpCaller(registry, "10.0.0.5", secret, false));
        assertFalse(registry.isMcpCaller("odd"));
    }

    /** The name {@code isx mcp} serves: identified with its lineage, and stamped {@code mcp-caller}. */
    private static String mcpCaller(InstanceRegistry registry, String address, String secret, boolean bySecretAlone) {
        var identified = registry.identifyWithLineage(address, secret, bySecretAlone);
        return identified != null && identified.mcpCaller() ? identified.instanceName() : null;
    }

    @Test
    void describesAnInstanceByItsStamps() {
        var registry = new InstanceRegistry(new dev.incusspawn.incus.IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                return io.hyperfoil.tools.jjq.value.JqValues.parse("""
                        [{"name":"a","created_at":"2026-10-07T10:00:00Z",
                          "config":{"user.incus-spawn.static-ip":"10.0.0.5","user.incus-spawn.profile":"tpl-x",
                                    "user.incus-spawn.mcp-purpose":"#7 implement","user.incus-spawn.mcp-session":"42-9"}},
                         {"name":"b","created_at":"2026-10-07T11:00:00Z",
                          "config":{"user.incus-spawn.static-ip":"10.0.0.6","user.incus-spawn.purpose":"mine",
                                    "user.incus-spawn.mcp-purpose":"theirs"}}]
                        """);
            }
        });
        registry.refresh();
        assertEquals(new InstanceRegistry.Description("2026-10-07T10:00:00Z", "tpl-x", "#7 implement", "42-9"),
                registry.description("a"));
        assertEquals("mine", registry.description("b").purpose(), "a person's purpose outranks the session's");
        assertEquals("", registry.description("b").session());
        assertNull(registry.description("nobody"));
    }

    private static java.util.Map<String, String> chatGptBound(String secret, String session, String address) {
        var proofs = ChatGptProof.derive(secret, "work", session);
        return java.util.Map.of(ADDRESS_KEY, address,
                dev.incusspawn.incus.Metadata.INSTANCE_SECRET_SHA256, InstanceSecret.sha256(secret),
                dev.incusspawn.incus.Metadata.CHATGPT_PROOF_SECRET_SHA256, InstanceSecret.sha256(secret),
                dev.incusspawn.incus.Metadata.CHATGPT_SESSION, "work:" + session,
                dev.incusspawn.incus.Metadata.CHATGPT_ACCESS_PROOF_SHA256, ChatGptProof.verifier(proofs.access()),
                dev.incusspawn.incus.Metadata.CHATGPT_REFRESH_PROOF_SHA256, ChatGptProof.verifier(proofs.refresh()));
    }

    @Test
    void aChatGptProofNamesTheRunningInstanceItWasMadeForWhenTheAddressNamesNone() {
        var secretA = InstanceSecret.generate();
        var secretB = InstanceSecret.generate();
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .instance("a", "container", "Running", chatGptBound(secretA, "s1", "10.0.0.5"))
                .instance("b", "container", "Running", chatGptBound(secretB, "s1", "10.0.0.6"))
                .instance("stopped", "container", "Stopped", chatGptBound(InstanceSecret.generate(), "s1", "10.0.0.7"));
        var registry = new InstanceRegistry(daemon.client());
        assertTrue(registry.refresh());
        var a = ChatGptProof.derive(secretA, "work", "s1");
        var b = ChatGptProof.derive(secretB, "work", "s1");

        assertEquals("a", registry.chatGptCaller("10.166.11.2", true, java.util.List.of(a.access())).accounts().instanceName());
        assertEquals("b", registry.chatGptCaller("10.166.11.2", true, java.util.List.of(b.refresh())).binding().instanceName());
        assertNull(registry.chatGptCaller("10.166.11.2", true, java.util.List.of(a.access(), b.access())), "two instances' proofs");
        assertNull(registry.chatGptCaller("10.166.11.2", true, java.util.List.of()), "no proof");
        assertNull(registry.chatGptCaller("10.166.11.2", true, java.util.List.of(ChatGptProof.verifier(a.access()))), "a verifier");
        assertNull(registry.chatGptCaller("10.166.11.2", false, java.util.List.of(a.access())), "Linux never goes by proof alone");
        assertEquals("a", registry.chatGptCaller("10.0.0.5", false, java.util.List.of()).accounts().instanceName());
    }

    @Test
    void aChatGptProofFromBeforeTheSecretMovedNamesNobody() {
        var secret = InstanceSecret.generate();
        var config = new java.util.HashMap<>(chatGptBound(secret, "s1", "10.0.0.5"));
        config.put(dev.incusspawn.incus.Metadata.INSTANCE_SECRET_SHA256, InstanceSecret.sha256(InstanceSecret.generate()));
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon().instance("a", "container", "Running", config);
        var registry = new InstanceRegistry(daemon.client());
        assertTrue(registry.refresh());

        assertNull(registry.chatGptCaller("10.166.11.2", true, java.util.List.of(ChatGptProof.derive(secret, "work", "s1").access())));
    }
}
