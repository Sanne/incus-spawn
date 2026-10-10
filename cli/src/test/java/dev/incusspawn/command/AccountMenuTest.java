package dev.incusspawn.command;

import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.AccountShape;
import dev.incusspawn.config.NamespaceAccounts;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.GhSetup;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code isx init} account menu, driven by canned input.
 *
 * <p>This code was reachable only by a human at a keyboard, which is how both of its bugs went
 * unnoticed until it was run under a PTY: a cancelled flow rewrote config.yaml, and the flat
 * fields it emptied stayed behind as {@code ""}. The menu now takes its lines from {@link Prompts}
 * rather than a {@code Console} -- which is final, and so cannot be stood in for -- so its
 * decisions can be asserted here instead.
 *
 * <p>'d' and 'x' save as they go, hence the isolated home.
 */
@ExtendWith(IsolatedHome.class)
class AccountMenuTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final AccountShape GITHUB = new GhSetup().accountShape();

    private static final String ONE_FLAT_CREDENTIAL = """
            github:
              token: "ghp_flat"
              email: "me@example.com"
            """;

    private static final String TWO_ACCOUNTS = """
            github:
              accounts:
                personal:
                  token: "ghp_personal"
                acme:
                  token: "ghp_acme"
              default: personal
            """;

    /**
     * CredentialSetup with its Incus and template lookups replaced: what is pinned where, which
     * templates name which account, and a record of the renames it would push to instances.
     */
    static final class StubbedInit extends CredentialSetup {
        final java.util.Map<String, java.util.Map<String, String>> pins = new java.util.LinkedHashMap<>();
        /** Instances that pin nothing, and whether each is running. */
        final java.util.Map<String, Boolean> unpinned = new java.util.LinkedHashMap<>();
        final java.util.Map<String, java.util.Map<String, String>> baked = new java.util.LinkedHashMap<>();
        final java.util.Map<String, List<String>> templates = new java.util.LinkedHashMap<>();
        final List<String> renames = new java.util.ArrayList<>();
        final List<String> pinned = new java.util.ArrayList<>();
        final List<String> refreshed = new java.util.ArrayList<>();

        StubbedInit pin(String instance, String namespace, String account) {
            pins.computeIfAbsent(instance, k -> new java.util.LinkedHashMap<>()).put(namespace, account);
            return this;
        }

        StubbedInit following(String instance, boolean running) {
            unpinned.put(instance, running);
            return this;
        }

        @Override
        java.util.Map<String, dev.incusspawn.proxy.InstanceRegistry.AccountState> instanceAccountStates() {
            var states = new java.util.LinkedHashMap<String, dev.incusspawn.proxy.InstanceRegistry.AccountState>();
            pins.forEach((name, p) -> states.put(name, new dev.incusspawn.proxy.InstanceRegistry.AccountState(
                    p, baked.getOrDefault(name, java.util.Map.of()), false)));
            unpinned.forEach((name, running) -> states.put(name, new dev.incusspawn.proxy.InstanceRegistry.AccountState(
                    java.util.Map.of(), baked.getOrDefault(name, java.util.Map.of()), running)));
            return states;
        }

        @Override
        List<String> renameInInstances(SpawnConfig config, String namespace, String from, String to) {
            renames.add(namespace + ":" + from + "->" + to);
            return dev.incusspawn.config.AccountUsage.pinnedTo(pins, namespace, from);
        }

        @Override
        List<String> templatesNaming(String namespace, String account) {
            return templates.getOrDefault(namespace + "=" + account, List.of());
        }

        @Override
        List<String> pinInstances(SpawnConfig config, List<String> instances, String namespace, String account) {
            instances.forEach(i -> pinned.add(i + ":" + namespace + "=" + account));
            return List.of();
        }

        @Override
        void refreshIdentities(List<String> instances) {
            refreshed.addAll(instances);
        }
    }

    /** Drives the menu with canned keystrokes; account names come from the same queue. */
    private static CredentialSetup.AccountTarget choose(SpawnConfig config, String... input) {
        return choose(new StubbedInit(), config, input);
    }

    private static CredentialSetup.AccountTarget choose(CredentialSetup init, SpawnConfig config, String... input) {
        return init.chooseAccountTarget(config, "github", "GitHub",
                ScriptedPrompts.lines(input)).orElse(null);
    }

    private static CredentialSetup.AccountTarget target(String name, boolean replaceOthers) {
        return new CredentialSetup.AccountTarget(name, replaceOthers);
    }

    private static String value(SpawnConfig config, String path) {
        return AccountResolver.navigate(config.tree(), path);
    }

    @Test
    void enterKeepsEverythingAsItIs() throws Exception {
        var config = YAML.readValue(ONE_FLAT_CREDENTIAL, SpawnConfig.class);
        assertNull(choose(config, ""), "no target means nothing is written");
        assertEquals("ghp_flat", value(config, "github.token"));
    }

    /** The flat credential is the account 'default', so replacing it targets that account. */
    @Test
    void replaceTargetsTheAccountTheFlatCredentialIs() throws Exception {
        var config = YAML.readValue(ONE_FLAT_CREDENTIAL, SpawnConfig.class);
        assertEquals(target("default", true), choose(config, "r"));
        assertEquals("ghp_flat", value(config, "github.token"), "choosing is not writing");
    }

    @Test
    void addingASecondAccountReturnsItsName() throws Exception {
        var config = YAML.readValue(ONE_FLAT_CREDENTIAL, SpawnConfig.class);
        assertEquals(target("acme-bot", false), choose(config, "a", "acme-bot"));
    }

    /**
     * The regression that shipped: choosing "add", naming it, then abandoning before a
     * credential arrives must leave the file exactly as it was. Migrating at menu time meant a
     * cancelled flow still rewrote it -- into a shape an older isx reads as having no GitHub
     * credentials at all.
     */
    @Test
    void abandoningAfterNamingAnAccountChangesNothing() throws Exception {
        var config = YAML.readValue(ONE_FLAT_CREDENTIAL, SpawnConfig.class);
        var before = YAML.writeValueAsString(config);

        choose(config, "a", "acme-bot");

        assertEquals(before, YAML.writeValueAsString(config),
                "the menu must not write anything; saving is the caller's job");
        assertEquals("ghp_flat", value(config, "github.token"));
        assertEquals(List.of("default"), NamespaceAccounts.names(config, "github"),
                "still just the flat credential, presented as its account");
    }

    @Test
    void cancellingTheNamePromptKeepsEverythingAsItIs() throws Exception {
        var config = YAML.readValue(ONE_FLAT_CREDENTIAL, SpawnConfig.class);
        assertNull(choose(config, "a", ""));
        assertEquals("ghp_flat", value(config, "github.token"));
    }

    // ── once a namespace has accounts, the full menu applies ─────────────────────

    @Test
    void anExistingAccountCanBeTargetedForReplacement() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        assertEquals(target("acme", false), choose(config, "e", "acme"));
    }

    @Test
    void namingAnAccountThatDoesNotExistReturnsToTheMenu() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        // 'e' then a bad name loops; the following Enter then keeps things as they are.
        assertNull(choose(config, "e", "ghost", ""));
    }

    /** 'd' and 'x' act immediately, so their confirmation cannot be left a lie by a later abort. */
    @Test
    void changingTheDefaultTakesEffectImmediately() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        assertNull(choose(config, "d", "acme", ""));
        assertEquals("acme", value(config, "github.default"));
    }

    @Test
    void removingAnAccountTakesEffectAndRepointsTheDefault() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        assertNull(choose(config, "x", "personal", ""));

        assertEquals(List.of("acme"), NamespaceAccounts.names(config, "github"));
        assertEquals("acme", value(config, "github.default"),
                "the default must never name an account that was just removed");
    }

    /**
     * "Replace all" is a target like any other: the accounts go only once the replacement is
     * saved, so abandoning the flow afterwards cannot leave the namespace with nothing.
     */
    @Test
    void replaceAllIsDeferredUntilSomethingIsSaved() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        assertEquals(target("default", true), choose(config, "r"));
        assertEquals(List.of("personal", "acme"), NamespaceAccounts.names(config, "github"));

        CredentialSetup.saveAccount(config, "github", GITHUB, target("default", true),
                java.util.Map.of("token", "ghp_new"));
        assertEquals(List.of("default"), NamespaceAccounts.names(config, "github"));
        assertEquals("default", value(config, "github.default"));
    }

    /** Skipping every prompt after "replace all" collected nothing, so nothing is replaced. */
    @Test
    void savingNothingReplacesNothing() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        assertFalse(CredentialSetup.saveAccount(config, "github", GITHUB, target("default", true), java.util.Map.of()));
        assertEquals(List.of("personal", "acme"), NamespaceAccounts.names(config, "github"));
    }

    /** Values without the credential (an email alone) are not an account, so nothing is replaced. */
    @Test
    void savingWithoutTheCredentialReplacesNothing() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        assertFalse(CredentialSetup.saveAccount(config, "github", GITHUB, target("default", true),
                java.util.Map.of("email", "me@example.com")));
        assertEquals(List.of("personal", "acme"), NamespaceAccounts.names(config, "github"));
    }

    /** An existing account keeps its credential, so its other values may change on their own. */
    @Test
    void anExistingAccountMayBeEditedWithoutItsCredential() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        assertTrue(CredentialSetup.saveAccount(config, "github", GITHUB, target("acme", false),
                java.util.Map.of("email", "bot@example.com")));
        assertEquals("ghp_acme", value(config, "github.accounts.acme.token"));
        assertEquals("bot@example.com", value(config, "github.accounts.acme.email"));
    }

    /** With one account, the destructive options are not offered -- and not reachable blind. */
    @Test
    void removeIsNotActedOnWhenThereIsOnlyOneAccount() throws Exception {
        var config = YAML.readValue("""
                github:
                  accounts:
                    only:
                      token: "ghp_only"
                  default: only
                """, SpawnConfig.class);
        assertNull(choose(config, "x", ""));
        assertEquals(List.of("only"), NamespaceAccounts.names(config, "github"));
    }

    /** Not configured at all: there is nothing to choose between, so the credential is the only one. */
    @Test
    void anUnconfiguredNamespaceTargetsAFreshDefaultAccount() throws Exception {
        var config = YAML.readValue("github: {}\n", SpawnConfig.class);
        assertEquals(CredentialSetup.AccountTarget.FRESH, choose(config));
    }

    /**
     * The default account has no token but another account has one. Asking only whether the
     * default is configured said no, skipped the menu, and the next saved token replaced the
     * whole namespace -- deleting 'b' and its token without a word.
     */
    @Test
    void anyAccountIsWorthAskingAboutEvenWhenTheDefaultHasNoCredential() throws Exception {
        var config = YAML.readValue("""
                github:
                  accounts:
                    a:
                      email: "x@example.com"
                    b:
                      token: "ghp_b"
                  default: a
                """, SpawnConfig.class);
        assertEquals("", AccountResolver.defaultValue(config, "github", "token"),
                "the trap: the default account alone looks unconfigured");
        assertTrue(CredentialSetup.hasAccountsToPreserve(config, "github"));

        assertFalse(CredentialSetup.hasAccountsToPreserve(
                YAML.readValue("github:\n  email: \"x@example.com\"\n", SpawnConfig.class), "github"),
                "a leftover email is not an account, so a first credential needs no menu");
    }

    // ── accounts that instances or templates still use ───────────────────────────

    /** Removing an account an instance is pinned to asks first; declining keeps it. */
    @Test
    void removingAPinnedAccountAsksAndCanBeDeclined() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        var init = new StubbedInit().pin("review-1", "github", "acme");
        assertNull(choose(init, config, "x", "acme", "n", ""));
        assertEquals(List.of("personal", "acme"), NamespaceAccounts.names(config, "github"));
    }

    @Test
    void removingAPinnedAccountProceedsWhenConfirmed() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        var init = new StubbedInit().pin("review-1", "github", "acme");
        assertNull(choose(init, config, "x", "acme", "y", ""));
        assertEquals(List.of("personal"), NamespaceAccounts.names(config, "github"));
    }

    /** A template naming the account counts too: it could no longer be branched. */
    @Test
    void removingAnAccountATemplateNamesAsks() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        var init = new StubbedInit();
        init.templates.put("github=acme", List.of("tpl-acme"));
        assertNull(choose(init, config, "x", "acme", "", ""), "Enter declines");
        assertEquals(List.of("personal", "acme"), NamespaceAccounts.names(config, "github"));
    }

    /** Nobody uses it: removed without a question, as before. */
    @Test
    void removingAnUnusedAccountDoesNotAsk() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        var init = new StubbedInit().pin("review-1", "github", "personal");
        assertNull(choose(init, config, "x", "acme", ""));
        assertEquals(List.of("personal"), NamespaceAccounts.names(config, "github"));
    }

    /** "Replace all" drops every other account, so pins to them are the same question. */
    @Test
    void replaceAllAsksWhenAnInstanceIsPinnedToAnAccountThatGoes() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        var init = new StubbedInit().pin("review-1", "github", "acme");
        assertNull(choose(init, config, "r", "n", ""), "declining returns to the menu");
        assertEquals(target("default", true), choose(init, config, "r", "y"));
    }

    @Test
    void renamingKeepsTheAccountAndItsPlaceAndMovesTheDefault() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        var init = new StubbedInit().pin("review-1", "github", "personal");
        assertNull(choose(init, config, "n", "personal", "me", ""));

        assertEquals(List.of("me", "acme"), NamespaceAccounts.names(config, "github"),
                "file order decides which account serves when the default is unusable");
        assertEquals("ghp_personal", value(config, "github.accounts.me.token"));
        assertEquals("me", value(config, "github.default"));
        assertEquals(List.of("github:personal->me"), init.renames, "pinned instances are followed");
        assertEquals("me", SpawnConfig.load().tree().path("github").path("default").asText(),
                "saved, not only changed in memory");
    }

    @Test
    void renamingOntoAnExistingNameIsRefused() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        var init = new StubbedInit();
        // The taken name is re-asked; Enter then cancels the rename.
        assertNull(choose(init, config, "n", "personal", "acme", "", ""));
        assertEquals(List.of("personal", "acme"), NamespaceAccounts.names(config, "github"));
        assertTrue(init.renames.isEmpty());
    }

    /** Claude accounts are typed; a rename must keep the type and credential intact. */
    @Test
    void renamingAClaudeAccountKeepsItsType() throws Exception {
        var config = YAML.readValue("""
                claude:
                  accounts:
                    personal:
                      type: oauth
                      oauthToken: "sk-ant-oat01-x"
                    acme:
                      type: vertex
                      cloudMlRegion: europe-west1
                      vertexProjectId: acme-prod
                  default: personal
                """, SpawnConfig.class);
        NamespaceAccounts.rename(config, "claude", "acme", "client");
        var renamed = config.getClaude().allAccounts().get("client");
        assertNotNull(renamed);
        assertEquals("acme-prod", renamed.getVertexProjectId());
        assertEquals(SpawnConfig.ClaudeAccountType.VERTEX, renamed.effectiveType());
        assertEquals("personal", config.getClaude().accountName());
    }

    /** A pre-accounts flat credential is the account 'default', and can be renamed like one. */
    @Test
    void renamingAFlatCredentialMaterializesItFirst() throws Exception {
        var config = YAML.readValue(ONE_FLAT_CREDENTIAL, SpawnConfig.class);
        NamespaceAccounts.rename(config, "github", "default", "me");
        assertEquals(List.of("me"), NamespaceAccounts.names(config, "github"));
        assertEquals("ghp_flat", value(config, "github.accounts.me.token"));
        assertEquals("me@example.com", value(config, "github.accounts.me.email"));
        assertEquals("me", value(config, "github.default"));
    }

    // ── changing the default moves every instance that follows it ────────────

    @Test
    void changingTheDefaultWithNobodyFollowingItDoesNotAsk() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        var init = new StubbedInit().pin("review-1", "github", "personal");
        assertNull(choose(init, config, "d", "acme", ""));
        assertEquals("acme", value(config, "github.default"));
    }

    /** Enter switches: not pinned means following the default. */
    @Test
    void followersSwitchByDefaultAndRunningOnesGetTheirIdentityRefreshed() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        var init = new StubbedInit().following("scratch", true).following("idle", false);
        assertNull(choose(init, config, "d", "acme", "", ""));
        assertEquals("acme", value(config, "github.default"));
        assertTrue(init.pinned.isEmpty());
        assertEquals(List.of("scratch"), init.refreshed, "only the running one; the other catches up on next use");
    }

    @Test
    void followersCanBeKeptOnTheAccountTheyUse() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        var init = new StubbedInit().following("scratch", true);
        assertNull(choose(init, config, "d", "acme", "k", ""));
        assertEquals("acme", value(config, "github.default"));
        assertEquals(List.of("scratch:github=personal"), init.pinned);
        assertTrue(init.refreshed.isEmpty(), "nothing moved");
    }

    @Test
    void cancellingLeavesTheDefaultAlone() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        var init = new StubbedInit().following("scratch", true);
        assertNull(choose(init, config, "d", "acme", "c", ""));
        assertEquals("personal", value(config, "github.default"));
        assertTrue(init.pinned.isEmpty());
    }

    /** No answer at all (EOF) must never move anyone's principal. */
    @Test
    void endOfInputCancels() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        var init = new StubbedInit().following("scratch", true);
        choose(init, config, "d", "acme");
        assertEquals("personal", value(config, "github.default"));
    }

    @Test
    void removingTheDefaultNamesItsFollowersAndRefreshesTheRunningOnes() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        var init = new StubbedInit().following("scratch", true);
        assertNull(choose(init, config, "x", "personal", "y", ""));
        assertEquals(List.of("acme"), NamespaceAccounts.names(config, "github"));
        assertEquals(List.of("scratch"), init.refreshed);
    }
}
