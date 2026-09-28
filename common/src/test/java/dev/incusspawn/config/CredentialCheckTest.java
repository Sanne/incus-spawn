package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.tool.ToolSetup;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pre-flight credential check answers for the accounts the instance will actually use --
 * whatever pinned them -- and for every credential its tools declare (#793).
 */
class CredentialCheckTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Built-in tools only, so no tool YAML on the developer's machine can leak in. */
    private static Map<String, ToolSetup> tools(ToolSetup... extra) {
        var map = new LinkedHashMap<String, ToolSetup>();
        RuntimeConstants.CDI_TOOLS.forEach(t -> map.put(t.name(), t));
        for (var t : extra) map.put(t.name(), t);
        return map;
    }

    private static String check(String configYaml, String templateYaml, Map<String, String> selection,
                                ToolSetup... extraTools) throws Exception {
        var config = YAML.readValue(configYaml, SpawnConfig.class);
        var all = tools(extraTools);
        return CredentialCheck.check(config, ImageDef.parseYaml(templateYaml), Map.of(), selection, all, all);
    }

    private static String check(String configYaml, String templateYaml) throws Exception {
        return check(configYaml, templateYaml, Map.of());
    }

    private static String template(String tools) {
        return "name: tpl\ntools: " + tools + "\n";
    }

    private static String piTemplate(String provider) {
        return "name: tpl-pi\ntools:\n  - pi: {provider: " + provider + "}\n";
    }

    /** {@code <ns>} accounts 'personal' (the default) and 'work', with {@code key} only in {@code keyed}. */
    private static String keyOnlyIn(String ns, String key, String keyed) {
        var other = keyed.equals("work") ? "personal" : "work";
        return """
                %s:
                  accounts:
                    %s: { %s: "secret-%s" }
                    %s: {}
                  default: personal
                """.formatted(ns, keyed, key, keyed, other);
    }

    // --- pi + OpenAI: the case #793 reported

    @Test
    void piOpenaiPinnedToConfiguredAccountIsNotMissing() throws Exception {
        assertEquals("", check(keyOnlyIn("openai", "apiKey", "work"), piTemplate("openai"),
                Map.of("openai", "work")));
    }

    @Test
    void piOpenaiPinnedToAccountWithoutKeyIsMissing() throws Exception {
        var result = check(keyOnlyIn("openai", "apiKey", "personal"), piTemplate("openai"),
                Map.of("openai", "work"));
        assertTrue(result.contains("OpenAI API key"), result);
    }

    @Test
    void piOpenaiUnpinnedUsesDefault() throws Exception {
        assertEquals("", check(keyOnlyIn("openai", "apiKey", "personal"), piTemplate("openai")));
    }

    // --- Declared secrets, account-aware

    @Test
    void codexGhAndBobAnswerForThePinnedAccount() throws Exception {
        var config = keyOnlyIn("openai", "apiKey", "personal")
                + keyOnlyIn("github", "token", "personal")
                + keyOnlyIn("bob", "apiKey", "personal");
        var pinned = Map.of("openai", "work", "github", "work", "bob", "work");
        var result = check(config, template("[codex, gh, bob]"), pinned);
        assertTrue(result.contains("OpenAI API key"), result);
        assertTrue(result.contains("GitHub personal access token"), result);
        assertTrue(result.contains("IBM Bob API key"), result);
        assertEquals("", check(config, template("[codex, gh, bob]")));
    }

    @Test
    void copilotNeedsTheGithubTokenItBorrows() throws Exception {
        var result = check("{}", template("[copilot]"));
        assertTrue(result.contains("GitHub"), result);
        assertEquals("", check("github: { token: ghp_x }", template("[copilot]")));
    }

    @Test
    void aSharedSecretIsReportedOnce() throws Exception {
        var result = check("{}", template("[gh, copilot]"));
        assertEquals(1, result.split("GitHub", -1).length - 1, result);
    }

    @Test
    void aToolsRequirementsAreCheckedToo() throws Exception {
        var wrapper = new FakeTool("my-agent", "codex");
        var result = check("{}", template("[my-agent]"), Map.of(), wrapper);
        assertTrue(result.contains("OpenAI API key"), result);
    }

    @Test
    void theWholeTemplateChainIsChecked() throws Exception {
        var config = YAML.readValue("{}", SpawnConfig.class);
        var parent = ImageDef.parseYaml("name: tpl-base\ntools: [gh]\n");
        var child = ImageDef.parseYaml("name: tpl-leaf\nparent: tpl-base\ntools: [podman]\n");
        var all = tools();
        var result = CredentialCheck.check(config, child, Map.of("tpl-base", parent), Map.of(), all, all);
        assertTrue(result.contains("GitHub"), result);
    }

    // --- Claude, and pi on Claude's credential

    private static final String CLAUDE_API_KEY_AND_VERTEX = """
            claude:
              accounts:
                personal: { type: api-key, apiKey: "sk-ant-x" }
                work: { type: vertex, cloudMlRegion: us-east5, vertexProjectId: proj }
              default: personal
            """;

    @Test
    void piVertexPinnedToVertexAccountIsNotMissing() throws Exception {
        assertEquals("", check(CLAUDE_API_KEY_AND_VERTEX, piTemplate("vertex"), Map.of("claude", "work")));
    }

    @Test
    void piVertexUnpinnedWithNonVertexDefaultIsMissing() throws Exception {
        var result = check(CLAUDE_API_KEY_AND_VERTEX, piTemplate("vertex"));
        assertTrue(result.contains("Vertex AI configuration"), result);
    }

    @Test
    void piDefaultProviderNeedsClaude() throws Exception {
        var result = check("{}", template("[pi]"));
        assertTrue(result.contains("Anthropic API key"), result);
    }

    @Test
    void piProviderIsxDoesNotManageNeedsNothingFromIt() throws Exception {
        assertEquals("", check("{}", piTemplate("openrouter")));
    }

    @Test
    void claudeWithNoAccountIsMissing() throws Exception {
        var result = check("{}", template("[claude]"));
        assertTrue(result.contains("Anthropic API key"), result);
    }

    @Test
    void anIncompleteLegacyVertexSetupIsMissing() throws Exception {
        var legacy = "claude: { useVertex: true }";
        assertTrue(check(legacy, template("[claude]")).contains("Anthropic API key"));
        assertTrue(check(legacy, piTemplate("vertex")).contains("Vertex AI configuration"));
    }

    @Test
    void aStaleSelectionIsReportedNotThrown() throws Exception {
        var result = check(CLAUDE_API_KEY_AND_VERTEX, template("[claude]"), Map.of("claude", "nope"));
        assertTrue(result.contains("nope"), result);
    }

    private record FakeTool(String name, String requirement) implements ToolSetup {
        @Override public List<String> requires() { return List.of(requirement); }
        @Override public void install(dev.incusspawn.incus.Container c, Map<String, String> params) { }
    }

    @Test
    void piWithAnEmptyProviderTakesItsDefault() throws Exception {
        // As the build resolves it: a null provider is the default, anthropic, not a crash.
        var result = check("{}", "name: tpl-pi\ntools:\n  - pi: {provider: ~}\n");
        assertTrue(result.contains("Anthropic API key"), result);
    }
}
