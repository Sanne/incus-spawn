package dev.incusspawn.command;

import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.NamespaceAccounts;
import dev.incusspawn.config.SpawnConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code isx init} account menu, driven by canned input.
 *
 * <p>This code was reachable only by a human at a keyboard, which is how both of its bugs went
 * unnoticed until it was run under a PTY: a cancelled flow rewrote config.yaml, and the flat
 * fields it emptied stayed behind as {@code ""}. The menu now takes its lines from a supplier
 * rather than a {@code Console} -- which is final, and so cannot be stood in for -- so its
 * decisions can be asserted here instead.
 */
class AccountMenuTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> KEYS = List.of("token", "email");

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
    private static String choose(SpawnConfig config, String... input) {
        var lines = new ArrayDeque<>(List.of(input));
        return new InitCommand().chooseAccountTarget(config, "github", "GitHub", KEYS,
                () -> lines.isEmpty() ? "" : lines.poll(),
                taken -> lines.isEmpty() ? "" : lines.poll());
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

    @Test
    void replaceTargetsTheFlatLayout() throws Exception {
        var config = YAML.readValue(ONE_FLAT_CREDENTIAL, SpawnConfig.class);
        assertEquals("", choose(config, "r"),
                "an empty target means the flat layout, which a single credential keeps");
    }

    @Test
    void addingASecondAccountReturnsItsName() throws Exception {
        var config = YAML.readValue(ONE_FLAT_CREDENTIAL, SpawnConfig.class);
        assertEquals("acme-bot", choose(config, "a", "acme-bot"));
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
        assertTrue(NamespaceAccounts.names(config, "github").isEmpty());
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
        assertEquals("acme", choose(config, "e", "acme"));
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

    @Test
    void replaceAllClearsEveryAccount() throws Exception {
        var config = YAML.readValue(TWO_ACCOUNTS, SpawnConfig.class);
        assertEquals("", choose(config, "r"));
        assertTrue(NamespaceAccounts.names(config, "github").isEmpty());
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
}
