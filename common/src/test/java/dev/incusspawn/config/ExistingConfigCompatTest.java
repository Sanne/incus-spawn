package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whole config.yaml files that exist in the wild must keep loading.
 *
 * <p>{@code ClaudeLegacyConfigCompatTest} pins the Claude section specifically; this covers the
 * rest of the file, and the paths the account work added that can touch it --
 * {@link SpawnConfig#removeConfigPath} in particular, which rebuilds the object from a pruned
 * tree and so could silently drop anything it fails to carry over.
 *
 * <p>Downgrading is explicitly not a goal. Reading what users already have is.
 */
class ExistingConfigCompatTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * A file from before any of this: every credential flat, plus the phantom {@code oauthMode}
     * key the old auto-detected is-getter used to write, and a user-defined tool namespace that
     * only ever lived in {@code extras}.
     */
    private static final String PRE_ACCOUNTS = """
            claude:
              useVertex: false
              oauthMode: true
              oauthToken: "sk-ant-oat01-legacy"
              apiKey: ""
            github:
              token: "ghp_legacy"
              email: "me@example.com"
            bob:
              apiKey: "bob-legacy"
              licenseConsent: true
            openai:
              apiKey: "sk-openai-legacy"
            artifactory:
              token: "art-legacy"
            features:
              - openai
            searchPaths:
              - ~/my-templates
            host-paths:
              - ~/src
            repo-paths:
              quarkus: ~/quarkus
            incus-bridge-gateway: "10.0.0.1"
            auto-clone-repos: "always"
            """;

    private static SpawnConfig parse(String yaml) throws Exception {
        return YAML.readValue(yaml, SpawnConfig.class);
    }

    private static String dump(SpawnConfig config) throws Exception {
        return YAML.writeValueAsString(config);
    }

    @Test
    void aPreAccountsFileLoadsWithEveryFieldIntact() throws Exception {
        var config = parse(PRE_ACCOUNTS);

        assertEquals("sk-ant-oat01-legacy", config.getClaude().getOauthToken());
        assertEquals("ghp_legacy", AccountResolver.value(config, "github", "", "token"));
        assertEquals("me@example.com", AccountResolver.value(config, "github", "", "email"));
        assertEquals("bob-legacy", config.getBob().getApiKey());
        assertTrue(config.getBob().isLicenseConsent());
        assertEquals("sk-openai-legacy", config.getOpenai().getApiKey());
        assertEquals("art-legacy", AccountResolver.value(config, "artifactory", "", "token"));
        assertEquals(List.of("openai"), config.getFeatures());
        assertEquals(List.of("~/my-templates"), config.getSearchPaths());
        assertEquals("10.0.0.1", config.getIncusBridgeGateway());
        assertEquals("always", config.getAutoCloneRepos());
    }

    /** Saving such a file must not lose anything, whatever else it reshapes. */
    @Test
    void aPreAccountsFileSurvivesASave() throws Exception {
        var out = dump(parse(PRE_ACCOUNTS));
        for (var value : List.of("sk-ant-oat01-legacy", "ghp_legacy", "me@example.com",
                "bob-legacy", "sk-openai-legacy", "art-legacy",
                "~/my-templates", "~/src", "~/quarkus", "10.0.0.1", "always")) {
            assertTrue(out.contains(value), "lost '" + value + "' on save:\n" + out);
        }
    }

    /**
     * {@code removeConfigPath} rebuilds the whole object from a pruned tree, so a single
     * removal is the most likely way for an unrelated field to disappear. It is reached from
     * {@code isx init} whenever a credential moves into an account.
     */
    @Test
    void removingOneKeyLeavesEveryOtherFieldAlone() throws Exception {
        var config = parse(PRE_ACCOUNTS);
        config.removeConfigPath("github.token");

        assertEquals("", AccountResolver.value(config, "github", "", "token"));
        // Everything else, including the namespace's own sibling key.
        assertEquals("me@example.com", AccountResolver.value(config, "github", "", "email"));
        assertEquals("sk-ant-oat01-legacy", config.getClaude().getOauthToken());
        assertEquals("bob-legacy", config.getBob().getApiKey());
        assertTrue(config.getBob().isLicenseConsent());
        assertEquals("sk-openai-legacy", config.getOpenai().getApiKey());
        assertEquals("art-legacy", AccountResolver.value(config, "artifactory", "", "token"));
        assertEquals(List.of("openai"), config.getFeatures());
        assertEquals(List.of("~/my-templates"), config.getSearchPaths());
        assertEquals(List.of("~/src"), config.getHostPaths());
        assertEquals("~/quarkus", config.getRepoPaths().get("quarkus"));
        assertEquals("10.0.0.1", config.getIncusBridgeGateway());
        assertEquals("always", config.getAutoCloneRepos());
    }

    /**
     * {@code copyFrom} is the hand-written half of {@code removeConfigPath}. A field added to
     * SpawnConfig and forgotten there is reset to its default by any removal -- silently, and
     * only for users who edit credentials. Asserted structurally rather than by listing today's
     * fields, the same drift-check shape as {@code NativeImageInitializationTest}.
     */
    @Test
    void copyFromCarriesEveryFieldSpawnConfigDeclares() throws Exception {
        var source = new StringBuilder(java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/dev/incusspawn/config/SpawnConfig.java"))).toString();
        var head = source.substring(0, source.indexOf("public enum ClaudeAccountType"));
        var declared = Pattern.compile("^ {4}private (?!static)(?:final )?[\\w<>, .\\[\\]]+ (\\w+)\\s*(?:=|;)",
                        Pattern.MULTILINE).matcher(head).results()
                .map(r -> r.group(1)).collect(java.util.stream.Collectors.toSet());
        var body = source.substring(source.indexOf("private void copyFrom(SpawnConfig other)"));
        body = body.substring(0, body.indexOf("\n    }"));
        var copied = Pattern.compile("this\\.(\\w+)\\s*=").matcher(body).results()
                .map(r -> r.group(1)).collect(java.util.stream.Collectors.toSet());

        assertFalse(declared.isEmpty(), "field scan found nothing -- the pattern has drifted");
        declared.removeAll(copied);
        assertTrue(declared.isEmpty(),
                "SpawnConfig.copyFrom must carry these, or removeConfigPath silently resets them: "
                        + declared);
    }

    /** A key no version of isx knows about must survive a load/save round trip. */
    @Test
    void unknownTopLevelAndNamespaceKeysSurvive() throws Exception {
        var out = dump(parse("""
                github:
                  token: "ghp_legacy"
                  someFutureKey: "keep-me"
                somethingEntirelyNew:
                  nested: "keep-me-too"
                """));
        assertTrue(out.contains("keep-me"), out);
        assertTrue(out.contains("keep-me-too"), out);
    }

    /** A config already on the accounts shape keeps working alongside flat namespaces. */
    @Test
    void anAccountsFileLoadsBesideFlatNamespaces() throws Exception {
        var config = parse("""
                claude:
                  accounts:
                    work:
                      type: api-key
                      apiKey: "sk-ant-api03-work"
                  default: work
                github:
                  token: "ghp_flat"
                bob:
                  apiKey: "bob-flat"
                """);
        assertEquals("sk-ant-api03-work", config.getClaude().getApiKey());
        assertEquals("ghp_flat", AccountResolver.value(config, "github", "", "token"));
        assertEquals("bob-flat", config.getBob().getApiKey());
    }
}
