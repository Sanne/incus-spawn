package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The write side of credential accounts, exercised on {@code github} -- a namespace with a
 * typed class and no Java that knows about accounts, which is the case every future credential
 * will be in.
 */
class NamespaceAccountsTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> GITHUB_KEYS = List.of("token", "email");

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
        assertTrue(NamespaceAccounts.hasFlatCredential(config, "github", GITHUB_KEYS));

        NamespaceAccounts.adoptFlat(config, "github", GITHUB_KEYS,
                NamespaceAccounts.DEFAULT_ACCOUNT_NAME);
        NamespaceAccounts.put(config, "github", "acme", "token", "ghp_acme");

        // The pre-existing credential survives under its own name, which is the whole point:
        // clearing the flat fields before copying them out would have destroyed it.
        assertEquals("ghp_flat", value(config, "github.accounts.default.token"));
        assertEquals("me@example.com", value(config, "github.accounts.default.email"));
        assertEquals("ghp_acme", value(config, "github.accounts.acme.token"));
        assertEquals("default", value(config, "github.default"));
        assertEquals("", value(config, "github.token"), "the flat field should be gone");
    }

    @Test
    void adoptFlatIsANoOpOnceAccountsExist() throws Exception {
        var config = parse("""
                github:
                  accounts:
                    acme:
                      token: "ghp_acme"
                  default: acme
                """);
        NamespaceAccounts.adoptFlat(config, "github", GITHUB_KEYS, "default");
        assertEquals(List.of("acme"), NamespaceAccounts.names(config, "github"));
        assertEquals("acme", value(config, "github.default"));
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
        NamespaceAccounts.clear(config, "github", GITHUB_KEYS);
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
    void adoptFlatLeavesNoEmptyFlatFieldsBehind() throws Exception {
        var config = parse("""
                github:
                  token: "ghp_flat"
                  email: "me@example.com"
                """);
        NamespaceAccounts.adoptFlat(config, "github", GITHUB_KEYS,
                NamespaceAccounts.DEFAULT_ACCOUNT_NAME);

        var out = dump(config);
        assertFalse(out.contains("token: \"\""), out);
        assertFalse(out.contains("email: \"\""), out);
        assertEquals("ghp_flat", value(config, "github.accounts.default.token"));
    }

    /** Works the same for a namespace with no Java class at all. */
    @Test
    void anUnknownNamespaceBehavesIdentically() throws Exception {
        var config = parse("artifactory:\n  token: \"art-flat\"\n");
        NamespaceAccounts.adoptFlat(config, "artifactory", List.of("token"), "default");
        NamespaceAccounts.put(config, "artifactory", "acme", "token", "art-acme");

        var reloaded = parse(dump(config));
        assertEquals(List.of("default", "acme"),
                NamespaceAccounts.names(reloaded, "artifactory"));
        assertEquals("art-flat", value(reloaded, "artifactory.accounts.default.token"));
    }
}
