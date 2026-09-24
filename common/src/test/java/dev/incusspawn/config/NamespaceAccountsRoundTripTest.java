package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A namespace's {@code accounts:} block must survive load-and-save.
 *
 * <p>Before {@link SpawnConfig.NamespaceConfig}, {@code GitHubConfig} and friends declared only
 * their flat fields and ignored everything else, so a hand-written {@code accounts:} block
 * deserialized to nothing and the next save <em>deleted</em> it -- destroying tokens, in the same
 * shape as the {@code putAccount} bug found in the review of the Claude account model. Namespaces
 * with no Java class were never at risk: they land in {@link SpawnConfig}'s own extras.
 */
class NamespaceAccountsRoundTripTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static String roundTrip(String yaml) throws Exception {
        return YAML.writeValueAsString(YAML.readValue(yaml, SpawnConfig.class));
    }

    @Test
    void githubAccountsSurviveASave() throws Exception {
        var out = roundTrip("""
                github:
                  accounts:
                    personal:
                      token: "ghp_personal"
                    acme:
                      token: "ghp_acme"
                  default: acme
                """);
        assertTrue(out.contains("ghp_personal"), out);
        assertTrue(out.contains("ghp_acme"), out);
        assertTrue(out.contains("acme"), out);

        // And still resolve after the round trip.
        var reloaded = YAML.readValue(out, SpawnConfig.class);
        var tree = new ObjectMapper().<com.fasterxml.jackson.databind.JsonNode>valueToTree(reloaded);
        assertEquals("acme", AccountResolver.effectiveAccount(tree, "github", null));
        assertEquals("ghp_personal", dev.incusspawn.proxy.ToolProxyResolver
                .navigateConfigPath(tree, "github.accounts.personal.token"));
    }

    @Test
    void bobAndOpenaiAccountsSurviveASave() throws Exception {
        var out = roundTrip("""
                bob:
                  accounts:
                    work:
                      apiKey: "bob-work"
                  default: work
                openai:
                  accounts:
                    work:
                      apiKey: "sk-openai-work"
                  default: work
                """);
        assertTrue(out.contains("bob-work"), out);
        assertTrue(out.contains("sk-openai-work"), out);
    }

    @Test
    void flatFieldsStillRoundTripAlongsideAccounts() throws Exception {
        var out = roundTrip("""
                github:
                  token: "ghp_flat"
                  email: "me@example.com"
                  accounts:
                    acme:
                      token: "ghp_acme"
                """);
        assertTrue(out.contains("ghp_flat"), out);
        assertTrue(out.contains("me@example.com"), out);
        assertTrue(out.contains("ghp_acme"), out);
    }

    /**
     * The fix only holds for classes that opt in, so a new namespace class that forgets
     * {@code extends NamespaceConfig} would reintroduce credential deletion silently. Assert
     * the rule structurally rather than naming today's three by hand -- the same drift-check
     * shape as {@code NativeImageInitializationTest}.
     */
    @Test
    void everyNamespaceConfigClassPreservesUnknownKeys() {
        for (var nested : SpawnConfig.class.getDeclaredClasses()) {
            if (!java.lang.reflect.Modifier.isPublic(nested.getModifiers())) continue;
            if (nested.isEnum() || nested.isInterface() || nested.isRecord()) continue;
            if (nested == SpawnConfig.NamespaceConfig.class) continue;
            // ClaudeConfig declares `accounts` as a typed field, so it needs no any-setter.
            if (nested == SpawnConfig.ClaudeConfig.class
                    || nested == SpawnConfig.ClaudeAccount.class) continue;
            assertTrue(SpawnConfig.NamespaceConfig.class.isAssignableFrom(nested),
                    nested.getSimpleName() + " must extend NamespaceConfig, or an accounts:"
                            + " block written into that namespace is deleted on the next save");
        }
    }

    /** A namespace with no Java class at all was already safe; keep it that way. */
    @Test
    void unknownNamespaceAccountsSurviveASave() throws Exception {
        var out = roundTrip("""
                artifactory:
                  accounts:
                    acme:
                      token: "art-acme"
                  default: acme
                """);
        assertTrue(out.contains("art-acme"), out);
    }

}
