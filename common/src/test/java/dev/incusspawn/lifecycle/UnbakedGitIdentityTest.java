package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.GhSetup;
import dev.incusspawn.tool.ToolSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * A template built while no GitHub token was configured has gh's {@code .gitconfig} defaults
 * and no {@code [user]}. It is stamped {@link Metadata#ACCOUNT_IDENTITY_NONE}, so configuring a
 * token later is reconciled into its branches like any other change of account. What is
 * stamped and re-derived is decided by asking the guest, so a template an older isx built --
 * unstamped, or stamped with an identity it never got -- is repaired too, once.
 */
@ExtendWith(TempHome.class)
class UnbakedGitIdentityTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String NAME = "work-1";

    private static SpawnConfig withToken() throws Exception {
        return YAML.readValue("""
                github:
                  accounts:
                    me:
                      token: "ghp_userA"
                      email: "me@example.com"
                  default: me
                """, SpawnConfig.class);
    }

    private static SpawnConfig withoutToken() {
        return new SpawnConfig();
    }

    /** What the build of a template stamps, given the accounts configured then and what the guest lacks. */
    private static Map<String, String> builtStamps(SpawnConfig config, Set<String> lacking) {
        return build(config, Map.of(), new RecordingGh(), lacking.contains("github"), new ArrayList<>());
    }

    /** {@link BuildAccounts#settleIdentities} for a template with gh, on {@code inherited}. */
    private static Map<String, String> build(SpawnConfig config, Map<String, String> inherited, RecordingGh gh,
                                             boolean lacks, List<String> warnings) {
        gh.lacks = lacks;
        return BuildAccounts.settleIdentities(null, config, Map.of("github", gh), Map.of(), List.of("github"),
                inherited, msg -> { }, warnings::add);
    }

    private static Map<String, String> verified(String identity) {
        return Map.of(Metadata.accountIdentityKey("github"), identity, Metadata.ACCOUNT_IDENTITY_VERIFIED, "true");
    }

    private static Map<String, String> stale(FakeIncusDaemon daemon, SpawnConfig config) {
        return AccountSelection.staleIdentities(config, daemon.client(), NAME,
                Map.of("github", new GhSetup()));
    }

    // ── what the build stamps ────────────────────────────────────────────────

    @Test
    void aBuildWithGhButNoTokenIsStampedAsHavingNoIdentity() {
        var warnings = new ArrayList<String>();
        assertEquals(verified(Metadata.ACCOUNT_IDENTITY_NONE),
                build(withoutToken(), Map.of(), new RecordingGh(), true, warnings));
        assertEquals(List.of(new GhSetup().unbakedIdentityWarning("")), warnings);
    }

    @Test
    void aBuildWithoutGhIsStampedWithNothingButVerified() {
        assertEquals(Map.of(Metadata.ACCOUNT_IDENTITY_VERIFIED, "true"), builtStamps(withoutToken(), Set.of()),
                "nothing in the image could take an identity, so there is nothing to reconcile");
    }

    /** Without the marker every branch of a template this isx built would pay an older one's check. */
    @Test
    void everyBuildIsMarkedVerified() throws Exception {
        for (var config : List.of(withToken(), withoutToken())) {
            for (var lacks : List.of(true, false)) {
                assertEquals("true", build(config, Map.of(), new RecordingGh(), lacks, new ArrayList<>())
                        .get(Metadata.ACCOUNT_IDENTITY_VERIFIED));
            }
        }
    }

    /** A child of a token-less template, built once a token is configured: given the identity. */
    @Test
    void aBuildWithATokenOnAParentWithoutAnIdentityDerivesAndStampsIt() throws Exception {
        var gh = new RecordingGh();
        var warnings = new ArrayList<String>();
        var stamps = build(withToken(), Map.of("github", Metadata.ACCOUNT_IDENTITY_NONE), gh, true, warnings);
        assertEquals(List.of("me"), gh.rebaked);
        assertEquals(List.of(), warnings);
        assertEquals(verified(new GhSetup().bakedAccountIdentity(withToken(), "me")), stamps);
    }

    @Test
    void aBuildWithATokenStampsItsIdentityNotTheMarker() throws Exception {
        var stamps = builtStamps(withToken(), Set.of());
        assertEquals(new GhSetup().bakedAccountIdentity(withToken(), "me"),
                stamps.get(Metadata.accountIdentityKey("github")));
    }

    /** gh's setup wrote nothing though a token was configured: re-derived, never stamped as baked. */
    @Test
    void aBuildWhoseGuestLacksTheIdentityDespiteATokenReDerivesIt() throws Exception {
        var setups = Map.<String, ToolSetup>of("github", new GhSetup());
        var baked = AccountSelection.bakedIdentities(withToken(),
                AccountSelection.effectiveSelection(Map.of(), setups), setups);
        assertEquals(Set.of("github"), BuildAccounts.toRederive(baked, Map.of(), Set.of("github"), setups));
    }

    @Test
    void aParentsRealIdentityIsKeptWhileTheGuestHasIt() {
        // The token is gone from config.yaml now, but the parent's .gitconfig still has its [user]
        var stamps = BuildAccounts.identityStamps(Map.of(), Map.of("github", "me#0123456789ab"), Set.of());
        assertEquals(Map.of(Metadata.accountIdentityKey("github"), "me#0123456789ab"), stamps);
    }

    /** An older isx stamped from config.yaml: the stamp claims an identity the guest never got. */
    @Test
    void aParentsStampIsReplacedByTheMarkerWhenTheGuestLacksTheIdentity() {
        var stamps = BuildAccounts.identityStamps(Map.of(), Map.of("github", "me#0123456789ab"), Set.of("github"));
        assertEquals(Map.of(Metadata.accountIdentityKey("github"), Metadata.ACCOUNT_IDENTITY_NONE), stamps);
    }

    @Test
    void onlyAToolThatOwnsAndCanReDeriveTheNamespaceCounts() throws Exception {
        var base = ImageDef.parseYaml("name: tpl-dev\ntools:\n  - gh\n");
        var child = ImageDef.parseYaml("name: tpl-ai\nparent: tpl-dev\ntools:\n  - claude\n");
        var copilotOnly = ImageDef.parseYaml("name: tpl-copilot\ntools:\n  - copilot\n");
        Map<String, ToolSetup> tools = Map.of("gh", new GhSetup(), "claude", new ClaudeSetup(),
                "copilot", new dev.incusspawn.tool.CopilotSetup());
        var defs = Map.of("tpl-dev", base, "tpl-ai", child, "tpl-copilot", copilotOnly);

        var setups = AccountSelection.byNamespace(tools);
        assertEquals(Set.of("github"), AccountSelection.rederivableNamespaces(child, defs, tools, setups),
                "gh from the parent; Claude cannot re-derive, so it is never marked");
        assertEquals(Set.of(), AccountSelection.rederivableNamespaces(copilotOnly, defs, tools, setups),
                "Copilot spends the GitHub credential but derives no git identity");
        assertEquals(Set.of(), AccountSelection.rederivableNamespaces(null, defs, tools, setups));
        assertEquals(Set.of(), AccountSelection.rederivableNamespaces(child, defs, tools, Map.of()),
                "a namespace the feature gate hides is never reconciled, so never marked");
    }

    // ── what a child's build does with its parent's ──────────────────────────

    private static final Map<String, ToolSetup> SETUPS = Map.of("github", new GhSetup(), "claude", new ClaudeSetup());

    private static final String ME = "me#0123456789ab";

    @Test
    void aChildBuiltOnceATokenIsConfiguredReDerivesItsParentsMissingIdentity() {
        assertEquals(Set.of("github"), BuildAccounts.toRederive(Map.of("github", ME),
                Map.of("github", Metadata.ACCOUNT_IDENTITY_NONE), Set.of("github"), SETUPS));
    }

    @Test
    void aChildBuiltStillWithoutATokenHasNothingToReDerive() {
        assertEquals(Set.of(), BuildAccounts.toRederive(Map.of(),
                Map.of("github", Metadata.ACCOUNT_IDENTITY_NONE), Set.of("github"), SETUPS));
    }

    /** The user's template: built before the marker, with gh, no token and so no stamp. */
    @Test
    void aParentWithGhButNoStampAndNoIdentityIsReDerived() {
        assertEquals(Set.of("github"), BuildAccounts.toRederive(Map.of("github", ME),
                Map.of(), Set.of("github"), SETUPS));
    }

    /** The gap: an older isx stamped the child of an identity-less parent from config.yaml. */
    @Test
    void aParentStampedWithTheWantedIdentityButLackingItIsReDerived() {
        assertEquals(Set.of("github"), BuildAccounts.toRederive(Map.of("github", ME),
                Map.of("github", ME), Set.of("github"), SETUPS));
    }

    /** Built before stamps existed, with an identity: kept, as the build would have before. */
    @Test
    void aParentWithAnIdentityButNoStampKeepsIt() {
        assertEquals(Set.of(), BuildAccounts.toRederive(Map.of("github", ME),
                Map.of(), Set.of(), SETUPS), "a lookup that cannot fail cannot fail the build");
    }

    @Test
    void aParentBuiltForTheSameIdentityIsLeftAlone() {
        assertEquals(Set.of(), BuildAccounts.toRederive(Map.of("github", ME),
                Map.of("github", ME), Set.of(), SETUPS));
    }

    @Test
    void aParentBuiltForAnotherIdentityIsReDerived() {
        assertEquals(Set.of("github"), BuildAccounts.toRederive(Map.of("github", ME),
                Map.of("github", "other#0123456789ab"), Set.of(), SETUPS));
    }

    @Test
    void aNamespaceThatCannotReDeriveIsNeverReDerived() {
        assertEquals(Set.of(), BuildAccounts.toRederive(Map.of("claude", "api-key"),
                Map.of("claude", "oauth"), Set.of("claude"), SETUPS));
    }

    // ── what a branch does with it ───────────────────────────────────────────

    @Test
    void theMarkerIsStaleOnceATokenIsConfigured() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.accountIdentityKey("github"), Metadata.ACCOUNT_IDENTITY_NONE));
        assertEquals(Map.of("github", "me"), stale(daemon, withToken()));
    }

    @Test
    void theMarkerIsLeftAloneWhileThereIsStillNoToken() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.accountIdentityKey("github"), Metadata.ACCOUNT_IDENTITY_NONE));
        assertEquals(Map.of(), stale(daemon, withoutToken()),
                "re-deriving with nothing to derive from would fail on every start");
    }

    /** From a template this isx built: its stamps were checked against the guest, so trusted. */
    @Test
    void aVerifiedInstanceWithoutAStampIsLeftAlone() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(Metadata.ACCOUNT_IDENTITY_VERIFIED, "true"));
        var plan = AccountSelection.identityReconcile(withToken(), daemon.client(), NAME,
                Map.of("github", new GhSetup()));
        assertTrue(plan.isEmpty(), "no gh in it, or an identity: either way nothing to ask the guest");
    }

    /** From a template an older isx built: unstamped cannot be told from 'no gh' but by the guest. */
    @Test
    void anUnverifiedInstanceIsCheckedOnceAnAccountIsConfigured() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        var plan = AccountSelection.identityReconcile(withToken(), daemon.client(), NAME,
                Map.of("github", new GhSetup()));
        assertEquals(Map.of(), plan.stale());
        assertEquals(Map.of("github", "me"), plan.unverified());
        assertTrue(AccountSelection.identityReconcile(withoutToken(), daemon.client(), NAME,
                Map.of("github", new GhSetup())).isEmpty(), "without a token there is nothing to derive");
    }

    /** The user's template: gh, no token when built, so no [user] and, from an older isx, no stamp. */
    @Test
    void anUnverifiedInstanceLackingTheIdentityIsRepairedOnce() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        var gh = new RecordingGh();
        gh.lacks = true;
        reconcile(daemon, gh);

        assertEquals(List.of("me"), gh.rebaked);
        var config = daemon.instance(NAME).path("config");
        assertEquals(new GhSetup().bakedAccountIdentity(withToken(), "me"),
                config.path(Metadata.accountIdentityKey("github")).asText());
        assertEquals("true", config.path(Metadata.ACCOUNT_IDENTITY_VERIFIED).asText());

        reconcile(daemon, gh);
        assertEquals(List.of("me"), gh.rebaked);
        assertEquals(1, gh.checks, "the guest is asked once, ever");
    }

    /** The gap: an older isx stamped a child of an identity-less parent from config.yaml. */
    @Test
    void anUnverifiedStampClaimingAnIdentityTheGuestLacksIsRepaired() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.accountIdentityKey("github"), new GhSetup().bakedAccountIdentity(withToken(), "me")));
        var gh = new RecordingGh();
        gh.lacks = true;
        reconcile(daemon, gh);
        assertEquals(List.of("me"), gh.rebaked);
    }

    @Test
    void anUnverifiedInstanceWithItsIdentityIsOnlyMarked() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        var gh = new RecordingGh();
        reconcile(daemon, gh);
        assertEquals(List.of(), gh.rebaked, "an identity it has, whoever's, is not overwritten");
        assertEquals("true", daemon.instance(NAME).path("config").path(Metadata.ACCOUNT_IDENTITY_VERIFIED).asText());
        reconcile(daemon, gh);
        assertEquals(1, gh.checks);
    }

    /** An older instance with no stamp: marked, and stamped, so a later change is still seen. */
    @Test
    void anUnverifiedInstanceWithItsIdentityIsStampedSoALaterChangeIsReconciled() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        var gh = new RecordingGh();
        reconcile(daemon, gh);
        assertEquals(new GhSetup().bakedAccountIdentity(withToken(), "me"),
                daemon.instance(NAME).path("config").path(Metadata.accountIdentityKey("github")).asText());

        var replaced = YAML.readValue("""
                github:
                  accounts:
                    me:
                      token: "ghp_userB"
                      email: "me@example.com"
                  default: me
                """, SpawnConfig.class);
        assertEquals(Map.of("github", "me"), stale(daemon, replaced), "the token was replaced with another user's");
    }

    /** An older instance whose stamp is simply stale is re-derived, and is then as good as verified. */
    @Test
    void anUnverifiedInstanceWithAStaleStampIsMarkedOnceRepaired() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.accountIdentityKey("github"), "other#0123456789ab"));
        var gh = new RecordingGh();
        reconcile(daemon, gh);
        assertEquals(List.of("me"), gh.rebaked);
        assertEquals("true", daemon.instance(NAME).path("config").path(Metadata.ACCOUNT_IDENTITY_VERIFIED).asText());
        reconcile(daemon, gh);
        assertEquals(0, gh.checks, "nothing left to ask the guest");
    }

    /**
     * An older instance with no stamps, while nothing is configured: settled from the read, so
     * the tool definitions are not read from disk on every shell. It is never marked (no account
     * to check it against yet), so this would go on for good.
     */
    @Test
    void anUnverifiedInstanceWithNoAccountDoesNotReadTheToolDefinitions() {
        assertNothingToDoWithoutReadingToolDefinitions(Map.of(), withoutToken());
    }

    /**
     * The other paths every shell takes: a template built without a token, while there still is
     * none, and one built with the token still configured. Only a built-in tool can re-derive,
     * so neither needs the tool YAMLs read from disk.
     */
    @Test
    void theCommonPathsDoNotReadTheToolDefinitions() throws Exception {
        assertNothingToDoWithoutReadingToolDefinitions(verified(Metadata.ACCOUNT_IDENTITY_NONE), withoutToken());
        assertNothingToDoWithoutReadingToolDefinitions(
                verified(new GhSetup().bakedAccountIdentity(withToken(), "me")), withToken());
    }

    private static void assertNothingToDoWithoutReadingToolDefinitions(Map<String, String> stamps, SpawnConfig config) {
        var daemon = new FakeIncusDaemon().container(NAME, stamps);
        try (var selection = org.mockito.Mockito.mockStatic(AccountSelection.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            assertTrue(AccountSelection.identityReconcile(config, daemon.client(), NAME, null).isEmpty(), stamps.toString());
            selection.verify(() -> AccountSelection.namespaceSetups(any(SpawnConfig.class)), never());
            selection.verify(() -> AccountSelection.namespaceSetups(any(SpawnConfig.class), any()), never());
        }
    }

    /** Only tools that can re-derive, so one borrowing a namespace never stands in for its owner. */
    @Test
    void theBuiltInSetupsAreTheOnesThatCanReDerive() {
        var setups = AccountSelection.rederivableSetups();
        assertInstanceOf(GhSetup.class, setups.get("github"));
        setups.values().forEach(tool -> assertTrue(tool.canRebakeForAccount(), tool.name()));
    }

    /** With no setups passed, what is found is still decided by the built-in gh. */
    @Test
    void withoutKnownSetupsTheBuiltInToolsDecide() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.accountIdentityKey("github"), Metadata.ACCOUNT_IDENTITY_NONE));
        assertEquals(Map.of("github", "me"),
                AccountSelection.identityReconcile(withToken(), daemon.client(), NAME, null).stale());
    }

    /** Only re-deriving needs the network; asking the guest does not, so no wait for an address. */
    @Test
    void anUnverifiedInstanceWithItsIdentityIsMarkedWithoutWaitingForAnAddress() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        var incus = addresslessClient(daemon);
        var gh = new RecordingGh();
        var warnings = new ArrayList<String>();

        InstanceLifecycle.reconcileAccountIdentities(incus, NAME, withToken(), Map.of("github", gh),
                msg -> { }, warnings::add);

        assertEquals(List.of(), warnings);
        assertEquals("true", daemon.instance(NAME).path("config").path(Metadata.ACCOUNT_IDENTITY_VERIFIED).asText());
        verify(incus, never()).pollUntilReady(any(), anyInt(), any(String[].class));
    }

    /**
     * An airgapped instance has no proxy to re-derive through and never gets an address: before,
     * every shell in one from an older template, or from one built without a token, waited 30 s
     * for it and then warned.
     */
    @Test
    void anAirgappedInstanceIsNeverReconciled() throws Exception {
        for (var stamps : List.of(
                Map.of(Metadata.NETWORK_MODE, "AIRGAP"),
                Map.of(Metadata.NETWORK_MODE, "AIRGAP", Metadata.ACCOUNT_IDENTITY_VERIFIED, "true",
                        Metadata.accountIdentityKey("github"), Metadata.ACCOUNT_IDENTITY_NONE))) {
            var daemon = new FakeIncusDaemon().container(NAME, stamps);
            var incus = addresslessClient(daemon);
            var gh = new RecordingGh();
            gh.lacks = true;
            var warnings = new ArrayList<String>();

            InstanceLifecycle.reconcileAccountIdentities(incus, NAME, withToken(), Map.of("github", gh),
                    msg -> { }, warnings::add);

            assertEquals(List.of(), warnings, stamps.toString());
            assertEquals(0, gh.checks, stamps.toString());
            assertEquals(List.of(), gh.rebaked, stamps.toString());
            verify(incus, never()).pollUntilReady(any(), anyInt(), any(String[].class));
        }
    }

    private static void reconcile(FakeIncusDaemon daemon, RecordingGh gh) throws Exception {
        var warnings = new ArrayList<String>();
        InstanceLifecycle.reconcileAccountIdentities(readyClient(daemon), NAME, withToken(), Map.of("github", gh),
                msg -> { }, warnings::add);
        assertEquals(List.of(), warnings);
    }

    @Test
    void reconcilingTheMarkerWritesTheIdentityAndStampsIt() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.accountIdentityKey("github"), Metadata.ACCOUNT_IDENTITY_NONE));
        var incus = readyClient(daemon);
        var gh = new RecordingGh();
        var warnings = new ArrayList<String>();

        InstanceLifecycle.reconcileAccountIdentities(incus, NAME, withToken(), Map.of("github", gh),
                msg -> { }, warnings::add);

        assertEquals(List.of("me"), gh.rebaked);
        assertEquals(List.of(), warnings);
        assertEquals(new GhSetup().bakedAccountIdentity(withToken(), "me"),
                daemon.instance(NAME).path("config").path(Metadata.accountIdentityKey("github")).asText(),
                "the stamp now says what was derived, so the next start does not do it again");

        InstanceLifecycle.reconcileAccountIdentities(incus, NAME, withToken(), Map.of("github", gh),
                msg -> { }, warnings::add);
        assertEquals(List.of("me"), gh.rebaked, "repaired once");
    }

    /** As an account re-point: a failed re-derive warns, and keeps the marker for the next use. */
    @Test
    void aFailedRepairWarnsAndIsRetriedOnTheNextUse() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.accountIdentityKey("github"), Metadata.ACCOUNT_IDENTITY_NONE));
        var gh = new RecordingGh();
        gh.fail = true;
        var warnings = new ArrayList<String>();

        InstanceLifecycle.reconcileAccountIdentities(readyClient(daemon), NAME, withToken(),
                Map.of("github", gh), msg -> { }, warnings::add);

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("could not update credential identity"), warnings.get(0));
        assertEquals(Metadata.ACCOUNT_IDENTITY_NONE,
                daemon.instance(NAME).path("config").path(Metadata.accountIdentityKey("github")).asText());
    }

    /** The fake serves no exec; the instance is taken to have its address already. */
    private static IncusClient readyClient(FakeIncusDaemon daemon) {
        return client(daemon, true);
    }

    /** An instance that never gets an address, as an airgapped one. */
    private static IncusClient addresslessClient(FakeIncusDaemon daemon) {
        return client(daemon, false);
    }

    private static IncusClient client(FakeIncusDaemon daemon, boolean hasAddress) {
        var incus = spy(daemon.client());
        doReturn(hasAddress).when(incus).pollUntilReady(eq(NAME), anyInt(), any(String[].class));
        return incus;
    }

    /** gh, minus the guest: records what it was asked to re-derive. */
    private static final class RecordingGh extends GhSetup {
        final List<String> rebaked = new ArrayList<>();
        boolean fail;
        boolean lacks;
        int checks;

        @Override
        public void rebakeForAccount(Container container, String accountName) {
            if (fail) throw new dev.incusspawn.incus.IncusException("Could not determine git identity from GitHub");
            rebaked.add(accountName);
            lacks = false;
        }

        @Override
        public boolean lacksBakedIdentity(Container container) {
            checks++;
            return lacks;
        }
    }
}
