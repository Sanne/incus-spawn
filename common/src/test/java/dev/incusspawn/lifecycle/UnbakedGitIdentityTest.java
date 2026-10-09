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
import static org.mockito.Mockito.spy;

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
        var setups = Map.<String, ToolSetup>of("github", new GhSetup());
        var baked = AccountSelection.bakedIdentities(config,
                AccountSelection.effectiveSelection(Map.of(), setups), setups);
        var unbaked = new java.util.LinkedHashSet<>(lacking);
        unbaked.removeAll(BuildAccounts.toRederive(baked, Map.of(), lacking, setups));
        return BuildAccounts.identityStamps(baked, Map.of(), unbaked);
    }

    private static Map<String, String> stale(FakeIncusDaemon daemon, SpawnConfig config) {
        return AccountSelection.staleIdentities(config, daemon.client(), NAME,
                Map.of("github", new GhSetup()));
    }

    // ── what the build stamps ────────────────────────────────────────────────

    @Test
    void aBuildWithGhButNoTokenIsStampedAsHavingNoIdentity() {
        assertEquals(Map.of(Metadata.accountIdentityKey("github"), Metadata.ACCOUNT_IDENTITY_NONE),
                builtStamps(withoutToken(), Set.of("github")));
    }

    @Test
    void aBuildWithoutGhIsStampedWithNothing() {
        assertEquals(Map.of(), builtStamps(withoutToken(), Set.of()),
                "nothing in the image could take an identity, so there is nothing to reconcile");
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
        var incus = spy(daemon.client());
        doReturn(true).when(incus).pollUntilReady(eq(NAME), anyInt(), any(String[].class));
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
