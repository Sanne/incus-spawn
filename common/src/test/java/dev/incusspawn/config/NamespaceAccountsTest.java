package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The write side of credential accounts, exercised on {@code github} -- a namespace with a
 * typed class and no Java that knows about accounts, which is the case every future credential
 * will be in. Every write produces the accounts layout; a flat credential is moved into
 * {@code accounts.default} by the first write, never before.
 */
class NamespaceAccountsTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static SpawnConfig parse(String yaml) throws Exception {
        return YAML.readValue(yaml, SpawnConfig.class);
    }

    private static String dump(SpawnConfig config) throws Exception {
        return YAML.writeValueAsString(config);
    }

    private static String value(SpawnConfig config, String path) {
        return AccountResolver.navigate(new ObjectMapper().valueToTree(config), path);
    }

    @Test
    void addingAnAccountToAFlatNamespaceMaterializesTheFlatOneFirst() throws Exception {
        var config = parse("""
                github:
                  token: "ghp_flat"
                  email: "me@example.com"
                """);
        NamespaceAccounts.put(config, "github", "acme", "token", "ghp_acme");

        // The pre-existing credential survives under its own name, which is the whole point:
        // clearing the flat fields before copying them out would have destroyed it.
        assertEquals("ghp_flat", value(config, "github.accounts.default.token"));
        assertEquals("me@example.com", value(config, "github.accounts.default.email"));
        assertEquals("ghp_acme", value(config, "github.accounts.acme.token"));
        assertEquals("default", value(config, "github.default"),
                "adding an account must not re-point the default at it");
        assertEquals("", value(config, "github.token"), "the flat field should be gone");
        assertEquals("", value(config, "github.email"), "the email belongs to the identity, not the namespace");
    }

    /**
     * With no {@code default:} line the first account in file order serves. Adding another must
     * not write one naming the newcomer, or every unpinned instance would silently move to it.
     */
    @Test
    void addingAnAccountWithoutAnExplicitDefaultKeepsServingTheFirst() throws Exception {
        var config = parse("""
                github:
                  accounts:
                    work:
                      token: "ghp_work"
                    personal:
                      token: "ghp_personal"
                """);
        NamespaceAccounts.put(config, "github", "client", "token", "ghp_client");

        assertEquals("work", NamespaceAccounts.defaultName(config, "github"));
        assertEquals(List.of("work", "personal", "client"), NamespaceAccounts.names(config, "github"));
    }

    /** A single credential is written as an account too: there is one layout, not two. */
    @Test
    void aFirstCredentialIsWrittenAsTheDefaultAccount() throws Exception {
        var config = parse("github: {}\n");
        NamespaceAccounts.put(config, "github", NamespaceAccounts.DEFAULT_ACCOUNT_NAME, "token", "ghp_new");

        assertEquals("ghp_new", value(config, "github.accounts.default.token"));
        assertEquals("default", value(config, "github.default"));
        assertEquals("", value(config, "github.token"));
    }

    /** Replacing a flat credential in place is a write too, and lands in the same account. */
    @Test
    void replacingAFlatCredentialRewritesItAsAnAccount() throws Exception {
        var config = parse("""
                github:
                  token: "ghp_flat"
                  email: "me@example.com"
                """);
        NamespaceAccounts.replaceAll(config, "github", "default", java.util.Map.of("token", "ghp_new"));

        assertEquals(List.of("default"), NamespaceAccounts.names(config, "github"));
        assertEquals("ghp_new", value(config, "github.accounts.default.token"));
        assertEquals("", value(config, "github.token"));
        assertEquals("", value(config, "github.email"), "replace-all keeps nothing of the old identity");
    }

    @Test
    void materializeIsANoOpOnceAccountsExist() throws Exception {
        var config = parse("""
                github:
                  accounts:
                    acme:
                      token: "ghp_acme"
                  default: acme
                """);
        NamespaceAccounts.materialize(config, "github");
        assertEquals(List.of("acme"), NamespaceAccounts.names(config, "github"));
        assertEquals("acme", value(config, "github.default"));
    }

    /** Only the credential moves; a value shared by every account stays where they all inherit it. */
    @Test
    void materializeLeavesSharedValuesInPlace() throws Exception {
        var config = parse("""
                bob:
                  apiKey: "bob-flat"
                  licenseConsent: true
                """);
        NamespaceAccounts.put(config, "bob", "work", "apiKey", "bob-work");

        assertEquals("bob-flat", value(config, "bob.accounts.default.apiKey"));
        assertEquals("bob-work", value(config, "bob.accounts.work.apiKey"));
        assertTrue(config.getBob().isLicenseConsent(), "consent is the namespace's, not one key's");
        assertEquals("true", AccountResolver.value(config, "bob", "work", "licenseConsent"),
                "every account inherits a shared value");
    }

    @Test
    void removingAnAccountRepointsTheDefault() throws Exception {
        var config = parse("""
                github:
                  accounts:
                    personal:
                      token: "ghp_personal"
                    acme:
                      token: "ghp_acme"
                  default: acme
                """);
        NamespaceAccounts.remove(config, "github", "acme");
        assertEquals(List.of("personal"), NamespaceAccounts.names(config, "github"));
        assertEquals("personal", value(config, "github.default"),
                "the default must never name an account that is gone");
    }

    /** An emptied account would still be listed, so removal has to prune the parent. */
    @Test
    void removingTheLastAccountLeavesNothingBehind() throws Exception {
        var config = parse("""
                github:
                  accounts:
                    acme:
                      token: "ghp_acme"
                  default: acme
                """);
        NamespaceAccounts.remove(config, "github", "acme");
        assertTrue(NamespaceAccounts.names(config, "github").isEmpty());
        assertEquals("", value(config, "github.default"));
        assertFalse(dump(config).contains("acme"), dump(config));
    }

    @Test
    void removingAFieldDoesNotLeaveAnEmptyString() throws Exception {
        var config = parse("""
                github:
                  accounts:
                    acme:
                      token: "ghp_acme"
                      email: "bot@acme.example"
                """);
        NamespaceAccounts.put(config, "github", "acme", "email", "");
        assertEquals("", value(config, "github.accounts.acme.email"));
        assertFalse(dump(config).contains("acme.example"), dump(config));
        assertEquals("ghp_acme", value(config, "github.accounts.acme.token"));
    }

    @Test
    void setDefaultAndNamesRoundTripThroughASave() throws Exception {
        var config = parse("github: {}\n");
        NamespaceAccounts.put(config, "github", "acme", "token", "ghp_acme");
        NamespaceAccounts.put(config, "github", "personal", "token", "ghp_personal");
        NamespaceAccounts.setDefault(config, "github", "personal");

        var reloaded = parse(dump(config));
        assertEquals(List.of("acme", "personal"), NamespaceAccounts.names(reloaded, "github"));
        assertEquals("personal", NamespaceAccounts.defaultName(reloaded, "github"));
        assertEquals("ghp_acme", value(reloaded, "github.accounts.acme.token"));
    }

    @Test
    void clearRemovesAccountsAndFlatFieldsAlike() throws Exception {
        var config = parse("""
                github:
                  token: "ghp_flat"
                  accounts:
                    acme:
                      token: "ghp_acme"
                  default: acme
                """);
        NamespaceAccounts.clear(config, "github");
        assertTrue(NamespaceAccounts.names(config, "github").isEmpty());
        assertEquals("", value(config, "github.token"));
        assertEquals("", value(config, "github.default"));
    }

    /**
     * The flat fields must not survive as empty strings once their credential has moved: an
     * older isx reads {@code token: ""} as a namespace configured with nothing, rather than as
     * one it does not understand, and reports no credentials at all.
     */
    @Test
    void materializeLeavesNoEmptyFlatFieldsBehind() throws Exception {
        for (var namespace : List.of("github", "bob", "openai")) {
            var key = namespace.equals("github") ? "token" : "apiKey";
            var config = parse(namespace + ":\n  " + key + ": \"flat-secret\"\n");
            NamespaceAccounts.materialize(config, namespace);

            var out = dump(config);
            assertFalse(out.contains(key + ": \"\""), out);
            assertEquals("flat-secret", value(config, namespace + ".accounts.default." + key));
        }
    }

    /** Works the same for a namespace with no Java class at all, given the shape its tool declares. */
    @Test
    void anUnknownNamespaceBehavesIdentically() throws Exception {
        var shape = AccountShape.declared(List.of("token"), List.of("token"));
        var config = parse("artifactory:\n  token: \"art-flat\"\n");
        NamespaceAccounts.materialize(config, "artifactory", shape);
        NamespaceAccounts.put(config, "artifactory", "acme", "token", "art-acme");

        var reloaded = parse(dump(config));
        assertEquals(List.of("default", "acme"),
                AccountResolver.accountNames(reloaded.tree(), "artifactory", shape));
        assertEquals("art-flat", value(reloaded, "artifactory.accounts.default.token"));
        assertEquals("", value(reloaded, "artifactory.token"));
    }
}
