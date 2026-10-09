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
 * token later is reconciled into its branches like any other change of account -- and an
 * image without gh, or one built before the marker existed, is left exactly as it was.
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

    /** What the build of a template with gh stamps, given the accounts configured then. */
    private static Map<String, String> builtStamps(SpawnConfig config, Set<String> rederivable) {
        var setups = Map.<String, ToolSetup>of("github", new GhSetup());
        var baked = AccountSelection.bakedIdentities(config,
                AccountSelection.effectiveSelection(Map.of(), setups), setups);
        return BuildAccounts.identityStamps(baked, Map.of(), rederivable);
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
        var stamps = builtStamps(withToken(), Set.of("github"));
        assertEquals(new GhSetup().bakedAccountIdentity(withToken(), "me"),
                stamps.get(Metadata.accountIdentityKey("github")));
    }

    @Test
    void aParentsRealIdentityIsKeptOverTheMarker() {
        // The token is gone from config.yaml now, but the parent's .gitconfig still has its [user]
        var stamps = BuildAccounts.identityStamps(Map.of(), Map.of("github", "me#0123456789ab"), Set.of("github"));
        assertEquals(Map.of(Metadata.accountIdentityKey("github"), "me#0123456789ab"), stamps);
    }

    @Test
    void aParentsMarkerIsReplacedByWhatTheChildBakes() {
        var stamps = BuildAccounts.identityStamps(Map.of("github", "me#0123456789ab"),
                Map.of("github", Metadata.ACCOUNT_IDENTITY_NONE), Set.of("github"));
        assertEquals(Map.of(Metadata.accountIdentityKey("github"), "me#0123456789ab"), stamps);
    }

    @Test
    void onlyAToolThatOwnsAndCanReDeriveTheNamespaceCounts() throws Exception {
        var base = ImageDef.parseYaml("name: tpl-dev\ntools:\n  - gh\n");
        var child = ImageDef.parseYaml("name: tpl-ai\nparent: tpl-dev\ntools:\n  - claude\n");
        var copilotOnly = ImageDef.parseYaml("name: tpl-copilot\ntools:\n  - copilot\n");
        Map<String, ToolSetup> tools = Map.of("gh", new GhSetup(), "claude", new ClaudeSetup(),
                "copilot", new dev.incusspawn.tool.CopilotSetup());
        var defs = Map.of("tpl-dev", base, "tpl-ai", child, "tpl-copilot", copilotOnly);

        assertEquals(Set.of("github"), AccountSelection.rederivableNamespaces(child, defs, tools),
                "gh from the parent; Claude cannot re-derive, so it is never marked");
        assertEquals(Set.of(), AccountSelection.rederivableNamespaces(copilotOnly, defs, tools),
                "Copilot spends the GitHub credential but derives no git identity");
        assertEquals(Set.of(), AccountSelection.rederivableNamespaces(null, defs, tools));
    }

    // ── what a child's build does with its parent's ──────────────────────────

    private static final Map<String, ToolSetup> SETUPS = Map.of("github", new GhSetup(), "claude", new ClaudeSetup());

    @Test
    void aChildBuiltOnceATokenIsConfiguredReDerivesItsParentsMissingIdentity() {
        assertEquals(Set.of("github"), BuildAccounts.inheritedToRederive(Map.of("github", "me#0123456789ab"),
                Map.of("github", Metadata.ACCOUNT_IDENTITY_NONE), Set.of("github"), SETUPS));
    }

    @Test
    void aChildBuiltStillWithoutATokenHasNothingToReDerive() {
        assertEquals(Set.of(), BuildAccounts.inheritedToRederive(Map.of(),
                Map.of("github", Metadata.ACCOUNT_IDENTITY_NONE), Set.of("github"), SETUPS));
    }

    /** The user's template: built before the marker, with gh, no token and so no stamp. */
    @Test
    void aParentWithGhButNoStampIsReDerivedToo() {
        assertEquals(Set.of("github"), BuildAccounts.inheritedToRederive(Map.of("github", "me#0123456789ab"),
                Map.of(), Set.of("github"), SETUPS));
    }

    @Test
    void aParentWithoutGhAndWithoutAStampIsLeftAlone() {
        assertEquals(Set.of(), BuildAccounts.inheritedToRederive(Map.of("github", "me#0123456789ab"),
                Map.of(), Set.of(), SETUPS), "no gh, no .gitconfig to derive into");
    }

    @Test
    void aParentBuiltForTheSameIdentityIsLeftAlone() {
        assertEquals(Set.of(), BuildAccounts.inheritedToRederive(Map.of("github", "me#0123456789ab"),
                Map.of("github", "me#0123456789ab"), Set.of("github"), SETUPS));
    }

    @Test
    void aNamespaceThatCannotReDeriveIsNeverReDerived() {
        assertEquals(Set.of(), BuildAccounts.inheritedToRederive(Map.of("claude", "api-key"),
                Map.of("claude", "oauth"), Set.of(), SETUPS));
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

    /** Built by an isx that stamped nothing for an account it did not have: unchanged behaviour. */
    @Test
    void anInstanceWithoutAStampIsNotReDerived() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        assertEquals(Map.of(), stale(daemon, withToken()),
                "absent cannot be told from 'no gh' without asking the guest on every branch");
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

        @Override
        public void rebakeForAccount(Container container, String accountName) {
            if (fail) throw new dev.incusspawn.incus.IncusException("Could not determine git identity from GitHub");
            rebaked.add(accountName);
        }
    }
}
