package dev.incusspawn.config;

import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.proxy.InheritedProof;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pre-flight credential check answers for the accounts the instance will actually use --
 * whatever pinned them -- and for every credential its tools declare (#793).
 */
class CredentialCheckTest {


    /** Built-in tools only, so no tool YAML on the developer's machine can leak in. */
    private static Map<String, ToolSetup> tools(ToolSetup... extra) {
        var map = new LinkedHashMap<String, ToolSetup>();
        RuntimeConstants.CDI_TOOLS.forEach(t -> map.put(t.name(), t));
        for (var t : extra) map.put(t.name(), t);
        return map;
    }

    private static String check(String configYaml, String templateYaml, Map<String, String> selection,
                                ToolSetup... extraTools) throws Exception {
        var config = Jjq.spawnConfig(configYaml);
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
        var config = Jjq.spawnConfig("{}");
        var parent = ImageDef.parseYaml("name: tpl-base\ntools: [gh]\n");
        var child = ImageDef.parseYaml("name: tpl-leaf\nparent: tpl-base\ntools: [podman]\n");
        var all = tools();
        var result = CredentialCheck.check(config, child, Map.of("tpl-base", parent), Map.of(), all, all);
        assertTrue(result.contains("GitHub"), result);
    }

    @Test
    void eachMissingCredentialNamesTheTemplatesThatNeedIt() throws Exception {
        var config = Jjq.spawnConfig("{}");
        var defs = new LinkedHashMap<String, ImageDef>();
        for (var yaml : List.of("name: tpl-base\n", "name: tpl-dev\nparent: tpl-base\ntools: [gh]\n",
                "name: tpl-agent\nparent: tpl-dev\ntools: [codex]\n",
                "name: tpl-typo\nparent: tpl-dev\naccounts: {github: nope}\n")) {
            var def = ImageDef.parseYaml(yaml);
            defs.put(def.getName(), def);
        }
        var all = tools();
        var missing = CredentialCheck.missingByCredential(config, defs.keySet(), defs, all, all);
        // tpl-typo's unknown account is the build's to refuse, not a missing credential
        assertEquals(Map.of(
                new CredentialCheck.Missing("GitHub personal access token", "isx account add github"),
                List.of("tpl-dev", "tpl-agent"),
                new CredentialCheck.Missing("OpenAI API key", "isx account add openai"),
                List.of("tpl-agent")), missing);
    }

    // --- The remedy names the one credential's command, not the whole of 'isx init' (#1469)

    @Test
    void theRefusalNamesTheCommandThatAddsTheMissingCredential() throws Exception {
        assertEquals("Missing credentials: GitHub personal access token."
                + " Run 'isx account add github' to configure it.", check("{}", template("[gh]")));
    }

    /** The refusal says why the key that was written is not the one in use (#1733). */
    @Test
    void theRefusalNamesAKeyIsxDoesNotRead() throws Exception {
        assertEquals("Missing credentials: OpenAI API key. Run 'isx account add openai' to configure it."
                + " config.yaml has openai.token, which isx does not read;"
                + " it reads openai.apiKey or the same under openai.accounts.<name>.",
                check("openai:\n  token: sk-handwritten\n", template("[codex]")));
    }

    /**
     * The caller's tools do not decide what is read: a project-local one in them, which the
     * proxy never serves, does not silence the note by declaring the stray key or shadowing the
     * tool that owns the namespace (#1733).
     */
    @Test
    void aProjectToolAmongTheCallersDoesNotHideTheNote() throws Exception {
        var token = new dev.incusspawn.tool.ToolDef.ConfigEntry();
        token.setConfigPath("token");
        token.setSecret(true);
        var proxy = new dev.incusspawn.tool.ToolDef.ProxyDef();
        proxy.setConfigNamespace("openai");
        proxy.setConfiguration(Map.of("token", token));
        var def = new dev.incusspawn.tool.ToolDef();
        def.setName("sneaky");
        def.setProxy(proxy);
        var all = tools(new dev.incusspawn.tool.YamlToolSetup(def));
        var served = all;

        var result = CredentialCheck.check(Jjq.spawnConfig("openai:\n  token: sk-handwritten\n"),
                ImageDef.parseYaml(template("[codex]")), Map.of(), Map.of(), all, served);
        assertTrue(result.contains("config.yaml has openai.token, which isx does not read"), result);
    }

    @Test
    void eachMissingCredentialGetsItsOwnCommand() throws Exception {
        var result = check("{}", template("[claude, gh]"));
        assertTrue(result.endsWith(" Run 'isx account add claude' and 'isx account add github'"
                + " to configure them."), result);
    }

    /** Copilot borrows GitHub's token, so the command is GitHub's: Copilot has no step of its own. */
    @Test
    void aBorrowedCredentialNamesTheNamespaceThatOwnsIt() throws Exception {
        var result = check("{}", template("[copilot]"));
        assertTrue(result.endsWith(" Run 'isx account add github' to configure it."), result);
    }

    @Test
    void piNamesTheCredentialItsProviderSpends() throws Exception {
        assertTrue(check("{}", template("[pi]")).contains(" Run 'isx account add claude' to configure it. "));
        assertTrue(check("{}", piTemplate("openai")).endsWith("'isx account add openai' to configure it."));
    }

    @Test
    void aYamlToolsCredentialIsAddedByItsNamespace() throws Exception {
        var result = check("{}", template("[three-ways]"), Map.of(), yamlTool(THREE_WAYS));
        assertTrue(result.endsWith(" Run 'isx account add threeWays' to configure them."), result);
    }

    /** A secret outside any namespace, declared by a tool with a step of its own: that step sets it. */
    @Test
    void aCredentialOutsideANamespaceNamesTheToolThatDeclaresIt() throws Exception {
        var result = check("{}", template("[borrower]"), Map.of(), yamlTool(BORROWER));
        assertEquals("Missing credentials: Borrowed key. Run 'isx account add borrower' to configure it.", result);
    }

    private static final String BORROWER = """
            name: borrower
            proxy:
              configuration:
                key: { config-path: elsewhere.key, description: "Borrowed key", secret: true }
              auth:
                - { domains: [e.example], type: bearer, token: "${key}" }
            """;

    /** No step owns the secret, and the tool declaring it has none either: init's menu is the way. */
    @Test
    void aCredentialNoStepOwnsFallsBackToInit() throws Exception {
        var tool = new NoStep(yamlTool(BORROWER));
        var result = check("{}", template("[borrower]"), Map.of(), tool);
        assertEquals("Missing credentials: Borrowed key. Run 'isx init' to configure it.", result);
    }

    /** {@code delegate}'s proxy, but borrowed, as Copilot's is: no step of its own. */
    private record NoStep(ToolSetup delegate) implements ToolSetup {
        @Override public String name() { return delegate.name(); }
        @Override public dev.incusspawn.tool.ToolDef.ProxyDef proxy() { return delegate.proxy(); }
        @Override public boolean hasOwnCredentials() { return false; }
        @Override public void install(dev.incusspawn.incus.Container c, Map<String, String> params) { }
    }

    /**
     * A YAML tool named after gh's namespace must not take over gh's remedy: {@code isx account
     * add github} still runs gh's step, which is the one that sets the missing token.
     */
    @Test
    void aToolNamedAfterAnotherNamespaceDoesNotTakeItOver() throws Exception {
        var impostor = yamlTool("""
                name: github
                proxy:
                  config-namespace: ghe
                  configuration:
                    token: { config-path: token, description: "GHE token", secret: true }
                  auth:
                    - { domains: [ghe.example], type: bearer, token: "${token}" }
                """);
        var all = tools(impostor);
        assertEquals("gh", CredentialCheck.credentialTool("github", all));
        assertEquals("github", CredentialCheck.credentialTool("ghe", all));
        assertEquals("Missing credentials: GitHub personal access token."
                + " Run 'isx account add github' to configure it.", check("{}", template("[gh]"), Map.of(), impostor));
        assertEquals("isx account add ghe", CredentialCheck.stepCommand("github", all));
        assertEquals("isx account add github", CredentialCheck.stepCommand("gh", all));
        // Its own secret: its namespace, not its name, which is gh's
        assertEquals("Missing credentials: GHE token. Run 'isx account add ghe' to configure it.",
                check("{}", template("[github]"), Map.of(), impostor));
    }

    /**
     * A tool declaring a key in a namespace another tool's step owns: that step never asks for the
     * key, so the declaring tool's step is named.
     */
    @Test
    void aKeyTheNamespaceOwnersStepDoesNotAskForNamesTheDeclaringTool() throws Exception {
        var aider = yamlTool("""
                name: aider
                proxy:
                  config-namespace: openai
                  configuration:
                    org: { config-path: org, description: "OpenAI organization", secret: true }
                  auth:
                    - { domains: [org.example], type: header, name: X-Org, value: "${org}" }
                """);
        // The owner codex's key is served in the same namespace, so it is asked for too
        assertEquals("Missing credentials: OpenAI organization, OpenAI API key."
                        + " Run 'isx account add aider' and 'isx account add openai' to configure them.",
                check("{}", template("[aider]"), Map.of(), aider));
        // Its step's own skip message names the same command, and codex's names its namespace
        var all = tools(aider);
        assertEquals("isx account add aider", CredentialCheck.stepCommand("aider", all));
        assertEquals("isx account add openai", CredentialCheck.stepCommand("codex", all));
        // codex's own key still goes to the namespace codex owns
        assertTrue(check("{}", template("[codex]"), Map.of(), aider).endsWith("'isx account add openai' to configure it."));
    }

    @Test
    void theStepIsFoundByNamespaceOrByTool() {
        var all = tools();
        assertEquals("gh", CredentialCheck.credentialTool("github", all));
        assertEquals("gh", CredentialCheck.credentialTool("gh", all));
        assertEquals("claude", CredentialCheck.credentialTool("claude", all));
        assertEquals("codex", CredentialCheck.credentialTool("openai", all));
        // Copilot spends GitHub's token and has no step; pi and podman have no credential at all
        assertNull(CredentialCheck.credentialTool("copilot", all));
        assertNull(CredentialCheck.credentialTool("pi", all));
        assertNull(CredentialCheck.credentialTool("podman", all));
        assertNull(CredentialCheck.credentialTool("nope", all));
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

    // Branch checks the definitions a template was built from, so editing it alone changes nothing
    private static final String PI_NOTE_TAIL = " To use another provider, set it in the template's tools,"
            + " run 'isx build' on the template and branch from it:"
            + " '- pi: {provider: openai}' spends an OpenAI API key,"
            + " and a provider isx does not manage needs nothing from isx.";

    /** Pi spends Claude's account on its behalf: the message says so, and what else would do (#1750). */
    @Test
    void piOnClaudesAccountNamesPiItsProviderAndAnotherProvider() throws Exception {
        assertEquals("Missing credentials: Anthropic API key, OAuth token, or Vertex AI."
                + " Run 'isx account add claude' to configure it."
                + " Pi needs a Claude account for its provider 'anthropic'." + PI_NOTE_TAIL,
                check("{}", template("[pi]")));
        assertEquals("Missing credentials: Vertex AI configuration."
                + " Run 'isx account add claude' to configure it."
                + " Pi needs a Vertex AI Claude account for its provider 'google-vertex'." + PI_NOTE_TAIL,
                check("{}", piTemplate("google-vertex")));
    }

    /** Pi's google provider is the Gemini API, which spends the gemini namespace's key. */
    @Test
    void piOnTheGeminiApiNeedsAGeminiKey() throws Exception {
        assertEquals("Missing credentials: Gemini API key. Run 'isx account add gemini' to configure it.",
                check("{}", piTemplate("google")));
    }

    /** Claude Code needs the account whatever pi's provider, so pi's note is left out. */
    @Test
    void piBesideClaudeIsNotOfferedAnotherProvider() throws Exception {
        assertEquals("Missing credentials: Anthropic API key, OAuth token, or Vertex AI."
                + " Run 'isx account add claude' to configure it.", check("{}", template("[pi, claude]")));
    }

    /** Beside Claude Code, pi's stricter need still shows, so one fix does not reveal another. */
    @Test
    void piOnVertexBesideClaudeKeepsItsOwnNeed() throws Exception {
        assertEquals("Missing credentials: Anthropic API key, OAuth token, or Vertex AI, Vertex AI configuration."
                + " Run 'isx account add claude' to configure them.",
                check("{}", "name: tpl\ntools:\n  - claude\n  - pi: {provider: vertex}\n"));
    }

    /** The note travels with what the build and init report. */
    @Test
    void missingByCredentialCarriesPisNote() throws Exception {
        var all = tools();
        var template = ImageDef.parseYaml(template("[pi]"));
        var missing = CredentialCheck.missingByCredential(Jjq.spawnConfig("{}"), List.of("tpl"),
                Map.of("tpl", template), all, all);
        assertEquals(1, missing.size(), missing.toString());
        assertTrue(missing.keySet().iterator().next().notes().getFirst().startsWith("Pi needs a Claude account"), missing.toString());
    }

    /** A blank note says nothing, so it cannot stop another tool's note being suppressed. */
    @Test
    void aBlankNoteIsNoNote() {
        assertEquals(List.of(), new CredentialCheck.Missing("key", "isx init", List.of("", " ")).notes());
    }

    /** One credential is one entry across templates: pi's note does not split it from Claude Code's. */
    @Test
    void piAndClaudeTemplatesShareOneEntryForTheAccount() throws Exception {
        var all = tools();
        var defs = Map.of("tpl-pi", ImageDef.parseYaml("name: tpl-pi\ntools: [pi]\n"),
                "tpl-claude", ImageDef.parseYaml("name: tpl-claude\ntools: [claude]\n"));
        var missing = CredentialCheck.missingByCredential(Jjq.spawnConfig("{}"), List.of("tpl-pi", "tpl-claude"),
                defs, all, all);
        assertEquals(1, missing.size(), missing.toString());
        var entry = missing.entrySet().iterator().next();
        assertEquals(List.of("tpl-pi", "tpl-claude"), entry.getValue());
        assertEquals(2, entry.getKey().notes().size(), entry.getKey().toString());
    }

    /** Notes that differ by provider are each kept, on the one entry, and what they share is said once. */
    @Test
    void piTemplatesOnDifferentProvidersShareOneEntry() throws Exception {
        var all = tools();
        var defs = Map.of("tpl-vertex", ImageDef.parseYaml(piTemplate("vertex").replace("tpl-pi", "tpl-vertex")),
                "tpl-google", ImageDef.parseYaml(piTemplate("google-vertex").replace("tpl-pi", "tpl-google")));
        var missing = CredentialCheck.missingByCredential(Jjq.spawnConfig("{}"), List.of("tpl-vertex", "tpl-google"),
                defs, all, all);
        assertEquals(1, missing.size(), missing.toString());
        // Each provider's own sentence, and the advice they share once
        var notes = missing.keySet().iterator().next().notes();
        assertEquals(3, notes.size(), notes.toString());
        assertTrue(notes.stream().anyMatch(n -> n.endsWith("'vertex'."))
                && notes.stream().anyMatch(n -> n.endsWith("'google-vertex'.")), notes.toString());
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

    // --- Only what the proxy needs: an auth entry is served once the keys it references resolve

    private static dev.incusspawn.tool.ToolSetup yamlTool(String yaml) throws Exception {
        return new dev.incusspawn.tool.YamlToolSetup(dev.incusspawn.tool.ToolDef.loadFromStream(
                new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    /** Three auth entries on three domains, each with its own secret, as the CI fixture has. */
    private static final String THREE_WAYS = """
            name: three-ways
            proxy:
              config-namespace: threeWays
              configuration:
                token: { config-path: token, description: "Three-ways token", secret: true }
                password: { config-path: password, description: "Three-ways password", secret: true }
                api-key: { config-path: apiKey, description: "Three-ways API key", secret: true }
              auth:
                - { domains: [a.example], type: bearer, token: "${token}" }
                - { domains: [b.example], type: basic, username: "u", password: "${password}" }
                - { domains: [c.example], type: header, name: X-Key, value: "Key ${api-key}" }
            """;

    @Test
    void oneServableAuthEntryIsEnough() throws Exception {
        // The proxy injects the bearer token; the other two secrets serve other domains.
        assertEquals("", check("threeWays: { token: t }", template("[three-ways]"), Map.of(),
                yamlTool(THREE_WAYS)));
    }

    @Test
    void noServableAuthEntryNamesWhatWouldServeOne() throws Exception {
        var result = check("{}", template("[three-ways]"), Map.of(), yamlTool(THREE_WAYS));
        assertTrue(result.contains("Three-ways token"), result);
        assertTrue(result.contains("Three-ways password"), result);
    }

    @Test
    void aSecretNobodyCouldSetIsNotAskedFor() throws Exception {
        // No config-path and no value: the validator only warns, and no config.yaml could hold it.
        var tool = yamlTool("""
                name: unsettable
                proxy:
                  configuration:
                    key: { description: "Unsettable key", secret: true }
                  auth:
                    - { domains: [d.example], type: bearer, token: "${key}" }
                """);
        assertEquals("", check("{}", template("[unsettable]"), Map.of(), tool));
    }

    // --- inherit (#1110): the parent instance's credential, wherever this isx runs in one

    @Test
    void anInheritedCredentialNeedsNoneHereWhenNested(@TempDir Path dir) throws Throwable {
        withSecretFile(dir, true, () -> {
            assertEquals("", check("{}", template("[gh]"), Map.of("github", "inherit")));
            assertEquals("", check("github:\n  token: \"ghp_flat\"\n  default: inherit\n", template("[gh]")));
            assertEquals("", check("{}", template("[claude]"), Map.of("claude", "inherit")));
            assertEquals("", check("claude:\n  default: inherit\n", template("[claude]")));
        });
    }

    @Test
    void anInheritedCredentialOutsideAnIsxInstanceIsMissing(@TempDir Path dir) throws Throwable {
        withSecretFile(dir, false, () -> {
            for (var result : List.of(check("{}", template("[gh]"), Map.of("github", "inherit")),
                    check("claude:\n  default: inherit\n", template("[claude]")))) {
                assertTrue(result.contains("not running in an isx instance"), result);
            }
        });
    }

    private static void withSecretFile(Path dir, boolean nested, Executable check) throws Throwable {
        var property = InheritedProof.SECRET_FILE_PROPERTY;
        var original = System.getProperty(property);
        var file = dir.resolve("instance-secret");
        if (nested) Files.writeString(file, "11".repeat(32) + "\n");
        System.setProperty(property, file.toString());
        try {
            check.execute();
        } finally {
            if (original == null) System.clearProperty(property);
            else System.setProperty(property, original);
        }
    }

    @Test
    void piOnVertexInheritingClaudeNeedsNoneHereWhenNested(@TempDir Path dir) throws Throwable {
        withSecretFile(dir, true, () ->
                assertEquals("", check("{}", piTemplate("vertex"), Map.of("claude", "inherit"))));
    }
}
