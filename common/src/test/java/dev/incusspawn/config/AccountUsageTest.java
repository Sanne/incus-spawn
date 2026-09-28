package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.GhSetup;
import dev.incusspawn.tool.ToolSetup;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What {@code isx account show} reports: the account served per namespace, and why. */
class AccountUsageTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final String CONFIG = """
            claude:
              accounts:
                personal:
                  type: oauth
                  oauthToken: "sk-ant-oat01-x"
                acme:
                  type: vertex
                  cloudMlRegion: europe-west1
                  vertexProjectId: acme-prod
              default: personal
            github:
              accounts:
                me:
                  token: "ghp_me"
                bot:
                  token: "ghp_bot"
                  email: "bot@acme.example"
              default: me
            """;

    private static Map<String, ToolSetup> setups() {
        var setups = new LinkedHashMap<String, ToolSetup>();
        setups.put("claude", new ClaudeSetup());
        setups.put("github", new GhSetup());
        return setups;
    }

    private static List<AccountUsage.Use> uses(Map<String, String> pins, Map<String, String> template)
            throws Exception {
        return uses(pins, Map.of(), template);
    }

    private static List<AccountUsage.Use> uses(Map<String, String> pins, Map<String, AccountOrigin> origins,
                                               Map<String, String> template) throws Exception {
        return AccountUsage.of(YAML.readValue(CONFIG, SpawnConfig.class), setups(), pins, origins, template);
    }

    private static AccountUsage.Use find(List<AccountUsage.Use> uses, String namespace) {
        return uses.stream().filter(u -> u.namespace().equals(namespace)).findFirst().orElseThrow();
    }

    /** Unpinned namespaces are reported too, as the account the default resolves to. */
    @Test
    void anUnpinnedNamespaceShowsTheDefaultItFollows() throws Exception {
        var claude = find(uses(Map.of(), Map.of()), "claude");
        assertEquals("personal", claude.account());
        assertFalse(claude.pinned());
        assertEquals("Claude Pro/Max OAuth token", claude.description());
        assertFalse(claude.differsFromTemplate());
    }

    /** Who chose a pin is what was recorded, never inferred from whether it matches the template. */
    @Test
    void anExplicitChoiceOfTheTemplatesAccountIsStillExplicit() throws Exception {
        var claude = find(uses(Map.of("claude", "acme"), Map.of("claude", AccountOrigin.EXPLICIT),
                Map.of("claude", "acme")), "claude");
        assertEquals(AccountOrigin.EXPLICIT, claude.origin());
        assertTrue(claude.description().startsWith("Google Cloud Vertex AI"));
        assertTrue(AccountUsage.explainSource(claude, "tpl-acme", false).startsWith("Pinned by an explicit choice"));
    }

    @Test
    void aTemplatePinSaysWhichTemplateChoseIt() throws Exception {
        var claude = find(uses(Map.of("claude", "acme"), Map.of("claude", AccountOrigin.template("tpl-acme")),
                Map.of("claude", "acme")), "claude");
        assertEquals("Pinned by template tpl-acme's accounts: setting, copied onto this instance when it"
                + " was branched.", AccountUsage.explainSource(claude, "tpl-acme", false));
        assertEquals("Pinned by this template's own accounts: setting, when it was built.",
                AccountUsage.explainSource(claude, "tpl-acme", true));
    }

    /** Pinned before origins were recorded: say so, rather than guess who chose it. */
    @Test
    void anUnrecordedOriginIsNotGuessed() throws Exception {
        var claude = find(uses(Map.of("claude", "acme"), Map.of("claude", "acme")), "claude");
        assertEquals(AccountOrigin.UNKNOWN, claude.origin());
        assertEquals("Pinned before isx recorded who chose it; it is the account template tpl-acme chooses.",
                AccountUsage.explainSource(claude, "tpl-acme", false));
    }

    /** Edited since branching, or overridden: either way the instance does not follow it. */
    @Test
    void aPinDifferingFromTheTemplateIsFlagged() throws Exception {
        var github = find(uses(Map.of("github", "me"), Map.of("github", "bot")), "github");
        assertTrue(github.pinned());
        assertEquals("bot", github.templateAccount());
        assertTrue(github.differsFromTemplate());
    }

    /** A template pin added after branching does not reach the unpinned instance -- say so. */
    @Test
    void anUnpinnedInstanceOfATemplateThatNowPinsIsFlagged() throws Exception {
        var github = find(uses(Map.of(), Map.of("github", "bot")), "github");
        assertFalse(github.pinned());
        assertEquals("me", github.account());
        assertTrue(github.differsFromTemplate());
    }

    /** Fail closed, and visibly: a pin to a removed account is shown with why it fails. */
    @Test
    void aDanglingPinIsReportedWithItsProblem() throws Exception {
        var github = find(uses(Map.of("github", "gone"), Map.of()), "github");
        assertEquals("gone", github.account());
        assertTrue(github.pinned());
        assertTrue(github.problem().contains("'gone'"), github.problem());
        assertEquals("", github.description());
    }

    @Test
    void aTemplateNamingAMissingAccountIsReported() throws Exception {
        var github = find(uses(Map.of("github", "me"), Map.of("github", "renamed-away")), "github");
        assertTrue(github.templateProblem().contains("'renamed-away'"), github.templateProblem());
        assertEquals("", find(uses(Map.of(), Map.of("github", "bot")), "github").templateProblem());
    }

    @Test
    void theGitHubDescriptionNamesTheCommitEmailNotTheToken() throws Exception {
        var github = find(uses(Map.of("github", "bot"), Map.of()), "github");
        assertEquals("commits as bot@acme.example", github.description());
        assertFalse(github.description().contains("ghp_"));
    }

    @Test
    void aNamespaceWithNothingConfiguredIsLeftOut() throws Exception {
        var setups = setups();
        var config = YAML.readValue("claude:\n  apiKey: \"sk-ant-api-x\"\n", SpawnConfig.class);
        var uses = AccountUsage.of(config, setups, Map.of(), Map.of(), Map.of());
        assertEquals(List.of("claude"), uses.stream().map(AccountUsage.Use::namespace).toList());
    }

    @Test
    void pinnedToListsTheInstancesUsingOneAccount() {
        var pins = new LinkedHashMap<String, Map<String, String>>();
        pins.put("a", Map.of("github", "bot"));
        pins.put("b", Map.of("github", "me", "claude", "bot"));
        pins.put("c", Map.of("github", "bot"));
        assertEquals(List.of("a", "c"), AccountUsage.pinnedTo(pins, "github", "bot"));
    }

    // ── which credentials a template's tools use ─────────────────────────────

    @Test
    void aTemplatesCredentialsComeFromItsToolsDownTheChain() throws Exception {
        var base = ImageDef.parseYaml("name: tpl-dev\ntools:\n  - gh\n");
        var child = ImageDef.parseYaml("name: tpl-ai\nparent: tpl-dev\ntools:\n  - pi\n");
        Map<String, ToolSetup> tools = Map.of("gh", new GhSetup(), "pi", new dev.incusspawn.tool.PiSetup());
        var defs = Map.of("tpl-dev", base, "tpl-ai", child);
        assertEquals(java.util.Set.of("github", "claude", "openai"),
                AccountSelection.templateNamespaces(child, defs, tools::get));
    }

    @Test
    void aBorrowedCredentialCountsForTheToolThatBorrowsIt() {
        assertEquals(java.util.Set.of("github"), new dev.incusspawn.tool.CopilotSetup().credentialNamespaces());
        assertEquals(java.util.Set.of("claude"), new ClaudeSetup().credentialNamespaces());
    }
}
