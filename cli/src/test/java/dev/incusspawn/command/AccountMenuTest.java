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

    /** Drives the menu with canned keystrokes; account names come from the same queue. */
    private static InitCommand.AccountTarget choose(SpawnConfig config, String... input) {
        return new InitCommand().chooseAccountTarget(config, "github", "GitHub",
                ScriptedPrompts.lines(input)).orElse(null);
    }

    private static InitCommand.AccountTarget target(String name, boolean replaceOthers) {
        return new InitCommand.AccountTarget(name, replaceOthers);
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

        InitCommand.saveAccount(config, "github", GITHUB, target("default", true),
                java.util.Map.of("token", "ghp_new"));
        assertEquals(List.of("default"), NamespaceAccounts.names(config, "github"));
        assertEquals("default", value(config, "github.default"));
    }

    /** Skipping every prompt after "replace all" collected nothing, so nothing is replaced. */
    @Test
    void savingNothingReplacesNothing() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        assertFalse(InitCommand.saveAccount(config, "github", GITHUB, target("default", true), java.util.Map.of()));
        assertEquals(List.of("personal", "acme"), NamespaceAccounts.names(config, "github"));
    }

    /** Values without the credential (an email alone) are not an account, so nothing is replaced. */
    @Test
    void savingWithoutTheCredentialReplacesNothing() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        assertFalse(InitCommand.saveAccount(config, "github", GITHUB, target("default", true),
                java.util.Map.of("email", "me@example.com")));
        assertEquals(List.of("personal", "acme"), NamespaceAccounts.names(config, "github"));
    }

    /** An existing account keeps its credential, so its other values may change on their own. */
    @Test
    void anExistingAccountMayBeEditedWithoutItsCredential() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        assertTrue(InitCommand.saveAccount(config, "github", GITHUB, target("acme", false),
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
        assertEquals(InitCommand.AccountTarget.FRESH, choose(config));
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
        assertTrue(InitCommand.hasAccountsToPreserve(config, "github"));

        assertFalse(InitCommand.hasAccountsToPreserve(
                YAML.readValue("github:\n  email: \"x@example.com\"\n", SpawnConfig.class), "github"),
                "a leftover email is not an account, so a first credential needs no menu");
    }
}
