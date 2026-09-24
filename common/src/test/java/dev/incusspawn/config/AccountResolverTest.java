package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The generic, namespace-agnostic half of account resolution -- the part that gives any tool
 * with a {@code config-namespace} named accounts without Java code of its own.
 */
class AccountResolverTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Two accounts in file order; each test appends its own {@code default:} line. */
    private static final String TWO_ACCOUNTS = """
            github:
              accounts:
                personal:
                  token: "ghp_personal"
                acme:
                  token: "ghp_acme"
            """;

    private static com.fasterxml.jackson.databind.JsonNode tree(String yaml) throws Exception {
        return JSON.valueToTree(YAML.readValue(yaml, SpawnConfig.class));
    }

    private static com.fasterxml.jackson.databind.JsonNode twoAccountsWithDefault(String name)
            throws Exception {
        return tree(TWO_ACCOUNTS + "  default: " + name + "\n");
    }

    @Test
    void noAccountsAndNoSelectionResolvesToNothing() throws Exception {
        var t = tree("""
                github:
                  token: "ghp_flat"
                """);
        assertEquals("", AccountResolver.effectiveAccount(t, "github", null));
        assertTrue(AccountResolver.accountNames(t, "github").isEmpty());
    }

    @Test
    void namedDefaultWins() throws Exception {
        var t = twoAccountsWithDefault("acme");
        assertEquals("acme", AccountResolver.effectiveAccount(t, "github", null));
        assertEquals(java.util.List.of("personal", "acme"),
                AccountResolver.accountNames(t, "github"));
    }

    @Test
    void explicitSelectionOverridesTheDefault() throws Exception {
        var t = twoAccountsWithDefault("acme");
        assertEquals("personal", AccountResolver.effectiveAccount(t, "github", "personal"));
    }

    @Test
    void missingDefaultFallsBackToFirstInFileOrder() throws Exception {
        var t = twoAccountsWithDefault("typo");
        assertEquals("personal", AccountResolver.effectiveAccount(t, "github", null));
    }

    /**
     * The point of #351: an instance pinned to a client's account must never quietly fall
     * back to another one.
     */
    @Test
    void selectingAnAccountThatDoesNotExistFailsClosed() throws Exception {
        var t = tree("""
                github:
                  accounts:
                    personal:
                      token: "ghp_personal"
                """);
        var e = assertThrows(AccountResolver.UnknownAccountException.class,
                () -> AccountResolver.effectiveAccount(t, "github", "acme"));
        assertEquals("github", e.namespace());
        assertEquals("acme", e.accountName());
        assertTrue(e.getMessage().contains("personal"),
                "the message should name what is configured: " + e.getMessage());
    }

    @Test
    void selectingAnAccountWhenTheNamespaceIsFlatFailsClosed() throws Exception {
        var t = tree("""
                github:
                  token: "ghp_flat"
                """);
        assertThrows(AccountResolver.UnknownAccountException.class,
                () -> AccountResolver.effectiveAccount(t, "github", "acme"));
    }

    @Test
    void unknownNamespaceIsNotAnError() throws Exception {
        var t = tree("claude: {}\n");
        assertEquals("", AccountResolver.effectiveAccount(t, "nosuchtool", null));
        assertEquals("", AccountResolver.defaultName(t, "nosuchtool"));
    }
}
