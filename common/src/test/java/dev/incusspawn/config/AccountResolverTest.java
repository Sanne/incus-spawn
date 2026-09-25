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

    /** GitHub's shape, as {@code GhSetup} declares it. */
    private static final AccountShape GITHUB =
            AccountShape.declared(java.util.List.of("token"), java.util.List.of("token", "email"));

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

    /**
     * A flat credential is the account {@code default}: listed, resolved and pinnable exactly
     * as if it had been written under {@code accounts:} (#773).
     */
    @Test
    void aFlatCredentialPresentsAsTheDefaultAccount() throws Exception {
        var t = tree("""
                github:
                  token: "ghp_flat"
                """);
        assertEquals("default", AccountResolver.effectiveAccount(t, "github", GITHUB, null));
        assertEquals("default", AccountResolver.effectiveAccount(t, "github", GITHUB, "default"));
        assertEquals(java.util.List.of("default"), AccountResolver.accountNames(t, "github", GITHUB));
        assertEquals("ghp_flat", AccountResolver.value(t, "github", "default", "token"));
    }

    /** Only the credential makes an account: an email left behind, or an empty token, does not. */
    @Test
    void aNamespaceWithoutItsCredentialHasNoAccounts() throws Exception {
        for (var yaml : java.util.List.of("github:\n  email: \"me@example.com\"\n",
                "github:\n  token: \"\"\n", "claude: {}\n")) {
            var t = tree(yaml);
            assertEquals("", AccountResolver.effectiveAccount(t, "github", GITHUB, null), yaml);
            assertTrue(AccountResolver.accountNames(t, "github", GITHUB).isEmpty(), yaml);
        }
    }

    /** With nothing declared about a namespace, a flat file stays without accounts, as before. */
    @Test
    void anUndeclaredNamespaceRecognisesNoFlatCredential() throws Exception {
        var t = tree("artifactory:\n  token: \"art\"\n");
        assertEquals("", AccountResolver.effectiveAccount(t, "artifactory", AccountShape.UNDECLARED, null));
    }

    @Test
    void namedDefaultWins() throws Exception {
        var t = twoAccountsWithDefault("acme");
        assertEquals("acme", AccountResolver.effectiveAccount(t, "github", GITHUB, null));
        assertEquals(java.util.List.of("personal", "acme"),
                AccountResolver.accountNames(t, "github", GITHUB));
    }

    @Test
    void explicitSelectionOverridesTheDefault() throws Exception {
        var t = twoAccountsWithDefault("acme");
        assertEquals("personal", AccountResolver.effectiveAccount(t, "github", GITHUB, "personal"));
    }

    @Test
    void missingDefaultFallsBackToFirstInFileOrder() throws Exception {
        var t = twoAccountsWithDefault("typo");
        assertEquals("personal", AccountResolver.effectiveAccount(t, "github", GITHUB, null));
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
                () -> AccountResolver.effectiveAccount(t, "github", GITHUB, "acme"));
        assertEquals("github", e.namespace());
        assertEquals("acme", e.accountName());
        assertTrue(e.getMessage().contains("personal"),
                "the message should name what is configured: " + e.getMessage());
    }

    /** The flat credential answers only to {@code default}; any other name is still refused. */
    @Test
    void selectingAnAccountWhenTheNamespaceIsFlatFailsClosed() throws Exception {
        var t = tree("""
                github:
                  token: "ghp_flat"
                """);
        assertThrows(AccountResolver.UnknownAccountException.class,
                () -> AccountResolver.effectiveAccount(t, "github", GITHUB, "acme"));
    }

    @Test
    void unknownNamespaceIsNotAnError() throws Exception {
        var t = tree("claude: {}\n");
        assertEquals("", AccountResolver.effectiveAccount(t, "nosuchtool", AccountShape.UNDECLARED, null));
        assertEquals("", AccountResolver.defaultName(t, "nosuchtool"));
    }

    /**
     * An account the namespace's shape calls unusable is skipped when picking the default, and
     * pinning it says why rather than claiming it does not exist (#742).
     */
    @Test
    void anUnusableAccountIsSkippedAndExplained() throws Exception {
        var needsToken = new AccountShape() {
            @Override public boolean hasFlatCredential(com.fasterxml.jackson.databind.JsonNode ns) { return false; }
            @Override public com.fasterxml.jackson.databind.node.ObjectNode flatAccount(com.fasterxml.jackson.databind.JsonNode ns) {
                return JSON.createObjectNode();
            }
            @Override public java.util.List<String> flatKeys() { return java.util.List.of(); }
            @Override public String problem(com.fasterxml.jackson.databind.JsonNode account,
                                            com.fasterxml.jackson.databind.JsonNode ns) {
                return account.path("token").asText().isBlank() ? "it has no token" : "";
            }
        };
        var t = tree("""
                github:
                  accounts:
                    broken:
                      email: "x@example.com"
                    acme:
                      token: "ghp_acme"
                  default: broken
                """);
        assertEquals("acme", AccountResolver.effectiveAccount(t, "github", needsToken, null));
        assertEquals(java.util.List.of("acme"), AccountResolver.usableAccountNames(t, "github", needsToken));
        assertEquals(java.util.List.of("broken", "acme"), AccountResolver.accountNames(t, "github", needsToken));
        var e = assertThrows(AccountResolver.UnknownAccountException.class,
                () -> AccountResolver.effectiveAccount(t, "github", needsToken, "broken"));
        assertTrue(e.getMessage().contains("incomplete") && e.getMessage().contains("it has no token"),
                e.getMessage());
    }
}
