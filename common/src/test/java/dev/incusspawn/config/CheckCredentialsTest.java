package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The build/branch pre-flight credential check answers for the account a template pins, not
 * just the namespace default (#793).
 */
class CheckCredentialsTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static String check(String configYaml, String templateYaml) throws Exception {
        var config = YAML.readValue(configYaml, SpawnConfig.class);
        return SpawnConfig.checkCredentials(config, ImageDef.parseYaml(templateYaml), Map.of(), n -> false);
    }

    private static String piTemplate(String provider, String accounts) {
        return """
                name: tpl-pi
                tools:
                  - pi: {provider: %s}
                %s""".formatted(provider, accounts);
    }

    /** OpenAI accounts 'personal' (the default) and 'work', with a key only in {@code keyed}. */
    private static String openaiKeyOnlyIn(String keyed) {
        var other = keyed.equals("work") ? "personal" : "work";
        return """
                openai:
                  accounts:
                    %s: { apiKey: "sk-%s" }
                    %s: {}
                  default: personal
                """.formatted(keyed, keyed, other);
    }

    @Test
    void piOpenaiPinnedToConfiguredAccountIsNotMissing() throws Exception {
        assertEquals("", check(openaiKeyOnlyIn("work"), piTemplate("openai", "accounts:\n  openai: work\n")));
    }

    @Test
    void piOpenaiPinnedToAccountWithoutKeyIsMissing() throws Exception {
        var result = check(openaiKeyOnlyIn("personal"), piTemplate("openai", "accounts:\n  openai: work\n"));
        assertTrue(result.contains("OpenAI API key"), result);
    }

    @Test
    void piOpenaiUnpinnedUsesDefault() throws Exception {
        assertEquals("", check(openaiKeyOnlyIn("personal"), piTemplate("openai", "")));
    }

    private static final String CLAUDE_API_KEY_AND_VERTEX = """
            claude:
              accounts:
                personal: { type: api-key, apiKey: "sk-ant-x" }
                work: { type: vertex, cloudMlRegion: us-east5, vertexProjectId: proj }
              default: personal
            """;

    @Test
    void piVertexPinnedToVertexAccountIsNotMissing() throws Exception {
        assertEquals("", check(CLAUDE_API_KEY_AND_VERTEX, piTemplate("vertex", "accounts:\n  claude: work\n")));
    }

    @Test
    void piVertexUnpinnedWithNonVertexDefaultIsMissing() throws Exception {
        var result = check(CLAUDE_API_KEY_AND_VERTEX, piTemplate("vertex", ""));
        assertTrue(result.contains("Vertex AI configuration"), result);
    }

    @Test
    void claudePinnedToConfiguredAccountIsNotMissing() throws Exception {
        assertEquals("", check(CLAUDE_API_KEY_AND_VERTEX, """
                name: tpl-claude
                tools: [claude]
                accounts:
                  claude: work
                """));
    }

    @Test
    void claudePinnedToUnknownAccountReportsThePinNotMissingCredentials() throws Exception {
        var result = check(CLAUDE_API_KEY_AND_VERTEX, """
                name: tpl-claude
                tools: [claude]
                accounts:
                  claude: nope
                """);
        assertTrue(result.contains("nope"), result);
        assertFalse(result.startsWith("Missing credentials"), result);
    }

    @Test
    void claudeWithNoAccountIsMissing() throws Exception {
        var result = check("{}", "name: tpl-claude\ntools: [claude]\n");
        assertTrue(result.contains("Anthropic API key"), result);
    }
}
